package com.lazaro.sqlide.core.redis;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns a flat Redis key list into a colon-delimited folder tree.
 *
 * <p>{@code cache:session:abc123} becomes folder {@code cache} / folder
 * {@code session} / leaf {@code abc123}. The leaf keeps the full key in
 * {@link SchemaNode#META_REDIS_KEY}.
 */
public final class RedisKeyTree {

    private RedisKeyTree() {
    }

    public static List<SchemaNode> build(Iterable<String> keys) {
        Node root = new Node("");
        if (keys != null) {
            for (String key : keys) {
                if (key != null && !key.isEmpty()) {
                    insert(root, key);
                }
            }
        }
        return toChildren(root);
    }

    private static void insert(Node root, String key) {
        String[] parts = key.split(":", -1);
        Node current = root;
        for (int i = 0; i < parts.length; i++) {
            String segment = parts[i].isEmpty() ? ":" : parts[i];
            current = current.child(segment);
            if (i == parts.length - 1) {
                current.fullKey = key;
            }
        }
    }

    private static List<SchemaNode> toChildren(Node node) {
        List<SchemaNode> out = new ArrayList<>();
        for (Node child : node.children.values()) {
            out.add(toSchema(child));
        }
        return List.copyOf(out);
    }

    private static SchemaNode toSchema(Node node) {
        boolean folder = !node.children.isEmpty();
        if (!folder) {
            return redisKey(node.segment, Objects.requireNonNullElse(node.fullKey, node.segment));
        }
        List<SchemaNode> kids = new ArrayList<>();
        if (node.fullKey != null) {
            kids.add(redisKey(node.segment, node.fullKey));
        }
        kids.addAll(toChildren(node));
        Map<String, String> extra = new LinkedHashMap<>();
        extra.put(SchemaNode.META_FOLDER_KIND, SchemaNode.FOLDER_REDIS);
        if (node.fullKey != null) {
            extra.put(SchemaNode.META_REDIS_KEY, node.fullKey);
        }
        return SchemaNode.folder(node.segment, SchemaNode.FOLDER_REDIS, kids.size(), extra)
                .withChildren(kids);
    }

    private static SchemaNode redisKey(String display, String fullKey) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(SchemaNode.META_REDIS_KEY, fullKey);
        return SchemaNode.of(display, NodeType.REDIS_KEY, meta);
    }

    private static final class Node {
        final String segment;
        final Map<String, Node> children = new LinkedHashMap<>();
        String fullKey;

        Node(String segment) {
            this.segment = segment;
        }

        Node child(String name) {
            return children.computeIfAbsent(name, Node::new);
        }
    }
}
