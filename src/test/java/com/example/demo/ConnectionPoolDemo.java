package com.example.demo;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Demo application showing production connection pool usage.
 */
public class ConnectionPoolDemo {

    public static void main(String[] args) throws Exception {
        System.out.println("═".repeat(70));
        System.out.println("PRODUCTION CONNECTION POOL DEMO");
        System.out.println("═".repeat(70));

        // Create connection pool using builder
        ProductionConnectionPool pool = new ProductionConnectionPool.Builder()
            .jdbcUrl("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1")  // In-memory H2 database
            .minConnections(5)
            .maxConnections(10)
            .connectionTimeoutMs(5000)
            .idleTimeoutMs(60000)
            .maxLifetimeMs(300000)
            .leakDetectionThresholdMs(10000)
            .testOnBorrow(true)
            .validationQuery("SELECT 1")
            .build();

        // Setup test database
        setupDatabase(pool);

        System.out.println("\n" + "─".repeat(70));
        System.out.println("Test 1: Normal Operations (50 threads, 10 connections)");
        System.out.println("─".repeat(70));
        testNormalOperations(pool, 50);

        Thread.sleep(2000);

        System.out.println("\n" + "─".repeat(70));
        System.out.println("Test 2: Pool Exhaustion (20 threads, 10 connections, slow queries)");
        System.out.println("─".repeat(70));
        testPoolExhaustion(pool, 20);

        Thread.sleep(2000);

        System.out.println("\n" + "─".repeat(70));
        System.out.println("Test 3: Connection Leak Detection");
        System.out.println("─".repeat(70));
        testConnectionLeak(pool);

        Thread.sleep(2000);

        // Print final statistics
        System.out.println("\n" + "═".repeat(70));
        System.out.println("FINAL STATISTICS");
        System.out.println("═".repeat(70));
        printDetailedStats(pool);

        // Shutdown
        pool.shutdown();

        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEMO COMPLETE");
        System.out.println("═".repeat(70));
    }

    private static void setupDatabase(ProductionConnectionPool pool) throws Exception {
        try (Connection conn = pool.getConnection()) {
            var stmt = conn.createStatement();

            // Create test table
            stmt.execute("CREATE TABLE IF NOT EXISTS users (" +
                "id INT PRIMARY KEY, " +
                "name VARCHAR(100), " +
                "email VARCHAR(100))");

            // Insert test data
            stmt.execute("DELETE FROM users");
            for (int i = 1; i <= 100; i++) {
                stmt.execute("INSERT INTO users VALUES (" + i + ", " +
                    "'User" + i + "', 'user" + i + "@example.com')");
            }

            System.out.println("[Setup] Created test database with 100 users");
        }
    }

    private static void testNormalOperations(ProductionConnectionPool pool, int numThreads)
            throws InterruptedException {

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    // Get connection
                    Connection conn = pool.getConnection();

                    // Execute query
                    PreparedStatement stmt = conn.prepareStatement(
                        "SELECT * FROM users WHERE id = ?"
                    );
                    stmt.setInt(1, threadId % 100 + 1);
                    ResultSet rs = stmt.executeQuery();

                    if (rs.next()) {
                        String name = rs.getString("name");
                        // Simulate processing
                        Thread.sleep(100);
                    }

                    rs.close();
                    stmt.close();

                    // Return connection to pool
                    conn.close();

                    System.out.println("[Thread-" + threadId + "] Query executed successfully");

                } catch (Exception e) {
                    System.err.println("[Thread-" + threadId + "] Error: " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        long duration = System.currentTimeMillis() - startTime;
        System.out.println("\n✓ Test completed in " + duration + "ms");
        System.out.println("  Throughput: " + (numThreads * 1000 / duration) + " ops/sec");
    }

    private static void testPoolExhaustion(ProductionConnectionPool pool, int numThreads)
            throws InterruptedException {

        ExecutorService executor = Executors.newFixedThreadPool(numThreads);

        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    System.out.println("[Thread-" + threadId + "] Requesting connection...");

                    // Get connection (will block if pool exhausted)
                    Connection conn = pool.getConnection(2000); // 2 second timeout

                    System.out.println("[Thread-" + threadId + "] Got connection!");

                    // Simulate long-running query
                    Thread.sleep(500);

                    // Return connection
                    conn.close();

                    System.out.println("[Thread-" + threadId + "] Released connection");

                } catch (Exception e) {
                    System.err.println("[Thread-" + threadId + "] ⚠️  " + e.getMessage());
                }
            });
        }

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);

        System.out.println("\n✓ Pool exhaustion test completed");
    }

    private static void testConnectionLeak(ProductionConnectionPool pool) {
        System.out.println("[Test] Acquiring connection and NOT releasing it...");

        try {
            Connection conn = pool.getConnection();

            // Execute query
            var stmt = conn.createStatement();
            var rs = stmt.executeQuery("SELECT COUNT(*) FROM users");
            rs.next();
            int count = rs.getInt(1);

            System.out.println("[Test] Query returned " + count + " users");

            // Simulate work
            Thread.sleep(12000); // Hold for 12 seconds (leak threshold is 10 seconds)

            // Now close (leak will be detected)
            conn.close();

            System.out.println("[Test] Connection closed (after leak threshold)");

        } catch (Exception e) {
            System.err.println("[Test] Error: " + e.getMessage());
        }
    }

    private static void printDetailedStats(ProductionConnectionPool pool) {
        var stats = pool.getStats();

        System.out.println("Pool Configuration:");
        System.out.println("  Max connections: " + stats.totalConnections());
        System.out.println();

        System.out.println("Current State:");
        System.out.println("  Active connections: " + stats.activeConnections());
        System.out.println("  Available connections: " + stats.availableConnections());
        System.out.println("  Waiting threads: " + stats.waitingThreads());
        System.out.println();

        System.out.println("Lifetime Metrics:");
        System.out.println("  Total connections created: " + stats.totalCreated());
        System.out.println("  Total connections destroyed: " + stats.totalDestroyed());
        System.out.println("  Total requests: " + stats.totalRequests());
        System.out.println("  Total timeouts: " + stats.totalWaits());
        System.out.println("  Connection leaks detected: " + stats.totalLeaks());
        System.out.println();

        double utilization = stats.totalConnections() > 0 ?
            (double) stats.activeConnections() / stats.totalConnections() * 100 : 0;
        System.out.println("Pool Utilization: " + String.format("%.1f%%", utilization));
    }
}

