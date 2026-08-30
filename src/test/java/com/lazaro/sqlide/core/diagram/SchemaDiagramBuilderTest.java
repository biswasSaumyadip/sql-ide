package com.lazaro.sqlide.core.diagram;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Cardinality;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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
        assertEquals(Cardinality.ZERO_OR_MANY, model.edges().getFirst().fromCardinality());
        assertEquals(Cardinality.ONE, model.edges().getFirst().toCardinality());
        assertFalse(model.truncated());
    }

    @Test
    void groupsCompositeForeignKeyIntoOneEdge() {
        SchemaNode users = new SchemaNode("users", NodeType.TABLE, List.of(
                col("tenant_id", true, false),
                col("id", true, false)), Map.of(SchemaNode.META_CATALOG, "game"));
        String fks = SchemaMetadataCodec.encodeForeignKeys(List.of(
                new SchemaMetadataCodec.ForeignKey("fk_member", "tenant_id", "users", "tenant_id"),
                new SchemaMetadataCodec.ForeignKey("fk_member", "user_id", "users", "id")));
        SchemaNode members = new SchemaNode("members", NodeType.TABLE, List.of(
                col("id", true, false),
                col("tenant_id", false, false),
                col("user_id", false, false)), Map.of(
                SchemaNode.META_CATALOG, "game",
                SchemaNode.META_FOREIGN_KEYS, fks));

        SchemaCache cache = new SchemaCache();
        cache.replace(List.of(new SchemaNode("game", NodeType.DATABASE, List.of(users, members), Map.of())));

        SchemaDiagramModel model = SchemaDiagramBuilder.buildCatalog(cache, "game");
        assertEquals(1, model.edges().size());
        var edge = model.edges().getFirst();
        assertTrue(edge.composite());
        assertEquals(2, edge.columns().size());
        assertEquals("fk_member", edge.name());
        assertEquals(Cardinality.ONE_OR_MANY, edge.fromCardinality());
    }

    @Test
    void uniqueNotNullForeignKeyIsOneToOne() {
        SchemaNode factions = new SchemaNode("factions", NodeType.TABLE, List.of(
                col("id", true, false)), Map.of(SchemaNode.META_CATALOG, "game"));
        String fks = SchemaMetadataCodec.encodeForeignKeys(List.of(
                new SchemaMetadataCodec.ForeignKey("fk_leader", "faction_id", "factions", "id")));
        String indexes = SchemaMetadataCodec.encodeIndexes(List.of(
                new SchemaMetadataCodec.IndexInfo("uk_leader", true, List.of("faction_id"))));
        SchemaNode leaders = new SchemaNode("leaders", NodeType.TABLE, List.of(
                col("id", true, false),
                col("faction_id", false, false)), Map.of(
                SchemaNode.META_CATALOG, "game",
                SchemaNode.META_FOREIGN_KEYS, fks,
                SchemaNode.META_INDEXES, indexes));

        SchemaCache cache = new SchemaCache();
        cache.replace(List.of(new SchemaNode("game", NodeType.DATABASE, List.of(factions, leaders), Map.of())));

        SchemaDiagramModel model = SchemaDiagramBuilder.buildCatalog(cache, "game");
        assertEquals(Cardinality.ONE, model.edges().getFirst().fromCardinality());
    }

    @Test
    void neighborhoodIncludesOnlyRelatedTables() {
        SchemaCache cache = sampleCache();
        SchemaNode items = new SchemaNode("items", NodeType.TABLE, List.of(
                col("id", true, false)), Map.of(
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
    void capsCatalogAndPrefersRelatedTables() {
        List<SchemaNode> tables = new ArrayList<>();
        SchemaCache sample = sampleCache();
        tables.add(sample.findTable("factions", "game").orElseThrow());
        tables.add(sample.findTable("characters", "game").orElseThrow());
        for (int i = 0; i < SchemaDiagramBuilder.MAX_TABLES; i++) {
            tables.add(new SchemaNode("extra_" + i, NodeType.TABLE, List.of(
                    col("id", true, false)), Map.of(SchemaNode.META_CATALOG, "game")));
        }
        SchemaCache cache = new SchemaCache();
        cache.replace(List.of(new SchemaNode("game", NodeType.DATABASE, tables, Map.of())));

        SchemaDiagramModel model = SchemaDiagramBuilder.buildCatalog(cache, "game");
        assertEquals(SchemaDiagramBuilder.MAX_TABLES, model.tables().size());
        assertEquals(tables.size(), model.availableTableCount());
        assertTrue(model.truncated());
        assertTrue(model.tables().stream().anyMatch(t -> t.name().equals("characters")));
        assertTrue(model.tables().stream().anyMatch(t -> t.name().equals("factions")));
        assertEquals(1, model.edges().size());
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

    @Test
    void applyPositionsOverlaysSavedCoordinates() {
        SchemaCache cache = sampleCache();
        SchemaDiagramModel laidOut = SchemaDiagramLayout.layout(
                SchemaDiagramBuilder.buildCatalog(cache, "game"));
        var characters = laidOut.tables().stream()
                .filter(t -> t.name().equals("characters")).findFirst().orElseThrow();
        SchemaDiagramModel moved = SchemaDiagramLayout.applyPositions(laidOut, Map.of(
                characters.id(), new double[] {420, 880}));
        var after = moved.tables().stream()
                .filter(t -> t.name().equals("characters")).findFirst().orElseThrow();
        assertEquals(420, after.x(), 0.01);
        assertEquals(880, after.y(), 0.01);
        var factions = moved.tables().stream()
                .filter(t -> t.name().equals("factions")).findFirst().orElseThrow();
        var originalFactions = laidOut.tables().stream()
                .filter(t -> t.name().equals("factions")).findFirst().orElseThrow();
        assertEquals(originalFactions.x(), factions.x(), 0.01);
    }

    private static SchemaCache sampleCache() {
        SchemaNode factionId = col("id", true, false);
        SchemaNode factionName = col("name", false, true);
        SchemaNode factions = new SchemaNode("factions", NodeType.TABLE,
                List.of(factionId, factionName),
                Map.of(SchemaNode.META_CATALOG, "game"));

        SchemaNode charId = col("id", true, false);
        SchemaNode charFaction = col("faction_id", false, true);
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

    private static SchemaNode col(String name, boolean pk, boolean nullable) {
        return new SchemaNode(name, NodeType.COLUMN, List.of(), Map.of(
                SchemaNode.META_DATA_TYPE, "INT",
                SchemaNode.META_PRIMARY_KEY, Boolean.toString(pk),
                SchemaNode.META_NULLABLE, Boolean.toString(nullable)));
    }
}
