package com.lazaro.sqlide.core.sql;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves table / alias scope for a SQL fragment using JSqlParser when possible,
 * with a regex fallback for incomplete caret-time SQL.
 *
 * <p>Also projects CTE / subquery select-list columns so autocomplete can treat
 * them like tables without consulting {@link SchemaCache}.
 */
public final class SqlTableScope {

    /**
     * Supports {@code FROM catalog.table [AS] alias} and unquoted/quoted identifiers.
     */
    private static final Pattern TABLE_REF = Pattern.compile(
            "(?i)\\b(?:from|join|update|into|table)\\s+"
                    + "([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?)"
                    + "(?:\\s*\\.\\s*([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?))?"
                    + "(?:\\s+(?:as\\s+)?([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?))?",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern RESERVED_ALIAS = Pattern.compile(
            "(?i)^(on|where|join|inner|left|right|full|outer|cross|as|and|or|group|order|limit|set|values|having)$");

    private static final Pattern WITH_CTE = Pattern.compile(
            "(?i)\\bWITH\\s+(?:RECURSIVE\\s+)?([A-Za-z0-9_]+)\\s*(?:\\(([^)]*)\\))?\\s+AS\\s*\\(",
            Pattern.CASE_INSENSITIVE);

    /**
     * @param aliases         lower-case alias / table / catalog.table → physical or virtual name
     * @param virtualColumns  lower-case CTE / subquery name → projected column names
     * @param cteNames        lower-case names that are CTEs (suggested like tables)
     */
    public record ResolvedScope(
            Map<String, String> aliases,
            Map<String, List<String>> virtualColumns,
            Set<String> cteNames
    ) {
        public ResolvedScope {
            aliases = Map.copyOf(aliases == null ? Map.of() : aliases);
            Map<String, List<String>> cols = new LinkedHashMap<>();
            if (virtualColumns != null) {
                for (Map.Entry<String, List<String>> entry : virtualColumns.entrySet()) {
                    cols.put(entry.getKey(), List.copyOf(entry.getValue()));
                }
            }
            virtualColumns = Map.copyOf(cols);
            cteNames = Set.copyOf(cteNames == null ? Set.of() : cteNames);
        }

        public static ResolvedScope empty() {
            return new ResolvedScope(Map.of(), Map.of(), Set.of());
        }

        public List<String> columnsOf(String tableOrAlias) {
            if (tableOrAlias == null || tableOrAlias.isBlank()) {
                return List.of();
            }
            String physical = aliases.getOrDefault(
                    tableOrAlias.toLowerCase(Locale.ROOT), tableOrAlias);
            List<String> cols = virtualColumns.get(physical.toLowerCase(Locale.ROOT));
            return cols == null ? List.of() : cols;
        }

        public boolean isVirtual(String tableOrAlias) {
            if (tableOrAlias == null || tableOrAlias.isBlank()) {
                return false;
            }
            String physical = aliases.getOrDefault(
                    tableOrAlias.toLowerCase(Locale.ROOT), tableOrAlias);
            return virtualColumns.containsKey(physical.toLowerCase(Locale.ROOT));
        }
    }

    private SqlTableScope() {
    }

    /**
     * Full scope for autocomplete: aliases, CTE names, and projected columns.
     */
    public static ResolvedScope resolve(
            String sqlBeforeCaret, SchemaCache cache, String activeCatalog) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, List<String>> virtualColumns = new LinkedHashMap<>();
        Set<String> cteNames = new LinkedHashSet<>();

        ResolvedScope fromAst = tryAstScope(sqlBeforeCaret, cache, activeCatalog);
        aliases.putAll(fromAst.aliases());
        virtualColumns.putAll(fromAst.virtualColumns());
        cteNames.addAll(fromAst.cteNames());

        for (Map.Entry<String, String> entry : regexAliases(sqlBeforeCaret).entrySet()) {
            aliases.putIfAbsent(entry.getKey(), entry.getValue());
        }
        // Regex WITH names when the AST path failed mid-CTE.
        for (Map.Entry<String, List<String>> entry : regexCteColumns(sqlBeforeCaret).entrySet()) {
            cteNames.add(entry.getKey());
            aliases.putIfAbsent(entry.getKey(), entry.getKey());
            virtualColumns.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return new ResolvedScope(aliases, virtualColumns, cteNames);
    }

    /**
     * Alias → physical table map for autocomplete / docs. Never throws.
     *
     * @param sqlBeforeCaret SQL up to (but not past) the caret
     */
    public static Map<String, String> resolveAliases(
            String sqlBeforeCaret, SchemaCache cache, String activeCatalog) {
        return resolve(sqlBeforeCaret, cache, activeCatalog).aliases();
    }

    /** Regex-only path (tests / fallback). */
    public static Map<String, String> regexAliases(String sqlBeforeCaret) {
        Map<String, String> aliases = new LinkedHashMap<>();
        if (sqlBeforeCaret == null || sqlBeforeCaret.isBlank()) {
            return Map.of();
        }
        Matcher matcher = TABLE_REF.matcher(sqlBeforeCaret);
        while (matcher.find()) {
            String first = stripQuotes(matcher.group(1));
            String second = matcher.group(2) == null ? null : stripQuotes(matcher.group(2));
            String aliasToken = matcher.group(3) == null ? null : stripQuotes(matcher.group(3));

            String catalog;
            String table;
            if (second != null && !second.isBlank()) {
                catalog = first;
                table = second;
            } else {
                catalog = null;
                table = first;
            }
            String alias = aliasToken;
            if (alias == null || alias.isBlank() || RESERVED_ALIAS.matcher(alias).matches()) {
                alias = table;
            }
            register(aliases, alias, catalog, table, table);
        }
        return Map.copyOf(aliases);
    }

    private static Map<String, List<String>> regexCteColumns(String sqlBeforeCaret) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (sqlBeforeCaret == null || sqlBeforeCaret.isBlank()) {
            return out;
        }
        Matcher matcher = WITH_CTE.matcher(sqlBeforeCaret);
        while (matcher.find()) {
            String name = matcher.group(1).toLowerCase(Locale.ROOT);
            List<String> cols = new ArrayList<>();
            String explicit = matcher.group(2);
            if (explicit != null && !explicit.isBlank()) {
                for (String part : explicit.split(",")) {
                    String col = stripQuotes(part.strip());
                    if (!col.isEmpty()) {
                        cols.add(col);
                    }
                }
            }
            out.putIfAbsent(name, List.copyOf(cols));
        }
        return out;
    }

    private static ResolvedScope tryAstScope(
            String sqlBeforeCaret, SchemaCache cache, String activeCatalog) {
        Statement statement = tryParse(sqlBeforeCaret);
        if (statement == null) {
            return ResolvedScope.empty();
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, List<String>> virtualColumns = new LinkedHashMap<>();
        Set<String> cteNames = new LinkedHashSet<>();
        collectStatement(statement, cache, activeCatalog, aliases, virtualColumns, cteNames);
        return new ResolvedScope(aliases, virtualColumns, cteNames);
    }

    private static Statement tryParse(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        String trimmed = sql.strip();
        Statement parsed = parseQuietly(trimmed);
        if (parsed != null) {
            return parsed;
        }
        return parseQuietly(stripTrailingIncomplete(trimmed));
    }

    private static Statement parseQuietly(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Removes a trailing incomplete qualifier / keyword so the fragment can parse.
     * Package-visible for tests.
     */
    public static String stripTrailingIncomplete(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        String s = sql;
        s = s.replaceFirst("(?i)\\s+[A-Za-z0-9_]*\\s*\\.\\s*$", " ");
        s = s.replaceFirst("(?i)\\s+(?:join|inner|left|right|full|outer|cross)\\s*$", " ");
        s = s.replaceFirst("(?i)\\s+(?:where|on|and|or|set|having|group\\s+by|order\\s+by)\\s+[A-Za-z0-9_]*\\s*$", " ");
        String compact = s.stripTrailing();
        return compact.isEmpty() ? sql.stripTrailing() : compact;
    }

    private static void collectStatement(
            Statement statement,
            SchemaCache cache,
            String activeCatalog,
            Map<String, String> aliases,
            Map<String, List<String>> virtualColumns,
            Set<String> cteNames) {
        if (statement instanceof Select select) {
            collectSelect(select, cache, activeCatalog, aliases, virtualColumns, cteNames, true);
        } else if (statement instanceof Update update) {
            collectWithList(update.getWithItemsList(), cache, activeCatalog, aliases, virtualColumns, cteNames);
            if (update.getTable() != null) {
                addTable(update.getTable(), cache, activeCatalog, aliases);
            }
        } else if (statement instanceof Delete delete) {
            collectWithList(delete.getWithItemsList(), cache, activeCatalog, aliases, virtualColumns, cteNames);
            if (delete.getTable() != null) {
                addTable(delete.getTable(), cache, activeCatalog, aliases);
            }
        } else if (statement instanceof Insert insert) {
            collectWithList(insert.getWithItemsList(), cache, activeCatalog, aliases, virtualColumns, cteNames);
            if (insert.getTable() != null) {
                addTable(insert.getTable(), cache, activeCatalog, aliases);
            }
        }
    }

    private static void collectWithList(
            List<? extends WithItem<?>> withItems,
            SchemaCache cache,
            String activeCatalog,
            Map<String, String> aliases,
            Map<String, List<String>> virtualColumns,
            Set<String> cteNames) {
        if (withItems == null) {
            return;
        }
        for (WithItem<?> withItem : withItems) {
            collectWithItem(withItem, cache, activeCatalog, aliases, virtualColumns, cteNames);
        }
    }

    private static void collectSelect(
            Select select,
            SchemaCache cache,
            String activeCatalog,
            Map<String, String> aliases,
            Map<String, List<String>> virtualColumns,
            Set<String> cteNames,
            boolean includeWith) {
        if (includeWith) {
            collectWithList(select.getWithItemsList(), cache, activeCatalog, aliases, virtualColumns, cteNames);
        }
        if (select instanceof PlainSelect plain) {
            addFromItem(plain.getFromItem(), cache, activeCatalog, aliases, virtualColumns, cteNames);
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    addFromItem(join.getRightItem(), cache, activeCatalog, aliases, virtualColumns, cteNames);
                }
            }
        } else if (select instanceof SetOperationList set) {
            List<Select> selects = set.getSelects();
            if (selects != null) {
                for (Select part : selects) {
                    collectSelect(part, cache, activeCatalog, aliases, virtualColumns, cteNames, false);
                }
            }
        } else if (select instanceof ParenthesedSelect parenthesed) {
            if (parenthesed.getSelect() != null) {
                collectSelect(parenthesed.getSelect(), cache, activeCatalog, aliases, virtualColumns, cteNames, false);
            }
        }
    }

    private static void collectWithItem(
            WithItem<?> withItem,
            SchemaCache cache,
            String activeCatalog,
            Map<String, String> aliases,
            Map<String, List<String>> virtualColumns,
            Set<String> cteNames) {
        String name = withItem.getAliasName();
        if (name == null || name.isBlank()) {
            name = withItem.getUnquotedAliasName();
        }
        if (name == null || name.isBlank()) {
            return;
        }
        String cte = stripQuotes(name);
        String key = cte.toLowerCase(Locale.ROOT);
        cteNames.add(key);
        // CTE is its own physical name — do not register inner FROM tables into outer scope.
        register(aliases, cte, null, cte, cte);

        List<String> columns = new ArrayList<>();
        if (withItem.getWithItemList() != null && !withItem.getWithItemList().isEmpty()) {
            for (SelectItem<?> item : withItem.getWithItemList()) {
                String col = columnNameFromSelectItem(item);
                if (col != null) {
                    columns.add(col);
                }
            }
        } else {
            ParenthesedSelect nested = withItem.getSelect();
            if (nested != null && nested.getSelect() != null) {
                columns.addAll(projectColumns(nested.getSelect(), cache, activeCatalog));
            }
        }
        if (!columns.isEmpty()) {
            virtualColumns.put(key, List.copyOf(columns));
        }
    }

    private static void addFromItem(
            FromItem fromItem,
            SchemaCache cache,
            String activeCatalog,
            Map<String, String> aliases,
            Map<String, List<String>> virtualColumns,
            Set<String> cteNames) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof Table table) {
            addTable(table, cache, activeCatalog, aliases);
            return;
        }
        if (fromItem instanceof ParenthesedFromItem parenthesed) {
            addFromItem(parenthesed.getFromItem(), cache, activeCatalog, aliases, virtualColumns, cteNames);
            Alias alias = parenthesed.getAlias();
            if (alias != null && alias.getName() != null) {
                String name = stripQuotes(alias.getName());
                register(aliases, name, null, name, name);
            }
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesed) {
            Alias alias = parenthesed.getAlias();
            String name = alias != null && alias.getName() != null
                    ? stripQuotes(alias.getName())
                    : null;
            if (name != null && !name.isBlank()) {
                register(aliases, name, null, name, name);
                if (parenthesed.getSelect() != null) {
                    List<String> columns = projectColumns(parenthesed.getSelect(), cache, activeCatalog);
                    if (!columns.isEmpty()) {
                        virtualColumns.put(name.toLowerCase(Locale.ROOT), columns);
                    }
                }
            }
            // Do not leak subquery inner FROM into outer alias map.
        }
    }

    private static void addTable(
            Table table, SchemaCache cache, String activeCatalog, Map<String, String> aliases) {
        TableRef ref = TableRef.from(table);
        if (ref.tableName().isBlank()) {
            return;
        }
        String alias = table.getAlias() != null
                ? stripQuotes(table.getAlias().getName())
                : ref.tableName();
        String physical = ref.tableName();
        if (cache != null && cache.isReady()) {
            Optional<SchemaNode> found =
                    cache.resolveTable(ref.catalogOrSchema(), ref.tableName(), activeCatalog);
            if (found.isPresent()) {
                physical = found.get().name();
            }
        }
        register(aliases, alias, ref.catalogOrSchema(), ref.tableName(), physical);
    }

    /**
     * Column names projected by a SELECT (aliases preferred; {@code *} expanded via schema).
     */
    static List<String> projectColumns(Select select, SchemaCache cache, String activeCatalog) {
        if (select == null) {
            return List.of();
        }
        if (select instanceof ParenthesedSelect parenthesed) {
            return projectColumns(parenthesed.getSelect(), cache, activeCatalog);
        }
        if (select instanceof SetOperationList set) {
            List<Select> parts = set.getSelects();
            if (parts == null || parts.isEmpty()) {
                return List.of();
            }
            return projectColumns(parts.getFirst(), cache, activeCatalog);
        }
        if (!(select instanceof PlainSelect plain) || plain.getSelectItems() == null) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (SelectItem<?> item : plain.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns) {
                for (String col : expandStar(plain, null, cache, activeCatalog)) {
                    if (seen.add(col.toLowerCase(Locale.ROOT))) {
                        columns.add(col);
                    }
                }
                continue;
            }
            if (expression instanceof AllTableColumns allTable) {
                String tableName = allTable.getTable() != null
                        ? stripQuotes(allTable.getTable().getName())
                        : null;
                for (String col : expandStar(plain, tableName, cache, activeCatalog)) {
                    if (seen.add(col.toLowerCase(Locale.ROOT))) {
                        columns.add(col);
                    }
                }
                continue;
            }
            String name = columnNameFromSelectItem(item);
            if (name != null && seen.add(name.toLowerCase(Locale.ROOT))) {
                columns.add(name);
            }
        }
        return List.copyOf(columns);
    }

    private static String columnNameFromSelectItem(SelectItem<?> item) {
        if (item == null) {
            return null;
        }
        if (item.getAlias() != null && item.getAlias().getName() != null) {
            String alias = stripQuotes(item.getAlias().getName());
            if (!alias.isBlank()) {
                return alias;
            }
        }
        Expression expression = item.getExpression();
        if (expression instanceof Column column) {
            return stripQuotes(column.getColumnName());
        }
        return null;
    }

    private static List<String> expandStar(
            PlainSelect plain, String onlyTable, SchemaCache cache, String activeCatalog) {
        if (cache == null || !cache.isReady()) {
            return List.of();
        }
        List<String> columns = new ArrayList<>();
        List<FromItem> sources = new ArrayList<>();
        if (plain.getFromItem() != null) {
            sources.add(plain.getFromItem());
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.getRightItem() != null) {
                    sources.add(join.getRightItem());
                }
            }
        }
        for (FromItem source : sources) {
            if (!(source instanceof Table table)) {
                continue;
            }
            TableRef ref = TableRef.from(table);
            if (onlyTable != null && !onlyTable.equalsIgnoreCase(ref.tableName())
                    && (table.getAlias() == null
                    || !onlyTable.equalsIgnoreCase(stripQuotes(table.getAlias().getName())))) {
                continue;
            }
            for (SchemaNode column : cache.columnsOf(ref.tableName(), activeCatalog)) {
                columns.add(column.name());
            }
        }
        return columns;
    }

    private static void register(
            Map<String, String> aliases,
            String alias,
            String catalog,
            String tableName,
            String physical) {
        if (physical == null || physical.isBlank()) {
            return;
        }
        aliases.put(alias.toLowerCase(Locale.ROOT), physical);
        aliases.put(tableName.toLowerCase(Locale.ROOT), physical);
        aliases.put(physical.toLowerCase(Locale.ROOT), physical);
        if (catalog != null && !catalog.isBlank()) {
            aliases.put((catalog + "." + tableName).toLowerCase(Locale.ROOT), physical);
        }
    }

    private record TableRef(String catalogOrSchema, String tableName) {
        static TableRef from(Table table) {
            if (table == null) {
                return new TableRef(null, "");
            }
            String catalog = firstNonBlank(table.getCatalogName(), table.getSchemaName());
            String name = stripQuotes(Objects.requireNonNullElse(table.getName(), ""));
            if ((catalog == null || catalog.isBlank()) && name.contains(".")) {
                int dot = name.lastIndexOf('.');
                catalog = stripQuotes(name.substring(0, dot));
                name = stripQuotes(name.substring(dot + 1));
            } else if (catalog != null) {
                catalog = stripQuotes(catalog);
            }
            return new TableRef(
                    catalog == null || catalog.isBlank() ? null : catalog,
                    name);
        }

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) {
                return first;
            }
            return second != null && !second.isBlank() ? second : null;
        }
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
