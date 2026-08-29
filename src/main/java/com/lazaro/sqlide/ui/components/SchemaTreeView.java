package com.lazaro.sqlide.ui.components;

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
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
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
 * <p>When nothing is connected the tree is replaced by a prompt with a
 * {@linkplain #setOnConnectRequested(Runnable) New Connection} action, not a blank
 * panel. Levels are fetched only when a node is first expanded.
 */
public final class SchemaTreeView extends VBox {

    /** Marks synthetic rows that stand in for a pending or failed fetch. */
    private static final String META_PLACEHOLDER = "__placeholder";

    private final TreeView<SchemaNode> tree = new TreeView<>();
    private final Label headerLabel = new Label("SCHEMA");
    private final StackPane body = new StackPane();
    private final VBox emptyState = new VBox(10);
    private final VBox loadingState = new VBox(10);
    private final Label emptyTitle = new Label("No database connected");
    private final Label emptyDetail = new Label("Connect to browse catalogs, tables and columns.");
    private final Label loadingLabel = new Label("Loading schemas\u2026");
    private final ProgressIndicator loadingSpinner = new ProgressIndicator();

    private DataSourceDriver driver;
    private Consumer<SchemaNode> onActivate = node -> { };
    private Consumer<SchemaNode> onViewObject = node -> { };
    private Consumer<SchemaNode> onUseDatabase = node -> { };
    private Runnable onConnectRequested = () -> { };

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

        emptyTitle.getStyleClass().add("empty-state-title");
        emptyDetail.getStyleClass().add("empty-state-detail");
        emptyDetail.setWrapText(true);

        Button connectButton = new Button("New Connection");
        connectButton.getStyleClass().add("empty-state-action");
        connectButton.setOnAction(event -> onConnectRequested.run());

        emptyState.getStyleClass().add("empty-state");
        emptyState.setAlignment(Pos.CENTER);
        emptyState.setPadding(new Insets(24, 16, 24, 16));
        emptyState.getChildren().addAll(Icons.database(), emptyTitle, emptyDetail, connectButton);

        loadingSpinner.setMaxSize(22, 22);
        loadingLabel.getStyleClass().add("empty-state-detail");
        loadingState.getStyleClass().addAll("empty-state", "loading-state");
        loadingState.setAlignment(Pos.CENTER);
        loadingState.getChildren().addAll(loadingSpinner, loadingLabel);
        loadingState.setVisible(false);
        loadingState.setManaged(false);

        body.getChildren().addAll(tree, emptyState, loadingState);
        StackPane.setAlignment(emptyState, Pos.CENTER);
        StackPane.setAlignment(loadingState, Pos.CENTER);
        VBox.setVgrow(body, Priority.ALWAYS);

        getChildren().addAll(header, body);
        showDisconnected();
    }

    // ---------------------------------------------------------------- public API

    public void setDriver(DataSourceDriver driver) {
        this.driver = driver;
    }

    /** Opens the connect dialog when the empty-state button is pressed. */
    public void setOnConnectRequested(Runnable onConnectRequested) {
        this.onConnectRequested = onConnectRequested == null ? () -> { } : onConnectRequested;
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
        emptyTitle.setText("No database connected");
        emptyDetail.setText("Connect to browse catalogs, tables and columns.");
        emptyState.setVisible(true);
        emptyState.setManaged(true);
        loadingState.setVisible(false);
        loadingState.setManaged(false);
        tree.setVisible(false);
        tree.setManaged(false);
    }

    private void showLoading() {
        emptyState.setVisible(false);
        emptyState.setManaged(false);
        loadingState.setVisible(true);
        loadingState.setManaged(true);
        tree.setVisible(false);
        tree.setManaged(false);
    }

    private void showLoadError(String message) {
        tree.getRoot().getChildren().clear();
        emptyTitle.setText("Could not load schema");
        emptyDetail.setText(message);
        emptyState.setVisible(true);
        emptyState.setManaged(true);
        loadingState.setVisible(false);
        loadingState.setManaged(false);
        tree.setVisible(false);
        tree.setManaged(false);
    }

    private void showTree() {
        emptyState.setVisible(false);
        emptyState.setManaged(false);
        loadingState.setVisible(false);
        loadingState.setManaged(false);
        tree.setVisible(true);
        tree.setManaged(true);
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
}
