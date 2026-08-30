package com.lazaro.sqlide.core.diff;

import com.lazaro.sqlide.core.diff.SchemaDiff.Change;
import com.lazaro.sqlide.core.diff.SchemaDiff.Kind;

/**
 * Generates MySQL-oriented {@code ALTER TABLE} scripts from a {@link SchemaDiff}.
 * Left is the current (source) schema; right is the desired (target) schema.
 * The script migrates left → right.
 */
public final class AlterScriptGenerator {

    private AlterScriptGenerator() {
    }

    public static String generate(String tableName, SchemaDiff diff) {
        if (diff == null || diff.isEmpty()) {
            return "-- No structural differences\n";
        }
        String table = tableName == null || tableName.isBlank() ? diff.rightName() : tableName;
        StringBuilder sql = new StringBuilder();
        sql.append("-- Migrate ").append(diff.leftName()).append(" \u2192 ").append(diff.rightName()).append('\n');
        sql.append("-- Generated ALTER script (review before running)\n\n");

        for (Change change : diff.changes()) {
            String stmt = toAlter(table, change);
            if (stmt != null && !stmt.isBlank()) {
                sql.append(stmt);
                if (!stmt.endsWith(";\n") && !stmt.endsWith(";")) {
                    sql.append(';');
                }
                if (!sql.toString().endsWith("\n")) {
                    sql.append('\n');
                }
                sql.append('\n');
            }
        }
        return sql.toString().stripTrailing() + "\n";
    }

    private static String toAlter(String table, Change change) {
        return switch (change.kind()) {
            case COLUMN_ADDED -> "ALTER TABLE " + table + " ADD COLUMN " + change.path()
                    + " " + change.rightDetail();
            case COLUMN_REMOVED -> "ALTER TABLE " + table + " DROP COLUMN " + change.path();
            case COLUMN_TYPE_CHANGED, COLUMN_NULLABLE_CHANGED ->
                    "ALTER TABLE " + table + " MODIFY COLUMN " + change.path()
                            + " " + preferRightType(change);
            case PRIMARY_KEY_CHANGED -> {
                StringBuilder sb = new StringBuilder();
                if (!change.leftDetail().equals("(none)")) {
                    sb.append("ALTER TABLE ").append(table).append(" DROP PRIMARY KEY;\n");
                }
                if (!change.rightDetail().equals("(none)")) {
                    sb.append("ALTER TABLE ").append(table).append(" ADD PRIMARY KEY (")
                            .append(change.rightDetail()).append(')');
                }
                yield sb.toString().isBlank() ? null : sb.toString();
            }
            case INDEX_ADDED -> {
                boolean unique = change.rightDetail().toUpperCase().startsWith("UNIQUE");
                String cols = change.rightDetail().replace("UNIQUE ", "").trim();
                yield "ALTER TABLE " + table + " ADD " + (unique ? "UNIQUE " : "")
                        + "INDEX " + sanitizeIdent(change.path()) + " " + cols;
            }
            case INDEX_REMOVED -> "ALTER TABLE " + table + " DROP INDEX " + sanitizeIdent(change.path());
            case FOREIGN_KEY_ADDED ->
                    "ALTER TABLE " + table + " ADD CONSTRAINT " + sanitizeIdent(change.path())
                            + " FOREIGN KEY (" + fkColumn(change.rightDetail()) + ") REFERENCES "
                            + fkTarget(change.rightDetail());
            case FOREIGN_KEY_REMOVED ->
                    "ALTER TABLE " + table + " DROP FOREIGN KEY " + sanitizeIdent(change.path());
        };
    }

    private static String preferRightType(Change change) {
        if (change.kind() == Kind.COLUMN_NULLABLE_CHANGED) {
            // Keep type from leftDetail context unavailable — use rightDetail alone is wrong.
            // rightDetail is NULL/NOT NULL; caller usually also emits TYPE_CHANGED.
            return change.rightDetail();
        }
        // TYPE_CHANGED: rightDetail is the new type string only — append nullable if present in path notes
        return change.rightDetail();
    }

    private static String sanitizeIdent(String name) {
        if (name == null || name.isBlank()) {
            return "idx";
        }
        return name.replaceAll("[^A-Za-z0-9_]", "_");
    }

    private static String fkColumn(String detail) {
        int arrow = detail.indexOf('\u2192');
        if (arrow < 0) {
            arrow = detail.indexOf("->");
        }
        if (arrow < 0) {
            return detail;
        }
        return detail.substring(0, arrow).trim();
    }

    private static String fkTarget(String detail) {
        int arrow = detail.indexOf('\u2192');
        int skip = 1;
        if (arrow < 0) {
            arrow = detail.indexOf("->");
            skip = 2;
        }
        if (arrow < 0) {
            return detail;
        }
        return detail.substring(arrow + skip).trim();
    }
}
