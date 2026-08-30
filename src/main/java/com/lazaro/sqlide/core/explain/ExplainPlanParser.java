package com.lazaro.sqlide.core.explain;

import com.lazaro.sqlide.core.db.QueryResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns tabular / text EXPLAIN output into an {@link ExplainPlanNode} tree.
 *
 * <p>Handles:
 * <ul>
 *   <li>Indented text plans (PostgreSQL, MySQL {@code EXPLAIN ANALYZE}, H2)</li>
 *   <li>Classic MySQL tabular EXPLAIN ({@code id}, {@code select_type}, {@code table}…)</li>
 *   <li>Single-column plan dumps where each row is one line of text</li>
 * </ul>
 */
public final class ExplainPlanParser {

    private static final Pattern LEADING_WS = Pattern.compile("^(\\s*)(.*)$");
    private static final Pattern COST_TAIL = Pattern.compile(
            "^(.*?)(\\s*(?:\\(cost=|\\(actual time=|\\(rows=|cost=|rows=).+)$",
            Pattern.CASE_INSENSITIVE);

    private ExplainPlanParser() {
    }

    /**
     * @return a root plan when {@code result} looks like EXPLAIN output; empty otherwise
     */
    public static Optional<ExplainPlanNode> tryParse(QueryResult result) {
        if (result == null || result.isError() || !result.isResultSet() || result.rows().isEmpty()) {
            return Optional.empty();
        }
        if (isClassicMysqlExplain(result)) {
            return Optional.of(parseMysqlTabular(result));
        }
        if (isTextPlanResult(result)) {
            List<String> lines = flattenTextLines(result);
            if (lines.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(parseIndentedText(lines));
        }
        return Optional.empty();
    }

    /** Force-parse any result-set rows as a text plan (used after an Explain action). */
    public static ExplainPlanNode parse(QueryResult result) {
        return tryParse(result).orElseGet(() -> fallbackFlat(result));
    }

    static boolean isTextPlanResult(QueryResult result) {
        if (result.columnCount() == 1) {
            return true;
        }
        // Postgres often returns "QUERY PLAN"; some drivers add extra columns.
        for (String name : result.columnNames()) {
            String upper = name.toUpperCase(Locale.ROOT);
            if (upper.contains("PLAN") || upper.equals("EXPLAIN")) {
                return true;
            }
        }
        return false;
    }

    static boolean isClassicMysqlExplain(QueryResult result) {
        List<String> names = result.columnNames().stream()
                .map(n -> n.toLowerCase(Locale.ROOT))
                .toList();
        return names.contains("id")
                && names.contains("select_type")
                && names.contains("table")
                && result.columnCount() >= 4;
    }

    static ExplainPlanNode parseIndentedText(List<String> lines) {
        ExplainPlanNode.Builder root = new ExplainPlanNode.Builder("Plan", "");
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(-1, root));

        for (String raw : lines) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            Matcher matcher = LEADING_WS.matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            int indent = indentWidth(matcher.group(1));
            String content = matcher.group(2).strip();
            if (content.isEmpty()) {
                continue;
            }
            String label = content;
            String detail = "";
            Matcher cost = COST_TAIL.matcher(content);
            if (cost.matches()) {
                label = cost.group(1).strip();
                detail = cost.group(2).strip();
            }
            ExplainPlanNode.Builder node = new ExplainPlanNode.Builder(label, detail);
            while (stack.size() > 1 && stack.peek().indent >= indent) {
                stack.pop();
            }
            stack.peek().builder.add(node);
            stack.push(new Frame(indent, node));
        }

        ExplainPlanNode built = root.build();
        if (built.children().size() == 1 && built.label().equals("Plan")) {
            return built.children().getFirst();
        }
        return built;
    }

    static ExplainPlanNode parseMysqlTabular(QueryResult result) {
        int idIdx = columnIndex(result, "id");
        int typeIdx = columnIndex(result, "select_type");
        int tableIdx = columnIndex(result, "table");
        int accessIdx = columnIndex(result, "type");
        int rowsIdx = columnIndex(result, "rows");
        int extraIdx = columnIndex(result, "extra");
        int keyIdx = columnIndex(result, "key");

        ExplainPlanNode.Builder root = new ExplainPlanNode.Builder("EXPLAIN", "");
        Deque<Frame> stack = new ArrayDeque<>();
        stack.push(new Frame(0, root));

        for (List<String> row : result.rows()) {
            int id = parseInt(cell(row, idIdx), 1);
            String selectType = cell(row, typeIdx);
            String table = cell(row, tableIdx);
            String access = cell(row, accessIdx);
            String rows = cell(row, rowsIdx);
            String extra = cell(row, extraIdx);
            String key = cell(row, keyIdx);

            String label = (table == null || table.isBlank() ? selectType : table);
            if (selectType != null && !selectType.isBlank() && table != null && !table.isBlank()) {
                label = selectType + " · " + table;
            }
            List<String> details = new ArrayList<>();
            if (access != null && !access.isBlank()) {
                details.add(access);
            }
            if (key != null && !key.isBlank()) {
                details.add("key=" + key);
            }
            if (rows != null && !rows.isBlank()) {
                details.add("rows=" + rows);
            }
            if (extra != null && !extra.isBlank()) {
                details.add(extra);
            }
            ExplainPlanNode.Builder node = new ExplainPlanNode.Builder(label, String.join(" · ", details));

            while (stack.size() > 1 && stack.peek().indent >= id) {
                stack.pop();
            }
            stack.peek().builder.add(node);
            stack.push(new Frame(id, node));
        }
        return root.build();
    }

    private static ExplainPlanNode fallbackFlat(QueryResult result) {
        ExplainPlanNode.Builder root = new ExplainPlanNode.Builder("Plan", "");
        if (result == null || result.rows().isEmpty()) {
            return root.build();
        }
        for (List<String> row : result.rows()) {
            String line = String.join(" | ", row.stream().map(v -> v == null ? "NULL" : v).toList());
            root.add(new ExplainPlanNode.Builder(line, ""));
        }
        return root.build();
    }

    private static List<String> flattenTextLines(QueryResult result) {
        List<String> lines = new ArrayList<>();
        int planCol = 0;
        for (int i = 0; i < result.columnNames().size(); i++) {
            String name = result.columnNames().get(i).toUpperCase(Locale.ROOT);
            if (name.contains("PLAN") || name.equals("EXPLAIN")) {
                planCol = i;
                break;
            }
        }
        for (List<String> row : result.rows()) {
            if (planCol >= row.size()) {
                continue;
            }
            String cell = row.get(planCol);
            if (cell == null || cell.isBlank()) {
                continue;
            }
            // Some drivers pack a multi-line plan into one cell.
            for (String line : cell.split("\\R")) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines;
    }

    private static int columnIndex(QueryResult result, String name) {
        for (int i = 0; i < result.columnNames().size(); i++) {
            if (result.columnNames().get(i).equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int index) {
        if (index < 0 || index >= row.size()) {
            return null;
        }
        return row.get(index);
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int indentWidth(String whitespace) {
        int width = 0;
        for (int i = 0; i < whitespace.length(); i++) {
            char c = whitespace.charAt(i);
            width += c == '\t' ? 4 : 1;
        }
        return width;
    }

    private record Frame(int indent, ExplainPlanNode.Builder builder) {
    }
}
