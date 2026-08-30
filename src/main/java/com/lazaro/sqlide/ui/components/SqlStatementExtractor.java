package com.lazaro.sqlide.ui.components;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits SQL text into statements the way the MySQL client / DataGrip do: on the
 * <em>active delimiter</em> (default {@code ;}) outside quotes and comments.
 *
 * <p>{@code DELIMITER} is a client meta-command, not sent to JDBC. It changes
 * the terminator used for subsequent statements so stored procedures with
 * internal semicolons stay one executable block.
 */
public final class SqlStatementExtractor {

    private static final String DEFAULT_DELIMITER = ";";

    /** Inclusive-start, exclusive-end range in the original buffer. */
    public record Span(int start, int end) {
        public Span {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("start=" + start + " end=" + end);
            }
        }

        public boolean isEmpty() {
            return end <= start;
        }
    }

    /**
     * One parsed region: either a {@code DELIMITER} meta-command or a SQL
     * statement. {@code contentEnd} is the start of the terminator (or {@code end}
     * when there is none).
     */
    record Piece(int from, int contentEnd, int end, boolean meta) {
    }

    private SqlStatementExtractor() {
    }

    /**
     * @param sql   full editor text
     * @param caret caret offset (clamped to {@code [0, sql.length()]})
     * @return the statement containing the caret, trimmed, without its terminator;
     *         empty when there is none or the caret is on a {@code DELIMITER} command
     */
    public static String statementAt(String sql, int caret) {
        Piece piece = pieceAt(sql, caret);
        if (piece == null || sql == null || piece.meta()) {
            return "";
        }
        return trimStatement(sql.substring(piece.from(), piece.contentEnd()));
    }

    /**
     * Buffer offsets of the statement under {@code caret}, including a trailing
     * terminator ({@code ;}, {@code $$}, …) when present. Leading/trailing
     * whitespace around the statement is excluded so a highlight can hug the query.
     */
    public static Span rangeAt(String sql, int caret) {
        if (sql == null || sql.isBlank()) {
            return new Span(0, 0);
        }
        Piece piece = pieceAt(sql, caret);
        if (piece == null) {
            return new Span(0, 0);
        }
        return hug(sql, piece);
    }

    /**
     * All non-empty SQL statements in {@code sql}, in order. {@code DELIMITER}
     * commands are applied as state and omitted from the result. Terminators are
     * stripped; blank fragments are dropped.
     */
    public static List<String> statements(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Piece piece : parse(sql)) {
            if (piece.meta()) {
                continue;
            }
            String statement = trimStatement(sql.substring(piece.from(), piece.contentEnd()));
            if (!statement.isEmpty()) {
                out.add(statement);
            }
        }
        return List.copyOf(out);
    }

    static Piece pieceAt(String sql, int caret) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        int pos = Math.max(0, Math.min(caret, sql.length()));
        Piece lastNonEmpty = null;
        for (Piece piece : parse(sql)) {
            Span span = hug(sql, piece);
            if (span.isEmpty() && !piece.meta()) {
                continue;
            }
            if (pos < piece.end()) {
                return span.isEmpty() ? lastNonEmpty : piece;
            }
            if (!span.isEmpty()) {
                lastNonEmpty = piece;
            }
        }
        return lastNonEmpty;
    }

    static List<Piece> parse(String sql) {
        List<Piece> pieces = new ArrayList<>();
        String delimiter = DEFAULT_DELIMITER;
        int start = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            char next = i + 1 < sql.length() ? sql.charAt(i + 1) : 0;

            if (c == '-' && next == '-') {
                i = skipLine(sql, i + 2);
                continue;
            }
            if (c == '/' && next == '*') {
                i = skipBlockComment(sql, i + 2);
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                continue;
            }

            DelimiterCommand command = parseDelimiterCommand(sql, i);
            if (command != null) {
                if (hasNonWhitespace(sql, start, i)) {
                    pieces.add(new Piece(start, i, i, false));
                }
                pieces.add(new Piece(command.from(), command.end(), command.end(), true));
                delimiter = command.token();
                start = command.end();
                i = command.end();
                continue;
            }

            if (delimiterMatches(sql, i, delimiter)) {
                pieces.add(new Piece(start, i, i + delimiter.length(), false));
                start = i + delimiter.length();
                i = start;
                continue;
            }
            i++;
        }
        if (start < sql.length()) {
            pieces.add(new Piece(start, sql.length(), sql.length(), false));
        }
        return pieces;
    }

    private static Span hug(String sql, Piece piece) {
        int start = piece.from();
        int end = piece.end();
        while (start < end && Character.isWhitespace(sql.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(sql.charAt(end - 1))) {
            end--;
        }
        if (end <= start) {
            return new Span(0, 0);
        }
        return new Span(start, end);
    }

    private static String trimStatement(String fragment) {
        String trimmed = fragment.strip();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static boolean hasNonWhitespace(String sql, int from, int to) {
        int start = Math.max(0, from);
        int end = Math.min(sql.length(), to);
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(sql.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean delimiterMatches(String sql, int index, String delimiter) {
        if (delimiter == null || delimiter.isEmpty() || index < 0) {
            return false;
        }
        int n = delimiter.length();
        return index + n <= sql.length() && sql.regionMatches(index, delimiter, 0, n);
    }

    /**
     * {@code DELIMITER} is recognized only at the start of a line (after optional
     * spaces/tabs), never inside a statement's quoted strings or comments.
     */
    private static DelimiterCommand parseDelimiterCommand(String sql, int index) {
        if (!atLineStart(sql, index)) {
            return null;
        }
        int j = index;
        while (j < sql.length() && isHorizontalWs(sql.charAt(j))) {
            j++;
        }
        if (!startsWithWord(sql, j, "DELIMITER")) {
            return null;
        }
        int afterKeyword = j + "DELIMITER".length();
        int k = afterKeyword;
        while (k < sql.length() && isHorizontalWs(sql.charAt(k))) {
            k++;
        }
        if (k >= sql.length() || isNewline(sql.charAt(k))) {
            return null;
        }
        int tokenStart = k;
        while (k < sql.length() && !Character.isWhitespace(sql.charAt(k))) {
            k++;
        }
        String token = sql.substring(tokenStart, k);
        if (token.isEmpty()) {
            return null;
        }
        while (k < sql.length() && !isNewline(sql.charAt(k))) {
            k++;
        }
        if (k < sql.length() && sql.charAt(k) == '\r') {
            k++;
        }
        if (k < sql.length() && sql.charAt(k) == '\n') {
            k++;
        }
        return new DelimiterCommand(j, k, token);
    }

    private static boolean atLineStart(String sql, int index) {
        if (index <= 0) {
            return true;
        }
        return isNewline(sql.charAt(index - 1));
    }

    private static boolean startsWithWord(String sql, int index, String word) {
        int n = word.length();
        if (index < 0 || index + n > sql.length()) {
            return false;
        }
        if (!sql.regionMatches(true, index, word, 0, n)) {
            return false;
        }
        if (index + n == sql.length()) {
            return true;
        }
        return !isIdentChar(sql.charAt(index + n));
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static boolean isHorizontalWs(char c) {
        return c == ' ' || c == '\t' || c == '\f';
    }

    private static boolean isNewline(char c) {
        return c == '\n' || c == '\r';
    }

    private static int skipLine(String sql, int from) {
        int i = from;
        while (i < sql.length() && !isNewline(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String sql, int from) {
        int i = from;
        while (i + 1 < sql.length()) {
            if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return sql.length();
    }

    private static int skipQuoted(String sql, int openIndex, char quote) {
        int i = openIndex + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            if (c == '\\' && i + 1 < sql.length()) {
                i += 2;
                continue;
            }
            i++;
        }
        return sql.length();
    }

    private record DelimiterCommand(int from, int end, String token) {
    }
}
