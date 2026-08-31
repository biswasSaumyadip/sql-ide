package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaMetadataCodec.ForeignKey;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Client-side snapshot of the connected schema. Loaded once per connection (and on
 * Refresh), then consulted by autocomplete and the object viewer without further
 * JDBC traffic.
 *
 * <p>Thread-safe for concurrent readers; writers replace the whole snapshot.
 */
public final class SchemaCache {

    public record JoinSuggestion(String insertText, String displayText, String fromTable, String toTable) {
    }

    private final CopyOnWriteArrayList<SchemaNode> catalogs = new CopyOnWriteArrayList<>();
    private volatile Map<String, List<SchemaNode>> tablesByLowerName = Map.of();
    private volatile Map<String, List<SchemaNode>> tablesByLowerCatalog = Map.of();
    private volatile List<SchemaNode> allTables = List.of();
    private volatile List<FkEdge> foreignKeyEdges = List.of();
    private volatile Map<String, SchemaPrefixIndex> prefixByCatalog = Map.of();
    private volatile SchemaPrefixIndex allPrefixIndex = SchemaPrefixIndex.EMPTY;
    private volatile Map<String, List<SchemaNode>> proceduresByLowerCatalog = Map.of();
    private volatile List<SchemaNode> allProcedures = List.of();
    private volatile Map<String, SchemaPrefixIndex> procedurePrefixByCatalog = Map.of();
    private volatile SchemaPrefixIndex allProcedurePrefixIndex = SchemaPrefixIndex.EMPTY;
    private volatile boolean ready;

    private record FkEdge(
            String fromTable,
            String fromTableLower,
            String toTable,
            String toTableLower,
            String fkColumn,
            String pkColumn) {
    }

    public void replace(List<SchemaNode> fullSchema) {
        catalogs.clear();
        if (fullSchema != null) {
            catalogs.addAll(fullSchema);
        }
        ready = !catalogs.isEmpty();
        rebuildIndexes();
    }

    /**
     * Inserts or replaces catalogs by name, leaving unmatched catalogs in place.
     * Used when the active database is indexed first and the rest arrive later.
     */
    public void upsertCatalogs(List<SchemaNode> incoming) {
        if (incoming == null || incoming.isEmpty()) {
            return;
        }
        for (SchemaNode next : incoming) {
            if (next == null || next.name() == null || next.name().isBlank()) {
                continue;
            }
            boolean replaced = false;
            for (int i = 0; i < catalogs.size(); i++) {
                if (catalogs.get(i).name().equalsIgnoreCase(next.name())) {
                    catalogs.set(i, next);
                    replaced = true;
                    break;
                }
            }
            if (!replaced) {
                catalogs.add(next);
            }
        }
        ready = !catalogs.isEmpty();
        rebuildIndexes();
    }

    public void clear() {
        catalogs.clear();
        tablesByLowerName = Map.of();
        tablesByLowerCatalog = Map.of();
        allTables = List.of();
        foreignKeyEdges = List.of();
        prefixByCatalog = Map.of();
        allPrefixIndex = SchemaPrefixIndex.EMPTY;
        proceduresByLowerCatalog = Map.of();
        allProcedures = List.of();
        procedurePrefixByCatalog = Map.of();
        allProcedurePrefixIndex = SchemaPrefixIndex.EMPTY;
        ready = false;
    }

    public boolean isReady() {
        return ready;
    }

    public List<SchemaNode> catalogs() {
        return List.copyOf(catalogs);
    }

    /** Flat list of every TABLE and VIEW across catalogs. */
    public List<SchemaNode> tables() {
        return tables(null);
    }

    /**
     * Tables and views, optionally restricted to {@code catalog}.
     * When {@code catalog} is null/blank, returns every loaded catalog.
     * Returns the snapshot built on {@link #replace} / {@link #upsertCatalogs};
     * callers must not mutate it.
     */
    public List<SchemaNode> tables(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return allTables;
        }
        List<SchemaNode> snapshot = tablesByLowerCatalog.get(catalog.toLowerCase(Locale.ROOT));
        return snapshot == null ? List.of() : snapshot;
    }

    /**
     * Tables whose names start with {@code prefix}, using the sorted prefix index.
     * Empty prefix returns an empty list — use {@link #tables(String)} for the
     * full snapshot.
     */
    public List<SchemaNode> tablesWithPrefix(String catalog, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        SchemaPrefixIndex index;
        if (catalog == null || catalog.isBlank()) {
            index = allPrefixIndex;
        } else {
            index = prefixByCatalog.getOrDefault(catalog.toLowerCase(Locale.ROOT), SchemaPrefixIndex.EMPTY);
        }
        return index.prefixHits(prefix);
    }

    /**
     * Stored procedures (not functions), optionally restricted to {@code catalog}.
     * When {@code catalog} is null/blank, returns every loaded catalog.
     */
    public List<SchemaNode> procedures(String catalog) {
        if (catalog == null || catalog.isBlank()) {
            return allProcedures;
        }
        List<SchemaNode> snapshot = proceduresByLowerCatalog.get(catalog.toLowerCase(Locale.ROOT));
        return snapshot == null ? List.of() : snapshot;
    }

    public List<SchemaNode> proceduresWithPrefix(String catalog, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        SchemaPrefixIndex index;
        if (catalog == null || catalog.isBlank()) {
            index = allProcedurePrefixIndex;
        } else {
            index = procedurePrefixByCatalog.getOrDefault(
                    catalog.toLowerCase(Locale.ROOT), SchemaPrefixIndex.EMPTY);
        }
        return index.prefixHits(prefix);
    }

    /**
     * Tree children from the snapshot. Empty when this level is not indexed yet
     * so the caller can fall back to JDBC.
     */
    public Optional<List<SchemaNode>> cachedChildren(SchemaNode parent) {
        if (!ready || parent == null) {
            return Optional.empty();
        }
        return switch (parent.type()) {
            case DATABASE, SCHEMA -> {
                String catalog = parent.name();
                List<SchemaNode> tables = tables(catalog);
                List<SchemaNode> procs = procedures(catalog);
                if (tables.isEmpty() && procs.isEmpty()) {
                    yield Optional.empty();
                }
                yield Optional.of(SchemaFolders.forCatalog(catalog, tables, procs));
            }
            case FOLDER -> cachedFolderChildren(parent);
            case TABLE, VIEW -> findTable(parent.name(), parent.metadata(SchemaNode.META_CATALOG))
                    .map(SchemaCache::tableDetailFolders);
            default -> Optional.empty();
        };
    }

    private Optional<List<SchemaNode>> cachedFolderChildren(SchemaNode folder) {
        String kind = Objects.requireNonNullElse(folder.folderKind(), "");
        String catalog = folder.metadata(SchemaNode.META_CATALOG);
        String table = folder.metadata(SchemaNode.META_TABLE);
        return switch (kind) {
            case SchemaNode.FOLDER_TABLES -> {
                List<SchemaNode> tables = tables(catalog).stream()
                        .filter(node -> node.type() == NodeType.TABLE)
                        .toList();
                yield tables.isEmpty() ? Optional.empty() : Optional.of(tables);
            }
            case SchemaNode.FOLDER_VIEWS -> {
                List<SchemaNode> views = tables(catalog).stream()
                        .filter(node -> node.type() == NodeType.VIEW)
                        .toList();
                yield views.isEmpty() ? Optional.empty() : Optional.of(views);
            }
            case SchemaNode.FOLDER_PROCEDURES -> {
                List<SchemaNode> procs = procedures(catalog);
                yield procs.isEmpty() ? Optional.empty() : Optional.of(procs);
            }
            case SchemaNode.FOLDER_COLUMNS -> findTable(table, catalog)
                    .map(SchemaNode::children)
                    .filter(cols -> !cols.isEmpty());
            case SchemaNode.FOLDER_KEYS -> findTable(table, catalog)
                    .map(SchemaCache::keyNodesFrom)
                    .filter(keys -> !keys.isEmpty());
            case SchemaNode.FOLDER_INDEXES -> findTable(table, catalog)
                    .map(SchemaCache::indexNodesFrom)
                    .filter(indexes -> !indexes.isEmpty());
            default -> Optional.empty();
        };
    }

    private static List<SchemaNode> tableDetailFolders(SchemaNode table) {
        String catalog = Objects.requireNonNullElse(table.metadata(SchemaNode.META_CATALOG), "");
        return SchemaFolders.forTable(
                catalog, table.name(), table.children(), keyNodesFrom(table), indexNodesFrom(table));
    }

    private static List<SchemaNode> keyNodesFrom(SchemaNode table) {
        String catalog = Objects.requireNonNullElse(table.metadata(SchemaNode.META_CATALOG), "");
        Map<String, String> shared = Map.of(
                SchemaNode.META_CATALOG, catalog,
                SchemaNode.META_TABLE, table.name());
        List<SchemaNode> keys = new ArrayList<>();
        List<String> pkColumns = new ArrayList<>();
        for (SchemaNode child : table.children()) {
            if (child.type() == NodeType.COLUMN && child.metadataFlag(SchemaNode.META_PRIMARY_KEY)) {
                pkColumns.add(child.name());
            }
        }
        if (!pkColumns.isEmpty()) {
            keys.add(SchemaNode.key("PRIMARY", "PRIMARY", pkColumns, shared));
        }
        for (ForeignKey fk : SchemaMetadataCodec.decodeForeignKeys(table.metadata(SchemaNode.META_FOREIGN_KEYS))) {
            keys.add(SchemaNode.key(fk.name(), "FOREIGN", List.of(fk.fkColumn()), shared));
        }
        return List.copyOf(keys);
    }

    private static List<SchemaNode> indexNodesFrom(SchemaNode table) {
        String catalog = Objects.requireNonNullElse(table.metadata(SchemaNode.META_CATALOG), "");
        Map<String, String> shared = Map.of(
                SchemaNode.META_CATALOG, catalog,
                SchemaNode.META_TABLE, table.name());
        List<SchemaNode> nodes = new ArrayList<>();
        for (SchemaMetadataCodec.IndexInfo index :
                SchemaMetadataCodec.decodeIndexes(table.metadata(SchemaNode.META_INDEXES))) {
            nodes.add(SchemaNode.index(index.name(), index.unique(), index.columns(), shared));
        }
        return List.copyOf(nodes);
    }

    private void rebuildIndexes() {
        Map<String, List<SchemaNode>> byCatalog = new LinkedHashMap<>();
        Map<String, List<SchemaNode>> byName = new LinkedHashMap<>();
        Map<String, SchemaPrefixIndex> prefixes = new LinkedHashMap<>();
        Map<String, List<SchemaNode>> procsByCatalog = new LinkedHashMap<>();
        Map<String, SchemaPrefixIndex> procPrefixes = new LinkedHashMap<>();
        List<SchemaNode> all = new ArrayList<>();
        List<SchemaNode> allProcs = new ArrayList<>();
        List<FkEdge> edges = new ArrayList<>();
        for (SchemaNode db : catalogs) {
            if (db == null || db.name() == null || db.name().isBlank()) {
                continue;
            }
            List<SchemaNode> catalogTables = new ArrayList<>();
            collectTables(db, db.name(), catalogTables);
            List<SchemaNode> frozenCatalog = List.copyOf(catalogTables);
            byCatalog.put(db.name().toLowerCase(Locale.ROOT), frozenCatalog);
            prefixes.put(db.name().toLowerCase(Locale.ROOT), SchemaPrefixIndex.of(frozenCatalog));
            List<SchemaNode> catalogProcs = new ArrayList<>();
            collectProcedures(db, db.name(), catalogProcs);
            List<SchemaNode> frozenProcs = List.copyOf(catalogProcs);
            procsByCatalog.put(db.name().toLowerCase(Locale.ROOT), frozenProcs);
            procPrefixes.put(db.name().toLowerCase(Locale.ROOT), SchemaPrefixIndex.of(frozenProcs));
            allProcs.addAll(frozenProcs);
            for (SchemaNode table : frozenCatalog) {
                all.add(table);
                byName.computeIfAbsent(table.name().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(table);
                for (ForeignKey key : SchemaMetadataCodec.decodeForeignKeys(
                        table.metadata(SchemaNode.META_FOREIGN_KEYS))) {
                    if (key.pkTable() == null || key.pkTable().isBlank()) {
                        continue;
                    }
                    edges.add(new FkEdge(
                            table.name(),
                            table.name().toLowerCase(Locale.ROOT),
                            key.pkTable(),
                            key.pkTable().toLowerCase(Locale.ROOT),
                            key.fkColumn(),
                            key.pkColumn()));
                }
            }
        }
        Map<String, List<SchemaNode>> frozenNames = new LinkedHashMap<>();
        for (Map.Entry<String, List<SchemaNode>> entry : byName.entrySet()) {
            frozenNames.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        tablesByLowerCatalog = Map.copyOf(byCatalog);
        tablesByLowerName = Map.copyOf(frozenNames);
        allTables = List.copyOf(all);
        foreignKeyEdges = List.copyOf(edges);
        prefixByCatalog = Map.copyOf(prefixes);
        allPrefixIndex = SchemaPrefixIndex.of(allTables);
        proceduresByLowerCatalog = Map.copyOf(procsByCatalog);
        allProcedures = List.copyOf(allProcs);
        procedurePrefixByCatalog = Map.copyOf(procPrefixes);
        allProcedurePrefixIndex = SchemaPrefixIndex.of(allProcedures);
    }

    private static void collectTables(SchemaNode parent, String catalogName, List<SchemaNode> out) {
        for (SchemaNode child : parent.children()) {
            if (child.type() == NodeType.TABLE || child.type() == NodeType.VIEW) {
                String meta = child.metadata(SchemaNode.META_CATALOG);
                if (meta != null && !meta.isBlank() && catalogName != null
                        && !meta.equalsIgnoreCase(catalogName)) {
                    continue;
                }
                out.add(child);
            } else if (child.type() == NodeType.FOLDER) {
                collectTables(child, catalogName, out);
            }
        }
    }

    private static void collectProcedures(SchemaNode parent, String catalogFilter, List<SchemaNode> out) {
        for (SchemaNode child : parent.children()) {
            if (child.type() == NodeType.PROCEDURE && isStoredProcedure(child)) {
                if (catalogFilter != null) {
                    String meta = child.metadata(SchemaNode.META_CATALOG);
                    if (meta != null && !meta.isBlank() && !meta.equalsIgnoreCase(catalogFilter)) {
                        continue;
                    }
                }
                out.add(child);
            } else if (child.type() == NodeType.FOLDER) {
                collectProcedures(child, catalogFilter, out);
            }
        }
    }

    private static boolean isStoredProcedure(SchemaNode node) {
        String kind = node.metadata(SchemaNode.META_ROUTINE_KIND);
        return kind == null || kind.isBlank() || SchemaNode.ROUTINE_PROCEDURE.equalsIgnoreCase(kind);
    }

    public Optional<SchemaNode> findTable(String name) {
        return findTable(name, null);
    }

    /**
     * Locates a table by name. When {@code preferredCatalog} is set and multiple
     * catalogs share the name, the match in that catalog wins; otherwise the first
     * match across catalogs is returned.
     */
    public Optional<SchemaNode> findTable(String name, String preferredCatalog) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String needle = stripQuotes(name);
        List<SchemaNode> matches = tablesByLowerName.get(needle.toLowerCase(Locale.ROOT));
        if (matches == null || matches.isEmpty()) {
            return Optional.empty();
        }
        if (preferredCatalog == null || preferredCatalog.isBlank()) {
            return Optional.of(matches.getFirst());
        }
        Optional<SchemaNode> fallback = Optional.empty();
        for (SchemaNode table : matches) {
            String meta = table.metadata(SchemaNode.META_CATALOG);
            String owner = meta != null && !meta.isBlank() ? meta : catalogOf(table);
            if (owner != null && owner.equalsIgnoreCase(preferredCatalog)) {
                return Optional.of(table);
            }
            if (fallback.isEmpty()) {
                fallback = Optional.of(table);
            }
        }
        return fallback;
    }

    /**
     * Resolves a possibly qualified table reference.
     *
     * <ul>
     *   <li>When {@code catalogOrSchema} is set, looks <em>only</em> inside that
     *       catalog (no cross-catalog fallback).</li>
     *   <li>Otherwise prefers {@code activeCatalog}, then any catalog.</li>
     * </ul>
     */
    public Optional<SchemaNode> resolveTable(String catalogOrSchema, String tableName, String activeCatalog) {
        if (tableName == null || tableName.isBlank()) {
            return Optional.empty();
        }
        String needle = stripQuotes(tableName);
        if (catalogOrSchema != null && !catalogOrSchema.isBlank()) {
            return findInCatalog(stripQuotes(catalogOrSchema), needle);
        }
        return findTable(needle, activeCatalog);
    }

    /** Strict lookup: table must live under the named catalog. */
    public Optional<SchemaNode> findInCatalog(String catalog, String tableName) {
        if (catalog == null || catalog.isBlank() || tableName == null || tableName.isBlank()) {
            return Optional.empty();
        }
        String catalogNeedle = stripQuotes(catalog);
        String tableNeedle = stripQuotes(tableName);
        for (SchemaNode db : catalogs) {
            if (!db.name().equalsIgnoreCase(catalogNeedle)) {
                continue;
            }
            Optional<SchemaNode> direct = findTableChild(db, tableNeedle);
            if (direct.isPresent()) {
                return direct;
            }
        }
        // Fallback: match via META_CATALOG when the catalog node name differs.
        for (SchemaNode table : tables(catalogNeedle)) {
            if (table.name().equalsIgnoreCase(tableNeedle)) {
                return Optional.of(table);
            }
        }
        return Optional.empty();
    }

    private static Optional<SchemaNode> findTableChild(SchemaNode parent, String tableName) {
        for (SchemaNode child : parent.children()) {
            if ((child.type() == NodeType.TABLE || child.type() == NodeType.VIEW)
                    && child.name().equalsIgnoreCase(tableName)) {
                return Optional.of(child);
            }
            // Defensive: skip logical folders if a future snapshot nests them.
            if (child.type() == NodeType.FOLDER) {
                Optional<SchemaNode> nested = findTableChild(child, tableName);
                if (nested.isPresent()) {
                    return nested;
                }
            }
        }
        return Optional.empty();
    }

    private String catalogOf(SchemaNode table) {
        for (SchemaNode db : catalogs) {
            if (containsTable(db, table)) {
                return db.name();
            }
        }
        return null;
    }

    private static boolean containsTable(SchemaNode parent, SchemaNode table) {
        for (SchemaNode child : parent.children()) {
            if (child == table || (child.type() == table.type()
                    && child.name().equalsIgnoreCase(table.name())
                    && (child.type() == NodeType.TABLE || child.type() == NodeType.VIEW))) {
                return true;
            }
            if (child.type() == NodeType.FOLDER && containsTable(child, table)) {
                return true;
            }
        }
        return false;
    }

    public List<SchemaNode> columnsOf(String tableOrAlias) {
        return columnsOf(tableOrAlias, null);
    }

    public List<SchemaNode> columnsOf(String tableOrAlias, String preferredCatalog) {
        return findTable(tableOrAlias, preferredCatalog).map(SchemaNode::children).orElse(List.of());
    }

    /**
     * JOIN snippets implied by foreign keys among {@code tablesInScope}, plus FKs
     * from those tables to anything else in the cache.
     */
    public List<JoinSuggestion> joinSuggestions(Collection<String> tablesInScope) {
        Map<String, String> aliases = new LinkedHashMap<>();
        for (String name : tablesInScope) {
            aliases.put(name.toLowerCase(Locale.ROOT), name);
        }

        List<JoinSuggestion> suggestions = new ArrayList<>();
        for (FkEdge key : foreignKeyEdges) {
            boolean fromInScope = aliases.containsKey(key.fromTableLower());
            boolean toInScope = aliases.containsKey(key.toTableLower());
            if (!fromInScope && !toInScope) {
                continue;
            }
            // Prefer joining the table that is not yet in scope.
            if (fromInScope && !toInScope) {
                String clause = "%s ON %s.%s = %s.%s".formatted(
                        key.toTable(), key.toTable(), key.pkColumn(), key.fromTable(), key.fkColumn());
                suggestions.add(new JoinSuggestion(
                        clause, "JOIN " + clause, key.fromTable(), key.toTable()));
            } else if (toInScope && !fromInScope) {
                String clause = "%s ON %s.%s = %s.%s".formatted(
                        key.fromTable(), key.fromTable(), key.fkColumn(), key.toTable(), key.pkColumn());
                suggestions.add(new JoinSuggestion(
                        clause, "JOIN " + clause, key.toTable(), key.fromTable()));
            } else {
                String clause = "%s ON %s.%s = %s.%s".formatted(
                        key.fromTable(), key.fromTable(), key.fkColumn(), key.toTable(), key.pkColumn());
                suggestions.add(new JoinSuggestion(
                        clause, "JOIN " + clause, key.toTable(), key.fromTable()));
            }
        }
        return List.copyOf(suggestions);
    }

    private static String stripQuotes(String name) {
        String trimmed = name.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '`' && last == '`')
                    || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }
}
