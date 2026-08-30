package com.lazaro.sqlide.core.redis;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RedisKeyTreeTest {

    @Test
    @DisplayName("keys without colons sit at the root as leaves")
    void flatKeys() {
        List<SchemaNode> tree = RedisKeyTree.build(List.of("ping", "alpha"));
        assertEquals(2, tree.size());
        assertEquals("ping", tree.get(0).name());
        assertEquals(NodeType.REDIS_KEY, tree.get(0).type());
        assertEquals("ping", tree.get(0).metadata(SchemaNode.META_REDIS_KEY));
        assertEquals("alpha", tree.get(1).name());
    }

    @Test
    @DisplayName("colon-separated keys become nested folders")
    void nestedNamespaces() {
        List<SchemaNode> tree = RedisKeyTree.build(List.of("cache:session:abc123"));
        assertEquals(1, tree.size());
        SchemaNode cache = tree.getFirst();
        assertEquals("cache", cache.name());
        assertEquals(NodeType.FOLDER, cache.type());
        assertEquals(SchemaNode.FOLDER_REDIS, cache.folderKind());

        SchemaNode session = cache.children().getFirst();
        assertEquals("session", session.name());
        assertEquals(NodeType.FOLDER, session.type());

        SchemaNode leaf = session.children().getFirst();
        assertEquals("abc123", leaf.name());
        assertEquals(NodeType.REDIS_KEY, leaf.type());
        assertEquals("cache:session:abc123", leaf.metadata(SchemaNode.META_REDIS_KEY));
    }

    @Test
    @DisplayName("a prefix that is itself a key appears as a leaf beside its children")
    void keyThatIsAlsoAFolder() {
        List<SchemaNode> tree = RedisKeyTree.build(List.of("user", "user:100:profile"));
        SchemaNode user = tree.getFirst();
        assertEquals(NodeType.FOLDER, user.type());
        assertEquals(2, user.children().size());
        SchemaNode self = user.children().getFirst();
        assertEquals(NodeType.REDIS_KEY, self.type());
        assertEquals("user", self.metadata(SchemaNode.META_REDIS_KEY));
        SchemaNode nested = user.children().get(1);
        assertEquals("100", nested.name());
        assertEquals(NodeType.FOLDER, nested.type());
        assertEquals("user:100:profile", nested.children().getFirst().metadata(SchemaNode.META_REDIS_KEY));
    }
}
