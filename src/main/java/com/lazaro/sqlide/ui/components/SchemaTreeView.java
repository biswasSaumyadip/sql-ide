package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.ui.Icons;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Schema explorer: catalogs, then tables and views, then columns.
 *
 * <p>Levels are fetched only when a node is first expanded, because introspecting
 * a whole server upfront is unusable on a real database. Fetches run on the
 * driver's worker threads and the resulting items are attached via
 * {@link Platform#runLater}.
 */
public final class SchemaTreeView extends VBox {

    /** Marks synthetic rows that stand in for a pending or failed fetch. */
    private static final String META_PLACEHOLDER = "__placeholder";

    private final TreeView<SchemaNode> tree = new TreeView<>();
    private final Label headerLabel = new Label("SCHEMA");

    private DataSourceDriver driver;
    private Consumer<SchemaNode> onActivate = node -> { };

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
                activateSelection();
            }
        });

        // The tree takes every pixel the panel is not using for its header.
        VBox.setVgrow(tree, Priority.ALWAYS);
        getChildren().addAll(header, tree);

        showMessage("Not connected");
    }

    // ---------------------------------------------------------------- public API

    public void setDriver(DataSourceDriver driver) {
        this.driver = driver;
    }

    /** Called when a node is double-clicked, so the controller can paste it into the editor. */
    public void setOnActivate(Consumer<SchemaNode> onActivate) {
        this.onActivate = onActivate == null ? node -> { } : onActivate;
    }

    /** Reloads the top level. Safe to call from the JavaFX Application Thread. */
    public void reload() {
        if (driver == null || !driver.isConnected()) {
            showMessage("Not connected");
            return;
        }
        showMessage("Loading\u2026");
        driver.getSchemaTree().whenComplete((nodes, error) -> Platform.runLater(() -> {
            if (error != null) {
                showMessage(rootCauseMessage(error));
            } else if (nodes.isEmpty()) {
                showMessage("No databases visible");
            } else {
                tree.getRoot().getChildren().setAll(nodes.stream().map(this::itemFor).toList());
            }
        }));
    }

    public void clear() {
        showMessage("Not connected");
    }

    // ---------------------------------------------------------------- internals

    private void activateSelection() {
        TreeItem<SchemaNode> selected = tree.getSelectionModel().getSelectedItem();
        if (selected != null && selected.getValue() != null && !isPlaceholder(selected.getValue())) {
            onActivate.accept(selected.getValue());
        }
    }

    private void showMessage(String message) {
        tree.getRoot().getChildren().setAll(placeholderItem(message));
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

    /** A single non-selectable row standing in for a pending, empty or failed fetch. */
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

    /** Tree item that asks the driver for its children the first time it is opened. */
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
            // Decided by node type, not by whether children happen to be loaded yet.
            return getValue().isLeaf();
        }
    }

    /** Renders icon, name and, for columns, a dimmed type. */
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
