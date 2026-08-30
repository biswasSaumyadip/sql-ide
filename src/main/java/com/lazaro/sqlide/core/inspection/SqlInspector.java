package com.lazaro.sqlide.core.inspection;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.sql.SchemaChangingSql;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.parser.ParseException;
import net.sf.jsqlparser.parser.Token;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs AST inspections over SQL text using JSqlParser and an optional
 * {@link SchemaCache}. Safe to call off the JavaFX Application Thread.
 */
public final class SqlInspector {

    private static final Set<String> AGGREGATES = Set.of(
            "COUNT", "SUM", "AVG", "MIN", "MAX", "GROUP_CONCAT", "STRING_AGG", "ARRAY_AGG");

    private SqlInspector() {
    }

    public static List<InspectionIssue> inspect(String sql, SchemaCache schema) {
        return inspect(sql, schema, null);
    }

    public static List<InspectionIssue> inspect(String sql, SchemaCache schema, String activeCatalog) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<InspectionIssue> issues = new ArrayList<>();
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            for (Statement statement : statements) {
                inspectStatement(statement, sql, schema, activeCatalog, issues);
            }
        } catch (JSQLParserException ex) {
            if (!SchemaChangingSql.isClientOrRoutineSql(sql)) {
                issues.add(syntaxIssue(ex, sql));
            }
        }
        return List.copyOf(issues);
    }

    private static void inspectStatement(
            Statement statement,
            String sql,
            SchemaCache schema,
            String activeCatalog,
            List<InspectionIssue> issues) {
        if (statement instanceof Insert insert) {
            inspectInsert(insert, sql, schema, activeCatalog, issues);
            return;
        }
        if (statement instanceof Update update) {
            if (update.getWhere() == null) {
                issues.add(issueAround(update, sql, "UPDATE",
                        "Unsafe query: modifying/deleting without WHERE", Severity.WARNING));
            }
            Scope scope = Scope.fromUpdate(update, schema, activeCatalog, sql, issues);
            inspectUpdateAssignments(update, scope, sql, issues);
            if (update.getWhere() != null) {
                inspectExpression(update.getWhere(), scope, sql, issues);
            }
            return;
        }
        if (statement instanceof Delete delete) {
            if (delete.getWhere() == null) {
                issues.add(issueAround(delete, sql, "DELETE",
                        "Unsafe query: modifying/deleting without WHERE", Severity.WARNING));
            }
            Scope scope = Scope.fromDelete(delete, schema, activeCatalog, sql, issues);
            if (delete.getWhere() != null) {
                inspectExpression(delete.getWhere(), scope, sql, issues);
            }
            return;
        }
        if (statement instanceof Select select) {
            select.accept(new SelectVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(PlainSelect plainSelect, S context) {
                    inspectPlainSelect(plainSelect, sql, schema, activeCatalog, issues);
                    return null;
                }

                @Override
                public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                    if (parenthesedSelect.getSelect() != null) {
                        parenthesedSelect.getSelect().accept(this, context);
                    }
                    return null;
                }
            }, null);
        }
    }

    private static void inspectInsert(
            Insert insert,
            String sql,
            SchemaCache schema,
            String activeCatalog,
            List<InspectionIssue> issues) {
        Table table = insert.getTable();
        if (table == null || table.getName() == null || table.getName().isBlank()) {
            return;
        }
        boolean ready = schema != null && schema.isReady();
        TableRef ref = TableRef.from(table);
        Optional<SchemaNode> tableNode = ready
                ? schema.resolveTable(ref.catalogOrSchema(), ref.tableName(), activeCatalog)
                : Optional.empty();
        if (ready && tableNode.isEmpty()) {
            issues.add(issueAround(table, sql, ref.displayName(),
                    "Unknown table '" + ref.displayName() + "'", Severity.ERROR));
            return;
        }

        List<SchemaNode> schemaColumns = tableNode.map(SchemaNode::children).orElse(List.of());
        List<Column> insertColumns = columnsOf(insert);
        List<TargetColumn> targets = resolveInsertTargets(insertColumns, schemaColumns, sql, issues);
        if (targets.isEmpty()) {
            return;
        }

        Values values = insert.getValues();
        if (values == null || values.getExpressions() == null) {
            return;
        }
        for (List<Expression> row : valueRows(values.getExpressions())) {
            if (row.size() != targets.size()) {
                Expression anchor = row.isEmpty() ? values : row.getFirst();
                issues.add(issueAround(anchor, sql, anchor.toString(),
                        "Column count (" + targets.size() + ") does not match value count ("
                                + row.size() + ")",
                        Severity.ERROR));
                continue;
            }
            for (int i = 0; i < targets.size(); i++) {
                TargetColumn target = targets.get(i);
                Expression value = row.get(i);
                if (target.dataType == null || target.dataType.isBlank()) {
                    continue;
                }
                String mismatch = SqlValueTypes.mismatchMessage(target.name, target.dataType, value);
                if (mismatch != null) {
                    issues.add(issueAround(value, sql, value.toString(), mismatch, Severity.WARNING));
                }
            }
        }
    }

    private static void inspectUpdateAssignments(
            Update update, Scope scope, String sql, List<InspectionIssue> issues) {
        if (update.getUpdateSets() == null || !scope.schemaReady) {
            return;
        }
        for (UpdateSet set : update.getUpdateSets()) {
            if (set.getColumns() == null || set.getValues() == null) {
                continue;
            }
            int n = Math.min(set.getColumns().size(), set.getValues().size());
            for (int i = 0; i < n; i++) {
                Column column = set.getColumn(i);
                Expression value = set.getValue(i);
                inspectExpression(column, scope, sql, issues);
                String dataType = scope.columnType(column);
                if (dataType == null || dataType.isBlank()) {
                    continue;
                }
                String mismatch = SqlValueTypes.mismatchMessage(
                        stripQuotes(column.getColumnName()), dataType, value);
                if (mismatch != null) {
                    issues.add(issueAround(value, sql, value.toString(), mismatch, Severity.WARNING));
                }
            }
        }
    }

    private record TargetColumn(String name, String dataType) {
    }

    private static List<Column> columnsOf(Insert insert) {
        ExpressionList<Column> columns = insert.getColumns();
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        List<Column> list = new ArrayList<>(columns.size());
        for (Column column : columns) {
            list.add(column);
        }
        return list;
    }

    private static List<TargetColumn> resolveInsertTargets(
            List<Column> insertColumns,
            List<SchemaNode> schemaColumns,
            String sql,
            List<InspectionIssue> issues) {
        if (!insertColumns.isEmpty()) {
            Map<String, SchemaNode> byName = new HashMap<>();
            for (SchemaNode column : schemaColumns) {
                byName.put(column.name().toLowerCase(Locale.ROOT), column);
            }
            List<TargetColumn> targets = new ArrayList<>(insertColumns.size());
            for (Column column : insertColumns) {
                String name = stripQuotes(column.getColumnName());
                SchemaNode node = byName.get(name.toLowerCase(Locale.ROOT));
                if (node == null && !schemaColumns.isEmpty()) {
                    issues.add(issueAround(column, sql, name,
                            "Unknown column '" + name + "'", Severity.ERROR));
                    targets.add(new TargetColumn(name, null));
                } else {
                    String type = node == null ? null : node.metadata(SchemaNode.META_DATA_TYPE);
                    targets.add(new TargetColumn(name, type));
                }
            }
            return targets;
        }
        if (schemaColumns.isEmpty()) {
            return List.of();
        }
        List<TargetColumn> targets = new ArrayList<>(schemaColumns.size());
        for (SchemaNode column : schemaColumns) {
            targets.add(new TargetColumn(column.name(), column.metadata(SchemaNode.META_DATA_TYPE)));
        }
        return targets;
    }

    private static List<List<Expression>> valueRows(ExpressionList<?> expressions) {
        if (expressions == null || expressions.isEmpty()) {
            return List.of();
        }
        Expression first = expressions.get(0);
        if (first instanceof ExpressionList) {
            List<List<Expression>> rows = new ArrayList<>(expressions.size());
            for (Expression expression : expressions) {
                if (expression instanceof ExpressionList<?> row) {
                    rows.add(expressionList(row));
                }
            }
            return rows;
        }
        return List.of(expressionList(expressions));
    }

    private static List<Expression> expressionList(ExpressionList<?> list) {
        List<Expression> values = new ArrayList<>(list.size());
        for (Expression expression : list) {
            values.add(expression);
        }
        return values;
    }

    private static void inspectPlainSelect(
            PlainSelect select,
            String sql,
            SchemaCache schema,
            String activeCatalog,
            List<InspectionIssue> issues) {
        Scope scope = Scope.fromSelect(select, schema, activeCatalog, sql, issues);

        if (select.getWhere() != null) {
            inspectExpression(select.getWhere(), scope, sql, issues);
        }
        if (select.getHaving() != null) {
            inspectExpression(select.getHaving(), scope, sql, issues);
        }
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getOnExpressions() != null) {
                    for (Expression on : join.getOnExpressions()) {
                        inspectExpression(on, scope, sql, issues);
                    }
                }
            }
        }

        GroupByElement groupBy = select.getGroupBy();
        Set<String> groupByColumns = new HashSet<>();
        ExpressionList<?> groupExpressions = groupBy == null ? null : groupBy.getGroupByExpressionList();
        if (groupExpressions != null) {
            for (Expression expression : groupExpressions) {
                if (expression instanceof Column column) {
                    groupByColumns.add(columnKey(column));
                }
                inspectExpression(expression, scope, sql, issues);
            }
        }

        if (select.getSelectItems() == null) {
            return;
        }
        boolean hasGroupBy = groupExpressions != null && !groupExpressions.isEmpty();

        for (SelectItem<?> item : select.getSelectItems()) {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
                continue;
            }
            if (expression != null) {
                inspectExpression(expression, scope, sql, issues);
            }
            if (hasGroupBy && expression != null && !isAggregate(expression)) {
                if (expression instanceof Column column) {
                    if (!groupByColumns.contains(columnKey(column))
                            && !groupByColumns.contains(column.getColumnName().toLowerCase(Locale.ROOT))) {
                        issues.add(issueAround(column, sql, column.getColumnName(),
                                "Column must appear in GROUP BY or be used in an aggregate",
                                Severity.ERROR));
                    }
                } else if (!(expression instanceof LongValue)
                        && !(expression instanceof DoubleValue)
                        && !(expression instanceof StringValue)
                        && !(expression instanceof NullValue)) {
                    issues.add(issueAround(expression, sql, expression.toString(),
                            "Expression must appear in GROUP BY or be used in an aggregate",
                            Severity.ERROR));
                }
            }
        }
    }

    private static void inspectExpression(
            Expression expression, Scope scope, String sql, List<InspectionIssue> issues) {
        if (expression == null) {
            return;
        }
        expression.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Column column, S context) {
                // Only schema Columns are validated — never literals.
                checkColumn(column, scope, sql, issues);
                return null;
            }

            @Override
            public <S> Void visit(StringValue stringValue, S context) {
                return null;
            }

            @Override
            public <S> Void visit(LongValue longValue, S context) {
                return null;
            }

            @Override
            public <S> Void visit(DoubleValue doubleValue, S context) {
                return null;
            }

            @Override
            public <S> Void visit(NullValue nullValue, S context) {
                return null;
            }

            @Override
            public <S> Void visit(EqualsTo equalsTo, S context) {
                flagConstantCondition(equalsTo, sql, issues);
                return super.visit(equalsTo, context);
            }

            @Override
            public <S> Void visit(NotEqualsTo notEqualsTo, S context) {
                flagConstantCondition(notEqualsTo, sql, issues);
                return super.visit(notEqualsTo, context);
            }
        });
    }

    private static void checkColumn(Column column, Scope scope, String sql, List<InspectionIssue> issues) {
        if (scope == null || !scope.schemaReady || column == null) {
            return;
        }
        String columnName = stripQuotes(column.getColumnName());
        if (columnName == null || columnName.isBlank() || "*".equals(columnName)) {
            return;
        }
        // Double-quoted LIKE patterns / numeric "identifiers" are not schema objects.
        if (isNonSchemaColumnName(columnName)) {
            return;
        }

        Table table = column.getTable();
        if (table != null && table.getName() != null && !table.getName().isBlank()) {
            TableRef ref = TableRef.from(table);
            Optional<String> resolved = scope.resolveQualifier(ref.displayName());
            if (resolved.isEmpty()) {
                resolved = scope.resolveQualifier(ref.tableName());
            }
            if (resolved.isEmpty()) {
                issues.add(issueAround(table, sql, ref.displayName(),
                        "Unknown table or alias '" + ref.displayName() + "'", Severity.ERROR));
                return;
            }
            if (!scope.hasColumn(resolved.get(), columnName)) {
                issues.add(issueAround(column, sql, columnName,
                        "Unknown column '" + columnName + "' on table '" + resolved.get() + "'",
                        Severity.ERROR));
            }
            return;
        }

        List<String> owners = scope.tablesOwning(columnName);
        if (owners.isEmpty()) {
            issues.add(issueAround(column, sql, columnName,
                    "Unknown column '" + columnName + "'", Severity.ERROR));
        } else if (owners.size() > 1) {
            issues.add(issueAround(column, sql, columnName,
                    "Ambiguous column reference", Severity.ERROR));
        }
    }

    /**
     * True for values the parser promoted to {@link Column} that are clearly data
     * (LIKE wildcards, pure numbers), not schema identifiers.
     */
    static boolean isNonSchemaColumnName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return true;
        }
        if (columnName.indexOf('%') >= 0) {
            return true;
        }
        // Pure numeric tokens (misparsed literals).
        boolean digits = true;
        for (int i = 0; i < columnName.length(); i++) {
            if (!Character.isDigit(columnName.charAt(i))) {
                digits = false;
                break;
            }
        }
        return digits;
    }

    private static void flagConstantCondition(BinaryExpression expression, String sql, List<InspectionIssue> issues) {
        Expression left = expression.getLeftExpression();
        Expression right = expression.getRightExpression();
        if (left instanceof NullValue || right instanceof NullValue) {
            issues.add(issueAround(expression, sql, "=",
                    "Comparison with NULL is always unknown; use IS NULL", Severity.WEAK_WARNING));
            return;
        }
        String leftLit = literalKey(left);
        String rightLit = literalKey(right);
        if (leftLit != null && leftLit.equals(rightLit)) {
            issues.add(issueAround(expression, sql, "=",
                    "Constant condition", Severity.WEAK_WARNING));
        }
    }

    private static String literalKey(Expression expression) {
        if (expression instanceof LongValue value) {
            return "L:" + value.getValue();
        }
        if (expression instanceof DoubleValue value) {
            return "D:" + value.getValue();
        }
        if (expression instanceof StringValue value) {
            return "S:" + value.getValue();
        }
        return null;
    }

    private static boolean isAggregate(Expression expression) {
        if (expression instanceof Function function) {
            String name = function.getName();
            return name != null && AGGREGATES.contains(name.toUpperCase(Locale.ROOT));
        }
        return false;
    }

    private static String columnKey(Column column) {
        String name = stripQuotes(column.getColumnName()).toLowerCase(Locale.ROOT);
        if (column.getTable() != null && column.getTable().getName() != null) {
            return stripQuotes(column.getTable().getName()).toLowerCase(Locale.ROOT) + "." + name;
        }
        return name;
    }

    private static InspectionIssue syntaxIssue(JSQLParserException ex, String sql) {
        Throwable cause = ex;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof ParseException parseException && parseException.currentToken != null) {
            Token token = parseException.currentToken.next != null
                    ? parseException.currentToken.next
                    : parseException.currentToken;
            int[] range = SourceOffsets.rangeFromToken(token, sql);
            String image = token.image == null ? "token" : token.image;
            return new InspectionIssue(range[0], range[1],
                    "Syntax error near '" + image + "'", Severity.ERROR);
        }
        String message = ex.getMessage() == null ? "Syntax error" : ex.getMessage();
        int[] range = SourceOffsets.rangeOf(null, sql, null);
        return new InspectionIssue(range[0], range[1], shorten(message), Severity.ERROR);
    }

    private static InspectionIssue issueAround(
            Object node, String sql, String fallback, String message, Severity severity) {
        int[] range = SourceOffsets.rangeOf(node, sql, fallback);
        return new InspectionIssue(range[0], range[1], message, severity);
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

    /**
     * Extracts catalog/schema + table from a JSqlParser {@link Table}, including
     * dotted names when the parser left the qualifier inside {@code getName()}.
     */
    private record TableRef(String catalogOrSchema, String tableName) {

        static TableRef from(Table table) {
            if (table == null) {
                return new TableRef(null, "");
            }
            String catalog = firstNonBlank(table.getCatalogName(), table.getSchemaName());
            String name = stripQuotes(table.getName());
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

        String displayName() {
            if (catalogOrSchema == null || catalogOrSchema.isBlank()) {
                return tableName;
            }
            return catalogOrSchema + "." + tableName;
        }

        private static String firstNonBlank(String first, String second) {
            if (first != null && !first.isBlank()) {
                return first;
            }
            return second != null && !second.isBlank() ? second : null;
        }
    }

    private static String shorten(String message) {
        String oneLine = message.replace('\n', ' ').strip();
        return oneLine.length() <= 160 ? oneLine : oneLine.substring(0, 159) + "\u2026";
    }

    // ---------------------------------------------------------------- scope

    private static final class Scope {
        private final boolean schemaReady;
        /** alias/table-name (lower) → physical table name */
        private final Map<String, String> aliases;
        /** physical table (lower) → column names (lower) */
        private final Map<String, Set<String>> columnsByTable;
        /** physical table (lower) → column name (lower) → SQL type string */
        private final Map<String, Map<String, String>> typesByTable;

        private Scope(
                boolean schemaReady,
                Map<String, String> aliases,
                Map<String, Set<String>> columnsByTable,
                Map<String, Map<String, String>> typesByTable) {
            this.schemaReady = schemaReady;
            this.aliases = aliases;
            this.columnsByTable = columnsByTable;
            this.typesByTable = typesByTable;
        }

        static Scope fromSelect(
                PlainSelect select, SchemaCache schema, String catalog, String sql, List<InspectionIssue> issues) {
            Map<String, String> aliases = new LinkedHashMap<>();
            Map<String, Set<String>> columns = new HashMap<>();
            Map<String, Map<String, String>> types = new HashMap<>();
            boolean ready = schema != null && schema.isReady();
            addFromItem(select.getFromItem(), schema, catalog, ready, aliases, columns, types, sql, issues);
            if (select.getJoins() != null) {
                for (Join join : select.getJoins()) {
                    addFromItem(join.getRightItem(),
                            schema, catalog, ready, aliases, columns, types, sql, issues);
                }
            }
            return new Scope(ready, aliases, columns, types);
        }

        static Scope fromUpdate(
                Update update, SchemaCache schema, String catalog, String sql, List<InspectionIssue> issues) {
            Map<String, String> aliases = new LinkedHashMap<>();
            Map<String, Set<String>> columns = new HashMap<>();
            Map<String, Map<String, String>> types = new HashMap<>();
            boolean ready = schema != null && schema.isReady();
            if (update.getTable() != null) {
                addTable(update.getTable(), schema, catalog, ready, aliases, columns, types, sql, issues);
            }
            return new Scope(ready, aliases, columns, types);
        }

        static Scope fromDelete(
                Delete delete, SchemaCache schema, String catalog, String sql, List<InspectionIssue> issues) {
            Map<String, String> aliases = new LinkedHashMap<>();
            Map<String, Set<String>> columns = new HashMap<>();
            Map<String, Map<String, String>> types = new HashMap<>();
            boolean ready = schema != null && schema.isReady();
            if (delete.getTable() != null) {
                addTable(delete.getTable(), schema, catalog, ready, aliases, columns, types, sql, issues);
            }
            return new Scope(ready, aliases, columns, types);
        }

        private static void addFromItem(
                FromItem fromItem,
                SchemaCache schema,
                String catalog,
                boolean ready,
                Map<String, String> aliases,
                Map<String, Set<String>> columns,
                Map<String, Map<String, String>> types,
                String sql,
                List<InspectionIssue> issues) {
            if (fromItem instanceof Table table) {
                addTable(table, schema, catalog, ready, aliases, columns, types, sql, issues);
            }
        }

        private static void addTable(
                Table table,
                SchemaCache schema,
                String catalog,
                boolean ready,
                Map<String, String> aliases,
                Map<String, Set<String>> columns,
                Map<String, Map<String, String>> types,
                String sql,
                List<InspectionIssue> issues) {
            TableRef ref = TableRef.from(table);
            if (ref.tableName().isBlank()) {
                return;
            }
            String alias = table.getAlias() != null
                    ? stripQuotes(table.getAlias().getName())
                    : ref.tableName();
            if (!ready) {
                registerAlias(aliases, alias, ref, ref.tableName());
                return;
            }
            Optional<SchemaNode> found = schema.resolveTable(ref.catalogOrSchema(), ref.tableName(), catalog);
            if (found.isEmpty()) {
                issues.add(issueAround(table, sql, ref.displayName(),
                        "Unknown table '" + ref.displayName() + "'", Severity.ERROR));
                registerAlias(aliases, alias, ref, ref.tableName());
                return;
            }
            String physical = found.get().name();
            registerAlias(aliases, alias, ref, physical);
            Set<String> cols = columns.computeIfAbsent(physical.toLowerCase(Locale.ROOT), key -> new HashSet<>());
            Map<String, String> typeMap = types.computeIfAbsent(
                    physical.toLowerCase(Locale.ROOT), key -> new HashMap<>());
            for (SchemaNode column : found.get().children()) {
                String colName = column.name().toLowerCase(Locale.ROOT);
                cols.add(colName);
                String dataType = column.metadata(SchemaNode.META_DATA_TYPE);
                if (dataType != null && !dataType.isBlank()) {
                    typeMap.put(colName, dataType);
                }
            }
        }

        private static void registerAlias(
                Map<String, String> aliases, String alias, TableRef ref, String physical) {
            aliases.put(alias.toLowerCase(Locale.ROOT), physical);
            aliases.put(ref.tableName().toLowerCase(Locale.ROOT), physical);
            aliases.put(physical.toLowerCase(Locale.ROOT), physical);
            if (ref.catalogOrSchema() != null && !ref.catalogOrSchema().isBlank()) {
                String qualified = ref.catalogOrSchema() + "." + ref.tableName();
                aliases.put(qualified.toLowerCase(Locale.ROOT), physical);
            }
        }

        Optional<String> resolveQualifier(String qualifier) {
            return Optional.ofNullable(aliases.get(qualifier.toLowerCase(Locale.ROOT)));
        }

        boolean hasColumn(String table, String column) {
            Set<String> cols = columnsByTable.get(table.toLowerCase(Locale.ROOT));
            return cols != null && cols.contains(column.toLowerCase(Locale.ROOT));
        }

        String columnType(Column column) {
            String columnName = stripQuotes(column.getColumnName()).toLowerCase(Locale.ROOT);
            Table table = column.getTable();
            if (table != null && table.getName() != null && !table.getName().isBlank()) {
                TableRef ref = TableRef.from(table);
                Optional<String> resolved = resolveQualifier(ref.displayName());
                if (resolved.isEmpty()) {
                    resolved = resolveQualifier(ref.tableName());
                }
                if (resolved.isEmpty()) {
                    return null;
                }
                Map<String, String> types = typesByTable.get(resolved.get().toLowerCase(Locale.ROOT));
                return types == null ? null : types.get(columnName);
            }
            List<String> owners = tablesOwning(columnName);
            if (owners.size() != 1) {
                return null;
            }
            Map<String, String> types = typesByTable.get(owners.getFirst());
            return types == null ? null : types.get(columnName);
        }

        List<String> tablesOwning(String column) {
            String needle = column.toLowerCase(Locale.ROOT);
            List<String> owners = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : columnsByTable.entrySet()) {
                if (entry.getValue().contains(needle)) {
                    owners.add(entry.getKey());
                }
            }
            return owners;
        }
    }
}
