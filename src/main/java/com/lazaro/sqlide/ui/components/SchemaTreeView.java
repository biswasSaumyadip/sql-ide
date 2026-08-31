package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.config.ConnectionProfile;
import com.lazaro.sqlide.core.config.ConnectionProfileManager;
import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.RedisDriver;
import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.redis.RedisKeyspace;
import com.lazaro.sqlide.core.redis.RedisReadCommands;
import com.lazaro.sqlide.core.session.ConnectionSession;
import com.lazaro.sqlide.core.session.SessionManager;
import com.lazaro.sqlide.ui.Icons;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * IntelliJ-style Database pane: saved data sources as persistent roots, live
 * schema under each connected session, filter box, and generate/copy actions.
 */
public final class SchemaTreeView extends VBox {

    private static final String META_PLACEHOLDER = "__placeholder";
    private static final String META_SHOW_MORE = "__showMore";

    private final TreeView<SchemaNode> tree = new TreeView<>();
    private final TextField filterField = new TextField();
    private final SchemaSelectionControl schemaSelection = new SchemaSelectionControl();
    private final StackPane body = new StackPane();
    private final Label emptyHint = new Label();

    private ConnectionProfileManager profileManager = new ConnectionProfileManager();
    private SessionManager sessionManager;
    private String filterQuery = "";
    private String busyConnectionId;
    private final List<TreeItem<SchemaNode>> allDataSources = new ArrayList<>();

    private Consumer<SchemaNode> onActivate = node -> { };
    private Consumer<SchemaNode> onViewObject = node -> { };
    private Consumer<SchemaNode> onShowDiagram = node -> { };
    private Consumer<SchemaNode> onOpenData = node -> { };
    private Consumer<SchemaNode> onImportData = node -> { };
    private Consumer<SchemaNode> onTransferData = node -> { };
    private Consumer<SchemaNode> onModifyTable = node -> { };
    private Consumer<SchemaNode> onUseDatabase = node -> { };
    private Runnable onConnectRequested = () -> { };
    private Consumer<ConnectionProfile> onConnectProfile = profile -> { };
    private Consumer<ConnectionProfile> onDeleteProfile = profile -> { };
    private Consumer<String> onInsertSql = sql -> { };
    private Consumer<String> onRunCommand = command -> { };
    private Consumer<SqlTemplateGenerator.Template> onOpenTemplate = template -> { };
    private Consumer<String> onNewQuery = sessionId -> { };
    private Consumer<String> onDisconnect = sessionId -> { };
    private Runnable onRefreshSchema = () -> { };
    private Consumer<String> onSessionFocused = sessionId -> { };

    public SchemaTreeView() {
        getStyleClass().add("schema-tree-pane");
        setMinWidth(170);

        Button addConnection = new Button();
        addConnection.setGraphic(Icons.newQuery());
        addConnection.getStyleClass().addAll(
                "panel-header-action",
                "panel-header-icon-action",
                "schema-add-connection");
        addConnection.setTooltip(new Tooltip("New Connection"));
        addConnection.setOnAction(event -> onConnectRequested.run());
        HBox header = new HBox(addConnection);
        header.getStyleClass().add("panel-header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(4, 6, 4, 6));

        filterField.setPromptText("Filter tables, columns\u2026");
        filterField.getStyleClass().add("sidebar-search");
        filterField.setMaxWidth(Double.MAX_VALUE);
        filterField.textProperty().addListener((observable, previous, current) -> {
            filterQuery = current == null ? "" : current;
            applyFilter();
        });
        filterField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                filterField.clear();
            }
        });

        schemaSelection.setOnSelectionChanged(connectionId -> {
            applyFilter();
            tree.refresh();
        });
        HBox.setHgrow(filterField, Priority.ALWAYS);
        HBox filterBar = new HBox(4, filterField);
        filterBar.getStyleClass().add("schema-toolbar");
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(4, 6, 4, 6));

        tree.setShowRoot(false);
        tree.getStyleClass().add("schema-tree");
        tree.setCellFactory(view -> new SchemaTreeCell(schemaSelection));
        tree.setRoot(new TreeItem<>(SchemaNode.of("root", NodeType.DATABASE)));
        tree.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
            if (selected != null && isShowMore(selected.getValue())
                    && selected.getParent() instanceof LazyItem parent) {
                event.consume();
                parent.showMore();
                return;
            }
            if (event.getClickCount() != 2) {
                return;
            }
            if (selected == null || selected.getValue() == null || isPlaceholder(selected.getValue())) {
                return;
            }
            handleDoubleClick(selected);
        });
        SchemaTreeContextMenus contextMenus = new SchemaTreeContextMenus(
                tree.getSelectionModel()::getSelectedItem,
                new SchemaTreeContextMenus.Actions(
                        () -> onNewQuery.accept(sessionIdOfSelection()),
                        () -> onConnectRequested.run(),
                        sql -> onInsertSql.accept(sql),
                        template -> onOpenTemplate.accept(template),
                        item -> {
                            if (item != null && item.getValue() != null) {
                                onViewObject.accept(item.getValue());
                            }
                        },
                        item -> {
                            if (item != null && item.getValue() != null) {
                                onShowDiagram.accept(item.getValue());
                            }
                        },
                        item -> {
                            if (item != null && item.getValue() != null) {
                                onOpenData.accept(item.getValue());
                            }
                        },
                        item -> {
                            if (item != null && item.getValue() != null) {
                                onImportData.accept(item.getValue());
                            }
                        },
                        item -> {
                            if (item != null && item.getValue() != null) {
                                onTransferData.accept(item.getValue());
                            }
                        },
                        item -> {
                            if (item != null && item.getValue() != null) {
                                onModifyTable.accept(item.getValue());
                            }
                        },
                        this::refreshTreeItem,
                        () -> onRefreshSchema.run(),
                        this::editConnectionFromItem,
                        () -> onDisconnect.accept(sessionIdOfSelection()),
                        this::connectSelectedDataSource,
                        this::removeConnectionFromItem,
                        this::dumpSqlToFile,
                        command -> onRunCommand.accept(command)));
        tree.setContextMenu(contextMenus.menu());
        // Populate before show — mutating items in onShowing cancels the popup.
        tree.addEventFilter(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            contextMenus.prepare();
        });
        tree.getSelectionModel().selectedItemProperty().addListener((obs, prev, selected) -> {
            String sessionId = sessionIdOf(selected);
            if (sessionId != null) {
                onSessionFocused.accept(sessionId);
            }
        });

        emptyHint.getStyleClass().add("empty-state-detail");
        emptyHint.setWrapText(true);
        emptyHint.setPadding(new Insets(12));
        emptyHint.setMouseTransparent(true);

        body.getChildren().addAll(tree, emptyHint);
        StackPane.setAlignment(emptyHint, Pos.CENTER);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, filterBar, body);
        rebuildDataSources();
    }

    /**
     * Replaces the MySQL / database icon on the matching connection with a spinner
     * while a connect or query is in flight. Pass {@code null} to restore the icon.
     */
    public void setBusyConnection(String connectionId) {
        String next = connectionId == null || connectionId.isBlank() ? null : connectionId;
        if (Objects.equals(busyConnectionId, next)) {
            return;
        }
        busyConnectionId = next;
        tree.refresh();
    }

    private boolean isBusy(SchemaNode node) {
        if (busyConnectionId == null || node == null || node.type() != NodeType.DATA_SOURCE) {
            return false;
        }
        if (busyConnectionId.equals(connectionIdOf(node))) {
            return true;
        }
        String sessionId = node.metadata("sessionId");
        return busyConnectionId.equals(sessionId);
    }

    private void refreshTreeItem(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            onRefreshSchema.run();
            return;
        }
        if (item.getValue().type() == NodeType.DATA_SOURCE) {
            onRefreshSchema.run();
            return;
        }
        if (item instanceof LazyItem lazy) {
            lazy.forceReload(this::loadSchemaChildren);
            return;
        }
        onRefreshSchema.run();
    }

    private void editConnectionFromItem(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            onConnectRequested.run();
            return;
        }
        String profileId = connectionIdOf(item.getValue());
        if (profileId == null || item.getValue().metadataFlag(SchemaNode.META_SESSION)) {
            onConnectRequested.run();
            return;
        }
        profileManager.loadProfiles().stream()
                .filter(profile -> profileId.equals(profile.id()))
                .findFirst()
                .ifPresentOrElse(onConnectProfile, onConnectRequested);
    }

    private void removeConnectionFromItem(TreeItem<SchemaNode> item) {
        if (item == null || item.getValue() == null) {
            return;
        }
        String profileId = item.getValue().metadata(SchemaNode.META_PROFILE_ID);
        if (profileId == null || item.getValue().metadataFlag(SchemaNode.META_SESSION)) {
            return;
        }
        profileManager.loadProfiles().stream()
                .filter(profile -> profileId.equals(profile.id()))
                .findFirst()
                .ifPresent(profile -> {
                    onDeleteProfile.accept(profile);
                    schemaSelection.forgetConnection(profileId);
                    rebuildDataSources();
                });
    }

    private void dumpSqlToFile(String sql) {
        if (sql == null || sql.isBlank() || tree.getScene() == null) {
            return;
        }
        javafx.stage.FileChooser chooser = new javafx.stage.FileChooser();
        chooser.setTitle("Dump SQL to File");
        chooser.getExtensionFilters().add(
                new javafx.stage.FileChooser.ExtensionFilter("SQL files", "*.sql"));
        chooser.setInitialFileName("dump.sql");
        java.io.File file = chooser.showSaveDialog(tree.getScene().getWindow());
        if (file == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(file.toPath(), sql, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ignored) {
            // best-effort dump
        }
    }

    // ---------------------------------------------------------------- public API

    public void setSessionManager(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
        rebuildDataSources();
    }

    public void setProfileManager(ConnectionProfileManager profileManager) {
        this.profileManager = profileManager == null ? new ConnectionProfileManager() : profileManager;
        rebuildDataSources();
    }

    public void setOnConnectRequested(Runnable onConnectRequested) {
        this.onConnectRequested = onConnectRequested == null ? () -> { } : onConnectRequested;
    }

    public void setOnConnectProfile(Consumer<ConnectionProfile> onConnectProfile) {
        this.onConnectProfile = onConnectProfile == null ? profile -> { } : onConnectProfile;
    }

    public void setOnDeleteProfile(Consumer<ConnectionProfile> onDeleteProfile) {
        this.onDeleteProfile = onDeleteProfile == null ? profile -> { } : onDeleteProfile;
    }

    public void setOnActivate(Consumer<SchemaNode> onActivate) {
        this.onActivate = onActivate == null ? node -> { } : onActivate;
    }

    public void setOnViewObject(Consumer<SchemaNode> onViewObject) {
        this.onViewObject = onViewObject == null ? node -> { } : onViewObject;
    }

    public void setOnShowDiagram(Consumer<SchemaNode> onShowDiagram) {
        this.onShowDiagram = onShowDiagram == null ? node -> { } : onShowDiagram;
    }

    public void setOnOpenData(Consumer<SchemaNode> onOpenData) {
        this.onOpenData = onOpenData == null ? node -> { } : onOpenData;
    }

    public void setOnImportData(Consumer<SchemaNode> onImportData) {
        this.onImportData = onImportData == null ? node -> { } : onImportData;
    }

    public void setOnTransferData(Consumer<SchemaNode> onTransferData) {
        this.onTransferData = onTransferData == null ? node -> { } : onTransferData;
    }

    public void setOnModifyTable(Consumer<SchemaNode> onModifyTable) {
        this.onModifyTable = onModifyTable == null ? node -> { } : onModifyTable;
    }

    public void setOnUseDatabase(Consumer<SchemaNode> onUseDatabase) {
        this.onUseDatabase = onUseDatabase == null ? node -> { } : onUseDatabase;
    }

    /** Inserts generated SQL into the active editor. */
    public void setOnInsertSql(Consumer<String> onInsertSql) {
        this.onInsertSql = onInsertSql == null ? sql -> { } : onInsertSql;
    }

    /** Opens a command in the editor and executes it (Redis Flush / key reads). */
    public void setOnRunCommand(Consumer<String> onRunCommand) {
        this.onRunCommand = onRunCommand == null ? command -> { } : onRunCommand;
    }

    /** Opens a new query tab with a generated CREATE/ALTER template. */
    public void setOnOpenTemplate(Consumer<SqlTemplateGenerator.Template> onOpenTemplate) {
        this.onOpenTemplate = onOpenTemplate == null ? template -> { } : onOpenTemplate;
    }

    /** Opens a new query console; argument is the preferred session id (may be null). */
    public void setOnNewQuery(Consumer<String> onNewQuery) {
        this.onNewQuery = onNewQuery == null ? sessionId -> { } : onNewQuery;
    }

    /** Disconnects the session identified by id (session id or profile-backed session). */
    public void setOnDisconnect(Consumer<String> onDisconnect) {
        this.onDisconnect = onDisconnect == null ? sessionId -> { } : onDisconnect;
    }

    public void setOnRefreshSchema(Runnable onRefreshSchema) {
        this.onRefreshSchema = onRefreshSchema == null ? this::reload : onRefreshSchema;
    }

    public void setOnSessionFocused(Consumer<String> onSessionFocused) {
        this.onSessionFocused = onSessionFocused == null ? sessionId -> { } : onSessionFocused;
    }

    /** Nearest TABLE ancestor of the current selection, if any. Views are ignored. */
    public Optional<SchemaNode> selectedTable() {
        TreeItem<SchemaNode> item = tree.getSelectionModel().getSelectedItem();
        while (item != null) {
            SchemaNode node = item.getValue();
            if (node != null && node.type() == NodeType.TABLE) {
                return Optional.of(node);
            }
            item = item.getParent();
        }
        return Optional.empty();
    }

    public void refreshSavedConnections() {
        rebuildDataSources();
    }

    /** Reloads live schema under every connected data source. */
    public void reload() {
        rebuildDataSources();
        if (sessionManager == null) {
            return;
        }
        for (TreeItem<SchemaNode> rootItem : allDataSources) {
            if (!(rootItem instanceof DataSourceItem dataSourceItem)) {
                continue;
            }
            SchemaNode node = dataSourceItem.getValue();
            if (node == null || !node.metadataFlag(SchemaNode.META_ACTIVE)) {
                continue;
            }
            loadDataSourceChildren(dataSourceItem);
        }
    }

    public void clear() {
        rebuildDataSources();
    }

    /**
     * Expands the active data source and selects {@code catalog.table[.column]}.
     * Returns {@code false} when the path cannot be started (no active connection).
     */
    public boolean revealObject(String catalog, String table, String column) {
        if (table == null || table.isBlank()) {
            return false;
        }
        filterField.clear();
        TreeItem<SchemaNode> dataSource = findActiveDataSourceItem();
        if (dataSource == null) {
            return false;
        }
        dataSource.setExpanded(true);
        revealStep(dataSource, catalog, table, column, 0);
        return true;
    }

    private void revealStep(
            TreeItem<SchemaNode> root, String catalog, String table, String column, int attempt) {
        if (attempt > 40) {
            return;
        }
        if (isStillLoading(root)) {
            Platform.runLater(() -> revealStep(root, catalog, table, column, attempt + 1));
            return;
        }
        TreeItem<SchemaNode> db = findChild(root, NodeType.DATABASE, catalog);
        if (db == null) {
            db = findChild(root, NodeType.SCHEMA, catalog);
        }
        if (db == null && (catalog == null || catalog.isBlank())) {
            db = firstDatabase(root);
        }
        if (db == null) {
            return;
        }
        db.setExpanded(true);
        if (isStillLoading(db)) {
            TreeItem<SchemaNode> dbRef = db;
            Platform.runLater(() -> revealAfterDatabase(dbRef, table, column, 0));
            return;
        }
        revealAfterDatabase(db, table, column, 0);
    }

    private void revealAfterDatabase(TreeItem<SchemaNode> db, String table, String column, int attempt) {
        if (attempt > 40) {
            return;
        }
        if (isStillLoading(db)) {
            Platform.runLater(() -> revealAfterDatabase(db, table, column, attempt + 1));
            return;
        }
        TreeItem<SchemaNode> tablesFolder = findFolder(db, SchemaNode.FOLDER_TABLES);
        TreeItem<SchemaNode> viewsFolder = findFolder(db, SchemaNode.FOLDER_VIEWS);
        if (tablesFolder != null) {
            tablesFolder.setExpanded(true);
        }
        if (viewsFolder != null) {
            viewsFolder.setExpanded(true);
        }
        TreeItem<SchemaNode> tableItem = findTableUnder(db, table);
        if (tableItem == null) {
            if ((tablesFolder != null && isStillLoading(tablesFolder))
                    || (viewsFolder != null && isStillLoading(viewsFolder))) {
                Platform.runLater(() -> revealAfterDatabase(db, table, column, attempt + 1));
            }
            return;
        }
        tableItem.setExpanded(true);
        if (column == null || column.isBlank()) {
            selectAndScroll(tableItem);
            return;
        }
        revealColumn(tableItem, column, 0);
    }

    private void revealColumn(TreeItem<SchemaNode> tableItem, String column, int attempt) {
        if (attempt > 40) {
            selectAndScroll(tableItem);
            return;
        }
        if (isStillLoading(tableItem)) {
            Platform.runLater(() -> revealColumn(tableItem, column, attempt + 1));
            return;
        }
        TreeItem<SchemaNode> columnsFolder = findFolder(tableItem, SchemaNode.FOLDER_COLUMNS);
        if (columnsFolder != null) {
            columnsFolder.setExpanded(true);
            if (isStillLoading(columnsFolder)) {
                Platform.runLater(() -> revealColumn(tableItem, column, attempt + 1));
                return;
            }
            TreeItem<SchemaNode> columnItem = findChild(columnsFolder, NodeType.COLUMN, column);
            if (columnItem != null) {
                selectAndScroll(columnItem);
                return;
            }
        }
        TreeItem<SchemaNode> direct = findChild(tableItem, NodeType.COLUMN, column);
        selectAndScroll(direct != null ? direct : tableItem);
    }

    private void selectAndScroll(TreeItem<SchemaNode> item) {
        if (item == null) {
            return;
        }
        tree.getSelectionModel().select(item);
        int index = tree.getRow(item);
        if (index >= 0) {
            tree.scrollTo(index);
        }
        tree.requestFocus();
    }

    private static boolean isStillLoading(TreeItem<SchemaNode> item) {
        if (!item.isLeaf() && item.getChildren().isEmpty()) {
            return true;
        }
        if (item.getChildren().size() == 1) {
            SchemaNode only = item.getChildren().getFirst().getValue();
            return only != null && (isPlaceholder(only)
                    || "Loading\u2026".equals(only.name())
                    || (only.name() != null && only.name().startsWith("Could not load")));
        }
        return false;
    }

    private static TreeItem<SchemaNode> findFolder(TreeItem<SchemaNode> parent, String folderKind) {
        for (TreeItem<SchemaNode> child : parent.getChildren()) {
            SchemaNode node = child.getValue();
            if (node != null && node.type() == NodeType.FOLDER && folderKind.equalsIgnoreCase(node.folderKind())) {
                return child;
            }
        }
        return null;
    }

    private static TreeItem<SchemaNode> findChild(TreeItem<SchemaNode> parent, NodeType type, String name) {
        if (parent == null || name == null || name.isBlank()) {
            return null;
        }
        for (TreeItem<SchemaNode> child : parent.getChildren()) {
            SchemaNode node = child.getValue();
            if (node != null && node.type() == type && name.equalsIgnoreCase(node.name())) {
                return child;
            }
        }
        return null;
    }

    private static TreeItem<SchemaNode> firstDatabase(TreeItem<SchemaNode> root) {
        for (TreeItem<SchemaNode> child : root.getChildren()) {
            SchemaNode node = child.getValue();
            if (node != null && (node.type() == NodeType.DATABASE || node.type() == NodeType.SCHEMA)
                    && !isPlaceholder(node)) {
                return child;
            }
        }
        return null;
    }

    private TreeItem<SchemaNode> findTableUnder(TreeItem<SchemaNode> db, String table) {
        TreeItem<SchemaNode> inTables = findFolder(db, SchemaNode.FOLDER_TABLES);
        if (inTables instanceof LazyItem tablesLazy) {
            tablesLazy.ensureNamedChildVisible(NodeType.TABLE, table);
            tablesLazy.ensureNamedChildVisible(NodeType.VIEW, table);
        }
        if (inTables != null) {
            TreeItem<SchemaNode> hit = findChild(inTables, NodeType.TABLE, table);
            if (hit != null) {
                return hit;
            }
            hit = findChild(inTables, NodeType.VIEW, table);
            if (hit != null) {
                return hit;
            }
        }
        TreeItem<SchemaNode> inViews = findFolder(db, SchemaNode.FOLDER_VIEWS);
        if (inViews instanceof LazyItem viewsLazy) {
            viewsLazy.ensureNamedChildVisible(NodeType.VIEW, table);
        }
        if (inViews != null) {
            TreeItem<SchemaNode> hit = findChild(inViews, NodeType.VIEW, table);
            if (hit != null) {
                return hit;
            }
        }
        TreeItem<SchemaNode> direct = findChild(db, NodeType.TABLE, table);
        if (direct != null) {
            return direct;
        }
        return findChild(db, NodeType.VIEW, table);
    }

    // ---------------------------------------------------------------- tree model

    private void rebuildDataSources() {
        List<ConnectionProfile> profiles = profileManager.loadProfiles();
        List<ConnectionSession> live = sessionManager == null
                ? List.of()
                : sessionManager.connectedSessions();

        List<TreeItem<SchemaNode>> roots = new ArrayList<>();
        java.util.HashSet<String> matchedSessionIds = new java.util.HashSet<>();

        for (ConnectionProfile profile : profiles) {
            Optional<ConnectionSession> session = sessionManager == null
                    ? Optional.empty()
                    : sessionManager.findByProfileId(profile.id()).filter(ConnectionSession::isConnected);
            session.map(ConnectionSession::id).ifPresent(matchedSessionIds::add);
            boolean active = session.isPresent();
            SchemaNode node = dataSourceNode(profile, active, session.map(ConnectionSession::id).orElse(null));
            roots.add(new DataSourceItem(node, this::loadDataSourceChildren));
        }
        for (ConnectionSession session : live) {
            if (matchedSessionIds.contains(session.id())) {
                continue;
            }
            if (session.profileId().isPresent()) {
                // Profile was deleted but session still live — show as ephemeral.
            }
            roots.add(0, new DataSourceItem(sessionNode(session), this::loadDataSourceChildren));
        }

        allDataSources.clear();
        allDataSources.addAll(roots);
        if (live.isEmpty()) {
            schemaSelection.clearActive();
        }
        publishDataSources();
        updateEmptyHint();
        tree.setVisible(true);
        tree.setManaged(true);
    }

    private void publishDataSources() {
        String needle = filterQuery.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            for (TreeItem<SchemaNode> child : allDataSources) {
                if (child instanceof FilterableItem filterable) {
                    filterable.applyFilter("");
                }
            }
            tree.getRoot().getChildren().setAll(allDataSources);
            return;
        }
        List<TreeItem<SchemaNode>> visible = new ArrayList<>();
        for (TreeItem<SchemaNode> child : allDataSources) {
            if (child instanceof FilterableItem filterable) {
                filterable.applyFilter(needle);
                if (filterable.isVisibleUnderFilter()) {
                    visible.add(child);
                }
            }
        }
        tree.getRoot().getChildren().setAll(visible);
    }

    private void updateEmptyHint() {
        boolean empty = allDataSources.isEmpty();
        if (empty) {
            emptyHint.setText("No saved connections yet.\nUse + in the header and check Save.");
        }
        emptyHint.setVisible(empty && filterQuery.isBlank());
        emptyHint.setManaged(empty && filterQuery.isBlank());
    }

    private void applyFilter() {
        publishDataSources();
        updateEmptyHint();
    }

    private void loadDataSourceChildren(DataSourceItem item) {
        SchemaNode node = item.getValue();
        DataSourceDriver driver = driverFor(item);
        if (node == null || !node.metadataFlag(SchemaNode.META_ACTIVE) || driver == null || !driver.isConnected()) {
            item.replaceChildren(List.of(placeholderItem("Connect to browse schemas")));
            return;
        }
        if (isRedisDriver(driver, node)) {
            populateRedisDatabases(item, driver);
            return;
        }
        driver.getSchemaTree().whenComplete((nodes, error) -> Platform.runLater(() -> {
            applyLoadedChildren(item, driver, nodes, error, true);
        }));
    }

    /**
     * Logical Redis databases under the connection node. Keys load when a DB expands.
     */
    private void populateRedisDatabases(DataSourceItem item, DataSourceDriver driver) {
        driver.getSchemaTree().whenComplete((nodes, error) -> Platform.runLater(() -> {
            applyLoadedChildren(item, driver, nodes, error, false);
        }));
    }

    private void applyLoadedChildren(
            DataSourceItem item,
            DataSourceDriver driver,
            List<SchemaNode> nodes,
            Throwable error,
            boolean rememberSchemas) {
        if (error != null) {
            item.replaceChildren(List.of(placeholderItem(rootCauseMessage(error))));
        } else if (nodes == null || nodes.isEmpty()) {
            item.replaceChildren(List.of(placeholderItem("empty")));
        } else {
            if (rememberSchemas) {
                rememberAvailableSchemas(nodes, connectionIdOf(item.getValue()), driver);
            }
            List<TreeItem<SchemaNode>> children = new ArrayList<>();
            for (SchemaNode child : nodes) {
                children.add(schemaItem(child));
            }
            item.replaceChildren(children);
        }
        item.setExpanded(true);
        applyFilter();
    }

    private static boolean isRedisDriver(DataSourceDriver driver, SchemaNode dataSource) {
        if (driver instanceof RedisDriver) {
            return true;
        }
        if (driver != null && driver.currentConfig().map(c -> c.connectionType().isRedis()).orElse(false)) {
            return true;
        }
        return dataSource != null && "REDIS".equalsIgnoreCase(dataSource.metadata(SchemaNode.META_CONNECTION_TYPE));
    }

    private boolean isRedisTreeItem(TreeItem<SchemaNode> item) {
        TreeItem<SchemaNode> current = item;
        while (current != null) {
            SchemaNode node = current.getValue();
            if (node != null && node.type() == NodeType.DATA_SOURCE) {
                return isRedisDriver(driverFor(current), node);
            }
            current = current.getParent();
        }
        return false;
    }

    private void rememberAvailableSchemas(List<SchemaNode> nodes, String connectionId, DataSourceDriver driver) {
        List<String> names = new ArrayList<>();
        for (SchemaNode node : nodes) {
            if (node.type() == NodeType.DATABASE || node.type() == NodeType.SCHEMA) {
                names.add(node.name());
            }
        }
        String preferred = driver == null ? null : driver.activeCatalog().orElse(null);
        if ((preferred == null || preferred.isBlank()) && driver != null) {
            preferred = driver.currentConfig().map(ConnectionConfig::database).orElse(null);
        }
        schemaSelection.setAvailableSchemas(connectionId, names, preferred);
        tree.refresh();
    }

    private static String connectionIdOf(SchemaNode node) {
        if (node == null) {
            return null;
        }
        String profile = node.metadata(SchemaNode.META_PROFILE_ID);
        if (profile != null && !profile.isBlank()) {
            return profile;
        }
        return node.metadata("sessionId");
    }

    private String sessionIdOfSelection() {
        return sessionIdOf(tree.getSelectionModel().getSelectedItem());
    }

    private String sessionIdOf(TreeItem<SchemaNode> item) {
        TreeItem<SchemaNode> current = item;
        while (current != null) {
            SchemaNode node = current.getValue();
            if (node != null && node.type() == NodeType.DATA_SOURCE) {
                String sessionId = node.metadata("sessionId");
                if (sessionId != null && !sessionId.isBlank()) {
                    return sessionId;
                }
                String profileId = node.metadata(SchemaNode.META_PROFILE_ID);
                if (sessionManager != null && profileId != null) {
                    return sessionManager.findByProfileId(profileId).map(ConnectionSession::id).orElse(null);
                }
                return null;
            }
            current = current.getParent();
        }
        return null;
    }

    private DataSourceDriver driverFor(TreeItem<SchemaNode> item) {
        String sessionId = sessionIdOf(item);
        if (sessionManager == null || sessionId == null) {
            return null;
        }
        return sessionManager.find(sessionId).map(ConnectionSession::driver).orElse(null);
    }

    private SchemaCache cacheFor(TreeItem<SchemaNode> item) {
        String sessionId = sessionIdOf(item);
        if (sessionManager == null || sessionId == null) {
            return null;
        }
        return sessionManager.find(sessionId).map(ConnectionSession::schemaCache).orElse(null);
    }

    private LazyItem schemaItem(SchemaNode node) {
        return new LazyItem(node, this::loadSchemaChildren);
    }

    private void loadSchemaChildren(LazyItem item) {
        SchemaNode node = item.getValue();
        DataSourceDriver driver = driverFor(item);
        if (driver == null || !driver.isConnected()) {
            item.replaceChildren(List.of(placeholderItem("Not connected")));
            return;
        }
        // Folders/tables may already carry children from the parent JDBC round-trip.
        if (!node.children().isEmpty()) {
            item.presentSources(node.children());
            applyFilter();
            return;
        }
        SchemaCache cache = cacheFor(item);
        if (cache != null) {
            Optional<List<SchemaNode>> cached = cache.cachedChildren(node);
            if (cached.isPresent()) {
                item.presentSources(cached.get());
                applyFilter();
                return;
            }
        }
        driver.getChildren(node).whenComplete((children, error) -> Platform.runLater(() -> {
            if (error != null) {
                item.replaceChildren(List.of(placeholderItem(rootCauseMessage(error))));
            } else if (children == null || children.isEmpty()) {
                item.replaceChildren(List.of(placeholderItem("empty")));
            } else {
                item.presentSources(children);
            }
            applyFilter();
        }));
    }

    // ---------------------------------------------------------------- interactions

    private void handleDoubleClick(TreeItem<SchemaNode> selected) {
        SchemaNode node = selected.getValue();
        if (node.type() == NodeType.DATA_SOURCE) {
            if (node.metadataFlag(SchemaNode.META_ACTIVE)) {
                selected.setExpanded(!selected.isExpanded());
            } else {
                connectDataSource(node);
            }
            return;
        }
        if (node.type() == NodeType.REDIS_KEY) {
            openRedisKey(selected, node);
            return;
        }
        if (node.type() == NodeType.DATABASE || node.type() == NodeType.SCHEMA) {
            if (isRedisTreeItem(selected)) {
                selected.setExpanded(!selected.isExpanded());
                return;
            }
            onUseDatabase.accept(node);
        } else if (node.type() == NodeType.TABLE || node.type() == NodeType.VIEW) {
            onUseDatabase.accept(node);
            onOpenData.accept(node);
        } else {
            onActivate.accept(node);
        }
    }

    private void openRedisKey(TreeItem<SchemaNode> selected, SchemaNode node) {
        String key = node.metadata(SchemaNode.META_REDIS_KEY);
        if (key == null || key.isBlank()) {
            key = node.name();
        }
        String fullKey = key;
        int db = redisDbOf(selected, node);
        DataSourceDriver driver = driverFor(selected);
        if (driver instanceof RedisDriver redis) {
            redis.keyType(fullKey, db).whenComplete((type, error) -> Platform.runLater(() -> {
                String command = RedisReadCommands.forType(fullKey, error == null ? type : "string", db);
                onRunCommand.accept(command);
            }));
            return;
        }
        onRunCommand.accept(RedisReadCommands.forType(fullKey, "string", db));
    }

    private static int redisDbOf(TreeItem<SchemaNode> item, SchemaNode node) {
        if (node != null && node.metadata(SchemaNode.META_REDIS_DB) != null) {
            return RedisKeyspace.indexOf(node);
        }
        for (TreeItem<SchemaNode> cursor = item; cursor != null; cursor = cursor.getParent()) {
            SchemaNode value = cursor.getValue();
            if (value != null && value.metadata(SchemaNode.META_REDIS_DB) != null) {
                return RedisKeyspace.indexOf(value);
            }
        }
        return 0;
    }

    private void connectSelectedDataSource() {
        TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null) {
            connectDataSource(selected.getValue());
        }
    }

    private void connectDataSource(SchemaNode node) {
        if (node.type() != NodeType.DATA_SOURCE) {
            return;
        }
        String profileId = node.metadata(SchemaNode.META_PROFILE_ID);
        if (profileId == null || node.metadataFlag(SchemaNode.META_SESSION)) {
            onConnectRequested.run();
            return;
        }
        profileManager.loadProfiles().stream()
                .filter(profile -> profileId.equals(profile.id()))
                .findFirst()
                .ifPresentOrElse(onConnectProfile, onConnectRequested);
    }

    private TreeItem<SchemaNode> findActiveDataSourceItem() {
        if (sessionManager != null) {
            Optional<ConnectionSession> focused = sessionManager.focused();
            if (focused.isPresent()) {
                String id = focused.get().id();
                for (TreeItem<SchemaNode> child : tree.getRoot().getChildren()) {
                    if (child.getValue() != null && id.equals(child.getValue().metadata("sessionId"))) {
                        return child;
                    }
                }
            }
        }
        for (TreeItem<SchemaNode> child : tree.getRoot().getChildren()) {
            if (child.getValue() != null && child.getValue().metadataFlag(SchemaNode.META_ACTIVE)) {
                return child;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- node factories

    private static SchemaNode dataSourceNode(ConnectionProfile profile, boolean active, String sessionId) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(SchemaNode.META_PROFILE_ID, profile.id());
        meta.put(SchemaNode.META_ACTIVE, Boolean.toString(active));
        if (sessionId != null) {
            meta.put("sessionId", sessionId);
        }
        String user = profile.username().isBlank() ? "<anonymous>" : profile.username();
        String schema = profile.database().isBlank() ? "" : "/" + profile.database();
        meta.put("endpoint", "%s@%s:%d%s".formatted(user, profile.host(), profile.port(), schema));
        meta.put(SchemaNode.META_CONNECTION_TYPE, connectionTypeName(profile.driver()));
        meta.put(SchemaNode.META_DRIVER, profile.driver());
        return SchemaNode.of(profile.displayName(), NodeType.DATA_SOURCE, meta);
    }

    private static SchemaNode sessionNode(ConnectionSession session) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("sessionId", session.id());
        session.profileId().ifPresent(id -> meta.put(SchemaNode.META_PROFILE_ID, id));
        meta.put(SchemaNode.META_SESSION, "true");
        meta.put(SchemaNode.META_ACTIVE, "true");
        meta.put("endpoint", session.config().displayLabel());
        meta.put(SchemaNode.META_CONNECTION_TYPE, session.config().connectionType().name());
        meta.put(SchemaNode.META_DRIVER, session.config().driver().name());
        return SchemaNode.of(session.displayName(), NodeType.DATA_SOURCE, meta);
    }

    private static String connectionTypeName(String driverRaw) {
        if (driverRaw == null || driverRaw.isBlank()) {
            return ConnectionConfig.ConnectionType.MYSQL.name();
        }
        try {
            return ConnectionConfig.Driver.valueOf(driverRaw.trim().toUpperCase(Locale.ROOT))
                    .connectionType()
                    .name();
        } catch (IllegalArgumentException ignored) {
            return "redis".equalsIgnoreCase(driverRaw.trim())
                    ? ConnectionConfig.ConnectionType.REDIS.name()
                    : ConnectionConfig.ConnectionType.MYSQL.name();
        }
    }

    private static TreeItem<SchemaNode> placeholderItem(String text) {
        SchemaNode node = SchemaNode.of(text, NodeType.COLUMN, Map.of(META_PLACEHOLDER, "true"));
        return new TreeItem<>(node);
    }

    private static boolean isPlaceholder(SchemaNode node) {
        return node != null && node.metadataFlag(META_PLACEHOLDER);
    }

    private static boolean isShowMore(SchemaNode node) {
        return isPlaceholder(node) && node.metadataFlag(META_SHOW_MORE);
    }

    private TreeItem<SchemaNode> showMoreItem(int remaining, int matched) {
        SchemaNode node = SchemaNode.of(
                SchemaTreePaging.showMoreLabel(remaining, matched),
                NodeType.COLUMN,
                Map.of(META_PLACEHOLDER, "true", META_SHOW_MORE, "true"));
        return new TreeItem<>(node);
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    // ---------------------------------------------------------------- tree items

    private abstract static class FilterableItem extends TreeItem<SchemaNode> {
        protected final List<TreeItem<SchemaNode>> fullChildren = new ArrayList<>();
        protected boolean visibleUnderFilter = true;

        FilterableItem(SchemaNode value) {
            super(value);
        }

        void replaceChildren(List<TreeItem<SchemaNode>> children) {
            fullChildren.clear();
            fullChildren.addAll(children);
            getChildren().setAll(children);
        }

        boolean isVisibleUnderFilter() {
            return visibleUnderFilter;
        }

        abstract void applyFilter(String needle);
    }

    private final class DataSourceItem extends FilterableItem {
        private boolean loaded;
        private final Consumer<DataSourceItem> loader;

        DataSourceItem(SchemaNode value, Consumer<DataSourceItem> loader) {
            super(value);
            this.loader = loader;
            expandedProperty().addListener((observable, wasExpanded, isExpanded) -> {
                if (isExpanded && !loaded) {
                    loaded = true;
                    loader.accept(this);
                }
            });
        }

        @Override
        public boolean isLeaf() {
            return false;
        }

        @Override
        void applyFilter(String needle) {
            String text = needle == null ? "" : needle;
            boolean filteringText = !text.isBlank();
            boolean selfMatch = matchesNeedle(getValue(), text);
            boolean anyChild = false;
            List<TreeItem<SchemaNode>> shown = new ArrayList<>();
            for (TreeItem<SchemaNode> child : fullChildren) {
                if (!passesSchemaSelection(child)) {
                    continue;
                }
                if (!filteringText) {
                    if (child instanceof FilterableItem filterable) {
                        filterable.applyFilter("");
                    }
                    shown.add(child);
                    anyChild = true;
                    continue;
                }
                if (child instanceof FilterableItem filterable) {
                    filterable.applyFilter(text);
                    if (filterable.isVisibleUnderFilter()) {
                        shown.add(child);
                        anyChild = true;
                    }
                } else if (child.getValue() != null && matchesNeedle(child.getValue(), text)) {
                    shown.add(child);
                    anyChild = true;
                }
            }
            visibleUnderFilter = !filteringText || selfMatch || anyChild;
            if (loaded || !fullChildren.isEmpty()) {
                getChildren().setAll(shown);
            }
            if (filteringText && anyChild) {
                setExpanded(true);
            }
        }

        private boolean passesSchemaSelection(TreeItem<SchemaNode> child) {
            SchemaNode node = child.getValue();
            if (node == null || isPlaceholder(node)) {
                return true;
            }
            if ("REDIS".equalsIgnoreCase(getValue().metadata(SchemaNode.META_CONNECTION_TYPE))) {
                return true;
            }
            if (node.type() != NodeType.DATABASE && node.type() != NodeType.SCHEMA) {
                return true;
            }
            return schemaSelection.isSchemaVisible(connectionIdOf(getValue()), node.name());
        }
    }

    private final class LazyItem extends FilterableItem {
        private boolean loaded;
        private final Consumer<LazyItem> loader;
        private List<SchemaNode> sourceNodes = List.of();
        private int pageLimit = SchemaTreePaging.PAGE_SIZE;

        LazyItem(SchemaNode value, Consumer<LazyItem> loader) {
            super(value);
            this.loader = loader;
            expandedProperty().addListener((observable, wasExpanded, isExpanded) -> {
                if (isExpanded && !loaded) {
                    loaded = true;
                    loader.accept(this);
                }
            });
        }

        void presentSources(List<SchemaNode> nodes) {
            sourceNodes = nodes == null ? List.of() : List.copyOf(nodes);
            pageLimit = SchemaTreePaging.PAGE_SIZE;
            loaded = true;
            materialize(sliceNeedle(filterQuery));
        }

        void showMore() {
            pageLimit += SchemaTreePaging.PAGE_SIZE;
            materialize(sliceNeedle(filterQuery));
        }

        boolean ensureNamedChildVisible(NodeType type, String name) {
            int index = SchemaTreePaging.indexOf(sourceNodes, type, name);
            if (index < 0) {
                return false;
            }
            if (index >= pageLimit) {
                pageLimit = index + 1;
                materialize(sliceNeedle(filterQuery));
            }
            return true;
        }

        private String sliceNeedle(String needle) {
            return SchemaTreePaging.groupsByFolder(sourceNodes) ? "" : needle;
        }

        /**
         * Rebuild the visible page, but keep already-loaded child {@link LazyItem}s.
         * Parent {@code applyFilter()} used to call {@code schemaItem()} again, which
         * threw away the expanded tables folder and made expand look like a no-op.
         */
        private SchemaTreePaging.Page materialize(String needle) {
            SchemaTreePaging.Page page = SchemaTreePaging.slice(sourceNodes, needle, pageLimit);
            Map<String, TreeItem<SchemaNode>> existing = new LinkedHashMap<>();
            for (TreeItem<SchemaNode> child : getChildren()) {
                SchemaNode value = child.getValue();
                if (value == null || isPlaceholder(value)) {
                    continue;
                }
                existing.put(SchemaTreePaging.identityKey(value), child);
            }
            List<TreeItem<SchemaNode>> items = new ArrayList<>(page.visible().size() + 1);
            for (SchemaNode child : page.visible()) {
                TreeItem<SchemaNode> prior = existing.remove(SchemaTreePaging.identityKey(child));
                items.add(prior != null ? prior : schemaItem(child));
            }
            if (page.hasMore()) {
                items.add(showMoreItem(page.remaining(), page.matched()));
            }
            if (items.isEmpty()) {
                items.add(placeholderItem("empty"));
            }
            replaceChildren(items);
            return page;
        }

        void forceReload(Consumer<LazyItem> reload) {
            loaded = false;
            sourceNodes = List.of();
            pageLimit = SchemaTreePaging.PAGE_SIZE;
            fullChildren.clear();
            getChildren().clear();
            SchemaNode value = getValue();
            if (value != null && !value.children().isEmpty()) {
                setValue(value.withChildren(List.of()));
            }
            if (isExpanded()) {
                loaded = true;
                reload.accept(this);
            } else {
                setExpanded(true);
            }
        }

        @Override
        public boolean isLeaf() {
            return getValue().isLeaf();
        }

        @Override
        void applyFilter(String needle) {
            if (!sourceNodes.isEmpty()) {
                String text = needle == null ? "" : needle;
                boolean selfMatch = matchesNeedle(getValue(), text);
                boolean grouping = SchemaTreePaging.groupsByFolder(sourceNodes);
                if (!text.isBlank() && !grouping) {
                    pageLimit = SchemaTreePaging.PAGE_SIZE;
                }
                SchemaTreePaging.Page page = materialize(sliceNeedle(text));
                if (grouping) {
                    List<TreeItem<SchemaNode>> shown = new ArrayList<>();
                    boolean anyChild = false;
                    for (TreeItem<SchemaNode> child : List.copyOf(getChildren())) {
                        if (!(child instanceof FilterableItem filterable) || isPlaceholder(child.getValue())) {
                            continue;
                        }
                        filterable.applyFilter(text);
                        if (text.isBlank() || filterable.isVisibleUnderFilter()) {
                            shown.add(child);
                            anyChild = anyChild || filterable.isVisibleUnderFilter();
                        }
                    }
                    if (!text.isBlank()) {
                        getChildren().setAll(shown.isEmpty() && selfMatch ? List.copyOf(fullChildren) : shown);
                    }
                    visibleUnderFilter = text.isBlank() || selfMatch || anyChild;
                    if (!text.isBlank() && anyChild) {
                        setExpanded(true);
                    }
                    return;
                }
                for (TreeItem<SchemaNode> child : getChildren()) {
                    if (child instanceof FilterableItem filterable && !isPlaceholder(child.getValue())) {
                        filterable.applyFilter(text);
                    }
                }
                visibleUnderFilter = text.isBlank() || selfMatch || page.matched() > 0;
                if (!text.isBlank() && page.matched() > 0) {
                    setExpanded(true);
                }
                return;
            }
            if (needle == null || needle.isBlank()) {
                visibleUnderFilter = true;
                if (!fullChildren.isEmpty()) {
                    getChildren().setAll(fullChildren);
                }
                for (TreeItem<SchemaNode> child : fullChildren) {
                    if (child instanceof FilterableItem filterable) {
                        filterable.applyFilter("");
                    }
                }
                return;
            }
            boolean selfMatch = matchesNeedle(getValue(), needle);
            List<TreeItem<SchemaNode>> shown = new ArrayList<>();
            boolean anyChild = false;
            for (TreeItem<SchemaNode> child : fullChildren) {
                if (child instanceof FilterableItem filterable) {
                    filterable.applyFilter(needle);
                    if (filterable.isVisibleUnderFilter()) {
                        shown.add(child);
                        anyChild = true;
                    }
                } else if (child.getValue() != null && matchesNeedle(child.getValue(), needle)) {
                    shown.add(child);
                    anyChild = true;
                }
            }
            visibleUnderFilter = selfMatch || anyChild;
            if (loaded || !fullChildren.isEmpty()) {
                getChildren().setAll(selfMatch && shown.isEmpty() ? fullChildren : shown);
                if (selfMatch && shown.isEmpty() && !fullChildren.isEmpty()) {
                    getChildren().setAll(fullChildren);
                }
            }
            if (anyChild) {
                setExpanded(true);
            }
        }
    }

    private static boolean matchesNeedle(SchemaNode node, String needle) {
        if (node == null || isPlaceholder(node)) {
            return false;
        }
        if (node.name().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String redisKey = node.metadata(SchemaNode.META_REDIS_KEY);
        if (redisKey != null && redisKey.toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        String endpoint = node.metadata("endpoint");
        return endpoint != null && endpoint.toLowerCase(Locale.ROOT).contains(needle);
    }

    private final class SchemaTreeCell extends TreeCell<SchemaNode> {

        private static final double DISCLOSURE_RESERVE = 28;

        private static final double ICON_SIZE = 14;

        private final SchemaSelectionControl selection;
        private final Label nameLabel = new Label();
        private final Label detailLabel = new Label();
        private final Label activeBadge = new Label("●");
        private final Label schemaBadge = new Label();
        private final Region spacer = new Region();
        private final HBox row = new HBox(6);
        private final StackPane iconSlot = new StackPane();
        private final ProgressIndicator busySpinner = new ProgressIndicator();

        SchemaTreeCell(SchemaSelectionControl selection) {
            this.selection = selection;
            nameLabel.getStyleClass().add("schema-node-name");
            nameLabel.setMinWidth(Region.USE_PREF_SIZE);
            detailLabel.getStyleClass().add("schema-node-detail");
            detailLabel.setMinWidth(0);
            detailLabel.setMaxWidth(Double.MAX_VALUE);
            detailLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            HBox.setHgrow(detailLabel, Priority.SOMETIMES);
            activeBadge.getStyleClass().add("schema-active-badge");
            schemaBadge.getStyleClass().add("schema-count-badge");
            schemaBadge.setMinWidth(Region.USE_PREF_SIZE);
            schemaBadge.setMaxWidth(Region.USE_PREF_SIZE);
            schemaBadge.setTooltip(new Tooltip("Choose which schemas appear under this connection"));
            schemaBadge.setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                SchemaNode node = getItem();
                if (node == null || node.type() != NodeType.DATA_SOURCE) {
                    return;
                }
                selection.showFor(schemaBadge, connectionIdOf(node));
                event.consume();
            });
            busySpinner.getStyleClass().add("schema-busy-spinner");
            busySpinner.setMinSize(ICON_SIZE, ICON_SIZE);
            busySpinner.setPrefSize(ICON_SIZE, ICON_SIZE);
            busySpinner.setMaxSize(ICON_SIZE, ICON_SIZE);
            iconSlot.setMinSize(ICON_SIZE, ICON_SIZE);
            iconSlot.setPrefSize(ICON_SIZE, ICON_SIZE);
            iconSlot.setMaxSize(ICON_SIZE, ICON_SIZE);
            iconSlot.setMouseTransparent(true);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            spacer.setMinWidth(0);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setMinWidth(0);
            row.getStyleClass().add("tree-cell-graphic");
            row.maxWidthProperty().bind(Bindings.max(0, widthProperty().subtract(DISCLOSURE_RESERVE)));
        }

        @Override
        protected void updateItem(SchemaNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("schema-placeholder", "schema-data-source", "schema-data-source-active");

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }

            if (isPlaceholder(item)) {
                getStyleClass().add("schema-placeholder");
                setText(item.name());
                setGraphic(null);
                setTooltip(null);
                return;
            }

            nameLabel.setText(item.name());
            detailLabel.setText(detailOf(item));
            detailLabel.getStyleClass().removeAll("schema-node-muted", "schema-node-detail");
            detailLabel.getStyleClass().add(
                    item.type() == NodeType.FOLDER || item.type() == NodeType.COLUMN
                            || item.type() == NodeType.KEY || item.type() == NodeType.INDEX
                            || item.type() == NodeType.REDIS_KEY
                            ? "schema-node-muted" : "schema-node-detail");
            boolean showDetail = !detailLabel.getText().isEmpty();
            detailLabel.setVisible(showDetail);
            detailLabel.setManaged(showDetail);

            boolean dataSource = item.type() == NodeType.DATA_SOURCE;
            boolean active = dataSource && item.metadataFlag(SchemaNode.META_ACTIVE);
            if (dataSource) {
                getStyleClass().add("schema-data-source");
            }
            if (active) {
                getStyleClass().add("schema-data-source-active");
            }
            activeBadge.setVisible(active);
            activeBadge.setManaged(active);

            String connectionId = connectionIdOf(item);
            boolean showSchemaBadge = dataSource && selection.hasSchemas(connectionId);
            if (showSchemaBadge) {
                schemaBadge.setText(selection.countLabel(connectionId));
            }

            row.getChildren().clear();
            iconSlot.getChildren().setAll(dataSource && isBusy(item) ? busySpinner : Icons.forNode(item));
            row.getChildren().add(iconSlot);
            row.getChildren().add(nameLabel);
            if (active) {
                row.getChildren().add(activeBadge);
            }
            if (showDetail) {
                row.getChildren().add(detailLabel);
            }
            row.getChildren().add(spacer);
            if (showSchemaBadge) {
                row.getChildren().add(schemaBadge);
            }

            setText(null);
            setGraphic(row);
            applyNodeTooltip(item);
        }

        private void applyNodeTooltip(SchemaNode node) {
            String text = tooltipOf(node);
            if (text == null || text.isBlank()) {
                setTooltip(null);
                return;
            }
            Tooltip tip = getTooltip();
            if (tip == null) {
                tip = new Tooltip();
                tip.setWrapText(true);
                setTooltip(tip);
            }
            tip.setMaxWidth(text.contains("\n") ? 560 : 360);
            tip.setText(text);
        }

        private static String tooltipOf(SchemaNode node) {
            if (node == null) {
                return null;
            }
            return switch (node.type()) {
                case DATA_SOURCE -> {
                    String endpoint = node.metadata("endpoint");
                    yield endpoint == null || endpoint.isBlank() ? null : endpoint;
                }
                case PROCEDURE -> {
                    String ddl = node.metadata(SchemaNode.META_DDL);
                    if (ddl != null && !ddl.isBlank()) {
                        yield ddl;
                    }
                    boolean function = SchemaNode.ROUTINE_FUNCTION.equalsIgnoreCase(
                            node.metadata(SchemaNode.META_ROUTINE_KIND));
                    String kind = function ? "Stored function" : "Stored procedure";
                    String catalog = node.metadata(SchemaNode.META_CATALOG);
                    yield catalog == null || catalog.isBlank() ? kind : kind + " in " + catalog;
                }
                case FOLDER -> switch (Objects.requireNonNullElse(node.folderKind(), "")) {
                    case SchemaNode.FOLDER_PROCEDURES -> "Stored procedures in this database";
                    case SchemaNode.FOLDER_REDIS -> "Redis key namespace";
                    default -> null;
                };
                case DATABASE, SCHEMA -> node.metadata(SchemaNode.META_REDIS_DB) == null
                        ? null
                        : "Redis logical database " + node.metadata(SchemaNode.META_REDIS_DB);
                case REDIS_KEY -> {
                    String key = node.metadata(SchemaNode.META_REDIS_KEY);
                    yield key == null || key.isBlank() ? node.name() : key;
                }
                default -> null;
            };
        }

        private static String detailOf(SchemaNode node) {
            return switch (node.type()) {
                case DATA_SOURCE -> "";
                case FOLDER -> {
                    int count = node.childCountBadge();
                    yield count > 0 || node.metadata(SchemaNode.META_CHILD_COUNT) != null
                            ? Integer.toString(count) : "";
                }
                case COLUMN -> Objects.requireNonNullElse(node.metadata(SchemaNode.META_DATA_TYPE), "");
                case KEY -> {
                    String columns = node.metadata(SchemaNode.META_COLUMNS);
                    yield columns == null || columns.isBlank() ? "" : "(" + columns + ")";
                }
                case INDEX -> {
                    String columns = node.metadata(SchemaNode.META_COLUMNS);
                    String cols = columns == null || columns.isBlank() ? "" : "(" + columns + ")";
                    if (node.metadataFlag(SchemaNode.META_UNIQUE)) {
                        yield cols.isEmpty() ? "UNIQUE" : cols + " UNIQUE";
                    }
                    yield cols;
                }
                case VIEW -> "view";
                case DATABASE, SCHEMA -> {
                    if (node.metadata(SchemaNode.META_REDIS_DB) == null) {
                        yield "";
                    }
                    String count = node.metadata(SchemaNode.META_CHILD_COUNT);
                    if (count == null || count.isBlank()) {
                        yield "";
                    }
                    yield count + ("1".equals(count) ? " key" : " keys");
                }
                case PROCEDURE -> {
                    String kind = node.metadata(SchemaNode.META_ROUTINE_KIND);
                    yield SchemaNode.ROUTINE_FUNCTION.equalsIgnoreCase(kind) ? "function" : "procedure";
                }
                case REDIS_KEY -> Objects.requireNonNullElse(node.metadata(SchemaNode.META_REDIS_TYPE), "");
                default -> "";
            };
        }
    }
}
