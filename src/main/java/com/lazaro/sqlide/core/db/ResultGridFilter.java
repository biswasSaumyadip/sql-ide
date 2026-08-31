package com.lazaro.sqlide.core.db;

import java.util.List;
import java.util.Locale;

/**
 * In-memory predicates for the result grid: a global "find in results" needle plus
 * optional per-column quick-filter strings. Never talks to the database.
 */
public final class ResultGridFilter {

    private ResultGridFilter() {
    }

    public static boolean matches(
            List<String> row, String globalQuery, List<String> columnQueries) {
        if (row == null) {
            return false;
        }
        if (!matchesGlobal(row, globalQuery)) {
            return false;
        }
        return matchesColumns(row, columnQueries);
    }

    static boolean matchesGlobal(List<String> row, String globalQuery) {
        String needle = normalize(globalQuery);
        if (needle.isEmpty()) {
            return true;
        }
        for (String cell : row) {
            if (cell != null && cell.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    static boolean matchesColumns(List<String> row, List<String> columnQueries) {
        if (columnQueries == null || columnQueries.isEmpty()) {
            return true;
        }
        int limit = columnQueries.size();
        for (int i = 0; i < limit; i++) {
            String needle = normalize(columnQueries.get(i));
            if (needle.isEmpty()) {
                continue;
            }
            String cell = i < row.size() ? row.get(i) : null;
            if (cell == null || !cell.toLowerCase(Locale.ROOT).contains(needle)) {
                return false;
            }
        }
        return true;
    }

    private static String normalize(String query) {
        return query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
    }
}
