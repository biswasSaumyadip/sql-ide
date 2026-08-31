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
import javafx.application.Platform;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.geometry.Side;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Visual table designer (columns / indexes / FKs) that emits {@code ALTER TABLE}
 * for review in the editor. Nothing is executed from this dialog.
 */
public final class ModifyTableDialog extends Dialog<String> {

    private static final List<String> COMMON_TYPES = List.of(
            "INT", "BIGINT", "SMALLINT", "TINYINT",
            "VARCHAR", "CHAR", "VARBINARY", "BINARY",
            "TEXT", "LONGTEXT", "JSON",
            "DATETIME", "TIMESTAMP", "DATE", "TIME",
            "DECIMAL", "NUMERIC", "DOUBLE", "FLOAT", "BOOLEAN", "BLOB");

    private static final Set<String> LENGTH_TYPES = Set.of(
            "VARCHAR", "CHAR", "VARBINARY", "BINARY", "DECIMAL", "NUMERIC", "FLOAT", "DOUBLE", "BIT");

    private static final List<String> INDEX_TYPES = List.of("BTREE", "HASH");
    private static final List<String> FK_ACTIONS = List.of("CASCADE", "RESTRICT", "SET NULL", "NO ACTION");

    private static final ButtonType OPEN_IN_EDITOR =
            new ButtonType("Open in Editor", ButtonBar.ButtonData.OK_DONE);

    private final TableDesignerModel model;
    private final Driver driver;
    private final Map<String, List<String>> columnsByTable;
    private final ObservableList<String> tableNames;
    private final DialogChrome chrome;

    private final TableView<ColumnDraft> columnsTable = new TableView<>();
    private final TableView<IndexDraft> indexesTable = new TableView<>();
    private final TableView<FkDraft> fksTable = new TableView<>();
    private final CodeArea preview = new CodeArea();
    private Node openButton;

    public ModifyTableDialog(Window owner, SchemaNode table, Driver driver, List<String> tableNames) {
        this(owner, table, driver, namesToEmptyColumns(tableNames));
    }

    public ModifyTableDialog(
            Window owner,
            SchemaNode table,
            Driver driver,
            Map<String, List<String>> columnsByTable) {
        Objects.requireNonNull(table, "table");
        this.model = TableDesignerModel.from(table);
        this.driver = driver == null ? Driver.MYSQL : driver;
        this.columnsByTable = columnsByTable == null ? Map.of() : Map.copyOf(columnsByTable);
        this.tableNames = FXCollections.observableArrayList(this.columnsByTable.keySet());
        this.tableNames.sort(String.CASE_INSENSITIVE_ORDER);
        this.chrome = new DialogChrome(this, 780, 520);

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
        getDialogPane().setPrefSize(1100, 680);
        getDialogPane().setMinSize(780, 520);

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
        List<String> names = new ArrayList<>(columnsByTable(cache, catalog).keySet());
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public static Map<String, List<String>> columnsByTable(SchemaCache cache, String catalog) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (cache == null) {
            return map;
        }
        for (SchemaNode table : cache.tables(catalog)) {
            if (table.type() != NodeType.TABLE) {
                continue;
            }
            List<String> cols = new ArrayList<>();
            for (SchemaNode column : TableDesignerModel.columnNodes(table)) {
                cols.add(column.name());
            }
            map.put(table.name(), List.copyOf(cols));
        }
        return map;
    }

    private static Map<String, List<String>> namesToEmptyColumns(List<String> tableNames) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        if (tableNames == null) {
            return map;
        }
        for (String name : tableNames) {
            if (name != null && !name.isBlank()) {
                map.put(name, List.of());
            }
        }
        return map;
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
        columnsTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        columnsTable.setItems(FXCollections.observableArrayList(model.columns()));
        columnsTable.setRowFactory(tv -> dirtyRow(
                ColumnDraft::dropped, ColumnDraft::added, ColumnDraft::modified));

        TableColumn<ColumnDraft, String> name = liveTextColumn(
                "Name", 140, ColumnDraft::name, ColumnDraft::setName, this::reloadDependentGrids);
        TableColumn<ColumnDraft, String> type = new TableColumn<>("Type");
        type.setPrefWidth(150);
        type.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().dataType()));
        type.setCellFactory(col -> new TypeComboCell());
        TableColumn<ColumnDraft, Boolean> nullable = boolColumn(
                "Null", ColumnDraft::nullable, (draft, value) -> {
                    if (!draft.primaryKey() && !draft.autoIncrement()) {
                        draft.setNullable(value);
                    }
                });
        TableColumn<ColumnDraft, Boolean> pk = boolColumn("PK", ColumnDraft::primaryKey, ColumnDraft::setPrimaryKey);
        TableColumn<ColumnDraft, Boolean> ai = boolColumn("AI", ColumnDraft::autoIncrement, ColumnDraft::setAutoIncrement);
        TableColumn<ColumnDraft, String> def = liveTextColumn(
                "Default Value", 140, ColumnDraft::defaultValue, ColumnDraft::setDefaultValue, null);
        TableColumn<ColumnDraft, String> comment = liveTextColumn(
                "Comment", 180, ColumnDraft::comment, ColumnDraft::setComment, null);
        columnsTable.getColumns().setAll(List.of(name, type, nullable, pk, ai, def, comment));

        Button add = toolButton(Icons.newQuery(), "Add column");
        Button drop = toolButton(Icons.clear(), "Drop / Restore");
        Button up = toolButton(Icons.arrowUp(), "Move up");
        Button down = toolButton(Icons.arrowDown(), "Move down");
        add.setOnAction(event -> {
            model.addColumn();
            reloadColumns();
            columnsTable.getSelectionModel().selectLast();
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
        indexesTable.setRowFactory(tv -> dirtyRow(
                IndexDraft::dropped, IndexDraft::added, IndexDraft::modified));

        TableColumn<IndexDraft, String> name = liveTextColumn(
                "Name", 160, IndexDraft::name, IndexDraft::setName, null);
        TableColumn<IndexDraft, Boolean> unique = boolColumn("Unique", IndexDraft::unique, IndexDraft::setUnique);
        TableColumn<IndexDraft, String> type = comboColumn(
                "Index Type", INDEX_TYPES, false, IndexDraft::type, IndexDraft::setType, null);
        type.setPrefWidth(110);
        type.setMaxWidth(140);
        TableColumn<IndexDraft, String> cols = checkComboColumn(
                "Columns", IndexDraft::columns, IndexDraft::setColumns, this::liveColumnNames);
        indexesTable.getColumns().setAll(List.of(name, unique, type, cols));

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
        fksTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        fksTable.setItems(FXCollections.observableArrayList(model.foreignKeys()));
        fksTable.setRowFactory(tv -> dirtyRow(
                FkDraft::dropped, FkDraft::added, FkDraft::modified));

        TableColumn<FkDraft, String> name = liveTextColumn(
                "Name", 140, FkDraft::name, FkDraft::setName, null);
        TableColumn<FkDraft, String> cols = checkComboColumn(
                "Columns", FkDraft::columns, FkDraft::setColumns, this::liveColumnNames);
        cols.setPrefWidth(160);
        TableColumn<FkDraft, String> refTable = comboColumn(
                "Target table",
                tableNames,
                true,
                FkDraft::refTable,
                FkDraft::setRefTable,
                draft -> fksTable.refresh());
        refTable.setPrefWidth(150);
        TableColumn<FkDraft, String> refCols = new TableColumn<>("Target columns");
        refCols.setPrefWidth(160);
        refCols.setCellValueFactory(cd -> new SimpleStringProperty(cd.getValue().refColumns()));
        refCols.setCellFactory(col -> new CheckComboCell<>(
                FkDraft::refColumns,
                FkDraft::setRefColumns,
                () -> List.of(),
                this::columnsOfReferencedTable));
        TableColumn<FkDraft, String> onUpdate = comboColumn(
                "ON UPDATE", FK_ACTIONS, false, FkDraft::onUpdate, FkDraft::setOnUpdate, null);
        onUpdate.setPrefWidth(120);
        TableColumn<FkDraft, String> onDelete = comboColumn(
                "ON DELETE", FK_ACTIONS, false, FkDraft::onDelete, FkDraft::setOnDelete, null);
        onDelete.setPrefWidth(120);
        fksTable.getColumns().setAll(List.of(name, cols, refTable, refCols, onUpdate, onDelete));

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
        reloadDependentGrids();
        refreshPreview();
    }

    private void reloadDependentGrids() {
        indexesTable.refresh();
        fksTable.refresh();
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

    private List<String> liveColumnNames() {
        List<String> names = new ArrayList<>();
        for (ColumnDraft column : model.columns()) {
            if (!column.dropped() && !column.name().isBlank()) {
                names.add(column.name());
            }
        }
        return names;
    }

    private List<String> columnsOfTable(String table) {
        if (table == null || table.isBlank()) {
            return List.of();
        }
        List<String> exact = columnsByTable.get(table);
        if (exact != null) {
            return exact;
        }
        for (Map.Entry<String, List<String>> entry : columnsByTable.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(table)) {
                return entry.getValue();
            }
        }
        return List.of();
    }

    private List<String> columnsOfReferencedTable(FkDraft draft) {
        return draft == null ? List.of() : columnsOfTable(draft.refTable());
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

    private <S> TableColumn<S, String> liveTextColumn(
            String title,
            double prefWidth,
            Function<S, String> getter,
            BiConsumer<S, String> setter,
            Runnable afterChange) {
        TableColumn<S, String> column = new TableColumn<>(title);
        column.setPrefWidth(prefWidth);
        column.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        column.setCellFactory(col -> new LiveTextCell<>(getter, setter, afterChange));
        return column;
    }

    private <S> TableColumn<S, String> comboColumn(
            String title,
            List<String> items,
            boolean editable,
            Function<S, String> getter,
            BiConsumer<S, String> setter,
            java.util.function.Consumer<S> afterChange) {
        TableColumn<S, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        column.setCellFactory(col -> new ComboCell<>(items, editable, getter, setter, afterChange));
        return column;
    }

    private <S> TableColumn<S, String> checkComboColumn(
            String title,
            Function<S, String> getter,
            BiConsumer<S, String> setter,
            Supplier<List<String>> options) {
        TableColumn<S, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cd -> new SimpleStringProperty(getter.apply(cd.getValue())));
        column.setCellFactory(col -> new CheckComboCell<>(getter, setter, options, row -> options.get()));
        return column;
    }

    private <S> TableColumn<S, Boolean> boolColumn(
            String title,
            Function<S, Boolean> getter,
            BiConsumer<S, Boolean> setter) {
        TableColumn<S, Boolean> column = new TableColumn<>(title);
        column.setPrefWidth(56);
        column.setMinWidth(48);
        column.setMaxWidth(72);
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

    private static <S> TableRow<S> dirtyRow(
            Function<S, Boolean> dropped,
            Function<S, Boolean> added,
            Function<S, Boolean> modified) {
        return new TableRow<>() {
            @Override
            protected void updateItem(S item, boolean empty) {
                super.updateItem(item, empty);
                getStyleClass().removeAll("dropped-row", "added-row", "modified-row");
                if (item == null || empty) {
                    return;
                }
                if (Boolean.TRUE.equals(dropped.apply(item))) {
                    getStyleClass().add("dropped-row");
                } else if (Boolean.TRUE.equals(added.apply(item))) {
                    getStyleClass().add("added-row");
                } else if (Boolean.TRUE.equals(modified.apply(item))) {
                    getStyleClass().add("modified-row");
                }
            }
        };
    }

    private static boolean dropped(Object row) {
        return switch (row) {
            case ColumnDraft column -> column.dropped();
            case IndexDraft index -> index.dropped();
            case FkDraft fk -> fk.dropped();
            default -> false;
        };
    }

    private static void applyDirtyStyle(TableRow<?> row, Object item) {
        if (row == null) {
            return;
        }
        row.getStyleClass().removeAll("dropped-row", "added-row", "modified-row");
        if (item == null) {
            return;
        }
        boolean isDropped = dropped(item);
        boolean added = switch (item) {
            case ColumnDraft column -> column.added();
            case IndexDraft index -> index.added();
            case FkDraft fk -> fk.added();
            default -> false;
        };
        boolean modified = switch (item) {
            case ColumnDraft column -> column.modified();
            case IndexDraft index -> index.modified();
            case FkDraft fk -> fk.modified();
            default -> false;
        };
        if (isDropped) {
            row.getStyleClass().add("dropped-row");
        } else if (added) {
            row.getStyleClass().add("added-row");
        } else if (modified) {
            row.getStyleClass().add("modified-row");
        }
    }

    private static List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> parts = new ArrayList<>();
        for (String part : csv.split(",")) {
            String name = part.strip();
            if (!name.isEmpty()) {
                parts.add(name);
            }
        }
        return parts;
    }

    private static String joinCsv(Iterable<String> names) {
        StringBuilder out = new StringBuilder();
        for (String name : names) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(name);
        }
        return out.toString();
    }

    private static String baseTypeName(String type) {
        if (type == null) {
            return "";
        }
        String stripped = type.strip();
        int paren = stripped.indexOf('(');
        return paren < 0 ? stripped : stripped.substring(0, paren).strip();
    }

    private static boolean needsLength(String type) {
        return LENGTH_TYPES.contains(baseTypeName(type).toUpperCase(Locale.ROOT));
    }

    private static String withLengthPlaceholder(String type) {
        if (type == null || type.isBlank()) {
            return "";
        }
        String stripped = type.strip();
        if (stripped.contains("(") || !needsLength(stripped)) {
            return stripped;
        }
        return stripped + "()";
    }

    private static void placeCaretInsideParens(TextField editor, String type) {
        if (editor == null || type == null) {
            return;
        }
        int open = type.indexOf('(');
        int close = type.indexOf(')', open + 1);
        if (open < 0 || close < 0) {
            return;
        }
        editor.setText(type);
        editor.requestFocus();
        if (close == open + 1) {
            editor.positionCaret(open + 1);
        } else {
            editor.selectRange(open + 1, close);
        }
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

    private final class LiveTextCell<S> extends TableCell<S, String> {
        private final TextField field = new TextField();
        private final Function<S, String> getter;
        private final BiConsumer<S, String> setter;
        private final Runnable afterChange;
        private boolean updating;

        private LiveTextCell(Function<S, String> getter, BiConsumer<S, String> setter, Runnable afterChange) {
            this.getter = getter;
            this.setter = setter;
            this.afterChange = afterChange;
            field.getStyleClass().add("modify-table-cell-field");
            field.textProperty().addListener((obs, previous, next) -> {
                if (updating || isEmpty() || getTableRow() == null || getTableRow().getItem() == null) {
                    return;
                }
                S row = getTableRow().getItem();
                if (!Objects.equals(getter.apply(row), next)) {
                    setter.accept(row, next);
                    applyDirtyStyle(getTableRow(), row);
                    refreshPreview();
                    if (afterChange != null) {
                        afterChange.run();
                    }
                }
            });
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
                return;
            }
            S row = getTableRow().getItem();
            String value = Objects.requireNonNullElse(getter.apply(row), "");
            updating = true;
            try {
                if (!Objects.equals(field.getText(), value)) {
                    field.setText(value);
                }
            } finally {
                updating = false;
            }
            field.setDisable(dropped(row));
            setGraphic(field);
        }
    }

    private final class ComboCell<S> extends TableCell<S, String> {
        private final ComboBox<String> combo = new ComboBox<>();
        private final Function<S, String> getter;
        private final BiConsumer<S, String> setter;
        private final java.util.function.Consumer<S> afterChange;
        private boolean updating;

        private ComboCell(
                List<String> items,
                boolean editable,
                Function<S, String> getter,
                BiConsumer<S, String> setter,
                java.util.function.Consumer<S> afterChange) {
            this.getter = getter;
            this.setter = setter;
            this.afterChange = afterChange;
            combo.getItems().setAll(items);
            combo.setEditable(editable);
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.getStyleClass().add("modify-table-combo");
            combo.valueProperty().addListener((obs, previous, next) -> commit(next));
            if (editable) {
                combo.getEditor().focusedProperty().addListener((obs, was, focused) -> {
                    if (!focused) {
                        commit(combo.getEditor().getText());
                    }
                });
            }
        }

        private void commit(String raw) {
            if (updating || isEmpty() || getTableRow() == null || getTableRow().getItem() == null) {
                return;
            }
            S row = getTableRow().getItem();
            String next = raw == null ? "" : raw.strip();
            if (Objects.equals(getter.apply(row), next)) {
                return;
            }
            setter.accept(row, next);
            applyDirtyStyle(getTableRow(), row);
            refreshPreview();
            if (afterChange != null) {
                afterChange.accept(row);
            }
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
                return;
            }
            S row = getTableRow().getItem();
            String value = Objects.requireNonNullElse(getter.apply(row), "");
            updating = true;
            try {
            if (combo.isEditable()) {
                combo.getEditor().setText(value);
            }
            if (!value.isBlank() && !combo.getItems().contains(value)) {
                combo.getItems().add(value);
            }
            combo.setValue(value.isBlank() ? null : value);
            } finally {
                updating = false;
            }
            combo.setDisable(dropped(row));
            setGraphic(combo);
        }
    }

    private final class TypeComboCell extends TableCell<ColumnDraft, String> {
        private final ObservableList<String> all = FXCollections.observableArrayList(COMMON_TYPES);
        private final FilteredList<String> filtered = new FilteredList<>(all, unused -> true);
        private final ComboBox<String> combo = new ComboBox<>(filtered);
        private boolean updating;

        private TypeComboCell() {
            combo.setEditable(true);
            combo.setMaxWidth(Double.MAX_VALUE);
            combo.getStyleClass().add("modify-table-type-combo");
            combo.getEditor().textProperty().addListener((obs, previous, text) -> {
                if (updating) {
                    return;
                }
                String query = text == null ? "" : text.strip();
                filtered.setPredicate(item -> query.isEmpty()
                        || item.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)));
                if (combo.isFocused() && !combo.isShowing() && !filtered.isEmpty()) {
                    combo.show();
                }
            });
            combo.setOnAction(event -> commitFromPick(combo.getValue()));
            combo.getEditor().focusedProperty().addListener((obs, was, focused) -> {
                if (!focused) {
                    commitTyped(combo.getEditor().getText());
                }
            });
        }

        private void commitFromPick(String raw) {
            if (updating || isEmpty() || getTableRow() == null || getTableRow().getItem() == null) {
                return;
            }
            String next = withLengthPlaceholder(raw);
            applyType(next, needsLength(next) && next.contains("()"));
        }

        private void commitTyped(String raw) {
            if (updating || isEmpty() || getTableRow() == null || getTableRow().getItem() == null) {
                return;
            }
            applyType(raw == null ? "" : raw.strip(), false);
        }

        private void applyType(String next, boolean placeCaret) {
            ColumnDraft draft = getTableRow().getItem();
            if (!Objects.equals(draft.dataType(), next)) {
                draft.setDataType(next);
                applyDirtyStyle(getTableRow(), draft);
                refreshPreview();
            }
            updating = true;
            try {
                filtered.setPredicate(unused -> true);
                combo.getEditor().setText(next);
                if (!next.isBlank() && all.contains(next)) {
                    combo.setValue(next);
                }
            } finally {
                updating = false;
            }
            if (placeCaret) {
                Platform.runLater(() -> placeCaretInsideParens(combo.getEditor(), next));
            }
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
                return;
            }
            ColumnDraft draft = getTableRow().getItem();
            String value = Objects.requireNonNullElse(draft.dataType(), "");
            updating = true;
            try {
                filtered.setPredicate(unused -> true);
                combo.getEditor().setText(value);
                combo.setValue(all.contains(value) ? value : null);
            } finally {
                updating = false;
            }
            combo.setDisable(draft.dropped());
            setGraphic(combo);
        }
    }

    private final class CheckComboCell<S> extends TableCell<S, String> {
        private final MenuButton button = new MenuButton();
        private final Function<S, String> getter;
        private final BiConsumer<S, String> setter;
        private final Supplier<List<String>> sharedOptions;
        private final Function<S, List<String>> rowOptions;

        private CheckComboCell(
                Function<S, String> getter,
                BiConsumer<S, String> setter,
                Supplier<List<String>> sharedOptions,
                Function<S, List<String>> rowOptions) {
            this.getter = getter;
            this.setter = setter;
            this.sharedOptions = sharedOptions;
            this.rowOptions = rowOptions;
            button.getStyleClass().add("modify-table-check-combo");
            button.setMaxWidth(Double.MAX_VALUE);
            button.setPopupSide(Side.BOTTOM);
        }

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || getTableRow() == null || getTableRow().getItem() == null) {
                setGraphic(null);
                return;
            }
            rebuild(getTableRow().getItem());
            setGraphic(button);
        }

        private void rebuild(S draft) {
            List<String> selected = parseCsv(getter.apply(draft));
            Set<String> selectedSet = new LinkedHashSet<>();
            for (String name : selected) {
                selectedSet.add(name.toLowerCase(Locale.ROOT));
            }
            LinkedHashSet<String> options = new LinkedHashSet<>();
            List<String> supplied = rowOptions != null ? rowOptions.apply(draft) : List.of();
            if (supplied == null || supplied.isEmpty()) {
                supplied = sharedOptions.get();
            }
            if (supplied != null) {
                options.addAll(supplied);
            }
            options.addAll(selected);
            button.getItems().clear();
            for (String option : options) {
                CheckMenuItem check = new CheckMenuItem(option);
                check.setSelected(selectedSet.contains(option.toLowerCase(Locale.ROOT)));
                check.setOnAction(event -> {
                    applyChecks(draft);
                    event.consume();
                    Platform.runLater(() -> {
                        if (!button.isShowing()) {
                            button.show();
                        }
                    });
                });
                button.getItems().add(check);
            }
            button.setText(selected.isEmpty() ? "Select columns" : joinCsv(selected));
            button.setDisable(dropped(draft) || options.isEmpty());
        }

        private void applyChecks(S draft) {
            List<String> next = new ArrayList<>();
            for (var menuItem : button.getItems()) {
                if (menuItem instanceof CheckMenuItem check && check.isSelected()) {
                    next.add(check.getText());
                }
            }
            setter.accept(draft, joinCsv(next));
            button.setText(next.isEmpty() ? "Select columns" : joinCsv(next));
            applyDirtyStyle(getTableRow(), draft);
            refreshPreview();
        }
    }
}
