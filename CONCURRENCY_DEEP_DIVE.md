# Concurrency Deep Dive — Interview Reference

Topics: CAS/lock-free, Mutex, Semaphore, ReentrantLock, ReadWriteLock, Deadlock, Thread States, Connection Pool

---

## 1. The Concurrency Problem

```java
// BROKEN — count++ is NOT atomic, it's 3 operations:
// 1. READ count from memory
// 2. ADD 1
// 3. WRITE back to memory
// Two threads can interleave between step 1 and 3
class Counter {
    int count = 0;
    void increment() { count++; }  // race condition
}
```

Every synchronization mechanism below exists to solve this in different trade-off points.

---

## 2. Mutex (Mutual Exclusion)

**What:** Binary lock — only 1 thread in critical section at a time.  
**Ownership rule:** The thread that locked MUST be the thread that unlocks.  
**Analogy:** Bathroom with 1 key.

### Java: `synchronized`

```java
class Counter {
    private int count = 0;
    private final Object lock = new Object();

    public void increment() {
        synchronized (lock) {
            count++;  // ONLY one thread here at a time
        }
    }
}
```

### What happens at OS level

```
Thread A: synchronized(lock) → calls OS mutex_lock()
  Mutex is FREE → Thread A acquires it, continues
  
Thread B: synchronized(lock) → calls OS mutex_lock()
  Mutex is LOCKED by A → OS puts Thread B to SLEEP
  Thread B state: BLOCKED
  OS context switch → runs other threads
  
Thread A: exits synchronized block → calls OS mutex_unlock()
  OS wakes Thread B (moves to RUNNABLE)
  Thread B acquires mutex, continues
```

### Cost

```
Uncontended (no waiting thread): ~50-100 ns
Contended (OS puts thread to sleep):
  syscall overhead:     ~1 μs
  context switch save:  ~2 μs  (save registers, stack, PC)
  context switch wake:  ~2 μs  (restore another thread)
  Total:                ~5-10 μs — 100x more expensive than CAS
```

### When to Use

```
✅ Long critical sections (>1ms) — database write, file I/O, complex logic
✅ Low concurrency (<10 threads)
❌ Simple counter operations — use CAS (AtomicLong)
❌ High throughput (>10k ops/sec) — blocking is too expensive
```

---

## 3. Semaphore

**What:** Counter-based lock — allows up to N threads concurrently.  
**Ownership rule:** ANY thread can release (unlike mutex).  
**Analogy:** Parking lot with N spaces.

```java
// Allow max 10 concurrent database connections
Semaphore pool = new Semaphore(10, true);  // true = fair (FIFO)

pool.acquire();  // count: 10→9, blocks if count=0
try {
    // use connection
} finally {
    pool.release();  // count: 9→10, wakes blocked thread
}
```

### State Transitions

```
Initial: count=3

Thread A acquire() → count=2 (continues)
Thread B acquire() → count=1 (continues)
Thread C acquire() → count=0 (continues)
Thread D acquire() → count=0 → D BLOCKS (WAITING state)

Thread A release() → count=1 → OS wakes Thread D
Thread D acquire() → count=0 (D continues)
```

### Mutex vs Semaphore

| | Mutex | Semaphore |
|--|-------|-----------|
| Count | Binary (0/1) | 0 to N |
| Ownership | Same thread must release | Any thread can release |
| Use case | Protect critical section | Resource pool / signaling |
| Example | `synchronized` block | Connection pool |

### When to Use

```
✅ N identical resources (DB connections, worker slots)
✅ Producer-consumer signaling
❌ Simple mutual exclusion (use mutex — semantics are clearer)
❌ Rate limiting counters (wrong model — tokens don't release)
```

---

## 4. ReentrantLock

**What:** Explicit mutex with more control than `synchronized`.  
**Key extras:** `tryLock(timeout)`, `lockInterruptibly()`, multiple `Condition` objects, fairness.

```java
ReentrantLock lock = new ReentrantLock(true); // fair = FIFO

// tryLock — don't block indefinitely
if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
    try {
        // critical section
    } finally {
        lock.unlock();  // ALWAYS in finally — never skip!
    }
} else {
    // timed out — handle gracefully
}
```

### Why ReentrantLock in HybridRateLimitService

In `syncAndConsume()` we use `tryLock()` (no timeout arg) to let only ONE thread sync with Redis while others spin on local:

```java
if (!lock.tryLock()) {
    // Another thread is syncing Redis right now
    // Don't block — spin-wait and retry local cache
    Thread.onSpinWait();
    return localQuotaManager.tryConsume(tenantId);
}
// I am the ONE thread syncing Redis for this tenant
```

`synchronized` has no `tryLock()` — that's why ReentrantLock is used here.

### ReentrantLock vs synchronized

| | synchronized | ReentrantLock |
|--|------------|--------------|
| `tryLock(timeout)` | ❌ | ✅ |
| `lockInterruptibly()` | ❌ | ✅ |
| Multiple conditions | ❌ (1 wait set) | ✅ |
| Fairness control | ❌ | ✅ |
| Performance | ~same | ~same |
| "Re-entrant" | ✅ | ✅ |

**Re-entrant** = same thread can acquire lock it already holds (won't deadlock itself).

---

## 5. ReadWriteLock

**What:** Allows concurrent reads OR exclusive writes (not both).  
**Use when:** Reads >> Writes (read-heavy workloads).

```java
ReadWriteLock rwLock = new ReentrantReadWriteLock();

// Multiple threads can read simultaneously
public Data read() {
    rwLock.readLock().lock();
    try {
        return data;
    } finally {
        rwLock.readLock().unlock();
    }
}

// Only one writer, blocks all readers
public void write(Data newData) {
    rwLock.writeLock().lock();
    try {
        data = newData;
    } finally {
        rwLock.writeLock().unlock();
    }
}
```

**Rule:** When writer holds lock, ALL readers block. When any reader holds lock, writers block.

---

## 6. CAS (Compare-And-Swap) — Lock-Free

**What:** Single CPU instruction that reads, compares, and conditionally writes — all atomically.  
**Key insight:** No OS involvement, no blocking, no context switch.

### x86 Instruction

```asm
; Java: available.compareAndSet(1000, 999)
; Translates to:

mov  eax, 1000          ; expected value into EAX register
mov  ecx, 999           ; new value into ECX register
lea  rdx, [available]   ; memory address

LOCK CMPXCHG [rdx], ecx
; 1. Atomically: if *rdx == EAX, then *rdx = ECX, set ZF=1
; 2. Otherwise: EAX = *rdx, set ZF=0
; LOCK prefix: locks memory bus — other CPUs stall for this one instruction
```

The entire read-compare-write happens as ONE CPU cycle. No OS, no syscall, no blocking.

### CAS Loop Pattern

```java
AtomicLong available = new AtomicLong(1000);

// Safely decrement — handles 5000 concurrent threads
while (true) {
    long current = available.get();        // read

    if (current <= 0) return false;        // can't consume

    if (available.compareAndSet(current, current - 1)) {
        return true;                       // won the CAS — success
    }
    // Lost the CAS — another thread changed available between our read and CAS
    // No blocking: spin immediately to retry
    // JVM hint: tells CPU we're in a spin-wait (improves power/performance)
    Thread.onSpinWait();
}
```

### The CAS Loop — Step by Step (How It Actually Solves the Problem)

The problem CAS solves: two threads both read `current=1000`, both try to write `999` — one of them must **lose and retry**, not silently succeed with a corrupt value.

Here is exactly what happens for **3 threads competing** (easier to trace than 5000):

```
available = 1000

─── Thread A ───────────────────────────────────────────────────────────────
Step 1: current = available.get()    → current = 1000
Step 2: current > 0? YES, continue
Step 3: compareAndSet(1000, 999)
          CPU checks: is memory still 1000?
          YES → writes 999 atomically → returns true
          available is now 999
Step 4: return true ✅  (Thread A consumed a token)


─── Thread B (ran simultaneously with A) ───────────────────────────────────
Step 1: current = available.get()    → current = 1000   ← read SAME value as A!
Step 2: current > 0? YES, continue
Step 3: compareAndSet(1000, 999)
          CPU checks: is memory still 1000?
          NO — Thread A already changed it to 999!
          → returns false. available unchanged.
          ← CAS FAILED
Step 4: // loop retries automatically

Step 1 (retry): current = available.get()  → current = 999   ← sees updated value
Step 2: current > 0? YES
Step 3: compareAndSet(999, 998)
          CPU checks: is memory still 999?
          YES → writes 998 → returns true
Step 4: return true ✅  (Thread B consumed a token)


─── Thread C (also ran simultaneously) ─────────────────────────────────────
Step 1: current = available.get()    → current = 1000
Step 3: compareAndSet(1000, 999)  → FAILS (A already wrote 999)
Retry:
Step 1: current = available.get()    → current = 999
Step 3: compareAndSet(999, 998)   → FAILS (B already wrote 998)
Retry:
Step 1: current = available.get()    → current = 998
Step 3: compareAndSet(998, 997)   → WINS
Step 4: return true ✅

FINAL: available = 997. Exactly 3 tokens consumed. No double-spend.
```

**The guarantee:** `compareAndSet(expected, new)` is ONE CPU instruction (`LOCK CMPXCHG`).  
The CPU checks AND writes in one atomic step — no other thread can sneak in between the check and the write.

```
WITHOUT CAS (broken):                    WITH CAS (correct):
─────────────────────────────────────    ─────────────────────────────
Thread A: read 1000                      Thread A: CAS(1000→999) WINS ✅
Thread B: read 1000   ← same value!     Thread B: CAS(1000→999) FAILS
Thread A: write 999                        Thread B retries
Thread B: write 999   ← overwrites A!     Thread B: CAS(999→998) WINS ✅
Result: available=999 (should be 998!)   Result: available=998 ✅
```

### What "Lost the CAS" Means Concretely

```java
long current = available.get();            // Step 1: I read 1000

// ← RIGHT HERE another thread can change available from 1000 to 999

if (available.compareAndSet(current,       // Step 2: Is it STILL 1000?
                            current - 1)) {
    return true;   // YES → I wrote 999. I win.
}
// NO → someone else changed it. My write is rejected. I retry.
```

The gap between Step 1 (read) and Step 2 (CAS) is where races happen.  
CAS detects the race and refuses to write if the value changed. That's the entire trick.

### Why the While(true) Loop?

A thread might need to retry many times if there's heavy contention:

```
available = 100, threads = 1000

Thread 1: read 100, CAS(100→99)  WINS  → available=99
Thread 2: read 100, CAS(100→99)  FAILS → retry, read 99, CAS(99→98) WINS
Thread 3: read 100, CAS(100→99)  FAILS → retry, read 99, CAS(99→98) FAILS
                                        → retry, read 98, CAS(98→97) WINS
...

Worst case: a thread retries O(N) times where N = number of competing threads
But each retry is ~5ns (one CPU instruction) — even 100 retries = 500ns
Compare to: 1 context switch = 5,000ns (10x more expensive than 1000 retries)
```

The `while(true)` is a **spin loop** — the thread stays active on the CPU, retrying at CPU speed rather than going to sleep and waiting for a wakeup signal.

### Thread.onSpinWait() — Why It's There

```java
Thread.onSpinWait();   // Added after a failed CAS
```

This is a JVM hint — translates to `PAUSE` instruction on x86:
- Tells CPU "I am spinning, this is intentional"
- CPU reduces power consumption (doesn't speculate ahead aggressively)
- Prevents memory pipeline hazards — without it, the CPU's store-forwarding can cause unnecessary cache invalidation storms across CPU cores
- On a 4-core machine with 4 threads all spinning: without `onSpinWait()` they hammer the memory bus; with it, the hardware slows each spinning thread slightly so the one that wins can complete faster

In practice: small but real performance improvement under high contention.

### What Happens When available Reaches 0

```java
while (true) {
    long current = available.get();

    if (current <= 0) return false;   // ← EXIT: no retry, immediate denial
    ...
}
```

Once `available = 0`, ALL threads hit the `current <= 0` check and return `false` immediately — no CAS attempted, no spinning. This is the happy path for rejected requests (fast denial).

### Summary: Why This Is Safe

```
Race condition possible?  NO — compareAndSet is ONE atomic CPU instruction
Over-consumption?         NO — only the thread that wins CAS decrements
Under-consumption?        NO — failed threads retry until success or 0
Blocking?                 NO — threads spin at CPU speed (nanoseconds)
Deadlock?                 NO — no locks, nothing to deadlock on
Starvation?               Theoretically possible but extremely rare at 5k threads
                          (each thread wins within a handful of retries statistically)
```

### What Happens with 5000 Concurrent Threads

```
All 5000 threads enter CAS loop simultaneously
available = 1000

Round 1: All 5000 read current=1000
         Thread 1 wins CAS(1000→999)   ← exactly 1 wins
         Threads 2-5000 fail CAS (saw 1000, memory is now 999)

Round 2: Threads 2-5000 retry, read current=999
         Thread 2 wins CAS(999→998)    ← exactly 1 wins
         Threads 3-5000 fail, retry

...continues until available=0...

Threads 1001-5000: read current=0 → return false immediately

RESULT: Exactly 1000 succeed, exactly 4000 denied. Zero over-consumption.
        Total time: ~25ms (vs 250ms with synchronized)
```

### Why Not synchronized for This?

```
synchronized approach:
  Thread 1 acquires mutex → runs decrement
  Threads 2-4999 ALL BLOCKED — OS puts them to sleep
  Context switch per thread: ~5μs
  5000 threads × 5μs = 25ms just in context switches
  Plus threads sleeping/waking overhead
  
CAS approach:
  All threads run concurrently — no blocking
  Each retry: ~5ns (CPU instruction)
  Even with 10 retries per thread: 50ns total
  1000x less overhead per thread
```

### Performance Comparison

| Mechanism | Throughput | Latency | Blocking | Use for rate limiting |
|-----------|-----------|---------|----------|----------------------|
| synchronized | 50k TPS | 5ms | YES | ❌ |
| ReentrantLock | 80k TPS | 2ms | YES | ❌ |
| CAS (AtomicLong) | 200k TPS | 0.1ms | NO | ✅ |

### Memory Visibility: volatile + CAS

`AtomicLong` uses `volatile` internally:
- `volatile` write: flushes CPU cache → all threads see new value immediately
- `volatile` read: reads from main memory, not stale CPU cache
- CAS on volatile: read-compare-write, all visible across CPU cores (cache coherence protocol MESI)

```java
// Under the hood in AtomicLong (simplified):
public final class AtomicLong {
    private volatile long value;  // volatile ensures visibility

    public final boolean compareAndSet(long expected, long update) {
        // Unsafe.compareAndSwapLong — maps to LOCK CMPXCHG instruction
        return U.compareAndSetLong(this, VALUE, expected, update);
    }
}
```

---

## 7. Deadlock

**What:** Two threads each hold a lock the other needs — both wait forever.

### Classic Deadlock

```java
class DeadlockDemo {
    static final Object LOCK_A = new Object();
    static final Object LOCK_B = new Object();

    public static void main(String[] args) {
        // Thread 1: acquires A, then needs B
        Thread t1 = new Thread(() -> {
            synchronized (LOCK_A) {
                System.out.println("T1 holds A, waiting for B");
                try { Thread.sleep(50); } catch (Exception e) {}
                synchronized (LOCK_B) {          // BLOCKS — T2 holds B!
                    System.out.println("T1 holds both");
                }
            }
        });

        // Thread 2: acquires B, then needs A
        Thread t2 = new Thread(() -> {
            synchronized (LOCK_B) {
                System.out.println("T2 holds B, waiting for A");
                try { Thread.sleep(50); } catch (Exception e) {}
                synchronized (LOCK_A) {          // BLOCKS — T1 holds A!
                    System.out.println("T2 holds both");
                }
            }
        });

        t1.start();
        t2.start();
        // DEADLOCK: T1 waits for B (held by T2), T2 waits for A (held by T1)
        // Both stuck forever. Program never prints "holds both".
    }
}
```

### Deadlock Prevention — Lock Ordering

```java
// Fix: ALWAYS acquire locks in the same order globally
// Order: LOCK_A first, then LOCK_B (in both threads)

Thread t1 = new Thread(() -> {
    synchronized (LOCK_A) {         // acquire A first
        synchronized (LOCK_B) {     // then B
            System.out.println("T1 done");
        }
    }
});

Thread t2 = new Thread(() -> {
    synchronized (LOCK_A) {         // acquire A first (same order!)
        synchronized (LOCK_B) {     // then B
            System.out.println("T2 done");
        }
    }
});
// No deadlock — one thread always gets A first, the other waits cleanly
```

### Deadlock Detection — Thread Dump

```bash
# While program is running, get thread dump
jstack <pid>

# Look for:
# "Thread-1" BLOCKED on java.lang.Object@1a2b3c4d
#   waiting to lock java.lang.Object@5e6f7a8b
#   which is held by "Thread-2"
# "Thread-2" BLOCKED on java.lang.Object@5e6f7a8b  
#   waiting to lock java.lang.Object@1a2b3c4d
#   which is held by "Thread-1"
```

### Deadlock Conditions (Coffman, 1971)

All 4 must hold — break any one to prevent deadlock:

1. **Mutual Exclusion** — resource can only be held by one thread
2. **Hold and Wait** — thread holds one lock while waiting for another
3. **No Preemption** — OS can't forcefully take a lock away
4. **Circular Wait** — T1 waits for T2, T2 waits for T1 (cycle)

**Fix circular wait:** enforce global lock ordering (breaks condition 4).

---

## 8. Thread States

Java defines 6 states in `Thread.State`:

```java
public enum State { NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, TERMINATED }
```

### State Diagram

```
NEW ──start()──► RUNNABLE ◄──────────────────────────────────────┐
                    │                                             │
                    ├──synchronized (lock held)──► BLOCKED ──unlock()
                    │
                    ├──Object.wait()──────────────► WAITING ──notify()
                    ├──LockSupport.park()──────────►         ──unpark()
                    │
                    ├──Thread.sleep(ms)───────────► TIMED_WAITING
                    ├──Object.wait(ms)─────────────►          ──timeout/notify
                    ├──lock.tryLock(ms)────────────►          ──timeout
                    │
                    └──run() returns──────────────► TERMINATED
```

### Key Distinctions

| State | OS Scheduled? | CPU Used? | Trigger |
|-------|--------------|-----------|---------|
| NEW | No | No | Thread created, not started |
| RUNNABLE | Yes | Yes (or ready) | `start()` called, or woke up |
| BLOCKED | No | No | Waiting for `synchronized` lock |
| WAITING | No | No | `wait()`, `park()` — indefinite |
| TIMED_WAITING | No | No | `sleep(ms)`, `wait(ms)` — with timeout |
| TERMINATED | No | No | `run()` returned |

**BLOCKED vs WAITING:**
- `BLOCKED` = waiting for a *monitor lock* (`synchronized`)
- `WAITING` = waiting for an *explicit signal* (`notify()`, `unpark()`)

**RUNNABLE includes both:** currently running on CPU AND ready-to-run in OS scheduler queue. Java can't distinguish (OS controls actual CPU scheduling).

### Context Switch Explained

Context switch = OS stops one thread, runs another.  
Cost: ~5-10μs — save registers + stack + PC of thread A, restore state of thread B, CPU cache goes cold.

This is exactly why CAS is better than `synchronized` for hot paths — CAS never causes a context switch.

---

## 9. Connection Pooling with Semaphore

Connection pool = N database connections, many threads share them.  
Semaphore(N) is the perfect fit: acquire = borrow connection, release = return connection.

```java
public class ConnectionPool {

    private final Semaphore permits;                           // limits concurrency
    private final ConcurrentLinkedQueue<Connection> available; // idle connections
    // key=Connection object (its identity), value=PooledConnection metadata (lease time, stack trace)
    private final ConcurrentHashMap<Connection, PooledConnection> leased; // active leases

    public ConnectionPool(String url, int maxSize) throws SQLException {
        this.permits = new Semaphore(maxSize, true); // fair=true → FIFO wait queue
        this.available = new ConcurrentLinkedQueue<>();
        this.leased = new ConcurrentHashMap<>();

        for (int i = 0; i < maxSize; i++) {
            available.add(DriverManager.getConnection(url));
        }
    }

    /**
     * Borrow a connection. Blocks if all N are in use.
     * Timeout prevents waiting forever when pool is exhausted.
     */
    public Connection getConnection(long timeoutMs) throws Exception {
        // Blocks here if count=0; wakes when another thread releases
        if (!permits.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("Connection pool exhausted after " + timeoutMs + "ms");
        }

        Connection conn = available.poll();                    // get from queue

        // Record lease for leak detection
        leased.put(conn, new PooledConnection(conn, Thread.currentThread().getStackTrace()));

        return conn;
    }

    /**
     * Return connection to pool. Wakes one blocked thread.
     */
    public void release(Connection conn) {
        PooledConnection pc = leased.remove(conn);             // remove lease record

        if (pc != null) {
            long heldMs = System.currentTimeMillis() - pc.leasedAt;
            if (heldMs > 30_000) {
                System.err.println("⚠ Connection leak! Held for " + heldMs + "ms");
                pc.printStackTrace();
            }
        }

        available.offer(conn);  // return to queue
        permits.release();      // increment count — wakes a blocked thread if any
    }

    // ─── Why ConcurrentHashMap<Connection, PooledConnection>? ───────────────
    // Key = Connection object. Java uses object identity (reference address) as
    // default hashCode/equals for objects that don't override them.
    // So map.get(conn) works because we're looking up the exact same Connection
    // instance we put in — same object reference = same hash = same bucket.
    // Value = PooledConnection (holds lease time + stack trace for leak detection)
    record PooledConnection(Connection conn, StackTraceElement[] trace, long leasedAt) {
        PooledConnection(Connection conn, StackTraceElement[] trace) {
            this(conn, trace, System.currentTimeMillis());
        }
        void printStackTrace() {
            for (StackTraceElement e : trace) System.err.println("  at " + e);
        }
    }
}
```

### Why `ConcurrentHashMap<Connection, PooledConnection>`?

The **key is the Connection object** (its reference/identity in memory):
- Connection doesn't override `hashCode()` → Java uses `System.identityHashCode()` (memory address)
- `map.get(conn)` works because we pass the exact same object we `put()` earlier
- This lets us look up "who borrowed this specific connection" in O(1)

### Why Semaphore Here (vs ReentrantLock)?

```java
// With ReentrantLock — need manual wait/signal:
while (available.isEmpty()) { condition.await(); }
Connection c = available.poll();
// release: available.add(c); condition.signal();

// With Semaphore — automatic:
permits.acquire();      // blocks automatically when count=0
Connection c = available.poll();
// release: available.add(c); permits.release();  // wakes a waiter automatically
```

Semaphore models "N resources" naturally — it IS the counter. ReentrantLock would just rebuild a semaphore manually.

---

## 10. Why CAS for Rate Limiting (Not the Others)

| Mechanism | Why Not Used in LocalQuotaManager |
|-----------|----------------------------------|
| `synchronized` | Blocks threads — 100x slower under 5k concurrency |
| `ReentrantLock` | Same blocking behavior as synchronized |
| Semaphore | Wrong model — rate limit tokens are consumed, not borrowed-and-returned |
| ReadWriteLock | Writers still block; rate limiting is all writes |
| **CAS** | ✅ Lock-free, no blocking, 200k+ TPS, perfect for simple decrement |

`ReentrantLock` IS used in `syncAndConsume()` — but only for the rare Redis sync path (10% of requests), not for the hot path. The hot path (90%+ of requests) is pure CAS.

---

## 11. Quick Cheat Sheet

```
synchronized     → implicit mutex. Simple. No tryLock.
ReentrantLock    → explicit mutex. tryLock(timeout). Multiple conditions.
Semaphore(N)     → N concurrent threads allowed. Any thread can release.
ReadWriteLock    → concurrent reads OR exclusive write.
AtomicLong/CAS   → lock-free counter. CPU instruction. Best for high-TPS.
volatile         → visibility only (no atomicity for compound ops).

Deadlock fix:    → global consistent lock ordering across all threads.
Thread BLOCKED   → waiting for synchronized monitor lock.
Thread WAITING   → waiting for notify()/unpark() — indefinite.
Context switch   → OS swaps threads: ~5-10μs. Avoided by CAS.
```

