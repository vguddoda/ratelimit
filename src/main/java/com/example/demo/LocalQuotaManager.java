package com.example.demo;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe local quota manager using Caffeine cache.
 * Handles high-concurrency scenarios (5k+ simultaneous requests).
 */
@Component
public class LocalQuotaManager {

    private static final Logger log = LoggerFactory.getLogger(LocalQuotaManager.class);

    private final Cache<String, LocalQuota> quotaCache;
    private final ConcurrentHashMap<String, ReentrantLock> tenantLocks;

    public LocalQuotaManager() {
        this.quotaCache = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(Duration.ofMinutes(2))
                .recordStats()
                .build();

        this.tenantLocks = new ConcurrentHashMap<>();
    }

    /**
     * Try to consume tokens from local cache (thread-safe).
     */
    public boolean tryConsumeLocal(String tenantId, long tokens) {
        LocalQuota quota = quotaCache.getIfPresent(tenantId);

        if (quota == null) {
            return false;
        }

        // CAS loop: Lock-free atomic decrement
        while (true) {
            long current = quota.available.get();

            if (current < tokens) {
                log.debug("Local quota exhausted for tenant: {}", tenantId);
                return false;
            }

            if (quota.available.compareAndSet(current, current - tokens)) {
                quota.consumed.addAndGet(tokens);
                log.trace("Consumed {} tokens for tenant: {}", tokens, tenantId);
                return true;
            }
        }
    }

    /**
     * Allocate new quota chunk to local cache.
     */
    public void allocateQuota(String tenantId, long tokens) {
        ReentrantLock lock = tenantLocks.computeIfAbsent(tenantId, k -> new ReentrantLock());

        lock.lock();
        try {
            LocalQuota existing = quotaCache.getIfPresent(tenantId);

            if (existing != null) {
                existing.available.addAndGet(tokens);
                existing.allocated.addAndGet(tokens);
                log.info("Added {} tokens to tenant: {}", tokens, tenantId);
            } else {
                LocalQuota newQuota = new LocalQuota(tokens);
                quotaCache.put(tenantId, newQuota);
                log.info("Allocated new quota for tenant: {}", tenantId);
            }
        } finally {
            lock.unlock();
        }
    }

    public LocalQuota getQuota(String tenantId) {
        return quotaCache.getIfPresent(tenantId);
    }

    /**
     * Check if tenant needs sync with Redis.
     */
    public boolean needsSync(String tenantId, int syncThresholdPercent) {
        LocalQuota quota = quotaCache.getIfPresent(tenantId);

        if (quota == null) {
            return true;
        }

        long allocated = quota.allocated.get();
        long consumed = quota.consumed.get();
        long threshold = (allocated * syncThresholdPercent) / 100;

        return consumed >= threshold || quota.available.get() < 10;
    }

    public void resetQuota(String tenantId) {
        quotaCache.invalidate(tenantId);
        log.info("Reset local quota for tenant: {}", tenantId);
    }

    public void invalidateAll() {
        quotaCache.invalidateAll();
        log.warn("Invalidated all local quotas");
    }

    /**
     * Local quota holder with atomic counters.
     */
    public static class LocalQuota {
        public final AtomicLong allocated;
        public final AtomicLong consumed;
        public final AtomicLong available;
        public final AtomicLong lastSyncTime;

        public LocalQuota(long initialTokens) {
            this.allocated = new AtomicLong(initialTokens);
            this.consumed = new AtomicLong(0);
            this.available = new AtomicLong(initialTokens);
            this.lastSyncTime = new AtomicLong(System.currentTimeMillis());
        }

        public long getAvailable() {
            return available.get();
        }

        public long getConsumed() {
            return consumed.get();
        }

        public long getAllocated() {
            return allocated.get();
        }
    }
}

