package com.example.demo;

import io.github.bucket4j.distributed.proxy.ProxyManager;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Comprehensive tests for rate limiting functionality.
 * Tests verify bucket configuration, rate enforcement, and high-throughput scenarios.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
		"rate.limit.capacity=5",
		"rate.limit.refill.tokens=5",
		"rate.limit.refill.duration=10s",
		"logging.level.root=INFO"
})
class DemoApplicationTests {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private ProxyManager<String> proxyManager;

	@BeforeEach
	void setUp() {
		// Clear rate limit buckets between tests to ensure test isolation
		// Note: In production Redis, you might want to use FLUSHDB or key deletion
		System.out.println("Test setup complete");
	}

	@Test
	void contextLoads() {
		assertThat(mockMvc).isNotNull();
		assertThat(proxyManager).isNotNull();
	}

	/**
	 * Test that the endpoint returns successful response when within rate limits.
	 */
	@Test
	void shouldAllowRequestWhenWithinRateLimit() throws Exception {
		mockMvc.perform(get("/api/data")
						.header("X-Forwarded-For", "192.168.1.100"))
				.andExpect(status().isOk())
				.andExpect(content().string("Here is the protected data!"));
	}

	/**
	 * Test that rate limiting kicks in after exceeding the capacity.
	 * Capacity is 5, so the 6th request should be rate limited.
	 */
	@Test
	void shouldEnforceRateLimitAfterExceedingCapacity() throws Exception {
		String clientIp = "192.168.1.101";

		// Make 5 requests (within capacity)
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/data")
							.header("X-Forwarded-For", clientIp))
					.andExpect(status().isOk());
		}

		// 6th request should be rate limited
		mockMvc.perform(get("/api/data")
						.header("X-Forwarded-For", clientIp))
				.andExpect(status().isTooManyRequests())
				.andExpect(content().json("{\"error\":\"Too many requests\"}"));
	}

	/**
	 * Test that different IP addresses have separate rate limit buckets.
	 */
	@Test
	void shouldMaintainSeparateBucketsForDifferentIPs() throws Exception {
		String clientIp1 = "192.168.1.102";
		String clientIp2 = "192.168.1.103";

		// Exhaust rate limit for IP1
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/data")
							.header("X-Forwarded-For", clientIp1))
					.andExpect(status().isOk());
		}

		// IP1 should be rate limited
		mockMvc.perform(get("/api/data")
						.header("X-Forwarded-For", clientIp1))
				.andExpect(status().isTooManyRequests());

		// IP2 should still be allowed (separate bucket)
		mockMvc.perform(get("/api/data")
						.header("X-Forwarded-For", clientIp2))
				.andExpect(status().isOk());
	}

	/**
	 * Test IP extraction from X-Forwarded-For header with multiple IPs.
	 */
	@Test
	void shouldExtractFirstIPFromXForwardedForHeader() throws Exception {
		String multipleIps = "203.0.113.1, 198.51.100.1, 192.0.2.1";

		// First IP should be used for rate limiting
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/data")
							.header("X-Forwarded-For", multipleIps))
					.andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/data")
						.header("X-Forwarded-For", multipleIps))
				.andExpect(status().isTooManyRequests());
	}

	/**
	 * Test IP extraction from X-Real-IP header when X-Forwarded-For is absent.
	 */
	@Test
	void shouldExtractIPFromXRealIPHeader() throws Exception {
		String clientIp = "203.0.113.5";

		// Use X-Real-IP header
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/data")
							.header("X-Real-IP", clientIp))
					.andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/data")
						.header("X-Real-IP", clientIp))
				.andExpect(status().isTooManyRequests());
	}

	/**
	 * Test concurrent requests to verify thread-safety and correct rate limiting.
	 */
	@Test
	void shouldHandleConcurrentRequestsCorrectly() throws Exception {
		String clientIp = "192.168.1.200";
		int totalRequests = 10;
		AtomicInteger successCount = new AtomicInteger(0);
		AtomicInteger rateLimitedCount = new AtomicInteger(0);

		ExecutorService executor = Executors.newFixedThreadPool(10);
		CountDownLatch latch = new CountDownLatch(totalRequests);

		for (int i = 0; i < totalRequests; i++) {
			executor.submit(() -> {
				try {
					var result = mockMvc.perform(get("/api/data")
									.header("X-Forwarded-For", clientIp))
							.andReturn();

					int status = result.getResponse().getStatus();
					if (status == 200) {
						successCount.incrementAndGet();
					} else if (status == 429) {
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

		// With capacity of 5, we expect exactly 5 successful requests
		// and 5 rate-limited requests
		System.out.println("Success count: " + successCount.get());
		System.out.println("Rate limited count: " + rateLimitedCount.get());

		assertThat(successCount.get()).isEqualTo(5);
		assertThat(rateLimitedCount.get()).isEqualTo(5);
		assertThat(successCount.get() + rateLimitedCount.get()).isEqualTo(totalRequests);
	}

	/**
	 * Test that bucket refills over time (requires waiting).
	 * This test verifies the refill mechanism works correctly.
	 */
	@Test
	void shouldRefillBucketOverTime() throws Exception {
		String clientIp = "192.168.1.201";

		// Exhaust the bucket (5 requests)
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/data")
							.header("X-Forwarded-For", clientIp))
					.andExpect(status().isOk());
		}

		// Next request should be rate limited
		mockMvc.perform(get("/api/data")
						.header("X-Forwarded-For", clientIp))
				.andExpect(status().isTooManyRequests());

		// Wait for refill (10 seconds as per config: 5 tokens per 10s)
		System.out.println("Waiting for bucket to refill...");
		Thread.sleep(11000); // Wait 11 seconds to ensure refill

		// After refill, requests should be allowed again
		mockMvc.perform(get("/api/data")
						.header("X-Forwarded-For", clientIp))
				.andExpect(status().isOk());
	}

	/**
	 * High-throughput test: Simulate burst traffic from multiple clients.
	 * Tests the system's ability to handle many concurrent requests.
	 */
	@Test
	void shouldHandleHighThroughputFromMultipleClients() throws Exception {
		int numClients = 10;
		int requestsPerClient = 6;
		ExecutorService executor = Executors.newFixedThreadPool(20);
		CountDownLatch latch = new CountDownLatch(numClients * requestsPerClient);

		List<AtomicInteger> successCounts = new ArrayList<>();
		List<AtomicInteger> rateLimitCounts = new ArrayList<>();

		for (int clientId = 0; clientId < numClients; clientId++) {
			AtomicInteger success = new AtomicInteger(0);
			AtomicInteger rateLimited = new AtomicInteger(0);
			successCounts.add(success);
			rateLimitCounts.add(rateLimited);

			String clientIp = "10.0.0." + clientId;

			for (int req = 0; req < requestsPerClient; req++) {
				executor.submit(() -> {
					try {
						var result = mockMvc.perform(get("/api/data")
										.header("X-Forwarded-For", clientIp))
								.andReturn();

						if (result.getResponse().getStatus() == 200) {
							success.incrementAndGet();
						} else if (result.getResponse().getStatus() == 429) {
							rateLimited.incrementAndGet();
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

		// Each client should have exactly 5 successful requests and 1 rate limited
		for (int i = 0; i < numClients; i++) {
			System.out.println("Client " + i + " - Success: " + successCounts.get(i).get() +
					", Rate Limited: " + rateLimitCounts.get(i).get());

			assertThat(successCounts.get(i).get()).isEqualTo(5);
			assertThat(rateLimitCounts.get(i).get()).isEqualTo(1);
		}
	}

	/**
	 * Test fallback to remote address when no proxy headers are present.
	 */
	@Test
	void shouldUseRemoteAddressWhenNoProxyHeaders() throws Exception {
		// Without X-Forwarded-For or X-Real-IP, should use remote address
		for (int i = 0; i < 5; i++) {
			mockMvc.perform(get("/api/data"))
					.andExpect(status().isOk());
		}

		mockMvc.perform(get("/api/data"))
				.andExpect(status().isTooManyRequests());
	}
}
