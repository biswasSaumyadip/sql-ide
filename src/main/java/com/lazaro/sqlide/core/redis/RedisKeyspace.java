package com.lazaro.sqlide.core.redis;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses Redis {@code INFO keyspace} and builds lazy logical-database tree nodes
 * ({@code DB 0}, {@code DB 1}, …).
 */
public final class RedisKeyspace {

    private static final Pattern DB_LINE = Pattern.compile(
            "(?i)\\bdb(\\d+)\\s*:\\s*(?:keys=(\\d+))?");
    private static final Pattern DB_NAME = Pattern.compile("(?i)(?:^|\\b)db\\s*(\\d+)\\b");

    private RedisKeyspace() {
    }

    /**
     * Indexes present in {@code INFO keyspace}. Index {@code 0} is always included
     * so an empty instance still has a place to create keys.
     */
    public static List<Integer> parseDatabaseIndexes(String keyspaceInfo) {
        List<Entry> entries = parse(keyspaceInfo);
        List<Integer> indexes = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            indexes.add(entry.index());
        }
        return List.copyOf(indexes);
    }

    public static List<Entry> parse(String keyspaceInfo) {
        TreeMap<Integer, Entry> byIndex = new TreeMap<>();
        byIndex.put(0, new Entry(0, 0));
        if (keyspaceInfo != null && !keyspaceInfo.isBlank()) {
            Matcher matcher = DB_LINE.matcher(keyspaceInfo);
            while (matcher.find()) {
                int index = Integer.parseInt(matcher.group(1));
                int keys = 0;
                if (matcher.group(2) != null) {
                    keys = Integer.parseInt(matcher.group(2));
                }
                byIndex.put(index, new Entry(index, keys));
            }
        }
        return List.copyOf(byIndex.values());
    }

    public static List<SchemaNode> databaseNodes(String keyspaceInfo) {
        List<SchemaNode> nodes = new ArrayList<>();
        for (Entry entry : parse(keyspaceInfo)) {
            nodes.add(databaseNode(entry.index(), entry.keys()));
        }
        return List.copyOf(nodes);
    }

    public static SchemaNode databaseNode(int index, int keyCount) {
        int db = Math.max(0, index);
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(SchemaNode.META_REDIS_DB, Integer.toString(db));
        meta.put(SchemaNode.META_CATALOG, Integer.toString(db));
        meta.put(SchemaNode.META_CHILD_COUNT, Integer.toString(Math.max(0, keyCount)));
        return SchemaNode.of(displayName(db), NodeType.DATABASE, meta);
    }

    public static String displayName(int index) {
        return "DB " + Math.max(0, index);
    }

    /** Reads the logical DB index from metadata, then from a {@code DB N} label. */
    public static int indexOf(SchemaNode node) {
        if (node == null) {
            return 0;
        }
        String meta = node.metadata(SchemaNode.META_REDIS_DB);
        if (meta != null && !meta.isBlank()) {
            return parseIndex(meta);
        }
        return parseIndex(node.name());
    }

    public static int parseIndex(String nameOrNumber) {
        if (nameOrNumber == null || nameOrNumber.isBlank()) {
            return 0;
        }
        String raw = nameOrNumber.strip();
        Matcher named = DB_NAME.matcher(raw);
        if (named.find()) {
            return Integer.parseInt(named.group(1));
        }
        try {
            return Math.max(0, Integer.parseInt(raw));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public record Entry(int index, int keys) {
        public Entry {
            index = Math.max(0, index);
            keys = Math.max(0, keys);
        }
    }
}
