# Rate Limiting System Design — Deep Dive

Topics: APISIX setup, consistent hashing, hybrid local cache + Redis Bucket4j, CAS, failure handling

---

## 1. Why Not Pure Bucket4j?

With Bucket4j every request → 1 Redis call:
- 45k TPS = 45k Redis ops/sec
- Redis is single-threaded; network RTT adds up
- P99 latency dominated by Redis call overhead

**Fix:** Allocate tokens in *chunks* from Bucket4j Redis, serve most requests from local in-memory cache.

```
Without chunking:  10,000 requests → 10,000 Redis calls (Bucket4j)
With 10% chunks:   10,000 requests → ~10 Redis calls    (99% reduction)
```

Bucket4j is still the source of truth — it manages the token bucket in Redis with proper
refill rate, capacity, atomicity. We just don't call it on every request.

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
tenant-123 → Pod 1 → cache miss → Bucket4j consume(10) → local: 10 tokens
tenant-123 → Pod 2 → cache miss → Bucket4j consume(10) → local: 10 tokens (WASTED!)
tenant-123 → Pod 3 → cache miss → Bucket4j consume(10) → local: 10 tokens (WASTED!)
Total consumed from Bucket4j: 30 tokens for maybe 3 requests
```

### With Consistent Hashing

```
tenant-123 → Pod 2 (always)
  Request 1: cache miss → Bucket4j consume(10) → local: 10 tokens
  Request 2-10: local cache hit — zero Redis calls
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

## 4. Hybrid Local + Redis Bucket4j Algorithm

### Architecture (3 layers)

```
Layer 1: LocalQuotaManager (CAS, in-memory, per-pod)
  ↓ chunk exhausted / expired
Layer 2: HybridRateLimitService (per-tenant ReentrantLock, circuit breaker)
  ↓ calls
Layer 3: RedisQuotaManager → Bucket4j ProxyManager (Token Bucket in Redis)
```

### Token Bucket in Redis (Bucket4j)

```java
// Bucket4j configuration for each tenant
BucketConfiguration.builder()
    .addLimit(Bandwidth.builder()
        .capacity(100)              // max burst: 100 tokens
        .refillGreedy(100, 60s)     // refill 100 tokens per 60 seconds (continuously)
        .initialTokens(100)         // start full
        .build())
    .build();
```

Refill is GREEDY (continuous):
```
  t=0:   100 tokens (full capacity)
  Consume 10: 90 left
  t=6s:  ~10 tokens refilled → back to 100 (capped at capacity)
  t=60s: 100 tokens refilled total in this period
```

### Token Allocation Flow

```
Request arrives at pod
       │
       ▼
  [LocalQuotaManager.tryConsume()]
       │
       ├── ALLOW    → CAS decremented local available → done (NO Redis)
       │
       ├── DENY     → chunk empty (available=0)
       │      │
       │      ▼
       │   [HybridRateLimitService.syncAndConsume()]
       │      │
       │      ├── tryLock(tenantId) → only 1 thread enters
       │      │      │
       │      │      ▼
       │      │   [RedisQuotaManager.consumeChunk()]
       │      │   bucket4j.tryConsume(chunkSize=10)  ← 1 Redis call
       │      │      │
       │      │      ├── granted=10 → allocateChunk locally → serve request
       │      │      ├── granted=3  → partial chunk (end of budget)
       │      │      └── granted=0  → 429 (global limit exhausted)
       │      │
       │      └── tryLock fails → another thread syncing → spin + retry local
       │
       └── EXPIRED  → chunk too old (slow traffic)
              │
              ▼
           returnUnusedToRedis (bucket4j.addTokens)
           invalidate local chunk
           syncAndConsume() → get fresh chunk
```

### LocalQuotaManager — CAS Loop (actual code)

```java
public ConsumeResult tryConsume(String tenantId) {
    LocalQuota quota = quotaCache.getIfPresent(tenantId);
    if (quota == null) return ConsumeResult.DENY;

    // Slow traffic expiry: chunk sitting longer than window duration
    long age = System.currentTimeMillis() - quota.allocatedAtMs;
    if (age > maxChunkAgeMs) return ConsumeResult.EXPIRED;

    // CAS loop: atomically decrement available
    while (true) {
        long current = quota.available.get();
        if (current <= 0) return ConsumeResult.DENY;

        if (quota.available.compareAndSet(current, current - 1)) {
            quota.consumed.incrementAndGet();
            return ConsumeResult.ALLOW;
        }
        Thread.onSpinWait(); // CPU hint for spin-waiting
    }
}
```

### RedisQuotaManager — Bucket4j Integration (actual code)

```java
public long consumeChunk(String tenantId, long chunkSize, long globalLimit) {
    BucketProxy bucket = getOrCreateBucket(tenantId, globalLimit);

    // Try full chunk
    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(chunkSize);
    if (probe.isConsumed()) return chunkSize;

    // Partial: take whatever remains
    long remaining = probe.getRemainingTokens();
    if (remaining > 0) {
        ConsumptionProbe partial = bucket.tryConsumeAndReturnRemaining(remaining);
        if (partial.isConsumed()) return remaining;
    }

    return 0; // global limit exhausted
}
```

### HybridRateLimitService — syncAndConsume (actual code)

```java
private boolean syncAndConsume(String tenantId) {
    ReentrantLock lock = tenantLocks.computeIfAbsent(tenantId, k -> new ReentrantLock());

    if (!lock.tryLock()) {
        // Another thread syncing → spin + retry local
        Thread.onSpinWait();
        return localQuotaManager.tryConsume(tenantId) == ConsumeResult.ALLOW;
    }

    try {
        // Double-check: maybe another thread already synced
        if (localQuotaManager.tryConsume(tenantId) == ConsumeResult.ALLOW) return true;

        return circuitBreaker.executeSupplier(() -> {
            long globalLimit = redisQuotaManager.getTenantLimit(tenantId);
            long chunkSize = Math.max(1, (globalLimit * chunkPercent) / 100);

            long granted = redisQuotaManager.consumeChunk(tenantId, chunkSize, globalLimit);

            if (granted > 0) {
                localQuotaManager.allocateChunk(tenantId, granted, globalLimit);
                return localQuotaManager.tryConsume(tenantId) == ConsumeResult.ALLOW;
            }
            return false; // 429
        });

    } catch (Exception e) {
        return handleDegradedMode(tenantId);
    } finally {
        lock.unlock();
    }
}
```

---

## 5. Case Analysis

### CASE 1: Initial Request (no local chunk)

```
tryConsume → DENY (quota=null)
syncAndConsume:
  Bucket4j: creates bucket in Redis with 100 tokens (initialTokens=100)
  consumeChunk(10) → granted=10, 90 remaining in Redis
  allocateChunk locally: available=10, consumed=0
  tryConsume → ALLOW (available: 10→9)
```

### CASE 2: Normal Flow (100 req/min, steady)

```
Requests 1-10:  tryConsume → ALLOW (available 10→0)     0 Redis calls
Request 11:     tryConsume → DENY → syncAndConsume()     1 Redis call
  Bucket4j: 90 remaining → consume 10 → local: available=10
Requests 12-21: tryConsume → ALLOW (local)               0 Redis calls
...
Request 91:     syncAndConsume → Bucket4j: 10 remaining   1 Redis call
Request 101:    syncAndConsume → Bucket4j: 0 remaining → 429

Total: 100 requests, 10 Redis calls (99% reduction)
```

### CASE 3: 5000 Concurrent Requests (spike)

```
local available = 10, then 5000 threads arrive at once

All 5000 enter CAS loop:
  Threads 1-10: each wins a CAS slot → ALLOW (available 10→0)
  Threads 11-5000: available=0 → DENY → syncAndConsume()
    Thread 11 wins tryLock → calls Bucket4j → gets 10 more → local: 10
    Threads 12-5000: tryLock fails → spin + retry local
    Threads 12-21: pick up the new 10 tokens → ALLOW
    Threads 22-5000: DENY again → syncAndConsume → eventually 429

After 10 rounds of sync: 100 requests allowed, 4900 get 429
Exactly correct. No over-consumption. No deadlock.
```

### CASE 4: Slow Traffic (rate=100/min, 1 req every 10 minutes)

```
10:00 - Request 1:  no local → sync → Bucket4j consume(10) → available=9
          Bucket4j: 100-10 = 90 remaining in Redis

10:01 - (60s later): Bucket4j has refilled 100 tokens → back to 100 in Redis
          Local chunk was allocated at 10:00, age=60s = maxChunkAgeMs → EXPIRED

10:10 - Request 2:  tryConsume → EXPIRED (age=600s > 60s)
          returnUnusedToRedis: 9 unused → bucket4j.addTokens(9)
          syncAndConsume → Bucket4j: 100 tokens (refilled) → consume 10 → available=9

10:20 - Request 3:  EXPIRED again → return 9 → re-sync → fresh chunk

WITHOUT expiry: tenant drains 9 old tokens over hours, wasting refilled capacity
WITH expiry:    tenant gets fresh view of Redis bucket every 60s
```

### CASE 5: Pod Shutdown

```java
@PreDestroy
public void shutdown() {
    for (String tenantId : localQuotaManager.getAllTenants()) {
        long unused = localQuotaManager.getUnusedTokens(tenantId);
        if (unused > 0) {
            redisQuotaManager.returnTokens(tenantId, unused, limit);
        }
    }
    localQuotaManager.invalidateAll();
}
```

Unused tokens returned via `bucket4j.addTokens()`. No token waste.
New pod gets fresh chunk from Redis.

---

## 6. Failure Scenarios

### Redis Completely Down

```java
CircuitBreakerConfig.custom()
    .failureRateThreshold(50)           // open at 50% failure rate
    .waitDurationInOpenState(10s)       // retry after 10s
    .slidingWindowSize(100)             // track last 100 calls
    .build();

private boolean handleDegradedMode(String tenantId) {
    LocalQuota quota = localQuotaManager.getQuota(tenantId);
    if (quota == null) return false;  // fail closed

    long buffer = quota.getAllocated() * degradedPercent / 100;
    return quota.getConsumed() < buffer;
}
```

**Decision by use case:**
- Payment API → `degradedPercent=0` → fail closed (security > availability)
- Public API → `degradedPercent=10` → small buffer (availability > strict accuracy)
- Internal API → `degradedPercent=100` → fail open (trust internal traffic)

### Pod Crash

```
Data lost: local cache (in-memory)
Data safe: Bucket4j Redis bucket (persisted, auto-refilling)

Recovery:
  APISIX health check detects in 2s → removes from hash ring
  K8s restarts pod → consistent hash remaps tenant to another pod
  New pod: cache miss → fresh Bucket4j allocation
  Possible loss: ≤1 chunk of tokens (≤10% of limit)
```

### Failure Summary

| Failure | Detection | Impact | Recovery |
|---------|-----------|--------|----------|
| Redis down | Circuit breaker (2-10s) | Degraded mode | Auto — CB half-open retry |
| Redis slow | Timeout (500ms) | Slight latency | Auto — fail fast to local |
| Pod crash | APISIX health (2s) | Traffic reroute + 1 lost chunk | Auto — K8s restart |
| Pod scaling (3→6) | Immediate | ~16% cache misses | 1-2 min stabilize |
| 5k concurrent | None needed | CAS handles atomically | N/A |
| Slow traffic | Chunk expiry | Re-sync on next request | Auto — return unused |

---

## 7. Interview Q&A

**Q: How does this differ from using Bucket4j directly?**
> We still use Bucket4j as the source of truth in Redis — same token bucket algorithm, same refill rate, same atomicity. The only difference: instead of calling `bucket.tryConsume(1)` on every request (45k Redis calls/sec), we call `bucket.tryConsume(chunkSize)` once to grab a batch of tokens, then serve the next chunk-1 requests from local CAS cache. 99% fewer Redis calls, same correctness.

**Q: What if the tenant sends 1 request per hour but limit is 100/min?**
> Bucket4j's `refillGreedy(100, 60s)` refills continuously — by the time the next request comes (say 1 hour later), the Redis bucket is full (100 tokens, capped at capacity). The local chunk has expired (age > maxChunkAgeMs=60s), so we return unused tokens and re-fetch. The tenant always gets a fresh view of their refilled bucket.

**Q: Why CAS instead of synchronized for local consumption?**
> For `available--`, `synchronized` puts 4999 threads to sleep (5μs context switch each). CAS retries at CPU speed (~5ns). Under 5k contention: CAS=25ms total, synchronized=250ms. 10x faster.

**Q: Why partial grant in consumeChunk()?**
> End of budget: Bucket4j has 3 tokens left, we ask for 10. Instead of denying and wasting 3, we ask for the 3 remaining. Maximizes utilization.

**Q: Why returnTokens on chunk expiry instead of letting them expire?**
> Without return: those tokens are "consumed" in Bucket4j but never used. Effectively reduces the tenant's rate limit. With `addTokens()`, they go back to the Redis bucket and can be consumed by future chunks. Correct behavior.

---

## 8. Testing

```bash
# Start Redis + app
docker run -d -p 6379:6379 redis:latest
./mvnw spring-boot:run

# Basic request (creates Bucket4j bucket + first chunk)
curl -H "X-Tenant-ID: tenant-123" http://localhost:8080/api/data

# Check status (see local vs global state)
curl http://localhost:8080/api/status/tenant-123

# Burst test: send 200 requests rapidly
for i in $(seq 1 200); do
  curl -s -o /dev/null -w "%{http_code}\n" \
    -H "X-Tenant-ID: tenant-burst" http://localhost:8080/api/data
done | sort | uniq -c
# Should show: ~100 of 200, rest 429 (depends on refill timing)

# Reset and retry
curl -X POST http://localhost:8080/api/reset/tenant-burst
```
