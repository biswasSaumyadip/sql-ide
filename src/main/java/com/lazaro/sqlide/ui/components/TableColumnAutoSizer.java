package com.lazaro.sqlide.ui.components;

import javafx.scene.control.TableColumn;

import java.util.List;

/**
 * Prefers content-aware column widths for result grids (DataGrip-style), without
 * {@code CONSTRAINED_RESIZE_POLICY} flattening columns to equal shares.
 */
public final class TableColumnAutoSizer {

    private static final double CHAR_WIDTH = 8.4;
    private static final double CELL_PADDING = 20;   // 8px + 8px + grid borders
    private static final double HEADER_EXTRA = 44;   // sort arrow / type badge / key icons
    private static final double MIN_WIDTH = 56;
    private static final double MAX_WIDTH = 480;
    private static final int SAMPLE_ROWS = 120;

    private TableColumnAutoSizer() {
    }

    /**
     * Sets {@code column}'s preferred width from its header and sampled cell text.
     *
     * @param columnIndex index into each row list (not the TableView column index)
     */
    public static void apply(
            TableColumn<?, ?> column,
            String header,
            List<? extends List<String>> rows,
            int columnIndex) {
        if (column == null) {
            return;
        }
        column.setPrefWidth(estimate(header, rows, columnIndex));
        column.setMinWidth(MIN_WIDTH);
    }

    /** Width for the leading {@code #} row-number column. */
    public static double rowNumberWidth(int rowCount) {
        int digits = Math.max(2, String.valueOf(Math.max(rowCount, 1)).length());
        return Math.clamp(digits * CHAR_WIDTH + CELL_PADDING + 8, 44, 72);
    }

    public static double estimate(String header, List<? extends List<String>> rows, int columnIndex) {
        int widestChars = displayLength(header);
        // Headers often render slightly wider (weight / sort glyph).
        widestChars = Math.max(widestChars, (int) Math.ceil(displayLength(header) * 1.05));

        if (rows != null && !rows.isEmpty()) {
            int sampled = Math.min(rows.size(), SAMPLE_ROWS);
            for (int i = 0; i < sampled; i++) {
                List<String> row = rows.get(i);
                if (row == null || columnIndex < 0 || columnIndex >= row.size()) {
                    continue;
                }
                widestChars = Math.max(widestChars, displayLength(row.get(columnIndex)));
            }
        }

        double width = widestChars * CHAR_WIDTH + CELL_PADDING + HEADER_EXTRA;
        return Math.clamp(width, MIN_WIDTH, MAX_WIDTH);
    }

    private static int displayLength(String value) {
        if (value == null) {
            return 4; // "NULL"
        }
        // Treat tabs/newlines as wider so wrapped-ish values aren't clipped early.
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            length += (ch == '\t') ? 4 : 1;
        }
        return length;
    }
}
