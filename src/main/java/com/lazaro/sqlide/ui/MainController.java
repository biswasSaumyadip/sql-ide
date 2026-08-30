package com.lazaro.sqlide.ui;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.DriverRegistry;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.explain.ExplainSql;
import com.lazaro.sqlide.ui.components.EditorTabPane;
import com.lazaro.sqlide.ui.components.QueryOutcomePane;
import com.lazaro.sqlide.ui.components.SchemaTreeView;
import com.lazaro.sqlide.ui.components.SqlEditorPane;
import com.lazaro.sqlide.ui.components.StatusBar;
import com.lazaro.sqlide.ui.dialogs.ConnectionDialog;
import javafx.concurrent.Task;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Builds the main window and connects its actions to a {@link DataSourceDriver}
 * obtained from the {@link DriverRegistry}. No concrete driver class is named here.
 *
 * <p>Database work always runs inside a {@link Task} on a background executor;
 * results reach the scene graph only through the Task's JavaFX callbacks. Failures
 * are surfaced inline — never through modal alerts that interrupt editing flow.
 */
public final class MainController {

    /** Width left visible when the schema explorer is collapsed. */
    private static final double RAIL_WIDTH = 42;
    private static final double DEFAULT_MAIN_DIVIDER = 0.22;
    private static final double DEFAULT_RIGHT_DIVIDER = 0.55;

    private final DriverRegistry registry;
    private final WorkspaceState state;
    private final ExecutorService backgroundTasks = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "sqlide-ui-task");
        thread.setDaemon(true);
        return thread;
    });

    private final SchemaTreeView schemaTree = new SchemaTreeView();
    private final EditorTabPane editors = new EditorTabPane();
    private final QueryOutcomePane outcome = new QueryOutcomePane();
    private final StatusBar statusBar = new StatusBar();
    private final SchemaCache schemaCache = new SchemaCache();

    private final SplitPane mainSplit = new SplitPane();
    private final SplitPane rightSplit = new SplitPane();
    private final BorderPane sidebar = new BorderPane();
    private final BorderPane root = new BorderPane();

    private Button runButton;
    private Button stopButton;
    private Button explainButton;
    private Button explainAnalyzeButton;
    private ToggleButton autoCommitToggle;
    private Button beginButton;
    private Button commitButton;
    private Button rollbackButton;
    private Button connectButton;
    private Button disconnectButton;
    private Button refreshButton;
    private ProgressIndicator toolbarActivity;

    private DataSourceDriver driver;
    private Task<?> activeTask;
    private volatile boolean cancelling;
    private boolean sidebarCollapsed;
    private double expandedMainDivider = DEFAULT_MAIN_DIVIDER;

    public MainController(DriverRegistry registry, WorkspaceState state) {
        this.registry = registry;
        this.state = state;
        this.driver = registry.create(DriverRegistry.DEFAULT_DRIVER_ID);
        schemaTree.setDriver(driver);
        editors.setSchemaCache(() -> schemaCache);
        editors.setActiveCatalog(() -> driver.activeCatalog().orElse(null));
    }

    // ---------------------------------------------------------------- view

    public Parent createView() {
        root.getStyleClass().add("app-root");
        root.setTop(buildToolBar());
        root.setCenter(buildMainSplit());
        root.setBottom(statusBar);

        schemaTree.setOnConnectRequested(this::openConnectionDialog);
        schemaTree.setOnActivate(this::insertNodeReference);
        schemaTree.setOnViewObject(this::openObjectViewer);
        schemaTree.setOnUseDatabase(this::useDatabase);
        editors.activeEditorProperty().addListener((observable, previous, current) -> bindCaret(current));
        bindCaret(editors.activeEditor());

        updateActionStates();
        return root;
    }

    private ToolBar buildToolBar() {
        Button sidebarToggle = iconButton(Icons.sidebar(), "Toggle Schema Explorer (Ctrl+1)", this::toggleSidebar);
        Button newQuery = iconButton(Icons.newQuery(), "New Query (Ctrl+T)", editors::newTab);
        Button save = iconButton(Icons.save(), "Save Query (Ctrl+S)", () -> editors.saveActiveTab(owner()));

        connectButton = labelledButton(Icons.connect(), "Connect", "Connect to a database (Ctrl+K)",
                this::openConnectionDialog);
        disconnectButton = iconButton(Icons.disconnect(), "Disconnect", this::disconnect);
        refreshButton = iconButton(Icons.refresh(), "Refresh Schema (Ctrl+R)", this::refreshSchema);

        runButton = labelledButton(Icons.run(), "Run", "Execute (Ctrl+Enter)", this::runQuery);
        runButton.getStyleClass().add("run-button");
        stopButton = labelledButton(Icons.stop(), "Stop", "Cancel running query (Ctrl+Break)", this::cancelQuery);
        stopButton.getStyleClass().add("stop-button");
        explainButton = iconButton(Icons.explain(), "EXPLAIN selected statement (Ctrl+Shift+E)",
                () -> runExplain(false));
        explainAnalyzeButton = labelledButton(Icons.explain(), "Analyze",
                "EXPLAIN ANALYZE selected statement (Ctrl+Shift+A)", () -> runExplain(true));

        autoCommitToggle = new ToggleButton("Auto-commit");
        autoCommitToggle.getStyleClass().add("toolbar-toggle");
        autoCommitToggle.setSelected(state.autoCommit());
        autoCommitToggle.setTooltip(new Tooltip("Toggle auto-commit (off = manual transactions)"));
        autoCommitToggle.setOnAction(event -> toggleAutoCommit());
        beginButton = labelledButton(Icons.begin(), "Begin", "Begin manual transaction", this::beginTransaction);
        commitButton = labelledButton(Icons.commit(), "Commit", "Commit current transaction", this::commitTransaction);
        rollbackButton = labelledButton(Icons.rollback(), "Rollback", "Rollback current transaction",
                this::rollbackTransaction);

        toolbarActivity = new ProgressIndicator();
        toolbarActivity.setMaxSize(16, 16);
        toolbarActivity.getStyleClass().add("toolbar-activity");
        toolbarActivity.setVisible(false);
        toolbarActivity.setManaged(false);

        ToolBar toolBar = new ToolBar(
                sidebarToggle,
                separator(),
                newQuery, save,
                separator(),
                connectButton, disconnectButton, refreshButton,
                separator(),
                runButton, stopButton, explainButton, explainAnalyzeButton, toolbarActivity,
                separator(),
                autoCommitToggle, beginButton, commitButton, rollbackButton);
        toolBar.getStyleClass().add("app-toolbar");
        return toolBar;
    }

    private Node buildMainSplit() {
        VBox rail = new VBox(railToggle());
        rail.getStyleClass().add("sidebar-rail");
        rail.setAlignment(Pos.TOP_CENTER);
        rail.setMinWidth(RAIL_WIDTH);
        rail.setPrefWidth(RAIL_WIDTH);
        rail.setMaxWidth(RAIL_WIDTH);

        sidebar.getStyleClass().add("sidebar");
        sidebar.setLeft(rail);
        sidebar.setCenter(schemaTree);
        sidebar.setMinWidth(RAIL_WIDTH);

        outcome.setMinHeight(80);
        rightSplit.setOrientation(Orientation.VERTICAL);
        rightSplit.getItems().setAll(editors, outcome);
        rightSplit.setMinWidth(320);
        SplitPane.setResizableWithParent(outcome, true);

        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.getItems().setAll(sidebar, rightSplit);
        SplitPane.setResizableWithParent(sidebar, false);
        mainSplit.setDividerPositions(DEFAULT_MAIN_DIVIDER);

        return mainSplit;
    }

    private Button railToggle() {
        Button toggle = iconButton(Icons.sidebar(), "Toggle Schema Explorer (Ctrl+1)", this::toggleSidebar);
        toggle.getStyleClass().add("rail-button");
        return toggle;
    }

    /** Installs shortcuts as filters so the editor cannot swallow them first. */
    public void installShortcuts(Scene scene) {
        KeyCombination run = new KeyCodeCombination(KeyCode.ENTER, KeyCombination.SHORTCUT_DOWN);
        KeyCombination runAlt = new KeyCodeCombination(KeyCode.F5);
        KeyCombination stop = new KeyCodeCombination(KeyCode.PAUSE, KeyCombination.SHORTCUT_DOWN);
        KeyCombination explain = new KeyCodeCombination(KeyCode.E, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCombination explainAnalyze = new KeyCodeCombination(
                KeyCode.A, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCombination toggleSidebar = new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN);
        KeyCombination newTab = new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN);
        KeyCombination closeTab = new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN);
        KeyCombination save = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
        KeyCombination connect = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);
        KeyCombination refresh = new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (run.match(event) || runAlt.match(event)) {
                consumeAnd(event, this::runQuery);
            } else if (stop.match(event)) {
                consumeAnd(event, this::cancelQuery);
            } else if (explainAnalyze.match(event)) {
                consumeAnd(event, () -> runExplain(true));
            } else if (explain.match(event)) {
                consumeAnd(event, () -> runExplain(false));
            } else if (toggleSidebar.match(event)) {
                consumeAnd(event, this::toggleSidebar);
            } else if (newTab.match(event)) {
                consumeAnd(event, editors::newTab);
            } else if (closeTab.match(event)) {
                consumeAnd(event, editors::closeActiveTab);
            } else if (save.match(event)) {
                consumeAnd(event, () -> editors.saveActiveTab(owner()));
            } else if (connect.match(event)) {
                consumeAnd(event, this::openConnectionDialog);
            } else if (refresh.match(event)) {
                consumeAnd(event, this::refreshSchema);
            }
        });
    }

    // ---------------------------------------------------------------- lifecycle

    public void restoreLayout() {
        mainSplit.setDividerPositions(state.mainDivider(DEFAULT_MAIN_DIVIDER));
        rightSplit.setDividerPositions(state.rightDivider(DEFAULT_RIGHT_DIVIDER));
        expandedMainDivider = mainSplit.getDividerPositions()[0];

        if (state.sidebarCollapsed()) {
            sidebarCollapsed = false;
            toggleSidebar();
        }
    }

    public boolean confirmExit() {
        return editors.confirmCloseAll();
    }

    public void saveState(Stage stage) {
        state.saveWindow(stage);
        state.saveLayout(
                sidebarCollapsed ? expandedMainDivider : mainSplit.getDividerPositions()[0],
                rightSplit.getDividerPositions()[0],
                sidebarCollapsed);
    }

    public void shutdown() {
        editors.dispose();
        driver.close();
        backgroundTasks.shutdownNow();
    }

    // ---------------------------------------------------------------- actions

    private void toggleSidebar() {
        if (sidebarCollapsed) {
            schemaTree.setVisible(true);
            schemaTree.setManaged(true);
            mainSplit.setDividerPositions(expandedMainDivider);
            sidebarCollapsed = false;
        } else {
            expandedMainDivider = mainSplit.getDividerPositions()[0];
            schemaTree.setVisible(false);
            schemaTree.setManaged(false);
            double width = Math.max(mainSplit.getWidth(), 1);
            mainSplit.setDividerPositions(RAIL_WIDTH / width);
            sidebarCollapsed = true;
        }
    }

    private void openConnectionDialog() {
        ConnectionDialog dialog = new ConnectionDialog(state.lastConnection(), driver);
        dialog.initOwner(owner());
        dialog.showAndWait().ifPresent(this::connect);
    }

    private void connect(ConnectionConfig config) {
        if (activeTask != null && activeTask.isRunning()) {
            return;
        }
        DataSourceDriver active = driver;
        setConnecting(true);
        statusBar.setBusy("Connecting to " + config.displayLabel() + "\u2026");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                active.connect(config).get();
                return null;
            }
        };
        activeTask = task;
        task.setOnSucceeded(event -> {
            activeTask = null;
            setConnecting(false);
            String database = config.database().isBlank()
                    ? active.activeCatalog().orElse(null)
                    : config.database();
            statusBar.setConnected(config.endpointLabel(), database);
            state.saveLastConnection(config);
            schemaTree.reload();
            refreshSchemaCache();
            applyPreferredAutoCommit();
            updateActionStates();
        });
        task.setOnFailed(event -> {
            activeTask = null;
            setConnecting(false);
            String message = rootCauseMessage(task.getException());
            statusBar.setConnectionError("Connection failed: " + message);
            schemaTree.clear();
            schemaCache.clear();
            updateActionStates();
        });
        backgroundTasks.execute(task);
    }

    private void disconnect() {
        cancelling = false;
        DataSourceDriver retired = driver;
        driver = registry.create(DriverRegistry.DEFAULT_DRIVER_ID);

        schemaTree.setDriver(driver);
        schemaTree.clear();
        schemaCache.clear();
        outcome.clear();
        statusBar.setDisconnected();
        autoCommitToggle.setSelected(state.autoCommit());
        updateActionStates();

        backgroundTasks.execute(retired::close);
    }

    /** Reloads the tree and the client-side autocomplete/object-viewer cache. */
    private void refreshSchema() {
        if (!driver.isConnected()) {
            return;
        }
        schemaTree.reload();
        refreshSchemaCache();
    }

    /**
     * Pulls the full schema once into {@link #schemaCache}. Failures leave the
     * previous snapshot intact so typing is not disrupted by a flaky refresh.
     */
    private void refreshSchemaCache() {
        DataSourceDriver active = driver;
        if (!active.isConnected()) {
            schemaCache.clear();
            return;
        }
        active.getFullSchema().whenComplete((nodes, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null || nodes == null) {
                return;
            }
            schemaCache.replace(nodes);
            editors.refreshAutocompleteEngines();
        }));
    }

    private void openObjectViewer(SchemaNode node) {
        // Prefer the richly populated cache entry over the thin tree node.
        SchemaNode detailed = schemaCache.findTable(node.name()).orElse(node);
        editors.openObjectViewer(detailed);
    }

    /**
     * Switches the session to the catalog implied by {@code node}: the node itself
     * when it is a database/schema, otherwise its {@link SchemaNode#META_CATALOG}.
     */
    private void useDatabase(SchemaNode node) {
        if (node == null || !driver.isConnected()) {
            return;
        }
        String catalog = switch (node.type()) {
            case DATABASE, SCHEMA -> node.name();
            case TABLE, VIEW, COLUMN -> {
                String meta = node.metadata(SchemaNode.META_CATALOG);
                yield meta == null || meta.isBlank() ? null : meta;
            }
        };
        if (catalog == null || catalog.isBlank()) {
            return;
        }

        String previous = driver.activeCatalog().orElse(null);
        if (catalog.equalsIgnoreCase(previous)) {
            statusBar.setActiveDatabase(previous);
            return;
        }

        DataSourceDriver active = driver;
        statusBar.setBusy("Using database " + catalog + "\u2026");
        active.setActiveCatalog(catalog).whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                statusBar.setConnectionError("Could not use " + catalog + ": " + rootCauseMessage(error));
                active.currentConfig().ifPresent(config ->
                        statusBar.setConnected(config.endpointLabel(), active.activeCatalog().orElse(null)));
                return;
            }
            active.currentConfig().ifPresentOrElse(
                    config -> statusBar.setConnected(config.endpointLabel(), catalog),
                    () -> statusBar.setActiveDatabase(catalog));
        }));
    }

    private void runQuery() {
        executeSql(null, false);
    }

    private void runExplain(boolean analyze) {
        executeSql(analyze, true);
    }

    /**
     * @param analyze {@code null} for a normal run; otherwise explain mode flag
     * @param preferPlan whether the outcome pane should open the plan tree
     */
    private void executeSql(Boolean analyze, boolean preferPlan) {
        if (activeTask != null && activeTask.isRunning()) {
            return;
        }
        SqlEditorPane editor = editors.activeEditor();
        if (editor == null) {
            return;
        }
        DataSourceDriver active = driver;
        if (!active.isConnected()) {
            outcome.present(QueryResult.ofError("Not connected. Use Connect or New Connection first.", 0));
            statusBar.setResult(QueryResult.ofError("Not connected", 0));
            return;
        }

        String sql = editor.getEffectiveSql();
        if (sql.isBlank()) {
            outcome.present(QueryResult.ofError("Nothing to run. Type a statement or select one.", 0));
            statusBar.setResult(QueryResult.ofError("Nothing to run", 0));
            return;
        }

        ConnectionConfig.Driver dialect = active.currentConfig()
                .map(ConnectionConfig::driver)
                .orElse(ConnectionConfig.Driver.MYSQL);
        final String toRun;
        if (analyze == null) {
            toRun = sql;
        } else {
            toRun = ExplainSql.wrap(sql, dialect, analyze);
            if (toRun.isBlank()) {
                outcome.present(QueryResult.ofError("Nothing to explain.", 0));
                return;
            }
        }

        cancelling = false;
        setQueryRunning(true);
        outcome.showLoading();
        statusBar.setQueryRunning();

        Task<QueryResult> task = new Task<>() {
            @Override
            protected QueryResult call() throws Exception {
                return active.executeQueryAsync(toRun).get();
            }
        };
        activeTask = task;
        task.setOnSucceeded(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            QueryResult result = task.getValue();
            outcome.present(result, preferPlan);
            statusBar.setResult(result);
            syncTransactionStatus();
            if (!result.isError() && analyze == null) {
                syncActiveCatalogFromSql(sql);
            }
        });
        task.setOnFailed(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            QueryResult result = QueryResult.ofError(rootCauseMessage(task.getException()), 0);
            outcome.present(result);
            statusBar.setResult(result);
            syncTransactionStatus();
        });
        task.setOnCancelled(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            QueryResult result = QueryResult.ofError("Query cancelled", 0);
            outcome.present(result);
            statusBar.setResult(result);
            syncTransactionStatus();
        });
        backgroundTasks.execute(task);
    }

    private void cancelQuery() {
        Task<?> task = activeTask;
        if (task == null || !task.isRunning() || cancelling) {
            return;
        }
        cancelling = true;
        outcome.showCancelling();
        statusBar.setQueryRunning();
        DataSourceDriver active = driver;
        active.cancelExecution().whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (task.isRunning()) {
                task.cancel(true);
            }
            updateActionStates();
        }));
        updateActionStates();
    }

    private void toggleAutoCommit() {
        if (!driver.isConnected() || !driver.capabilities().supportsTransactions()) {
            autoCommitToggle.setSelected(true);
            return;
        }
        boolean enabled = autoCommitToggle.isSelected();
        DataSourceDriver active = driver;
        statusBar.setBusy(enabled ? "Enabling auto-commit\u2026" : "Starting manual transaction\u2026");
        active.setAutoCommit(enabled).whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                autoCommitToggle.setSelected(active.isAutoCommit());
                statusBar.setConnectionError("Auto-commit: " + rootCauseMessage(error));
                syncTransactionStatus();
                updateActionStates();
                return;
            }
            state.saveAutoCommit(enabled);
            active.currentConfig().ifPresent(config ->
                    statusBar.setConnected(config.endpointLabel(), active.activeCatalog().orElse(null)));
            syncTransactionStatus();
            updateActionStates();
        }));
    }

    private void beginTransaction() {
        if (!driver.isConnected()) {
            return;
        }
        DataSourceDriver active = driver;
        statusBar.setBusy("Beginning transaction\u2026");
        active.beginTransaction().whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                statusBar.setConnectionError("Begin failed: " + rootCauseMessage(error));
            } else {
                autoCommitToggle.setSelected(false);
                state.saveAutoCommit(false);
                active.currentConfig().ifPresent(config ->
                        statusBar.setConnected(config.endpointLabel(), active.activeCatalog().orElse(null)));
            }
            syncTransactionStatus();
            updateActionStates();
        }));
    }

    private void commitTransaction() {
        if (!driver.isConnected() || driver.isAutoCommit()) {
            return;
        }
        DataSourceDriver active = driver;
        statusBar.setBusy("Committing\u2026");
        active.commit().whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                statusBar.setConnectionError("Commit failed: " + rootCauseMessage(error));
            } else {
                active.currentConfig().ifPresent(config ->
                        statusBar.setConnected(config.endpointLabel(), active.activeCatalog().orElse(null)));
                outcome.showIdle();
                outcome.results().showMessage("Transaction committed.");
                statusBar.clearResult();
            }
            syncTransactionStatus();
            updateActionStates();
        }));
    }

    private void rollbackTransaction() {
        if (!driver.isConnected() || driver.isAutoCommit()) {
            return;
        }
        DataSourceDriver active = driver;
        statusBar.setBusy("Rolling back\u2026");
        active.rollback().whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                statusBar.setConnectionError("Rollback failed: " + rootCauseMessage(error));
            } else {
                active.currentConfig().ifPresent(config ->
                        statusBar.setConnected(config.endpointLabel(), active.activeCatalog().orElse(null)));
                outcome.showIdle();
                outcome.results().showMessage("Transaction rolled back.");
            }
            syncTransactionStatus();
            updateActionStates();
        }));
    }

    private void applyPreferredAutoCommit() {
        boolean preferred = state.autoCommit();
        autoCommitToggle.setSelected(preferred);
        DataSourceDriver active = driver;
        if (!active.isConnected() || !active.capabilities().supportsTransactions()) {
            syncTransactionStatus();
            return;
        }
        if (active.isAutoCommit() == preferred) {
            syncTransactionStatus();
            return;
        }
        active.setAutoCommit(preferred).whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                autoCommitToggle.setSelected(active.isAutoCommit());
            }
            syncTransactionStatus();
            updateActionStates();
        }));
    }

    private void syncTransactionStatus() {
        boolean connected = driver.isConnected();
        boolean auto = !connected || driver.isAutoCommit();
        statusBar.setTransactionState(auto, connected);
        autoCommitToggle.setSelected(auto);
    }

    private void insertNodeReference(SchemaNode node) {
        String text = switch (node.type()) {
            case TABLE, VIEW -> node.qualifiedName();
            default -> node.name();
        };
        editors.insertIntoActiveEditor(text);
    }

    /** Keeps the footer in sync when the user runs {@code USE db} from the editor. */
    private void syncActiveCatalogFromSql(String sql) {
        String catalog = parseUseCatalog(sql);
        if (catalog == null) {
            return;
        }
        DataSourceDriver active = driver;
        active.setActiveCatalog(catalog).whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                return;
            }
            active.currentConfig().ifPresent(config ->
                    statusBar.setConnected(config.endpointLabel(), catalog));
        }));
    }

    /** @return catalog name from a USE statement, or {@code null} if {@code sql} is not one */
    static String parseUseCatalog(String sql) {
        if (sql == null) {
            return null;
        }
        String trimmed = sql.strip();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1).strip();
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("(?i)^USE\\s+([`\"\\[]?[A-Za-z0-9_]+[`\"\\]]?)$")
                .matcher(trimmed);
        if (!matcher.matches()) {
            return null;
        }
        String name = matcher.group(1);
        if (name.length() >= 2) {
            char first = name.charAt(0);
            char last = name.charAt(name.length() - 1);
            if ((first == '`' && last == '`')
                    || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                return name.substring(1, name.length() - 1);
            }
        }
        return name;
    }

    private void updateActionStates() {
        boolean connected = driver.isConnected();
        boolean busy = activeTask != null && activeTask.isRunning();
        boolean txn = connected && driver.capabilities().supportsTransactions();
        boolean manual = txn && !driver.isAutoCommit();

        runButton.setDisable(!connected || busy);
        stopButton.setDisable(!busy || cancelling);
        explainButton.setDisable(!connected || busy);
        explainAnalyzeButton.setDisable(!connected || busy);
        connectButton.setDisable(busy);
        disconnectButton.setDisable(!connected || busy);
        refreshButton.setDisable(!connected || busy);

        autoCommitToggle.setDisable(!txn || busy);
        beginButton.setDisable(!txn || busy || manual);
        commitButton.setDisable(!manual || busy);
        rollbackButton.setDisable(!manual || busy);
    }

    private void setConnecting(boolean connecting) {
        toolbarActivity.setVisible(connecting);
        toolbarActivity.setManaged(connecting);
        connectButton.setDisable(connecting);
        updateActionStates();
    }

    private void setQueryRunning(boolean running) {
        toolbarActivity.setVisible(running);
        toolbarActivity.setManaged(running);
        updateActionStates();
    }

    private void bindCaret(SqlEditorPane editor) {
        statusBar.bindCaret(editor == null ? null : editor.caretLocation());
    }

    // ---------------------------------------------------------------- helpers

    private static void consumeAnd(KeyEvent event, Runnable action) {
        event.consume();
        action.run();
    }

    private static Button iconButton(Node icon, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Button labelledButton(Node icon, String text, String tooltip, Runnable action) {
        Button button = iconButton(icon, tooltip, action);
        button.setText(text);
        button.getStyleClass().remove("icon-button");
        button.getStyleClass().add("labelled-button");
        return button;
    }

    private static Separator separator() {
        Separator separator = new Separator(Orientation.VERTICAL);
        separator.getStyleClass().add("toolbar-separator");
        return separator;
    }

    private Window owner() {
        return root.getScene() == null ? null : root.getScene().getWindow();
    }

    private static String rootCauseMessage(Throwable error) {
        if (error == null) {
            return "Unknown error";
        }
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
