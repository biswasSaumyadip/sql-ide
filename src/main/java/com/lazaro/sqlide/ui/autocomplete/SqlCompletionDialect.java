package com.lazaro.sqlide.ui.autocomplete;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.doc.SqlKeywordDocs;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Dialect-specific SQL keywords and built-in functions for completion.
 */
final class SqlCompletionDialect {

    record Function(String name, String insertText, String detail, String documentation) {
    }

    private SqlCompletionDialect() {
    }

    static String[] keywords(ConnectionConfig.Driver driver) {
        List<String> out = new ArrayList<>(List.of(COMMON_KEYWORDS));
        ConnectionConfig.Driver d = driver == null ? ConnectionConfig.Driver.MYSQL : driver;
        switch (d) {
            case POSTGRESQL -> out.addAll(List.of(POSTGRES_KEYWORDS));
            case H2_MEMORY -> out.addAll(List.of(H2_KEYWORDS));
            case MYSQL, MARIADB -> out.addAll(List.of(MYSQL_KEYWORDS));
        }
        return out.toArray(String[]::new);
    }

    static String[] keywordPhrases(ConnectionConfig.Driver driver) {
        List<String> out = new ArrayList<>(List.of(COMMON_PHRASES));
        if (driver == ConnectionConfig.Driver.POSTGRESQL) {
            out.add("ON CONFLICT");
            out.add("DO NOTHING");
            out.add("DO UPDATE");
        }
        if (driver == ConnectionConfig.Driver.MYSQL || driver == ConnectionConfig.Driver.MARIADB) {
            out.add("ON DUPLICATE KEY UPDATE");
        }
        return out.toArray(String[]::new);
    }

    static List<Function> functions(ConnectionConfig.Driver driver) {
        List<Function> out = new ArrayList<>(COMMON_FUNCTIONS);
        ConnectionConfig.Driver d = driver == null ? ConnectionConfig.Driver.MYSQL : driver;
        switch (d) {
            case POSTGRESQL -> out.addAll(POSTGRES_FUNCTIONS);
            case H2_MEMORY -> out.addAll(H2_FUNCTIONS);
            case MYSQL, MARIADB -> out.addAll(MYSQL_FUNCTIONS);
        }
        return List.copyOf(out);
    }

    static String dialectLabel(ConnectionConfig.Driver driver) {
        if (driver == null) {
            return "SQL";
        }
        return driver.displayName();
    }

    private static final String[] COMMON_KEYWORDS = {
            "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE",
            "FROM", "WHERE", "JOIN", "INNER", "LEFT", "RIGHT", "FULL", "OUTER", "CROSS", "ON", "USING",
            "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "DISTINCT",
            "INTO", "VALUES", "SET", "AS", "UNION", "ALL", "WITH",
            "CREATE", "ALTER", "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "SCHEMA",
            "PROCEDURE", "FUNCTION", "TRIGGER", "CALL", "DELIMITER",
            "AND", "OR", "NOT", "IN", "EXISTS", "BETWEEN", "LIKE", "IS", "NULL",
            "CASE", "WHEN", "THEN", "ELSE", "END", "ASC", "DESC",
            "BEGIN", "START", "TRANSACTION", "COMMIT", "ROLLBACK",
            "PRIMARY", "FOREIGN", "KEY", "REFERENCES", "CONSTRAINT", "UNIQUE", "DEFAULT",
            "TRUE", "FALSE", "CAST", "COALESCE"
    };

    private static final String[] COMMON_PHRASES = {
            "START TRANSACTION",
            "BEGIN TRANSACTION"
    };

    private static final String[] MYSQL_KEYWORDS = {
            "REPLACE", "IGNORE", "DUPLICATE", "AUTO_INCREMENT", "ENGINE", "CHARSET",
            "USE", "SHOW", "DESCRIBE", "EXPLAIN", "ANALYZE", "FORCE", "STRAIGHT_JOIN",
            "REGEXP", "RLIKE", "XOR", "DIV", "MOD", "SEPARATOR", "LOCK", "UNLOCK", "TABLES"
    };

    private static final String[] POSTGRES_KEYWORDS = {
            "RETURNING", "ILIKE", "SIMILAR", "VACUUM", "ANALYZE", "EXPLAIN",
            "SERIAL", "BIGSERIAL", "CONFLICT", "LATERAL", "ONLY", "RECURSIVE",
            "WINDOW", "OVER", "PARTITION", "FILTER", "EXCLUDE", "MATERIALIZED"
    };

    private static final String[] H2_KEYWORDS = {
            "REPLACE", "MERGE", "TOP", "ROWNUM", "MEMORY", "CACHED", "EXPLAIN", "ANALYZE"
    };

    private static final List<Function> COMMON_FUNCTIONS = List.of(
            fn("COUNT", "COUNT($expr$)", "aggregate", "COUNT(expr) — number of non-null rows."),
            fn("COUNT", "COUNT(*)", "aggregate", "COUNT(*) — number of rows."),
            fn("SUM", "SUM($expr$)", "aggregate", "SUM(expr) — total of numeric values."),
            fn("AVG", "AVG($expr$)", "aggregate", "AVG(expr) — average of numeric values."),
            fn("MIN", "MIN($expr$)", "aggregate", "MIN(expr) — minimum value."),
            fn("MAX", "MAX($expr$)", "aggregate", "MAX(expr) — maximum value."),
            fn("COALESCE", "COALESCE($expr$, $fallback$)", "null-safe",
                    "COALESCE(a, b, …) — first non-null argument."),
            fn("NULLIF", "NULLIF($a$, $b$)", "null-safe",
                    "NULLIF(a, b) — NULL when a equals b, otherwise a."),
            fn("CAST", "CAST($expr$ AS $type$)", "conversion", "CAST(expr AS type) — convert a value."),
            fn("UPPER", "UPPER($expr$)", "string", "UPPER(expr) — uppercase string."),
            fn("LOWER", "LOWER($expr$)", "string", "LOWER(expr) — lowercase string."),
            fn("LENGTH", "LENGTH($expr$)", "string", "LENGTH(expr) — character length."),
            fn("TRIM", "TRIM($expr$)", "string", "TRIM(expr) — strip leading/trailing spaces."),
            fn("SUBSTRING", "SUBSTRING($expr$, $start$, $length$)", "string",
                    "SUBSTRING(expr, start, length) — extract a slice."),
            fn("CONCAT", "CONCAT($a$, $b$)", "string", "CONCAT(a, b, …) — concatenate strings."),
            fn("ABS", "ABS($expr$)", "numeric", "ABS(expr) — absolute value."),
            fn("ROUND", "ROUND($expr$, $scale$)", "numeric", "ROUND(expr, scale) — round to scale digits.")
    );

    private static final List<Function> MYSQL_FUNCTIONS = List.of(
            fn("IFNULL", "IFNULL($expr$, $fallback$)", "MySQL",
                    "IFNULL(expr, fallback) — MySQL null substitute."),
            fn("IF", "IF($cond$, $then$, $else$)", "MySQL", "IF(cond, then, else) — MySQL conditional."),
            fn("NOW", "NOW()", "MySQL", "NOW() — current date and time."),
            fn("CURDATE", "CURDATE()", "MySQL", "CURDATE() — current date."),
            fn("DATE_FORMAT", "DATE_FORMAT($date$, $format$)", "MySQL",
                    "DATE_FORMAT(date, format) — format a datetime."),
            fn("GROUP_CONCAT", "GROUP_CONCAT($expr$)", "MySQL",
                    "GROUP_CONCAT(expr) — concatenate group values."),
            fn("JSON_EXTRACT", "JSON_EXTRACT($doc$, $path$)", "MySQL",
                    "JSON_EXTRACT(doc, path) — read a JSON path.")
    );

    private static final List<Function> POSTGRES_FUNCTIONS = List.of(
            fn("NULLIF", "NULLIF($a$, $b$)", "Postgres", "NULLIF(a, b) — NULL when equal."),
            fn("NOW", "NOW()", "Postgres", "NOW() — current timestamp."),
            fn("CURRENT_DATE", "CURRENT_DATE", "Postgres", "CURRENT_DATE — session date."),
            fn("CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP", "Postgres", "CURRENT_TIMESTAMP — session timestamp."),
            fn("STRING_AGG", "STRING_AGG($expr$, $sep$)", "Postgres",
                    "STRING_AGG(expr, sep) — concatenate group values."),
            fn("ARRAY_AGG", "ARRAY_AGG($expr$)", "Postgres", "ARRAY_AGG(expr) — aggregate into an array."),
            fn("JSON_BUILD_OBJECT", "JSON_BUILD_OBJECT($key$, $value$)", "Postgres",
                    "JSON_BUILD_OBJECT(key, value, …) — build a JSON object."),
            fn("TO_CHAR", "TO_CHAR($value$, $format$)", "Postgres",
                    "TO_CHAR(value, format) — format a value as text."),
            fn("TO_DATE", "TO_DATE($text$, $format$)", "Postgres",
                    "TO_DATE(text, format) — parse a date.")
    );

    private static final List<Function> H2_FUNCTIONS = List.of(
            fn("IFNULL", "IFNULL($expr$, $fallback$)", "H2", "IFNULL(expr, fallback) — null substitute."),
            fn("NOW", "NOW()", "H2", "NOW() — current timestamp."),
            fn("CURRENT_DATE", "CURRENT_DATE", "H2", "CURRENT_DATE — session date."),
            fn("CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP", "H2", "CURRENT_TIMESTAMP — session timestamp.")
    );

    private static Function fn(String name, String insert, String detail, String doc) {
        return new Function(name, insert, detail, doc);
    }

    static boolean isSpaceAfterKeyword(String keyword) {
        return SPACE_AFTER.contains(keyword.toUpperCase(Locale.ROOT));
    }

    static String keywordDocumentation(String keyword, String dialectLabel) {
        return SqlKeywordDocs.describe(keyword)
                .orElse((keyword == null ? "" : keyword) + " — "
                        + (dialectLabel == null ? "SQL" : dialectLabel) + " keyword.");
    }

    private static final java.util.Set<String> SPACE_AFTER = java.util.Set.of(
            "SELECT", "INSERT", "UPDATE", "DELETE", "FROM", "WHERE", "JOIN", "INNER", "LEFT",
            "RIGHT", "FULL", "OUTER", "CROSS", "ON", "GROUP", "ORDER", "BY", "HAVING",
            "LIMIT", "OFFSET", "INTO", "VALUES", "SET", "AS", "UNION", "WITH", "CREATE",
            "ALTER", "DROP", "TRUNCATE", "TABLE", "SCHEMA", "AND", "OR", "NOT", "IN", "EXISTS",
            "BETWEEN", "LIKE", "ILIKE", "IS", "CASE", "WHEN", "THEN", "ELSE",
            "BEGIN", "START", "TRANSACTION", "USE", "SHOW", "RETURNING", "PARTITION", "OVER",
            "CALL", "PROCEDURE", "FUNCTION", "TRIGGER");
}
