package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaPrefixIndexTest {

    @Test
    void prefixHitsAreAContiguousSortedRange() {
        SchemaPrefixIndex index = SchemaPrefixIndex.of(tables("alpha", "users", "user_roles", "orders", "usage"));
        assertEquals(List.of("usage", "user_roles", "users"), names(index.prefixHits("us")));
        assertEquals(List.of("orders"), names(index.prefixHits("ord")));
        assertTrue(index.prefixHits("xyz").isEmpty());
        assertTrue(index.prefixHits("").isEmpty());
    }

    @Test
    void prefixLookupIsCaseInsensitive() {
        SchemaPrefixIndex index = SchemaPrefixIndex.of(tables("Users", "USER_ROLES"));
        assertEquals(List.of("USER_ROLES", "Users"), names(index.prefixHits("US")));
    }

    @Test
    void emptyIndexHasNoHits() {
        assertTrue(SchemaPrefixIndex.EMPTY.prefixHits("us").isEmpty());
        assertTrue(SchemaPrefixIndex.of(List.of()).prefixHits("us").isEmpty());
    }

    private static List<SchemaNode> tables(String... names) {
        List<SchemaNode> tables = new ArrayList<>(names.length);
        for (String name : names) {
            tables.add(SchemaNode.of(name, NodeType.TABLE, Map.of()));
        }
        return tables;
    }

    private static List<String> names(List<SchemaNode> nodes) {
        return nodes.stream().map(SchemaNode::name).toList();
    }
}
