package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import javafx.scene.control.TreeItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Helpers for Database-pane actions: qualified names and generated SQL. */
public final class SchemaObjectNames {

    private SchemaObjectNames() {
    }

    /** Dot-qualified path excluding data-source roots and placeholders. */
    public static String qualifiedName(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (TreeItem<SchemaNode> cursor = item; cursor != null; cursor = cursor.getParent()) {
            if (cursor.getParent() == null) {
                continue;
            }
            SchemaNode node = cursor.getValue();
            if (node == null || node.type() == NodeType.DATA_SOURCE) {
                continue;
            }
            if (node.metadataFlag("__placeholder")) {
                continue;
            }
            parts.add(0, node.name());
        }
        return String.join(".", parts);
    }

    /**
     * {@code SELECT * FROM catalog.table;} for tables/views, or
     * {@code SELECT col FROM catalog.table;} for columns. {@code null} when N/A.
     */
    public static String generateSelect(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return null;
        }
        SchemaNode node = item.getValue();
        return switch (node.type()) {
            case TABLE, VIEW -> "SELECT * FROM " + qualifiedName(item) + ";";
            case COLUMN -> {
                TreeItem<SchemaNode> parent = item.getParent();
                if (parent == null || parent.getValue() == null) {
                    yield "SELECT " + node.name() + ";";
                }
                yield "SELECT " + node.name() + " FROM " + qualifiedName(parent) + ";";
            }
            default -> null;
        };
    }

    public static String selectFirstRows(TreeItem<SchemaNode> item, int limit) {
        if (item == null || item.getValue() == null) {
            return null;
        }
        SchemaNode node = item.getValue();
        if (node.type() != NodeType.TABLE && node.type() != NodeType.VIEW) {
            return null;
        }
        int safeLimit = Math.max(1, limit);
        return "SELECT * FROM " + qualifiedName(item) + " LIMIT " + safeLimit + ";";
    }

    public static String generateInsert(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return null;
        }
        SchemaNode node = item.getValue();
        if (node.type() != NodeType.TABLE && node.type() != NodeType.VIEW) {
            return null;
        }
        List<String> columns = columnNames(item);
        String table = qualifiedName(item);
        if (columns.isEmpty()) {
            return "INSERT INTO " + table + " VALUES ();";
        }
        String cols = String.join(", ", columns);
        String placeholders = String.join(", ", columns.stream().map(ignored -> "?").toList());
        return "INSERT INTO " + table + " (" + cols + ") VALUES (" + placeholders + ");";
    }

    public static String generateDdl(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return null;
        }
        SchemaNode node = item.getValue();
        String ddl = node.metadata(SchemaNode.META_DDL);
        if (ddl != null && !ddl.isBlank()) {
            return ddl.endsWith(";") ? ddl : ddl + ";";
        }
        String name = qualifiedName(item);
        return switch (node.type()) {
            case TABLE -> "CREATE TABLE " + name + " (\n    -- columns\n);";
            case VIEW -> "CREATE VIEW " + name + " AS\nSELECT * FROM ...;";
            case DATABASE, SCHEMA -> "CREATE DATABASE " + node.name() + ";";
            default -> null;
        };
    }

    public static String dropStatement(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return null;
        }
        SchemaNode node = item.getValue();
        return switch (node.type()) {
            case TABLE -> "DROP TABLE IF EXISTS " + qualifiedName(item) + ";";
            case VIEW -> "DROP VIEW IF EXISTS " + qualifiedName(item) + ";";
            case DATABASE, SCHEMA -> "DROP DATABASE IF EXISTS " + node.name() + ";";
            default -> null;
        };
    }

    public static String truncateStatement(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return null;
        }
        if (item.getValue().type() != NodeType.TABLE) {
            return null;
        }
        return "TRUNCATE TABLE " + qualifiedName(item) + ";";
    }

    public static String createTableTemplate(TreeItem<SchemaNode> schemaItem) {
        String schema = schemaItem == null || schemaItem.getValue() == null
                ? "new_table"
                : schemaItem.getValue().name() + ".new_table";
        return """
                CREATE TABLE %s (
                    id INT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL
                );
                """.formatted(schema).strip();
    }

    public static String createViewTemplate(TreeItem<SchemaNode> schemaItem) {
        String schema = schemaItem == null || schemaItem.getValue() == null
                ? ""
                : schemaItem.getValue().name() + ".";
        return "CREATE VIEW " + schema + "new_view AS\nSELECT * FROM ...;";
    }

    public static String createSchemaTemplate() {
        return "CREATE DATABASE new_schema;";
    }

    public static String createColumnTemplate(TreeItem<SchemaNode> tableItem) {
        String table = tableItem == null ? "table_name" : qualifiedName(tableItem);
        return "ALTER TABLE " + table + " ADD COLUMN new_column VARCHAR(255) NULL;";
    }

    public static String createIndexTemplate(TreeItem<SchemaNode> tableItem) {
        String table = tableItem == null ? "table_name" : qualifiedName(tableItem);
        String indexName = tableItem == null || tableItem.getValue() == null
                ? "idx_name"
                : "idx_" + tableItem.getValue().name().toLowerCase(Locale.ROOT);
        return "CREATE INDEX " + indexName + " ON " + table + " (/* columns */);";
    }

    public static String createForeignKeyTemplate(TreeItem<SchemaNode> tableItem) {
        String table = tableItem == null ? "table_name" : qualifiedName(tableItem);
        return "ALTER TABLE " + table
                + " ADD CONSTRAINT fk_name FOREIGN KEY (/* column */) REFERENCES other_table (id);";
    }

    public static String modifyTableTemplate(TreeItem<SchemaNode> tableItem) {
        String table = tableItem == null ? "table_name" : qualifiedName(tableItem);
        return "ALTER TABLE " + table + "\n    -- ADD / DROP / MODIFY COLUMN\n;";
    }

    private static List<String> columnNames(TreeItem<SchemaNode> tableItem) {
        List<String> columns = new ArrayList<>();
        if (tableItem == null) {
            return columns;
        }
        SchemaNode node = tableItem.getValue();
        if (node != null) {
            for (SchemaNode child : node.children()) {
                if (child.type() == NodeType.COLUMN) {
                    columns.add(child.name());
                }
            }
        }
        if (!columns.isEmpty()) {
            return columns;
        }
        for (TreeItem<SchemaNode> child : tableItem.getChildren()) {
            SchemaNode value = child.getValue();
            if (value != null && value.type() == NodeType.COLUMN
                    && !value.metadataFlag("__placeholder")) {
                columns.add(value.name());
            }
        }
        return columns;
    }
}
