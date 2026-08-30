package com.lazaro.sqlide.core.diff;

import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.ForeignKey;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.IndexInfo;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.diff.SchemaDiff.Change;
import com.lazaro.sqlide.core.diff.SchemaDiff.Kind;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Compares two table {@link SchemaNode}s (columns, PK, indexes, FKs).
 */
public final class SchemaDiffService {

    private SchemaDiffService() {
    }

    public static SchemaDiff diffTables(SchemaNode left, SchemaNode right) {
        Objects.requireNonNull(left, "left");
        Objects.requireNonNull(right, "right");
        List<Change> changes = new ArrayList<>();

        Map<String, SchemaNode> leftCols = columnMap(left);
        Map<String, SchemaNode> rightCols = columnMap(right);

        Set<String> all = new LinkedHashSet<>();
        all.addAll(leftCols.keySet());
        all.addAll(rightCols.keySet());
        for (String name : all) {
            SchemaNode l = leftCols.get(name);
            SchemaNode r = rightCols.get(name);
            if (l == null) {
                changes.add(new Change(Kind.COLUMN_ADDED, name, "", columnDetail(r)));
            } else if (r == null) {
                changes.add(new Change(Kind.COLUMN_REMOVED, name, columnDetail(l), ""));
            } else {
                String lt = typeOf(l);
                String rt = typeOf(r);
                if (!lt.equalsIgnoreCase(rt)) {
                    changes.add(new Change(Kind.COLUMN_TYPE_CHANGED, name, lt, rt));
                }
                boolean ln = l.metadataFlag(SchemaNode.META_NULLABLE);
                boolean rn = r.metadataFlag(SchemaNode.META_NULLABLE);
                if (ln != rn) {
                    changes.add(new Change(Kind.COLUMN_NULLABLE_CHANGED, name,
                            ln ? "NULL" : "NOT NULL", rn ? "NULL" : "NOT NULL"));
                }
            }
        }

        String leftPk = primaryKey(left);
        String rightPk = primaryKey(right);
        if (!leftPk.equalsIgnoreCase(rightPk)) {
            changes.add(new Change(Kind.PRIMARY_KEY_CHANGED, "PRIMARY KEY",
                    leftPk.isBlank() ? "(none)" : leftPk,
                    rightPk.isBlank() ? "(none)" : rightPk));
        }

        Map<String, IndexInfo> leftIdx = indexMap(left);
        Map<String, IndexInfo> rightIdx = indexMap(right);
        Set<String> idxNames = new LinkedHashSet<>();
        idxNames.addAll(leftIdx.keySet());
        idxNames.addAll(rightIdx.keySet());
        for (String name : idxNames) {
            IndexInfo l = leftIdx.get(name);
            IndexInfo r = rightIdx.get(name);
            if (l == null) {
                changes.add(new Change(Kind.INDEX_ADDED, name, "", formatIndex(r)));
            } else if (r == null) {
                changes.add(new Change(Kind.INDEX_REMOVED, name, formatIndex(l), ""));
            } else if (!formatIndex(l).equalsIgnoreCase(formatIndex(r))) {
                changes.add(new Change(Kind.INDEX_REMOVED, name, formatIndex(l), ""));
                changes.add(new Change(Kind.INDEX_ADDED, name, "", formatIndex(r)));
            }
        }

        Map<String, ForeignKey> leftFk = fkMap(left);
        Map<String, ForeignKey> rightFk = fkMap(right);
        Set<String> fkNames = new LinkedHashSet<>();
        fkNames.addAll(leftFk.keySet());
        fkNames.addAll(rightFk.keySet());
        for (String name : fkNames) {
            ForeignKey l = leftFk.get(name);
            ForeignKey r = rightFk.get(name);
            if (l == null) {
                changes.add(new Change(Kind.FOREIGN_KEY_ADDED, name, "", formatFk(r)));
            } else if (r == null) {
                changes.add(new Change(Kind.FOREIGN_KEY_REMOVED, name, formatFk(l), ""));
            } else if (!formatFk(l).equalsIgnoreCase(formatFk(r))) {
                changes.add(new Change(Kind.FOREIGN_KEY_REMOVED, name, formatFk(l), ""));
                changes.add(new Change(Kind.FOREIGN_KEY_ADDED, name, "", formatFk(r)));
            }
        }

        return new SchemaDiff(qualify(left), qualify(right), changes);
    }

    private static Map<String, SchemaNode> columnMap(SchemaNode table) {
        Map<String, SchemaNode> map = new LinkedHashMap<>();
        for (SchemaNode col : columnsOf(table)) {
            map.put(col.name().toLowerCase(Locale.ROOT), col);
        }
        return map;
    }

    private static List<SchemaNode> columnsOf(SchemaNode table) {
        List<SchemaNode> columns = new ArrayList<>();
        collectColumns(table, columns);
        return columns;
    }

    private static void collectColumns(SchemaNode node, List<SchemaNode> out) {
        if (node.type() == NodeType.COLUMN) {
            out.add(node);
            return;
        }
        for (SchemaNode child : node.children()) {
            if (child.type() == NodeType.FOLDER
                    && SchemaNode.FOLDER_COLUMNS.equals(child.metadata(SchemaNode.META_FOLDER_KIND))) {
                for (SchemaNode col : child.children()) {
                    if (col.type() == NodeType.COLUMN) {
                        out.add(col);
                    }
                }
            } else if (child.type() == NodeType.COLUMN) {
                out.add(child);
            } else {
                collectColumns(child, out);
            }
        }
    }

    private static String primaryKey(SchemaNode table) {
        return columnsOf(table).stream()
                .filter(c -> c.metadataFlag(SchemaNode.META_PRIMARY_KEY))
                .map(SchemaNode::name)
                .collect(Collectors.joining(", "));
    }

    private static Map<String, IndexInfo> indexMap(SchemaNode table) {
        Map<String, IndexInfo> map = new LinkedHashMap<>();
        for (IndexInfo index : SchemaMetadataCodec.decodeIndexes(table.metadata(SchemaNode.META_INDEXES))) {
            String key = index.name().isBlank() ? formatIndex(index) : index.name().toLowerCase(Locale.ROOT);
            map.put(key, index);
        }
        return map;
    }

    private static Map<String, ForeignKey> fkMap(SchemaNode table) {
        Map<String, ForeignKey> map = new LinkedHashMap<>();
        for (ForeignKey fk : SchemaMetadataCodec.decodeForeignKeys(table.metadata(SchemaNode.META_FOREIGN_KEYS))) {
            String key = fk.name().isBlank() ? formatFk(fk) : fk.name().toLowerCase(Locale.ROOT);
            map.put(key, fk);
        }
        return map;
    }

    private static String typeOf(SchemaNode col) {
        String type = col.metadata(SchemaNode.META_DATA_TYPE);
        return type == null || type.isBlank() ? "?" : type;
    }

    private static String columnDetail(SchemaNode col) {
        String nullable = col.metadataFlag(SchemaNode.META_NULLABLE) ? "NULL" : "NOT NULL";
        return typeOf(col) + " " + nullable;
    }

    private static String formatIndex(IndexInfo index) {
        return (index.unique() ? "UNIQUE " : "") + "(" + String.join(", ", index.columns()) + ")";
    }

    private static String formatFk(ForeignKey fk) {
        return fk.fkColumn() + " \u2192 " + fk.pkTable() + "(" + fk.pkColumn() + ")";
    }

    private static String qualify(SchemaNode table) {
        String catalog = table.metadata(SchemaNode.META_CATALOG);
        if (catalog == null || catalog.isBlank()) {
            return table.name();
        }
        return catalog + "." + table.name();
    }
}
