package com.lazaro.sqlide.core.sql;

import com.lazaro.sqlide.core.inspection.SourceOffsets;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.Values;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.update.UpdateSet;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Derives non-editable inlay labels (column / parameter names) from SQL via JSqlParser.
 * Safe to call off the JavaFX thread.
 */
public final class SqlInlayHints {

    public record Hint(int offset, String label) {
    }

    private SqlInlayHints() {
    }

    public static List<Hint> extract(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<Hint> hints = new ArrayList<>();
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            for (Statement statement : statements) {
                collect(statement, sql, hints);
            }
        } catch (JSQLParserException ignored) {
            // Incomplete SQL while typing — show nothing rather than stale hints.
        }
        hints.sort(Comparator.comparingInt(Hint::offset));
        return dedupe(hints);
    }

    private static void collect(Statement statement, String sql, List<Hint> hints) {
        if (statement instanceof Insert insert) {
            collectInsert(insert, sql, hints);
            return;
        }
        if (statement instanceof Update update) {
            if (update.getWhere() != null) {
                collectParameters(update.getWhere(), sql, hints);
            }
            if (update.getUpdateSets() != null) {
                for (UpdateSet set : update.getUpdateSets()) {
                    if (set.getValues() != null) {
                        for (Expression value : set.getValues()) {
                            collectParameters(value, sql, hints);
                        }
                    }
                }
            }
            return;
        }
        if (statement instanceof Select select) {
            select.accept(new SelectVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(PlainSelect plainSelect, S context) {
                    if (plainSelect.getWhere() != null) {
                        collectParameters(plainSelect.getWhere(), sql, hints);
                    }
                    if (plainSelect.getSelectItems() != null) {
                        plainSelect.getSelectItems().forEach(item -> {
                            if (item.getExpression() != null) {
                                collectParameters(item.getExpression(), sql, hints);
                            }
                        });
                    }
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

    private static void collectInsert(Insert insert, String sql, List<Hint> hints) {
        ExpressionList<Column> columns = insert.getColumns();
        Values values = insert.getValues();
        if (columns == null || columns.isEmpty() || values == null || values.getExpressions() == null) {
            if (values != null && values.getExpressions() != null) {
                for (Expression expression : flatten(values.getExpressions())) {
                    collectParameters(expression, sql, hints);
                }
            }
            return;
        }
        List<String> names = new ArrayList<>(columns.size());
        for (Column column : columns) {
            names.add(stripQuotes(column.getColumnName()));
        }
        for (List<Expression> row : valueRows(values.getExpressions())) {
            int n = Math.min(names.size(), row.size());
            for (int i = 0; i < n; i++) {
                Expression value = row.get(i);
                int[] range = SourceOffsets.rangeOf(value, sql, null);
                if (range[0] >= 0 && range[0] < sql.length()) {
                    hints.add(new Hint(range[0], names.get(i)));
                }
                collectParameters(value, sql, hints);
            }
        }
    }

    private static void collectParameters(Expression root, String sql, List<Hint> hints) {
        if (root == null) {
            return;
        }
        root.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            protected <S> Void visitBinaryExpression(BinaryExpression expression, S context) {
                Expression left = expression.getLeftExpression();
                Expression right = expression.getRightExpression();
                maybeParameterHint(left, right, sql, hints);
                maybeParameterHint(right, left, sql, hints);
                return super.visitBinaryExpression(expression, context);
            }

            @Override
            public <S> Void visit(JdbcParameter parameter, S context) {
                // Bare ? without column context — skip (binary visitor covers equals).
                return null;
            }

            @Override
            public <S> Void visit(JdbcNamedParameter parameter, S context) {
                return null;
            }
        }, null);
    }

    private static void maybeParameterHint(
            Expression parameterSide, Expression columnSide, String sql, List<Hint> hints) {
        if (!(columnSide instanceof Column column)) {
            return;
        }
        if (!(parameterSide instanceof JdbcParameter || parameterSide instanceof JdbcNamedParameter)) {
            return;
        }
        String name = stripQuotes(column.getColumnName());
        if (name.isBlank()) {
            return;
        }
        int[] range = SourceOffsets.rangeOf(parameterSide, sql, "?");
        if (parameterSide instanceof JdbcNamedParameter named) {
            range = SourceOffsets.rangeOf(parameterSide, sql, ":" + named.getName());
        }
        if (range[0] >= 0 && range[0] < sql.length()) {
            hints.add(new Hint(range[0], name));
        }
    }

    @SuppressWarnings("unchecked")
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

    private static List<Expression> flatten(ExpressionList<?> expressions) {
        List<Expression> flat = new ArrayList<>();
        for (List<Expression> row : valueRows(expressions)) {
            flat.addAll(row);
        }
        return flat;
    }

    private static List<Hint> dedupe(List<Hint> hints) {
        List<Hint> out = new ArrayList<>(hints.size());
        int lastOffset = Integer.MIN_VALUE;
        for (Hint hint : hints) {
            if (hint.offset() == lastOffset) {
                continue;
            }
            out.add(hint);
            lastOffset = hint.offset();
        }
        return List.copyOf(out);
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
