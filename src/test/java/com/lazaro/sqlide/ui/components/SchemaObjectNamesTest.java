package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import javafx.scene.control.TreeItem;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
