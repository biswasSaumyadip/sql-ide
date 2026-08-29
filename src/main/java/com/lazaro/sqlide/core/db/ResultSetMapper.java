package com.lazaro.sqlide.core.db;

import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Turns a live {@link ResultSet} into a detached {@link QueryResult}.
 *
 * <p>Shared by {@link JdbcSqlDriver} and by the UI layer so that both drain rows
 * identically. Every method here performs blocking JDBC I/O and must therefore run
 * on a background thread, never on the JavaFX Application Thread.
 */
public final class ResultSetMapper {

    private static final int CLOB_PREVIEW_CHARS = 4_096;

    private ResultSetMapper() {
    }

    /** Drains up to {@link JdbcSqlDriver#MAX_ROWS} rows, timing the work from now. */
    public static QueryResult drain(ResultSet resultSet) throws SQLException {
        return drain(resultSet, JdbcSqlDriver.MAX_ROWS, System.nanoTime());
    }

    /**
     * Reads the cursor into memory.
     *
     * @param maxRows    hard cap on materialised rows
     * @param startNanos {@link System#nanoTime()} reading taken when execution began
     */
    public static QueryResult drain(ResultSet resultSet, int maxRows, long startNanos) throws SQLException {
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        List<String> columnNames = columnLabels(metaData, columnCount);

        List<List<String>> rows = new ArrayList<>();
        while (rows.size() < maxRows && resultSet.next()) {
            List<String> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(stringify(resultSet, i));
            }
            rows.add(row);
        }
        return QueryResult.ofRows(columnNames, rows, elapsedMs(startNanos));
    }

    private static List<String> columnLabels(ResultSetMetaData metaData, int columnCount) throws SQLException {
        List<String> labels = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = metaData.getColumnLabel(i);
            labels.add(label == null || label.isEmpty() ? metaData.getColumnName(i) : label);
        }
        return labels;
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

    static long elapsedMs(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }
}
