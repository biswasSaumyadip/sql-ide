package com.lazaro.sqlide.core.doc;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a hovered SQL identifier to table / column documentation using the
 * client-side {@link SchemaCache} (and light statement scoping for aliases).
 */
public final class SqlDocResolver {

    public enum Kind {
        TABLE,
        COLUMN
    }

    public record Doc(
            Kind kind,
            String dataSource,
            String schema,
            String table,
            String column,
            String code,
            SchemaNode tableNode,
            SchemaNode columnNode
    ) {
        public Doc {
            Objects.requireNonNull(kind, "kind");
            dataSource = Objects.requireNonNullElse(dataSource, "");
            schema = Objects.requireNonNullElse(schema, "");
            table = Objects.requireNonNullElse(table, "");
            column = column == null || column.isBlank() ? null : column;
            code = Objects.requireNonNullElse(code, "");
        }

        public boolean isTable() {
            return kind == Kind.TABLE;
        }
    }

    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join|update|into|table)\\s+([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?)"
                    + "(?:\\s*\\.\\s*([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?))?"
                    + "(?:\\s+(?:as\\s+)?([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?))?",
            Pattern.CASE_INSENSITIVE);

    private SqlDocResolver() {
    }

    public static Optional<Doc> resolve(
            String sql,
            int charIndex,
            SchemaCache cache,
            String activeCatalog,
            String dataSourceLabel) {
        if (sql == null || sql.isEmpty() || cache == null || !cache.isReady()) {
            return Optional.empty();
        }
        int index = clampIndex(sql, charIndex);
        Identifier ident = identifierAt(sql, index);
        if (ident == null || ident.name().isBlank()) {
            return Optional.empty();
        }

        Map<String, String> aliases = aliasesIn(sql);
        String catalog = activeCatalog;

        if (ident.qualifier() != null) {
            String left = ident.qualifier();
            String right = ident.name();

            // catalog.table
            Optional<SchemaNode> asTable = cache.resolveTable(left, right, catalog);
            if (asTable.isPresent()) {
                return Optional.of(tableDoc(asTable.get(), dataSourceLabel, cache));
            }

            // alias.column or table.column
            String physical = aliases.getOrDefault(left.toLowerCase(Locale.ROOT), left);
            Optional<SchemaNode> owner = cache.findTable(physical, catalog);
            if (owner.isPresent()) {
                Optional<SchemaNode> col = findColumn(owner.get(), right);
                if (col.isPresent()) {
                    return Optional.of(columnDoc(owner.get(), col.get(), dataSourceLabel, cache));
                }
            }
            return Optional.empty();
        }

        // Bare name: prefer table match.
        Optional<SchemaNode> table = cache.findTable(ident.name(), catalog);
        if (table.isPresent()) {
            return Optional.of(tableDoc(table.get(), dataSourceLabel, cache));
        }

        // Column in scoped tables / aliases.
        for (String physical : new java.util.LinkedHashSet<>(aliases.values())) {
            Optional<SchemaNode> owner = cache.findTable(physical, catalog);
            if (owner.isEmpty()) {
                continue;
            }
            Optional<SchemaNode> col = findColumn(owner.get(), ident.name());
            if (col.isPresent()) {
                return Optional.of(columnDoc(owner.get(), col.get(), dataSourceLabel, cache));
            }
        }

        // Unique column across active catalog.
        SchemaNode uniqueOwner = null;
        SchemaNode uniqueCol = null;
        for (SchemaNode candidate : cache.tables(catalog)) {
            Optional<SchemaNode> col = findColumn(candidate, ident.name());
            if (col.isEmpty()) {
                continue;
            }
            if (uniqueOwner != null) {
                uniqueOwner = null;
                uniqueCol = null;
                break;
            }
            uniqueOwner = candidate;
            uniqueCol = col.get();
        }
        if (uniqueOwner != null && uniqueCol != null) {
            return Optional.of(columnDoc(uniqueOwner, uniqueCol, dataSourceLabel, cache));
        }
        return Optional.empty();
    }

    private static Doc tableDoc(SchemaNode table, String dataSource, SchemaCache cache) {
        String schema = schemaOf(table, cache);
        String ddl = table.metadata(SchemaNode.META_DDL);
        if (ddl == null || ddl.isBlank()) {
            ddl = "-- DDL not loaded for " + table.name() + "\n"
                    + "-- Refresh schema (Ctrl+R) to populate CREATE TABLE.";
        }
        return new Doc(Kind.TABLE, dataSource, schema, table.name(), null, ddl, table, null);
    }

    private static Doc columnDoc(SchemaNode table, SchemaNode column, String dataSource, SchemaCache cache) {
        String schema = schemaOf(table, cache);
        String type = Objects.requireNonNullElse(column.metadata(SchemaNode.META_DATA_TYPE), "UNKNOWN");
        String nullable = column.metadataFlag(SchemaNode.META_NULLABLE) ? "" : " NOT NULL";
        String pk = column.metadataFlag(SchemaNode.META_PRIMARY_KEY) ? " /* PRIMARY KEY */" : "";
        String snippet = "ALTER TABLE " + table.name()
                + " ADD " + column.name()
                + " " + type + nullable + ";" + pk;
        return new Doc(Kind.COLUMN, dataSource, schema, table.name(), column.name(), snippet, table, column);
    }

    private static String schemaOf(SchemaNode table, SchemaCache cache) {
        String catalog = table.metadata(SchemaNode.META_CATALOG);
        if (catalog != null && !catalog.isBlank()) {
            return catalog;
        }
        for (SchemaNode db : cache.catalogs()) {
            if (containsTable(db, table)) {
                return db.name();
            }
        }
        return "";
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

    private static Optional<SchemaNode> findColumn(SchemaNode table, String name) {
        if (table == null || name == null) {
            return Optional.empty();
        }
        String needle = stripQuotes(name);
        for (SchemaNode child : table.children()) {
            if (child.type() == NodeType.COLUMN && child.name().equalsIgnoreCase(needle)) {
                return Optional.of(child);
            }
            if (child.type() == NodeType.FOLDER
                    && SchemaNode.FOLDER_COLUMNS.equals(child.metadata(SchemaNode.META_FOLDER_KIND))) {
                for (SchemaNode col : child.children()) {
                    if (col.type() == NodeType.COLUMN && col.name().equalsIgnoreCase(needle)) {
                        return Optional.of(col);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Map<String, String> aliasesIn(String sql) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Matcher matcher = TABLE_REF.matcher(sql);
        while (matcher.find()) {
            String first = stripQuotes(matcher.group(1));
            String second = matcher.group(2) == null ? null : stripQuotes(matcher.group(2));
            String alias = matcher.group(3) == null ? null : stripQuotes(matcher.group(3));
            String table;
            if (second != null && !second.isBlank()) {
                table = second;
            } else {
                table = first;
            }
            if (alias == null || alias.isBlank()) {
                alias = table;
            }
            aliases.put(alias.toLowerCase(Locale.ROOT), table);
            aliases.put(table.toLowerCase(Locale.ROOT), table);
        }
        return aliases;
    }

    public record Identifier(String qualifier, String name) {
    }

    public static Identifier identifierAt(String text, int index) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int i = clampIndex(text, index);
        if (!isIdentChar(text.charAt(i))) {
            if (i > 0 && isIdentChar(text.charAt(i - 1))) {
                i--;
            } else {
                return null;
            }
        }
        int start = i;
        int end = i + 1;
        while (start > 0 && isIdentChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isIdentChar(text.charAt(end))) {
            end++;
        }
        String name = text.substring(start, end);
        String qualifier = null;
        int q = start - 1;
        while (q >= 0 && Character.isWhitespace(text.charAt(q))) {
            q--;
        }
        if (q >= 0 && text.charAt(q) == '.') {
            int qEnd = q;
            int qStart = q;
            while (qStart > 0 && isIdentChar(text.charAt(qStart - 1))) {
                qStart--;
            }
            if (qStart < qEnd) {
                qualifier = text.substring(qStart, qEnd);
            }
        }
        return new Identifier(qualifier, name);
    }

    private static int clampIndex(String text, int index) {
        if (index < 0) {
            return 0;
        }
        if (index >= text.length()) {
            return text.length() - 1;
        }
        return index;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static String stripQuotes(String name) {
        if (name == null) {
            return "";
        }
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
