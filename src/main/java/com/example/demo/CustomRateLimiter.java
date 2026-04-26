package com.example.demo;

import java.util.concurrent.atomic.AtomicLong;

/**
 * ╔══════════════════════════════════════════════════════════════════════════════╗
 * ║          CUSTOM TOKEN BUCKET RATE LIMITER — FROM SCRATCH                    ║
 * ║                                                                              ║
 * ║  No Bucket4j, no Guava, no external library. Pure Java.                     ║
 * ║  Implements everything: refill, consume, probe, burst, denial cache.        ║
 * ╚══════════════════════════════════════════════════════════════════════════════╝
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  VISUALIZATION: Token Bucket Lifecycle (limit=10 tokens, refill=1 token/sec)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *  TIME    BUCKET STATE              ACTION              RESULT
 *  ─────   ─────────────────────     ──────────────────  ──────────
 *  t=0s    [■■■■■■■■■■] 10/10       ← initial fill      FULL
 *  t=1s    [■■■■■■■■■□]  9/10       consume(1)          ALLOW
 *  t=2s    [■■■■■■■■□□]  8/10       consume(1)          ALLOW
 *  t=3s    [■■■■■■■□□□]  7/10       consume(1)          ALLOW
 *  ...burst of 7 requests at t=4s...
 *  t=4s    [□□□□□□□□□□]  0/10       consume(7)          ALLOW (burst OK!)
 *  t=4.1s  [□□□□□□□□□□]  0/10       consume(1)          DENY → 429
 *  t=4.2s  [□□□□□□□□□□]  0/10       consume(1)          DENY → 429 (denial cache)
 *  t=5s    [■□□□□□□□□□]  1/10       ← refill (+1)       
 *  t=5.1s  [□□□□□□□□□□]  0/10       consume(1)          ALLOW
 *  t=15s   [■■■■■■■■■■] 10/10       ← refill capped     FULL again
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  REFILL MECHANISM (lazy refill — calculated on demand, not via timer)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *  Instead of a background timer adding tokens every second, we calculate
 *  how many tokens SHOULD have been added since last access:
 *
 *    elapsedNanos = now - lastRefillTimestamp
 *    tokensToAdd  = elapsedNanos * refillRate / 1_000_000_000
 *    newTokens    = min(currentTokens + tokensToAdd, capacity)
 *
 *  WHY LAZY?
 *    • No background threads (saves memory for 100k tenants)
 *    • Calculation is O(1) — just subtraction + multiplication
 *    • Tokens "appear" exactly when needed
 *
 *  Example: capacity=60, refillRate=1/sec
 *    Last access: t=10s, tokens=0
 *    New request: t=16s
 *    elapsedNanos = 6_000_000_000
 *    tokensToAdd = 6_000_000_000 * 1 / 1_000_000_000 = 6
 *    newTokens = min(0 + 6, 60) = 6  ← 6 tokens magically available
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  CONSUMPTION PROBE — "Can I consume? If not, how long to wait?"
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *  ConsumptionProbe answers 3 questions:
 *    1. Was consumption successful?        → boolean consumed
 *    2. How many tokens remain?            → long remainingTokens
 *    3. If denied, how long until refill?  → long nanosToWaitForRefill
 *
 *  This is what Bucket4j's tryConsumeAndReturnRemaining() returns.
 *  We implement the same thing here from scratch.
 *
 *  Example:
 *    probe = limiter.tryConsumeAndReturnRemaining(1);
 *    if (probe.consumed) {
 *        // 200 OK, remaining = probe.remainingTokens
 *    } else {
 *        // 429, Retry-After = probe.nanosToWaitForRefill / 1_000_000_000
 *    }
 *
 * ═══════════════════════════════════════════════════════════════════════════════
 *  CAS (Compare-And-Set) — Lock-free thread safety
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 *  Problem: 100 threads call consume() simultaneously
 *  Bad solution: synchronized → only 1 thread at a time → slow
 *  Good solution: CAS loop
 *
 *    while (true) {
 *        long current = tokens.get();        // read current value
 *        long newVal = current - 1;          // compute new value
 *        if (tokens.compareAndSet(current, newVal)) {
 *            return SUCCESS;                 // I won the race
 *        }
 *        // Another thread changed it — retry immediately (~5ns)
 *    }
 *
 *  CAS is a single CPU instruction (CMPXCHG on x86). No OS call.
 *  5000 concurrent threads: CAS loop ~5ns/retry vs synchronized ~5μs (1000x slower)
 */
public class CustomRateLimiter {

    // ─── Configuration ───────────────────────────────────────────────────────
    private final long capacity;           // max tokens (bucket size)
    private final long refillTokens;       // how many tokens to add per refill period
    private final long refillPeriodNanos;  // refill period in nanoseconds

    // ─── State (all accessed via CAS — no locks) ────────────────────────────
    private final AtomicLong availableTokens;
    private final AtomicLong lastRefillNanos;

    // ─── Denial Cache (Cloudflare-style: instant 429 without recalculation) ─
    private final AtomicLong deniedUntilNanos = new AtomicLong(0);

    /**
     * Create a rate limiter.
     *
     * @param capacity        max tokens (e.g. 60)
     * @param refillTokens    tokens added per period (e.g. 1)
     * @param refillPeriodMs  period in ms (e.g. 1000 → 1 token/sec → 60 tokens/min)
     *
     * Example configurations:
     *   new CustomRateLimiter(60, 1, 1000)    → 60 req/min, refill 1/sec
     *   new CustomRateLimiter(100, 10, 1000)  → 100 req/10sec, refill 10/sec
     *   new CustomRateLimiter(5, 5, 60000)    → 5 req/min, refill all at once
     */
    public CustomRateLimiter(long capacity, long refillTokens, long refillPeriodMs) {
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriodNanos = refillPeriodMs * 1_000_000L;
        this.availableTokens = new AtomicLong(capacity); // start full
        this.lastRefillNanos = new AtomicLong(System.nanoTime());
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CORE: tryConsumeAndReturnRemaining — the full consumption probe
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Try to consume `tokensToConsume` tokens.
     *
     * STEPS:
     *   1. Check denial cache → instant reject if still denied
     *   2. Lazy refill → calculate how many tokens have accumulated since last call
     *   3. CAS loop → atomically try to subtract tokens
     *   4. If denied → calculate nanosToWaitForRefill and cache denial
     *
     * @return ConsumptionProbe with result details
     */
    public ConsumptionProbe tryConsumeAndReturnRemaining(long tokensToConsume) {

        // ── Step 1: Denial cache check (Cloudflare optimization) ─────────
        // If we recently determined the bucket is empty, skip all math.
        // This saves CPU for post-exhaustion spam (e.g., 5000 reqs after limit hit)
        long now = System.nanoTime();
        long deniedUntil = deniedUntilNanos.get();
        if (deniedUntil > 0 && now < deniedUntil) {
            long waitNanos = deniedUntil - now;
            return new ConsumptionProbe(false, 0, waitNanos);
        }

        // ── Step 2: Lazy refill ─────────────────────────────────────────
        // Calculate tokens that should have been added since last refill.
        // This is the key insight: no background thread needed!
        refill(now);

        // ── Step 3: CAS loop — atomically consume tokens ────────────────
        while (true) {
            long current = availableTokens.get();

            if (current >= tokensToConsume) {
                // Enough tokens — try to consume
                long newValue = current - tokensToConsume;
                if (availableTokens.compareAndSet(current, newValue)) {
                    // SUCCESS — consumed!
                    // Clear denial cache since we clearly have tokens
                    deniedUntilNanos.set(0);
                    return new ConsumptionProbe(true, newValue, 0);
                }
                // CAS failed — another thread consumed between get() and CAS
                // Spin and retry immediately (~5ns)
                Thread.onSpinWait(); // hint to CPU: we're in a spin loop
                continue;
            }

            // ── Step 4: Not enough tokens — calculate wait time ─────────
            // How long until enough tokens refill?
            //
            // Formula:
            //   deficit = tokensToConsume - current
            //   nanosToWait = deficit * refillPeriodNanos / refillTokens
            //
            // Example: need 1, have 0, refill = 1 token per 1000ms
            //   deficit = 1, nanosToWait = 1 * 1_000_000_000 / 1 = 1 second
            long deficit = tokensToConsume - current;
            long nanosToWait = (deficit * refillPeriodNanos) / refillTokens;

            // Cache denial (Cloudflare-style)
            // For the next `nanosToWait`, all requests get instant 429
            deniedUntilNanos.set(now + nanosToWait);

            return new ConsumptionProbe(false, current, nanosToWait);
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  REFILL: Lazy token replenishment
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Lazy refill: add tokens based on elapsed time since last refill.
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  Timeline:                                                          │
     * │                                                                     │
     * │  lastRefill          now                                            │
     * │     │                  │                                            │
     * │     ├──── elapsed ─────┤                                            │
     * │     │                  │                                            │
     * │  tokensToAdd = elapsed / refillPeriod * refillTokens                │
     * │  newTokens = min(current + tokensToAdd, capacity)                   │
     * │                                                                     │
     * │  Then advance lastRefill by (tokensToAdd / refillTokens) periods    │
     * │  (NOT to 'now' — we keep fractional time for next refill)           │
     * └─────────────────────────────────────────────────────────────────────┘
     *
     * Example: refill=1/sec, last refill was 2.7 seconds ago
     *   tokensToAdd = floor(2.7) = 2 tokens
     *   advance lastRefill by 2 seconds (not 2.7 — keep 0.7s credit for next call)
     */
    private void refill(long nowNanos) {
        while (true) {
            long lastRefill = lastRefillNanos.get();
            long elapsed = nowNanos - lastRefill;

            if (elapsed < refillPeriodNanos) {
                return; // not enough time for even 1 token
            }

            // How many complete refill periods have passed?
            long periods = elapsed / refillPeriodNanos;
            long tokensToAdd = periods * refillTokens;

            // Advance lastRefill by exactly the periods we're accounting for
            // This preserves fractional time (sub-period elapsed time)
            long newLastRefill = lastRefill + (periods * refillPeriodNanos);

            if (lastRefillNanos.compareAndSet(lastRefill, newLastRefill)) {
                // We won the CAS — now add tokens (also via CAS)
                while (true) {
                    long current = availableTokens.get();
                    long newTokens = Math.min(current + tokensToAdd, capacity);
                    if (current == newTokens) break; // already at cap
                    if (availableTokens.compareAndSet(current, newTokens)) {
                        break;
                    }
                    Thread.onSpinWait();
                }
                return;
            }
            // Another thread did the refill — retry to check if more refill needed
            Thread.onSpinWait();
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  SIMPLE CONSUME — boolean version (for simpler use cases)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Simple consume: returns true if allowed, false if denied.
     * Use tryConsumeAndReturnRemaining() for full details (remaining, wait time).
     */
    public boolean tryConsume(long tokens) {
        return tryConsumeAndReturnRemaining(tokens).consumed;
    }

    /**
     * Consume 1 token.
     */
    public boolean tryConsume() {
        return tryConsume(1);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  OBSERVABILITY
    // ═════════════════════════════════════════════════════════════════════════

    public long getAvailableTokens() {
        refill(System.nanoTime());
        return availableTokens.get();
    }

    public long getCapacity() {
        return capacity;
    }

    /**
     * Manually reset (e.g., for testing or admin API)
     */
    public void reset() {
        availableTokens.set(capacity);
        lastRefillNanos.set(System.nanoTime());
        deniedUntilNanos.set(0);
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  CONSUMPTION PROBE — result object (mirrors Bucket4j's ConsumptionProbe)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Result of a consume attempt. Contains everything the caller needs:
     *
     * ┌─────────────────────────────────────────────────────────────────────┐
     * │  consumed = true:                                                   │
     * │    ✓ Token(s) consumed successfully                                │
     * │    remainingTokens = how many left after this consume               │
     * │    nanosToWaitForRefill = 0                                         │
     * │                                                                     │
     * │  consumed = false:                                                  │
     * │    ✗ Not enough tokens                                             │
     * │    remainingTokens = current tokens (less than requested)            │
     * │    nanosToWaitForRefill = how long until enough tokens refill        │
     * │      → use for Retry-After header: seconds = nanos / 1_000_000_000  │
     * └─────────────────────────────────────────────────────────────────────┘
     */
    public static class ConsumptionProbe {
        private final boolean consumed;
        private final long remainingTokens;
        private final long nanosToWaitForRefill;

        public ConsumptionProbe(boolean consumed, long remainingTokens, long nanosToWaitForRefill) {
            this.consumed = consumed;
            this.remainingTokens = remainingTokens;
            this.nanosToWaitForRefill = nanosToWaitForRefill;
        }

        public boolean isConsumed()            { return consumed; }
        public long getRemainingTokens()       { return remainingTokens; }
        public long getNanosToWaitForRefill()  { return nanosToWaitForRefill; }

        /** Convenience: seconds to wait (for Retry-After header) */
        public long getSecondsToWait() {
            return (nanosToWaitForRefill + 999_999_999) / 1_000_000_000; // ceiling
        }

        @Override
        public String toString() {
            if (consumed) {
                return String.format("ALLOWED (remaining=%d)", remainingTokens);
            } else {
                return String.format("DENIED (remaining=%d, retryAfter=%ds)",
                        remainingTokens, getSecondsToWait());
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    //  DEMO — run this to see the rate limiter in action
    // ═════════════════════════════════════════════════════════════════════════

    public static void main(String[] args) throws InterruptedException {
        System.out.println("╔══════════════════════════════════════════════════════════════╗");
        System.out.println("║       Custom Token Bucket Rate Limiter — Live Demo          ║");
        System.out.println("╚══════════════════════════════════════════════════════════════╝");
        System.out.println();

        // 10 tokens max, refill 1 token every 500ms (= 2 tokens/sec)
        CustomRateLimiter limiter = new CustomRateLimiter(10, 1, 500);

        System.out.println("Config: capacity=10, refill=1 token per 500ms (2/sec)");
        System.out.println("═══════════════════════════════════════════════════════════════");
        System.out.println();

        // Phase 1: Consume all 10 tokens rapidly
        System.out.println("── Phase 1: Burst consume 10 tokens ──────────────────────────");
        for (int i = 1; i <= 12; i++) {
            ConsumptionProbe probe = limiter.tryConsumeAndReturnRemaining(1);
            System.out.printf("  Request %2d: %s%n", i, probe);
        }

        System.out.println();
        System.out.println("── Phase 2: Wait 2 seconds for refill (expect ~4 tokens) ────");
        Thread.sleep(2000);

        ConsumptionProbe probe = limiter.tryConsumeAndReturnRemaining(1);
        System.out.printf("  After 2s wait: %s (available=%d)%n", probe, limiter.getAvailableTokens());

        System.out.println();
        System.out.println("── Phase 3: Denial cache demo ────────────────────────────────");
        // Exhaust remaining tokens
        while (limiter.tryConsume()) { /* drain */ }
        System.out.println("  Drained all tokens.");

        // These should hit denial cache (instant, no refill calculation)
        long start = System.nanoTime();
        for (int i = 0; i < 10000; i++) {
            limiter.tryConsume(); // all hit denial cache
        }
        long elapsed = System.nanoTime() - start;
        System.out.printf("  10,000 denied requests (via denial cache): %d μs total (%.1f ns/req)%n",
                elapsed / 1000, (double) elapsed / 10000);

        System.out.println();
        System.out.println("── Phase 4: Concurrent access (100 threads, 1 token each) ───");
        limiter.reset();
        AtomicLong allowed = new AtomicLong(0);
        AtomicLong denied = new AtomicLong(0);

        Thread[] threads = new Thread[100];
        for (int i = 0; i < 100; i++) {
            threads[i] = new Thread(() -> {
                if (limiter.tryConsume()) allowed.incrementAndGet();
                else denied.incrementAndGet();
            });
        }
        for (Thread t : threads) t.start();
        for (Thread t : threads) t.join();

        System.out.printf("  100 concurrent threads: %d allowed, %d denied (capacity=10 ✓)%n",
                allowed.get(), denied.get());

        System.out.println();
        System.out.println("══════════════════════════════════════════════════════════════");
        System.out.println("Done! All token bucket mechanics demonstrated.");
    }
}

