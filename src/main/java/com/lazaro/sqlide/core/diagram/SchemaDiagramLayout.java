package com.lazaro.sqlide.core.diagram;

import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Edge;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Table;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Layered ER layout: referenced (PK) tables sit above FK owners, with stable
 * left-to-right ordering inside each rank.
 */
public final class SchemaDiagramLayout {

    private static final double H_GAP = 48;
    private static final double V_GAP = 64;
    private static final double MIN_WIDTH = 180;
    private static final double CHAR_WIDTH = 7.4;
    private static final double HEADER_HEIGHT = 28;
    private static final double ROW_HEIGHT = 18;
    public static final int MAX_VISIBLE_COLUMNS = 14;
    private static final double PADDING_X = 40;
    private static final double PADDING_Y = 40;

    private SchemaDiagramLayout() {
    }

    public static SchemaDiagramModel layout(SchemaDiagramModel model) {
        if (model == null || model.tables().isEmpty()) {
            return model == null
                    ? new SchemaDiagramModel("", null, List.of(), List.of())
                    : model;
        }

        Map<String, Table> sized = new LinkedHashMap<>();
        for (Table table : model.tables()) {
            sized.put(table.id(), withMeasuredSize(table));
        }

        Map<String, Integer> rank = assignRanks(sized, model.edges());
        Map<Integer, List<Table>> layers = new LinkedHashMap<>();
        for (Table table : sized.values()) {
            int r = rank.getOrDefault(table.id(), 0);
            layers.computeIfAbsent(r, key -> new ArrayList<>()).add(table);
        }

        List<Integer> sortedRanks = new ArrayList<>(layers.keySet());
        sortedRanks.sort(Integer::compareTo);

        List<Table> placed = new ArrayList<>();
        double y = PADDING_Y;
        for (int r : sortedRanks) {
            List<Table> layer = layers.get(r);
            layer.sort(Comparator.comparing(Table::name, String.CASE_INSENSITIVE_ORDER));
            double x = PADDING_X;
            double maxHeight = 0;
            for (Table table : layer) {
                placed.add(table.withBounds(x, y, table.width(), table.height()));
                x += table.width() + H_GAP;
                maxHeight = Math.max(maxHeight, table.height());
            }
            y += maxHeight + V_GAP;
        }

        return model.withTables(placed);
    }

    /** Overlay previously saved {@code tableId → [x, y]} positions onto an already-laid-out model. */
    public static SchemaDiagramModel applyPositions(SchemaDiagramModel model, Map<String, double[]> positions) {
        if (model == null || positions == null || positions.isEmpty()) {
            return model;
        }
        List<Table> next = new ArrayList<>(model.tables().size());
        boolean any = false;
        for (Table table : model.tables()) {
            double[] xy = positions.get(table.id());
            if (xy != null && xy.length >= 2 && Double.isFinite(xy[0]) && Double.isFinite(xy[1])) {
                next.add(table.withBounds(xy[0], xy[1], table.width(), table.height()));
                any = true;
            } else {
                next.add(table);
            }
        }
        return any ? model.withTables(next) : model;
    }

    public static Map<String, double[]> positionsOf(SchemaDiagramModel model) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        if (model == null) {
            return positions;
        }
        for (Table table : model.tables()) {
            positions.put(table.id(), new double[] {table.x(), table.y()});
        }
        return positions;
    }

    /** Re-run layered layout, discarding current coordinates but keeping graph membership. */
    public static SchemaDiagramModel relayout(SchemaDiagramModel model) {
        if (model == null) {
            return new SchemaDiagramModel("", null, List.of(), List.of());
        }
        List<Table> reset = new ArrayList<>(model.tables().size());
        for (Table table : model.tables()) {
            reset.add(table.withBounds(0, 0, 0, 0));
        }
        return layout(model.withTables(reset));
    }

    public static Table withMeasuredSize(Table table) {
        return withMeasuredSize(table, MAX_VISIBLE_COLUMNS);
    }

    public static Table withMeasuredSize(Table table, int maxVisibleColumns) {
        int cap = maxVisibleColumns < 0 ? table.columns().size() : maxVisibleColumns;
        int visible = Math.min(table.columns().size(), cap);
        int nameLen = table.name().length();
        int typeLen = 0;
        for (int i = 0; i < visible; i++) {
            var column = table.columns().get(i);
            nameLen = Math.max(nameLen, column.name().length() + 2);
            typeLen = Math.max(typeLen, column.dataType().length());
        }
        double width = Math.max(MIN_WIDTH, (nameLen + typeLen) * CHAR_WIDTH + 36);
        double height = HEADER_HEIGHT + Math.max(1, visible) * ROW_HEIGHT + 8;
        if (table.columns().size() > MAX_VISIBLE_COLUMNS) {
            height += ROW_HEIGHT;
        }
        return table.withBounds(0, 0, width, height);
    }

    /**
     * Rank increases along FK ownership: referenced PK tables get lower ranks
     * (drawn higher). Self-loops and cycles are tolerated via a bounded relax.
     */
    static Map<String, Integer> assignRanks(Map<String, Table> tables, List<Edge> edges) {
        Map<String, Integer> rank = new HashMap<>();
        for (String id : tables.keySet()) {
            rank.put(id, 0);
        }
        // from = FK owner (child), to = PK table (parent)
        for (int pass = 0; pass < tables.size() + 2; pass++) {
            boolean changed = false;
            for (Edge edge : edges) {
                if (!rank.containsKey(edge.fromTableId()) || !rank.containsKey(edge.toTableId())) {
                    continue;
                }
                int parent = rank.get(edge.toTableId());
                int child = rank.get(edge.fromTableId());
                int next = parent + 1;
                if (child < next) {
                    rank.put(edge.fromTableId(), next);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return rank;
    }
}
