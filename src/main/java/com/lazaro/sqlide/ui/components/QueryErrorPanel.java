package com.lazaro.sqlide.ui.components;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Dedicated, non-modal surface for SQL errors. Sits below the editor so a failed
 * statement never blocks the next one with a dialog.
 */
public final class QueryErrorPanel extends VBox {

    private final Label title = new Label("Query failed");
    private final TextArea message = new TextArea();
    private final Button copyButton = new Button("Copy");
    private final Button dismissButton = new Button("Dismiss");

    public QueryErrorPanel() {
        getStyleClass().add("query-error-panel");
        setSpacing(6);
        setPadding(new Insets(8, 10, 8, 10));
        setVisible(false);
        setManaged(false);

        title.getStyleClass().add("query-error-title");

        message.setEditable(false);
        message.setWrapText(true);
        message.setPrefRowCount(3);
        message.getStyleClass().add("query-error-text");
        VBox.setVgrow(message, Priority.ALWAYS);

        copyButton.getStyleClass().add("query-error-action");
        copyButton.setTooltip(new Tooltip("Copy error text"));
        copyButton.setOnAction(event -> copyToClipboard());

        dismissButton.getStyleClass().add("query-error-action");
        dismissButton.setOnAction(event -> clear());

        HBox actions = new HBox(6, copyButton, dismissButton);
        actions.setAlignment(Pos.CENTER_RIGHT);

        getChildren().addAll(title, message, actions);
    }

    public void show(String errorText) {
        message.setText(errorText == null || errorText.isBlank() ? "Unknown error" : errorText);
        setVisible(true);
        setManaged(true);
    }

    public void clear() {
        message.clear();
        setVisible(false);
        setManaged(false);
    }

    public boolean isShowing() {
        return isVisible();
    }

    private void copyToClipboard() {
        String text = message.getText();
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
