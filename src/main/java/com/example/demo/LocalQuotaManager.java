package com.example.demo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe local quota manager using Caffeine cache and CAS operations.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  HOW IT WORKS
 * ═══════════════════════════════════════════════════════════════════
 *
 *  Each tenant gets a LocalQuota holding ONE chunk of tokens (10% of global limit).
 *  Requests consume tokens via CAS loop — lock-free, handles 5k concurrent threads.
 *
 *  When available hits 0 → chunk exhausted → caller (HybridRateLimitService) calls
 *  Bucket4j Redis to consume the NEXT chunk from the global token bucket.
 *
 *  FIELDS:
 *   available     = tokens left in this chunk (CAS target — hot field)
 *   consumed      = tokens used from this chunk (for observability)
 *   allocated     = chunk size when it was allocated
 *   globalLimit   = tenant's total limit per window
 *   allocatedAtMs = when chunk was fetched from Redis (for slow-traffic expiry)
 *
 *  SLOW TRAFFIC CASE (rate=100/min, tenant sends 1 req/min):
 *   Chunk of 10 allocated at 10:00. Consumed over 10 minutes.
 *   But Bucket4j refills tokens continuously (100 tokens/60s = ~1.67/sec).
 *   By 10:10 the Redis bucket has refilled 100 tokens 10 times.
 *   If we hold the old chunk, we're blocking the tenant from using refilled tokens.
 *   Fix: expire chunk if older than maxChunkAgeMs, return unused tokens, get fresh chunk.
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
     *   ALLOW   → token consumed from local chunk (no Redis call needed)
     *   DENY    → chunk exhausted (available=0), caller must sync Redis
     *   EXPIRED → chunk too old (slow traffic), caller should return unused + re-fetch
     *
     * Thread safety:
     *   CAS loop — only one thread wins per compareAndSet. Losers retry immediately
     *   at CPU speed (~5ns per retry). No OS call, no blocking, no context switch.
     */
    public ConsumeResult tryConsume(String tenantId) {
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
    }

    public void invalidateAll() {
        quotaCache.invalidateAll();
    }

    public Set<String> getAllTenants() {
        return quotaCache.asMap().keySet();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TYPES
    // ─────────────────────────────────────────────────────────────────────────

    public enum ConsumeResult {
        ALLOW,    // token granted from local chunk
        DENY,     // chunk empty, need Redis sync
        EXPIRED   // chunk too old (slow traffic), return unused + re-fetch
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
