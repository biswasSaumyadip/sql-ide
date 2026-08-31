package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.db.ConnectionConfig.Driver;
import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.tabledesign.TableDesignerModel;
import com.lazaro.sqlide.core.tabledesign.TableDesignerModel.ColumnDraft;
import com.lazaro.sqlide.core.tabledesign.TableDesignerModel.FkDraft;
import com.lazaro.sqlide.core.tabledesign.TableDesignerModel.IndexDraft;
import com.lazaro.sqlide.ui.Icons;
import com.lazaro.sqlide.ui.components.SqlSyntaxHighlighter;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.ComboBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Visual table designer (columns / indexes / FKs) that emits {@code ALTER TABLE}
 * for review in the editor. Nothing is executed from this dialog.
 */
public final class ModifyTableDialog extends Dialog<String> {

    private static final List<String> COMMON_TYPES = List.of(
            "INT", "BIGINT", "SMALLINT", "TINYINT",
            "VARCHAR(255)", "VARCHAR(100)", "CHAR(36)",
            "TEXT", "LONGTEXT", "JSON",
            "DATETIME", "TIMESTAMP", "DATE", "TIME",
            "DECIMAL(10,2)", "DOUBLE", "FLOAT", "BOOLEAN", "BLOB");

    private static final ButtonType OPEN_IN_EDITOR =
            new ButtonType("Open in Editor", ButtonBar.ButtonData.OK_DONE);

    private final TableDesignerModel model;
    private final Driver driver;
    private final ObservableList<String> tableNames;
    private final DialogChrome chrome;

    private final TableView<ColumnDraft> columnsTable = new TableView<>();
    private final TableView<IndexDraft> indexesTable = new TableView<>();
    private final TableView<FkDraft> fksTable = new TableView<>();
    private final CodeArea preview = new CodeArea();
    private Node openButton;

    public ModifyTableDialog(Window owner, SchemaNode table, Driver driver, List<String> tableNames) {
        Objects.requireNonNull(table, "table");
        this.model = TableDesignerModel.from(table);
        this.driver = driver == null ? Driver.MYSQL : driver;
        this.tableNames = FXCollections.observableArrayList(tableNames == null ? List.of() : tableNames);
        this.chrome = new DialogChrome(this, 720, 480);

        String qualified = table.qualifiedName();
        initStyle(StageStyle.UNDECORATED);
        setTitle("Modify Table");
        setHeaderText(null);
        setGraphic(null);
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        getDialogPane().getButtonTypes().setAll(OPEN_IN_EDITOR, ButtonType.CANCEL);
        openButton = getDialogPane().lookupButton(OPEN_IN_EDITOR);
        openButton.getStyleClass().addAll(Styles.ACCENT, "modify-table-open");

        getDialogPane().getStyleClass().add("modify-table-dialog");
        getDialogPane().getStylesheets().addAll(appStylesheet(), editorStylesheet());
        getDialogPane().setContent(buildRoot(qualified));
        getDialogPane().setPrefSize(920, 640);
        getDialogPane().setMinSize(720, 480);

        setOnShown(event -> chrome.installResize());
        setResultConverter(button -> {
            if (button != OPEN_IN_EDITOR || !model.dirty()) {
                return null;
            }
            return model.alterScript(this.driver);
        });
        refreshPreview();
    }

    public static List<String> tableNames(SchemaCache cache, String catalog) {
        List<String> names = new ArrayList<>();
        if (cache == null) {
            return names;
        }
        for (SchemaNode table : cache.tables(catalog)) {
            if (table.type() == NodeType.TABLE) {
                names.add(table.name());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private BorderPane buildRoot(String qualified) {
        Tab columnsTab = new Tab("Columns", columnsPane());
        Tab indexesTab = new Tab("Indexes", indexesPane());
        Tab fksTab = new Tab("Foreign keys", fksPane());
        for (Tab tab : List.of(columnsTab, indexesTab, fksTab)) {
            tab.setClosable(false);
        }
        TabPane tabs = new TabPane(columnsTab, indexesTab, fksTab);
        tabs.getStyleClass().add("modify-table-tabs");

        preview.getStyleClass().addAll("sql-editor", "modify-table-preview");
        preview.setEditable(false);
        preview.setWrapText(true);
        VirtualizedScrollPane<CodeArea> previewScroll = new VirtualizedScrollPane<>(
                preview, ScrollPane.ScrollBarPolicy.AS_NEEDED, ScrollPane.ScrollBarPolicy.AS_NEEDED);
        previewScroll.getStyleClass().add("modify-table-preview-scroll");
        VBox.setVgrow(previewScroll, Priority.ALWAYS);

        Label previewHint = new Label("Nothing runs until you execute this ALTER in the editor");
        previewHint.getStyleClass().add("modify-table-hint");
        previewHint.setAlignment(Pos.CENTER_LEFT);
        previewHint.setMaxWidth(Double.MAX_VALUE);

        VBox previewBox = new VBox(6, previewHint, previewScroll);
        previewBox.getStyleClass().add("modify-table-preview-box");
        SplitPane.setResizableWithParent(previewBox, false);

        SplitPane split = new SplitPane(tabs, previewBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.62);
        VBox.setVgrow(split, Priority.ALWAYS);

        Label subtitle = new Label(qualified);
        subtitle.getStyleClass().add("modify-table-subtitle");

        VBox body = new VBox(10, subtitle, split);
        body.getStyleClass().add("modify-table-body");

        BorderPane root = new BorderPane();
        root.getStyleClass().add("modify-table-root");
        root.setTop(chrome.titleBar("Modify Table"));
        root.setCenter(body);
        return root;
    }

    private VBox columnsPane() {
        columnsTable.setEditable(true);
        columnsTable.getStyleClass().add("modify-table-grid");
        columnsTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        columnsTable.setItems(FXCollections.observableArrayList(model.columns()));
        columnsTable.setRowFactory(tv -> droppedRow(ColumnDraft::dropped, ColumnDraft::added));

        TableColumn<ColumnDraft, String> name = textColumn("Name", ColumnDraft::name, ColumnDraft::setName);
        TableColumn<ColumnDraft, String> type = new TableColumn<>("Type");
        type.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().dataType()));
        type.setCellFactory(col -> {
            ComboBoxTableCell<ColumnDraft, String> cell =
                    new ComboBoxTableCell<>(FXCollections.observableArrayList(COMMON_TYPES));
            cell.setComboBoxEditable(true);
            return cell;
        });
        type.setOnEditCommit(event -> {
            event.getRowValue().setDataType(event.getNewValue());
            refreshPreview();
            columnsTable.refresh();
        });
        TableColumn<ColumnDraft, Boolean> nullable = boolColumn(
                "Null", ColumnDraft::nullable, (draft, value) -> {
                    if (!draft.primaryKey()) {
                        draft.setNullable(value);
                    }
                });
        TableColumn<ColumnDraft, Boolean> pk = boolColumn("PK", ColumnDraft::primaryKey, ColumnDraft::setPrimaryKey);
        columnsTable.getColumns().setAll(List.of(name, type, nullable, pk));

        Button add = toolButton(Icons.newQuery(), "Add column");
        Button drop = toolButton(Icons.clear(), "Drop / Restore");
        Button up = toolButton(Icons.arrowUp(), "Move up");
        Button down = toolButton(Icons.arrowDown(), "Move down");
        add.setOnAction(event -> {
            model.addColumn();
            reloadColumns();
            columnsTable.getSelectionModel().selectLast();
            columnsTable.edit(columnsTable.getItems().size() - 1, name);
        });
        drop.setOnAction(event -> {
            model.removeColumn(columnsTable.getSelectionModel().getSelectedItem());
            reloadColumns();
        });
        up.setOnAction(event -> moveSelected(-1));
        down.setOnAction(event -> moveSelected(1));
        return paneWithToolbar(columnsTable, add, drop, up, down);
    }

    private VBox indexesPane() {
        indexesTable.setEditable(true);
        indexesTable.getStyleClass().add("modify-table-grid");
        indexesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        indexesTable.setItems(FXCollections.observableArrayList(model.indexes()));
        indexesTable.setRowFactory(tv -> droppedRow(IndexDraft::dropped, IndexDraft::added));

        TableColumn<IndexDraft, String> name = textColumn("Name", IndexDraft::name, IndexDraft::setName);
        TableColumn<IndexDraft, Boolean> unique = boolColumn("Unique", IndexDraft::unique, IndexDraft::setUnique);
        TableColumn<IndexDraft, String> cols = textColumn("Columns", IndexDraft::columns, IndexDraft::setColumns);
        indexesTable.getColumns().setAll(List.of(name, unique, cols));

        Button add = toolButton(Icons.newQuery(), "Add index");
        Button drop = toolButton(Icons.clear(), "Drop / Restore");
        add.setOnAction(event -> {
            model.addIndex();
            indexesTable.getItems().setAll(model.indexes());
            indexesTable.getSelectionModel().selectLast();
            refreshPreview();
        });
        drop.setOnAction(event -> {
            model.removeIndex(indexesTable.getSelectionModel().getSelectedItem());
            indexesTable.getItems().setAll(model.indexes());
            indexesTable.refresh();
            refreshPreview();
        });
        return paneWithToolbar(indexesTable, add, drop);
    }

    private VBox fksPane() {
        fksTable.setEditable(true);
        fksTable.getStyleClass().add("modify-table-grid");
        fksTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        fksTable.setItems(FXCollections.observableArrayList(model.foreignKeys()));
        fksTable.setRowFactory(tv -> droppedRow(FkDraft::dropped, FkDraft::added));

        TableColumn<FkDraft, String> name = textColumn("Name", FkDraft::name, FkDraft::setName);
        TableColumn<FkDraft, String> cols = textColumn("Columns", FkDraft::columns, FkDraft::setColumns);
        TableColumn<FkDraft, String> refTable = new TableColumn<>("References");
        refTable.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().refTable()));
        refTable.setCellFactory(col -> {
            ComboBoxTableCell<FkDraft, String> cell = new ComboBoxTableCell<>(tableNames);
            cell.setComboBoxEditable(true);
            return cell;
        });
        refTable.setOnEditCommit(event -> {
            event.getRowValue().setRefTable(event.getNewValue());
            refreshPreview();
            fksTable.refresh();
        });
        TableColumn<FkDraft, String> refCols = textColumn("Ref. columns", FkDraft::refColumns, FkDraft::setRefColumns);
        fksTable.getColumns().setAll(List.of(name, cols, refTable, refCols));

        Button add = toolButton(Icons.newQuery(), "Add foreign key");
        Button drop = toolButton(Icons.clear(), "Drop / Restore");
        add.setOnAction(event -> {
            model.addForeignKey();
            fksTable.getItems().setAll(model.foreignKeys());
            fksTable.getSelectionModel().selectLast();
            refreshPreview();
        });
        drop.setOnAction(event -> {
            model.removeForeignKey(fksTable.getSelectionModel().getSelectedItem());
            fksTable.getItems().setAll(model.foreignKeys());
            fksTable.refresh();
            refreshPreview();
        });
        return paneWithToolbar(fksTable, add, drop);
    }

    private void moveSelected(int delta) {
        int index = columnsTable.getSelectionModel().getSelectedIndex();
        model.moveColumn(index, delta);
        reloadColumns();
        int next = index + delta;
        if (next >= 0 && next < columnsTable.getItems().size()) {
            columnsTable.getSelectionModel().select(next);
        }
    }

    private void reloadColumns() {
        columnsTable.getItems().setAll(model.columns());
        columnsTable.refresh();
        refreshPreview();
    }

    private void refreshPreview() {
        String sql = Objects.requireNonNullElse(model.alterScript(driver), "");
        if (!sql.equals(preview.getText())) {
            preview.replaceText(sql);
        }
        preview.setStyleSpans(0, SqlSyntaxHighlighter.computeHighlighting(sql, List.of(), driver));
        if (openButton != null) {
            openButton.setDisable(!model.dirty());
        }
    }

    private VBox paneWithToolbar(TableView<?> table, Button... buttons) {
        HBox bar = new HBox(2, buttons);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("modify-table-toolbar");
        VBox pane = new VBox(8, bar, table);
        pane.setPadding(new Insets(8, 0, 0, 0));
        VBox.setVgrow(table, Priority.ALWAYS);
        return pane;
    }

    private static Button toolButton(Node graphic, String tooltip) {
        Button button = new Button();
        button.setGraphic(graphic);
        button.getStyleClass().addAll(Styles.FLAT, "modify-table-tool-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setFocusTraversable(false);
        return button;
    }

    private <S> TableColumn<S, String> textColumn(
            String title,
            Function<S, String> getter,
            BiConsumer<S, String> setter) {
        TableColumn<S, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        column.setCellFactory(TextFieldTableCell.forTableColumn());
        column.setOnEditCommit(event -> {
            setter.accept(event.getRowValue(), event.getNewValue());
            event.getTableView().refresh();
            refreshPreview();
        });
        return column;
    }

    private <S> TableColumn<S, Boolean> boolColumn(
            String title,
            Function<S, Boolean> getter,
            BiConsumer<S, Boolean> setter) {
        TableColumn<S, Boolean> column = new TableColumn<>(title);
        column.setPrefWidth(70);
        column.setMaxWidth(90);
        column.setCellValueFactory(cd -> {
            S row = cd.getValue();
            SimpleBooleanProperty property = new SimpleBooleanProperty(Boolean.TRUE.equals(getter.apply(row)));
            property.addListener((obs, previous, next) -> {
                setter.accept(row, next);
                cd.getTableView().refresh();
                refreshPreview();
            });
            return property;
        });
        column.setCellFactory(CheckBoxTableCell.forTableColumn(column));
        column.setEditable(true);
        column.setStyle("-fx-alignment: CENTER;");
        return column;
    }

    private static <S> TableRow<S> droppedRow(
            Function<S, Boolean> dropped,
            Function<S, Boolean> added) {
        return new TableRow<>() {
            @Override
            protected void updateItem(S item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("dropped-row", "added-row");
                if (item == null || empty) {
                    return;
                }
                if (Boolean.TRUE.equals(dropped.apply(item))) {
                    getStyleClass().add("dropped-row");
                } else if (Boolean.TRUE.equals(added.apply(item))) {
                    getStyleClass().add("added-row");
                }
            }
        };
    }

    private static String appStylesheet() {
        return Objects.requireNonNull(
                        ModifyTableDialog.class.getResource("/com/lazaro/sqlide/css/app.css"),
                        "app.css is missing from the classpath")
                .toExternalForm();
    }

    private static String editorStylesheet() {
        return Objects.requireNonNull(
                        ModifyTableDialog.class.getResource("/com/lazaro/sqlide/css/sql-editor.css"),
                        "sql-editor.css is missing from the classpath")
                .toExternalForm();
    }
}
