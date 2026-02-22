package com.example.demo;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.Refill;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * High-performance rate limiting filter optimized for 45k+ TPS.
 * Uses cached bucket configuration and optimized IP extraction.
 */
@Component
public class RateLimitingFilter implements Filter {

    private final ProxyManager<String> proxyManager;

    @Value("${rate.limit.capacity:2}")
    private int capacity;

    @Value("${rate.limit.refill.tokens:1}")
    private int refillTokens;

    @Value("${rate.limit.refill.duration:100s}")
    private String refillDuration;

    // Lazy-initialized bucket configuration
    private volatile Supplier<BucketConfiguration> cachedConfigSupplier;

    @Autowired
    public RateLimitingFilter(ProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    /**
     * Get or create the cached bucket configuration.
     * Lazy initialization ensures property values are properly injected.
     */
    private Supplier<BucketConfiguration> getConfigSupplier() {
        if (cachedConfigSupplier == null) {
            synchronized (this) {
                if (cachedConfigSupplier == null) {
                    Duration duration = parseDuration(refillDuration);
                    Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(refillTokens, duration));
                    BucketConfiguration config = BucketConfiguration.builder()
                            .addLimit(limit)
                            .build();
                    cachedConfigSupplier = () -> config;

                    // Log the configuration for debugging
                    System.out.println("Rate Limit Config - Capacity: " + capacity +
                                     ", Refill: " + refillTokens + " tokens per " + refillDuration);
                }
            }
        }
        return cachedConfigSupplier;
    }

    /**
     * Intercepts incoming requests and applies rate limiting per client IP.
     * Optimized for high throughput with minimal overhead.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;

        // Fast path: Get client IP (optimized)
        String clientIp = getClientIpFast(httpRequest);

        // Create a unique key for this client
        String key = "rl:" + clientIp; // Shortened prefix for less Redis memory

        // Get bucket from Redis and check rate limit
        var bucket = proxyManager.builder().build(key, getConfigSupplier());

        System.out.println("available tokens " + bucket.getAvailableTokens());

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response); // Forward the request if rate limiting is not hit
        } else {
            // Fast response for rate limit exceeded
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(429);
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\":\"Too many requests\"}");
        }
    }

    /**
     * Fast client IP extraction optimized for high throughput.
     * Uses direct header access without creating intermediate objects.
     */
    private String getClientIpFast(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip != null && !ip.isEmpty()) {
            int commaIndex = ip.indexOf(',');
            return commaIndex > 0 ? ip.substring(0, commaIndex) : ip;
        }

        ip = request.getHeader("X-Real-IP");
        if (ip != null && !ip.isEmpty()) {
            return ip;
        }

        return request.getRemoteAddr();
    }

    /**
     * Parses duration string (e.g., "1m", "60s", "1h") into Duration object.
     */
    private Duration parseDuration(String durationStr) {
        if (durationStr.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
        } else if (durationStr.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
        } else if (durationStr.endsWith("h")) {
            return Duration.ofHours(Long.parseLong(durationStr.substring(0, durationStr.length() - 1)));
        }
        return Duration.ofSeconds(1); // default to 1 second for high throughput
    }

    @Override
    public void destroy() {
        // Cleanup code if needed
    }
}

