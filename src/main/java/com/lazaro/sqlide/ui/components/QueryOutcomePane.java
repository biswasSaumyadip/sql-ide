package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Everything below the editor: an error strip when a statement fails, otherwise a
 * results grid or an update-count message.
 */
public final class QueryOutcomePane extends VBox {

    private final QueryErrorPanel errorPanel = new QueryErrorPanel();
    private final DynamicResultTable results = new DynamicResultTable();

    public QueryOutcomePane() {
        getStyleClass().add("query-outcome-pane");
        setSpacing(0);
        VBox.setVgrow(results, Priority.ALWAYS);
        getChildren().addAll(errorPanel, results);
    }

    public DynamicResultTable results() {
        return results;
    }

    public void showIdle() {
        errorPanel.clear();
        results.clear();
    }

    public void showLoading() {
        errorPanel.clear();
        results.showMessage("Running query\u2026");
    }

    /** Renders a detached outcome. Errors go to the panel; everything else to the grid. */
    public void present(QueryResult result) {
        if (result.isError()) {
            errorPanel.show(result.errorMessage());
            results.showMessage("Fix the statement above and run again.");
            return;
        }
        errorPanel.clear();
        results.setResult(result);
    }

    public void clear() {
        showIdle();
    }
}
