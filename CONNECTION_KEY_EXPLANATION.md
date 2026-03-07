# Why Connection Object is Used as Map Key

## The Question
> "The key here is connection object in map? Is it right?"

## ✅ YES! Here's Why

```java
private final ConcurrentHashMap<Connection, PooledConnection> leasedConnections;
//                              ↑ KEY        ↑ VALUE
```

---

## The Problem It Solves

### What Happens When Thread Closes Connection?

```java
// User code (thread has ConnectionProxy)
Connection conn = pool.getConnection();
// ... use connection ...
conn.close();  // ← How does pool find the PooledConnection?
```

**The pool needs to:**
1. Find the corresponding `PooledConnection` (has metadata)
2. Check lease time (for leak detection)
3. Return connection to available queue

**But the thread only has the `Connection` object!**

---

## Visual Flow

```
┌─────────────────────────────────────────────────────────────┐
│  Step 1: Thread Gets Connection                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  pool.getConnection()                                        │
│      ↓                                                       │
│  Creates PooledConnection:                                   │
│  ┌──────────────────────────────────────┐                  │
│  │ PooledConnection                     │                  │
│  │  - rawConnection: Connection@ABC123  │ ← JDBC connection│
│  │  - leaseTime: 1234567890             │                  │
│  │  - stackTrace: Exception(...)        │                  │
│  └──────────────────────────────────────┘                  │
│                                                              │
│  Adds to leased map:                                         │
│  leasedConnections.put(Connection@ABC123, PooledConnection) │
│                         ↑ KEY             ↑ VALUE           │
│                                                              │
│  Returns to thread:                                          │
│  ConnectionProxy → wraps PooledConnection                    │
│  Thread receives: Connection object                          │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Step 2: Thread Uses Connection                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  Thread has: Connection@ABC123 (via ConnectionProxy)        │
│                                                              │
│  PreparedStatement stmt = conn.prepareStatement("...");     │
│  ResultSet rs = stmt.executeQuery();                        │
│  // ... business logic ...                                   │
│                                                              │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│  Step 3: Thread Closes Connection                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  conn.close()  ← Thread calls on Connection@ABC123         │
│      ↓                                                       │
│  ConnectionProxy.close() intercepts                          │
│      ↓                                                       │
│  pool.returnConnection(pooledConnection)                     │
│      ↓                                                       │
│  Connection rawConn = pooledConn.getRawConnection();        │
│  // rawConn = Connection@ABC123                             │
│      ↓                                                       │
│  leasedConnections.remove(rawConn);  ← LOOKUP BY KEY!      │
│  // Uses Connection@ABC123 to find PooledConnection         │
│      ↓                                                       │
│  Now we have PooledConnection with all metadata!            │
│  - Check lease time → leak detection                        │
│  - Return to availableConnections queue                     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Why Connection Object Works as Key

### 1. **Identity-Based Lookup**

```java
Connection conn1 = DriverManager.getConnection(url);
Connection conn2 = DriverManager.getConnection(url);

// These are DIFFERENT objects
conn1 != conn2  // true

// Map uses object identity (reference equality)
Map<Connection, PooledConnection> map = new HashMap<>();
map.put(conn1, pooled1);
map.put(conn2, pooled2);

map.get(conn1) == pooled1  // true ✓
map.get(conn2) == pooled2  // true ✓
```

Each Connection object is unique, so it's a perfect key!

### 2. **Available During close()**

```java
class ConnectionProxy implements Connection {
    private final PooledConnection pooledConnection;
    
    @Override
    public void close() throws SQLException {
        // We have access to:
        Connection rawConn = pooledConnection.getRawConnection();
        
        // Can use it to lookup in map:
        pool.returnConnection(pooledConnection);
    }
}
```

When `close()` is called, we have the raw Connection object available for lookup.

---

## Alternative Approaches (Why They Don't Work)

### ❌ Option 1: Use PooledConnection as Key

```java
// Bad idea!
Map<PooledConnection, SomeMetadata> map = new HashMap<>();

// Problem: Thread doesn't have PooledConnection
Connection conn = pool.getConnection();
conn.close();  // ← How to get PooledConnection to look up in map?
```

**Doesn't work** because thread receives Connection, not PooledConnection.

### ❌ Option 2: Use Thread ID as Key

```java
// Bad idea!
Map<Long, PooledConnection> map = new HashMap<>();
map.put(Thread.currentThread().getId(), pooledConn);

// Problem: Same thread can get multiple connections
Connection c1 = pool.getConnection();
Connection c2 = pool.getConnection();
// Both map to same thread ID - COLLISION!
```

**Doesn't work** because one thread can hold multiple connections.

### ✅ Option 3: Use Connection as Key (Current Implementation)

```java
// Perfect!
Map<Connection, PooledConnection> map = new HashMap<>();

// When getting connection:
Connection rawConn = pooledConn.getRawConnection();
map.put(rawConn, pooledConn);  // Store mapping

// When closing connection (we have rawConn):
map.remove(rawConn);  // Easy lookup!
```

**Works perfectly** because:
- Each Connection is unique
- We have Connection object when closing
- Direct O(1) lookup

---

## The Complete Picture

```
┌─────────────────────────────────────────────────────────────┐
│  Data Structures Working Together                            │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  availableConnections (Queue):                               │
│  [PooledConnection1, PooledConnection2, PooledConnection3]  │
│   └─ Contains: Idle connections ready to be borrowed        │
│                                                              │
│  leasedConnections (Map):                                   │
│  {                                                           │
│    Connection@ABC123 → PooledConnection1,                   │
│    Connection@DEF456 → PooledConnection4,                   │
│    Connection@GHI789 → PooledConnection5                    │
│  }                                                           │
│   └─ Key: Raw JDBC Connection object (for O(1) lookup)     │
│   └─ Value: PooledConnection wrapper (has metadata)        │
│                                                              │
│  Why this design?                                            │
│  • availableConnections: Fast poll/add operations          │
│  • leasedConnections: Fast lookup when closing             │
│  • Connection as key: Thread has it during close()         │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

---

## Code Walkthrough

### When Connection Leased:

```java
public Connection getConnection(long timeoutMs) throws SQLException {
    // ... acquire semaphore ...
    
    PooledConnection pooledConn = getAvailableConnection();
    
    // Mark as leased
    pooledConn.lease();
    
    // ADD TO MAP - Connection as key!
    leasedConnections.put(
        pooledConn.getRawConnection(),  // ← Key: Connection object
        pooledConn                      // ← Value: Full metadata
    );
    
    return new ConnectionProxy(pooledConn, this);
}
```

### When Connection Returned:

```java
void returnConnection(PooledConnection pooledConn) {
    // GET CONNECTION OBJECT (the key)
    Connection rawConn = pooledConn.getRawConnection();
    
    // REMOVE FROM MAP using Connection as key
    leasedConnections.remove(rawConn);
    //                        ↑
    //                        O(1) lookup!
    
    // Check lease time for leak detection
    long leaseTime = System.currentTimeMillis() - pooledConn.getLeaseTime();
    if (leaseTime > leakDetectionThresholdMs) {
        System.err.println("⚠️  Connection leak detected!");
        pooledConn.getLeaseStackTrace().printStackTrace();
    }
    
    // Return to pool
    availableConnections.add(pooledConn);
    semaphore.release();
}
```

---

## Performance Implications

```
HashMap Lookup: O(1) average case

Operation: leasedConnections.remove(connection)
Time: ~10-50 nanoseconds

If we had to search through all connections:
Time: O(n) = up to 10 connections * 100ns = 1000ns

Using Connection as key: 20x-100x faster!
```

---

## Summary

### ✅ YES, Connection Object is the Right Key!

**Reasons:**
1. ✅ **Unique identity** - Each Connection object is unique
2. ✅ **Available during close()** - Thread has it when returning
3. ✅ **O(1) lookup** - Fast map operations
4. ✅ **Natural mapping** - Connection → PooledConnection
5. ✅ **Simple code** - No complex logic needed

**Alternative approaches don't work:**
- ❌ PooledConnection as key - Thread doesn't have it
- ❌ Thread ID as key - One thread can have multiple connections
- ❌ Sequential ID as key - Need to pass ID around

**The Connection object is the perfect key because it's the "handle" the thread has, and it uniquely identifies the borrowed connection.** 🎯

---

## How HashMap Lookup Works with Connection Objects

### The Question
> "So get on map using connection, how does this work in HashMap?"

### The Answer: HashMap Internal Mechanics

When you call `leasedConnections.get(connection)`, here's what happens inside HashMap:

```
┌─────────────────────────────────────────────────────────────┐
│  HashMap Structure                                           │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  HashMap is an array of "buckets":                          │
│                                                              │
│  buckets[0]  → Entry → Entry → null                        │
│  buckets[1]  → null                                         │
│  buckets[2]  → Entry → null                                 │
│  buckets[3]  → null                                         │
│  ...                                                         │
│  buckets[15] → Entry → Entry → Entry → null                │
│                                                              │
│  Each Entry contains: (key, value, next)                    │
└─────────────────────────────────────────────────────────────┘
```

### Step-by-Step: put(connection, pooledConnection)

```java
leasedConnections.put(conn, pooledConn);

// Step 1: Calculate hash code
int hash = conn.hashCode();
// Example: hash = 1845094589

// Step 2: Calculate bucket index
int bucketIndex = hash & (buckets.length - 1);
// Example: 1845094589 & 15 = 13
// (buckets.length = 16 for small maps)

// Step 3: Store in bucket
buckets[13] = new Entry(conn, pooledConn, buckets[13]);
//             Key: Connection@ABC123
//             Value: PooledConnection{leaseTime=..., stackTrace=...}
//             Next: previous entry in this bucket (if any)
```

### Step-by-Step: get(connection)

```java
PooledConnection pooled = leasedConnections.get(conn);

// Step 1: Calculate hash code (same as put)
int hash = conn.hashCode();
// Example: hash = 1845094589

// Step 2: Calculate bucket index (same as put)
int bucketIndex = hash & (buckets.length - 1);
// Example: bucketIndex = 13

// Step 3: Search bucket for matching key
Entry e = buckets[13];
while (e != null) {
    // Check if this entry's key matches
    if (e.hash == hash && (e.key == conn || e.key.equals(conn))) {
        return e.value;  // ← Found it! Return PooledConnection
    }
    e = e.next;  // Check next entry in bucket
}
return null;  // Not found
```

### Visual Example

```
Before: leasedConnections is empty

buckets[0]  → null
buckets[1]  → null
...
buckets[13] → null
...
buckets[15] → null


Thread-1 gets connection:
leasedConnections.put(Connection@ABC123, PooledConnection1)

1. hash = Connection@ABC123.hashCode() = 1845094589
2. bucket = 1845094589 & 15 = 13
3. Store:

buckets[0]  → null
buckets[1]  → null
...
buckets[13] → Entry {
                key: Connection@ABC123
                value: PooledConnection1
                next: null
              }
...
buckets[15] → null


Thread-2 gets connection:
leasedConnections.put(Connection@DEF456, PooledConnection2)

1. hash = Connection@DEF456.hashCode() = 987654321
2. bucket = 987654321 & 15 = 1
3. Store:

buckets[0]  → null
buckets[1]  → Entry {
                key: Connection@DEF456
                value: PooledConnection2
                next: null
              }
...
buckets[13] → Entry {
                key: Connection@ABC123
                value: PooledConnection1
                next: null
              }
...
buckets[15] → null


Thread-1 closes connection:
leasedConnections.get(Connection@ABC123)

1. hash = Connection@ABC123.hashCode() = 1845094589
2. bucket = 1845094589 & 15 = 13
3. Search bucket 13:
   - Entry.key == Connection@ABC123? YES! ✓
   - Return: PooledConnection1
   
4. Now we have the PooledConnection with all metadata!
   - Check leaseTime for leak detection
   - Get stackTrace for debugging
   - Return to available queue
```

### Why Connection Object Works Perfectly as Key

#### 1. **Consistent Hash Code**

```java
Connection conn = DriverManager.getConnection(url);

int hash1 = conn.hashCode();
int hash2 = conn.hashCode();

// ALWAYS the same for the same object
hash1 == hash2  // true ✓

// This means:
// - Always maps to SAME bucket
// - Consistent lookup every time
```

#### 2. **Identity-Based Equality**

```java
Connection conn1 = createConnection();
Connection conn2 = createConnection();

// Different objects
conn1 == conn2  // false

// HashMap uses == (identity) for comparison
map.put(conn1, metadata1);
map.put(conn2, metadata2);

map.get(conn1) == metadata1  // true ✓ (correct metadata)
map.get(conn2) == metadata2  // true ✓ (correct metadata)
```

Each Connection object is unique, so no collisions!

#### 3. **Fast Comparison**

```java
// Inside HashMap.get()
if (e.key == conn) {  // ← Identity comparison
    return e.value;   // Just pointer comparison!
}

// Versus string comparison:
if (e.key.equals(conn)) {  // Slower, involves method call
    return e.value;
}
```

Identity check (`==`) is fastest possible comparison:
- Just compares memory addresses
- No method call overhead
- ~1 nanosecond

### Performance Breakdown

```
┌─────────────────────────────────────────────────────────────┐
│  HashMap Operations with Connection Key                      │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  put(connection, pooledConnection):                          │
│    1. conn.hashCode()         ~5ns                          │
│    2. Calculate bucket index   ~3ns                          │
│    3. Store entry              ~5ns                          │
│    ────────────────────────────────                         │
│    Total:                     ~13ns                          │
│                                                              │
│  get(connection):                                            │
│    1. conn.hashCode()         ~5ns                          │
│    2. Calculate bucket index   ~3ns                          │
│    3. Find entry (key == conn) ~5ns                         │
│    4. Return value             ~2ns                          │
│    ────────────────────────────────                         │
│    Total:                     ~15ns                          │
│                                                              │
│  remove(connection):                                         │
│    1. conn.hashCode()         ~5ns                          │
│    2. Calculate bucket index   ~3ns                          │
│    3. Find and remove entry    ~8ns                          │
│    ────────────────────────────────                         │
│    Total:                     ~16ns                          │
│                                                              │
│  Time Complexity: O(1) average case                         │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### What If We Used a List Instead?

```java
// BAD: Using ArrayList instead of HashMap
List<Entry> leasedConnections = new ArrayList<>();

// put(connection, pooledConnection)
leasedConnections.add(new Entry(connection, pooledConnection));
// O(1) - fast

// get(connection)
for (Entry e : leasedConnections) {
    if (e.key == connection) {
        return e.value;  // Found it!
    }
}
// O(n) - SLOW! Need to check every entry
// 10 connections * 50ns = 500ns
// 30x SLOWER than HashMap!

// remove(connection)
for (int i = 0; i < leasedConnections.size(); i++) {
    if (leasedConnections.get(i).key == connection) {
        leasedConnections.remove(i);
        break;
    }
}
// O(n) - SLOW!
```

### Real-World Example

```java
// Connection pool with 10 max connections
ProductionConnectionPool pool = new ProductionConnectionPool.Builder()
    .maxConnections(10)
    .build();

// Internal map:
Map<Connection, PooledConnection> leasedConnections = new ConcurrentHashMap<>();

// Thread-1: Get connection
Connection c1 = pool.getConnection();
// → leasedConnections.put(c1, pooled1)  ~15ns

// Thread-2: Get connection
Connection c2 = pool.getConnection();
// → leasedConnections.put(c2, pooled2)  ~15ns

// ... 8 more threads get connections ...

// Now: leasedConnections has 10 entries

// Thread-1: Close connection
c1.close();
// → PooledConnection pooled = leasedConnections.get(c1)  ~15ns ✓
// → Check lease time
// → leasedConnections.remove(c1)  ~16ns ✓
// → Return to pool
// Total: ~31ns

// With ArrayList:
// → Linear search through 10 entries: ~500ns
// → 16x SLOWER!
```

### Summary

**How HashMap lookup works with Connection:**

1. ✅ **Hash Code** - Connection.hashCode() gives consistent value
2. ✅ **Bucket Index** - hash & (buckets-1) maps to array index
3. ✅ **Identity Check** - key == connection (pointer comparison)
4. ✅ **Return Value** - Get PooledConnection with metadata

**Why it's fast:**
- O(1) time complexity
- ~15 nanoseconds per lookup
- No iteration needed
- Just array access + pointer comparison

**Why Connection is perfect key:**
- Unique (each Connection object different)
- Consistent hash code (always same for same object)
- Fast equality check (identity-based)
- Available when needed (thread has it during close())

---

## Runnable Demo

You can run `HashMapLookupDemo.java` to see all this in action:

```bash
./mvnw exec:java \
  -Dexec.mainClass="com.example.demo.HashMapLookupDemo" \
  -Dexec.classpathScope=test
```

**Output shows:**
- Basic HashMap operations
- Identity vs equality
- Internal mechanics (hash, bucket, lookup)
- Real connection pool scenario
- Performance analysis

---

End of Explanation

