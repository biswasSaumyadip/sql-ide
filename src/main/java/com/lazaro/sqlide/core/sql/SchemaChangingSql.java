package com.lazaro.sqlide.core.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects statements that add, drop, or reshape schema objects so the client-side
 * cache can be refreshed after a successful run.
 */
public final class SchemaChangingSql {

    private static final Set<String> DDL_VERBS = Set.of("CREATE", "ALTER", "DROP", "RENAME");
    private static final Set<String> SCHEMA_OBJECTS = Set.of(
            "TABLE", "VIEW", "PROCEDURE", "FUNCTION", "TRIGGER",
            "INDEX", "SCHEMA", "DATABASE", "EVENT", "SEQUENCE");

    private SchemaChangingSql() {
    }

    /** True when any statement in {@code sqls} creates, alters, or drops a schema object. */
    public static boolean anyChangesSchema(Iterable<String> sqls) {
        if (sqls == null) {
            return false;
        }
        for (String sql : sqls) {
            if (changesSchema(sql)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for {@code CREATE}/{@code ALTER}/{@code DROP}/{@code RENAME} of tables,
     * views, routines, indexes, and databases. {@code SELECT} and DML return false.
     */
    public static boolean changesSchema(String sql) {
        List<String> tokens = leadingTokens(sql, 16);
        if (tokens.isEmpty() || !DDL_VERBS.contains(tokens.getFirst())) {
            return false;
        }
        for (String token : tokens) {
            if (SCHEMA_OBJECTS.contains(token)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True for client meta-commands ({@code DELIMITER}) and routine DDL that
     * JSqlParser cannot parse. Used to avoid false syntax errors in the editor.
     */
    public static boolean isClientOrRoutineSql(String sql) {
        List<String> tokens = leadingTokens(sql, 16);
        if (tokens.isEmpty()) {
            return false;
        }
        String first = tokens.getFirst();
        if ("DELIMITER".equals(first) || "CALL".equals(first)) {
            return true;
        }
        if (!Set.of("CREATE", "ALTER", "DROP").contains(first)) {
            return false;
        }
        for (String token : tokens) {
            if ("PROCEDURE".equals(token) || "FUNCTION".equals(token) || "TRIGGER".equals(token)) {
                return true;
            }
        }
        return false;
    }

    static List<String> leadingTokens(String sql, int max) {
        if (sql == null || sql.isBlank() || max <= 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>(Math.min(max, 8));
        int n = sql.length();
        int i = 0;
        while (i < n && out.size() < max) {
            char c = sql.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '-' && i + 1 < n && sql.charAt(i + 1) == '-') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (c == '#') {
                int nl = sql.indexOf('\n', i);
                i = nl < 0 ? n : nl + 1;
                continue;
            }
            if (c == '/' && i + 1 < n && sql.charAt(i + 1) == '*') {
                int end = sql.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                char quote = c;
                i++;
                while (i < n) {
                    if (sql.charAt(i) == quote) {
                        if (i + 1 < n && sql.charAt(i + 1) == quote) {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                continue;
            }
            if (isIdentStart(c)) {
                int start = i;
                i++;
                while (i < n && isIdentPart(sql.charAt(i))) {
                    i++;
                }
                out.add(sql.substring(start, i).toUpperCase(Locale.ROOT));
                continue;
            }
            i++;
        }
        return List.copyOf(out);
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
