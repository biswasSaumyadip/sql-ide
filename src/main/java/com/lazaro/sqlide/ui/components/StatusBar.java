package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.shape.Circle;

/**
 * Thin strip along the bottom: connection state on the left, last query outcome
 * and caret position on the right. A small spinner appears while work is in flight.
 */
public final class StatusBar extends HBox {

    private static final PseudoClass ERROR = PseudoClass.getPseudoClass("error");
    private static final PseudoClass BUSY = PseudoClass.getPseudoClass("busy");
    private static final int MAX_RESULT_CHARS = 90;

    private static final String DOT_CONNECTED = "status-dot-connected";
    private static final String DOT_DISCONNECTED = "status-dot-disconnected";
    private static final String DOT_BUSY = "status-dot-busy";

    private final Circle dot = new Circle(4.5);
    private final ProgressIndicator activity = new ProgressIndicator();
    private final Label connectionLabel = new Label("Not connected");
    private final Label resultLabel = new Label();
    private final Label caretLabel = new Label("Ln 1, Col 1");

    public StatusBar() {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);

        dot.getStyleClass().add("status-dot");

        activity.setMaxSize(14, 14);
        activity.getStyleClass().add("status-activity");
        activity.setVisible(false);
        activity.setManaged(false);

        connectionLabel.getStyleClass().add("status-text");
        resultLabel.getStyleClass().addAll("status-text", "status-result");
        caretLabel.getStyleClass().addAll("status-text", "status-caret");

        connectionLabel.setMinWidth(Region.USE_PREF_SIZE);
        caretLabel.setMinWidth(Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                dot, activity, connectionLabel,
                spacer,
                resultLabel, new Separator(Orientation.VERTICAL), caretLabel);

        setDisconnected();
    }

    public void setConnected(String description) {
        setActivity(false);
        applyDotClass(DOT_CONNECTED);
        connectionLabel.setText(description);
        connectionLabel.pseudoClassStateChanged(ERROR, false);
        connectionLabel.setTooltip(null);
    }

    public void setDisconnected() {
        setActivity(false);
        applyDotClass(DOT_DISCONNECTED);
        connectionLabel.setText("Not connected");
        connectionLabel.pseudoClassStateChanged(ERROR, false);
        connectionLabel.setTooltip(null);
        resultLabel.setText("");
        resultLabel.setTooltip(null);
        resultLabel.pseudoClassStateChanged(ERROR, false);
    }

    /** Connection or query work is in flight. */
    public void setBusy(String message) {
        setActivity(true);
        applyDotClass(DOT_BUSY);
        connectionLabel.setText(message);
        connectionLabel.pseudoClassStateChanged(ERROR, false);
        connectionLabel.setTooltip(null);
    }

    /** A failed connection attempt, shown inline instead of a modal. */
    public void setConnectionError(String message) {
        setActivity(false);
        applyDotClass(DOT_DISCONNECTED);
        connectionLabel.setText(abbreviate(message, 72));
        connectionLabel.setTooltip(new Tooltip(message));
        connectionLabel.pseudoClassStateChanged(ERROR, true);
    }

    public void setQueryRunning() {
        setActivity(true);
        resultLabel.setText("Running query\u2026");
        resultLabel.setTooltip(null);
        resultLabel.pseudoClassStateChanged(ERROR, false);
        pseudoClassStateChanged(BUSY, true);
    }

    public void clearQueryRunning() {
        setActivity(false);
        pseudoClassStateChanged(BUSY, false);
    }

    /** Shows the outcome of the last statement, or its error. */
    public void setResult(QueryResult result) {
        clearQueryRunning();
        String summary = result.summary();
        resultLabel.setText(abbreviate(summary, MAX_RESULT_CHARS));
        resultLabel.setTooltip(summary.equals(resultLabel.getText()) ? null : new Tooltip(summary));
        resultLabel.pseudoClassStateChanged(ERROR, result.isError());
    }

    public void clearResult() {
        clearQueryRunning();
        resultLabel.setText("");
        resultLabel.setTooltip(null);
        resultLabel.pseudoClassStateChanged(ERROR, false);
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

    private void setActivity(boolean active) {
        activity.setVisible(active);
        activity.setManaged(active);
    }

    private void applyDotClass(String styleClass) {
        dot.getStyleClass().removeAll(DOT_CONNECTED, DOT_DISCONNECTED, DOT_BUSY);
        dot.getStyleClass().add(styleClass);
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }
}
