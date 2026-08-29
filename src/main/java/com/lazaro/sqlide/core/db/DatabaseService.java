package com.lazaro.sqlide.core.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the connection pool and executes SQL off the caller's thread.
 *
 * <p>This class is deliberately free of any JavaFX dependency: it hands back plain
 * {@link QueryResult} records and never touches the scene graph. Every returned
 * {@link CompletableFuture} is completed on a pooled worker thread, so callers on
 * the JavaFX Application Thread must marshal results back themselves.
 *
 * <p>Query failures are reported as a {@link QueryResult#isError() failed result}
 * rather than an exceptional future; connection failures complete exceptionally,
 * because they are lifecycle problems rather than statement problems.
 */
public final class DatabaseService implements AutoCloseable {

    /** Hard cap on rows materialised per query, to bound memory. */
    public static final int MAX_ROWS = 1_000;

    private static final int POOL_SIZE = 4;
    private static final long CONNECTION_TIMEOUT_MS = 10_000L;
    private static final int CLOB_PREVIEW_CHARS = 4_096;

    private final ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE, workerThreadFactory());

    private volatile HikariDataSource dataSource;
    private volatile ConnectionConfig config;

    // ---------------------------------------------------------------- lifecycle

    /**
     * Replaces any existing pool with one built from {@code newConfig}, validating
     * the credentials eagerly. The future fails if the server rejects the connection.
     */
    public CompletableFuture<ConnectionConfig> connectAsync(ConnectionConfig newConfig) {
        Objects.requireNonNull(newConfig, "config must not be null");
        return CompletableFuture.supplyAsync(() -> openPool(newConfig), executor);
    }

    public boolean isConnected() {
        HikariDataSource current = dataSource;
        return current != null && !current.isClosed();
    }

    public Optional<ConnectionConfig> currentConfig() {
        return Optional.ofNullable(config);
    }

    /** Closes the pool. Safe to call when already disconnected. */
    public void disconnect() {
        HikariDataSource current = dataSource;
        dataSource = null;
        config = null;
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

            statement.setMaxRows(MAX_ROWS);
            boolean producedResultSet = statement.execute(sql);

            if (producedResultSet) {
                try (ResultSet resultSet = statement.getResultSet()) {
                    return drain(resultSet, startNanos);
                }
            }
            return QueryResult.ofUpdate(statement.getUpdateCount(), elapsedMs(startNanos));

        } catch (SQLException e) {
            return QueryResult.ofError(describe(e), elapsedMs(startNanos));
        }
    }

    private static QueryResult drain(ResultSet resultSet, long startNanos) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();

        List<String> columnNames = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            columnNames.add(label == null || label.isEmpty() ? metaData.getColumnName(i) : label);
        }

        List<List<String>> rows = new ArrayList<>();
        while (rows.size() < MAX_ROWS && resultSet.next()) {
            List<String> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(stringify(resultSet, i));
            }
            rows.add(row);
        }
        return QueryResult.ofRows(columnNames, rows, elapsedMs(startNanos));
    }

    /** Renders a cell as text, returning {@code null} for SQL NULL and a placeholder for binary payloads. */
    private static String stringify(ResultSet resultSet, int columnIndex) throws SQLException {
        Object value = resultSet.getObject(columnIndex);
        if (value == null || resultSet.wasNull()) {
            return null;
        }
        return switch (value) {
            case byte[] bytes -> "<binary, %d bytes>".formatted(bytes.length);
            case Blob blob -> "<blob, %d bytes>".formatted(blob.length());
            case Clob clob -> clob.getSubString(1, (int) Math.min(clob.length(), CLOB_PREVIEW_CHARS));
            default -> value.toString();
        };
    }

    // ---------------------------------------------------------------- internals

    private ConnectionConfig openPool(ConnectionConfig newConfig) {
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
        return newConfig;
    }

    private static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
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
