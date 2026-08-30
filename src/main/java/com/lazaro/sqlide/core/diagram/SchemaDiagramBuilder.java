package com.lazaro.sqlide.core.diagram;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.ForeignKey;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Column;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Edge;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Builds an ER graph from a {@link SchemaCache} snapshot.
 */
public final class SchemaDiagramBuilder {

    /** Soft cap so a full-schema diagram stays usable. */
    public static final int MAX_TABLES = 80;

    private SchemaDiagramBuilder() {
    }

    /** All tables/views in {@code catalog} (or every catalog when blank). */
    public static SchemaDiagramModel buildCatalog(SchemaCache cache, String catalog) {
        Objects.requireNonNull(cache, "cache");
        List<SchemaNode> source = cache.tables(blankToNull(catalog));
        if (source.size() > MAX_TABLES) {
            source = source.subList(0, MAX_TABLES);
        }
        return buildFromNodes(source, catalog, null);
    }

    /**
     * Focus table plus neighbors connected by foreign keys (1 hop).
     * Falls back to the whole catalog when the focus table is missing.
     */
    public static SchemaDiagramModel buildNeighborhood(SchemaCache cache, String catalog, String focusTable) {
        Objects.requireNonNull(cache, "cache");
        if (focusTable == null || focusTable.isBlank()) {
            return buildCatalog(cache, catalog);
        }
        List<SchemaNode> all = cache.tables(blankToNull(catalog));
        Map<String, SchemaNode> byName = indexByName(all);
        SchemaNode focus = byName.get(focusTable.toLowerCase(Locale.ROOT));
        if (focus == null) {
            return buildCatalog(cache, catalog);
        }

        Set<String> include = new LinkedHashSet<>();
        include.add(focus.name().toLowerCase(Locale.ROOT));
        for (SchemaNode table : all) {
            for (ForeignKey fk : foreignKeysOf(table)) {
                String from = table.name().toLowerCase(Locale.ROOT);
                String to = fk.pkTable() == null ? "" : fk.pkTable().toLowerCase(Locale.ROOT);
                if (from.equals(focus.name().toLowerCase(Locale.ROOT)) && byName.containsKey(to)) {
                    include.add(to);
                }
                if (to.equals(focus.name().toLowerCase(Locale.ROOT))) {
                    include.add(from);
                }
            }
        }

        List<SchemaNode> selected = new ArrayList<>();
        for (SchemaNode table : all) {
            if (include.contains(table.name().toLowerCase(Locale.ROOT))) {
                selected.add(table);
            }
        }
        return buildFromNodes(selected, catalogOf(focus, catalog), tableId(focus));
    }

    private static SchemaDiagramModel buildFromNodes(
            List<SchemaNode> nodes, String catalog, String focusTableId) {
        Map<String, Table> tables = new LinkedHashMap<>();
        Map<String, String> nameToId = new HashMap<>();
        for (SchemaNode node : nodes) {
            if (node.type() != NodeType.TABLE && node.type() != NodeType.VIEW) {
                continue;
            }
            Table table = toTable(node);
            tables.put(table.id(), table);
            nameToId.put(table.name().toLowerCase(Locale.ROOT), table.id());
        }

        List<Edge> edges = new ArrayList<>();
        Set<String> edgeKeys = new HashSet<>();
        for (SchemaNode node : nodes) {
            if (node.type() != NodeType.TABLE && node.type() != NodeType.VIEW) {
                continue;
            }
            String fromId = nameToId.get(node.name().toLowerCase(Locale.ROOT));
            if (fromId == null) {
                continue;
            }
            for (ForeignKey fk : foreignKeysOf(node)) {
                String toId = nameToId.get(
                        fk.pkTable() == null ? "" : fk.pkTable().toLowerCase(Locale.ROOT));
                if (toId == null || toId.equals(fromId)) {
                    continue;
                }
                String key = fromId + "->" + toId + ":" + fk.fkColumn() + ":" + fk.pkColumn();
                if (!edgeKeys.add(key)) {
                    continue;
                }
                edges.add(new Edge(key, fromId, toId, fk.fkColumn(), fk.pkColumn(), fk.name()));
            }
        }

        return new SchemaDiagramModel(catalog == null ? "" : catalog, focusTableId,
                List.copyOf(tables.values()), List.copyOf(edges));
    }

    private static Table toTable(SchemaNode node) {
        Set<String> fkColumns = new HashSet<>();
        for (ForeignKey fk : foreignKeysOf(node)) {
            if (fk.fkColumn() != null && !fk.fkColumn().isBlank()) {
                fkColumns.add(fk.fkColumn().toLowerCase(Locale.ROOT));
            }
        }
        List<Column> columns = new ArrayList<>();
        for (SchemaNode columnNode : columnNodes(node)) {
            columns.add(new Column(
                    columnNode.name(),
                    columnNode.metadata(SchemaNode.META_DATA_TYPE),
                    columnNode.metadataFlag(SchemaNode.META_PRIMARY_KEY),
                    fkColumns.contains(columnNode.name().toLowerCase(Locale.ROOT))));
        }
        String catalog = node.metadata(SchemaNode.META_CATALOG);
        return new Table(
                tableId(node),
                node.name(),
                catalog == null ? "" : catalog,
                node.type() == NodeType.VIEW,
                columns,
                0, 0, 0, 0);
    }

    private static List<SchemaNode> columnNodes(SchemaNode table) {
        List<SchemaNode> columns = new ArrayList<>();
        for (SchemaNode child : table.children()) {
            if (child.type() == NodeType.COLUMN) {
                columns.add(child);
            } else if (child.type() == NodeType.FOLDER
                    && SchemaNode.FOLDER_COLUMNS.equals(child.folderKind())) {
                for (SchemaNode nested : child.children()) {
                    if (nested.type() == NodeType.COLUMN) {
                        columns.add(nested);
                    }
                }
            }
        }
        return columns;
    }

    private static List<ForeignKey> foreignKeysOf(SchemaNode table) {
        return SchemaMetadataCodec.decodeForeignKeys(table.metadata(SchemaNode.META_FOREIGN_KEYS));
    }

    private static Map<String, SchemaNode> indexByName(List<SchemaNode> tables) {
        Map<String, SchemaNode> map = new HashMap<>();
        for (SchemaNode table : tables) {
            map.put(table.name().toLowerCase(Locale.ROOT), table);
        }
        return map;
    }

    private static String tableId(SchemaNode node) {
        String catalog = node.metadata(SchemaNode.META_CATALOG);
        if (catalog == null || catalog.isBlank()) {
            return node.name().toLowerCase(Locale.ROOT);
        }
        return catalog.toLowerCase(Locale.ROOT) + "." + node.name().toLowerCase(Locale.ROOT);
    }

    private static String catalogOf(SchemaNode focus, String fallback) {
        String meta = focus.metadata(SchemaNode.META_CATALOG);
        if (meta != null && !meta.isBlank()) {
            return meta;
        }
        return fallback == null ? "" : fallback;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
