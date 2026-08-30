package com.lazaro.sqlide.core.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds {@code UPDATE} / {@code INSERT} / {@code DELETE} statements from edited
 * result rows keyed by primary-key columns.
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

        String where = whereClause(columnNames, primaryKeyColumns, originalRow);
        if (where == null) {
            return null;
        }
        return "UPDATE " + qualifiedTable + " SET " + String.join(", ", sets) + " WHERE " + where + ";";
    }

    /**
     * @return INSERT statement, or {@code null} when inputs are invalid
     */
    public static String insert(String qualifiedTable, List<String> columnNames, List<String> values) {
        Objects.requireNonNull(qualifiedTable, "qualifiedTable");
        if (columnNames == null || values == null || columnNames.isEmpty()
                || columnNames.size() != values.size()) {
            return null;
        }
        List<String> cols = new ArrayList<>(columnNames.size());
        List<String> vals = new ArrayList<>(values.size());
        for (int i = 0; i < columnNames.size(); i++) {
            cols.add(quoteIdent(columnNames.get(i)));
            vals.add(literal(values.get(i)));
        }
        return "INSERT INTO " + qualifiedTable + " (" + String.join(", ", cols) + ") VALUES ("
                + String.join(", ", vals) + ");";
    }

    /**
     * @return DELETE statement keyed by PK values from {@code originalRow}, or {@code null}
     */
    public static String delete(
            String qualifiedTable,
            List<String> columnNames,
            List<String> primaryKeyColumns,
            List<String> originalRow) {
        Objects.requireNonNull(qualifiedTable, "qualifiedTable");
        if (columnNames == null || primaryKeyColumns == null || primaryKeyColumns.isEmpty()
                || originalRow == null) {
            return null;
        }
        String where = whereClause(columnNames, primaryKeyColumns, originalRow);
        if (where == null) {
            return null;
        }
        return "DELETE FROM " + qualifiedTable + " WHERE " + where + ";";
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

    private static String whereClause(
            List<String> columnNames, List<String> primaryKeyColumns, List<String> originalRow) {
        List<String> wheres = new ArrayList<>();
        for (String pk : primaryKeyColumns) {
            int index = indexOfIgnoreCase(columnNames, pk);
            if (index < 0 || index >= originalRow.size()) {
                return null;
            }
            String value = originalRow.get(index);
            if (value == null) {
                wheres.add(quoteIdent(pk) + " IS NULL");
            } else {
                wheres.add(quoteIdent(pk) + " = " + literal(value));
            }
        }
        return String.join(" AND ", wheres);
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
