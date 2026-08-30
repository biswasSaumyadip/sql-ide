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
     * Runs {@code statements} in order on the interactive session. Stops after the
     * first error; earlier successes remain in the returned {@link ScriptResult}.
     */
    CompletableFuture<ScriptResult> executeScriptAsync(List<String> statements);

    /**
     * Top level of the structure tree, with children left unloaded so that a large
     * server does not have to be introspected in full before anything appears.
     * Expand a node with {@link #getChildren(SchemaNode)}.
     */
    CompletableFuture<List<SchemaNode>> getSchemaTree();

    /** Loads one level below {@code parent}. Returns empty for leaves. */
    CompletableFuture<List<SchemaNode>> getChildren(SchemaNode parent);

    /**
     * Eagerly loads every catalog with tables, columns, indexes and foreign keys
     * populated. Intended for the client-side schema cache (autocomplete / object
     * viewer), not for painting the lazy tree.
     */
    CompletableFuture<List<SchemaNode>> getFullSchema();

    /**
     * Sets the catalog/schema used for subsequent statements (MySQL {@code USE},
     * JDBC {@code Connection#setCatalog}). Pass {@code null} or blank to clear.
     */
    CompletableFuture<Void> setActiveCatalog(String catalog);

    /** Catalog currently applied to new statements, empty when none is selected. */
    Optional<String> activeCatalog();

    /**
     * Soft cap on rows materialised per query (Statement#setMaxRows). Default is
     * driver-specific (typically 1000). Values below 1 are clamped to 1.
     */
    default int maxRowsPerQuery() {
        return 1_000;
    }

    /** Overrides {@link #maxRowsPerQuery()} for subsequent statement executions. */
    default void setMaxRowsPerQuery(int maxRows) {
        // optional for drivers that ignore caps
    }

    /**
     * Whether subsequent statements auto-commit. Default {@code true}. When
     * {@code false}, statements share one session connection until
     * {@link #commit()} or {@link #rollback()}.
     */
    boolean isAutoCommit();

    /**
     * Enables or disables auto-commit on the interactive session. Turning it on
     * commits any open work and releases the held session connection.
     */
    CompletableFuture<Void> setAutoCommit(boolean enabled);

    /**
     * Starts a manual transaction (turns auto-commit off). Idempotent when already
     * in manual mode.
     */
    CompletableFuture<Void> beginTransaction();

    /** Commits the open session transaction. No-op when auto-commit is on. */
    CompletableFuture<Void> commit();

    /** Rolls back the open session transaction. No-op when auto-commit is on. */
    CompletableFuture<Void> rollback();

    /**
     * Requests cancellation of the statement currently executing on this driver, if
     * any. Uses {@link java.sql.Statement#cancel()}; completion does not wait for
     * the cancelled query future.
     */
    CompletableFuture<Void> cancelExecution();

    /** {@code true} while a user statement is mid-flight on the session. */
    boolean isExecuting();

    boolean isConnected();

    /** The configuration currently in use, empty when disconnected. */
    Optional<ConnectionConfig> currentConfig();

    /** Releases the connection and any worker threads. Idempotent. */
    @Override
    void close();
}
