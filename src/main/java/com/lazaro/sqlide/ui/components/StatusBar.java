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
 * Thin strip along the bottom: connection + active database on the left, last query
 * outcome and caret position on the right.
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
    private final Label databaseLabel = new Label();
    private final Label transactionLabel = new Label();
    private final Label resultLabel = new Label();
    private final Label caretLabel = new Label("Ln 1, Col 1");

    private String endpointText = "Not connected";

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
        databaseLabel.getStyleClass().addAll("status-text", "status-database");
        transactionLabel.getStyleClass().addAll("status-text", "status-transaction");
        resultLabel.getStyleClass().addAll("status-text", "status-result");
        caretLabel.getStyleClass().addAll("status-text", "status-caret");

        connectionLabel.setMinWidth(Region.USE_PREF_SIZE);
        databaseLabel.setMinWidth(Region.USE_PREF_SIZE);
        transactionLabel.setMinWidth(Region.USE_PREF_SIZE);
        caretLabel.setMinWidth(Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                dot, activity, connectionLabel, databaseLabel, transactionLabel,
                spacer,
                resultLabel, new Separator(Orientation.VERTICAL), caretLabel);

        setDisconnected();
    }

    /**
     * @param endpoint {@code user@host:port} without a database suffix
     * @param database active catalog/schema, or {@code null}/{@code ""} when none
     */
    public void setConnected(String endpoint, String database) {
        setActivity(false);
        applyDotClass(DOT_CONNECTED);
        endpointText = endpoint == null || endpoint.isBlank() ? "Connected" : endpoint;
        connectionLabel.setText(endpointText);
        connectionLabel.pseudoClassStateChanged(ERROR, false);
        connectionLabel.setTooltip(null);
        setActiveDatabase(database);
        // Transaction readout is owned by MainController via setTransactionState.
    }

    /** Updates only the active-database readout (connection stays as-is). */
    public void setActiveDatabase(String database) {
        if (database == null || database.isBlank()) {
            databaseLabel.setText("· no database");
            databaseLabel.setTooltip(new Tooltip("Double-click a database in the schema tree to use it"));
            databaseLabel.pseudoClassStateChanged(ERROR, false);
            databaseLabel.getStyleClass().remove("status-database-active");
        } else {
            databaseLabel.setText("· " + database);
            databaseLabel.setTooltip(new Tooltip("Active database: " + database));
            databaseLabel.getStyleClass().add("status-database-active");
        }
        databaseLabel.setVisible(true);
        databaseLabel.setManaged(true);
    }

    /**
     * @param autoCommit {@code true} when each statement commits alone
     * @param visible    hide the readout while disconnected
     */
    public void setTransactionState(boolean autoCommit, boolean visible) {
        if (!visible) {
            transactionLabel.setText("");
            transactionLabel.setVisible(false);
            transactionLabel.setManaged(false);
            transactionLabel.getStyleClass().removeAll("status-txn-auto", "status-txn-manual");
            return;
        }
        transactionLabel.setVisible(true);
        transactionLabel.setManaged(true);
        transactionLabel.getStyleClass().removeAll("status-txn-auto", "status-txn-manual");
        if (autoCommit) {
            transactionLabel.setText("· Auto-commit");
            transactionLabel.setTooltip(new Tooltip("Each statement commits immediately"));
            transactionLabel.getStyleClass().add("status-txn-auto");
        } else {
            transactionLabel.setText("· Manual txn");
            transactionLabel.setTooltip(new Tooltip("Commit or Rollback from the toolbar"));
            transactionLabel.getStyleClass().add("status-txn-manual");
        }
    }

    public void setDisconnected() {
        setActivity(false);
        applyDotClass(DOT_DISCONNECTED);
        endpointText = "Not connected";
        connectionLabel.setText(endpointText);
        connectionLabel.pseudoClassStateChanged(ERROR, false);
        connectionLabel.setTooltip(null);
        databaseLabel.setText("");
        databaseLabel.setTooltip(null);
        databaseLabel.setVisible(false);
        databaseLabel.setManaged(false);
        databaseLabel.getStyleClass().remove("status-database-active");
        setTransactionState(true, false);
        resultLabel.setText("");
        resultLabel.setTooltip(null);
        resultLabel.pseudoClassStateChanged(ERROR, false);
    }

    public void setBusy(String message) {
        setActivity(true);
        applyDotClass(DOT_BUSY);
        connectionLabel.setText(message);
        connectionLabel.pseudoClassStateChanged(ERROR, false);
        connectionLabel.setTooltip(null);
    }

    public void setConnectionError(String message) {
        setActivity(false);
        applyDotClass(DOT_DISCONNECTED);
        connectionLabel.setText(abbreviate(message, 72));
        connectionLabel.setTooltip(new Tooltip(message));
        connectionLabel.pseudoClassStateChanged(ERROR, true);
        databaseLabel.setText("");
        databaseLabel.setVisible(false);
        databaseLabel.setManaged(false);
        setTransactionState(true, false);
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
