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
    private volatile boolean ready;

    public void replace(List<SchemaNode> fullSchema) {
        catalogs.clear();
        if (fullSchema != null) {
            catalogs.addAll(fullSchema);
        }
        ready = !catalogs.isEmpty();
    }

    public void clear() {
        catalogs.clear();
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
        List<SchemaNode> tables = new ArrayList<>();
        for (SchemaNode catalog : catalogs) {
            for (SchemaNode child : catalog.children()) {
                if (child.type() == NodeType.TABLE || child.type() == NodeType.VIEW) {
                    tables.add(child);
                }
            }
        }
        return List.copyOf(tables);
    }

    public Optional<SchemaNode> findTable(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        String needle = stripQuotes(name);
        for (SchemaNode table : tables()) {
            if (table.name().equalsIgnoreCase(needle)) {
                return Optional.of(table);
            }
        }
        return Optional.empty();
    }

    public List<SchemaNode> columnsOf(String tableOrAlias) {
        return findTable(tableOrAlias).map(SchemaNode::children).orElse(List.of());
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
