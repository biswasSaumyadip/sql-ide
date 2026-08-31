package com.lazaro.sqlide.ui;

import com.lazaro.sqlide.core.config.ConnectionProfile;
import com.lazaro.sqlide.core.config.ConnectionProfileManager;
import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.DriverRegistry;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.ScriptResult;
import com.lazaro.sqlide.core.doc.SqlDocResolver;
import com.lazaro.sqlide.core.explain.ExplainSql;
import com.lazaro.sqlide.core.export.ResultExporter;
import com.lazaro.sqlide.core.history.QueryHistoryStore;
import com.lazaro.sqlide.core.runconfig.RunConfiguration;
import com.lazaro.sqlide.core.runconfig.RunConfigurationStore;
import com.lazaro.sqlide.core.session.ConnectionSession;
import com.lazaro.sqlide.core.session.SessionManager;
import com.lazaro.sqlide.core.snippets.SnippetStore;
import com.lazaro.sqlide.ui.components.DynamicResultTable;
import com.lazaro.sqlide.ui.components.EditorTabPane;
import com.lazaro.sqlide.ui.components.QueryHistoryPane;
import com.lazaro.sqlide.ui.components.QueryOutcomePane;
import com.lazaro.sqlide.ui.components.RunConfigsPane;
import com.lazaro.sqlide.ui.components.SchemaTreeView;
import com.lazaro.sqlide.ui.components.SnippetsPane;
import com.lazaro.sqlide.ui.components.SqlEditorPane;
import com.lazaro.sqlide.ui.components.SqlTemplateGenerator;
import com.lazaro.sqlide.ui.components.StatusBar;
import com.lazaro.sqlide.core.transfer.TransferRequest;
import com.lazaro.sqlide.core.transfer.TransferResult;
import com.lazaro.sqlide.core.redis.RedisMutatingCommands;
import com.lazaro.sqlide.core.sql.PageSql;
import com.lazaro.sqlide.core.sql.ResultPager;
import com.lazaro.sqlide.core.sql.SchemaChangingSql;
import com.lazaro.sqlide.core.sql.SimpleSelectAnalyzer;
import com.lazaro.sqlide.core.sql.SqlParameterParser;
import com.lazaro.sqlide.ui.dialogs.CompareDataDialog;
import com.lazaro.sqlide.ui.dialogs.CompareStructureDialog;
import com.lazaro.sqlide.ui.dialogs.ConnectionDialog;
import com.lazaro.sqlide.ui.dialogs.SchemaDiagramDialog;
import com.lazaro.sqlide.ui.dialogs.SettingsDialog;
import com.lazaro.sqlide.ui.dialogs.ImportDataDialog;
import com.lazaro.sqlide.ui.dialogs.ModifyTableDialog;
import com.lazaro.sqlide.ui.dialogs.ParameterPromptDialog;
import com.lazaro.sqlide.ui.dialogs.TableDataTransferDialog;
import com.lazaro.sqlide.ui.dialogs.TransferProgressDialog;
import com.lazaro.sqlide.ui.autocomplete.SqlCompletionHygiene.Style;
import javafx.concurrent.Task;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Builds the main window and connects its actions to live {@link ConnectionSession}s
 * via {@link SessionManager}. No concrete driver class is named here.
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
    private final ConnectionProfileManager profileManager = new ConnectionProfileManager();
    private final QueryHistoryStore historyStore = new QueryHistoryStore();
    private final SnippetStore snippetStore = new SnippetStore();
    private final QueryHistoryPane historyPane = new QueryHistoryPane(historyStore);
    private final SnippetsPane snippetsPane = new SnippetsPane(snippetStore);
    private final RunConfigurationStore runConfigStore = new RunConfigurationStore();
    private final RunConfigsPane runConfigsPane;
    private final TabPane sidebarTabs = new TabPane();
    private final EditorTabPane editors = new EditorTabPane();
    private final QueryOutcomePane outcome = new QueryOutcomePane();
    private final StatusBar statusBar = new StatusBar();
    private final SessionManager sessions;

    private final WindowChrome windowChrome = new WindowChrome();
    private final SplitPane mainSplit = new SplitPane();
    private final SplitPane rightSplit = new SplitPane();
    private final BorderPane sidebar = new BorderPane();
    private final BorderPane root = new BorderPane();

    private Button runButton;
    private Button stopButton;
    private MenuButton explainButton;
    private ToggleButton autoCommitToggle;
    private Button beginButton;
    private Button commitButton;
    private Button rollbackButton;
    private Button connectButton;
    private Button disconnectButton;
    private Button refreshButton;
    private Button compareStructureButton;
    private Button compareDataButton;

    private Task<?> activeTask;
    private volatile boolean cancelling;
    private boolean sidebarCollapsed;
    private double expandedMainDivider = DEFAULT_MAIN_DIVIDER;
    /** Last successfully-submitted script for results-toolbar refresh / auto-rerun. */
    private List<String> lastRerunStatements = List.of();
    private String lastRerunHistorySql = "";
    private String lastRunSessionId;
    private final AtomicLong schemaIndexGeneration = new AtomicLong();
    private boolean connectingBusy;
    private boolean indexingBusy;
    private String treeBusyConnectionId;

    public MainController(DriverRegistry registry, WorkspaceState state) {
        this.registry = registry;
        this.state = state;
        this.sessions = new SessionManager(registry);
        this.runConfigsPane = new RunConfigsPane(runConfigStore, profileManager);
        schemaTree.setSessionManager(sessions);
        schemaTree.setProfileManager(profileManager);
        editors.setSchemaCache(() -> resolveSession(editors.activeEditor())
                .map(ConnectionSession::schemaCache)
                .orElseGet(SchemaCache::new));
        editors.setActiveCatalog(() -> resolveSession(editors.activeEditor())
                .map(s -> s.driver().activeCatalog().orElse(null))
                .orElse(null));
        editors.setDialect(() -> resolveSession(editors.activeEditor())
                .map(ConnectionSession::config)
                .map(ConnectionConfig::driver)
                .orElse(ConnectionConfig.Driver.MYSQL));
        editors.setCompletionStyle(this::completionStyle);
        editors.setEditorPreferences(
                state.editorFontFamily(), state.editorFontSize(), state.editorWordWrap());
        outcome.setConnectionType(() -> resolveSession(editors.activeEditor())
                .map(s -> s.config().connectionType())
                .orElse(ConnectionConfig.ConnectionType.MYSQL));
        sessions.addListener(this::onSessionsChanged);
    }

    // ---------------------------------------------------------------- view

    public Parent createView() {
        root.getStyleClass().add("app-root");
        root.setTop(buildToolBar());
        root.setCenter(buildMainSplit());
        root.setBottom(statusBar);

        schemaTree.setOnConnectRequested(this::openConnectionDialog);
        schemaTree.setOnConnectProfile(this::openConnectionDialog);
        schemaTree.setOnDeleteProfile(this::deleteSavedProfile);
        schemaTree.setOnActivate(this::insertNodeReference);
        schemaTree.setOnViewObject(this::openObjectViewer);
        schemaTree.setOnShowDiagram(this::openSchemaDiagram);
        schemaTree.setOnOpenData(this::openTableData);
        schemaTree.setOnImportData(this::openImportData);
        schemaTree.setOnTransferData(this::openTransferData);
        schemaTree.setOnModifyTable(this::openModifyTable);
        schemaTree.setOnUseDatabase(this::useDatabase);
        schemaTree.setOnInsertSql(sql -> editors.insertIntoActiveEditor(sql));
        schemaTree.setOnRunCommand(this::runGeneratedCommand);
        schemaTree.setOnOpenTemplate(template -> editors.openGeneratedSql(
                template, sessions.focused().map(ConnectionSession::id).orElse(null)));
        schemaTree.setOnNewQuery(this::openQueryTab);
        schemaTree.setOnDisconnect(this::disconnectSession);
        schemaTree.setOnSessionFocused(sessionId -> {
            sessions.focus(sessionId);
            refreshStatusFromFocus();
            updateActionStates();
        });
        schemaTree.setOnRefreshSchema(this::refreshSchema);
        historyPane.setOnRerun(entry -> rerunHistory(entry.sql()));
        historyPane.setOnInsert(entry -> editors.insertIntoActiveEditor(entry.sql()));
        snippetsPane.setOnInsert(snippet -> editors.insertIntoActiveEditor(snippet.sql()));
        snippetsPane.setSqlSupplier(() -> {
            SqlEditorPane editor = editors.activeEditor();
            return editor == null ? "" : editor.getEffectiveSql();
        });
        runConfigsPane.setSqlSupplier(() -> {
            SqlEditorPane editor = editors.activeEditor();
            return editor == null ? "" : editor.getEffectiveSql();
        });
        runConfigsPane.setProfileIdSupplier(() ->
                sessions.focused().flatMap(ConnectionSession::profileId).orElse(""));
        runConfigsPane.setOnOpen(this::openRunConfiguration);
        runConfigsPane.setOnRun(this::runConfiguration);
        outcome.setOnExportToFile(this::exportResultToFile);
        outcome.setOnExportJsonArray(this::exportResultAsJsonArray);
        outcome.setOnRefresh(this::rerunLastQuery);
        outcome.setOnActionsChanged(this::updateActionStates);
        outcome.setScriptRunner(statements -> {
            Optional<ConnectionSession> session = Optional.ofNullable(lastRunSessionId)
                    .flatMap(sessions::find)
                    .filter(ConnectionSession::isConnected)
                    .or(() -> sessions.focused().filter(ConnectionSession::isConnected));
            if (session.isEmpty()) {
                return CompletableFuture.completedFuture(ScriptResult.ofSingle(
                        QueryResult.ofError("Not connected", 0)));
            }
            return session.get().driver().executeScriptAsync(statements);
        });
        outcome.setBackgroundExecutor(backgroundTasks);
        outcome.setEditableResultResolver(this::resolveEditableResult);
        outcome.setPageSizeSupplier(() -> outcome.toolbar().maxRows());
        outcome.setPageLoader(this::loadResultPage);
        outcome.setRefreshEnabled(false);
        outcome.setMockApiOwnerSupplier(editors::activeQueryTab);
        outcome.setMockApiLatencyMs(state::mockApiLatencyMs);
        editors.setOnQueryTabClosed(outcome::stopMockApiOwnedBy);
        outcome.toolbar().setStopAutoRefreshOnError(state.stopAutoRefreshOnError());
        outcome.toolbar().setOnStopOnErrorChanged(state::saveStopAutoRefreshOnError);
        outcome.toolbar().setMaxRows(state.maxRows());
        outcome.toolbar().setOnMaxRowsChanged(rows -> {
            state.saveMaxRows(rows);
            sessions.connectedSessions().forEach(s -> s.driver().setMaxRowsPerQuery(rows));
        });
        editors.activeEditorProperty().addListener((observable, previous, current) -> {
            bindCaret(current);
            if (current != null) {
                current.setOnSelectInDatabase(this::selectInDatabase);
                current.setOnShowTablePreview(this::openTablePreviewFromDoc);
            }
            onSessionsChanged();
            updateActionStates();
        });
        bindCaret(editors.activeEditor());
        if (editors.activeEditor() != null) {
            editors.activeEditor().setOnSelectInDatabase(this::selectInDatabase);
            editors.activeEditor().setOnShowTablePreview(this::openTablePreviewFromDoc);
        }

        onSessionsChanged();
        updateActionStates();

        HBox titleBar = windowChrome.createTitleBar();
        VBox.setVgrow(titleBar, Priority.NEVER);
        VBox.setVgrow(root, Priority.ALWAYS);

        VBox shell = new VBox(titleBar, root);
        shell.getStyleClass().add("app-shell");
        return shell;
    }

    public void installWindowChrome(Stage stage) {
        windowChrome.attach(stage);
    }

    public void maximizeRestoredWindow() {
        if (!windowChrome.isMaximized()) {
            windowChrome.toggleMaximize();
        }
    }

    private ToolBar buildToolBar() {
        Button sidebarToggle = iconButton(Icons.sidebar(), "Toggle Schema Explorer (Ctrl+1)", this::toggleSidebar);
        Button newQuery = iconButton(Icons.newQuery(), "New Query (Ctrl+T)",
                () -> openQueryTab(sessions.focused().map(ConnectionSession::id).orElse(null)));
        Button save = iconButton(Icons.save(), "Save Query (Ctrl+S)", () -> editors.saveActiveTab(owner()));

        connectButton = labelledButton(Icons.connect(), "Connect", "Connect to a database (Ctrl+K)",
                this::openConnectionDialog);
        disconnectButton = iconButton(Icons.disconnect(), "Disconnect", this::disconnect);
        refreshButton = iconButton(Icons.refresh(), "Refresh Schema (Ctrl+R)", this::refreshSchema);
        compareStructureButton = new Button("Struct");
        compareStructureButton.getStyleClass().add("labelled-button");
        compareStructureButton.setTooltip(new Tooltip("Compare table structure / generate ALTER"));
        compareStructureButton.setOnAction(event -> openCompareStructure());
        compareDataButton = new Button("Data");
        compareDataButton.getStyleClass().add("labelled-button");
        compareDataButton.setTooltip(new Tooltip("Compare pinned result sets"));
        compareDataButton.setOnAction(event -> openCompareData());

        runButton = labelledButton(Icons.run(), "Run", "Execute (Ctrl+Enter)", this::runQuery);
        runButton.getStyleClass().add("run-button");
        stopButton = labelledButton(Icons.stop(), "Stop", "Cancel running query (Ctrl+Break)", this::cancelQuery);
        stopButton.getStyleClass().add("stop-button");
        explainButton = explainMenu();

        autoCommitToggle = new ToggleButton("Auto-commit");
        autoCommitToggle.getStyleClass().add("toolbar-toggle");
        autoCommitToggle.setSelected(state.autoCommit());
        autoCommitToggle.setTooltip(new Tooltip("Toggle auto-commit (off = manual transactions)"));
        autoCommitToggle.setOnAction(event -> toggleAutoCommit());
        beginButton = labelledButton(Icons.begin(), "Begin", "Begin manual transaction", this::beginTransaction);
        commitButton = labelledButton(Icons.commit(), "Commit", "Commit current transaction", this::commitTransaction);
        rollbackButton = labelledButton(Icons.rollback(), "Rollback", "Rollback current transaction",
                this::rollbackTransaction);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button settingsButton = iconButton(Icons.settings(), "Settings (Ctrl+,)", this::openSettings);

        ToolBar toolBar = new ToolBar(
                sidebarToggle,
                separator(),
                newQuery, save,
                separator(),
                connectButton, disconnectButton, refreshButton,
                compareStructureButton, compareDataButton,
                separator(),
                runButton, stopButton, explainButton,
                separator(),
                autoCommitToggle, beginButton, commitButton, rollbackButton,
                spacer,
                settingsButton);
        toolBar.getStyleClass().add("app-toolbar");
        // High-density IDE chrome: tight gaps between controls.
        toolBar.setStyle("-fx-spacing: 2;");
        return toolBar;
    }

    private Node buildMainSplit() {
        VBox rail = new VBox(railToggle());
        rail.getStyleClass().add("sidebar-rail");
        rail.setAlignment(Pos.TOP_CENTER);
        rail.setMinWidth(RAIL_WIDTH);
        rail.setPrefWidth(RAIL_WIDTH);
        rail.setMaxWidth(RAIL_WIDTH);

        Tab schemaTab = new Tab("Database", schemaTree);
        schemaTab.setClosable(false);
        Tab historyTab = new Tab("History", historyPane);
        historyTab.setClosable(false);
        Tab snippetsTab = new Tab("Snippets", snippetsPane);
        snippetsTab.setClosable(false);
        Tab runConfigsTab = new Tab("Run Configs", runConfigsPane);
        runConfigsTab.setClosable(false);
        sidebarTabs.getTabs().setAll(schemaTab, historyTab, snippetsTab, runConfigsTab);
        sidebarTabs.getStyleClass().add("sidebar-tabs");
        sidebarTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        sidebar.getStyleClass().add("sidebar");
        sidebar.setLeft(rail);
        sidebar.setCenter(sidebarTabs);
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
        KeyCombination copyTsv = new KeyCodeCombination(
                KeyCode.C, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCombination exportFile = new KeyCodeCombination(
                KeyCode.X, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        KeyCombination find = new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN);
        KeyCombination replace = new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN);
        KeyCombination findNext = new KeyCodeCombination(KeyCode.F3);
        KeyCombination findPrevious = new KeyCodeCombination(KeyCode.F3, KeyCombination.SHIFT_DOWN);
        KeyCombination selectInDatabase = new KeyCodeCombination(KeyCode.F1, KeyCombination.ALT_DOWN);
        KeyCombination formatCode = new KeyCodeCombination(
                KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN);
        KeyCombination settings = new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN);
        KeyCombination modifyTable = new KeyCodeCombination(KeyCode.F6, KeyCombination.CONTROL_DOWN);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (run.match(event) || runAlt.match(event)) {
                consumeAnd(event, this::runQuery);
            } else if (stop.match(event)) {
                consumeAnd(event, this::cancelQuery);
            } else if (explainAnalyze.match(event)) {
                consumeAnd(event, () -> runExplain(true));
            } else if (explain.match(event)) {
                consumeAnd(event, () -> runExplain(false));
            } else if (copyTsv.match(event)) {
                consumeAnd(event, () -> copyActiveResult(ResultExporter.Format.TSV));
            } else if (exportFile.match(event)) {
                consumeAnd(event, () -> exportActiveResult(false));
            } else if (find.match(event)) {
                consumeAnd(event, this::findInContext);
            } else if (replace.match(event)) {
                consumeAnd(event, this::replaceInEditor);
            } else if (findPrevious.match(event)) {
                consumeAnd(event, () -> findStep(false));
            } else if (findNext.match(event)) {
                consumeAnd(event, () -> findStep(true));
            } else if (selectInDatabase.match(event)) {
                consumeAnd(event, this::selectInDatabase);
            } else if (formatCode.match(event)) {
                consumeAnd(event, this::formatActiveEditor);
            } else if (settings.match(event)) {
                consumeAnd(event, this::openSettings);
            } else if (toggleSidebar.match(event)) {
                consumeAnd(event, this::toggleSidebar);
            } else if (newTab.match(event)) {
                consumeAnd(event, () -> openQueryTab(
                        sessions.focused().map(ConnectionSession::id).orElse(null)));
            } else if (closeTab.match(event)) {
                consumeAnd(event, editors::closeActiveTab);
            } else if (save.match(event)) {
                consumeAnd(event, () -> editors.saveActiveTab(owner()));
            } else if (connect.match(event)) {
                consumeAnd(event, this::openConnectionDialog);
            } else if (refresh.match(event)) {
                consumeAnd(event, this::refreshSchema);
            } else if (modifyTable.match(event)) {
                consumeAnd(event, () -> schemaTree.selectedTable().ifPresent(this::openModifyTable));
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
        return editors.confirmCloseAll() && outcome.confirmCloseAll();
    }

    public void saveState(Stage stage) {
        state.saveWindow(stage);
        state.saveLayout(
                sidebarCollapsed ? expandedMainDivider : mainSplit.getDividerPositions()[0],
                rightSplit.getDividerPositions()[0],
                sidebarCollapsed);
    }

    public void shutdown() {
        outcome.dispose();
        editors.dispose();
        sessions.close();
        backgroundTasks.shutdownNow();
    }

    // ---------------------------------------------------------------- actions

    private void toggleSidebar() {
        if (sidebarCollapsed) {
            sidebarTabs.setVisible(true);
            sidebarTabs.setManaged(true);
            mainSplit.setDividerPositions(expandedMainDivider);
            sidebarCollapsed = false;
        } else {
            expandedMainDivider = mainSplit.getDividerPositions()[0];
            sidebarTabs.setVisible(false);
            sidebarTabs.setManaged(false);
            double width = Math.max(mainSplit.getWidth(), 1);
            mainSplit.setDividerPositions(RAIL_WIDTH / width);
            sidebarCollapsed = true;
        }
    }

    private void openConnectionDialog() {
        openConnectionDialog(null);
    }

    private void openSettings() {
        SettingsDialog dialog = new SettingsDialog(state);
        dialog.initOwner(owner());
        Optional<Boolean> saved = dialog.showAndWait();
        if (saved.orElse(false)) {
            editors.setCompletionStyle(this::completionStyle);
            editors.refreshAutocompleteEngines();
            editors.setEditorPreferences(
                    state.editorFontFamily(), state.editorFontSize(), state.editorWordWrap());
            outcome.toolbar().setMaxRows(state.maxRows());
            sessions.connectedSessions().forEach(s -> s.driver().setMaxRowsPerQuery(state.maxRows()));
            autoCommitToggle.setSelected(state.autoCommit());
        }
    }

    private Style completionStyle() {
        return new Style(
                state.keywordCasing(),
                state.autoQuoteReserved(),
                state.preserveDbCasing(),
                state.autoGenerateTableAliases(),
                state.suggestJoinColumns());
    }

    private void openConnectionDialog(ConnectionProfile profile) {
        DataSourceDriver probe = registry.create(DriverRegistry.DEFAULT_DRIVER_ID);
        ConnectionDialog dialog = new ConnectionDialog(
                state.lastConnection(), probe, registry, profileManager, profile);
        dialog.initOwner(owner());
        dialog.showAndWait().ifPresent(config -> {
            String profileId = dialog.selectedProfileId().orElse(
                    profile == null ? null : profile.id());
            String displayName = dialog.selectedProfileId()
                    .flatMap(id -> profileManager.loadProfiles().stream().filter(p -> p.id().equals(id)).findFirst())
                    .map(ConnectionProfile::displayName)
                    .orElse(config.displayLabel());
            connect(profileId, displayName, config);
            schemaTree.refreshSavedConnections();
        });
        schemaTree.refreshSavedConnections();
        probe.close();
    }

    private void deleteSavedProfile(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        profileManager.deleteProfile(profile.id());
        schemaTree.refreshSavedConnections();
    }

    private void connect(String profileId, String displayName, ConnectionConfig config) {
        if (activeTask != null && activeTask.isRunning()) {
            return;
        }
        ConnectionSession session = sessions.open(profileId, displayName, config, state.maxRows());
        setConnecting(true, busyConnectionId(profileId, session));
        statusBar.setBusy("Connecting to " + config.displayLabel() + "\u2026");
        DataSourceDriver active = session.driver();
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
            connectingBusy = false;
            connectButton.setDisable(false);
            String database = config.database().isBlank()
                    ? active.activeCatalog().orElse(null)
                    : config.database();
            statusBar.setConnected(config.endpointLabel(), database);
            state.saveLastConnection(config);
            sessions.focus(session.id());
            schemaTree.reload();
            refreshSchemaCache(session);
            applyPreferredAutoCommit(session);
            onSessionsChanged();
            SqlEditorPane ed = editors.activeEditor();
            if (ed != null && (ed.getBoundSessionId() == null || ed.getBoundSessionId().isBlank())) {
                ed.setBoundSessionId(session.id());
            }
            updateActionStates();
        });
        task.setOnFailed(event -> {
            activeTask = null;
            setConnecting(false);
            statusBar.setConnectionError("Connection failed: " + rootCauseMessage(task.getException()));
            sessions.closeSession(session.id());
            schemaTree.reload();
            onSessionsChanged();
            updateActionStates();
        });
        backgroundTasks.execute(task);
    }

    private void disconnect() {
        sessions.focused().map(ConnectionSession::id).ifPresent(this::disconnectSession);
    }

    private void disconnectSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        cancelling = false;
        sessions.closeSession(sessionId);
        outcome.toolbar().stopAutoRefresh();
        if (sessions.connectedSessions().isEmpty()) {
            lastRerunStatements = List.of();
            lastRerunHistorySql = "";
            lastRunSessionId = null;
            outcome.clear();
            outcome.setRefreshEnabled(false);
            statusBar.setDisconnected();
            autoCommitToggle.setSelected(state.autoCommit());
            clearSchemaIndexing();
        } else {
            refreshStatusFromFocus();
        }
        connectingBusy = false;
        indexingBusy = false;
        schemaTree.setBusyConnection(null);
        schemaTree.reload();
        onSessionsChanged();
        updateActionStates();
    }

    private Optional<ConnectionSession> resolveSession(SqlEditorPane editor) {
        if (editor != null) {
            String id = editor.getBoundSessionId();
            if (id != null && !id.isBlank()) {
                Optional<ConnectionSession> bound = sessions.find(id).filter(ConnectionSession::isConnected);
                if (bound.isPresent()) {
                    return bound;
                }
            }
        }
        return sessions.focused().filter(ConnectionSession::isConnected);
    }

    private void openQueryTab(String sessionId) {
        editors.newTab(sessionId, sessionDriver(sessionId));
    }

    private ConnectionConfig.Driver sessionDriver(String sessionId) {
        return Optional.ofNullable(sessionId).flatMap(sessions::find)
                .or(sessions::focused)
                .map(s -> s.config().driver())
                .orElse(ConnectionConfig.Driver.MYSQL);
    }

    private static boolean isRedis(ConnectionSession session) {
        return session != null && session.config().connectionType().isRedis();
    }

    private void onSessionsChanged() {
        List<SqlEditorPane.SessionChoice> choices = new ArrayList<>();
        for (ConnectionSession s : sessions.connectedSessions()) {
            choices.add(new SqlEditorPane.SessionChoice(s.id(), s.comboLabel()));
        }
        String fallback = sessions.focused().map(ConnectionSession::id).orElse(null);
        editors.refreshSessionChoices(choices, fallback);
        editors.refreshAutocompleteEngines();
        runConfigsPane.refresh();
    }

    private void refreshStatusFromFocus() {
        sessions.focused().filter(ConnectionSession::isConnected).ifPresentOrElse(s -> {
            ConnectionConfig c = s.config();
            statusBar.setConnected(c.endpointLabel(), s.driver().activeCatalog().orElse(c.database()));
            syncTransactionStatus(s);
        }, statusBar::setDisconnected);
    }

    /** Reloads the tree and the client-side autocomplete/object-viewer cache. */
    private void refreshSchema() {
        resolveSession(editors.activeEditor()).ifPresent(s -> {
            schemaTree.reload();
            refreshSchemaCache(s);
        });
    }

    /**
     * Pulls the active catalog first so autocomplete works before other databases
     * are indexed. Failures leave the previous snapshot intact so typing is not
     * disrupted. Status bar + Database pane show progress while that runs.
     */
    private void refreshSchemaCache(ConnectionSession session) {
        if (session == null || !session.isConnected()) {
            return;
        }
        DataSourceDriver active = session.driver();
        SchemaCache cache = session.schemaCache();
        final long generation = schemaIndexGeneration.incrementAndGet();
        String catalog = active.activeCatalog().orElseGet(() ->
                session.config().database().isBlank() ? null : session.config().database());
        showSchemaIndexing(catalog == null || catalog.isBlank()
                ? "Indexing schema\u2026"
                : "Indexing " + catalog + "\u2026");
        active.getSchemaOutline().whenComplete((outline, outlineError) -> {
            boolean pendingOthers = outlineHasPendingCatalogs(outline);
            javafx.application.Platform.runLater(() -> {
                if (generation != schemaIndexGeneration.get() || !session.isConnected()) {
                    return;
                }
                if (outlineError != null) {
                    clearSchemaIndexing(generation);
                    statusBar.setIndexingError("Indexing failed: " + rootCauseMessage(outlineError));
                    return;
                }
                if (outline != null) {
                    cache.replace(outline);
                    editors.refreshAutocompleteEngines();
                }
            });
            if (outlineError != null || active.schemaOutlineIsAuthoritative()) {
                if (outlineError == null) {
                    javafx.application.Platform.runLater(() -> clearSchemaIndexing(generation));
                }
                return;
            }
            active.enrichSchema(outline).whenComplete((nodes, error) -> javafx.application.Platform.runLater(() -> {
                if (generation != schemaIndexGeneration.get() || !session.isConnected()) {
                    return;
                }
                if (error == null && nodes != null) {
                    cache.replace(nodes);
                    editors.refreshAutocompleteEngines();
                }
                if (pendingOthers) {
                    showSchemaIndexing("Indexing other databases\u2026");
                } else {
                    clearSchemaIndexing(generation);
                    if (error != null) {
                        statusBar.setIndexingError("Indexing failed: " + rootCauseMessage(error));
                    }
                }
                active.getSecondarySchema().whenComplete((secondary, secondaryError) ->
                        javafx.application.Platform.runLater(() -> {
                            if (generation != schemaIndexGeneration.get() || !session.isConnected()) {
                                return;
                            }
                            if (secondaryError == null && secondary != null && !secondary.isEmpty()) {
                                cache.upsertCatalogs(secondary);
                                editors.refreshAutocompleteEngines();
                            }
                            clearSchemaIndexing(generation);
                            if (secondaryError != null) {
                                statusBar.setIndexingError(
                                        "Indexing other databases failed: " + rootCauseMessage(secondaryError));
                            }
                        }));
            }));
        });
    }

    private static boolean outlineHasPendingCatalogs(List<SchemaNode> outline) {
        if (outline == null || outline.size() < 2) {
            return false;
        }
        for (int i = 1; i < outline.size(); i++) {
            if (outline.get(i).children().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void showSchemaIndexing(String message) {
        statusBar.setIndexing(message);
        indexingBusy = true;
        syncTreeBusy(focusedBusyConnectionId());
    }

    private void clearSchemaIndexing() {
        schemaIndexGeneration.incrementAndGet();
        statusBar.clearIndexing();
        indexingBusy = false;
        syncTreeBusy(null);
    }

    private void clearSchemaIndexing(long generation) {
        if (generation != schemaIndexGeneration.get()) {
            return;
        }
        statusBar.clearIndexing();
        indexingBusy = false;
        syncTreeBusy(null);
    }

    private void openCompareStructure() {
        Window owner = owner();
        CompareStructureDialog.showAndGetAlter(owner, sessions.connectedSessions())
                .ifPresent(sql -> {
                    openQueryTab(sessions.focused().map(ConnectionSession::id).orElse(null));
                    SqlEditorPane ed = editors.activeEditor();
                    if (ed != null) {
                        ed.setSql(sql);
                    }
                });
    }

    private void openCompareData() {
        List<CompareDataDialog.NamedResult> pinned = outcome.pinnedNamedResults();
        if (pinned.size() < 2) {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("Compare Data");
            alert.setHeaderText("Pin two result tabs first");
            alert.setContentText(
                    "Pin at least two result tabs that contain query results, then open Compare Data again.");
            if (owner() != null) {
                alert.initOwner(owner());
            }
            alert.showAndWait();
            return;
        }
        CompareDataDialog dialog = new CompareDataDialog(owner(), pinned);
        dialog.showAndWait();
    }

    private void openRunConfiguration(RunConfiguration config) {
        if (config == null) {
            return;
        }
        Optional<ConnectionSession> session = sessions.findByProfileId(config.profileId())
                .filter(ConnectionSession::isConnected);
        String sessionId = session.map(ConnectionSession::id).orElse(null);
        openQueryTab(sessionId);
        SqlEditorPane ed = editors.activeEditor();
        if (ed != null) {
            ed.setSql(config.sql());
            ed.setRunConfigParams(config.defaultParams());
            if (sessionId != null) {
                ed.setBoundSessionId(sessionId);
            }
        }
    }

    private void runConfiguration(RunConfiguration config) {
        if (config == null) {
            return;
        }
        Optional<ConnectionSession> session = sessions.findByProfileId(config.profileId())
                .filter(ConnectionSession::isConnected);
        if (session.isEmpty()) {
            profileManager.loadProfiles().stream()
                    .filter(p -> p.id().equals(config.profileId()))
                    .findFirst()
                    .ifPresentOrElse(this::openConnectionDialog, this::openConnectionDialog);
            return;
        }
        ConnectionSession s = session.get();
        sessions.focus(s.id());
        String sql = config.sql();
        if (!config.defaultParams().isEmpty() && !SqlParameterParser.find(sql).isEmpty()) {
            var substituted = ParameterPromptDialog.promptAndSubstitute(owner(), sql, config.defaultParams());
            if (substituted.isEmpty()) {
                return;
            }
            sql = substituted.get();
        }
        openQueryTab(s.id());
        SqlEditorPane ed = editors.activeEditor();
        if (ed != null) {
            ed.setSql(sql);
            ed.setRunConfigParams(config.defaultParams());
            ed.setBoundSessionId(s.id());
        }
        executeSql(null, false);
    }

    private void openObjectViewer(SchemaNode node) {
        Optional<ConnectionSession> session = resolveSession(editors.activeEditor())
                .or(() -> sessions.focused().filter(ConnectionSession::isConnected));
        SchemaCache cache = session.map(ConnectionSession::schemaCache).orElseGet(SchemaCache::new);
        SchemaNode detailed = detailedFromCache(cache, node);
        if (needsObjectDetails(detailed) && session.isPresent() && session.get().isConnected()) {
            session.get().driver().getObjectDetails(detailed).whenComplete((fresh, error) ->
                    javafx.application.Platform.runLater(() ->
                            editors.openObjectViewer(fresh != null ? fresh : detailed)));
            return;
        }
        editors.openObjectViewer(detailed);
    }

    private static SchemaNode detailedFromCache(SchemaCache cache, SchemaNode node) {
        if (node.type() == SchemaNode.NodeType.PROCEDURE) {
            return cache.procedures(null).stream()
                    .filter(procedure -> procedure.name().equalsIgnoreCase(node.name()))
                    .findFirst()
                    .orElse(node);
        }
        return cache.findTable(node.name()).orElse(node);
    }

    private static boolean needsObjectDetails(SchemaNode node) {
        return switch (node.type()) {
            case TABLE, VIEW -> node.children().isEmpty();
            case PROCEDURE -> {
                String ddl = node.metadata(SchemaNode.META_DDL);
                yield ddl == null || ddl.isBlank();
            }
            default -> false;
        };
    }

    private void openSchemaDiagram(SchemaNode node) {
        Optional<ConnectionSession> sessionOpt = sessions.focused()
                .filter(ConnectionSession::isConnected)
                .or(() -> resolveSession(editors.activeEditor()).filter(ConnectionSession::isConnected));
        if (node == null || sessionOpt.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        SchemaCache cache = session.schemaCache();
        if (!cache.isReady()) {
            refreshSchemaCache(session);
        }

        String catalog;
        String focusTable = null;
        SchemaNode.NodeType type = node.type();
        if (type == SchemaNode.NodeType.DATABASE || type == SchemaNode.NodeType.SCHEMA) {
            catalog = node.name();
        } else if (type == SchemaNode.NodeType.TABLE || type == SchemaNode.NodeType.VIEW) {
            catalog = node.metadata(SchemaNode.META_CATALOG);
            if (catalog == null || catalog.isBlank()) {
                catalog = session.driver().activeCatalog().orElse(null);
            }
            focusTable = node.name();
        } else {
            catalog = session.driver().activeCatalog().orElse(null);
        }

        String layoutKey = SchemaDiagramDialog.layoutKey(
                session.config().host() + "_" + session.config().port(),
                catalog,
                focusTable);
        SchemaDiagramDialog dialog = new SchemaDiagramDialog(
                owner(),
                cache,
                catalog,
                focusTable,
                layoutKey,
                state,
                this::openObjectViewer);
        dialog.showAndWait();
    }

    private void openModifyTable(SchemaNode node) {
        if (node == null || node.type() != SchemaNode.NodeType.TABLE) {
            return;
        }
        Optional<ConnectionSession> sessionOpt = sessions.focused()
                .filter(ConnectionSession::isConnected)
                .or(() -> resolveSession(editors.activeEditor()).filter(ConnectionSession::isConnected));
        if (sessionOpt.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        SchemaCache cache = session.schemaCache();
        SchemaNode detailed = cache.findTable(node.name(), node.metadata(SchemaNode.META_CATALOG)).orElse(node);
        String catalog = detailed.metadata(SchemaNode.META_CATALOG);
        Map<String, List<String>> columnsByTable = ModifyTableDialog.columnsByTable(cache, catalog);
        ModifyTableDialog dialog = new ModifyTableDialog(
                owner(),
                detailed,
                session.config().driver(),
                columnsByTable);
        dialog.showAndWait().ifPresent(sql -> editors.openGeneratedSql(
                new SqlTemplateGenerator.Template(sql, "", "query-modify-table.sql"),
                session.id()));
    }

    private void openTablePreviewFromDoc(SqlDocResolver.Doc doc) {
        if (doc == null || doc.tableNode() == null) {
            return;
        }
        openTableData(doc.tableNode());
    }

    private void openTableData(SchemaNode node) {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (node == null || sessionOpt.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
        SchemaCache cache = session.schemaCache();
        useDatabase(node);
        SchemaNode detailed = cache.findTable(
                node.name(),
                node.metadata(SchemaNode.META_CATALOG)).orElse(node);
        String catalog = detailed.metadata(SchemaNode.META_CATALOG);
        if (catalog == null || catalog.isBlank()) {
            catalog = active.activeCatalog().orElse(null);
        }
        String qualified = catalog == null || catalog.isBlank()
                ? detailed.name()
                : catalog + "." + detailed.name();
        List<String> primaryKeys = primaryKeyColumns(detailed);
        outcome.openTableData(
                detailed,
                qualified,
                primaryKeys,
                statements -> active.executeScriptAsync(statements),
                backgroundTasks);
    }

    private void openImportData(SchemaNode node) {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (node == null || sessionOpt.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        SchemaCache cache = session.schemaCache();
        useDatabase(node);
        SchemaNode detailed = cache.findTable(
                node.name(),
                node.metadata(SchemaNode.META_CATALOG)).orElse(node);
        List<String> columns = ImportDataDialog.columnsOf(detailed);
        if (columns.isEmpty()) {
            columns = ImportDataDialog.columnsOf(
                    cache.findTable(detailed.name(), detailed.metadata(SchemaNode.META_CATALOG))
                            .orElse(detailed));
        }
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        ImportDataDialog dialog = new ImportDataDialog(owner, detailed, columns);
        dialog.showAndWait();
        dialog.importPlan().ifPresent(plan -> {
            outcome.output().appendSeparator();
            outcome.output().appendInfo("Import plan for " + plan.targetTableQualified());
            outcome.output().appendSql("FILE " + plan.sourceFile());
            outcome.output().appendOk(plan.columnMapping().size() + " column(s) mapped"
                    + " · batch " + plan.batchSize()
                    + (plan.truncateBeforeImport() ? " · truncate" : "")
                    + " · " + plan.errorHandling());
            outcome.output().appendInfo("Execution of the import plan will run in a follow-up step.");
            statusBar.setScriptSummary("Import plan ready for " + plan.targetTableQualified(), false);
        });
    }

    private void openTransferData(SchemaNode node) {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (node == null || sessionOpt.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
        SchemaCache cache = session.schemaCache();
        ConnectionConfig sourceConfig = active.currentConfig().orElse(null);
        if (sourceConfig == null) {
            return;
        }
        useDatabase(node);
        SchemaNode detailed = cache.findTable(
                node.name(),
                node.metadata(SchemaNode.META_CATALOG)).orElse(node);
        List<String> columns = ImportDataDialog.columnsOf(detailed);
        if (columns.isEmpty()) {
            columns = ImportDataDialog.columnsOf(
                    cache.findTable(detailed.name(), detailed.metadata(SchemaNode.META_CATALOG))
                            .orElse(detailed));
        }
        String catalog = detailed.metadata(SchemaNode.META_CATALOG);
        if (catalog == null || catalog.isBlank()) {
            catalog = active.activeCatalog().orElse(null);
        }
        if (catalog != null && !catalog.isBlank()
                && (detailed.metadata(SchemaNode.META_CATALOG) == null
                || detailed.metadata(SchemaNode.META_CATALOG).isBlank())) {
            java.util.Map<String, String> meta = new java.util.LinkedHashMap<>(detailed.metadata());
            meta.put(SchemaNode.META_CATALOG, catalog);
            detailed = new SchemaNode(detailed.name(), detailed.type(), detailed.children(), meta);
        }
        final SchemaNode tableNode = detailed;
        final List<String> columnNames = columns;
        final String countCatalog = catalog;
        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        Task<Long> countTask = TableDataTransferDialog.countRowsTask(sourceConfig, countCatalog, tableNode.name());
        countTask.setOnSucceeded(event -> {
            long rowCount = countTask.getValue() == null ? 0L : countTask.getValue();
            TableDataTransferDialog dialog = new TableDataTransferDialog(
                    owner, sourceConfig, tableNode, columnNames, rowCount, profileManager);
            dialog.showAndWait().ifPresent(request -> runTransfer(request, owner));
        });
        countTask.setOnFailed(event -> {
            TableDataTransferDialog dialog = new TableDataTransferDialog(
                    owner, sourceConfig, tableNode, columnNames, 0, profileManager);
            dialog.showAndWait().ifPresent(request -> runTransfer(request, owner));
        });
        backgroundTasks.execute(countTask);
    }

    private void runTransfer(TransferRequest request, Window owner) {
        TransferProgressDialog progress = new TransferProgressDialog(owner, request, line -> {
        });
        backgroundTasks.execute(progress.task());
        progress.showAndWait();
        TransferResult result = progress.getResult();
        if (result == null) {
            return;
        }
        outcome.output().appendSeparator();
        outcome.output().appendInfo("Table transfer " + request.sourceTable() + " \u2192 " + request.targetTable());
        outcome.output().appendInfo("Strategy: " + result.strategy());
        if (result.cancelled()) {
            outcome.output().appendError(result.message());
        } else if (!result.errorLog().isEmpty() && result.rowsTransferred() == 0) {
            outcome.output().appendError(result.message());
            result.errorLog().forEach(line -> outcome.output().appendError(line));
        } else {
            outcome.output().appendOk(result.message() + " \u00B7 " + result.elapsedMs() + " ms");
            result.errorLog().forEach(line -> outcome.output().appendInfo(line));
        }
        statusBar.setScriptSummary(result.message(), result.cancelled() || result.rowsTransferred() == 0);

        Alert alert = new Alert(result.cancelled() ? Alert.AlertType.WARNING : Alert.AlertType.INFORMATION);
        alert.setTitle("Transfer complete");
        alert.setHeaderText(result.cancelled() ? "Transfer cancelled" : "Transfer finished");
        alert.setContentText("%s\nRows: %,d transferred, %,d skipped\nTime: %d ms\nStrategy: %s"
                .formatted(
                        result.message(),
                        result.rowsTransferred(),
                        result.rowsSkipped(),
                        result.elapsedMs(),
                        result.strategy()));
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    private static List<String> primaryKeyColumns(SchemaNode table) {
        List<String> keys = new ArrayList<>();
        if (table == null) {
            return keys;
        }
        collectPrimaryKeys(table, keys);
        return keys;
    }

    private static void collectPrimaryKeys(SchemaNode node, List<String> keys) {
        if (node.type() == SchemaNode.NodeType.COLUMN && node.metadataFlag(SchemaNode.META_PRIMARY_KEY)) {
            keys.add(node.name());
            return;
        }
        for (SchemaNode child : node.children()) {
            if (child.type() == SchemaNode.NodeType.FOLDER
                    && SchemaNode.FOLDER_COLUMNS.equalsIgnoreCase(child.folderKind())) {
                collectPrimaryKeys(child, keys);
            } else if (child.type() == SchemaNode.NodeType.COLUMN) {
                collectPrimaryKeys(child, keys);
            }
        }
    }

    private void findInContext() {
        if (isFocusInside(outcome)) {
            outcome.toolbar().focusFind();
            return;
        }
        SqlEditorPane editor = editors.activeEditor();
        if (editor != null) {
            editor.showFind(false);
        } else {
            outcome.toolbar().focusFind();
        }
    }

    private void replaceInEditor() {
        SqlEditorPane editor = editors.activeEditor();
        if (editor != null) {
            editor.showFind(true);
        }
    }

    private void formatActiveEditor() {
        SqlEditorPane editor = editors.activeEditor();
        if (editor != null) {
            editor.formatCode();
        }
    }

    private void findStep(boolean forward) {
        if (isFocusInside(outcome)) {
            // Results find is a filter, not a stepper — open/focus it instead.
            outcome.toolbar().focusFind();
            return;
        }
        SqlEditorPane editor = editors.activeEditor();
        if (editor != null) {
            editor.findNext(forward);
        }
    }

    private void selectInDatabase() {
        SqlEditorPane editor = editors.activeEditor();
        Optional<ConnectionSession> sessionOpt = resolveSession(editor);
        if (editor == null || sessionOpt.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
        SchemaCache schemaCache = session.schemaCache();
        var resolved = SqlIdentifierAtCaret.resolve(
                editor.getSql(),
                editor.getCodeArea().getCaretPosition(),
                editor.getCodeArea().getSelectedText());
        if (resolved.isEmpty()) {
            statusBar.setBusy("No identifier under caret");
            return;
        }
        SqlIdentifierAtCaret.Ref ref = resolved.get();
        String activeCatalog = active.activeCatalog().orElse(null);
        String catalog;
        String table;
        String column;

        if (ref.hasColumn()) {
            catalog = ref.catalogOrSchema();
            table = ref.tableOrColumn();
            column = ref.column();
        } else if (ref.catalogOrSchema() != null) {
            var asCatalogTable = schemaCache.resolveTable(ref.catalogOrSchema(), ref.tableOrColumn(), activeCatalog);
            if (asCatalogTable.isPresent()) {
                catalog = ref.catalogOrSchema();
                table = ref.tableOrColumn();
                column = null;
            } else {
                var asTable = schemaCache.findTable(ref.catalogOrSchema(), activeCatalog);
                catalog = asTable.map(node -> node.metadata(SchemaNode.META_CATALOG)).orElse(activeCatalog);
                table = ref.catalogOrSchema();
                column = ref.tableOrColumn();
            }
        } else {
            catalog = activeCatalog;
            table = ref.tableOrColumn();
            column = null;
        }

        ensureSidebarVisible();
        if (!schemaTree.revealObject(catalog, table, column)) {
            statusBar.setBusy("Could not reveal " + table + " in Database tree");
        }
    }

    private void ensureSidebarVisible() {
        if (sidebarCollapsed) {
            toggleSidebar();
        }
        sidebarTabs.getSelectionModel().select(0);
    }

    private static boolean isFocusInside(Node ancestor) {
        if (ancestor == null || ancestor.getScene() == null) {
            return false;
        }
        Node focus = ancestor.getScene().getFocusOwner();
        while (focus != null) {
            if (focus == ancestor) {
                return true;
            }
            focus = focus.getParent();
        }
        return false;
    }

    /**
     * Switches the session to the catalog implied by {@code node}: the node itself
     * when it is a database/schema, otherwise its {@link SchemaNode#META_CATALOG}.
     */
    private void useDatabase(SchemaNode node) {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (node == null || sessionOpt.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
        String catalog = switch (node.type()) {
            case DATABASE, SCHEMA -> node.name();
            case TABLE, VIEW, COLUMN, FOLDER, KEY, INDEX, PROCEDURE, REDIS_KEY -> {
                String meta = node.metadata(SchemaNode.META_CATALOG);
                yield meta == null || meta.isBlank() ? null : meta;
            }
            case DATA_SOURCE -> null;
        };
        if (catalog == null || catalog.isBlank()) {
            return;
        }

        String previous = active.activeCatalog().orElse(null);
        if (catalog.equalsIgnoreCase(previous)) {
            statusBar.setActiveDatabase(previous);
            return;
        }

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

    /** Opens {@code command} in a query tab bound to the focused session and runs it. */
    private void runGeneratedCommand(String command) {
        if (command == null || command.isBlank()) {
            return;
        }
        String sessionId = sessions.focused().map(ConnectionSession::id).orElse(null);
        editors.openGeneratedSql(
                new SqlTemplateGenerator.Template(command, "", "redis-cmd.redis"), sessionId);
        executeSql(null, false);
    }

    /** Re-runs the last non-explain script from the results toolbar / auto-refresh. */
    private void rerunLastQuery() {
        if (lastRerunStatements.isEmpty()) {
            return;
        }
        if (activeTask != null && activeTask.isRunning()) {
            return;
        }
        Optional<ConnectionSession> sessionOpt = Optional.ofNullable(lastRunSessionId)
                .flatMap(sessions::find)
                .filter(ConnectionSession::isConnected)
                .or(() -> resolveSession(editors.activeEditor()));
        if (sessionOpt.isEmpty()) {
            outcome.present(QueryResult.ofError("Not connected. Use Connect or New Connection first.", 0));
            return;
        }
        DataSourceDriver active = sessionOpt.get().driver();
        boolean redis = isRedis(sessionOpt.get());
        cancelling = false;
        setQueryRunning(true, sessionOpt.get());
        final List<String> toRun = lastRerunStatements;
        final String historySql = lastRerunHistorySql;
        outcome.showLoading(toRun);
        statusBar.setQueryRunning(redis);
        Task<ScriptResult> task = new Task<>() {
            @Override
            protected ScriptResult call() throws Exception {
                return active.executeScriptAsync(toRun).get();
            }
        };
        activeTask = task;
        task.setOnSucceeded(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            ScriptResult script = task.getValue();
            outcome.presentScript(script, false, toRun);
            statusBar.setScriptSummary(script.summary(redis), script.errorCount() > 0);
            outcome.toolbar().notifyQueryFinished(script.errorCount() > 0);
            recordHistory(historySql, script, redis);
            historyPane.refresh();
            syncTransactionStatus(sessionOpt.get());
            if (script.errorCount() == 0) {
                afterSuccessfulScript(sessionOpt.get(), toRun);
            }
            updateActionStates();
        });
        task.setOnFailed(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            QueryResult result = QueryResult.ofError(rootCauseMessage(task.getException()), 0);
            outcome.present(result);
            statusBar.setResult(result, redis);
            outcome.toolbar().notifyQueryFinished(true);
            updateActionStates();
        });
        task.setOnCancelled(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            QueryResult result = QueryResult.ofError(redis ? "Command cancelled" : "Query cancelled", 0);
            outcome.present(result);
            statusBar.setResult(result, redis);
            outcome.toolbar().notifyQueryFinished(true);
            updateActionStates();
        });
        backgroundTasks.execute(task);
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
        Optional<ConnectionSession> sessionOpt = resolveSession(editor);
        if (sessionOpt.isEmpty()) {
            outcome.present(QueryResult.ofError("Not connected. Use Connect or New Connection first.", 0));
            statusBar.setResult(QueryResult.ofError("Not connected", 0));
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
        lastRunSessionId = session.id();
        boolean redis = session.config().connectionType().isRedis();

        if (analyze != null && redis) {
            outcome.present(QueryResult.ofError("EXPLAIN is not available for Redis connections.", 0));
            statusBar.setResult(QueryResult.ofError("EXPLAIN is not available for Redis.", 0));
            return;
        }

        List<String> statements = redis
                ? editor.getEffectiveRedisCommands()
                : editor.getEffectiveStatements();
        if (statements.isEmpty()) {
            outcome.present(QueryResult.ofError("Nothing to run. Type a statement or select one.", 0));
            statusBar.setResult(QueryResult.ofError("Nothing to run", 0));
            return;
        }

        Window owner = root.getScene() == null ? null : root.getScene().getWindow();
        ConnectionConfig.Driver dialect = active.currentConfig()
                .map(ConnectionConfig::driver)
                .orElse(ConnectionConfig.Driver.MYSQL);

        final List<String> toRun;
        final String historySql;
        final List<String> sourceStatements;
        if (analyze == null) {
            if (redis) {
                toRun = statements;
                historySql = String.join("\n", statements);
                lastRerunStatements = List.copyOf(statements);
                lastRerunHistorySql = historySql;
                outcome.setRefreshEnabled(true);
                sourceStatements = List.copyOf(statements);
            } else {
                List<String> bound = new ArrayList<>(statements.size());
                for (String statement : statements) {
                    if (SqlParameterParser.find(statement).isEmpty()) {
                        bound.add(statement);
                        continue;
                    }
                    var substituted = ParameterPromptDialog.promptAndSubstitute(owner, statement);
                    if (substituted.isEmpty()) {
                        return;
                    }
                    bound.add(substituted.get());
                }
                toRun = bound;
                historySql = String.join(";\n", bound);
                lastRerunStatements = List.copyOf(bound);
                lastRerunHistorySql = historySql;
                outcome.setRefreshEnabled(true);
                sourceStatements = List.copyOf(bound);
            }
        } else {
            String one = editor.getEffectiveSql();
            if (!SqlParameterParser.find(one).isEmpty()) {
                var substituted = ParameterPromptDialog.promptAndSubstitute(owner, one);
                if (substituted.isEmpty()) {
                    return;
                }
                one = substituted.get();
            }
            String wrapped = ExplainSql.wrap(one, dialect, analyze);
            if (wrapped.isBlank()) {
                outcome.present(QueryResult.ofError("Nothing to explain.", 0));
                return;
            }
            toRun = List.of(wrapped);
            historySql = wrapped;
            sourceStatements = List.of(one);
        }

        cancelling = false;
        setQueryRunning(true, session);
        outcome.showLoading(toRun);
        statusBar.setQueryRunning(redis);

        Task<ScriptResult> task = new Task<>() {
            @Override
            protected ScriptResult call() throws Exception {
                return active.executeScriptAsync(toRun).get();
            }
        };
        activeTask = task;
        task.setOnSucceeded(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            ScriptResult script = task.getValue();
            outcome.presentScript(script, preferPlan, sourceStatements);
            statusBar.setScriptSummary(script.summary(redis), script.errorCount() > 0);
            outcome.toolbar().notifyQueryFinished(script.errorCount() > 0);
            recordHistory(historySql, script, redis);
            historyPane.refresh();
            syncTransactionStatus(session);
            if (analyze == null && script.errorCount() == 0) {
                afterSuccessfulScript(session, sourceStatements);
            }
        });
        task.setOnFailed(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            QueryResult result = QueryResult.ofError(rootCauseMessage(task.getException()), 0);
            outcome.present(result);
            statusBar.setResult(result, redis);
            outcome.toolbar().notifyQueryFinished(true);
            recordHistory(historySql, ScriptResult.ofSingle(result), redis);
            historyPane.refresh();
            syncTransactionStatus(session);
        });
        task.setOnCancelled(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            QueryResult result = QueryResult.ofError(redis ? "Command cancelled" : "Query cancelled", 0);
            outcome.present(result);
            statusBar.setResult(result, redis);
            outcome.toolbar().notifyQueryFinished(true);
            recordHistory(historySql, ScriptResult.ofSingle(result), redis);
            historyPane.refresh();
            syncTransactionStatus(session);
        });
        backgroundTasks.execute(task);
    }

    private CompletableFuture<QueryResult> loadResultPage(QueryOutcomePane.PageRequest request) {
        if (request == null || request.sql() == null || request.sql().isBlank()) {
            return CompletableFuture.completedFuture(QueryResult.ofError("Nothing to load", 0));
        }
        Optional<ConnectionSession> sessionOpt = Optional.ofNullable(lastRunSessionId)
                .flatMap(sessions::find)
                .filter(ConnectionSession::isConnected)
                .or(() -> sessions.focused().filter(ConnectionSession::isConnected));
        if (sessionOpt.isEmpty()) {
            return CompletableFuture.completedFuture(QueryResult.ofError("Not connected", 0));
        }
        DataSourceDriver driver = sessionOpt.get().driver();
        ResultPager.Plan plan = ResultPager.plan(request.sql(), request.offset(), request.limit());
        return driver.executeQueryAsync(plan.sql(), plan.skipRows(), plan.maxRows())
                .thenCompose(result -> {
                    if (result.isError() && PageSql.canWrap(request.sql()) && plan.skipRows() == 0
                            && request.offset() > 0) {
                        return driver.executeQueryAsync(request.sql(), request.offset(), request.limit());
                    }
                    return CompletableFuture.completedFuture(result);
                });
    }

    private java.util.Optional<QueryOutcomePane.EditableResultTarget> resolveEditableResult(String sql) {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (sessionOpt.isEmpty()) {
            return java.util.Optional.empty();
        }
        ConnectionSession session = sessionOpt.get();
        SchemaCache schemaCache = session.schemaCache();
        DataSourceDriver active = session.driver();
        var simple = SimpleSelectAnalyzer.tryAnalyze(sql);
        if (simple.isEmpty()) {
            return java.util.Optional.empty();
        }
        var info = simple.get();
        SchemaNode table = schemaCache.findTable(info.table(), info.catalog())
                .or(() -> schemaCache.findTable(info.table()))
                .orElse(null);
        if (table == null || table.type() != SchemaNode.NodeType.TABLE) {
            return java.util.Optional.empty();
        }
        List<String> pks = primaryKeyColumns(table);
        if (pks.isEmpty()) {
            return java.util.Optional.empty();
        }
        String catalog = info.catalog();
        if (catalog == null || catalog.isBlank()) {
            catalog = table.metadata(SchemaNode.META_CATALOG);
        }
        if (catalog == null || catalog.isBlank()) {
            catalog = active.activeCatalog().orElse(null);
        }
        String qualified = catalog == null || catalog.isBlank() ? table.name() : catalog + "." + table.name();
        return java.util.Optional.of(new QueryOutcomePane.EditableResultTarget(table, qualified, pks));
    }

    private void rerunHistory(String sql) {
        SqlEditorPane editor = editors.activeEditor();
        if (editor != null) {
            editor.setSql(sql);
        }
        Optional<ConnectionSession> sessionOpt = resolveSession(editor);
        if (sessionOpt.isEmpty()) {
            outcome.present(QueryResult.ofError("Not connected. Use Connect or New Connection first.", 0));
            return;
        }
        // Run the restored text as a script (may be multi-statement).
        if (activeTask != null && activeTask.isRunning()) {
            return;
        }
        List<String> statements = com.lazaro.sqlide.ui.components.SqlStatementExtractor.statements(sql);
        if (statements.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
        boolean redis = isRedis(session);
        lastRunSessionId = session.id();
        cancelling = false;
        setQueryRunning(true, session);
        outcome.showLoading(statements);
        statusBar.setQueryRunning(redis);
        Task<ScriptResult> task = new Task<>() {
            @Override
            protected ScriptResult call() throws Exception {
                return active.executeScriptAsync(statements).get();
            }
        };
        activeTask = task;
        task.setOnSucceeded(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            ScriptResult script = task.getValue();
            outcome.presentScript(script, false, statements);
            statusBar.setScriptSummary(script.summary(redis), script.errorCount() > 0);
            outcome.toolbar().notifyQueryFinished(script.errorCount() > 0);
            recordHistory(sql, script, redis);
            historyPane.refresh();
            syncTransactionStatus(session);
            if (script.errorCount() == 0) {
                afterSuccessfulScript(session, statements);
            }
        });
        task.setOnFailed(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            QueryResult result = QueryResult.ofError(rootCauseMessage(task.getException()), 0);
            outcome.present(result);
            statusBar.setResult(result, redis);
            outcome.toolbar().notifyQueryFinished(true);
            syncTransactionStatus(session);
        });
        task.setOnCancelled(event -> {
            activeTask = null;
            cancelling = false;
            setQueryRunning(false);
            outcome.toolbar().notifyQueryFinished(true);
            syncTransactionStatus(session);
        });
        backgroundTasks.execute(task);
    }

    private void recordHistory(String sql, ScriptResult script) {
        recordHistory(sql, script, false);
    }

    private void recordHistory(String sql, ScriptResult script, boolean redis) {
        if (sql == null || sql.isBlank() || script == null) {
            return;
        }
        historyStore.record(sql, script.summary(redis), script.errorCount() == 0, script.totalTimeMs());
    }

    private void copyActiveResult(ResultExporter.Format format) {
        DynamicResultTable table = outcome.results();
        boolean ok = switch (format) {
            case CSV -> table.copyAsCsv();
            case TSV -> table.copyAsTsv();
            default -> false;
        };
        if (ok) {
            QueryResult slice = table.exportableResult(true);
            int rows = slice == null ? 0 : slice.rowCount();
            String kind = format == ResultExporter.Format.CSV ? "CSV" : "TSV";
            statusBar.setScriptSummary(
                    "Copied " + rows + (rows == 1 ? " row" : " rows") + " as " + kind, false);
        }
    }

    private void exportActiveResult(boolean selectionOnly) {
        QueryResult slice = outcome.results().exportableResult(selectionOnly);
        if (slice == null) {
            return;
        }
        exportResultToFile(slice);
    }

    private void exportResultToFile(QueryResult result) {
        if (result == null || result.isError() || !result.isResultSet()) {
            return;
        }
        ChoiceDialog<ResultExporter.Format> formatDialog = new ChoiceDialog<>(
                ResultExporter.Format.CSV,
                ResultExporter.Format.CSV,
                ResultExporter.Format.TSV,
                ResultExporter.Format.JSON,
                ResultExporter.Format.SQL_INSERT);
        formatDialog.setTitle("Export results");
        formatDialog.setHeaderText("Choose an export format (" + result.rowCount()
                + (result.rowCount() == 1 ? " row" : " rows") + ")");
        formatDialog.setContentText("Format:");
        formatDialog.initOwner(owner());
        var format = formatDialog.showAndWait();
        if (format.isEmpty()) {
            return;
        }

        String tableName = "exported_table";
        if (format.get() == ResultExporter.Format.SQL_INSERT) {
            TextInputDialog tableDialog = new TextInputDialog(tableName);
            tableDialog.setTitle("Export as INSERT");
            tableDialog.setHeaderText("Target table name");
            tableDialog.setContentText("Table:");
            tableDialog.initOwner(owner());
            tableName = tableDialog.showAndWait().orElse(tableName);
        }

        String extension = switch (format.get()) {
            case CSV -> "*.csv";
            case TSV -> "*.tsv";
            case JSON -> "*.json";
            case SQL_INSERT -> "*.sql";
        };
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export results");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(format.get().name(), extension));
        chooser.setInitialFileName("results" + extension.substring(1));
        var file = chooser.showSaveDialog(owner());
        if (file == null) {
            return;
        }
        try {
            String body = ResultExporter.export(result, format.get(), tableName);
            Files.writeString(file.toPath(), body, StandardCharsets.UTF_8);
            statusBar.setScriptSummary("Exported " + file.getName(), false);
        } catch (Exception e) {
            statusBar.setScriptSummary("Export failed: " + rootCauseMessage(e), true);
        }
    }

    private void exportResultAsJsonArray(QueryResult result) {
        if (result == null || result.isError() || !result.isResultSet()) {
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export as JSON Array");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        chooser.setInitialFileName("results.json");
        var file = chooser.showSaveDialog(owner());
        if (file == null) {
            return;
        }
        statusBar.setScriptSummary("Exporting JSON\u2026", false);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                String body = ResultExporter.toJson(result);
                Files.writeString(file.toPath(), body, StandardCharsets.UTF_8);
                return null;
            }
        };
        task.setOnSucceeded(event ->
                statusBar.setScriptSummary(
                        "Exported JSON array (" + result.rowCount()
                                + (result.rowCount() == 1 ? " row" : " rows")
                                + ") to " + file.getName(),
                        false));
        task.setOnFailed(event -> statusBar.setScriptSummary(
                "Export failed: " + rootCauseMessage(task.getException()), true));
        backgroundTasks.execute(task);
    }

    private void cancelQuery() {
        Task<?> task = activeTask;
        if (task == null || !task.isRunning() || cancelling) {
            return;
        }
        cancelling = true;
        outcome.showCancelling();
        Optional<ConnectionSession> sessionOpt = Optional.ofNullable(lastRunSessionId)
                .flatMap(sessions::find)
                .or(() -> resolveSession(editors.activeEditor()));
        statusBar.setQueryRunning(sessionOpt.filter(MainController::isRedis).isPresent());
        DataSourceDriver active = sessionOpt.map(ConnectionSession::driver).orElse(null);
        if (active == null) {
            if (task.isRunning()) {
                task.cancel(true);
            }
            updateActionStates();
            return;
        }
        active.cancelExecution().whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (task.isRunning()) {
                task.cancel(true);
            }
            updateActionStates();
        }));
        updateActionStates();
    }

    private void toggleAutoCommit() {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (sessionOpt.isEmpty() || !sessionOpt.get().driver().capabilities().supportsTransactions()) {
            autoCommitToggle.setSelected(true);
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
        boolean enabled = autoCommitToggle.isSelected();
        statusBar.setBusy(enabled ? "Enabling auto-commit\u2026" : "Starting manual transaction\u2026");
        active.setAutoCommit(enabled).whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                autoCommitToggle.setSelected(active.isAutoCommit());
                statusBar.setConnectionError("Auto-commit: " + rootCauseMessage(error));
                syncTransactionStatus(session);
                updateActionStates();
                return;
            }
            state.saveAutoCommit(enabled);
            active.currentConfig().ifPresent(config ->
                    statusBar.setConnected(config.endpointLabel(), active.activeCatalog().orElse(null)));
            syncTransactionStatus(session);
            updateActionStates();
        }));
    }

    private void beginTransaction() {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (sessionOpt.isEmpty()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
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
            syncTransactionStatus(session);
            updateActionStates();
        }));
    }

    private void commitTransaction() {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (sessionOpt.isEmpty() || sessionOpt.get().driver().isAutoCommit()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
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
            syncTransactionStatus(session);
            updateActionStates();
        }));
    }

    private void rollbackTransaction() {
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        if (sessionOpt.isEmpty() || sessionOpt.get().driver().isAutoCommit()) {
            return;
        }
        ConnectionSession session = sessionOpt.get();
        DataSourceDriver active = session.driver();
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
            syncTransactionStatus(session);
            updateActionStates();
        }));
    }

    private void applyPreferredAutoCommit(ConnectionSession session) {
        boolean preferred = state.autoCommit();
        autoCommitToggle.setSelected(preferred);
        if (session == null || !session.isConnected()) {
            syncTransactionStatus(session);
            return;
        }
        DataSourceDriver active = session.driver();
        if (!active.capabilities().supportsTransactions()) {
            syncTransactionStatus(session);
            return;
        }
        if (active.isAutoCommit() == preferred) {
            syncTransactionStatus(session);
            return;
        }
        active.setAutoCommit(preferred).whenComplete((ignored, error) -> javafx.application.Platform.runLater(() -> {
            if (error != null) {
                autoCommitToggle.setSelected(active.isAutoCommit());
            }
            syncTransactionStatus(session);
            updateActionStates();
        }));
    }

    private void syncTransactionStatus(ConnectionSession session) {
        boolean connected = session != null && session.isConnected();
        boolean auto = !connected || session.driver().isAutoCommit();
        statusBar.setTransactionState(auto, connected);
        autoCommitToggle.setSelected(auto);
    }

    private void insertNodeReference(SchemaNode node) {
        String text = switch (node.type()) {
            case TABLE, VIEW, PROCEDURE -> node.qualifiedName();
            case REDIS_KEY -> {
                String key = node.metadata(SchemaNode.META_REDIS_KEY);
                yield key == null || key.isBlank() ? node.name() : key;
            }
            case DATA_SOURCE -> node.name();
            default -> node.name();
        };
        editors.insertIntoActiveEditor(text);
    }

    /**
     * After a successful script: honor {@code USE} and reload the schema cache so
     * newly created tables / views / procedures appear in autocomplete immediately.
     */
    private void afterSuccessfulScript(ConnectionSession session, List<String> statements) {
        if (session == null || statements == null) {
            return;
        }
        for (String statement : statements) {
            syncActiveCatalogFromSql(session, statement);
        }
        if (SchemaChangingSql.anyChangesSchema(statements)
                || (session.config().connectionType().isRedis() && RedisMutatingCommands.any(statements))) {
            schemaTree.reload();
            refreshSchemaCache(session);
        }
    }

    /** Keeps the footer in sync when the user runs {@code USE db} from the editor. */
    private void syncActiveCatalogFromSql(ConnectionSession session, String sql) {
        String catalog = parseUseCatalog(sql);
        if (catalog == null || session == null) {
            return;
        }
        DataSourceDriver active = session.driver();
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
        Optional<ConnectionSession> sessionOpt = resolveSession(editors.activeEditor());
        boolean connected = sessionOpt.isPresent();
        boolean busy = activeTask != null && activeTask.isRunning();
        DataSourceDriver active = sessionOpt.map(ConnectionSession::driver).orElse(null);
        boolean txn = connected && active != null && active.capabilities().supportsTransactions();
        boolean manual = txn && !active.isAutoCommit();

        boolean redis = connected && sessionOpt.get().config().connectionType().isRedis();

        runButton.setDisable(!connected || busy);
        stopButton.setDisable(!busy || cancelling);
        explainButton.setDisable(!connected || busy || redis);
        connectButton.setDisable(busy);
        disconnectButton.setDisable(!connected || busy);
        refreshButton.setDisable(!connected || busy);
        compareStructureButton.setDisable(!connected || busy || sessions.connectedSessions().isEmpty());
        compareDataButton.setDisable(busy);

        autoCommitToggle.setDisable(!txn || busy);
        beginButton.setDisable(!txn || busy || manual);
        commitButton.setDisable(!manual || busy);
        rollbackButton.setDisable(!manual || busy);

        outcome.setRefreshEnabled(!lastRerunStatements.isEmpty() && connected && !busy);
    }

    private void setConnecting(boolean connecting) {
        setConnecting(connecting, focusedBusyConnectionId());
    }

    private void setConnecting(boolean connecting, String connectionId) {
        connectingBusy = connecting;
        connectButton.setDisable(connecting);
        syncTreeBusy(connectionId);
        updateActionStates();
    }

    private void setQueryRunning(boolean running) {
        setQueryRunning(running, null);
    }

    private void setQueryRunning(boolean running, ConnectionSession session) {
        ConnectionSession target = session != null ? session : sessions.focused().orElse(null);
        syncTreeBusy(running && target != null ? busyConnectionId(target) : null);
        updateActionStates();
    }

    private void syncTreeBusy(String connectionId) {
        if (connectionId != null && !connectionId.isBlank()) {
            treeBusyConnectionId = connectionId;
        }
        boolean queryBusy = activeTask != null && activeTask.isRunning();
        boolean busy = connectingBusy || indexingBusy || queryBusy;
        schemaTree.setBusyConnection(busy ? treeBusyConnectionId : null);
    }

    private String focusedBusyConnectionId() {
        return sessions.focused().map(MainController::busyConnectionId).orElse(null);
    }

    private static String busyConnectionId(ConnectionSession session) {
        return busyConnectionId(session.profileId().orElse(null), session);
    }

    private static String busyConnectionId(String profileId, ConnectionSession session) {
        if (profileId != null && !profileId.isBlank()) {
            return profileId;
        }
        return session == null ? null : session.id();
    }

    private void bindCaret(SqlEditorPane editor) {
        statusBar.bindCaret(editor == null ? null : editor.caretLocation());
    }

    // ---------------------------------------------------------------- helpers

    private static void consumeAnd(KeyEvent event, Runnable action) {
        event.consume();
        action.run();
    }

    private MenuButton explainMenu() {
        MenuButton menu = new MenuButton();
        menu.setGraphic(Icons.explain());
        menu.getStyleClass().addAll("icon-button", "explain-menu-button");
        menu.setTooltip(new Tooltip("EXPLAIN (Ctrl+Shift+E) / EXPLAIN ANALYZE (Ctrl+Shift+A)"));
        MenuItem explain = new MenuItem("Explain");
        explain.setOnAction(event -> runExplain(false));
        MenuItem analyze = new MenuItem("Explain Analyze");
        analyze.setOnAction(event -> runExplain(true));
        menu.getItems().setAll(explain, analyze);
        return menu;
    }

    private static Button iconButton(Node icon, String tooltip, Runnable action) {
        Button button = new Button();
        button.setGraphic(icon);
        button.setGraphicTextGap(4);
        button.getStyleClass().add("icon-button");
        button.setTooltip(new Tooltip(tooltip));
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Button labelledButton(Node icon, String text, String tooltip, Runnable action) {
        Button button = iconButton(icon, tooltip, action);
        button.setText(text);
        button.setGraphicTextGap(4);
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
