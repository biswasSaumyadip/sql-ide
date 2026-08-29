package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

/**
 * Thin strip along the bottom: connection state on the left, last query outcome
 * and caret position on the right.
 */
public final class StatusBar extends HBox {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final int MAX_RESULT_CHARS = 90;

    private static final String DOT_CONNECTED = "status-dot-connected";
    private static final String DOT_DISCONNECTED = "status-dot-disconnected";
    private static final String DOT_BUSY = "status-dot-busy";

    private final Circle dot = new Circle(4.5);
    private final Label connectionLabel = new Label("Not connected");
    private final Label resultLabel = new Label();
    private final Label caretLabel = new Label("Ln 1, Col 1");

    public StatusBar() {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);

        dot.getStyleClass().add("status-dot");
        connectionLabel.getStyleClass().add("status-text");
        resultLabel.getStyleClass().addAll("status-text", "status-result");
        caretLabel.getStyleClass().addAll("status-text", "status-caret");

        // When space runs short only the query summary gives way; the connection and
        // caret readouts must stay legible.
        connectionLabel.setMinWidth(Region.USE_PREF_SIZE);
        caretLabel.setMinWidth(Region.USE_PREF_SIZE);

        // The spacer absorbs all slack, pinning the right-hand readouts to the edge.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                dot, connectionLabel,
                spacer,
                resultLabel, new Separator(Orientation.VERTICAL), caretLabel);

        setDisconnected();
    }

    public void setConnected(String description) {
        applyDotClass(DOT_CONNECTED);
        connectionLabel.setText(description);
    }

    public void setDisconnected() {
        applyDotClass(DOT_DISCONNECTED);
        connectionLabel.setText("Not connected");
        resultLabel.setText("");
    }

    public void setBusy(String message) {
        applyDotClass(DOT_BUSY);
        connectionLabel.setText(message);
    }

    /** Shows the outcome of the last statement, or its error. */
    public void setResult(QueryResult result) {
        String summary = result.summary();
        resultLabel.setText(abbreviate(summary));
        // Server errors run long; the strip shows the head and the tooltip the rest.
        resultLabel.setTooltip(summary.equals(resultLabel.getText()) ? null : new Tooltip(summary));
        resultLabel.pseudoClassStateChanged(ERROR, result.isError());
    }

    public void clearResult() {
        resultLabel.setText("");
        resultLabel.setTooltip(null);
        resultLabel.pseudoClassStateChanged(ERROR, false);
    }

    private static String abbreviate(String text) {
        return text.length() <= MAX_RESULT_CHARS ? text : text.substring(0, MAX_RESULT_CHARS - 1) + "\u2026";
    }

    /** Follows the caret of whichever editor is active. */
    public void bindCaret(ObservableValue<String> caretLocation) {
        caretLabel.textProperty().unbind();
        if (caretLocation == null) {
            caretLabel.setText("Ln 1, Col 1");
        } else {
            caretLabel.textProperty().bind(caretLocation);
        }
    }

    private void applyDotClass(String styleClass) {
        dot.getStyleClass().removeAll(DOT_CONNECTED, DOT_DISCONNECTED, DOT_BUSY);
        dot.getStyleClass().add(styleClass);
    }
}
