package com.lazaro.sqlide.core.db;

import java.util.Locale;
import java.util.Objects;

/**
 * A single column of a table, as reported by {@link java.sql.DatabaseMetaData}.
 *
 * @param name          column name
 * @param typeName      vendor type name, e.g. {@code VARCHAR}
 * @param size          declared size or precision, {@code 0} when not applicable
 * @param decimalDigits scale for numeric types, {@code 0} otherwise
 * @param nullable      whether the column accepts {@code NULL}
 * @param position      1-based ordinal position within the table
 * @param primaryKey    whether the column participates in the primary key
 */
public record ColumnNode(
        String name,
        String typeName,
        int size,
        int decimalDigits,
        boolean nullable,
        int position,
        boolean primaryKey
) {

    public ColumnNode {
        name = Objects.requireNonNull(name, "name must not be null");
        typeName = Objects.requireNonNullElse(typeName, "UNKNOWN");
    }

    /** Type rendered the way a DDL statement would spell it, e.g. {@code VARCHAR(255)}. */
    public String displayType() {
        String upper = typeName.toUpperCase(Locale.ROOT);
        boolean scaled = decimalDigits > 0 && (upper.contains("DECIMAL") || upper.contains("NUMERIC"));
        if (scaled) {
            return "%s(%d,%d)".formatted(typeName, size, decimalDigits);
        }
        boolean sized = size > 0
                && (upper.contains("CHAR") || upper.contains("BINARY") || upper.contains("DECIMAL") || upper.contains("NUMERIC"));
        return sized ? "%s(%d)".formatted(typeName, size) : typeName;
    }

    /** Label for the schema tree, e.g. {@code id : INT [PK]}. */
    public String label() {
        return "%s : %s%s".formatted(name, displayType(), primaryKey ? "  [PK]" : "");
    }
}
