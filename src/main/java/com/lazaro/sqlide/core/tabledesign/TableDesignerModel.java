package com.lazaro.sqlide.core.tabledesign;

import com.lazaro.sqlide.core.db.ConnectionConfig.Driver;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.ForeignKey;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.IndexInfo;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.transfer.TransferSql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Mutable draft of a table's columns / keys. Compared with the original snapshot
 * to emit a MySQL-oriented {@code ALTER TABLE} script (DataGrip Modify Table).
 */
public final class TableDesignerModel {

    public static final class ColumnDraft {
        private final String originalName;
        private final String snapshotName;
        private final String snapshotType;
        private final boolean snapshotNullable;
        private final boolean snapshotPrimaryKey;
        private final boolean snapshotAutoIncrement;
        private final String snapshotDefault;
        private final String snapshotComment;
        private String name;
        private String dataType;
        private boolean nullable;
        private boolean primaryKey;
        private boolean autoIncrement;
        private String defaultValue;
        private String comment;
        private boolean dropped;

        public ColumnDraft(String originalName, String name, String dataType, boolean nullable, boolean primaryKey) {
            this(originalName, name, dataType, nullable, primaryKey, false, "", "");
        }

        public ColumnDraft(
                String originalName,
                String name,
                String dataType,
                boolean nullable,
                boolean primaryKey,
                boolean autoIncrement,
                String defaultValue,
                String comment) {
            this.originalName = originalName;
            this.name = name == null ? "" : name;
            this.dataType = dataType == null ? "" : dataType;
            this.nullable = nullable;
            this.primaryKey = primaryKey;
            this.autoIncrement = autoIncrement;
            this.defaultValue = defaultValue == null ? "" : defaultValue;
            this.comment = comment == null ? "" : comment;
            this.snapshotName = this.name;
            this.snapshotType = this.dataType;
            this.snapshotNullable = nullable;
            this.snapshotPrimaryKey = primaryKey;
            this.snapshotAutoIncrement = autoIncrement;
            this.snapshotDefault = this.defaultValue;
            this.snapshotComment = this.comment;
        }

        public static ColumnDraft added(String name) {
            return new ColumnDraft(null, name, "VARCHAR(255)", true, false);
        }

        public String originalName() {
            return originalName;
        }

        public boolean added() {
            return originalName == null || originalName.isBlank();
        }

        public boolean modified() {
            if (added() || dropped) {
                return false;
            }
            return !name.equals(snapshotName)
                    || !dataType.equalsIgnoreCase(snapshotType)
                    || nullable != snapshotNullable
                    || primaryKey != snapshotPrimaryKey
                    || autoIncrement != snapshotAutoIncrement
                    || !defaultValue.equals(snapshotDefault)
                    || !comment.equals(snapshotComment);
        }

        public String name() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.strip();
        }

        public String dataType() {
            return dataType;
        }

        public void setDataType(String dataType) {
            this.dataType = dataType == null ? "" : dataType.strip();
        }

        public boolean nullable() {
            return nullable;
        }

        public void setNullable(boolean nullable) {
            this.nullable = nullable;
        }

        public boolean primaryKey() {
            return primaryKey;
        }

        public void setPrimaryKey(boolean primaryKey) {
            this.primaryKey = primaryKey;
            if (primaryKey) {
                this.nullable = false;
            }
        }

        public boolean autoIncrement() {
            return autoIncrement;
        }

        public void setAutoIncrement(boolean autoIncrement) {
            this.autoIncrement = autoIncrement;
            if (autoIncrement) {
                this.nullable = false;
            }
        }

        public String defaultValue() {
            return defaultValue;
        }

        public void setDefaultValue(String defaultValue) {
            this.defaultValue = defaultValue == null ? "" : defaultValue.strip();
        }

        public String comment() {
            return comment;
        }

        public void setComment(String comment) {
            this.comment = comment == null ? "" : comment;
        }

        public boolean dropped() {
            return dropped;
        }

        public void setDropped(boolean dropped) {
            this.dropped = dropped;
        }
    }

    public static final class IndexDraft {
        private final String originalName;
        private final String snapshotName;
        private final boolean snapshotUnique;
        private final String snapshotColumns;
        private final String snapshotType;
        private String name;
        private boolean unique;
        private String columns;
        private String type;
        private boolean dropped;

        public IndexDraft(String originalName, String name, boolean unique, String columns) {
            this(originalName, name, unique, columns, "BTREE");
        }

        public IndexDraft(String originalName, String name, boolean unique, String columns, String type) {
            this.originalName = originalName;
            this.name = name == null ? "" : name;
            this.unique = unique;
            this.columns = columns == null ? "" : columns;
            this.type = type == null || type.isBlank() ? "BTREE" : type.strip().toUpperCase(Locale.ROOT);
            this.snapshotName = this.name;
            this.snapshotUnique = unique;
            this.snapshotColumns = this.columns;
            this.snapshotType = this.type;
        }

        public static IndexDraft added(String name) {
            return new IndexDraft(null, name, false, "");
        }

        public boolean added() {
            return originalName == null || originalName.isBlank();
        }

        public boolean modified() {
            if (added() || dropped) {
                return false;
            }
            return unique != snapshotUnique
                    || !normalizeCsv(columns).equalsIgnoreCase(normalizeCsv(snapshotColumns))
                    || !name.equalsIgnoreCase(snapshotName)
                    || !type.equalsIgnoreCase(snapshotType);
        }

        public String originalName() {
            return originalName;
        }

        public String name() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.strip();
        }

        public boolean unique() {
            return unique;
        }

        public void setUnique(boolean unique) {
            this.unique = unique;
        }

        public String columns() {
            return columns;
        }

        public void setColumns(String columns) {
            this.columns = columns == null ? "" : columns.strip();
        }

        public String type() {
            return type;
        }

        public void setType(String type) {
            this.type = type == null || type.isBlank() ? "BTREE" : type.strip().toUpperCase(Locale.ROOT);
        }

        public boolean dropped() {
            return dropped;
        }

        public void setDropped(boolean dropped) {
            this.dropped = dropped;
        }
    }

    public static final class FkDraft {
        private final String originalName;
        private final String snapshotName;
        private final String snapshotColumns;
        private final String snapshotRefTable;
        private final String snapshotRefColumns;
        private final String snapshotOnUpdate;
        private final String snapshotOnDelete;
        private String name;
        private String columns;
        private String refTable;
        private String refColumns;
        private String onUpdate;
        private String onDelete;
        private boolean dropped;

        public FkDraft(String originalName, String name, String columns, String refTable, String refColumns) {
            this(originalName, name, columns, refTable, refColumns, "NO ACTION", "NO ACTION");
        }

        public FkDraft(
                String originalName,
                String name,
                String columns,
                String refTable,
                String refColumns,
                String onUpdate,
                String onDelete) {
            this.originalName = originalName;
            this.name = name == null ? "" : name;
            this.columns = columns == null ? "" : columns;
            this.refTable = refTable == null ? "" : refTable;
            this.refColumns = refColumns == null ? "" : refColumns;
            this.onUpdate = normalizeAction(onUpdate);
            this.onDelete = normalizeAction(onDelete);
            this.snapshotName = this.name;
            this.snapshotColumns = this.columns;
            this.snapshotRefTable = this.refTable;
            this.snapshotRefColumns = this.refColumns;
            this.snapshotOnUpdate = this.onUpdate;
            this.snapshotOnDelete = this.onDelete;
        }

        public static FkDraft added(String name) {
            return new FkDraft(null, name, "", "", "id");
        }

        public boolean added() {
            return originalName == null || originalName.isBlank();
        }

        public boolean modified() {
            if (added() || dropped) {
                return false;
            }
            return !normalizeCsv(columns).equalsIgnoreCase(normalizeCsv(snapshotColumns))
                    || !refTable.equalsIgnoreCase(snapshotRefTable)
                    || !normalizeCsv(refColumns).equalsIgnoreCase(normalizeCsv(snapshotRefColumns))
                    || !name.equalsIgnoreCase(snapshotName)
                    || !onUpdate.equalsIgnoreCase(snapshotOnUpdate)
                    || !onDelete.equalsIgnoreCase(snapshotOnDelete);
        }

        public String originalName() {
            return originalName;
        }

        public String name() {
            return name;
        }

        public void setName(String name) {
            this.name = name == null ? "" : name.strip();
        }

        public String columns() {
            return columns;
        }

        public void setColumns(String columns) {
            this.columns = columns == null ? "" : columns.strip();
        }

        public String refTable() {
            return refTable;
        }

        public void setRefTable(String refTable) {
            this.refTable = refTable == null ? "" : refTable.strip();
        }

        public String refColumns() {
            return refColumns;
        }

        public void setRefColumns(String refColumns) {
            this.refColumns = refColumns == null ? "" : refColumns.strip();
        }

        public String onUpdate() {
            return onUpdate;
        }

        public void setOnUpdate(String onUpdate) {
            this.onUpdate = normalizeAction(onUpdate);
        }

        public String onDelete() {
            return onDelete;
        }

        public void setOnDelete(String onDelete) {
            this.onDelete = normalizeAction(onDelete);
        }

        public boolean dropped() {
            return dropped;
        }

        public void setDropped(boolean dropped) {
            this.dropped = dropped;
        }
    }

    private final String catalog;
    private final String tableName;
    private final List<ColumnDraft> originalColumns;
    private final List<IndexDraft> originalIndexes;
    private final List<FkDraft> originalForeignKeys;
    private final List<ColumnDraft> columns = new ArrayList<>();
    private final List<IndexDraft> indexes = new ArrayList<>();
    private final List<FkDraft> foreignKeys = new ArrayList<>();

    public TableDesignerModel(
            String catalog,
            String tableName,
            List<ColumnDraft> columns,
            List<IndexDraft> indexes,
            List<FkDraft> foreignKeys) {
        this.catalog = catalog == null ? "" : catalog;
        this.tableName = Objects.requireNonNullElse(tableName, "table");
        this.originalColumns = snapshotColumns(columns);
        this.originalIndexes = snapshotIndexes(indexes);
        this.originalForeignKeys = snapshotFks(foreignKeys);
        this.columns.addAll(columns == null ? List.of() : columns);
        this.indexes.addAll(indexes == null ? List.of() : indexes);
        this.foreignKeys.addAll(foreignKeys == null ? List.of() : foreignKeys);
    }

    public static TableDesignerModel from(SchemaNode table) {
        Objects.requireNonNull(table, "table");
        String catalog = table.metadata(SchemaNode.META_CATALOG);
        List<ColumnDraft> cols = new ArrayList<>();
        for (SchemaNode column : columnNodes(table)) {
            cols.add(new ColumnDraft(
                    column.name(),
                    column.name(),
                    Objects.requireNonNullElse(column.metadata(SchemaNode.META_DATA_TYPE), "INT"),
                    column.metadataFlag(SchemaNode.META_NULLABLE),
                    column.metadataFlag(SchemaNode.META_PRIMARY_KEY),
                    column.metadataFlag(SchemaNode.META_AUTO_INCREMENT),
                    Objects.requireNonNullElse(column.metadata(SchemaNode.META_DEFAULT), ""),
                    Objects.requireNonNullElse(column.metadata(SchemaNode.META_COMMENT), "")));
        }
        List<IndexDraft> idxs = new ArrayList<>();
        for (IndexInfo index : SchemaMetadataCodec.decodeIndexes(table.metadata(SchemaNode.META_INDEXES))) {
            if ("PRIMARY".equalsIgnoreCase(index.name())) {
                continue;
            }
            idxs.add(new IndexDraft(
                    index.name(),
                    index.name(),
                    index.unique(),
                    String.join(", ", index.columns()),
                    index.type()));
        }
        List<FkDraft> fks = new ArrayList<>();
        for (List<ForeignKey> group : groupForeignKeys(SchemaMetadataCodec.decodeForeignKeys(
                table.metadata(SchemaNode.META_FOREIGN_KEYS)))) {
            ForeignKey first = group.getFirst();
            String colsJoined = group.stream().map(ForeignKey::fkColumn).collect(Collectors.joining(", "));
            String refsJoined = group.stream().map(ForeignKey::pkColumn).collect(Collectors.joining(", "));
            fks.add(new FkDraft(
                    first.name(),
                    first.name(),
                    colsJoined,
                    first.pkTable(),
                    refsJoined,
                    first.onUpdate(),
                    first.onDelete()));
        }
        return new TableDesignerModel(catalog, table.name(), cols, idxs, fks);
    }

    public String catalog() {
        return catalog;
    }

    public String tableName() {
        return tableName;
    }

    public List<ColumnDraft> columns() {
        return columns;
    }

    public List<IndexDraft> indexes() {
        return indexes;
    }

    public List<FkDraft> foreignKeys() {
        return foreignKeys;
    }

    public ColumnDraft addColumn() {
        Set<String> used = new LinkedHashSet<>();
        for (ColumnDraft column : columns) {
            used.add(column.name().toLowerCase(Locale.ROOT));
        }
        String name = "new_column";
        int n = 2;
        while (used.contains(name.toLowerCase(Locale.ROOT))) {
            name = "new_column_" + n++;
        }
        ColumnDraft draft = ColumnDraft.added(name);
        columns.add(draft);
        return draft;
    }

    public void removeColumn(ColumnDraft column) {
        if (column == null) {
            return;
        }
        if (column.added()) {
            columns.remove(column);
        } else {
            column.setDropped(!column.dropped());
        }
    }

    public void moveColumn(int index, int delta) {
        int next = index + delta;
        if (index < 0 || next < 0 || index >= columns.size() || next >= columns.size()) {
            return;
        }
        ColumnDraft item = columns.remove(index);
        columns.add(next, item);
    }

    public IndexDraft addIndex() {
        IndexDraft draft = IndexDraft.added("idx_" + tableName.toLowerCase(Locale.ROOT));
        indexes.add(draft);
        return draft;
    }

    public void removeIndex(IndexDraft index) {
        if (index == null) {
            return;
        }
        if (index.added()) {
            indexes.remove(index);
        } else {
            index.setDropped(!index.dropped());
        }
    }

    public FkDraft addForeignKey() {
        FkDraft draft = FkDraft.added("fk_" + tableName.toLowerCase(Locale.ROOT));
        foreignKeys.add(draft);
        return draft;
    }

    public void removeForeignKey(FkDraft fk) {
        if (fk == null) {
            return;
        }
        if (fk.added()) {
            foreignKeys.remove(fk);
        } else {
            fk.setDropped(!fk.dropped());
        }
    }

    public boolean dirty() {
        return !alterStatements(Driver.MYSQL).isEmpty();
    }

    public String alterScript(Driver driver) {
        Driver d = driver == null ? Driver.MYSQL : driver;
        String qualified = TransferSql.qualify(catalog, tableName, d);
        List<String> statements = alterStatements(d);
        StringBuilder sql = new StringBuilder();
        sql.append("-- Modify table ").append(qualified).append('\n');
        sql.append("-- Review and run. This does not execute automatically.\n\n");
        if (statements.isEmpty()) {
            sql.append("-- No changes\n");
            return sql.toString();
        }
        for (String statement : statements) {
            sql.append(statement);
            if (!statement.endsWith(";")) {
                sql.append(';');
            }
            sql.append("\n\n");
        }
        return sql.toString().stripTrailing() + "\n";
    }

    List<String> alterStatements(Driver driver) {
        String table = TransferSql.qualify(catalog, tableName, driver);
        List<String> out = new ArrayList<>();

        Map<String, FkDraft> currentFks = byOriginalFk();
        Map<String, FkDraft> snapshotFks = snapshotFkMap();
        for (FkDraft original : originalForeignKeys) {
            FkDraft current = currentFks.get(key(original.originalName()));
            if (current == null || current.dropped() || fkChanged(original, current)) {
                out.add("ALTER TABLE " + table + " DROP FOREIGN KEY " + TransferSql.quote(original.originalName(), driver));
            }
        }

        Map<String, IndexDraft> currentIdx = byOriginalIndex();
        Map<String, IndexDraft> snapshotIdx = snapshotIndexMap();
        for (IndexDraft original : originalIndexes) {
            IndexDraft current = currentIdx.get(key(original.originalName()));
            if (current == null || current.dropped() || indexChanged(original, current)) {
                out.add("ALTER TABLE " + table + " DROP INDEX " + TransferSql.quote(original.originalName(), driver));
            }
        }

        String originalPk = pkList(originalColumns);
        String nextPk = pkList(columns);
        boolean pkChanged = !originalPk.equalsIgnoreCase(nextPk);
        if (pkChanged && !originalPk.isBlank()) {
            out.add("ALTER TABLE " + table + " DROP PRIMARY KEY");
        }

        Map<String, ColumnDraft> currentCols = byOriginalColumn();
        Map<String, ColumnDraft> snapshotCols = snapshotColumnMap();
        for (ColumnDraft original : originalColumns) {
            ColumnDraft current = currentCols.get(key(original.originalName()));
            if (current == null || current.dropped()) {
                out.add("ALTER TABLE " + table + " DROP COLUMN " + TransferSql.quote(original.originalName(), driver));
            }
        }

        String previousKept = null;
        for (ColumnDraft current : columns) {
            if (current.dropped() || current.name().isBlank()) {
                continue;
            }
            ColumnDraft snapshot = current.added() ? null : snapshotCols.get(key(current.originalName()));
            if (current.added()) {
                out.add("ALTER TABLE " + table + " ADD COLUMN " + columnDef(current, driver)
                        + afterClause(previousKept, driver));
            } else if (columnChanged(snapshot, current)) {
                boolean renamed = snapshot != null && !snapshot.originalName().equalsIgnoreCase(current.name());
                if (renamed) {
                    out.add("ALTER TABLE " + table + " CHANGE COLUMN "
                            + TransferSql.quote(current.originalName(), driver) + " "
                            + columnDef(current, driver));
                } else {
                    out.add("ALTER TABLE " + table + " MODIFY COLUMN " + columnDef(current, driver));
                }
            }
            previousKept = current.name();
        }

        if (pkChanged && !nextPk.isBlank()) {
            out.add("ALTER TABLE " + table + " ADD PRIMARY KEY (" + quoteList(nextPk, driver) + ")");
        }

        for (IndexDraft current : indexes) {
            if (current.dropped() || current.name().isBlank() || current.columns().isBlank()) {
                continue;
            }
            IndexDraft snapshot = current.added() ? null : snapshotIdx.get(key(current.originalName()));
            if (snapshot == null || indexChanged(snapshot, current)) {
                String unique = current.unique() ? "UNIQUE " : "";
                String using = usingClause(current.type());
                out.add("ALTER TABLE " + table + " ADD " + unique + "INDEX "
                        + TransferSql.quote(current.name(), driver)
                        + " (" + quoteList(current.columns(), driver) + ")" + using);
            }
        }

        for (FkDraft current : foreignKeys) {
            if (current.dropped() || current.columns().isBlank() || current.refTable().isBlank()) {
                continue;
            }
            FkDraft snapshot = current.added() ? null : snapshotFks.get(key(current.originalName()));
            if (snapshot == null || fkChanged(snapshot, current)) {
                String cname = current.name().isBlank() ? "fk_" + tableName : current.name();
                out.add("ALTER TABLE " + table + " ADD CONSTRAINT " + TransferSql.quote(cname, driver)
                        + " FOREIGN KEY (" + quoteList(current.columns(), driver) + ") REFERENCES "
                        + TransferSql.quote(current.refTable(), driver)
                        + " (" + quoteList(current.refColumns().isBlank() ? "id" : current.refColumns(), driver) + ")"
                        + referentialClause(current));
            }
        }
        return out;
    }

    private static String afterClause(String previous, Driver driver) {
        if (previous == null || previous.isBlank()) {
            return "";
        }
        return " AFTER " + TransferSql.quote(previous, driver);
    }

    private static String columnDef(ColumnDraft column, Driver driver) {
        String type = column.dataType().isBlank() ? "INT" : column.dataType();
        boolean notNull = column.autoIncrement() || column.primaryKey() || !column.nullable();
        StringBuilder sql = new StringBuilder();
        sql.append(TransferSql.quote(column.name(), driver)).append(' ').append(type);
        sql.append(notNull ? " NOT NULL" : " NULL");
        String defaultSql = defaultClause(column.defaultValue());
        if (!defaultSql.isEmpty()) {
            sql.append(' ').append(defaultSql);
        }
        if (column.autoIncrement()) {
            sql.append(" AUTO_INCREMENT");
        }
        if (!column.comment().isBlank()) {
            sql.append(" COMMENT '").append(column.comment().replace("'", "''")).append('\'');
        }
        return sql.toString();
    }

    private static String defaultClause(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.strip();
        if (isBareDefault(value)) {
            return "DEFAULT " + value;
        }
        return "DEFAULT '" + value.replace("'", "''") + "'";
    }

    private static boolean isBareDefault(String value) {
        String upper = value.toUpperCase(Locale.ROOT);
        if ("NULL".equals(upper) || "TRUE".equals(upper) || "FALSE".equals(upper)
                || "CURRENT_TIMESTAMP".equals(upper) || "CURRENT_DATE".equals(upper)
                || "CURRENT_TIME".equals(upper) || "NOW()".equals(upper)) {
            return true;
        }
        if (upper.startsWith("CURRENT_TIMESTAMP(") || upper.startsWith("NOW(")) {
            return true;
        }
        if ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("(") && value.endsWith(")"))) {
            return true;
        }
        return value.matches("-?\\d+(\\.\\d+)?");
    }

    private static String usingClause(String type) {
        if (type == null || type.isBlank() || "BTREE".equalsIgnoreCase(type)) {
            return "";
        }
        return " USING " + type.strip().toUpperCase(Locale.ROOT);
    }

    private static String referentialClause(FkDraft fk) {
        StringBuilder sql = new StringBuilder();
        if (isEmittedAction(fk.onDelete())) {
            sql.append(" ON DELETE ").append(fk.onDelete());
        }
        if (isEmittedAction(fk.onUpdate())) {
            sql.append(" ON UPDATE ").append(fk.onUpdate());
        }
        return sql.toString();
    }

    private static boolean isEmittedAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        return !"NO ACTION".equalsIgnoreCase(action);
    }

    static String normalizeAction(String action) {
        if (action == null || action.isBlank()) {
            return "NO ACTION";
        }
        String normalized = action.strip().replace('_', ' ').toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "CASCADE", "RESTRICT", "SET NULL", "SET DEFAULT", "NO ACTION" -> normalized;
            default -> "NO ACTION";
        };
    }

    private static String quoteList(String csv, Driver driver) {
        String[] parts = csv.split(",");
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            String name = part.strip();
            if (!name.isEmpty()) {
                quoted.add(TransferSql.quote(name, driver));
            }
        }
        return String.join(", ", quoted);
    }

    private static boolean columnChanged(ColumnDraft original, ColumnDraft current) {
        if (original == null || current == null || current.dropped()) {
            return false;
        }
        return !original.originalName().equalsIgnoreCase(current.name())
                || !original.dataType().equalsIgnoreCase(current.dataType())
                || original.nullable() != current.nullable()
                || original.autoIncrement() != current.autoIncrement()
                || !original.defaultValue().equals(current.defaultValue())
                || !original.comment().equals(current.comment());
    }

    private static boolean indexChanged(IndexDraft original, IndexDraft current) {
        if (original == null || current == null) {
            return true;
        }
        return original.unique() != current.unique()
                || !normalizeCsv(original.columns()).equalsIgnoreCase(normalizeCsv(current.columns()))
                || !original.originalName().equalsIgnoreCase(current.name())
                || !original.type().equalsIgnoreCase(current.type());
    }

    private static boolean fkChanged(FkDraft original, FkDraft current) {
        if (original == null || current == null) {
            return true;
        }
        return !normalizeCsv(original.columns()).equalsIgnoreCase(normalizeCsv(current.columns()))
                || !original.refTable().equalsIgnoreCase(current.refTable())
                || !normalizeCsv(original.refColumns()).equalsIgnoreCase(normalizeCsv(current.refColumns()))
                || !original.originalName().equalsIgnoreCase(current.name())
                || !original.onUpdate().equalsIgnoreCase(current.onUpdate())
                || !original.onDelete().equalsIgnoreCase(current.onDelete());
    }

    private static String normalizeCsv(String csv) {
        if (csv == null) {
            return "";
        }
        return java.util.Arrays.stream(csv.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(","));
    }

    private static String pkList(List<ColumnDraft> cols) {
        List<String> names = new ArrayList<>();
        for (ColumnDraft column : cols) {
            if (column.dropped()) {
                continue;
            }
            if (column.primaryKey()) {
                names.add(column.name());
            }
        }
        return String.join(", ", names);
    }

    private Map<String, ColumnDraft> byOriginalColumn() {
        return mapByOriginalName(columns, ColumnDraft::added, ColumnDraft::originalName);
    }

    private Map<String, IndexDraft> byOriginalIndex() {
        return mapByOriginalName(indexes, IndexDraft::added, IndexDraft::originalName);
    }

    private Map<String, FkDraft> byOriginalFk() {
        return mapByOriginalName(foreignKeys, FkDraft::added, FkDraft::originalName);
    }

    private Map<String, ColumnDraft> snapshotColumnMap() {
        return mapByOriginalName(originalColumns, ColumnDraft::added, ColumnDraft::originalName);
    }

    private Map<String, IndexDraft> snapshotIndexMap() {
        return mapByOriginalName(originalIndexes, IndexDraft::added, IndexDraft::originalName);
    }

    private Map<String, FkDraft> snapshotFkMap() {
        return mapByOriginalName(originalForeignKeys, FkDraft::added, FkDraft::originalName);
    }

    private static <T> Map<String, T> mapByOriginalName(
            List<T> items, Predicate<T> added, Function<T, String> originalName) {
        Map<String, T> map = new LinkedHashMap<>();
        for (T item : items) {
            if (!added.test(item)) {
                map.put(key(originalName.apply(item)), item);
            }
        }
        return map;
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }

    private static List<ColumnDraft> snapshotColumns(List<ColumnDraft> source) {
        List<ColumnDraft> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (ColumnDraft column : source) {
            copy.add(new ColumnDraft(
                    column.originalName(),
                    column.name(),
                    column.dataType(),
                    column.nullable(),
                    column.primaryKey(),
                    column.autoIncrement(),
                    column.defaultValue(),
                    column.comment()));
        }
        return copy;
    }

    private static List<IndexDraft> snapshotIndexes(List<IndexDraft> source) {
        List<IndexDraft> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (IndexDraft index : source) {
            copy.add(new IndexDraft(
                    index.originalName(), index.name(), index.unique(), index.columns(), index.type()));
        }
        return copy;
    }

    private static List<FkDraft> snapshotFks(List<FkDraft> source) {
        List<FkDraft> copy = new ArrayList<>();
        if (source == null) {
            return copy;
        }
        for (FkDraft fk : source) {
            copy.add(new FkDraft(
                    fk.originalName(),
                    fk.name(),
                    fk.columns(),
                    fk.refTable(),
                    fk.refColumns(),
                    fk.onUpdate(),
                    fk.onDelete()));
        }
        return copy;
    }

    public static List<SchemaNode> columnNodes(SchemaNode table) {
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

    static List<List<ForeignKey>> groupForeignKeys(List<ForeignKey> keys) {
        if (keys == null || keys.isEmpty()) {
            return List.of();
        }
        Map<String, List<ForeignKey>> grouped = new LinkedHashMap<>();
        int unnamed = 0;
        for (ForeignKey fk : keys) {
            String name = fk.name() == null ? "" : fk.name().strip();
            String pkTable = fk.pkTable() == null ? "" : fk.pkTable();
            String groupKey = name.isEmpty() ? "__unnamed_" + unnamed++ + "\0" + pkTable : name + "\0" + pkTable;
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(fk);
        }
        return List.copyOf(grouped.values());
    }
}
