package com.lazaro.sqlide.core.db;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One entry in a data source's structure tree.
 *
 * <p>Deliberately generic: a relational catalog, a table, a column, and one day a
 * Redis keyspace all share this shape. Anything type-specific lives in
 * {@link #metadata()} rather than in extra fields, which keeps the UI able to
 * render any driver's tree without knowing what produced it.
 *
 * <p>{@code children} is empty for nodes that have not been expanded yet; ask the
 * driver for children rather than assuming an empty list means a leaf. Use
 * {@link #isLeaf()} to distinguish the two.
 *
 * @param name     display name, unique among its siblings
 * @param type     what kind of object this is
 * @param children already-loaded children, possibly empty
 * @param metadata extra attributes such as {@code type} or {@code nullable}
 */
public record SchemaNode(String name, NodeType type, List<SchemaNode> children, Map<String, String> metadata) {

    /** Metadata key holding a column's rendered SQL type, e.g. {@code VARCHAR(255)}. */
    public static final String META_DATA_TYPE = "dataType";
    /** Metadata key, {@code "true"} when a column accepts NULL. */
    public static final String META_NULLABLE = "nullable";
    /** Metadata key, {@code "true"} when a column is part of the primary key. */
    public static final String META_PRIMARY_KEY = "primaryKey";
    /** Metadata key holding the owning catalog of a table. */
    public static final String META_CATALOG = "catalog";
    /** Metadata key holding the raw JDBC table type, e.g. {@code BASE TABLE}. */
    public static final String META_TABLE_TYPE = "tableType";
    /**
     * Encoded foreign keys on a table node:
     * {@code name|fkColumn|pkTable|pkColumn} entries joined by {@code ;}.
     */
    public static final String META_FOREIGN_KEYS = "foreignKeys";
    /**
     * Encoded indexes on a table node:
     * {@code name|UNIQUE|col1,col2} entries joined by {@code ;}.
     */
    public static final String META_INDEXES = "indexes";
    /** Generated {@code CREATE TABLE}/{@code CREATE VIEW} DDL for the object viewer. */
    public static final String META_DDL = "ddl";
    /** Metadata key holding a {@link ConnectionProfile} id on a {@link NodeType#DATA_SOURCE}. */
    public static final String META_PROFILE_ID = "profileId";
    /** Metadata key, {@code "true"} when this data source is the live session. */
    public static final String META_ACTIVE = "active";
    /** Metadata key, {@code "true"} for an unsaved ephemeral session root. */
    public static final String META_SESSION = "session";
    /**
     * Logical folder kind under a database or table:
     * {@code tables}, {@code views}, {@code procedures}, {@code columns}, {@code keys}, {@code indexes}.
     */
    public static final String META_FOLDER_KIND = "folderKind";
    /** {@code procedure} or {@code function} on a {@link NodeType#PROCEDURE} node. */
    public static final String META_ROUTINE_KIND = "routineKind";
    public static final String ROUTINE_PROCEDURE = "procedure";
    public static final String ROUTINE_FUNCTION = "function";
    /** Child count shown muted next to a folder label. */
    public static final String META_CHILD_COUNT = "childCount";
    /** Comma-separated column list for a key or index, e.g. {@code id,name}. */
    public static final String META_COLUMNS = "columns";
    /** {@code true} when an index is unique. */
    public static final String META_UNIQUE = "unique";
    /** Owning table name for a folder / key / index under a table. */
    public static final String META_TABLE = "table";
    /** Key kind: {@code PRIMARY} or {@code FOREIGN}. */
    public static final String META_KEY_KIND = "keyKind";

    public static final String FOLDER_TABLES = "tables";
    public static final String FOLDER_VIEWS = "views";
    public static final String FOLDER_PROCEDURES = "procedures";
    public static final String FOLDER_COLUMNS = "columns";
    public static final String FOLDER_KEYS = "keys";
    public static final String FOLDER_INDEXES = "indexes";
    /** Namespace folder in a Redis key tree ({@code cache:session:…}). */
    public static final String FOLDER_REDIS = "redis-namespace";
    /** Full Redis key on a {@link NodeType#REDIS_KEY} (and optionally a namespace folder). */
    public static final String META_REDIS_KEY = "redisKey";
    /** Redis TYPE string: {@code string}, {@code hash}, {@code list}, {@code set}, … */
    public static final String META_REDIS_TYPE = "redisType";
    /** Logical Redis database index ({@code 0}…{@code 15}) on a database or key node. */
    public static final String META_REDIS_DB = "redisDb";
    /** {@link com.lazaro.sqlide.core.db.ConnectionConfig.ConnectionType} name on a data source. */
    public static final String META_CONNECTION_TYPE = "connectionType";
    /** {@link com.lazaro.sqlide.core.db.ConnectionConfig.Driver} name on a data source. */
    public static final String META_DRIVER = "driver";

    public enum NodeType {
        /** Saved / session data source root in the Database pane. */
        DATA_SOURCE,
        DATABASE,
        SCHEMA,
        /** Virtual grouping folder (tables / columns / keys / indexes). */
        FOLDER,
        TABLE,
        VIEW,
        /** Stored procedure or function (see {@link #META_ROUTINE_KIND}). */
        PROCEDURE,
        /** Redis key (leaf). Namespace prefixes use {@link #FOLDER}. */
        REDIS_KEY,
        COLUMN,
        /** Primary or foreign key under a table's keys folder. */
        KEY,
        /** Index under a table's indexes folder. */
        INDEX;

        /** Whether nodes of this kind can ever contain children. */
        public boolean isContainer() {
            return this != COLUMN && this != KEY && this != INDEX && this != PROCEDURE && this != REDIS_KEY;
        }
    }

    public SchemaNode {
        name = Objects.requireNonNull(name, "name must not be null");
        type = Objects.requireNonNull(type, "type must not be null");
        children = List.copyOf(Objects.requireNonNullElse(children, List.of()));
        metadata = Map.copyOf(Objects.requireNonNullElse(metadata, Map.of()));
    }

    public static SchemaNode of(String name, NodeType type) {
        return new SchemaNode(name, type, List.of(), Map.of());
    }

    public static SchemaNode of(String name, NodeType type, Map<String, String> metadata) {
        return new SchemaNode(name, type, List.of(), metadata);
    }

    /** Virtual folder with a muted child-count badge. */
    public static SchemaNode folder(String label, String kind, int childCount, Map<String, String> extra) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (extra != null) {
            metadata.putAll(extra);
        }
        metadata.put(META_FOLDER_KIND, kind);
        metadata.put(META_CHILD_COUNT, Integer.toString(Math.max(0, childCount)));
        return of(label, NodeType.FOLDER, metadata);
    }

    /** Primary / foreign key leaf shown as {@code name (cols)}. */
    public static SchemaNode key(String name, String kind, List<String> columns, Map<String, String> extra) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (extra != null) {
            metadata.putAll(extra);
        }
        metadata.put(META_KEY_KIND, kind);
        metadata.put(META_COLUMNS, String.join(",", columns == null ? List.of() : columns));
        return of(name, NodeType.KEY, metadata);
    }

    /** Index leaf shown as {@code name (cols) [UNIQUE]}. */
    public static SchemaNode index(String name, boolean unique, List<String> columns, Map<String, String> extra) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (extra != null) {
            metadata.putAll(extra);
        }
        metadata.put(META_UNIQUE, Boolean.toString(unique));
        metadata.put(META_COLUMNS, String.join(",", columns == null ? List.of() : columns));
        return of(name, NodeType.INDEX, metadata);
    }

    public SchemaNode withChildren(List<SchemaNode> newChildren) {
        return new SchemaNode(name, type, newChildren, metadata);
    }

    /** True when this kind of node can never have children, regardless of what is loaded. */
    public boolean isLeaf() {
        return !type.isContainer();
    }

    public String metadata(String key) {
        return metadata.get(key);
    }

    public boolean metadataFlag(String key) {
        return Boolean.parseBoolean(metadata.get(key));
    }

    public int childCountBadge() {
        String raw = metadata.get(META_CHILD_COUNT);
        if (raw == null || raw.isBlank()) {
            return children.size();
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return children.size();
        }
    }

    public String folderKind() {
        return metadata.get(META_FOLDER_KIND);
    }

    /** Name usable in a statement, e.g. {@code sales.orders}, when the catalog is known. */
    public String qualifiedName() {
        String catalog = metadata.get(META_CATALOG);
        return catalog == null || catalog.isBlank() ? name : catalog + "." + name;
    }
}
