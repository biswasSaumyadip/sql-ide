package com.lazaro.sqlide.ui.components;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.db.ScriptResult;
import com.lazaro.sqlide.core.export.UpdateSqlGenerator;
import com.lazaro.sqlide.ui.Icons;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.util.converter.DefaultStringConverter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

/**
 * Browse / edit table rows: loads {@code SELECT * \ldots LIMIT n}, tracks dirty cells,
 * and submits generated {@code UPDATE} statements keyed by primary-key columns.
 */
public final class TableDataEditorPane extends BorderPane {

    private static final int DEFAULT_LIMIT = 1000;

    private final SchemaNode table;
    private final String qualifiedName;
    private final List<String> primaryKeyColumns;
    private final boolean editable;
    private final Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner;
    private final Executor background;

    private final TableView<ObservableList<String>> grid = new TableView<>();
    private final Label status = new Label();
    private final Button submitButton = new Button();
    private final Button revertButton = new Button();
    private final Button refreshButton = new Button();

    private final Map<ObservableList<String>, List<String>> originals = new HashMap<>();
    private List<String> columnNames = List.of();

    public TableDataEditorPane(
            SchemaNode table,
            String qualifiedName,
            List<String> primaryKeyColumns,
            Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner,
            Executor background) {
        this.table = Objects.requireNonNull(table);
        this.qualifiedName = Objects.requireNonNullElse(qualifiedName, table.name());
        this.primaryKeyColumns = List.copyOf(primaryKeyColumns == null ? List.of() : primaryKeyColumns);
        this.editable = table.type() == NodeType.TABLE && !this.primaryKeyColumns.isEmpty();
        this.scriptRunner = Objects.requireNonNull(scriptRunner);
        this.background = Objects.requireNonNull(background);

        getStyleClass().add("table-data-editor");
        grid.getStyleClass().add("result-table");
        grid.setEditable(editable);
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);

        refreshButton.setGraphic(Icons.refresh());
        submitButton.setGraphic(Icons.commit());
        revertButton.setGraphic(Icons.rollback());
        for (Button action : List.of(refreshButton, submitButton, revertButton)) {
            action.getStyleClass().addAll(Styles.FLAT, "table-data-action", "table-data-icon-button");
        }
        submitButton.setTooltip(new Tooltip("Submit pending UPDATEs"));
        revertButton.setTooltip(new Tooltip("Revert unsaved cell edits"));
        refreshButton.setTooltip(new Tooltip("Refresh rows from the database"));
        submitButton.setDisable(true);
        revertButton.setDisable(true);
        submitButton.setOnAction(event -> submitChanges());
        revertButton.setOnAction(event -> revertChanges());
        refreshButton.setOnAction(event -> reload());

        status.getStyleClass().add("table-data-status");
        HBox toolbar = new HBox(2, refreshButton, submitButton, revertButton, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.getStyleClass().add("table-data-toolbar");
        HBox.setHgrow(status, Priority.ALWAYS);

        setTop(toolbar);
        setCenter(grid);
        reload();
    }

    public SchemaNode table() {
        return table;
    }

    public boolean matches(SchemaNode node) {
        return node != null && table.name().equalsIgnoreCase(node.name())
                && Objects.equals(
                nullToEmpty(table.metadata(SchemaNode.META_CATALOG)),
                nullToEmpty(node.metadata(SchemaNode.META_CATALOG)));
    }

    private void reload() {
        status.setText("Loading\u2026");
        submitButton.setDisable(true);
        revertButton.setDisable(true);
        String sql = "SELECT * FROM " + qualifiedName + " LIMIT " + DEFAULT_LIMIT + ";";
        Task<ScriptResult> task = new Task<>() {
            @Override
            protected ScriptResult call() throws Exception {
                return scriptRunner.apply(List.of(sql)).get();
            }
        };
        task.setOnSucceeded(event -> {
            ScriptResult script = task.getValue();
            if (script == null || script.isEmpty()) {
                status.setText("No result");
                return;
            }
            QueryResult result = script.results().getFirst();
            if (result.isError()) {
                status.setText(result.errorMessage());
                grid.getItems().clear();
                grid.getColumns().clear();
                return;
            }
            present(result);
        });
        task.setOnFailed(event -> status.setText(String.valueOf(task.getException().getMessage())));
        background.execute(task);
    }

    private void present(QueryResult result) {
        originals.clear();
        columnNames = result.columnNames();
        grid.getColumns().clear();
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();

        for (int i = 0; i < columnNames.size(); i++) {
            int columnIndex = i;
            String name = columnNames.get(i);
            boolean pk = containsIgnoreCase(primaryKeyColumns, name);
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(pk ? name + " (PK)" : name);
            column.setPrefWidth(Math.clamp(name.length() * 8 + 28, 70, 280));
            column.setEditable(editable && !pk);
            column.setCellValueFactory(features -> {
                ObservableList<String> row = features.getValue();
                String value = columnIndex < row.size() ? row.get(columnIndex) : null;
                return new SimpleStringProperty(value == null ? "" : value);
            });
            column.setCellFactory(TextFieldTableCell.forTableColumn(new DefaultStringConverter()));
            column.setOnEditCommit(event -> {
                ObservableList<String> row = event.getRowValue();
                String newValue = event.getNewValue();
                // Preserve SQL NULL only when the cell was never touched from empty display of null —
                // empty edit commits as empty string.
                if (columnIndex < row.size()) {
                    row.set(columnIndex, newValue);
                }
                markDirtyState();
            });
            grid.getColumns().add(column);
        }

        for (List<String> row : result.rows()) {
            ObservableList<String> live = FXCollections.observableArrayList(row);
            // Store nulls as empty for the text cells, keep originals as-is for WHERE/SET.
            for (int i = 0; i < live.size(); i++) {
                if (live.get(i) == null) {
                    live.set(i, "");
                }
            }
            List<String> original = new ArrayList<>(row.size());
            for (String cell : row) {
                original.add(cell);
            }
            originals.put(live, original);
            rows.add(live);
        }
        grid.setItems(rows);

        String mode = editable
                ? "Editable \u00B7 PK: " + String.join(", ", primaryKeyColumns)
                : table.type() == NodeType.VIEW
                ? "View \u00B7 read-only"
                : "Read-only \u00B7 no primary key detected";
        status.setText(result.rowCount() + " rows \u00B7 " + mode
                + (result.truncated() ? " \u00B7 truncated" : ""));
        markDirtyState();
    }

    private void markDirtyState() {
        boolean dirty = !pendingUpdates().isEmpty();
        submitButton.setDisable(!editable || !dirty);
        revertButton.setDisable(!dirty);
    }

    private List<String> pendingUpdates() {
        List<String> statements = new ArrayList<>();
        for (Map.Entry<ObservableList<String>, List<String>> entry : originals.entrySet()) {
            ObservableList<String> live = entry.getKey();
            List<String> original = entry.getValue();
            List<String> current = new ArrayList<>(live.size());
            for (int i = 0; i < original.size(); i++) {
                String liveValue = i < live.size() ? live.get(i) : null;
                String orig = original.get(i);
                if (orig == null && (liveValue == null || liveValue.isEmpty())) {
                    current.add(null);
                } else {
                    current.add(liveValue);
                }
            }
            String sql = UpdateSqlGenerator.update(qualifiedName, columnNames, primaryKeyColumns, original, current);
            if (sql != null) {
                statements.add(sql);
            }
        }
        return statements;
    }

    private void submitChanges() {
        List<String> statements = pendingUpdates();
        if (statements.isEmpty()) {
            return;
        }
        status.setText("Submitting " + statements.size() + " update(s)\u2026");
        submitButton.setDisable(true);
        Task<ScriptResult> task = new Task<>() {
            @Override
            protected ScriptResult call() throws Exception {
                return scriptRunner.apply(statements).get();
            }
        };
        task.setOnSucceeded(event -> {
            ScriptResult script = task.getValue();
            if (script.errorCount() > 0) {
                status.setText("Submit failed: " + script.summary());
                markDirtyState();
                return;
            }
            status.setText("Submitted " + statements.size() + " update(s)");
            Platform.runLater(this::reload);
        });
        task.setOnFailed(event -> {
            status.setText("Submit failed: " + task.getException().getMessage());
            markDirtyState();
        });
        background.execute(task);
    }

    private void revertChanges() {
        for (Map.Entry<ObservableList<String>, List<String>> entry : originals.entrySet()) {
            ObservableList<String> live = entry.getKey();
            List<String> original = entry.getValue();
            for (int i = 0; i < original.size() && i < live.size(); i++) {
                String value = original.get(i);
                live.set(i, value == null ? "" : value);
            }
        }
        grid.refresh();
        markDirtyState();
        status.setText("Reverted local edits");
    }

    private static boolean containsIgnoreCase(List<String> values, String needle) {
        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
