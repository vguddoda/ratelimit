# Failure Scenarios & Recovery Strategies
## Interview Prep Material - Comprehensive Failure Handling

---

## Table of Contents
1. [Redis Failures](#redis-failures)
2. [Pod Failures](#pod-failures)
3. [Network Failures](#network-failures)
4. [Race Conditions](#race-conditions)
5. [Scale Operations](#scale-operations)
6. [Data Consistency Issues](#data-consistency-issues)
7. [Performance Degradation](#performance-degradation)
8. [Recovery Procedures](#recovery-procedures)

---

## Redis Failures

### Scenario 1: Redis Completely Down

**What Happens:**
```
Time 0:00 - Redis crashes
Time 0:01 - Rate limit service detects failure
Time 0:02 - Circuit breaker opens
Time 0:03 - System enters degraded mode
```

**Detection:**
```java
// Circuit breaker configuration
CircuitBreakerConfig.custom()
    .failureRateThreshold(50)           // Open if 50% failures
    .waitDurationInOpenState(Duration.ofSeconds(10))  // Wait 10s
    .slidingWindowSize(100)             // Track last 100 calls
    .build();
```

**Behavior in Degraded Mode:**

```java
private boolean handleDegradedMode(String tenantId) {
    LocalQuota quota = localQuotaManager.getQuota(tenantId);
    
    if (quota == null) {
        // STRATEGY 1: Fail Closed (Secure)
        // No local state → Deny all requests
        return false;
    }
    
    // STRATEGY 2: Degraded Allowance (Balanced)
    // Allow 10% of normal traffic using local state
    long degradedLimit = quota.getAllocated() * 0.1;
    return quota.getConsumed() < degradedLimit;
    
    // STRATEGY 3: Fail Open (Availability-first)
    // Allow all requests (comment out for production)
    // return true;
}
```

**Impact Analysis:**

| Metric | Normal | Degraded Mode | Impact |
|--------|--------|---------------|--------|
| Availability | 99.9% | 95% | Some 429s |
| Latency P99 | 2ms | 0.1ms | Faster (no Redis) |
| Protection | 100% | ~10% | Reduced protection |
| False positives | 0% | 5% | Some valid requests denied |

**Interview Answer:**
```
"When Redis fails, we use a circuit breaker to detect the failure quickly. 
The system enters degraded mode where:

1. Existing local quotas continue to work (no disruption for active tenants)
2. New tenants get limited quota (10% of normal) to prevent abuse
3. After 10 seconds, circuit breaker attempts recovery
4. This balances availability (system stays up) with security (some protection remains)

The decision depends on the use case:
- Payment API: Fail closed (deny all when Redis down)
- Public API: Degraded mode (allow limited traffic)
- Internal API: Fail open (trust internal clients)"
```

---

### Scenario 2: Redis Slow (High Latency)

**What Happens:**
```
Normal: Redis latency < 1ms
Degraded: Redis latency > 50ms
Crisis: Redis latency > 500ms
```

**Detection:**
```java
@Scheduled(fixedDelay = 5000)
public void monitorRedisLatency() {
    long start = System.nanoTime();
    redisConnection.sync().ping();
    long latency = (System.nanoTime() - start) / 1_000_000; // ms
    
    if (latency > 50) {
        log.warn("Redis latency high: {}ms", latency);
        metrics.recordHighLatency(latency);
    }
    
    if (latency > 500) {
        log.error("Redis latency critical: {}ms", latency);
        // Consider opening circuit breaker
        circuitBreaker.recordFailure();
    }
}
```

**Mitigation:**
```java
// Add timeout to Redis operations
RedisURI.Builder
    .redis(host, port)
    .withTimeout(Duration.ofMillis(100))  // Fail fast
    .build();

// Use async operations with timeout
CompletableFuture<Long> future = CompletableFuture.supplyAsync(() -> 
    redisQuotaManager.allocateQuota(tenantId, chunkSize)
);

try {
    return future.get(100, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    // Redis too slow, use local cache only
    return handleDegradedMode(tenantId);
}
```

**Root Causes & Solutions:**

| Cause | Symptom | Solution |
|-------|---------|----------|
| Network congestion | Variable latency | Use dedicated Redis subnet |
| Memory pressure | Sudden spike | Increase Redis memory, enable eviction |
| Too many keys | Slow commands | Use Redis Cluster, partition data |
| Long-running Lua scripts | Timeout | Optimize Lua scripts, add limits |
| CPU saturation | Consistent high latency | Scale Redis (read replicas) |

---

### Scenario 3: Redis Cluster Split-Brain

**What Happens:**
```
┌─────────────┐         ┌─────────────┐
│  Master A   │  ╳╳╳╳╳  │  Master B   │
│  (thinks    │         │  (thinks    │
│  it's       │         │  it's       │
│  master)    │         │  master)    │
└─────────────┘         └─────────────┘
      │                       │
      ├── Pod 1               ├── Pod 2
      └── Pod 2               └── Pod 3

Result: Different quotas in different partitions!
```

**Detection:**
```java
public boolean isRedisHealthy() {
    try {
        // Check cluster state
        String clusterInfo = redisConnection.sync()
            .clusterInfo()
            .toString();
        
        // Look for cluster_state:ok
        if (!clusterInfo.contains("cluster_state:ok")) {
            log.error("Redis cluster unhealthy: {}", clusterInfo);
            return false;
        }
        
        return true;
    } catch (Exception e) {
        return false;
    }
}
```

**Prevention:**
```yaml
# redis.conf
# Require majority consensus before accepting writes
min-replicas-to-write 2
min-replicas-max-lag 10

# Use Redis Sentinel for automatic failover
sentinel monitor mymaster 127.0.0.1 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000
```

**Recovery:**
```bash
# Manual intervention required
# 1. Identify true master
redis-cli -h master-a ROLE
# Returns: master, offset, replicas

# 2. Force replica to sync
redis-cli -h master-b REPLICAOF master-a 6379

# 3. Clear local caches (to force re-sync)
curl -X POST http://ratelimit-service:8080/api/admin/clear-cache
```

---

## Pod Failures

### Scenario 4: Pod Crash Mid-Request

**What Happens:**
```
Time 0:00 - Pod 1 handling 1000 req/sec for tenant-123
Time 0:01 - Pod 1 OOMKilled (out of memory)
Time 0:02 - K8s marks pod as NotReady
Time 0:03 - APISIX health check fails
Time 0:04 - APISIX removes pod from rotation
Time 0:05 - Consistent hash recalculates
Time 0:06 - tenant-123 traffic → Pod 2
```

**Data Loss:**
```
Pod 1 local cache (lost):
  tenant-123: available=850, consumed=150, allocated=1000

Redis global state (safe):
  tenant-123: consumed=7340, limit=10000

Outcome:
  Pod 2 will allocate fresh 1000 tokens
  Total allocated: 7340 + 1000 = 8340
  Lost tokens: 850 (acceptable, < 10% of limit)
```

**Code - PreDestroy Hook:**
```java
@PreDestroy
public void flushOnShutdown() {
    log.warn("Pod shutting down, syncing {} tenants with Redis", 
            localQuotaManager.getCacheSize());
    
    // Flush all local quotas to Redis
    localQuotaManager.getAllTenants().forEach(tenantId -> {
        LocalQuota quota = localQuotaManager.getQuota(tenantId);
        if (quota != null && quota.getConsumed() > 0) {
            syncLocalToRedis(tenantId, quota);
        }
    });
    
    log.info("Successfully flushed local cache to Redis");
}

private void syncLocalToRedis(String tenantId, LocalQuota quota) {
    try {
        // Return unused tokens to Redis
        long unused = quota.getAvailable();
        if (unused > 0) {
            redisConnection.sync().hincrby(
                getQuotaKey(tenantId), 
                "consumed", 
                -unused  // Decrement consumed
            );
        }
    } catch (Exception e) {
        log.error("Failed to sync tenant {} to Redis", tenantId, e);
    }
}
```

**Interview Answer:**
```
"When a pod crashes:

1. K8s detects failure via liveness probe (within 30s)
2. APISIX health checks also detect (within 2s for active upstream)
3. Traffic is redirected using consistent hashing
4. Local cache is lost but Redis has global state
5. New pod allocates fresh quota (slight over-allocation possible)

We use PreDestroy hooks to flush local state on graceful shutdown.
For crashes, we accept small quota loss (~10%) as tradeoff for performance.

This is acceptable because:
- Rate limiting is probabilistic, not transactional
- Small over-allocation (10%) doesn't break the system
- Alternative (sync every request) kills performance"
```

---

### Scenario 5: Cascading Pod Failures

**What Happens:**
```
Initial: 3 pods, each handling 15k TPS = 45k total

Time 0:00 - Pod 1 crashes (OOM)
Time 0:01 - Traffic redistributes to Pod 2 & 3
            Each now handles 22.5k TPS (50% overload)

Time 0:30 - Pod 2 crashes (overload)
Time 0:31 - Pod 3 now handles 45k TPS (100% overload)

Time 0:45 - Pod 3 crashes
Time 0:46 - All pods down, service outage
```

**Prevention - Resource Limits:**
```yaml
# k8s deployment.yaml
resources:
  requests:
    memory: "512Mi"
    cpu: "500m"
  limits:
    memory: "1Gi"      # OOM killer activates here
    cpu: "1000m"       # Throttled beyond this

# Horizontal Pod Autoscaler
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: ratelimit-hpa
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: ratelimit-service
  minReplicas: 3
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70  # Scale at 70% CPU
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

**Circuit Breaker at Pod Level:**
```java
@Component
public class LoadShedding {
    
    private final AtomicLong currentRPS = new AtomicLong(0);
    private final AtomicLong maxRPS = new AtomicLong(20_000);
    
    @Scheduled(fixedRate = 1000)
    public void resetCounter() {
        long current = currentRPS.getAndSet(0);
        
        // Adaptive threshold based on CPU
        double cpuUsage = getCpuUsage();
        if (cpuUsage > 0.9) {
            maxRPS.set(maxRPS.get() * 80 / 100);  // Reduce by 20%
            log.warn("High CPU, reducing max RPS to {}", maxRPS.get());
        } else if (cpuUsage < 0.5) {
            maxRPS.set(Math.min(20_000, maxRPS.get() * 110 / 100));
        }
    }
    
    public boolean shouldAccept() {
        long current = currentRPS.incrementAndGet();
        return current <= maxRPS.get();
    }
}

// In filter:
if (!loadShedding.shouldAccept()) {
    httpResponse.setStatus(503);  // Service Unavailable
    httpResponse.getWriter().write("{\"error\":\"Server overloaded\"}");
    return;
}
```

---

## Race Conditions

### Scenario 6: Concurrent Quota Allocation

**Problem:**
```
Thread 1: Checks Redis → available=1000 → Starts allocation
Thread 2: Checks Redis → available=1000 → Starts allocation
Thread 1: Allocates 1000 tokens
Thread 2: Allocates 1000 tokens

Result: Over-allocated by 1000 tokens!
```

**Solution - Lua Script (Atomic):**
```lua
-- allocate-quota.lua
local key = KEYS[1]
local requested = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

-- Redis guarantees atomicity of entire script
local consumed = tonumber(redis.call('HGET', key, 'consumed') or '0')
local available = limit - consumed

if available <= 0 then
    return 0
end

local allocated = math.min(requested, available)
redis.call('HINCRBY', key, 'consumed', allocated)

return allocated
```

**Why Lua Scripts?**
```
Regular commands (race condition possible):
  GET → compute → SET (another thread can interleave)

Lua script (atomic):
  GET + compute + SET (all in one operation)

Performance:
  Regular: 2-3 network roundtrips
  Lua: 1 network roundtrip
```

---

### Scenario 7: 5k Concurrent Requests from Same Tenant

**Problem:**
```
Time 0:00.000 - 5000 threads arrive simultaneously
All threads: local cache available = 850 tokens
All threads: Try to consume 1 token each

Without proper synchronization:
  850 requests allowed (correct)
  4150 requests denied (correct)

With bad synchronization:
  Race condition could allow 5000 requests!
```

**Solution - CAS Loop:**
```java
public boolean tryConsumeLocal(String tenantId, long tokens) {
    LocalQuota quota = quotaCache.getIfPresent(tenantId);
    if (quota == null) return false;
    
    // CAS (Compare-And-Swap) loop
    while (true) {
        long current = quota.available.get();
        
        if (current < tokens) {
            return false;  // Insufficient
        }
        
        // Atomic operation: only succeeds if value unchanged
        if (quota.available.compareAndSet(current, current - tokens)) {
            quota.consumed.addAndGet(tokens);
            return true;  // Success
        }
        
        // CAS failed (another thread modified), retry
        // CPU spin, no blocking
    }
}
```

**Why CAS instead of synchronized?**

| Approach | Throughput | Latency | CPU | Scalability |
|----------|-----------|---------|-----|-------------|
| synchronized | 50k TPS | 5ms | Low | Poor (blocking) |
| ReentrantLock | 80k TPS | 2ms | Low | Better |
| CAS (lock-free) | 200k TPS | 0.1ms | High | Best |

**CAS Benchmark:**
```
Test: 5000 threads, each consuming 1 token

synchronized:
  Duration: 250ms
  Contention: High (threads waiting)
  
CAS:
  Duration: 25ms
  Contention: Low (threads spinning)
```

---

## Scale Operations

### Scenario 8: Scaling Up (3 → 6 pods)

**Impact on Consistent Hashing:**
```
Before (3 pods):
  hash("tenant-123") % 3 = 1 → Pod 1
  hash("tenant-456") % 3 = 0 → Pod 0
  hash("tenant-789") % 3 = 2 → Pod 2

After (6 pods):
  hash("tenant-123") % 6 = 1 → Pod 1 (same!)
  hash("tenant-456") % 6 = 0 → Pod 0 (same!)
  hash("tenant-789") % 6 = 5 → Pod 5 (MOVED!)

Result: ~50% of tenants remapped
```

**Better Approach - Consistent Hash Ring:**
```java
public class ConsistentHashRing {
    private final TreeMap<Long, String> ring = new TreeMap<>();
    private final int virtualNodes = 150;  // Per physical node
    
    public void addNode(String nodeId) {
        for (int i = 0; i < virtualNodes; i++) {
            long hash = hash(nodeId + ":" + i);
            ring.put(hash, nodeId);
        }
    }
    
    public String getNode(String key) {
        long hash = hash(key);
        Map.Entry<Long, String> entry = ring.ceilingEntry(hash);
        return entry != null ? entry.getValue() : ring.firstEntry().getValue();
    }
}

// With consistent hash ring:
// Adding 3 pods → Only ~16% of tenants remapped (vs 50%)
```

**Handling Remapping:**
```java
@EventListener
public void onPodScaling(PodScalingEvent event) {
    if (event.isScaleUp()) {
        // Expect cache misses for remapped tenants
        // They will allocate from Redis (normal operation)
        log.info("Scaling up, expect temporary cache miss rate increase");
        
        // Optional: Pre-warm caches for high-priority tenants
        prewarmCaches(highPriorityTenants);
    }
}
```

---

## Summary Table: Failure Impact & Mitigation

| Failure | Detection Time | Impact | Recovery Time | Mitigation |
|---------|---------------|--------|---------------|------------|
| Redis down | 2-10s | Degraded mode, limited traffic | Auto (circuit breaker) | Local cache continues |
| Redis slow | 1-5s | Increased latency | Manual (scale Redis) | Timeouts, fail fast |
| Pod crash | 2-30s | Traffic redistribution | Auto (K8s restart) | PreDestroy hooks |
| Network partition | 5-30s | Inconsistent state | Manual (resolve partition) | Use Redis time |
| Burst traffic | Immediate | Rate limit exceeded | N/A (expected) | Local cache absorbs |
| Scale operation | Immediate | Cache misses spike | 1-2 minutes | Consistent hashing |

---

End of Failure Scenarios Document

