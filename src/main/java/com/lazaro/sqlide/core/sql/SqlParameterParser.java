package com.lazaro.sqlide.core.sql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects {@code :name} and {@code ?} placeholders in SQL, skipping string
 * literals and comments.
 */
public final class SqlParameterParser {

    public enum Kind {
        NAMED,
        POSITIONAL
    }

    public record Parameter(Kind kind, String name, int index) {
        public String displayName() {
            return kind == Kind.NAMED ? name : ("?" + (index + 1));
        }
    }

    private static final Pattern NAMED = Pattern.compile(":([A-Za-z_][A-Za-z0-9_]*)");

    private SqlParameterParser() {
    }

    public static List<Parameter> find(String sql) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }
        List<Parameter> found = new ArrayList<>();
        Set<String> seenNamed = new LinkedHashSet<>();
        int positional = 0;
        int i = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                i = skipQuoted(sql, i, c);
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                i = skipLineComment(sql, i);
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                i = skipBlockComment(sql, i);
                continue;
            }
            if (c == '?') {
                found.add(new Parameter(Kind.POSITIONAL, "?", positional++));
                i++;
                continue;
            }
            if (c == ':') {
                Matcher matcher = NAMED.matcher(sql);
                if (matcher.find(i) && matcher.start() == i) {
                    String name = matcher.group(1);
                    String key = name.toLowerCase(Locale.ROOT);
                    if (seenNamed.add(key)) {
                        found.add(new Parameter(Kind.NAMED, name, -1));
                    }
                    i = matcher.end();
                    continue;
                }
            }
            i++;
        }
        return List.copyOf(found);
    }

    /**
     * Substitutes parameter values into SQL. Named values keyed without leading
     * colon; positional values ordered for each {@code ?}.
     */
    public static String substitute(String sql, java.util.Map<String, String> namedValues, List<String> positionalValues) {
        Objects.requireNonNull(sql, "sql");
        java.util.Map<String, String> named = namedValues == null ? java.util.Map.of() : namedValues;
        List<String> positional = positionalValues == null ? List.of() : positionalValues;
        StringBuilder out = new StringBuilder(sql.length() + 32);
        int i = 0;
        int pos = 0;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\'' || c == '"' || c == '`') {
                int end = skipQuoted(sql, i, c);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '-' && i + 1 < sql.length() && sql.charAt(i + 1) == '-') {
                int end = skipLineComment(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '/' && i + 1 < sql.length() && sql.charAt(i + 1) == '*') {
                int end = skipBlockComment(sql, i);
                out.append(sql, i, end);
                i = end;
                continue;
            }
            if (c == '?') {
                if (pos >= positional.size()) {
                    throw new IllegalArgumentException("Missing value for positional parameter ?" + (pos + 1));
                }
                out.append(sqlLiteral(positional.get(pos++)));
                i++;
                continue;
            }
            if (c == ':') {
                Matcher matcher = NAMED.matcher(sql);
                if (matcher.find(i) && matcher.start() == i) {
                    String name = matcher.group(1);
                    String value = named.get(name);
                    if (value == null) {
                        value = named.get(name.toLowerCase(Locale.ROOT));
                    }
                    if (value == null) {
                        throw new IllegalArgumentException("Missing value for parameter :" + name);
                    }
                    out.append(sqlLiteral(value));
                    i = matcher.end();
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static String sqlLiteral(String raw) {
        if (raw == null || "NULL".equalsIgnoreCase(raw.strip())) {
            return "NULL";
        }
        // Allow unquoted numbers / booleans typed by the user.
        String trimmed = raw.strip();
        if (trimmed.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) {
            return trimmed;
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        return "'" + trimmed.replace("'", "''") + "'";
    }

    private static int skipQuoted(String sql, int start, char quote) {
        int i = start + 1;
        while (i < sql.length()) {
            char c = sql.charAt(i);
            if (c == '\\' && i + 1 < sql.length()) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < sql.length() && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return sql.length();
    }

    private static int skipLineComment(String sql, int start) {
        int i = start + 2;
        while (i < sql.length() && sql.charAt(i) != '\n') {
            i++;
        }
        return i;
    }

    private static int skipBlockComment(String sql, int start) {
        int i = start + 2;
        while (i + 1 < sql.length()) {
            if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return sql.length();
    }
}
