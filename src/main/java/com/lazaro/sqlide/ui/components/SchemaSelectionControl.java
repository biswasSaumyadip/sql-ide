package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.config.SchemaSelectionStore;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Per-connection schema filter: owns selection state and a shared checklist popup
 * anchored under the clicked connection badge. Selections are persisted per
 * connection id under {@code ~/.sql-ide-config/schema-selections.json}.
 */
public final class SchemaSelectionControl {

    private static final String SESSION_ID = "__session__";

    private final SchemaSelectionStore store;
    private final SchemaSelectionRegistry registry = new SchemaSelectionRegistry();
    private final Popup popup = new Popup();
    private final TextField searchField = new TextField();
    private final VBox checkList = new VBox(2);
    private final Label summaryLabel = new Label();

    private SchemaSelectionState state = new SchemaSelectionState();
    private String popupConnectionId;
    private Consumer<String> onSelectionChanged = connectionId -> { };

    public SchemaSelectionControl() {
        this(new SchemaSelectionStore());
    }

    SchemaSelectionControl(SchemaSelectionStore store) {
        this.store = Objects.requireNonNull(store, "store");
        restoreFromDisk();
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(buildPopupContent());
        popup.setOnHidden(event -> popupConnectionId = null);
    }

    private void restoreFromDisk() {
        for (Map.Entry<String, List<String>> entry : store.loadAll().entrySet()) {
            if (SESSION_ID.equals(entry.getKey())) {
                continue;
            }
            registry.forConnection(entry.getKey()).applyRestoredSelection(entry.getValue());
        }
    }

    /**
     * Fired with the connection id whose selection changed (so the tree can refresh
     * that connection's badge and children).
     */
    public void setOnSelectionChanged(Consumer<String> onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged == null ? id -> { } : onSelectionChanged;
    }

    public void setAvailableSchemas(String connectionId, List<String> schemas, String preferredActive) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }
        SchemaSelectionState target = registry.forConnection(connectionId);
        target.setAvailableSchemas(schemas, preferredActive);
        if (Objects.equals(popupConnectionId, connectionId)) {
            state = target;
            rebuildCheckList();
            updateSummary();
        }
        onSelectionChanged.accept(connectionId);
    }

    public void hidePopup() {
        popup.hide();
        popupConnectionId = null;
    }

    /** Disconnect helper — closes the popup; remembered selections stay per id. */
    public void clearActive() {
        hidePopup();
    }

    public void forgetConnection(String connectionId) {
        registry.remove(connectionId);
        if (!SESSION_ID.equals(connectionId)) {
            store.remove(connectionId);
        }
        if (Objects.equals(popupConnectionId, connectionId)) {
            hidePopup();
        }
    }

    public boolean hasSchemas(String connectionId) {
        return connectionId != null
                && registry.hasConnection(connectionId)
                && registry.forConnection(connectionId).availableCount() > 0;
    }

    /** Badge text, e.g. {@code 1/5}. Empty when unknown. */
    public String countLabel(String connectionId) {
        if (!hasSchemas(connectionId)) {
            return "";
        }
        SchemaSelectionState target = registry.forConnection(connectionId);
        return target.selectedCount() + "/" + target.availableCount();
    }

    public boolean isSchemaVisible(String connectionId, String name) {
        if (connectionId == null || connectionId.isBlank()) {
            return true;
        }
        if (!registry.hasConnection(connectionId)) {
            return true;
        }
        return registry.forConnection(connectionId).isSchemaVisible(name);
    }

    /**
     * Opens (or toggles) the checklist anchored directly under {@code anchor}
     * for the given connection.
     */
    public void showFor(Node anchor, String connectionId) {
        if (anchor == null || connectionId == null || connectionId.isBlank()) {
            return;
        }
        if (popup.isShowing() && Objects.equals(popupConnectionId, connectionId)) {
            hidePopup();
            return;
        }
        if (!hasSchemas(connectionId)) {
            return;
        }

        popupConnectionId = connectionId;
        state = registry.forConnection(connectionId);
        searchField.clear();
        rebuildCheckList();
        updateSummary();

        // Defer so layout has a real height and the click that opened us has finished.
        javafx.application.Platform.runLater(() -> {
            if (!Objects.equals(popupConnectionId, connectionId)) {
                return;
            }
            double height = anchor.getBoundsInLocal().getHeight();
            if (height <= 0 && anchor instanceof Region region) {
                height = Math.max(region.getHeight(), 18);
            }
            Point2D point = anchor.localToScreen(0, Math.max(height, 1));
            if (point == null || anchor.getScene() == null || anchor.getScene().getWindow() == null) {
                return;
            }
            popup.show(anchor.getScene().getWindow(), point.getX(), point.getY());
            searchField.requestFocus();
        });
    }

    private VBox buildPopupContent() {
        Label title = new Label("Schemas / Databases");
        title.getStyleClass().add("schema-picker-title");

        Hyperlink all = new Hyperlink("All");
        all.setOnAction(event -> {
            state.selectAll();
            rebuildCheckList();
            fireChanged();
        });
        Hyperlink none = new Hyperlink("None");
        none.setOnAction(event -> {
            state.selectNone();
            rebuildCheckList();
            fireChanged();
        });
        HBox links = new HBox(10, all, none);
        links.setAlignment(Pos.CENTER_LEFT);

        searchField.setPromptText("Filter schemas\u2026");
        searchField.getStyleClass().add("sidebar-search");
        searchField.textProperty().addListener((observable, previous, current) -> rebuildCheckList());
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hidePopup();
            }
        });

        checkList.getStyleClass().add("schema-picker-list");
        ScrollPane scroll = new ScrollPane(checkList);
        scroll.setFitToWidth(true);
        scroll.setPrefHeight(220);
        scroll.setMaxHeight(280);
        scroll.getStyleClass().add("schema-picker-scroll");

        summaryLabel.getStyleClass().add("schema-picker-summary");

        VBox root = new VBox(8, title, links, searchField, scroll, summaryLabel);
        root.getStyleClass().add("schema-picker-popup");
        root.setPadding(new Insets(10));
        root.setPrefWidth(260);
        return root;
    }

    private void rebuildCheckList() {
        String needle = Objects.requireNonNullElse(searchField.getText(), "");
        checkList.getChildren().clear();
        for (String schema : state.filteredAvailable(needle)) {
            CheckBox box = new CheckBox(schema);
            box.setSelected(state.isSchemaVisible(schema));
            box.setMaxWidth(Double.MAX_VALUE);
            box.selectedProperty().addListener((observable, was, isSelected) -> {
                state.setSelected(schema, isSelected);
                fireChanged();
            });
            checkList.getChildren().add(box);
        }
        updateSummary();
    }

    private void fireChanged() {
        updateSummary();
        persistSelection(popupConnectionId);
        onSelectionChanged.accept(popupConnectionId);
    }

    private void persistSelection(String connectionId) {
        if (connectionId == null || connectionId.isBlank() || SESSION_ID.equals(connectionId)) {
            return;
        }
        SchemaSelectionState target = registry.hasConnection(connectionId)
                ? registry.forConnection(connectionId)
                : state;
        store.saveSelection(connectionId, target.selected());
    }

    private void updateSummary() {
        summaryLabel.setText(state.selectedCount() + " of " + state.availableCount() + " selected");
    }
}
