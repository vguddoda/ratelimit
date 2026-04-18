package com.example.demo;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Hybrid Rate Limiting: coordinates local CAS cache + Redis Bucket4j.
 *
 * ══════════════════════════════════════════════════════════════════
 *  COMPLETE ALGORITHM  (rate=100/min, chunk=10% = 10 tokens)
 * ══════════════════════════════════════════════════════════════════
 *
 *  ── FAST PATH (90%+ of requests, <0.1ms) ──────────────────────
 *
 *  tryConsume(tenantId)
 *    → ALLOW   : CAS decremented local available → done, no Redis
 *    → DENY    : available=0 → syncAndConsume()
 *    → EXPIRED : chunk too old (slow traffic) → returnUnused + syncAndConsume()
 *
 *  ── SLOW PATH (10% of requests, 1-2ms) ────────────────────────
 *
 *  syncAndConsume(tenantId)
 *    1. tryLock per-tenant ReentrantLock (only 1 thread syncs Redis)
 *    2. Double-check: maybe another thread synced while we waited
 *    3. Bucket4j.tryConsume(chunkSize=10) → deducts 10 from Redis bucket
 *    4. Store chunk in local: available=10, consumed=0
 *    5. Consume 1 from local for this request
 *
 *  ── FAILURE PATH ───────────────────────────────────────────────
 *
 *  Redis down → CircuitBreaker opens → degradedMode()
 *    → Has local chunk: allow 10% of it
 *    → No local chunk: deny (fail closed)
 *
 * ══════════════════════════════════════════════════════════════════
 *  CASE ANALYSIS
 * ══════════════════════════════════════════════════════════════════
 *
 *  CASE 1: INITIAL REQUEST (no local chunk)
 *    tryConsume → DENY (quota=null)
 *    syncAndConsume → Bucket4j creates bucket with 100 tokens, tryConsume(10) → OK
 *    local: available=10, consumed=0
 *    serve this request → available=9
 *
 *  CASE 2: NORMAL FLOW
 *    Requests 1-10:  tryConsume → ALLOW (available 10→0)  — zero Redis calls
 *    Request 11:     tryConsume → DENY → syncAndConsume()
 *      Bucket4j: 90 remaining → consume 10 → local: available=10
 *    Requests 12-21: local cache → zero Redis calls
 *    ...100 requests = 10 Redis calls (99% reduction vs pure Bucket4j)
 *
 *  CASE 3: 5000 CONCURRENT REQUESTS (spike)
 *    local available = 10
 *    5000 threads enter CAS loop:
 *      Threads 1-10: each wins a CAS slot → ALLOW
 *      Threads 11-5000: available=0 → DENY → syncAndConsume()
 *        Only 1 thread wins tryLock → calls Bucket4j → gets 10 more
 *        Others: tryLock fails → spin → retry local → some get new tokens
 *    Eventually: exactly 100 allowed (rate limit enforced), rest get 429
 *
 *  CASE 4: SLOW TRAFFIC (rate=100/min, tenant sends 1 req every 10 min)
 *    10:00 - Request 1: no local → sync → Bucket4j consume(10) → local available=9
 *    10:10 - Request 2: chunk age = 10min > maxChunkAge(60s) → EXPIRED
 *      Return 9 unused tokens to Bucket4j (addTokens)
 *      Re-sync: Bucket4j has refilled to 100 (greedy refill) → consume 10 → available=9
 *    10:20 - Request 3: same expiry → re-sync → fresh chunk
 *
 *    WHY THIS IS CORRECT:
 *    Bucket4j refillGreedy(100, 60s) = ~1.67 tokens/sec refill
 *    By 10:10 (600s later) → fully refilled to 100 many times
 *    Without expiry: tenant stuck draining old 9 tokens for hours
 *    With expiry: gets fresh view of refilled bucket every maxChunkAge
 *
 *  CASE 5: POD SHUTDOWN
 *    @PreDestroy: iterate all tenants, return unused local tokens to Bucket4j
 *    No token waste. New pod for this tenant gets fresh chunk from Redis.
 *
 *  CASE 6: REDIS DOWN
 *    CircuitBreaker opens after 50% failure rate in last 100 calls
 *    degradedMode: allow 10% of existing local chunk (safety buffer)
 *    After 10s: CB tries half-open → 5 test calls → if Redis back → CLOSED
 */
@Service
public class HybridRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(HybridRateLimitService.class);

    private final LocalQuotaManager localQuotaManager;
    private final RedisQuotaManager redisQuotaManager;
    private final CircuitBreaker    circuitBreaker;

    // Per-tenant lock: only 1 thread syncs Redis per tenant (prevents thundering herd)
    private final ConcurrentHashMap<String, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();

    @Value("${rate.limit.chunk.percent:10}")
    private int chunkPercent;

    @Value("${rate.limit.degraded.percent:10}")
    private int degradedPercent;

    public HybridRateLimitService(LocalQuotaManager localQuotaManager,
                                   RedisQuotaManager redisQuotaManager) {
        this.localQuotaManager = localQuotaManager;
        this.redisQuotaManager = redisQuotaManager;

        // Set local chunk max age = Redis window duration
        // So slow-traffic chunks expire and re-sync at least once per window
        // (Done in @PostConstruct would be cleaner, but keeping it simple)

        CircuitBreakerConfig cbConfig = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .slidingWindowSize(100)
                .minimumNumberOfCalls(10)
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();

        this.circuitBreaker = CircuitBreakerRegistry.of(cbConfig).circuitBreaker("redis");
        this.circuitBreaker.getEventPublisher().onStateTransition(e ->
                log.warn("CircuitBreaker: {} → {}", e.getStateTransition().getFromState(),
                        e.getStateTransition().getToState()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  MAIN ENTRY POINT
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Is this request allowed?
     * Called from RateLimitingFilter for every HTTP request.
     */
    public boolean allowRequest(String tenantId) {
        // Configure chunk expiry based on Redis window duration
        localQuotaManager.setMaxChunkAgeMs(redisQuotaManager.getWindowDurationSeconds() * 1000);

        LocalQuotaManager.ConsumeResult result = localQuotaManager.tryConsume(tenantId);

        return switch (result) {
            case ALLOW -> true;                          // ✓ served from local cache

            case DENY -> syncAndConsume(tenantId);      // chunk empty → Redis

            case EXPIRED -> {                            // slow traffic → return unused + re-fetch
                returnUnusedToRedis(tenantId);
                yield syncAndConsume(tenantId);
            }
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SLOW PATH: sync with Redis Bucket4j
    // ─────────────────────────────────────────────────────────────────────────

    private boolean syncAndConsume(String tenantId) {
        ReentrantLock lock = tenantLocks.computeIfAbsent(tenantId, k -> new ReentrantLock());

        // Only ONE thread calls Redis per tenant — others wait briefly and retry local.
        // The syncing thread will have updated local cache by the time we retry.
        // We retry a few times because the Redis call takes 1-2ms.
        try {
            if (!lock.tryLock(50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // Lock held too long — try local one more time (syncing thread should be done)
                return localQuotaManager.tryConsume(tenantId) == LocalQuotaManager.ConsumeResult.ALLOW;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }

        try {
            // Double-check: maybe another thread already synced
            if (localQuotaManager.tryConsume(tenantId) == LocalQuotaManager.ConsumeResult.ALLOW) {
                return true;
            }

            // Call Bucket4j Redis (wrapped in circuit breaker)
            return circuitBreaker.executeSupplier(() -> {
                long globalLimit = redisQuotaManager.getTenantLimit(tenantId);
                long chunkSize = Math.max(1, (globalLimit * chunkPercent) / 100);

                // Bucket4j atomically deducts chunkSize tokens from the Redis token bucket
                // If bucket doesn't exist yet → Bucket4j creates it with full capacity
                long granted = redisQuotaManager.consumeChunk(tenantId, chunkSize, globalLimit);

                if (granted > 0) {
                    localQuotaManager.allocateChunk(tenantId, granted, globalLimit);
                    return localQuotaManager.tryConsume(tenantId) == LocalQuotaManager.ConsumeResult.ALLOW;
                }

                log.warn("Global rate limit exhausted for tenant {}", tenantId);
                return false;
            });

        } catch (Exception e) {
            log.error("Redis sync failed for tenant {}: {}", tenantId, e.getMessage());
            return handleDegradedMode(tenantId);
        } finally {
            lock.unlock();
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  FAILURE PATH: Redis down
    // ─────────────────────────────────────────────────────────────────────────

    private boolean handleDegradedMode(String tenantId) {
        LocalQuotaManager.LocalQuota quota = localQuotaManager.getQuota(tenantId);

        if (quota == null) {
            log.warn("Degraded: no local state for tenant {} → deny", tenantId);
            return false; // fail closed
        }

        long buffer = quota.getAllocated() * degradedPercent / 100;
        boolean allowed = quota.getConsumed() < buffer;

        if (allowed) {
            // Consume directly from local (bypass tryConsume's expiry check)
            quota.available.decrementAndGet();
            quota.consumed.incrementAndGet();
        }

        log.warn("Degraded tenant {}: consumed={} buffer={} → {}",
                tenantId, quota.getConsumed(), buffer, allowed ? "allow" : "deny");
        return allowed;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  HELPERS
    // ─────────────────────────────────────────────────────────────────────────

    /** Return unused local tokens to Bucket4j Redis (chunk expiry / window roll) */
    private void returnUnusedToRedis(String tenantId) {
        long unused = localQuotaManager.getUnusedTokens(tenantId);
        if (unused > 0) {
            long globalLimit = redisQuotaManager.getTenantLimit(tenantId);
            try {
                redisQuotaManager.returnTokens(tenantId, unused, globalLimit);
            } catch (Exception e) {
                log.warn("Failed to return tokens for tenant {}: {}", tenantId, e.getMessage());
            }
        }
        localQuotaManager.invalidate(tenantId);
    }

    public RateLimitStatus getStatus(String tenantId) {
        LocalQuotaManager.LocalQuota local = localQuotaManager.getQuota(tenantId);
        RedisQuotaManager.QuotaStats redis = redisQuotaManager.getQuotaStats(tenantId);
        return new RateLimitStatus(
                tenantId,
                local != null ? local.getAvailable() : 0,
                local != null ? local.getConsumed() : 0,
                redis.consumed(),
                redis.limit(),
                redisQuotaManager.isHealthy(),
                circuitBreaker.getState().toString()
        );
    }

    public void resetTenant(String tenantId) {
        localQuotaManager.invalidate(tenantId);
        redisQuotaManager.resetQuota(tenantId);
        log.info("Reset rate limit for tenant {}", tenantId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SHUTDOWN: return all unused tokens to Redis before pod dies
    // ─────────────────────────────────────────────────────────────────────────

    @PreDestroy
    public void shutdown() {
        log.warn("Pod shutting down — returning unused tokens to Redis");
        for (String tenantId : localQuotaManager.getAllTenants()) {
            long unused = localQuotaManager.getUnusedTokens(tenantId);
            if (unused > 0) {
                long limit = redisQuotaManager.getTenantLimit(tenantId);
                try {
                    redisQuotaManager.returnTokens(tenantId, unused, limit);
                    log.info("Shutdown: returned {} tokens for tenant {}", unused, tenantId);
                } catch (Exception e) {
                    log.warn("Shutdown: failed to return tokens for tenant {}", tenantId);
                }
            }
        }
        localQuotaManager.invalidateAll();
    }

    public record RateLimitStatus(
            String tenantId, long localAvailable, long localConsumed,
            long globalConsumed, long globalLimit,
            boolean redisHealthy, String circuitBreakerState
    ) {}
}

