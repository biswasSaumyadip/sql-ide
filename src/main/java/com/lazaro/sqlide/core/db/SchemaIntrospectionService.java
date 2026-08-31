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
            Set.of("INFORMATION_SCHEMA", "PG_CATALOG", "PG_TOAST", "SYS", "SYSTEM LOBS",
                    "MYSQL", "PERFORMANCE_SCHEMA");

    private static final Comparator<String> BY_NAME = String.CASE_INSENSITIVE_ORDER;

    /**
     * Per-table {@code getImportedKeys}/{@code getIndexInfo}/{@code getPrimaryKeys}
     * is what blows up on large catalogs. Below this size we still enrich fully;
     * above it, batched columns are enough for autocomplete.
     */
    private static final int DETAILED_KEYS_TABLE_LIMIT = 200;

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
            case COLUMN, KEY, INDEX, PROCEDURE, DATA_SOURCE, REDIS_KEY -> CompletableFuture.completedFuture(List.of());
        };
    }

    private static List<SchemaNode> catalogFolders(Connection connection, String catalog) throws SQLException {
        List<SchemaNode> all = readTables(connection, catalog);
        List<SchemaNode> tables = all.stream().filter(node -> node.type() == NodeType.TABLE).toList();
        List<SchemaNode> views = all.stream().filter(node -> node.type() == NodeType.VIEW).toList();
        List<SchemaNode> procedures = readRoutines(connection, catalog, false);
        Map<String, String> catalogMeta = Map.of(SchemaNode.META_CATALOG, catalog);
        List<SchemaNode> folders = new ArrayList<>(3);
        folders.add(SchemaNode.folder(SchemaNode.FOLDER_TABLES, SchemaNode.FOLDER_TABLES, tables.size(), catalogMeta)
                .withChildren(tables));
        if (!views.isEmpty()) {
            folders.add(SchemaNode.folder(SchemaNode.FOLDER_VIEWS, SchemaNode.FOLDER_VIEWS, views.size(), catalogMeta)
                    .withChildren(views));
        }
        if (!procedures.isEmpty()) {
            folders.add(SchemaNode.folder(
                            SchemaNode.FOLDER_PROCEDURES, SchemaNode.FOLDER_PROCEDURES, procedures.size(), catalogMeta)
                    .withChildren(procedures));
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
            case SchemaNode.FOLDER_PROCEDURES -> readRoutines(connection, catalog, false);
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
     * Catalogs with table / view / routine <em>names</em> only — no columns, indexes
     * or {@code SHOW CREATE} bodies. Cheap enough for 1000+ objects so autocomplete
     * can offer table names before the detailed pass finishes.
     */
    public CompletableFuture<List<SchemaNode>> fetchSchemaOutlineAsync() {
        return fetchSchemaOutlineAsync(null);
    }

    /**
     * Like {@link #fetchSchemaOutlineAsync()}, but loads {@code preferredCatalog}
     * first and leaves other catalogs as empty shells.
     */
    public CompletableFuture<List<SchemaNode>> fetchSchemaOutlineAsync(String preferredCatalog) {
        return supplyAsync(connection -> readSchemaOutline(connection, preferredCatalog));
    }

    /**
     * Loads catalogs, then attaches columns (batched) plus indexes / foreign keys
     * per table. Routine bodies are left unloaded; fetch them on demand.
     * Used once per connection (and on Refresh) so autocomplete never hits the
     * network per keystroke. A failure on one table keeps the name-only node.
     */
    public CompletableFuture<List<SchemaNode>> fetchFullSchemaAsync() {
        return fetchFullSchemaAsync(null);
    }

    /**
     * Like {@link #fetchFullSchemaAsync()}, but enriches {@code preferredCatalog}
     * only. Other catalogs stay as name-only shells for {@link #fetchSecondarySchemaAsync}.
     */
    public CompletableFuture<List<SchemaNode>> fetchFullSchemaAsync(String preferredCatalog) {
        return supplyAsync(connection -> readFullSchema(connection, preferredCatalog));
    }

    /**
     * Tables / columns for every catalog except {@code preferredCatalog}. System
     * catalogs stay name-only. Empty when {@code preferredCatalog} is blank.
     */
    public CompletableFuture<List<SchemaNode>> fetchSecondarySchemaAsync(String preferredCatalog) {
        return supplyAsync(connection -> readSecondarySchema(connection, preferredCatalog));
    }

    /**
     * Eagerly loads one catalog with all of its tables and columns. Convenient for
     * small schemas; prefer the lazy per-level calls for large servers.
     */
    public CompletableFuture<SchemaNode> fetchDatabaseAsync(String catalog) {
        Objects.requireNonNull(catalog, "catalog must not be null");
        return supplyAsync(connection -> {
            List<SchemaNode> children = new ArrayList<>();
            children.addAll(readTablesSafe(connection, catalog));
            children.addAll(readRoutines(connection, catalog, false));
            SchemaNode database = new SchemaNode(catalog, NodeType.DATABASE, children, Map.of());
            return enrichCatalog(connection, database);
        });
    }

    /**
     * {@code SHOW CREATE} / {@code INFORMATION_SCHEMA} body for one routine. Used
     * by the object viewer so bulk schema loads do not issue one query per procedure.
     */
    public CompletableFuture<SchemaNode> fetchRoutineDetailsAsync(String catalog, String name) {
        Objects.requireNonNull(name, "name must not be null");
        String owner = Objects.requireNonNullElse(catalog, "");
        return supplyAsync(connection -> {
            Map<String, String> metadata = new LinkedHashMap<>();
            metadata.put(SchemaNode.META_CATALOG, owner);
            metadata.put(SchemaNode.META_ROUTINE_KIND, SchemaNode.ROUTINE_PROCEDURE);
            return withRoutineDdl(connection, catalog, SchemaNode.of(name, NodeType.PROCEDURE, metadata));
        });
    }

    // ---------------------------------------------------------------- outline / enrich

    private static List<SchemaNode> readSchemaOutline(Connection connection, String preferredCatalog)
            throws SQLException {
        List<SchemaNode> databases = readDatabases(connection);
        SchemaNode preferred = findPreferred(databases, preferredCatalog);
        List<SchemaNode> loaded = new ArrayList<>(databases.size());
        if (preferred == null) {
            for (SchemaNode database : databases) {
                loaded.add(loadOutlineChildren(connection, database));
            }
            return List.copyOf(loaded);
        }
        loaded.add(loadOutlineChildren(connection, preferred));
        for (SchemaNode database : databases) {
            if (!database.name().equalsIgnoreCase(preferred.name())) {
                loaded.add(database);
            }
        }
        return List.copyOf(loaded);
    }

    private static List<SchemaNode> readFullSchema(Connection connection, String preferredCatalog)
            throws SQLException {
        List<SchemaNode> outline = readSchemaOutline(connection, preferredCatalog);
        SchemaNode preferred = findPreferred(outline, preferredCatalog);
        List<SchemaNode> loaded = new ArrayList<>(outline.size());
        for (SchemaNode database : outline) {
            if (preferred != null && !database.name().equalsIgnoreCase(preferred.name())) {
                loaded.add(database);
            } else {
                loaded.add(enrichCatalog(connection, database));
            }
        }
        return List.copyOf(loaded);
    }

    private static List<SchemaNode> readSecondarySchema(Connection connection, String preferredCatalog)
            throws SQLException {
        if (preferredCatalog == null || preferredCatalog.isBlank()) {
            return List.of();
        }
        List<SchemaNode> databases = readDatabases(connection);
        List<SchemaNode> loaded = new ArrayList<>();
        for (SchemaNode database : databases) {
            if (database.name().equalsIgnoreCase(preferredCatalog) || isSystemCatalogName(database.name())) {
                continue;
            }
            SchemaNode outlined = loadOutlineChildren(connection, database);
            loaded.add(enrichCatalog(connection, outlined));
        }
        return List.copyOf(loaded);
    }

    private static SchemaNode loadOutlineChildren(Connection connection, SchemaNode database)
            throws SQLException {
        List<SchemaNode> children = new ArrayList<>();
        children.addAll(readTablesSafe(connection, database.name()));
        children.addAll(readRoutines(connection, database.name(), false));
        return database.withChildren(children);
    }

    private static SchemaNode findPreferred(List<SchemaNode> databases, String preferredCatalog) {
        if (preferredCatalog == null || preferredCatalog.isBlank() || databases == null) {
            return null;
        }
        for (SchemaNode database : databases) {
            if (database.name().equalsIgnoreCase(preferredCatalog)) {
                return database;
            }
        }
        return null;
    }

    private static List<SchemaNode> readTablesSafe(Connection connection, String catalog) {
        try {
            return readTables(connection, catalog);
        } catch (SQLException ignored) {
            return List.of();
        }
    }

    /**
     * Attaches columns (one {@code getColumns} per catalog) plus per-table keys /
     * indexes. System catalogs stay name-only. A single table's metadata failure
     * does not drop it from autocomplete.
     */
    private static SchemaNode enrichCatalog(Connection connection, SchemaNode database) {
        String catalog = database.name();
        if (isSystemCatalogName(catalog)) {
            return database;
        }
        int tableCount = 0;
        for (SchemaNode child : database.children()) {
            if (child.type() == NodeType.TABLE || child.type() == NodeType.VIEW) {
                tableCount++;
            }
        }
        boolean loadKeys = tableCount <= DETAILED_KEYS_TABLE_LIMIT;
        Map<String, List<SchemaNode>> columnsByTable = readAllColumnsGrouped(connection, catalog);
        List<SchemaNode> children = new ArrayList<>(database.children().size());
        for (SchemaNode child : database.children()) {
            if (child.type() == NodeType.TABLE || child.type() == NodeType.VIEW) {
                try {
                    List<SchemaNode> columns = columnsByTable.getOrDefault(
                            child.name().toLowerCase(Locale.ROOT), List.of());
                    if (loadKeys) {
                        children.add(readTableDetails(connection, catalog, child.name(), child, columns));
                    } else if (!columns.isEmpty()) {
                        children.add(withBatchedColumns(child, columns, catalog));
                    } else {
                        children.add(child);
                    }
                } catch (SQLException ignored) {
                    children.add(child);
                }
            } else {
                children.add(child);
            }
        }
        return database.withChildren(children);
    }

    private static SchemaNode withBatchedColumns(SchemaNode base, List<SchemaNode> columns, String catalog) {
        Map<String, String> metadata = new LinkedHashMap<>(base.metadata());
        metadata.put(SchemaNode.META_CATALOG, Objects.requireNonNullElse(
                firstNonBlank(base.metadata(SchemaNode.META_CATALOG), catalog), ""));
        metadata.put(SchemaNode.META_DDL, generateDdl(base.type(), base.name(), columns, List.of(), List.of()));
        return new SchemaNode(base.name(), base.type(), columns, metadata);
    }

    private static boolean isSystemCatalogName(String name) {
        return name != null && SYSTEM_SCHEMAS.contains(name.toUpperCase(Locale.ROOT));
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

    /**
     * Stored procedures and functions for {@code catalog}. Drivers that do not
     * implement {@code getProcedures}/{@code getFunctions} return an empty list.
     * {@code includeDdl} is false for bulk listing — {@code SHOW CREATE} per
     * routine is what made 1000+ procedures stall schema load.
     */
    private static List<SchemaNode> readRoutines(Connection connection, String catalog, boolean includeDdl)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        List<SchemaNode> routines = readRoutines(metaData, catalog, null);
        if (routines.isEmpty()) {
            routines = readRoutines(metaData, null, catalog);
        }
        if (!includeDdl) {
            routines.sort(Comparator.comparing(SchemaNode::name, BY_NAME));
            return List.copyOf(routines);
        }
        List<SchemaNode> withDdl = new ArrayList<>(routines.size());
        for (SchemaNode node : routines) {
            withDdl.add(withRoutineDdl(connection, catalog, node));
        }
        withDdl.sort(Comparator.comparing(SchemaNode::name, BY_NAME));
        return List.copyOf(withDdl);
    }

    private static List<SchemaNode> readRoutines(DatabaseMetaData metaData, String catalog, String schema) {
        Map<String, SchemaNode> byName = new LinkedHashMap<>();
        for (SchemaNode node : readJdbcProcedures(metaData, catalog, schema)) {
            addRoutine(byName, node);
        }
        for (SchemaNode node : readJdbcFunctions(metaData, catalog, schema)) {
            addRoutine(byName, node);
        }
        return new ArrayList<>(byName.values());
    }

    private static void addRoutine(Map<String, SchemaNode> byName, SchemaNode node) {
        String key = node.name().toLowerCase(Locale.ROOT);
        SchemaNode existing = byName.get(key);
        if (existing == null) {
            byName.put(key, node);
            return;
        }
        if (SchemaNode.ROUTINE_FUNCTION.equalsIgnoreCase(node.metadata(SchemaNode.META_ROUTINE_KIND))
                && !SchemaNode.ROUTINE_FUNCTION.equalsIgnoreCase(
                        existing.metadata(SchemaNode.META_ROUTINE_KIND))) {
            byName.put(key, node);
        }
    }

    private static List<SchemaNode> readJdbcProcedures(DatabaseMetaData metaData, String catalog, String schema) {
        List<SchemaNode> out = new ArrayList<>();
        try (ResultSet resultSet = metaData.getProcedures(catalog, schema, "%")) {
            while (resultSet.next()) {
                String name = resultSet.getString("PROCEDURE_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                String routineSchema = columnOrNull(resultSet, "PROCEDURE_SCHEM");
                if (isSystemObject(routineSchema, null)) {
                    continue;
                }
                String owner = firstNonBlank(columnOrNull(resultSet, "PROCEDURE_CAT"), routineSchema);
                String resolvedOwner = owner != null ? owner : firstNonBlank(catalog, schema);
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put(SchemaNode.META_CATALOG, Objects.requireNonNullElse(resolvedOwner, ""));
                metadata.put(SchemaNode.META_ROUTINE_KIND, procedureKind(resultSet));
                out.add(SchemaNode.of(name, NodeType.PROCEDURE, metadata));
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return out;
    }

    private static List<SchemaNode> readJdbcFunctions(DatabaseMetaData metaData, String catalog, String schema) {
        List<SchemaNode> out = new ArrayList<>();
        try (ResultSet resultSet = metaData.getFunctions(catalog, schema, "%")) {
            while (resultSet.next()) {
                String name = resultSet.getString("FUNCTION_NAME");
                if (name == null || name.isBlank()) {
                    continue;
                }
                String functionSchema = columnOrNull(resultSet, "FUNCTION_SCHEM");
                if (isSystemObject(functionSchema, null)) {
                    continue;
                }
                String owner = firstNonBlank(columnOrNull(resultSet, "FUNCTION_CAT"), functionSchema);
                String resolvedOwner = owner != null ? owner : firstNonBlank(catalog, schema);
                Map<String, String> metadata = new LinkedHashMap<>();
                metadata.put(SchemaNode.META_CATALOG, Objects.requireNonNullElse(resolvedOwner, ""));
                metadata.put(SchemaNode.META_ROUTINE_KIND, SchemaNode.ROUTINE_FUNCTION);
                out.add(SchemaNode.of(name, NodeType.PROCEDURE, metadata));
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return out;
    }

    private static String procedureKind(ResultSet resultSet) {
        try {
            int type = resultSet.getInt("PROCEDURE_TYPE");
            if (!resultSet.wasNull() && type == DatabaseMetaData.procedureReturnsResult) {
                return SchemaNode.ROUTINE_FUNCTION;
            }
        } catch (SQLException ignored) {
            // column missing on some drivers
        }
        return SchemaNode.ROUTINE_PROCEDURE;
    }

    private static SchemaNode withRoutineDdl(Connection connection, String catalog, SchemaNode node) {
        String owner = firstNonBlank(node.metadata(SchemaNode.META_CATALOG), catalog);
        String ddl = readRoutineDdl(connection, owner, node.name());
        if (ddl == null || ddl.isBlank()) {
            return node;
        }
        Map<String, String> metadata = new LinkedHashMap<>(node.metadata());
        metadata.put(SchemaNode.META_DDL, ddl);
        return new SchemaNode(node.name(), node.type(), node.children(), metadata);
    }

    /**
     * MySQL/MariaDB {@code SHOW CREATE PROCEDURE}, then {@code INFORMATION_SCHEMA.ROUTINES}.
     * Failures return {@code null} so a missing grant does not drop the procedure from the tree.
     */
    static String readRoutineDdl(Connection connection, String catalog, String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String qualified = qualifyIdent(catalog, name);
        String show = showCreateRoutine(connection, "PROCEDURE", qualified);
        if (show == null) {
            show = showCreateRoutine(connection, "FUNCTION", qualified);
        }
        if (show != null && !show.isBlank()) {
            return show.strip();
        }
        return readInformationSchemaRoutine(connection, catalog, name);
    }

    private static String showCreateRoutine(Connection connection, String kind, String qualified) {
        String sql = "SHOW CREATE " + kind + " " + qualified;
        try (var statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                return null;
            }
            String[] columns = "FUNCTION".equalsIgnoreCase(kind)
                    ? new String[]{"Create Function", "CREATE FUNCTION"}
                    : new String[]{"Create Procedure", "CREATE PROCEDURE"};
            for (String column : columns) {
                String value = columnOrNull(resultSet, column);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
            int count = resultSet.getMetaData().getColumnCount();
            if (count >= 3) {
                return resultSet.getString(3);
            }
            return count >= 1 ? resultSet.getString(1) : null;
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static String readInformationSchemaRoutine(Connection connection, String catalog, String name) {
        String sql = """
                SELECT ROUTINE_TYPE, ROUTINE_DEFINITION, ROUTINE_SCHEMA, ROUTINE_CATALOG
                FROM INFORMATION_SCHEMA.ROUTINES
                WHERE UPPER(ROUTINE_NAME) = ?
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, name.toUpperCase(Locale.ROOT));
            try (ResultSet resultSet = statement.executeQuery()) {
                String fallback = null;
                while (resultSet.next()) {
                    String schema = firstNonBlank(
                            resultSet.getString("ROUTINE_SCHEMA"), resultSet.getString("ROUTINE_CATALOG"));
                    String type = resultSet.getString("ROUTINE_TYPE");
                    String body = resultSet.getString("ROUTINE_DEFINITION");
                    String wrapped = wrapRoutineDefinition(type, name, body);
                    if (catalog != null && !catalog.isBlank() && schema != null
                            && schema.equalsIgnoreCase(catalog)) {
                        return wrapped;
                    }
                    if (fallback == null) {
                        fallback = wrapped;
                    }
                }
                return fallback;
            }
        } catch (SQLException ignored) {
            return null;
        }
    }

    /** Builds {@code CREATE PROCEDURE name() …} when the catalog only returned the body. */
    static String wrapRoutineDefinition(String routineType, String name, String definition) {
        String body = definition == null ? "" : definition.strip();
        if (body.toUpperCase(Locale.ROOT).startsWith("CREATE ")) {
            return body;
        }
        String kind = routineType == null || routineType.isBlank() ? "PROCEDURE" : routineType.strip();
        if (kind.equalsIgnoreCase("FUNCTION")) {
            kind = "FUNCTION";
        } else {
            kind = "PROCEDURE";
        }
        if (body.isEmpty()) {
            return "CREATE " + kind + " " + name + "()";
        }
        return "CREATE " + kind + " " + name + "()\n" + body;
    }

    private static String qualifyIdent(String catalog, String name) {
        String quotedName = quoteIdent(name);
        if (catalog == null || catalog.isBlank()) {
            return quotedName;
        }
        return quoteIdent(catalog) + "." + quotedName;
    }

    private static String quoteIdent(String identifier) {
        return "`" + identifier.replace("`", "``") + "`";
    }

    private static String columnOrNull(ResultSet resultSet, String column) {
        try {
            return resultSet.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static List<SchemaNode> readTables(DatabaseMetaData metaData, String catalog, String schema)
            throws SQLException {
        return readTables(metaData, catalog, schema, "%");
    }

    private static List<SchemaNode> readTables(
            DatabaseMetaData metaData, String catalog, String schema, String tablePattern) throws SQLException {
        String pattern = tablePattern == null || tablePattern.isBlank() ? "%" : tablePattern;
        List<SchemaNode> tables = new ArrayList<>();
        try (ResultSet resultSet = metaData.getTables(catalog, schema, pattern, null)) {
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

    /**
     * All columns in {@code catalog} in one {@code DatabaseMetaData#getColumns}
     * call, keyed by lower-cased table name. Empty when the driver rejects a
     * wildcard table pattern — callers then fall back to per-table reads.
     */
    private static Map<String, List<SchemaNode>> readAllColumnsGrouped(Connection connection, String catalog) {
        try {
            DatabaseMetaData metaData = connection.getMetaData();
            Map<String, List<PositionedColumn>> grouped = readAllColumnsGrouped(metaData, catalog, null);
            if (grouped.isEmpty()) {
                grouped = readAllColumnsGrouped(metaData, null, catalog);
            }
            Map<String, List<SchemaNode>> columns = new LinkedHashMap<>();
            for (Map.Entry<String, List<PositionedColumn>> entry : grouped.entrySet()) {
                List<PositionedColumn> positioned = new ArrayList<>(entry.getValue());
                positioned.sort(Comparator.comparingInt(PositionedColumn::position));
                columns.put(entry.getKey(), positioned.stream().map(PositionedColumn::node).toList());
            }
            return columns;
        } catch (SQLException ignored) {
            return Map.of();
        }
    }

    private static Map<String, List<PositionedColumn>> readAllColumnsGrouped(
            DatabaseMetaData metaData, String catalog, String schema) {
        Map<String, List<PositionedColumn>> grouped = new LinkedHashMap<>();
        try (ResultSet resultSet = metaData.getColumns(catalog, schema, "%", "%")) {
            while (resultSet.next()) {
                String table = resultSet.getString("TABLE_NAME");
                String name = resultSet.getString("COLUMN_NAME");
                if (table == null || table.isBlank() || name == null || name.isBlank()) {
                    continue;
                }
                String tableSchema = resultSet.getString("TABLE_SCHEM");
                if (isSystemObject(tableSchema, null)) {
                    continue;
                }
                PositionedColumn column = columnFromResultSet(resultSet, name, catalog, schema);
                grouped.computeIfAbsent(table.toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(column);
            }
        } catch (SQLException ignored) {
            return Map.of();
        }
        return grouped;
    }

    private static PositionedColumn columnFromResultSet(
            ResultSet resultSet, String name, String catalog, String schema) throws SQLException {
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
        metadata.put(SchemaNode.META_PRIMARY_KEY, "false");
        metadata.put(SchemaNode.META_CATALOG, Objects.requireNonNullElse(firstNonBlank(catalog, schema), ""));
        return new PositionedColumn(SchemaNode.of(name, NodeType.COLUMN, metadata), position);
    }

    private static List<SchemaNode> withPrimaryKeys(
            Connection connection, String catalog, String table, List<SchemaNode> columns) throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        Set<String> primaryKeys = readPrimaryKeys(metaData, catalog, null, table);
        if (primaryKeys.isEmpty()) {
            primaryKeys = readPrimaryKeys(metaData, null, catalog, table);
        }
        if (primaryKeys.isEmpty()) {
            return columns;
        }
        List<SchemaNode> updated = new ArrayList<>(columns.size());
        for (SchemaNode column : columns) {
            boolean pk = false;
            for (String pkName : primaryKeys) {
                if (pkName.equalsIgnoreCase(column.name())) {
                    pk = true;
                    break;
                }
            }
            if (!pk) {
                updated.add(column);
                continue;
            }
            Map<String, String> metadata = new LinkedHashMap<>(column.metadata());
            metadata.put(SchemaNode.META_PRIMARY_KEY, "true");
            updated.add(new SchemaNode(column.name(), column.type(), column.children(), metadata));
        }
        return List.copyOf(updated);
    }

    private static SchemaNode readTableDetails(Connection connection, String catalog, String table)
            throws SQLException {
        DatabaseMetaData metaData = connection.getMetaData();
        List<SchemaNode> matches = readTables(metaData, catalog, null, table);
        if (matches.isEmpty()) {
            matches = readTables(metaData, null, catalog, table);
        }
        SchemaNode base = matches.isEmpty()
                ? SchemaNode.of(table, NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, catalog))
                : matches.getFirst();
        return readTableDetails(connection, catalog, table, base, List.of());
    }

    private static SchemaNode readTableDetails(
            Connection connection,
            String catalog,
            String table,
            SchemaNode base,
            List<SchemaNode> preloadedColumns) throws SQLException {

        DatabaseMetaData metaData = connection.getMetaData();
        List<SchemaNode> columns = preloadedColumns != null && !preloadedColumns.isEmpty()
                ? withPrimaryKeys(connection, catalog, table, preloadedColumns)
                : readColumns(connection, catalog, table);

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
