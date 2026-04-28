package com.example.demo;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.concurrent.locks.*;

/**
 * ============================================================
 *  TOKEN BUCKET RATE LIMITER — Complete Algorithm (DS Round)
 * ============================================================
 *
 * CONCEPT:
 *   - A bucket holds max N tokens (capacity).
 *   - Tokens refill at a fixed rate R tokens/second.
 *   - Each request consumes 1 token.
 *   - If no token available → request is DENIED (429).
 *
 * ANALOGY:
 *   Imagine a physical bucket with a hole at bottom (drain = requests)
 *   and a tap at top (refill = time passing). You can burst as long as
 *   bucket has tokens, but sustained rate is capped by refill rate.
 *
 * KEY PROPERTIES:
 *   ┌─────────────────────────────────────────────┐
 *   │  Allows bursting up to `capacity` tokens    │
 *   │  Sustained rate = refillRate tokens/sec     │
 *   │  Memory: O(number of tenants)               │
 *   │  Time per request: O(1)                     │
 *   └─────────────────────────────────────────────┘
 *
 * COMPARED TO OTHER ALGORITHMS:
 *   ┌───────────────────────┬──────────┬─────────┬──────────┬──────────────┐
 *   │ Algorithm             │ Burst    │ Memory  │ Smooth   │ Edge Reset   │
 *   ├───────────────────────┼──────────┼─────────┼──────────┼──────────────┤
 *   │ Token Bucket          │ YES ✓    │ O(1)    │ Moderate │ No           │
 *   │ Leaky Bucket          │ NO       │ O(1)    │ YES ✓    │ No           │
 *   │ Fixed Window          │ YES ✓    │ O(1)    │ NO       │ YES (spike)  │
 *   │ Sliding Window Log    │ YES ✓    │ O(n) *  │ YES ✓    │ No           │
 *   │ Sliding Window Counter│ YES ✓    │ O(1) ** │ YES ✓    │ No           │
 *   └───────────────────────┴──────────┴─────────┴──────────┴──────────────┘
 *
 *   * Sliding Window LOG — WHY O(n) memory:
 *       Stores the EXACT TIMESTAMP of every request inside the current window.
 *       Data structure used: Queue / LinkedList of timestamps, one per tenant.
 *
 *         window = 60 seconds, limit = 100 req/min
 *         Queue → [t1=1000ms, t2=1200ms, t3=1800ms, ... t_n]
 *                  ↑ one Long entry added per request
 *
 *       On each new request:
 *         1. Evict all timestamps older than (now - 60s) from head of queue
 *         2. Count remaining entries → that IS the current request count
 *         3. count < limit  → ALLOW, append `now` to tail of queue
 *            count >= limit → DENY
 *
 *       WHY memory grows:
 *         Every allowed request adds one timestamp to the queue.
 *         Queue size at any moment = number of requests in the window.
 *           limit = 100 req/min   → queue holds up to 100 entries  per tenant
 *           limit = 10,000 req/min → queue holds up to 10,000 entries per tenant
 *         So n = max requests allowed per window → memory is O(n).
 *
 *         Token Bucket comparison: no matter how many requests come in,
 *         it only ever stores 3 numbers (tokens, capacity, lastRefillTime) → O(1).
 *
 *   ** Sliding Window COUNTER — WHY O(1) memory:
 *       Does NOT store individual timestamps. Uses a weighted approximation instead.
 *       Data structure: just 3 fields per tenant (prevCount, currCount, windowStart).
 *
 *         Formula:
 *           weight    = fraction of previous window still overlapping now  (0.0–1.0)
 *           estimated = (prevCount × weight) + currCount
 *
 *         Example — limit=10, window=60s, currently 40s into new window:
 *           weight    = (60 - 40) / 60 = 0.33  (33% of prev window still counts)
 *           estimated = prevCount * 0.33 + currCount
 *           if estimated < 10 → ALLOW
 *
 *         Memory is always just those 3 fields regardless of traffic volume → O(1).
 *         Trade-off: ~0–1% estimation error at window boundary vs exact LOG approach.
 *
 * MULTI-TENANT:
 *   Each tenant has its own independent bucket stored in a ConcurrentHashMap.
 */
public class TokenBucketRateLimiter {

    // ─────────────────────────────────────────────────────────────
    //  CORE DATA STRUCTURE: The Bucket
    // ─────────────────────────────────────────────────────────────

    /**
     * Represents a single tenant's token bucket.
     *
     * FIELDS:
     *   tokens         → current available tokens (double for fractional refill)
     *   capacity       → max tokens the bucket can hold (burst size)
     *   refillRate     → tokens added per second
     *   lastRefillTime → last time we computed refill (nanoseconds for precision)
     *
     * WHY double for tokens?
     *   If refill rate = 0.5 tokens/sec, after 1s we get 0.5 tokens.
     *   Using int would lose this fractional accumulation.
     *   Real consumption still requires >= 1.0 token.
     */
    private static class Bucket {
        double tokens;              // current token count (can be fractional)
        final double capacity;      // max tokens (burst ceiling)
        final double refillRate;    // tokens per second
        long lastRefillTimeNanos;   // wall-clock nanoseconds at last refill

        // ReentrantLock per bucket — fine-grained locking
        // (better than synchronizing the whole map)
        final ReentrantLock lock = new ReentrantLock();

        Bucket(double capacity, double refillRate) {
            this.capacity = capacity;
            this.refillRate = refillRate;
            this.tokens = capacity;  // start FULL — allow burst from the beginning
            this.lastRefillTimeNanos = System.nanoTime();
        }

        @Override
        public String toString() {
            return String.format("Bucket{tokens=%.2f, capacity=%.0f, refillRate=%.1f/s}",
                    tokens, capacity, refillRate);
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  STATE
    // ─────────────────────────────────────────────────────────────

    // ConcurrentHashMap for thread-safe tenant bucket storage
    // Key: tenantId (String),  Value: Bucket
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    // Global defaults (can be overridden per tenant)
    private final double defaultCapacity;   // e.g. 10 → allows burst of 10
    private final double defaultRefillRate; // e.g. 5  → 5 requests/sec sustained

    // Metrics (AtomicLong for thread-safe increment without locks)
    private final AtomicLong totalAllowed = new AtomicLong(0);
    private final AtomicLong totalDenied  = new AtomicLong(0);

    // ─────────────────────────────────────────────────────────────
    //  CONSTRUCTOR
    // ─────────────────────────────────────────────────────────────

    /**
     * @param defaultCapacity   max burst size (e.g. 10 tokens)
     * @param defaultRefillRate tokens added per second (e.g. 5.0)
     */
    public TokenBucketRateLimiter(double defaultCapacity, double defaultRefillRate) {
        if (defaultCapacity <= 0 || defaultRefillRate <= 0) {
            throw new IllegalArgumentException("Capacity and refill rate must be > 0");
        }
        this.defaultCapacity   = defaultCapacity;
        this.defaultRefillRate = defaultRefillRate;
    }

    // ─────────────────────────────────────────────────────────────
    //  CORE ALGORITHM: tryConsume
    // ─────────────────────────────────────────────────────────────

    /**
     * THE MAIN ALGORITHM — O(1) time, O(1) space per tenant
     *
     * STEPS:
     *   1. Get or create bucket for this tenant
     *   2. Lock the bucket (per-tenant, not global)
     *   3. Compute how many tokens to add since last check (lazy refill)
     *   4. Cap tokens at bucket capacity
     *   5. If tokens >= 1 → consume 1 token → ALLOW
     *      Else           → do nothing      → DENY
     *   6. Unlock
     *
     * LAZY REFILL TRICK (key insight for interviews):
     *   We do NOT use a background scheduler to add tokens every second.
     *   Instead, on every request, we calculate:
     *       tokensToAdd = elapsedSeconds * refillRate
     *   This is mathematically equivalent and avoids timer threads entirely.
     *
     * @param tenantId  unique identifier for the caller
     * @return true if request is ALLOWED, false if RATE LIMITED
     */
    public boolean tryConsume(String tenantId) {
        return tryConsume(tenantId, 1);
    }

    /**
     * Overloaded: consume `tokensNeeded` tokens at once (weighted requests).
     * Useful when some endpoints cost more (e.g. bulk API = 5 tokens).
     *
     * @param tenantId     unique caller ID
     * @param tokensNeeded number of tokens this request costs
     */
    public boolean tryConsume(String tenantId, int tokensNeeded) {
        // STEP 1: Get or create bucket (computeIfAbsent is atomic in ConcurrentHashMap)
        Bucket bucket = buckets.computeIfAbsent(
                tenantId,
                id -> new Bucket(defaultCapacity, defaultRefillRate)
        );

        // STEP 2: Lock this specific bucket only (fine-grained)
        bucket.lock.lock();
        try {
            // STEP 3: Lazy Refill — calculate tokens earned since last call
            long nowNanos = System.nanoTime();
            double elapsedSeconds = (nowNanos - bucket.lastRefillTimeNanos) / 1_000_000_000.0;

            //   tokens earned = time elapsed × refill rate
            double tokensToAdd = elapsedSeconds * bucket.refillRate;
            bucket.tokens = Math.min(
                    bucket.capacity,                  // STEP 4: cap at max capacity
                    bucket.tokens + tokensToAdd       // add earned tokens
            );
            bucket.lastRefillTimeNanos = nowNanos;    // update timestamp

            // STEP 5: Decision
            if (bucket.tokens >= tokensNeeded) {
                bucket.tokens -= tokensNeeded;        // consume tokens
                totalAllowed.incrementAndGet();
                return true;                          // ✅ ALLOW
            } else {
                totalDenied.incrementAndGet();
                return false;                         // ❌ DENY (429)
            }

        } finally {
            // STEP 6: Always unlock (finally block = safety net)
            bucket.lock.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────
    //  VARIANT 1: tryConsumeWithWait
    //  (Block and wait instead of immediately rejecting)
    // ─────────────────────────────────────────────────────────────

    /**
     * BLOCKING variant — waits up to `maxWaitMillis` for a token to become available.
     *
     * WHEN TO USE:
     *   - Background jobs / async tasks where waiting is acceptable
     *   - NOT for user-facing APIs (latency unacceptable)
     *
     * ALGORITHM:
     *   1. Try to consume immediately
     *   2. If denied, calculate exactly how long until next token is available
     *   3. Sleep that duration (not a spin loop — efficient)
     *   4. Try again once after waking up
     *
     * @param tenantId      caller ID
     * @param maxWaitMillis maximum time willing to wait
     * @return true if consumed within wait window, false if timed out
     */
    public boolean tryConsumeWithWait(String tenantId, long maxWaitMillis)
            throws InterruptedException {

        // Fast path: immediate success
        if (tryConsume(tenantId)) return true;

        Bucket bucket = buckets.get(tenantId);
        if (bucket == null) return false;

        // Calculate wait time:
        //   tokensNeeded = 1.0 - bucket.tokens (deficit)
        //   waitSeconds  = deficit / refillRate
        bucket.lock.lock();
        double deficit;
        double refillRate;
        try {
            deficit    = 1.0 - bucket.tokens;
            refillRate = bucket.refillRate;
        } finally {
            bucket.lock.unlock();
        }

        long waitMillis = (long) ((deficit / refillRate) * 1000);

        if (waitMillis > maxWaitMillis) {
            return false;  // Would take too long — reject
        }

        Thread.sleep(waitMillis);
        return tryConsume(tenantId);  // One more try after waiting
    }

    // ─────────────────────────────────────────────────────────────
    //  VARIANT 2: Per-Tenant Custom Config
    //  (Different limits for different tiers: Free / Pro / Enterprise)
    // ─────────────────────────────────────────────────────────────

    /**
     * Register a tenant with custom capacity and refill rate.
     *
     * USE CASE:
     *   Free tier    → capacity=10,  refillRate=1.0  (1 req/sec)
     *   Pro tier     → capacity=100, refillRate=10.0 (10 req/sec)
     *   Enterprise   → capacity=500, refillRate=50.0 (50 req/sec)
     *
     * @param tenantId   tenant identifier
     * @param capacity   burst ceiling
     * @param refillRate tokens per second
     */
    public void registerTenant(String tenantId, double capacity, double refillRate) {
        buckets.put(tenantId, new Bucket(capacity, refillRate));
    }

    // ─────────────────────────────────────────────────────────────
    //  UTILITY: Inspect bucket state (for debugging / monitoring)
    // ─────────────────────────────────────────────────────────────

    /**
     * Returns remaining tokens for a tenant WITHOUT consuming any.
     * Applies lazy refill first so the count is accurate.
     */
    public double availableTokens(String tenantId) {
        Bucket bucket = buckets.get(tenantId);
        if (bucket == null) return defaultCapacity; // never seen → full bucket

        bucket.lock.lock();
        try {
            long nowNanos = System.nanoTime();
            double elapsed = (nowNanos - bucket.lastRefillTimeNanos) / 1_000_000_000.0;
            return Math.min(bucket.capacity, bucket.tokens + elapsed * bucket.refillRate);
        } finally {
            bucket.lock.unlock();
        }
    }

    /**
     * How long (milliseconds) until at least 1 token is available.
     * Returns 0 if tokens are already available.
     */
    public long millisUntilNextToken(String tenantId) {
        Bucket bucket = buckets.get(tenantId);
        if (bucket == null) return 0;

        bucket.lock.lock();
        try {
            if (bucket.tokens >= 1.0) return 0;
            double deficit = 1.0 - bucket.tokens;
            return (long) ((deficit / bucket.refillRate) * 1000);
        } finally {
            bucket.lock.unlock();
        }
    }

    // Global metrics snapshot
    public long getTotalAllowed() { return totalAllowed.get(); }
    public long getTotalDenied()  { return totalDenied.get(); }
    public int  activeTenants()   { return buckets.size(); }

    // ─────────────────────────────────────────────────────────────
    //  MAIN — Demo / Interview Walkthrough
    // ─────────────────────────────────────────────────────────────

    /**
     * Self-contained demo. Run this class directly to see the algorithm in action.
     *
     * SCENARIOS COVERED:
     *   1. Basic allow / deny
     *   2. Burst then throttle
     *   3. Refill over time
     *   4. Multi-tenant isolation
     *   5. Concurrent thread safety
     *   6. Weighted token cost
     *   7. Tiered limits (Free vs Pro vs Enterprise)
     */
    public static void main(String[] args) throws InterruptedException {

        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   TOKEN BUCKET RATE LIMITER — DEMO       ║");
        System.out.println("╚══════════════════════════════════════════╝\n");

        // ── SCENARIO 1: Basic Allow / Deny ──────────────────────
        System.out.println("━━━ SCENARIO 1: Basic Allow / Deny ━━━");
        // capacity=5, refillRate=1 token/sec
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5, 1.0);

        for (int i = 1; i <= 7; i++) {
            boolean allowed = limiter.tryConsume("tenant-A");
            System.out.printf("  Request #%d → %s  (tokens left: %.1f)%n",
                    i, allowed ? "✅ ALLOW" : "❌ DENY ",
                    limiter.availableTokens("tenant-A"));
        }
        // Expected: 1-5 ALLOW, 6-7 DENY

        // ── SCENARIO 2: Refill Over Time ────────────────────────
        System.out.println("\n━━━ SCENARIO 2: Refill After 2 Seconds ━━━");
        System.out.println("  Sleeping 2 seconds...");
        Thread.sleep(2000);
        for (int i = 1; i <= 4; i++) {
            boolean allowed = limiter.tryConsume("tenant-A");
            System.out.printf("  Request #%d → %s  (tokens left: %.1f)%n",
                    i, allowed ? "✅ ALLOW" : "❌ DENY ",
                    limiter.availableTokens("tenant-A"));
        }
        // After 2s at rate=1/s: 2 tokens refilled → 1,2 ALLOW, 3,4 DENY

        // ── SCENARIO 3: Multi-Tenant Isolation ──────────────────
        System.out.println("\n━━━ SCENARIO 3: Multi-Tenant Isolation ━━━");
        TokenBucketRateLimiter multi = new TokenBucketRateLimiter(3, 1.0);
        String[] tenants = {"alice", "bob", "charlie"};
        for (String tenant : tenants) {
            boolean r1 = multi.tryConsume(tenant);
            boolean r2 = multi.tryConsume(tenant);
            System.out.printf("  %-10s: req1=%s  req2=%s  remaining=%.0f%n",
                    tenant,
                    r1 ? "✅" : "❌",
                    r2 ? "✅" : "❌",
                    multi.availableTokens(tenant));
        }
        // Each tenant has independent bucket — alice exhausted ≠ bob exhausted

        // ── SCENARIO 4: Weighted Token Cost ─────────────────────
        System.out.println("\n━━━ SCENARIO 4: Weighted Requests (Bulk API = 3 tokens) ━━━");
        TokenBucketRateLimiter weighted = new TokenBucketRateLimiter(10, 2.0);
        System.out.printf("  Single  request (1 token)  → %s  remaining=%.0f%n",
                weighted.tryConsume("bulk-tenant", 1) ? "✅ ALLOW" : "❌ DENY",
                weighted.availableTokens("bulk-tenant"));
        System.out.printf("  Bulk    request (3 tokens) → %s  remaining=%.0f%n",
                weighted.tryConsume("bulk-tenant", 3) ? "✅ ALLOW" : "❌ DENY",
                weighted.availableTokens("bulk-tenant"));
        System.out.printf("  Heavy   request (8 tokens) → %s  remaining=%.0f%n",
                weighted.tryConsume("bulk-tenant", 8) ? "✅ ALLOW" : "❌ DENY",
                weighted.availableTokens("bulk-tenant"));
        // 10 - 1 - 3 = 6 remaining → 8 token request DENIED

        // ── SCENARIO 5: Tiered Limits ────────────────────────────
        System.out.println("\n━━━ SCENARIO 5: Tiered Limits (Free vs Pro vs Enterprise) ━━━");
        TokenBucketRateLimiter tiered = new TokenBucketRateLimiter(1, 1.0);
        tiered.registerTenant("free-user",       3,   1.0);  // 1 req/sec,  burst=3
        tiered.registerTenant("pro-user",        20,  5.0);  // 5 req/sec,  burst=20
        tiered.registerTenant("enterprise-user", 100, 50.0); // 50 req/sec, burst=100

        for (String tenant : new String[]{"free-user", "pro-user", "enterprise-user"}) {
            int allowed = 0;
            for (int i = 0; i < 25; i++) {
                if (tiered.tryConsume(tenant)) allowed++;
            }
            System.out.printf("  %-20s → %d/25 allowed%n", tenant, allowed);
        }
        // free: 3/25, pro: 20/25, enterprise: 25/25

        // ── SCENARIO 6: Concurrent Thread Safety ─────────────────
        System.out.println("\n━━━ SCENARIO 6: Concurrent Thread Safety (50 threads) ━━━");
        TokenBucketRateLimiter concurrent = new TokenBucketRateLimiter(10, 1.0);
        AtomicInteger concAllowed = new AtomicInteger(0);
        AtomicInteger concDenied  = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(50);

        for (int i = 0; i < 50; i++) {
            new Thread(() -> {
                if (concurrent.tryConsume("shared-tenant")) {
                    concAllowed.incrementAndGet();
                } else {
                    concDenied.incrementAndGet();
                }
                latch.countDown();
            }).start();
        }

        latch.await();
        System.out.printf("  50 concurrent threads → allowed=%d  denied=%d%n",
                concAllowed.get(), concDenied.get());
        // MUST be exactly: allowed=10, denied=40 (no race conditions)

        // ── FINAL STATS ──────────────────────────────────────────
        System.out.println("\n━━━ ALGORITHM COMPLEXITY SUMMARY ━━━");
        System.out.println("  tryConsume()       → Time: O(1)  | Space: O(1) per tenant");
        System.out.println("  Refill strategy    → Lazy (no background thread needed)");
        System.out.println("  Thread safety      → Per-bucket ReentrantLock (no global lock)");
        System.out.println("  Burst support      → YES (controlled by capacity)");
        System.out.println("  Fractional tokens  → YES (double precision accumulation)");
        System.out.println("  Distributed        → Needs Redis CAS (see RedisQuotaManager)");
    }
}

