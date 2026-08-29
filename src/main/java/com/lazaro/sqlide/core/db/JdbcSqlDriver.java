package com.lazaro.sqlide.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
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
 */
public final class JdbcSqlDriver implements DataSourceDriver {

    /** Registry key under which this driver is published. */
    public static final String ID = "jdbc-mysql";

    /** Hard cap on rows materialised per query, to bound memory. */
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

    private volatile HikariDataSource dataSource;
    private volatile ConnectionConfig config;
    /** Applied to every borrowed connection before statement execution. */
    private volatile String activeCatalog;

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
        HikariDataSource current = dataSource;
        dataSource = null;
        config = null;
        activeCatalog = null;
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

    private QueryResult execute(String sql) {
        long startNanos = System.nanoTime();
        // Both the statement and the connection are released here, before the value
        // is handed to the future, so no JDBC resource can escape to the UI layer.
        try (Connection connection = getConnection();
             Statement statement = connection.createStatement()) {

            applyActiveCatalog(connection);
            statement.setMaxRows(MAX_ROWS);
            boolean producedResultSet = statement.execute(sql);

            if (producedResultSet) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    return ResultSetMapper.drain(resultSet, MAX_ROWS, startNanos);
                }
            }
            return QueryResult.ofUpdate(statement.getUpdateCount(), elapsedMs(startNanos));

        } catch (SQLException e) {
            return QueryResult.ofError(describe(e), elapsedMs(startNanos));
        }
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
    public CompletableFuture<List<SchemaNode>> getFullSchema() {
        return introspection.fetchFullSchemaAsync();
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
