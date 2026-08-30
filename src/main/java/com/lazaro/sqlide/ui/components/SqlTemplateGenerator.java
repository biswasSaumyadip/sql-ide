package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import javafx.scene.control.TreeItem;

/**
 * Boilerplate SQL for Database-pane "New …" / "Modify Table…" actions.
 */
public final class SqlTemplateGenerator {

    public static final String PLACEHOLDER_SCHEMA = "new_schema_name";
    public static final String PLACEHOLDER_TABLE = "new_table_name";
    public static final String PLACEHOLDER_VIEW = "new_view_name";

    private SqlTemplateGenerator() {
    }

    /** Result ready to open in a new query tab. */
    public record Template(String sql, String placeholder, String tabTitle) {
        public Template {
            sql = sql == null ? "" : sql;
            placeholder = placeholder == null ? "" : placeholder;
            tabTitle = tabTitle == null || tabTitle.isBlank() ? "query-new.sql" : tabTitle;
        }
    }

    public static Template newSchema() {
        String sql = """
                CREATE SCHEMA %s
                DEFAULT CHARACTER SET utf8mb4
                COLLATE utf8mb4_unicode_ci;
                """.formatted(PLACEHOLDER_SCHEMA).strip();
        return new Template(sql, PLACEHOLDER_SCHEMA, "query-new-schema.sql");
    }

    public static Template newTable(String schema) {
        String catalog = safeSchema(schema);
        String sql = """
                CREATE TABLE %s.%s (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(255) NOT NULL,
                    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                );
                """.formatted(catalog, PLACEHOLDER_TABLE).strip();
        return new Template(sql, PLACEHOLDER_TABLE, "query-new-table.sql");
    }

    public static Template newView(String schema) {
        String catalog = safeSchema(schema);
        String sql = """
                CREATE OR REPLACE VIEW %s.%s AS
                SELECT column1, column2
                FROM %s.target_table
                WHERE condition = true;
                """.formatted(catalog, PLACEHOLDER_VIEW, catalog).strip();
        return new Template(sql, PLACEHOLDER_VIEW, "query-new-view.sql");
    }

    public static Template modifyTable(String schema, String table) {
        String catalog = safeSchema(schema);
        String tableName = table == null || table.isBlank() ? "table_name" : table.strip();
        String qualified = catalog + "." + tableName;
        String sql = """
                -- Altering table: %s

                -- Add a new column:
                -- ALTER TABLE %s ADD COLUMN new_column INT NOT NULL DEFAULT 0;

                -- Modify an existing column:
                -- ALTER TABLE %s MODIFY COLUMN existing_column VARCHAR(100);

                -- Drop a column:
                -- ALTER TABLE %s DROP COLUMN old_column;

                -- Add an index:
                -- CREATE INDEX idx_new_col ON %s (new_column);
                """.formatted(qualified, qualified, qualified, qualified, qualified).strip();
        return new Template(sql, qualified, "query-modify-table.sql");
    }

    /** Schema / database name for the selected tree item. */
    public static String schemaOf(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return "schema_name";
        }
        SchemaNode node = item.getValue();
        if (node.type() == NodeType.DATABASE || node.type() == NodeType.SCHEMA) {
            return node.name();
        }
        String catalog = node.metadata(SchemaNode.META_CATALOG);
        if (catalog != null && !catalog.isBlank()) {
            return catalog;
        }
        for (TreeItem<SchemaNode> cursor = item.getParent(); cursor != null; cursor = cursor.getParent()) {
            SchemaNode parent = cursor.getValue();
            if (parent == null) {
                continue;
            }
            if (parent.type() == NodeType.DATABASE || parent.type() == NodeType.SCHEMA) {
                return parent.name();
            }
            catalog = parent.metadata(SchemaNode.META_CATALOG);
            if (catalog != null && !catalog.isBlank()) {
                return catalog;
            }
        }
        return "schema_name";
    }

    public static String tableOf(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return "table_name";
        }
        SchemaNode node = item.getValue();
        if (node.type() == NodeType.TABLE || node.type() == NodeType.VIEW) {
            return node.name();
        }
        return "table_name";
    }

    private static String safeSchema(String schema) {
        return schema == null || schema.isBlank() ? "schema_name" : schema.strip();
    }
}
