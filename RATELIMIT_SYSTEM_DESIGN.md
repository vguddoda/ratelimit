# Rate Limiting System Design — Deep Dive

Topics: APISIX setup, consistent hashing, hybrid local cache + Redis Bucket4j, CAS, failure handling

---

## 1. Why Not Pure Bucket4j?

With Bucket4j every request → 1 Redis call:
- 45k TPS = 45k Redis ops/sec
- Redis is single-threaded; network RTT adds up
- P99 latency dominated by Redis call overhead

**Fix:** Allocate tokens in *chunks* from Bucket4j Redis, serve most requests from local in-memory cache.

**For all examples in this doc, assume a given tenant has:**
```
globalLimit = 60 tokens per minute
chunkSize   = 10% of 60 = 6 tokens per chunk
refill      = refillGreedy(60, 60s) = 1 token/second (continuous)
```

```
Without chunking:  60 requests → 60 Redis calls (Bucket4j per request)
With 10% chunks:   60 requests → 10 Redis calls (one per chunk of 6) → 83% reduction
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

### Config Used in All Examples Below

```
globalLimit    = 60 tokens per minute
chunkSize      = 10% of 60 = 6 tokens per chunk
Bucket4j refill = refillGreedy(60, 60s) = 1 token/second (continuous)
maxChunkAge    = 60 seconds (= window duration)
```

### Architecture (3 layers)

```
Layer 1: LocalQuotaManager (CAS, in-memory, per-pod)
  ↓ chunk exhausted / expired / no denial cached
Layer 2: HybridRateLimitService (per-tenant ReentrantLock, circuit breaker)
  ↓ calls
Layer 3: RedisQuotaManager → Bucket4j ProxyManager (Token Bucket in Redis)
```

### The 4 Results from tryConsume() — KEY DISTINCTION

```
ALLOW         → token consumed from local chunk. Done. No Redis.
DENY          → available=0. Chunk FULLY consumed. Nothing to return. Get next chunk from Bucket4j.
EXPIRED       → chunk still has tokens but is OLD. Return unused to Bucket4j, then get fresh chunk.
DENIED_CACHED → Bucket4j already said "exhausted" recently. Instant 429. ZERO Redis calls.
```

### Token Bucket in Redis (Bucket4j)

```java
BucketConfiguration.builder()
    .addLimit(Bandwidth.builder()
        .capacity(60)                // max burst: 60 tokens
        .refillGreedy(60, 60s)       // 1 token/second continuous refill
        .initialTokens(60)           // start with full bucket
        .build())
    .build();
```

Refill is GREEDY (continuous):
```
  t=0s:  60 tokens (full)
  Consume 6: 54 left
  t=6s:  54 + 6 refilled = 60 again (capped at capacity)
  Refill rate: 60 tokens / 60 seconds = exactly 1 token/second
```

### Token Allocation Flow

```
Request arrives at pod
       │
       ▼
  [LocalQuotaManager.tryConsume()]
       │
       ├── DENIED_CACHED → instant 429, ZERO Redis calls
       │     (Bucket4j said "exhausted, wait Nms" earlier — still waiting)
       │
       ├── ALLOW → CAS decremented local available → done (NO Redis)
       │
       ├── DENY → chunk empty (available=0, all 6 tokens consumed)
       │      │
       │      ▼
       │   [HybridRateLimitService.syncAndConsume()]
       │      │
       │      ├── tryLock(tenantId) → only 1 thread enters
       │      │      │
       │      │      ▼
       │      │   [RedisQuotaManager.consumeChunk()]
       │      │   Bucket4j.tryConsumeAndReturnRemaining(6)  ← 1 Redis call
       │      │      │
       │      │      ├── granted=6 → clearDenied, allocateChunk locally → serve
       │      │      ├── granted=3 → partial chunk (end of budget) → serve
       │      │      └── granted=0 → markDenied(nanosToWait) → 429
       │      │                       ↑ cache "exhausted" locally
       │      │                       next requests → DENIED_CACHED → instant 429
       │      │
       │      └── tryLock fails → wait 50ms → retry local
       │
       └── EXPIRED → chunk too old (slow traffic)
              │
              ▼
           Return unused to Bucket4j (addTokens)
           Invalidate local chunk
           syncAndConsume() → get fresh chunk
```

### LocalQuotaManager — CAS Loop (actual code, with denial cache)

```java
public ConsumeResult tryConsume(String tenantId) {

    // ── Check denial cache FIRST (avoids ALL Redis calls when exhausted) ──
    AtomicLong deniedUntil = deniedUntilMs.get(tenantId);
    if (deniedUntil != null && System.currentTimeMillis() < deniedUntil.get()) {
        return ConsumeResult.DENIED_CACHED;  // instant 429
    }

    LocalQuota quota = quotaCache.getIfPresent(tenantId);
    if (quota == null) return ConsumeResult.DENY;

    // Slow traffic expiry
    if (System.currentTimeMillis() - quota.allocatedAtMs > maxChunkAgeMs) {
        return ConsumeResult.EXPIRED;
    }

    // CAS loop: atomically decrement available
    while (true) {
        long current = quota.available.get();
        if (current <= 0) return ConsumeResult.DENY;

        if (quota.available.compareAndSet(current, current - 1)) {
            quota.consumed.incrementAndGet();
            return ConsumeResult.ALLOW;
        }
        Thread.onSpinWait();
    }
}
```

### RedisQuotaManager — Returns nanosToWaitForRefill (actual code)

```java
public ChunkResult consumeChunk(String tenantId, long chunkSize, long globalLimit) {
    BucketProxy bucket = getOrCreateBucket(tenantId, globalLimit);

    ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(chunkSize);

    if (probe.isConsumed()) return new ChunkResult(chunkSize, 0);

    // Partial: take whatever remains
    long remaining = probe.getRemainingTokens();
    if (remaining > 0) {
        ConsumptionProbe partial = bucket.tryConsumeAndReturnRemaining(remaining);
        if (partial.isConsumed()) return new ChunkResult(remaining, 0);
    }

    // Exhausted — return nanosToWait so caller can cache the denial
    return new ChunkResult(0, probe.getNanosToWaitForRefill());
}

// granted=tokens granted, nanosToWaitForRefill=how long until 1 token refills
public record ChunkResult(long granted, long nanosToWaitForRefill) {}
```

### HybridRateLimitService — syncAndConsume with denial caching (actual code)

```java
return circuitBreaker.executeSupplier(() -> {
    long globalLimit = redisQuotaManager.getTenantLimit(tenantId);
    long chunkSize = Math.max(1, (globalLimit * chunkPercent) / 100);

    ChunkResult result = redisQuotaManager.consumeChunk(tenantId, chunkSize, globalLimit);

    if (result.granted() > 0) {
        localQuotaManager.clearDenied(tenantId);     // was denied before, now it's not
        localQuotaManager.allocateChunk(tenantId, result.granted(), globalLimit);
        return localQuotaManager.tryConsume(tenantId) == ConsumeResult.ALLOW;
    }

    // Exhausted: cache denial locally — next N ms get instant 429, no Redis
    localQuotaManager.markDenied(tenantId, result.nanosToWaitForRefill());
    return false;
});
```

---

## 5. Case Analysis (all cases: 60 tokens/min, chunk=6, refill=1/sec)

### CASE 1: Initial Request (no local chunk)

```
t=0s: First request. No local cache.
tryConsume → DENY (quota=null)
syncAndConsume:
  Bucket4j: creates bucket with 60 tokens (initialTokens=60)
  consumeChunk(6) → granted=6, 54 remaining in Redis
  allocateChunk: available=6, consumed=0
  tryConsume → ALLOW (available: 6→5)
Redis calls: 1
```

### CASE 2: Normal Steady Traffic (1 req/sec)

```
t=0s:  Req 1:  sync → Bucket4j consume(6) → local available=5    [1 Redis call]
t=1s:  Req 2:  tryConsume → ALLOW (5→4)                           [0 Redis calls]
t=2s:  Req 3:  ALLOW (4→3)
t=3s:  Req 4:  ALLOW (3→2)
t=4s:  Req 5:  ALLOW (2→1)
t=5s:  Req 6:  ALLOW (1→0)
t=6s:  Req 7:  DENY (available=0) → sync                          [1 Redis call]
  Bucket4j: 54 remaining + ~6 refilled = 60 → consume(6) → 54 left
  local: available=6 again
t=7s:  Req 8:  ALLOW (5)                                          [0 Redis calls]
...
t=59s: Req 60: last request this minute

TOTAL: 60 requests, 10 Redis calls (one per chunk of 6)
Pure Bucket4j: 60 Redis calls → 83% reduction
```

### CASE 3: Chunk Exhausted in 5 Seconds (THE KEY CASE)

```
t=0s:   Chunk allocated: 6 tokens from Bucket4j (54 left in Redis)
t=0-5s: 6 requests, each CAS-consumes 1 → available: 6→0

t=5s:   Req 7: tryConsume → DENY (available=0)
  → This is DENY (not EXPIRED): chunk is 5 seconds old < maxChunkAge(60s)
  → Nothing to return: available=0, all 6 tokens were used
  → syncAndConsume:
      Bucket4j: 54 remaining + ~5 refilled (5s × 1/s) = ~59
      consumeChunk(6) → granted=6, ~53 remaining
      local: available=6 (fresh chunk)

  WHY THIS IS CORRECT:
    Chunk empty → just grab next chunk, no return needed
    Bucket4j refilled ~5 tokens during those 5 seconds
    Tenant used 12 of 60 → NOT rate-limited yet
    Bucket4j enforces the global limit; we just batch the calls

  Per minute at max rate (1 req/sec):
    60 requests → 10 chunks × 6 tokens → 10 Redis calls
    If tenant sends request #61 → Bucket4j returns 0 → 429
```

### CASE 4: Exhaustion + Denial Cache (the NEW optimization)

```
t=0s:   All 60 tokens consumed (10 chunks × 6 tokens, 10 Redis calls)

t=0s:   Req 61: tryConsume → DENY (local empty) → syncAndConsume()
  Bucket4j: 0 remaining, nanosToWaitForRefill = 1_000_000_000 (1 second)
  → markDenied(tenantId, 1_000_000_000)
  → deniedUntilMs = now + 1000ms
  → return false → 429

t=0.2s: Req 62: tryConsume → DENIED_CACHED (deniedUntilMs > now)
  → instant 429, ZERO Redis calls ← THIS IS THE OPTIMIZATION

t=0.5s: Req 63: tryConsume → DENIED_CACHED → instant 429

t=1.0s: Req 64: tryConsume → denial expired (deniedUntilMs < now)
  → DENY → syncAndConsume()
  → Bucket4j: 1 token refilled (1 second × 1/sec)
  → consumeChunk(6) → only 1 available → partial grant(1)
  → local: available=1 → serve 1 request → then DENY again

WITHOUT denial cache:
  Reqs 61-100 at t=0-1s: 40 Redis calls, all returning 0 → wasted
WITH denial cache:
  Req 61: 1 Redis call (discovers exhaustion)
  Reqs 62-100: DENIED_CACHED → ZERO Redis calls
  Req 64 at t=1s: 1 Redis call (denial expired, retry)
  TOTAL: 2 Redis calls instead of 40 → 95% reduction in wasted calls
```

### CASE 5: Slow Traffic (1 req every 2 minutes)

```
t=0:00: Req 1: no local → sync → Bucket4j consume(6) → local available=5
  Bucket4j: 60-6 = 54 remaining

t=2:00: Req 2 (120s later):
  tryConsume: DENIED_CACHED? No (no denial set). Check quota.
  Chunk age = 120s > maxChunkAge(60s) → EXPIRED

  EXPIRED PATH (different from DENY):
    1. Return 5 unused tokens to Bucket4j: addTokens(5)
       Bucket4j: refilled to 60 long ago + 5 returned → still 60 (capped)
    2. Invalidate local chunk
    3. syncAndConsume: Bucket4j consume(6) → granted=6 → local: available=5

t=4:00: Req 3: EXPIRED again → return 5 → re-sync → fresh chunk

  WHY EXPIRED RETURNS TOKENS (DENY DOES NOT):
    DENY:    available=0 → nothing to return → just get next chunk
    EXPIRED: available=5 → those 5 were pre-consumed from Bucket4j but never used
             Return them so they're not wasted

  WHY NOT KEEP OLD CHUNK:
    Bucket4j refills 1/sec. After 120s → fully refilled to 60.
    Old chunk: tenant sees 5 tokens, unaware that Redis has 60.
    Expiry → re-sync → tenant gets fresh view of actual capacity.
```

### CASE 6: Burst (5000 concurrent requests)

```
5000 threads arrive at t=0s, local available=0

All → DENY → syncAndConsume()
Thread 1 wins tryLock → Bucket4j consume(6) → local: 6
Threads 2-6: wait 50ms → retry local → CAS → ALLOW (6→0)
Thread 7 wins next lock → Bucket4j consume(6) → local: 6
...
After 10 syncs: 10 × 6 = 60 tokens → all 60 allowed
Thread 61: Bucket4j returns 0 → markDenied → 429
Threads 62-5000: DENIED_CACHED → instant 429, zero Redis calls

Redis calls: 11 (10 grants + 1 denial)
Without denial cache: ~4990 Redis calls all returning 0
```

### CASE 7: Pod Shutdown

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

### CASE 8: Redis Down

```
CircuitBreaker opens after 50% failure in last 100 Redis calls.
degradedMode: allow degradedPercent% of existing local chunk.
  chunk=6, degradedPercent=10 → buffer = 0.6 → 0 → deny all
  (For larger limits: chunk=1000, 10% = 100 → meaningful buffer)
After 10s: CB half-open → 5 test calls → Redis back → CLOSED
```

---

## 6. Failure Summary

| Failure | Detection | Impact | Recovery |
|---------|-----------|--------|----------|
| Redis down | Circuit breaker (2-10s) | Degraded mode | Auto — CB half-open retry |
| Redis slow | Timeout (500ms) | Slight latency | Auto — fail fast |
| Pod crash | APISIX health (2s) | Reroute + 1 lost chunk | Auto — K8s restart |
| Pod scale 3→6 | Immediate | ~16% cache misses | 1-2 min stabilize |
| 5k concurrent | None needed | CAS + denial cache | N/A |
| Slow traffic | Chunk expiry (60s) | Re-sync | Auto — return unused |
| Post-exhaustion spam | Denial cache | **Zero Redis calls** | Auto — expires on refill |

---

## 7. Interview Q&A

**Q: Who makes the final rate limit decision — local cache or Redis?**
> Always Bucket4j (Redis). Local cache is only a *batch optimization*. It holds pre-consumed tokens that Bucket4j already approved. When local is empty, we go to Bucket4j. When Bucket4j says "exhausted", that's final — we cache that denial locally to avoid redundant Redis calls. Local cache NEVER overrides Bucket4j.

**Q: What happens after all 60 tokens are used at t=30s? The next 30 seconds?**
> Bucket4j returns `nanosToWaitForRefill` (e.g. 1 second). We store `deniedUntilMs = now + 1s`. All requests in that 1s → `DENIED_CACHED` → instant 429 from local memory. After 1s: denial expires, we call Bucket4j again. If 1 token refilled, we get a partial chunk. Repeat. No wasted Redis calls.

**Q: 60 tokens/min with chunk=6 — how many Redis calls per minute?**
> Normal: 10 calls (60/6). With post-exhaustion denial cache, even in burst scenarios: ~11 calls (10 grants + 1 denial). Without denial cache a burst of 5000 requests after exhaustion would cause ~4990 wasted Redis calls all returning 0.

**Q: DENY vs EXPIRED — when does each happen?**
> DENY = `available=0`, chunk fully consumed within maxChunkAge. All tokens used, nothing to return. Just get next chunk from Bucket4j.
> EXPIRED = chunk still has tokens but is older than maxChunkAge (60s). Slow traffic: tenant didn't use all tokens. Must return unused to Bucket4j (they were pre-consumed from Redis). Then get fresh chunk.

**Q: Why not just use a fixed-window counter in Redis (INCR/EXPIRE)?**
> Fixed window has burst-at-boundary problem: 60 requests at t=59s + 60 at t=61s = 120 in 2 seconds. Token bucket with greedy refill handles this correctly — tokens accumulate gradually, burst capacity is naturally limited by the bucket capacity.

**Q: Why `addTokens()` instead of just letting Bucket4j refill naturally?**
> Without `addTokens()`: 5 unused tokens from an expired chunk are "consumed" in Bucket4j but never used. Tenant effectively gets 55/min instead of 60/min. With `addTokens()`: those 5 go back, bucket capacity stays accurate. Especially important for slow-traffic tenants.

---

## 8. Testing

```bash
# Start Redis + app
docker run -d -p 6379:6379 redis:latest
./mvnw spring-boot:run

# Basic request (creates Bucket4j bucket + first chunk)
curl -H "X-Tenant-ID: tenant-123" http://localhost:8080/api/data

# Check status (local vs global state)
curl http://localhost:8080/api/status/tenant-123

# Run all integration tests (11 tests)
./mvnw test -Dtest=DemoApplicationTests

# Tests use limit=20, chunk=2 for speed.
# Cases tested:
#   1. Basic request allowed
#   2. Rate limit enforced at 20 requests
#   3. Separate tenants have independent limits
#   4. 30 concurrent requests → exactly 20 succeed (CAS correctness)
#   5. Multi-chunk flow (chunk=2, 15 requests → multiple Redis syncs)
#   6. Status endpoint shows correct state
#   7. Reset clears both local + Redis
#   8. Bucket4j refill: exhaust → wait 7s → tokens available again
#   9. 5 tenants × 25 concurrent requests each
#  10. Fallback to IP when no X-Tenant-ID header
#  11. Health endpoint always returns 200
```
