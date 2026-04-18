package com.example.demo;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.BucketProxy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Global quota manager backed by Bucket4j distributed token bucket in Redis.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  WHY BUCKET4J (not raw Lua scripts)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  Bucket4j implements Token Bucket algorithm correctly in Redis:
 *    capacity = globalLimit (e.g. 100)
 *    refill   = greedy: tokens added continuously, not in one batch
 *               100 tokens / 60 seconds = ~1.67 tokens/sec
 *
 *  Bucket4j uses Redis CAS internally (compare-and-swap on bucket state)
 *  so it's safe across multiple pods — no Lua scripts needed from our side.
 *
 * ═══════════════════════════════════════════════════════════════════
 *  HOW REFILL WORKS (critical for slow traffic case)
 * ═══════════════════════════════════════════════════════════════════
 *
 *  refillGreedy(100, 60s) means:
 *    At t=0:    bucket starts with 100 tokens (capacity)
 *    Consume 10: 90 left
 *    At t=6s:   ~10 tokens refilled → 100 again (capped at capacity)
 *    At t=60s:  100 tokens refilled total
 *
 *  So even if a tenant slowly sends 1 req/min:
 *    10:00 - consume chunk of 10 → 90 left in Redis
 *    10:06 - Redis refills 10 → 100 again (auto, continuous)
 *    10:10 - local chunk expired (maxChunkAge=60s) → re-fetch → get 10 more
 *
 *  Bucket4j handles refill math automatically — we just call tryConsume(N).
 *
 * ═══════════════════════════════════════════════════════════════════
 *  API USED BY HybridRateLimitService
 * ═══════════════════════════════════════════════════════════════════
 *
 *  consumeChunk(tenantId, chunkSize, globalLimit) → long tokensGranted
 *    Called when local chunk exhausted. Bucket4j deducts chunkSize from Redis.
 *    Returns full chunk, partial chunk (end of budget), or 0 (limit hit).
 *
 *  returnTokens(tenantId, unused, globalLimit) → void
 *    Called on chunk expiry or pod shutdown. Adds tokens back to Redis bucket.
 */
@Component
public class RedisQuotaManager {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaManager.class);

    private final ProxyManager<String> proxyManager;

    @Value("${rate.limit.window.duration:60}")
    private long windowDurationSeconds;

    @Value("${rate.limit.default.limit:10000}")
    private long defaultLimit;

    public RedisQuotaManager(ProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  CORE: consume chunkSize tokens from Redis token bucket
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Consume chunkSize tokens from the global Redis bucket for this tenant.
     *
     * CASES:
     *  1. Bucket has enough  → tryConsume(chunkSize) succeeds → return chunkSize
     *  2. Bucket has partial → probe shows remaining < chunkSize → consume remaining
     *  3. Bucket empty       → return 0 (global limit exhausted)
     *
     * INITIAL CASE:
     *  First call ever: Bucket4j creates the bucket in Redis with full capacity.
     *  So first tryConsume(10) with capacity=100 → succeeds, 90 remaining.
     *
     * ATOMICITY:
     *  Bucket4j uses Redis CAS internally. Safe across all pods.
     */
    public long consumeChunk(String tenantId, long chunkSize, long globalLimit) {
        try {
            BucketProxy bucket = getOrCreateBucket(tenantId, globalLimit);

            // Try consuming full chunk
            ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(chunkSize);

            if (probe.isConsumed()) {
                log.info("Bucket4j: granted {} tokens for tenant {} ({} remaining)",
                        chunkSize, tenantId, probe.getRemainingTokens());
                return chunkSize;
            }

            // Full chunk unavailable — try partial (take whatever is left)
            long remaining = probe.getRemainingTokens();
            if (remaining > 0) {
                ConsumptionProbe partial = bucket.tryConsumeAndReturnRemaining(remaining);
                if (partial.isConsumed()) {
                    log.info("Bucket4j: granted PARTIAL {} tokens for tenant {} (0 remaining)",
                            remaining, tenantId);
                    return remaining;
                }
            }

            log.warn("Bucket4j: global limit exhausted for tenant {}", tenantId);
            return 0;

        } catch (Exception e) {
            log.error("Bucket4j error for tenant {}: {}", tenantId, e.getMessage());
            throw e; // let circuit breaker catch this
        }
    }

    /**
     * Return unused tokens back to Redis bucket.
     * Called when local chunk expires (slow traffic) or pod shuts down.
     *
     * Bucket4j addTokens() adds tokens back to the bucket (capped at capacity).
     */
    public void returnTokens(String tenantId, long tokens, long globalLimit) {
        if (tokens <= 0) return;
        try {
            BucketProxy bucket = getOrCreateBucket(tenantId, globalLimit);
            bucket.addTokens(tokens);
            log.info("Returned {} tokens to Redis for tenant {}", tokens, tenantId);
        } catch (Exception e) {
            log.warn("Failed to return tokens for tenant {}: {}", tenantId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  BUCKET CREATION
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Get existing bucket from Redis or create with config.
     *
     * Token Bucket config:
     *   capacity = globalLimit        → max burst size (e.g. 100)
     *   refill   = refillGreedy       → tokens added continuously
     *              globalLimit tokens per windowDurationSeconds
     *
     * Example (limit=100, window=60s):
     *   Bucket starts with 100 tokens (full capacity)
     *   Refill rate: 100/60 = ~1.67 tokens/second
     *   After consuming 10: 90 left, 6 seconds later back to 100
     */
    private BucketProxy getOrCreateBucket(String tenantId, long globalLimit) {
        String key = "ratelimit:" + tenantId;

        Supplier<BucketConfiguration> configSupplier = () -> BucketConfiguration.builder()
                .addLimit(Bandwidth.builder()
                        .capacity(globalLimit)
                        .refillGreedy(globalLimit, Duration.ofSeconds(windowDurationSeconds))
                        .initialTokens(globalLimit) // start with full bucket
                        .build())
                .build();

        return proxyManager.builder().build(key, configSupplier);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TENANT CONFIG & STATUS
    // ─────────────────────────────────────────────────────────────────────────

    public long getTenantLimit(String tenantId) {
        // Production: look up per-tenant config from DB/cache
        return defaultLimit;
    }

    public long getWindowDurationSeconds() {
        return windowDurationSeconds;
    }

    public boolean isHealthy() {
        try {
            // Quick check: create a test bucket and probe it
            BucketProxy bucket = getOrCreateBucket("__health__", 1);
            bucket.getAvailableTokens();
            return true;
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return false;
        }
    }

    public QuotaStats getQuotaStats(String tenantId) {
        try {
            long globalLimit = getTenantLimit(tenantId);
            BucketProxy bucket = getOrCreateBucket(tenantId, globalLimit);
            long available = bucket.getAvailableTokens();
            long consumed = globalLimit - available;
            return new QuotaStats(tenantId, globalLimit, Math.max(0, consumed), Math.max(0, available));
        } catch (Exception e) {
            log.error("Failed to get stats for tenant {}", tenantId, e);
            return new QuotaStats(tenantId, 0, 0, 0);
        }
    }

    public void resetQuota(String tenantId) {
        try {
            BucketProxy bucket = getOrCreateBucket(tenantId, getTenantLimit(tenantId));
            bucket.reset();
            log.info("Reset bucket for tenant {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to reset quota for tenant {}", tenantId, e);
        }
    }

    public record QuotaStats(String tenantId, long limit, long consumed, long remaining) {}
}
