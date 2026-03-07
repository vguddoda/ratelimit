# Production-Ready Connection Pool

## 🎯 Overview

A production-grade database connection pool implementation using **Semaphore** for connection limiting, with all the features you'd need in a real production system.

## ✨ Features

### Core Functionality
- ✅ **Semaphore-based limiting** - Fair queuing for connection requests
- ✅ **Connection validation** - Test connections before use
- ✅ **Connection proxy** - Intercepts close() to return to pool
- ✅ **Thread-safe** - Handles high concurrency

### Production Features
- ✅ **Min/Max connections** - Maintains minimum, grows to maximum
- ✅ **Idle timeout** - Removes connections idle too long
- ✅ **Max lifetime** - Refreshes connections periodically
- ✅ **Connection leak detection** - Warns if connections held too long
- ✅ **Graceful shutdown** - Closes all connections cleanly
- ✅ **Health monitoring** - Background maintenance tasks
- ✅ **Metrics collection** - Tracks usage statistics

## 📦 Files

```
src/test/java/com/example/demo/
├── ProductionConnectionPool.java  ← Production-ready pool
└── ConnectionPoolDemo.java        ← Demo application
```

## 🚀 Usage

### Basic Example

```java
// Create pool
ProductionConnectionPool pool = new ProductionConnectionPool.Builder()
    .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
    .username("user")
    .password("password")
    .minConnections(5)
    .maxConnections(10)
    .build();

// Use connection
try (Connection conn = pool.getConnection()) {
    PreparedStatement stmt = conn.prepareStatement("SELECT * FROM users");
    ResultSet rs = stmt.executeQuery();
    // Process results...
}  // Connection automatically returned to pool!

// Shutdown
pool.shutdown();
```

### Advanced Configuration

```java
ProductionConnectionPool pool = new ProductionConnectionPool.Builder()
    .jdbcUrl("jdbc:postgresql://localhost:5432/mydb")
    .username("dbuser")
    .password("dbpass")
    
    // Connection limits
    .minConnections(5)                    // Keep at least 5
    .maxConnections(20)                   // Allow up to 20
    
    // Timeouts
    .connectionTimeoutMs(30000)           // Wait max 30s for connection
    .idleTimeoutMs(600000)                // Remove idle after 10 min
    .maxLifetimeMs(1800000)               // Refresh after 30 min
    
    // Leak detection
    .leakDetectionThresholdMs(60000)      // Warn if held > 1 min
    
    // Validation
    .testOnBorrow(true)                   // Validate before use
    .validationQuery("SELECT 1")          // Query to test connection
    
    .build();
```

## 🏗️ Architecture

### How It Works

```
┌─────────────────────────────────────────────────────────┐
│  Thread Pool (100 threads)                               │
│  ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓ ↓                                   │
└─────────────────────────────────────────────────────────┘
           │
           ↓
┌─────────────────────────────────────────────────────────┐
│  Semaphore (permits = 10)                                │
│  • 10 threads can acquire simultaneously                 │
│  • Other threads BLOCK until permit available            │
└─────────────────────────────────────────────────────────┘
           │
           ↓
┌─────────────────────────────────────────────────────────┐
│  Available Connections Queue                             │
│  [C1] [C2] [C3] [C4] [C5] [C6] [C7] [C8] [C9] [C10]   │
└─────────────────────────────────────────────────────────┘
           │
           ↓
┌─────────────────────────────────────────────────────────┐
│  Active Connections (in use by threads)                  │
│  Thread-1 → C1                                           │
│  Thread-2 → C2                                           │
│  ...                                                      │
└─────────────────────────────────────────────────────────┘
```

### Connection Lifecycle

```
1. CREATE
   ├─ DriverManager.getConnection()
   ├─ Wrap in PooledConnection
   └─ Add to available queue

2. ACQUIRE (getConnection())
   ├─ semaphore.acquire() → BLOCKS if no permits
   ├─ Poll from available queue
   ├─ Validate connection (optional)
   ├─ Mark as leased
   └─ Return ConnectionProxy

3. USE
   ├─ Execute queries
   ├─ Transactions
   └─ Business logic

4. RETURN (close())
   ├─ ConnectionProxy.close() intercepted
   ├─ Check for leak (held too long?)
   ├─ Validate still usable
   ├─ Add back to available queue
   └─ semaphore.release() → WAKES waiting thread

5. DESTROY (if expired)
   ├─ Check idle timeout
   ├─ Check max lifetime
   ├─ connection.close() (real close)
   └─ Remove from pool
```

## 🔍 Key Components

### 1. Semaphore (Connection Limiting)

```java
private final Semaphore connectionSemaphore = new Semaphore(maxConnections, true);

// Acquire permit (blocks if none available)
connectionSemaphore.acquire();

// Release permit (wakes waiting thread)
connectionSemaphore.release();
```

**Why Semaphore?**
- Perfect for resource pooling (N connections → Semaphore(N))
- Automatic blocking when exhausted
- Automatic waking when available
- Fair queuing (FIFO)

### 2. PooledConnection (Metadata Wrapper)

```java
static class PooledConnection {
    private final Connection rawConnection;
    private final long creationTime;      // When created
    private volatile long lastUsedTime;   // Last returned to pool
    private volatile long leaseTime;      // When acquired
    private volatile Exception leaseStackTrace;  // For leak detection
}
```

Tracks:
- Creation time → Check max lifetime
- Last used time → Check idle timeout
- Lease time → Detect connection leaks
- Stack trace → Debug where leak occurred

### 3. ConnectionProxy (Intercept close())

```java
@Override
public void close() throws SQLException {
    // DON'T actually close!
    // Return to pool instead
    pool.returnConnection(pooledConnection);
}
```

**Why?**
- User calls `conn.close()` as normal
- We intercept and return to pool
- Real connection stays open (reused)
- Transparent to application code

### 4. Background Maintenance

```java
// Task 1: Remove expired connections (every 30 seconds)
maintenanceExecutor.scheduleAtFixedRate(() -> {
    removeExpiredConnections();
}, 30, 30, TimeUnit.SECONDS);

// Task 2: Ensure minimum connections (every 60 seconds)
maintenanceExecutor.scheduleAtFixedRate(() -> {
    ensureMinimumConnections();
}, 60, 60, TimeUnit.SECONDS);

// Task 3: Print statistics (every 60 seconds)
maintenanceExecutor.scheduleAtFixedRate(() -> {
    printStatistics();
}, 60, 60, TimeUnit.SECONDS);
```

## 📊 Metrics & Monitoring

### Available Metrics

```java
PoolStats stats = pool.getStats();

stats.totalConnections();      // Current total
stats.activeConnections();     // Currently in use
stats.availableConnections();  // Currently available
stats.waitingThreads();        // Threads blocked waiting

stats.totalCreated();          // Lifetime connections created
stats.totalDestroyed();        // Lifetime connections destroyed
stats.totalRequests();         // Total getConnection() calls
stats.totalWaits();            // Total connection timeouts
stats.totalLeaks();            // Detected connection leaks
```

### Example Output

```
[ConnectionPool] Statistics:
  Total: 10
  Active: 7
  Available: 3
  Waiting threads: 5
  Created: 15
  Destroyed: 5
  Requests: 1000
  Timeouts: 3
  Leaks detected: 1
```

## 🔥 Running The Demo

### Compile and Run

```bash
cd /Users/vishalkumarbg/Documents/Bheembali/ratelimit

# Add H2 database dependency to pom.xml (for demo)
# Or use your existing database

# Compile
./mvnw test-compile

# Run demo
./mvnw exec:java \
  -Dexec.mainClass="com.example.demo.ConnectionPoolDemo" \
  -Dexec.classpathScope=test
```

### Demo Tests

**Test 1: Normal Operations**
- 50 threads, 10 connections
- Each thread executes a query
- Shows connection reuse

**Test 2: Pool Exhaustion**
- 20 threads, 10 connections, slow queries
- Shows threads blocking when pool exhausted
- Shows threads waking up when connections released

**Test 3: Connection Leak Detection**
- Hold connection for 12 seconds
- Threshold is 10 seconds
- Shows leak warning with stack trace

## ⚠️ Connection Leak Detection

### How It Works

```java
// When connection acquired
pooledConn.lease();  // Records current time + stack trace

// When connection returned
long leaseTime = System.currentTimeMillis() - pooledConn.getLeaseTime();

if (leaseTime > leakDetectionThresholdMs) {
    System.err.println("⚠️  Connection leak detected!");
    System.err.println("Held for " + leaseTime + "ms");
    pooledConn.getLeaseStackTrace().printStackTrace();  // Shows where it was acquired!
}
```

### Example Leak Warning

```
[ConnectionPool] ⚠️  Connection leak detected!
Held for 12000ms (threshold: 10000ms)
[ConnectionPool] Leak stack trace:
java.lang.Exception: Connection leased from here
    at ProductionConnectionPool$PooledConnection.lease(...)
    at ProductionConnectionPool.getConnection(...)
    at ConnectionPoolDemo.testConnectionLeak(...)
    at ConnectionPoolDemo.main(...)
```

**Benefits:**
- Quickly identify code that doesn't release connections
- Stack trace shows EXACTLY where connection was acquired
- Helps prevent resource exhaustion in production

## 🎯 Production Checklist

### Before Going to Production

- [ ] **Tune pool size** based on load testing
  ```
  Rule of thumb: connections = (core_count * 2) + disk_count
  Example: 8 cores, 1 disk → 8*2 + 1 = 17 connections
  ```

- [ ] **Set appropriate timeouts**
  ```java
  .connectionTimeoutMs(5000)    // Fail fast
  .idleTimeoutMs(600000)        // 10 minutes
  .maxLifetimeMs(1800000)       // 30 minutes
  ```

- [ ] **Enable leak detection**
  ```java
  .leakDetectionThresholdMs(60000)  // 1 minute warning
  ```

- [ ] **Configure validation**
  ```java
  .testOnBorrow(true)
  .validationQuery("SELECT 1")  // Lightweight query
  ```

- [ ] **Monitor metrics**
  ```java
  // Expose via JMX, Prometheus, etc.
  PoolStats stats = pool.getStats();
  ```

- [ ] **Load test** with expected concurrency
  ```
  - Simulate peak load
  - Check for connection timeouts
  - Verify pool size is adequate
  ```

## 🆚 vs HikariCP

### When to Use This Implementation

✅ **Use This When:**
- Learning how connection pools work
- Need to understand semaphore-based pooling
- Want full control over implementation
- Educational purposes

✅ **Use HikariCP When:**
- Production application
- Need battle-tested implementation
- Want best performance (HikariCP is fastest)
- Need comprehensive features

### Feature Comparison

```
┌────────────────────────┬──────────────┬──────────────┐
│   Feature              │ This Pool    │ HikariCP     │
├────────────────────────┼──────────────┼──────────────┤
│ Semaphore-based        │ Yes          │ Yes          │
│ Connection validation  │ Yes          │ Yes          │
│ Leak detection         │ Yes          │ Yes          │
│ Fair queuing           │ Yes          │ Yes          │
│ Production-ready       │ Yes*         │ Yes          │
│ Battle-tested          │ No           │ Yes          │
│ Performance optimized  │ Good         │ Excellent    │
│ JMX monitoring         │ No           │ Yes          │
│ Statement caching      │ No           │ Yes          │
│ Documentation          │ This guide   │ Extensive    │
└────────────────────────┴──────────────┴──────────────┘

* With thorough testing
```

## 📚 Interview Topics Covered

✅ **Semaphore usage** - Connection limiting  
✅ **Connection pooling** - Resource reuse  
✅ **Thread blocking** - When pool exhausted  
✅ **Fair queuing** - FIFO ordering  
✅ **Leak detection** - Resource tracking  
✅ **Background maintenance** - ScheduledExecutorService  
✅ **Proxy pattern** - Intercept close()  
✅ **Builder pattern** - Configuration  
✅ **Metrics collection** - AtomicLong counters  
✅ **Graceful shutdown** - Cleanup

## 💡 Key Interview Answers

### Q: Why use Semaphore for connection pooling?

> "Semaphore is perfect for connection pooling because we have N database connections and need to limit concurrent access to N threads. Semaphore(N) naturally models this: acquire() gets a connection (blocks if none available), release() returns it (wakes waiting threads). The alternative would be manual locks and conditions, which is more complex and error-prone."

### Q: How do you prevent connection leaks?

> "We track when each connection is acquired (timestamp + stack trace). When returned, we check how long it was held. If it exceeds the threshold (e.g., 60 seconds), we log a warning with the stack trace showing WHERE it was acquired. This helps developers quickly identify code that doesn't properly release connections in finally blocks."

### Q: What happens when the pool is exhausted?

> "When all connections are in use, the next thread calling getConnection() will block at semaphore.acquire(). The thread enters WAITING state and releases the CPU. When another thread returns a connection, semaphore.release() wakes one waiting thread, which acquires the connection and continues. This prevents busy-waiting and conserves CPU."

## 🎓 What You Learned

✅ Production-ready connection pool implementation  
✅ Semaphore for resource limiting  
✅ Connection leak detection  
✅ Background maintenance tasks  
✅ Metrics and monitoring  
✅ Graceful shutdown  
✅ Complete proxy pattern usage  

**You now have production-quality code to show in interviews!** 🚀

