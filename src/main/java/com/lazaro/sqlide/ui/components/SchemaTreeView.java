package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.config.ConnectionProfile;
import com.lazaro.sqlide.core.config.ConnectionProfileManager;
import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
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
 * schema under the active source, filter box, and generate/copy actions.
 */
public final class SchemaTreeView extends VBox {

    private static final String META_PLACEHOLDER = "__placeholder";
    private static final String SESSION_ID = "__session__";

    private final TreeView<SchemaNode> tree = new TreeView<>();
    private final TextField filterField = new TextField();
    private final SchemaSelectionControl schemaSelection = new SchemaSelectionControl();
    private final Label headerLabel = new Label("DATABASE");
    private final StackPane body = new StackPane();
    private final VBox loadingState = new VBox(10);
    private final Label emptyHint = new Label();
    private final Label loadingLabel = new Label("Loading schemas\u2026");
    private final ProgressIndicator loadingSpinner = new ProgressIndicator();
    private final Button newConnectionButton = new Button("New Connection");

    private ConnectionProfileManager profileManager = new ConnectionProfileManager();
    private DataSourceDriver driver;
    private String filterQuery = "";
    private final List<TreeItem<SchemaNode>> allDataSources = new ArrayList<>();

    private Consumer<SchemaNode> onActivate = node -> { };
    private Consumer<SchemaNode> onViewObject = node -> { };
    private Consumer<SchemaNode> onUseDatabase = node -> { };
    private Runnable onConnectRequested = () -> { };
    private Consumer<ConnectionProfile> onConnectProfile = profile -> { };
    private Consumer<ConnectionProfile> onDeleteProfile = profile -> { };
    private Consumer<String> onInsertSql = sql -> { };

    public SchemaTreeView() {
        getStyleClass().add("schema-tree-pane");
        setMinWidth(170);

        headerLabel.getStyleClass().add("panel-header");
        HBox header = new HBox(headerLabel);
        header.getStyleClass().add("panel-header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerLabel, Priority.ALWAYS);

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

        schemaSelection.setOnSelectionChanged(selection -> applyFilter());
        HBox.setHgrow(schemaSelection, Priority.NEVER);
        schemaSelection.setMinWidth(72);
        schemaSelection.setPrefWidth(88);
        HBox.setHgrow(filterField, Priority.ALWAYS);
        HBox filterBar = new HBox(6, schemaSelection, filterField);
        filterBar.setAlignment(Pos.CENTER_LEFT);
        filterBar.setPadding(new Insets(0, 8, 6, 8));

        tree.setShowRoot(false);
        tree.getStyleClass().add("schema-tree");
        tree.setCellFactory(view -> new SchemaTreeCell());
        tree.setRoot(new TreeItem<>(SchemaNode.of("root", NodeType.DATABASE)));
        tree.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
                return;
            }
            TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
            if (selected == null || selected.getValue() == null || isPlaceholder(selected.getValue())) {
                return;
            }
            handleDoubleClick(selected);
        });
        tree.setContextMenu(buildContextMenu());

        emptyHint.getStyleClass().add("empty-state-detail");
        emptyHint.setWrapText(true);
        emptyHint.setPadding(new Insets(12));
        emptyHint.setMouseTransparent(true);

        newConnectionButton.getStyleClass().add("empty-state-action");
        newConnectionButton.setMaxWidth(Double.MAX_VALUE);
        newConnectionButton.setOnAction(event -> onConnectRequested.run());
        VBox footer = new VBox(newConnectionButton);
        footer.setPadding(new Insets(8, 12, 12, 12));

        buildLoadingState();

        body.getChildren().addAll(tree, emptyHint, loadingState);
        StackPane.setAlignment(emptyHint, Pos.CENTER);
        StackPane.setAlignment(loadingState, Pos.CENTER);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, filterBar, body, footer);
        rebuildDataSources();
    }

    private void buildLoadingState() {
        loadingSpinner.setMaxSize(22, 22);
        loadingLabel.getStyleClass().add("empty-state-detail");
        loadingState.getStyleClass().addAll("empty-state", "loading-state");
        loadingState.setAlignment(Pos.CENTER);
        loadingState.getChildren().addAll(loadingSpinner, loadingLabel);
        loadingState.setVisible(false);
        loadingState.setManaged(false);
    }

    private ContextMenu buildContextMenu() {
        MenuItem connect = new MenuItem("Connect\u2026");
        connect.setOnAction(event -> connectSelectedDataSource());

        MenuItem delete = new MenuItem("Delete");
        delete.setOnAction(event -> deleteSelectedDataSource());

        MenuItem newConnection = new MenuItem("New Connection\u2026");
        newConnection.setOnAction(event -> onConnectRequested.run());

        MenuItem useDatabase = new MenuItem("Use database");
        useDatabase.setOnAction(event -> useSelectedDatabase());

        MenuItem insertName = new MenuItem("Insert name");
        insertName.setOnAction(event -> activateSelection());

        MenuItem copyName = new MenuItem("Copy Name");
        copyName.setOnAction(event -> copyText(selectedNodeName()));

        MenuItem copyQualified = new MenuItem("Copy Qualified Name");
        copyQualified.setOnAction(event -> {
            TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
            if (selected != null) {
                copyText(SchemaObjectNames.qualifiedName(selected));
            }
        });

        MenuItem generateSelect = new MenuItem("Generate SELECT");
        generateSelect.setOnAction(event -> {
            TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
            String sql = SchemaObjectNames.generateSelect(selected);
            if (sql != null && !sql.isBlank()) {
                onInsertSql.accept(sql);
            }
        });

        MenuItem viewProperties = new MenuItem("View DDL / Properties");
        viewProperties.setOnAction(event -> viewSelectedObject());

        ContextMenu menu = new ContextMenu(
                connect, delete, new SeparatorMenuItem(),
                useDatabase, insertName, copyName, copyQualified, generateSelect,
                new SeparatorMenuItem(), viewProperties, new SeparatorMenuItem(), newConnection);

        menu.setOnShowing(event -> {
            TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
            SchemaNode value = selected == null ? null : selected.getValue();
            boolean placeholder = value == null || isPlaceholder(value);
            boolean dataSource = value != null && value.type() == NodeType.DATA_SOURCE;
            boolean activeDs = dataSource && value.metadataFlag(SchemaNode.META_ACTIVE);
            boolean hasProfile = dataSource && value.metadata(SchemaNode.META_PROFILE_ID) != null
                    && !SESSION_ID.equals(value.metadata(SchemaNode.META_PROFILE_ID));
            boolean usable = !placeholder && value != null
                    && (value.type() == NodeType.DATABASE || value.type() == NodeType.SCHEMA
                    || value.type() == NodeType.TABLE || value.type() == NodeType.VIEW);
            boolean object = value != null
                    && (value.type() == NodeType.TABLE || value.type() == NodeType.VIEW);
            boolean selectable = !placeholder && value != null && value.type() != NodeType.DATA_SOURCE;
            boolean canSelect = SchemaObjectNames.generateSelect(selected) != null;

            connect.setDisable(!dataSource || activeDs);
            delete.setDisable(!hasProfile);
            useDatabase.setDisable(!usable);
            insertName.setDisable(!selectable);
            copyName.setDisable(placeholder);
            copyQualified.setDisable(!selectable);
            generateSelect.setDisable(!canSelect);
            viewProperties.setDisable(!object);
        });
        return menu;
    }

    // ---------------------------------------------------------------- public API

    public void setDriver(DataSourceDriver driver) {
        this.driver = driver;
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

    public void setOnUseDatabase(Consumer<SchemaNode> onUseDatabase) {
        this.onUseDatabase = onUseDatabase == null ? node -> { } : onUseDatabase;
    }

    /** Inserts generated SQL into the active editor. */
    public void setOnInsertSql(Consumer<String> onInsertSql) {
        this.onInsertSql = onInsertSql == null ? sql -> { } : onInsertSql;
    }

    public void refreshSavedConnections() {
        rebuildDataSources();
    }

    /** Reloads live schema under the active data source. */
    public void reload() {
        rebuildDataSources();
        if (driver == null || !driver.isConnected()) {
            return;
        }
        TreeItem<SchemaNode> active = findActiveDataSourceItem();
        if (active == null) {
            return;
        }
        showLoadingOverlay(true);
        driver.getSchemaTree().whenComplete((nodes, error) -> Platform.runLater(() -> {
            showLoadingOverlay(false);
            if (!(active instanceof DataSourceItem dataSourceItem)) {
                return;
            }
            if (error != null) {
                dataSourceItem.replaceChildren(List.of(placeholderItem("Could not load: " + rootCauseMessage(error))));
                dataSourceItem.setExpanded(true);
                return;
            }
            if (nodes == null || nodes.isEmpty()) {
                dataSourceItem.replaceChildren(List.of(placeholderItem("No databases visible")));
                schemaSelection.clear();
            } else {
                rememberAvailableSchemas(nodes);
                List<TreeItem<SchemaNode>> children = new ArrayList<>();
                for (SchemaNode node : nodes) {
                    children.add(schemaItem(node));
                }
                dataSourceItem.replaceChildren(children);
            }
            dataSourceItem.setExpanded(true);
            applyFilter();
            updateEmptyHint();
        }));
    }

    public void clear() {
        rebuildDataSources();
    }

    // ---------------------------------------------------------------- tree model

    private void rebuildDataSources() {
        List<ConnectionProfile> profiles = profileManager.loadProfiles();
        Optional<ConnectionConfig> config = driver == null
                ? Optional.empty()
                : driver.currentConfig().filter(ignored -> driver.isConnected());

        List<TreeItem<SchemaNode>> roots = new ArrayList<>();
        String matchedId = null;
        for (ConnectionProfile profile : profiles) {
            boolean active = config.isPresent() && matches(profile, config.get());
            if (active) {
                matchedId = profile.id();
            }
            roots.add(new DataSourceItem(dataSourceNode(profile, active), this::loadDataSourceChildren));
        }
        if (config.isPresent() && matchedId == null) {
            roots.add(0, new DataSourceItem(sessionNode(config.get()), this::loadDataSourceChildren));
        }

        allDataSources.clear();
        allDataSources.addAll(roots);
        if (config.isEmpty()) {
            schemaSelection.clear();
        }
        publishDataSources();
        updateEmptyHint();
        showLoadingOverlay(false);
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
            emptyHint.setText("No saved connections yet.\nUse New Connection and check Save.");
        }
        emptyHint.setVisible(empty && filterQuery.isBlank());
        emptyHint.setManaged(empty && filterQuery.isBlank());
    }

    private void showLoadingOverlay(boolean loading) {
        loadingState.setVisible(loading);
        loadingState.setManaged(loading);
    }

    private void applyFilter() {
        publishDataSources();
        updateEmptyHint();
    }

    private void loadDataSourceChildren(DataSourceItem item) {
        SchemaNode node = item.getValue();
        if (node == null || !node.metadataFlag(SchemaNode.META_ACTIVE)
                || driver == null || !driver.isConnected()) {
            item.replaceChildren(List.of(placeholderItem("Connect to browse schemas")));
            return;
        }
        item.replaceChildren(List.of(placeholderItem("Loading\u2026")));
        driver.getSchemaTree().whenComplete((nodes, error) -> Platform.runLater(() -> {
            if (error != null) {
                item.replaceChildren(List.of(placeholderItem(rootCauseMessage(error))));
            } else if (nodes == null || nodes.isEmpty()) {
                item.replaceChildren(List.of(placeholderItem("empty")));
                schemaSelection.clear();
            } else {
                rememberAvailableSchemas(nodes);
                List<TreeItem<SchemaNode>> children = new ArrayList<>();
                for (SchemaNode child : nodes) {
                    children.add(schemaItem(child));
                }
                item.replaceChildren(children);
            }
            applyFilter();
        }));
    }

    private void rememberAvailableSchemas(List<SchemaNode> nodes) {
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
        schemaSelection.setAvailableSchemas(names, preferred);
    }

    private LazyItem schemaItem(SchemaNode node) {
        return new LazyItem(node, this::loadSchemaChildren);
    }

    private void loadSchemaChildren(LazyItem item) {
        SchemaNode node = item.getValue();
        if (driver == null || !driver.isConnected()) {
            item.replaceChildren(List.of(placeholderItem("Not connected")));
            return;
        }
        item.replaceChildren(List.of(placeholderItem("Loading\u2026")));
        driver.getChildren(node).whenComplete((children, error) -> Platform.runLater(() -> {
            if (error != null) {
                item.replaceChildren(List.of(placeholderItem(rootCauseMessage(error))));
            } else if (children.isEmpty()) {
                item.replaceChildren(List.of(placeholderItem("empty")));
            } else {
                List<TreeItem<SchemaNode>> items = new ArrayList<>();
                for (SchemaNode child : children) {
                    items.add(schemaItem(child));
                }
                item.replaceChildren(items);
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
        if (node.type() == NodeType.DATABASE || node.type() == NodeType.SCHEMA) {
            onUseDatabase.accept(node);
        } else if (node.type() == NodeType.TABLE || node.type() == NodeType.VIEW) {
            onUseDatabase.accept(node);
            onViewObject.accept(node);
        } else {
            onActivate.accept(node);
        }
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
        if (profileId == null || SESSION_ID.equals(profileId)) {
            onConnectRequested.run();
            return;
        }
        profileManager.loadProfiles().stream()
                .filter(profile -> profileId.equals(profile.id()))
                .findFirst()
                .ifPresentOrElse(onConnectProfile, onConnectRequested);
    }

    private void deleteSelectedDataSource() {
        TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected == null || selected.getValue() == null) {
            return;
        }
        String profileId = selected.getValue().metadata(SchemaNode.META_PROFILE_ID);
        if (profileId == null || SESSION_ID.equals(profileId)) {
            return;
        }
        profileManager.loadProfiles().stream()
                .filter(profile -> profileId.equals(profile.id()))
                .findFirst()
                .ifPresent(onDeleteProfile);
    }

    private void activateSelection() {
        TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null && !isPlaceholder(selected.getValue())
                && selected.getValue().type() != NodeType.DATA_SOURCE) {
            onActivate.accept(selected.getValue());
        }
    }

    private void viewSelectedObject() {
        TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null
                && (selected.getValue().type() == NodeType.TABLE || selected.getValue().type() == NodeType.VIEW)) {
            onViewObject.accept(selected.getValue());
        }
    }

    private void useSelectedDatabase() {
        TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null && !isPlaceholder(selected.getValue())) {
            onUseDatabase.accept(selected.getValue());
        }
    }

    private String selectedNodeName() {
        TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
        return selected == null || selected.getValue() == null ? "" : selected.getValue().name();
    }

    private static void copyText(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private TreeItem<SchemaNode> findActiveDataSourceItem() {
        for (TreeItem<SchemaNode> child : tree.getRoot().getChildren()) {
            if (child.getValue() != null && child.getValue().metadataFlag(SchemaNode.META_ACTIVE)) {
                return child;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- node factories

    private static SchemaNode dataSourceNode(ConnectionProfile profile, boolean active) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(SchemaNode.META_PROFILE_ID, profile.id());
        meta.put(SchemaNode.META_ACTIVE, Boolean.toString(active));
        String user = profile.username().isBlank() ? "<anonymous>" : profile.username();
        String schema = profile.database().isBlank() ? "" : "/" + profile.database();
        meta.put("endpoint", "%s@%s:%d%s".formatted(user, profile.host(), profile.port(), schema));
        return SchemaNode.of(profile.displayName(), NodeType.DATA_SOURCE, meta);
    }

    private static SchemaNode sessionNode(ConnectionConfig config) {
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put(SchemaNode.META_PROFILE_ID, SESSION_ID);
        meta.put(SchemaNode.META_SESSION, "true");
        meta.put(SchemaNode.META_ACTIVE, "true");
        meta.put("endpoint", config.displayLabel());
        return SchemaNode.of("Current session", NodeType.DATA_SOURCE, meta);
    }

    private static boolean matches(ConnectionProfile profile, ConnectionConfig config) {
        if (config == null) {
            return false;
        }
        if (!profile.host().equalsIgnoreCase(config.host())) {
            return false;
        }
        if (profile.port() != config.port()) {
            return false;
        }
        if (!profile.username().equals(config.user())) {
            return false;
        }
        if (!profile.driver().equalsIgnoreCase(config.driver().name())
                && !profile.driver().equalsIgnoreCase(config.driver().displayName())) {
            return false;
        }
        // Database is optional on profiles; when both set, require equality.
        if (!profile.database().isBlank() && !config.database().isBlank()
                && !profile.database().equalsIgnoreCase(config.database())) {
            return false;
        }
        return true;
    }

    private static TreeItem<SchemaNode> placeholderItem(String text) {
        SchemaNode node = SchemaNode.of(text, NodeType.COLUMN, Map.of(META_PLACEHOLDER, "true"));
        return new TreeItem<>(node);
    }

    private static boolean isPlaceholder(SchemaNode node) {
        return node.metadataFlag(META_PLACEHOLDER);
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
            if (node.type() != NodeType.DATABASE && node.type() != NodeType.SCHEMA) {
                return true;
            }
            return schemaSelection.isSchemaVisible(node.name());
        }
    }

    private final class LazyItem extends FilterableItem {
        private boolean loaded;
        private final Consumer<LazyItem> loader;

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

        @Override
        public boolean isLeaf() {
            return getValue().isLeaf();
        }

        @Override
        void applyFilter(String needle) {
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
                // If only self matches and children loaded, keep all children visible when name matches.
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
        String endpoint = node.metadata("endpoint");
        return endpoint != null && endpoint.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static final class SchemaTreeCell extends TreeCell<SchemaNode> {

        private final Label nameLabel = new Label();
        private final Label detailLabel = new Label();
        private final Label activeBadge = new Label("●");
        private final HBox layout = new HBox(6);

        SchemaTreeCell() {
            nameLabel.getStyleClass().add("schema-node-name");
            detailLabel.getStyleClass().add("schema-node-detail");
            activeBadge.getStyleClass().add("schema-active-badge");
            layout.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(SchemaNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().removeAll("schema-placeholder", "schema-data-source", "schema-data-source-active");

            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }

            if (isPlaceholder(item)) {
                getStyleClass().add("schema-placeholder");
                setText(item.name());
                setGraphic(null);
                return;
            }

            nameLabel.setText(item.name());
            detailLabel.setText(detailOf(item));
            detailLabel.setVisible(!detailLabel.getText().isEmpty());
            detailLabel.setManaged(detailLabel.isVisible());

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

            layout.getChildren().setAll(Icons.forNode(item), nameLabel);
            if (active) {
                layout.getChildren().add(activeBadge);
            }
            if (detailLabel.isVisible()) {
                layout.getChildren().add(detailLabel);
            }
            setText(null);
            setGraphic(layout);
        }

        private static String detailOf(SchemaNode node) {
            return switch (node.type()) {
                case DATA_SOURCE -> Objects.requireNonNullElse(node.metadata("endpoint"), "");
                case COLUMN -> {
                    String type = node.metadata(SchemaNode.META_DATA_TYPE);
                    yield type == null ? "" : type;
                }
                case VIEW -> "view";
                default -> "";
            };
        }
    }
}
