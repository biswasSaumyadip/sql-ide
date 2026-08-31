package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

    @Test
    void findTableUsesNameIndexAmongManyTables() {
        List<SchemaNode> children = new ArrayList<>();
        for (int i = 0; i < 1_200; i++) {
            children.add(SchemaNode.of("t_" + i, NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app")));
        }
        children.add(SchemaNode.of("users", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app")));
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, children, Map.of())));

        assertEquals("users", cache.findTable("users", "app").orElseThrow().name());
        assertTrue(cache.findTable("missing", "app").isEmpty());
    }

    @Test
    void upsertCatalogsReplacesByNameAndKeepsTheRest() {
        SchemaNode items = SchemaNode.of("items", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "shop"));
        cache.upsertCatalogs(List.of(
                new SchemaNode("shop", NodeType.DATABASE, List.of(items), Map.of()),
                new SchemaNode("app", NodeType.DATABASE, List.of(
                        SchemaNode.of("accounts", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app"))),
                        Map.of())));

        assertEquals("accounts", cache.findTable("accounts", "app").orElseThrow().name());
        assertTrue(cache.findTable("users", "app").isEmpty(), "replaced catalog must drop stale tables");
        assertEquals("items", cache.findTable("items", "shop").orElseThrow().name());
        assertEquals("faction", cache.findTable("faction", "warcraft").orElseThrow().name());
    }
}
