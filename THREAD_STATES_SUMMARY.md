# ✅ COMPLETE! Thread States & Deadlock - Quick Start

## 🎯 What You Asked For

> "Also explain thread states and how states will change"

## ✅ What You Now Have

### NEW Files Created

#### 1. **ThreadStatesDemo.java** ✨ RUNNABLE
**Location:** `src/test/java/com/example/demo/ThreadStatesDemo.java`

**Demonstrates ALL 6 Java thread states with live examples:**
- ✅ NEW - Thread created but not started
- ✅ RUNNABLE - Executing or ready to execute
- ✅ BLOCKED - Waiting for monitor lock
- ✅ WAITING - Waiting indefinitely for notify()
- ✅ TIMED_WAITING - Waiting with timeout (sleep)
- ✅ TERMINATED - Execution completed

**Plus:** Complete state transition demonstration showing how threads move between states

#### 2. **THREAD_STATES_GUIDE.md** 📚 COMPLETE REFERENCE
**Complete documentation including:**
- All 6 thread states explained in depth
- State transition diagrams (visual)
- Detailed explanation of each transition
- BLOCKED vs WAITING vs TIMED_WAITING comparison
- Real-world examples (Producer-Consumer, Deadlock)
- Interview Q&A (ready-made answers)
- Quick reference card

#### 3. **DEADLOCK_SUMMARY.md** (Updated)
Added thread states section with quick reference

---

## 🚀 Run The Demo

```bash
cd /Users/vishalkumarbg/Documents/Bheembali/ratelimit

# Compile
./mvnw test-compile

# Run thread states demo
./mvnw exec:java \
  -Dexec.mainClass="com.example.demo.ThreadStatesDemo" \
  -Dexec.classpathScope=test
```

### What You'll See

```
═══════════════════════════════════════════════════════════
STATE 1: NEW
═══════════════════════════════════════════════════════════
Thread created but not started yet
State: NEW

Explanation:
- Thread object created but start() not called
- No system resources allocated yet
- Not scheduled by OS

═══════════════════════════════════════════════════════════
STATE 2: RUNNABLE
═══════════════════════════════════════════════════════════
Thread is executing!
Working... 0
Working... 1
State while running: RUNNABLE

Explanation:
- Thread is executing on CPU OR ready to run
- OS scheduler gives it time slices

═══════════════════════════════════════════════════════════
STATE 3: BLOCKED
═══════════════════════════════════════════════════════════
Thread-1: Acquired lock, holding for 3 seconds
Thread-2: Trying to acquire lock...

Thread-2 state: BLOCKED

Explanation:
- Thread-2 is waiting to acquire monitor lock
- Blocked by synchronized keyword
- Automatically transitions to RUNNABLE when lock available

... (continues through all 6 states)
```

---

## 📋 The 6 Thread States

### Quick Overview

```
┌────────────────────────────────────────────────────┐
│  State           Cause                    Duration │
├────────────────────────────────────────────────────┤
│  NEW             new Thread()             Until    │
│                                            start() │
│                                                     │
│  RUNNABLE        start() called           Until    │
│                  or resumed               blocked  │
│                                                     │
│  BLOCKED         synchronized(locked)     Until    │
│                                            lock     │
│                                            free     │
│                                                     │
│  WAITING         wait(), join()           Until    │
│                                            notify() │
│                                                     │
│  TIMED_WAITING   sleep(ms), wait(ms)     Until    │
│                                            timeout  │
│                                                     │
│  TERMINATED      run() exits              Forever  │
│                                            (final)  │
└────────────────────────────────────────────────────┘
```

### State Transitions Flowchart

```
                          start()
                NEW ────────────→ RUNNABLE
                                     │
                   ┌─────────────────┼─────────────────┐
                   │                 │                 │
              sleep(ms)          wait()         synchronized
                   │                 │           (contested)
                   ↓                 ↓                 ↓
            TIMED_WAITING        WAITING           BLOCKED
                   │                 │                 │
              timeout/          notify()/         lock
              interrupt         notifyAll()      available
                   │                 │                 │
                   └─────────────────┴─────────────────┘
                                     │
                              back to RUNNABLE
                                     │
                               run() completes
                                     ↓
                               TERMINATED
```

---

## 🎓 Key Concepts

### 1. What is NEW?

**Definition:** Thread object exists but OS thread not created yet.

```java
Thread t = new Thread(() -> System.out.println("Hello"));
// State: NEW

t.start();
// State: RUNNABLE
```

**Characteristics:**
- Thread object in memory
- No OS resources allocated
- Cannot do work yet
- One-way transition: NEW → RUNNABLE (via start())

---

### 2. What is RUNNABLE?

**Definition:** Thread is either running on CPU OR ready to run.

**Important:** Java doesn't distinguish "running" vs "ready" because:
- OS controls CPU scheduling
- Thread can be preempted anytime
- JVM has no visibility into OS scheduling

```java
Thread t = new Thread(() -> {
    // This code executes in RUNNABLE state
    for (int i = 0; i < 1000000; i++) {
        // Still RUNNABLE, even if preempted by OS
    }
});
t.start();  // NEW → RUNNABLE
```

---

### 3. What is BLOCKED?

**Definition:** Waiting to acquire a monitor lock (synchronized).

**Key Points:**
- Only caused by `synchronized` keyword
- Happens automatically (not explicit call)
- Cannot be interrupted
- Transitions automatically when lock available

```java
Object lock = new Object();

// Thread 1 holds lock
synchronized(lock) {
    Thread.sleep(5000);
}

// Thread 2 tries to enter (becomes BLOCKED)
synchronized(lock) {  // ← BLOCKED here
    System.out.println("Got lock!");
}
```

**Flow:**
```
Thread 2: RUNNABLE → synchronized(lock) → BLOCKED → lock free → RUNNABLE
```

---

### 4. What is WAITING?

**Definition:** Waiting indefinitely for another thread's action.

**Key Points:**
- Explicit call: wait(), join(), park()
- **Releases monitor lock** (if held)
- Can be interrupted
- Must be notified to resume

```java
Object lock = new Object();

synchronized(lock) {
    lock.wait();  // RUNNABLE → WAITING (releases lock!)
}

// Another thread:
synchronized(lock) {
    lock.notify();  // Wakes up waiter
}
```

**Flow:**
```
Thread: RUNNABLE → wait() → WAITING → notify() → BLOCKED → RUNNABLE
                            (releases)          (reacquire)
```

**Why BLOCKED after notify()?**
Because wait() released the lock, so thread must reacquire it!

---

### 5. What is TIMED_WAITING?

**Definition:** Waiting for a specified time period.

**Key Points:**
- Like WAITING but with timeout
- Automatically resumes after timeout
- Can also be woken by notify()
- Can be interrupted

```java
// Method 1: Thread.sleep()
Thread.sleep(2000);  // RUNNABLE → TIMED_WAITING for 2 seconds

// Method 2: Object.wait(timeout)
synchronized(lock) {
    lock.wait(2000);  // RUNNABLE → TIMED_WAITING
}

// Method 3: Thread.join(timeout)
thread.join(1000);  // RUNNABLE → TIMED_WAITING
```

**Flow:**
```
Thread: RUNNABLE → sleep(2000) → TIMED_WAITING → timeout → RUNNABLE
                                 (2 seconds)
```

---

### 6. What is TERMINATED?

**Definition:** Thread has completed execution.

**Key Points:**
- run() method exited
- All resources released
- **Final state** - no more transitions
- **Cannot be restarted** (IllegalThreadStateException)

```java
Thread t = new Thread(() -> {
    System.out.println("Work done");
});  // TERMINATED when run() exits

t.start();
t.join();  // Wait for completion

System.out.println(t.getState());  // TERMINATED

t.start();  // ❌ IllegalThreadStateException!
```

---

## 🔍 BLOCKED vs WAITING vs TIMED_WAITING

### The Key Differences

```
┌─────────────────────────────────────────────────────────┐
│                     COMPARISON                           │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  BLOCKED:                                               │
│  • Only for synchronized locks                          │
│  • Automatic (not explicit)                             │
│  • Cannot be interrupted                                │
│  • Doesn't release lock (never had it)                  │
│  • Example: synchronized(obj) when obj is held          │
│                                                          │
│  WAITING:                                               │
│  • Explicit call (wait(), join(), park())               │
│  • Releases lock if held                                │
│  • Can be interrupted                                   │
│  • Waits indefinitely for signal                        │
│  • Example: obj.wait()                                  │
│                                                          │
│  TIMED_WAITING:                                         │
│  • Like WAITING but with timeout                        │
│  • Automatically resumes after time                     │
│  • Can be interrupted                                   │
│  • Example: Thread.sleep(1000)                          │
│                                                          │
└─────────────────────────────────────────────────────────┘
```

### Visual Example

```java
// BLOCKED Example
synchronized(lock) {  // If lock held, thread enters BLOCKED
    // Only ONE way to exit: lock becomes available
}

// WAITING Example
synchronized(lock) {
    lock.wait();  // Enters WAITING, releases lock
    // TWO ways to exit:
    // 1. Another thread calls notify()
    // 2. Thread is interrupted
}

// TIMED_WAITING Example
Thread.sleep(1000);  // Enters TIMED_WAITING
// THREE ways to exit:
// 1. Timeout expires (1000ms)
// 2. Thread is interrupted
// 3. (if wait(ms)) Another thread calls notify()
```

---

## 💡 Interview Q&A

### Q: What are the 6 Java thread states?

> "Java has 6 thread states: NEW (created not started), RUNNABLE (executing or ready), BLOCKED (waiting for synchronized lock), WAITING (waiting indefinitely for notify), TIMED_WAITING (waiting with timeout like sleep), and TERMINATED (completed execution). Check with thread.getState()."

### Q: What's the difference between BLOCKED and WAITING?

> "BLOCKED is specifically for synchronized locks - automatic, can't be interrupted. WAITING is for explicit wait() calls - releases the lock, can be interrupted. 
>
> Example: BLOCKED when trying to enter synchronized block. WAITING after calling obj.wait() inside synchronized block."

### Q: Can RUNNABLE mean the thread is NOT running?

> "Yes! RUNNABLE means either executing on CPU OR ready to execute. Java doesn't distinguish because OS controls scheduling. A RUNNABLE thread might be waiting for its CPU time slice."

### Q: How does notify() wake a WAITING thread?

> "When notify() is called, the thread goes from WAITING to BLOCKED (not directly to RUNNABLE!). This is because wait() released the monitor lock, so the thread must reacquire it before continuing. Flow: WAITING → BLOCKED → (lock acquired) → RUNNABLE."

### Q: Can you restart a TERMINATED thread?

> "No! TERMINATED is final. Calling start() on a terminated thread throws IllegalThreadStateException. If you need to run the same task again, create a new Thread object."

---

## 📊 Complete Materials

```
Thread States & Deadlock:
├── ThreadStatesDemo.java          ← Runnable demo (6 states)
├── THREAD_STATES_GUIDE.md        ← Complete reference
├── DeadlockDemo.java              ← Deadlock demo
├── DEADLOCK_DEMO_README.md       ← Deadlock guide
└── THREAD_STATES_SUMMARY.md      ← This file
```

---

## 🚀 Quick Commands

```bash
# Run thread states demo (see all 6 states in action)
./mvnw exec:java \
  -Dexec.mainClass="com.example.demo.ThreadStatesDemo" \
  -Dexec.classpathScope=test

# Run deadlock demo (shows BLOCKED state in deadlock)
./mvnw exec:java \
  -Dexec.mainClass="com.example.demo.DeadlockDemo" \
  -Dexec.classpathScope=test
```

---

## ✅ You're Thread States Expert!

You can now:
- ✅ Explain all 6 thread states
- ✅ Describe state transitions
- ✅ Code examples for each state
- ✅ Differentiate BLOCKED/WAITING/TIMED_WAITING
- ✅ Check thread state with getState()
- ✅ Explain why RUNNABLE includes "ready"
- ✅ Show how notify() works (WAITING → BLOCKED → RUNNABLE)

**Run the demos to see everything in action!** 🎉

