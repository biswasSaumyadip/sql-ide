package com.lazaro.sqlide.core.diagram;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaDiagramBuilderTest {

    @Test
    void buildsCatalogGraphWithForeignKeys() {
        SchemaCache cache = sampleCache();
        SchemaDiagramModel model = SchemaDiagramBuilder.buildCatalog(cache, "game");
        assertEquals(2, model.tables().size());
        assertEquals(1, model.edges().size());
        assertTrue(model.edges().getFirst().fromTableId().endsWith("characters"));
        assertTrue(model.edges().getFirst().toTableId().endsWith("factions"));
        assertTrue(model.edges().getFirst().fkColumn().equalsIgnoreCase("faction_id"));
    }

    @Test
    void neighborhoodIncludesOnlyRelatedTables() {
        SchemaCache cache = sampleCache();
        // add an unrelated table
        SchemaNode items = new SchemaNode("items", NodeType.TABLE, List.of(
                col("id", true)), Map.of(
                SchemaNode.META_CATALOG, "game"));
        SchemaNode factions = cache.findTable("factions", "game").orElseThrow();
        SchemaNode characters = cache.findTable("characters", "game").orElseThrow();
        cache.replace(List.of(new SchemaNode("game", NodeType.DATABASE,
                List.of(factions, characters, items), Map.of())));

        SchemaDiagramModel model = SchemaDiagramBuilder.buildNeighborhood(cache, "game", "characters");
        assertEquals(2, model.tables().size());
        assertTrue(model.tables().stream().anyMatch(t -> t.name().equals("characters")));
        assertTrue(model.tables().stream().anyMatch(t -> t.name().equals("factions")));
        assertFalse(model.tables().stream().anyMatch(t -> t.name().equals("items")));
    }

    @Test
    void layoutPlacesReferencedTablesAboveOwners() {
        SchemaCache cache = sampleCache();
        SchemaDiagramModel model = SchemaDiagramLayout.layout(
                SchemaDiagramBuilder.buildCatalog(cache, "game"));
        var factions = model.tables().stream().filter(t -> t.name().equals("factions")).findFirst().orElseThrow();
        var characters = model.tables().stream().filter(t -> t.name().equals("characters")).findFirst().orElseThrow();
        assertTrue(factions.y() < characters.y(), "PK table should sit above FK owner");
        assertTrue(factions.width() > 0 && factions.height() > 0);
    }

    private static SchemaCache sampleCache() {
        SchemaNode factionId = col("id", true);
        SchemaNode factionName = col("name", false);
        SchemaNode factions = new SchemaNode("factions", NodeType.TABLE,
                List.of(factionId, factionName),
                Map.of(SchemaNode.META_CATALOG, "game"));

        SchemaNode charId = col("id", true);
        SchemaNode charFaction = new SchemaNode("faction_id", NodeType.COLUMN, List.of(), Map.of(
                SchemaNode.META_DATA_TYPE, "INT",
                SchemaNode.META_PRIMARY_KEY, "false"));
        String fks = SchemaMetadataCodec.encodeForeignKeys(List.of(
                new SchemaMetadataCodec.ForeignKey("fk_char_faction", "faction_id", "factions", "id")));
        SchemaNode characters = new SchemaNode("characters", NodeType.TABLE,
                List.of(charId, charFaction),
                Map.of(SchemaNode.META_CATALOG, "game", SchemaNode.META_FOREIGN_KEYS, fks));

        SchemaCache cache = new SchemaCache();
        cache.replace(List.of(new SchemaNode("game", NodeType.DATABASE,
                List.of(factions, characters), Map.of())));
        return cache;
    }

    private static SchemaNode col(String name, boolean pk) {
        return new SchemaNode(name, NodeType.COLUMN, List.of(), Map.of(
                SchemaNode.META_DATA_TYPE, "INT",
                SchemaNode.META_PRIMARY_KEY, Boolean.toString(pk)));
    }
}
