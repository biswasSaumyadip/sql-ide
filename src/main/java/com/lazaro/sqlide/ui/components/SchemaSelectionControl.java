package com.lazaro.sqlide.ui.components;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * IntelliJ-style schema filter control: a compact {@code N of M} button that opens
 * a checklist of schemas/databases to show in the Database tree.
 */
public final class SchemaSelectionControl extends HBox {

    private final SchemaSelectionState state = new SchemaSelectionState();
    private final Button pickerButton = new Button("Schemas");
    private final Popup popup = new Popup();
    private final TextField searchField = new TextField();
    private final VBox checkList = new VBox(2);
    private final Label summaryLabel = new Label();

    private Consumer<Set<String>> onSelectionChanged = selection -> { };

    public SchemaSelectionControl() {
        getStyleClass().add("schema-selection-control");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(0);

        pickerButton.getStyleClass().add("schema-picker-button");
        pickerButton.setMaxWidth(Double.MAX_VALUE);
        pickerButton.setTooltip(new Tooltip("Choose which schemas appear in the tree"));
        pickerButton.setOnAction(event -> togglePopup());
        HBox.setHgrow(pickerButton, Priority.ALWAYS);
        getChildren().add(pickerButton);

        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.getContent().add(buildPopupContent());

        updateButton();
    }

    public void setOnSelectionChanged(Consumer<Set<String>> onSelectionChanged) {
        this.onSelectionChanged = onSelectionChanged == null ? selection -> { } : onSelectionChanged;
    }

    /** Replaces the known schema list. Preserves prior checks when possible. */
    public void setAvailableSchemas(List<String> schemas, String preferredActive) {
        state.setAvailableSchemas(schemas, preferredActive);
        rebuildCheckList();
        updateButton();
        setDisable(state.availableCount() == 0);
    }

    public void clear() {
        state.clear();
        rebuildCheckList();
        updateButton();
        setDisable(true);
        popup.hide();
    }

    public Set<String> selectedSchemas() {
        return state.selected();
    }

    public boolean isSchemaVisible(String name) {
        return state.isSchemaVisible(name);
    }

    public int availableCount() {
        return state.availableCount();
    }

    public int selectedCount() {
        return state.selectedCount();
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
                popup.hide();
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

    private void togglePopup() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        if (state.availableCount() == 0) {
            return;
        }
        searchField.clear();
        rebuildCheckList();
        Bounds bounds = pickerButton.localToScreen(pickerButton.getBoundsInLocal());
        if (bounds == null) {
            return;
        }
        popup.show(pickerButton, bounds.getMinX(), bounds.getMaxY() + 2);
        searchField.requestFocus();
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
        updateButton();
        updateSummary();
        onSelectionChanged.accept(selectedSchemas());
    }

    private void updateSummary() {
        summaryLabel.setText(state.selectedCount() + " of " + state.availableCount() + " selected");
    }

    private void updateButton() {
        if (state.availableCount() == 0) {
            pickerButton.setText("Schemas");
            pickerButton.setDisable(true);
            return;
        }
        pickerButton.setDisable(false);
        pickerButton.setText(state.selectedCount() + " of " + state.availableCount());
    }
}
