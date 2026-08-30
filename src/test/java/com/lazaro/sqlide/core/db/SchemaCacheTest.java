package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaCacheTest {

    private SchemaCache cache;

    @BeforeEach
    void seed() {
        SchemaNode faction = new SchemaNode("faction", NodeType.TABLE, List.of(
                SchemaNode.of("id", NodeType.COLUMN)), Map.of(SchemaNode.META_CATALOG, "warcraft"));
        SchemaNode users = new SchemaNode("users", NodeType.TABLE, List.of(
                SchemaNode.of("id", NodeType.COLUMN)), Map.of(SchemaNode.META_CATALOG, "app"));
        cache = new SchemaCache();
        cache.replace(List.of(
                new SchemaNode("app", NodeType.DATABASE, List.of(users), Map.of()),
                new SchemaNode("warcraft", NodeType.DATABASE, List.of(faction), Map.of())));
    }

    @Test
    void resolveTableUsesQualifiedCatalogStrictly() {
        assertEquals("faction", cache.resolveTable("warcraft", "faction", "app").orElseThrow().name());
        assertTrue(cache.resolveTable("app", "faction", "app").isEmpty());
        assertTrue(cache.findInCatalog("warcraft", "users").isEmpty());
    }

    @Test
    void resolveTableFallsBackToActiveCatalogWhenUnqualified() {
        assertEquals("users", cache.resolveTable(null, "users", "app").orElseThrow().name());
        assertEquals("faction", cache.resolveTable(null, "faction", "warcraft").orElseThrow().name());
    }

    @Test
    void proceduresAreListedSeparatelyFromTables() {
        SchemaNode greet = SchemaNode.of("greet_user", NodeType.PROCEDURE, Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_ROUTINE_KIND, SchemaNode.ROUTINE_PROCEDURE));
        SchemaNode fn = SchemaNode.of("add_gold", NodeType.PROCEDURE, Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_ROUTINE_KIND, SchemaNode.ROUTINE_FUNCTION));
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, List.of(
                SchemaNode.of("users", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app")),
                greet,
                fn), Map.of())));

        assertEquals(List.of("greet_user"), cache.procedures("app").stream().map(SchemaNode::name).toList());
        assertTrue(cache.tables("app").stream().noneMatch(n -> n.type() == NodeType.PROCEDURE));
    }
}
