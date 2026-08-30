package com.lazaro.sqlide.core.sql;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Finds multi-line fold ranges for {@code ()}, {@code {}}, and {@code []} in SQL,
 * skipping string literals and comments.
 */
public final class SqlFoldRegions {

    public record Region(int startLine, int endLine, int openOffset, int closeOffset, char open) {
        public boolean spansMultipleLines() {
            return endLine > startLine;
        }
    }

    private record Frame(char open, int line, int offset) {
    }

    private SqlFoldRegions() {
    }

    /** All multi-line fold regions, ordered by opening offset. */
    public static List<Region> find(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        List<Region> regions = new ArrayList<>();
        Deque<Frame> stack = new ArrayDeque<>();
        int line = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') {
                line++;
                i++;
                continue;
            }
            if (c == '-' && i + 1 < text.length() && text.charAt(i + 1) == '-') {
                i = skipLineComment(text, i);
                continue;
            }
            if (c == '/' && i + 1 < text.length() && text.charAt(i + 1) == '*') {
                int[] skipped = skipBlockComment(text, i, line);
                i = skipped[0];
                line = skipped[1];
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                // JSON injected into SQL string literals can still fold on { } / [ ].
                int[] consumed = scanQuotedWithJsonFolds(text, i, c, line, regions);
                i = consumed[0];
                line = consumed[1];
                continue;
            }
            if (c == '(' || c == '{' || c == '[') {
                stack.push(new Frame(c, line, i));
                i++;
                continue;
            }
            if (c == ')' || c == '}' || c == ']') {
                char expected = openerFor(c);
                if (!stack.isEmpty() && stack.peek().open() == expected) {
                    Frame open = stack.pop();
                    if (line > open.line()) {
                        regions.add(new Region(open.line(), line, open.offset(), i, open.open()));
                    }
                }
                i++;
                continue;
            }
            i++;
        }
        regions.sort(Comparator.comparingInt(Region::openOffset));
        return List.copyOf(regions);
    }

    /**
     * Walks a quoted literal; records multi-line {@code {}} / {@code []} folds for
     * JSON-in-SQL while ignoring parentheses inside the string.
     *
     * @return {@code [indexAfterLiteral, line]}
     */
    private static int[] scanQuotedWithJsonFolds(
            String text, int start, char quote, int line, List<Region> regions) {
        Deque<Frame> jsonStack = new ArrayDeque<>();
        int i = start + 1;
        int currentLine = line;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') {
                currentLine++;
                i++;
                continue;
            }
            if (c == '\\' && i + 1 < text.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (quote == '\'' && i + 1 < text.length() && text.charAt(i + 1) == '\'') {
                    i += 2;
                    continue;
                }
                return new int[]{i + 1, currentLine};
            }
            if (c == '{' || c == '[') {
                jsonStack.push(new Frame(c, currentLine, i));
                i++;
                continue;
            }
            if (c == '}' || c == ']') {
                char expected = openerFor(c);
                if (!jsonStack.isEmpty() && jsonStack.peek().open() == expected) {
                    Frame open = jsonStack.pop();
                    if (currentLine > open.line()) {
                        regions.add(new Region(open.line(), currentLine, open.offset(), i, open.open()));
                    }
                }
                i++;
                continue;
            }
            i++;
        }
        return new int[]{text.length(), currentLine};
    }

    /**
     * Map of opening line → preferred fold region starting on that line
     * (outermost / earliest open when several share a line).
     */
    public static Map<Integer, Region> byStartLine(String text) {
        Map<Integer, Region> map = new LinkedHashMap<>();
        for (Region region : find(text)) {
            map.putIfAbsent(region.startLine(), region);
        }
        return Map.copyOf(map);
    }

    private static char openerFor(char close) {
        return switch (close) {
            case ')' -> '(';
            case '}' -> '{';
            case ']' -> '[';
            default -> '\0';
        };
    }

    private static int skipLineComment(String text, int start) {
        int i = start + 2;
        while (i < text.length() && text.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    /** @return {@code [nextIndex, lineAfter]} */
    private static int[] skipBlockComment(String text, int start, int line) {
        int i = start + 2;
        int currentLine = line;
        while (i + 1 < text.length()) {
            char c = text.charAt(i);
            if (c == '\n') {
                currentLine++;
            }
            if (c == '*' && text.charAt(i + 1) == '/') {
                return new int[]{i + 2, currentLine};
            }
            i++;
        }
        return new int[]{text.length(), currentLine};
    }
}
