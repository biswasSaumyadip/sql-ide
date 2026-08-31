package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.runtime.HeapMemory;
import com.lazaro.sqlide.ui.Icons;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.value.ObservableValue;
import javafx.css.PseudoClass;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

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
    private final Label indexingLabel = new Label();
    private final Label resultLabel = new Label();
    private final Label caretLabel = new Label("Ln 1, Col 1");
    private final Label heapLabel = new Label();
    private final Button gcButton = new Button();
    private final Timeline heapTimeline;

    private String endpointText = "Not connected";
    private boolean queryBusy;
    private boolean indexing;
    private boolean lifecycleBusy;

    public StatusBar() {
        getStyleClass().add("status-bar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(8);

        dot.getStyleClass().add("status-dot");

        activity.setMaxSize(14, 14);
        activity.getStyleClass().add("status-activity");
        activity.setVisible(false);
        StackPane activitySlot = new StackPane(activity);
        activitySlot.setMinSize(14, 14);
        activitySlot.setPrefSize(14, 14);
        activitySlot.setMaxSize(14, 14);
        activitySlot.setMouseTransparent(true);

        connectionLabel.getStyleClass().add("status-text");
        connectionLabel.setMinWidth(0);
        connectionLabel.setMaxWidth(220);
        connectionLabel.setTextOverrun(javafx.scene.control.OverrunStyle.ELLIPSIS);
        databaseLabel.getStyleClass().addAll("status-text", "status-database");
        transactionLabel.getStyleClass().addAll("status-text", "status-transaction");
        indexingLabel.getStyleClass().addAll("status-text", "status-indexing");
        indexingLabel.setVisible(false);
        indexingLabel.setManaged(true);
        indexingLabel.setMinWidth(0);
        resultLabel.getStyleClass().addAll("status-text", "status-result");
        caretLabel.getStyleClass().addAll("status-text", "status-caret");
        heapLabel.getStyleClass().addAll("status-text", "status-heap");
        heapLabel.setMinWidth(Region.USE_PREF_SIZE);
        gcButton.setGraphic(Icons.gc());
        gcButton.getStyleClass().add("status-gc-button");
        gcButton.setFocusTraversable(false);
        gcButton.setTooltip(new Tooltip("Run garbage collection to free unused heap"));
        gcButton.setOnAction(event -> {
            System.gc();
            refreshHeap();
        });

        databaseLabel.setMinWidth(Region.USE_PREF_SIZE);
        transactionLabel.setMinWidth(Region.USE_PREF_SIZE);
        caretLabel.setMinWidth(Region.USE_PREF_SIZE);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                dot, activitySlot, connectionLabel, databaseLabel, transactionLabel, indexingLabel,
                spacer,
                resultLabel, new Separator(Orientation.VERTICAL), caretLabel,
                new Separator(Orientation.VERTICAL), heapLabel, gcButton);

        heapTimeline = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshHeap()));
        heapTimeline.setCycleCount(Timeline.INDEFINITE);
        heapTimeline.play();
        refreshHeap();

        setDisconnected();
    }

    /**
     * @param endpoint {@code user@host:port} without a database suffix
     * @param database active catalog/schema, or {@code null}/{@code ""} when none
     */
    public void setConnected(String endpoint, String database) {
        lifecycleBusy = false;
        applyDotClass(DOT_CONNECTED);
        syncActivity();
        endpointText = endpoint == null || endpoint.isBlank() ? "Connected" : endpoint;
        connectionLabel.setText(endpointText);
        connectionLabel.pseudoClassStateChanged(ERROR, false);
        connectionLabel.setTooltip(new Tooltip(endpointText));
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
        queryBusy = false;
        indexing = false;
        lifecycleBusy = false;
        hideIndexingLabel();
        syncActivity();
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
        lifecycleBusy = true;
        syncActivity();
        applyDotClass(DOT_BUSY);
        connectionLabel.setText(message);
        connectionLabel.pseudoClassStateChanged(ERROR, false);
        connectionLabel.setTooltip(null);
    }

    public void setConnectionError(String message) {
        queryBusy = false;
        indexing = false;
        lifecycleBusy = false;
        hideIndexingLabel();
        syncActivity();
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
        setQueryRunning(false);
    }

    public void setQueryRunning(boolean redis) {
        queryBusy = true;
        syncActivity();
        resultLabel.setText(redis ? "Running command\u2026" : "Running query\u2026");
        resultLabel.setTooltip(null);
        resultLabel.pseudoClassStateChanged(ERROR, false);
        pseudoClassStateChanged(BUSY, true);
    }

    public void clearQueryRunning() {
        queryBusy = false;
        syncActivity();
        pseudoClassStateChanged(BUSY, false);
    }

    /**
     * Background schema indexing. Does not replace the connection readout or a
     * running-query message; the spinner stays on while this or a query is active.
     */
    public void setIndexing(String message) {
        indexing = message != null && !message.isBlank();
        indexingLabel.pseudoClassStateChanged(ERROR, false);
        if (indexing) {
            indexingLabel.setText("· " + message);
            indexingLabel.setTooltip(new Tooltip(message));
        } else {
            hideIndexingLabel();
        }
        indexingLabel.setVisible(indexing);
        indexingLabel.setManaged(true);
        syncActivity();
    }

    /** Spinner off; keep a visible failure on the indexing slot. */
    public void setIndexingError(String message) {
        indexing = false;
        String text = message == null || message.isBlank() ? "Indexing failed" : message;
        indexingLabel.setText("· " + abbreviate(text, 80));
        indexingLabel.setTooltip(new Tooltip(text));
        indexingLabel.setVisible(true);
        indexingLabel.setManaged(true);
        indexingLabel.pseudoClassStateChanged(ERROR, true);
        syncActivity();
    }

    public void clearIndexing() {
        setIndexing(null);
    }

    public void setResult(QueryResult result) {
        setResult(result, false);
    }

    public void setResult(QueryResult result, boolean redis) {
        clearQueryRunning();
        String summary = result.summary(redis);
        resultLabel.setText(abbreviate(summary, MAX_RESULT_CHARS));
        resultLabel.setTooltip(summary.equals(resultLabel.getText()) ? null : new Tooltip(summary));
        resultLabel.pseudoClassStateChanged(ERROR, result.isError());
    }

    public void setScriptSummary(String summary, boolean hasError) {
        clearQueryRunning();
        String text = summary == null ? "" : summary;
        resultLabel.setText(abbreviate(text, MAX_RESULT_CHARS));
        resultLabel.setTooltip(text.equals(resultLabel.getText()) ? null : new Tooltip(text));
        resultLabel.pseudoClassStateChanged(ERROR, hasError);
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

    private void refreshHeap() {
        HeapMemory.Snapshot snapshot = HeapMemory.current();
        heapLabel.setText(snapshot.display());
        heapLabel.setTooltip(new Tooltip("JVM heap \u2014 " + snapshot.display()));
    }

    private void syncActivity() {
        boolean active = queryBusy || indexing || lifecycleBusy;
        activity.setVisible(active);
    }

    private void hideIndexingLabel() {
        indexingLabel.setText("");
        indexingLabel.setTooltip(null);
        indexingLabel.setVisible(false);
        indexingLabel.pseudoClassStateChanged(ERROR, false);
    }

    private void applyDotClass(String styleClass) {
        dot.getStyleClass().removeAll(DOT_CONNECTED, DOT_DISCONNECTED, DOT_BUSY);
        dot.getStyleClass().add(styleClass);
    }

    private static String abbreviate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "\u2026";
    }
}
