package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.snippets.SnippetStore;
import com.lazaro.sqlide.core.snippets.SnippetStore.Snippet;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.Optional;
import java.util.function.Consumer;

/** Saved SQL templates for the sidebar. */
public final class SnippetsPane extends VBox {

    private final SnippetStore store;
    private final TextField search = new TextField();
    private final ListView<Snippet> list = new ListView<>();
    private Consumer<Snippet> onInsert = snippet -> { };
    private java.util.function.Supplier<String> sqlSupplier = () -> "";

    public SnippetsPane(SnippetStore store) {
        this.store = store;
        getStyleClass().add("snippets-pane");
        setSpacing(0);

        Label headerLabel = new Label("SNIPPETS");
        headerLabel.getStyleClass().add("panel-header");
        Button add = new Button("Save");
        add.getStyleClass().add("panel-header-action");
        add.setTooltip(new Tooltip("Save current editor SQL as a snippet"));
        add.setOnAction(event -> saveFromEditor());
        HBox header = new HBox(8, headerLabel, add);
        header.getStyleClass().add("panel-header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerLabel, Priority.ALWAYS);

        search.setPromptText("Search snippets\u2026");
        search.getStyleClass().add("sidebar-search");
        search.textProperty().addListener((observable, previous, current) -> refresh());
        VBox.setMargin(search, new Insets(6, 8, 6, 8));

        list.getStyleClass().add("snippets-list");
        list.setCellFactory(view -> new SnippetCell());
        list.setOnMouseClicked(event -> {
            Snippet selected = list.getSelectionModel().getSelectedItem();
            if (selected != null && event.getClickCount() == 2) {
                onInsert.accept(selected);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().addAll(header, search, list);
        refresh();
    }

    public void setOnInsert(Consumer<Snippet> action) {
        this.onInsert = action == null ? snippet -> { } : action;
    }

    public void setSqlSupplier(java.util.function.Supplier<String> supplier) {
        this.sqlSupplier = supplier == null ? () -> "" : supplier;
    }

    public void refresh() {
        list.setItems(FXCollections.observableArrayList(store.search(search.getText())));
    }

    private void saveFromEditor() {
        String sql = sqlSupplier.get();
        if (sql == null || sql.isBlank()) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog("Untitled snippet");
        dialog.setTitle("Save snippet");
        dialog.setHeaderText("Name this snippet");
        dialog.setContentText("Name:");
        Optional<String> name = dialog.showAndWait();
        name.filter(value -> !value.isBlank()).ifPresent(value -> {
            store.save(null, value.strip(), sql);
            refresh();
        });
    }

    private void edit(Snippet snippet) {
        TextInputDialog nameDialog = new TextInputDialog(snippet.name());
        nameDialog.setTitle("Edit snippet");
        nameDialog.setHeaderText("Snippet name");
        nameDialog.setContentText("Name:");
        Optional<String> name = nameDialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Edit snippet SQL");
        dialog.setHeaderText(name.get().strip());
        TextArea area = new TextArea(snippet.sql());
        area.setPrefRowCount(12);
        area.setPrefColumnCount(48);
        dialog.getDialogPane().setContent(area);
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.setResultConverter(button ->
                button == javafx.scene.control.ButtonType.OK ? area.getText() : null);
        Optional<String> sql = dialog.showAndWait();
        sql.ifPresent(value -> {
            store.save(snippet.id(), name.get().strip(), value);
            refresh();
        });
    }

    private final class SnippetCell extends ListCell<Snippet> {
        private final Label name = new Label();
        private final Label preview = new Label();
        private final VBox box = new VBox(2, name, preview);

        SnippetCell() {
            name.getStyleClass().add("snippet-name");
            preview.getStyleClass().add("snippet-preview");
            setOnContextMenuRequested(event -> {
                Snippet item = getItem();
                if (item == null) {
                    return;
                }
                var menu = new javafx.scene.control.ContextMenu(
                        menuItem("Insert into editor", () -> onInsert.accept(item)),
                        menuItem("Edit\u2026", () -> edit(item)),
                        menuItem("Delete", () -> {
                            store.delete(item.id());
                            refresh();
                        }));
                menu.show(this, event.getScreenX(), event.getScreenY());
            });
        }

        @Override
        protected void updateItem(Snippet item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            name.setText(item.name());
            String oneLine = item.sql().replace('\n', ' ').strip();
            preview.setText(oneLine.length() > 64 ? oneLine.substring(0, 63) + "\u2026" : oneLine);
            setTooltip(new Tooltip(item.sql()));
            setGraphic(box);
            setText(null);
        }

        private static javafx.scene.control.MenuItem menuItem(String text, Runnable action) {
            var item = new javafx.scene.control.MenuItem(text);
            item.setOnAction(event -> action.run());
            return item;
        }
    }
}
