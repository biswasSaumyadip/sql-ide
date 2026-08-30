package com.lazaro.sqlide.core.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds {@code UPDATE} statements from edited result rows keyed by primary-key columns.
 */
public final class UpdateSqlGenerator {

    private UpdateSqlGenerator() {
    }

    /**
     * @return SQL or {@code null} when nothing changed / keys missing
     */
    public static String update(
            String qualifiedTable,
            List<String> columnNames,
            List<String> primaryKeyColumns,
            List<String> originalRow,
            List<String> currentRow) {
        Objects.requireNonNull(qualifiedTable, "qualifiedTable");
        if (columnNames == null || primaryKeyColumns == null || primaryKeyColumns.isEmpty()
                || originalRow == null || currentRow == null) {
            return null;
        }
        if (columnNames.size() != originalRow.size() || columnNames.size() != currentRow.size()) {
            return null;
        }

        List<String> sets = new ArrayList<>();
        for (int i = 0; i < columnNames.size(); i++) {
            String column = columnNames.get(i);
            if (containsIgnoreCase(primaryKeyColumns, column)) {
                continue;
            }
            String before = originalRow.get(i);
            String after = currentRow.get(i);
            if (Objects.equals(before, after)) {
                continue;
            }
            sets.add(quoteIdent(column) + " = " + literal(after));
        }
        if (sets.isEmpty()) {
            return null;
        }

        List<String> wheres = new ArrayList<>();
        for (String pk : primaryKeyColumns) {
            int index = indexOfIgnoreCase(columnNames, pk);
            if (index < 0) {
                return null;
            }
            String value = originalRow.get(index);
            if (value == null) {
                wheres.add(quoteIdent(pk) + " IS NULL");
            } else {
                wheres.add(quoteIdent(pk) + " = " + literal(value));
            }
        }
        return "UPDATE " + qualifiedTable + " SET " + String.join(", ", sets)
                + " WHERE " + String.join(" AND ", wheres) + ";";
    }

    public static String literal(String value) {
        if (value == null) {
            return "NULL";
        }
        return "'" + value.replace("'", "''") + "'";
    }

    public static String quoteIdent(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        return "`" + name.replace("`", "``") + "`";
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        return indexOfIgnoreCase(values, needle) >= 0;
    }

    private static int indexOfIgnoreCase(List<String> values, String needle) {
        for (int i = 0; i < values.size(); i++) {
            if (values.get(i) != null && values.get(i).equalsIgnoreCase(needle)) {
                return i;
            }
        }
        return -1;
    }
}
