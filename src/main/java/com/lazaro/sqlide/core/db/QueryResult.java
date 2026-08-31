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
 * @param statusText      Redis status reply ({@code OK}, {@code (integer) 1}), or {@code null}
 * @param columns         JDBC type / key metadata aligned with {@code columnNames}
 */
public record QueryResult(
        List<String> columnNames,
        List<List<String>> rows,
        int rowCount,
        long executionTimeMs,
        boolean isResultSet,
        String errorMessage,
        boolean truncated,
        String statusText,
        List<ResultColumn> columns
) {

    public QueryResult {
        columns = columns == null || columns.isEmpty()
                ? ResultColumn.fromNames(columnNames)
                : List.copyOf(columns);
        columnNames = columns.isEmpty()
                ? List.copyOf(Objects.requireNonNullElse(columnNames, List.of()))
                : columns.stream().map(ResultColumn::name).toList();
        rows = deepCopy(rows);
    }

    public static QueryResult ofRows(List<String> columnNames, List<List<String>> rows, long executionTimeMs) {
        return ofRows(columnNames, rows, executionTimeMs, false);
    }

    public static QueryResult ofRows(
            List<String> columnNames, List<List<String>> rows, long executionTimeMs, boolean truncated) {
        return ofRows(columnNames, rows, executionTimeMs, truncated, ResultColumn.fromNames(columnNames));
    }

    public static QueryResult ofRows(
            List<String> columnNames,
            List<List<String>> rows,
            long executionTimeMs,
            boolean truncated,
            List<ResultColumn> columns) {
        int count = rows == null ? 0 : rows.size();
        return new QueryResult(columnNames, rows, count, executionTimeMs, true, null, truncated, null, columns);
    }

    /**
     * Concatenates {@code next} onto this result. Timing is summed; truncation
     * follows the later page. Column labels must match. Metadata is kept from
     * this (left-hand) page.
     */
    public QueryResult appended(QueryResult next) {
        if (next == null || next.isError() || !next.isResultSet() || isError() || !isResultSet()) {
            return this;
        }
        if (!columnNames.equals(next.columnNames())) {
            return QueryResult.ofError("Load more returned different columns than the current grid.", 0);
        }
        List<List<String>> combined = new ArrayList<>(rows.size() + next.rows().size());
        combined.addAll(rows);
        combined.addAll(next.rows());
        return ofRows(columnNames, combined, executionTimeMs + next.executionTimeMs(), next.truncated(), columns);
    }

    public static QueryResult ofUpdate(int updateCount, long executionTimeMs) {
        return new QueryResult(
                List.of(), List.of(), Math.max(updateCount, 0), executionTimeMs, false, null, false, null, List.of());
    }

    /**
     * Redis simple-string / integer reply that belongs in the Output log rather
     * than a result grid ({@code SET} → {@code OK}, {@code DEL} → {@code (integer) 1}).
     */
    public static QueryResult ofStatus(String reply, long executionTimeMs) {
        String text = reply == null || reply.isBlank() ? "OK" : reply;
        return new QueryResult(List.of(), List.of(), 0, executionTimeMs, false, null, false, text, List.of());
    }

    public static QueryResult ofError(String errorMessage, long executionTimeMs) {
        return new QueryResult(List.of(), List.of(), 0, executionTimeMs, false,
                Objects.requireNonNullElse(errorMessage, "Unknown error"), false, null, List.of());
    }

    public boolean isError() {
        return errorMessage != null;
    }

    /** {@code true} for Redis write/status replies that should not open a result grid. */
    public boolean isStatusReply() {
        return !isError() && !isResultSet && statusText != null;
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
        return "Showing %,d rows \u2014 more available.".formatted(rowCount);
    }

    /** Single-line status suitable for a footer or log line. */
    public String summary() {
        return summary(false);
    }

    public String summary(boolean redis) {
        if (isError()) {
            return "Failed after %d ms: %s".formatted(executionTimeMs, errorMessage);
        }
        if (isStatusReply()) {
            return "Reply: %s in %d ms".formatted(statusText, executionTimeMs);
        }
        String unit = countUnit(redis, rowCount);
        if (isResultSet) {
            if (truncated) {
                return "%,d+ %s in %d ms (truncated)".formatted(rowCount, unit, executionTimeMs);
            }
            return "%d %s in %d ms".formatted(rowCount, unit, executionTimeMs);
        }
        return "%d %s affected in %d ms".formatted(rowCount, unit, executionTimeMs);
    }

    /** Friendly one-liner for the results pane on a successful statement. */
    public String successMessage() {
        return successMessage(false);
    }

    public String successMessage(boolean redis) {
        if (isError()) {
            return errorMessage();
        }
        if (isStatusReply()) {
            return "Reply: " + statusText;
        }
        String ok = redis ? "Command OK" : "Query OK";
        String unit = countUnit(redis, rowCount);
        if (isResultSet) {
            if (truncated) {
                return "%s \u2014 %,d %s shown, more available (%d ms)".formatted(
                        ok, rowCount, unit, executionTimeMs);
            }
            return "%s \u2014 %d %s returned (%d ms)".formatted(ok, rowCount, unit, executionTimeMs);
        }
        return "%s \u2014 %d %s affected (%d ms)".formatted(ok, rowCount, unit, executionTimeMs);
    }

    private static String countUnit(boolean redis, int count) {
        if (redis) {
            return count == 1 ? "key" : "keys";
        }
        return count == 1 ? "row" : "rows";
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
