package com.lazaro.sqlide.core.db;

import com.lazaro.sqlide.core.db.SchemaNode.NodeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Virtual grouping folders under a catalog or table. Children are {@link SchemaNode}
 * values only — the tree pages {@code TreeItem}s separately.
 */
final class SchemaFolders {

    private SchemaFolders() {
    }

    static List<SchemaNode> forCatalog(
            String catalog, List<SchemaNode> tablesAndViews, List<SchemaNode> procedures) {
        List<SchemaNode> tables = new ArrayList<>();
        List<SchemaNode> views = new ArrayList<>();
        for (SchemaNode node : tablesAndViews) {
            if (node == null) {
                continue;
            }
            if (node.type() == NodeType.TABLE) {
                tables.add(node);
            } else if (node.type() == NodeType.VIEW) {
                views.add(node);
            }
        }
        Map<String, String> catalogMeta = Map.of(SchemaNode.META_CATALOG, catalog == null ? "" : catalog);
        List<SchemaNode> folders = new ArrayList<>(3);
        folders.add(SchemaNode.folder(SchemaNode.FOLDER_TABLES, SchemaNode.FOLDER_TABLES, tables.size(), catalogMeta)
                .withChildren(tables));
        if (!views.isEmpty()) {
            folders.add(SchemaNode.folder(SchemaNode.FOLDER_VIEWS, SchemaNode.FOLDER_VIEWS, views.size(), catalogMeta)
                    .withChildren(views));
        }
        if (procedures != null && !procedures.isEmpty()) {
            folders.add(SchemaNode.folder(
                            SchemaNode.FOLDER_PROCEDURES, SchemaNode.FOLDER_PROCEDURES, procedures.size(), catalogMeta)
                    .withChildren(procedures));
        }
        return List.copyOf(folders);
    }

    static List<SchemaNode> forTable(
            String catalog,
            String table,
            List<SchemaNode> columns,
            List<SchemaNode> keys,
            List<SchemaNode> indexes) {
        Map<String, String> shared = Map.of(
                SchemaNode.META_CATALOG, catalog == null ? "" : catalog,
                SchemaNode.META_TABLE, table == null ? "" : table);
        List<SchemaNode> cols = columns == null ? List.of() : columns;
        List<SchemaNode> keyNodes = keys == null ? List.of() : keys;
        List<SchemaNode> indexNodes = indexes == null ? List.of() : indexes;
        return List.of(
                folder(SchemaNode.FOLDER_COLUMNS, cols.size(), shared, cols),
                folder(SchemaNode.FOLDER_KEYS, keyNodes.size(), shared, keyNodes),
                folder(SchemaNode.FOLDER_INDEXES, indexNodes.size(), shared, indexNodes));
    }

    /** Count-only folders so expanding a table does not fetch columns/keys/indexes yet. */
    static List<SchemaNode> emptyTableFolders(String catalog, String table) {
        return forTable(catalog, table, List.of(), List.of(), List.of());
    }

    private static SchemaNode folder(String kind, int count, Map<String, String> shared, List<SchemaNode> children) {
        SchemaNode node = SchemaNode.folder(kind, kind, count, shared);
        return children.isEmpty() ? node : node.withChildren(children);
    }
}
