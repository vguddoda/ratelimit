package com.example.demo;

import java.util.HashMap;
import java.util.Map;

/**
 * Demonstration of how HashMap lookup works with Connection objects.
 *
 * This explains the internal mechanics of:
 * leasedConnections.get(connection)
 * leasedConnections.put(connection, pooledConnection)
 * leasedConnections.remove(connection)
 */
public class HashMapLookupDemo {

    public static void main(String[] args) {
        System.out.println("═".repeat(70));
        System.out.println("HOW HASHMAP LOOKUP WORKS WITH CONNECTION OBJECTS");
        System.out.println("═".repeat(70));

        demonstrateBasicHashMap();
        demonstrateIdentityVsEquality();
        demonstrateHashMapInternals();
        demonstrateConnectionPoolScenario();
        explainPerformance();
    }

    /**
     * Basic HashMap usage with objects.
     */
    static void demonstrateBasicHashMap() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("1. BASIC HASHMAP WITH OBJECTS");
        System.out.println("─".repeat(70));

        // Create mock connection objects
        MockConnection conn1 = new MockConnection("Connection-1");
        MockConnection conn2 = new MockConnection("Connection-2");
        MockConnection conn3 = new MockConnection("Connection-3");

        // Create map (like leasedConnections in pool)
        Map<MockConnection, String> map = new HashMap<>();

        // Put operations
        System.out.println("\nPutting connections into map:");
        map.put(conn1, "Metadata-1");
        System.out.println("  put(conn1, 'Metadata-1')");

        map.put(conn2, "Metadata-2");
        System.out.println("  put(conn2, 'Metadata-2')");

        map.put(conn3, "Metadata-3");
        System.out.println("  put(conn3, 'Metadata-3')");

        // Get operations
        System.out.println("\nGetting from map using connection object:");
        String meta1 = map.get(conn1);
        System.out.println("  get(conn1) → " + meta1);

        String meta2 = map.get(conn2);
        System.out.println("  get(conn2) → " + meta2);

        // Remove operation
        System.out.println("\nRemoving from map:");
        String removed = map.remove(conn1);
        System.out.println("  remove(conn1) → " + removed);
        System.out.println("  Map now has " + map.size() + " entries");

        System.out.println("\n✓ Key Point: Using the SAME object reference for lookup");
    }

    /**
     * Shows the difference between identity and equality.
     */
    static void demonstrateIdentityVsEquality() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("2. IDENTITY vs EQUALITY");
        System.out.println("─".repeat(70));

        MockConnection conn1 = new MockConnection("Connection-1");
        MockConnection conn1Copy = conn1;  // Same reference
        MockConnection conn1New = new MockConnection("Connection-1");  // Different object

        System.out.println("\nIdentity (==) checks object reference:");
        System.out.println("  conn1 == conn1Copy:  " + (conn1 == conn1Copy) + " ✓ (same reference)");
        System.out.println("  conn1 == conn1New:   " + (conn1 == conn1New) + " ✗ (different objects)");

        System.out.println("\nHashMap uses identity for lookup:");
        Map<MockConnection, String> map = new HashMap<>();
        map.put(conn1, "Data");

        System.out.println("  map.get(conn1):      " + map.get(conn1) + " ✓ (same object)");
        System.out.println("  map.get(conn1Copy):  " + map.get(conn1Copy) + " ✓ (same reference)");
        System.out.println("  map.get(conn1New):   " + map.get(conn1New) + " ✗ (different object)");

        System.out.println("\n✓ Key Point: HashMap finds entry using OBJECT IDENTITY");
    }

    /**
     * Shows how HashMap internally works.
     */
    static void demonstrateHashMapInternals() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("3. HASHMAP INTERNAL MECHANICS");
        System.out.println("─".repeat(70));

        MockConnection conn = new MockConnection("Connection-1");

        System.out.println("\nWhen you call map.put(conn, value):");
        System.out.println("  Step 1: Calculate hash code");
        int hashCode = conn.hashCode();
        System.out.println("          hashCode = " + hashCode);

        System.out.println("\n  Step 2: Calculate bucket index");
        int buckets = 16; // Default initial capacity
        int bucketIndex = hashCode & (buckets - 1);  // Same as hashCode % buckets
        System.out.println("          bucket = " + bucketIndex + " (hash & " + (buckets-1) + ")");

        System.out.println("\n  Step 3: Store entry in bucket");
        System.out.println("          buckets[" + bucketIndex + "] → Entry(key=conn, value=metadata)");

        System.out.println("\n\nWhen you call map.get(conn):");
        System.out.println("  Step 1: Calculate hash code of lookup key");
        System.out.println("          hashCode = " + hashCode);

        System.out.println("\n  Step 2: Calculate bucket index");
        System.out.println("          bucket = " + bucketIndex);

        System.out.println("\n  Step 3: Search bucket for matching key");
        System.out.println("          Check: key.equals(conn) or key == conn");

        System.out.println("\n  Step 4: Return value if found");
        System.out.println("          return entry.value");

        System.out.println("\n✓ Time Complexity: O(1) average case");
    }

    /**
     * Shows realistic connection pool scenario.
     */
    static void demonstrateConnectionPoolScenario() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("4. CONNECTION POOL SCENARIO");
        System.out.println("─".repeat(70));

        // Simulate connection pool
        Map<MockConnection, PooledConnectionMetadata> leasedConnections = new HashMap<>();

        // Thread-1 gets connection
        System.out.println("\nThread-1: getConnection()");
        MockConnection conn1 = new MockConnection("JDBC-Connection-1");
        PooledConnectionMetadata meta1 = new PooledConnectionMetadata(
            System.currentTimeMillis(),
            "Thread-1",
            new Exception("Leased from Thread-1")
        );

        leasedConnections.put(conn1, meta1);
        System.out.println("  Stored: " + conn1 + " → " + meta1);
        System.out.println("  Map size: " + leasedConnections.size());

        // Thread-2 gets connection
        System.out.println("\nThread-2: getConnection()");
        MockConnection conn2 = new MockConnection("JDBC-Connection-2");
        PooledConnectionMetadata meta2 = new PooledConnectionMetadata(
            System.currentTimeMillis(),
            "Thread-2",
            new Exception("Leased from Thread-2")
        );

        leasedConnections.put(conn2, meta2);
        System.out.println("  Stored: " + conn2 + " → " + meta2);
        System.out.println("  Map size: " + leasedConnections.size());

        // Thread-1 returns connection
        System.out.println("\nThread-1: conn.close()");
        System.out.println("  Looking up metadata using connection object...");

        // THIS IS THE KEY OPERATION!
        PooledConnectionMetadata found = leasedConnections.get(conn1);
        System.out.println("  Found: " + found);
        System.out.println("  Leased at: " + found.leaseTime);
        System.out.println("  Leased by: " + found.threadName);

        // Check lease time
        long leaseTime = System.currentTimeMillis() - found.leaseTime;
        System.out.println("  Held for: " + leaseTime + "ms");

        // Remove from map
        leasedConnections.remove(conn1);
        System.out.println("  Removed from leased map");
        System.out.println("  Map size: " + leasedConnections.size());

        System.out.println("\n✓ Key Point: Fast O(1) lookup using Connection object");
    }

    /**
     * Performance analysis.
     */
    static void explainPerformance() {
        System.out.println("\n" + "─".repeat(70));
        System.out.println("5. PERFORMANCE ANALYSIS");
        System.out.println("─".repeat(70));

        System.out.println("""
            
            HashMap Operations with Connection Key:
            ──────────────────────────────────────────
            
            put(connection, metadata):
              • Calculate hash:        ~5 nanoseconds
              • Find bucket:           ~5 nanoseconds
              • Store entry:           ~5 nanoseconds
              • Total:                 ~15 nanoseconds
            
            get(connection):
              • Calculate hash:        ~5 nanoseconds
              • Find bucket:           ~5 nanoseconds
              • Compare key:           ~5 nanoseconds
              • Return value:          ~5 nanoseconds
              • Total:                 ~20 nanoseconds
            
            remove(connection):
              • Calculate hash:        ~5 nanoseconds
              • Find bucket:           ~5 nanoseconds
              • Remove entry:          ~10 nanoseconds
              • Total:                 ~20 nanoseconds
            
            Why So Fast?
            ────────────
            1. Hash code is cached (conn.hashCode() called once)
            2. Bucket lookup is array index (O(1))
            3. Identity comparison is pointer equality (fastest)
            4. No iteration needed
            
            Alternative: Linear Search
            ──────────────────────────
            If we stored in ArrayList and searched:
              • Iterate through list:  O(n) = 10 connections * 50ns = 500ns
              • 25x SLOWER than HashMap!
            
            Conclusion:
            ───────────
            HashMap with Connection as key:
              ✓ O(1) time complexity
              ✓ ~20 nanosecond lookup
              ✓ Perfect for connection pool
            """);
    }

    // Mock classes for demonstration

    static class MockConnection {
        private final String id;
        private final int hashCode;  // Cached hash code

        public MockConnection(String id) {
            this.id = id;
            this.hashCode = System.identityHashCode(this);  // Based on memory address
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            // Default identity-based equality (this == obj)
            return this == obj;
        }

        @Override
        public String toString() {
            return id + "@" + Integer.toHexString(hashCode);
        }
    }

    static class PooledConnectionMetadata {
        final long leaseTime;
        final String threadName;
        final Exception stackTrace;

        public PooledConnectionMetadata(long leaseTime, String threadName, Exception stackTrace) {
            this.leaseTime = leaseTime;
            this.threadName = threadName;
            this.stackTrace = stackTrace;
        }

        @Override
        public String toString() {
            return "Metadata(leaseTime=" + leaseTime + ", thread=" + threadName + ")";
        }
    }
}

