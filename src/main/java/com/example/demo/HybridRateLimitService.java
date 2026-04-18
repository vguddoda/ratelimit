package com.example.demo;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Hybrid rate limiting service combining local cache and Redis.
 *
 * FLOW:
 * 1. Check local cache (99% of requests)
 * 2. If local quota exhausted, sync with Redis
 * 3. Allocate chunk from Redis (10% of limit)
 * 4. Continue serving from local cache
 */
@Service
public class HybridRateLimitService {

    private static final Logger log = LoggerFactory.getLogger(HybridRateLimitService.class);

    private final LocalQuotaManager localQuotaManager;
    private final RedisQuotaManager redisQuotaManager;
    private final CircuitBreaker circuitBreaker;

    @Value("${rate.limit.chunk.percent:10}")
    private int chunkPercent;

    @Value("${rate.limit.sync.threshold:90}")
    private int syncThreshold;

    @Value("${rate.limit.degraded.percent:10}")
    private int degradedPercent;

    @Autowired
    public HybridRateLimitService(
            LocalQuotaManager localQuotaManager,
            RedisQuotaManager redisQuotaManager) {

        this.localQuotaManager = localQuotaManager;
        this.redisQuotaManager = redisQuotaManager;

        // Configure circuit breaker for Redis failures
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .slidingWindowSize(100)
                .minimumNumberOfCalls(10)
                .permittedNumberOfCallsInHalfOpenState(5)
                .build();

        CircuitBreakerRegistry registry = CircuitBreakerRegistry.of(config);
        this.circuitBreaker = registry.circuitBreaker("redis");

        circuitBreaker.getEventPublisher()
                .onStateTransition(event ->
                    log.warn("Circuit breaker state changed: {} -> {}",
                            event.getStateTransition().getFromState(),
                            event.getStateTransition().getToState())
                );
    }

    /**
     * Main entry point: Check if request is allowed.
     *
     * PERFORMANCE:
     * - 90% requests: <0.1ms (pure local)
     * - 10% requests: 1-2ms (Redis sync)
     */
    public boolean allowRequest(String tenantId) {
        // Fast path: Try local cache first
        if (localQuotaManager.tryConsumeLocal(tenantId, 1)) {
            return true;
        }

        log.debug("Local quota exhausted for tenant: {}", tenantId);

        if (localQuotaManager.needsSync(tenantId, syncThreshold)) {
            return syncAndConsume(tenantId);
        }

        return false;
    }

    /**
     * Sync with Redis and allocate new chunk.
     */
    private boolean syncAndConsume(String tenantId) {
        try {
            return circuitBreaker.executeSupplier(() -> {
                long tenantLimit = redisQuotaManager.getTenantLimit(tenantId);
                long chunkSize = (tenantLimit * chunkPercent) / 100;
                chunkSize = Math.max(chunkSize, 10);

                log.info("Requesting chunk allocation for tenant: {}", tenantId);

                long allocated = redisQuotaManager.allocateQuota(tenantId, chunkSize);

                if (allocated > 0) {
                    localQuotaManager.allocateQuota(tenantId, allocated);
                    log.info("Allocated {} tokens for tenant: {}", allocated, tenantId);
                    return localQuotaManager.tryConsumeLocal(tenantId, 1);
                } else {
                    log.warn("Tenant {} is globally rate limited", tenantId);
                    return false;
                }
            });

        } catch (Exception e) {
            log.error("Failed to sync with Redis for tenant: {}", tenantId, e);
            return handleDegradedMode(tenantId);
        }
    }

    /**
     * Handle requests when Redis is unavailable.
     */
    private boolean handleDegradedMode(String tenantId) {
        log.warn("Entering degraded mode for tenant: {}", tenantId);

        LocalQuotaManager.LocalQuota quota = localQuotaManager.getQuota(tenantId);

        if (quota == null) {
            log.warn("No local quota for tenant: {}, denying", tenantId);
            return false;
        }

        long allocated = quota.getAllocated();
        long consumed = quota.getConsumed();
        long degradedLimit = (allocated * degradedPercent) / 100;

        if (consumed < degradedLimit) {
            return localQuotaManager.tryConsumeLocal(tenantId, 1);
        }

        return false;
    }

    public RateLimitStatus getStatus(String tenantId) {
        LocalQuotaManager.LocalQuota localQuota = localQuotaManager.getQuota(tenantId);
        RedisQuotaManager.QuotaStats redisStats = redisQuotaManager.getQuotaStats(tenantId);
        boolean redisHealthy = redisQuotaManager.isHealthy();

        return new RateLimitStatus(
                tenantId,
                localQuota != null ? localQuota.getAvailable() : 0,
                localQuota != null ? localQuota.getConsumed() : 0,
                redisStats.consumed(),
                redisStats.limit(),
                redisHealthy,
                circuitBreaker.getState().toString()
        );
    }

    public void resetTenant(String tenantId) {
        localQuotaManager.resetQuota(tenantId);
        redisQuotaManager.resetQuota(tenantId);
        log.info("Reset rate limit for tenant: {}", tenantId);
    }

    @PreDestroy
    public void shutdown() {
        log.warn("Pod shutting down, flushing local cache");
        localQuotaManager.invalidateAll();
    }

    public record RateLimitStatus(
            String tenantId,
            long localAvailable,
            long localConsumed,
            long globalConsumed,
            long globalLimit,
            boolean redisHealthy,
            String circuitBreakerState
    ) {}
}

