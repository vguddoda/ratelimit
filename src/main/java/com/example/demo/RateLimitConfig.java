package com.example.demo;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.distributed.remote.RemoteBucketState;
import io.github.bucket4j.redis.lettuce.cas.LettuceBasedProxyManager;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.codec.RedisCodec;
import io.lettuce.core.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    @Value("${spring.data.redis.host:localhost}")
    private String redisHost;

    @Value("${spring.data.redis.port:6379}")
    private int redisPort;

    /**
     * Creates a Redis client for connecting to Redis server.
     */
    @Bean
    public RedisClient redisClient() {
        RedisURI redisUri = RedisURI.Builder
                .redis(redisHost, redisPort)
                .build();
        return RedisClient.create(redisUri);
    }

    /**
     * Creates a stateful connection to Redis.
     */
    @Bean
    public StatefulRedisConnection<String, byte[]> redisConnection(RedisClient redisClient) {
        RedisCodec<String, byte[]> codec = RedisCodec.of(StringCodec.UTF8, ByteArrayCodec.INSTANCE);
        return redisClient.connect(codec);
    }

    /**
     * Creates a ProxyManager for managing distributed buckets in Redis.
     * This allows rate limiting to work across multiple application instances.
     */
    @Bean
    public ProxyManager<String> proxyManager(StatefulRedisConnection<String, byte[]> redisConnection) {
        return LettuceBasedProxyManager.builderFor(redisConnection)
                .withExpirationStrategy(
                        io.github.bucket4j.distributed.ExpirationAfterWriteStrategy
                                .basedOnTimeForRefillingBucketUpToMax(Duration.ofMinutes(10))
                )
                .build();
    }
}

