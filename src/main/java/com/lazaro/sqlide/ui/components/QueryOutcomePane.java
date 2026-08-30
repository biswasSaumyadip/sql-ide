package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.explain.ExplainPlanNode;
import com.lazaro.sqlide.core.explain.ExplainPlanParser;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

/**
 * Everything below the editor: an error strip when a statement fails, otherwise a
 * results grid or an EXPLAIN plan tree.
 */
public final class QueryOutcomePane extends VBox {

    private final QueryErrorPanel errorPanel = new QueryErrorPanel();
    private final DynamicResultTable results = new DynamicResultTable();
    private final ExplainPlanTreeView planTree = new ExplainPlanTreeView();
    private final StackPane body = new StackPane(results, planTree);

    public QueryOutcomePane() {
        getStyleClass().add("query-outcome-pane");
        setSpacing(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        planTree.setVisible(false);
        planTree.setManaged(false);
        getChildren().addAll(errorPanel, body);
    }

    public DynamicResultTable results() {
        return results;
    }

    public void showIdle() {
        errorPanel.clear();
        results.clear();
        showGrid();
    }

    public void showLoading() {
        errorPanel.clear();
        showGrid();
        results.showMessage("Running query\u2026");
    }

    public void showCancelling() {
        errorPanel.clear();
        showGrid();
        results.showMessage("Cancelling query\u2026");
    }

    /** Renders a detached outcome. Errors go to the panel; plans to the tree; else the grid. */
    public void present(QueryResult result) {
        present(result, false);
    }

    /**
     * @param preferPlan when {@code true} (Explain actions), always try the tree view
     */
    public void present(QueryResult result, boolean preferPlan) {
        if (result.isError()) {
            errorPanel.show(result.errorMessage());
            showGrid();
            results.showMessage("Fix the statement above and run again.");
            return;
        }
        errorPanel.clear();
        if (preferPlan && result.isResultSet()) {
            showPlan(ExplainPlanParser.parse(result));
            return;
        }
        var plan = ExplainPlanParser.tryParse(result);
        if (plan.isPresent() && looksUseful(plan.get())) {
            showPlan(plan.get());
            return;
        }
        showGrid();
        results.setResult(result);
    }

    public void clear() {
        showIdle();
    }

    private void showPlan(ExplainPlanNode plan) {
        results.setVisible(false);
        results.setManaged(false);
        planTree.setVisible(true);
        planTree.setManaged(true);
        planTree.setPlan(plan);
    }

    private void showGrid() {
        planTree.clear();
        planTree.setVisible(false);
        planTree.setManaged(false);
        results.setVisible(true);
        results.setManaged(true);
    }

    private static boolean looksUseful(ExplainPlanNode plan) {
        return plan != null && (!plan.label().isBlank() || !plan.children().isEmpty());
    }
}
