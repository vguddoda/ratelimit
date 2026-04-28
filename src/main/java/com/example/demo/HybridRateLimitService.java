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
 *  CONFIG EXAMPLE  (used throughout all cases below)
 * ══════════════════════════════════════════════════════════════════
 *
 *  globalLimit    = 60 tokens per minute
 *  chunkSize      = 10% of 60 = 6 tokens per chunk
 *  Bucket4j refill = refillGreedy(60, 60s) = 1 token/second
 *  maxChunkAge    = 60 seconds (= window duration)
 *
 *  KEY MATH:
 *  At max throughput (1 req/sec), chunk of 6 lasts 6 seconds.
 *  After 6 seconds: local empty → sync Redis → get next 6 tokens.
 *  Redis calls per minute = 60 / 6 = 10 calls (vs 60 with pure Bucket4j = 83% reduction)
 *
 * ══════════════════════════════════════════════════════════════════
 *  ALGORITHM FLOW
 * ══════════════════════════════════════════════════════════════════
 *
 *  ── FAST PATH (90%+ of requests, <0.1ms) ──────────────────────
 *
 *  tryConsume(tenantId)
 *    → ALLOW   : CAS decremented local available → done, no Redis
 *    → DENY    : available=0, chunk FULLY consumed → syncAndConsume()
 *    → EXPIRED : chunk too old (slow traffic) → returnUnused + syncAndConsume()
 *
 *  ── SLOW PATH (1 Redis call per chunk = every 6th request) ─────
 *
 *  syncAndConsume(tenantId)
 *    1. tryLock per-tenant ReentrantLock (only 1 thread syncs Redis)
 *    2. Double-check: maybe another thread synced while we waited
 *    3. Bucket4j.tryConsume(chunkSize=6) → deducts 6 from Redis bucket
 *    4. Store chunk in local: available=6, consumed=0
 *    5. Consume 1 from local for this request
 *
 *  IMPORTANT DISTINCTION:
 *    DENY    = chunk EMPTY (available=0). Nothing to return. Just get next chunk.
 *    EXPIRED = chunk still has tokens but is OLD. Return unused, THEN get fresh chunk.
 *
 *  ── FAILURE PATH ───────────────────────────────────────────────
 *
 *  Redis down → CircuitBreaker opens → degradedMode()
 *    → Has local chunk: allow 10% of it
 *    → No local chunk: deny (fail closed)
 *
 * ══════════════════════════════════════════════════════════════════
 *  CASE ANALYSIS  (all cases use 60 tokens/min, chunk=6)
 * ══════════════════════════════════════════════════════════════════
 *
 *  CASE 1: INITIAL REQUEST (no local chunk yet)
 *  ─────────────────────────────────────────────
 *    t=0s: First request arrives, no local cache for this tenant
 *    tryConsume → DENY (quota=null, never allocated)
 *    syncAndConsume:
 *      Bucket4j creates bucket in Redis: capacity=60, initialTokens=60
 *      consumeChunk(6) → granted=6, 54 remaining in Redis
 *      allocateChunk locally: available=6, consumed=0
 *      tryConsume → ALLOW (available: 6→5)
 *    Redis calls: 1
 *
 *  CASE 2: NORMAL STEADY TRAFFIC (1 req/sec)
 *  ──────────────────────────────────────────
 *    t=0s:  Request 1:  sync → Bucket4j consume(6) → local available=5        [1 Redis call]
 *    t=1s:  Request 2:  tryConsume → ALLOW (available: 5→4)                    [0 Redis calls]
 *    t=2s:  Request 3:  tryConsume → ALLOW (available: 4→3)
 *    t=3s:  Request 4:  tryConsume → ALLOW (available: 3→2)
 *    t=4s:  Request 5:  tryConsume → ALLOW (available: 2→1)
 *    t=5s:  Request 6:  tryConsume → ALLOW (available: 1→0)
 *    t=6s:  Request 7:  tryConsume → DENY (available=0, chunk EMPTY)           [1 Redis call]
 *             syncAndConsume:
 *               Bucket4j: had 54 remaining + ~6 refilled (6s × 1tok/s) = 60 → consume(6) → 54
 *               local: available=6 again
 *    t=7s:  Request 8:  tryConsume → ALLOW (available: 5)                      [0 Redis calls]
 *    ...
 *    t=59s: Request 60: last request in this minute
 *
 *    TOTAL: 60 requests, ~10 Redis calls (one per chunk)
 *    Pure Bucket4j: 60 Redis calls. Reduction: 83%
 *
 *  CASE 3: BURST (60 requests in 1 second)
 *  ────────────────────────────────────────
 *    t=0s: 60 threads arrive simultaneously
 *    local available = 0 (no chunk yet)
 *
 *    ALL threads → DENY → syncAndConsume()
 *    Thread 1 wins tryLock → Bucket4j consume(6) → local: available=6
 *    Threads 2-6: waited 50ms for lock, retry local → CAS → ALLOW (available: 5→0)
 *    Thread 7 wins next lock → Bucket4j consume(6) → local: available=6
 *    ...
 *    After 10 syncs: 10 × 6 = 60 tokens consumed from Bucket4j → all 60 allowed
 *    Thread 61 (if any): Bucket4j has 0 → 429
 *
 *    Redis calls: 10 (one per chunk of 6)
 *    Result: Exactly 60 succeed, rest get 429. No over-consumption.
 *
 *  CASE 4: CHUNK EXHAUSTION IN 5 SECONDS (the key case)
 *  ─────────────────────────────────────────────────────
 *    Config: 60/min, chunk=6, Bucket4j refill = 1 token/sec
 *
 *    t=0s:   Chunk allocated: 6 tokens from Bucket4j (54 left in Redis)
 *    t=0-5s: 6 requests arrive, each CAS-consumes 1 token
 *    t=5s:   available=0, chunk FULLY consumed in 5 seconds
 *
 *    t=5s: Request 7: tryConsume → DENY (available=0)
 *      → This is DENY not EXPIRED (chunk is 5 seconds old, < maxChunkAge of 60s)
 *      → No tokens to return (available=0, all consumed)
 *      → syncAndConsume:
 *          Bucket4j: 54 remaining + ~5 refilled (5s × 1tok/s) = ~59
 *          consumeChunk(6) → granted=6 → 53 remaining
 *          local: available=6 (fresh chunk)
 *
 *    THIS IS CORRECT:
 *    - Chunk empty → just grab next chunk, no return needed
 *    - Bucket4j refilled ~5 tokens during those 5 seconds
 *    - Tenant is NOT rate-limited yet (only used 12 of 60 per minute)
 *    - Bucket4j enforces the global limit, we just batch the calls
 *
 *    Per minute at this pace:
 *      60 requests, consumed in ~10 chunks of 6
 *      Bucket4j keeps pace because refill = 1/sec = 60/min = exactly the limit
 *      If tenant tries 61st → Bucket4j returns 0 → 429
 *
 *  CASE 5: SLOW TRAFFIC (1 request every 2 minutes)
 *  ──────────────────────────────────────────────────
 *    t=0:00: Request 1: no local → sync → Bucket4j consume(6) → local available=5
 *              Bucket4j: 60-6 = 54 remaining
 *
 *    t=2:00: Request 2 (120 seconds later):
 *      tryConsume checks: chunk age = 120s > maxChunkAge(60s) → EXPIRED
 *
 *      EXPIRED PATH (different from DENY):
 *        1. Return 5 unused tokens to Bucket4j: addTokens(5)
 *           Bucket4j: 54 + refilled_over_120s + 5_returned → capped at 60 (capacity)
 *        2. Invalidate local chunk
 *        3. syncAndConsume: Bucket4j consume(6) → granted=6 → local: available=5
 *
 *    t=4:00: Request 3: EXPIRED again → return 5 → re-sync → fresh chunk
 *
 *    WHY EXPIRED RETURNS TOKENS (DENY DOES NOT):
 *      DENY:    available=0 → nothing to return → just get next chunk
 *      EXPIRED: available=5 → those 5 tokens were pre-consumed from Bucket4j
 *               but never used → return them so they're not wasted
 *               Without return: tenant loses 5 tokens per chunk forever
 *               With return: tokens go back to Redis bucket, can be used later
 *
 *    WHY NOT JUST KEEP THE OLD CHUNK:
 *      Bucket4j refills 1 token/sec. After 120s → 120 tokens refilled (capped at 60).
 *      If we keep the old chunk: tenant sees only 5 local tokens, unaware that
 *      Redis bucket is fully refilled. Tenant gets artificially throttled.
 *      Expiry forces re-sync → tenant gets fresh view of actual available capacity.
 *
 *  CASE 6: POD SHUTDOWN
 *  ─────────────────────
 *    @PreDestroy: iterate all tenants
 *    For each: return unused local tokens to Bucket4j (addTokens)
 *    Example: tenant has 3 unused tokens → addTokens(3) → back in Redis bucket
 *    No token waste. Consistent hash routes tenant to new pod → fresh chunk.
 *
 *  CASE 7: REDIS DOWN
 *  ───────────────────
 *    CircuitBreaker opens after 50% failure rate in last 100 calls
 *    degradedMode: allow 10% of existing local chunk (safety buffer)
 *    Example: chunk was 6, degraded buffer = 0.6 → rounds to 0 → deny all
 *      (with larger limits like 1000/chunk, 10% = 100 buffer → meaningful)
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

            case DENIED_CACHED -> false;                 // Bucket4j said "exhausted" recently
                                                         // → instant 429, zero Redis calls
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
                // Returns ChunkResult: granted tokens + nanosToWaitForRefill (if exhausted)
                RedisQuotaManager.ChunkResult result2 = redisQuotaManager.consumeChunk(tenantId, chunkSize, globalLimit);

                if (result2.granted() > 0) {
                    // Got tokens — clear any previous denial, store chunk locally
                    localQuotaManager.clearDenied(tenantId);
                    localQuotaManager.allocateChunk(tenantId, result2.granted(), globalLimit);
                    return localQuotaManager.tryConsume(tenantId) == LocalQuotaManager.ConsumeResult.ALLOW;
                }

                // EXHAUSTED: Bucket4j tells us exactly when next token refills.
                // Cache this denial locally — any request until then gets instant 429.
                //
                // Example: 60/min, all used at t=30s
                //   nanosToWaitForRefill = 1_000_000_000 (1 second until 1 token)
                //   markDenied: deniedUntilMs = now + 1000
                //   Next 1 second: tryConsume → DENIED_CACHED → false → 429 (zero Redis calls)
                //   After 1 second: denial expired → tryConsume → DENY → syncAndConsume → retry Bucket4j
                localQuotaManager.markDenied(tenantId, result2.nanosToWaitForRefill());
                log.warn("Global rate limit exhausted for tenant {} (retry in {}ms)",
                        tenantId, result2.nanosToWaitForRefill() / 1_000_000);
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

