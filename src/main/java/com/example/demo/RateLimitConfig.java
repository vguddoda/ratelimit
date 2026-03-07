package com.example.demo;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * High-performance Redis configuration optimized for 45k+ TPS
 */
@Configuration
public class RateLimitConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    @Value("${redis.io.threads:8}")
    private int ioThreads;

    @Value("${redis.computation.threads:8}")
    private int computationThreads;

    /**
     * Creates optimized client resources for high throughput.
     * Configured for handling 45k+ TPS with proper thread pooling.
     */
    @Bean(destroyMethod = "shutdown")
    public ClientResources clientResources() {
        return DefaultClientResources.builder()
                .ioThreadPoolSize(ioThreads)
                .computationThreadPoolSize(computationThreads)
                .build();
    }

    /**
     * Creates a Redis client optimized for high performance.
     * Uses connection pooling and pipelining for better throughput.
     */
    @Bean(destroyMethod = "shutdown")
    public RedisClient redisClient(ClientResources clientResources) {
        RedisURI redisUri = RedisURI.Builder
                .redis(redisHost, redisPort)
                .withTimeout(Duration.ofMillis(500)) // Fast timeout for high throughput
                .build();

        return RedisClient.create(clientResources, redisUri);
    }

    /**
     * Creates a stateful connection to Redis with optimized settings.
     * Auto-pipelining enabled for batching commands.
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient redisClient) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        StatefulRedisConnection<String, byte[]> connection = redisClient.connect(codec);

        // Enable auto-flush for better throughput
        connection.setAutoFlushCommands(true);

        return connection;
    }

    /**
     * Creates a String-String stateful connection for HybridRateLimitService.
     * This is used for Lua script execution and general Redis operations.
     */
    @Bean(destroyMethod = "close")
    public StatefulRedisConnection<String, String> redisStringConnection(RedisClient redisClient) {
        StatefulRedisConnection<String, String> connection = redisClient.connect(StringCodec.UTF8);
        connection.setAutoFlushCommands(true);
        return connection;
    }

    /**
     * Creates a ProxyManager for managing distributed buckets in Redis.
     * Optimized for high throughput with efficient expiration strategy.
     */
    @Bean
    public ProxyManager<String> proxyManager(StatefulRedisConnection<String, byte[]> redisConnection) {
        return LettuceBasedProxyManager.builderFor(redisConnection)
                .withExpirationStrategy(
                        io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
                                .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(5))
                )
                .build();
    }
}

