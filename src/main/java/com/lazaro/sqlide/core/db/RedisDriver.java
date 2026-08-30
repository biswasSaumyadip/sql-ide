package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.redis.RedisConnectionManager;
import com.lazaro.sqlide.core.redis.RedisExecutor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis implementation of {@link DataSourceDriver}, pooling Jedis connections.
 *
 * <p>Transactions, catalogs and SQL schema introspection are not applicable;
 * the structure tree shows a single database node so the Database pane is not
 * empty after connect.
 */
public final class RedisDriver implements DataSourceDriver {

    public static final String ID = "redis";

    private static final DriverCapabilities CAPABILITIES = new DriverCapabilities(
            ID,
            "Redis",
            true,
            false,
            false,
            1_000);

    private final RedisConnectionManager connections = new RedisConnectionManager();
    private final RedisExecutor executor = new RedisExecutor(connections);
    private final ExecutorService workers = Executors.newFixedThreadPool(2, workerThreadFactory());
    private volatile boolean executing;

    @Override
    public DriverCapabilities capabilities() {
        return CAPABILITIES;
    }

    @Override
    public CompletableFuture<Void> connect(ConnectionConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return CompletableFuture.runAsync(() -> {
            try {
                connections.connect(config);
            } catch (RuntimeException e) {
                throw new CompletionException(e);
            }
        }, workers);
    }

    @Override
    public CompletableFuture<String> testConnection(ConnectionConfig config) {
        Objects.requireNonNull(config, "config must not be null");
        return CompletableFuture.supplyAsync(() -> {
            try {
                return RedisConnectionManager.probe(config);
            } catch (RuntimeException e) {
                throw new CompletionException(e);
            }
        }, workers);
    }

    @Override
    public CompletableFuture<QueryResult> executeQueryAsync(String sql) {
        String line = sql == null ? "" : sql.trim();
        if (line.isEmpty()) {
            return CompletableFuture.completedFuture(
                    QueryResult.ofError("Nothing to execute: the command is empty.", 0L));
        }
        return CompletableFuture.supplyAsync(() -> {
            executing = true;
            try {
                return executor.execute(line);
            } finally {
                executing = false;
            }
        }, workers);
    }

    @Override
    public CompletableFuture<ScriptResult> executeScriptAsync(List<String> statements) {
        return CompletableFuture.supplyAsync(() -> {
            executing = true;
            try {
                return executor.executeScript(statements);
            } finally {
                executing = false;
            }
        }, workers);
    }

    @Override
    public CompletableFuture<List<SchemaNode>> getSchemaTree() {
        return CompletableFuture.supplyAsync(this::rootNodes, workers);
    }

    @Override
    public CompletableFuture<List<SchemaNode>> getChildren(SchemaNode parent) {
        return CompletableFuture.completedFuture(List.of());
    }

    @Override
    public CompletableFuture<List<SchemaNode>> getFullSchema() {
        return getSchemaTree();
    }

    @Override
    public CompletableFuture<Void> setActiveCatalog(String catalog) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public Optional<String> activeCatalog() {
        return Optional.empty();
    }

    @Override
    public boolean isAutoCommit() {
        return true;
    }

    @Override
    public CompletableFuture<Void> setAutoCommit(boolean enabled) {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> beginTransaction() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> commit() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> rollback() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<Void> cancelExecution() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public boolean isExecuting() {
        return executing;
    }

    @Override
    public boolean isConnected() {
        return connections.isConnected();
    }

    @Override
    public Optional<ConnectionConfig> currentConfig() {
        return connections.currentConfig();
    }

    @Override
    public void close() {
        connections.close();
        workers.shutdownNow();
        try {
            workers.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private List<SchemaNode> rootNodes() {
        String label = connections.currentConfig()
                .map(ConnectionConfig::displayLabel)
                .orElse("Redis");
        return List.of(SchemaNode.of(label, SchemaNode.NodeType.DATABASE));
    }

    private static ThreadFactory workerThreadFactory() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "sqlide-redis-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
