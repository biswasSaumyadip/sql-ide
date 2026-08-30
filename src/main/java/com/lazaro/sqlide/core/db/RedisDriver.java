package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.redis.RedisConnectionManager;
import com.lazaro.sqlide.core.redis.RedisExecutor;
import com.lazaro.sqlide.core.redis.RedisKeyTree;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;

import java.util.ArrayList;
import java.util.Collections;
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
 * the structure tree is a colon-grouped key explorer built with {@code SCAN}.
 */
public final class RedisDriver implements DataSourceDriver {

    public static final String ID = "redis";

    /** Soft cap so a huge keyspace cannot freeze the Database pane. */
    static final int MAX_SCAN_KEYS = 10_000;

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
        return CompletableFuture.supplyAsync(this::populateRedisKeys, workers);
    }

    @Override
    public CompletableFuture<List<SchemaNode>> getChildren(SchemaNode parent) {
        if (parent != null && !parent.children().isEmpty()) {
            return CompletableFuture.completedFuture(parent.children());
        }
        return CompletableFuture.completedFuture(List.of());
    }

    /**
     * SCAN the current Redis DB and group keys into a colon-delimited folder tree.
     */
    public List<SchemaNode> populateRedisKeys() {
        List<String> keys = scanKeys();
        Collections.sort(keys);
        return RedisKeyTree.build(keys);
    }

    /** Redis {@code TYPE} for {@code key}, or {@code none} when missing. */
    public CompletableFuture<String> keyType(String key) {
        String target = key == null ? "" : key;
        return CompletableFuture.supplyAsync(() -> {
            try (Jedis jedis = connections.borrow()) {
                String type = jedis.type(target);
                return type == null || type.isBlank() ? "none" : type;
            } catch (RuntimeException e) {
                throw new CompletionException(e);
            }
        }, workers);
    }

    private List<String> scanKeys() {
        List<String> keys = new ArrayList<>();
        try (Jedis jedis = connections.borrow()) {
            ScanParams params = new ScanParams().match("*").count(500);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> page = jedis.scan(cursor, params);
                keys.addAll(page.getResult());
                cursor = page.getCursor();
                if (keys.size() >= MAX_SCAN_KEYS) {
                    return new ArrayList<>(keys.subList(0, MAX_SCAN_KEYS));
                }
            } while (cursor != null && !ScanParams.SCAN_POINTER_START.equals(cursor));
        }
        return keys;
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

    private static ThreadFactory workerThreadFactory() {
        AtomicInteger n = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "sqlide-redis-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
