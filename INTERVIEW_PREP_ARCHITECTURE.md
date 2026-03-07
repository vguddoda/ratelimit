# Distributed Rate Limiting with Local Cache + Redis
## Interview Prep Material - Architecture Deep Dive

---

## Table of Contents
1. [System Architecture Overview](#system-architecture-overview)
2. [Traffic Flow](#traffic-flow)
3. [Core Design Principles](#core-design-principles)
4. [Local Cache + Redis Strategy](#local-cache--redis-strategy)
5. [Handling 5k Concurrent Requests](#handling-5k-concurrent-requests)
6. [Failure Scenarios](#failure-scenarios)
7. [Performance Optimizations](#performance-optimizations)

---

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│                         APISIX Gateway                          │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐     │
│  │ Auth Plugin  │───▶│ Set TenantID │───▶│  Consistent  │     │
│  │ (JWT/OAuth)  │    │  in Header   │    │   Hashing    │     │
│  └──────────────┘    └──────────────┘    └──────────────┘     │
└─────────────────────────────────────────────────────────────────┘
                                │
                                │ X-Tenant-ID: tenant-123
                                │
            ┌───────────────────┼───────────────────┐
            │                   │                   │
            ▼                   ▼                   ▼
    ┌───────────────┐   ┌───────────────┐   ┌───────────────┐
    │ RateLimit Pod │   │ RateLimit Pod │   │ RateLimit Pod │
    │    (Pod 1)    │   │    (Pod 2)    │   │    (Pod 3)    │
    │               │   │               │   │               │
    │ ┌───────────┐ │   │ ┌───────────┐ │   │ ┌───────────┐ │
    │ │   LOCAL   │ │   │ │   LOCAL   │ │   │ │   LOCAL   │ │
    │ │   CACHE   │ │   │ │   CACHE   │ │   │ │   CACHE   │ │
    │ │ (Caffeine)│ │   │ │ (Caffeine)│ │   │ │ (Caffeine)│ │
    │ └─────┬─────┘ │   │ └─────┬─────┘ │   │ └─────┬─────┘ │
    │       │       │   │       │       │   │       │       │
    └───────┼───────┘   └───────┼───────┘   └───────┼───────┘
            │                   │                   │
            └───────────────────┼───────────────────┘
                                │
                                ▼
                    ┌───────────────────────┐
                    │    Redis Cluster      │
                    │  (Global State)       │
                    │                       │
                    │  tenant-123: 8500/10k │
                    │  tenant-456: 1200/5k  │
                    └───────────────────────┘
```

---

## Traffic Flow

### Step-by-Step Request Processing

**1. Request enters APISIX:**
```
GET /api/data
Authorization: Bearer eyJhbGciOiJIUzI1NiIs...
```

**2. Auth Plugin validates token and extracts tenant ID:**
```lua
-- APISIX Plugin (for reference)
local tenant_id = jwt_claims.tenant_id  -- e.g., "tenant-123"
ngx.req.set_header("X-Tenant-ID", tenant_id)
```

**3. Consistent Hashing determines target pod:**
```
hash(tenant-123) % 3 = Pod 2
ALL requests from tenant-123 → Pod 2
```

**4. Rate Limit Service processes request:**
```java
// Local cache check first (< 1ms latency)
// Redis sync every 10% consumption or time window
```

---

## Core Design Principles

### 1. **Consistent Hashing**
- **Why?** Same tenant always hits same pod
- **Benefit:** Local cache efficiency (90%+ hit rate)
- **Implementation:** K8s Service with session affinity or APISIX routing

### 2. **Hybrid Cache Strategy**
- **Local Cache:** Caffeine (in-memory, JVM heap)
- **Global State:** Redis (distributed, source of truth)
- **Sync Threshold:** 10% of limit consumed

### 3. **Write-Behind Pattern**
- Don't block request for Redis write
- Batch updates to Redis
- Async synchronization

---

## Local Cache + Redis Strategy

### The 10% Rule

**Concept:**
- Allocate quota in chunks from Redis
- Consume locally without Redis calls
- Sync back when chunk is consumed

**Example:**
```
Tenant Limit: 10,000 requests/minute
Chunk Size: 10% = 1,000 requests

Flow:
1. First request → Check Redis (1 call)
2. Allocate 1,000 tokens locally
3. Next 999 requests → Pure local cache (NO Redis)
4. On 1,001st request → Sync and get new chunk
```

**Math:**
```
Without chunking: 10,000 requests = 10,000 Redis calls
With 10% chunking: 10,000 requests = ~10 Redis calls
Reduction: 99% fewer Redis calls
```

---

## Handling 5k Concurrent Requests

### Scenario: 5,000 simultaneous requests from same tenant

**Problem:**
```
5,000 threads hit the service simultaneously
All trying to consume tokens at exact same microsecond
Race condition risk without proper synchronization
```

### Solution: Optimistic Locking with Local State

```java
class LocalQuotaManager {
    private final ConcurrentHashMap<String, AtomicLong> localTokens;
    private final ConcurrentHashMap<String, AtomicLong> allocatedQuota;
    
    // Thread-safe token consumption
    public boolean tryConsume(String tenantId, long tokens) {
        AtomicLong available = localTokens.get(tenantId);
        
        // CAS loop for atomicity
        while (true) {
            long current = available.get();
            if (current < tokens) {
                // Need to sync with Redis
                return syncWithRedisAndRetry(tenantId, tokens);
            }
            if (available.compareAndSet(current, current - tokens)) {
                return true; // Success
            }
            // CAS failed, retry
        }
    }
}
```

**Key Points:**
1. **AtomicLong** for thread-safe counter
2. **CAS (Compare-And-Swap)** for lock-free updates
3. **No synchronized blocks** for maximum throughput
4. **JVM guarantees** atomicity across threads

---

## Corner Cases & Handling

### Case 1: Burst Traffic (5k requests in 1 second)

**Scenario:**
```
Time 0ms: 5,000 requests arrive
Local quota: 1,000 tokens available
Need: 5,000 tokens
```

**Handling:**
```java
public boolean consumeToken(String tenantId) {
    // 1. Try local first
    if (tryConsumeLocal(tenantId)) {
        return true;
    }
    
    // 2. Local exhausted, sync with Redis
    synchronized (getLockForTenant(tenantId)) {
        // Double-check after acquiring lock
        if (tryConsumeLocal(tenantId)) {
            return true;
        }
        
        // 3. Allocate new chunk from Redis
        long allocated = allocateChunkFromRedis(tenantId);
        
        if (allocated > 0) {
            updateLocalQuota(tenantId, allocated);
            return tryConsumeLocal(tenantId);
        }
    }
    
    // 4. Globally rate limited
    return false;
}
```

**Result:**
- First 1,000 requests: Accepted (local cache)
- Requests 1,001-1,020: Blocked briefly (~2ms) during Redis sync
- Next 1,000 requests: Accepted (new chunk)
- Repeat until global limit hit

---

### Case 2: Redis Failure

**Scenario:** Redis cluster goes down

**Strategy: Fail-Safe with Circuit Breaker**

```java
@Component
public class RateLimitService {
    private final CircuitBreaker circuitBreaker;
    private final LoadingCache<String, LocalQuota> localCache;
    
    public boolean allowRequest(String tenantId) {
        try {
            // Try local cache first
            LocalQuota quota = localCache.get(tenantId);
            if (quota.hasTokens()) {
                return true;
            }
            
            // Need Redis sync
            if (circuitBreaker.isOpen()) {
                // Redis is down, use degraded mode
                return handleRedisFailure(tenantId, quota);
            }
            
            return syncWithRedis(tenantId);
            
        } catch (Exception e) {
            circuitBreaker.recordFailure();
            return handleRedisFailure(tenantId, quota);
        }
    }
    
    private boolean handleRedisFailure(String tenantId, LocalQuota quota) {
        // Option 1: Allow limited traffic (e.g., 10% of normal)
        return quota.tryConsumeDegraded();
        
        // Option 2: Strict - deny all (if SLA critical)
        // return false;
        
        // Option 3: Allow all (if availability > rate limiting)
        // return true;
    }
}
```

**Decision Matrix:**
| Scenario | Action | Reasoning |
|----------|--------|-----------|
| Critical payment API | Deny all | Prevent abuse |
| Public read API | Allow 10% | Availability over strict limiting |
| Internal API | Allow all | Trust internal traffic |

---

### Case 3: Pod Restart / Crash

**Problem:**
```
Pod crashes → Local cache lost
Allocated but unconsumed tokens are lost
Could lead to over-consumption
```

**Solution: Redis as Source of Truth**

```java
public class QuotaRecoveryService {
    
    @PostConstruct
    public void recoverQuotaOnStartup() {
        // On pod restart, DON'T start with fresh allocations
        // Force sync with Redis for first request of each tenant
        localCache.invalidateAll();
        
        log.info("Pod started. Local cache cleared. Will sync with Redis.");
    }
    
    public long allocateChunk(String tenantId) {
        // Redis tracks: allocated_total and consumed_total
        long consumed = redisGetConsumed(tenantId);
        long allocated = redisGetAllocated(tenantId);
        long limit = getTenantLimit(tenantId);
        
        // Available globally = limit - consumed
        long globalAvailable = limit - consumed;
        
        if (globalAvailable <= 0) {
            return 0; // Rate limited
        }
        
        // Allocate chunk (10% or remaining, whichever is smaller)
        long chunkSize = Math.min(limit / 10, globalAvailable);
        
        // Update Redis atomically
        redisIncrementAllocated(tenantId, chunkSize);
        
        return chunkSize;
    }
}
```

---

### Case 4: Clock Skew Between Pods

**Problem:**
```
Pod 1 time: 12:00:00
Pod 2 time: 12:00:05 (5 sec ahead)
Both think they're in different time windows
```

**Solution: Use Redis Time**

```java
public long getCurrentWindowTimestamp() {
    // Use Redis TIME command (atomic, cluster-wide)
    List<String> redisTime = redisConnection.sync().time();
    long seconds = Long.parseLong(redisTime.get(0));
    return seconds / 60; // minute-based window
}
```

---

### Case 5: Multiple Pods for Same Tenant

**Wait, I thought consistent hashing prevents this?**

Yes, but consider:
- **Pod scaling**: New pod added, hash changes slightly
- **Pod failure**: Traffic redistributes
- **Health check**: Temporary removal then re-addition

**Solution: Redis-based Distributed Lock for Critical Sections**

```java
public long allocateChunkWithLock(String tenantId) {
    String lockKey = "lock:quota:" + tenantId;
    RLock lock = redisson.getLock(lockKey);
    
    try {
        // Try to acquire lock (max 100ms wait)
        if (lock.tryLock(100, 5000, TimeUnit.MILLISECONDS)) {
            try {
                return allocateChunkFromRedis(tenantId);
            } finally {
                lock.unlock();
            }
        } else {
            // Couldn't get lock, use cached value
            return 0;
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return 0;
    }
}
```

---

## Performance Optimizations

### 1. **Batch Redis Updates**

```java
private final BlockingQueue<QuotaUpdate> updateQueue = 
    new LinkedBlockingQueue<>(10000);

@Scheduled(fixedDelay = 100) // Every 100ms
public void batchUpdateRedis() {
    List<QuotaUpdate> batch = new ArrayList<>();
    updateQueue.drainTo(batch, 1000); // Max 1000 updates
    
    if (!batch.isEmpty()) {
        RedisPipeline pipeline = redis.pipelined();
        for (QuotaUpdate update : batch) {
            pipeline.hincrby("quota:" + update.tenantId, 
                           "consumed", update.amount);
        }
        pipeline.exec();
    }
}
```

**Benefit:** 1000 updates = 1 network roundtrip

---

### 2. **Lock Striping for Concurrency**

```java
private final Striped<Lock> locks = Striped.lock(128);

public boolean consume(String tenantId) {
    Lock lock = locks.get(tenantId);
    lock.lock();
    try {
        // Critical section
    } finally {
        lock.unlock();
    }
}
```

**Benefit:** 128 concurrent tenants can process simultaneously

---

### 3. **Lazy Synchronization**

Don't sync immediately when local quota exhausted:

```java
private boolean needsSync(LocalQuota quota) {
    // Sync only if:
    return quota.consumed() >= quota.allocated() * 0.9  // 90% used
        || quota.lastSyncAge() > Duration.ofSeconds(5)   // 5 sec old
        || quota.available() < 10;                        // Almost empty
}
```

---

## Key Interview Talking Points

### 1. **Why not use Redis for every request?**
**Answer:** At 45k TPS, that's 45k Redis operations/sec. Even with pipelining:
- Network latency: ~1ms per operation
- Redis CPU: Single-threaded, becomes bottleneck
- Cost: High memory and I/O

With local caching (10% chunks):
- 45k TPS → 450 Redis ops/sec (99% reduction)
- Most requests: <0.1ms (in-memory)
- Redis: Only coordination, not hot path

---

### 2. **How do you handle race conditions?**
**Answer:** Multi-layer strategy:
- **L1 (Pod level):** AtomicLong + CAS for thread safety
- **L2 (Cluster level):** Redis Lua scripts for atomic operations
- **L3 (Allocation):** Distributed locks for chunk allocation

```lua
-- Redis Lua script (atomic)
local key = KEYS[1]
local amount = tonumber(ARGV[1])
local limit = tonumber(ARGV[2])

local consumed = redis.call('GET', key) or 0
consumed = tonumber(consumed)

if consumed + amount <= limit then
    redis.call('INCRBY', key, amount)
    return 1
else
    return 0
end
```

---

### 3. **What happens during pod scaling?**
**Answer:**
- **Scale up:** New pods start with empty cache, sync with Redis
- **Scale down:** In-flight requests complete, local quota lost but Redis has truth
- **Consistent hashing:** Minimize tenant migration (only ~1/N affected)

**Mitigation:**
```java
@PreDestroy
public void flushLocalQuota() {
    // Before shutdown, flush all local state to Redis
    localCache.asMap().forEach((tenant, quota) -> {
        syncLocalToRedis(tenant, quota);
    });
}
```

---

### 4. **Worst-case latency?**
**Answer:**

| Operation | Latency | Frequency |
|-----------|---------|-----------|
| Local cache hit | 0.01ms | 90% of requests |
| Redis sync | 1-2ms | 10% of requests |
| Redis down (circuit breaker) | 0.1ms | 0.01% (failures) |
| Lock contention | 5-50ms | <1% (high concurrency) |

**P99 latency:** <2ms
**P999 latency:** <10ms

---

### 5. **Data consistency guarantees?**
**Answer:** Eventually consistent with bounded staleness:

- **Strong consistency:** Not required (rate limiting is probabilistic)
- **Bounded staleness:** Max 10% over-allocation per pod
- **Global invariant:** Redis enforces global limit
- **Recovery:** Time-window resets restore correctness

**Example:**
```
Limit: 10,000 req/min
Pods: 3
Max over-allocation: 3 * 10% = 3,000 extra
Absolute worst case: 13,000 requests
Reality: ~10,200 requests (due to Redis checks)
```

This is acceptable because:
1. Prevents abuse (10k vs 1M)
2. SLA met (tenant expects "around 10k")
3. Cost of strict consistency > value gained

---

## Summary Cheat Sheet

```
┌─────────────────────────────────────────────────────────────┐
│                    ARCHITECTURE DECISIONS                    │
├─────────────────────────────────────────────────────────────┤
│ Local Cache:    Caffeine (JVM heap, <1ms)                  │
│ Global State:   Redis (source of truth)                     │
│ Sync Strategy:  10% chunk allocation                        │
│ Concurrency:    AtomicLong + CAS (lock-free)               │
│ Consistency:    Eventually consistent (bounded)             │
│ Failure Mode:   Circuit breaker → Degraded service         │
│ Performance:    45k TPS → 450 Redis ops/sec                │
└─────────────────────────────────────────────────────────────┘
```

**Redis calls reduced by 99%**
**Latency improved from 2ms → 0.01ms**
**Cost reduced by 10x**

---

## APISIX Consistent Hashing Configuration

### Complete APISIX Upstream Configuration

This section shows the exact APISIX configuration for consistent hashing routing to Kubernetes service endpoints based on tenant ID.

```yaml
# APISIX Route Configuration
routes:
  - id: ratelimit-route
    uri: /api/*
    name: rate-limit-service
    methods:
      - GET
      - POST
      - PUT
      - DELETE
    
    # Plugin chain executes in order
    plugins:
      # Step 1: JWT Authentication
      jwt-auth:
        header: Authorization
        query: jwt
        cookie: jwt
      
      # Step 2: Extract tenant_id from JWT and set header
      serverless-pre-function:
        phase: rewrite
        functions:
          - |
            return function(conf, ctx)
              local core = require("apisix.core")
              local jwt_obj = ctx.var.jwt_obj
              
              if jwt_obj and jwt_obj.tenant_id then
                -- Store tenant_id in context for consistent hashing
                ctx.var.tenant_id = jwt_obj.tenant_id
                
                -- Set header for upstream service
                core.request.set_header(ctx, "X-Tenant-ID", jwt_obj.tenant_id)
                
                core.log.info("Routing tenant: ", jwt_obj.tenant_id)
              else
                return 401, {error = "Missing tenant_id in JWT"}
              end
            end
    
    # Reference upstream with consistent hashing
    upstream_id: ratelimit-upstream

# Upstream Configuration with Consistent Hashing
upstreams:
  - id: ratelimit-upstream
    name: rate-limit-pods
    
    # ===== CONSISTENT HASHING CONFIGURATION =====
    type: chash                    # Consistent hashing algorithm
    hash_on: vars                  # Hash based on Nginx variable
    key: tenant_id                 # Use tenant_id variable for hashing
    
    # ===== KUBERNETES SERVICE DISCOVERY =====
    service_name: ratelimit-service.default.svc.cluster.local
    discovery_type: dns            # Use DNS for service discovery
    
    # Alternative: Manual pod IPs (if not using K8s service discovery)
    # nodes:
    #   "10.0.1.10:8080": 1        # Pod 1
    #   "10.0.1.11:8080": 1        # Pod 2
    #   "10.0.1.12:8080": 1        # Pod 3
    
    # ===== HEALTH CHECKS =====
    checks:
      active:
        type: http
        http_path: /api/health
        timeout: 1
        healthy:
          interval: 2              # Check every 2 seconds
          successes: 2             # 2 success = healthy
        unhealthy:
          interval: 1              # Check every 1 second when down
          http_failures: 2         # 2 failures = unhealthy
      
      passive:
        healthy:
          http_statuses:           # Consider these as healthy
            - 200
            - 201
            - 204
        unhealthy:
          http_statuses:           # Consider these as unhealthy
            - 500
            - 503
          http_failures: 3         # 3 consecutive failures
    
    # ===== TIMEOUT CONFIGURATION =====
    timeout:
      connect: 1                   # 1 second connect timeout
      send: 1                      # 1 second send timeout
      read: 1                      # 1 second read timeout
```

### How Consistent Hashing Works

```
Request Flow:
┌────────────────────────────────────────────────────────┐
│ 1. Request arrives with JWT token                     │
│    Authorization: Bearer eyJhbGc...                    │
└────────────────────────────────────────────────────────┘
                      ↓
┌────────────────────────────────────────────────────────┐
│ 2. JWT Auth Plugin validates token                    │
│    Extracts claims: {tenant_id: "tenant-123", ...}    │
└────────────────────────────────────────────────────────┘
                      ↓
┌────────────────────────────────────────────────────────┐
│ 3. Serverless Function stores tenant_id                │
│    ctx.var.tenant_id = "tenant-123"                    │
│    Sets header: X-Tenant-ID: tenant-123                │
└────────────────────────────────────────────────────────┘
                      ↓
┌────────────────────────────────────────────────────────┐
│ 4. Consistent Hash Calculation                         │
│    hash = crc32("tenant-123")                          │
│    pod_index = hash % num_pods                         │
└────────────────────────────────────────────────────────┘
                      ↓
┌────────────────────────────────────────────────────────┐
│ 5. DNS Resolution (K8s Service Discovery)              │
│    Query: ratelimit-service.default.svc.cluster.local  │
│    Returns: [10.0.1.10, 10.0.1.11, 10.0.1.12]         │
└────────────────────────────────────────────────────────┘
                      ↓
┌────────────────────────────────────────────────────────┐
│ 6. Select Pod Based on Hash                            │
│    Selected: 10.0.1.11:8080 (Pod 2)                   │
└────────────────────────────────────────────────────────┘
                      ↓
┌────────────────────────────────────────────────────────┐
│ 7. Forward Request to Pod                              │
│    POST http://10.0.1.11:8080/api/data                │
│    Headers: X-Tenant-ID: tenant-123                    │
└────────────────────────────────────────────────────────┘
```

### Kubernetes Service Configuration

The APISIX upstream references a Kubernetes service. Here's the K8s configuration:

```yaml
# Headless Service for Direct Pod Access
apiVersion: v1
kind: Service
metadata:
  name: ratelimit-service
  namespace: default
  labels:
    app: ratelimit
spec:
  # clusterIP: None         # Uncomment for headless service
  selector:
    app: ratelimit
  ports:
    - protocol: TCP
      port: 8080
      targetPort: 8080
      name: http
  
  # Optional: Session affinity (backup to consistent hashing)
  # sessionAffinity: ClientIP
  # sessionAffinityConfig:
  #   clientIP:
  #     timeoutSeconds: 10800
```

### Hash Distribution Example

**Scenario: 3 Pods, 10 Tenants**

```
Tenant ID           Hash (CRC32)    Modulo 3    Target Pod
─────────────────────────────────────────────────────────────
tenant-001          0x8F3A2B1C      0           Pod 1 (10.0.1.10)
tenant-002          0x9E4B3C2D      1           Pod 2 (10.0.1.11)
tenant-003          0xAD5C4D3E      2           Pod 3 (10.0.1.12)
tenant-004          0xBC6D5E4F      0           Pod 1 (10.0.1.10)
tenant-005          0xCB7E6F5A      1           Pod 2 (10.0.1.11)
tenant-006          0xDA8F7A6B      2           Pod 3 (10.0.1.12)
tenant-007          0xE99A8B7C      0           Pod 1 (10.0.1.10)
tenant-008          0xF8AB9C8D      1           Pod 2 (10.0.1.11)
tenant-009          0xA7BCAD9E      2           Pod 3 (10.0.1.12)
tenant-010          0xB6CDBEAF      0           Pod 1 (10.0.1.10)

Distribution:
  Pod 1: 4 tenants (40%)
  Pod 2: 3 tenants (30%)
  Pod 3: 3 tenants (30%)
```

### Benefits of This Configuration

**1. Automatic Service Discovery**
```
✓ No manual pod IP management
✓ K8s DNS resolves service name to pod IPs
✓ Automatic updates when pods scale
✓ Works with K8s deployments and rolling updates
```

**2. Consistent Hashing Advantages**
```
✓ Same tenant always routed to same pod
✓ Local cache hit rate: 90%+
✓ Minimal remapping during scale operations
✓ Load balanced across pods
```

**3. Health Check Integration**
```
✓ Unhealthy pods removed from rotation automatically
✓ Traffic redistributed to healthy pods
✓ Fast failure detection (<2 seconds)
✓ Automatic recovery when pod becomes healthy
```

### Verification Commands

```bash
# Check APISIX routing configuration
curl http://apisix-admin:9180/apisix/admin/routes/ratelimit-route

# Check upstream configuration
curl http://apisix-admin:9180/apisix/admin/upstreams/ratelimit-upstream

# Test routing (should hit same pod for same tenant)
for i in {1..10}; do
  curl -H "Authorization: Bearer <token-with-tenant-123>" \
       http://apisix-gateway:9080/api/data
done

# Check which pod handled request (look at logs)
kubectl logs -l app=ratelimit -n default --tail=20
```

### Interview Talking Points

**Q: Why use DNS service discovery instead of hardcoded IPs?**
```
A: Dynamic pod management in Kubernetes:
- Pods can be recreated with different IPs
- Scaling operations change pod count
- DNS automatically resolves to current pod IPs
- No manual APISIX configuration updates needed
```

**Q: What happens when a pod is removed?**
```
A: APISIX handles it gracefully:
1. Health check detects pod is down (2 seconds)
2. Pod removed from upstream pool
3. Consistent hash recalculates with remaining pods
4. Affected tenants (1/N) remapped to other pods
5. They allocate new local cache from Redis
```

**Q: How do you ensure even distribution?**
```
A: Multiple mechanisms:
1. CRC32 hash provides uniform distribution
2. Consistent hashing minimizes remapping
3. Virtual nodes can be added (future enhancement)
4. Monitor distribution with metrics
```

---

End of Architecture Document

