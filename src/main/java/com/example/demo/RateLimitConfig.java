package com.example.demo;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.Bucket4j;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Refill;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class RateLimitConfig {

    /**
     * Configures a Bucket4j bucket with a capacity of 5 tokens that refills at a rate of 5 tokens per minute.
     * Each token represents the capacity to handle one request.
     */
    @Bean
    public Bucket bucket() {
        // Define the bandwidth with a limit of 5 tokens, refilled every minute
        Bandwidth limit = Bandwidth.classic(5, Refill.greedy(5, Duration.ofMinutes(1)));
        return Bucket4j.builder().addLimit(limit).build();
    }
}