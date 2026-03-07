package com.example.demo;

import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Redis-based global quota manager using Lua scripts for atomicity.
 */
@Component
public class RedisQuotaManager {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaManager.class);

    private final StatefulRedisConnection<String, String> redisConnection;

    @Value("${rate.limit.window.duration:60}")
    private long windowDurationSeconds;

    @Value("${rate.limit.default.limit:10000}")
    private long defaultLimit;

    private static final String ALLOCATE_QUOTA_SCRIPT = """
            local key = KEYS[1]
            local limit = tonumber(ARGV[1])
            local requested = tonumber(ARGV[2])
            local window_start = tonumber(ARGV[3])
            
            local consumed = tonumber(redis.call('HGET', key, 'consumed') or '0')
            local stored_window = tonumber(redis.call('HGET', key, 'window_start') or '0')
            
            if stored_window ~= window_start then
                consumed = 0
                redis.call('HSET', key, 'window_start', window_start)
                redis.call('HSET', key, 'consumed', 0)
            end
            
            local available = limit - consumed
            
            if available <= 0 then
                return 0
            end
            
            local allocated = math.min(requested, available)
            redis.call('HINCRBY', key, 'consumed', allocated)
            redis.call('HSET', key, 'limit', limit)
            redis.call('EXPIRE', key, ARGV[4])
            
            return allocated
            """;

    private String allocateScriptSha;

    @Autowired
    public RedisQuotaManager(StatefulRedisConnection<String, String> redisConnection) {
        this.redisConnection = redisConnection;
        loadScripts();
    }

    private void loadScripts() {
        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            allocateScriptSha = commands.scriptLoad(ALLOCATE_QUOTA_SCRIPT);
            log.info("Loaded Redis Lua script: {}", allocateScriptSha);
        } catch (Exception e) {
            log.error("Failed to load Lua scripts", e);
        }
    }

    /**
     * Allocate quota chunk from Redis (atomic operation).
     */
    public long allocateQuota(String tenantId, long requestedTokens) {
        try {
            long limit = getTenantLimit(tenantId);
            long windowStart = getCurrentWindow();
            String key = getQuotaKey(tenantId, windowStart);

            RedisCommands<String, String> commands = redisConnection.sync();

            // evalsha expects: scriptSha, outputType, keys[], values[]
            String[] keys = new String[]{key};
            String[] values = new String[]{
                String.valueOf(limit),
                String.valueOf(requestedTokens),
                String.valueOf(windowStart),
                String.valueOf(windowDurationSeconds * 2)
            };

            Long result = commands.evalsha(
                    allocateScriptSha,
                    ScriptOutputType.INTEGER,
                    keys,
                    values
            );

            long allocated = result != null ? result : 0L;
            log.info("Allocated {} tokens for tenant: {}", allocated, tenantId);

            return allocated;

        } catch (Exception e) {
            log.error("Failed to allocate quota for tenant: {}", tenantId, e);
            return 0;
        }
    }

    /**
     * Get current time window ID (using Redis time for consistency).
     */
    public long getCurrentWindow() {
        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            Object timeResult = commands.time();

            if (timeResult instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<?> timeList = (java.util.List<?>) timeResult;
                String secondsStr = timeList.get(0).toString();
                long seconds = Long.parseLong(secondsStr);
                return seconds / windowDurationSeconds;
            }

            return System.currentTimeMillis() / 1000 / windowDurationSeconds;

        } catch (Exception e) {
            log.error("Failed to get Redis time", e);
            return System.currentTimeMillis() / 1000 / windowDurationSeconds;
        }
    }

    public long getTenantLimit(String tenantId) {
        return defaultLimit;
    }

    private String getQuotaKey(String tenantId, long window) {
        return String.format("quota:%s:%d", tenantId, window);
    }

    public void resetQuota(String tenantId) {
        try {
            long windowStart = getCurrentWindow();
            String key = getQuotaKey(tenantId, windowStart);
            RedisCommands<String, String> commands = redisConnection.sync();
            commands.del(key);
            log.info("Reset quota for tenant: {}", tenantId);
        } catch (Exception e) {
            log.error("Failed to reset quota for tenant: {}", tenantId, e);
        }
    }

    public boolean isHealthy() {
        try {
            RedisCommands<String, String> commands = redisConnection.sync();
            String pong = commands.ping();
            return "PONG".equals(pong);
        } catch (Exception e) {
            log.error("Redis health check failed", e);
            return false;
        }
    }

    public QuotaStats getQuotaStats(String tenantId) {
        try {
            long limit = getTenantLimit(tenantId);
            long windowStart = getCurrentWindow();
            String key = getQuotaKey(tenantId, windowStart);

            RedisCommands<String, String> commands = redisConnection.sync();

            String consumedStr = commands.hget(key, "consumed");

            long consumed = consumedStr != null ? Long.parseLong(consumedStr) : 0;

            return new QuotaStats(tenantId, limit, consumed, 0, limit - consumed);

        } catch (Exception e) {
            log.error("Failed to get stats for tenant: {}", tenantId, e);
            return new QuotaStats(tenantId, 0, 0, 0, 0);
        }
    }

    public record QuotaStats(
            String tenantId,
            long limit,
            long consumed,
            long allocated,
            long remaining
    ) {}
}

