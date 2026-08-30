package com.lazaro.sqlide.core.inspection;

import net.sf.jsqlparser.parser.ASTNodeAccess;
import net.sf.jsqlparser.parser.Token;

/**
 * Maps JSqlParser tokens / AST nodes onto absolute {@code [start, end)} character
 * ranges in the original SQL string.
 *
 * <p>JSqlParser's {@code absoluteBegin}/{@code absoluteEnd} are <strong>1-based</strong>,
 * with {@code absoluteEnd} exclusive (one past the last character).
 */
public final class SourceOffsets {

    private SourceOffsets() {
    }

    /**
     * Prefer a tight range around {@code preferredImage} inside the node's span
     * (IntelliJ underlines the identifier, not the whole clause).
     */
    public static int[] rangeOf(Object node, String sql, String preferredImage) {
        int[] nodeRange = rangeFromAst(node);
        if (preferredImage != null && !preferredImage.isBlank()) {
            int[] tight = findImage(sql, preferredImage, nodeRange);
            if (tight != null) {
                return clamp(tight, sql.length());
            }
        }
        if (nodeRange != null) {
            return clamp(nodeRange, sql.length());
        }
        if (preferredImage != null && !preferredImage.isBlank()) {
            int[] anywhere = findImage(sql, preferredImage, null);
            if (anywhere != null) {
                return clamp(anywhere, sql.length());
            }
        }
        return fallbackToken(sql);
    }

    static int[] rangeFromAst(Object node) {
        Token first = firstToken(node);
        Token last = lastToken(node);
        if (first == null) {
            return null;
        }
        if (last == null) {
            last = first;
        }
        return rangeFromTokens(first, last, null);
    }

    static int[] rangeFromToken(Token token, String sql) {
        if (token == null) {
            return fallbackToken(sql);
        }
        return clamp(rangeFromTokens(token, token, sql), sql.length());
    }

    private static int[] rangeFromTokens(Token first, Token last, String sql) {
        if (first.absoluteBegin > 0 && last.absoluteEnd >= first.absoluteBegin) {
            int start = first.absoluteBegin - 1;
            int end = last.absoluteEnd - 1;
            if (end <= start && first.image != null) {
                end = start + first.image.length();
            }
            return new int[]{start, Math.max(start + 1, end)};
        }
        if (sql != null) {
            return lineColumnToRange(sql, first.beginLine, first.beginColumn, last.endLine, last.endColumn);
        }
        return null;
    }

    static int[] lineColumnToRange(String sql, int beginLine, int beginColumn, int endLine, int endColumn) {
        int start = offsetOf(sql, beginLine, beginColumn);
        // endColumn is inclusive (1-based); convert to exclusive offset.
        int end = offsetOf(sql, endLine, endColumn) + 1;
        if (end <= start) {
            end = Math.min(sql.length(), start + 1);
        }
        return clamp(new int[]{start, end}, sql.length());
    }

    static int offsetOf(String sql, int line, int column) {
        int currentLine = 1;
        int i = 0;
        while (i < sql.length() && currentLine < line) {
            if (sql.charAt(i) == '\n') {
                currentLine++;
            }
            i++;
        }
        return Math.min(sql.length(), i + Math.max(0, column - 1));
    }

    private static int[] findImage(String sql, String image, int[] within) {
        String needle = stripQuotes(image);
        if (needle.isEmpty()) {
            return null;
        }
        int from = within == null ? 0 : Math.max(0, within[0]);
        int to = within == null ? sql.length() : Math.min(sql.length(), within[1]);
        String window = sql.substring(from, to);
        int at = indexOfWord(window, needle);
        if (at < 0) {
            at = indexOfIgnoreCase(window, needle);
        }
        if (at < 0) {
            return null;
        }
        return new int[]{from + at, from + at + needle.length()};
    }

    /** Case-insensitive search that prefers a whole identifier match. */
    private static int indexOfWord(String haystack, String needle) {
        String lowerHay = haystack.toLowerCase();
        String lowerNeedle = needle.toLowerCase();
        int from = 0;
        while (from <= lowerHay.length() - lowerNeedle.length()) {
            int at = lowerHay.indexOf(lowerNeedle, from);
            if (at < 0) {
                return -1;
            }
            boolean startOk = at == 0 || !isIdentChar(haystack.charAt(at - 1));
            int end = at + needle.length();
            boolean endOk = end >= haystack.length() || !isIdentChar(haystack.charAt(end));
            if (startOk && endOk) {
                return at;
            }
            from = at + 1;
        }
        return -1;
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private static int indexOfIgnoreCase(String haystack, String needle) {
        return haystack.toLowerCase().indexOf(needle.toLowerCase());
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

    private static Token firstToken(Object node) {
        Object ast = astNode(node);
        if (ast == null) {
            return null;
        }
        try {
            return (Token) ast.getClass().getMethod("jjtGetFirstToken").invoke(ast);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Token lastToken(Object node) {
        Object ast = astNode(node);
        if (ast == null) {
            return null;
        }
        try {
            return (Token) ast.getClass().getMethod("jjtGetLastToken").invoke(ast);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    private static Object astNode(Object node) {
        if (!(node instanceof ASTNodeAccess access)) {
            return null;
        }
        return access.getASTNode();
    }

    private static int[] fallbackToken(String sql) {
        if (sql == null || sql.isEmpty()) {
            return new int[]{0, 0};
        }
        // Underline the first non-whitespace character — never the whole buffer.
        int i = 0;
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        if (i >= sql.length()) {
            return new int[]{0, 1};
        }
        int end = i + 1;
        while (end < sql.length() && isIdentChar(sql.charAt(end))) {
            end++;
        }
        return new int[]{i, Math.max(i + 1, end)};
    }

    private static int[] clamp(int[] range, int length) {
        if (length <= 0) {
            return new int[]{0, 0};
        }
        int start = Math.max(0, Math.min(range[0], length - 1));
        int end = Math.max(start + 1, Math.min(range[1], length));
        return new int[]{start, end};
    }
}
