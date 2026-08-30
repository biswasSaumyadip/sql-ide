package com.lazaro.sqlide.core.diff;

import com.lazaro.sqlide.core.db.SchemaNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiffServiceTest {

    @Test
    void detectsAddedAndTypeChangedColumns() {
        SchemaNode left = table("users",
                column("id", "INT", false, true),
                column("name", "VARCHAR(50)", true, false));
        SchemaNode right = table("users",
                column("id", "BIGINT", false, true),
                column("name", "VARCHAR(50)", true, false),
                column("email", "VARCHAR(100)", true, false));

        SchemaDiff diff = SchemaDiffService.diffTables(left, right);
        assertFalse(diff.isEmpty());
        assertTrue(diff.changes().stream().anyMatch(c -> c.kind() == SchemaDiff.Kind.COLUMN_ADDED));
        assertTrue(diff.changes().stream().anyMatch(c -> c.kind() == SchemaDiff.Kind.COLUMN_TYPE_CHANGED));

        String alter = AlterScriptGenerator.generate("users", diff);
        assertTrue(alter.contains("ADD COLUMN email"));
        assertTrue(alter.contains("MODIFY COLUMN id"));
    }

    @Test
    void identicalTablesProduceEmptyDiff() {
        SchemaNode left = table("t", column("id", "INT", false, true));
        SchemaNode right = table("t", column("id", "INT", false, true));
        assertTrue(SchemaDiffService.diffTables(left, right).isEmpty());
        assertEquals("-- No structural differences\n", AlterScriptGenerator.generate("t",
                SchemaDiffService.diffTables(left, right)));
    }

    private static SchemaNode table(String name, SchemaNode... columns) {
        SchemaNode folder = SchemaNode.folder("Columns", SchemaNode.FOLDER_COLUMNS, columns.length, Map.of());
        // folder with children — rebuild
        return new SchemaNode(name, SchemaNode.NodeType.TABLE,
                List.of(new SchemaNode(folder.name(), folder.type(), List.of(columns), folder.metadata())),
                Map.of(SchemaNode.META_CATALOG, "db"));
    }

    private static SchemaNode column(String name, String type, boolean nullable, boolean pk) {
        return SchemaNode.of(name, SchemaNode.NodeType.COLUMN, Map.of(
                SchemaNode.META_DATA_TYPE, type,
                SchemaNode.META_NULLABLE, Boolean.toString(nullable),
                SchemaNode.META_PRIMARY_KEY, Boolean.toString(pk)));
    }
}
