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

@Component
public class RateLimitingFilter implements Filter {

    private final ProxyManager<String> proxyManager;

    @Value("${rate.limit.capacity:5}")
    private int capacity;

    @Value("${rate.limit.refill.tokens:5}")
    private int refillTokens;

    @Value("${rate.limit.refill.duration:1m}")
    private String refillDuration;

    @Autowired
    public RateLimitingFilter(ProxyManager<String> proxyManager) {
        this.proxyManager = proxyManager;
    }

    /**
     * Intercepts incoming requests and applies rate limiting per client IP.
     * If a token is available, the request is processed; otherwise, a 429 status code is returned.
     */
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        String clientIp = getClientIp(httpRequest);

        // Create a unique key for this client
        String key = "rate_limit:" + clientIp;

        // Get or create a bucket for this client from Redis
        Supplier<BucketConfiguration> configSupplier = getConfigSupplier();
        var bucket = proxyManager.builder().build(key, configSupplier);

        if (bucket.tryConsume(1)) {
            chain.doFilter(request, response); // Forward the request if rate limiting is not hit
        } else {
            HttpServletResponse httpResponse = (HttpServletResponse) response;
            httpResponse.setStatus(429); // Return 429 if rate limit is exceeded
            httpResponse.setContentType("application/json");
            httpResponse.getWriter().write("{\"error\": \"Too many requests. Please try again later.\"}");
        }
    }

    /**
     * Extracts the client IP address from the request, considering proxy headers.
     */
    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }

    /**
     * Creates the bucket configuration supplier with the defined rate limits.
     */
    private Supplier<BucketConfiguration> getConfigSupplier() {
        return () -> {
            Duration duration = parseDuration(refillDuration);
            Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(refillTokens, duration));
            return BucketConfiguration.builder()
                    .addLimit(limit)
                    .build();
        };
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
        return Duration.ofMinutes(1); // default to 1 minute
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        // Initialization code if needed
    }

    @Override
    public void destroy() {
        // Cleanup code if needed
    }
}

