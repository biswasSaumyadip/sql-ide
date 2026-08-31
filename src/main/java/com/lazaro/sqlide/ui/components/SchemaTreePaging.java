package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Caps how many schema-tree rows we materialise at once so a 1000-table folder
 * does not create 1000 {@code TreeItem}s. The rest stay as {@link SchemaNode}
 * values until Show more or the tree filter asks for them.
 */
final class SchemaTreePaging {

    static final int PAGE_SIZE = 150;

    record Page(List<SchemaNode> visible, int remaining, int matched, int total) {
        Page {
            visible = visible == null ? List.of() : List.copyOf(visible);
        }

        boolean hasMore() {
            return remaining > 0;
        }
    }

    private SchemaTreePaging() {
    }

    static Page slice(List<SchemaNode> source, String needle, int limit) {
        int cap = Math.max(1, limit);
        if (source == null || source.isEmpty()) {
            return new Page(List.of(), 0, 0, 0);
        }
        String query = needle == null ? "" : needle.strip().toLowerCase(Locale.ROOT);
        List<SchemaNode> matched = new ArrayList<>();
        for (SchemaNode node : source) {
            if (node == null) {
                continue;
            }
            if (query.isEmpty() || node.name().toLowerCase(Locale.ROOT).contains(query)) {
                matched.add(node);
            }
        }
        int totalMatched = matched.size();
        if (totalMatched <= cap) {
            return new Page(matched, 0, totalMatched, source.size());
        }
        return new Page(matched.subList(0, cap), totalMatched - cap, totalMatched, source.size());
    }

    static int indexOf(List<SchemaNode> source, NodeType type, String name) {
        if (source == null || name == null || name.isBlank()) {
            return -1;
        }
        for (int i = 0; i < source.size(); i++) {
            SchemaNode node = source.get(i);
            if (node != null && node.type() == type && node.name().equalsIgnoreCase(name)) {
                return i;
            }
        }
        return -1;
    }

    static String showMoreLabel(int remaining, int matched) {
        if (remaining <= 0) {
            return "Show more";
        }
        if (remaining == 1) {
            return "Show more (1 of " + matched + " remaining)";
        }
        return "Show more (" + remaining + " of " + matched + " remaining)";
    }

    /**
     * Grouping folders ({@code tables}/{@code views}/{@code procedures}) are not a
     * paged name list. Filter by what is inside them, not by the folder label.
     */
    static boolean groupsByFolder(List<SchemaNode> source) {
        if (source == null || source.isEmpty()) {
            return false;
        }
        for (SchemaNode node : source) {
            if (node == null || node.type() != NodeType.FOLDER) {
                return false;
            }
        }
        return true;
    }

    static String identityKey(SchemaNode node) {
        if (node == null) {
            return "";
        }
        return node.type().name() + '\0' + node.name();
    }
}
