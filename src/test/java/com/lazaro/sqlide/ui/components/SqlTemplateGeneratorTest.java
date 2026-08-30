package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTemplateGeneratorTest {

    @Test
    void newSchemaTemplateHighlightsPlaceholder() {
        SqlTemplateGenerator.Template template = SqlTemplateGenerator.newSchema();
        assertTrue(template.sql().contains("CREATE SCHEMA new_schema_name"));
        assertTrue(template.sql().contains("utf8mb4_unicode_ci"));
        assertEquals(SqlTemplateGenerator.PLACEHOLDER_SCHEMA, template.placeholder());
    }

    @Test
    void newTableInjectsSchema() {
        SqlTemplateGenerator.Template template = SqlTemplateGenerator.newTable("warcraft");
        assertTrue(template.sql().contains("CREATE TABLE warcraft.new_table_name"));
        assertTrue(template.sql().contains("AUTO_INCREMENT"));
        assertEquals(SqlTemplateGenerator.PLACEHOLDER_TABLE, template.placeholder());
    }

    @Test
    void newViewInjectsSchema() {
        SqlTemplateGenerator.Template template = SqlTemplateGenerator.newView("warcraft");
        assertTrue(template.sql().contains("CREATE OR REPLACE VIEW warcraft.new_view_name AS"));
        assertTrue(template.sql().contains("FROM warcraft.target_table"));
        assertEquals(SqlTemplateGenerator.PLACEHOLDER_VIEW, template.placeholder());
    }

    @Test
    void modifyTableInjectsQualifiedName() {
        SqlTemplateGenerator.Template template = SqlTemplateGenerator.modifyTable("warcraft", "race");
        assertTrue(template.sql().contains("-- Altering table: warcraft.race"));
        assertTrue(template.sql().contains("ALTER TABLE warcraft.race ADD COLUMN"));
        assertTrue(template.sql().contains("CREATE INDEX idx_new_col ON warcraft.race"));
        assertEquals("warcraft.race", template.placeholder());
    }

    @Test
    void schemaOfWalksParents() {
        TreeItem<SchemaNode> catalog = new TreeItem<>(SchemaNode.of("warcraft", NodeType.DATABASE));
        TreeItem<SchemaNode> table = new TreeItem<>(SchemaNode.of("race", NodeType.TABLE,
                Map.of(SchemaNode.META_CATALOG, "warcraft")));
        catalog.getChildren().add(table);

        assertEquals("warcraft", SqlTemplateGenerator.schemaOf(catalog));
        assertEquals("warcraft", SqlTemplateGenerator.schemaOf(table));
        assertEquals("race", SqlTemplateGenerator.tableOf(table));
    }
}
