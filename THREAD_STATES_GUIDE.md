# Java Thread States - Complete Guide
## Understanding Thread Lifecycle and State Transitions

---

## Table of Contents
1. [Overview of Thread States](#overview-of-thread-states)
2. [The 6 Thread States](#the-6-thread-states)
3. [State Transitions](#state-transitions)
4. [Visual Diagrams](#visual-diagrams)
5. [Common Scenarios](#common-scenarios)
6. [Interview Q&A](#interview-qa)

---

## Overview of Thread States

Java defines **6 thread states** in the `Thread.State` enum:

```java
public enum State {
    NEW,
    RUNNABLE,
    BLOCKED,
    WAITING,
    TIMED_WAITING,
    TERMINATED
}
```

**Key Point:** These are **JVM thread states**, not OS thread states!

---

## The 6 Thread States

### 1. NEW

**Definition:** Thread has been created but not yet started.

```java
Thread thread = new Thread(() -> {
    System.out.println("Hello");
});

System.out.println(thread.getState());  // NEW
```

**Characteristics:**
```
┌────────────────────────────────────────────┐
│  NEW State                                 │
├────────────────────────────────────────────┤
│  • Thread object exists in memory          │
│  • No OS thread created yet                │
│  • No system resources allocated           │
│  • Not scheduled by OS                     │
│  • start() has not been called             │
└────────────────────────────────────────────┘
```

**Transition:**
```
NEW ──start()──> RUNNABLE
```

**Example:**
```java
Thread t = new Thread(() -> {
    // Work here
});

// State: NEW (created but not started)
assert t.getState() == Thread.State.NEW;

t.start();  // Transition to RUNNABLE

// State: RUNNABLE (now running or ready to run)
```

---

### 2. RUNNABLE

**Definition:** Thread is either executing or ready to execute.

```java
Thread thread = new Thread(() -> {
    for (int i = 0; i < 1000; i++) {
        System.out.println(i);  // Thread is RUNNABLE here
    }
});

thread.start();  // NEW → RUNNABLE
```

**Characteristics:**
```
┌────────────────────────────────────────────┐
│  RUNNABLE State                            │
├────────────────────────────────────────────┤
│  • Thread is executing on CPU, OR          │
│  • Ready to run (waiting for CPU time)     │
│  • Has all resources needed                │
│  • OS scheduler decides when it runs       │
│  • Can be preempted by OS                  │
└────────────────────────────────────────────┘

IMPORTANT: RUNNABLE includes BOTH:
  1. Actually running on CPU
  2. Ready to run (in run queue)
```

**Why "RUNNABLE" not "RUNNING"?**

Java doesn't distinguish between "running" and "ready to run" because:
- OS controls actual CPU scheduling
- Thread can be preempted at any time
- JVM doesn't know if thread is on CPU or waiting

**Visual:**
```
CPU Timeline:
│
├─ Thread A RUNNING  ─┤  (on CPU)
                      │
                      ├─ Thread B RUNNING ─┤  (A preempted, B gets CPU)
                                            │
                                            ├─ Thread A RUNNING ─┤

Both threads are in RUNNABLE state the entire time!
```

**Transitions from RUNNABLE:**
```
RUNNABLE ──sleep()────────> TIMED_WAITING
RUNNABLE ──wait()─────────> WAITING
RUNNABLE ──synchronized──> BLOCKED (if lock held)
RUNNABLE ──run() ends────> TERMINATED
```

---

### 3. BLOCKED

**Definition:** Waiting to acquire a monitor lock (synchronized).

```java
synchronized(lock) {  // If lock is held by another thread,
    // this thread enters BLOCKED state
}
```

**Characteristics:**
```
┌────────────────────────────────────────────┐
│  BLOCKED State                             │
├────────────────────────────────────────────┤
│  • Waiting to acquire monitor lock         │
│  • Caused by synchronized keyword          │
│  • Cannot proceed until lock available     │
│  • Automatically transitions when ready    │
│  • CANNOT be interrupted                   │
└────────────────────────────────────────────┘
```

**Visual Example:**
```
Object lock = new Object();

Thread 1:                    Thread 2:
┌────────────────────┐      ┌────────────────────┐
│ synchronized(lock) │      │ synchronized(lock) │
│ {                  │      │ {                  │
│   // Got lock! ✓   │      │   // Want lock... │ ← BLOCKED
│   sleep(2000);     │      │                    │
│ }                  │      │                    │
└────────────────────┘      └────────────────────┘
        │                            ↑
        └─ Releases lock ────────────┘
                                     │
                            Now RUNNABLE (got lock)
```

**State Transition:**
```
RUNNABLE ──synchronized (contested)──> BLOCKED
BLOCKED  ──lock becomes available────> RUNNABLE
```

**Code Example:**
```java
Object lock = new Object();

Thread t1 = new Thread(() -> {
    synchronized(lock) {
        try {
            Thread.sleep(3000);  // Hold lock for 3 seconds
        } catch (InterruptedException e) {}
    }
});

Thread t2 = new Thread(() -> {
    synchronized(lock) {  // Will BLOCK here
        System.out.println("Finally got lock!");
    }
});

t1.start();
Thread.sleep(100);  // Let t1 acquire lock
t2.start();
Thread.sleep(500);

System.out.println(t2.getState());  // BLOCKED
```

---

### 4. WAITING

**Definition:** Waiting indefinitely for another thread's action.

```java
synchronized(lock) {
    lock.wait();  // RUNNABLE → WAITING
}
```

**Characteristics:**
```
┌────────────────────────────────────────────┐
│  WAITING State                             │
├────────────────────────────────────────────┤
│  • Waiting indefinitely                    │
│  • Releases monitor lock (if held)         │
│  • Needs another thread to signal          │
│  • CAN be interrupted                      │
└────────────────────────────────────────────┘
```

**Caused by:**
```
1. Object.wait()           // Wait for notify()
2. Thread.join()           // Wait for thread completion
3. LockSupport.park()      // Low-level park
```

**Visual Example:**
```
Thread 1 (Waiter):
┌─────────────────────────────────────┐
│ synchronized(lock) {                │
│   lock.wait();  // → WAITING       │ ← Releases lock
│   // Suspended here...              │
│ }                                   │
└─────────────────────────────────────┘
         ↓
    (sleeping)
         ↑
         │ notify() from Thread 2
         │
┌─────────────────────────────────────┐
│ synchronized(lock) {                │
│   // → BLOCKED (reacquire lock)    │
│   // → RUNNABLE (got lock)         │
│   // Continue execution             │
│ }                                   │
└─────────────────────────────────────┘
```

**State Transition:**
```
RUNNABLE ──wait()/join()/park()──> WAITING
WAITING  ──notify()/notifyAll()──> BLOCKED (to reacquire lock)
BLOCKED  ──lock acquired─────────> RUNNABLE
```

**Code Example:**
```java
Object lock = new Object();

Thread waiter = new Thread(() -> {
    synchronized(lock) {
        try {
            System.out.println("Waiting...");
            lock.wait();  // RUNNABLE → WAITING
            System.out.println("Notified!");
        } catch (InterruptedException e) {}
    }
});

Thread notifier = new Thread(() -> {
    try {
        Thread.sleep(2000);
        synchronized(lock) {
            lock.notify();  // Wake up waiter
        }
    } catch (InterruptedException e) {}
});

waiter.start();
Thread.sleep(500);
System.out.println(waiter.getState());  // WAITING

notifier.start();
```

---

### 5. TIMED_WAITING

**Definition:** Waiting for a specified time period.

```java
Thread.sleep(1000);  // RUNNABLE → TIMED_WAITING for 1 second
```

**Characteristics:**
```
┌────────────────────────────────────────────┐
│  TIMED_WAITING State                       │
├────────────────────────────────────────────┤
│  • Waiting for specified time              │
│  • Automatically resumes after timeout     │
│  • OR can be woken by notify()             │
│  • CAN be interrupted                      │
└────────────────────────────────────────────┘
```

**Caused by:**
```
1. Thread.sleep(ms)        // Sleep for milliseconds
2. Object.wait(timeout)    // Wait with timeout
3. Thread.join(timeout)    // Wait for thread with timeout
4. LockSupport.parkNanos() // Park with nanoseconds timeout
5. LockSupport.parkUntil() // Park until absolute time
```

**Visual Timeline:**
```
Thread.sleep(2000):

Time 0ms:    RUNNABLE
             │
             │ sleep(2000)
             ↓
Time 0ms:    TIMED_WAITING
             │
             │ (sleeping for 2000ms)
             │
Time 2000ms: RUNNABLE (automatically)
```

**State Transition:**
```
RUNNABLE ──sleep(ms)/wait(ms)──> TIMED_WAITING
TIMED_WAITING ──timeout──────────> RUNNABLE
TIMED_WAITING ──notify()─────────> BLOCKED → RUNNABLE
TIMED_WAITING ──interrupt()──────> RUNNABLE (with InterruptedException)
```

**Code Example:**
```java
Thread sleeper = new Thread(() -> {
    try {
        System.out.println("Going to sleep");
        Thread.sleep(3000);  // RUNNABLE → TIMED_WAITING
        System.out.println("Woke up");
    } catch (InterruptedException e) {
        System.out.println("Interrupted!");
    }
});

sleeper.start();
Thread.sleep(500);

System.out.println(sleeper.getState());  // TIMED_WAITING
```

---

### 6. TERMINATED

**Definition:** Thread has completed execution.

```java
Thread thread = new Thread(() -> {
    System.out.println("Work done");
});  // Thread will become TERMINATED when run() exits

thread.start();
thread.join();  // Wait for completion

System.out.println(thread.getState());  // TERMINATED
```

**Characteristics:**
```
┌────────────────────────────────────────────┐
│  TERMINATED State                          │
├────────────────────────────────────────────┤
│  • Thread completed execution              │
│  • run() method exited                     │
│  • All resources released                  │
│  • FINAL state - no more transitions       │
│  • CANNOT be restarted                     │
└────────────────────────────────────────────┘
```

**Causes:**
```
1. run() method returns normally
2. Uncaught exception in run()
3. Thread.stop() (deprecated, don't use!)
```

**State Transition:**
```
RUNNABLE ──run() completes──> TERMINATED
RUNNABLE ──exception────────> TERMINATED

TERMINATED cannot transition to any other state!
```

**Important:** Cannot restart a terminated thread:
```java
Thread t = new Thread(() -> {});
t.start();
t.join();  // Wait for termination

// State: TERMINATED

t.start();  // ❌ IllegalThreadStateException!
```

---

## State Transitions

### Complete State Transition Diagram

```
                          start()
                NEW ────────────→ RUNNABLE ←──────────────────┐
                                     │  ↑                      │
                                     │  │                      │
                        sleep()      │  │ timeout/notify()     │
                        wait(ms)     │  │                      │
                                     ↓  │                      │
                              TIMED_WAITING                    │
                                                               │
                                  RUNNABLE                     │
                                     │  ↑                      │
                                     │  │                      │
                        wait()       │  │ notify()/           │
                        join()       │  │ notifyAll()         │
                        park()       │  │                      │
                                     ↓  │                      │
                                 WAITING ──────────────────────┘
                                                  (via BLOCKED)
                                  RUNNABLE
                                     │  ↑
                                     │  │
                      synchronized   │  │ lock
                      (contested)    │  │ available
                                     ↓  │
                                  BLOCKED
            
                                  RUNNABLE
                                     │
                                     │ run() completes
                                     │ or exception
                                     ↓
                                TERMINATED
```

### Detailed Transitions

```
FROM NEW:
  start() → RUNNABLE

FROM RUNNABLE:
  Thread.sleep(ms) → TIMED_WAITING
  Object.wait() → WAITING
  Object.wait(ms) → TIMED_WAITING
  Thread.join() → WAITING
  Thread.join(ms) → TIMED_WAITING
  synchronized (contested) → BLOCKED
  run() completes → TERMINATED
  uncaught exception → TERMINATED

FROM BLOCKED:
  lock acquired → RUNNABLE

FROM WAITING:
  Object.notify()/notifyAll() → BLOCKED (then → RUNNABLE)
  Thread.interrupt() → BLOCKED (then → RUNNABLE with exception)

FROM TIMED_WAITING:
  timeout expires → RUNNABLE
  Object.notify()/notifyAll() → BLOCKED (then → RUNNABLE)
  Thread.interrupt() → RUNNABLE (with exception)

FROM TERMINATED:
  (none - final state)
```

---

## Visual Diagrams

### Blocking vs Waiting vs Timed Waiting

```
┌─────────────────────────────────────────────────────────────┐
│                    COMPARISON                                │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  BLOCKED:                                                   │
│  ┌───────────────────────────────────────┐                 │
│  │ Thread wants to enter synchronized    │                 │
│  │ block but lock is held by another     │                 │
│  │ thread. Automatically transitions     │                 │
│  │ when lock becomes available.          │                 │
│  └───────────────────────────────────────┘                 │
│                                                              │
│  Example:                                                    │
│  synchronized(obj) {  // ← Becomes BLOCKED if obj is held  │
│      // work                                                │
│  }                                                           │
│                                                              │
│  WAITING:                                                   │
│  ┌───────────────────────────────────────┐                 │
│  │ Thread explicitly called wait() and   │                 │
│  │ is waiting indefinitely for another   │                 │
│  │ thread to call notify()/notifyAll().  │                 │
│  │ Releases the monitor lock.            │                 │
│  └───────────────────────────────────────┘                 │
│                                                              │
│  Example:                                                    │
│  synchronized(obj) {                                        │
│      obj.wait();  // ← Becomes WAITING                     │
│  }                                                           │
│                                                              │
│  TIMED_WAITING:                                             │
│  ┌───────────────────────────────────────┐                 │
│  │ Thread is waiting for specified time. │                 │
│  │ Automatically resumes after timeout   │                 │
│  │ OR can be woken by notify().          │                 │
│  └───────────────────────────────────────┘                 │
│                                                              │
│  Example:                                                    │
│  Thread.sleep(1000);  // ← Becomes TIMED_WAITING          │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

### Real-World Example: Producer-Consumer

```
Producer Thread:                Consumer Thread:
┌───────────────────────┐      ┌───────────────────────┐
│ while (true) {        │      │ while (true) {        │
│   synchronized(q) {   │      │   synchronized(q) {   │
│     while (q.full())  │      │     while (q.empty()) │
│       q.wait();       │      │       q.wait();       │
│     │  WAITING        │      │     │  WAITING        │
│     │                 │      │     │                 │
│     q.add(item);      │      │     item = q.remove();│
│     q.notifyAll();    │──────┼────>│  BLOCKED       │
│   }                   │      │     │  RUNNABLE       │
│ }                     │      │   }                   │
└───────────────────────┘      └───────────────────────┘
```

---

## Common Scenarios

### Scenario 1: Thread.sleep()

```java
Thread thread = new Thread(() -> {
    try {
        Thread.sleep(2000);
    } catch (InterruptedException e) {}
});

// State transitions:
thread.start();            // NEW → RUNNABLE
Thread.sleep(100);         
// thread is TIMED_WAITING (sleeping)
Thread.sleep(2000);        
// thread is RUNNABLE (woke up)
thread.join();             
// thread is TERMINATED (completed)
```

### Scenario 2: wait() and notify()

```java
Object lock = new Object();

Thread waiter = new Thread(() -> {
    synchronized(lock) {
        try {
            lock.wait();  // RUNNABLE → WAITING
        } catch (InterruptedException e) {}
    }
});

Thread notifier = new Thread(() -> {
    synchronized(lock) {
        lock.notify();  // Wakes waiter
    }
});

// States:
waiter.start();     // NEW → RUNNABLE → WAITING
notifier.start();   // NEW → RUNNABLE
// waiter: WAITING → BLOCKED → RUNNABLE → TERMINATED
```

### Scenario 3: Deadlock

```java
Object lock1 = new Object();
Object lock2 = new Object();

Thread t1 = new Thread(() -> {
    synchronized(lock1) {
        synchronized(lock2) {  // Will BLOCK forever
            // never reaches here
        }
    }
});

Thread t2 = new Thread(() -> {
    synchronized(lock2) {
        synchronized(lock1) {  // Will BLOCK forever
            // never reaches here
        }
    }
});

t1.start();
t2.start();

// States:
// t1: RUNNABLE (has lock1) → BLOCKED (wants lock2)
// t2: RUNNABLE (has lock2) → BLOCKED (wants lock1)
// Both stuck in BLOCKED state forever!
```

---

## Interview Q&A

### Q1: How many thread states are there in Java?

**Answer:**
> "Java has 6 thread states defined in the Thread.State enum: NEW, RUNNABLE, BLOCKED, WAITING, TIMED_WAITING, and TERMINATED. These are JVM-level states, not OS thread states."

### Q2: What's the difference between BLOCKED and WAITING?

**Answer:**
> "BLOCKED is specifically for threads waiting to acquire a monitor lock (synchronized). It happens automatically and cannot be interrupted.
>
> WAITING is for threads that explicitly called wait(), join(), or park(). They're waiting indefinitely for another thread's action. WAITING releases the monitor lock if held, while BLOCKED doesn't hold the lock yet.
>
> Example:
> - BLOCKED: synchronized(obj) { } when obj is held by another thread
> - WAITING: synchronized(obj) { obj.wait(); } explicitly releases lock"

### Q3: What does RUNNABLE mean? Is the thread running?

**Answer:**
> "RUNNABLE means the thread is either actually executing on CPU OR ready to execute (in the OS run queue). Java doesn't distinguish between these because:
> 1. OS controls CPU scheduling
> 2. Threads can be preempted at any time
> 3. JVM doesn't have visibility into OS scheduling
>
> So a RUNNABLE thread might be running right now, or waiting for its next CPU time slice."

### Q4: Can a thread transition from WAITING to RUNNABLE directly?

**Answer:**
> "No. When notify() is called, the thread transitions from WAITING to BLOCKED (to reacquire the monitor lock), then to RUNNABLE once it gets the lock.
>
> Flow: WAITING → (notify) → BLOCKED → (lock acquired) → RUNNABLE
>
> This is because wait() released the lock, so the thread must reacquire it before continuing."

### Q5: What happens when you call start() on a TERMINATED thread?

**Answer:**
> "It throws IllegalThreadStateException. A terminated thread cannot be restarted. Once a thread completes execution, it's final. If you need to run the same task again, you must create a new Thread object."

### Q6: How do you check a thread's current state?

**Answer:**
> "Use the getState() method:
> ```java
> Thread thread = new Thread(() -> {});
> Thread.State state = thread.getState();
> System.out.println(state);  // e.g., NEW, RUNNABLE
> ```
> This is useful for debugging and monitoring threads in production."

---

## Summary Reference Card

```
╔═══════════════════════════════════════════════════════════╗
║  JAVA THREAD STATES QUICK REFERENCE                       ║
╠═══════════════════════════════════════════════════════════╣
║                                                            ║
║  1. NEW            Thread created, not started            ║
║     • new Thread(...)                                     ║
║     • Transition: start() → RUNNABLE                      ║
║                                                            ║
║  2. RUNNABLE       Executing or ready to execute          ║
║     • After start()                                       ║
║     • Includes both running and ready states              ║
║                                                            ║
║  3. BLOCKED        Waiting for monitor lock               ║
║     • synchronized(obj) when obj is held                  ║
║     • Cannot be interrupted                               ║
║                                                            ║
║  4. WAITING        Waiting indefinitely                   ║
║     • Object.wait(), Thread.join()                        ║
║     • Releases lock, can be interrupted                   ║
║                                                            ║
║  5. TIMED_WAITING  Waiting for specified time             ║
║     • Thread.sleep(ms), Object.wait(ms)                   ║
║     • Auto-resumes after timeout                          ║
║                                                            ║
║  6. TERMINATED     Execution completed                    ║
║     • run() exited                                        ║
║     • Final state, cannot restart                         ║
║                                                            ║
║  Key Methods:                                             ║
║    • thread.getState()     Get current state              ║
║    • thread.start()        NEW → RUNNABLE                 ║
║    • Thread.sleep(ms)      RUNNABLE → TIMED_WAITING       ║
║    • object.wait()         RUNNABLE → WAITING             ║
║    • object.notify()       WAITING → BLOCKED → RUNNABLE   ║
║                                                            ║
╚═══════════════════════════════════════════════════════════╝
```

---

End of Thread States Guide

