package com.lazaro.sqlide.ui;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.DriverRegistry;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.ui.components.DynamicResultTable;
import com.lazaro.sqlide.ui.components.EditorTabPane;
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
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
 * results reach the scene graph only through the Task's JavaFX callbacks.
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
    private final DynamicResultTable results = new DynamicResultTable();
    private final StatusBar statusBar = new StatusBar();

    private final SplitPane mainSplit = new SplitPane();
    private final SplitPane rightSplit = new SplitPane();
    private final BorderPane sidebar = new BorderPane();
    private final BorderPane root = new BorderPane();

    private Button runButton;
    private Button connectButton;
    private Button disconnectButton;
    private Button refreshButton;

    private DataSourceDriver driver;
    private boolean sidebarCollapsed;
    private double expandedMainDivider = DEFAULT_MAIN_DIVIDER;

    public MainController(DriverRegistry registry, WorkspaceState state) {
        this.registry = registry;
        this.state = state;
        this.driver = registry.create(DriverRegistry.DEFAULT_DRIVER_ID);
        schemaTree.setDriver(driver);
    }

    // ---------------------------------------------------------------- view

    public Parent createView() {
        root.getStyleClass().add("app-root");
        root.setTop(buildToolBar());
        root.setCenter(buildMainSplit());
        root.setBottom(statusBar);

        schemaTree.setOnActivate(this::insertNodeReference);
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
        refreshButton = iconButton(Icons.refresh(), "Refresh Schema (Ctrl+R)", schemaTree::reload);

        runButton = labelledButton(Icons.run(), "Run", "Execute (Ctrl+Enter)", this::runQuery);
        runButton.getStyleClass().add("run-button");

        ToolBar toolBar = new ToolBar(
                sidebarToggle,
                separator(),
                newQuery, save,
                separator(),
                connectButton, disconnectButton, refreshButton,
                separator(),
                runButton);
        toolBar.getStyleClass().add("app-toolbar");
        return toolBar;
    }

    private Node buildMainSplit() {
        // --- schema explorer, with the rail that survives collapsing -------------
        VBox rail = new VBox(railToggle());
        rail.getStyleClass().add("sidebar-rail");
        rail.setAlignment(Pos.TOP_CENTER);
        rail.setMinWidth(RAIL_WIDTH);
        rail.setPrefWidth(RAIL_WIDTH);
        rail.setMaxWidth(RAIL_WIDTH);

        sidebar.getStyleClass().add("sidebar");
        sidebar.setLeft(rail);
        sidebar.setCenter(schemaTree);
        // Never let the explorer be squeezed past its rail.
        sidebar.setMinWidth(RAIL_WIDTH);

        // --- editor over results -------------------------------------------------
        results.setMinHeight(80);
        rightSplit.setOrientation(Orientation.VERTICAL);
        rightSplit.getItems().setAll(editors, results);
        rightSplit.setMinWidth(320);
        SplitPane.setResizableWithParent(results, true);

        mainSplit.setOrientation(Orientation.HORIZONTAL);
        mainSplit.getItems().setAll(sidebar, rightSplit);
        // Window resizing grows the working area, not the explorer.
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
        KeyCombination toggleSidebar = new KeyCodeCombination(KeyCode.DIGIT1, KeyCombination.SHORTCUT_DOWN);
        KeyCombination newTab = new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN);
        KeyCombination closeTab = new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN);
        KeyCombination save = new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN);
        KeyCombination connect = new KeyCodeCombination(KeyCode.K, KeyCombination.SHORTCUT_DOWN);
        KeyCombination refresh = new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN);

        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (run.match(event) || runAlt.match(event)) {
                consumeAnd(event, this::runQuery);
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
                consumeAnd(event, schemaTree::reload);
            }
        });
    }

    // ---------------------------------------------------------------- lifecycle

    /** Applies saved divider positions. Must run after the stage is shown. */
    public void restoreLayout() {
        mainSplit.setDividerPositions(state.mainDivider(DEFAULT_MAIN_DIVIDER));
        rightSplit.setDividerPositions(state.rightDivider(DEFAULT_RIGHT_DIVIDER));
        expandedMainDivider = mainSplit.getDividerPositions()[0];

        if (state.sidebarCollapsed()) {
            sidebarCollapsed = false;
            toggleSidebar();
        }
    }

    /** @return {@code false} when the user cancelled out of an unsaved-changes prompt */
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
            // Dropping the tree from the layout lets the sidebar shrink to the rail;
            // otherwise the tree's own minimum width would hold the divider open.
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
        DataSourceDriver active = driver;
        connectButton.setDisable(true);
        statusBar.setBusy("Connecting to " + config.displayLabel() + "\u2026");

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                active.connect(config).get();
                return null;
            }
        };
        task.setOnSucceeded(event -> {
            connectButton.setDisable(false);
            statusBar.setConnected(config.displayLabel());
            state.saveLastConnection(config);
            schemaTree.reload();
            updateActionStates();
        });
        task.setOnFailed(event -> {
            connectButton.setDisable(false);
            statusBar.setDisconnected();
            updateActionStates();
            showError("Connection failed", "Could not connect to " + config.displayLabel(),
                    rootCauseMessage(task.getException()));
        });
        backgroundTasks.execute(task);
    }

    /**
     * Retires the current driver and takes a fresh one from the registry, which
     * keeps disconnection expressible through the interface alone.
     */
    private void disconnect() {
        DataSourceDriver retired = driver;
        driver = registry.create(DriverRegistry.DEFAULT_DRIVER_ID);

        schemaTree.setDriver(driver);
        schemaTree.clear();
        results.clear();
        statusBar.setDisconnected();
        updateActionStates();

        backgroundTasks.execute(retired::close);
    }

    private void runQuery() {
        SqlEditorPane editor = editors.activeEditor();
        if (editor == null) {
            return;
        }
        DataSourceDriver active = driver;
        if (!active.isConnected()) {
            showError("Not connected", "Connect to a database before running a query.", null);
            return;
        }

        String sql = editor.getEffectiveSql();
        runButton.setDisable(true);
        results.showMessage("Running\u2026");
        statusBar.clearResult();

        Task<QueryResult> task = new Task<>() {
            @Override
            protected QueryResult call() throws Exception {
                return active.executeQueryAsync(sql).get();
            }
        };
        task.setOnSucceeded(event -> {
            runButton.setDisable(false);
            QueryResult result = task.getValue();
            // A rejected statement is reported in the grid and the status strip, not
            // as a modal: interrupting the user on every syntax slip is unusable.
            results.setResult(result);
            statusBar.setResult(result);
        });
        task.setOnFailed(event -> {
            runButton.setDisable(false);
            results.showMessage("Execution failed.");
            showError("Execution failed", "The query could not be run.", rootCauseMessage(task.getException()));
        });
        backgroundTasks.execute(task);
    }

    private void insertNodeReference(SchemaNode node) {
        String text = switch (node.type()) {
            case TABLE, VIEW -> node.qualifiedName();
            default -> node.name();
        };
        editors.insertIntoActiveEditor(text);
    }

    private void updateActionStates() {
        boolean connected = driver.isConnected();
        runButton.setDisable(!connected);
        disconnectButton.setDisable(!connected);
        refreshButton.setDisable(!connected);
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

    private void showError(String title, String header, String detail) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.initOwner(owner());

        if (detail == null || detail.isBlank()) {
            alert.setContentText("No further detail was reported.");
        } else if (detail.length() <= 220) {
            alert.setContentText(detail);
        } else {
            // Long server messages get their own scrollable area rather than
            // stretching the dialog off-screen.
            alert.setContentText("The server returned a long message.");
            TextArea area = new TextArea(detail);
            area.setEditable(false);
            area.setWrapText(true);
            area.setPrefRowCount(8);
            VBox.setVgrow(area, Priority.ALWAYS);
            HBox.setHgrow(area, Priority.ALWAYS);
            alert.getDialogPane().setExpandableContent(area);
            alert.getDialogPane().setExpanded(true);
        }
        alert.showAndWait();
    }

    private static String rootCauseMessage(Throwable error) {
        if (error == null) {
            return null;
        }
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

}
