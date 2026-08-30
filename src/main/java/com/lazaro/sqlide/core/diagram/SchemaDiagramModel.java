package com.lazaro.sqlide.core.diagram;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Immutable ER graph used by the schema diagram UI.
 */
public final class SchemaDiagramModel {

    public enum Cardinality {
        ONE("1"),
        ZERO_OR_ONE("0..1"),
        ONE_OR_MANY("1..n"),
        ZERO_OR_MANY("0..n");

        private final String label;

        Cardinality(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Column(String name, String dataType, boolean primaryKey, boolean foreignKey, boolean nullable) {
        public Column {
            Objects.requireNonNull(name, "name");
            dataType = dataType == null ? "" : dataType;
        }
    }

    public record ColumnPair(String fkColumn, String pkColumn) {
        public ColumnPair {
            fkColumn = fkColumn == null ? "" : fkColumn;
            pkColumn = pkColumn == null ? "" : pkColumn;
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
     * Relationship from the table that owns the FK column(s) toward the referenced PK table.
     * Composite keys are one edge with several {@link ColumnPair}s.
     */
    public record Edge(
            String id,
            String fromTableId,
            String toTableId,
            List<ColumnPair> columns,
            String name,
            Cardinality fromCardinality,
            Cardinality toCardinality) {
        public Edge {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(fromTableId, "fromTableId");
            Objects.requireNonNull(toTableId, "toTableId");
            columns = List.copyOf(Objects.requireNonNullElse(columns, List.of()));
            name = name == null ? "" : name;
            fromCardinality = fromCardinality == null ? Cardinality.ZERO_OR_MANY : fromCardinality;
            toCardinality = toCardinality == null ? Cardinality.ONE : toCardinality;
        }

        public String fkColumn() {
            if (columns.isEmpty()) {
                return "";
            }
            if (columns.size() == 1) {
                return columns.getFirst().fkColumn();
            }
            return columns.stream().map(ColumnPair::fkColumn).collect(Collectors.joining(","));
        }

        public String pkColumn() {
            if (columns.isEmpty()) {
                return "";
            }
            if (columns.size() == 1) {
                return columns.getFirst().pkColumn();
            }
            return columns.stream().map(ColumnPair::pkColumn).collect(Collectors.joining(","));
        }

        public String columnSummary() {
            return columns.stream()
                    .map(pair -> pair.fkColumn() + " \u2192 " + pair.pkColumn())
                    .collect(Collectors.joining(", "));
        }

        public boolean composite() {
            return columns.size() > 1;
        }
    }

    private final String catalog;
    private final String focusTableId;
    private final List<Table> tables;
    private final List<Edge> edges;
    private final int availableTableCount;

    public SchemaDiagramModel(String catalog, String focusTableId, List<Table> tables, List<Edge> edges) {
        this(catalog, focusTableId, tables, edges, tables == null ? 0 : tables.size());
    }

    public SchemaDiagramModel(
            String catalog,
            String focusTableId,
            List<Table> tables,
            List<Edge> edges,
            int availableTableCount) {
        this.catalog = catalog == null ? "" : catalog;
        this.focusTableId = focusTableId;
        this.tables = List.copyOf(Objects.requireNonNullElse(tables, List.of()));
        this.edges = List.copyOf(Objects.requireNonNullElse(edges, List.of()));
        this.availableTableCount = Math.max(availableTableCount, this.tables.size());
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

    /** Tables that exist in the catalog/neighborhood before the diagram cap. */
    public int availableTableCount() {
        return availableTableCount;
    }

    public boolean truncated() {
        return availableTableCount > tables.size();
    }

    public SchemaDiagramModel withTables(List<Table> next) {
        return new SchemaDiagramModel(catalog, focusTableId, next, edges, availableTableCount);
    }
}
