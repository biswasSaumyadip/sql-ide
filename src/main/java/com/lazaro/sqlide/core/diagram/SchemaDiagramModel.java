package com.lazaro.sqlide.core.diagram;

import java.util.List;
import java.util.Objects;

/**
 * Immutable ER graph used by the schema diagram UI.
 */
public final class SchemaDiagramModel {

    public record Column(String name, String dataType, boolean primaryKey, boolean foreignKey) {
        public Column {
            Objects.requireNonNull(name, "name");
            dataType = dataType == null ? "" : dataType;
        }
    }

    public record Table(
            String id,
            String name,
            String catalog,
            boolean view,
            List<Column> columns,
            double x,
            double y,
            double width,
            double height) {
        public Table {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            catalog = catalog == null ? "" : catalog;
            columns = List.copyOf(Objects.requireNonNullElse(columns, List.of()));
        }

        public Table withBounds(double x, double y, double width, double height) {
            return new Table(id, name, catalog, view, columns, x, y, width, height);
        }
    }

    /**
     * Relationship from the table that owns the FK column toward the referenced PK table.
     */
    public record Edge(
            String id,
            String fromTableId,
            String toTableId,
            String fkColumn,
            String pkColumn,
            String name) {
        public Edge {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(fromTableId, "fromTableId");
            Objects.requireNonNull(toTableId, "toTableId");
            fkColumn = fkColumn == null ? "" : fkColumn;
            pkColumn = pkColumn == null ? "" : pkColumn;
            name = name == null ? "" : name;
        }
    }

    private final String catalog;
    private final String focusTableId;
    private final List<Table> tables;
    private final List<Edge> edges;

    public SchemaDiagramModel(String catalog, String focusTableId, List<Table> tables, List<Edge> edges) {
        this.catalog = catalog == null ? "" : catalog;
        this.focusTableId = focusTableId;
        this.tables = List.copyOf(Objects.requireNonNullElse(tables, List.of()));
        this.edges = List.copyOf(Objects.requireNonNullElse(edges, List.of()));
    }

    public String catalog() {
        return catalog;
    }

    public String focusTableId() {
        return focusTableId;
    }

    public List<Table> tables() {
        return tables;
    }

    public List<Edge> edges() {
        return edges;
    }

    public SchemaDiagramModel withTables(List<Table> next) {
        return new SchemaDiagramModel(catalog, focusTableId, next, edges);
    }
}
