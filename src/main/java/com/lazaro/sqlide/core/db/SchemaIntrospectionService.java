package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Reads the server's structure through {@link DatabaseMetaData} and maps it onto
 * {@link SchemaNode} values.
 *
 * <p>Every method is asynchronous and runs on the {@link JdbcSqlDriver} worker pool.
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

    private final JdbcSqlDriver driver;

    public SchemaIntrospectionService(JdbcSqlDriver driver) {
        this.driver = Objects.requireNonNull(driver, "driver must not be null");
    }

    // ---------------------------------------------------------------- public API

    /** All catalogs (databases) visible to the current user, without their tables. */
    public CompletableFuture<List<SchemaNode>> fetchDatabasesAsync() {
        return supplyAsync(SchemaIntrospectionService::readDatabases);
    }

    /** Tables and views inside {@code catalog}, without their columns. */
    public CompletableFuture<List<SchemaNode>> fetchTablesAsync(String catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        return supplyAsync(connection -> readTables(connection, catalog));
    }

    /** Columns of a single table, ordered by their position in the table. */
    public CompletableFuture<List<SchemaNode>> fetchColumnsAsync(String catalog, String table) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(table, "table must not be null");
        return supplyAsync(connection -> readColumns(connection, catalog, table));
    }

    /**
     * Loads the level below {@code parent}, dispatching on its node type. This is
     * what drives lazy expansion of the schema tree.
     */
    public CompletableFuture<List<SchemaNode>> fetchChildrenAsync(SchemaNode parent) {
        Objects.requireNonNull(parent, "parent must not be null");
        return switch (parent.type()) {
            case DATABASE, SCHEMA -> fetchTablesAsync(parent.name());
            case TABLE, VIEW -> fetchColumnsAsync(
                    Objects.requireNonNullElse(parent.metadata(SchemaNode.META_CATALOG), ""), parent.name());
            case COLUMN -> CompletableFuture.completedFuture(List.of());
        };
    }

    /**
     * Eagerly loads one catalog with all of its tables and columns. Convenient for
     * small schemas; prefer the lazy per-level calls for large servers.
     */
    public CompletableFuture<SchemaNode> fetchDatabaseAsync(String catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        return supplyAsync(connection -> {
            List<SchemaNode> tables = new ArrayList<>();
            for (SchemaNode table : readTables(connection, catalog)) {
                tables.add(table.withChildren(readColumns(connection, catalog, table.name())));
            }
            return new SchemaNode(catalog, NodeType.DATABASE, tables, Map.of());
        });
    }

    // ---------------------------------------------------------------- metadata reads

    private static List<SchemaNode> readDatabases(Connection connection) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();

        List<String> names = new ArrayList<>();
        NodeType type = NodeType.DATABASE;

        try (ResultSet resultSet = metaData.getCatalogs()) {
            while (resultSet.next()) {
                addIfPresent(names, resultSet.getString("TABLE_CAT"));
            }
        }
        if (names.isEmpty()) {
            type = NodeType.SCHEMA;
            try (ResultSet resultSet = metaData.getSchemas()) {
                while (resultSet.next()) {
                    addIfPresent(names, resultSet.getString("TABLE_SCHEM"));
                }
            }
        }

        names.sort(BY_NAME);
        NodeType nodeType = type;
        return names.stream().map(name -> SchemaNode.of(name, nodeType)).toList();
    }

    private static List<SchemaNode> readTables(Connection connection, String catalog) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();

        List<SchemaNode> tables = readTables(metaData, catalog, null);
        if (tables.isEmpty()) {
            tables = readTables(metaData, null, catalog);
        }
        tables.sort(Comparator.comparing(SchemaNode::name, BY_NAME));
        return List.copyOf(tables);
    }

    private static List<SchemaNode> readTables(DatabaseMetaData metaData, String catalog, String schema)
            throws SQLException {
        List<SchemaNode> tables = new ArrayList<>();
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
                String resolvedOwner = owner != null ? owner : firstNonBlank(catalog, schema);
                String resolvedType = Objects.requireNonNullElse(type, "TABLE");

                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put(SchemaNode.META_CATALOG, Objects.requireNonNullElse(resolvedOwner, ""));
                metadata.put(SchemaNode.META_TABLE_TYPE, resolvedType);

                tables.add(SchemaNode.of(name, nodeTypeOf(resolvedType), metadata));
            }
        }
        return tables;
    }

    private static List<SchemaNode> readColumns(Connection connection, String catalog, String table)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();

        List<PositionedColumn> columns = readColumns(metaData, catalog, null, table);
        if (columns.isEmpty()) {
            columns = readColumns(metaData, null, catalog, table);
        }
        columns.sort(Comparator.comparingInt(PositionedColumn::position));
        return columns.stream().map(PositionedColumn::node).toList();
    }

    private static List<PositionedColumn> readColumns(
            DatabaseMetaData metaData, String catalog, String schema, String table) throws SQLException {

        Set<String> primaryKeys = readPrimaryKeys(metaData, catalog, schema, table);

        List<PositionedColumn> columns = new ArrayList<>();
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
                boolean nullable = resultSet.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls;
                int position = resultSet.getInt("ORDINAL_POSITION");

                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put(SchemaNode.META_DATA_TYPE, formatType(
                        resultSet.getString("TYPE_NAME"), resultSet.getInt("COLUMN_SIZE"), decimalDigits));
                metadata.put(SchemaNode.META_NULLABLE, Boolean.toString(nullable));
                metadata.put(SchemaNode.META_PRIMARY_KEY, Boolean.toString(primaryKeys.contains(name)));
                metadata.put(SchemaNode.META_CATALOG, Objects.requireNonNullElse(firstNonBlank(catalog, schema), ""));

                columns.add(new PositionedColumn(SchemaNode.of(name, NodeType.COLUMN, metadata), position));
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

    /**
     * Renders a type the way DDL would spell it. The size is only appended where it
     * carries meaning: {@code VARCHAR(255)} is useful, {@code TIMESTAMP(26)} is noise.
     */
    static String formatType(String typeName, int size, int decimalDigits) {
        String name = Objects.requireNonNullElse(typeName, "UNKNOWN");
        String upper = name.toUpperCase(Locale.ROOT);
        boolean decimal = upper.contains("DECIMAL") || upper.contains("NUMERIC");

        if (decimal && decimalDigits > 0) {
            return "%s(%d,%d)".formatted(name, size, decimalDigits);
        }
        boolean sized = size > 0 && (upper.contains("CHAR") || upper.contains("BINARY") || decimal);
        return sized ? "%s(%d)".formatted(name, size) : name;
    }

    // ---------------------------------------------------------------- plumbing

    /** Ordinal position is only needed while sorting, so it never reaches the node itself. */
    private record PositionedColumn(SchemaNode node, int position) {
    }

    private <T> CompletableFuture<T> supplyAsync(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = driver.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new CompletionException(e);
            }
        }, driver.asyncExecutor());
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private static NodeType nodeTypeOf(String jdbcTableType) {
        return jdbcTableType.toUpperCase(Locale.ROOT).contains("VIEW") ? NodeType.VIEW : NodeType.TABLE;
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
