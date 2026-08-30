package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.JdbcSqlDriver;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.ResultSetMapper;
import com.lazaro.sqlide.core.export.ResultExporter;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Grid that shapes itself around whatever a query returns, building its columns
 * from the result metadata instead of a fixed model.
 *
 * <p>Rows are held as {@code ObservableList<String>} where a {@code null} element
 * means SQL {@code NULL}, rendered distinctly from an empty string.
 */
public final class DynamicResultTable extends TableView<ObservableList<String>> {

    private static final String PLACEHOLDER_IDLE = "Run a query to see results.";
    private static final int MIN_COLUMN_WIDTH = 70;
    private static final int MAX_COLUMN_WIDTH = 420;
    private static final int PIXELS_PER_CHARACTER = 8;
    private static final int COLUMN_PADDING = 28;
    private static final int WIDTH_SAMPLE_ROWS = 60;

    /** Active while no columns exist, so the empty header strip can be hidden. */
    private static final PseudoClass EMPTY_GRID = PseudoClass.getPseudoClass("empty-grid");

    private final Label placeholder = new Label(PLACEHOLDER_IDLE);
    private final ObservableList<ObservableList<String>> allRows = FXCollections.observableArrayList();
    private QueryResult currentResult;
    private String rowFilter = "";
    private Consumer<QueryResult> onExportToFile = result -> { };
    private TableDataEditSession editSession;

    public DynamicResultTable() {
        getStyleClass().add("result-table");
        getStylesheets().add(stylesheet());

        placeholder.getStyleClass().add("result-placeholder");
        setPlaceholder(placeholder);

        pseudoClassStateChanged(EMPTY_GRID, true);
        getColumns().addListener((ListChangeListener<TableColumn<ObservableList<String>, ?>>) change ->
                pseudoClassStateChanged(EMPTY_GRID, getColumns().isEmpty()));

        setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        getSelectionModel().setCellSelectionEnabled(true);
        getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        setContextMenu(buildExportMenu());
    }

    /** Live backing store used by both read-only results and data-edit mode. */
    ObservableList<ObservableList<String>> backingRows() {
        return allRows;
    }

    /** Row-number column shared with {@link TableDataEditSession}. */
    TableColumn<ObservableList<String>, Void> createRowNumberColumn() {
        return rowNumberColumn();
    }

    /**
     * Marks the grid as holding an editable table-data result so export/copy use
     * live rows (including unsaved inserts).
     */
    void beginEditPresent(QueryResult result) {
        Objects.requireNonNull(result, "result must not be null");
        currentResult = result;
        rowFilter = "";
        placeholder.setText(PLACEHOLDER_IDLE);
    }

    public boolean isDataEditMode() {
        return editSession != null;
    }

    public TableDataEditSession editSession() {
        return editSession;
    }

    void attachEditSession(TableDataEditSession session) {
        this.editSession = session;
    }

    void detachEditSession() {
        if (editSession != null) {
            editSession.dispose();
            editSession = null;
        }
    }

    /** Opens a save dialog for the given slice (selection or full result). */
    public void setOnExportToFile(Consumer<QueryResult> action) {
        this.onExportToFile = action == null ? result -> { } : action;
    }

    public QueryResult currentResult() {
        return currentResult;
    }

    public boolean hasExportableResult() {
        return currentResult != null && !currentResult.isError() && currentResult.isResultSet();
    }

    public boolean hasRowSelection() {
        return !selectedRowIndices().isEmpty();
    }

    /**
     * Result to export/copy. When {@code preferSelection} is true and cells are
     * selected, returns only those rows; otherwise the full grid.
     */
    public QueryResult exportableResult(boolean preferSelection) {
        if (!hasExportableResult()) {
            return null;
        }
        QueryResult base = liveExportBase();
        if (preferSelection) {
            Set<Integer> indices = selectedRowIndices();
            if (!indices.isEmpty()) {
                List<List<String>> rows = new ArrayList<>(indices.size());
                ObservableList<ObservableList<String>> items = getItems();
                for (int index : indices) {
                    if (index >= 0 && index < items.size()) {
                        rows.add(new ArrayList<>(items.get(index)));
                    }
                }
                if (!rows.isEmpty()) {
                    return ResultExporter.subset(base, rows);
                }
            }
        }
        return base;
    }

    private QueryResult liveExportBase() {
        if (!isDataEditMode()) {
            return currentResult;
        }
        List<List<String>> rows = new ArrayList<>(allRows.size());
        for (ObservableList<String> row : allRows) {
            rows.add(new ArrayList<>(row));
        }
        return ResultExporter.subset(currentResult, rows);
    }

    /** Copies TSV to the clipboard (selection if any, otherwise all rows). */
    public boolean copyAsTsv() {
        return copyFormatted(ResultExporter.Format.TSV);
    }

    public boolean copyAsCsv() {
        return copyFormatted(ResultExporter.Format.CSV);
    }

    private boolean copyFormatted(ResultExporter.Format format) {
        QueryResult slice = exportableResult(true);
        if (slice == null) {
            return false;
        }
        String text = ResultExporter.export(slice, format, null);
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    /**
     * Renders an already-drained result. Must be called on the JavaFX Application
     * Thread; it performs no database work.
     */
    public void setResult(QueryResult result) {
        Objects.requireNonNull(result, "result must not be null");
        detachEditSession();
        clearGridOnly();
        currentResult = result;

        if (result.isError()) {
            throw new IllegalArgumentException(
                    "Errors are rendered by QueryErrorPanel; call QueryOutcomePane.present instead.");
        }
        if (!result.isResultSet()) {
            placeholder.setText(result.successMessage());
            return;
        }
        if (result.columnNames().isEmpty()) {
            placeholder.setText("The query returned no columns.");
            return;
        }

        buildColumns(result);

        allRows.clear();
        for (List<String> row : result.rows()) {
            allRows.add(FXCollections.observableArrayList(row));
        }
        applyRowFilter(rowFilter);

        if (allRows.isEmpty()) {
            placeholder.setText("Query OK \u2014 no rows returned (%d ms)".formatted(result.executionTimeMs()));
        }
    }

    /**
     * Case-insensitive substring filter across all cells. Blank clears the filter.
     */
    public void applyRowFilter(String query) {
        rowFilter = query == null ? "" : query.strip();
        if (rowFilter.isEmpty()) {
            setItems(allRows);
            return;
        }
        String needle = rowFilter.toLowerCase();
        ObservableList<ObservableList<String>> filtered = FXCollections.observableArrayList();
        for (ObservableList<String> row : allRows) {
            if (rowMatches(row, needle)) {
                filtered.add(row);
            }
        }
        setItems(filtered);
        if (filtered.isEmpty() && !allRows.isEmpty()) {
            placeholder.setText("No rows match \u201c" + rowFilter + "\u201d");
        }
    }

    public String rowFilter() {
        return rowFilter;
    }

    /** Re-estimates preferred widths from headers and sampled cell values. */
    public void fitColumnWidths() {
        if (currentResult == null || !currentResult.isResultSet()) {
            return;
        }
        List<String> names = currentResult.columnNames();
        List<List<String>> sampleRows;
        if (isDataEditMode()) {
            sampleRows = new ArrayList<>(allRows.size());
            for (ObservableList<String> row : allRows) {
                sampleRows.add(new ArrayList<>(row));
            }
        } else {
            sampleRows = currentResult.rows();
        }
        // Skip the leading "#" column.
        for (int i = 0; i < names.size(); i++) {
            int tableColumnIndex = i + 1;
            if (tableColumnIndex >= getColumns().size()) {
                break;
            }
            TableColumn<ObservableList<String>, ?> column = getColumns().get(tableColumnIndex);
            column.setPrefWidth(estimateWidth(names.get(i), sampleRows, i));
        }
    }

    /**
     * Drains a live cursor and then renders it.
     *
     * <p>This blocks on JDBC I/O, so it must be called from a background thread;
     * the UI update is marshalled back via {@link Platform#runLater}. Calling it on
     * the JavaFX Application Thread is a programming error and fails fast.
     *
     * @throws IllegalStateException if invoked on the JavaFX Application Thread
     */
    public void populate(ResultSet resultSet) throws SQLException {
        Objects.requireNonNull(resultSet, "resultSet must not be null");
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException(
                    "Draining a ResultSet on the JavaFX Application Thread would freeze the UI. "
                            + "Call this from a Task or CompletableFuture, or use setResult(QueryResult).");
        }
        QueryResult result = ResultSetMapper.drain(resultSet, JdbcSqlDriver.MAX_ROWS, System.nanoTime());
        Platform.runLater(() -> setResult(result));
    }

    /** Drops all columns and rows and restores the idle placeholder. */
    public void clear() {
        detachEditSession();
        clearGridOnly();
        placeholder.setText(PLACEHOLDER_IDLE);
    }

    private void clearGridOnly() {
        currentResult = null;
        rowFilter = "";
        allRows.clear();
        setItems(FXCollections.observableArrayList());
        getColumns().clear();
        setEditable(false);
        setRowFactory(null);
        getStyleClass().remove("table-data-grid");
    }

    private static boolean rowMatches(ObservableList<String> row, String needleLower) {
        for (String cell : row) {
            if (cell != null && cell.toLowerCase().contains(needleLower)) {
                return true;
            }
        }
        return false;
    }

    /** Shows a message in place of results, e.g. while a query is running. */
    public void showMessage(String message) {
        clear();
        placeholder.setText(Objects.requireNonNullElse(message, PLACEHOLDER_IDLE));
    }

    private ContextMenu buildExportMenu() {
        MenuItem copyTsv = new MenuItem("Copy as TSV");
        copyTsv.setOnAction(event -> copyAsTsv());

        MenuItem copyCsv = new MenuItem("Copy as CSV");
        copyCsv.setOnAction(event -> copyAsCsv());

        MenuItem exportAll = new MenuItem("Export all to File\u2026");
        exportAll.setOnAction(event -> {
            QueryResult slice = exportableResult(false);
            if (slice != null) {
                onExportToFile.accept(slice);
            }
        });

        MenuItem exportSelection = new MenuItem("Export selection to File\u2026");
        exportSelection.setOnAction(event -> {
            QueryResult slice = exportableResult(true);
            if (slice != null) {
                onExportToFile.accept(slice);
            }
        });

        ContextMenu menu = new ContextMenu(copyTsv, copyCsv, new SeparatorMenuItem(), exportAll, exportSelection);
        menu.setOnShowing(event -> {
            boolean ready = hasExportableResult();
            boolean selection = hasRowSelection();
            copyTsv.setDisable(!ready);
            copyCsv.setDisable(!ready);
            exportAll.setDisable(!ready);
            exportSelection.setDisable(!ready || !selection);
            copyTsv.setText(selection ? "Copy selection as TSV" : "Copy as TSV");
            copyCsv.setText(selection ? "Copy selection as CSV" : "Copy as CSV");
        });
        return menu;
    }

    private Set<Integer> selectedRowIndices() {
        Set<Integer> indices = new LinkedHashSet<>();
        for (TablePosition<?, ?> position : getSelectionModel().getSelectedCells()) {
            int row = position.getRow();
            if (row >= 0) {
                indices.add(row);
            }
        }
        return indices;
    }

    private void buildColumns(QueryResult result) {
        getColumns().add(rowNumberColumn());

        List<String> names = result.columnNames();
        for (int i = 0; i < names.size(); i++) {
            int columnIndex = i;
            String name = names.get(i);

            TableColumn<ObservableList<String>, String> column = new TableColumn<>(name);
            column.setCellValueFactory(features -> {
                ObservableList<String> row = features.getValue();
                String value = columnIndex < row.size() ? row.get(columnIndex) : null;
                return new ReadOnlyStringWrapper(value);
            });
            column.setCellFactory(ignored -> new ValueCell());
            column.setPrefWidth(estimateWidth(name, result.rows(), columnIndex));
            column.setSortable(true);
            getColumns().add(column);
        }
    }

    private TableColumn<ObservableList<String>, Void> rowNumberColumn() {
        TableColumn<ObservableList<String>, Void> column = new TableColumn<>("#");
        column.setSortable(false);
        column.setReorderable(false);
        column.setPrefWidth(56);
        column.getStyleClass().add("row-number-column");
        column.setCellFactory(ignored -> new TableCell<>() {
            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty ? null : String.valueOf(getIndex() + 1));
            }
        });
        return column;
    }

    private static double estimateWidth(String header, List<List<String>> rows, int columnIndex) {
        int widest = header.length();
        int sampled = Math.min(rows.size(), WIDTH_SAMPLE_ROWS);
        for (int i = 0; i < sampled; i++) {
            List<String> row = rows.get(i);
            String value = columnIndex < row.size() ? row.get(columnIndex) : null;
            int length = value == null ? 4 : value.length();
            if (length > widest) {
                widest = length;
            }
        }
        int width = widest * PIXELS_PER_CHARACTER + COLUMN_PADDING;
        return Math.clamp(width, MIN_COLUMN_WIDTH, MAX_COLUMN_WIDTH);
    }

    private static String stylesheet() {
        return Objects.requireNonNull(
                        DynamicResultTable.class.getResource("/com/lazaro/sqlide/css/result-table.css"),
                        "result-table.css is missing from the classpath")
                .toExternalForm();
    }

    private static final class ValueCell extends TableCell<ObservableList<String>, String> {

        private static final String NULL_STYLE_CLASS = "null-value";

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove(NULL_STYLE_CLASS);

            if (empty) {
                setText(null);
            } else if (item == null) {
                setText("NULL");
                getStyleClass().add(NULL_STYLE_CLASS);
            } else {
                setText(item);
            }
        }
    }
}
