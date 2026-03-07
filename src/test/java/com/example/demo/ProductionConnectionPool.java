package com.example.demo;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;

/**
 * Production-Ready Database Connection Pool
 *
 * Features:
 * - Semaphore-based connection limiting
 * - Connection validation (test on borrow)
 * - Idle connection timeout
 * - Maximum connection lifetime
 * - Connection leak detection
 * - Graceful shutdown
 * - Health monitoring
 * - Fair queuing
 * - Metrics collection
 *
 * Thread-safe and optimized for high concurrency.
 */
public class ProductionConnectionPool implements DataSource {

    // Pool configuration
    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final int minConnections;
    private final int maxConnections;
    private final long connectionTimeoutMs;
    private final long idleTimeoutMs;
    private final long maxLifetimeMs;
    private final long leakDetectionThresholdMs;
    private final boolean testOnBorrow;
    private final String validationQuery;

    // Core pool components
    private final Semaphore connectionSemaphore;

    /**
     * Queue of available (idle) connections ready to be borrowed.
     *
     * Lifecycle:
     * - Connections START here when pool is initialized
     * - REMOVED when thread calls getConnection()
     * - RETURNED when thread calls close() (via ConnectionProxy)
     *
     * Thread-safe: ConcurrentLinkedQueue allows lock-free operations
     *
     * Example flow:
     * 1. Pool init: [C1, C2, C3, C4, C5] (5 available)
     * 2. Thread-A gets connection: [C2, C3, C4, C5] (C1 leased)
     * 3. Thread-A closes connection: [C2, C3, C4, C5, C1] (C1 returned)
     */
    private final ConcurrentLinkedQueue<PooledConnection> availableConnections;

    /**
     * Map of leased (borrowed/in-use) connections for tracking and leak detection.
     *
     * Key: Raw Connection object (the actual JDBC connection)
     *      WHY Connection as key? Because when thread calls conn.close(), we need to
     *      look up the corresponding PooledConnection to get its metadata (leaseTime,
     *      stackTrace). The thread has the Connection object, so we use it for lookup.
     *
     * Value: PooledConnection wrapper (contains metadata: leaseTime, stackTrace)
     *
     * Purpose:
     * 1. LEAK DETECTION: Track how long each connection is held
     *    - If held > threshold (e.g., 60s), log WARNING with stack trace
     *    - Stack trace shows WHERE connection was acquired
     *
     * 2. METRICS: Count active connections (size of this map)
     *
     * 3. GRACEFUL SHUTDOWN: Check if any connections still in use
     *
     * Lifecycle:
     * - Connection ADDED when getConnection() returns to thread
     * - Connection REMOVED when close() called (returned to pool)
     *
     * Example:
     * Thread-1: getConnection() → {C1 → PooledConnection(leaseTime=1234567890, stackTrace=...)}
     * Thread-2: getConnection() → {C1 → ..., C2 → PooledConnection(leaseTime=1234567900, ...)}
     * Thread-1: close()        → {C2 → ...} (C1 removed, returned to availableConnections)
     */
    private final ConcurrentHashMap<Connection, PooledConnection> leasedConnections;

    // Background maintenance
    private final ScheduledExecutorService maintenanceExecutor;
    private volatile boolean isShutdown = false;

    // Metrics
    private final AtomicInteger totalConnections = new AtomicInteger(0);
    private final AtomicInteger activeConnections = new AtomicInteger(0);
    private final AtomicLong totalConnectionsCreated = new AtomicLong(0);
    private final AtomicLong totalConnectionsDestroyed = new AtomicLong(0);
    private final AtomicLong totalConnectionRequests = new AtomicLong(0);
    private final AtomicLong totalConnectionWaits = new AtomicLong(0);
    private final AtomicLong totalConnectionLeaks = new AtomicLong(0);

    /**
     * Builder for connection pool configuration.
     */
    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private int minConnections = 5;
        private int maxConnections = 10;
        private long connectionTimeoutMs = 30000; // 30 seconds
        private long idleTimeoutMs = 600000; // 10 minutes
        private long maxLifetimeMs = 1800000; // 30 minutes
        private long leakDetectionThresholdMs = 60000; // 1 minute
        private boolean testOnBorrow = true;
        private String validationQuery = "SELECT 1";

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder minConnections(int minConnections) {
            this.minConnections = minConnections;
            return this;
        }

        public Builder maxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
            return this;
        }

        public Builder connectionTimeoutMs(long connectionTimeoutMs) {
            this.connectionTimeoutMs = connectionTimeoutMs;
            return this;
        }

        public Builder idleTimeoutMs(long idleTimeoutMs) {
            this.idleTimeoutMs = idleTimeoutMs;
            return this;
        }

        public Builder maxLifetimeMs(long maxLifetimeMs) {
            this.maxLifetimeMs = maxLifetimeMs;
            return this;
        }

        public Builder leakDetectionThresholdMs(long leakDetectionThresholdMs) {
            this.leakDetectionThresholdMs = leakDetectionThresholdMs;
            return this;
        }

        public Builder testOnBorrow(boolean testOnBorrow) {
            this.testOnBorrow = testOnBorrow;
            return this;
        }

        public Builder validationQuery(String validationQuery) {
            this.validationQuery = validationQuery;
            return this;
        }

        public ProductionConnectionPool build() {
            if (jdbcUrl == null || jdbcUrl.isEmpty()) {
                throw new IllegalArgumentException("JDBC URL is required");
            }
            return new ProductionConnectionPool(this);
        }
    }

    private ProductionConnectionPool(Builder builder) {
        this.jdbcUrl = builder.jdbcUrl;
        this.username = builder.username;
        this.password = builder.password;
        this.minConnections = builder.minConnections;
        this.maxConnections = builder.maxConnections;
        this.connectionTimeoutMs = builder.connectionTimeoutMs;
        this.idleTimeoutMs = builder.idleTimeoutMs;
        this.maxLifetimeMs = builder.maxLifetimeMs;
        this.leakDetectionThresholdMs = builder.leakDetectionThresholdMs;
        this.testOnBorrow = builder.testOnBorrow;
        this.validationQuery = builder.validationQuery;

        // Initialize pool components
        this.connectionSemaphore = new Semaphore(maxConnections, true); // Fair semaphore
        this.availableConnections = new ConcurrentLinkedQueue<>();
        this.leasedConnections = new ConcurrentHashMap<>();

        // Initialize maintenance executor
        this.maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ConnectionPool-Maintenance");
            t.setDaemon(true);
            return t;
        });

        // Initialize pool
        initializePool();

        // Start maintenance tasks
        startMaintenanceTasks();

        System.out.println("[ConnectionPool] Initialized with " +
            minConnections + " min, " + maxConnections + " max connections");
    }

    private void initializePool() {
        for (int i = 0; i < minConnections; i++) {
            try {
                PooledConnection pooledConn = createNewConnection();
                availableConnections.add(pooledConn);
            } catch (SQLException e) {
                System.err.println("[ConnectionPool] Failed to create initial connection: " +
                    e.getMessage());
            }
        }
    }

    private PooledConnection createNewConnection() throws SQLException {
        Connection rawConnection;
        if (username != null && password != null) {
            rawConnection = DriverManager.getConnection(jdbcUrl, username, password);
        } else {
            rawConnection = DriverManager.getConnection(jdbcUrl);
        }

        totalConnections.incrementAndGet();
        totalConnectionsCreated.incrementAndGet();

        return new PooledConnection(rawConnection, System.currentTimeMillis());
    }

    /**
     * Get a connection from the pool.
     * Blocks if no connections available, up to connectionTimeoutMs.
     */
    @Override
    public Connection getConnection() throws SQLException {
        return getConnection(connectionTimeoutMs);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        throw new SQLFeatureNotSupportedException("Use getConnection() instead");
    }

    public Connection getConnection(long timeoutMs) throws SQLException {
        if (isShutdown) {
            throw new SQLException("Connection pool is shut down");
        }

        totalConnectionRequests.incrementAndGet();
        long startTime = System.currentTimeMillis();

        try {
            // Try to acquire permit from semaphore
            boolean acquired = connectionSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);

            if (!acquired) {
                totalConnectionWaits.incrementAndGet();
                throw new SQLException("Connection timeout: No connection available within " +
                    timeoutMs + "ms. Pool exhausted!");
            }

            try {
                // Try to get connection from pool
                PooledConnection pooledConn = getAvailableConnection();

                if (pooledConn == null) {
                    // No available connection, create new one
                    pooledConn = createNewConnection();
                }

                // Validate connection if configured
                if (testOnBorrow && !validateConnection(pooledConn)) {
                    // Connection invalid, destroy and create new
                    destroyConnection(pooledConn);
                    pooledConn = createNewConnection();
                }

                // Mark connection as leased
                pooledConn.lease();
                leasedConnections.put(pooledConn.getRawConnection(), pooledConn);
                activeConnections.incrementAndGet();

                long waitTime = System.currentTimeMillis() - startTime;
                if (waitTime > 100) {
                    System.out.println("[ConnectionPool] Connection acquired after " +
                        waitTime + "ms wait");
                }

                // Return proxy that intercepts close()
                return new ConnectionProxy(pooledConn, this);

            } catch (SQLException e) {
                // Failed to get connection, release permit
                connectionSemaphore.release();
                throw e;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SQLException("Interrupted while waiting for connection", e);
        }
    }

    private PooledConnection getAvailableConnection() {
        PooledConnection conn;
        while ((conn = availableConnections.poll()) != null) {
            // Check if connection is still valid
            if (isConnectionStillValid(conn)) {
                return conn;
            } else {
                // Connection expired, destroy it
                destroyConnection(conn);
            }
        }
        return null;
    }

    private boolean isConnectionStillValid(PooledConnection conn) {
        long now = System.currentTimeMillis();

        // Check max lifetime
        if (maxLifetimeMs > 0 && (now - conn.getCreationTime()) > maxLifetimeMs) {
            return false;
        }

        // Check idle timeout
        if (idleTimeoutMs > 0 && (now - conn.getLastUsedTime()) > idleTimeoutMs) {
            return false;
        }

        // Check if connection is closed
        try {
            if (conn.getRawConnection().isClosed()) {
                return false;
            }
        } catch (SQLException e) {
            return false;
        }

        return true;
    }

    private boolean validateConnection(PooledConnection conn) {
        try {
            var stmt = conn.getRawConnection().createStatement();
            stmt.execute(validationQuery);
            stmt.close();
            return true;
        } catch (SQLException e) {
            System.err.println("[ConnectionPool] Connection validation failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Return connection to pool (called by ConnectionProxy.close())
     */
    void returnConnection(PooledConnection pooledConn) {
        Connection rawConn = pooledConn.getRawConnection();
        leasedConnections.remove(rawConn);
        activeConnections.decrementAndGet();

        // Check for connection leak
        long leaseTime = System.currentTimeMillis() - pooledConn.getLeaseTime();
        if (leaseTime > leakDetectionThresholdMs) {
            totalConnectionLeaks.incrementAndGet();
            System.err.println("[ConnectionPool] ⚠️  Connection leak detected! " +
                "Held for " + leaseTime + "ms (threshold: " + leakDetectionThresholdMs + "ms)");
            System.err.println("[ConnectionPool] Leak stack trace:");
            pooledConn.getLeaseStackTrace().printStackTrace(System.err);
        }

        // Check if connection is still usable
        if (isConnectionStillValid(pooledConn)) {
            pooledConn.returnToPool();
            availableConnections.add(pooledConn);
        } else {
            destroyConnection(pooledConn);
        }

        // Release permit
        connectionSemaphore.release();
    }

    private void destroyConnection(PooledConnection conn) {
        try {
            conn.getRawConnection().close();
            totalConnections.decrementAndGet();
            totalConnectionsDestroyed.incrementAndGet();
        } catch (SQLException e) {
            System.err.println("[ConnectionPool] Error closing connection: " + e.getMessage());
        }
    }

    /**
     * Start background maintenance tasks.
     */
    private void startMaintenanceTasks() {
        // Task 1: Remove idle connections
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            if (isShutdown) return;

            int removed = 0;
            int poolSize = availableConnections.size();

            // Don't remove below minimum
            while (poolSize > minConnections) {
                PooledConnection conn = availableConnections.peek();
                if (conn != null && !isConnectionStillValid(conn)) {
                    availableConnections.poll();
                    destroyConnection(conn);
                    removed++;
                    poolSize--;
                } else {
                    break;
                }
            }

            if (removed > 0) {
                System.out.println("[ConnectionPool] Removed " + removed +
                    " expired connections from pool");
            }

        }, 30, 30, TimeUnit.SECONDS);

        // Task 2: Ensure minimum connections
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            if (isShutdown) return;

            int needed = minConnections - totalConnections.get();
            if (needed > 0) {
                for (int i = 0; i < needed; i++) {
                    try {
                        PooledConnection conn = createNewConnection();
                        availableConnections.add(conn);
                    } catch (SQLException e) {
                        System.err.println("[ConnectionPool] Failed to create connection: " +
                            e.getMessage());
                    }
                }
            }

        }, 60, 60, TimeUnit.SECONDS);

        // Task 3: Print statistics
        maintenanceExecutor.scheduleAtFixedRate(() -> {
            if (isShutdown) return;
            printStatistics();
        }, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * Get current pool statistics.
     */
    public PoolStats getStats() {
        return new PoolStats(
            totalConnections.get(),
            activeConnections.get(),
            availableConnections.size(),
            connectionSemaphore.getQueueLength(),
            totalConnectionsCreated.get(),
            totalConnectionsDestroyed.get(),
            totalConnectionRequests.get(),
            totalConnectionWaits.get(),
            totalConnectionLeaks.get()
        );
    }

    private void printStatistics() {
        PoolStats stats = getStats();
        System.out.println("[ConnectionPool] Statistics:");
        System.out.println("  Total: " + stats.totalConnections());
        System.out.println("  Active: " + stats.activeConnections());
        System.out.println("  Available: " + stats.availableConnections());
        System.out.println("  Waiting threads: " + stats.waitingThreads());
        System.out.println("  Created: " + stats.totalCreated());
        System.out.println("  Destroyed: " + stats.totalDestroyed());
        System.out.println("  Requests: " + stats.totalRequests());
        System.out.println("  Timeouts: " + stats.totalWaits());
        System.out.println("  Leaks detected: " + stats.totalLeaks());
    }

    /**
     * Gracefully shutdown the pool.
     */
    public void shutdown() {
        if (isShutdown) {
            return;
        }

        System.out.println("[ConnectionPool] Shutting down...");
        isShutdown = true;

        // Stop maintenance tasks
        maintenanceExecutor.shutdown();
        try {
            maintenanceExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Close all available connections
        PooledConnection conn;
        while ((conn = availableConnections.poll()) != null) {
            destroyConnection(conn);
        }

        // Warn about leased connections
        if (!leasedConnections.isEmpty()) {
            System.err.println("[ConnectionPool] ⚠️  Warning: " +
                leasedConnections.size() + " connections still in use during shutdown!");
        }

        System.out.println("[ConnectionPool] Shutdown complete");
    }

    // DataSource interface methods (not used)
    @Override
    public PrintWriter getLogWriter() throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setLogWriter(PrintWriter out) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public void setLoginTimeout(int seconds) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public int getLoginTimeout() throws SQLException {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) throws SQLException {
        return false;
    }

    // Inner classes

    /**
     * Wraps a raw connection with metadata.
     */
    static class PooledConnection {
        private final Connection rawConnection;
        private final long creationTime;
        private volatile long lastUsedTime;
        private volatile long leaseTime;
        private volatile Exception leaseStackTrace;

        public PooledConnection(Connection rawConnection, long creationTime) {
            this.rawConnection = rawConnection;
            this.creationTime = creationTime;
            this.lastUsedTime = creationTime;
        }

        public void lease() {
            this.leaseTime = System.currentTimeMillis();
            this.leaseStackTrace = new Exception("Connection leased from here");
        }

        public void returnToPool() {
            this.lastUsedTime = System.currentTimeMillis();
        }

        public Connection getRawConnection() {
            return rawConnection;
        }

        public long getCreationTime() {
            return creationTime;
        }

        public long getLastUsedTime() {
            return lastUsedTime;
        }

        public long getLeaseTime() {
            return leaseTime;
        }

        public Exception getLeaseStackTrace() {
            return leaseStackTrace;
        }
    }

    /**
     * Proxy that intercepts close() to return connection to pool.
     */
    static class ConnectionProxy implements Connection {
        private final PooledConnection pooledConnection;
        private final ProductionConnectionPool pool;
        private volatile boolean closed = false;

        public ConnectionProxy(PooledConnection pooledConnection, ProductionConnectionPool pool) {
            this.pooledConnection = pooledConnection;
            this.pool = pool;
        }

        @Override
        public void close() throws SQLException {
            if (closed) {
                return;
            }
            closed = true;

            // Return to pool instead of actually closing
            pool.returnConnection(pooledConnection);
        }

        @Override
        public boolean isClosed() throws SQLException {
            return closed;
        }

        // Delegate all other methods to raw connection
        private Connection getDelegate() throws SQLException {
            if (closed) {
                throw new SQLException("Connection is closed");
            }
            return pooledConnection.getRawConnection();
        }

        @Override
        public java.sql.Statement createStatement() throws SQLException {
            return getDelegate().createStatement();
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql) throws SQLException {
            return getDelegate().prepareStatement(sql);
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql) throws SQLException {
            return getDelegate().prepareCall(sql);
        }

        @Override
        public String nativeSQL(String sql) throws SQLException {
            return getDelegate().nativeSQL(sql);
        }

        @Override
        public void setAutoCommit(boolean autoCommit) throws SQLException {
            getDelegate().setAutoCommit(autoCommit);
        }

        @Override
        public boolean getAutoCommit() throws SQLException {
            return getDelegate().getAutoCommit();
        }

        @Override
        public void commit() throws SQLException {
            getDelegate().commit();
        }

        @Override
        public void rollback() throws SQLException {
            getDelegate().rollback();
        }

        @Override
        public java.sql.DatabaseMetaData getMetaData() throws SQLException {
            return getDelegate().getMetaData();
        }

        @Override
        public void setReadOnly(boolean readOnly) throws SQLException {
            getDelegate().setReadOnly(readOnly);
        }

        @Override
        public boolean isReadOnly() throws SQLException {
            return getDelegate().isReadOnly();
        }

        @Override
        public void setCatalog(String catalog) throws SQLException {
            getDelegate().setCatalog(catalog);
        }

        @Override
        public String getCatalog() throws SQLException {
            return getDelegate().getCatalog();
        }

        @Override
        public void setTransactionIsolation(int level) throws SQLException {
            getDelegate().setTransactionIsolation(level);
        }

        @Override
        public int getTransactionIsolation() throws SQLException {
            return getDelegate().getTransactionIsolation();
        }

        @Override
        public java.sql.SQLWarning getWarnings() throws SQLException {
            return getDelegate().getWarnings();
        }

        @Override
        public void clearWarnings() throws SQLException {
            getDelegate().clearWarnings();
        }

        // Additional Connection interface methods...
        // (Implement remaining methods similarly - omitted for brevity)
        // In production, use a delegating wrapper or implement all methods

        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency) throws SQLException {
            return getDelegate().createStatement(resultSetType, resultSetConcurrency);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return getDelegate().prepareStatement(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency) throws SQLException {
            return getDelegate().prepareCall(sql, resultSetType, resultSetConcurrency);
        }

        @Override
        public java.util.Map<String, Class<?>> getTypeMap() throws SQLException {
            return getDelegate().getTypeMap();
        }

        @Override
        public void setTypeMap(java.util.Map<String, Class<?>> map) throws SQLException {
            getDelegate().setTypeMap(map);
        }

        @Override
        public void setHoldability(int holdability) throws SQLException {
            getDelegate().setHoldability(holdability);
        }

        @Override
        public int getHoldability() throws SQLException {
            return getDelegate().getHoldability();
        }

        @Override
        public java.sql.Savepoint setSavepoint() throws SQLException {
            return getDelegate().setSavepoint();
        }

        @Override
        public java.sql.Savepoint setSavepoint(String name) throws SQLException {
            return getDelegate().setSavepoint(name);
        }

        @Override
        public void rollback(java.sql.Savepoint savepoint) throws SQLException {
            getDelegate().rollback(savepoint);
        }

        @Override
        public void releaseSavepoint(java.sql.Savepoint savepoint) throws SQLException {
            getDelegate().releaseSavepoint(savepoint);
        }

        @Override
        public java.sql.Statement createStatement(int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return getDelegate().createStatement(resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return getDelegate().prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public java.sql.CallableStatement prepareCall(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability) throws SQLException {
            return getDelegate().prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
            return getDelegate().prepareStatement(sql, autoGeneratedKeys);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, int[] columnIndexes) throws SQLException {
            return getDelegate().prepareStatement(sql, columnIndexes);
        }

        @Override
        public java.sql.PreparedStatement prepareStatement(String sql, String[] columnNames) throws SQLException {
            return getDelegate().prepareStatement(sql, columnNames);
        }

        @Override
        public java.sql.Clob createClob() throws SQLException {
            return getDelegate().createClob();
        }

        @Override
        public java.sql.Blob createBlob() throws SQLException {
            return getDelegate().createBlob();
        }

        @Override
        public java.sql.NClob createNClob() throws SQLException {
            return getDelegate().createNClob();
        }

        @Override
        public java.sql.SQLXML createSQLXML() throws SQLException {
            return getDelegate().createSQLXML();
        }

        @Override
        public boolean isValid(int timeout) throws SQLException {
            return getDelegate().isValid(timeout);
        }

        @Override
        public void setClientInfo(String name, String value) throws java.sql.SQLClientInfoException {
            try {
                getDelegate().setClientInfo(name, value);
            } catch (SQLException e) {
                throw new java.sql.SQLClientInfoException();
            }
        }

        @Override
        public void setClientInfo(java.util.Properties properties) throws java.sql.SQLClientInfoException {
            try {
                getDelegate().setClientInfo(properties);
            } catch (SQLException e) {
                throw new java.sql.SQLClientInfoException();
            }
        }

        @Override
        public String getClientInfo(String name) throws SQLException {
            return getDelegate().getClientInfo(name);
        }

        @Override
        public java.util.Properties getClientInfo() throws SQLException {
            return getDelegate().getClientInfo();
        }

        @Override
        public java.sql.Array createArrayOf(String typeName, Object[] elements) throws SQLException {
            return getDelegate().createArrayOf(typeName, elements);
        }

        @Override
        public java.sql.Struct createStruct(String typeName, Object[] attributes) throws SQLException {
            return getDelegate().createStruct(typeName, attributes);
        }

        @Override
        public void setSchema(String schema) throws SQLException {
            getDelegate().setSchema(schema);
        }

        @Override
        public String getSchema() throws SQLException {
            return getDelegate().getSchema();
        }

        @Override
        public void abort(java.util.concurrent.Executor executor) throws SQLException {
            getDelegate().abort(executor);
        }

        @Override
        public void setNetworkTimeout(java.util.concurrent.Executor executor, int milliseconds) throws SQLException {
            getDelegate().setNetworkTimeout(executor, milliseconds);
        }

        @Override
        public int getNetworkTimeout() throws SQLException {
            return getDelegate().getNetworkTimeout();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return getDelegate().unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return getDelegate().isWrapperFor(iface);
        }
    }

    /**
     * Pool statistics snapshot.
     */
    public record PoolStats(
        int totalConnections,
        int activeConnections,
        int availableConnections,
        int waitingThreads,
        long totalCreated,
        long totalDestroyed,
        long totalRequests,
        long totalWaits,
        long totalLeaks
    ) {}
}

