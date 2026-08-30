package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.db.ScriptResult;
import com.lazaro.sqlide.core.export.UpdateSqlGenerator;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TableRow;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

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
 * DataGrip-style edit mode attached to a {@link DynamicResultTable}: INSERT / UPDATE /
 * DELETE, NULL cells, dirty-row highlighting, and submit / revert.
 */
public final class TableDataEditSession {

    private static final int DEFAULT_LIMIT = 1000;
    private static final String NULL_DISPLAY = "NULL";

    private enum RowKind {
        EXISTING,
        INSERTED,
        DELETED
    }

    private record RowState(RowKind kind, List<String> original) {
    }

    private final DynamicResultTable table;
    private final SchemaNode schemaTable;
    private final String qualifiedName;
    private final List<String> primaryKeyColumns;
    private final boolean editable;
    private final Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner;
    private final Executor background;

    private final Map<ObservableList<String>, RowState> rowStates = new HashMap<>();
    private List<String> columnNames = List.of();
    private boolean truncated;
    private Runnable onDirtyChanged = () -> { };
    private Runnable onStatusChanged = () -> { };
    private String statusText = "";

    public TableDataEditSession(
            DynamicResultTable table,
            SchemaNode schemaTable,
            String qualifiedName,
            List<String> primaryKeyColumns,
            Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner,
            Executor background) {
        this.table = Objects.requireNonNull(table);
        this.schemaTable = Objects.requireNonNull(schemaTable);
        this.qualifiedName = Objects.requireNonNullElse(qualifiedName, schemaTable.name());
        this.primaryKeyColumns = List.copyOf(primaryKeyColumns == null ? List.of() : primaryKeyColumns);
        this.editable = schemaTable.type() == NodeType.TABLE && !this.primaryKeyColumns.isEmpty();
        this.scriptRunner = Objects.requireNonNull(scriptRunner);
        this.background = Objects.requireNonNull(background);

        table.getStyleClass().add("table-data-grid");
        table.setEditable(editable);
        table.setRowFactory(view -> new DataRow());
        table.addEventFilter(KeyEvent.KEY_PRESSED, this::onKeyPressed);
    }

    public SchemaNode schemaTable() {
        return schemaTable;
    }

    public boolean matches(SchemaNode node) {
        return node != null && schemaTable.name().equalsIgnoreCase(node.name())
                && Objects.equals(
                nullToEmpty(schemaTable.metadata(SchemaNode.META_CATALOG)),
                nullToEmpty(node.metadata(SchemaNode.META_CATALOG)));
    }

    public boolean editable() {
        return editable;
    }

    public boolean isDirty() {
        return !pendingStatements().isEmpty();
    }

    public String statusText() {
        return statusText;
    }

    public void setOnDirtyChanged(Runnable action) {
        this.onDirtyChanged = action == null ? () -> { } : action;
    }

    public void setOnStatusChanged(Runnable action) {
        this.onStatusChanged = action == null ? () -> { } : action;
    }

    public void reload() {
        setStatus("Loading\u2026");
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
                setStatus("No result");
                return;
            }
            QueryResult result = script.results().getFirst();
            if (result.isError()) {
                setStatus(result.errorMessage());
                table.clear();
                rowStates.clear();
                notifyDirty();
                return;
            }
            present(result);
        });
        task.setOnFailed(event -> setStatus(String.valueOf(task.getException().getMessage())));
        background.execute(task);
    }

    public void reloadWithConfirm() {
        if (isDirty()) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Discard edits?");
            alert.setHeaderText("Reload will discard unsaved changes.");
            alert.setContentText("Continue?");
            initOwner(alert);
            Optional<ButtonType> choice = alert.showAndWait();
            if (choice.isEmpty() || choice.get() != ButtonType.OK) {
                return;
            }
        }
        reload();
    }

    public void addRow() {
        if (!editable || columnNames.isEmpty()) {
            return;
        }
        ObservableList<String> live = FXCollections.observableArrayList();
        for (int i = 0; i < columnNames.size(); i++) {
            live.add(null);
        }
        rowStates.put(live, new RowState(RowKind.INSERTED, null));
        table.backingRows().add(live);
        table.applyRowFilter(table.rowFilter());
        table.getSelectionModel().clearSelection();
        table.getSelectionModel().select(live);
        table.scrollTo(live);
        notifyDirty();
        updateStatusLine();
    }

    public void deleteSelected() {
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
        table.backingRows().removeAll(toRemove);
        table.applyRowFilter(table.rowFilter());
        table.refresh();
        notifyDirty();
        updateStatusLine();
    }

    public void submitChanges() {
        submitChanges(false);
    }

    public boolean submitChanges(boolean blocking) {
        List<String> statements = pendingStatements();
        if (statements.isEmpty()) {
            return true;
        }
        setStatus("Submitting " + statements.size() + " change(s)\u2026");
        if (blocking) {
            try {
                ScriptResult script = scriptRunner.apply(statements).get(60, TimeUnit.SECONDS);
                if (script.errorCount() > 0) {
                    setStatus("Submit failed: " + script.summary());
                    notifyDirty();
                    return false;
                }
                setStatus("Submitted " + statements.size() + " change(s)");
                reload();
                return true;
            } catch (Exception error) {
                setStatus("Submit failed: " + error.getMessage());
                notifyDirty();
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
                setStatus("Submit failed: " + script.summary());
                notifyDirty();
                return;
            }
            setStatus("Submitted " + statements.size() + " change(s)");
            Platform.runLater(this::reload);
        });
        task.setOnFailed(event -> {
            setStatus("Submit failed: " + task.getException().getMessage());
            notifyDirty();
        });
        background.execute(task);
        return true;
    }

    public void revertChanges() {
        List<ObservableList<String>> keep = new ArrayList<>();
        for (ObservableList<String> row : List.copyOf(table.backingRows())) {
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
        table.backingRows().setAll(keep);
        table.applyRowFilter(table.rowFilter());
        table.refresh();
        notifyDirty();
        updateStatusLine();
        setStatus("Reverted local edits");
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
        alert.setHeaderText("\"" + schemaTable.name() + "\" has unsaved INSERT / UPDATE / DELETE changes.");
        alert.setContentText("Submit them to the database before closing?");
        alert.getButtonTypes().setAll(submit, discard, ButtonType.CANCEL);
        initOwner(alert);
        Optional<ButtonType> choice = alert.showAndWait();
        if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
            return false;
        }
        if (choice.get() == discard) {
            return true;
        }
        return submitChanges(true);
    }

    public void dispose() {
        table.getStyleClass().remove("table-data-grid");
        table.setEditable(false);
        table.setRowFactory(null);
        rowStates.clear();
    }

    private void present(QueryResult result) {
        rowStates.clear();
        truncated = result.truncated();
        columnNames = result.columnNames();
        table.beginEditPresent(result);

        ObservableList<ObservableList<String>> rows = table.backingRows();
        rows.clear();
        table.getColumns().clear();
        table.getColumns().add(table.createRowNumberColumn());

        for (int i = 0; i < columnNames.size(); i++) {
            int columnIndex = i;
            String name = columnNames.get(i);
            boolean pk = containsIgnoreCase(primaryKeyColumns, name);
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(pk ? name + " (PK)" : name);
            column.setPrefWidth(Math.clamp(name.length() * 8 + 28, 70, 420));
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
                table.refresh();
                notifyDirty();
            });
            table.getColumns().add(column);
        }

        for (List<String> row : result.rows()) {
            ObservableList<String> live = FXCollections.observableArrayList(row);
            rowStates.put(live, new RowState(RowKind.EXISTING, copyRow(row)));
            rows.add(live);
        }
        table.applyRowFilter("");
        updateStatusLine();
        notifyDirty();
    }

    private void onKeyPressed(KeyEvent event) {
        if (!editable) {
            return;
        }
        KeyCombination setNull = new KeyCodeCombination(KeyCode.N, KeyCombination.ALT_DOWN);
        if (setNull.match(event)) {
            setSelectedCellsNull();
            event.consume();
        }
    }

    private void setSelectedCellsNull() {
        boolean changed = false;
        for (TablePosition<?, ?> position : List.copyOf(table.getSelectionModel().getSelectedCells())) {
            int rowIndex = position.getRow();
            int colIndex = position.getColumn();
            // Skip leading "#" column.
            int dataCol = colIndex - 1;
            if (rowIndex < 0 || dataCol < 0 || rowIndex >= table.getItems().size()) {
                continue;
            }
            ObservableList<String> row = table.getItems().get(rowIndex);
            if (isDeleted(row)) {
                continue;
            }
            boolean pk = dataCol < columnNames.size()
                    && containsIgnoreCase(primaryKeyColumns, columnNames.get(dataCol));
            if (pk && !isInserted(row)) {
                continue;
            }
            if (dataCol < row.size()) {
                row.set(dataCol, null);
                changed = true;
            }
        }
        if (changed) {
            table.refresh();
            notifyDirty();
        }
    }

    private Set<ObservableList<String>> selectedRows() {
        Set<ObservableList<String>> rows = new HashSet<>();
        for (TablePosition<?, ?> position : table.getSelectionModel().getSelectedCells()) {
            int rowIndex = position.getRow();
            if (rowIndex >= 0 && rowIndex < table.getItems().size()) {
                rows.add(table.getItems().get(rowIndex));
            }
        }
        for (ObservableList<String> row : table.getSelectionModel().getSelectedItems()) {
            if (row != null) {
                rows.add(row);
            }
        }
        return rows;
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

    private void notifyDirty() {
        onDirtyChanged.run();
    }

    private void updateStatusLine() {
        String mode = editable
                ? "Editable \u00B7 PK: " + String.join(", ", primaryKeyColumns)
                : schemaTable.type() == NodeType.VIEW
                ? "View \u00B7 read-only"
                : "Read-only \u00B7 no primary key detected";
        int visible = table.backingRows().size();
        setStatus(visible + " rows \u00B7 " + mode
                + (truncated ? " \u00B7 truncated" : "")
                + (editable ? " \u00B7 Alt+N = NULL" : ""));
    }

    private void setStatus(String text) {
        statusText = Objects.requireNonNullElse(text, "");
        onStatusChanged.run();
    }

    private void initOwner(Alert alert) {
        if (table.getScene() != null) {
            alert.initOwner(table.getScene().getWindow());
        }
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
                table.refresh();
                notifyDirty();
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
