package com.example.demo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe local quota manager using Caffeine cache and CAS operations.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  HOW IT WORKS  (example: 60 tokens/min, chunk=10% = 6 tokens)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  Each tenant gets a LocalQuota holding ONE chunk of tokens (6 tokens).
 *  Requests consume tokens via CAS loop — lock-free, handles 5k concurrent threads.
 *
 *  Three possible results from tryConsume():
 *
 *   ALLOW   → token consumed from local chunk (no Redis call needed)
 *
 *   DENY    → available=0, chunk FULLY consumed
 *             Nothing to return (all tokens used).
 *             Caller calls syncAndConsume() → Bucket4j consume(6) → get next chunk.
 *             Example: 6 tokens consumed in 5 seconds at 1 req/sec
 *                      → DENY → sync → Bucket4j grants next 6 → continue
 *
 *   EXPIRED → chunk still has tokens but is OLDER than maxChunkAgeMs
 *             Must RETURN unused tokens to Bucket4j (they were pre-consumed from Redis).
 *             Then get a fresh chunk with current Bucket4j state.
 *             Example: 6 tokens allocated, only 1 consumed in 2 minutes (slow traffic)
 *                      → 5 unused → return 5 to Bucket4j → get fresh 6
 *
 *  FIELDS:
 *   available     = tokens left in this chunk (CAS target — hot field)
 *   consumed      = tokens used from this chunk (for observability)
 *   allocated     = chunk size when it was allocated (e.g. 6)
 *   globalLimit   = tenant's total limit per window (e.g. 60)
 *   allocatedAtMs = when chunk was fetched from Redis (for slow-traffic expiry)
 *
 *  SLOW TRAFFIC CASE (60/min limit, tenant sends 1 req every 2 minutes):
 *   Chunk of 6 allocated at 10:00.  Only 1 consumed by 10:02.
 *   Bucket4j refills 1 token/sec → 120 tokens refilled in 120s → capped at 60.
 *   If we keep the old chunk: tenant sees 5 tokens, but Redis has 60 available.
 *   Fix: expire chunk after maxChunkAgeMs(60s), return 5 to Redis, get fresh 6.
 *
 *  FAST TRAFFIC CASE (60/min limit, tenant sends 1 req/sec):
 *   Chunk of 6 consumed in 6 seconds → DENY (not EXPIRED, age=6s < 60s max)
 *   → syncAndConsume → Bucket4j: had 54 + refilled ~6 = 60 → grant 6 → continue
 *   Nothing to return because available=0 (all consumed).
 */
@Component
public class LocalQuotaManager {

    private static final Logger log = LoggerFactory.getLogger(LocalQuotaManager.class);

    /**
     * Max age of a local chunk before we consider it stale and re-fetch from Redis.
     * This handles the slow-traffic case: if a chunk sits for longer than one full
     * refill period, Bucket4j has refilled tokens we can't see from local cache.
     *
     * Set to the window duration (e.g. 60s). Configurable via setMaxChunkAgeMs().
     */
    private long maxChunkAgeMs = 60_000;

    private final Cache<String, LocalQuota> quotaCache;

    /**
     * Per-tenant denial cache: when Bucket4j says "exhausted, wait N nanos",
     * we store deniedUntilMs = now + N/1M so subsequent requests get instant 429
     * without any Redis call.
     *
     * Example: 60/min limit, all 60 consumed at t=30s
     *   Bucket4j: nanosToWaitForRefill = ~1 billion ns (1 second until 1 token refills)
     *   BUT we need chunkSize(6) tokens, not just 1 — so wait for chunkSize * refillInterval
     *   deniedUntilMs = now + nanosToWait/1M
     *   Next 1 second: any request → DENIED_CACHED → instant 429, zero Redis calls
     *   After 1 second: denial expired → retry Bucket4j → maybe get partial/full chunk
     */
    private final ConcurrentHashMap<String, AtomicLong> deniedUntilMs = new ConcurrentHashMap<>();

    public LocalQuotaManager() {
        this.quotaCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(5)) // cleanup inactive tenants
                .recordStats()
                .build();
    }

    public void setMaxChunkAgeMs(long ms) {
        this.maxChunkAgeMs = ms;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CONSUME — CAS loop, handles 5k concurrent threads
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Try to consume 1 token from the local chunk.
     *
     * Returns:
     *   ALLOW          → token consumed from local chunk (no Redis call needed)
     *   DENY           → chunk exhausted (available=0), caller must sync Redis
     *   EXPIRED        → chunk too old (slow traffic), caller should return unused + re-fetch
     *   DENIED_CACHED  → Bucket4j already said "exhausted" recently, instant 429, NO Redis call
     *
     * Thread safety:
     *   CAS loop — only one thread wins per compareAndSet. Losers retry immediately
     *   at CPU speed (~5ns per retry). No OS call, no blocking, no context switch.
     */
    public ConsumeResult tryConsume(String tenantId) {

        // ── Check denial cache FIRST (most important optimization) ────────
        // If Bucket4j said "exhausted, wait N nanos", we cached that.
        // Until that time passes, return instant 429 — zero Redis calls.
        //
        // Example: 60/min all used up at t=30s
        //   Bucket4j: nanosToWait = 1 second (until 1 token refills)
        //   deniedUntilMs = t=30s + 1s = t=31s
        //   Requests from t=30s to t=31s: DENIED_CACHED → instant 429
        //   Request at t=31s: denial expired → tryConsume → DENY → sync → Bucket4j may have tokens
        AtomicLong deniedUntil = deniedUntilMs.get(tenantId);
        if (deniedUntil != null) {
            long until = deniedUntil.get();
            if (until > 0 && System.currentTimeMillis() < until) {
                log.debug("Denied (cached) for tenant {} until {}ms", tenantId, until);
                return ConsumeResult.DENIED_CACHED;
            }
            // Denial expired — clear it so next attempt goes to Bucket4j
            deniedUntil.set(0);
        }

        LocalQuota quota = quotaCache.getIfPresent(tenantId);

        if (quota == null) {
            return ConsumeResult.DENY; // no chunk allocated yet
        }

        // ── Slow traffic expiry ──────────────────────────────────────────
        // If this chunk has been sitting for longer than maxChunkAgeMs,
        // Bucket4j has been refilling the Redis bucket during that time.
        // We should discard this stale chunk and fetch a fresh one.
        //
        // Example: rate=100/min, chunk=10 tokens
        //   Allocated at 10:00:00, it's now 10:01:30 (90 seconds later)
        //   In 90 seconds Bucket4j refilled ~150 tokens in Redis
        //   But locally we're still draining the original 10 tokens
        //   This check forces a re-sync so the tenant isn't unfairly throttled
        long age = System.currentTimeMillis() - quota.allocatedAtMs;
        if (age > maxChunkAgeMs) {
            log.debug("Chunk expired for tenant {} (age={}ms > max={}ms)", tenantId, age, maxChunkAgeMs);
            return ConsumeResult.EXPIRED;
        }

        // ── CAS loop: atomically decrement available ─────────────────────
        while (true) {
            long current = quota.available.get();

            if (current <= 0) {
                return ConsumeResult.DENY; // chunk fully consumed
            }

            if (quota.available.compareAndSet(current, current - 1)) {
                quota.consumed.incrementAndGet();
                return ConsumeResult.ALLOW;
            }
            // CAS failed — another thread won this slot — spin immediately
            Thread.onSpinWait();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ALLOCATE — stamp a new chunk into local cache
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Store a freshly-allocated chunk from Redis Bucket4j into local cache.
     * Resets consumed counter, stamps allocatedAtMs for expiry tracking.
     *
     * Called only by the ONE thread holding the per-tenant lock in HybridRateLimitService.
     */
    public void allocateChunk(String tenantId, long tokens, long globalLimit) {
        long now = System.currentTimeMillis();
        LocalQuota existing = quotaCache.getIfPresent(tenantId);

        if (existing != null) {
            // Subsequent chunk: reset and top up
            existing.available.set(tokens);
            existing.allocated.set(tokens);
            existing.consumed.set(0);
            existing.globalLimit = globalLimit;
            existing.allocatedAtMs = now;
        } else {
            // First chunk for this tenant on this pod
            quotaCache.put(tenantId, new LocalQuota(tokens, globalLimit, now));
        }
        log.info("Local chunk for tenant {}: {} tokens (limit={})", tenantId, tokens, globalLimit);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  ACCESSORS
    // ─────────────────────────────────────────────────────────────────────────

    public LocalQuota getQuota(String tenantId) {
        return quotaCache.getIfPresent(tenantId);
    }

    /** Unused tokens in current chunk (for returning to Redis on expiry/shutdown) */
    public long getUnusedTokens(String tenantId) {
        LocalQuota q = quotaCache.getIfPresent(tenantId);
        return q == null ? 0 : Math.max(0, q.available.get());
    }

    public void invalidate(String tenantId) {
        quotaCache.invalidate(tenantId);
        clearDenied(tenantId);
    }

    public void invalidateAll() {
        quotaCache.invalidateAll();
        deniedUntilMs.clear();
    }

    /**
     * Mark a tenant as denied until a specific time.
     * Called when Bucket4j returns 0 tokens (global limit exhausted).
     *
     * @param tenantId    the tenant
     * @param nanosToWait from Bucket4j's ConsumptionProbe.getNanosToWaitForRefill()
     *                    This is how long until at LEAST 1 token refills.
     *                    We need chunkSize tokens, so actual wait may be longer.
     *                    But even waiting for 1 token's refill avoids pointless Redis calls.
     *
     * Example: 60/min, refill = 1 token/sec
     *   All 60 consumed → nanosToWait = 1_000_000_000 (1 second for 1 token)
     *   deniedUntilMs = now + 1000ms
     *   Any request in next 1 second → DENIED_CACHED → instant 429
     */
    public void markDenied(String tenantId, long nanosToWait) {
        long untilMs = System.currentTimeMillis() + (nanosToWait / 1_000_000);
        deniedUntilMs.computeIfAbsent(tenantId, k -> new AtomicLong()).set(untilMs);
        log.info("Tenant {} denied for {}ms (until refill)", tenantId, nanosToWait / 1_000_000);
    }

    /**
     * Clear denial cache for a tenant (called after successful chunk allocation or reset).
     */
    public void clearDenied(String tenantId) {
        AtomicLong d = deniedUntilMs.get(tenantId);
        if (d != null) d.set(0);
    }

    public Set<String> getAllTenants() {
        return quotaCache.asMap().keySet();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TYPES
    // ─────────────────────────────────────────────────────────────────────────

    public enum ConsumeResult {
        ALLOW,          // token granted from local chunk
        DENY,           // chunk empty, need Redis sync
        EXPIRED,        // chunk too old (slow traffic), return unused + re-fetch
        DENIED_CACHED   // Bucket4j said "exhausted" recently, instant 429, NO Redis call
    }

    /**
     * Holds ONE chunk of tokens for a tenant.
     *
     *  available     10 → 0   CAS target (AtomicLong)
     *  consumed       0 → 10  incremented alongside available decrement
     *  allocated      10      chunk size (set once per chunk)
     *  globalLimit   100      tenant's per-window limit
     *  allocatedAtMs epoch    when this chunk was grabbed from Redis
     */
    public static class LocalQuota {
        public final AtomicLong available;
        public final AtomicLong consumed;
        public final AtomicLong allocated;
        public volatile long globalLimit;
        public volatile long allocatedAtMs;

        public LocalQuota(long tokens, long globalLimit, long now) {
            this.available     = new AtomicLong(tokens);
            this.consumed      = new AtomicLong(0);
            this.allocated     = new AtomicLong(tokens);
            this.globalLimit   = globalLimit;
            this.allocatedAtMs = now;
        }

        public long getAvailable()  { return available.get(); }
        public long getConsumed()   { return consumed.get(); }
        public long getAllocated()  { return allocated.get(); }
    }
}
