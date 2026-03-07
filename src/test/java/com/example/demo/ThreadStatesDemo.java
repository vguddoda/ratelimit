package com.example.demo;

import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread States and State Transitions Demo
 *
 * This demo demonstrates ALL 6 thread states in Java:
 * 1. NEW
 * 2. RUNNABLE
 * 3. BLOCKED
 * 4. WAITING
 * 5. TIMED_WAITING
 * 6. TERMINATED
 *
 * Run this to see threads transition through different states.
 */
public class ThreadStatesDemo {

    private static final Object lock = new Object();
    private static final ReentrantLock reentrantLock = new ReentrantLock();

    // ============================================================
    // STATE 1: NEW
    // ============================================================

    public static void demonstrateNewState() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("STATE 1: NEW");
        System.out.println("=".repeat(70));

        Thread thread = new Thread(() -> {
            System.out.println("Thread is running!");
        });

        System.out.println("Thread created but not started yet");
        System.out.println("State: " + thread.getState());  // NEW
        System.out.println();
        System.out.println("Explanation:");
        System.out.println("- Thread object created but start() not called");
        System.out.println("- No system resources allocated yet");
        System.out.println("- Not scheduled by OS");

        thread.start();  // Will transition to RUNNABLE

        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // STATE 2: RUNNABLE
    // ============================================================

    public static void demonstrateRunnableState() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("STATE 2: RUNNABLE");
        System.out.println("=".repeat(70));

        Thread thread = new Thread(() -> {
            System.out.println("Thread is executing!");

            // This thread is RUNNABLE
            // It's either:
            // a) Actually running on CPU, or
            // b) Ready to run, waiting for CPU time slice

            for (int i = 0; i < 5; i++) {
                System.out.println("Working... " + i);
                // Thread remains RUNNABLE during computation
            }
        });

        thread.start();  // NEW → RUNNABLE

        try {
            Thread.sleep(10);  // Give thread time to start
            System.out.println("\nState while running: " + thread.getState());
            System.out.println();
            System.out.println("Explanation:");
            System.out.println("- Thread is executing on CPU OR ready to run");
            System.out.println("- OS scheduler gives it time slices");
            System.out.println("- Can be preempted by OS (time-sharing)");

            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // STATE 3: BLOCKED
    // ============================================================

    public static void demonstrateBlockedState() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("STATE 3: BLOCKED");
        System.out.println("=".repeat(70));

        Thread thread1 = new Thread(() -> {
            synchronized(lock) {
                System.out.println("Thread-1: Acquired lock, holding for 3 seconds");
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("Thread-1: Releasing lock");
            }
        }, "Thread-1");

        Thread thread2 = new Thread(() -> {
            System.out.println("Thread-2: Trying to acquire lock...");
            synchronized(lock) {  // Will BLOCK here
                System.out.println("Thread-2: Finally got the lock!");
            }
        }, "Thread-2");

        thread1.start();

        try {
            Thread.sleep(100);  // Let thread1 acquire lock
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        thread2.start();

        try {
            Thread.sleep(500);  // Give thread2 time to block
            System.out.println("\nThread-2 state: " + thread2.getState());  // BLOCKED
            System.out.println();
            System.out.println("Explanation:");
            System.out.println("- Thread-2 is waiting to acquire monitor lock");
            System.out.println("- Blocked by synchronized keyword");
            System.out.println("- Cannot proceed until Thread-1 releases lock");
            System.out.println("- Automatically transitions to RUNNABLE when lock available");

            thread1.join();
            thread2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // STATE 4: WAITING
    // ============================================================

    public static void demonstrateWaitingState() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("STATE 4: WAITING");
        System.out.println("=".repeat(70));

        Thread waiter = new Thread(() -> {
            synchronized(lock) {
                try {
                    System.out.println("Waiter: Calling wait() - releasing lock and waiting");
                    lock.wait();  // RUNNABLE → WAITING
                    System.out.println("Waiter: Woken up by notify()!");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "Waiter");

        Thread notifier = new Thread(() -> {
            try {
                Thread.sleep(2000);  // Wait 2 seconds
                synchronized(lock) {
                    System.out.println("Notifier: Calling notify() to wake waiter");
                    lock.notify();  // Wake up waiter
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Notifier");

        waiter.start();

        try {
            Thread.sleep(500);  // Give waiter time to wait
            System.out.println("\nWaiter state: " + waiter.getState());  // WAITING
            System.out.println();
            System.out.println("Explanation:");
            System.out.println("- Thread called Object.wait() (infinite wait)");
            System.out.println("- Released the monitor lock");
            System.out.println("- Waiting for another thread to call notify()/notifyAll()");
            System.out.println("- Will transition to BLOCKED (to reacquire lock), then RUNNABLE");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        notifier.start();

        try {
            waiter.join();
            notifier.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // STATE 5: TIMED_WAITING
    // ============================================================

    public static void demonstrateTimedWaitingState() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("STATE 5: TIMED_WAITING");
        System.out.println("=".repeat(70));

        // Method 1: Thread.sleep()
        Thread sleeper = new Thread(() -> {
            try {
                System.out.println("Sleeper: Going to sleep for 3 seconds");
                Thread.sleep(3000);  // RUNNABLE → TIMED_WAITING
                System.out.println("Sleeper: Woke up!");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Sleeper");

        sleeper.start();

        try {
            Thread.sleep(500);
            System.out.println("\nSleeper state: " + sleeper.getState());  // TIMED_WAITING
            System.out.println();
            System.out.println("Explanation (Thread.sleep()):");
            System.out.println("- Thread called Thread.sleep(3000)");
            System.out.println("- Waiting for specified time to elapse");
            System.out.println("- Automatically transitions to RUNNABLE after timeout");

            sleeper.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Method 2: Object.wait(timeout)
        System.out.println("\n--- Also caused by Object.wait(timeout) ---");

        Thread timedWaiter = new Thread(() -> {
            synchronized(lock) {
                try {
                    System.out.println("TimedWaiter: Calling wait(2000)");
                    lock.wait(2000);  // RUNNABLE → TIMED_WAITING
                    System.out.println("TimedWaiter: Timeout elapsed or notified");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }, "TimedWaiter");

        timedWaiter.start();

        try {
            Thread.sleep(500);
            System.out.println("TimedWaiter state: " + timedWaiter.getState());

            timedWaiter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // STATE 6: TERMINATED
    // ============================================================

    public static void demonstrateTerminatedState() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("STATE 6: TERMINATED");
        System.out.println("=".repeat(70));

        Thread thread = new Thread(() -> {
            System.out.println("Thread: Doing some work");
            System.out.println("Thread: Completing execution");
        });

        thread.start();

        try {
            thread.join();  // Wait for thread to complete

            System.out.println("\nState after completion: " + thread.getState());  // TERMINATED
            System.out.println();
            System.out.println("Explanation:");
            System.out.println("- Thread completed its run() method");
            System.out.println("- All resources released");
            System.out.println("- Cannot be restarted (will throw IllegalThreadStateException)");
            System.out.println("- Final state - no more transitions possible");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // COMPLETE STATE TRANSITION DEMONSTRATION
    // ============================================================

    public static void demonstrateAllTransitions() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("COMPLETE STATE TRANSITIONS");
        System.out.println("=".repeat(70));

        final Object monitor = new Object();

        Thread thread = new Thread(() -> {
            try {
                // State: RUNNABLE (executing)
                System.out.println("Step 1: Thread is RUNNABLE (executing)");

                // Transition to TIMED_WAITING
                System.out.println("Step 2: Calling sleep() → TIMED_WAITING");
                Thread.sleep(1000);

                // Back to RUNNABLE
                System.out.println("Step 3: Woke up → RUNNABLE");

                // Transition to BLOCKED
                synchronized(monitor) {
                    System.out.println("Step 4: Acquired lock → still RUNNABLE");

                    // Transition to WAITING
                    System.out.println("Step 5: Calling wait() → WAITING");
                    monitor.wait();

                    // Back to RUNNABLE
                    System.out.println("Step 6: Notified → RUNNABLE");
                }

                System.out.println("Step 7: Exiting run() → will become TERMINATED");

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "DemoThread");

        // State: NEW
        System.out.println("\nInitial state (before start): " + thread.getState());

        thread.start();

        try {
            Thread.sleep(100);
            System.out.println("State after start: " + thread.getState());

            Thread.sleep(500);
            System.out.println("State during sleep: " + thread.getState());

            Thread.sleep(600);
            System.out.println("State during wait: " + thread.getState());

            // Notify the waiting thread
            synchronized(monitor) {
                monitor.notify();
            }

            thread.join();

            System.out.println("Final state: " + thread.getState());

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ============================================================
    // MAIN RUNNER
    // ============================================================

    public static void main(String[] args) {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("JAVA THREAD STATES DEMONSTRATION");
        System.out.println("═".repeat(70));

        try {
            demonstrateNewState();
            Thread.sleep(1000);

            demonstrateRunnableState();
            Thread.sleep(1000);

            demonstrateBlockedState();
            Thread.sleep(1000);

            demonstrateWaitingState();
            Thread.sleep(1000);

            demonstrateTimedWaitingState();
            Thread.sleep(1000);

            demonstrateTerminatedState();
            Thread.sleep(1000);

            demonstrateAllTransitions();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\n" + "═".repeat(70));
        System.out.println("SUMMARY");
        System.out.println("═".repeat(70));
        printSummary();
    }

    private static void printSummary() {
        System.out.println("""
            
            6 JAVA THREAD STATES:
            ═══════════════════════════════════════════════════════════
            
            1. NEW
               • Thread created but not started
               • No OS resources allocated
               • Transition: start() → RUNNABLE
            
            2. RUNNABLE
               • Executing on CPU OR ready to run
               • Has all resources needed
               • OS scheduler decides when it runs
               • Transitions:
                 - wait() → WAITING
                 - wait(timeout) → TIMED_WAITING
                 - sleep() → TIMED_WAITING
                 - synchronized (blocked) → BLOCKED
                 - run() completes → TERMINATED
            
            3. BLOCKED
               • Waiting to acquire monitor lock
               • Caused by synchronized keyword
               • Automatically transitions to RUNNABLE when lock available
               • Cannot be interrupted
            
            4. WAITING
               • Waiting indefinitely for another thread
               • Caused by:
                 - Object.wait()
                 - Thread.join()
                 - LockSupport.park()
               • Transition: notify()/notifyAll() → BLOCKED → RUNNABLE
            
            5. TIMED_WAITING
               • Waiting for specified time
               • Caused by:
                 - Thread.sleep(ms)
                 - Object.wait(timeout)
                 - Thread.join(timeout)
                 - LockSupport.parkNanos()
               • Transition: timeout expires → RUNNABLE
            
            6. TERMINATED
               • Thread completed execution
               • run() method exited
               • Final state - cannot transition
               • Cannot be restarted
            
            
            STATE TRANSITION DIAGRAM:
            ═══════════════════════════════════════════════════════════
            
                          start()
                NEW ────────────→ RUNNABLE ←──────────────┐
                                     │  ↑                  │
                                     │  │                  │
                        sleep()      │  │ timeout          │
                        wait(ms)     │  │                  │
                                     ↓  │                  │
                              TIMED_WAITING                │
                                                           │
                                  RUNNABLE                 │
                                     │  ↑                  │
                                     │  │                  │
                        wait()       │  │ notify()         │
                        join()       │  │                  │
                                     ↓  │                  │
                                 WAITING ──────────────────┘
                                                  (via BLOCKED)
                                  RUNNABLE
                                     │  ↑
                                     │  │
                      synchronized   │  │ lock available
                      (contested)    │  │
                                     ↓  │
                                  BLOCKED
            
                                  RUNNABLE
                                     │
                                     │ run() completes
                                     │ or exception
                                     ↓
                                TERMINATED
            
            
            BLOCKED vs WAITING vs TIMED_WAITING:
            ═══════════════════════════════════════════════════════════
            
            BLOCKED:
              • Specifically for synchronized locks
              • Automatic - no explicit call
              • Cannot be interrupted
              • Example: Two threads on same synchronized block
            
            WAITING:
              • Explicit wait() call
              • Releases lock
              • Can be interrupted
              • Example: Producer-consumer with wait/notify
            
            TIMED_WAITING:
              • Like WAITING but with timeout
              • Automatically resumes after timeout
              • Can be interrupted
              • Example: Thread.sleep(1000)
            
            
            COMMON CAUSES:
            ═══════════════════════════════════════════════════════════
            
            NEW:
              Thread t = new Thread(...)
            
            RUNNABLE:
              t.start()
              Executing code
            
            BLOCKED:
              synchronized(obj) { }  // when obj is held by another thread
            
            WAITING:
              Object.wait()
              Thread.join()
              LockSupport.park()
            
            TIMED_WAITING:
              Thread.sleep(1000)
              Object.wait(1000)
              Thread.join(1000)
              LockSupport.parkNanos(ns)
            
            TERMINATED:
              return from run()
              Uncaught exception
            
            
            INTERVIEW KEY POINTS:
            ═══════════════════════════════════════════════════════════
            
            • Java has 6 thread states (not OS thread states!)
            • RUNNABLE includes both "running" and "ready to run"
            • BLOCKED is only for synchronized locks
            • WAITING/TIMED_WAITING for explicit wait operations
            • State transitions are managed by JVM
            • Use Thread.getState() to check current state
            • TERMINATED is final - thread cannot be restarted
            
            """);
    }
}

