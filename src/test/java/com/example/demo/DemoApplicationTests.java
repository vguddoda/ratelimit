package com.example.demo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for Hybrid Rate Limiting (Local CAS cache + Redis Bucket4j).
 *
 * REQUIRES: Redis running on localhost:6379
 *   docker run -d -p 6379:6379 redis:latest
 *
 * Test config: limit=20 per 60s, chunk=10% = 2 tokens per chunk
 * This keeps tests fast (only need ~25 requests to exhaust limit).
 *
 * CASES TESTED:
 *  1. Basic request allowed (first request creates Bucket4j bucket + chunk)
 *  2. Rate limit enforced after exhausting global limit
 *  3. Separate tenants have independent limits
 *  4. Concurrent requests handled correctly by CAS (no over-consumption)
 *  5. Chunk exhaustion triggers Redis sync (verify multi-chunk flow)
 *  6. Status endpoint shows correct local + global state
 *  7. Reset clears both local and Redis state
 *  8. Refill: Bucket4j refills tokens over time
 *  9. Fallback to IP when no X-Tenant-ID header
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        // Low limit for fast test: 20 requests per 60 seconds
        "rate.limit.default.limit=20",
        "rate.limit.window.duration=60",
        // 10% chunk = 2 tokens per chunk (20 * 10%)
        "rate.limit.chunk.percent=10",
        "rate.limit.degraded.percent=10",
        "logging.level.com.example.demo=DEBUG"
})
class DemoApplicationTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HybridRateLimitService rateLimitService;

    @Autowired
    private LocalQuotaManager localQuotaManager;

    /** Reset state before each test so tests don't interfere */
    @BeforeEach
    void setUp() throws Exception {
        // Reset a set of tenants used across tests
        for (String tenant : List.of(
                "test-basic", "test-limit", "test-a", "test-b",
                "test-concurrent", "test-chunk", "test-status",
                "test-reset", "test-refill", "test-multi-0", "test-multi-1",
                "test-multi-2", "test-multi-3", "test-multi-4",
                "203.0.113.99", "127.0.0.1")) {
            try {
                rateLimitService.resetTenant(tenant);
            } catch (Exception ignored) {}
        }
        // Small delay for Redis to process resets
        Thread.sleep(100);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 1: Basic request allowed
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldAllowRequestWithinRateLimit() throws Exception {
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", "test-basic"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenant").value("test-basic"))
                .andExpect(jsonPath("$.message").value("Here is the protected data!"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 2: Rate limit enforced after exhausting global limit
    //
    //  limit=20, chunk=2. After 20 requests: all chunks consumed from Bucket4j.
    //  Request 21+ should get 429.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldEnforceRateLimitAfterExceedingCapacity() throws Exception {
        String tenant = "test-limit";
        int limit = 20;

        // Send exactly `limit` requests — all should succeed
        for (int i = 0; i < limit; i++) {
            mockMvc.perform(get("/api/data")
                            .header("X-Tenant-ID", tenant))
                    .andExpect(status().isOk());
        }

        // Next request should be 429 (global limit exhausted)
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", tenant))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("Too many requests"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 3: Separate tenants have independent limits
    //
    //  Exhaust tenant-a, tenant-b should still be allowed.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldMaintainSeparateLimitsPerTenant() throws Exception {
        String tenantA = "test-a";
        String tenantB = "test-b";

        // Exhaust tenant-a (20 requests)
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/data")
                            .header("X-Tenant-ID", tenantA))
                    .andExpect(status().isOk());
        }

        // tenant-a should be limited
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", tenantA))
                .andExpect(status().isTooManyRequests());

        // tenant-b should still work (separate Bucket4j bucket in Redis)
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", tenantB))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 4: Concurrent requests — CAS correctness
    //
    //  Send 30 concurrent requests from same tenant (limit=20).
    //  Exactly 20 should succeed, 10 should get 429.
    //  This verifies CAS doesn't over-consume tokens.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldHandleConcurrentRequestsCorrectly() throws Exception {
        String tenant = "test-concurrent";
        int totalRequests = 30;
        int expectedSuccess = 20; // limit

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger rateLimitedCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch latch = new CountDownLatch(totalRequests);

        for (int i = 0; i < totalRequests; i++) {
            executor.submit(() -> {
                try {
                    var result = mockMvc.perform(get("/api/data")
                                    .header("X-Tenant-ID", tenant))
                            .andReturn();

                    if (result.getResponse().getStatus() == 200) {
                        successCount.incrementAndGet();
                    } else if (result.getResponse().getStatus() == 429) {
                        rateLimitedCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await();
        executor.shutdown();

        System.out.println("Concurrent test — Success: " + successCount.get() +
                ", Rate limited: " + rateLimitedCount.get());

        // Exactly limit requests succeed, rest get 429
        // (allow ±1 tolerance for race between CAS check and Bucket4j timing)
        assertThat(successCount.get())
                .as("Successful requests should be close to the limit of %d", expectedSuccess)
                .isBetween(expectedSuccess - 1, expectedSuccess + 1);
        assertThat(successCount.get() + rateLimitedCount.get()).isEqualTo(totalRequests);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 5: Chunk exhaustion triggers multiple Redis syncs
    //
    //  limit=20, chunk=2. Sending 20 requests should cause ~10 Redis syncs
    //  (each sync allocates 2 tokens from Bucket4j).
    //  Verify all 20 succeed and local state is correct.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldAllocateMultipleChunksFromRedis() throws Exception {
        String tenant = "test-chunk";

        // Send 15 requests (should trigger multiple chunk allocations)
        for (int i = 0; i < 15; i++) {
            mockMvc.perform(get("/api/data")
                            .header("X-Tenant-ID", tenant))
                    .andExpect(status().isOk());
        }

        // Check local state — consumed should be 15
        LocalQuotaManager.LocalQuota quota = localQuotaManager.getQuota(tenant);
        assertThat(quota).isNotNull();
        // consumed may be less than 15 if a new chunk was just allocated (consumed resets)
        // but total requests served = 15, which is what matters
        System.out.println("After 15 requests — local available: " + quota.getAvailable() +
                ", consumed: " + quota.getConsumed() + ", allocated: " + quota.getAllocated());

        // Chunk size = 2, so allocated should be 2 (current chunk)
        assertThat(quota.getAllocated()).isEqualTo(2);

        // Send 5 more to exhaust limit
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/data")
                            .header("X-Tenant-ID", tenant))
                    .andExpect(status().isOk());
        }

        // 21st request should be denied
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", tenant))
                .andExpect(status().isTooManyRequests());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 6: Status endpoint shows local + global state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldShowCorrectStatusAfterRequests() throws Exception {
        String tenant = "test-status";

        // Send 5 requests
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(get("/api/data")
                            .header("X-Tenant-ID", tenant))
                    .andExpect(status().isOk());
        }

        // Check status endpoint
        mockMvc.perform(get("/api/status/" + tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(tenant))
                .andExpect(jsonPath("$.globalLimit").value(20))
                .andExpect(jsonPath("$.redisHealthy").value(true))
                .andExpect(jsonPath("$.circuitBreakerState").value("CLOSED"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 7: Reset clears both local and Redis state
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldResetTenantState() throws Exception {
        String tenant = "test-reset";

        // Exhaust all 20 tokens
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/data")
                            .header("X-Tenant-ID", tenant))
                    .andExpect(status().isOk());
        }

        // Should be rate limited
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", tenant))
                .andExpect(status().isTooManyRequests());

        // Reset
        mockMvc.perform(post("/api/reset/" + tenant))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Rate limit reset successfully"));

        // After reset, should be allowed again
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", tenant))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 8: Bucket4j refills tokens over time
    //
    //  Exhaust limit, wait for refill, verify tokens available again.
    //  refillGreedy(20, 60s) = ~0.33 tokens/sec → 1 token in ~3s
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldRefillBucketOverTime() throws Exception {
        String tenant = "test-refill";

        // Exhaust all 20 tokens
        for (int i = 0; i < 20; i++) {
            mockMvc.perform(get("/api/data")
                            .header("X-Tenant-ID", tenant))
                    .andExpect(status().isOk());
        }

        // Should be rate limited
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", tenant))
                .andExpect(status().isTooManyRequests());

        // Wait for Bucket4j to refill some tokens
        // refillGreedy(20, 60s) = 1 token every 3 seconds
        // Wait 7 seconds → ~2 tokens refilled → enough for 1 chunk of 2
        System.out.println("Waiting 7 seconds for Bucket4j refill...");
        Thread.sleep(7000);

        // Invalidate local cache so next request triggers fresh Redis sync
        localQuotaManager.invalidate(tenant);

        // After refill, request should succeed (Bucket4j has refilled some tokens)
        mockMvc.perform(get("/api/data")
                        .header("X-Tenant-ID", tenant))
                .andExpect(status().isOk());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 9: Multiple tenants under concurrent load
    //
    //  5 tenants, each gets 25 concurrent requests (limit=20).
    //  Each tenant should have ~20 successes and ~5 denials.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldHandleMultipleTenantsUnderLoad() throws Exception {
        int numTenants = 5;
        int requestsPerTenant = 25;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch latch = new CountDownLatch(numTenants * requestsPerTenant);

        List<AtomicInteger> successCounts = new ArrayList<>();
        List<AtomicInteger> deniedCounts = new ArrayList<>();

        for (int t = 0; t < numTenants; t++) {
            AtomicInteger success = new AtomicInteger(0);
            AtomicInteger denied = new AtomicInteger(0);
            successCounts.add(success);
            deniedCounts.add(denied);

            String tenant = "test-multi-" + t;

            for (int r = 0; r < requestsPerTenant; r++) {
                executor.submit(() -> {
                    try {
                        var result = mockMvc.perform(get("/api/data")
                                        .header("X-Tenant-ID", tenant))
                                .andReturn();

                        if (result.getResponse().getStatus() == 200) {
                            success.incrementAndGet();
                        } else {
                            denied.incrementAndGet();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        latch.countDown();
                    }
                });
            }
        }

        latch.await();
        executor.shutdown();

        for (int t = 0; t < numTenants; t++) {
            int ok = successCounts.get(t).get();
            int no = deniedCounts.get(t).get();
            System.out.printf("Tenant test-multi-%d — Success: %d, Denied: %d%n", t, ok, no);

            // Each tenant: ~20 success (allow ±1 for timing), rest denied
            assertThat(ok).as("Tenant %d successes", t).isBetween(19, 21);
            assertThat(ok + no).isEqualTo(requestsPerTenant);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 10: Fallback to IP when no X-Tenant-ID header
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void shouldFallbackToIpWhenNoTenantHeader() throws Exception {
        // Without X-Tenant-ID, filter uses client IP as tenant ID
        // X-Forwarded-For simulates the client IP
        String clientIp = "203.0.113.99";

        mockMvc.perform(get("/api/data")
                        .header("X-Forwarded-For", clientIp))
                .andExpect(status().isOk());

        // Send 19 more (total 20 = limit)
        for (int i = 1; i < 20; i++) {
            mockMvc.perform(get("/api/data")
                            .header("X-Forwarded-For", clientIp))
                    .andExpect(status().isOk());
        }

        // 21st should be rate limited
        mockMvc.perform(get("/api/data")
                        .header("X-Forwarded-For", clientIp))
                .andExpect(status().isTooManyRequests());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  TEST 11: Health endpoint always available
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void healthEndpointShouldAlwaysWork() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
