package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds DataGrip-style, node-specific context menus for the Database tree.
 */
final class SchemaTreeContextMenus {

    record Actions(
            Runnable newQueryConsole,
            Runnable newDataSource,
            Consumer<String> insertSql,
            Consumer<SqlTemplateGenerator.Template> openTemplate,
            Consumer<TreeItem<SchemaNode>> viewObject,
            Consumer<TreeItem<SchemaNode>> showDiagram,
            Consumer<TreeItem<SchemaNode>> openData,
            Consumer<TreeItem<SchemaNode>> importData,
            Consumer<TreeItem<SchemaNode>> transferData,
            Consumer<TreeItem<SchemaNode>> modifyTable,
            Consumer<TreeItem<SchemaNode>> refreshItem,
            Runnable refreshAll,
            Consumer<TreeItem<SchemaNode>> editConnection,
            Runnable disconnect,
            Runnable reconnect,
            Consumer<TreeItem<SchemaNode>> removeConnection,
            Consumer<String> dumpToFile
    ) {
    }

    private final Supplier<TreeItem<SchemaNode>> selection;
    private final Actions actions;
    private final ContextMenu menu = new ContextMenu();

    SchemaTreeContextMenus(Supplier<TreeItem<SchemaNode>> selection, Actions actions) {
        this.selection = Objects.requireNonNull(selection);
        this.actions = Objects.requireNonNull(actions);
        menu.getStyleClass().add("schema-context-menu");
    }

    ContextMenu menu() {
        return menu;
    }

    /**
     * Rebuilds items for the current selection. Must run before the menu is
     * shown — replacing items in {@code onShowing} aborts the popup in JavaFX.
     */
    void prepare() {
        TreeItem<SchemaNode> item = selection.get();
        SchemaNode node = item == null ? null : item.getValue();
        if (node == null || node.metadataFlag("__placeholder")) {
            menu.getItems().setAll(item(new MenuItem("No actions"), false));
            return;
        }
        List<MenuItem> items = switch (node.type()) {
            case DATA_SOURCE -> connectionMenu(item);
            case DATABASE, SCHEMA -> schemaMenu(item);
            case FOLDER -> folderMenu(item);
            case TABLE -> tableMenu(item);
            case VIEW -> viewMenu(item);
            case COLUMN -> columnMenu(item);
            case KEY, INDEX -> keyOrIndexMenu(item);
        };
        menu.getItems().setAll(items);
    }

    private List<MenuItem> connectionMenu(TreeItem<SchemaNode> item) {
        boolean active = item.getValue().metadataFlag(SchemaNode.META_ACTIVE);

        Menu neu = new Menu("+ New");
        neu.getItems().addAll(
                action("Query Console", new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                        actions.newQueryConsole),
                action("Schema / Database", null, () -> open(SqlTemplateGenerator.newSchema())),
                action("Data Source", null, actions.newDataSource));

        List<MenuItem> items = new ArrayList<>();
        items.add(neu);
        items.add(action("Refresh", new KeyCodeCombination(KeyCode.F5, KeyCombination.CONTROL_DOWN), actions.refreshAll));
        items.add(new SeparatorMenuItem());
        items.add(action("Edit Connection Properties\u2026", null, () -> actions.editConnection.accept(item)));
        if (active) {
            items.add(action("Disconnect", null, actions.disconnect));
            items.add(action("Reconnect", null, actions.reconnect));
        } else {
            items.add(action("Connect / Reconnect", null, actions.reconnect));
        }
        items.add(new SeparatorMenuItem());
        items.add(danger("Remove Connection", () -> actions.removeConnection.accept(item)));
        return items;
    }

    private List<MenuItem> schemaMenu(TreeItem<SchemaNode> item) {
        Menu neu = new Menu("+ New");
        neu.getItems().addAll(
                action("Query Console", new KeyCodeCombination(KeyCode.Q, KeyCombination.CONTROL_DOWN, KeyCombination.SHIFT_DOWN),
                        actions.newQueryConsole),
                action("Table", null, () -> open(SqlTemplateGenerator.newTable(SqlTemplateGenerator.schemaOf(item)))),
                action("View", null, () -> open(SqlTemplateGenerator.newView(SqlTemplateGenerator.schemaOf(item)))),
                action("Schema", null, () -> open(SqlTemplateGenerator.newSchema())));

        Menu scripts = new Menu("SQL Scripts");
        scripts.getItems().addAll(
                action("Generate DDL", null, () -> insert(SchemaObjectNames.generateDdl(item))),
                action("Dump to File\u2026", null, () -> {
                    String ddl = SchemaObjectNames.generateDdl(item);
                    if (ddl != null) {
                        actions.dumpToFile.accept(ddl);
                    }
                }));

        Menu diagrams = new Menu("Diagrams");
        diagrams.getItems().add(
                action("Show Visualization\u2026", null, () -> actions.showDiagram.accept(item)));

        return List.of(
                neu,
                action("Refresh", new KeyCodeCombination(KeyCode.F5, KeyCombination.CONTROL_DOWN),
                        () -> actions.refreshItem.accept(item)),
                new SeparatorMenuItem(),
                action("Copy Name", null, () -> copy(item.getValue().name())),
                action("Copy Qualified Name", null, () -> copy(SchemaObjectNames.qualifiedName(item))),
                scripts,
                diagrams,
                new SeparatorMenuItem(),
                danger("Drop Database\u2026", () -> insert(SchemaObjectNames.dropStatement(item))));
    }

    private List<MenuItem> tableMenu(TreeItem<SchemaNode> item) {
        Menu neu = new Menu("+ New");
        neu.getItems().addAll(
                action("Column", null, () -> actions.insertSql.accept(SchemaObjectNames.createColumnTemplate(item))),
                action("Index", null, () -> actions.insertSql.accept(SchemaObjectNames.createIndexTemplate(item))),
                action("Foreign Key", null, () -> actions.insertSql.accept(SchemaObjectNames.createForeignKeyTemplate(item))));

        Menu scripts = new Menu("SQL Scripts");
        scripts.getItems().addAll(
                action("Generate SELECT", null, () -> insert(SchemaObjectNames.generateSelect(item))),
                action("Generate INSERT", null, () -> insert(SchemaObjectNames.generateInsert(item))),
                action("Generate DDL", null, () -> {
                    String ddl = SchemaObjectNames.generateDdl(item);
                    if (ddl != null && ddl.contains("-- columns")) {
                        actions.viewObject.accept(item);
                    } else {
                        insert(ddl);
                    }
                }));

        Menu diagrams = new Menu("Diagrams");
        diagrams.getItems().add(
                action("Show Visualization\u2026", null, () -> actions.showDiagram.accept(item)));

        return List.of(
                neu,
                action("Edit Data", null, () -> actions.openData.accept(item)),
                action("Import Data\u2026", null, () -> actions.importData.accept(item)),
                action("Export / Transfer to Table\u2026", null, () -> actions.transferData.accept(item)),
                action("Modify Table\u2026", new KeyCodeCombination(KeyCode.F6, KeyCombination.CONTROL_DOWN),
                        () -> actions.modifyTable.accept(item)),
                action("Refresh", new KeyCodeCombination(KeyCode.F5, KeyCombination.CONTROL_DOWN),
                        () -> actions.refreshItem.accept(item)),
                new SeparatorMenuItem(),
                action("Select First 1000 Rows", null, () -> insert(SchemaObjectNames.selectFirstRows(item, 1000))),
                action("Jump to DDL", null, () -> actions.viewObject.accept(item)),
                action("Copy Name", null, () -> copy(item.getValue().name())),
                action("Copy Qualified Name", null, () -> copy(SchemaObjectNames.qualifiedName(item))),
                scripts,
                diagrams,
                new SeparatorMenuItem(),
                danger("Truncate Table\u2026", () -> insert(SchemaObjectNames.truncateStatement(item))),
                danger("Drop Table\u2026", () -> insert(SchemaObjectNames.dropStatement(item))));
    }

    private List<MenuItem> viewMenu(TreeItem<SchemaNode> item) {
        Menu scripts = new Menu("SQL Scripts");
        scripts.getItems().addAll(
                action("Generate SELECT", null, () -> insert(SchemaObjectNames.generateSelect(item))),
                action("Generate DDL", null, () -> {
                    actions.viewObject.accept(item);
                }));

        Menu diagrams = new Menu("Diagrams");
        diagrams.getItems().add(
                action("Show Visualization\u2026", null, () -> actions.showDiagram.accept(item)));

        return List.of(
                action("Browse Data", null, () -> actions.openData.accept(item)),
                action("Refresh", new KeyCodeCombination(KeyCode.F5, KeyCombination.CONTROL_DOWN),
                        () -> actions.refreshItem.accept(item)),
                new SeparatorMenuItem(),
                action("Select First 1000 Rows", null, () -> insert(SchemaObjectNames.selectFirstRows(item, 1000))),
                action("Jump to DDL", null, () -> actions.viewObject.accept(item)),
                action("Copy Name", null, () -> copy(item.getValue().name())),
                action("Copy Qualified Name", null, () -> copy(SchemaObjectNames.qualifiedName(item))),
                scripts,
                diagrams,
                new SeparatorMenuItem(),
                danger("Drop View\u2026", () -> insert(SchemaObjectNames.dropStatement(item))));
    }

    private List<MenuItem> columnMenu(TreeItem<SchemaNode> item) {
        return List.of(
                action("Copy Name", null, () -> copy(item.getValue().name())),
                action("Copy Qualified Name", null, () -> copy(SchemaObjectNames.qualifiedName(item))),
                action("Generate SELECT", null, () -> insert(SchemaObjectNames.generateSelect(item))));
    }

    private List<MenuItem> folderMenu(TreeItem<SchemaNode> item) {
        return List.of(
                action("Refresh", new KeyCodeCombination(KeyCode.F5, KeyCombination.CONTROL_DOWN),
                        () -> actions.refreshItem.accept(item)),
                new SeparatorMenuItem(),
                action("Copy Name", null, () -> copy(item.getValue().name())));
    }

    private List<MenuItem> keyOrIndexMenu(TreeItem<SchemaNode> item) {
        return List.of(
                action("Copy Name", null, () -> copy(item.getValue().name())),
                action("Copy Detail", null, () -> {
                    String columns = item.getValue().metadata(SchemaNode.META_COLUMNS);
                    copy(columns == null ? item.getValue().name() : item.getValue().name() + " (" + columns + ")");
                }));
    }

    private void insert(String sql) {
        if (sql != null && !sql.isBlank()) {
            actions.insertSql.accept(sql);
        }
    }

    private void open(SqlTemplateGenerator.Template template) {
        if (template != null && !template.sql().isBlank()) {
            actions.openTemplate.accept(template);
        }
    }

    private void copy(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        javafx.scene.input.ClipboardContent content = new javafx.scene.input.ClipboardContent();
        content.putString(text);
        javafx.scene.input.Clipboard.getSystemClipboard().setContent(content);
    }

    private static MenuItem action(String text, KeyCombination accelerator, Runnable handler) {
        MenuItem item = new MenuItem(text);
        if (accelerator != null) {
            item.setAccelerator(accelerator);
        }
        item.setOnAction(event -> {
            if (handler != null) {
                handler.run();
            }
        });
        return item;
    }

    private static MenuItem danger(String text, Runnable handler) {
        MenuItem item = action(text, null, handler);
        item.getStyleClass().add("menu-item-danger");
        return item;
    }

    private static MenuItem item(MenuItem menuItem, boolean enabled) {
        menuItem.setDisable(!enabled);
        return menuItem;
    }
}
