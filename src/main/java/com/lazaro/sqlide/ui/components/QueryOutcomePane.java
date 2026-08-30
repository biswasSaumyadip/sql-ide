package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.ScriptResult;
import com.lazaro.sqlide.core.explain.ExplainPlanNode;
import com.lazaro.sqlide.core.explain.ExplainPlanParser;
import com.lazaro.sqlide.ui.dialogs.CompareDataDialog;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Everything below the editor: results toolbar, error strip, and one or more
 * result tabs (grid, update message, EXPLAIN plan, or editable table data).
 */
public final class QueryOutcomePane extends VBox {

    private static final String PINNED_STYLE = "result-tab-pinned";
    private static final String DATA_TAB_STYLE = "result-tab-data";
    private static final String OUTPUT_TAB_STYLE = "result-tab-output";
    private static final String DIRTY_MARK = "\u25CF ";

    private final ResultToolbar toolbar = new ResultToolbar();
    private final QueryErrorPanel errorPanel = new QueryErrorPanel();
    private final TabPane resultTabs = new TabPane();
    private final DynamicResultTable fallbackResults = new DynamicResultTable();
    private final OutputConsoleView output = new OutputConsoleView();
    private final Tab outputTab = createOutputTab();
    private final StackPane body = new StackPane(resultTabs);

    private Consumer<QueryResult> onExportToFile = result -> { };
    private Consumer<QueryResult> onExportJsonArray = result -> { };
    private Runnable onRefresh = () -> { };
    private Runnable onActionsChanged = () -> { };
    private Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner = statements ->
            CompletableFuture.completedFuture(ScriptResult.ofSingle(QueryResult.ofError("No runner", 0)));
    private Executor background = Runnable::run;
    private Function<String, java.util.Optional<EditableResultTarget>> editableResolver =
            sql -> java.util.Optional.empty();
    private boolean awaitingRunResult;

    public QueryOutcomePane() {
        getStyleClass().add("query-outcome-pane");
        setSpacing(0);
        VBox.setVgrow(body, Priority.ALWAYS);
        resultTabs.getStyleClass().add("result-tabs");
        resultTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);

        toolbar.setTableSupplier(this::results);
        toolbar.setOnExportToFile(result -> onExportToFile.accept(result));
        toolbar.setOnExportJsonArray(result -> onExportJsonArray.accept(result));
        toolbar.setOnRefresh(this::refreshActive);
        toolbar.setOnClear(this::clearUnpinned);
        toolbar.setOnTogglePin(this::togglePinSelected);
        toolbar.setOnToggleView(this::toggleViewSelected);
        toolbar.setOnCompare(this::comparePinnedResults);
        toolbar.setOnAddRow(() -> {
            TableDataEditSession session = activeEditSession();
            if (session != null) {
                session.addRow();
            }
        });
        toolbar.setOnDeleteRows(() -> {
            TableDataEditSession session = activeEditSession();
            if (session != null) {
                session.deleteSelected();
            }
        });
        toolbar.setOnSubmitEdits(() -> {
            TableDataEditSession session = activeEditSession();
            if (session != null) {
                session.submitChanges();
            }
        });
        toolbar.setOnRevertEdits(() -> {
            TableDataEditSession session = activeEditSession();
            if (session != null) {
                session.revertChanges();
            }
        });
        resultTabs.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> {
            syncToolbarState();
            onActionsChanged.run();
        });

        getChildren().addAll(toolbar, errorPanel, body);
        ensureOutputTab();
        showIdle();
    }

    private Tab createOutputTab() {
        Tab tab = new Tab("Output", output);
        tab.setClosable(false);
        tab.getStyleClass().add(OUTPUT_TAB_STYLE);
        return tab;
    }

    private void ensureOutputTab() {
        if (!resultTabs.getTabs().contains(outputTab)) {
            resultTabs.getTabs().add(0, outputTab);
        } else if (resultTabs.getTabs().indexOf(outputTab) != 0) {
            resultTabs.getTabs().remove(outputTab);
            resultTabs.getTabs().add(0, outputTab);
        }
    }

    public OutputConsoleView output() {
        return output;
    }

    public ResultToolbar toolbar() {
        return toolbar;
    }

    public void setOnExportToFile(Consumer<QueryResult> action) {
        this.onExportToFile = action == null ? result -> { } : action;
        toolbar.setOnExportToFile(this.onExportToFile);
    }

    public void setOnExportJsonArray(Consumer<QueryResult> action) {
        this.onExportJsonArray = action == null ? result -> { } : action;
        toolbar.setOnExportJsonArray(this.onExportJsonArray);
    }

    public void setOnRefresh(Runnable action) {
        this.onRefresh = action == null ? () -> { } : action;
    }

    public void setOnActionsChanged(Runnable action) {
        this.onActionsChanged = action == null ? () -> { } : action;
    }

    public void setScriptRunner(Function<List<String>, CompletableFuture<ScriptResult>> runner) {
        this.scriptRunner = runner == null
                ? statements -> CompletableFuture.completedFuture(
                ScriptResult.ofSingle(QueryResult.ofError("No runner", 0)))
                : runner;
    }

    public void setBackgroundExecutor(Executor executor) {
        this.background = executor == null ? Runnable::run : executor;
    }

    /**
     * Resolves whether a statement's result grid can be edited (simple SELECT + PK).
     */
    public void setEditableResultResolver(Function<String, java.util.Optional<EditableResultTarget>> resolver) {
        this.editableResolver = resolver == null ? sql -> java.util.Optional.empty() : resolver;
    }

    /** Target metadata for enabling edit mode on a query result tab. */
    public record EditableResultTarget(SchemaNode table, String qualifiedName, List<String> primaryKeyColumns) {
    }

    public void setRefreshEnabled(boolean enabled) {
        toolbar.setRefreshEnabled(enabled);
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

    /** Pinned result tabs that hold a {@link QueryResult}, for cross-connection data compare. */
    public List<CompareDataDialog.NamedResult> pinnedNamedResults() {
        List<CompareDataDialog.NamedResult> named = new ArrayList<>();
        for (Tab tab : resultTabs.getTabs()) {
            if (!isPinned(tab) || !(tab.getContent() instanceof ResultPage page)) {
                continue;
            }
            QueryResult result = page.result();
            if (result == null || !result.isResultSet() || result.isError()) {
                continue;
            }
            String name = tab.getText() == null ? "Result" : tab.getText();
            named.add(new CompareDataDialog.NamedResult(name, result));
        }
        return named;
    }

    /**
     * Opens (or focuses) an editable table-data tab in the results pane.
     */
    public void openTableData(
            SchemaNode node,
            String qualifiedName,
            List<String> primaryKeyColumns,
            Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner,
            Executor background) {
        if (node == null) {
            return;
        }
        for (Tab tab : resultTabs.getTabs()) {
            if (tab.getContent() instanceof ResultPage page
                    && page.editSession() != null
                    && page.editSession().matches(node)) {
                resultTabs.getSelectionModel().select(tab);
                syncToolbarState();
                return;
            }
        }

        ResultPage page = ResultPage.forTableData(
                node, qualifiedName, primaryKeyColumns, scriptRunner, background,
                onExportToFile, onExportJsonArray, output);
        Tab tab = wrap(node.name() + " data", page, false, false);
        tab.getStyleClass().add(DATA_TAB_STYLE);
        page.editSession().setOnDirtyChanged(() -> {
            refreshDataTabTitle(tab, page);
            syncToolbarState();
        });
        page.editSession().setOnStatusChanged(() -> {
            if (resultTabs.getSelectionModel().getSelectedItem() == tab) {
                toolbar.setSummary(page.editSession().statusText());
            }
        });
        tab.setOnCloseRequest(event -> {
            if (page.editSession() != null && !page.editSession().confirmClose()) {
                event.consume();
            }
        });
        tab.setOnClosed(event -> page.disposeEditSession());
        resultTabs.getTabs().add(tab);
        resultTabs.getSelectionModel().select(tab);
        syncToolbarState();
        onActionsChanged.run();
    }

    public void showIdle() {
        errorPanel.clear();
        replaceTransientWith(List.of());
        resultTabs.getSelectionModel().select(outputTab);
        toolbar.setSummary("");
        syncToolbarState();
    }

    public void showLoading() {
        showLoading(List.of());
    }

    public void showLoading(List<String> statements) {
        errorPanel.clear();
        awaitingRunResult = true;
        output.appendRunning(statements);
        replaceTransientWith(List.of(wrap("Running", ResultPage.message("Running query\u2026"), false, false)));
        toolbar.setSummary("Running\u2026");
        syncToolbarState();
    }

    public void showCancelling() {
        errorPanel.clear();
        output.appendCancelling();
        replaceTransientWith(List.of(wrap("Cancelling", ResultPage.message("Cancelling query\u2026"), false, false)));
        toolbar.setSummary("Cancelling\u2026");
        syncToolbarState();
    }

    public void present(QueryResult result) {
        present(result, false);
    }

    public void present(QueryResult result, boolean preferPlan) {
        presentScript(ScriptResult.ofSingle(result), preferPlan);
    }

    public void presentScript(ScriptResult script) {
        presentScript(script, false, List.of());
    }

    public void presentScript(ScriptResult script, boolean preferPlan) {
        presentScript(script, preferPlan, List.of());
    }

    public void presentScript(ScriptResult script, boolean preferPlan, List<String> sourceStatements) {
        errorPanel.clear();
        if (script == null || script.isEmpty()) {
            if (!awaitingRunResult) {
                output.appendSeparator();
            }
            output.appendInfo("Nothing executed");
            awaitingRunResult = false;
            showIdle();
            return;
        }

        if (!awaitingRunResult) {
            output.appendSeparator();
        }
        output.appendScript(script);
        awaitingRunResult = false;

        List<QueryResult> results = script.results();
        List<String> statements = sourceStatements == null ? List.of() : sourceStatements;
        List<Tab> fresh = new ArrayList<>(results.size());
        QueryResult lastError = null;
        boolean hasResultSet = false;
        for (int i = 0; i < results.size(); i++) {
            QueryResult result = results.get(i);
            if (result.isError()) {
                lastError = result;
            }
            if (result.isResultSet() && !result.isError()) {
                hasResultSet = true;
            }
            String title = results.size() == 1 ? tabTitle(result) : "Result " + (i + 1);
            ResultPage page = ResultPage.from(result, preferPlan && results.size() == 1, onExportToFile, onExportJsonArray);
            String sql = i < statements.size() ? statements.get(i) : null;
            maybeEnableEditing(page, result, sql);
            Tab tab = wrap(title, page, result.isError(), false);
            if (page.editSession() != null) {
                tab.getStyleClass().add(DATA_TAB_STYLE);
                page.editSession().setOnDirtyChanged(() -> {
                    refreshEditableTitle(tab, page, title);
                    syncToolbarState();
                });
                tab.setOnCloseRequest(event -> {
                    if (page.editSession() != null && !page.editSession().confirmClose()) {
                        event.consume();
                    }
                });
                tab.setOnClosed(event -> page.disposeEditSession());
            }
            fresh.add(tab);
        }
        replaceTransientWith(fresh);
        if (lastError != null) {
            errorPanel.show(lastError.errorMessage());
        }
        if (hasResultSet && !fresh.isEmpty()) {
            resultTabs.getSelectionModel().select(fresh.getFirst());
        } else {
            resultTabs.getSelectionModel().select(outputTab);
        }
        toolbar.setSummary(script.summary());
        syncToolbarState();
        onActionsChanged.run();
    }

    private void maybeEnableEditing(ResultPage page, QueryResult result, String sql) {
        if (sql == null || result == null || result.isError() || !result.isResultSet()) {
            return;
        }
        java.util.Optional<EditableResultTarget> target = editableResolver.apply(sql);
        if (target.isEmpty()) {
            return;
        }
        EditableResultTarget edit = target.get();
        if (edit.primaryKeyColumns() == null || edit.primaryKeyColumns().isEmpty()) {
            return;
        }
        // PK columns must be present in the result set.
        for (String pk : edit.primaryKeyColumns()) {
            boolean found = result.columnNames().stream().anyMatch(c -> c.equalsIgnoreCase(pk));
            if (!found) {
                return;
            }
        }
        page.enableEditing(edit.table(), edit.qualifiedName(), edit.primaryKeyColumns(),
                scriptRunner, background, output);
        page.editSession().bindLoadedResult(result);
    }

    public void clear() {
        showIdle();
    }

    public void clearUnpinned() {
        List<Tab> keep = stickyTabs();
        resultTabs.getTabs().setAll(keep);
        ensureOutputTab();
        resultTabs.getSelectionModel().select(outputTab);
        toolbar.setSummary("");
        syncToolbarState();
        onActionsChanged.run();
    }

    /** Prompts for dirty data tabs (e.g. app exit). */
    public boolean confirmCloseAll() {
        for (Tab tab : List.copyOf(resultTabs.getTabs())) {
            if (tab.getContent() instanceof ResultPage page
                    && page.editSession() != null
                    && page.editSession().isDirty()) {
                resultTabs.getSelectionModel().select(tab);
                if (!page.editSession().confirmClose()) {
                    return false;
                }
            }
        }
        return true;
    }

    private void refreshActive() {
        TableDataEditSession session = activeEditSession();
        if (session != null) {
            session.reloadWithConfirm();
            return;
        }
        onRefresh.run();
    }

    private TableDataEditSession activeEditSession() {
        Tab selected = resultTabs.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getContent() instanceof ResultPage page) {
            return page.editSession();
        }
        return null;
    }

    private void replaceTransientWith(List<Tab> fresh) {
        List<Tab> sticky = stickyTabs();
        List<Tab> next = new ArrayList<>(1 + sticky.size() + fresh.size());
        next.add(outputTab);
        for (Tab tab : sticky) {
            if (tab != outputTab) {
                next.add(tab);
            }
        }
        next.addAll(fresh);
        resultTabs.getTabs().setAll(next);
    }

    private List<Tab> stickyTabs() {
        List<Tab> sticky = new ArrayList<>();
        for (Tab tab : resultTabs.getTabs()) {
            if (isSticky(tab)) {
                sticky.add(tab);
            }
        }
        return sticky;
    }

    private static boolean isSticky(Tab tab) {
        return isPinned(tab) || isDataTab(tab) || isOutputTab(tab);
    }

    private static boolean isDataTab(Tab tab) {
        return tab != null && tab.getStyleClass().contains(DATA_TAB_STYLE);
    }

    private static boolean isOutputTab(Tab tab) {
        return tab != null && tab.getStyleClass().contains(OUTPUT_TAB_STYLE);
    }

    private void togglePinSelected() {
        Tab selected = resultTabs.getSelectionModel().getSelectedItem();
        if (selected == null || isOutputTab(selected) || !(selected.getContent() instanceof ResultPage)) {
            return;
        }
        setPinned(selected, !isPinned(selected));
        syncToolbarState();
        onActionsChanged.run();
    }

    private void toggleViewSelected() {
        Tab selected = resultTabs.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getContent() instanceof ResultPage page) {
            page.toggleView();
            syncToolbarState();
        }
    }

    /** Side-by-side compare of two pinned result grids. */
    private void comparePinnedResults() {
        List<Tab> pinned = new ArrayList<>();
        for (Tab tab : resultTabs.getTabs()) {
            if (isPinned(tab) && tab.getContent() instanceof ResultPage page
                    && page.table() != null && page.table().hasExportableResult()) {
                pinned.add(tab);
            }
        }
        if (pinned.size() < 2) {
            toolbar.setSummary("Pin at least two result tabs to compare");
            return;
        }
        Tab leftTab;
        Tab rightTab;
        Tab selected = resultTabs.getSelectionModel().getSelectedItem();
        if (pinned.size() > 2 && selected != null && pinned.contains(selected)) {
            leftTab = selected;
            rightTab = pinned.get(0) == selected ? pinned.get(1) : pinned.get(0);
        } else {
            leftTab = pinned.get(0);
            rightTab = pinned.get(1);
        }
        ResultPage left = (ResultPage) leftTab.getContent();
        ResultPage right = (ResultPage) rightTab.getContent();
        for (Tab tab : resultTabs.getTabs()) {
            if (tab.getStyleClass().contains("result-tab-compare")) {
                resultTabs.getTabs().remove(tab);
                break;
            }
        }
        javafx.scene.control.SplitPane split = new javafx.scene.control.SplitPane();
        split.setOrientation(javafx.geometry.Orientation.HORIZONTAL);
        DynamicResultTable leftGrid = new DynamicResultTable();
        DynamicResultTable rightGrid = new DynamicResultTable();
        if (left.result() != null) {
            leftGrid.setResult(left.result());
        }
        if (right.result() != null) {
            rightGrid.setResult(right.result());
        }
        VBox leftBox = new VBox(4, new Label(leftTab.getText()), leftGrid);
        VBox rightBox = new VBox(4, new Label(rightTab.getText()), rightGrid);
        VBox.setVgrow(leftGrid, Priority.ALWAYS);
        VBox.setVgrow(rightGrid, Priority.ALWAYS);
        leftBox.setPadding(new Insets(6));
        rightBox.setPadding(new Insets(6));
        split.getItems().addAll(leftBox, rightBox);
        split.setDividerPositions(0.5);
        Tab compare = new Tab("Compare", split);
        compare.getStyleClass().add("result-tab-compare");
        compare.setClosable(true);
        resultTabs.getTabs().add(compare);
        resultTabs.getSelectionModel().select(compare);
        toolbar.setSummary("Comparing " + leftTab.getText() + " | " + rightTab.getText());
        syncToolbarState();
    }

    private static void refreshEditableTitle(Tab tab, ResultPage page, String baseTitle) {
        if (page.editSession() != null && page.editSession().isDirty()) {
            tab.setText(DIRTY_MARK + baseTitle);
        } else {
            tab.setText(baseTitle);
        }
    }

    private static boolean isPinned(Tab tab) {
        return tab != null && tab.getStyleClass().contains(PINNED_STYLE);
    }

    private static void setPinned(Tab tab, boolean pinned) {
        if (tab == null) {
            return;
        }
        tab.getStyleClass().remove(PINNED_STYLE);
        if (pinned) {
            tab.getStyleClass().add(PINNED_STYLE);
            tab.setGraphic(com.lazaro.sqlide.ui.Icons.pin());
            tab.setClosable(false);
        } else {
            tab.setGraphic(null);
            tab.setClosable(true);
        }
    }

    private void syncToolbarState() {
        Tab selected = resultTabs.getSelectionModel().getSelectedItem();
        boolean hasPage = selected != null && selected.getContent() instanceof ResultPage;
        DynamicResultTable table = hasPage ? ((ResultPage) selected.getContent()).table() : null;
        toolbar.bindActiveTable(table, hasPage);
        toolbar.setPinnedSelected(isPinned(selected) && !isOutputTab(selected));
        int pinnedResults = 0;
        for (Tab tab : resultTabs.getTabs()) {
            if (isPinned(tab) && tab.getContent() instanceof ResultPage page
                    && page.table() != null && page.table().hasExportableResult()) {
                pinnedResults++;
            }
        }
        toolbar.setCompareEnabled(pinnedResults >= 2);
        boolean hasClearable = false;
        for (Tab tab : resultTabs.getTabs()) {
            if (!isSticky(tab)) {
                hasClearable = true;
                break;
            }
        }
        // Keep Clear usable even while Output is focused.
        if (isOutputTab(selected)) {
            toolbar.setClearEnabled(hasClearable);
        }
        if (hasPage) {
            ResultPage page = (ResultPage) selected.getContent();
            toolbar.setViewToggleAvailable(page.hasPlan(), page.showingPlan());
            TableDataEditSession session = page.editSession();
            if (session != null) {
                toolbar.setDataEditMode(true, session.editable(), session.isDirty());
                toolbar.setSummary(session.statusText());
            } else {
                toolbar.setDataEditMode(false, false, false);
            }
        } else {
            toolbar.setViewToggleAvailable(false, false);
            toolbar.setDataEditMode(false, false, false);
        }
        toolbar.reapplyFindIfOpen();
    }

    private static void refreshDataTabTitle(Tab tab, ResultPage page) {
        TableDataEditSession session = page.editSession();
        if (session == null) {
            return;
        }
        String base = session.schemaTable().name() + " data";
        tab.setText(session.isDirty() ? DIRTY_MARK + base : base);
    }

    private static Tab wrap(String title, ResultPage page, boolean error, boolean pinned) {
        Tab tab = new Tab(title, page);
        tab.setClosable(!pinned);
        if (error) {
            tab.getStyleClass().add("result-tab-error");
        }
        if (pinned) {
            setPinned(tab, true);
        }
        return tab;
    }

    private static String tabTitle(QueryResult result) {
        if (result.isError()) {
            return "Error";
        }
        if (result.isResultSet()) {
            if (result.truncated()) {
                return result.rowCount() + "+ rows";
            }
            return result.rowCount() + (result.rowCount() == 1 ? " row" : " rows");
        }
        return "Update";
    }

    private static final class ResultPage extends VBox {
        private final Label truncationBanner = new Label();
        private final StackPane content = new StackPane();
        private final DynamicResultTable table = new DynamicResultTable();
        private final ExplainPlanTreeView planTree = new ExplainPlanTreeView();
        private QueryResult result;
        private ExplainPlanNode plan;
        private boolean showingPlan;
        private TableDataEditSession editSession;

        private ResultPage() {
            truncationBanner.getStyleClass().add("result-truncation-banner");
            truncationBanner.setWrapText(true);
            truncationBanner.setMaxWidth(Double.MAX_VALUE);
            truncationBanner.setPadding(new Insets(6, 10, 6, 10));
            truncationBanner.setVisible(false);
            truncationBanner.setManaged(false);

            content.getChildren().addAll(table, planTree);
            planTree.setVisible(false);
            planTree.setManaged(false);
            VBox.setVgrow(content, Priority.ALWAYS);

            getChildren().addAll(truncationBanner, content);
        }

        static ResultPage message(String text) {
            ResultPage page = new ResultPage();
            page.table.showMessage(text);
            return page;
        }

        static ResultPage from(
                QueryResult result,
                boolean preferPlan,
                Consumer<QueryResult> onExportToFile,
                Consumer<QueryResult> onExportJsonArray) {
            ResultPage page = new ResultPage();
            page.result = result;
            page.table.setOnExportToFile(onExportToFile);
            page.table.setOnExportJsonArray(onExportJsonArray);
            page.applyTruncationBanner(result);
            if (result.isError()) {
                page.table.showMessage("Fix the statement above and run again.");
                return page;
            }
            if (result.isResultSet()) {
                page.table.setResult(result);
                ExplainPlanNode parsed = preferPlan
                        ? ExplainPlanParser.parse(result)
                        : ExplainPlanParser.tryParse(result).orElse(null);
                if (parsed != null && looksUseful(parsed)) {
                    page.plan = parsed;
                    page.showPlan(parsed);
                }
                return page;
            }
            page.table.setResult(result);
            return page;
        }

        void enableEditing(
                SchemaNode node,
                String qualifiedName,
                List<String> primaryKeyColumns,
                Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner,
                Executor background,
                OutputConsoleView output) {
            if (editSession != null) {
                return;
            }
            TableDataEditSession session = new TableDataEditSession(
                    table, node, qualifiedName, primaryKeyColumns, scriptRunner, background);
            if (output != null) {
                session.setOutputHooks(output::appendRunning, output::appendScript);
            }
            table.attachEditSession(session);
            this.editSession = session;
        }

        static ResultPage forTableData(
                SchemaNode node,
                String qualifiedName,
                List<String> primaryKeyColumns,
                Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner,
                Executor background,
                Consumer<QueryResult> onExportToFile,
                Consumer<QueryResult> onExportJsonArray,
                OutputConsoleView output) {
            ResultPage page = new ResultPage();
            page.table.setOnExportToFile(onExportToFile);
            page.table.setOnExportJsonArray(onExportJsonArray);
            TableDataEditSession session = new TableDataEditSession(
                    page.table, node, qualifiedName, primaryKeyColumns, scriptRunner, background);
            session.setOutputHooks(output::appendRunning, output::appendScript);
            page.table.attachEditSession(session);
            page.editSession = session;
            session.reload();
            return page;
        }

        DynamicResultTable table() {
            return table;
        }

        QueryResult result() {
            return result != null ? result : table.currentResult();
        }

        TableDataEditSession editSession() {
            return editSession;
        }

        void disposeEditSession() {
            table.detachEditSession();
            editSession = null;
        }

        boolean hasPlan() {
            return plan != null;
        }

        boolean showingPlan() {
            return showingPlan;
        }

        void toggleView() {
            if (plan == null) {
                return;
            }
            if (showingPlan) {
                showGrid();
            } else {
                showPlan(plan);
            }
        }

        private void applyTruncationBanner(QueryResult result) {
            String banner = result == null ? null : result.truncationBanner();
            if (banner == null || banner.isBlank()) {
                truncationBanner.setVisible(false);
                truncationBanner.setManaged(false);
                truncationBanner.setText("");
                return;
            }
            truncationBanner.setText(banner);
            truncationBanner.setVisible(true);
            truncationBanner.setManaged(true);
        }

        private void showPlan(ExplainPlanNode plan) {
            this.plan = plan;
            showingPlan = true;
            table.setVisible(false);
            table.setManaged(false);
            planTree.setVisible(true);
            planTree.setManaged(true);
            planTree.setPlan(plan);
        }

        private void showGrid() {
            showingPlan = false;
            planTree.setVisible(false);
            planTree.setManaged(false);
            table.setVisible(true);
            table.setManaged(true);
        }

        private static boolean looksUseful(ExplainPlanNode plan) {
            return plan != null && (!plan.label().isBlank() || !plan.children().isEmpty());
        }
    }
}
