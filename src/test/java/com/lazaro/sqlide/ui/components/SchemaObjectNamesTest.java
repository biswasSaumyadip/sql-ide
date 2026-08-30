package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaObjectNamesTest {

    @Test
    void buildsQualifiedPathSkippingDataSource() {
        TreeItem<SchemaNode> ds = new TreeItem<>(SchemaNode.of("Local", NodeType.DATA_SOURCE));
        TreeItem<SchemaNode> catalog = new TreeItem<>(SchemaNode.of("app", NodeType.DATABASE));
        TreeItem<SchemaNode> table = new TreeItem<>(
                SchemaNode.of("users", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app")));
        TreeItem<SchemaNode> column = new TreeItem<>(SchemaNode.of("email", NodeType.COLUMN));
        ds.getChildren().add(catalog);
        catalog.getChildren().add(table);
        table.getChildren().add(column);

        assertEquals("app.users.email", SchemaObjectNames.qualifiedName(column));
        assertEquals("app.users", SchemaObjectNames.qualifiedName(table));
    }

    @Test
    void qualifiedNameSkipsLogicalFolders() {
        TreeItem<SchemaNode> ds = new TreeItem<>(SchemaNode.of("Local", NodeType.DATA_SOURCE));
        TreeItem<SchemaNode> catalog = new TreeItem<>(SchemaNode.of("app", NodeType.DATABASE));
        TreeItem<SchemaNode> tables = new TreeItem<>(
                SchemaNode.folder("tables", SchemaNode.FOLDER_TABLES, 1, Map.of()));
        TreeItem<SchemaNode> table = new TreeItem<>(SchemaNode.of("users", NodeType.TABLE));
        TreeItem<SchemaNode> columns = new TreeItem<>(
                SchemaNode.folder("columns", SchemaNode.FOLDER_COLUMNS, 1, Map.of()));
        TreeItem<SchemaNode> column = new TreeItem<>(SchemaNode.of("email", NodeType.COLUMN));
        ds.getChildren().add(catalog);
        catalog.getChildren().add(tables);
        tables.getChildren().add(table);
        table.getChildren().add(columns);
        columns.getChildren().add(column);

        assertEquals("app.users.email", SchemaObjectNames.qualifiedName(column));
        assertEquals("SELECT email FROM app.users;", SchemaObjectNames.generateSelect(column));
    }

    @Test
    void generateSelectForTableAndColumn() {
        TreeItem<SchemaNode> ds = new TreeItem<>(SchemaNode.of("Local", NodeType.DATA_SOURCE));
        TreeItem<SchemaNode> catalog = new TreeItem<>(SchemaNode.of("app", NodeType.DATABASE));
        TreeItem<SchemaNode> table = new TreeItem<>(SchemaNode.of("users", NodeType.TABLE));
        TreeItem<SchemaNode> column = new TreeItem<>(SchemaNode.of("id", NodeType.COLUMN));
        ds.getChildren().add(catalog);
        catalog.getChildren().add(table);
        table.getChildren().add(column);

        assertEquals("SELECT * FROM app.users;", SchemaObjectNames.generateSelect(table));
        assertEquals("SELECT id FROM app.users;", SchemaObjectNames.generateSelect(column));
        assertNull(SchemaObjectNames.generateSelect(catalog));
    }

    @Test
    void selectFirstRowsAndInsertAndDdl() {
        TreeItem<SchemaNode> ds = new TreeItem<>(SchemaNode.of("Local", NodeType.DATA_SOURCE));
        TreeItem<SchemaNode> catalog = new TreeItem<>(SchemaNode.of("app", NodeType.DATABASE));
        TreeItem<SchemaNode> table = new TreeItem<>(SchemaNode.of("users", NodeType.TABLE));
        TreeItem<SchemaNode> id = new TreeItem<>(SchemaNode.of("id", NodeType.COLUMN));
        TreeItem<SchemaNode> email = new TreeItem<>(SchemaNode.of("email", NodeType.COLUMN));
        ds.getChildren().add(catalog);
        catalog.getChildren().add(table);
        table.getChildren().add(id);
        table.getChildren().add(email);

        assertEquals("SELECT * FROM app.users LIMIT 1000;", SchemaObjectNames.selectFirstRows(table, 1000));
        assertEquals(
                "INSERT INTO app.users (id, email) VALUES (?, ?);",
                SchemaObjectNames.generateInsert(table));
        assertEquals("CREATE TABLE app.users (\n    -- columns\n);", SchemaObjectNames.generateDdl(table));
        assertEquals("TRUNCATE TABLE app.users;", SchemaObjectNames.truncateStatement(table));
        assertEquals("DROP TABLE IF EXISTS app.users;", SchemaObjectNames.dropStatement(table));
        assertEquals("DROP DATABASE IF EXISTS app;", SchemaObjectNames.dropStatement(catalog));
    }

    @Test
    void createTemplates() {
        TreeItem<SchemaNode> catalog = new TreeItem<>(SchemaNode.of("app", NodeType.DATABASE));
        TreeItem<SchemaNode> table = new TreeItem<>(SchemaNode.of("users", NodeType.TABLE));
        catalog.getChildren().add(table);

        assertTrue(SchemaObjectNames.createSchemaTemplate().contains("CREATE SCHEMA"));
        assertTrue(SchemaObjectNames.createTableTemplate(catalog).contains("app.new_table_name"));
        assertTrue(SchemaObjectNames.createColumnTemplate(table).contains("ALTER TABLE users"));
        assertTrue(SchemaObjectNames.modifyTableTemplate(table).contains("Altering table:"));
    }
}
