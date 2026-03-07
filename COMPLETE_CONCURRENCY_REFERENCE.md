# Complete Concurrency Concepts Reference
## Mutex, Semaphore, Locks, and CAS - Everything You Need to Know

---

## Table of Contents
1. [Fundamental Concepts](#fundamental-concepts)
2. [Mutex Deep Dive](#mutex-deep-dive)
3. [Semaphore Deep Dive](#semaphore-deep-dive)
4. [ReentrantLock](#reentrantlock)
5. [ReadWriteLock](#readwritelock)
6. [StampedLock](#stampedlock)
7. [CAS (Compare-And-Swap)](#cas-compare-and-swap)
8. [Complete Comparison](#complete-comparison)
9. [Interview Q&A](#interview-qa)

---

## Fundamental Concepts

### What is Concurrency?

**Concurrency** = Multiple threads executing code simultaneously, potentially accessing shared data.

**The Problem:**
```java
// WITHOUT synchronization - RACE CONDITION!
class Counter {
    private int count = 0;
    
    public void increment() {
        count++;  // NOT atomic! Three operations:
                  // 1. Read count
                  // 2. Add 1
                  // 3. Write back
    }
}

// Two threads executing simultaneously:
Thread 1: Read count (0) → Add 1 → Write 1
Thread 2: Read count (0) → Add 1 → Write 1
Result: count = 1 (WRONG! Should be 2)
```

**The Solution:** Synchronization mechanisms ensure only ONE thread modifies shared data at a time.

---

## Mutex Deep Dive

### What is a Mutex?

**Mutex** = **MUT**ual **EX**clusion

A mutex is a **locking mechanism** that ensures only ONE thread can access a critical section at a time.

**Think of it as:** A bathroom with one key. Only the person with the key can enter.

### Java Implementation

```java
// Option 1: synchronized (implicit mutex)
class Counter {
    private int count = 0;
    
    public synchronized void increment() {
        count++;  // Only ONE thread at a time
    }
}

// Option 2: Explicit object as mutex
class Counter {
    private int count = 0;
    private final Object lock = new Object();
    
    public void increment() {
        synchronized(lock) {
            count++;
        }
    }
}
```

### How Mutex Works (Step by Step)

```
Initial State:
┌────────────────┐
│ Mutex: FREE    │
│ Owner: none    │
│ Queue: []      │
└────────────────┘

Step 1: Thread-A arrives
────────────────────────────────────────────────────
Thread-A: "I want the mutex"
Mutex: "It's free! You can have it"
Thread-A: Acquires mutex

┌────────────────┐
│ Mutex: LOCKED  │
│ Owner: Thread-A│
│ Queue: []      │
└────────────────┘

Thread-A executes: count++


Step 2: Thread-B arrives (while A holds mutex)
────────────────────────────────────────────────────
Thread-B: "I want the mutex"
Mutex: "Sorry, Thread-A has it"
Thread-B: "OK, I'll wait"

OS: Puts Thread-B to SLEEP (blocks)
    Adds Thread-B to wait queue
    Context switch (OS runs other threads)

┌────────────────┐
│ Mutex: LOCKED  │
│ Owner: Thread-A│
│ Queue: [B]     │ ← B is sleeping here
└────────────────┘


Step 3: Thread-A finishes
────────────────────────────────────────────────────
Thread-A: Releases mutex
Mutex: "I'm free now!"
OS: Wakes up Thread-B from wait queue

┌────────────────┐
│ Mutex: FREE    │
│ Owner: none    │
│ Queue: []      │
└────────────────┘

Thread-B wakes up (context switch)


Step 4: Thread-B acquires mutex
────────────────────────────────────────────────────
Thread-B: Acquires mutex

┌────────────────┐
│ Mutex: LOCKED  │
│ Owner: Thread-B│
│ Queue: []      │
└────────────────┘

Thread-B executes: count++
Thread-B: Releases mutex

Final: count = 2 (correct!)
```

### Mutex in Operating System Kernel

```c
// Simplified OS-level implementation
struct mutex {
    int locked;           // 0 = free, 1 = locked
    thread_t *owner;      // Who owns this mutex
    queue_t wait_queue;   // Threads waiting for this mutex
    spinlock_t spinlock;  // Protects mutex structure itself
};

// Acquire mutex
void mutex_lock(struct mutex *m) {
    spin_lock(&m->spinlock);  // Protect mutex structure
    
    while (m->locked) {
        // Mutex is busy, we must wait
        
        // Add current thread to wait queue
        enqueue(&m->wait_queue, current_thread());
        
        // Mark thread as SLEEPING
        current_thread()->state = SLEEPING;
        
        spin_unlock(&m->spinlock);
        
        // CONTEXT SWITCH: OS scheduler runs another thread
        schedule();  // Current thread sleeps here
        
        // ... Thread wakes up later when mutex is released ...
        
        spin_lock(&m->spinlock);
    }
    
    // Mutex is free! Acquire it
    m->locked = 1;
    m->owner = current_thread();
    
    spin_unlock(&m->spinlock);
}

// Release mutex
void mutex_unlock(struct mutex *m) {
    spin_lock(&m->spinlock);
    
    // Free the mutex
    m->locked = 0;
    m->owner = NULL;
    
    // Wake up one waiting thread
    if (!queue_empty(&m->wait_queue)) {
        thread_t *next = dequeue(&m->wait_queue);
        next->state = READY;  // OS will schedule this thread
    }
    
    spin_unlock(&m->spinlock);
}
```

### Mutex Performance Characteristics

```
┌─────────────────────────────────────────────────────┐
│  MUTEX PERFORMANCE                                   │
├─────────────────────────────────────────────────────┤
│                                                      │
│  Uncontended (no other threads):                    │
│    - Time: 50-100 nanoseconds                       │
│    - Operations:                                     │
│      • Check if free (10ns)                         │
│      • Set owner (20ns)                             │
│      • Memory barrier (20ns)                        │
│                                                      │
│  Contended (other threads waiting):                 │
│    - Time: 5,000-10,000 nanoseconds (5-10µs)       │
│    - Operations:                                     │
│      • Check if free (10ns)                         │
│      • Syscall to kernel (1000ns)                   │
│      • Add to wait queue (100ns)                    │
│      • Context switch save (2000ns)                 │
│      • Sleep (variable)                             │
│      • Wake signal (1000ns)                         │
│      • Context switch restore (2000ns)              │
│                                                      │
│  Context switch is EXPENSIVE!                       │
│    - Save: registers, stack, program counter        │
│    - Restore: different thread's state              │
│    - CPU cache becomes invalid (cache miss)         │
│                                                      │
└─────────────────────────────────────────────────────┘
```

### When to Use Mutex

```
✅ USE MUTEX WHEN:
   • Critical section > 1 millisecond
   • Doing I/O operations (file, network, database)
   • Complex calculations
   • Multiple lines of code to protect
   • Low concurrency (< 10 threads)

❌ DON'T USE MUTEX WHEN:
   • Critical section < 100 nanoseconds
   • Simple counter operations
   • High throughput required (> 10k ops/sec)
   • Tiny operations (use CAS instead)

📋 EXAMPLES:
   ✓ File writing: synchronized(fileLock) { file.write(...) }
   ✓ Database transaction: synchronized(dbLock) { db.commit() }
   ✗ Counter increment: DON'T use mutex (use AtomicInteger)
   ✗ Rate limiting: DON'T use mutex (use CAS)
```

---

## Semaphore Deep Dive

### What is a Semaphore?

**Semaphore** = A signaling mechanism with a **counter**

Unlike mutex (binary: locked/unlocked), a semaphore has a **count** allowing N threads.

**Think of it as:** A parking lot with N spaces. N cars can park simultaneously.

### Types of Semaphores

```
┌──────────────────────────────────────────────────┐
│  1. BINARY SEMAPHORE (count = 0 or 1)           │
├──────────────────────────────────────────────────┤
│  Similar to mutex                                 │
│  Used for: Mutual exclusion                      │
│                                                   │
│  Example:                                         │
│  Semaphore sem = new Semaphore(1);              │
│  sem.acquire();  // count: 1 → 0                │
│  // critical section                             │
│  sem.release();  // count: 0 → 1                │
└──────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────┐
│  2. COUNTING SEMAPHORE (count = 0 to N)         │
├──────────────────────────────────────────────────┤
│  Allows N threads simultaneously                 │
│  Used for: Resource pooling, rate limiting       │
│                                                   │
│  Example:                                         │
│  Semaphore pool = new Semaphore(10);            │
│  // Up to 10 threads can acquire                │
└──────────────────────────────────────────────────┘
```

### Java Implementation

```java
import java.util.concurrent.Semaphore;

// Example: Connection pool with 10 connections
class ConnectionPool {
    private final Semaphore semaphore = new Semaphore(10);
    private final List<Connection> connections;
    
    public Connection getConnection() throws InterruptedException {
        semaphore.acquire();  // Wait if count = 0
        
        synchronized(connections) {
            return connections.remove(0);
        }
    }
    
    public void releaseConnection(Connection conn) {
        synchronized(connections) {
            connections.add(conn);
        }
        
        semaphore.release();  // Increment count
    }
}
```

### How Semaphore Works (Step by Step)

```
Semaphore pool = new Semaphore(3);  // 3 permits

Initial State:
┌────────────────┐
│ Count: 3       │
│ Queue: []      │
└────────────────┘


Step 1: Thread-A calls acquire()
────────────────────────────────────────────────────
Thread-A: "I need a permit"
Semaphore: "Count = 3, here you go!"
Semaphore: Decrements count (3 → 2)

┌────────────────┐
│ Count: 2       │ ← Decremented
│ Queue: []      │
└────────────────┘

Thread-A continues execution


Step 2: Thread-B calls acquire()
────────────────────────────────────────────────────
Thread-B: "I need a permit"
Semaphore: "Count = 2, here you go!"
Semaphore: Decrements count (2 → 1)

┌────────────────┐
│ Count: 1       │
│ Queue: []      │
└────────────────┘

Thread-B continues execution


Step 3: Thread-C calls acquire()
────────────────────────────────────────────────────
Thread-C: "I need a permit"
Semaphore: "Count = 1, here you go!"
Semaphore: Decrements count (1 → 0)

┌────────────────┐
│ Count: 0       │ ← All permits taken!
│ Queue: []      │
└────────────────┘

Thread-C continues execution


Step 4: Thread-D calls acquire() (NO permits left!)
────────────────────────────────────────────────────
Thread-D: "I need a permit"
Semaphore: "Count = 0, sorry! You must wait"
Thread-D: Blocks (goes to sleep)

OS: Puts Thread-D in wait queue
    Context switch to another thread

┌────────────────┐
│ Count: 0       │
│ Queue: [D]     │ ← D is waiting/sleeping
└────────────────┘


Step 5: Thread-A calls release()
────────────────────────────────────────────────────
Thread-A: "I'm done, releasing permit"
Semaphore: Increments count (0 → 1)
Semaphore: "Thread-D is waiting, wake it up!"

OS: Wakes Thread-D from wait queue

┌────────────────┐
│ Count: 1       │ ← Count increased
│ Queue: []      │ ← D moved to ready
└────────────────┘

Thread-D wakes up (context switch)


Step 6: Thread-D acquires permit
────────────────────────────────────────────────────
Thread-D: acquire() succeeds
Semaphore: Decrements count (1 → 0)

┌────────────────┐
│ Count: 0       │
│ Queue: []      │
└────────────────┘

Thread-D continues execution
```

### Semaphore in Operating System

```c
// Simplified OS-level implementation
struct semaphore {
    int count;            // Number of available permits
    queue_t wait_queue;   // Threads waiting for permits
    spinlock_t spinlock;  // Protects semaphore structure
};

// Acquire permit (P operation, wait, acquire)
void sem_wait(struct semaphore *s) {
    spin_lock(&s->spinlock);
    
    while (s->count <= 0) {
        // No permits available, must wait
        
        enqueue(&s->wait_queue, current_thread());
        current_thread()->state = SLEEPING;
        
        spin_unlock(&s->spinlock);
        
        // Context switch - thread sleeps
        schedule();
        
        // ... Wakes up when permit released ...
        
        spin_lock(&s->spinlock);
    }
    
    // Got a permit!
    s->count--;  // Take one
    
    spin_unlock(&s->spinlock);
}

// Release permit (V operation, signal, release)
void sem_post(struct semaphore *s) {
    spin_lock(&s->spinlock);
    
    s->count++;  // Return permit
    
    // Wake up one waiting thread
    if (!queue_empty(&s->wait_queue)) {
        thread_t *next = dequeue(&s->wait_queue);
        next->state = READY;
    }
    
    spin_unlock(&s->spinlock);
}
```

### Mutex vs Semaphore - Key Differences

```
┌─────────────────────────────────────────────────────────────┐
│              MUTEX vs SEMAPHORE                              │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  MUTEX:                                                     │
│  ───────                                                     │
│  • Binary (0 or 1)                                          │
│  • Ownership: MUST be released by same thread that acquired│
│  • Purpose: Protect critical section                        │
│  • Analogy: Bathroom with ONE key                          │
│                                                              │
│  synchronized(lock) {                                        │
│      // Only thread that entered can exit                   │
│  }                                                           │
│                                                              │
│                                                              │
│  SEMAPHORE:                                                 │
│  ───────────                                                │
│  • Counter (0 to N)                                         │
│  • Ownership: ANY thread can release                        │
│  • Purpose: Resource pooling, signaling                     │
│  • Analogy: Parking lot with N spaces                      │
│                                                              │
│  sem.acquire();  // Thread A                                │
│  sem.release();  // Thread B (allowed!)                     │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### When to Use Semaphore

```
✅ USE SEMAPHORE WHEN:
   • Limiting concurrent access to N resources
   • Resource pooling (DB connections, threads)
   • Producer-consumer patterns
   • Signaling between threads
   • Example: Connection pool (N connections)

❌ DON'T USE SEMAPHORE WHEN:
   • Simple mutual exclusion (use mutex)
   • Single variable protection (use CAS)
   • Rate limiting (tokens don't release!)

📋 EXAMPLES:
   ✓ DB pool: Semaphore(10) for 10 connections
   ✓ Thread pool: Semaphore(8) for 8 worker threads
   ✓ Parking lot: Semaphore(100) for 100 spaces
   ✗ Counter: Don't use semaphore (use AtomicInteger)
   ✗ Rate limit: Don't use semaphore (wrong model)
```

---

## Context Switch - Deep Dive

### What is a Context Switch?

**Context Switch** = OS saves current thread's **entire execution state** and restores another thread's state.

**NOT just a state change!** It's a complete swap of execution context.

### What Gets Saved/Restored?

```
┌─────────────────────────────────────────────────────────┐
│  CONTEXT SWITCH INVOLVES:                                │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  1. CPU REGISTERS (15-30 registers)                     │
│     • General purpose: RAX, RBX, RCX, RDX, etc.         │
│     • Stack pointer: RSP                                 │
│     • Base pointer: RBP                                  │
│     • Instruction pointer: RIP (where to resume)        │
│     • Flags register: RFLAGS                            │
│                                                          │
│  2. PROGRAM COUNTER (PC)                                │
│     • Next instruction to execute                        │
│     • Critical for resuming at exact point              │
│                                                          │
│  3. STACK POINTER                                       │
│     • Points to thread's stack                          │
│     • Contains local variables, call frames             │
│                                                          │
│  4. MEMORY MANAGEMENT INFO                              │
│     • Page tables                                        │
│     • Memory mappings                                    │
│                                                          │
│  5. FLOATING POINT REGISTERS                            │
│     • XMM0-XMM15 (SSE)                                  │
│     • FPU state                                          │
│                                                          │
│  6. THREAD LOCAL STORAGE (TLS)                          │
│     • Thread-specific data                               │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Context Switch Step-by-Step

```
Scenario: Thread-A is running, Thread-B needs to wake up

STEP 1: SAVE Thread-A's Context
────────────────────────────────────────────────────────
OS Scheduler:
  1. Save all CPU registers to Thread-A's PCB*
     - RAX, RBX, RCX, RDX, RSI, RDI, RBP, RSP
     - RIP (instruction pointer)
     - RFLAGS
  
  2. Save Program Counter (where Thread-A was executing)
  
  3. Save Stack Pointer (Thread-A's stack location)
  
  4. Save FPU/SSE registers
  
  5. Mark Thread-A state: READY (will run later)

Time taken: ~2000-3000 nanoseconds


STEP 2: SELECT Next Thread (Thread-B)
────────────────────────────────────────────────────────
OS Scheduler:
  1. Check scheduler queue
  2. Pick Thread-B (highest priority, just woken)
  3. Mark Thread-B state: RUNNING

Time taken: ~500 nanoseconds


STEP 3: RESTORE Thread-B's Context
────────────────────────────────────────────────────────
OS Scheduler:
  1. Load Thread-B's PCB* into CPU registers
     - RAX, RBX, RCX, RDX, etc.
  
  2. Restore Program Counter (RIP)
     → CPU jumps to where Thread-B left off!
  
  3. Restore Stack Pointer (RSP)
     → Points to Thread-B's stack
  
  4. Restore FPU/SSE registers
  
  5. Flush CPU cache (Thread-A's data no longer valid)

Time taken: ~2000-3000 nanoseconds


STEP 4: Resume Execution
────────────────────────────────────────────────────────
CPU now executes Thread-B's code from where it stopped!

Thread-B: "I'm running now!" (resumes after sem.acquire())


TOTAL COST: ~5000-7000 nanoseconds (5-7 microseconds)

* PCB = Process Control Block (OS data structure)
```

### Visual Diagram

```
Time 0: Thread-A is RUNNING on CPU
┌─────────────────────────────────────┐
│  CPU                                │
│  ┌─────────────────────────────┐   │
│  │ Registers: Thread-A's values│   │
│  │ RAX = 0x1234                │   │
│  │ RBX = 0x5678                │   │
│  │ RIP = 0xABCD (PC)           │   │
│  └─────────────────────────────┘   │
│                                     │
│  Executing: Thread-A's code         │
└─────────────────────────────────────┘

        Memory:
        Thread-A's stack: 0x7FFF...
        Thread-B's stack: 0x7FFE... (not active)


Time 1: Context Switch Begins (Thread-B wakes up)
┌─────────────────────────────────────┐
│  OS Scheduler: "Time to switch!"    │
│                                     │
│  1. Save Thread-A registers         │
│     to Thread-A's PCB:              │
│     ┌─────────────────────┐        │
│     │ RAX = 0x1234        │        │
│     │ RBX = 0x5678        │        │
│     │ RIP = 0xABCD        │        │
│     └─────────────────────┘        │
│                                     │
│  2. Load Thread-B registers         │
│     from Thread-B's PCB:            │
│     ┌─────────────────────┐        │
│     │ RAX = 0x9999        │        │
│     │ RBX = 0x8888        │        │
│     │ RIP = 0x1111        │        │
│     └─────────────────────┘        │
└─────────────────────────────────────┘


Time 2: Thread-B is RUNNING on CPU
┌─────────────────────────────────────┐
│  CPU                                │
│  ┌─────────────────────────────┐   │
│  │ Registers: Thread-B's values│   │
│  │ RAX = 0x9999                │   │
│  │ RBX = 0x8888                │   │
│  │ RIP = 0x1111 (PC)           │   │
│  └─────────────────────────────┘   │
│                                     │
│  Executing: Thread-B's code         │
│  (Resumes after sem.acquire())      │
└─────────────────────────────────────┘

        Memory:
        Thread-A's stack: 0x7FFF... (saved)
        Thread-B's stack: 0x7FFE... (now active)
```

### Context Switch vs Thread State Change

```
┌─────────────────────────────────────────────────────────┐
│  THEY ARE DIFFERENT!                                     │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  THREAD STATE CHANGE:                                   │
│  • Logical state: NEW → RUNNABLE → BLOCKED → WAITING   │
│  • JVM concept                                           │
│  • Fast (just changing a flag)                          │
│  • Example: thread.state = BLOCKED                      │
│  • Cost: ~10 nanoseconds                                │
│                                                          │
│  CONTEXT SWITCH:                                        │
│  • Physical CPU context swap                            │
│  • OS scheduler operation                               │
│  • Expensive (save/restore registers)                   │
│  • Example: Save Thread-A, Load Thread-B               │
│  • Cost: ~5000-7000 nanoseconds                         │
│                                                          │
│  RELATIONSHIP:                                          │
│  State change CAN trigger context switch, but not always│
│  • RUNNABLE → BLOCKED: State change + context switch    │
│  • WAITING → RUNNABLE: State change (no switch yet)     │
│  • RUNNABLE → RUNNABLE: No state change, but switch!    │
│    (OS preemption for time-sharing)                     │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Example with Semaphore

```java
Semaphore sem = new Semaphore(0);

// Thread-A
sem.acquire();  // Blocks because count = 0

// What happens:
// 1. STATE CHANGE: RUNNABLE → BLOCKED (~10ns)
// 2. OS adds Thread-A to wait queue
// 3. CONTEXT SWITCH: Save Thread-A, load Thread-B (~5000ns)
// 4. Thread-A is now SLEEPING (not using CPU)


// Thread-B (later)
sem.release();  // Increments count, wakes Thread-A

// What happens:
// 1. OS marks Thread-A state: SLEEPING → READY (~10ns)
// 2. Thread-A added to scheduler's ready queue
// 3. CONTEXT SWITCH: Save current thread, load Thread-A (~5000ns)
// 4. Thread-A resumes execution after sem.acquire()
```

### Why Context Switch is Expensive

```
┌─────────────────────────────────────────────────────────┐
│  COST BREAKDOWN                                          │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  1. DIRECT COSTS:                                       │
│     • Save registers: ~1000ns                           │
│     • Restore registers: ~1000ns                        │
│     • Scheduler overhead: ~500ns                        │
│     • Memory barriers: ~500ns                           │
│     Total: ~3000ns                                       │
│                                                          │
│  2. INDIRECT COSTS (Cache Effects):                     │
│     • CPU cache invalidation: ~2000ns                   │
│     • TLB flush: ~500ns                                 │
│     • Pipeline flush: ~500ns                            │
│     Total: ~3000ns                                       │
│                                                          │
│  TOTAL: ~5000-7000ns (5-7 microseconds)                 │
│                                                          │
│  For comparison:                                         │
│  • L1 cache hit: 1ns                                    │
│  • L2 cache hit: 4ns                                    │
│  • L3 cache hit: 10ns                                   │
│  • RAM access: 100ns                                     │
│  • Context switch: 5000ns (50x worse than RAM!)         │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Cache Invalidation Effect

```
Before Context Switch (Thread-A running):
┌────────────────────────────────────────┐
│  CPU L1 Cache                          │
│  ┌──────────────────────────────────┐ │
│  │ Thread-A's data (HOT in cache)   │ │
│  │ - Local variables                 │ │
│  │ - Frequently accessed objects     │ │
│  │ - Code instructions               │ │
│  └──────────────────────────────────┘ │
└────────────────────────────────────────┘
   Fast access: 1-4 nanoseconds


After Context Switch (Thread-B running):
┌────────────────────────────────────────┐
│  CPU L1 Cache                          │
│  ┌──────────────────────────────────┐ │
│  │ Thread-A's data (STALE/INVALID)  │ │
│  │ Thread-B's data (NOT IN CACHE)   │ │
│  │ - Cache MISS on first access     │ │
│  │ - Must fetch from RAM (100ns)    │ │
│  └──────────────────────────────────┘ │
└────────────────────────────────────────┘
   Slow access: 100+ nanoseconds

This is called "COLD CACHE" - major performance hit!
```

### Voluntary vs Involuntary Context Switch

```
┌─────────────────────────────────────────────────────────┐
│  VOLUNTARY CONTEXT SWITCH                                │
│  (Thread explicitly gives up CPU)                        │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Caused by:                                              │
│  • Thread.sleep()                                        │
│  • Object.wait()                                         │
│  • sem.acquire() when no permits                        │
│  • I/O operation (read(), write())                      │
│  • Thread.yield()                                        │
│                                                          │
│  Thread state: RUNNABLE → BLOCKED/WAITING/TIMED_WAITING │
│                                                          │
│  Cost: ~5000ns (context switch)                         │
│                                                          │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  INVOLUNTARY CONTEXT SWITCH                              │
│  (OS forcibly takes CPU away)                           │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  Caused by:                                              │
│  • Time slice expired (e.g., 10ms quantum)              │
│  • Higher priority thread becomes ready                 │
│  • Interrupt (hardware, timer)                          │
│                                                          │
│  Thread state: RUNNABLE → RUNNABLE (no state change!)  │
│  But CPU is now running different thread                │
│                                                          │
│  Cost: ~5000ns (context switch)                         │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Interview Q&A on Context Switch

**Q: What is a context switch?**

> "A context switch is when the OS scheduler saves the current thread's entire execution state (CPU registers, program counter, stack pointer, etc.) and restores another thread's saved state so it can resume execution. It's expensive because it involves saving ~30 registers plus flushing CPU caches, taking 5-7 microseconds. This is 500x more expensive than a simple state change."

**Q: When does a context switch occur?**

> "Two scenarios:
> 1. **Voluntary:** Thread calls sleep(), wait(), or acquire() on unavailable resource. Thread gives up CPU voluntarily.
> 2. **Involuntary:** OS preempts thread when time slice expires or higher priority thread becomes ready. Thread still wants CPU but OS takes it away for fairness.
>
> Both cost ~5000ns but voluntary includes state change (RUNNABLE → BLOCKED), while involuntary doesn't change thread state."

**Q: Why is context switch expensive?**

> "Three reasons:
> 1. **Direct cost:** Saving/restoring 30+ CPU registers takes ~3000ns
> 2. **Cache invalidation:** Previous thread's data in L1/L2/L3 cache becomes invalid, causing cache misses (cold cache)
> 3. **TLB flush:** Translation Lookaside Buffer must be cleared and refilled
>
> Total: ~5-7μs. This is why mutex (which causes context switches) is 100x slower than CAS for tiny operations."

**Q: How does CAS avoid context switches?**

> "CAS (Compare-And-Swap) is a single CPU instruction. When it fails, the thread immediately retries without releasing the CPU. No OS involvement, no saving registers, no cache flush. The thread 'spins' (busy-waits) which uses CPU but avoids the 5000ns context switch cost. For nanosecond operations, spinning is faster than switching."

---

## Complete Comparison

### Feature Matrix

```
┌─────────────────┬──────────┬───────────┬──────────┬──────────┐
│   Feature       │  Mutex   │ Semaphore │ RWLock   │   CAS    │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ Access Count    │ 1        │ N         │ N read/  │ Unlimited│
│                 │          │           │ 1 write  │          │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ OS Kernel Call  │ YES      │ YES       │ YES      │ NO       │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ Thread Blocking │ YES      │ YES       │ YES      │ NO       │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ Context Switch  │ YES      │ YES       │ YES      │ NO       │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ Latency (best)  │ 50-100ns │ 50-100ns  │ 50-100ns │ 10-20ns  │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ Latency (cont.) │ 5-10µs   │ 5-10µs    │ 5-10µs   │ 50-100ns │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ Fairness        │ FIFO     │ FIFO      │ FIFO     │ Random   │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ Deadlock Risk   │ YES      │ YES       │ YES      │ NO       │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ CPU Usage       │ Low      │ Low       │ Low      │ High     │
│                 │ (sleep)  │ (sleep)   │ (sleep)  │ (spin)   │
├─────────────────┼──────────┼───────────┼──────────┼──────────┤
│ Best For        │ Long CS  │ Resource  │ Read-    │ Short CS │
│                 │          │ Pool      │ Heavy    │          │
└─────────────────┴──────────┴───────────┴──────────┴──────────┘

CS = Critical Section
```

### Performance Comparison (Real Numbers)

```
Test: 10,000 threads, simple counter increment

┌──────────────────┬──────────┬──────────────┬──────────┐
│   Mechanism      │ Duration │  Throughput  │ Latency  │
├──────────────────┼──────────┼──────────────┼──────────┤
│ synchronized     │  250ms   │  40k ops/sec │  6.25ms  │
│ ReentrantLock    │  180ms   │  55k ops/sec │  4.5ms   │
│ Semaphore(1)     │  220ms   │  45k ops/sec │  5.5ms   │
│ CAS (AtomicLong) │   50ms   │ 200k ops/sec │  0.5ms   │
└──────────────────┴──────────┴──────────────┴──────────┘

CAS is 5x faster than synchronized!
```

---

## Interview Q&A

### Q1: What's the difference between mutex and semaphore?

**Answer:**
> "A mutex is binary (locked/unlocked) and provides mutual exclusion—only ONE thread can hold it. It must be released by the same thread that acquired it, making it suitable for protecting critical sections.
>
> A semaphore has a counter (0 to N) and can allow N threads simultaneously. Any thread can release permits (not tied to ownership), making it suitable for resource pooling like connection pools.
>
> Think of mutex as a bathroom with one key (only one person), and semaphore as a parking lot with N spaces (N cars can park)."

### Q2: Why use CAS instead of mutex for rate limiting?

**Answer:**
> "Rate limiting is a 5-nanosecond operation (counter decrement). Mutex adds 1000ns syscall overhead plus 5000ns thread blocking—that's 1200x overhead!
>
> CAS (Compare-And-Swap) is a CPU instruction that provides atomicity in just 15ns with no OS involvement, no thread blocking. Even with retries under contention, it's 5x faster than mutex.
>
> For tiny operations, CAS is the right tool. For longer operations (I/O, complex logic), mutex is appropriate."

### Q3: Can you explain how mutex works at OS level?

**Answer:**
> "When a thread tries to acquire a mutex:
> 1. If free: Set owner, enter critical section (~50ns)
> 2. If locked: Syscall to kernel (1000ns), add to wait queue, put thread to sleep, context switch (2000ns)
>
> When released: Kernel wakes one waiting thread, context switch back (2000ns). Total contended cost: 5-10µs.
>
> This involves OS scheduler, which is why it's expensive for tiny operations."

### Q4: When would you use a semaphore over a mutex?

**Answer:**
> "Use semaphore when you need to limit concurrent access to N resources, not just 1. Examples:
> - Connection pool with 10 DB connections: Semaphore(10)
> - Thread pool with 8 workers: Semaphore(8)
> - Rate limiting permits (though CAS is better)
>
> Mutex is for ONE resource. Semaphore is for N resources."

### Q5: What's a deadlock and how do you prevent it?

**Answer:**
> "Deadlock occurs when threads wait for each other circularly:
> - Thread A holds Lock1, wants Lock2
> - Thread B holds Lock2, wants Lock1
> - Both wait forever!
>
> Prevention:
> 1. Always acquire locks in same order
> 2. Use tryLock() with timeout
> 3. Use lock-free algorithms (CAS)
> 4. Avoid holding multiple locks
>
> CAS is deadlock-free by design—no locks to wait on!"

---

## Deadlock - Complete Guide

### What is a Deadlock?

**Deadlock** = Two or more threads waiting for each other circularly, all blocked forever.

```
Classic Example:
┌────────────────────────────────────────────┐
│  Thread 1: Has Lock A, wants Lock B       │
│  Thread 2: Has Lock B, wants Lock A       │
│                                            │
│  Thread 1: Waits for Thread 2 to release B│
│  Thread 2: Waits for Thread 1 to release A│
│                                            │
│  Result: Both wait FOREVER! ☠️            │
└────────────────────────────────────────────┘
```

### Visual Example

```java
// Two bank accounts
BankAccount accountA = new BankAccount(1000);
BankAccount accountB = new BankAccount(1000);

// Thread 1: Transfer A → B
Thread t1 = new Thread(() -> {
    synchronized(accountA) {           // ✓ Got A
        sleep(100);
        synchronized(accountB) {       // ✗ Waiting for B...
            transfer(A, B, 100);
        }
    }
});

// Thread 2: Transfer B → A (opposite direction!)
Thread t2 = new Thread(() -> {
    synchronized(accountB) {           // ✓ Got B
        sleep(100);
        synchronized(accountA) {       // ✗ Waiting for A...
            transfer(B, A, 200);
        }
    }
});

t1.start();
t2.start();

// DEADLOCK! Both threads stuck forever
```

### The 4 Deadlock Conditions

**ALL 4 must be true for deadlock to occur:**

```
┌─────────────────────────────────────────────────────┐
│  1. MUTUAL EXCLUSION                                │
│     • Resources cannot be shared                    │
│     • Example: synchronized block                   │
│     • Only ONE thread can hold lock                 │
│                                                      │
│  2. HOLD AND WAIT                                   │
│     • Thread holds lock while waiting for another   │
│     • Example: Have A, trying to get B              │
│                                                      │
│  3. NO PREEMPTION                                   │
│     • Cannot force thread to release lock           │
│     • Thread must voluntarily release               │
│     • Example: synchronized can't be interrupted    │
│                                                      │
│  4. CIRCULAR WAIT                                   │
│     • Threads form a cycle                          │
│     • Example: T1→A→B, T2→B→A (A→B→A cycle)       │
└─────────────────────────────────────────────────────┘

Break ANY ONE condition → No deadlock!
```

### Prevention Strategy 1: Lock Ordering

**Break:** Circular Wait condition

```java
// DEADLOCK PRONE ❌
public void transfer(Account from, Account to, int amount) {
    synchronized(from) {
        synchronized(to) {
            from.balance -= amount;
            to.balance += amount;
        }
    }
}

// Thread 1: transfer(A, B, 100) → locks A, then B
// Thread 2: transfer(B, A, 200) → locks B, then A
// DEADLOCK!


// DEADLOCK FREE ✅
public void transfer(Account from, Account to, int amount) {
    // Always lock in same order (by ID)
    Account first = (from.id < to.id) ? from : to;
    Account second = (from.id < to.id) ? to : from;
    
    synchronized(first) {
        synchronized(second) {
            from.balance -= amount;
            to.balance += amount;
        }
    }
}

// Thread 1: transfer(A, B, 100) → locks A, then B
// Thread 2: transfer(B, A, 200) → locks A, then B (same order!)
// NO DEADLOCK! T2 waits for A, gets both, releases, T1 proceeds
```

### Prevention Strategy 2: tryLock with Timeout

**Break:** Hold and Wait condition

```java
ReentrantLock lockA = new ReentrantLock();
ReentrantLock lockB = new ReentrantLock();

public boolean transfer(int amount) {
    boolean gotA = false;
    boolean gotB = false;
    
    try {
        // Try to get lock A with timeout
        gotA = lockA.tryLock(500, TimeUnit.MILLISECONDS);
        if (!gotA) {
            return false;  // Couldn't get A, abort
        }
        
        // Try to get lock B with timeout
        gotB = lockB.tryLock(500, TimeUnit.MILLISECONDS);
        if (!gotB) {
            // Couldn't get B, release A and retry
            return false;
        }
        
        // Both locks acquired! Do transfer
        doTransfer(amount);
        return true;
        
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return false;
    } finally {
        // Always release in reverse order
        if (gotB) lockB.unlock();
        if (gotA) lockA.unlock();
    }
}

// If timeout occurs, thread releases locks and retries
// No holding while waiting indefinitely!
```

### Prevention Strategy 3: Single Global Lock

**Break:** Circular Wait condition

```java
// One lock for ALL transfers
private static final Object GLOBAL_LOCK = new Object();

public void transfer(Account from, Account to, int amount) {
    synchronized(GLOBAL_LOCK) {  // Single lock
        from.balance -= amount;
        to.balance += amount;
    }
}

// All threads use same lock
// Impossible to have circular wait!
// Trade-off: Reduced concurrency (serialized execution)
```

### Prevention Strategy 4: Lock-Free (CAS)

**Break:** Mutual Exclusion condition

```java
// No locks at all! Use CAS (Compare-And-Swap)
AtomicLong balance = new AtomicLong(1000);

public boolean withdraw(long amount) {
    while (true) {
        long current = balance.get();
        
        if (current < amount) {
            return false;
        }
        
        // Atomic CAS - no locks!
        if (balance.compareAndSet(current, current - amount)) {
            return true;  // Success!
        }
        // Failed, retry (no deadlock possible!)
    }
}

// NO LOCKS = NO DEADLOCKS!
// Highest performance
// Perfect for simple operations (counters, flags)
```

### Deadlock Detection

```java
import java.lang.management.*;

public class DeadlockDetector {
    
    public static void detectDeadlock() {
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        
        // Find deadlocked threads
        long[] deadlockedIds = mxBean.findDeadlockedThreads();
        
        if (deadlockedIds != null) {
            System.out.println("DEADLOCK DETECTED!");
            
            ThreadInfo[] threadInfos = mxBean.getThreadInfo(deadlockedIds);
            
            for (ThreadInfo info : threadInfos) {
                System.out.println("Thread: " + info.getThreadName());
                System.out.println("State: " + info.getThreadState());
                System.out.println("Blocked on: " + info.getLockName());
                System.out.println("Owned by: " + info.getLockOwnerName());
                System.out.println();
            }
        } else {
            System.out.println("No deadlock detected");
        }
    }
}

// Run periodically in production to detect deadlocks
// Can trigger alerts, auto-restart, etc.
```

### Comparison of Prevention Strategies

```
┌──────────────────┬─────────────┬────────────┬──────────┐
│   Strategy       │ Performance │ Complexity │ Deadlock │
├──────────────────┼─────────────┼────────────┼──────────┤
│ Lock Ordering    │ Excellent   │ Medium     │ Prevents │
│ tryLock/Timeout  │ Good        │ High       │ Prevents │
│ Global Lock      │ Poor        │ Low        │ Prevents │
│ Lock-Free (CAS)  │ Excellent   │ Low        │ N/A      │
└──────────────────┴─────────────┴────────────┴──────────┘

Best Choice for Rate Limiting: CAS (Lock-Free)
  • No locks = No deadlocks
  • Highest performance
  • Simple to implement
```

### Real-World Example: Rate Limiting

```java
// DEADLOCK PRONE ❌ (using synchronized)
class RateLimiter {
    private Map<String, Counter> counters = new HashMap<>();
    
    public synchronized boolean allow(String tenantA, String tenantB) {
        // Nested locking on tenant counters
        synchronized(getCounter(tenantA)) {
            synchronized(getCounter(tenantB)) {
                // Process...
            }
        }
    }
    // Risk: Different order of tenant IDs → deadlock
}


// DEADLOCK FREE ✅ (using CAS)
class RateLimiter {
    private Map<String, AtomicLong> counters = new ConcurrentHashMap<>();
    
    public boolean allow(String tenantId) {
        AtomicLong counter = counters.get(tenantId);
        
        while (true) {
            long current = counter.get();
            if (current <= 0) return false;
            
            if (counter.compareAndSet(current, current - 1)) {
                return true;  // No locks, no deadlocks!
            }
        }
    }
}
```

### Debugging Tools

```
┌─────────────────────────────────────────────────────┐
│  DEADLOCK DETECTION TOOLS                           │
├─────────────────────────────────────────────────────┤
│                                                      │
│  1. JVisualVM (GUI)                                 │
│     • Visual thread view                            │
│     • "Detect Deadlock" button                      │
│     • Shows circular dependencies                   │
│                                                      │
│  2. jstack (Command Line)                           │
│     $ jstack <pid>                                  │
│     • Thread dump                                   │
│     • Shows "Found Java-level deadlock"            │
│                                                      │
│  3. IntelliJ IDEA (Debugger)                        │
│     • Threads view shows BLOCKED threads           │
│     • Highlights circular wait                      │
│                                                      │
│  4. ThreadMXBean (Programmatic)                     │
│     • In production code                            │
│     • Auto-detect and alert                         │
│                                                      │
└─────────────────────────────────────────────────────┘
```

### Best Practices

```
✅ DO:
   • Minimize lock scope (hold briefly)
   • Document lock ordering
   • Use tryLock() with timeout
   • Prefer lock-free (CAS) when possible
   • Monitor with ThreadMXBean in production
   • Test with high concurrency

❌ DON'T:
   • Hold multiple locks unnecessarily
   • Call external code while holding lock
   • Mix synchronized and ReentrantLock
   • Ignore lock ordering discipline
   • Use nested locks without ordering
```

---

## Summary Cheat Sheet

```
╔═══════════════════════════════════════════════════════╗
║  CONCURRENCY MECHANISMS QUICK REFERENCE               ║
╠═══════════════════════════════════════════════════════╣
║                                                        ║
║  MUTEX (synchronized):                                 ║
║    • Binary lock (one thread)                          ║
║    • OS-level blocking                                 ║
║    • Use: Long critical sections (> 1ms)               ║
║                                                        ║
║  SEMAPHORE:                                            ║
║    • Counting (N threads)                              ║
║    • OS-level blocking                                 ║
║    • Use: Resource pooling                             ║
║                                                        ║
║  CAS (AtomicLong):                                     ║
║    • Lock-free, CPU-level                              ║
║    • No blocking (spin)                                ║
║    • Use: Tiny operations (< 100ns)                    ║
║                                                        ║
║  Decision: If operation < 100ns → CAS                  ║
║            If resource pool → Semaphore                ║
║            Otherwise → Mutex                           ║
║                                                        ║
╚═══════════════════════════════════════════════════════╝
```

---

End of Complete Concurrency Reference

