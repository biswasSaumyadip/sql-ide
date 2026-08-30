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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Browse / edit table rows with INSERT / UPDATE / DELETE, NULL-aware cells,
 * dirty-row highlighting, and confirm-before-close.
 */
public final class TableDataEditorPane extends BorderPane {

    private static final int DEFAULT_LIMIT = 1000;
    private static final String NULL_DISPLAY = "NULL";

    private enum RowKind {
        EXISTING,
        INSERTED,
        DELETED
    }

    private record RowState(RowKind kind, List<String> original) {
    }

    private final SchemaNode table;
    private final String qualifiedName;
    private final List<String> primaryKeyColumns;
    private final boolean editable;
    private final Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner;
    private final Executor background;

    private final TableView<ObservableList<String>> grid = new TableView<>();
    private final Label status = new Label();
    private final Button addButton = new Button();
    private final Button deleteButton = new Button();
    private final Button submitButton = new Button();
    private final Button revertButton = new Button();
    private final Button refreshButton = new Button();

    private final Map<ObservableList<String>, RowState> rowStates = new HashMap<>();
    private List<String> columnNames = List.of();
    private boolean truncated;
    private Runnable onDirtyChanged = () -> { };

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
        grid.getStyleClass().addAll("result-table", "table-data-grid");
        grid.getStylesheets().add(Objects.requireNonNull(
                        TableDataEditorPane.class.getResource("/com/lazaro/sqlide/css/result-table.css"),
                        "result-table.css is missing from the classpath")
                .toExternalForm());
        grid.setEditable(editable);
        grid.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        grid.getSelectionModel().setCellSelectionEnabled(true);
        grid.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        grid.setRowFactory(view -> new DataRow());

        refreshButton.setGraphic(Icons.refresh());
        addButton.setGraphic(Icons.newQuery());
        deleteButton.setGraphic(Icons.clear());
        submitButton.setGraphic(Icons.commit());
        revertButton.setGraphic(Icons.rollback());
        for (Button action : List.of(refreshButton, addButton, deleteButton, submitButton, revertButton)) {
            action.getStyleClass().addAll(Styles.FLAT, "table-data-action", "table-data-icon-button");
        }
        refreshButton.setTooltip(new Tooltip("Refresh rows from the database"));
        addButton.setTooltip(new Tooltip("Insert row"));
        deleteButton.setTooltip(new Tooltip("Delete selected row(s)"));
        submitButton.setTooltip(new Tooltip("Submit pending INSERT / UPDATE / DELETE"));
        revertButton.setTooltip(new Tooltip("Revert unsaved edits"));
        addButton.setDisable(!editable);
        deleteButton.setDisable(!editable);
        submitButton.setDisable(true);
        revertButton.setDisable(true);
        refreshButton.setOnAction(event -> reloadWithConfirm());
        addButton.setOnAction(event -> addRow());
        deleteButton.setOnAction(event -> deleteSelected());
        submitButton.setOnAction(event -> submitChanges(false));
        revertButton.setOnAction(event -> revertChanges());

        status.getStyleClass().add("table-data-status");
        HBox toolbar = new HBox(2, refreshButton, addButton, deleteButton, submitButton, revertButton, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(4, 8, 4, 8));
        toolbar.getStyleClass().add("table-data-toolbar");
        HBox.setHgrow(status, Priority.ALWAYS);

        installShortcuts();
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

    public boolean isDirty() {
        return !pendingStatements().isEmpty();
    }

    public void setOnDirtyChanged(Runnable action) {
        this.onDirtyChanged = action == null ? () -> { } : action;
    }

    /**
     * Confirm discard/submit before closing. Returns {@code true} when safe to close.
     */
    public boolean confirmClose() {
        if (!isDirty()) {
            return true;
        }
        ButtonType submit = new ButtonType("Submit", ButtonBar.ButtonData.YES);
        ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Unsaved data edits");
        alert.setHeaderText("\"" + table.name() + "\" has unsaved INSERT / UPDATE / DELETE changes.");
        alert.setContentText("Submit them to the database before closing?");
        alert.getButtonTypes().setAll(submit, discard, ButtonType.CANCEL);
        if (getScene() != null) {
            alert.initOwner(getScene().getWindow());
        }
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return false;
        }
        if (choice.get() == discard) {
            return true;
        }
        return submitChanges(true);
    }

    private void installShortcuts() {
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (!editable) {
                return;
            }
            KeyCombination setNull = new KeyCodeCombination(KeyCode.N, KeyCombination.ALT_DOWN);
            if (setNull.match(event)) {
                setSelectedCellsNull();
                event.consume();
            }
        });
    }

    private void reloadWithConfirm() {
        if (isDirty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Discard edits?");
            alert.setHeaderText("Reload will discard unsaved changes.");
            alert.setContentText("Continue?");
            if (getScene() != null) {
                alert.initOwner(getScene().getWindow());
            }
            Optional<ButtonType> choice = alert.showAndWait();
            if (choice.isEmpty() || choice.get() != ButtonType.OK) {
                return;
            }
        }
        reload();
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
                rowStates.clear();
                return;
            }
            present(result);
        });
        task.setOnFailed(event -> status.setText(String.valueOf(task.getException().getMessage())));
        background.execute(task);
    }

    private void present(QueryResult result) {
        rowStates.clear();
        truncated = result.truncated();
        columnNames = result.columnNames();
        grid.getColumns().clear();
        ObservableList<ObservableList<String>> rows = FXCollections.observableArrayList();

        for (int i = 0; i < columnNames.size(); i++) {
            int columnIndex = i;
            String name = columnNames.get(i);
            boolean pk = containsIgnoreCase(primaryKeyColumns, name);
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(pk ? name + " (PK)" : name);
            column.setPrefWidth(Math.clamp(name.length() * 8 + 28, 70, 280));
            column.setEditable(editable);
            column.setCellValueFactory(features -> {
                ObservableList<String> row = features.getValue();
                String value = columnIndex < row.size() ? row.get(columnIndex) : null;
                return new SimpleStringProperty(value);
            });
            column.setCellFactory(ignored -> new NullAwareCell(columnIndex, pk));
            column.setOnEditCommit(event -> {
                ObservableList<String> row = event.getRowValue();
                if (row == null || isDeleted(row)) {
                    return;
                }
                if (pk && !isInserted(row)) {
                    return;
                }
                if (columnIndex < row.size()) {
                    row.set(columnIndex, event.getNewValue());
                }
                refreshRowStyles();
                markDirtyState();
            });
            grid.getColumns().add(column);
        }

        for (List<String> row : result.rows()) {
            ObservableList<String> live = FXCollections.observableArrayList(row);
            rowStates.put(live, new RowState(RowKind.EXISTING, copyRow(row)));
            rows.add(live);
        }
        grid.setItems(rows);
        updateStatusLine();
        markDirtyState();
    }

    private void addRow() {
        if (!editable || columnNames.isEmpty()) {
            return;
        }
        ObservableList<String> live = FXCollections.observableArrayList();
        for (int i = 0; i < columnNames.size(); i++) {
            live.add(null);
        }
        rowStates.put(live, new RowState(RowKind.INSERTED, null));
        grid.getItems().add(live);
        grid.getSelectionModel().clearSelection();
        grid.getSelectionModel().select(live);
        grid.scrollTo(live);
        refreshRowStyles();
        markDirtyState();
        updateStatusLine();
    }

    private void deleteSelected() {
        if (!editable) {
            return;
        }
        Set<ObservableList<String>> selectedRows = selectedRows();
        if (selectedRows.isEmpty()) {
            return;
        }
        List<ObservableList<String>> toRemove = new ArrayList<>();
        for (ObservableList<String> row : selectedRows) {
            RowState state = rowStates.get(row);
            if (state == null) {
                continue;
            }
            if (state.kind() == RowKind.INSERTED) {
                toRemove.add(row);
                rowStates.remove(row);
            } else if (state.kind() == RowKind.EXISTING) {
                rowStates.put(row, new RowState(RowKind.DELETED, state.original()));
            } else if (state.kind() == RowKind.DELETED) {
                rowStates.put(row, new RowState(RowKind.EXISTING, state.original()));
            }
        }
        grid.getItems().removeAll(toRemove);
        refreshRowStyles();
        markDirtyState();
        updateStatusLine();
    }

    private void setSelectedCellsNull() {
        if (!editable) {
            return;
        }
        boolean changed = false;
        for (TablePosition<?, ?> position : List.copyOf(grid.getSelectionModel().getSelectedCells())) {
            int rowIndex = position.getRow();
            int colIndex = position.getColumn();
            if (rowIndex < 0 || colIndex < 0 || rowIndex >= grid.getItems().size()) {
                continue;
            }
            ObservableList<String> row = grid.getItems().get(rowIndex);
            if (isDeleted(row)) {
                continue;
            }
            boolean pk = colIndex < columnNames.size()
                    && containsIgnoreCase(primaryKeyColumns, columnNames.get(colIndex));
            if (pk && !isInserted(row)) {
                continue;
            }
            if (colIndex < row.size()) {
                row.set(colIndex, null);
                changed = true;
            }
        }
        if (changed) {
            grid.refresh();
            refreshRowStyles();
            markDirtyState();
        }
    }

    private Set<ObservableList<String>> selectedRows() {
        Set<ObservableList<String>> rows = new HashSet<>();
        for (TablePosition<?, ?> position : grid.getSelectionModel().getSelectedCells()) {
            int rowIndex = position.getRow();
            if (rowIndex >= 0 && rowIndex < grid.getItems().size()) {
                rows.add(grid.getItems().get(rowIndex));
            }
        }
        for (ObservableList<String> row : grid.getSelectionModel().getSelectedItems()) {
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
    }

    private void markDirtyState() {
        boolean dirty = isDirty();
        submitButton.setDisable(!editable || !dirty);
        revertButton.setDisable(!dirty);
        refreshRowStyles();
        onDirtyChanged.run();
    }

    private void updateStatusLine() {
        String mode = editable
                ? "Editable \u00B7 PK: " + String.join(", ", primaryKeyColumns)
                : table.type() == NodeType.VIEW
                ? "View \u00B7 read-only"
                : "Read-only \u00B7 no primary key detected";
        int visible = grid.getItems().size();
        status.setText(visible + " rows \u00B7 " + mode
                + (truncated ? " \u00B7 truncated" : "")
                + (editable ? " \u00B7 Alt+N = NULL" : ""));
    }

    private List<String> pendingStatements() {
        List<String> statements = new ArrayList<>();
        for (Map.Entry<ObservableList<String>, RowState> entry : rowStates.entrySet()) {
            ObservableList<String> live = entry.getKey();
            RowState state = entry.getValue();
            switch (state.kind()) {
                case DELETED -> {
                    String sql = UpdateSqlGenerator.delete(
                            qualifiedName, columnNames, primaryKeyColumns, state.original());
                    if (sql != null) {
                        statements.add(sql);
                    }
                }
                case INSERTED -> {
                    String sql = UpdateSqlGenerator.insert(qualifiedName, columnNames, copyRow(live));
                    if (sql != null) {
                        statements.add(sql);
                    }
                }
                case EXISTING -> {
                    List<String> current = copyRow(live);
                    String sql = UpdateSqlGenerator.update(
                            qualifiedName, columnNames, primaryKeyColumns, state.original(), current);
                    if (sql != null) {
                        statements.add(sql);
                    }
                }
            }
        }
        return statements;
    }

    /**
     * @param blocking when {@code true}, waits for the JDBC round-trip (used by close confirm)
     * @return {@code true} when submit succeeded (or nothing to do)
     */
    private boolean submitChanges(boolean blocking) {
        List<String> statements = pendingStatements();
        if (statements.isEmpty()) {
            return true;
        }
        status.setText("Submitting " + statements.size() + " change(s)\u2026");
        submitButton.setDisable(true);
        if (blocking) {
            try {
                ScriptResult script = scriptRunner.apply(statements).get(60, TimeUnit.SECONDS);
                if (script.errorCount() > 0) {
                    status.setText("Submit failed: " + script.summary());
                    markDirtyState();
                    return false;
                }
                status.setText("Submitted " + statements.size() + " change(s)");
                reload();
                return true;
            } catch (Exception error) {
                status.setText("Submit failed: " + error.getMessage());
                markDirtyState();
                return false;
            }
        }
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
            status.setText("Submitted " + statements.size() + " change(s)");
            Platform.runLater(this::reload);
        });
        task.setOnFailed(event -> {
            status.setText("Submit failed: " + task.getException().getMessage());
            markDirtyState();
        });
        background.execute(task);
        return true;
    }

    private void revertChanges() {
        List<ObservableList<String>> keep = new ArrayList<>();
        for (ObservableList<String> row : List.copyOf(grid.getItems())) {
            RowState state = rowStates.get(row);
            if (state == null) {
                continue;
            }
            if (state.kind() == RowKind.INSERTED) {
                rowStates.remove(row);
                continue;
            }
            if (state.kind() == RowKind.DELETED) {
                rowStates.put(row, new RowState(RowKind.EXISTING, state.original()));
            }
            List<String> original = state.original();
            for (int i = 0; i < original.size() && i < row.size(); i++) {
                row.set(i, original.get(i));
            }
            keep.add(row);
        }
        grid.getItems().setAll(keep);
        grid.refresh();
        refreshRowStyles();
        markDirtyState();
        updateStatusLine();
        status.setText("Reverted local edits");
    }

    private void refreshRowStyles() {
        grid.lookupAll(".table-row-cell").forEach(node -> {
            if (node instanceof TableRow<?> row) {
                row.requestLayout();
            }
        });
        // Force row factories to re-apply style classes.
        grid.refresh();
    }

    private boolean isInserted(ObservableList<String> row) {
        RowState state = rowStates.get(row);
        return state != null && state.kind() == RowKind.INSERTED;
    }

    private boolean isDeleted(ObservableList<String> row) {
        RowState state = rowStates.get(row);
        return state != null && state.kind() == RowKind.DELETED;
    }

    private boolean isDirtyRow(ObservableList<String> row) {
        RowState state = rowStates.get(row);
        if (state == null) {
            return false;
        }
        return switch (state.kind()) {
            case INSERTED, DELETED -> true;
            case EXISTING -> UpdateSqlGenerator.update(
                    qualifiedName, columnNames, primaryKeyColumns, state.original(), copyRow(row)) != null;
        };
    }

    private static List<String> copyRow(List<String> row) {
        return new ArrayList<>(row);
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

    private final class DataRow extends TableRow<ObservableList<String>> {
        @Override
        protected void updateItem(ObservableList<String> item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("dirty-row", "inserted-row", "deleted-row");
            if (empty || item == null) {
                return;
            }
            RowState state = rowStates.get(item);
            if (state == null) {
                return;
            }
            switch (state.kind()) {
                case INSERTED -> getStyleClass().add("inserted-row");
                case DELETED -> getStyleClass().add("deleted-row");
                case EXISTING -> {
                    if (isDirtyRow(item)) {
                        getStyleClass().add("dirty-row");
                    }
                }
            }
        }
    }

    private final class NullAwareCell extends TableCell<ObservableList<String>, String> {
        private final int columnIndex;
        private final boolean primaryKey;
        private TextField textField;

        NullAwareCell(int columnIndex, boolean primaryKey) {
            this.columnIndex = columnIndex;
            this.primaryKey = primaryKey;
            MenuItem setNull = new MenuItem("Set NULL (Alt+N)");
            setNull.setOnAction(event -> {
                ObservableList<String> row = getTableRow() == null ? null : getTableRow().getItem();
                if (row == null || isDeleted(row) || (primaryKey && !isInserted(row))) {
                    return;
                }
                if (columnIndex < row.size()) {
                    row.set(columnIndex, null);
                }
                updateItem(null, false);
                refreshRowStyles();
                markDirtyState();
            });
            setContextMenu(new ContextMenu(setNull));
        }

        @Override
        public void startEdit() {
            ObservableList<String> row = getTableRow() == null ? null : getTableRow().getItem();
            if (row == null || isDeleted(row)) {
                return;
            }
            if (primaryKey && !isInserted(row)) {
                return;
            }
            if (!isEditable() || !getTableView().isEditable() || !getTableColumn().isEditable()) {
                return;
            }
            super.startEdit();
            if (isEditing()) {
                if (textField == null) {
                    createTextField();
                }
                textField.setText(getItem() == null ? "" : getItem());
                setText(null);
                setGraphic(textField);
                textField.requestFocus();
                textField.selectAll();
            }
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            updateItem(getItem(), false);
        }

        @Override
        public void commitEdit(String newValue) {
            super.commitEdit(newValue);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("null-value");
            if (empty) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (isEditing()) {
                if (textField != null) {
                    textField.setText(item == null ? "" : item);
                }
                setText(null);
                setGraphic(textField);
                return;
            }
            if (item == null) {
                setText(NULL_DISPLAY);
                setGraphic(null);
                getStyleClass().add("null-value");
            } else {
                setText(item);
                setGraphic(null);
            }
        }

        private void createTextField() {
            textField = new TextField();
            textField.setOnAction(event -> commitEdit(textField.getText()));
            textField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
                if (event.getCode() == KeyCode.ESCAPE) {
                    cancelEdit();
                    event.consume();
                } else if (new KeyCodeCombination(KeyCode.N, KeyCombination.ALT_DOWN).match(event)) {
                    commitEdit(null);
                    event.consume();
                }
            });
            textField.focusedProperty().addListener((observable, was, isFocused) -> {
                if (!isFocused && isEditing()) {
                    commitEdit(textField.getText());
                }
            });
        }
    }
}
