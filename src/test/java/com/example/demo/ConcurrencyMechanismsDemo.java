package com.example.demo;

import java.util.concurrent.*;
import java.util.concurrent.locks.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive Concurrency Mechanisms Demo
 *
 * This class demonstrates ALL major concurrency mechanisms:
 * 1. synchronized (implicit mutex)
 * 2. ReentrantLock (explicit mutex)
 * 3. Semaphore (counting semaphore)
 * 4. ReadWriteLock
 * 5. StampedLock (optimistic locking)
 *
 * Each mechanism is tested with 1000 threads to show:
 * - Performance characteristics
 * - Use cases
 * - Trade-offs
 */
public class ConcurrencyMechanismsDemo {

    // ============================================================
    // 1. SYNCHRONIZED (Implicit Mutex)
    // ============================================================

    static class SynchronizedCounter {
        private long count = 0;

        /**
         * Using synchronized keyword.
         *
         * HOW IT WORKS:
         * - JVM creates an implicit monitor (mutex) for 'this' object
         * - Only ONE thread can execute this method at a time
         * - Other threads BLOCK and wait
         *
         * PROS:
         * - Simple to use
         * - Automatic lock release (even on exceptions)
         * - Built into Java language
         *
         * CONS:
         * - Cannot interrupt a waiting thread
         * - Cannot try to acquire with timeout
         * - Less flexible than explicit locks
         * - OS-level blocking (slow)
         */
        public synchronized void increment() {
            count++;
        }

        public synchronized long getCount() {
            return count;
        }
    }

    // ============================================================
    // 2. REENTRANT LOCK (Explicit Mutex)
    // ============================================================

    static class ReentrantLockCounter {
        private long count = 0;
        private final ReentrantLock lock = new ReentrantLock();

        /**
         * Using explicit ReentrantLock.
         *
         * HOW IT WORKS:
         * - Thread explicitly acquires lock
         * - Only ONE thread can hold lock at a time
         * - Must manually release in finally block
         *
         * PROS:
         * - Can try to acquire with timeout: tryLock(timeout)
         * - Can be interrupted: lockInterruptibly()
         * - Can check if locked: isLocked()
         * - Supports fairness (FIFO): new ReentrantLock(true)
         * - More flexible than synchronized
         *
         * CONS:
         * - Must remember to unlock in finally
         * - More verbose than synchronized
         * - Still uses OS-level blocking
         */
        public void increment() {
            lock.lock();  // Acquire lock (blocks if held)
            try {
                count++;
            } finally {
                lock.unlock();  // MUST release in finally
            }
        }

        /**
         * Try to acquire with timeout.
         * Returns false if cannot acquire within time limit.
         */
        public boolean tryIncrementWithTimeout(long timeoutMs) {
            try {
                if (lock.tryLock(timeoutMs, TimeUnit.MILLISECONDS)) {
                    try {
                        count++;
                        return true;
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return false;
        }

        public long getCount() {
            lock.lock();
            try {
                return count;
            } finally {
                lock.unlock();
            }
        }
    }

    // ============================================================
    // 3. SEMAPHORE (Counting Semaphore)
    // ============================================================

    static class SemaphoreRateLimiter {
        private final Semaphore semaphore;
        private final AtomicInteger processedCount = new AtomicInteger(0);

        /**
         * Semaphore with N permits.
         *
         * HOW IT WORKS:
         * - Maintains a count of available permits
         * - acquire() decrements count (blocks if 0)
         * - release() increments count (wakes waiting thread)
         * - Allows N threads to execute concurrently
         *
         * PROS:
         * - Perfect for resource pooling
         * - Can limit concurrent access to N
         * - Supports fairness
         * - Good for rate limiting with refill
         *
         * CONS:
         * - Acquire/release not tied to specific thread
         * - Can accidentally release without acquiring
         * - Still uses OS-level blocking
         * - Need external counter for consumed items
         */
        public SemaphoreRateLimiter(int maxConcurrent) {
            this.semaphore = new Semaphore(maxConcurrent);
        }

        public boolean processRequest() {
            try {
                // Try to acquire permit (blocks if none available)
                semaphore.acquire();
                try {
                    // Simulate processing
                    processedCount.incrementAndGet();
                    return true;
                } finally {
                    // Always release permit
                    semaphore.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        /**
         * Try to acquire without blocking.
         */
        public boolean tryProcessRequest() {
            if (semaphore.tryAcquire()) {
                try {
                    processedCount.incrementAndGet();
                    return true;
                } finally {
                    semaphore.release();
                }
            }
            return false;
        }

        public int getProcessedCount() {
            return processedCount.get();
        }

        public int getAvailablePermits() {
            return semaphore.availablePermits();
        }
    }

    // ============================================================
    // 4. READ-WRITE LOCK
    // ============================================================

    static class ReadWriteLockCounter {
        private long count = 0;
        private final ReadWriteLock rwLock = new ReentrantReadWriteLock();
        private final Lock readLock = rwLock.readLock();
        private final Lock writeLock = rwLock.writeLock();

        /**
         * ReadWriteLock allows:
         * - Multiple readers simultaneously
         * - Only ONE writer (exclusive)
         * - No readers while writing
         *
         * PERFECT FOR:
         * - Read-heavy workloads
         * - Caching
         * - Configuration data
         *
         * HOW IT WORKS:
         * - readLock: Multiple threads can acquire
         * - writeLock: Exclusive access (blocks all)
         *
         * PROS:
         * - Multiple readers = better throughput
         * - Writers get exclusive access
         * - Good for read-heavy scenarios
         *
         * CONS:
         * - More complex than simple lock
         * - Writer starvation possible
         * - Overhead of maintaining two locks
         */
        public void increment() {
            writeLock.lock();  // Exclusive access
            try {
                count++;
            } finally {
                writeLock.unlock();
            }
        }

        public long getCount() {
            readLock.lock();  // Shared access (multiple readers OK)
            try {
                return count;
            } finally {
                readLock.unlock();
            }
        }
    }

    // ============================================================
    // 5. STAMPED LOCK (Optimistic Locking)
    // ============================================================

    static class StampedLockCounter {
        private long count = 0;
        private final StampedLock lock = new StampedLock();

        /**
         * StampedLock with optimistic read.
         *
         * THREE MODES:
         * 1. Optimistic read: No lock, just stamp
         * 2. Read lock: Shared access
         * 3. Write lock: Exclusive access
         *
         * HOW OPTIMISTIC READ WORKS:
         * 1. Get stamp (no lock)
         * 2. Read value
         * 3. Validate stamp (check if modified)
         * 4. If valid: use value (super fast!)
         * 5. If invalid: upgrade to read lock
         *
         * PROS:
         * - Optimistic read is VERY fast (no lock!)
         * - Better than ReadWriteLock for read-heavy
         * - Can upgrade/downgrade locks
         *
         * CONS:
         * - More complex API
         * - Not reentrant (can deadlock yourself!)
         * - Need to validate optimistic reads
         */
        public void increment() {
            long stamp = lock.writeLock();  // Exclusive
            try {
                count++;
            } finally {
                lock.unlockWrite(stamp);
            }
        }

        public long getCountOptimistic() {
            // Optimistic read (no lock!)
            long stamp = lock.tryOptimisticRead();
            long currentCount = count;  // Read without lock

            if (!lock.validate(stamp)) {
                // Oops, modified during read. Upgrade to read lock.
                stamp = lock.readLock();
                try {
                    currentCount = count;
                } finally {
                    lock.unlockRead(stamp);
                }
            }

            return currentCount;
        }
    }

    // ============================================================
    // PERFORMANCE COMPARISON
    // ============================================================

    public static void main(String[] args) throws InterruptedException {
        System.out.println("\n" + "═".repeat(70));
        System.out.println("CONCURRENCY MECHANISMS COMPARISON");
        System.out.println("Testing with 1000 threads, 1000 iterations each");
        System.out.println("═".repeat(70) + "\n");

        int numThreads = 1000;
        int iterations = 1000;

        // Test 1: Synchronized
        System.out.println("Test 1: SYNCHRONIZED (Implicit Mutex)");
        testSynchronized(numThreads, iterations);

        // Test 2: ReentrantLock
        System.out.println("\nTest 2: REENTRANT LOCK (Explicit Mutex)");
        testReentrantLock(numThreads, iterations);

        // Test 3: Semaphore
        System.out.println("\nTest 3: SEMAPHORE (Counting, permits=100)");
        testSemaphore(numThreads, iterations);

        // Test 4: ReadWriteLock
        System.out.println("\nTest 4: READ-WRITE LOCK");
        testReadWriteLock(numThreads, iterations);

        // Test 5: StampedLock
        System.out.println("\nTest 5: STAMPED LOCK (Optimistic)");
        testStampedLock(numThreads, iterations);

        System.out.println("\n" + "═".repeat(70));
        System.out.println("SUMMARY: Which to use when?");
        System.out.println("═".repeat(70));
        printSummary();
    }

    private static void testSynchronized(int numThreads, int iterations)
            throws InterruptedException {
        SynchronizedCounter counter = new SynchronizedCounter();
        long startTime = runTest(numThreads, () -> {
            for (int i = 0; i < iterations; i++) {
                counter.increment();
            }
        });

        System.out.println("  Final count: " + counter.getCount());
        System.out.println("  Expected:    " + (numThreads * iterations));
        System.out.println("  Duration:    " + startTime + " ms");
        System.out.println("  Throughput:  " +
            String.format("%.0f", (double)(numThreads * iterations) / startTime * 1000) + " ops/sec");
    }

    private static void testReentrantLock(int numThreads, int iterations)
            throws InterruptedException {
        ReentrantLockCounter counter = new ReentrantLockCounter();
        long startTime = runTest(numThreads, () -> {
            for (int i = 0; i < iterations; i++) {
                counter.increment();
            }
        });

        System.out.println("  Final count: " + counter.getCount());
        System.out.println("  Expected:    " + (numThreads * iterations));
        System.out.println("  Duration:    " + startTime + " ms");
        System.out.println("  Throughput:  " +
            String.format("%.0f", (double)(numThreads * iterations) / startTime * 1000) + " ops/sec");
    }

    private static void testSemaphore(int numThreads, int iterations)
            throws InterruptedException {
        SemaphoreRateLimiter limiter = new SemaphoreRateLimiter(100);
        long startTime = runTest(numThreads, () -> {
            for (int i = 0; i < iterations; i++) {
                limiter.processRequest();
            }
        });

        System.out.println("  Processed:   " + limiter.getProcessedCount());
        System.out.println("  Expected:    " + (numThreads * iterations));
        System.out.println("  Duration:    " + startTime + " ms");
        System.out.println("  Throughput:  " +
            String.format("%.0f", (double)(numThreads * iterations) / startTime * 1000) + " ops/sec");
    }

    private static void testReadWriteLock(int numThreads, int iterations)
            throws InterruptedException {
        ReadWriteLockCounter counter = new ReadWriteLockCounter();
        long startTime = runTest(numThreads, () -> {
            for (int i = 0; i < iterations; i++) {
                if (i % 10 == 0) {
                    counter.increment();  // 10% writes
                } else {
                    counter.getCount();   // 90% reads
                }
            }
        });

        System.out.println("  Final count: " + counter.getCount());
        System.out.println("  Duration:    " + startTime + " ms");
        System.out.println("  Throughput:  " +
            String.format("%.0f", (double)(numThreads * iterations) / startTime * 1000) + " ops/sec");
    }

    private static void testStampedLock(int numThreads, int iterations)
            throws InterruptedException {
        StampedLockCounter counter = new StampedLockCounter();
        long startTime = runTest(numThreads, () -> {
            for (int i = 0; i < iterations; i++) {
                if (i % 10 == 0) {
                    counter.increment();          // 10% writes
                } else {
                    counter.getCountOptimistic(); // 90% optimistic reads
                }
            }
        });

        System.out.println("  Duration:    " + startTime + " ms");
        System.out.println("  Throughput:  " +
            String.format("%.0f", (double)(numThreads * iterations) / startTime * 1000) + " ops/sec");
    }

    private static long runTest(int numThreads, Runnable task)
            throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(numThreads);

        ExecutorService executor = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2
        );

        for (int i = 0; i < numThreads; i++) {
            executor.submit(() -> {
                try {
                    startGate.await();
                    task.run();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    endGate.countDown();
                }
            });
        }

        long startTime = System.currentTimeMillis();
        startGate.countDown();
        endGate.await();
        long duration = System.currentTimeMillis() - startTime;

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        return duration;
    }

    private static void printSummary() {
        System.out.println("""
            
            1. SYNCHRONIZED
               Use when: Simple mutual exclusion needed
               Pros: Easy to use, automatic cleanup
               Cons: Not interruptible, no timeout
               Best for: Short critical sections, simple cases
            
            2. REENTRANT LOCK
               Use when: Need advanced lock features
               Pros: Interruptible, timeout, fairness
               Cons: Must manually unlock, more verbose
               Best for: Complex locking logic, need control
            
            3. SEMAPHORE
               Use when: Limiting concurrent access to N resources
               Pros: Resource pooling, rate limiting
               Cons: Not tied to threads, can be misused
               Best for: Connection pools, resource limits
            
            4. READ-WRITE LOCK
               Use when: Many readers, few writers
               Pros: Multiple readers simultaneously
               Cons: Writer starvation, complex
               Best for: Caching, read-heavy workloads
            
            5. STAMPED LOCK
               Use when: Read-heavy with optimistic reads
               Pros: Fastest for read-heavy scenarios
               Cons: Not reentrant, complex API
               Best for: Very read-heavy, need max performance
            
            ⚠️  ALL OF THESE USE OS-LEVEL BLOCKING!
            For tiny critical sections (like counters),
            use CAS (AtomicLong) instead - 10x faster!
            """);
    }
}

