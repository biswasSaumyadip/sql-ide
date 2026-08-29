package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.JdbcSqlDriver;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.ResultSetMapper;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

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
    }

    // ---------------------------------------------------------------- public API

    /**
     * Renders an already-drained result. Must be called on the JavaFX Application
     * Thread; it performs no database work.
     */
    public void setResult(QueryResult result) {
        Objects.requireNonNull(result, "result must not be null");
        clear();

        if (result.isError()) {
            placeholder.setText(result.errorMessage());
            return;
        }
        if (!result.isResultSet()) {
            placeholder.setText(result.summary());
            return;
        }
        if (result.columnNames().isEmpty()) {
            placeholder.setText("The query returned no columns.");
            return;
        }

        buildColumns(result);

        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();
        for (List<String> row : result.rows()) {
            rows.add(FXCollections.observableArrayList(row));
        }
        setItems(rows);

        if (rows.isEmpty()) {
            placeholder.setText("No rows. " + result.summary());
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
        setItems(FXCollections.observableArrayList());
        getColumns().clear();
        placeholder.setText(PLACEHOLDER_IDLE);
    }

    /** Shows a message in place of results, e.g. while a query is running. */
    public void showMessage(String message) {
        clear();
        placeholder.setText(Objects.requireNonNullElse(message, PLACEHOLDER_IDLE));
    }

    // ---------------------------------------------------------------- internals

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

    /**
     * Ordinal column. Derived from the cell index rather than a lookup in the item
     * list, which would be quadratic over a thousand rows.
     */
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

    /** Renders SQL NULL as a muted literal so it cannot be confused with an empty string. */
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
