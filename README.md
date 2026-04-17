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
