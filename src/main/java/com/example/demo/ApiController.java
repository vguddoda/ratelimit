package com.example.demo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * API endpoints with rate limiting and monitoring.
 *
 * ENDPOINTS:
 * - GET  /api/data           - Protected data endpoint (rate limited)
 * - GET  /api/status/:tenant - Get rate limit status for tenant
 * - POST /api/reset/:tenant  - Reset rate limit for tenant (testing)
 */
@RestController
@RequestMapping("/api")
public class ApiController {

    @Autowired
    private HybridRateLimitService rateLimitService;

    /**
     * Protected endpoint that is rate-limited.
     *
     * USAGE:
     * curl -H "X-Tenant-ID: tenant-123" http://localhost:8080/api/data
     */
    @GetMapping("/data")
    public ResponseEntity<Map<String, Object>> getData(
            @RequestHeader(value = "X-Tenant-ID", required = false) String tenantId) {

        return ResponseEntity.ok(Map.of(
                "message", "Here is the protected data!",
                "tenant", tenantId != null ? tenantId : "unknown",
                "timestamp", System.currentTimeMillis()
        ));
    }

    /**
     * Get rate limit status for a tenant.
     *
     * USAGE:
     * curl http://localhost:8080/api/status/tenant-123
     *
     * RESPONSE:
     * {
     *   "tenantId": "tenant-123",
     *   "localAvailable": 850,
     *   "localConsumed": 150,
     *   "globalConsumed": 2340,
     *   "globalLimit": 10000,
     *   "redisHealthy": true,
     *   "circuitBreakerState": "CLOSED"
     * }
     */
    @GetMapping("/status/{tenantId}")
    public ResponseEntity<HybridRateLimitService.RateLimitStatus> getStatus(
            @PathVariable String tenantId) {

        HybridRateLimitService.RateLimitStatus status = rateLimitService.getStatus(tenantId);
        return ResponseEntity.ok(status);
    }

    /**
     * Reset rate limit for a tenant (for testing).
     *
     * USAGE:
     * curl -X POST http://localhost:8080/api/reset/tenant-123
     */
    @PostMapping("/reset/{tenantId}")
    public ResponseEntity<Map<String, String>> resetTenant(
            @PathVariable String tenantId) {

        rateLimitService.resetTenant(tenantId);

        return ResponseEntity.ok(Map.of(
                "message", "Rate limit reset successfully",
                "tenant", tenantId
        ));
    }

    /**
     * Health check endpoint.
     *
     * USAGE:
     * curl http://localhost:8080/api/health
     */
    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "service", "rate-limit-service"
        ));
    }
}

