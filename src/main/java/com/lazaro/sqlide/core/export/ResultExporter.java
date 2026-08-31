package com.lazaro.sqlide.core.export;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.json.JsonPayloads;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Formats a {@link QueryResult} as CSV, TSV, JSON, or SQL INSERT statements. */
public final class ResultExporter {

    public enum Format {
        CSV,
        TSV,
        JSON,
        SQL_INSERT
    }

    private ResultExporter() {
    }

    public static String export(QueryResult result, Format format, String tableName) {
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(format, "format");
        if (result.isError() || !result.isResultSet()) {
            throw new IllegalArgumentException("Only successful result sets can be exported");
        }
        return switch (format) {
            case CSV -> toCsv(result);
            case TSV -> toTsv(result);
            case JSON -> toJson(result);
            case SQL_INSERT -> toInserts(result, tableName == null || tableName.isBlank() ? "exported_table" : tableName);
        };
    }

    /** Builds a result containing only the given rows (same columns as {@code source}). */
    public static QueryResult subset(QueryResult source, List<List<String>> rows) {
        Objects.requireNonNull(source, "source");
        if (source.isError() || !source.isResultSet()) {
            throw new IllegalArgumentException("Only successful result sets can be sliced");
        }
        List<List<String>> copy = rows == null ? List.of() : List.copyOf(rows);
        return QueryResult.ofRows(
                source.columnNames(), copy, source.executionTimeMs(), false, source.columns());
    }

    public static String toCsv(QueryResult result) {
        return delimited(result, ',', true);
    }

    /** Tab-separated values with a header row — ideal for pasting into spreadsheets. */
    public static String toTsv(QueryResult result) {
        return delimited(result, '\t', false);
    }

    private static String delimited(QueryResult result, char separator, boolean csvStyleQuotes) {
        StringBuilder out = new StringBuilder();
        out.append(result.columnNames().stream()
                .map(name -> cell(name, separator, csvStyleQuotes))
                .collect(Collectors.joining(String.valueOf(separator))));
        out.append('\n');
        for (List<String> row : result.rows()) {
            List<String> cells = new ArrayList<>(result.columnCount());
            for (int i = 0; i < result.columnCount(); i++) {
                cells.add(cell(i < row.size() ? row.get(i) : null, separator, csvStyleQuotes));
            }
            out.append(String.join(String.valueOf(separator), cells)).append('\n');
        }
        return out.toString();
    }

    /**
     * Serializes the result set as a pretty-printed JSON array of objects
     * (column names as keys). Values are coerced to JSON types when possible.
     */
    public static String toJson(QueryResult result) {
        return JsonPayloads.writePretty(toRowMaps(result)) + "\n";
    }

    /** Builds row maps suitable for JSON array export (frontend-friendly). */
    public static List<Map<String, Object>> toRowMaps(QueryResult result) {
        Objects.requireNonNull(result, "result");
        List<String> columns = result.columnNames();
        List<Map<String, Object>> rows = new ArrayList<>(result.rows().size());
        for (List<String> row : result.rows()) {
            Map<String, Object> map = new LinkedHashMap<>(columns.size());
            for (int c = 0; c < columns.size(); c++) {
                String value = c < row.size() ? row.get(c) : null;
                map.put(columns.get(c), JsonPayloads.coerceValue(value));
            }
            rows.add(map);
        }
        return rows;
    }

    public static String toInserts(QueryResult result, String tableName) {
        String table = sanitizeIdent(tableName);
        String columns = result.columnNames().stream()
                .map(ResultExporter::sanitizeIdent)
                .collect(Collectors.joining(", "));
        StringBuilder out = new StringBuilder();
        for (List<String> row : result.rows()) {
            out.append("INSERT INTO ").append(table).append(" (").append(columns).append(") VALUES (");
            for (int i = 0; i < result.columnCount(); i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(sqlLiteral(i < row.size() ? row.get(i) : null));
            }
            out.append(");\n");
        }
        return out.toString();
    }

    private static String cell(String value, char separator, boolean csvStyleQuotes) {
        if (value == null) {
            return "";
        }
        if (!csvStyleQuotes) {
            // TSV: escape tabs/newlines so a single cell cannot break the grid.
            return value
                    .replace("\\", "\\\\")
                    .replace("\t", "\\t")
                    .replace("\r", "\\r")
                    .replace("\n", "\\n");
        }
        boolean quote = value.indexOf(separator) >= 0
                || value.contains("\"")
                || value.contains("\n")
                || value.contains("\r");
        String escaped = value.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    private static String sqlLiteral(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    private static String sanitizeIdent(String name) {
        String cleaned = name == null ? "col" : name.replaceAll("[^A-Za-z0-9_]", "_");
        if (cleaned.isBlank()) {
            cleaned = "col";
        }
        if (Character.isDigit(cleaned.charAt(0))) {
            cleaned = "t_" + cleaned;
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }
}
