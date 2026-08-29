package com.lazaro.sqlide.ui.autocomplete;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Context-aware SQL completion modelled on DataGrip / IntelliJ behaviour:
 * auto-popup only on natural triggers, schema items ranked above keywords, and
 * Ctrl+Space for an explicit, broader invoke.
 */
public final class SqlAutocompleteEngine {

    public enum Kind {
        KEYWORD,
        TABLE,
        COLUMN,
        JOIN
    }

    /**
     * @param insertText    text written into the editor
     * @param name          primary label (left column)
     * @param detail        secondary label (right column) — type, "table", …
     * @param trailingSpace append a space after insert (keywords like {@code FROM})
     */
    public record Suggestion(
            String insertText,
            String name,
            String detail,
            Kind kind,
            int replaceStart,
            int replaceEnd,
            int score,
            boolean trailingSpace
    ) {
    }

    private static final String[] KEYWORDS = {
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE",
            "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "USING",
            "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "DISTINCT",
            "INTO", "VALUES", "SET", "AS", "UNION", "ALL", "WITH",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX",
            "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE", "IS", "NULL",
            "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC"
    };

    /** Keywords that read more naturally with a trailing space. */
    private static final Set<String> SPACE_AFTER = Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "JOIN", "INNER", "LEFT",
            "RIGHT", "FULL", "OUTER", "CROSS", "ON", "GROUP", "ORDER", "BY", "HAVING",
            "LIMIT", "OFFSET", "INTO", "VALUES", "SET", "AS", "UNION", "WITH", "CREATE",
            "ALTER", "DROP", "TRUNCATE", "TABLE", "AND", "OR", "NOT", "IN", "EXISTS",
            "BETWEEN", "LIKE", "IS", "CASE", "WHEN", "THEN", "ELSE");

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]*$");
    private static final Pattern DOT_QUALIFIER = Pattern.compile("([A-Za-z0-9_]+)\\.\\s*([A-Za-z0-9_]*)$");
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join|update|into|table)\\s+([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?)"
                    + "(?:\\s+(?:as\\s+)?([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?))?",
            Pattern.CASE_INSENSITIVE);

    private static final Set<String> TABLE_CONTEXTS = Set.of(
            "FROM", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
            "UPDATE", "INTO", "TABLE", "TRUNCATE");
    private static final Set<String> COLUMN_CONTEXTS = Set.of(
            "SELECT", "WHERE", "HAVING", "ON", "AND", "OR", "SET", "BY", "WHEN");
    private static final Set<String> JOIN_KEYWORDS = Set.of(
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS");

    private final SchemaCache cache;

    public SqlAutocompleteEngine(SchemaCache cache) {
        this.cache = cache;
    }

    /** Auto-popup path — selective, like DataGrip's basic completion. */
    public List<Suggestion> suggest(String sql, int caret) {
        return suggest(sql, caret, false);
    }

    /**
     * @param invoked {@code true} for Ctrl+Space (broader list, including keywords)
     */
    public List<Suggestion> suggest(String sql, int caret, boolean invoked) {
        if (sql == null || caret < 0 || caret > sql.length()) {
            return List.of();
        }
        if (insideStringOrComment(sql, caret)) {
            return List.of();
        }

        String before = sql.substring(0, caret);
        Map<String, String> aliases = parseAliases(before);

        Matcher dot = DOT_QUALIFIER.matcher(before);
        if (dot.find()) {
            String qualifier = dot.group(1);
            String prefix = dot.group(2);
            int replaceStart = caret - prefix.length();
            String table = aliases.getOrDefault(qualifier.toLowerCase(Locale.ROOT), qualifier);
            return rank(columnSuggestions(table, prefix, replaceStart, caret));
        }

        Matcher word = WORD.matcher(before);
        String prefix = word.find() ? word.group() : "";
        int replaceStart = caret - prefix.length();
        String previous = previousToken(before, replaceStart);
        String previousUpper = previous.toUpperCase(Locale.ROOT);

        if (!invoked && !shouldAutoPopup(before, prefix, previousUpper)) {
            return List.of();
        }

        if (isJoinContext(previousUpper) && cache.isReady()) {
            List<Suggestion> out = new ArrayList<>();
            out.addAll(joinSuggestions(aliases.keySet(), prefix, replaceStart, caret));
            out.addAll(tableSuggestions(prefix, replaceStart, caret));
            if (invoked || !prefix.isEmpty()) {
                out.addAll(keywordSuggestions(prefix, replaceStart, caret, Set.of("JOIN", "ON", "AS")));
            }
            return rank(out);
        }

        if (TABLE_CONTEXTS.contains(previousUpper)) {
            List<Suggestion> out = new ArrayList<>(tableSuggestions(prefix, replaceStart, caret));
            // Only a few structural keywords here — never dump the whole keyword list.
            if (invoked || prefix.length() >= 1) {
                out.addAll(keywordSuggestions(prefix, replaceStart, caret, Set.of("AS", "JOIN", "ON", "WHERE")));
            }
            return rank(out);
        }

        if (COLUMN_CONTEXTS.contains(previousUpper)) {
            List<Suggestion> out = new ArrayList<>();
            if (cache.isReady()) {
                out.addAll(columnsInScope(aliases, prefix, replaceStart, caret));
                if (aliases.isEmpty() && ("SELECT".equals(previousUpper) || invoked)) {
                    out.addAll(allColumnSuggestions(prefix, replaceStart, caret));
                }
            }
            if ("SELECT".equals(previousUpper) && (prefix.isEmpty() || "*".startsWith(prefix))) {
                // DataGrip always offers * early in a SELECT list.
                out.add(0, new Suggestion("*", "*", "all columns", Kind.KEYWORD, replaceStart, caret, 1_000, true));
            }
            if (invoked || prefix.length() >= 2) {
                out.addAll(keywordSuggestions(prefix, replaceStart, caret, null));
            }
            return rank(out);
        }

        // Free typing / Ctrl+Space with no keyword context.
        List<Suggestion> out = new ArrayList<>();
        if (invoked || prefix.length() >= 2) {
            out.addAll(keywordSuggestions(prefix, replaceStart, caret, null));
        }
        if (cache.isReady() && (invoked || prefix.length() >= 1)) {
            out.addAll(tableSuggestions(prefix, replaceStart, caret));
            if (!aliases.isEmpty()) {
                out.addAll(columnsInScope(aliases, prefix, replaceStart, caret));
            }
        }
        return rank(out);
    }

    /**
     * Whether basic completion should open by itself. Mirrors DataGrip: popup after
     * {@code .}, after a clause keyword, or once the user has typed a real prefix —
     * not on every keystroke in empty space.
     */
    public boolean shouldAutoPopup(String beforeCaret, String prefix, String previousUpper) {
        if (beforeCaret.endsWith(".") || DOT_QUALIFIER.matcher(beforeCaret).find()) {
            return true;
        }
        if (TABLE_CONTEXTS.contains(previousUpper)
                || COLUMN_CONTEXTS.contains(previousUpper)
                || JOIN_KEYWORDS.contains(previousUpper)) {
            return true;
        }
        // Continuations while filtering an identifier.
        return prefix.length() >= 2;
    }

    public boolean shouldAutoPopup(String sql, int caret) {
        if (sql == null || caret < 0 || caret > sql.length() || insideStringOrComment(sql, caret)) {
            return false;
        }
        String before = sql.substring(0, caret);
        Matcher word = WORD.matcher(before);
        String prefix = word.find() ? word.group() : "";
        int replaceStart = caret - prefix.length();
        return shouldAutoPopup(before, prefix, previousToken(before, replaceStart).toUpperCase(Locale.ROOT));
    }

    // ---------------------------------------------------------------- builders

    private List<Suggestion> keywordSuggestions(
            String prefix, int start, int end, Set<String> only) {
        List<Suggestion> out = new ArrayList<>();
        for (String keyword : KEYWORDS) {
            if (only != null && !only.contains(keyword)) {
                continue;
            }
            int score = matchScore(keyword, prefix);
            if (score < 0) {
                continue;
            }
            out.add(new Suggestion(
                    keyword, keyword, "keyword", Kind.KEYWORD, start, end, score + 10,
                    SPACE_AFTER.contains(keyword)));
        }
        return out;
    }

    private List<Suggestion> tableSuggestions(String prefix, int start, int end) {
        if (!cache.isReady()) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        for (SchemaNode table : cache.tables()) {
            int score = matchScore(table.name(), prefix);
            if (score < 0) {
                continue;
            }
            boolean view = table.type() == SchemaNode.NodeType.VIEW;
            out.add(new Suggestion(
                    table.name(), table.name(), view ? "view" : "table", Kind.TABLE,
                    start, end, score + 80, false));
        }
        return out;
    }

    private List<Suggestion> columnSuggestions(String table, String prefix, int start, int end) {
        List<Suggestion> out = new ArrayList<>();
        for (SchemaNode column : cache.columnsOf(table)) {
            int score = matchScore(column.name(), prefix);
            if (score < 0) {
                continue;
            }
            String type = column.metadata(SchemaNode.META_DATA_TYPE);
            String detail = type == null ? table : type;
            int boost = column.metadataFlag(SchemaNode.META_PRIMARY_KEY) ? 15 : 0;
            out.add(new Suggestion(
                    column.name(), column.name(), detail, Kind.COLUMN,
                    start, end, score + 100 + boost, false));
        }
        return out;
    }

    private List<Suggestion> columnsInScope(
            Map<String, String> aliases, String prefix, int start, int end) {
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> out = new ArrayList<>();
        for (String table : new LinkedHashSet<>(aliases.values())) {
            for (SchemaNode column : cache.columnsOf(table)) {
                if (!seen.add(column.name().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                int score = matchScore(column.name(), prefix);
                if (score < 0) {
                    continue;
                }
                String type = column.metadata(SchemaNode.META_DATA_TYPE);
                String detail = type == null ? table : type + " · " + table;
                int boost = column.metadataFlag(SchemaNode.META_PRIMARY_KEY) ? 15 : 0;
                out.add(new Suggestion(
                        column.name(), column.name(), detail, Kind.COLUMN,
                        start, end, score + 100 + boost, false));
            }
        }
        return out;
    }

    private List<Suggestion> allColumnSuggestions(String prefix, int start, int end) {
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> out = new ArrayList<>();
        for (SchemaNode table : cache.tables()) {
            for (SchemaNode column : table.children()) {
                String key = column.name().toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                int score = matchScore(column.name(), prefix);
                if (score < 0) {
                    continue;
                }
                String type = column.metadata(SchemaNode.META_DATA_TYPE);
                out.add(new Suggestion(
                        column.name(), column.name(),
                        type == null ? table.name() : type + " · " + table.name(),
                        Kind.COLUMN, start, end, score + 60, false));
            }
        }
        return out;
    }

    private List<Suggestion> joinSuggestions(
            Set<String> tablesInScope, String prefix, int start, int end) {
        List<Suggestion> out = new ArrayList<>();
        for (SchemaCache.JoinSuggestion join : cache.joinSuggestions(tablesInScope)) {
            int score = matchScore(join.toTable(), prefix);
            if (score < 0 && !prefix.isEmpty()
                    && !join.insertText().toLowerCase(Locale.ROOT).contains(prefix.toLowerCase(Locale.ROOT))) {
                continue;
            }
            if (score < 0) {
                score = 40;
            }
            String onClause = join.insertText();
            int onAt = onClause.toUpperCase(Locale.ROOT).indexOf(" ON ");
            String detail = onAt >= 0 ? onClause.substring(onAt + 1).trim() : onClause;
            out.add(new Suggestion(
                    join.insertText(), join.toTable(),
                    detail,
                    Kind.JOIN, start, end, score + 120, false));
        }
        return out;
    }

    // ---------------------------------------------------------------- ranking / match

    /**
     * Higher is better. {@code -1} means no match.
     * Prefers prefix matches (DataGrip-like), then substring, then camel/snake gaps.
     */
    static int matchScore(String candidate, String prefix) {
        if (prefix == null || prefix.isEmpty()) {
            return 50;
        }
        String c = candidate.toLowerCase(Locale.ROOT);
        String p = prefix.toLowerCase(Locale.ROOT);
        if (c.equals(p)) {
            return 200;
        }
        if (c.startsWith(p)) {
            return 150 - Math.min(40, c.length() - p.length());
        }
        int at = c.indexOf(p);
        if (at > 0) {
            return 80 - Math.min(30, at);
        }
        // snake_case / camelCase initials: "uid" → "user_id"
        if (fuzzyInitials(c, p)) {
            return 70;
        }
        return -1;
    }

    private static boolean fuzzyInitials(String candidate, String prefix) {
        StringBuilder initials = new StringBuilder();
        boolean border = true;
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (ch == '_' || ch == '-') {
                border = true;
                continue;
            }
            if (border || Character.isUpperCase(candidate.charAt(i))) {
                initials.append(Character.toLowerCase(ch));
                border = false;
            }
        }
        return initials.toString().startsWith(prefix);
    }

    private static List<Suggestion> rank(List<Suggestion> suggestions) {
        suggestions.sort(Comparator
                .comparingInt(Suggestion::score).reversed()
                .thenComparing(Suggestion::name, String.CASE_INSENSITIVE_ORDER));
        // Deduplicate by insert text, keep highest score.
        Map<String, Suggestion> unique = new LinkedHashMap<>();
        for (Suggestion suggestion : suggestions) {
            unique.putIfAbsent(suggestion.insertText().toLowerCase(Locale.ROOT), suggestion);
        }
        List<Suggestion> ranked = new ArrayList<>(unique.values());
        return ranked.size() <= 50 ? List.copyOf(ranked) : List.copyOf(ranked.subList(0, 50));
    }

    // ---------------------------------------------------------------- parsing

    static Map<String, String> parseAliases(String sqlBeforeCaret) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Matcher matcher = TABLE_REF.matcher(sqlBeforeCaret);
        while (matcher.find()) {
            String table = stripQuotes(matcher.group(1));
            String alias = matcher.group(2) == null ? table : stripQuotes(matcher.group(2));
            if (isReserved(alias)) {
                alias = table;
            }
            aliases.put(alias.toLowerCase(Locale.ROOT), table);
            aliases.put(table.toLowerCase(Locale.ROOT), table);
        }
        return aliases;
    }

    private static boolean isJoinContext(String previousUpper) {
        return JOIN_KEYWORDS.contains(previousUpper);
    }

    private static String previousToken(String before, int wordStart) {
        int i = wordStart - 1;
        while (i >= 0 && Character.isWhitespace(before.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return "";
        }
        int end = i + 1;
        while (i >= 0 && isIdentChar(before.charAt(i))) {
            i--;
        }
        return before.substring(i + 1, end);
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isReserved(String word) {
        String upper = word.toUpperCase(Locale.ROOT);
        for (String keyword : KEYWORDS) {
            if (keyword.equals(upper)) {
                return true;
            }
        }
        return JOIN_KEYWORDS.contains(upper);
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

    /** Cheap scan: skip completion inside quotes or line/block comments. */
    static boolean insideStringOrComment(String sql, int caret) {
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        for (int i = 0; i < caret; i++) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;
            if (inLineComment) {
                if (c == '\n') {
                    inLineComment = false;
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++;
                }
                continue;
            }
            if (inSingle) {
                if (c == '\'' && next == '\'') {
                    i++;
                } else if (c == '\'') {
                    inSingle = false;
                }
                continue;
            }
            if (inDouble) {
                if (c == '"' && next == '"') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
                continue;
            }
            if (inBacktick) {
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++;
            } else if (c == '/' && next == '*') {
                inBlockComment = true;
                i++;
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '`') {
                inBacktick = true;
            }
        }
        return inLineComment || inBlockComment || inSingle || inDouble || inBacktick;
    }
}
