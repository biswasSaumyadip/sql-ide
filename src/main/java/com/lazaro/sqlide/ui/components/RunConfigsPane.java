package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.config.ConnectionProfile;
import com.lazaro.sqlide.core.config.ConnectionProfileManager;
import com.lazaro.sqlide.core.runconfig.RunConfiguration;
import com.lazaro.sqlide.core.runconfig.RunConfigurationStore;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Sidebar list of saved run configurations (SQL + target profile + params). */
public final class RunConfigsPane extends VBox {

    private final RunConfigurationStore store;
    private final ConnectionProfileManager profiles;
    private final TextField search = new TextField();
    private final ListView<RunConfiguration> list = new ListView<>();
    private Consumer<RunConfiguration> onRun = config -> { };
    private Consumer<RunConfiguration> onOpen = config -> { };
    private Supplier<String> sqlSupplier = () -> "";
    private Supplier<String> profileIdSupplier = () -> "";

    public RunConfigsPane(RunConfigurationStore store, ConnectionProfileManager profiles) {
        this.store = store;
        this.profiles = profiles;
        getStyleClass().add("run-configs-pane");
        setSpacing(0);

        Label headerLabel = new Label("RUN CONFIGS");
        headerLabel.getStyleClass().add("panel-header");
        Button add = new Button("Save");
        add.getStyleClass().add("panel-header-action");
        add.setTooltip(new Tooltip("Save current editor SQL as a run configuration"));
        add.setOnAction(event -> saveFromEditor());
        HBox header = new HBox(8, headerLabel, add);
        header.getStyleClass().add("panel-header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(headerLabel, Priority.ALWAYS);

        search.setPromptText("Search run configs\u2026");
        search.getStyleClass().add("sidebar-search");
        search.textProperty().addListener((o, a, b) -> refresh());
        VBox.setMargin(search, new Insets(6, 8, 6, 8));

        list.getStyleClass().add("snippets-list");
        list.setCellFactory(view -> new ConfigCell());
        list.setOnMouseClicked(event -> {
            RunConfiguration selected = list.getSelectionModel().getSelectedItem();
            if (selected != null && event.getClickCount() == 2) {
                onRun.accept(selected);
            }
        });
        VBox.setVgrow(list, Priority.ALWAYS);

        getChildren().addAll(header, search, list);
        refresh();
    }

    public void setOnRun(Consumer<RunConfiguration> action) {
        this.onRun = action == null ? config -> { } : action;
    }

    public void setOnOpen(Consumer<RunConfiguration> action) {
        this.onOpen = action == null ? config -> { } : action;
    }

    public void setSqlSupplier(Supplier<String> supplier) {
        this.sqlSupplier = supplier == null ? () -> "" : supplier;
    }

    public void setProfileIdSupplier(Supplier<String> supplier) {
        this.profileIdSupplier = supplier == null ? () -> "" : supplier;
    }

    public void refresh() {
        list.setItems(FXCollections.observableArrayList(store.search(search.getText())));
    }

    private void saveFromEditor() {
        String sql = sqlSupplier.get();
        if (sql == null || sql.isBlank()) {
            return;
        }
        TextInputDialog nameDialog = new TextInputDialog("Untitled run");
        nameDialog.setTitle("Save run configuration");
        nameDialog.setHeaderText("Name this configuration");
        nameDialog.setContentText("Name:");
        Optional<String> name = nameDialog.showAndWait();
        if (name.isEmpty() || name.get().isBlank()) {
            return;
        }

        ComboBox<ConnectionProfile> profileBox = new ComboBox<>();
        profileBox.setItems(FXCollections.observableArrayList(profiles.loadProfiles()));
        profileBox.setMaxWidth(Double.MAX_VALUE);
        String preferred = profileIdSupplier.get();
        if (preferred != null && !preferred.isBlank()) {
            profileBox.getItems().stream()
                    .filter(p -> preferred.equals(p.id()))
                    .findFirst()
                    .ifPresent(profileBox::setValue);
        }
        if (profileBox.getValue() == null && !profileBox.getItems().isEmpty()) {
            profileBox.getSelectionModel().selectFirst();
        }

        TextArea paramsArea = new TextArea();
        paramsArea.setPromptText("Optional defaults, one per line: name=value");
        paramsArea.setPrefRowCount(4);

        javafx.scene.control.Dialog<RunConfiguration> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle("Run configuration");
        dialog.setHeaderText(name.get().strip());
        VBox body = new VBox(8,
                new Label("Target connection"), profileBox,
                new Label("Default parameters"), paramsArea);
        body.setPadding(new Insets(8));
        dialog.getDialogPane().setContent(body);
        dialog.getDialogPane().getButtonTypes().addAll(
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
        dialog.setResultConverter(button -> {
            if (button != javafx.scene.control.ButtonType.OK) {
                return null;
            }
            ConnectionProfile profile = profileBox.getValue();
            return new RunConfiguration(
                    null,
                    name.get().strip(),
                    sql,
                    profile == null ? "" : profile.id(),
                    parseParams(paramsArea.getText()),
                    java.time.Instant.now());
        });
        dialog.showAndWait().ifPresent(config -> {
            store.save(config);
            refresh();
        });
    }

    private static Map<String, String> parseParams(String text) {
        Map<String, String> map = new LinkedHashMap<>();
        if (text == null || text.isBlank()) {
            return map;
        }
        for (String line : text.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || !trimmed.contains("=")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            map.put(trimmed.substring(0, eq).strip(), trimmed.substring(eq + 1).strip());
        }
        return map;
    }

    private final class ConfigCell extends ListCell<RunConfiguration> {
        private final HBox root = new HBox(6);
        private final Label title = new Label();
        private final Button run = new Button("Run");
        private final Button open = new Button("Open");
        private final Button delete = new Button("\u00D7");

        ConfigCell() {
            title.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(title, Priority.ALWAYS);
            run.getStyleClass().add("panel-header-action");
            open.getStyleClass().add("panel-header-action");
            delete.getStyleClass().add("panel-header-action");
            run.setOnAction(e -> {
                RunConfiguration item = getItem();
                if (item != null) {
                    onRun.accept(item);
                }
            });
            open.setOnAction(e -> {
                RunConfiguration item = getItem();
                if (item != null) {
                    onOpen.accept(item);
                }
            });
            delete.setOnAction(e -> {
                RunConfiguration item = getItem();
                if (item != null) {
                    store.delete(item.id());
                    refresh();
                }
            });
            root.setAlignment(Pos.CENTER_LEFT);
            root.getChildren().setAll(title, open, run, delete);
        }

        @Override
        protected void updateItem(RunConfiguration item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            String profileName = profiles.loadProfiles().stream()
                    .filter(p -> p.id().equals(item.profileId()))
                    .map(ConnectionProfile::displayName)
                    .findFirst()
                    .orElse(item.profileId().isBlank() ? "(no profile)" : item.profileId());
            title.setText(item.name() + "\n" + profileName);
            title.setTooltip(new Tooltip(item.sql()));
            setGraphic(root);
        }
    }
}
