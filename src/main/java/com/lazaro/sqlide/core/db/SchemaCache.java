package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaMetadataCodec.ForeignKey;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    private void rebuildIndexes() {
        Map<String, List<SchemaNode>> byCatalog = new LinkedHashMap<>();
        Map<String, List<SchemaNode>> byName = new LinkedHashMap<>();
        Map<String, SchemaPrefixIndex> prefixes = new LinkedHashMap<>();
        List<SchemaNode> all = new ArrayList<>();
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

    /**
     * Stored procedures (not functions), optionally restricted to {@code catalog}.
     * When {@code catalog} is null/blank, returns every loaded catalog.
     */
    public List<SchemaNode> procedures(String catalog) {
        List<SchemaNode> procedures = new ArrayList<>();
        String filter = catalog == null || catalog.isBlank() ? null : catalog;
        for (SchemaNode db : catalogs) {
            if (filter != null && !db.name().equalsIgnoreCase(filter)) {
                continue;
            }
            collectProcedures(db, filter, procedures);
        }
        return List.copyOf(procedures);
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
