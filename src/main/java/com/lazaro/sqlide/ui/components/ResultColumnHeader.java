package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.ResultColumn;
import com.lazaro.sqlide.ui.Icons;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

/**
 * Result-grid header: type badge, column name, PK/FK keys, and an optional
 * in-header {@link TextField} that filters that column in memory.
 */
final class ResultColumnHeader {

    private final TextField filterField = new TextField();
    private final VBox root = new VBox(2);
    private final Consumer<String> onFilter;

    private ResultColumnHeader(ResultColumn column, Consumer<String> onFilter) {
        this.onFilter = onFilter == null ? text -> { } : onFilter;

        Label name = new Label(column.name());
        name.getStyleClass().add("result-column-name");
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        HBox title = new HBox(4, typeGlyph(column), name);
        title.setAlignment(Pos.CENTER_LEFT);
        title.getStyleClass().add("result-column-title");
        if (column.primaryKey()) {
            Node key = Icons.primaryKeyBadge();
            Tooltip.install(key, new Tooltip("Primary key"));
            title.getChildren().add(key);
        }
        if (column.foreignKey()) {
            Node key = Icons.foreignKey();
            Tooltip.install(key, new Tooltip("Foreign key"));
            title.getChildren().add(key);
        }
        Tooltip.install(title, new Tooltip(column.typeTooltip()));

        filterField.getStyleClass().add("result-column-filter");
        filterField.setPromptText("Filter");
        filterField.setFocusTraversable(true);
        filterField.addEventFilter(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        filterField.addEventFilter(MouseEvent.MOUSE_PRESSED, MouseEvent::consume);
        filterField.textProperty().addListener((observable, previous, next) -> this.onFilter.accept(next));

        root.getStyleClass().add("result-column-header");
        root.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().add(title);
        root.getChildren().add(filterField);
        setFilterVisible(false);
    }

    static ResultColumnHeader attach(
            TableColumn<?, ?> column,
            ResultColumn meta,
            boolean filterVisible,
            Consumer<String> onFilter) {
        ResultColumnHeader header = new ResultColumnHeader(meta, onFilter);
        header.setFilterVisible(filterVisible);
        column.setText("");
        column.setGraphic(header.root);
        column.setUserData(header);
        return header;
    }

    void setFilterVisible(boolean visible) {
        filterField.setVisible(visible);
        filterField.setManaged(visible);
    }

    void clearFilterText() {
        if (!filterField.getText().isEmpty()) {
            filterField.clear();
        }
    }

    private static Node typeGlyph(ResultColumn column) {
        ResultColumn.Kind kind = column.kind();
        if (kind == ResultColumn.Kind.TEMPORAL) {
            Node clock = Icons.clock();
            Tooltip.install(clock, new Tooltip(column.typeTooltip()));
            return clock;
        }
        String badge = column.typeBadge();
        if (badge.isEmpty()) {
            badge = "?";
        }
        Label label = new Label(badge);
        label.getStyleClass().addAll("result-type-badge", "result-type-" + kind.name().toLowerCase());
        return label;
    }
}
