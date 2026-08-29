package com.lazaro.sqlide.core.db;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Everything the UI needs from a data source, independent of how it is reached.
 * JDBC is the only implementation today; the interface exists so that adding
 * another backend does not mean rewriting the controller.
 *
 * <p><strong>Threading contract.</strong> No method here may block the caller on
 * network or disk I/O. Every operation returns a {@link CompletableFuture} that is
 * completed on a driver-owned worker thread, so a caller on the JavaFX Application
 * Thread must marshal results back itself.
 *
 * <p><strong>Error contract.</strong> A statement the server rejects is data, not a
 * control-flow exception: it resolves to a {@link QueryResult#isError() failed result}.
 * Lifecycle problems — unreachable host, bad credentials, introspection failures —
 * complete their future exceptionally instead.
 */
public interface DataSourceDriver extends AutoCloseable {

    /** Static description of what this driver supports. Safe to call at any time. */
    DriverCapabilities capabilities();

    /**
     * Opens the connection, replacing any existing one. Fails exceptionally if the
     * endpoint is unreachable or rejects the credentials.
     */
    CompletableFuture<Void> connect(ConnectionConfig config);

    /**
     * Checks that an endpoint is usable without disturbing the current connection,
     * so a dialog can offer a "Test" button.
     *
     * @return a short description of the server, e.g. {@code MySQL 8.4.0}
     */
    CompletableFuture<String> testConnection(ConnectionConfig config);

    /** Runs one statement. See the error contract above. */
    CompletableFuture<QueryResult> executeQueryAsync(String sql);

    /**
     * Top level of the structure tree, with children left unloaded so that a large
     * server does not have to be introspected in full before anything appears.
     * Expand a node with {@link #getChildren(SchemaNode)}.
     */
    CompletableFuture<List<SchemaNode>> getSchemaTree();

    /** Loads one level below {@code parent}. Returns empty for leaves. */
    CompletableFuture<List<SchemaNode>> getChildren(SchemaNode parent);

    boolean isConnected();

    /** The configuration currently in use, empty when disconnected. */
    Optional<ConnectionConfig> currentConfig();

    /** Releases the connection and any worker threads. Idempotent. */
    @Override
    void close();
}
