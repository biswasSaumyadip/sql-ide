package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import javafx.scene.control.TreeItem;

import java.util.ArrayList;
import java.util.List;

/** Helpers for Database-pane actions: qualified names and generated SQL. */
public final class SchemaObjectNames {

    private SchemaObjectNames() {
    }

    /** Dot-qualified path excluding data-source roots and placeholders. */
    public static String qualifiedName(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (TreeItem<SchemaNode> cursor = item; cursor != null; cursor = cursor.getParent()) {
            // Skip the hidden TreeView root (parent == null).
            if (cursor.getParent() == null) {
                continue;
            }
            SchemaNode node = cursor.getValue();
            if (node == null || node.type() == NodeType.DATA_SOURCE) {
                continue;
            }
            if (node.metadataFlag("__placeholder")) {
                continue;
            }
            parts.add(0, node.name());
        }
        return String.join(".", parts);
    }

    /**
     * {@code SELECT * FROM catalog.table;} for tables/views, or
     * {@code SELECT col FROM catalog.table;} for columns. {@code null} when N/A.
     */
    public static String generateSelect(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return null;
        }
        SchemaNode node = item.getValue();
        return switch (node.type()) {
            case TABLE, VIEW -> "SELECT * FROM " + qualifiedName(item) + ";";
            case COLUMN -> {
                TreeItem<SchemaNode> parent = item.getParent();
                if (parent == null || parent.getValue() == null) {
                    yield "SELECT " + node.name() + ";";
                }
                yield "SELECT " + node.name() + " FROM " + qualifiedName(parent) + ";";
            }
            default -> null;
        };
    }
}
