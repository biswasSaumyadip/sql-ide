package com.lazaro.sqlide.core.db;

import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * JDBC-facing description of one result-set column: label, SQL type, and key
 * roles when {@link java.sql.DatabaseMetaData} could resolve them.
 */
public record ResultColumn(
        String name,
        String typeName,
        int sqlType,
        boolean primaryKey,
        boolean foreignKey
) {

    public ResultColumn {
        name = Objects.requireNonNullElse(name, "");
        typeName = Objects.requireNonNullElse(typeName, "");
    }

    public static ResultColumn named(String name) {
        return new ResultColumn(name, "", Types.OTHER, false, false);
    }

    public static List<ResultColumn> fromNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<ResultColumn> columns = new ArrayList<>(names.size());
        for (String name : names) {
            columns.add(named(name));
        }
        return List.copyOf(columns);
    }

    public ResultColumn withKeys(boolean primaryKey, boolean foreignKey) {
        return new ResultColumn(name, typeName, sqlType, primaryKey, foreignKey);
    }

    /** Compact type family used by the result-grid header badge. */
    public Kind kind() {
        Kind fromSql = kindFromSqlType(sqlType);
        if (fromSql != Kind.OTHER) {
            return fromSql;
        }
        return kindFromTypeName(typeName);
    }

    /** Text badge for numeric / character / boolean / binary; empty when an icon is used. */
    public String typeBadge() {
        return switch (kind()) {
            case NUMERIC -> "123";
            case TEXT -> "Aa";
            case BOOLEAN -> "01";
            case BINARY -> "[]";
            case TEMPORAL, OTHER -> "";
        };
    }

    public String typeTooltip() {
        String type = typeName.isBlank() ? kind().label() : typeName;
        if (primaryKey && foreignKey) {
            return type + " \u00B7 primary key, foreign key";
        }
        if (primaryKey) {
            return type + " \u00B7 primary key";
        }
        if (foreignKey) {
            return type + " \u00B7 foreign key";
        }
        return type;
    }

    static Kind kindFromSqlType(int sqlType) {
        return switch (sqlType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> Kind.NUMERIC;
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.CLOB, Types.NCLOB,
                 Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR, Types.SQLXML -> Kind.TEXT;
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE,
                 Types.TIMESTAMP_WITH_TIMEZONE -> Kind.TEMPORAL;
            case Types.BOOLEAN, Types.BIT -> Kind.BOOLEAN;
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> Kind.BINARY;
            default -> Kind.OTHER;
        };
    }

    static Kind kindFromTypeName(String typeName) {
        if (typeName == null || typeName.isBlank()) {
            return Kind.OTHER;
        }
        String upper = typeName.toUpperCase(Locale.ROOT);
        if (containsAny(upper, "INT", "DECIMAL", "NUMERIC", "NUMBER", "FLOAT", "DOUBLE",
                "REAL", "SERIAL", "MONEY")) {
            return Kind.NUMERIC;
        }
        if (containsAny(upper, "CHAR", "TEXT", "CLOB", "JSON", "XML", "UUID", "ENUM", "SET")) {
            return Kind.TEXT;
        }
        if (containsAny(upper, "DATE", "TIME", "YEAR")) {
            return Kind.TEMPORAL;
        }
        if (containsAny(upper, "BOOL", "BIT")) {
            return Kind.BOOLEAN;
        }
        if (containsAny(upper, "BLOB", "BINARY", "BYTE", "RAW", "IMAGE")) {
            return Kind.BINARY;
        }
        return Kind.OTHER;
    }

    private static boolean containsAny(String upper, String... tokens) {
        for (String token : tokens) {
            if (upper.contains(token)) {
                return true;
            }
        }
        return false;
    }

    public enum Kind {
        NUMERIC("Number"),
        TEXT("Text"),
        TEMPORAL("Date / time"),
        BOOLEAN("Boolean"),
        BINARY("Binary"),
        OTHER("Unknown");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }
}
