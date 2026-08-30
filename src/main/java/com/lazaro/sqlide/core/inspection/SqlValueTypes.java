package com.lazaro.sqlide.core.inspection;

import net.sf.jsqlparser.expression.DoubleValue;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.HexValue;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.DateValue;
import net.sf.jsqlparser.expression.TimeValue;
import net.sf.jsqlparser.expression.TimestampValue;

import java.util.Locale;
import java.util.Set;

/**
 * Coarse SQL type compatibility for literal ↔ column checks (IntelliJ-style).
 * Dialects coerce freely at runtime; we only flag clear mismatches.
 */
final class SqlValueTypes {

    enum Kind {
        STRING,
        NUMERIC,
        BOOLEAN,
        TEMPORAL,
        BINARY,
        NULL,
        OTHER
    }

    private static final Set<String> STRING = Set.of(
            "CHAR", "VARCHAR", "VARCHAR2", "NVARCHAR", "NCHAR", "TEXT", "TINYTEXT", "MEDIUMTEXT",
            "LONGTEXT", "CLOB", "NCLOB", "CHARACTER", "CHARACTER VARYING", "STRING", "BPCHAR",
            "NAME", "CIDR", "INET", "UUID", "ENUM", "SET");

    private static final Set<String> NUMERIC = Set.of(
            "INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT", "MEDIUMINT", "DECIMAL", "NUMERIC",
            "NUMBER", "FLOAT", "DOUBLE", "DOUBLE PRECISION", "REAL", "SERIAL", "BIGSERIAL",
            "SMALLSERIAL", "MONEY", "SMALLMONEY", "DEC", "FIXED", "INT2", "INT4", "INT8",
            "FLOAT4", "FLOAT8", "BINARY_FLOAT", "BINARY_DOUBLE");

    private static final Set<String> BOOLEAN = Set.of("BOOL", "BOOLEAN", "BIT");

    private static final Set<String> TEMPORAL = Set.of(
            "DATE", "TIME", "TIMESTAMP", "DATETIME", "DATETIME2", "SMALLDATETIME", "YEAR",
            "TIMESTAMPTZ", "TIMETZ", "INTERVAL");

    private static final Set<String> BINARY = Set.of(
            "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB", "BINARY", "VARBINARY", "BYTEA",
            "RAW", "LONG RAW", "IMAGE");

    private SqlValueTypes() {
    }

    static Kind columnKind(String sqlType) {
        if (sqlType == null || sqlType.isBlank()) {
            return Kind.OTHER;
        }
        String base = baseType(sqlType);
        if (STRING.contains(base) || base.startsWith("VARCHAR") || base.startsWith("CHAR")
                || base.endsWith("CHAR") || base.endsWith("TEXT") || base.contains("CHARACTER")) {
            return Kind.STRING;
        }
        if (NUMERIC.contains(base) || base.startsWith("INT") || base.startsWith("DECIMAL")
                || base.startsWith("NUMERIC") || base.startsWith("FLOAT") || base.startsWith("DOUBLE")) {
            return Kind.NUMERIC;
        }
        if (BOOLEAN.contains(base)) {
            return Kind.BOOLEAN;
        }
        if (TEMPORAL.contains(base) || base.startsWith("TIMESTAMP") || base.startsWith("TIME")
                || base.startsWith("DATE") || base.startsWith("INTERVAL")) {
            return Kind.TEMPORAL;
        }
        if (BINARY.contains(base) || base.contains("BLOB") || base.contains("BINARY")) {
            return Kind.BINARY;
        }
        if (base.equals("JSON") || base.equals("JSONB")) {
            return Kind.STRING;
        }
        return Kind.OTHER;
    }

    static Kind literalKind(Expression expression) {
        if (expression == null || expression instanceof NullValue) {
            return Kind.NULL;
        }
        if (expression instanceof StringValue) {
            return Kind.STRING;
        }
        if (expression instanceof LongValue || expression instanceof DoubleValue) {
            return Kind.NUMERIC;
        }
        if (expression instanceof DateValue || expression instanceof TimeValue
                || expression instanceof TimestampValue) {
            return Kind.TEMPORAL;
        }
        if (expression instanceof HexValue) {
            return Kind.BINARY;
        }
        String text = expression.toString().trim();
        if ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text)) {
            return Kind.BOOLEAN;
        }
        return Kind.OTHER;
    }

    /**
     * @return {@code null} when compatible / unknown; otherwise a short reason.
     */
    static String mismatchMessage(String columnName, String sqlType, Expression value) {
        Kind column = columnKind(sqlType);
        Kind literal = literalKind(value);
        if (column == Kind.OTHER || literal == Kind.OTHER || literal == Kind.NULL) {
            return null;
        }
        if (compatible(column, literal)) {
            return null;
        }
        return "Type mismatch: column '" + columnName + "' is " + displayType(sqlType)
                + ", got " + displayLiteral(literal);
    }

    private static boolean compatible(Kind column, Kind literal) {
        if (column == literal) {
            return true;
        }
        // MySQL-style 0/1 into BOOLEAN is common.
        if (column == Kind.BOOLEAN && literal == Kind.NUMERIC) {
            return true;
        }
        // Temporal literals are often written as strings: '2024-01-01'
        if (column == Kind.TEMPORAL && literal == Kind.STRING) {
            return true;
        }
        // Binary hex / string literals
        if (column == Kind.BINARY && (literal == Kind.STRING || literal == Kind.BINARY)) {
            return true;
        }
        return false;
    }

    static String baseType(String sqlType) {
        String trimmed = sqlType.trim().toUpperCase(Locale.ROOT);
        int paren = trimmed.indexOf('(');
        if (paren > 0) {
            trimmed = trimmed.substring(0, paren).trim();
        }
        // "UNSIGNED INT" / "DOUBLE PRECISION" already handled via set membership after normalize
        if (trimmed.startsWith("UNSIGNED ")) {
            trimmed = trimmed.substring("UNSIGNED ".length()).trim();
        }
        return trimmed;
    }

    private static String displayType(String sqlType) {
        String base = baseType(sqlType);
        return base.isBlank() ? "UNKNOWN" : base;
    }

    private static String displayLiteral(Kind kind) {
        return switch (kind) {
            case STRING -> "string";
            case NUMERIC -> "number";
            case BOOLEAN -> "boolean";
            case TEMPORAL -> "date/time";
            case BINARY -> "binary";
            case NULL -> "NULL";
            case OTHER -> "value";
        };
    }
}
