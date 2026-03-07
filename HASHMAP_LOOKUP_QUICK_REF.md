# HashMap Lookup with Connection Object - Quick Reference

## The Question
> "So get on map using connection, how does this work in HashMap?"

## ✅ Quick Answer

```java
leasedConnections.get(connection)  // How does this work?

// Step 1: Calculate hash code
int hash = connection.hashCode();  // Example: 1845094589

// Step 2: Find bucket
int bucket = hash & (size - 1);    // Example: 13

// Step 3: Search bucket
Entry e = buckets[13];
if (e.key == connection) {         // Identity check (==)
    return e.value;                // Return PooledConnection
}
```

**Time:** ~15 nanoseconds  
**Complexity:** O(1)

---

## Visual Flow

```
HashMap Internal Structure:
═══════════════════════════════════════════════

buckets[0]  → null
buckets[1]  → Entry(Connection@DEF456, PooledConnection2)
buckets[2]  → null
...
buckets[13] → Entry(Connection@ABC123, PooledConnection1)  ← Found here!
...
buckets[15] → null


Lookup Process:
═══════════════════════════════════════════════

1. Thread calls: leasedConnections.get(Connection@ABC123)

2. Calculate hash:
   hash = Connection@ABC123.hashCode()
   hash = 1845094589

3. Calculate bucket:
   bucket = hash & 15  (assuming 16 buckets)
   bucket = 13

4. Search bucket 13:
   Entry e = buckets[13]
   if (e.key == Connection@ABC123) {  ← YES! Match found
       return e.value  // PooledConnection1
   }

5. Return PooledConnection1 with metadata:
   - leaseTime: 1234567890
   - stackTrace: Exception("Leased from Thread-1...")

Total time: ~15 nanoseconds
```

---

## Why Connection Object Works

### 1. Consistent Hash Code
```java
Connection conn = createConnection();

conn.hashCode()  // 1845094589
conn.hashCode()  // 1845094589 (always same!)
conn.hashCode()  // 1845094589

// Always maps to SAME bucket → reliable lookup
```

### 2. Identity-Based Equality
```java
Connection conn1 = createConnection();
Connection conn2 = createConnection();

conn1 == conn2  // false (different objects)

// Each Connection is unique → no collisions
map.put(conn1, meta1);  // Bucket A
map.put(conn2, meta2);  // Bucket B (different)
```

### 3. Fast Comparison
```java
// Inside HashMap
if (e.key == connection) {  // Just pointer comparison!
    return e.value;         // ~1 nanosecond
}

// Versus
if (e.key.equals(connection)) {  // Method call overhead
    return e.value;              // ~10 nanoseconds
}
```

---

## Performance Comparison

```
┌──────────────────┬─────────────┬─────────────┐
│   Operation      │  HashMap    │  ArrayList  │
├──────────────────┼─────────────┼─────────────┤
│ get(connection)  │  ~15ns      │  ~500ns     │
│                  │  O(1)       │  O(n)       │
│                  │             │             │
│ put(connection)  │  ~13ns      │  ~5ns*      │
│                  │  O(1)       │  O(1)       │
│                  │             │             │
│ remove(conn)     │  ~16ns      │  ~500ns     │
│                  │  O(1)       │  O(n)       │
└──────────────────┴─────────────┴─────────────┘

* But get/remove are O(n) - dealbreaker!

HashMap is 30x FASTER for lookup!
```

---

## Real Connection Pool Example

```java
// Pool tracks leased connections
Map<Connection, PooledConnection> leasedConnections = new ConcurrentHashMap<>();

// Thread-1 gets connection
Connection c1 = pool.getConnection();
leasedConnections.put(c1, new PooledConnection(...));
// ~15ns

// Thread-1 closes connection
c1.close();

// ConnectionProxy needs to find metadata:
PooledConnection pooled = leasedConnections.get(c1);
// ~15ns - FAST lookup!

// Check for leak
long leaseTime = now - pooled.getLeaseTime();
if (leaseTime > threshold) {
    System.err.println("Leak detected!");
    pooled.getLeaseStackTrace().printStackTrace();
}

// Remove from map
leasedConnections.remove(c1);
// ~16ns

// Return to pool
availableConnections.add(pooled);
```

---

## Key Points

✅ **HashMap uses object identity** (==) for comparison  
✅ **Hash code is consistent** for same object  
✅ **O(1) lookup** - constant time regardless of map size  
✅ **~15 nanoseconds** - extremely fast  
✅ **Connection object is perfect key** - unique, available, fast  

---

## Run The Demo

```bash
./mvnw exec:java \
  -Dexec.mainClass="com.example.demo.HashMapLookupDemo" \
  -Dexec.classpathScope=test
```

**You'll see:**
- Basic HashMap operations
- Identity vs equality demonstration
- Internal mechanics visualization
- Real connection pool scenario
- Performance analysis

---

## Interview Answer Template

**Q: How does HashMap lookup work with Connection objects?**

> "When we call `map.get(connection)`, HashMap calculates the connection's hash code and uses it to find the bucket index (hash & size-1). Then it searches that bucket for an entry where `key == connection` using identity comparison. This is O(1) time complexity, taking about 15 nanoseconds. 
>
> Connection objects work perfectly as keys because each one is unique (different memory address), has a consistent hash code, and supports fast identity-based comparison. This is why our connection pool can efficiently track which PooledConnection corresponds to each raw Connection object for leak detection and proper return to the pool."

---

End of Quick Reference

