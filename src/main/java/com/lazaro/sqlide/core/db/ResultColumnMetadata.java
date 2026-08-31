package com.lazaro.sqlide.core.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds {@link ResultColumn} rows from JDBC {@link ResultSetMetaData}, optionally
 * enriching them with primary- and foreign-key flags from {@link DatabaseMetaData}.
 *
 * <p>Key lookup is per distinct table (not per column) and is skipped when the
 * driver does not report a table name — expressions, literals, and many aliases.
 */
public final class ResultColumnMetadata {

    /** Wide joins should not trigger dozens of {@code getPrimaryKeys} round-trips. */
    static final int KEY_LOOKUP_TABLE_LIMIT = 32;

    private ResultColumnMetadata() {
    }

    public static List<ResultColumn> fromResultSet(ResultSet resultSet) throws SQLException {
        Objects.requireNonNull(resultSet, "resultSet");
        ResultSetMetaData metaData = resultSet.getMetaData();
        int columnCount = metaData.getColumnCount();
        Map<TableRef, KeyFlags> keys = lookupKeys(connectionOf(resultSet), metaData, columnCount);

        List<ResultColumn> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            String label = columnLabel(metaData, i);
            String typeName = safeTypeName(metaData, i);
            int sqlType = safeSqlType(metaData, i);
            TableRef table = tableRef(metaData, i);
            KeyFlags flags = table == null ? KeyFlags.NONE : keys.getOrDefault(table, KeyFlags.NONE);
            String physical = safeColumnName(metaData, i);
            boolean pk = flags.isPrimary(physical) || flags.isPrimary(label);
            boolean fk = flags.isForeign(physical) || flags.isForeign(label);
            columns.add(new ResultColumn(label, typeName, sqlType, pk, fk));
        }
        return List.copyOf(columns);
    }

    private static Map<TableRef, KeyFlags> lookupKeys(
            Connection connection, ResultSetMetaData metaData, int columnCount) {
        if (connection == null) {
            return Map.of();
        }
        Map<TableRef, KeyFlags> keys = new LinkedHashMap<>();
        for (int i = 1; i <= columnCount; i++) {
            TableRef table = tableRef(metaData, i);
            if (table != null) {
                keys.putIfAbsent(table, null);
            }
            if (keys.size() > KEY_LOOKUP_TABLE_LIMIT) {
                return Map.of();
            }
        }
        if (keys.isEmpty()) {
            return Map.of();
        }
        DatabaseMetaData dbMeta;
        try {
            dbMeta = connection.getMetaData();
        } catch (SQLException ignored) {
            return Map.of();
        }
        Map<TableRef, KeyFlags> resolved = new HashMap<>();
        for (TableRef table : keys.keySet()) {
            resolved.put(table, readKeys(dbMeta, table));
        }
        return resolved;
    }

    private static KeyFlags readKeys(DatabaseMetaData dbMeta, TableRef table) {
        KeyFlags flags = readKeys(dbMeta, table.catalog(), table.schema(), table.table());
        if (flags == KeyFlags.NONE && table.catalog() != null) {
            flags = readKeys(dbMeta, null, table.schema(), table.table());
        }
        if (flags == KeyFlags.NONE) {
            flags = readKeys(dbMeta, null, null, table.table());
        }
        return flags;
    }

    private static KeyFlags readKeys(DatabaseMetaData dbMeta, String catalog, String schema, String table) {
        Set<String> primary = new HashSet<>();
        Set<String> foreign = new HashSet<>();
        try (ResultSet resultSet = dbMeta.getPrimaryKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                addName(primary, resultSet.getString("COLUMN_NAME"));
            }
        } catch (SQLException ignored) {
            // Driver or permission; type badges still render.
        }
        try (ResultSet resultSet = dbMeta.getImportedKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                addName(foreign, resultSet.getString("FKCOLUMN_NAME"));
            }
        } catch (SQLException ignored) {
            // Same: FK icons are best-effort.
        }
        if (primary.isEmpty() && foreign.isEmpty()) {
            return KeyFlags.NONE;
        }
        return new KeyFlags(primary, foreign);
    }

    private static void addName(Set<String> names, String column) {
        if (column == null || column.isBlank()) {
            return;
        }
        names.add(column);
        names.add(column.toLowerCase(Locale.ROOT));
    }

    private static Connection connectionOf(ResultSet resultSet) {
        try {
            var statement = resultSet.getStatement();
            return statement == null ? null : statement.getConnection();
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static TableRef tableRef(ResultSetMetaData metaData, int index) {
        String table = blankToNull(safe(() -> metaData.getTableName(index)));
        if (table == null) {
            return null;
        }
        String catalog = blankToNull(safe(() -> metaData.getCatalogName(index)));
        String schema = blankToNull(safe(() -> metaData.getSchemaName(index)));
        return new TableRef(catalog, schema, table);
    }

    private static String columnLabel(ResultSetMetaData metaData, int index) throws SQLException {
        String label = metaData.getColumnLabel(index);
        if (label == null || label.isEmpty()) {
            label = metaData.getColumnName(index);
        }
        return label == null ? "" : label;
    }

    private static String safeColumnName(ResultSetMetaData metaData, int index) {
        return Objects.requireNonNullElse(safe(() -> metaData.getColumnName(index)), "");
    }

    private static String safeTypeName(ResultSetMetaData metaData, int index) {
        return Objects.requireNonNullElse(safe(() -> metaData.getColumnTypeName(index)), "");
    }

    private static int safeSqlType(ResultSetMetaData metaData, int index) {
        Integer type = safe(() -> metaData.getColumnType(index));
        return type == null ? Types.OTHER : type;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static <T> T safe(SqlSupplier<T> supplier) {
        try {
            return supplier.get();
        } catch (SQLException ignored) {
            return null;
        }
    }

    @FunctionalInterface
    private interface SqlSupplier<T> {
        T get() throws SQLException;
    }

    private record TableRef(String catalog, String schema, String table) {
    }

    private record KeyFlags(Set<String> primary, Set<String> foreign) {
        static final KeyFlags NONE = new KeyFlags(Set.of(), Set.of());

        boolean isPrimary(String name) {
            return contains(primary, name);
        }

        boolean isForeign(String name) {
            return contains(foreign, name);
        }

        private static boolean contains(Set<String> names, String value) {
            if (value == null || value.isBlank() || names.isEmpty()) {
                return false;
            }
            return names.contains(value) || names.contains(value.toLowerCase(Locale.ROOT));
        }
    }
}
