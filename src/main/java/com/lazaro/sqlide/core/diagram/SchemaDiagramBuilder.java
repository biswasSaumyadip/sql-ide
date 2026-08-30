package com.lazaro.sqlide.core.diagram;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.ForeignKey;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.IndexInfo;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Cardinality;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Column;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.ColumnPair;
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
        int available = source.size();
        List<SchemaNode> selected = capTables(source, MAX_TABLES);
        return buildFromNodes(selected, catalog, null, available);
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
        int available = selected.size();
        selected = capTables(selected, MAX_TABLES);
        return buildFromNodes(selected, catalogOf(focus, catalog), tableId(focus), available);
    }

    static List<SchemaNode> capTables(List<SchemaNode> source, int max) {
        if (source == null || source.size() <= max) {
            return source == null ? List.of() : source;
        }
        Set<String> owners = new HashSet<>();
        Set<String> targets = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (SchemaNode table : source) {
            names.add(table.name().toLowerCase(Locale.ROOT));
        }
        for (SchemaNode table : source) {
            for (ForeignKey fk : foreignKeysOf(table)) {
                String from = table.name().toLowerCase(Locale.ROOT);
                String to = fk.pkTable() == null ? "" : fk.pkTable().toLowerCase(Locale.ROOT);
                if (!to.isEmpty() && names.contains(to)) {
                    owners.add(from);
                    targets.add(to);
                }
            }
        }
        List<SchemaNode> related = new ArrayList<>();
        List<SchemaNode> isolated = new ArrayList<>();
        for (SchemaNode table : source) {
            String name = table.name().toLowerCase(Locale.ROOT);
            if (owners.contains(name) || targets.contains(name)) {
                related.add(table);
            } else {
                isolated.add(table);
            }
        }
        List<SchemaNode> selected = new ArrayList<>(max);
        for (SchemaNode table : related) {
            if (selected.size() >= max) {
                break;
            }
            selected.add(table);
        }
        for (SchemaNode table : isolated) {
            if (selected.size() >= max) {
                break;
            }
            selected.add(table);
        }
        return selected;
    }

    private static SchemaDiagramModel buildFromNodes(
            List<SchemaNode> nodes, String catalog, String focusTableId, int availableTableCount) {
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
            for (List<ForeignKey> group : groupForeignKeys(foreignKeysOf(node))) {
                ForeignKey first = group.getFirst();
                String toId = nameToId.get(
                        first.pkTable() == null ? "" : first.pkTable().toLowerCase(Locale.ROOT));
                if (toId == null || toId.equals(fromId)) {
                    continue;
                }
                List<ColumnPair> pairs = new ArrayList<>(group.size());
                StringBuilder key = new StringBuilder(fromId).append("->").append(toId).append(':');
                for (ForeignKey fk : group) {
                    pairs.add(new ColumnPair(fk.fkColumn(), fk.pkColumn()));
                    key.append(fk.fkColumn()).append('>').append(fk.pkColumn()).append(',');
                }
                String edgeId = key.toString();
                if (!edgeKeys.add(edgeId)) {
                    continue;
                }
                Cardinality fromCard = childCardinality(node, group);
                edges.add(new Edge(edgeId, fromId, toId, pairs, first.name(), fromCard, Cardinality.ONE));
            }
        }

        return new SchemaDiagramModel(catalog == null ? "" : catalog, focusTableId,
                List.copyOf(tables.values()), List.copyOf(edges), availableTableCount);
    }

    /**
     * Groups JDBC FK rows that share a constraint name (and referenced table) into one edge.
     * Unnamed constraints stay one-column edges so two FKs to the same table are not merged.
     */
    static List<List<ForeignKey>> groupForeignKeys(List<ForeignKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        Map<String, List<ForeignKey>> grouped = new LinkedHashMap<>();
        int unnamed = 0;
        for (ForeignKey fk : keys) {
            String name = fk.name() == null ? "" : fk.name().strip();
            String pkTable = fk.pkTable() == null ? "" : fk.pkTable();
            String groupKey;
            if (name.isEmpty()) {
                groupKey = "__unnamed_" + unnamed++ + "\0" + pkTable;
            } else {
                groupKey = name + "\0" + pkTable;
            }
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(fk);
        }
        return List.copyOf(grouped.values());
    }

    static Cardinality childCardinality(SchemaNode owner, List<ForeignKey> parts) {
        List<String> fkColumns = new ArrayList<>(parts.size());
        for (ForeignKey fk : parts) {
            if (fk.fkColumn() != null && !fk.fkColumn().isBlank()) {
                fkColumns.add(fk.fkColumn());
            }
        }
        boolean optional = false;
        Map<String, SchemaNode> byName = new HashMap<>();
        for (SchemaNode column : columnNodes(owner)) {
            byName.put(column.name().toLowerCase(Locale.ROOT), column);
        }
        for (String fkColumn : fkColumns) {
            SchemaNode column = byName.get(fkColumn.toLowerCase(Locale.ROOT));
            if (column == null || column.metadataFlag(SchemaNode.META_NULLABLE)) {
                optional = true;
                break;
            }
        }
        boolean unique = columnsFormUniqueKey(owner, fkColumns);
        if (unique) {
            return optional ? Cardinality.ZERO_OR_ONE : Cardinality.ONE;
        }
        return optional ? Cardinality.ZERO_OR_MANY : Cardinality.ONE_OR_MANY;
    }

    static boolean columnsFormUniqueKey(SchemaNode table, List<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return false;
        }
        Set<String> want = lowerSet(columns);
        Set<String> pk = new HashSet<>();
        for (SchemaNode column : columnNodes(table)) {
            if (column.metadataFlag(SchemaNode.META_PRIMARY_KEY)) {
                pk.add(column.name().toLowerCase(Locale.ROOT));
            }
        }
        if (!pk.isEmpty() && pk.equals(want)) {
            return true;
        }
        for (IndexInfo index : SchemaMetadataCodec.decodeIndexes(table.metadata(SchemaNode.META_INDEXES))) {
            if (index.unique() && lowerSet(index.columns()).equals(want)) {
                return true;
            }
        }
        return false;
    }

    private static Set<String> lowerSet(List<String> values) {
        Set<String> set = new HashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                set.add(value.toLowerCase(Locale.ROOT));
            }
        }
        return set;
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
                    fkColumns.contains(columnNode.name().toLowerCase(Locale.ROOT)),
                    columnNode.metadataFlag(SchemaNode.META_NULLABLE)));
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
