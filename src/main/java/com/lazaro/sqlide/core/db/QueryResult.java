package com.lazaro.sqlide.core.db;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Detached outcome of a single statement execution. Holds no JDBC handles, so it
 * can safely cross thread boundaries and outlive the connection that produced it.
 *
 * <p>A {@code null} entry inside a row means SQL {@code NULL}, which is deliberately
 * distinct from the empty string.
 *
 * @param columnNames     column labels, empty for updates and errors
 * @param rows            materialised rows, empty for updates and errors
 * @param rowCount        rows fetched for a query, or the update count for DML/DDL
 * @param executionTimeMs wall-clock duration of the execution
 * @param isResultSet     {@code true} when the statement produced a result set
 * @param errorMessage    failure description, or {@code null} on success
 * @param truncated       {@code true} when more rows existed than were materialised
 */
public record QueryResult(
        List<String> columnNames,
        List<List<String>> rows,
        int rowCount,
        long executionTimeMs,
        boolean isResultSet,
        String errorMessage,
        boolean truncated
) {

    public QueryResult {
        columnNames = List.copyOf(Objects.requireNonNullElse(columnNames, List.of()));
        rows = deepCopy(rows);
    }

    public static QueryResult ofRows(List<String> columnNames, List<List<String>> rows, long executionTimeMs) {
        return ofRows(columnNames, rows, executionTimeMs, false);
    }

    public static QueryResult ofRows(
            List<String> columnNames, List<List<String>> rows, long executionTimeMs, boolean truncated) {
        return new QueryResult(columnNames, rows, rows.size(), executionTimeMs, true, null, truncated);
    }

    public static QueryResult ofUpdate(int updateCount, long executionTimeMs) {
        return new QueryResult(List.of(), List.of(), Math.max(updateCount, 0), executionTimeMs, false, null, false);
    }

    public static QueryResult ofError(String errorMessage, long executionTimeMs) {
        return new QueryResult(List.of(), List.of(), 0, executionTimeMs, false,
                Objects.requireNonNullElse(errorMessage, "Unknown error"), false);
    }

    public boolean isError() {
        return errorMessage != null;
    }

    public int columnCount() {
        return columnNames.size();
    }

    /**
     * Banner text when the grid is capped; {@code null} when the full result was
     * loaded.
     */
    public String truncationBanner() {
        if (!truncated || !isResultSet || isError()) {
            return null;
        }
        return "Showing first %,d rows \u2014 result truncated. Add a LIMIT or refine the query to see the rest."
                .formatted(rowCount);
    }

    /** Single-line status suitable for a footer or log line. */
    public String summary() {
        if (isError()) {
            return "Failed after %d ms: %s".formatted(executionTimeMs, errorMessage);
        }
        if (isResultSet) {
            if (truncated) {
                return "%,d+ rows in %d ms (truncated)".formatted(rowCount, executionTimeMs);
            }
            return "%d %s in %d ms".formatted(rowCount, rowCount == 1 ? "row" : "rows", executionTimeMs);
        }
        return "%d %s affected in %d ms".formatted(rowCount, rowCount == 1 ? "row" : "rows", executionTimeMs);
    }

    /** Friendly one-liner for the results pane on a successful statement. */
    public String successMessage() {
        if (isError()) {
            return errorMessage();
        }
        if (isResultSet) {
            if (truncated) {
                return "Query OK \u2014 first %,d rows shown, more available (%d ms)".formatted(
                        rowCount, executionTimeMs);
            }
            return "Query OK \u2014 %d %s returned (%d ms)".formatted(
                    rowCount, rowCount == 1 ? "row" : "rows", executionTimeMs);
        }
        return "Query OK \u2014 %d %s affected (%d ms)".formatted(
                rowCount, rowCount == 1 ? "row" : "rows", executionTimeMs);
    }

    private static List<List<String>> deepCopy(List<List<String>> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        // List.copyOf is unusable here: rows legitimately contain nulls for SQL NULL.
        List<List<String>> copy = new ArrayList<>(source.size());
        for (List<String> row : source) {
            copy.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        return Collections.unmodifiableList(copy);
    }
}
