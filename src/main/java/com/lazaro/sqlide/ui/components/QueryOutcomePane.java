package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.ScriptResult;
import com.lazaro.sqlide.core.explain.ExplainPlanNode;
import com.lazaro.sqlide.core.explain.ExplainPlanParser;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;

/**
 * Everything below the editor: an error strip when needed, otherwise one or more
 * result tabs (grid, update message, or EXPLAIN plan).
 */
public final class QueryOutcomePane extends VBox {

    private final QueryErrorPanel errorPanel = new QueryErrorPanel();
    private final TabPane resultTabs = new TabPane();
    private final DynamicResultTable fallbackResults = new DynamicResultTable();
    private final StackPane body = new StackPane(resultTabs);

    private Runnable onExportRequest = () -> { };

    public QueryOutcomePane() {
        getStyleClass().add("query-outcome-pane");
        setSpacing(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        resultTabs.getStyleClass().add("result-tabs");
        resultTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        getChildren().addAll(errorPanel, body);
        showIdle();
    }

    /** Invoked when the user picks Export from a result grid context menu. */
    public void setOnExportRequest(Runnable action) {
        this.onExportRequest = action == null ? () -> { } : action;
    }

    public DynamicResultTable results() {
        Tab selected = resultTabs.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getContent() instanceof ResultPage page) {
            return page.table();
        }
        return fallbackResults;
    }

    public QueryResult activeResult() {
        Tab selected = resultTabs.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getContent() instanceof ResultPage page) {
            return page.result();
        }
        return null;
    }

    public void showIdle() {
        errorPanel.clear();
        resultTabs.getTabs().clear();
        ResultPage idle = ResultPage.message("Run a query to see results.");
        resultTabs.getTabs().add(wrap("Result", idle, false));
    }

    public void showLoading() {
        errorPanel.clear();
        resultTabs.getTabs().clear();
        resultTabs.getTabs().add(wrap("Running", ResultPage.message("Running query\u2026"), false));
    }

    public void showCancelling() {
        errorPanel.clear();
        resultTabs.getTabs().clear();
        resultTabs.getTabs().add(wrap("Cancelling", ResultPage.message("Cancelling query\u2026"), false));
    }

    public void present(QueryResult result) {
        present(result, false);
    }

    public void present(QueryResult result, boolean preferPlan) {
        presentScript(ScriptResult.ofSingle(result), preferPlan);
    }

    public void presentScript(ScriptResult script) {
        presentScript(script, false);
    }

    public void presentScript(ScriptResult script, boolean preferPlan) {
        errorPanel.clear();
        resultTabs.getTabs().clear();
        if (script == null || script.isEmpty()) {
            showIdle();
            return;
        }

        List<QueryResult> results = script.results();
        QueryResult lastError = null;
        for (int i = 0; i < results.size(); i++) {
            QueryResult result = results.get(i);
            if (result.isError()) {
                lastError = result;
            }
            String title = results.size() == 1 ? tabTitle(result) : "Result " + (i + 1);
            ResultPage page = ResultPage.from(result, preferPlan && results.size() == 1, onExportRequest);
            resultTabs.getTabs().add(wrap(title, page, result.isError()));
        }
        if (lastError != null && results.size() > 1) {
            errorPanel.show(lastError.errorMessage());
        } else if (lastError != null) {
            errorPanel.show(lastError.errorMessage());
        }
        resultTabs.getSelectionModel().selectFirst();
    }

    public void clear() {
        showIdle();
    }

    private static Tab wrap(String title, ResultPage page, boolean error) {
        Tab tab = new Tab(title, page);
        tab.setClosable(false);
        if (error) {
            tab.getStyleClass().add("result-tab-error");
        }
        return tab;
    }

    private static String tabTitle(QueryResult result) {
        if (result.isError()) {
            return "Error";
        }
        if (result.isResultSet()) {
            return result.rowCount() + (result.rowCount() == 1 ? " row" : " rows");
        }
        return "Update";
    }

    /** One tab body: grid, plan tree, or message. */
    private static final class ResultPage extends StackPane {
        private final DynamicResultTable table = new DynamicResultTable();
        private final ExplainPlanTreeView planTree = new ExplainPlanTreeView();
        private QueryResult result;

        private ResultPage() {
            getChildren().addAll(table, planTree);
            planTree.setVisible(false);
            planTree.setManaged(false);
        }

        static ResultPage message(String text) {
            ResultPage page = new ResultPage();
            page.table.showMessage(text);
            return page;
        }

        static ResultPage from(QueryResult result, boolean preferPlan, Runnable onExport) {
            ResultPage page = new ResultPage();
            page.result = result;
            page.table.setOnExportRequest(onExport);
            if (result.isError()) {
                page.table.showMessage("Fix the statement above and run again.");
                return page;
            }
            if (preferPlan && result.isResultSet()) {
                page.showPlan(ExplainPlanParser.parse(result));
                return page;
            }
            var plan = ExplainPlanParser.tryParse(result);
            if (plan.isPresent() && looksUseful(plan.get())) {
                page.showPlan(plan.get());
                return page;
            }
            page.table.setResult(result);
            return page;
        }

        DynamicResultTable table() {
            return table;
        }

        QueryResult result() {
            return result;
        }

        private void showPlan(ExplainPlanNode plan) {
            table.setVisible(false);
            table.setManaged(false);
            planTree.setVisible(true);
            planTree.setManaged(true);
            planTree.setPlan(plan);
        }

        private static boolean looksUseful(ExplainPlanNode plan) {
            return plan != null && (!plan.label().isBlank() || !plan.children().isEmpty());
        }
    }
}
