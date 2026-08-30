package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.doc.SqlDocResolver;
import com.lazaro.sqlide.core.doc.SqlDocResolver.Doc;
import com.lazaro.sqlide.ui.Icons;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Popup;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Interactive Quick Documentation popup for SQL tables and columns.
 */
public final class SqlDocPopup extends Popup {

    private final GridPane metaGrid = new GridPane();
    private final TextFlow codeFlow = new TextFlow();
    private final ScrollPane codeScroll = new ScrollPane(codeFlow);
    private final Hyperlink previewLink = new Hyperlink("Show table preview");
    private final VBox root = new VBox(12);

    private Doc current;
    private Consumer<Doc> onShowPreview = doc -> { };

    public SqlDocPopup() {
        setAutoHide(true);
        setHideOnEscape(true);
        setAutoFix(true);

        metaGrid.setHgap(12);
        metaGrid.setVgap(6);
        ColumnConstraints keys = new ColumnConstraints();
        keys.setMinWidth(88);
        ColumnConstraints values = new ColumnConstraints();
        values.setHgrow(Priority.ALWAYS);
        metaGrid.getColumnConstraints().addAll(keys, values);

        codeFlow.getStyleClass().add("sql-doc-code-text");
        codeScroll.setFitToWidth(true);
        codeScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        codeScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        codeScroll.setPrefHeight(180);
        codeScroll.setMaxHeight(260);
        codeScroll.getStyleClass().add("sql-doc-code-scroll");

        VBox codeBlock = new VBox(6, codeScroll, buildCodeActions());
        codeBlock.getStyleClass().add("sql-doc-code-block");

        previewLink.getStyleClass().add("sql-doc-preview-link");
        previewLink.setOnAction(e -> {
            if (current != null) {
                onShowPreview.accept(current);
            }
            hide();
        });

        root.getChildren().setAll(metaGrid, codeBlock, previewLink);
        root.getStyleClass().add("sql-doc-popup");
        root.setPadding(new Insets(12));
        root.setPrefWidth(420);
        root.setMaxWidth(520);
        String css = Objects.requireNonNull(
                        SqlDocPopup.class.getResource("/com/lazaro/sqlide/css/app.css"),
                        "app.css missing")
                .toExternalForm();
        root.getStylesheets().add(css);

        // Keep popup open while the pointer is over it.
        root.setOnMouseEntered(e -> { /* cancels pending hide from editor */ });
        getContent().setAll(root);
    }

    public void setOnShowPreview(Consumer<Doc> action) {
        this.onShowPreview = action == null ? doc -> { } : action;
    }

    public void showDoc(Doc doc, javafx.scene.Node owner, double screenX, double screenY) {
        if (doc == null || owner == null || owner.getScene() == null) {
            hide();
            return;
        }
        this.current = doc;
        populate(doc);
        show(owner, screenX, screenY + 12);
    }

    public Doc currentDoc() {
        return current;
    }

    private void populate(Doc doc) {
        metaGrid.getChildren().clear();
        int row = 0;
        row = addMetaRow(row, "Data Source", blankToDash(doc.dataSource()));
        row = addMetaRow(row, "Schema", blankToDash(doc.schema()));
        row = addMetaRow(row, "Table", blankToDash(doc.table()));
        if (doc.kind() == SqlDocResolver.Kind.COLUMN && doc.column() != null) {
            addMetaRow(row, "Column", doc.column());
        }

        codeFlow.getChildren().setAll(new Text(doc.code()));
        previewLink.setVisible(doc.isTable());
        previewLink.setManaged(doc.isTable());
    }

    private int addMetaRow(int row, String key, String value) {
        Label keyLabel = new Label(key);
        keyLabel.getStyleClass().add("sql-doc-meta-key");
        Label valueLabel = new Label(value);
        valueLabel.getStyleClass().add("sql-doc-meta-value");
        valueLabel.setWrapText(true);
        metaGrid.add(keyLabel, 0, row);
        metaGrid.add(valueLabel, 1, row);
        return row + 1;
    }

    private HBox buildCodeActions() {
        Button copy = new Button();
        copy.setGraphic(Icons.copy());
        copy.getStyleClass().addAll("sql-doc-action", "icon-button");
        copy.setTooltip(new Tooltip("Copy"));
        copy.setFocusTraversable(false);
        copy.setOnAction(e -> {
            if (current == null) {
                return;
            }
            ClipboardContent content = new ClipboardContent();
            content.putString(current.code());
            Clipboard.getSystemClipboard().setContent(content);
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox actions = new HBox(6, spacer, copy);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getStyleClass().add("sql-doc-actions");
        return actions;
    }

    private static String blankToDash(String value) {
        return value == null || value.isBlank() ? "\u2014" : value;
    }
}
