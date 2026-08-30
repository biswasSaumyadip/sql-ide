package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.json.JsonPayloads;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex tokeniser for SQL, kept separate from the editor widget so it can be
 * exercised without a JavaFX toolkit. JSON-looking single-quoted string literals
 * are expanded into nested JSON token spans with an {@code injected-language} marker.
 * Active fold summary strings are styled as {@code fold-placeholder}.
 */
final class SqlSyntaxHighlighter {

    static final String KEYWORD = "sql-keyword";
    static final String FUNCTION = "sql-function";
    static final String STRING = "sql-string";
    static final String NUMBER = "sql-number";
    static final String COMMENT = "sql-comment";
    static final String OPERATOR = "sql-operator";
    static final String PUNCTUATION = "sql-punctuation";
    static final String INJECTED_LANGUAGE = "injected-language";
    static final String FOLD_PLACEHOLDER = "fold-placeholder";

    private static final String[] KEYWORDS = {
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT",
            "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "USING",
            "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "FETCH", "TOP", "DISTINCT",
            "INTO", "VALUES", "SET", "AS", "UNION", "ALL", "INTERSECT", "EXCEPT", "WITH", "RECURSIVE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "SCHEMA", "DATABASE",
            "PROCEDURE", "FUNCTION", "TRIGGER", "CALL", "DELIMITER",
            "DECLARE", "RETURNS", "INOUT", "LOOP", "LEAVE", "ITERATE", "REPEAT", "UNTIL", "WHILE",
            "ELSEIF", "SIGNAL", "RESIGNAL", "HANDLER", "CURSOR",
            "PRIMARY", "FOREIGN", "KEY", "REFERENCES", "CONSTRAINT", "UNIQUE", "DEFAULT", "AUTO_INCREMENT",
            "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE", "ILIKE", "IS", "NULL",
            "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC",
            "BEGIN", "COMMIT", "ROLLBACK", "TRANSACTION", "GRANT", "REVOKE", "EXPLAIN", "SHOW", "DESCRIBE",
            "INT", "INTEGER", "BIGINT", "SMALLINT", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "BOOLEAN",
            "CHAR", "VARCHAR", "TEXT", "DATE", "TIME", "TIMESTAMP", "BLOB", "JSON"
    };

    private static final String[] FUNCTIONS = {
            "COUNT", "SUM", "AVG", "MIN", "MAX", "ROUND", "ABS", "CEIL", "FLOOR",
            "COALESCE", "NULLIF", "CAST", "CONVERT", "CONCAT", "SUBSTRING", "TRIM", "LENGTH",
            "UPPER", "LOWER", "REPLACE", "NOW", "CURRENT_DATE", "CURRENT_TIMESTAMP",
            "ROW_NUMBER", "RANK", "DENSE_RANK", "OVER", "PARTITION"
    };

    /**
     * Fold placeholders first (exact summary shapes), then comments/strings so
     * keywords inside them are not mistaken for code.
     */
    private static final Pattern SYNTAX = Pattern.compile(
            "(?<FOLD>\\{ \\d+ keys? \\}|\\[ \\d+ items? \\]|\\( [^\\n]{1,60}?\\.\\.\\. \\))"
                    + "|(?<COMMENT>--[^\\n]*|/\\*(?:.|\\R)*?\\*/)"
                    + "|(?<STRING>'(?:[^']|'')*'|\"(?:[^\"]|\"\")*\"|`[^`]*`)"
                    + "|(?<FUNCTION>\\b(?i:" + String.join("|", FUNCTIONS) + ")\\b(?=\\s*\\())"
                    + "|(?<KEYWORD>\\b(?i:" + String.join("|", KEYWORDS) + ")\\b)"
                    + "|(?<NUMBER>\\b\\d+(?:\\.\\d+)?\\b)"
                    + "|(?<OPERATOR>[=<>!]+|\\|\\||[+\\-*/%])"
                    + "|(?<PUNCTUATION>[;,()])");

    private SqlSyntaxHighlighter() {
    }

    /** A classified region of the document, in character offsets. */
    record Token(String styleClass, int start, int end) {
    }

    /**
     * Unescaped content of a single-quoted SQL string that looks like JSON,
     * with absolute offsets for the literal (including quotes).
     */
    record JsonStringLiteral(String json, int literalStart, int literalEnd) {
    }

    /** Pure tokenisation, free of any JavaFX type. */
    static List<Token> tokenize(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        Matcher matcher = SYNTAX.matcher(text);
        List<Token> tokens = new ArrayList<>();
        while (matcher.find()) {
            tokens.add(new Token(styleClassOf(matcher), matcher.start(), matcher.end()));
        }
        return tokens;
    }

    static StyleSpans<Collection<String>> computeHighlighting(String text) {
        return computeHighlighting(text, List.of());
    }

    /**
     * @param foldRanges exact {@code [start,end)} spans for active fold summaries
     */
    static StyleSpans<Collection<String>> computeHighlighting(String text, List<int[]> foldRanges) {
        String source = text == null ? "" : text;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int lastEnd = 0;
        List<int[]> folds = normalizeFolds(foldRanges, source.length());

        for (Token token : tokenize(source)) {
            lastEnd = emitFoldsBefore(builder, folds, lastEnd, token.start());
            if (token.end() <= lastEnd) {
                continue;
            }
            int start = Math.max(token.start(), lastEnd);
            if (start >= token.end()) {
                continue;
            }
            builder.add(Collections.emptyList(), start - lastEnd);
            String style = token.styleClass();
            if (FOLD_PLACEHOLDER.equals(style) || coveredByFold(folds, start, token.end())) {
                builder.add(List.of(FOLD_PLACEHOLDER), token.end() - start);
            } else if (STRING.equals(style) && isJsonInjectableString(source, token)) {
                appendJsonInjection(builder, source, token);
            } else {
                builder.add(List.of(style), token.end() - start);
            }
            lastEnd = token.end();
        }
        lastEnd = emitFoldsBefore(builder, folds, lastEnd, source.length());
        builder.add(Collections.emptyList(), source.length() - lastEnd);
        return builder.create();
    }

    private static List<int[]> normalizeFolds(List<int[]> foldRanges, int length) {
        if (foldRanges == null || foldRanges.isEmpty() || length <= 0) {
            return List.of();
        }
        List<int[]> out = new ArrayList<>();
        for (int[] range : foldRanges) {
            if (range == null || range.length < 2) {
                continue;
            }
            int start = Math.max(0, Math.min(range[0], length));
            int end = Math.max(start, Math.min(range[1], length));
            if (end > start) {
                out.add(new int[]{start, end});
            }
        }
        out.sort((a, b) -> Integer.compare(a[0], b[0]));
        return out;
    }

    private static int emitFoldsBefore(
            StyleSpansBuilder<Collection<String>> builder, List<int[]> folds, int lastEnd, int until) {
        for (int[] fold : folds) {
            if (fold[1] <= lastEnd || fold[0] >= until) {
                continue;
            }
            int start = Math.max(fold[0], lastEnd);
            int end = Math.min(fold[1], until);
            if (end <= start) {
                continue;
            }
            if (start > lastEnd) {
                builder.add(Collections.emptyList(), start - lastEnd);
            }
            builder.add(List.of(FOLD_PLACEHOLDER), end - start);
            lastEnd = end;
        }
        return lastEnd;
    }

    private static boolean coveredByFold(List<int[]> folds, int start, int end) {
        for (int[] fold : folds) {
            if (start >= fold[0] && end <= fold[1]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Finds a JSON-looking single-quoted string under {@code caret}, or returns the
     * selection when it itself looks like JSON (with or without surrounding quotes).
     */
    static Optional<JsonStringLiteral> findJsonLiteralAt(String text, int caret, int selectionStart, int selectionEnd) {
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        if (selectionEnd > selectionStart) {
            String selected = text.substring(selectionStart, selectionEnd).strip();
            if (selected.length() >= 2 && selected.charAt(0) == '\'' && selected.charAt(selected.length() - 1) == '\'') {
                Optional<JsonStringLiteral> quoted = asJsonLiteral(text, selectionStart, selectionEnd);
                if (quoted.isPresent()) {
                    return quoted;
                }
            }
            if (JsonPayloads.looksLikeJson(selected)) {
                return Optional.of(new JsonStringLiteral(selected, selectionStart, selectionEnd));
            }
        }
        int index = Math.max(0, Math.min(caret, text.length()));
        for (Token token : tokenize(text)) {
            if (!STRING.equals(token.styleClass())) {
                continue;
            }
            if (index < token.start() || index > token.end()) {
                continue;
            }
            return asJsonLiteral(text, token.start(), token.end());
        }
        return Optional.empty();
    }

    private static Optional<JsonStringLiteral> asJsonLiteral(String source, int start, int end) {
        if (end - start < 2 || source.charAt(start) != '\'') {
            return Optional.empty();
        }
        String inner = unescapeSqlString(source.substring(start + 1, end - 1));
        if (!JsonPayloads.looksLikeJson(inner)) {
            return Optional.empty();
        }
        return Optional.of(new JsonStringLiteral(inner, start, end));
    }

    private static boolean isJsonInjectableString(String source, Token token) {
        if (token.end() - token.start() < 4) {
            return false;
        }
        if (source.charAt(token.start()) != '\'') {
            return false;
        }
        String inner = unescapeSqlString(source.substring(token.start() + 1, token.end() - 1));
        return JsonPayloads.looksLikeJson(inner);
    }

    private static void appendJsonInjection(
            StyleSpansBuilder<Collection<String>> builder, String source, Token token) {
        builder.add(List.of(STRING, INJECTED_LANGUAGE), 1);

        String inner = source.substring(token.start() + 1, token.end() - 1);
        List<JsonSyntaxHighlighter.Token> jsonTokens = JsonSyntaxHighlighter.tokenize(inner);
        int last = 0;
        for (JsonSyntaxHighlighter.Token jsonToken : jsonTokens) {
            if (jsonToken.start() > last) {
                builder.add(List.of(INJECTED_LANGUAGE), jsonToken.start() - last);
            }
            builder.add(
                    List.of(jsonToken.styleClass(), INJECTED_LANGUAGE),
                    jsonToken.end() - jsonToken.start());
            last = jsonToken.end();
        }
        if (last < inner.length()) {
            builder.add(List.of(INJECTED_LANGUAGE), inner.length() - last);
        }

        builder.add(List.of(STRING, INJECTED_LANGUAGE), 1);
    }

    /** SQL single-quoted literals escape a quote as two consecutive quotes. */
    static String unescapeSqlString(String escaped) {
        if (escaped == null || escaped.isEmpty()) {
            return "";
        }
        return escaped.replace("''", "'");
    }

    private static String styleClassOf(Matcher matcher) {
        if (matcher.group("FOLD") != null) {
            return FOLD_PLACEHOLDER;
        }
        if (matcher.group("COMMENT") != null) {
            return COMMENT;
        }
        if (matcher.group("STRING") != null) {
            return STRING;
        }
        if (matcher.group("FUNCTION") != null) {
            return FUNCTION;
        }
        if (matcher.group("KEYWORD") != null) {
            return KEYWORD;
        }
        if (matcher.group("NUMBER") != null) {
            return NUMBER;
        }
        if (matcher.group("OPERATOR") != null) {
            return OPERATOR;
        }
        return PUNCTUATION;
    }
}
