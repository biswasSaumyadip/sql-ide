package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.config.ConnectionProfile;
import com.lazaro.sqlide.core.config.ConnectionProfileManager;
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
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Schema explorer: catalogs, then tables and views, then columns.
 *
 * <p>When disconnected, shows IntelliJ-style <em>saved data sources</em> from
 * {@link ConnectionProfileManager} so previously added connections stay visible.
 * Levels are fetched only when a live schema node is first expanded.
 */
public final class SchemaTreeView extends VBox {

    /** Marks synthetic rows that stand in for a pending or failed fetch. */
    private static final String META_PLACEHOLDER = "__placeholder";

    private final TreeView<SchemaNode> tree = new TreeView<>();
    private final ListView<ConnectionProfile> savedList = new ListView<>();
    private final Label headerLabel = new Label("DATABASE");
    private final StackPane body = new StackPane();
    private final VBox disconnectedPane = new VBox(0);
    private final VBox loadingState = new VBox(10);
    private final Label savedHeader = new Label("Saved connections");
    private final Label emptyProfilesHint = new Label("No saved connections yet. Use New Connection and check Save.");
    private final Label loadingLabel = new Label("Loading schemas\u2026");
    private final ProgressIndicator loadingSpinner = new ProgressIndicator();
    private final Button newConnectionButton = new Button("New Connection");

    private ConnectionProfileManager profileManager = new ConnectionProfileManager();
    private DataSourceDriver driver;
    private Consumer<SchemaNode> onActivate = node -> { };
    private Consumer<SchemaNode> onViewObject = node -> { };
    private Consumer<SchemaNode> onUseDatabase = node -> { };
    private Runnable onConnectRequested = () -> { };
    private Consumer<ConnectionProfile> onConnectProfile = profile -> { };
    private Consumer<ConnectionProfile> onDeleteProfile = profile -> { };

    public SchemaTreeView() {
        getStyleClass().add("schema-tree-pane");
        setMinWidth(170);

        headerLabel.getStyleClass().add("panel-header");
        HBox header = new HBox(headerLabel);
        header.getStyleClass().add("panel-header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerLabel, Priority.ALWAYS);

        tree.setShowRoot(false);
        tree.getStyleClass().add("schema-tree");
        tree.setCellFactory(view -> new SchemaTreeCell());
        tree.setRoot(new TreeItem<>(SchemaNode.of("root", NodeType.DATABASE)));
        tree.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null && !isPlaceholder(selected.getValue())) {
                    SchemaNode node = selected.getValue();
                    if (node.type() == NodeType.DATABASE || node.type() == NodeType.SCHEMA) {
                        onUseDatabase.accept(node);
                    } else if (node.type() == NodeType.TABLE || node.type() == NodeType.VIEW) {
                        onUseDatabase.accept(node);
                        onViewObject.accept(node);
                    } else {
                        onActivate.accept(node);
                    }
                }
            }
        });

        MenuItem useDatabase = new MenuItem("Use database");
        useDatabase.setOnAction(event -> useSelectedDatabase());
        MenuItem insertName = new MenuItem("Insert name");
        insertName.setOnAction(event -> activateSelection());
        MenuItem viewProperties = new MenuItem("View DDL / Properties");
        viewProperties.setOnAction(event -> viewSelectedObject());
        ContextMenu menu = new ContextMenu(useDatabase, insertName, viewProperties);
        menu.setOnShowing(event -> {
            TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
            SchemaNode value = selected == null ? null : selected.getValue();
            boolean usable = value != null && !isPlaceholder(value)
                    && (value.type() == NodeType.DATABASE || value.type() == NodeType.SCHEMA
                    || value.type() == NodeType.TABLE || value.type() == NodeType.VIEW);
            boolean object = value != null
                    && (value.type() == NodeType.TABLE || value.type() == NodeType.VIEW);
            useDatabase.setDisable(!usable);
            viewProperties.setDisable(!object);
            insertName.setDisable(value == null || isPlaceholder(value));
        });
        tree.setContextMenu(menu);

        buildDisconnectedPane();
        buildLoadingState();

        body.getChildren().addAll(tree, disconnectedPane, loadingState);
        StackPane.setAlignment(disconnectedPane, Pos.TOP_CENTER);
        StackPane.setAlignment(loadingState, Pos.CENTER);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, body);
        showDisconnected();
    }

    private void buildDisconnectedPane() {
        savedHeader.getStyleClass().add("saved-connections-header");
        emptyProfilesHint.getStyleClass().add("empty-state-detail");
        emptyProfilesHint.setWrapText(true);
        emptyProfilesHint.setPadding(new Insets(8, 12, 4, 12));

        savedList.getStyleClass().add("saved-connections-list");
        savedList.setCellFactory(view -> new SavedConnectionCell());
        savedList.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                ConnectionProfile selected = savedList.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    onConnectProfile.accept(selected);
                }
            }
        });

        MenuItem connectItem = new MenuItem("Connect\u2026");
        connectItem.setOnAction(event -> {
            ConnectionProfile selected = savedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                onConnectProfile.accept(selected);
            }
        });
        MenuItem deleteItem = new MenuItem("Delete");
        deleteItem.setOnAction(event -> {
            ConnectionProfile selected = savedList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                onDeleteProfile.accept(selected);
            }
        });
        MenuItem newItem = new MenuItem("New Connection\u2026");
        newItem.setOnAction(event -> onConnectRequested.run());
        ContextMenu savedMenu = new ContextMenu(connectItem, deleteItem, new SeparatorMenuItem(), newItem);
        savedMenu.setOnShowing(event -> {
            boolean hasSelection = savedList.getSelectionModel().getSelectedItem() != null;
            connectItem.setDisable(!hasSelection);
            deleteItem.setDisable(!hasSelection);
        });
        savedList.setContextMenu(savedMenu);

        newConnectionButton.getStyleClass().add("empty-state-action");
        newConnectionButton.setMaxWidth(Double.MAX_VALUE);
        newConnectionButton.setOnAction(event -> onConnectRequested.run());

        VBox footer = new VBox(8, newConnectionButton);
        footer.setPadding(new Insets(10, 12, 12, 12));

        VBox.setVgrow(savedList, Priority.ALWAYS);
        disconnectedPane.getStyleClass().add("saved-connections-pane");
        disconnectedPane.getChildren().addAll(savedHeader, emptyProfilesHint, savedList, footer);
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

    // ---------------------------------------------------------------- public API

    public void setDriver(DataSourceDriver driver) {
        this.driver = driver;
    }

    public void setProfileManager(ConnectionProfileManager profileManager) {
        this.profileManager = profileManager == null ? new ConnectionProfileManager() : profileManager;
        refreshSavedConnections();
    }

    /** Opens the connect dialog for a blank / last-used endpoint. */
    public void setOnConnectRequested(Runnable onConnectRequested) {
        this.onConnectRequested = onConnectRequested == null ? () -> { } : onConnectRequested;
    }

    /** Opens the connect dialog pre-filled from a saved profile (password still empty). */
    public void setOnConnectProfile(Consumer<ConnectionProfile> onConnectProfile) {
        this.onConnectProfile = onConnectProfile == null ? profile -> { } : onConnectProfile;
    }

    public void setOnDeleteProfile(Consumer<ConnectionProfile> onDeleteProfile) {
        this.onDeleteProfile = onDeleteProfile == null ? profile -> { } : onDeleteProfile;
    }

    /** Called when a node is double-clicked (columns/databases) or Insert is chosen. */
    public void setOnActivate(Consumer<SchemaNode> onActivate) {
        this.onActivate = onActivate == null ? node -> { } : onActivate;
    }

    /** Called for table/view double-click or "View DDL / Properties". */
    public void setOnViewObject(Consumer<SchemaNode> onViewObject) {
        this.onViewObject = onViewObject == null ? node -> { } : onViewObject;
    }

    /**
     * Called when the user wants to switch the session catalog — database/schema
     * double-click, table double-click (uses the table's catalog), or the context menu.
     */
    public void setOnUseDatabase(Consumer<SchemaNode> onUseDatabase) {
        this.onUseDatabase = onUseDatabase == null ? node -> { } : onUseDatabase;
    }

    /** Reloads the saved-connection list from disk. Safe on the FX thread. */
    public void refreshSavedConnections() {
        List<ConnectionProfile> profiles = profileManager.loadProfiles();
        savedList.getItems().setAll(profiles);
        boolean empty = profiles.isEmpty();
        if (empty) {
            emptyProfilesHint.setText(
                    "No saved connections yet. Use New Connection and check Save.");
        }
        emptyProfilesHint.setVisible(empty);
        emptyProfilesHint.setManaged(empty);
        savedList.setVisible(!empty);
        savedList.setManaged(!empty);
    }

    /** Reloads the top level. Safe to call from the JavaFX Application Thread. */
    public void reload() {
        if (driver == null || !driver.isConnected()) {
            showDisconnected();
            return;
        }
        showLoading();
        driver.getSchemaTree().whenComplete((nodes, error) -> Platform.runLater(() -> {
            if (error != null) {
                showLoadError(rootCauseMessage(error));
            } else if (nodes.isEmpty()) {
                showLoadError("No databases visible for this account.");
            } else {
                tree.getRoot().getChildren().setAll(nodes.stream().map(this::itemFor).toList());
                showTree();
            }
        }));
    }

    public void clear() {
        showDisconnected();
    }

    // ---------------------------------------------------------------- view states

    private void showDisconnected() {
        tree.getRoot().getChildren().clear();
        refreshSavedConnections();
        disconnectedPane.setVisible(true);
        disconnectedPane.setManaged(true);
        loadingState.setVisible(false);
        loadingState.setManaged(false);
        tree.setVisible(false);
        tree.setManaged(false);
        headerLabel.setText("DATABASE");
    }

    private void showLoading() {
        disconnectedPane.setVisible(false);
        disconnectedPane.setManaged(false);
        loadingState.setVisible(true);
        loadingState.setManaged(true);
        tree.setVisible(false);
        tree.setManaged(false);
    }

    private void showLoadError(String message) {
        tree.getRoot().getChildren().clear();
        refreshSavedConnections();
        // Keep profiles visible; surface the load failure above the list.
        emptyProfilesHint.setText(message);
        emptyProfilesHint.setVisible(true);
        emptyProfilesHint.setManaged(true);
        disconnectedPane.setVisible(true);
        disconnectedPane.setManaged(true);
        loadingState.setVisible(false);
        loadingState.setManaged(false);
        tree.setVisible(false);
        tree.setManaged(false);
    }

    private void showTree() {
        disconnectedPane.setVisible(false);
        disconnectedPane.setManaged(false);
        loadingState.setVisible(false);
        loadingState.setManaged(false);
        tree.setVisible(true);
        tree.setManaged(true);
        headerLabel.setText("SCHEMA");
    }

    // ---------------------------------------------------------------- internals

    private void activateSelection() {
        TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null && !isPlaceholder(selected.getValue())) {
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

    private LazyItem itemFor(SchemaNode node) {
        return new LazyItem(node, this::loadChildren);
    }

    private void loadChildren(LazyItem item) {
        SchemaNode node = item.getValue();
        if (driver == null || !driver.isConnected()) {
            item.getChildren().setAll(placeholderItem("Not connected"));
            return;
        }
        item.getChildren().setAll(placeholderItem("Loading\u2026"));

        driver.getChildren(node).whenComplete((children, error) -> Platform.runLater(() -> {
            if (error != null) {
                item.getChildren().setAll(placeholderItem(rootCauseMessage(error)));
            } else if (children.isEmpty()) {
                item.getChildren().setAll(placeholderItem("empty"));
            } else {
                item.getChildren().setAll(children.stream().map(this::itemFor).toList());
            }
        }));
    }

    private static List<TreeItem<SchemaNode>> placeholderItem(String text) {
        SchemaNode node = SchemaNode.of(text, NodeType.COLUMN, Map.of(META_PLACEHOLDER, "true"));
        return List.of(new TreeItem<>(node));
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

    private static final class LazyItem extends TreeItem<SchemaNode> {

        private boolean loaded;

        LazyItem(SchemaNode value, Consumer<LazyItem> loader) {
            super(value);
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
    }

    private static final class SchemaTreeCell extends TreeCell<SchemaNode> {

        private final Label nameLabel = new Label();
        private final Label detailLabel = new Label();
        private final HBox layout = new HBox(6);

        SchemaTreeCell() {
            nameLabel.getStyleClass().add("schema-node-name");
            detailLabel.getStyleClass().add("schema-node-detail");
            layout.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(SchemaNode item, boolean empty) {
            super.updateItem(item, empty);
            getStyleClass().remove("schema-placeholder");

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

            layout.getChildren().setAll(Icons.forNode(item), nameLabel, detailLabel);
            setText(null);
            setGraphic(layout);
        }

        private static String detailOf(SchemaNode node) {
            return switch (node.type()) {
                case COLUMN -> {
                    String type = node.metadata(SchemaNode.META_DATA_TYPE);
                    yield type == null ? "" : type;
                }
                case VIEW -> "view";
                default -> "";
            };
        }
    }

    private static final class SavedConnectionCell extends ListCell<ConnectionProfile> {

        private final Label nameLabel = new Label();
        private final Label detailLabel = new Label();
        private final VBox text = new VBox(1, nameLabel, detailLabel);
        private final HBox layout = new HBox(8);

        SavedConnectionCell() {
            nameLabel.getStyleClass().add("saved-connection-name");
            detailLabel.getStyleClass().add("saved-connection-detail");
            layout.setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(6, 10, 6, 10));
        }

        @Override
        protected void updateItem(ConnectionProfile item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            nameLabel.setText(item.displayName());
            detailLabel.setText(endpointLabel(item));
            layout.getChildren().setAll(Icons.database(), text);
            setText(null);
            setGraphic(layout);
        }

        private static String endpointLabel(ConnectionProfile profile) {
            String user = profile.username().isBlank() ? "<anonymous>" : profile.username();
            String schema = profile.database().isBlank() ? "" : "/" + profile.database();
            return "%s@%s:%d%s".formatted(user, profile.host(), profile.port(), schema);
        }
    }
}
