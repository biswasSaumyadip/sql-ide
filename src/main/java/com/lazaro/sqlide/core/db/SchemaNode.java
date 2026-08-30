package com.lazaro.sqlide.core.db;

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

    public enum NodeType {
        /** Saved / session data source root in the Database pane. */
        DATA_SOURCE,
        DATABASE,
        SCHEMA,
        TABLE,
        VIEW,
        COLUMN;

        /** Whether nodes of this kind can ever contain children. */
        public boolean isContainer() {
            return this != COLUMN;
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

    /** Name usable in a statement, e.g. {@code sales.orders}, when the catalog is known. */
    public String qualifiedName() {
        String catalog = metadata.get(META_CATALOG);
        return catalog == null || catalog.isBlank() ? name : catalog + "." + name;
    }
}
