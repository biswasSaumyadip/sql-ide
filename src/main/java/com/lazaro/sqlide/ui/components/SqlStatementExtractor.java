package com.lazaro.sqlide.ui.components;

/**
 * Picks the SQL statement under the caret, the way DataGrip / IntelliJ do when
 * nothing is selected. JDBC drivers reject multi-statement strings by default, so
 * sending the whole buffer would make the second statement look like a syntax error.
 */
final class SqlStatementExtractor {

    private SqlStatementExtractor() {
    }

    /**
     * @param sql   full editor text
     * @param caret caret offset (clamped to {@code [0, sql.length()]})
     * @return the statement containing the caret, trimmed; empty when there is none
     */
    static String statementAt(String sql, int caret) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        int pos = Math.max(0, Math.min(caret, sql.length()));

        int start = 0;
        int i = 0;
        String lastNonEmpty = "";
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
            if (c == ';') {
                String statement = trimStatement(sql.substring(start, i));
                if (pos <= i) {
                    // Caret on this statement or on its terminating semicolon.
                    return statement.isEmpty() ? lastNonEmpty : statement;
                }
                if (!statement.isEmpty()) {
                    lastNonEmpty = statement;
                }
                start = i + 1;
            }
            i++;
        }

        String trailing = trimStatement(sql.substring(start));
        if (!trailing.isEmpty()) {
            return trailing;
        }
        // Caret sits after a trailing semicolon / blank lines — run the previous statement,
        // matching DataGrip when you hit Run at the end of `USE warcraft;`.
        return lastNonEmpty;
    }

    private static String trimStatement(String fragment) {
        String trimmed = fragment.strip();
        return trimmed.isEmpty() ? "" : trimmed;
    }

    private static int skipLine(String sql, int from) {
        int i = from;
        while (i < sql.length() && sql.charAt(i) != '\n') {
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
}
