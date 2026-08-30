package com.lazaro.sqlide.core.sql;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects simple single-table SELECTs that can be edited in the result grid
 * (DataGrip-style): {@code SELECT … FROM table [WHERE …] [ORDER BY …] [LIMIT …]}
 * with no JOIN / GROUP BY / DISTINCT / UNION / subqueries in the FROM clause.
 */
public final class SimpleSelectAnalyzer {

    public record SimpleSelect(String catalog, String table, String rawFromName) {
        public String qualifiedName() {
            if (catalog == null || catalog.isBlank()) {
                return table;
            }
            return catalog + "." + table;
        }
    }

    private static final Pattern SIMPLE = Pattern.compile(
            "^\\s*SELECT\\s+(.+?)\\s+FROM\\s+([\\w.`\"\\[\\]]+)\\s*"
                    + "(?:(?:AS\\s+)?[\\w]+\\s*)?"
                    + "(?:WHERE\\b.*?)?"
                    + "(?:ORDER\\s+BY\\b.*?)?"
                    + "(?:LIMIT\\b.*?)?"
                    + ";?\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern DISALLOWED = Pattern.compile(
            "\\b(JOIN|GROUP\\s+BY|HAVING|UNION|INTERSECT|EXCEPT|DISTINCT|WITH)\\b",
            Pattern.CASE_INSENSITIVE);

    private SimpleSelectAnalyzer() {
    }

    public static Optional<SimpleSelect> tryAnalyze(String sql) {
        if (sql == null || sql.isBlank()) {
            return Optional.empty();
        }
        String trimmed = sql.strip();
        if (DISALLOWED.matcher(trimmed).find()) {
            return Optional.empty();
        }
        // Reject FROM (subquery) and comma joins quickly.
        String upper = trimmed.toUpperCase(Locale.ROOT);
        int from = indexOfKeyword(upper, "FROM");
        if (from < 0) {
            return Optional.empty();
        }
        String afterFrom = trimmed.substring(from + 4).strip();
        if (afterFrom.startsWith("(")) {
            return Optional.empty();
        }
        Matcher matcher = SIMPLE.matcher(trimmed);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String selectList = matcher.group(1);
        if (selectList.toUpperCase(Locale.ROOT).contains(" FROM ")) {
            return Optional.empty(); // nested FROM
        }
        if (Pattern.compile("\\b(COUNT|SUM|AVG|MIN|MAX)\\s*\\(", Pattern.CASE_INSENSITIVE)
                .matcher(selectList).find()) {
            return Optional.empty();
        }
        String rawName = stripQuotes(matcher.group(2).strip());
        if (rawName.contains(",")) {
            return Optional.empty();
        }
        String catalog = null;
        String table = rawName;
        int dot = rawName.lastIndexOf('.');
        if (dot > 0) {
            catalog = stripQuotes(rawName.substring(0, dot));
            table = stripQuotes(rawName.substring(dot + 1));
        }
        if (table.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new SimpleSelect(catalog, table, rawName));
    }

    private static int indexOfKeyword(String upperSql, String keyword) {
        Pattern pattern = Pattern.compile("\\b" + keyword + "\\b");
        Matcher matcher = pattern.matcher(upperSql);
        return matcher.find() ? matcher.start() : -1;
    }

    private static String stripQuotes(String name) {
        if (name == null || name.length() < 2) {
            return name == null ? "" : name;
        }
        char a = name.charAt(0);
        char b = name.charAt(name.length() - 1);
        if ((a == '`' && b == '`') || (a == '"' && b == '"') || (a == '[' && b == ']')) {
            return name.substring(1, name.length() - 1);
        }
        return name;
    }
}
