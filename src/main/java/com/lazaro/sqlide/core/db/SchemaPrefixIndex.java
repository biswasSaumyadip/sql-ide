package com.lazaro.sqlide.core.db;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Sorted lowercase table names for {@code O(log n)} prefix lookup. Autocomplete
 * uses this so {@code FROM us} does not fuzzy-score every table in the catalog.
 */
public final class SchemaPrefixIndex {

    static final SchemaPrefixIndex EMPTY = new SchemaPrefixIndex(new String[0], new SchemaNode[0]);

    private final String[] keys;
    private final SchemaNode[] nodes;

    private SchemaPrefixIndex(String[] keys, SchemaNode[] nodes) {
        this.keys = keys;
        this.nodes = nodes;
    }

    static SchemaPrefixIndex of(List<SchemaNode> tables) {
        if (tables == null || tables.isEmpty()) {
            return EMPTY;
        }
        record Pair(String key, SchemaNode node) {
        }
        Pair[] pairs = new Pair[tables.size()];
        int n = 0;
        for (SchemaNode table : tables) {
            if (table == null || table.name() == null || table.name().isBlank()) {
                continue;
            }
            pairs[n++] = new Pair(table.name().toLowerCase(Locale.ROOT), table);
        }
        if (n == 0) {
            return EMPTY;
        }
        Arrays.sort(pairs, 0, n, Comparator.comparing(Pair::key));
        String[] keys = new String[n];
        SchemaNode[] nodes = new SchemaNode[n];
        for (int i = 0; i < n; i++) {
            keys[i] = pairs[i].key();
            nodes[i] = pairs[i].node();
        }
        return new SchemaPrefixIndex(keys, nodes);
    }

    /**
     * Tables whose names start with {@code prefix} (case-insensitive).
     * Empty prefix returns an empty list — callers should use the full snapshot
     * instead of materialising every name through the index.
     */
    List<SchemaNode> prefixHits(String prefix) {
        if (keys.length == 0 || prefix == null || prefix.isEmpty()) {
            return List.of();
        }
        String needle = prefix.toLowerCase(Locale.ROOT);
        int from = lowerBound(needle);
        List<SchemaNode> hits = new ArrayList<>();
        for (int i = from; i < keys.length; i++) {
            if (!keys[i].startsWith(needle)) {
                break;
            }
            hits.add(nodes[i]);
        }
        return List.copyOf(hits);
    }

    int size() {
        return keys.length;
    }

    private int lowerBound(String needle) {
        int lo = 0;
        int hi = keys.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (keys[mid].compareTo(needle) < 0) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }
}
