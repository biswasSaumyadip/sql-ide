package com.lazaro.sqlide.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JDBC implementation of {@link DataSourceDriver}, pooling connections with HikariCP.
 *
 * <p>Deliberately free of any JavaFX dependency: it hands back plain
 * {@link QueryResult} and {@link SchemaNode} values and never touches the scene
 * graph. Every returned {@link CompletableFuture} is completed on a pooled worker
 * thread, so callers on the JavaFX Application Thread must marshal results back
 * themselves.
 *
 * <p>User statements run on a dedicated session connection when auto-commit is off
 * (so begin/commit/rollback survive across queries). Schema introspection keeps
 * using the pool so the tree can refresh without blocking the session.
 */
public final class JdbcSqlDriver implements DataSourceDriver {

    /** Registry key under which this driver is published. */
    public static final String ID = "jdbc-mysql";

    /** Hard cap default on rows materialised per query, to bound memory. */
    public static final int MAX_ROWS = 1_000;

    private static final DriverCapabilities CAPABILITIES = new DriverCapabilities(
            ID,
            "JDBC (MySQL, MariaDB, PostgreSQL, H2)",
            true,
            true,
            true,
            MAX_ROWS);

    private static final int POOL_SIZE = 4;
    private static final long CONNECTION_TIMEOUT_MS = 10_000L;

    private final ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE, workerThreadFactory());
    private final SchemaIntrospectionService introspection = new SchemaIntrospectionService(this);
    private final Object sessionLock = new Object();

    private volatile HikariDataSource dataSource;
    private volatile ConnectionConfig config;
    /** Applied to every borrowed connection before statement execution. */
    private volatile String activeCatalog;
    private volatile boolean autoCommit = true;
    /** Soft row cap for user queries; defaults to {@link #MAX_ROWS}. */
    private volatile int queryMaxRows = MAX_ROWS;
    /** Held only while {@link #autoCommit} is {@code false}. */
    private volatile Connection sessionConnection;
    private volatile Statement activeStatement;
    private volatile boolean executing;

    // ---------------------------------------------------------------- lifecycle

    @Override
    public DriverCapabilities capabilities() {
        return CAPABILITIES;
    }

    /**
     * Replaces any existing pool with one built from {@code newConfig}, validating
     * the credentials eagerly. The future fails if the server rejects the connection.
     */
    @Override
    public CompletableFuture<Void> connect(ConnectionConfig newConfig) {
        Objects.requireNonNull(newConfig, "config must not be null");
        return CompletableFuture.runAsync(() -> openPool(newConfig), executor);
    }

    /**
     * Opens a single throwaway connection, leaving the live pool untouched, and
     * reports what answered.
     */
    @Override
    public CompletableFuture<String> testConnection(ConnectionConfig candidate) {
        Objects.requireNonNull(candidate, "config must not be null");
        return CompletableFuture.supplyAsync(() -> {
            try {
                Class.forName(candidate.driver().driverClassName());
            } catch (ClassNotFoundException e) {
                throw new CompletionException(
                        new SQLException("JDBC driver not on the classpath: " + candidate.driver().driverClassName(), e));
            }
            try (Connection connection = java.sql.DriverManager.getConnection(
                    candidate.jdbcUrl(), candidate.user(), candidate.password())) {
                DatabaseMetaData metaData = connection.getMetaData();
                return "%s %s".formatted(metaData.getDatabaseProductName(), metaData.getDatabaseProductVersion());
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public boolean isConnected() {
        HikariDataSource current = dataSource;
        return current != null && !current.isClosed();
    }

    @Override
    public Optional<ConnectionConfig> currentConfig() {
        return Optional.ofNullable(config);
    }

    /** Closes the pool. Safe to call when already disconnected. */
    public void disconnect() {
        cancelQuietly();
        releaseSessionConnection(true);
        HikariDataSource current = dataSource;
        dataSource = null;
        config = null;
        activeCatalog = null;
        autoCommit = true;
        if (current != null && !current.isClosed()) {
            current.close();
        }
    }

    /**
     * Borrows a connection from the pool. The caller owns it and must close it,
     * ideally via try-with-resources so it returns to the pool.
     */
    public Connection getConnection() throws SQLException {
        HikariDataSource current = dataSource;
        if (current == null || current.isClosed()) {
            throw new SQLException("Not connected. Open a connection before running statements.");
        }
        return current.getConnection();
    }

    /** Shared worker pool, reused by {@link SchemaIntrospectionService} so all JDBC work stays on the same threads. */
    Executor asyncExecutor() {
        return executor;
    }

    @Override
    public void close() {
        disconnect();
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- execution

    /**
     * Executes an arbitrary statement asynchronously. Handles both result-producing
     * queries and DML/DDL, and always resolves to a {@link QueryResult}.
     */
    @Override
    public CompletableFuture<QueryResult> executeQueryAsync(String sql) {
        String statement = sql == null ? "" : sql.trim();
        if (statement.isEmpty()) {
            return CompletableFuture.completedFuture(
                    QueryResult.ofError("Nothing to execute: the statement is empty.", 0L));
        }
        return CompletableFuture.supplyAsync(() -> execute(statement), executor);
    }

    @Override
    public CompletableFuture<ScriptResult> executeScriptAsync(List<String> statements) {
        List<String> cleaned = new ArrayList<>();
        if (statements != null) {
            for (String sql : statements) {
                if (sql != null && !sql.isBlank()) {
                    cleaned.add(sql.trim());
                }
            }
        }
        if (cleaned.isEmpty()) {
            return CompletableFuture.completedFuture(
                    new ScriptResult(List.of(QueryResult.ofError(
                            "Nothing to execute: the script is empty.", 0L)), 0L, true));
        }
        if (cleaned.size() == 1) {
            return executeQueryAsync(cleaned.getFirst()).thenApply(ScriptResult::ofSingle);
        }
        return CompletableFuture.supplyAsync(() -> executeScript(cleaned), executor);
    }

    private ScriptResult executeScript(List<String> statements) {
        long startNanos = System.nanoTime();
        List<QueryResult> results = new ArrayList<>();
        Connection connection = null;
        boolean releaseToPool = false;
        try {
            synchronized (sessionLock) {
                if (autoCommit) {
                    connection = getConnection();
                    releaseToPool = true;
                } else {
                    connection = ensureSessionConnectionLocked();
                }
                applyActiveCatalog(connection);
            }
            for (String sql : statements) {
                QueryResult result = executeOn(connection, sql);
                results.add(result);
                if (result.isError()) {
                    return new ScriptResult(results, elapsedMs(startNanos), true);
                }
            }
            return new ScriptResult(results, elapsedMs(startNanos), false);
        } catch (SQLException e) {
            results.add(QueryResult.ofError(describe(e), elapsedMs(startNanos)));
            return new ScriptResult(results, elapsedMs(startNanos), true);
        } finally {
            if (releaseToPool && connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // pool reclaim best-effort
                }
            }
        }
    }

    @Override
    public CompletableFuture<QueryResult> executeQueryAsync(String sql, int skipRows, int maxRows) {
        String statement = sql == null ? "" : sql.trim();
        if (statement.isEmpty()) {
            return CompletableFuture.completedFuture(
                    QueryResult.ofError("Nothing to execute: the statement is empty.", 0L));
        }
        int skip = Math.max(0, skipRows);
        int cap = Math.max(1, maxRows);
        return CompletableFuture.supplyAsync(() -> execute(statement, skip, cap), executor);
    }

    private QueryResult execute(String sql, int skipRows, int maxRows) {
        Connection connection = null;
        boolean releaseToPool = false;
        try {
            synchronized (sessionLock) {
                if (autoCommit) {
                    connection = getConnection();
                    releaseToPool = true;
                } else {
                    connection = ensureSessionConnectionLocked();
                }
                applyActiveCatalog(connection);
            }
            return executeOn(connection, sql, skipRows, maxRows);
        } catch (SQLException e) {
            return QueryResult.ofError(describe(e), 0L);
        } finally {
            if (releaseToPool && connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // pool reclaim best-effort
                }
            }
        }
    }

    private QueryResult execute(String sql) {
        return execute(sql, 0, queryMaxRows);
    }

    private QueryResult executeOn(Connection connection, String sql) {
        return executeOn(connection, sql, 0, queryMaxRows);
    }

    private QueryResult executeOn(Connection connection, String sql, int skipRows, int maxRows) {
        long startNanos = System.nanoTime();
        Statement statement = null;
        try {
            synchronized (sessionLock) {
                statement = connection.createStatement();
                activeStatement = statement;
                executing = true;
            }

            // Allow skipped rows plus one extra so ResultSetMapper can detect truncation.
            int cap = Math.max(1, maxRows);
            int skip = Math.max(0, skipRows);
            statement.setMaxRows(skip + cap + 1);
            boolean producedResultSet = statement.execute(sql);

            if (producedResultSet) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    return ResultSetMapper.drain(resultSet, skip, cap, startNanos);
                }
            }
            return QueryResult.ofUpdate(statement.getUpdateCount(), elapsedMs(startNanos));

        } catch (SQLException e) {
            if (wasCancelled(e)) {
                return QueryResult.ofError("Query cancelled", elapsedMs(startNanos));
            }
            return QueryResult.ofError(describe(e), elapsedMs(startNanos));
        } finally {
            synchronized (sessionLock) {
                if (activeStatement == statement) {
                    activeStatement = null;
                }
                executing = false;
            }
            if (statement != null) {
                try {
                    statement.close();
                } catch (SQLException ignored) {
                    // already closing
                }
            }
        }
    }

    // ---------------------------------------------------------------- transactions

    @Override
    public boolean isAutoCommit() {
        return autoCommit;
    }

    @Override
    public int maxRowsPerQuery() {
        return queryMaxRows;
    }

    @Override
    public void setMaxRowsPerQuery(int maxRows) {
        queryMaxRows = Math.max(1, maxRows);
    }

    @Override
    public CompletableFuture<Void> setAutoCommit(boolean enabled) {
        return CompletableFuture.runAsync(() -> {
            synchronized (sessionLock) {
                if (autoCommit == enabled) {
                    return;
                }
                try {
                    if (enabled) {
                        Connection session = sessionConnection;
                        if (session != null && !session.isClosed()) {
                            if (!session.getAutoCommit()) {
                                session.commit();
                            }
                            session.setAutoCommit(true);
                        }
                        releaseSessionConnectionLocked(false);
                        autoCommit = true;
                    } else {
                        Connection session = ensureSessionConnectionLocked();
                        session.setAutoCommit(false);
                        autoCommit = false;
                    }
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> beginTransaction() {
        return setAutoCommit(false);
    }

    @Override
    public CompletableFuture<Void> commit() {
        return CompletableFuture.runAsync(() -> {
            synchronized (sessionLock) {
                try {
                    Connection session = sessionConnection;
                    if (session == null || session.isClosed()) {
                        return;
                    }
                    session.commit();
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> rollback() {
        return CompletableFuture.runAsync(() -> {
            synchronized (sessionLock) {
                try {
                    Connection session = sessionConnection;
                    if (session == null || session.isClosed()) {
                        return;
                    }
                    session.rollback();
                } catch (SQLException e) {
                    throw new CompletionException(e);
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> cancelExecution() {
        return CompletableFuture.runAsync(this::cancelQuietly, executor);
    }

    @Override
    public boolean isExecuting() {
        return executing;
    }

    // ---------------------------------------------------------------- schema

    @Override
    public CompletableFuture<List<SchemaNode>> getSchemaTree() {
        return introspection.fetchDatabasesAsync();
    }

    @Override
    public CompletableFuture<List<SchemaNode>> getChildren(SchemaNode parent) {
        return introspection.fetchChildrenAsync(parent);
    }

    @Override
    public CompletableFuture<List<SchemaNode>> getSchemaOutline() {
        return introspection.fetchSchemaOutlineAsync(activeCatalog);
    }

    @Override
    public CompletableFuture<List<SchemaNode>> getFullSchema() {
        return introspection.fetchFullSchemaAsync(activeCatalog);
    }

    @Override
    public CompletableFuture<List<SchemaNode>> getSecondarySchema() {
        return introspection.fetchSecondarySchemaAsync(activeCatalog);
    }

    @Override
    public CompletableFuture<SchemaNode> getObjectDetails(SchemaNode node) {
        if (node == null) {
            return CompletableFuture.completedFuture(null);
        }
        String catalog = node.metadata(SchemaNode.META_CATALOG);
        if (catalog == null || catalog.isBlank()) {
            catalog = activeCatalog;
        }
        String owner = catalog == null ? "" : catalog;
        return switch (node.type()) {
            case TABLE, VIEW -> introspection.fetchTableDetailsAsync(owner, node.name());
            case PROCEDURE -> introspection.fetchRoutineDetailsAsync(owner, node.name());
            default -> CompletableFuture.completedFuture(node);
        };
    }

    @Override
    public CompletableFuture<Void> setActiveCatalog(String catalog) {
        String normalized = catalog == null || catalog.isBlank() ? null : catalog.trim();
        return CompletableFuture.runAsync(() -> {
            activeCatalog = normalized;
            if (normalized == null) {
                return;
            }
            // Probe once so a bad name fails here instead of on the next query.
            synchronized (sessionLock) {
                try {
                    if (sessionConnection != null && !sessionConnection.isClosed()) {
                        applyActiveCatalog(sessionConnection);
                        return;
                    }
                } catch (SQLException e) {
                    activeCatalog = null;
                    throw new CompletionException(e);
                }
            }
            try (Connection connection = getConnection()) {
                applyActiveCatalog(connection);
            } catch (SQLException e) {
                activeCatalog = null;
                throw new CompletionException(e);
            }
        }, executor);
    }

    @Override
    public Optional<String> activeCatalog() {
        return Optional.ofNullable(activeCatalog);
    }

    /** Direct access to introspection, for callers that need more than the tree. */
    public SchemaIntrospectionService introspection() {
        return introspection;
    }

    // ---------------------------------------------------------------- internals

    private void openPool(ConnectionConfig newConfig) {
        disconnect();

        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl(newConfig.jdbcUrl());
        hikariConfig.setUsername(newConfig.user());
        hikariConfig.setPassword(newConfig.password());
        hikariConfig.setDriverClassName(newConfig.driver().driverClassName());
        hikariConfig.setPoolName("sqlide-" + newConfig.host());
        hikariConfig.setMaximumPoolSize(POOL_SIZE);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        // Positive value: probe the server during construction so bad credentials
        // surface here instead of on the first query.
        hikariConfig.setInitializationFailTimeout(CONNECTION_TIMEOUT_MS);
        hikariConfig.setAutoCommit(true);

        HikariDataSource created = new HikariDataSource(hikariConfig);
        dataSource = created;
        config = newConfig;
        activeCatalog = newConfig.database().isBlank() ? null : newConfig.database();
        autoCommit = true;
    }

    /** Caller must hold {@link #sessionLock}. */
    private Connection ensureSessionConnectionLocked() throws SQLException {
        Connection current = sessionConnection;
        if (current != null && !current.isClosed()) {
            return current;
        }
        Connection created = getConnection();
        created.setAutoCommit(false);
        applyActiveCatalog(created);
        sessionConnection = created;
        return created;
    }

    private void releaseSessionConnection(boolean rollback) {
        synchronized (sessionLock) {
            releaseSessionConnectionLocked(rollback);
        }
    }

    /** Caller must hold {@link #sessionLock}. */
    private void releaseSessionConnectionLocked(boolean rollback) {
        Connection session = sessionConnection;
        sessionConnection = null;
        if (session == null) {
            return;
        }
        try {
            if (rollback && !session.isClosed() && !session.getAutoCommit()) {
                session.rollback();
            }
        } catch (SQLException ignored) {
            // best-effort before close
        }
        try {
            session.close();
        } catch (SQLException ignored) {
            // returning to pool / discarding
        }
    }

    private void cancelQuietly() {
        Statement statement;
        synchronized (sessionLock) {
            statement = activeStatement;
        }
        if (statement == null) {
            return;
        }
        try {
            statement.cancel();
        } catch (SQLException ignored) {
            // cancel is best-effort; the execute path reports the outcome
        }
    }

    /** Prefer setCatalog (MySQL); fall back to setSchema (PostgreSQL). */
    private void applyActiveCatalog(Connection connection) throws SQLException {
        String catalog = activeCatalog;
        if (catalog == null || catalog.isBlank()) {
            return;
        }
        try {
            connection.setCatalog(catalog);
        } catch (SQLException primary) {
            try {
                connection.setSchema(catalog);
            } catch (SQLException ignored) {
                throw primary;
            }
        }
    }

    private static boolean wasCancelled(SQLException e) {
        String message = e.getMessage();
        if (message != null) {
            String lower = message.toLowerCase();
            if (lower.contains("cancel") || lower.contains("interrupt") || lower.contains("aborted")) {
                return true;
            }
        }
        // Common SQLStates for statement cancel across vendors.
        String state = e.getSQLState();
        return "57014".equals(state) || "HY008".equals(state);
    }

    private static long elapsedMs(long startNanos) {
        return ResultSetMapper.elapsedMs(startNanos);
    }

    private static String describe(SQLException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String state = e.getSQLState();
        return state == null
                ? message
                : "%s (SQLState %s, vendor code %d)".formatted(message, state, e.getErrorCode());
    }

    private static ThreadFactory workerThreadFactory() {
        AtomicInteger counter = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "sqlide-db-" + counter.getAndIncrement());
            // Daemon threads so a hung query can never keep the application alive.
            thread.setDaemon(true);
            return thread;
        };
    }
}
