package com.lazaro.sqlide.core.db;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Reads the server's structure through {@link DatabaseMetaData} and maps it onto
 * the immutable {@link DatabaseNode} / {@link TableNode} / {@link ColumnNode} records.
 *
 * <p>Every method is asynchronous and runs on the {@link DatabaseService} worker pool.
 * Unlike query execution, introspection failures complete the future exceptionally
 * with the underlying {@link SQLException} as the cause.
 *
 * <p>Catalog handling differs by vendor: MySQL exposes databases as catalogs, while
 * PostgreSQL exposes them as schemas. Each lookup tries the catalog position first
 * and falls back to the schema position, so a single code path serves both.
 */
public final class SchemaIntrospectionService {

    /**
     * Table types are not portable: MySQL reports {@code TABLE} where H2 reports
     * {@code BASE TABLE}. Rather than filter by an allow-list that silently hides
     * tables on some servers, ask for everything and drop the non-table entries.
     */
    private static final Set<String> EXCLUDED_TABLE_TYPES =
            Set.of("SYSTEM TABLE", "SYSTEM VIEW", "SYSTEM INDEX", "INDEX", "SEQUENCE", "SYNONYM");

    private static final Set<String> SYSTEM_SCHEMAS =
            Set.of("INFORMATION_SCHEMA", "PG_CATALOG", "PG_TOAST", "SYS", "SYSTEM LOBS");

    private static final Comparator<String> BY_NAME = String.CASE_INSENSITIVE_ORDER;

    private final DatabaseService databaseService;

    public SchemaIntrospectionService(DatabaseService databaseService) {
        this.databaseService = Objects.requireNonNull(databaseService, "databaseService must not be null");
    }

    // ---------------------------------------------------------------- public API

    /** All catalogs (databases) visible to the current user, without their tables. */
    public CompletableFuture<List<DatabaseNode>> fetchDatabasesAsync() {
        return supplyAsync(SchemaIntrospectionService::readDatabases);
    }

    /** Tables and views inside {@code catalog}, without their columns. */
    public CompletableFuture<List<TableNode>> fetchTablesAsync(String catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        return supplyAsync(connection -> readTables(connection, catalog));
    }

    /** Columns of a single table, ordered by their position in the table. */
    public CompletableFuture<List<ColumnNode>> fetchColumnsAsync(String catalog, String table) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(table, "table must not be null");
        return supplyAsync(connection -> readColumns(connection, catalog, table));
    }

    /**
     * Eagerly loads one catalog with all of its tables and columns. Convenient for
     * small schemas; prefer the lazy per-level calls for large servers.
     */
    public CompletableFuture<DatabaseNode> fetchDatabaseAsync(String catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        return supplyAsync(connection -> {
            List<TableNode> tables = new ArrayList<>();
            for (TableNode table : readTables(connection, catalog)) {
                tables.add(table.withColumns(readColumns(connection, catalog, table.name())));
            }
            return new DatabaseNode(catalog, tables);
        });
    }

    // ---------------------------------------------------------------- metadata reads

    private static List<DatabaseNode> readDatabases(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();

        List<String> names = new ArrayList<>();
        try (ResultSet resultSet = metaData.getCatalogs()) {
            while (resultSet.next()) {
                addIfPresent(names, resultSet.getString("TABLE_CAT"));
            }
        }
        if (names.isEmpty()) {
            try (ResultSet resultSet = metaData.getSchemas()) {
                while (resultSet.next()) {
                    addIfPresent(names, resultSet.getString("TABLE_SCHEM"));
                }
            }
        }

        names.sort(BY_NAME);
        return names.stream().map(DatabaseNode::of).toList();
    }

    private static List<TableNode> readTables(Connection connection, String catalog) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();

        List<TableNode> tables = readTables(metaData, catalog, null);
        if (tables.isEmpty()) {
            tables = readTables(metaData, null, catalog);
        }
        tables.sort(Comparator.comparing(TableNode::name, BY_NAME));
        return List.copyOf(tables);
    }

    private static List<TableNode> readTables(DatabaseMetaData metaData, String catalog, String schema)
            throws SQLException {
        List<TableNode> tables = new ArrayList<>();
        try (ResultSet resultSet = metaData.getTables(catalog, schema, "%", null)) {
            while (resultSet.next()) {
                String name = resultSet.getString("TABLE_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                String tableSchema = resultSet.getString("TABLE_SCHEM");
                String type = resultSet.getString("TABLE_TYPE");
                if (isSystemObject(tableSchema, type)) {
                    continue;
                }
                String owner = firstNonBlank(resultSet.getString("TABLE_CAT"), tableSchema);
                tables.add(new TableNode(
                        owner != null ? owner : firstNonBlank(catalog, schema),
                        name,
                        type,
                        List.of()));
            }
        }
        return tables;
    }

    private static List<ColumnNode> readColumns(Connection connection, String catalog, String table)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();

        List<ColumnNode> columns = readColumns(metaData, catalog, null, table);
        if (columns.isEmpty()) {
            columns = readColumns(metaData, null, catalog, table);
        }
        columns.sort(Comparator.comparingInt(ColumnNode::position));
        return List.copyOf(columns);
    }

    private static List<ColumnNode> readColumns(DatabaseMetaData metaData, String catalog, String schema, String table)
            throws SQLException {
        Set<String> primaryKeys = readPrimaryKeys(metaData, catalog, schema, table);

        List<ColumnNode> columns = new ArrayList<>();
        try (ResultSet resultSet = metaData.getColumns(catalog, schema, table, "%")) {
            while (resultSet.next()) {
                String name = resultSet.getString("COLUMN_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                int decimalDigits = resultSet.getInt("DECIMAL_DIGITS");
                if (resultSet.wasNull()) {
                    decimalDigits = 0;
                }
                columns.add(new ColumnNode(
                        name,
                        resultSet.getString("TYPE_NAME"),
                        resultSet.getInt("COLUMN_SIZE"),
                        decimalDigits,
                        resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        resultSet.getInt("ORDINAL_POSITION"),
                        primaryKeys.contains(name)));
            }
        }
        return columns;
    }

    /** Primary keys are a nice-to-have: some drivers or permission sets refuse this call. */
    private static Set<String> readPrimaryKeys(DatabaseMetaData metaData, String catalog, String schema, String table) {
        Set<String> keys = new HashSet<>();
        try (ResultSet resultSet = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                addIfPresent(keys, resultSet.getString("COLUMN_NAME"));
            }
        } catch (SQLException ignored) {
            return Set.of();
        }
        return keys;
    }

    // ---------------------------------------------------------------- plumbing

    private <T> CompletableFuture<T> supplyAsync(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = databaseService.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, databaseService.asyncExecutor());
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private static boolean isSystemObject(String schema, String type) {
        if (type != null && EXCLUDED_TABLE_TYPES.contains(type.toUpperCase(Locale.ROOT))) {
            return true;
        }
        return schema != null && SYSTEM_SCHEMAS.contains(schema.toUpperCase(Locale.ROOT));
    }

    private static void addIfPresent(java.util.Collection<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        return second != null && !second.isBlank() ? second : null;
    }
}
