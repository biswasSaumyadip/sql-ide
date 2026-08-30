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
     * @param lowerKeywords     insert keywords in lower case ({@code select} vs {@code SELECT})
     * @param autoQuoteReserved wrap reserved / unsafe identifiers in dialect quotes
     * @param preserveDbCasing  keep schema object names as returned by the database
     */
    public record Style(boolean lowerKeywords, boolean autoQuoteReserved, boolean preserveDbCasing) {
        public static Style defaults() {
            return new Style(false, true, true);
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
            if (s.lowerKeywords()) {
                text = text.toLowerCase(Locale.ROOT);
            }
            return text;
        }

        if (kind == Kind.SNIPPET || kind == Kind.FUNCTION || kind == Kind.JOIN || kind == Kind.PARAMETER) {
            if (s.lowerKeywords() && (kind == Kind.SNIPPET || kind == Kind.JOIN)) {
                text = lowerSqlKeywordsInTemplate(text);
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

    private static String lowerSqlKeywordsInTemplate(String template) {
        // Lowercase only whole keyword tokens; leave $placeholders$ alone.
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
                    out.append(word.toLowerCase(Locale.ROOT));
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
