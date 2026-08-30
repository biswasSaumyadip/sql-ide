package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaMetadataCodec;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.ForeignKey;
import com.lazaro.sqlide.core.db.SchemaMetadataCodec.IndexInfo;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Objects;

/**
 * Detail panel for a table or view: generated DDL, columns, indexes and foreign keys.
 * Populated entirely from a fully-detailed {@link SchemaNode} — no extra JDBC calls.
 */
public final class ObjectViewerPane extends VBox {

    private final Label titleLabel = new Label();
    private final Label subtitleLabel = new Label();
    private final TextArea ddlArea = new TextArea();
    private final VBox columnsBox = new VBox(4);
    private final VBox indexesBox = new VBox(4);
    private final VBox foreignKeysBox = new VBox(4);

    public ObjectViewerPane() {
        getStyleClass().add("object-viewer");
        setSpacing(0);
        VBox.setVgrow(this, Priority.ALWAYS);

        titleLabel.getStyleClass().add("object-viewer-title");
        subtitleLabel.getStyleClass().add("object-viewer-subtitle");

        HBox header = new HBox(10, titleLabel, subtitleLabel);
        header.getStyleClass().add("object-viewer-header");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 12, 8, 12));

        ddlArea.getStyleClass().add("object-viewer-ddl");
        ddlArea.setEditable(false);
        ddlArea.setWrapText(false);
        VBox.setVgrow(ddlArea, Priority.ALWAYS);

        Tab ddlTab = new Tab("DDL", wrap(ddlArea));
        Tab columnsTab = new Tab("Columns", wrap(columnsBox));
        Tab indexesTab = new Tab("Indexes", wrap(indexesBox));
        Tab fkTab = new Tab("Foreign Keys", wrap(foreignKeysBox));
        for (Tab tab : List.of(ddlTab, columnsTab, indexesTab, fkTab)) {
            tab.setClosable(false);
        }

        TabPane tabs = new TabPane(ddlTab, columnsTab, indexesTab, fkTab);
        tabs.getStyleClass().add("object-viewer-tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        getChildren().addAll(header, new Separator(Orientation.HORIZONTAL), tabs);
    }

    public void show(SchemaNode node) {
        Objects.requireNonNull(node, "node");
        titleLabel.setText(node.name());
        String catalog = node.metadata(SchemaNode.META_CATALOG);
        String kind = switch (node.type()) {
            case VIEW -> "VIEW";
            case PROCEDURE -> SchemaNode.ROUTINE_FUNCTION.equalsIgnoreCase(
                    node.metadata(SchemaNode.META_ROUTINE_KIND)) ? "FUNCTION" : "PROCEDURE";
            default -> "TABLE";
        };
        subtitleLabel.setText(catalog == null || catalog.isBlank() ? kind : kind + " · " + catalog);

        String ddl = node.metadata(SchemaNode.META_DDL);
        ddlArea.setText(ddl == null || ddl.isBlank() ? "-- No DDL available for this object." : ddl);

        populateColumns(node.children());
        populateIndexes(SchemaMetadataCodec.decodeIndexes(node.metadata(SchemaNode.META_INDEXES)));
        populateForeignKeys(SchemaMetadataCodec.decodeForeignKeys(node.metadata(SchemaNode.META_FOREIGN_KEYS)));
    }

    private void populateColumns(List<SchemaNode> columns) {
        columnsBox.getChildren().clear();
        if (columns.isEmpty()) {
            columnsBox.getChildren().add(muted("No columns loaded."));
            return;
        }
        columnsBox.getChildren().add(sectionHeader("Name", "Type", "Nullable", "Key"));
        for (SchemaNode column : columns) {
            columnsBox.getChildren().add(row(
                    column.name(),
                    Objects.requireNonNullElse(column.metadata(SchemaNode.META_DATA_TYPE), ""),
                    column.metadataFlag(SchemaNode.META_NULLABLE) ? "YES" : "NO",
                    column.metadataFlag(SchemaNode.META_PRIMARY_KEY) ? "PK" : ""));
        }
    }

    private void populateIndexes(List<IndexInfo> indexes) {
        indexesBox.getChildren().clear();
        if (indexes.isEmpty()) {
            indexesBox.getChildren().add(muted("No indexes reported."));
            return;
        }
        indexesBox.getChildren().add(sectionHeader("Name", "Unique", "Columns", ""));
        for (IndexInfo index : indexes) {
            indexesBox.getChildren().add(row(
                    index.name(),
                    index.unique() ? "YES" : "NO",
                    String.join(", ", index.columns()),
                    ""));
        }
    }

    private void populateForeignKeys(List<ForeignKey> keys) {
        foreignKeysBox.getChildren().clear();
        if (keys.isEmpty()) {
            foreignKeysBox.getChildren().add(muted("No foreign keys reported."));
            return;
        }
        foreignKeysBox.getChildren().add(sectionHeader("Name", "Column", "References", ""));
        for (ForeignKey key : keys) {
            foreignKeysBox.getChildren().add(row(
                    key.name(),
                    key.fkColumn(),
                    key.pkTable() + "." + key.pkColumn(),
                    ""));
        }
    }

    private static ScrollPane wrap(javafx.scene.Node content) {
        if (content instanceof VBox box) {
            box.setPadding(new Insets(10, 12, 12, 12));
        }
        ScrollPane scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("object-viewer-scroll");
        return scroll;
    }

    private static GridPane sectionHeader(String a, String b, String c, String d) {
        GridPane grid = row(a, b, c, d);
        grid.getStyleClass().add("object-viewer-row-header");
        return grid;
    }

    private static GridPane row(String a, String b, String c, String d) {
        GridPane grid = new GridPane();
        grid.getStyleClass().add("object-viewer-row");
        grid.setHgap(12);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setPercentWidth(28);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setPercentWidth(28);
        ColumnConstraints c2 = new ColumnConstraints();
        c2.setPercentWidth(28);
        ColumnConstraints c3 = new ColumnConstraints();
        c3.setPercentWidth(16);
        grid.getColumnConstraints().addAll(c0, c1, c2, c3);

        Label la = cell(a);
        Label lb = cell(b);
        Label lc = cell(c);
        Label ld = cell(d);
        grid.addRow(0, la, lb, lc, ld);
        return grid;
    }

    private static Label cell(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("object-viewer-cell");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("object-viewer-muted");
        return label;
    }
}
