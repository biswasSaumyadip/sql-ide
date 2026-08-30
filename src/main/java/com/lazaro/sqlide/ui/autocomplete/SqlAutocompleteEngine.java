package com.lazaro.sqlide.ui.autocomplete;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.sql.SqlTableScope;
import com.lazaro.sqlide.core.sql.SqlTableScope.ResolvedScope;
import com.lazaro.sqlide.ui.autocomplete.SqlCompletionDialect.Function;
import com.lazaro.sqlide.ui.autocomplete.SqlCompletionHygiene.Style;
import com.lazaro.sqlide.ui.autocomplete.SqlSnippetCatalog.Snippet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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
        VIEW,
        COLUMN,
        SCHEMA,
        INDEX,
        FUNCTION,
        JOIN,
        SNIPPET,
        PARAMETER
    }

    /**
     * @param insertText     text written into the editor (may contain {@code $name$} markers)
     * @param name           primary label (left column)
     * @param detail         secondary label (right column) — type, "table", …
     * @param trailingSpace  append a space after insert (keywords like {@code FROM})
     * @param documentation  short docs strip text (Quick Doc style)
     * @param placeholders   ordered {@code $name$} labels inside {@code insertText}
     */
    public record Suggestion(
            String insertText,
            String name,
            String detail,
            Kind kind,
            int replaceStart,
            int replaceEnd,
            int score,
            boolean trailingSpace,
            String documentation,
            List<String> placeholders
    ) {
        public Suggestion {
            documentation = documentation == null ? "" : documentation;
            placeholders = placeholders == null ? List.of() : List.copyOf(placeholders);
        }

        public Suggestion(
                String insertText,
                String name,
                String detail,
                Kind kind,
                int replaceStart,
                int replaceEnd,
                int score,
                boolean trailingSpace) {
            this(insertText, name, detail, kind, replaceStart, replaceEnd, score, trailingSpace, "", List.of());
        }

        /** Stable identity for selection while the filtered list refreshes. */
        public String selectionKey() {
            return kind.name() + "|" + insertText.toLowerCase(Locale.ROOT);
        }
    }

    /** Ranked suggestions plus how many matched before the hard display cap. */
    public record SuggestResult(List<Suggestion> items, int totalMatched) {
        public SuggestResult {
            items = items == null ? List.of() : List.copyOf(items);
        }

        public static SuggestResult empty() {
            return new SuggestResult(List.of(), 0);
        }

        public boolean isEmpty() {
            return items.isEmpty();
        }
    }

    private static final int DISPLAY_CAP = 50;

    private static final Pattern WORD = Pattern.compile("[A-Za-z0-9_]*$");
    private static final Pattern DOT_QUALIFIER = Pattern.compile(
            "([A-Za-z0-9_]+(?:\\s*\\.\\s*[A-Za-z0-9_]+)?)\\.\\s*([A-Za-z0-9_]*)$");
    /** Caret is inside {@code INSERT INTO t (…)} column list (parens still open). */
    private static final Pattern INSERT_COLUMN_LIST = Pattern.compile(
            "(?i)\\bINSERT\\s+INTO\\s+(?:[`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?\\s*\\.\\s*)?"
                    + "([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?)\\s*\\(([^()]*)$");
    /** Open SELECT list: {@code SELECT …} before FROM / clause keywords. */
    private static final Pattern SELECT_LIST = Pattern.compile(
            "(?i)\\bSELECT\\s+(DISTINCT\\s+)?([^;]*?)$");

    private static final Set<String> TABLE_CONTEXTS = Set.of(
            "FROM", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS",
            "UPDATE", "INTO", "TABLE", "TRUNCATE", "USE");
    private static final Set<String> COLUMN_CONTEXTS = Set.of(
            "SELECT", "WHERE", "HAVING", "ON", "AND", "OR", "SET", "BY", "WHEN", "RETURNING");
    private static final Set<String> JOIN_KEYWORDS = Set.of(
            "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS");
    private static final Set<String> INDEX_CONTEXTS = Set.of("INDEX", "KEY");
    private static final Set<String> SCHEMA_CONTEXTS = Set.of("USE", "SCHEMA", "DATABASE");

    /** Catalogs / schemas whose objects should sink to the bottom of table suggestions. */
    private static final Set<String> SYSTEM_CATALOGS = Set.of(
            "INFORMATION_SCHEMA", "PG_CATALOG", "PG_TOAST", "SYS", "SYSTEM LOBS",
            "MYSQL", "PERFORMANCE_SCHEMA");

    private static final String[] SYSTEM_NAME_PREFIXES = {
            "schema_", "information_", "pg_", "sql_"
    };

    private final SchemaCache cache;
    private final Supplier<String> activeCatalog;
    private final Supplier<ConnectionConfig.Driver> dialect;
    private final Supplier<Map<String, String>> knownParams;
    private final Style style;

    public SqlAutocompleteEngine(SchemaCache cache) {
        this(cache, () -> null, () -> ConnectionConfig.Driver.MYSQL, Map::of, Style.defaults());
    }

    public SqlAutocompleteEngine(SchemaCache cache, Supplier<String> activeCatalog) {
        this(cache, activeCatalog, () -> ConnectionConfig.Driver.MYSQL, Map::of, Style.defaults());
    }

    public SqlAutocompleteEngine(
            SchemaCache cache,
            Supplier<String> activeCatalog,
            Supplier<ConnectionConfig.Driver> dialect) {
        this(cache, activeCatalog, dialect, Map::of, Style.defaults());
    }

    public SqlAutocompleteEngine(
            SchemaCache cache,
            Supplier<String> activeCatalog,
            Supplier<ConnectionConfig.Driver> dialect,
            Supplier<Map<String, String>> knownParams,
            Style style) {
        this.cache = cache;
        this.activeCatalog = activeCatalog == null ? () -> null : activeCatalog;
        this.dialect = dialect == null ? () -> ConnectionConfig.Driver.MYSQL : dialect;
        this.knownParams = knownParams == null ? Map::of : knownParams;
        this.style = style == null ? Style.defaults() : style;
    }

    /** Auto-popup path — selective, like DataGrip's basic completion. */
    public SuggestResult suggest(String sql, int caret) {
        return suggest(sql, caret, false);
    }

    /**
     * @param invoked {@code true} for Ctrl+Space (broader list, including keywords)
     */
    public SuggestResult suggest(String sql, int caret, boolean invoked) {
        if (sql == null || caret < 0 || caret > sql.length()) {
            return SuggestResult.empty();
        }
        if (insideStringOrComment(sql, caret)) {
            return SuggestResult.empty();
        }

        String before = sql.substring(0, caret);
        ResolvedScope scope = resolveScope(before);
        Map<String, String> aliases = scope.aliases();

        // :name parameter completion (colon immediately before the identifier).
        Matcher word = WORD.matcher(before);
        String prefix = word.find() ? word.group() : "";
        int replaceStart = caret - prefix.length();
        if (replaceStart > 0 && before.charAt(replaceStart - 1) == ':') {
            return rank(parameterSuggestions(prefix, replaceStart, caret, false));
        }
        // Bare "?" — offer named params from the run config as :name replacements.
        if ((prefix.isEmpty() && before.endsWith("?"))
                || (replaceStart > 0 && before.charAt(replaceStart - 1) == '?' && prefix.isEmpty())) {
            int qStart = before.endsWith("?") ? caret - 1 : replaceStart - 1;
            return rank(parameterSuggestions("", qStart, caret, true));
        }

        Matcher dot = DOT_QUALIFIER.matcher(before);
        if (dot.find()) {
            String qualifier = dot.group(1);
            String dotPrefix = dot.group(2);
            int dotStart = caret - dotPrefix.length();
            // catalog.table — when qualifier is a known catalog, offer its tables.
            if (isKnownCatalog(qualifier) && !aliases.containsKey(qualifier.toLowerCase(Locale.ROOT))) {
                return rank(tableSuggestionsInCatalog(qualifier, dotPrefix, dotStart, caret, scope));
            }
            String table = aliases.getOrDefault(qualifier.toLowerCase(Locale.ROOT), qualifier);
            return rank(columnSuggestions(table, dotPrefix, dotStart, caret, Set.of(), scope));
        }

        String previous = previousToken(before, replaceStart);
        String previousUpper = previous.toUpperCase(Locale.ROOT);

        // INSERT INTO t (col…) — only columns of that table, already-typed demoted.
        String insertTable = insertColumnListTable(before);
        if (insertTable != null) {
            Set<String> used = usedInsertColumns(before);
            return rank(columnSuggestions(insertTable, prefix, replaceStart, caret, used, scope));
        }

        if (!invoked && !shouldAutoPopup(before, prefix, previousUpper)) {
            return SuggestResult.empty();
        }

        if (isJoinContext(previousUpper) && cache.isReady()) {
            List<Suggestion> out = new ArrayList<>();
            out.addAll(joinSuggestions(aliases.keySet(), prefix, replaceStart, caret));
            out.addAll(tableSuggestions(prefix, replaceStart, caret, scope));
            out.addAll(schemaSuggestions(prefix, replaceStart, caret));
            if (invoked || !prefix.isEmpty()) {
                out.addAll(keywordSuggestions(prefix, replaceStart, caret, Set.of("JOIN", "ON", "AS")));
            }
            return rank(out);
        }

        if (SCHEMA_CONTEXTS.contains(previousUpper)) {
            List<Suggestion> out = new ArrayList<>(schemaSuggestions(prefix, replaceStart, caret));
            out.addAll(tableSuggestions(prefix, replaceStart, caret, scope));
            return rank(out);
        }

        if (INDEX_CONTEXTS.contains(previousUpper) && cache.isReady()) {
            List<Suggestion> out = new ArrayList<>(indexSuggestions(aliases, prefix, replaceStart, caret));
            out.addAll(tableSuggestions(prefix, replaceStart, caret, scope));
            return rank(out);
        }

        if (TABLE_CONTEXTS.contains(previousUpper)) {
            List<Suggestion> out = new ArrayList<>(tableSuggestions(prefix, replaceStart, caret, scope));
            out.addAll(schemaSuggestions(prefix, replaceStart, caret));
            if (invoked || prefix.length() >= 1) {
                out.addAll(keywordSuggestions(prefix, replaceStart, caret, Set.of("AS", "JOIN", "ON", "WHERE")));
            }
            return rank(out);
        }

        if (COLUMN_CONTEXTS.contains(previousUpper)) {
            List<Suggestion> out = new ArrayList<>();
            Set<String> usedInSelect = "SELECT".equals(previousUpper)
                    ? usedSelectColumns(before)
                    : Set.of();
            if (cache.isReady() || !scope.virtualColumns().isEmpty()) {
                out.addAll(columnsInScope(aliases, prefix, replaceStart, caret, usedInSelect, scope));
                if (aliases.isEmpty() && ("SELECT".equals(previousUpper) || invoked)) {
                    out.addAll(allColumnSuggestions(prefix, replaceStart, caret, usedInSelect));
                }
            }
            if ("SELECT".equals(previousUpper) && (prefix.isEmpty() || "*".startsWith(prefix))) {
                out.add(0, suggestion("*", "*", "all columns", Kind.KEYWORD, replaceStart, caret, 1_000, true,
                        "Select every column from the FROM clause.", List.of()));
            }
            if (invoked || prefix.length() >= 2) {
                out.addAll(functionSuggestions(prefix, replaceStart, caret));
                out.addAll(keywordSuggestions(prefix, replaceStart, caret, null));
            }
            return rank(out);
        }

        // Free typing / Ctrl+Space with no keyword context.
        List<Suggestion> out = new ArrayList<>();
        if (invoked || prefix.length() >= 2) {
            out.addAll(snippetSuggestions(prefix, replaceStart, caret));
            out.addAll(functionSuggestions(prefix, replaceStart, caret));
            out.addAll(keywordSuggestions(prefix, replaceStart, caret, null));
            out.addAll(parameterSuggestions(prefix, replaceStart, caret, false));
        }
        if ((cache.isReady() || !scope.cteNames().isEmpty()) && (invoked || prefix.length() >= 1)) {
            out.addAll(schemaSuggestions(prefix, replaceStart, caret));
            out.addAll(tableSuggestions(prefix, replaceStart, caret, scope));
            out.addAll(indexSuggestions(aliases, prefix, replaceStart, caret));
            if (!aliases.isEmpty()) {
                out.addAll(columnsInScope(aliases, prefix, replaceStart, caret, Set.of(), scope));
            }
        }
        return rank(out);
    }

    public boolean shouldAutoPopup(String beforeCaret, String prefix, String previousUpper) {
        if (beforeCaret.endsWith(".") || DOT_QUALIFIER.matcher(beforeCaret).find()) {
            return true;
        }
        if (isInsertColumnListContext(beforeCaret)) {
            return true;
        }
        int wordStart = beforeCaret.length() - prefix.length();
        if (wordStart > 0 && beforeCaret.charAt(wordStart - 1) == ':') {
            return true;
        }
        if (beforeCaret.endsWith("?")) {
            return true;
        }
        if (TABLE_CONTEXTS.contains(previousUpper)
                || COLUMN_CONTEXTS.contains(previousUpper)
                || JOIN_KEYWORDS.contains(previousUpper)
                || INDEX_CONTEXTS.contains(previousUpper)
                || SCHEMA_CONTEXTS.contains(previousUpper)) {
            return true;
        }
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

    private List<Suggestion> parameterSuggestions(
            String prefix, int start, int end, boolean replaceQuestion) {
        Map<String, String> params = knownParams.get();
        if (params == null || params.isEmpty()) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String name = entry.getKey();
            if (name == null || name.isBlank() || name.startsWith("?")) {
                continue;
            }
            int score = matchScore(name, prefix);
            if (score < 0) {
                continue;
            }
            String insert = replaceQuestion ? ":" + name : name;
            String detail = entry.getValue() == null || entry.getValue().isBlank()
                    ? "parameter"
                    : "default · " + entry.getValue();
            out.add(suggestion(
                    insert, name, detail, Kind.PARAMETER, start, end, score + 140, false,
                    "Parameter `:" + name + "`"
                            + (entry.getValue() == null || entry.getValue().isBlank()
                            ? "."
                            : " (default: " + entry.getValue() + ")."),
                    List.of()));
        }
        return out;
    }

    private List<Suggestion> keywordSuggestions(
            String prefix, int start, int end, Set<String> only) {
        ConnectionConfig.Driver driver = currentDialect();
        List<Suggestion> out = new ArrayList<>();
        String dialect = SqlCompletionDialect.dialectLabel(driver);
        for (String keyword : SqlCompletionDialect.keywords(driver)) {
            if (only != null && !only.contains(keyword)) {
                continue;
            }
            int score = matchScore(keyword, prefix);
            if (score < 0) {
                continue;
            }
            String insert = SqlCompletionHygiene.finalizeInsert(
                    keyword, keyword, Kind.KEYWORD, driver, style);
            out.add(suggestion(
                    insert, keyword, dialect + " keyword", Kind.KEYWORD, start, end, score + 10,
                    SqlCompletionDialect.isSpaceAfterKeyword(keyword),
                    keyword + " — " + dialect + " keyword.", List.of()));
        }
        if (only == null) {
            for (String phrase : SqlCompletionDialect.keywordPhrases(driver)) {
                int score = matchScore(phrase, prefix);
                if (score < 0) {
                    String firstWord = phrase.split("\\s+")[0];
                    score = matchScore(firstWord, prefix);
                    if (score < 0 || prefix.length() < 2) {
                        continue;
                    }
                    score = Math.max(score - 5, 40);
                }
                String insert = SqlCompletionHygiene.finalizeInsert(
                        phrase, phrase, Kind.KEYWORD, driver, style);
                out.add(suggestion(
                        insert, phrase, dialect + " keyword", Kind.KEYWORD, start, end, score + 25,
                        false, phrase + " — " + dialect + " keyword phrase.", List.of()));
            }
        }
        return out;
    }

    private List<Suggestion> functionSuggestions(String prefix, int start, int end) {
        List<Suggestion> out = new ArrayList<>();
        for (Function function : SqlCompletionDialect.functions(currentDialect())) {
            int score = matchScore(function.name(), prefix);
            if (score < 0) {
                score = matchScore(function.insertText(), prefix);
            }
            if (score < 0) {
                continue;
            }
            out.add(suggestion(
                    function.insertText(), function.name(), function.detail(), Kind.FUNCTION,
                    start, end, score + 55, false,
                    function.documentation(),
                    SqlSnippetCatalog.placeholderLabels(function.insertText())));
        }
        return out;
    }

    private List<Suggestion> snippetSuggestions(String prefix, int start, int end) {
        if (prefix.isEmpty()) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        String p = prefix.toLowerCase(Locale.ROOT);
        for (Snippet snippet : SqlSnippetCatalog.all()) {
            int score = matchScore(snippet.abbrev(), p);
            if (score < 0) {
                continue;
            }
            // Prefer exact / prefix abbrev hits strongly over accidental keyword collisions.
            int boost = snippet.abbrev().equalsIgnoreCase(prefix) ? 90 : 70;
            out.add(suggestion(
                    snippet.template(), snippet.name(), "snippet", Kind.SNIPPET,
                    start, end, score + boost, false,
                    snippet.documentation(),
                    SqlSnippetCatalog.placeholderLabels(snippet.template())));
        }
        return out;
    }

    private List<Suggestion> schemaSuggestions(String prefix, int start, int end) {
        if (!cache.isReady()) {
            return List.of();
        }
        List<Suggestion> out = new ArrayList<>();
        ConnectionConfig.Driver driver = currentDialect();
        for (SchemaNode catalog : cache.catalogs()) {
            int score = matchScore(catalog.name(), prefix);
            if (score < 0) {
                continue;
            }
            boolean system = SYSTEM_CATALOGS.contains(catalog.name().toUpperCase(Locale.ROOT));
            String insert = SqlCompletionHygiene.finalizeInsert(
                    catalog.name(), catalog.name(), Kind.SCHEMA, driver, style);
            out.add(suggestion(
                    insert, catalog.name(), system ? "system schema" : "schema",
                    Kind.SCHEMA, start, end, score + (system ? -50 : 75), false,
                    "Schema / database `" + catalog.name() + "`.", List.of()));
        }
        return out;
    }

    private List<Suggestion> tableSuggestions(String prefix, int start, int end, ResolvedScope scope) {
        return tableSuggestionsInCatalog(activeCatalog(), prefix, start, end, scope);
    }

    private List<Suggestion> tableSuggestionsInCatalog(
            String catalog, String prefix, int start, int end, ResolvedScope scope) {
        List<Suggestion> out = new ArrayList<>();
        ConnectionConfig.Driver driver = currentDialect();

        // CTE names behave like tables.
        if (scope != null) {
            for (String cte : scope.cteNames()) {
                int score = matchScore(cte, prefix);
                if (score < 0) {
                    continue;
                }
                String insert = SqlCompletionHygiene.finalizeInsert(
                        cte, cte, Kind.TABLE, driver, style);
                out.add(suggestion(
                        insert, cte, "cte", Kind.TABLE, start, end, score + 85, false,
                        "Common table expression `" + cte + "`.", List.of()));
            }
        }

        if (!cache.isReady()) {
            return out;
        }
        for (SchemaNode table : cache.tables(catalog)) {
            int score = matchScore(table.name(), prefix);
            if (score < 0) {
                continue;
            }
            boolean view = table.type() == SchemaNode.NodeType.VIEW;
            boolean system = isLowPriorityTable(table);
            int boost = system ? -400 : (view ? 75 : 80);
            Kind kind = view ? Kind.VIEW : Kind.TABLE;
            String detail = view ? "view" : (system ? "system" : "table");
            String doc = (view ? "View" : "Table") + " `" + table.name() + "`"
                    + (catalog == null || catalog.isBlank() ? "" : " in " + catalog) + ".";
            String insert = SqlCompletionHygiene.finalizeInsert(
                    table.name(), table.name(), kind, driver, style);
            out.add(suggestion(
                    insert, table.name(), detail, kind, start, end, score + boost, false,
                    doc, List.of()));
        }
        return out;
    }

    private List<Suggestion> columnSuggestions(
            String table,
            String prefix,
            int start,
            int end,
            Set<String> usedLower,
            ResolvedScope scope) {
        List<Suggestion> out = new ArrayList<>();
        ConnectionConfig.Driver driver = currentDialect();

        if (scope != null && scope.isVirtual(table)) {
            for (String column : scope.columnsOf(table)) {
                int score = matchScore(column, prefix);
                if (score < 0) {
                    continue;
                }
                int boost = 0;
                String detail = "cte";
                if (usedLower.contains(column.toLowerCase(Locale.ROOT))) {
                    boost -= 90;
                    detail = "cte · already listed";
                }
                String insert = SqlCompletionHygiene.finalizeInsert(
                        column, column, Kind.COLUMN, driver, style);
                out.add(suggestion(
                        insert, column, detail, Kind.COLUMN,
                        start, end, score + 100 + boost, false,
                        "Column `" + column + "` from CTE / subquery `" + table + "`.", List.of()));
            }
            return out;
        }

        for (SchemaNode column : cache.columnsOf(table, activeCatalog())) {
            int score = matchScore(column.name(), prefix);
            if (score < 0) {
                continue;
            }
            String type = column.metadata(SchemaNode.META_DATA_TYPE);
            String detail = type == null ? table : type;
            int boost = column.metadataFlag(SchemaNode.META_PRIMARY_KEY) ? 15 : 0;
            if (usedLower.contains(column.name().toLowerCase(Locale.ROOT))) {
                boost -= 90;
                detail = (detail == null ? "" : detail + " · ") + "already listed";
            }
            String insert = SqlCompletionHygiene.finalizeInsert(
                    column.name(), column.name(), Kind.COLUMN, driver, style);
            out.add(suggestion(
                    insert, column.name(), detail, Kind.COLUMN,
                    start, end, score + 100 + boost, false,
                    columnDoc(table, column), List.of()));
        }
        return out;
    }

    private List<Suggestion> columnsInScope(
            Map<String, String> aliases,
            String prefix,
            int start,
            int end,
            Set<String> usedLower,
            ResolvedScope scope) {
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> out = new ArrayList<>();
        ConnectionConfig.Driver driver = currentDialect();
        for (String table : new LinkedHashSet<>(aliases.values())) {
            if (scope != null && scope.isVirtual(table)) {
                for (String column : scope.columnsOf(table)) {
                    if (!seen.add(column.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    int score = matchScore(column, prefix);
                    if (score < 0) {
                        continue;
                    }
                    int boost = 0;
                    String detail = "cte · " + table;
                    if (usedLower.contains(column.toLowerCase(Locale.ROOT))) {
                        boost -= 90;
                        detail = detail + " · already listed";
                    }
                    String insert = SqlCompletionHygiene.finalizeInsert(
                            column, column, Kind.COLUMN, driver, style);
                    out.add(suggestion(
                            insert, column, detail, Kind.COLUMN,
                            start, end, score + 100 + boost, false,
                            "Column `" + column + "` from `" + table + "`.", List.of()));
                }
                continue;
            }
            for (SchemaNode column : cache.columnsOf(table, activeCatalog())) {
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
                if (usedLower.contains(column.name().toLowerCase(Locale.ROOT))) {
                    boost -= 90;
                    detail = detail + " · already listed";
                }
                String insert = SqlCompletionHygiene.finalizeInsert(
                        column.name(), column.name(), Kind.COLUMN, driver, style);
                out.add(suggestion(
                        insert, column.name(), detail, Kind.COLUMN,
                        start, end, score + 100 + boost, false,
                        columnDoc(table, column), List.of()));
            }
        }
        return out;
    }

    private List<Suggestion> allColumnSuggestions(
            String prefix, int start, int end, Set<String> usedLower) {
        Set<String> seen = new LinkedHashSet<>();
        List<Suggestion> out = new ArrayList<>();
        ConnectionConfig.Driver driver = currentDialect();
        for (SchemaNode table : cache.tables(activeCatalog())) {
            if (isLowPriorityTable(table)) {
                continue;
            }
            for (SchemaNode column : table.children()) {
                if (column.type() != SchemaNode.NodeType.COLUMN) {
                    continue;
                }
                String key = column.name().toLowerCase(Locale.ROOT);
                if (!seen.add(key)) {
                    continue;
                }
                int score = matchScore(column.name(), prefix);
                if (score < 0) {
                    continue;
                }
                String type = column.metadata(SchemaNode.META_DATA_TYPE);
                String detail = type == null ? table.name() : type + " · " + table.name();
                int boost = 60;
                if (usedLower.contains(key)) {
                    boost -= 90;
                    detail = detail + " · already listed";
                }
                String insert = SqlCompletionHygiene.finalizeInsert(
                        column.name(), column.name(), Kind.COLUMN, driver, style);
                out.add(suggestion(
                        insert, column.name(), detail,
                        Kind.COLUMN, start, end, score + boost, false,
                        columnDoc(table.name(), column), List.of()));
            }
        }
        return out;
    }

    private List<Suggestion> indexSuggestions(
            Map<String, String> aliases, String prefix, int start, int end) {
        if (!cache.isReady()) {
            return List.of();
        }
        Set<String> tables = new LinkedHashSet<>(aliases.values());
        if (tables.isEmpty()) {
            for (SchemaNode table : cache.tables(activeCatalog())) {
                if (!isLowPriorityTable(table)) {
                    tables.add(table.name());
                }
            }
        }
        List<Suggestion> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String tableName : tables) {
            cache.findTable(tableName, activeCatalog()).ifPresent(table -> {
                for (SchemaMetadataCodec.IndexInfo index :
                        SchemaMetadataCodec.decodeIndexes(table.metadata(SchemaNode.META_INDEXES))) {
                    if (index.name() == null || index.name().isBlank()) {
                        continue;
                    }
                    if (!seen.add(index.name().toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    int score = matchScore(index.name(), prefix);
                    if (score < 0) {
                        continue;
                    }
                    String cols = String.join(", ", index.columns());
                    String detail = (index.unique() ? "unique · " : "") + table.name()
                            + (cols.isEmpty() ? "" : " (" + cols + ")");
                    out.add(suggestion(
                            index.name(), index.name(), detail, Kind.INDEX,
                            start, end, score + 50, false,
                            "Index `" + index.name() + "` on `" + table.name() + "`"
                                    + (cols.isEmpty() ? "" : " (" + cols + ")") + ".",
                            List.of()));
                }
            });
        }
        return out;
    }

    private List<Suggestion> joinSuggestions(
            Set<String> tablesInScope, String prefix, int start, int end) {
        List<Suggestion> out = new ArrayList<>();
        for (SchemaCache.JoinSuggestion join : cache.joinSuggestions(tablesInScope)) {
            if (!joinTableInActiveCatalog(join.toTable())) {
                continue;
            }
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
            out.add(suggestion(
                    join.insertText(), join.toTable(), detail,
                    Kind.JOIN, start, end, score + 120, false,
                    "Join `" + join.toTable() + "` using foreign-key relationship.",
                    List.of()));
        }
        return out;
    }

    // ---------------------------------------------------------------- ranking / match

    /**
     * Higher is better. {@code -1} means no match.
     * Prefers prefix, then underscore-token / substring, initials, then 1-edit typos.
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
        // Mid-token at underscore / camel boundaries: "orders" → "sales_orders"
        int tokenScore = midTokenScore(c, p);
        if (tokenScore >= 0) {
            return tokenScore;
        }
        int at = c.indexOf(p);
        if (at > 0) {
            return 80 - Math.min(30, at);
        }
        if (fuzzyInitials(c, p)) {
            return 70;
        }
        // Typo tolerance: one edit against a same-length head (prefix length ≥ 3).
        if (p.length() >= 3) {
            String head = c.length() >= p.length() ? c.substring(0, p.length()) : c;
            if (editDistanceAtMostOne(head, p)) {
                return 52 - Math.min(10, Math.abs(c.length() - p.length()));
            }
            if (Math.abs(c.length() - p.length()) <= 1 && editDistanceAtMostOne(c, p)) {
                return 48;
            }
        }
        return -1;
    }

    /** Prefer matching a full segment after {@code _} / {@code -} (or camel hump). */
    private static int midTokenScore(String candidate, String prefix) {
        String[] parts = candidate.split("[_\\-]+");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.equals(prefix)) {
                return 110;
            }
            if (part.startsWith(prefix)) {
                return 95 - Math.min(20, part.length() - prefix.length());
            }
        }
        // CamelCase segments: salesOrders
        StringBuilder segment = new StringBuilder();
        List<String> camel = new ArrayList<>();
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (i > 0 && Character.isUpperCase(ch) && segment.length() > 0) {
                camel.add(segment.toString().toLowerCase(Locale.ROOT));
                segment.setLength(0);
            }
            if (ch != '_' && ch != '-') {
                segment.append(Character.toLowerCase(ch));
            }
        }
        if (segment.length() > 0) {
            camel.add(segment.toString());
        }
        for (String part : camel) {
            if (part.equals(prefix)) {
                return 108;
            }
            if (part.startsWith(prefix)) {
                return 93 - Math.min(20, part.length() - prefix.length());
            }
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

    /** True when Levenshtein distance between {@code a} and {@code b} is 0 or 1. */
    static boolean editDistanceAtMostOne(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        if (la > lb) {
            return editDistanceAtMostOne(b, a);
        }
        // la <= lb; lb - la is 0 or 1
        int i = 0;
        int j = 0;
        boolean used = false;
        while (i < la && j < lb) {
            if (a.charAt(i) == b.charAt(j)) {
                i++;
                j++;
                continue;
            }
            if (used) {
                return false;
            }
            used = true;
            if (la == lb) {
                i++;
                j++;
            } else {
                // insertion into a → advance only b
                j++;
            }
        }
        return true;
    }

    private static SuggestResult rank(List<Suggestion> suggestions) {
        suggestions.sort(Comparator
                .comparingInt(Suggestion::score).reversed()
                .thenComparing(Suggestion::name, String.CASE_INSENSITIVE_ORDER));
        Map<String, Suggestion> unique = new LinkedHashMap<>();
        for (Suggestion suggestion : suggestions) {
            unique.putIfAbsent(suggestion.insertText().toLowerCase(Locale.ROOT), suggestion);
        }
        List<Suggestion> ranked = new ArrayList<>(unique.values());
        int total = ranked.size();
        List<Suggestion> capped = total <= DISPLAY_CAP
                ? ranked
                : ranked.subList(0, DISPLAY_CAP);
        return new SuggestResult(List.copyOf(capped), total);
    }

    // ---------------------------------------------------------------- parsing

    ResolvedScope resolveScope(String sqlBeforeCaret) {
        return SqlTableScope.resolve(sqlBeforeCaret, cache, activeCatalog());
    }

    Map<String, String> resolveAliases(String sqlBeforeCaret) {
        return resolveScope(sqlBeforeCaret).aliases();
    }

    /** @deprecated prefer {@link #resolveAliases(String)}; kept for existing tests. */
    @Deprecated
    static Map<String, String> parseAliases(String sqlBeforeCaret) {
        return SqlTableScope.regexAliases(sqlBeforeCaret);
    }

    static String insertColumnListTable(String beforeCaret) {
        Matcher matcher = INSERT_COLUMN_LIST.matcher(beforeCaret);
        if (!matcher.find()) {
            return null;
        }
        return stripQuotes(matcher.group(1));
    }

    static boolean isInsertColumnListContext(String beforeCaret) {
        return insertColumnListTable(beforeCaret) != null;
    }

    /** Column names already present in an open {@code INSERT INTO t (…)} list. */
    static Set<String> usedInsertColumns(String beforeCaret) {
        Matcher matcher = INSERT_COLUMN_LIST.matcher(beforeCaret);
        if (!matcher.find()) {
            return Set.of();
        }
        return identList(matcher.group(2));
    }

    /**
     * Column / expression identifiers already typed in the SELECT list before the caret
     * (best-effort; ignores complex expressions beyond simple names).
     */
    static Set<String> usedSelectColumns(String beforeCaret) {
        Matcher matcher = SELECT_LIST.matcher(beforeCaret);
        if (!matcher.find()) {
            return Set.of();
        }
        String list = matcher.group(2);
        if (list == null) {
            return Set.of();
        }
        // Stop at FROM / clause keywords so we only see the projection list.
        String upper = list.toUpperCase(Locale.ROOT);
        int cut = indexOfClause(upper);
        if (cut >= 0) {
            list = list.substring(0, cut);
        }
        return identList(list);
    }

    private static int indexOfClause(String upperList) {
        int best = -1;
        for (String clause : List.of(" FROM ", " WHERE ", " GROUP ", " ORDER ", " HAVING ", " LIMIT ", " UNION ")) {
            int at = upperList.indexOf(clause);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    private static Set<String> identList(String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return Set.of();
        }
        Set<String> used = new LinkedHashSet<>();
        for (String part : fragment.split(",")) {
            String token = part.strip();
            if (token.isEmpty() || "*".equals(token)) {
                continue;
            }
            // Take the last identifier (alias or bare column); drop AS aliases' left side later.
            int asAt = token.toUpperCase(Locale.ROOT).lastIndexOf(" AS ");
            if (asAt >= 0) {
                token = token.substring(0, asAt).strip();
            }
            int dot = token.lastIndexOf('.');
            if (dot >= 0) {
                token = token.substring(dot + 1).strip();
            }
            token = stripQuotes(token);
            if (!token.isEmpty() && Character.isLetter(token.charAt(0))) {
                used.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return used;
    }

    private ConnectionConfig.Driver currentDialect() {
        ConnectionConfig.Driver driver = dialect.get();
        return driver == null ? ConnectionConfig.Driver.MYSQL : driver;
    }

    private String activeCatalog() {
        String catalog = activeCatalog.get();
        return catalog == null || catalog.isBlank() ? null : catalog;
    }

    private boolean isKnownCatalog(String name) {
        if (name == null || name.isBlank() || !cache.isReady()) {
            return false;
        }
        for (SchemaNode catalog : cache.catalogs()) {
            if (catalog.name().equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private boolean joinTableInActiveCatalog(String tableName) {
        String catalog = activeCatalog();
        if (catalog == null) {
            return true;
        }
        return cache.findTable(tableName, catalog)
                .map(table -> {
                    String meta = table.metadata(SchemaNode.META_CATALOG);
                    return meta == null || meta.isBlank() || meta.equalsIgnoreCase(catalog);
                })
                .orElse(false);
    }

    static boolean isLowPriorityTable(SchemaNode table) {
        String type = table.metadata(SchemaNode.META_TABLE_TYPE);
        if (type != null && type.toUpperCase(Locale.ROOT).contains("SYSTEM")) {
            return true;
        }
        String catalog = table.metadata(SchemaNode.META_CATALOG);
        if (catalog != null && SYSTEM_CATALOGS.contains(catalog.toUpperCase(Locale.ROOT))) {
            return true;
        }
        String name = table.name().toLowerCase(Locale.ROOT);
        for (String prefix : SYSTEM_NAME_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
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

    private static String columnDoc(String table, SchemaNode column) {
        String type = column.metadata(SchemaNode.META_DATA_TYPE);
        StringBuilder doc = new StringBuilder("Column `").append(column.name()).append("`");
        if (table != null && !table.isBlank()) {
            doc.append(" of `").append(table).append('`');
        }
        if (type != null && !type.isBlank()) {
            doc.append(" · ").append(type);
        }
        if (column.metadataFlag(SchemaNode.META_PRIMARY_KEY)) {
            doc.append(" · PRIMARY KEY");
        }
        doc.append('.');
        return doc.toString();
    }

    private static Suggestion suggestion(
            String insertText,
            String name,
            String detail,
            Kind kind,
            int start,
            int end,
            int score,
            boolean trailingSpace,
            String documentation,
            List<String> placeholders) {
        return new Suggestion(
                insertText, name, detail, kind, start, end, score, trailingSpace,
                documentation, placeholders);
    }

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
