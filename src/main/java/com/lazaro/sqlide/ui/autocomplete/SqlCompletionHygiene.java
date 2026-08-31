package com.lazaro.sqlide.ui.autocomplete;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.ConnectionConfig.Driver;
import com.lazaro.sqlide.core.transfer.TransferSql;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Kind;

import java.util.Locale;
import java.util.Set;

/**
 * Post-accept transforms for completion inserts: keyword casing and reserved-identifier quoting.
 */
public final class SqlCompletionHygiene {

    /**
     * How keywords are written when a suggestion is accepted.
     */
    public enum KeywordCasing {
        UPPERCASE("UPPERCASE"),
        LOWERCASE("lowercase"),
        CAPITALIZE("Capitalize");

        private final String label;

        KeywordCasing(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public static KeywordCasing parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return UPPERCASE;
            }
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                if (raw.equalsIgnoreCase("lowercase") || raw.equalsIgnoreCase("lower")) {
                    return LOWERCASE;
                }
                if (raw.equalsIgnoreCase("capitalize") || raw.equalsIgnoreCase("capitalise")) {
                    return CAPITALIZE;
                }
                return UPPERCASE;
            }
        }
    }

    /**
     * @param keywordCasing            SELECT vs select vs Select
     * @param autoQuoteReserved        wrap reserved / unsafe identifiers in dialect quotes
     * @param preserveDbCasing         keep schema object names as returned by the database
     * @param autoGenerateTableAliases insert {@code users u} after FROM / JOIN
     * @param suggestJoinColumns       offer {@code JOIN t ON …} from foreign keys
     */
    public record Style(
            KeywordCasing keywordCasing,
            boolean autoQuoteReserved,
            boolean preserveDbCasing,
            boolean autoGenerateTableAliases,
            boolean suggestJoinColumns
    ) {
        public Style {
            keywordCasing = keywordCasing == null ? KeywordCasing.UPPERCASE : keywordCasing;
        }

        public static Style defaults() {
            return new Style(KeywordCasing.UPPERCASE, true, true, false, true);
        }

        /** Compatibility constructor used by tests ({@code lowerKeywords, quote, preserve}). */
        public Style(boolean lowerKeywords, boolean autoQuoteReserved, boolean preserveDbCasing) {
            this(lowerKeywords ? KeywordCasing.LOWERCASE : KeywordCasing.UPPERCASE,
                    autoQuoteReserved, preserveDbCasing, false, true);
        }

        public boolean lowerKeywords() {
            return keywordCasing == KeywordCasing.LOWERCASE;
        }
    }

    private static final Set<String> RESERVED = Set.of(
            "add", "all", "alter", "and", "any", "as", "asc", "between", "by", "case", "check",
            "column", "constraint", "create", "cross", "current", "delete", "desc", "distinct",
            "drop", "else", "end", "except", "exists", "false", "for", "foreign", "from", "full",
            "group", "having", "in", "index", "inner", "insert", "intersect", "into", "is", "join",
            "key", "left", "like", "limit", "not", "null", "on", "or", "order", "outer", "primary",
            "references", "right", "select", "set", "table", "then", "to", "true", "union",
            "unique", "update", "user", "using", "values", "view", "when", "where", "with",
            "grant", "revoke", "window", "over", "partition", "range", "rows", "offset",
            "fetch", "only", "lateral", "returning", "ilike", "analyze", "explain");

    private SqlCompletionHygiene() {
    }

    /**
     * Applies case / quoting rules to a suggestion's insert text.
     * Display {@code name} is left unchanged by the caller.
     */
    public static String finalizeInsert(
            String insertText,
            String name,
            Kind kind,
            Driver driver,
            Style style) {
        if (insertText == null || insertText.isEmpty() || style == null) {
            return insertText;
        }
        Style s = style;
        String text = insertText;

        if (kind == Kind.KEYWORD) {
            return applyKeywordCasing(text, s.keywordCasing());
        }

        if (kind == Kind.SNIPPET || kind == Kind.FUNCTION || kind == Kind.JOIN || kind == Kind.PARAMETER) {
            if (kind == Kind.SNIPPET || kind == Kind.JOIN) {
                text = recaseSqlKeywordsInTemplate(text, s.keywordCasing());
            }
            return text;
        }

        // Schema objects: optional casing + reserved quoting.
        String ident = name == null || name.isBlank() ? text : name;
        if (!s.preserveDbCasing()) {
            ident = ident.toLowerCase(Locale.ROOT);
            // Only rewrite plain single-identifier inserts.
            if (isPlainIdentifier(text)) {
                text = ident;
            }
        }
        if (s.autoQuoteReserved() && needsQuoting(ident) && isPlainIdentifier(text)) {
            text = TransferSql.quote(ident, driver == null ? ConnectionConfig.Driver.MYSQL : driver);
        }
        return text;
    }

    static boolean needsQuoting(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            return false;
        }
        if (RESERVED.contains(identifier.toLowerCase(Locale.ROOT))) {
            return true;
        }
        if (!Character.isLetter(identifier.charAt(0)) && identifier.charAt(0) != '_') {
            return true;
        }
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (!(Character.isLetterOrDigit(c) || c == '_')) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlainIdentifier(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (text.indexOf(' ') >= 0 || text.indexOf('(') >= 0 || text.indexOf('$') >= 0) {
            return false;
        }
        char first = text.charAt(0);
        return first != '`' && first != '"' && first != '[';
    }

    /**
     * Compact alias for a table name: {@code users} → {@code u}, {@code order_items} → {@code oi}.
     */
    public static String tableAlias(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "t";
        }
        String raw = tableName.strip();
        if (!raw.isEmpty() && (raw.charAt(0) == '`' || raw.charAt(0) == '"' || raw.charAt(0) == '[')) {
            raw = raw.substring(1, Math.max(1, raw.length() - 1));
        }
        String[] parts = raw.split("[_\\-]+");
        if (parts.length >= 2) {
            StringBuilder out = new StringBuilder();
            for (String part : parts) {
                if (!part.isEmpty()) {
                    out.append(Character.toLowerCase(part.charAt(0)));
                }
            }
            return out.isEmpty() ? "t" : out.toString();
        }
        return Character.toString(Character.toLowerCase(raw.charAt(0)));
    }

    static String applyKeywordCasing(String word, KeywordCasing casing) {
        if (word == null || word.isEmpty()) {
            return word;
        }
        KeywordCasing mode = casing == null ? KeywordCasing.UPPERCASE : casing;
        return switch (mode) {
            case UPPERCASE -> word.toUpperCase(Locale.ROOT);
            case LOWERCASE -> word.toLowerCase(Locale.ROOT);
            case CAPITALIZE -> {
                String lower = word.toLowerCase(Locale.ROOT);
                yield Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
            }
        };
    }

    private static String recaseSqlKeywordsInTemplate(String template, KeywordCasing casing) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        StringBuilder out = new StringBuilder(template.length());
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '$') {
                int end = template.indexOf('$', i + 1);
                if (end > i) {
                    out.append(template, i, end + 1);
                    i = end + 1;
                    continue;
                }
            }
            if (Character.isLetter(c)) {
                int start = i;
                while (i < template.length() && (Character.isLetterOrDigit(template.charAt(i))
                        || template.charAt(i) == '_')) {
                    i++;
                }
                String word = template.substring(start, i);
                if (RESERVED.contains(word.toLowerCase(Locale.ROOT))) {
                    out.append(applyKeywordCasing(word, casing));
                } else {
                    out.append(word);
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }
}
