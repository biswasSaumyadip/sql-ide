package com.lazaro.sqlide.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * One-line, non-modal strip for SQL errors. The full text lives in Output;
 * this only flags the failure so the results pane does not jump.
 */
public final class QueryErrorPanel extends HBox {

    private final Label title = new Label("Query failed");
    private final Button copyButton = new Button("Copy");
    private final Button dismissButton = new Button("Dismiss");
    private String fullText = "";

    public QueryErrorPanel() {
        getStyleClass().add("query-error-panel");
        setSpacing(8);
        setPadding(new Insets(4, 12, 4, 12));
        setAlignment(Pos.CENTER_LEFT);
        setFillHeight(true);
        setMinHeight(Region.USE_PREF_SIZE);
        setMaxHeight(Region.USE_PREF_SIZE);
        VBox.setVgrow(this, Priority.NEVER);
        setVisible(false);
        setManaged(false);

        title.getStyleClass().add("query-error-title");
        title.setMinWidth(Region.USE_PREF_SIZE);
        title.setTextOverrun(OverrunStyle.CLIP);

        copyButton.getStyleClass().add("query-error-action");
        copyButton.setTooltip(new Tooltip("Copy error text"));
        copyButton.setMinWidth(Region.USE_PREF_SIZE);
        copyButton.setOnAction(event -> copyToClipboard());

        dismissButton.getStyleClass().add("query-error-action");
        dismissButton.setMinWidth(Region.USE_PREF_SIZE);
        dismissButton.setOnAction(event -> clear());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(title, spacer, copyButton, dismissButton);
    }

    public void show(String errorText) {
        fullText = errorText == null || errorText.isBlank() ? "Unknown error" : errorText.strip();
        title.setTooltip(new Tooltip(fullText));
        setVisible(true);
        setManaged(true);
    }

    public void clear() {
        fullText = "";
        title.setTooltip(null);
        setVisible(false);
        setManaged(false);
    }

    public boolean isShowing() {
        return isVisible();
    }

    private void copyToClipboard() {
        if (fullText == null || fullText.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(fullText);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
