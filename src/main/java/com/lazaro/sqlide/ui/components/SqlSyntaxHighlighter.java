package com.lazaro.sqlide.ui.components;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Regex tokeniser for SQL, kept separate from the editor widget so it can be
 * exercised without a JavaFX toolkit.
 */
final class SqlSyntaxHighlighter {

    static final String KEYWORD = "sql-keyword";
    static final String FUNCTION = "sql-function";
    static final String STRING = "sql-string";
    static final String NUMBER = "sql-number";
    static final String COMMENT = "sql-comment";
    static final String OPERATOR = "sql-operator";
    static final String PUNCTUATION = "sql-punctuation";

    private static final String[] KEYWORDS = {
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT",
            "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "USING",
            "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "FETCH", "TOP", "DISTINCT",
            "INTO", "VALUES", "SET", "AS", "UNION", "ALL", "INTERSECT", "EXCEPT", "WITH", "RECURSIVE",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "SCHEMA", "DATABASE",
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
     * Alternation order is significant: comments and string literals come first so
     * that a keyword written inside them is not mistaken for code.
     */
    private static final Pattern SYNTAX = Pattern.compile(
            "(?<COMMENT>--[^\\n]*|/\\*(?:.|\\R)*?\\*/)"
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
        String source = text == null ? "" : text;
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int lastEnd = 0;

        for (Token token : tokenize(source)) {
            builder.add(Collections.emptyList(), token.start() - lastEnd);
            builder.add(List.of(token.styleClass()), token.end() - token.start());
            lastEnd = token.end();
        }
        builder.add(Collections.emptyList(), source.length() - lastEnd);
        return builder.create();
    }

    private static String styleClassOf(Matcher matcher) {
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
