package com.lazaro.sqlide.core.transfer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes table-to-table transfers using same-connection {@code INSERT…SELECT}
 * or a cross-connection streaming pipeline. All work is synchronous and must run
 * off the JavaFX Application Thread.
 */
public final class DataTransferService {

    private final AtomicBoolean cancelRequested = new AtomicBoolean(false);
    private final AtomicReference<Statement> activeSourceStatement = new AtomicReference<>();

    public void requestCancel() {
        cancelRequested.set(true);
        Statement statement = activeSourceStatement.get();
        if (statement != null) {
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // best-effort
            }
        }
    }

    public boolean isCancelRequested() {
        return cancelRequested.get();
    }

    public TransferResult transfer(TransferRequest request, TransferProgressListener progress) throws SQLException {
        Objects.requireNonNull(request, "request");
        TransferProgressListener listener = progress == null ? (t, total, d) -> { } : progress;
        cancelRequested.set(false);
        long started = System.nanoTime();
        TransferRequest.Strategy strategy = request.resolveStrategy();
        listener.onLog("Strategy: " + strategy);
        long expected = request.expectedRowCount();
        if (expected <= 0) {
            try (Connection probe = TransferJdbc.open(request.sourceConfig())) {
                expected = TransferJdbc.countRows(
                        probe, request.sourceCatalog(), request.sourceTable(), request.sourceConfig().driver());
            } catch (SQLException ignored) {
                expected = 0;
            }
        }
        final long totalHint = expected;
        listener.onProgress(0, Math.max(0, totalHint), "Starting\u2026");

        try {
            TransferRequest effective = totalHint == request.expectedRowCount()
                    ? request
                    : new TransferRequest(
                    request.sourceConfig(),
                    request.sourceCatalog(),
                    request.sourceTable(),
                    request.sourceColumns(),
                    request.targetConfig(),
                    request.targetCatalog(),
                    request.targetTable(),
                    request.columnMapping(),
                    request.truncateTarget(),
                    request.errorHandling(),
                    request.batchSize(),
                    totalHint);
            TransferResult result = switch (strategy) {
                case SAME_CONNECTION -> transferSameConnection(effective, listener);
                case CROSS_CONNECTION -> transferCrossConnection(effective, listener);
            };
            long elapsed = elapsedMs(started);
            return new TransferResult(
                    strategy,
                    result.rowsTransferred(),
                    result.rowsSkipped(),
                    elapsed,
                    result.cancelled(),
                    result.message(),
                    result.errorLog());
        } finally {
            activeSourceStatement.set(null);
        }
    }

    private TransferResult transferSameConnection(TransferRequest request, TransferProgressListener progress)
            throws SQLException {
        List<String> errors = new ArrayList<>();
        try (Connection connection = TransferJdbc.open(request.sourceConfig())) {
            connection.setAutoCommit(false);
            if (request.truncateTarget()) {
                progress.onLog("Truncating target…");
                try (Statement statement = connection.createStatement()) {
                    activeSourceStatement.set(statement);
                    statement.executeUpdate(TransferSql.truncate(request));
                }
            }
            if (cancelRequested.get()) {
                connection.rollback();
                return cancelled(TransferRequest.Strategy.SAME_CONNECTION, 0, 0, errors);
            }
            String sql = TransferSql.insertSelect(request);
            progress.onLog(sql);
            long transferred;
            try (Statement statement = connection.createStatement()) {
                activeSourceStatement.set(statement);
                transferred = statement.executeUpdate(sql);
            }
            if (cancelRequested.get()) {
                connection.rollback();
                return cancelled(TransferRequest.Strategy.SAME_CONNECTION, 0, 0, errors);
            }
            connection.commit();
            progress.onProgress(transferred, transferred, "Complete");
            return new TransferResult(
                    TransferRequest.Strategy.SAME_CONNECTION,
                    transferred,
                    0,
                    0,
                    false,
                    "Transferred %,d rows via INSERT…SELECT".formatted(transferred),
                    errors);
        } catch (SQLException ex) {
            throw ex;
        }
    }

    private TransferResult transferCrossConnection(TransferRequest request, TransferProgressListener progress)
            throws SQLException {
        List<String> errors = new ArrayList<>();
        long transferred = 0;
        long skipped = 0;
        long total = Math.max(0, request.expectedRowCount());
        int columnCount = request.orderedMapping().size();

        try (Connection source = TransferJdbc.open(request.sourceConfig());
             Connection target = TransferJdbc.open(request.targetConfig())) {
            target.setAutoCommit(false);

            if (request.truncateTarget()) {
                progress.onLog("Truncating target…");
                try (Statement statement = target.createStatement()) {
                    statement.executeUpdate(TransferSql.truncate(request));
                    target.commit();
                }
            }

            String selectSql = TransferSql.selectMapped(request);
            String insertSql = TransferSql.insertPlaceholders(request);
            progress.onLog(selectSql);
            progress.onLog(insertSql);

            try (Statement sourceStatement = source.createStatement(
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY)) {
                activeSourceStatement.set(sourceStatement);
                sourceStatement.setFetchSize(Math.max(100, request.batchSize()));
                try (ResultSet rs = sourceStatement.executeQuery(selectSql);
                     PreparedStatement insert = target.prepareStatement(insertSql)) {

                    int inBatch = 0;
                    while (rs.next()) {
                        if (cancelRequested.get()) {
                            target.rollback();
                            return cancelled(TransferRequest.Strategy.CROSS_CONNECTION, transferred, skipped, errors);
                        }
                        try {
                            for (int i = 1; i <= columnCount; i++) {
                                insert.setObject(i, rs.getObject(i));
                            }
                            if (request.errorHandling() == TransferRequest.ErrorHandling.SKIP) {
                                insert.executeUpdate();
                                transferred++;
                                if (transferred % request.batchSize() == 0) {
                                    target.commit();
                                    progress.onProgress(transferred, total,
                                            "Transferred %,d of %,d rows…".formatted(transferred, total));
                                }
                            } else {
                                insert.addBatch();
                                inBatch++;
                                if (inBatch >= request.batchSize()) {
                                    insert.executeBatch();
                                    target.commit();
                                    transferred += inBatch;
                                    inBatch = 0;
                                    progress.onProgress(transferred, total,
                                            "Transferred %,d of %,d rows…".formatted(transferred, total));
                                }
                            }
                        } catch (SQLException rowError) {
                            if (request.errorHandling() == TransferRequest.ErrorHandling.SKIP) {
                                skipped++;
                                String line = "Skip row %,d: %s".formatted(transferred + skipped, rowError.getMessage());
                                errors.add(line);
                                progress.onLog(line);
                                try {
                                    target.rollback();
                                } catch (SQLException ignored) {
                                    // continue
                                }
                            } else {
                                target.rollback();
                                throw rowError;
                            }
                        }
                    }

                    if (request.errorHandling() == TransferRequest.ErrorHandling.ABORT && inBatch > 0) {
                        insert.executeBatch();
                        target.commit();
                        transferred += inBatch;
                    } else if (request.errorHandling() == TransferRequest.ErrorHandling.SKIP) {
                        target.commit();
                    }
                }
            }

            if (cancelRequested.get()) {
                target.rollback();
                return cancelled(TransferRequest.Strategy.CROSS_CONNECTION, transferred, skipped, errors);
            }

            progress.onProgress(transferred, total == 0 ? transferred : total, "Complete");
            return new TransferResult(
                    TransferRequest.Strategy.CROSS_CONNECTION,
                    transferred,
                    skipped,
                    0,
                    false,
                    "Transferred %,d rows (%d skipped)".formatted(transferred, skipped),
                    errors);
        }
    }

    private static TransferResult cancelled(
            TransferRequest.Strategy strategy, long transferred, long skipped, List<String> errors) {
        return new TransferResult(
                strategy,
                transferred,
                skipped,
                0,
                true,
                "Transfer cancelled after %,d rows".formatted(transferred),
                errors);
    }

    private static long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }
}
