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
     *
     * <p>Catalogs expand into logical {@code tables}/{@code views} folders; tables
     * expand into {@code columns}/{@code keys}/{@code indexes} folders. The full-schema
     * cache path stays flat (table → columns) and does not use these folders.
     */
    public CompletableFuture<List<SchemaNode>> fetchChildrenAsync(SchemaNode parent) {
        Objects.requireNonNull(parent, "parent must not be null");
        return switch (parent.type()) {
            case DATABASE, SCHEMA -> supplyAsync(connection ->
                    catalogFolders(connection, parent.name()));
            case FOLDER -> supplyAsync(connection -> folderChildren(connection, parent));
            case TABLE, VIEW -> supplyAsync(connection -> tableFolders(connection, parent));
            case COLUMN, KEY, INDEX, DATA_SOURCE -> CompletableFuture.completedFuture(List.of());
        };
    }

    private static List<SchemaNode> catalogFolders(Connection connection, String catalog) throws SQLException {
        List<SchemaNode> all = readTables(connection, catalog);
        List<SchemaNode> tables = all.stream().filter(node -> node.type() == NodeType.TABLE).toList();
        List<SchemaNode> views = all.stream().filter(node -> node.type() == NodeType.VIEW).toList();
        Map<String, String> catalogMeta = Map.of(SchemaNode.META_CATALOG, catalog);
        List<SchemaNode> folders = new ArrayList<>(2);
        folders.add(SchemaNode.folder(SchemaNode.FOLDER_TABLES, SchemaNode.FOLDER_TABLES, tables.size(), catalogMeta)
                .withChildren(tables));
        if (!views.isEmpty()) {
            folders.add(SchemaNode.folder(SchemaNode.FOLDER_VIEWS, SchemaNode.FOLDER_VIEWS, views.size(), catalogMeta)
                    .withChildren(views));
        }
        return List.copyOf(folders);
    }

    private static List<SchemaNode> folderChildren(Connection connection, SchemaNode folder) throws SQLException {
        String kind = Objects.requireNonNullElse(folder.folderKind(), "");
        String catalog = Objects.requireNonNullElse(folder.metadata(SchemaNode.META_CATALOG), "");
        String table = folder.metadata(SchemaNode.META_TABLE);
        return switch (kind) {
            case SchemaNode.FOLDER_TABLES -> readTables(connection, catalog).stream()
                    .filter(node -> node.type() == NodeType.TABLE)
                    .toList();
            case SchemaNode.FOLDER_VIEWS -> readTables(connection, catalog).stream()
                    .filter(node -> node.type() == NodeType.VIEW)
                    .toList();
            case SchemaNode.FOLDER_COLUMNS -> readColumns(connection, catalog, table);
            case SchemaNode.FOLDER_KEYS -> readKeyNodes(connection, catalog, table);
            case SchemaNode.FOLDER_INDEXES -> readIndexNodes(connection, catalog, table);
            default -> List.of();
        };
    }

    private static List<SchemaNode> tableFolders(Connection connection, SchemaNode table) throws SQLException {
        String catalog = Objects.requireNonNullElse(table.metadata(SchemaNode.META_CATALOG), "");
        String tableName = table.name();
        List<SchemaNode> columns = readColumns(connection, catalog, tableName);
        List<SchemaNode> keys = readKeyNodes(connection, catalog, tableName);
        List<SchemaNode> indexes = readIndexNodes(connection, catalog, tableName);

        Map<String, String> shared = new LinkedHashMap<>();
        shared.put(SchemaNode.META_CATALOG, catalog);
        shared.put(SchemaNode.META_TABLE, tableName);

        return List.of(
                SchemaNode.folder(SchemaNode.FOLDER_COLUMNS, SchemaNode.FOLDER_COLUMNS, columns.size(), shared)
                        .withChildren(columns),
                SchemaNode.folder(SchemaNode.FOLDER_KEYS, SchemaNode.FOLDER_KEYS, keys.size(), shared)
                        .withChildren(keys),
                SchemaNode.folder(SchemaNode.FOLDER_INDEXES, SchemaNode.FOLDER_INDEXES, indexes.size(), shared)
                        .withChildren(indexes));
    }

    private static List<SchemaNode> readKeyNodes(Connection connection, String catalog, String table)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        List<SchemaNode> keys = new ArrayList<>();

        List<String> pkColumns = readPrimaryKeyColumns(metaData, catalog, null, table);
        if (pkColumns.isEmpty()) {
            pkColumns = readPrimaryKeyColumns(metaData, null, catalog, table);
        }
        Map<String, String> shared = Map.of(
                SchemaNode.META_CATALOG, catalog,
                SchemaNode.META_TABLE, table);
        if (!pkColumns.isEmpty()) {
            keys.add(SchemaNode.key("PRIMARY", "PRIMARY", pkColumns, shared));
        }

        List<SchemaMetadataCodec.ForeignKey> foreignKeys = readForeignKeys(metaData, catalog, null, table);
        if (foreignKeys.isEmpty()) {
            foreignKeys = readForeignKeys(metaData, null, catalog, table);
        }
        for (SchemaMetadataCodec.ForeignKey fk : foreignKeys) {
            keys.add(SchemaNode.key(fk.name(), "FOREIGN", List.of(fk.fkColumn()), shared));
        }
        return List.copyOf(keys);
    }

    private static List<SchemaNode> readIndexNodes(Connection connection, String catalog, String table)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        List<SchemaMetadataCodec.IndexInfo> indexes = readIndexes(metaData, catalog, null, table);
        if (indexes.isEmpty()) {
            indexes = readIndexes(metaData, null, catalog, table);
        }
        Map<String, String> shared = Map.of(
                SchemaNode.META_CATALOG, catalog,
                SchemaNode.META_TABLE, table);
        List<SchemaNode> nodes = new ArrayList<>(indexes.size());
        for (SchemaMetadataCodec.IndexInfo index : indexes) {
            nodes.add(SchemaNode.index(index.name(), index.unique(), index.columns(), shared));
        }
        return List.copyOf(nodes);
    }

    /**
     * One table/view with columns as children and indexes, foreign keys and generated
     * DDL packed into its metadata map — the payload the object viewer and cache want.
     */
    public CompletableFuture<SchemaNode> fetchTableDetailsAsync(String catalog, String table) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        Objects.requireNonNull(table, "table must not be null");
        return supplyAsync(connection -> readTableDetails(connection, catalog, table));
    }

    /**
     * Eagerly loads every catalog with every table fully detailed. Used once per
     * connection (and on Refresh) so autocomplete never hits the network per keystroke.
     */
    public CompletableFuture<List<SchemaNode>> fetchFullSchemaAsync() {
        return supplyAsync(connection -> {
            List<SchemaNode> databases = readDatabases(connection);
            List<SchemaNode> loaded = new ArrayList<>(databases.size());
            for (SchemaNode database : databases) {
                List<SchemaNode> tables = readTables(connection, database.name());
                List<SchemaNode> detailed = new ArrayList<>(tables.size());
                for (SchemaNode table : tables) {
                    detailed.add(readTableDetails(connection, database.name(), table.name(), table));
                }
                loaded.add(database.withChildren(detailed));
            }
            return List.copyOf(loaded);
        });
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
                tables.add(readTableDetails(connection, catalog, table.name(), table));
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

    private static SchemaNode readTableDetails(Connection connection, String catalog, String table)
            throws SQLException {
        List<SchemaNode> tables = readTables(connection, catalog);
        SchemaNode base = tables.stream()
                .filter(node -> node.name().equalsIgnoreCase(table))
                .findFirst()
                .orElse(SchemaNode.of(table, NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, catalog)));
        return readTableDetails(connection, catalog, table, base);
    }

    private static SchemaNode readTableDetails(
            Connection connection, String catalog, String table, SchemaNode base) throws SQLException {

        DatabaseMetaData metaData = connection.getMetaData();
        List<SchemaNode> columns = readColumns(connection, catalog, table);

        List<SchemaMetadataCodec.ForeignKey> foreignKeys = readForeignKeys(metaData, catalog, null, table);
        if (foreignKeys.isEmpty()) {
            foreignKeys = readForeignKeys(metaData, null, catalog, table);
        }

        List<SchemaMetadataCodec.IndexInfo> indexes = readIndexes(metaData, catalog, null, table);
        if (indexes.isEmpty()) {
            indexes = readIndexes(metaData, null, catalog, table);
        }

        Map<String, String> metadata = new LinkedHashMap<>(base.metadata());
        metadata.put(SchemaNode.META_CATALOG, Objects.requireNonNullElse(
                firstNonBlank(base.metadata(SchemaNode.META_CATALOG), catalog), ""));
        String fkEncoded = SchemaMetadataCodec.encodeForeignKeys(foreignKeys);
        String indexEncoded = SchemaMetadataCodec.encodeIndexes(indexes);
        if (!fkEncoded.isEmpty()) {
            metadata.put(SchemaNode.META_FOREIGN_KEYS, fkEncoded);
        }
        if (!indexEncoded.isEmpty()) {
            metadata.put(SchemaNode.META_INDEXES, indexEncoded);
        }
        metadata.put(SchemaNode.META_DDL, generateDdl(base.type(), table, columns, foreignKeys, indexes));

        return new SchemaNode(base.name(), base.type(), columns, metadata);
    }

    private static List<SchemaMetadataCodec.ForeignKey> readForeignKeys(
            DatabaseMetaData metaData, String catalog, String schema, String table) {
        List<SchemaMetadataCodec.ForeignKey> keys = new ArrayList<>();
        try (ResultSet resultSet = metaData.getImportedKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                String fkColumn = resultSet.getString("FKCOLUMN_NAME");
                String pkTable = resultSet.getString("PKTABLE_NAME");
                String pkColumn = resultSet.getString("PKCOLUMN_NAME");
                if (fkColumn == null || pkTable == null || pkColumn == null) {
                    continue;
                }
                String name = Objects.requireNonNullElse(resultSet.getString("FK_NAME"), fkColumn + "_fk");
                keys.add(new SchemaMetadataCodec.ForeignKey(name, fkColumn, pkTable, pkColumn));
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return keys;
    }

    private static List<SchemaMetadataCodec.IndexInfo> readIndexes(
            DatabaseMetaData metaData, String catalog, String schema, String table) {
        Map<String, SchemaMetadataCodec.IndexInfo> byName = new LinkedHashMap<>();
        try (ResultSet resultSet = metaData.getIndexInfo(catalog, schema, table, false, false)) {
            while (resultSet.next()) {
                String name = resultSet.getString("INDEX_NAME");
                String column = resultSet.getString("COLUMN_NAME");
                if (name == null || name.isBlank() || column == null || column.isBlank()) {
                    continue;
                }
                boolean unique = !resultSet.getBoolean("NON_UNIQUE");
                SchemaMetadataCodec.IndexInfo existing = byName.get(name);
                if (existing == null) {
                    byName.put(name, new SchemaMetadataCodec.IndexInfo(name, unique, List.of(column)));
                } else {
                    List<String> columns = new ArrayList<>(existing.columns());
                    columns.add(column);
                    byName.put(name, new SchemaMetadataCodec.IndexInfo(name, existing.unique(), columns));
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return List.copyOf(byName.values());
    }

    /** Builds a readable CREATE statement from JDBC metadata — approximate, not a dump. */
    static String generateDdl(
            NodeType type,
            String table,
            List<SchemaNode> columns,
            List<SchemaMetadataCodec.ForeignKey> foreignKeys,
            List<SchemaMetadataCodec.IndexInfo> indexes) {

        StringBuilder ddl = new StringBuilder();
        if (type == NodeType.VIEW) {
            ddl.append("CREATE VIEW ").append(table).append(" AS\n")
                    .append("  -- definition not available via JDBC DatabaseMetaData\n")
                    .append("  SELECT ");
            if (columns.isEmpty()) {
                ddl.append("*");
            } else {
                ddl.append(String.join(", ", columns.stream().map(SchemaNode::name).toList()));
            }
            ddl.append(" FROM ").append(table).append(";\n");
            return ddl.toString();
        }

        ddl.append("CREATE TABLE ").append(table).append(" (\n");
        List<String> lines = new ArrayList<>();
        List<String> primaryKey = new ArrayList<>();
        for (SchemaNode column : columns) {
            StringBuilder line = new StringBuilder("  ").append(column.name()).append(' ')
                    .append(Objects.requireNonNullElse(column.metadata(SchemaNode.META_DATA_TYPE), "UNKNOWN"));
            if (!column.metadataFlag(SchemaNode.META_NULLABLE)) {
                line.append(" NOT NULL");
            }
            if (column.metadataFlag(SchemaNode.META_PRIMARY_KEY)) {
                primaryKey.add(column.name());
            }
            lines.add(line.toString());
        }
        if (!primaryKey.isEmpty()) {
            lines.add("  PRIMARY KEY (" + String.join(", ", primaryKey) + ")");
        }
        for (SchemaMetadataCodec.ForeignKey fk : foreignKeys) {
            lines.add("  CONSTRAINT " + fk.name()
                    + " FOREIGN KEY (" + fk.fkColumn() + ")"
                    + " REFERENCES " + fk.pkTable()
                    + " (" + fk.pkColumn() + ")");
        }
        ddl.append(String.join(",\n", lines)).append("\n);\n");

        for (SchemaMetadataCodec.IndexInfo index : indexes) {
            if ("PRIMARY".equalsIgnoreCase(index.name()) || index.columns().equals(primaryKey)) {
                continue;
            }
            ddl.append(index.unique() ? "CREATE UNIQUE INDEX " : "CREATE INDEX ")
                    .append(index.name())
                    .append(" ON ").append(table).append(" (")
                    .append(String.join(", ", index.columns()))
                    .append(");\n");
        }
        return ddl.toString();
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
        return new HashSet<>(readPrimaryKeyColumns(metaData, catalog, schema, table));
    }

    /** Ordered primary-key columns via {@link DatabaseMetaData#getPrimaryKeys}. */
    private static List<String> readPrimaryKeyColumns(
            DatabaseMetaData metaData, String catalog, String schema, String table) {
        Map<Integer, String> byPosition = new LinkedHashMap<>();
        try (ResultSet resultSet = metaData.getPrimaryKeys(catalog, schema, table)) {
            while (resultSet.next()) {
                String column = resultSet.getString("COLUMN_NAME");
                if (column == null || column.isBlank()) {
                    continue;
                }
                int position = resultSet.getInt("KEY_SEQ");
                byPosition.put(position, column);
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return List.copyOf(byPosition.values());
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
