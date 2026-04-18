package com.example.demo;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Deadlock Demonstration and Prevention Strategies
 *
 * This demo shows:
 * 1. How to CREATE a deadlock (circular wait)
 * 2. How to DETECT a deadlock
 * 3. Multiple ways to PREVENT deadlock
 *
 * Run each scenario to understand deadlock deeply.
 */
public class DeadlockDemo {

    // ============================================================
    // SCENARIO 1: Creating a Deadlock (The Problem)
    // ============================================================

    static class BankAccount {
        private final String accountId;
        private int balance;

        public BankAccount(String accountId, int balance) {
            this.accountId = accountId;
            this.balance = balance;
        }

        /**
         * DEADLOCK PRONE!
         * Two threads can deadlock if they transfer in opposite directions.
         */
        public synchronized void transferTo(BankAccount target, int amount) {
            System.out.println(Thread.currentThread().getName() +
                ": Acquired lock on " + this.accountId);

            // Simulate some processing time
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println(Thread.currentThread().getName() +
                ": Trying to acquire lock on " + target.accountId);

            // This will cause deadlock!
            synchronized(target) {
                this.balance -= amount;
                target.balance += amount;
                System.out.println(Thread.currentThread().getName() +
                    ": Transfer complete!");
            }
        }

        public String getAccountId() {
            return accountId;
        }
    }

    /**
     * Demonstrates actual deadlock.
     * Thread 1: A → B
     * Thread 2: B → A
     * Both wait forever!
     */
    public static void demonstrateDeadlock() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SCENARIO 1: Creating a Deadlock");
        System.out.println("=".repeat(70));

        BankAccount accountA = new BankAccount("A", 1000);
        BankAccount accountB = new BankAccount("B", 1000);

        // Thread 1: Transfer A → B
        Thread thread1 = new Thread(() -> {
            accountA.transferTo(accountB, 100);
        }, "Thread-1");

        // Thread 2: Transfer B → A (opposite direction!)
        Thread thread2 = new Thread(() -> {
            accountB.transferTo(accountA, 200);
        }, "Thread-2");

        thread1.start();
        thread2.start();

        // Wait a bit to see deadlock
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\n❌ DEADLOCK OCCURRED!");
        System.out.println("Thread-1 holds lock on A, wants lock on B");
        System.out.println("Thread-2 holds lock on B, wants lock on A");
        System.out.println("Both threads are stuck forever!");

        // Interrupt threads to exit
        thread1.interrupt();
        thread2.interrupt();
    }

    // ============================================================
    // SCENARIO 2: Prevention - Lock Ordering
    // ============================================================

    static class BankAccountSafe1 {
        private final String accountId;
        private final int accountNumber;  // For ordering
        private int balance;

        public BankAccountSafe1(String accountId, int accountNumber, int balance) {
            this.accountId = accountId;
            this.accountNumber = accountNumber;
            this.balance = balance;
        }

        /**
         * DEADLOCK-FREE using lock ordering.
         * Always acquire locks in same order (by account number).
         */
        public void transferTo(BankAccountSafe1 target, int amount) {
            // Determine lock order
            BankAccountSafe1 first;
            BankAccountSafe1 second;

            if (this.accountNumber < target.accountNumber) {
                first = this;
                second = target;
            } else {
                first = target;
                second = this;
            }

            // Always acquire locks in same order!
            synchronized(first) {
                System.out.println(Thread.currentThread().getName() +
                    ": Acquired lock on " + first.accountId);

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                synchronized(second) {
                    System.out.println(Thread.currentThread().getName() +
                        ": Acquired lock on " + second.accountId);

                    this.balance -= amount;
                    target.balance += amount;

                    System.out.println(Thread.currentThread().getName() +
                        ": Transfer complete!");
                }
            }
        }
    }

    public static void preventDeadlockWithOrdering() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SCENARIO 2: Prevention with Lock Ordering");
        System.out.println("=".repeat(70));

        BankAccountSafe1 accountA = new BankAccountSafe1("A", 1, 1000);
        BankAccountSafe1 accountB = new BankAccountSafe1("B", 2, 1000);

        Thread thread1 = new Thread(() -> {
            accountA.transferTo(accountB, 100);
        }, "Thread-1");

        Thread thread2 = new Thread(() -> {
            accountB.transferTo(accountA, 200);
        }, "Thread-2");

        thread1.start();
        thread2.start();

        try {
            thread1.join();
            thread2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\n✅ SUCCESS! No deadlock occurred.");
        System.out.println("Both threads acquired locks in same order (A before B)");
    }

    // ============================================================
    // SCENARIO 3: Prevention - tryLock with Timeout
    // ============================================================

    static class BankAccountSafe2 {
        private final String accountId;
        private final Lock lock = new ReentrantLock();
        private int balance;

        public BankAccountSafe2(String accountId, int balance) {
            this.accountId = accountId;
            this.balance = balance;
        }

        /**
         * DEADLOCK-FREE using tryLock with timeout.
         * If can't acquire lock within timeout, abort and retry.
         */
        public boolean transferTo(BankAccountSafe2 target, int amount) {
            int maxRetries = 3;

            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                boolean gotFirstLock = false;
                boolean gotSecondLock = false;

                try {
                    // Try to acquire first lock with timeout
                    gotFirstLock = lock.tryLock(500, TimeUnit.MILLISECONDS);

                    if (!gotFirstLock) {
                        System.out.println(Thread.currentThread().getName() +
                            " (attempt " + attempt + "): Failed to lock " + accountId);
                        continue;
                    }

                    System.out.println(Thread.currentThread().getName() +
                        ": Acquired lock on " + accountId);

                    Thread.sleep(100);

                    // Try to acquire second lock with timeout
                    gotSecondLock = target.lock.tryLock(500, TimeUnit.MILLISECONDS);

                    if (!gotSecondLock) {
                        System.out.println(Thread.currentThread().getName() +
                            " (attempt " + attempt + "): Failed to lock " + target.accountId +
                            ", releasing " + accountId);
                        continue;
                    }

                    System.out.println(Thread.currentThread().getName() +
                        ": Acquired lock on " + target.accountId);

                    // Both locks acquired! Do transfer
                    this.balance -= amount;
                    target.balance += amount;

                    System.out.println(Thread.currentThread().getName() +
                        ": Transfer complete!");

                    return true;

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                } finally {
                    // Always release locks in reverse order
                    if (gotSecondLock) {
                        target.lock.unlock();
                    }
                    if (gotFirstLock) {
                        lock.unlock();
                    }
                }
            }

            System.out.println(Thread.currentThread().getName() +
                ": Failed after " + maxRetries + " attempts");
            return false;
        }
    }

    public static void preventDeadlockWithTryLock() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SCENARIO 3: Prevention with tryLock (Timeout)");
        System.out.println("=".repeat(70));

        BankAccountSafe2 accountA = new BankAccountSafe2("A", 1000);
        BankAccountSafe2 accountB = new BankAccountSafe2("B", 1000);

        Thread thread1 = new Thread(() -> {
            accountA.transferTo(accountB, 100);
        }, "Thread-1");

        Thread thread2 = new Thread(() -> {
            accountB.transferTo(accountA, 200);
        }, "Thread-2");

        thread1.start();
        thread2.start();

        try {
            thread1.join();
            thread2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\n✅ SUCCESS! No deadlock occurred.");
        System.out.println("Threads used timeout and retry on lock contention");
    }

    // ============================================================
    // SCENARIO 4: Prevention - Single Global Lock
    // ============================================================

    static class BankAccountSafe3 {
        private static final Object GLOBAL_LOCK = new Object();

        private final String accountId;
        private int balance;

        public BankAccountSafe3(String accountId, int balance) {
            this.accountId = accountId;
            this.balance = balance;
        }

        /**
         * DEADLOCK-FREE using single global lock.
         * Simple but reduces concurrency.
         */
        public void transferTo(BankAccountSafe3 target, int amount) {
            synchronized(GLOBAL_LOCK) {  // Single lock for all transfers
                System.out.println(Thread.currentThread().getName() +
                    ": Acquired global lock");

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }

                this.balance -= amount;
                target.balance += amount;

                System.out.println(Thread.currentThread().getName() +
                    ": Transfer " + accountId + " → " + target.accountId + " complete!");
            }
        }
    }

    public static void preventDeadlockWithGlobalLock() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SCENARIO 4: Prevention with Single Global Lock");
        System.out.println("=".repeat(70));

        BankAccountSafe3 accountA = new BankAccountSafe3("A", 1000);
        BankAccountSafe3 accountB = new BankAccountSafe3("B", 1000);

        Thread thread1 = new Thread(() -> {
            accountA.transferTo(accountB, 100);
        }, "Thread-1");

        Thread thread2 = new Thread(() -> {
            accountB.transferTo(accountA, 200);
        }, "Thread-2");

        thread1.start();
        thread2.start();

        try {
            thread1.join();
            thread2.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        System.out.println("\n✅ SUCCESS! No deadlock occurred.");
        System.out.println("Single global lock prevents circular wait");
        System.out.println("Trade-off: Reduced concurrency (serialized execution)");
    }

    // ============================================================
    // SCENARIO 5: Detection - JVM Thread Dump
    // ============================================================

    public static void demonstrateDeadlockDetection() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SCENARIO 5: Deadlock Detection");
        System.out.println("=".repeat(70));

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();

        BankAccount accountA = new BankAccount("A", 1000);
        BankAccount accountB = new BankAccount("B", 1000);

        Thread thread1 = new Thread(() -> {
            accountA.transferTo(accountB, 100);
        }, "DeadlockThread-1");

        Thread thread2 = new Thread(() -> {
            accountB.transferTo(accountA, 200);
        }, "DeadlockThread-2");

        thread1.start();
        thread2.start();

        // Wait for deadlock to occur
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Detect deadlock
        long[] deadlockedThreads = threadMXBean.findDeadlockedThreads();

        if (deadlockedThreads != null) {
            System.out.println("\n⚠️  DEADLOCK DETECTED!");
            System.out.println("Number of deadlocked threads: " + deadlockedThreads.length);

            ThreadInfo[] threadInfos = threadMXBean.getThreadInfo(deadlockedThreads);

            for (ThreadInfo threadInfo : threadInfos) {
                System.out.println("\nThread: " + threadInfo.getThreadName());
                System.out.println("State: " + threadInfo.getThreadState());
                System.out.println("Blocked on: " + threadInfo.getLockName());
                System.out.println("Owned by: " + threadInfo.getLockOwnerName());
            }
        }

        thread1.interrupt();
        thread2.interrupt();

        System.out.println("\n💡 Detection Method: ThreadMXBean.findDeadlockedThreads()");
    }

    // ============================================================
    // MAIN RUNNER
    // ============================================================

    public static void main(String[] args) {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("DEADLOCK DEMONSTRATION AND PREVENTION");
        System.out.println("═".repeat(70));

        // Run each scenario
        try {
            // 1. Show the problem
            System.out.println("\n⚠️  WARNING: Next demo will create a deadlock!");
            System.out.println("Press Ctrl+C if it hangs too long...\n");
            Thread.sleep(2000);
            demonstrateDeadlock();

            Thread.sleep(2000);

            // 2. Solution 1: Lock ordering
            preventDeadlockWithOrdering();

            Thread.sleep(1000);

            // 3. Solution 2: tryLock with timeout
            preventDeadlockWithTryLock();

            Thread.sleep(1000);

            // 4. Solution 3: Global lock
            preventDeadlockWithGlobalLock();

            Thread.sleep(1000);

            // 5. Detection
            demonstrateDeadlockDetection();

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
            
            DEADLOCK CONDITIONS (All 4 must be true):
            ──────────────────────────────────────────
            1. Mutual Exclusion: Resources can't be shared
            2. Hold and Wait: Thread holds lock while waiting for another
            3. No Preemption: Can't force thread to release lock
            4. Circular Wait: Thread A → B → C → A (cycle)
            
            PREVENTION STRATEGIES:
            ──────────────────────────────────────────
            
            ✅ Strategy 1: Lock Ordering
               • Always acquire locks in same order
               • Breaks circular wait condition
               • Best performance
               • Example: Order by account ID
            
            ✅ Strategy 2: tryLock() with Timeout
               • Use ReentrantLock.tryLock(timeout)
               • If can't acquire, release and retry
               • Breaks hold-and-wait
               • Handles unexpected deadlocks gracefully
            
            ✅ Strategy 3: Single Global Lock
               • One lock for all operations
               • Simplest solution
               • Reduces concurrency (serialization)
               • Good for low-traffic scenarios
            
            ✅ Strategy 4: Lock-Free Algorithms
               • Use CAS (AtomicLong, AtomicReference)
               • No locks = no deadlocks!
               • Best for high performance
               • Example: Rate limiting with AtomicLong
            
            DETECTION:
            ──────────────────────────────────────────
            • JVM: ThreadMXBean.findDeadlockedThreads()
            • JVisualVM: Thread dump and analysis
            • JStack: Command-line thread dump
            • IntelliJ: Debugger shows deadlock
            
            BEST PRACTICES:
            ──────────────────────────────────────────
            1. Minimize lock scope (hold briefly)
            2. Avoid nested locks when possible
            3. Use lock-free algorithms (CAS) when suitable
            4. Document lock ordering
            5. Use tryLock() for robustness
            6. Monitor with ThreadMXBean in production
            
            FOR RATE LIMITING:
            ──────────────────────────────────────────
            ✨ Use CAS (AtomicLong) - NO DEADLOCKS POSSIBLE!
            """);
    }
}

