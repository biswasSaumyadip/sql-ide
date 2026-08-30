package com.lazaro.sqlide.core.tabledesign;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TableDesignerModelTest {

    @Test
    void unchangedTableEmitsNoAlter() {
        TableDesignerModel model = TableDesignerModel.from(sampleTable());
        String sql = model.alterScript(ConnectionConfig.Driver.MYSQL);
        assertTrue(sql.contains("-- No changes"));
        assertFalse(sql.contains("ALTER TABLE"));
        assertFalse(model.dirty());
    }

    @Test
    void addColumnEmitsAddAfterPrevious() {
        TableDesignerModel model = TableDesignerModel.from(sampleTable());
        var col = model.addColumn();
        col.setName("email");
        col.setDataType("VARCHAR(120)");
        col.setNullable(true);
        String sql = model.alterScript(ConnectionConfig.Driver.MYSQL);
        assertTrue(sql.contains("ADD COLUMN `email` VARCHAR(120) NULL"));
        assertTrue(sql.contains("AFTER `name`"));
        assertTrue(model.dirty());
    }

    @Test
    void dropAndRenameAndModify() {
        TableDesignerModel model = TableDesignerModel.from(sampleTable());
        model.columns().get(1).setDropped(true);
        model.columns().get(0).setDataType("BIGINT");
        TableDesignerModel.ColumnDraft extra = model.addColumn();
        extra.setName("title");
        extra.setDataType("VARCHAR(80)");
        extra.setNullable(false);

        TableDesignerModel renamed = TableDesignerModel.from(sampleTable());
        renamed.columns().get(1).setName("full_name");

        String dropSql = model.alterScript(ConnectionConfig.Driver.MYSQL);
        assertTrue(dropSql.contains("DROP COLUMN `name`"));
        assertTrue(dropSql.contains("MODIFY COLUMN `id` BIGINT NOT NULL"));
        assertTrue(dropSql.contains("ADD COLUMN `title` VARCHAR(80) NOT NULL"));

        String renameSql = renamed.alterScript(ConnectionConfig.Driver.MYSQL);
        assertTrue(renameSql.contains("CHANGE COLUMN `name` `full_name` VARCHAR(80) NULL"));
    }

    @Test
    void primaryKeyChangeDropsAndAdds() {
        TableDesignerModel model = TableDesignerModel.from(sampleTable());
        model.columns().get(0).setPrimaryKey(false);
        model.columns().get(1).setPrimaryKey(true);
        String sql = model.alterScript(ConnectionConfig.Driver.MYSQL);
        assertTrue(sql.contains("DROP PRIMARY KEY"));
        assertTrue(sql.contains("ADD PRIMARY KEY (`full_name`)")
                || sql.contains("ADD PRIMARY KEY (`name`)"));
    }

    @Test
    void addAndDropIndexAndForeignKey() {
        TableDesignerModel model = TableDesignerModel.from(sampleTable());
        model.indexes().getFirst().setDropped(true);
        var idx = model.addIndex();
        idx.setName("idx_email");
        idx.setColumns("name");
        idx.setUnique(true);
        model.foreignKeys().getFirst().setDropped(true);
        var fk = model.addForeignKey();
        fk.setName("fk_guild");
        fk.setColumns("id");
        fk.setRefTable("guilds");
        fk.setRefColumns("id");

        String sql = model.alterScript(ConnectionConfig.Driver.MYSQL);
        assertTrue(sql.contains("DROP INDEX `idx_name`"));
        assertTrue(sql.contains("ADD UNIQUE INDEX `idx_email` (`name`)"));
        assertTrue(sql.contains("DROP FOREIGN KEY `fk_faction`"));
        assertTrue(sql.contains("ADD CONSTRAINT `fk_guild` FOREIGN KEY (`id`) REFERENCES `guilds` (`id`)"));
    }

    @Test
    void changingIndexOrFkDropsThenRecreates() {
        TableDesignerModel model = TableDesignerModel.from(sampleTable());
        model.indexes().getFirst().setUnique(true);
        model.foreignKeys().getFirst().setRefTable("guilds");

        String sql = model.alterScript(ConnectionConfig.Driver.MYSQL);
        assertTrue(sql.contains("DROP INDEX `idx_name`"));
        assertTrue(sql.contains("ADD UNIQUE INDEX `idx_name` (`name`)"));
        assertTrue(sql.contains("DROP FOREIGN KEY `fk_faction`"));
        assertTrue(sql.contains("REFERENCES `guilds` (`id`)"));
    }

    @Test
    void quotesReservedIdentifiers() {
        SchemaNode id = new SchemaNode("order", NodeType.COLUMN, List.of(), Map.of(
                SchemaNode.META_DATA_TYPE, "INT",
                SchemaNode.META_NULLABLE, "false",
                SchemaNode.META_PRIMARY_KEY, "true"));
        SchemaNode table = new SchemaNode("select", NodeType.TABLE, List.of(id), Map.of(
                SchemaNode.META_CATALOG, "db"));
        TableDesignerModel model = TableDesignerModel.from(table);
        model.columns().getFirst().setDataType("BIGINT");
        String sql = model.alterScript(ConnectionConfig.Driver.MYSQL);
        assertTrue(sql.contains("ALTER TABLE `db`.`select` MODIFY COLUMN `order` BIGINT NOT NULL"));
    }

    private static SchemaNode sampleTable() {
        SchemaNode id = new SchemaNode("id", NodeType.COLUMN, List.of(), Map.of(
                SchemaNode.META_DATA_TYPE, "INT",
                SchemaNode.META_NULLABLE, "false",
                SchemaNode.META_PRIMARY_KEY, "true"));
        SchemaNode name = new SchemaNode("name", NodeType.COLUMN, List.of(), Map.of(
                SchemaNode.META_DATA_TYPE, "VARCHAR(80)",
                SchemaNode.META_NULLABLE, "true",
                SchemaNode.META_PRIMARY_KEY, "false"));
        String indexes = SchemaMetadataCodec.encodeIndexes(List.of(
                new SchemaMetadataCodec.IndexInfo("idx_name", false, List.of("name"))));
        String fks = SchemaMetadataCodec.encodeForeignKeys(List.of(
                new SchemaMetadataCodec.ForeignKey("fk_faction", "faction_id", "factions", "id")));
        return new SchemaNode("characters", NodeType.TABLE, List.of(id, name), Map.of(
                SchemaNode.META_CATALOG, "game",
                SchemaNode.META_INDEXES, indexes,
                SchemaNode.META_FOREIGN_KEYS, fks));
    }
}
