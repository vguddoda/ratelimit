# Rate Limiting System Design — Deep Dive

Topics: APISIX setup, consistent hashing, hybrid local+Redis algorithm, CAS in service, failure handling

---

## 1. Why Not Pure Redis?

With Bucket4j every request → 1 Redis call:
- 45k TPS = 45k Redis ops/sec
- Redis is single-threaded; network RTT adds up
- P99 latency dominated by Redis call overhead

**Fix:** Allocate tokens in *chunks* from Redis, serve most requests from local in-memory cache.

```
Without chunking:  10,000 requests → 10,000 Redis calls
With 10% chunks:   10,000 requests → ~10 Redis calls   (99% reduction)
```

---

## 2. APISIX Gateway Configuration

### Plugin Chain (Order Matters)

```
Request → jwt-auth → serverless-pre-function → proxy-rewrite → chash upstream → Pod
```

### Full APISIX Config

```yaml
routes:
  - id: ratelimit-route
    uri: /api/*
    plugins:
      # Step 1: Validate JWT
      jwt-auth:
        header: Authorization

      # Step 2: Extract tenant_id from JWT claims, set header
      serverless-pre-function:
        phase: rewrite
        functions:
          - |
            return function(conf, ctx)
              local core = require("apisix.core")
              local jwt_obj = ctx.var.jwt_obj
              if jwt_obj and jwt_obj.tenant_id then
                ctx.var.tenant_id = jwt_obj.tenant_id
                core.request.set_header(ctx, "X-Tenant-ID", jwt_obj.tenant_id)
              else
                return 401, {error = "Missing tenant_id in token"}
              end
            end

    upstream_id: ratelimit-upstream

upstreams:
  - id: ratelimit-upstream
    type: chash          # Consistent hashing
    hash_on: vars
    key: tenant_id       # Hash key = tenant_id variable set above
    # K8s headless service — APISIX discovers individual pod IPs
    service_name: ratelimit-service.default.svc.cluster.local
    discovery_type: dns
    checks:
      active:
        http_path: /api/health
        healthy:   { interval: 2, successes: 2 }
        unhealthy: { interval: 1, http_failures: 2 }
```

**Why headless K8s service?**  
`clusterIP: None` → APISIX sees individual pod IPs. Required for consistent hashing to route to a specific pod (not kube-proxy load balanced VIP).

```yaml
apiVersion: v1
kind: Service
metadata:
  name: ratelimit-service
spec:
  clusterIP: None    # Headless
  selector:
    app: ratelimit
  ports:
    - port: 8080
```

---

## 3. Consistent Hashing — Why and How

### Problem with Round-Robin

```
tenant-123 → Pod 1 → cache miss → allocate 1000 from Redis
tenant-123 → Pod 2 → cache miss → allocate 1000 from Redis  (WASTED!)
tenant-123 → Pod 3 → cache miss → allocate 1000 from Redis  (WASTED!)
Total allocated: 3000 for a tenant using maybe 300 requests
```

### With Consistent Hashing

```
tenant-123 → Pod 2 (always)
  Request 1: cache miss → Redis allocate 1000
  Request 2-1000: local cache hit — zero Redis calls
Cache efficiency: 90%+
```

### How APISIX chash Works

```
hash_ring = sorted circle of hash values
Each pod gets virtual_nodes (default 160) points on the ring

pod_to_route = ring.ceiling_entry(hash(tenant_id)).value

Adding a pod:
  - Only tenants whose hash falls between new pod and its predecessor remap
  - ~1/N remapping (N = new pod count) vs 50% with simple modulo
```

### Pod Scaling Impact

```
Scale 3 → 5 pods with simple modulo:
  hash("tenant-123") % 3 = 1 → Pod 1
  hash("tenant-123") % 5 = 3 → Pod 3 (MOVED — cache miss)
  ~50% of tenants remapped

Scale 3 → 5 pods with consistent hash ring:
  Only ~16% of tenants remapped (mathematical property of ring)
  Remapped tenants: first request = Redis call, then local cache resumes
```

---

## 4. Hybrid Local + Redis Algorithm

### Token Allocation Flow

```
Request arrives at pod
       │
       ▼
  [Local Cache] ─── available > 0 ──► consume locally (NO Redis)
       │
     empty
       │
       ▼
  [Per-tenant lock] acquire
       │
       ▼
  [Redis Lua script] allocate next chunk (10% of limit)
       │
       ▼
  Update local cache with new chunk
       │
       ▼
  Release lock, serve request
```

### LocalQuotaManager — Core CAS Loop

```java
public class LocalQuotaManager {

    record LocalQuota(
        AtomicLong available,   // Tokens left in the current chunk (decremented by CAS)
        AtomicLong consumed,    // Tokens consumed from current chunk (for flush trigger)
        AtomicLong allocated,   // Size of the current chunk allocated from Redis
        long       globalLimit, // ← ADDED: the tenant's total limit per window (e.g. 10,000)
                                //   needed to compute chunkSize = globalLimit * 10%
                                //   and the flush threshold = allocated (= 10% of globalLimit)
        volatile long lastSync  // Last Redis sync timestamp (volatile for cross-thread visibility)
    ) {
        // Flush trigger: have we consumed the full chunk?
        // allocated = 10% of globalLimit (e.g. 1,000 when limit=10,000)
        // When consumed >= allocated, this chunk is exhausted → sync Redis
        boolean isChunkExhausted() {
            return consumed.get() >= allocated.get();
        }

        // What % of global limit has been consumed locally this chunk
        // Useful for logging / observability
        double consumedPercent() {
            return (consumed.get() * 100.0) / globalLimit;
        }
    }

    /*
     * How the fields relate:
     *
     *  globalLimit = 10,000   ← tenant's total allowed per minute
     *  allocated   =  1,000   ← chunk grabbed from Redis (10% of globalLimit)
     *  consumed    =    0..1000 ← how many we've used from this chunk (CAS increments this)
     *  available   =  1000..0  ← globalLimit - consumed (CAS decrements this)
     *
     *  Flush to Redis when:  consumed >= allocated  (chunk exhausted)
     *  At that point exactly 10% of globalLimit was consumed locally without any Redis call.
     *  Then grab the next chunk from Redis (another 10%).
     *
     *  Max Redis calls per window = globalLimit / chunkSize = 10,000 / 1,000 = 10 calls
     *  vs. pure Redis approach   = 10,000 calls  (99% reduction)
     */

    private final ConcurrentHashMap<String, LocalQuota> quotaCache = new ConcurrentHashMap<>();

    /**
     * CAS loop — lock-free, handles 5k concurrent threads on same tenant safely.
     * Returns true if token consumed, false if chunk exhausted (caller must sync Redis).
     */
    public boolean tryConsume(String tenantId) {
        LocalQuota quota = quotaCache.get(tenantId);
        if (quota == null) return false;

        while (true) {
            long current = quota.available.get();

            if (current <= 0) {
                return false;  // Chunk exhausted → caller (HybridRateLimitService) syncs Redis
            }

            // Atomic: only succeeds if available is still `current`
            // If another thread changed it between get() and here, CAS fails → retry
            if (quota.available.compareAndSet(current, current - 1)) {
                quota.consumed.incrementAndGet();
                return true;
            }
            // CAS lost the race — spin immediately, no OS call
        }
    }

    public void allocateQuota(String tenantId, long tokens, long globalLimit) {
        quotaCache.compute(tenantId, (k, existing) -> {
            if (existing == null) {
                // First allocation for this tenant
                return new LocalQuota(
                    new AtomicLong(tokens),   // available = full chunk
                    new AtomicLong(0),        // consumed = 0
                    new AtomicLong(tokens),   // allocated = chunk size
                    globalLimit,              // total tenant limit — fixed per tenant
                    System.currentTimeMillis()
                );
            }
            // Subsequent chunk allocation: reset consumed, top up available
            existing.available.addAndGet(tokens);
            existing.allocated.set(tokens);          // new chunk size
            existing.consumed.set(0);                // reset — fresh chunk starts
            existing.lastSync = System.currentTimeMillis();
            return existing;
        });
    }
}
```

### RedisQuotaManager — Lua Script (Atomic)

```java
private static final String ALLOCATE_QUOTA_SCRIPT = """
    local key = KEYS[1]
    local requested = tonumber(ARGV[1])
    local limit = tonumber(ARGV[2])
    local window_ms = tonumber(ARGV[3])
    local now = tonumber(ARGV[4])

    -- Expire old windows
    local window_key = key .. ':' .. math.floor(now / window_ms)

    local consumed = tonumber(redis.call('HGET', window_key, 'consumed') or '0')
    local available = limit - consumed

    if available <= 0 then
        return 0
    end

    local allocated = math.min(requested, available)
    redis.call('HINCRBY', window_key, 'consumed', allocated)
    redis.call('PEXPIRE', window_key, window_ms * 2)

    return allocated
    """;

public long allocateQuota(String tenantId, long requestedChunk, long limit) {
    String key = "ratelimit:" + tenantId;
    long windowMs = 60_000;  // 1 minute window
    long now = System.currentTimeMillis();

    return redisTemplate.execute(
        redisScript,
        List.of(key),
        String.valueOf(requestedChunk),
        String.valueOf(limit),
        String.valueOf(windowMs),
        String.valueOf(now)
    );
}
```

**Why Lua?**
- Entire script runs atomically in Redis (single-threaded Redis can't interleave)
- No TOCTOU race: check-and-allocate is one operation
- 1 network roundtrip instead of GET + SET (2 roundtrips)

### HybridRateLimitService — Coordinator

```java
@Service
public class HybridRateLimitService {

    private static final double CHUNK_PERCENT = 0.10;
    // Per-tenant lock to prevent thundering herd on Redis sync
    private final ConcurrentHashMap<String, ReentrantLock> tenantLocks = new ConcurrentHashMap<>();

    public boolean isAllowed(String tenantId) {
        // Fast path: local cache serves >90% of requests
        if (localQuotaManager.tryConsume(tenantId)) {
            return true;
        }

        // Slow path: local exhausted, sync with Redis
        return syncAndConsume(tenantId);
    }

    private boolean syncAndConsume(String tenantId) {
        ReentrantLock lock = tenantLocks.computeIfAbsent(tenantId, k -> new ReentrantLock());

        // tryLock: only ONE thread syncs with Redis, others spin on local briefly
        if (!lock.tryLock()) {
            // Another thread is syncing — give it a chance and retry local
            Thread.onSpinWait();
            return localQuotaManager.tryConsume(tenantId);
        }

        try {
            // Double-check: maybe another thread already synced
            if (localQuotaManager.tryConsume(tenantId)) {
                return true;
            }

            // Allocate next chunk from Redis
            long chunkSize = (long)(getLimit(tenantId) * CHUNK_PERCENT);
            long allocated = circuitBreaker.executeSupplier(
                () -> redisQuotaManager.allocateQuota(tenantId, chunkSize, getLimit(tenantId))
            );

            if (allocated > 0) {
                localQuotaManager.allocateQuota(tenantId, allocated);
                return localQuotaManager.tryConsume(tenantId);
            }

            return false;  // Global limit exhausted

        } catch (Exception e) {
            return handleDegradedMode(tenantId);
        } finally {
            lock.unlock();
        }
    }
}
```

### What Happens with 5k Concurrent Requests

```
Time 0: local available = 1000

Thread 1-1000:
  All call tryConsume() simultaneously
  All enter the CAS loop
  Thread 1 reads current=1000, tries CAS(1000→999) → WINS
  Thread 2 reads current=1000, tries CAS(1000→999) → FAILS (value is now 999)
  Thread 2 retries: reads current=999, tries CAS(999→998) → WINS
  ...continues until available=0

Threads 1001-5000:
  tryConsume() → available=0 → return false
  Call syncAndConsume()
  Only ONE thread acquires the ReentrantLock
  That thread calls Redis, gets 1000 more tokens
  Other 3999 threads: tryLock() fails → spin wait → retry local
  After sync: next 1000 succeed, rest get 429

RESULT: Exactly correct. No over-consumption. No deadlock.
```

---

## 5. Failure Scenarios

### Redis Completely Down

```
Detection: Resilience4j CircuitBreaker
  - failureRateThreshold=50 → opens at 50% failures
  - windowSize=100 calls → tracks last 100 calls
  - waitInOpenState=10s → tries recovery every 10s
```

```java
CircuitBreakerConfig config = CircuitBreakerConfig.custom()
    .failureRateThreshold(50)
    .waitDurationInOpenState(Duration.ofSeconds(10))
    .slidingWindowSize(100)
    .build();

private boolean handleDegradedMode(String tenantId) {
    LocalQuota quota = localQuotaManager.getQuota(tenantId);

    if (quota == null) {
        // No local state, no Redis → fail closed (safe)
        return false;
    }

    // Allow 10% of already-allocated local quota
    // Prevents abuse while keeping existing tenants partially alive
    long degradedLimit = quota.getAllocated() * 10 / 100;
    return quota.getConsumed() < degradedLimit;
}
```

**Decision by use case:**
- Payment API → fail closed (`return false`) — security > availability
- Public API → degraded mode (10%) — availability > strict accuracy
- Internal API → fail open (`return true`) — trust internal traffic

### Redis High Latency

```java
// Set 100ms timeout on Redis operations — fail fast instead of hanging
RedisURI uri = RedisURI.Builder.redis(host, port)
    .withTimeout(Duration.ofMillis(100))
    .build();

// Async with timeout
try {
    return CompletableFuture.supplyAsync(
        () -> redisQuotaManager.allocateQuota(tenantId, chunkSize, limit)
    ).get(100, TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    return handleDegradedMode(tenantId);
}
```

### Pod Crash

```
Data lost: local cache (all in-memory)
Data safe: Redis (global state persisted)

On crash:
  K8s detects via liveness probe (30s max)
  APISIX active health check detects in 2s
  APISIX removes pod from hash ring
  Consistent hash remaps affected tenants to other pods
  New pod for tenant → cache miss → fresh Redis allocation
  Possible over-allocation: ≤10% of limit (one lost chunk)

On graceful shutdown (SIGTERM → PreDestroy):
```

```java
@PreDestroy
public void flushOnShutdown() {
    localQuotaManager.getAllTenants().forEach(tenantId -> {
        LocalQuota quota = localQuotaManager.getQuota(tenantId);
        if (quota != null) {
            long unused = quota.available.get();
            if (unused > 0) {
                // Return unused tokens to Redis so they're not wasted
                redisTemplate.opsForHash().increment(
                    "ratelimit:" + tenantId, "consumed", -unused
                );
            }
        }
    });
}
```

### Redis Cluster Split-Brain

```yaml
# redis.conf — require quorum before accepting writes
min-replicas-to-write 2
min-replicas-max-lag 10

# Sentinel config — automatic failover
sentinel monitor mymaster 127.0.0.1 6379 2
sentinel down-after-milliseconds mymaster 5000
sentinel failover-timeout mymaster 10000
```

```java
// Detect split-brain — check cluster_state
public boolean isRedisHealthy() {
    String info = redisConnection.sync().clusterInfo();
    return info.contains("cluster_state:ok");
}
```

---

## 6. Failure Summary Table

| Failure | Detection | Impact | Recovery |
|---------|-----------|--------|----------|
| Redis down | Circuit breaker (2-10s) | Degraded mode | Auto — CB half-open retry |
| Redis slow | Timeout (100ms) | Slight latency | Auto — fail fast to local |
| Pod crash | Liveness probe (30s) / APISIX (2s) | Traffic reroute + one lost chunk | Auto — K8s restart |
| Network partition | Cluster health check | Inconsistent quotas | Manual — resolve partition |
| Pod scaling (3→6) | Immediate | ~16% cache misses (ring) | 1-2 min stabilize |
| Burst (5k reqs) | None needed | CAS handles atomically | N/A |

---

## 7. Interview Q&A

**Q: How does consistent hashing differ from round-robin modulo?**
> Modulo: `hash(tenant) % N` — changing N remaps ~50% of keys. Consistent ring: adding 1 node remaps only `1/N` keys. This is critical for local cache efficiency — we want the same tenant always hitting the same pod.

**Q: Why CAS instead of synchronized for 5k concurrent threads?**
> `synchronized` puts threads to sleep (OS context switch, ~5μs overhead). CAS retries at CPU speed (~5ns per retry). For tiny operations like `available--`, CAS is 100x cheaper under contention. See benchmarks: synchronized=50k TPS, CAS=200k TPS for same operation.

**Q: Why Lua scripts for Redis allocation?**
> Without Lua: GET → compute → HINCRBY is 3 separate commands; another thread can interleave between them (TOCTOU race). Lua runs as a single atomic unit in Redis's single-threaded event loop. Also reduces 3 network roundtrips to 1.

**Q: What is the max over-allocation possible?**
> Per pod: 1 chunk = 10% of limit. For 3 pods: 30% max over-allocation globally. This is acceptable because rate limiting is probabilistic. The alternative — syncing every request — costs 99% more Redis calls.

**Q: How do you handle clock skew across pods?**
> Use Redis TIME command for window boundaries. All pods query Redis for current time (cached for 1s). No local `System.currentTimeMillis()` for window calculation.

**Q: What happens to in-flight requests when a pod crashes?**
> In-flight requests are dropped (connection reset). K8s readiness probe stops new traffic before SIGTERM. The PreDestroy hook returns unused local tokens to Redis. Net result: ≤10% token loss (one chunk), no global state corruption.

