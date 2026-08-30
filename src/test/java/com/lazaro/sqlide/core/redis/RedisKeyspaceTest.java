package com.lazaro.sqlide.core.redis;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisKeyspaceTest {

    @Test
    @DisplayName("INFO keyspace always includes DB 0 even when empty")
    void alwaysIncludesDb0() {
        List<Integer> empty = RedisKeyspace.parseDatabaseIndexes(null);
        assertEquals(List.of(0), empty);

        List<Integer> blank = RedisKeyspace.parseDatabaseIndexes("");
        assertEquals(List.of(0), blank);

        List<SchemaNode> nodes = RedisKeyspace.databaseNodes(null);
        assertEquals(1, nodes.size());
        assertEquals("DB 0", nodes.getFirst().name());
        assertEquals(NodeType.DATABASE, nodes.getFirst().type());
        assertEquals("0", nodes.getFirst().metadata(SchemaNode.META_REDIS_DB));
        assertTrue(nodes.getFirst().children().isEmpty());
    }

    @Test
    @DisplayName("populated databases from INFO keyspace are listed in index order")
    void parsesKeyspaceInfo() {
        String info = """
                # Keyspace
                db0:keys=2,expires=0,avg_ttl=0
                db2:keys=5,expires=1,avg_ttl=1000
                """;
        List<RedisKeyspace.Entry> entries = RedisKeyspace.parse(info);
        assertEquals(List.of(0, 2), RedisKeyspace.parseDatabaseIndexes(info));
        assertEquals(2, entries.getFirst().keys());
        assertEquals(5, entries.get(1).keys());

        List<SchemaNode> nodes = RedisKeyspace.databaseNodes(info);
        assertEquals("DB 0", nodes.get(0).name());
        assertEquals("DB 2", nodes.get(1).name());
        assertEquals("5", nodes.get(1).metadata(SchemaNode.META_CHILD_COUNT));
    }

    @Test
    @DisplayName("DB 0 is inserted when only higher indexes have keys")
    void insertsDb0WhenMissing() {
        String info = "db1:keys=3,expires=0\n";
        assertEquals(List.of(0, 1), RedisKeyspace.parseDatabaseIndexes(info));
    }

    @Test
    @DisplayName("index is read from metadata or a DB N label")
    void parsesIndexFromName() {
        SchemaNode db1 = RedisKeyspace.databaseNode(1, 4);
        assertEquals(1, RedisKeyspace.indexOf(db1));
        assertEquals(1, RedisKeyspace.parseIndex("DB 1"));
        assertEquals(12, RedisKeyspace.parseIndex("db12"));
        assertEquals(3, RedisKeyspace.parseIndex("3"));
        assertEquals(0, RedisKeyspace.parseIndex("not-a-db"));
    }
}
