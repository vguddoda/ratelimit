# Deadlock Demo - Quick Reference

## 🎯 What You Have

A complete Java demonstration showing how to **CREATE** and **PREVENT** deadlocks with 5 different scenarios.

---

## 🚀 How to Run

```bash
cd /Users/vishalkumarbg/Documents/Bheembali/ratelimit

# Compile
./mvnw test-compile

# Run the demo
./mvnw exec:java \
  -Dexec.mainClass="com.example.demo.DeadlockDemo" \
  -Dexec.classpathScope=test
```

---

## 📋 What You'll See

### Scenario 1: Creating a Deadlock ⚠️
```
Thread-1: Acquired lock on A
Thread-2: Acquired lock on B
Thread-1: Trying to acquire lock on B  ← BLOCKED!
Thread-2: Trying to acquire lock on A  ← BLOCKED!

❌ DEADLOCK OCCURRED!
Thread-1 holds A, wants B
Thread-2 holds B, wants A
Both stuck forever!
```

**The Problem:**
```
Thread 1: synchronized(A) { synchronized(B) { ... } }
Thread 2: synchronized(B) { synchronized(A) { ... } }

Circular wait: A → B → A
```

---

### Scenario 2: Prevention with Lock Ordering ✅
```
Thread-1: Acquired lock on A
Thread-1: Acquired lock on B
Thread-1: Transfer complete!
Thread-2: Acquired lock on A
Thread-2: Acquired lock on B
Thread-2: Transfer complete!

✅ SUCCESS! No deadlock occurred.
Both threads acquired locks in same order (A before B)
```

**The Solution:**
```java
// Always lock in same order (by ID)
if (this.id < target.id) {
    synchronized(this) { synchronized(target) { ... } }
} else {
    synchronized(target) { synchronized(this) { ... } }
}
```

---

### Scenario 3: Prevention with tryLock (Timeout) ✅
```
Thread-1: Acquired lock on A
Thread-2: Acquired lock on B
Thread-1 (attempt 1): Failed to lock B, releasing A
Thread-2 (attempt 1): Failed to lock A, releasing B
Thread-1: Acquired lock on A
Thread-1: Acquired lock on B
Thread-1: Transfer complete!

✅ SUCCESS! No deadlock occurred.
Threads used timeout and retry on lock contention
```

**The Solution:**
```java
if (lock.tryLock(500, TimeUnit.MILLISECONDS)) {
    try {
        // Got lock! Do work
    } finally {
        lock.unlock();
    }
} else {
    // Timeout! Release and retry
}
```

---

### Scenario 4: Prevention with Global Lock ✅
```
Thread-1: Acquired global lock
Thread-1: Transfer A → B complete!
Thread-2: Acquired global lock
Thread-2: Transfer B → A complete!

✅ SUCCESS! No deadlock occurred.
Single global lock prevents circular wait
Trade-off: Reduced concurrency (serialized execution)
```

**The Solution:**
```java
static final Object GLOBAL_LOCK = new Object();

synchronized(GLOBAL_LOCK) {
    // All transfers use same lock
    // No circular wait possible!
}
```

---

### Scenario 5: Deadlock Detection 🔍
```
⚠️  DEADLOCK DETECTED!
Number of deadlocked threads: 2

Thread: DeadlockThread-1
State: BLOCKED
Blocked on: BankAccount@abc123
Owned by: DeadlockThread-2

Thread: DeadlockThread-2
State: BLOCKED
Blocked on: BankAccount@def456
Owned by: DeadlockThread-1

💡 Detection Method: ThreadMXBean.findDeadlockedThreads()
```

**Detection Code:**
```java
ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
long[] deadlocked = mxBean.findDeadlockedThreads();

if (deadlocked != null) {
    System.out.println("DEADLOCK DETECTED!");
    // Get thread info and print
}
```

---

## 🎓 Key Concepts

### The 4 Deadlock Conditions (ALL must be true)

```
┌─────────────────────────────────────────────────┐
│  1. MUTUAL EXCLUSION                            │
│     Resources cannot be shared                  │
│     Example: synchronized(lock) { ... }         │
│                                                  │
│  2. HOLD AND WAIT                               │
│     Thread holds lock while waiting for another │
│     Example: Have A, want B                     │
│                                                  │
│  3. NO PREEMPTION                               │
│     Can't force thread to release lock          │
│     Example: Can't interrupt synchronized       │
│                                                  │
│  4. CIRCULAR WAIT                               │
│     Thread cycle: A → B → C → A                │
│     Example: T1 has A wants B, T2 has B wants A│
└─────────────────────────────────────────────────┘

To prevent deadlock: BREAK ANY ONE CONDITION!
```

---

## 🛡️ Prevention Strategies

### Strategy 1: Lock Ordering (Best Performance)

**Break:** Circular Wait

```java
// BAD: Can deadlock
synchronized(accountA) {
    synchronized(accountB) { ... }
}

// GOOD: Always same order
if (accountA.id < accountB.id) {
    synchronized(accountA) {
        synchronized(accountB) { ... }
    }
} else {
    synchronized(accountB) {
        synchronized(accountA) { ... }
    }
}
```

**Pros:** Fast, no retries  
**Cons:** Need consistent ordering scheme

---

### Strategy 2: tryLock with Timeout (Most Robust)

**Break:** Hold and Wait

```java
ReentrantLock lock1 = new ReentrantLock();
ReentrantLock lock2 = new ReentrantLock();

if (lock1.tryLock(500, MILLISECONDS)) {
    try {
        if (lock2.tryLock(500, MILLISECONDS)) {
            try {
                // Both locks acquired!
            } finally {
                lock2.unlock();
            }
        } else {
            // Timeout on lock2, release lock1 and retry
        }
    } finally {
        lock1.unlock();
    }
}
```

**Pros:** Handles unexpected deadlocks gracefully  
**Cons:** Retry overhead, more complex

---

### Strategy 3: Single Global Lock (Simplest)

**Break:** Circular Wait

```java
static final Object GLOBAL = new Object();

synchronized(GLOBAL) {
    // All operations use same lock
    // Impossible to have circular wait!
}
```

**Pros:** Simple, guaranteed deadlock-free  
**Cons:** Kills concurrency (serializes all operations)

---

### Strategy 4: Lock-Free (CAS) (Best for Counters)

**Break:** Mutual Exclusion

```java
AtomicLong counter = new AtomicLong(1000);

while (true) {
    long current = counter.get();
    if (counter.compareAndSet(current, current - 1)) {
        break;  // NO LOCKS = NO DEADLOCKS!
    }
}
```

**Pros:** No locks, no deadlocks, highest performance  
**Cons:** Only works for simple operations

---

## 🔍 Deadlock Detection Tools

### 1. In Code (Production)
```java
ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
long[] deadlocked = mxBean.findDeadlockedThreads();

if (deadlocked != null) {
    // Log alert, send notification
    // Auto-restart service
}
```

### 2. JVisualVM (Development)
```bash
jvisualvm
# Threads tab → "Detect Deadlock" button
```

### 3. jstack (Command Line)
```bash
# Find Java process ID
jps

# Get thread dump
jstack <pid>

# Look for "Found one Java-level deadlock"
```

### 4. IntelliJ IDEA (Debug)
```
1. Set breakpoint
2. Debug → Threads view
3. Look for "BLOCKED" threads
4. IDE highlights circular wait
```

---

## 📊 Comparison Table

```
┌──────────────────┬─────────────┬────────────┬──────────┐
│   Strategy       │ Performance │ Complexity │ Robustness│
├──────────────────┼─────────────┼────────────┼──────────┤
│ Lock Ordering    │ Excellent   │ Medium     │ Good     │
│ tryLock/Timeout  │ Good        │ High       │ Excellent│
│ Global Lock      │ Poor        │ Low        │ Good     │
│ Lock-Free (CAS)  │ Excellent   │ Low        │ Excellent│
└──────────────────┴─────────────┴────────────┴──────────┘

For Rate Limiting: Use CAS (AtomicLong) ✨
```

---

## 💡 Interview Answers

### Q: What is a deadlock?

> "A deadlock occurs when two or more threads wait for each other circularly, all blocked forever. Classic example: Thread 1 holds Lock A and wants Lock B, while Thread 2 holds Lock B and wants Lock A. Both wait indefinitely."

### Q: How do you prevent deadlock?

> "Four strategies:
> 1. **Lock ordering** - Always acquire locks in same order globally
> 2. **tryLock with timeout** - Use ReentrantLock.tryLock(), release and retry on timeout
> 3. **Single global lock** - One lock for all operations (simple but reduces concurrency)
> 4. **Lock-free algorithms** - Use CAS (AtomicLong) - no locks means no deadlocks!
>
> For our rate limiting, we use CAS which is both deadlock-free and highest performance."

### Q: How do you detect deadlock?

> "In production, use ThreadMXBean.findDeadlockedThreads() to programmatically detect deadlocks. During development, use JVisualVM or jstack for thread dumps. Modern IDEs like IntelliJ also highlight deadlocks in debugger."

### Q: What are the 4 conditions for deadlock?

> "All 4 must be true:
> 1. Mutual exclusion (resources can't be shared)
> 2. Hold and wait (holding lock while waiting for another)
> 3. No preemption (can't force release)
> 4. Circular wait (A → B → C → A)
>
> Prevent deadlock by breaking ANY one condition."

---

## 🎯 Summary

✅ **Created:** Working deadlock example  
✅ **Prevented:** 4 different strategies  
✅ **Detected:** ThreadMXBean detection code  
✅ **Explained:** All concepts with visuals  

**Run the demo to see everything in action!**

```bash
./mvnw exec:java \
  -Dexec.mainClass="com.example.demo.DeadlockDemo" \
  -Dexec.classpathScope=test
```

**You're deadlock-ready for interviews!** 🚀

