# Distributed Rate Limiting — Interview Reference

**Problem:** 45k TPS with Bucket4j requires one Redis call per request → bottleneck  
**Solution:** Hybrid local cache + Redis — 99% fewer Redis calls, <1ms latency

---

## Architecture

```
Client Request
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  APISIX Gateway                                      │
│  1. jwt-auth plugin   → validates JWT token          │
│  2. serverless func   → extracts tenant_id           │
│  3. proxy-rewrite     → sets X-Tenant-ID header      │
│  4. chash upstream    → routes by tenant_id          │
└──────────────────────────────────────────────────────┘
    │
    │  X-Tenant-ID: tenant-123 always → same pod
    │
    ├─── Pod 1 (tenant-A,B,C)
    ├─── Pod 2 (tenant-D,E,F)  ← tenant-123 always lands here
    └─── Pod 3 (tenant-G,H,I)
            │
            ▼ (10% of time — only on chunk exhaustion)
    ┌───────────────────┐
    │  Redis Cluster    │  ← Global source of truth
    │  tenant-123:8500  │
    └───────────────────┘
```

---

## Study Order

### Phase 1 — Concurrency (`CONCURRENCY_DEEP_DIVE.md`)

Read in this sequence — each concept builds on the previous:

```
1. The Problem       §1  → why count++ breaks under concurrency (3 CPU ops, not 1)
2. Mutex             §2  → OS blocking, context switch ~5μs, when to use
3. Semaphore         §3  → counting lock (N threads), connection pool model
4. ReentrantLock     §4  → explicit mutex + tryLock() — used in rate limiter
5. ReadWriteLock     §5  → concurrent reads OR exclusive write
6. CAS / Lock-Free   §6  → x86 LOCK CMPXCHG, 5k concurrent threads, 200k TPS
7. Deadlock          §7  → create one, detect via jstack, fix via lock ordering
8. Thread States     §8  → NEW→RUNNABLE→BLOCKED/WAITING→TERMINATED + context switch
9. Connection Pool   §9  → Semaphore in practice, ConcurrentHashMap<Connection,…> key
10. Why CAS here     §10 → decision table: why not synchronized/semaphore/locks
```

**Before moving to Phase 2, make sure you can answer:**
- What is a context switch and why does it cost ~5μs?
- Why does CAS never block a thread?
- What is BLOCKED vs WAITING thread state?
- Why is Semaphore right for connection pooling but wrong for rate limiting tokens?

---

### Phase 2 — Rate Limiting 45k TPS (`RATELIMIT_SYSTEM_DESIGN.md`)

Now the rate limiter makes full sense — CAS, ReentrantLock, Lua atomicity all connect:

```
1. Why Not Pure Redis  §1  → 45k TPS = 45k Redis ops → bottleneck math
2. APISIX Config       §2  → jwt-auth → extract tenant_id → chash upstream → headless K8s
3. Consistent Hashing  §3  → same tenant → same pod → local cache hits 90%+
4. Hybrid Algorithm    §4  → CAS loop (local) + Lua script (Redis) + ReentrantLock (sync)
5. 5k Concurrent       §4  → thread-by-thread walkthrough of what CAS does
6. Failure Scenarios   §5  → Redis down/slow/split-brain, pod crash, pod scaling
7. Interview Q&A       §7  → how to articulate design decisions under interview pressure
```

**Checkpoint questions:**
- Why does 10% chunk size give 99% fewer Redis calls?
- How do exactly 1000 of 5000 threads succeed — and not 1001?
- What happens step-by-step when Redis goes down mid-traffic?
- Why Lua script instead of GET + HINCRBY as two separate commands?

---

## Files in This Repo

| File | Purpose |
|------|---------|
| `RATELIMIT_SYSTEM_DESIGN.md` | APISIX config, consistent hashing, local+Redis algorithm, failure handling |
| `CONCURRENCY_DEEP_DIVE.md` | CAS, Mutex, Semaphore, Locks, Deadlock, Thread states, Connection pool |
| `HybridRateLimitService.java` | Main service coordinating local cache + Redis |
| `LocalQuotaManager.java` | Thread-safe local cache with CAS |
| `RedisQuotaManager.java` | Atomic Redis operations with Lua scripts |
| `CASConcurrencyDemo.java` | Debuggable CAS demo — run to see it live |
| `ConcurrencyMechanismsDemo.java` | Mutex, Semaphore, ReentrantLock live demos |
| `DeadlockDemo.java` | Deadlock creation + avoidance |
| `ProductionConnectionPool.java` | Semaphore-based connection pool |

---

## Quick Start

```bash
docker run -d -p 6379:6379 redis:latest
./mvnw spring-boot:run
curl -H "X-Tenant-ID: tenant-123" http://localhost:8080/api/data
curl http://localhost:8080/api/status/tenant-123
```

---

## Performance Numbers

| Metric | Value |
|--------|-------|
| Throughput | 50k+ TPS |
| P50 latency | 0.05ms (local cache hit) |
| P99 latency | 1.8ms (includes Redis sync) |
| Redis call reduction | 99% (45k → ~450 ops/sec) |
| Cache hit rate | 94% |

---

## Key Concepts

| Concept | One Line |
|---------|----------|
| CAS | Atomic CPU instruction — no OS, no lock, no blocking |
| Consistent Hash | Same tenant → same pod → local cache always hits |
| Hybrid Cache | Allocate chunks from Redis, serve 90% from local |
| Circuit Breaker | Redis down → degraded mode using local state |
| Lua Script | Atomic Redis quota allocation in 1 network roundtrip |
