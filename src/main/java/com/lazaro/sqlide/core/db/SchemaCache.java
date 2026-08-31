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
    private volatile boolean ready;

    public void replace(List<SchemaNode> fullSchema) {
        catalogs.clear();
        if (fullSchema != null) {
            catalogs.addAll(fullSchema);
        }
        ready = !catalogs.isEmpty();
        tablesByLowerName = indexTables();
    }

    public void clear() {
        catalogs.clear();
        tablesByLowerName = Map.of();
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
     */
    public List<SchemaNode> tables(String catalog) {
        List<SchemaNode> tables = new ArrayList<>();
        String filter = catalog == null || catalog.isBlank() ? null : catalog;
        for (SchemaNode db : catalogs) {
            if (filter != null && !db.name().equalsIgnoreCase(filter)) {
                continue;
            }
            for (SchemaNode child : db.children()) {
                if (child.type() == NodeType.TABLE || child.type() == NodeType.VIEW) {
                    if (filter != null) {
                        String meta = child.metadata(SchemaNode.META_CATALOG);
                        if (meta != null && !meta.isBlank() && !meta.equalsIgnoreCase(filter)) {
                            continue;
                        }
                    }
                    tables.add(child);
                }
            }
        }
        return List.copyOf(tables);
    }

    private Map<String, List<SchemaNode>> indexTables() {
        Map<String, List<SchemaNode>> index = new LinkedHashMap<>();
        for (SchemaNode table : tables()) {
            index.computeIfAbsent(table.name().toLowerCase(Locale.ROOT), key -> new ArrayList<>()).add(table);
        }
        Map<String, List<SchemaNode>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<SchemaNode>> entry : index.entrySet()) {
            frozen.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Map.copyOf(frozen);
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
        for (SchemaNode table : tables()) {
            List<ForeignKey> keys = SchemaMetadataCodec.decodeForeignKeys(table.metadata(SchemaNode.META_FOREIGN_KEYS));
            for (ForeignKey key : keys) {
                boolean fromInScope = aliases.containsKey(table.name().toLowerCase(Locale.ROOT));
                boolean toInScope = aliases.containsKey(key.pkTable().toLowerCase(Locale.ROOT));
                if (!fromInScope && !toInScope) {
                    continue;
                }
                // Prefer joining the table that is not yet in scope.
                if (fromInScope && !toInScope) {
                    String clause = "%s ON %s.%s = %s.%s".formatted(
                            key.pkTable(), key.pkTable(), key.pkColumn(), table.name(), key.fkColumn());
                    suggestions.add(new JoinSuggestion(
                            clause, "JOIN " + clause, table.name(), key.pkTable()));
                } else if (toInScope && !fromInScope) {
                    String clause = "%s ON %s.%s = %s.%s".formatted(
                            table.name(), table.name(), key.fkColumn(), key.pkTable(), key.pkColumn());
                    suggestions.add(new JoinSuggestion(
                            clause, "JOIN " + clause, key.pkTable(), table.name()));
                } else {
                    String clause = "%s ON %s.%s = %s.%s".formatted(
                            table.name(), table.name(), key.fkColumn(), key.pkTable(), key.pkColumn());
                    suggestions.add(new JoinSuggestion(
                            clause, "JOIN " + clause, key.pkTable(), table.name()));
                }
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
