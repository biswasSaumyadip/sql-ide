package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.history.QueryHistoryStore;
import com.lazaro.sqlide.core.history.QueryHistoryStore.Entry;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

/** Searchable, re-runnable query history list for the sidebar. */
public final class QueryHistoryPane extends VBox {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm")
            .withZone(ZoneId.systemDefault());

    private final QueryHistoryStore store;
    private final TextField search = new TextField();
    private final ListView<Entry> list = new ListView<>();
    private Consumer<Entry> onRerun = entry -> { };
    private Consumer<Entry> onInsert = entry -> { };

    public QueryHistoryPane(QueryHistoryStore store) {
        this.store = store;
        getStyleClass().add("history-pane");
        setSpacing(0);

        Label headerLabel = new Label("HISTORY");
        headerLabel.getStyleClass().add("panel-header");
        Button clear = new Button("Clear");
        clear.getStyleClass().add("panel-header-action");
        clear.setOnAction(event -> {
            store.clear();
            refresh();
        });
        HBox header = new HBox(8, headerLabel, clear);
        header.getStyleClass().add("panel-header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerLabel, Priority.ALWAYS);

        search.setPromptText("Search history\u2026");
        search.getStyleClass().add("sidebar-search");
        search.textProperty().addListener((observable, previous, current) -> refresh());
        VBox.setMargin(search, new Insets(6, 8, 6, 8));

        list.getStyleClass().add("history-list");
        list.setCellFactory(view -> new HistoryCell());
        list.setOnMouseClicked(event -> {
            Entry selected = list.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            if (event.getClickCount() == 2) {
                onRerun.accept(selected);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().addAll(header, search, list);
        refresh();
    }

    public void setOnRerun(Consumer<Entry> action) {
        this.onRerun = action == null ? entry -> { } : action;
    }

    public void setOnInsert(Consumer<Entry> action) {
        this.onInsert = action == null ? entry -> { } : action;
    }

    public void refresh() {
        list.setItems(FXCollections.observableArrayList(store.search(search.getText())));
    }

    private final class HistoryCell extends ListCell<Entry> {
        private final Label sql = new Label();
        private final Label meta = new Label();
        private final VBox box = new VBox(2, sql, meta);

        HistoryCell() {
            sql.getStyleClass().add("history-sql");
            meta.getStyleClass().add("history-meta");
            setOnContextMenuRequested(event -> {
                Entry item = getItem();
                if (item == null) {
                    return;
                }
                var menu = new javafx.scene.control.ContextMenu(
                        menuItem("Run again", () -> onRerun.accept(item)),
                        menuItem("Insert into editor", () -> onInsert.accept(item)),
                        menuItem("Delete", () -> {
                            store.delete(item.id());
                            refresh();
                        }));
                menu.show(this, event.getScreenX(), event.getScreenY());
            });
        }

        @Override
        protected void updateItem(Entry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }
            sql.setText(item.preview(72));
            meta.setText((item.success() ? "OK" : "ERR") + " \u00b7 "
                    + item.summary() + " \u00b7 " + TIME.format(item.executedAt()));
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
