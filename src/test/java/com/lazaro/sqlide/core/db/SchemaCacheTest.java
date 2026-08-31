package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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
        assertEquals("accounts", cache.tables("app").getFirst().name());
        assertTrue(cache.tables("shop").stream().anyMatch(t -> t.name().equals("items")));
    }

    @Test
    void tablesReturnsTheSameSnapshotUntilTheCacheIsRebuilt() {
        List<SchemaNode> first = cache.tables("app");
        List<SchemaNode> second = cache.tables("app");
        assertEquals(first, second);
        assertSame(first, second);
        assertSame(cache.tables(), cache.tables());
    }

    @Test
    void tablesWalksLogicalFolders() {
        SchemaNode nested = SchemaNode.of("orders", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app"));
        SchemaNode folder = SchemaNode.folder(
                "tables", SchemaNode.FOLDER_TABLES, 1, Map.of(SchemaNode.META_CATALOG, "app"));
        folder = folder.withChildren(List.of(nested));
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, List.of(folder), Map.of())));
        assertEquals("orders", cache.tables("app").getFirst().name());
        assertEquals("orders", cache.findTable("orders", "app").orElseThrow().name());
        assertEquals("orders", cache.tablesWithPrefix("app", "ord").getFirst().name());
    }

    @Test
    void tablesWithPrefixUsesSortedIndexPerCatalog() {
        List<SchemaNode> children = new ArrayList<>();
        children.add(SchemaNode.of("users", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app")));
        children.add(SchemaNode.of("user_roles", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app")));
        children.add(SchemaNode.of("orders", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app")));
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, children, Map.of())));

        assertEquals(List.of("user_roles", "users"),
                cache.tablesWithPrefix("app", "us").stream().map(SchemaNode::name).toList());
        assertTrue(cache.tablesWithPrefix("app", "xyz").isEmpty());
        assertTrue(cache.tablesWithPrefix("app", "").isEmpty());
        assertTrue(cache.tablesWithPrefix("shop", "us").isEmpty());
        assertEquals(List.of("user_roles", "users"),
                cache.tablesWithPrefix(null, "US").stream().map(SchemaNode::name).toList());
    }

    @Test
    void joinSuggestionsUseDecodedForeignKeysWithoutRescanningTables() {
        String fks = SchemaMetadataCodec.encodeForeignKeys(List.of(
                new SchemaMetadataCodec.ForeignKey("fk_orders_user", "user_id", "users", "id")));
        SchemaNode users = SchemaNode.of("users", NodeType.TABLE, Map.of(SchemaNode.META_CATALOG, "app"));
        SchemaNode orders = new SchemaNode("orders", NodeType.TABLE, List.of(), Map.of(
                SchemaNode.META_CATALOG, "app",
                SchemaNode.META_FOREIGN_KEYS, fks));
        cache.replace(List.of(new SchemaNode("app", NodeType.DATABASE, List.of(users, orders), Map.of())));

        List<SchemaCache.JoinSuggestion> fromUsers = cache.joinSuggestions(List.of("users"));
        assertTrue(fromUsers.stream().anyMatch(j ->
                j.toTable().equals("orders") && j.insertText().contains("user_id")), fromUsers.toString());
        List<SchemaCache.JoinSuggestion> fromOrders = cache.joinSuggestions(List.of("orders"));
        assertTrue(fromOrders.stream().anyMatch(j ->
                j.toTable().equals("users") && j.insertText().contains("orders")), fromOrders.toString());
    }

    @Test
    void cachedChildrenBuildsCatalogFoldersFromTheSnapshot() {
        Optional<List<SchemaNode>> folders = cache.cachedChildren(
                SchemaNode.of("app", NodeType.DATABASE));
        assertTrue(folders.isPresent());
        assertTrue(folders.get().stream().anyMatch(n ->
                SchemaNode.FOLDER_TABLES.equals(n.folderKind()) && !n.children().isEmpty()));
        assertTrue(cache.cachedChildren(SchemaNode.of("missing", NodeType.DATABASE)).isEmpty());
    }
}
