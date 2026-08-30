package com.lazaro.sqlide.core.sql;

import com.lazaro.sqlide.core.db.SchemaCache;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves table / alias scope for a SQL fragment using JSqlParser when possible,
 * with a regex fallback for incomplete caret-time SQL.
 *
 * <p>Map keys are lower-case alias, bare table, and {@code catalog.table} forms;
 * values are the physical table name used with {@link SchemaCache}.
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

    private SqlTableScope() {
    }

    /**
     * Alias → physical table map for autocomplete / docs. Never throws.
     *
     * @param sqlBeforeCaret SQL up to (but not past) the caret
     */
    public static Map<String, String> resolveAliases(
            String sqlBeforeCaret, SchemaCache cache, String activeCatalog) {
        Map<String, String> aliases = new LinkedHashMap<>();
        Map<String, String> fromAst = tryAstAliases(sqlBeforeCaret, cache, activeCatalog);
        aliases.putAll(fromAst);
        // Regex fills gaps for incomplete SQL the parser rejected (e.g. trailing JOIN).
        for (Map.Entry<String, String> entry : regexAliases(sqlBeforeCaret).entrySet()) {
            aliases.putIfAbsent(entry.getKey(), entry.getValue());
        }
        return Map.copyOf(aliases);
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

    private static Map<String, String> tryAstAliases(
            String sqlBeforeCaret, SchemaCache cache, String activeCatalog) {
        Statement statement = tryParse(sqlBeforeCaret);
        if (statement == null) {
            return Map.of();
        }
        Map<String, String> aliases = new LinkedHashMap<>();
        collectStatement(statement, cache, activeCatalog, aliases);
        return aliases;
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
        // Drop a trailing incomplete token / qualifier so
        // {@code ... FROM users u WHERE u.} and {@code ... JOIN } still parse.
        String stripped = stripTrailingIncomplete(trimmed);
        if (!stripped.equals(trimmed)) {
            parsed = parseQuietly(stripped);
            if (parsed != null) {
                return parsed;
            }
        }
        return null;
    }

    private static Statement parseQuietly(String sql) {
        try {
            return CCJSqlParserUtil.parse(sql);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Removes a dangling identifier, {@code ident.}, or bare join/clause keyword
     * at the end of the fragment.
     */
    static String stripTrailingIncomplete(String sql) {
        String s = sql.stripTrailing();
        // Trailing "word." or "word"
        s = s.replaceFirst("(?i)([\\s,(]|^)([A-Za-z0-9_]+)\\.\\s*$", "$1");
        s = s.replaceFirst("(?i)\\s+(?:inner|left|right|full|outer|cross)?\\s*join\\s+$", " ");
        s = s.replaceFirst("(?i)\\s+(?:where|on|and|or|set|having|group\\s+by|order\\s+by)\\s+[A-Za-z0-9_]*\\s*$", " ");
        // If we removed only whitespace-ish, keep original.
        String compact = s.stripTrailing();
        return compact.isEmpty() ? sql.stripTrailing() : compact;
    }

    private static void collectStatement(
            Statement statement, SchemaCache cache, String activeCatalog, Map<String, String> aliases) {
        if (statement instanceof Select select) {
            collectSelect(select, cache, activeCatalog, aliases);
        } else if (statement instanceof Update update) {
            if (update.getWithItemsList() != null) {
                for (WithItem<?> withItem : update.getWithItemsList()) {
                    collectWithItem(withItem, cache, activeCatalog, aliases);
                }
            }
            if (update.getTable() != null) {
                addTable(update.getTable(), cache, activeCatalog, aliases);
            }
        } else if (statement instanceof Delete delete) {
            if (delete.getWithItemsList() != null) {
                for (WithItem<?> withItem : delete.getWithItemsList()) {
                    collectWithItem(withItem, cache, activeCatalog, aliases);
                }
            }
            if (delete.getTable() != null) {
                addTable(delete.getTable(), cache, activeCatalog, aliases);
            }
        } else if (statement instanceof Insert insert) {
            if (insert.getWithItemsList() != null) {
                for (WithItem<?> withItem : insert.getWithItemsList()) {
                    collectWithItem(withItem, cache, activeCatalog, aliases);
                }
            }
            if (insert.getTable() != null) {
                addTable(insert.getTable(), cache, activeCatalog, aliases);
            }
        }
    }

    private static void collectSelect(
            Select select, SchemaCache cache, String activeCatalog, Map<String, String> aliases) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> withItem : select.getWithItemsList()) {
                collectWithItem(withItem, cache, activeCatalog, aliases);
            }
        }
        if (select instanceof PlainSelect plain) {
            addFromItem(plain.getFromItem(), cache, activeCatalog, aliases);
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) {
                    addFromItem(join.getRightItem(), cache, activeCatalog, aliases);
                }
            }
        } else if (select instanceof SetOperationList set) {
            List<Select> selects = set.getSelects();
            if (selects != null) {
                for (Select part : selects) {
                    collectSelect(part, cache, activeCatalog, aliases);
                }
            }
        } else if (select instanceof ParenthesedSelect parenthesed) {
            if (parenthesed.getSelect() != null) {
                collectSelect(parenthesed.getSelect(), cache, activeCatalog, aliases);
            }
        }
    }

    private static void collectWithItem(
            WithItem<?> withItem, SchemaCache cache, String activeCatalog, Map<String, String> aliases) {
        String name = withItem.getAliasName();
        if (name == null || name.isBlank()) {
            name = withItem.getUnquotedAliasName();
        }
        if (name != null && !name.isBlank()) {
            String cte = stripQuotes(name);
            // CTE acts as its own physical table name for column resolution later.
            register(aliases, cte, null, cte, cte);
        }
        ParenthesedSelect nested = withItem.getSelect();
        if (nested != null && nested.getSelect() != null) {
            // Walk inner FROM so nested physical tables are also known when needed.
            collectSelect(nested.getSelect(), cache, activeCatalog, aliases);
        }
    }

    private static void addFromItem(
            FromItem fromItem, SchemaCache cache, String activeCatalog, Map<String, String> aliases) {
        if (fromItem == null) {
            return;
        }
        if (fromItem instanceof Table table) {
            addTable(table, cache, activeCatalog, aliases);
            return;
        }
        if (fromItem instanceof ParenthesedFromItem parenthesed) {
            addFromItem(parenthesed.getFromItem(), cache, activeCatalog, aliases);
            Alias alias = parenthesed.getAlias();
            if (alias != null && alias.getName() != null) {
                // Parenthesed source with alias — map alias to itself (subquery).
                String name = stripQuotes(alias.getName());
                register(aliases, name, null, name, name);
            }
            return;
        }
        if (fromItem instanceof ParenthesedSelect parenthesed) {
            Alias alias = parenthesed.getAlias();
            if (alias != null && alias.getName() != null) {
                String name = stripQuotes(alias.getName());
                register(aliases, name, null, name, name);
            }
            if (parenthesed.getSelect() != null) {
                collectSelect(parenthesed.getSelect(), cache, activeCatalog, aliases);
            }
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
            Optional<com.lazaro.sqlide.core.db.SchemaNode> found =
                    cache.resolveTable(ref.catalogOrSchema(), ref.tableName(), activeCatalog);
            if (found.isPresent()) {
                physical = found.get().name();
            }
        }
        register(aliases, alias, ref.catalogOrSchema(), ref.tableName(), physical);
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
