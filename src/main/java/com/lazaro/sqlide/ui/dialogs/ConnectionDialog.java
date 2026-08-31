package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.controls.ToggleSwitch;
import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.config.ConnectionProfile;
import com.lazaro.sqlide.core.config.ConnectionProfileManager;
import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.ConnectionConfig.Environment;
import com.lazaro.sqlide.core.db.ConnectionConfig.TunnelSettings;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import com.lazaro.sqlide.core.db.DriverRegistry;
import com.lazaro.sqlide.core.db.RedisDriver;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.stage.StageStyle;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modal credential prompt with IntelliJ-style saved connections. Profiles store
 * everything except the password.
 */
public final class ConnectionDialog extends Dialog<ConnectionConfig> {

    private static final Logger LOG = Logger.getLogger(ConnectionDialog.class.getName());
    private static final String NEW_CONNECTION = "New connection";

    private final ConnectionProfileManager profileManager;
    private final DriverRegistry registry;
    private final DialogChrome chrome;
    private final ComboBox<ConnectionProfile> savedBox = new ComboBox<>();
    private final TextField nameField = new TextField();
    private final CheckBox saveProfileCheck = new CheckBox("Save connection");
    private final Button deleteProfileButton = new Button("Delete");

    private final ComboBox<ConnectionConfig.ConnectionType> typeBox = new ComboBox<>();
    private final ComboBox<ConnectionConfig.Driver> driverBox = new ComboBox<>();
    private final ComboBox<Environment> environmentBox = new ComboBox<>();
    private final Label driverLabel = new Label("Driver");
    private final Label databaseLabel = new Label("Database");
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final TextField databaseField = new TextField();
    private final TextField userField = new TextField();
    private final PasswordField passwordField = new PasswordField();

    private final ToggleSwitch sshToggle = new ToggleSwitch("SSH tunnel");
    private final TextField sshHostField = new TextField();
    private final TextField sshPortField = new TextField("22");
    private final TextField sshUserField = new TextField();
    private final TextField sshKeyField = new TextField();
    private final ToggleSwitch sslToggle = new ToggleSwitch("SSL / TLS");
    private final TextField sslCaField = new TextField();
    private final TextField sslClientField = new TextField();
    private final VBox sshFields = new VBox(8);
    private final VBox sslFields = new VBox(8);

    private final ObservableList<PropertyRow> jdbcRows = FXCollections.observableArrayList();
    private final TextField urlPreview = new TextField();
    private final Label feedback = new Label();
    private final ProgressIndicator testSpinner = new ProgressIndicator();
    private final Button testButton = new Button("Test Connection");
    private final Button connectButton = new Button("Connect");
    private final Button cancelButton = new Button("Cancel");

    /** Id of the profile currently loaded into the form (for update-on-save). */
    private String editingProfileId;

    public ConnectionDialog(ConnectionConfig initial, DataSourceDriver driver) {
        this(initial, driver, null, new ConnectionProfileManager(), null);
    }

    public ConnectionDialog(
            ConnectionConfig initial,
            DataSourceDriver driver,
            ConnectionProfileManager profileManager,
            ConnectionProfile preselect) {
        this(initial, driver, null, profileManager, preselect);
    }

    public ConnectionDialog(
            ConnectionConfig initial,
            DataSourceDriver driver,
            DriverRegistry registry,
            ConnectionProfileManager profileManager,
            ConnectionProfile preselect) {
        this.profileManager = profileManager == null ? new ConnectionProfileManager() : profileManager;
        this.registry = registry;
        this.chrome = new DialogChrome(this, 560, 480);

        initStyle(StageStyle.UNDECORATED);
        setTitle("Connect to database");
        setHeaderText(null);
        setGraphic(null);
        setResizable(true);

        getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        Button hiddenOk = (Button) getDialogPane().lookupButton(ButtonType.OK);
        hiddenOk.setText("Connect");
        hiddenOk.setDefaultButton(true);

        getDialogPane().getStyleClass().add("connection-dialog");
        getDialogPane().getStylesheets().add(stylesheet());
        getDialogPane().setContent(buildRoot());
        getDialogPane().setPrefSize(640, 620);
        getDialogPane().setMinSize(560, 480);

        reloadSavedProfiles();
        populate(initial);
        wireSavedProfiles();
        wireValidation();
        wireUrlPreview();
        wireTestButton(driver);
        wireCustomButtons(hiddenOk);
        if (preselect != null) {
            selectProfile(preselect);
        }

        setOnShown(event -> chrome.installResize());
        setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            ConnectionConfig config = toConfig();
            if (saveProfileCheck.isSelected()) {
                persistCurrentProfile();
            }
            return config;
        });
        Platform.runLater(() -> {
            if (preselect != null) {
                passwordField.requestFocus();
            } else {
                hostField.requestFocus();
            }
        });
    }

    /** Profile selected in the form when Connect was pressed (empty for ephemeral). */
    public java.util.Optional<String> selectedProfileId() {
        return java.util.Optional.ofNullable(editingProfileId).filter(id -> !id.isBlank());
    }

    /** Selects a saved profile in the dropdown (password is cleared). */
    public void selectProfile(ConnectionProfile profile) {
        if (profile == null) {
            return;
        }
        ConnectionProfile match = savedBox.getItems().stream()
                .filter(item -> profile.id().equals(item.id()))
                .findFirst()
                .orElse(profile);
        if (!savedBox.getItems().contains(match)) {
            savedBox.getItems().add(0, match);
        }
        savedBox.setValue(match);
        applyProfile(match);
    }

    // ---------------------------------------------------------------- form

    private BorderPane buildRoot() {
        Label section = new Label("Data source settings");
        section.getStyleClass().add("connection-section-title");
        Separator underline = new Separator();
        underline.getStyleClass().add("connection-section-rule");
        VBox header = new VBox(6, section, underline);
        header.getStyleClass().add("connection-section-header");

        TabPane tabs = new TabPane(
                closableTab("General", generalPane()),
                closableTab("SSH / SSL", sshSslPane()),
                closableTab("Advanced", advancedPane()));
        tabs.getStyleClass().add("connection-tabs");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        urlPreview.setEditable(false);
        urlPreview.setFocusTraversable(false);
        urlPreview.getStyleClass().add("connection-url-preview");
        urlPreview.setTooltip(new Tooltip("JDBC / Redis URI generated from the fields above"));
        Label urlLabel = new Label("URL");
        urlLabel.getStyleClass().add("connection-field-label");
        HBox urlRow = new HBox(10, urlLabel, urlPreview);
        urlRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(urlPreview, Priority.ALWAYS);

        feedback.getStyleClass().add("dialog-feedback");
        feedback.setWrapText(true);
        feedback.setMaxWidth(Double.MAX_VALUE);

        VBox body = new VBox(12, header, savedPane(), tabs, urlRow, feedback, buttonBar());
        body.getStyleClass().add("connection-body");
        VBox.setVgrow(tabs, Priority.ALWAYS);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("connection-root");
        root.setTop(chrome.titleBar("Connect to database"));
        root.setCenter(body);
        return root;
    }

    private static Tab closableTab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private GridPane savedPane() {
        nameField.setPromptText("Connection name");
        savedBox.setMaxWidth(Double.MAX_VALUE);
        savedBox.setPromptText(NEW_CONNECTION);
        HBox.setHgrow(savedBox, Priority.ALWAYS);
        deleteProfileButton.setTooltip(new Tooltip("Remove the selected saved connection"));
        deleteProfileButton.getStyleClass().add("connection-delete");

        HBox savedRow = new HBox(8, savedBox, deleteProfileButton);
        savedRow.setAlignment(Pos.CENTER_LEFT);

        GridPane grid = labeledGrid();
        grid.addRow(0, labeled("Saved"), savedRow);
        grid.addRow(1, labeled("Name"), nameField);
        grid.add(saveProfileCheck, 1, 2);
        saveProfileCheck.getStyleClass().add("connection-save-check");
        return grid;
    }

    private VBox generalPane() {
        typeBox.getItems().setAll(ConnectionConfig.ConnectionType.values());
        typeBox.setMaxWidth(Double.MAX_VALUE);
        for (ConnectionConfig.Driver driver : ConnectionConfig.Driver.values()) {
            if (driver.isJdbc()) {
                driverBox.getItems().add(driver);
            }
        }
        driverBox.setMaxWidth(Double.MAX_VALUE);
        environmentBox.getItems().setAll(Environment.values());
        environmentBox.setMaxWidth(Double.MAX_VALUE);
        environmentBox.setCellFactory(list -> environmentCell());
        environmentBox.setButtonCell(environmentCell());
        portField.setPrefColumnCount(6);

        GridPane grid = labeledGrid();
        int row = 0;
        grid.addRow(row++, labeled("Type"), typeBox);
        grid.addRow(row++, driverLabel, driverBox);
        grid.addRow(row++, labeled("Host"), hostField);
        grid.addRow(row++, labeled("Port"), portField);
        grid.addRow(row++, databaseLabel, databaseField);
        grid.addRow(row++, labeled("User"), userField);
        grid.addRow(row++, labeled("Password"), passwordField);
        grid.addRow(row++, labeled("Environment"), environmentBox);

        driverLabel.getStyleClass().add("connection-field-label");
        databaseLabel.getStyleClass().add("connection-field-label");

        VBox pane = new VBox(grid);
        pane.setPadding(new Insets(12, 0, 8, 0));
        return pane;
    }

    private VBox sshSslPane() {
        sshToggle.getStyleClass().add("connection-toggle");
        sslToggle.getStyleClass().add("connection-toggle");
        sshPortField.setPrefColumnCount(6);
        sshHostField.setPromptText("bastion.example.com");
        sshUserField.setPromptText("ec2-user");
        sshKeyField.setPromptText("Path to private key");
        sslCaField.setPromptText("CA / trust store");
        sslClientField.setPromptText("Client certificate");

        GridPane sshGrid = labeledGrid();
        sshGrid.addRow(0, labeled("SSH host"), sshHostField);
        sshGrid.addRow(1, labeled("SSH port"), sshPortField);
        sshGrid.addRow(2, labeled("SSH user"), sshUserField);
        sshGrid.addRow(3, labeled("Private key"), fileRow(sshKeyField, "Select private key"));
        sshFields.getChildren().setAll(sshGrid);
        Label sshHint = new Label("Opens an SSH tunnel before connecting. Not applied until a later release.");
        sshHint.getStyleClass().add("connection-hint");
        sshHint.setWrapText(true);

        GridPane sslGrid = labeledGrid();
        sslGrid.addRow(0, labeled("CA cert"), fileRow(sslCaField, "Select CA certificate"));
        sslGrid.addRow(1, labeled("Client cert"), fileRow(sslClientField, "Select client certificate"));
        sslFields.getChildren().setAll(sslGrid);
        Label sslHint = new Label("Certificate paths are saved with the profile. Use Advanced to inject SSL JDBC properties now.");
        sslHint.getStyleClass().add("connection-hint");
        sslHint.setWrapText(true);

        sshToggle.selectedProperty().addListener((obs, previous, selected) -> setGroupEnabled(sshFields, selected));
        sslToggle.selectedProperty().addListener((obs, previous, selected) -> setGroupEnabled(sslFields, selected));
        setGroupEnabled(sshFields, false);
        setGroupEnabled(sslFields, false);

        VBox pane = new VBox(12,
                sshToggle, sshHint, sshFields,
                new Separator(),
                sslToggle, sslHint, sslFields);
        pane.setPadding(new Insets(12, 0, 8, 0));
        return pane;
    }

    private VBox advancedPane() {
        TableView<PropertyRow> table = new TableView<>(jdbcRows);
        table.setEditable(true);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPlaceholder(new Label("No JDBC properties"));
        table.getStyleClass().add("connection-props-grid");

        TableColumn<PropertyRow, String> key = new TableColumn<>("Key");
        key.setPrefWidth(180);
        key.setCellValueFactory(cd -> cd.getValue().key);
        key.setCellFactory(TextFieldTableCell.forTableColumn());
        key.setOnEditCommit(event -> {
            event.getRowValue().key.set(event.getNewValue());
            refreshUrlPreview();
        });
        TableColumn<PropertyRow, String> value = new TableColumn<>("Value");
        value.setCellValueFactory(cd -> cd.getValue().value);
        value.setCellFactory(TextFieldTableCell.forTableColumn());
        value.setOnEditCommit(event -> {
            event.getRowValue().value.set(event.getNewValue());
            refreshUrlPreview();
        });
        table.getColumns().setAll(List.of(key, value));

        Button add = new Button("Add");
        add.getStyleClass().addAll(Styles.FLAT, "connection-tool-button");
        Button remove = new Button("Remove");
        remove.getStyleClass().addAll(Styles.FLAT, "connection-tool-button");
        add.setOnAction(event -> {
            jdbcRows.add(new PropertyRow("", ""));
            table.getSelectionModel().selectLast();
            table.edit(jdbcRows.size() - 1, key);
            refreshUrlPreview();
        });
        remove.setOnAction(event -> {
            PropertyRow selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                jdbcRows.remove(selected);
                refreshUrlPreview();
            }
        });
        HBox tools = new HBox(6, add, remove);
        tools.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("Key-value pairs are appended to the JDBC URL (ignored for Redis).");
        hint.getStyleClass().add("connection-hint");
        hint.setWrapText(true);

        VBox pane = new VBox(8, hint, tools, table);
        pane.setPadding(new Insets(12, 0, 8, 0));
        VBox.setVgrow(table, Priority.ALWAYS);
        return pane;
    }

    private HBox buttonBar() {
        testSpinner.setMaxSize(14, 14);
        testSpinner.getStyleClass().add("connection-test-spinner");
        testButton.getStyleClass().add("connection-test");
        connectButton.getStyleClass().addAll(Styles.ACCENT, "connection-connect");
        cancelButton.getStyleClass().add("connection-cancel");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox bar = new HBox(8, testButton, spacer, connectButton, cancelButton);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.getStyleClass().add("connection-button-bar");
        return bar;
    }

    private void wireCustomButtons(Button hiddenOk) {
        Button hiddenCancel = (Button) getDialogPane().lookupButton(ButtonType.CANCEL);
        connectButton.setOnAction(event -> hiddenOk.fire());
        cancelButton.setOnAction(event -> hiddenCancel.fire());
    }

    private static GridPane labeledGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(96);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);
        return grid;
    }

    private static Label labeled(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("connection-field-label");
        return label;
    }

    private HBox fileRow(TextField field, String title) {
        Button browse = new Button("Browse\u2026");
        browse.getStyleClass().addAll(Styles.FLAT, "connection-browse");
        browse.setOnAction(event -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(title);
            File file = chooser.showOpenDialog(getOwner() == null
                    ? getDialogPane().getScene().getWindow()
                    : getOwner());
            if (file != null) {
                field.setText(file.getAbsolutePath());
            }
        });
        HBox.setHgrow(field, Priority.ALWAYS);
        HBox row = new HBox(8, field, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static void setGroupEnabled(Node group, boolean enabled) {
        group.setDisable(!enabled);
        group.setOpacity(enabled ? 1 : 0.55);
    }

    private static ListCell<Environment> environmentCell() {
        return new ListCell<>() {
            private final Rectangle swatch = new Rectangle(10, 10);

            {
                swatch.setArcWidth(3);
                swatch.setArcHeight(3);
            }

            @Override
            protected void updateItem(Environment item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item.displayName());
                if (item == Environment.NONE) {
                    setGraphic(null);
                    return;
                }
                swatch.setFill(Color.web(item.colorHex()));
                setGraphic(swatch);
            }
        };
    }

    private void populate(ConnectionConfig initial) {
        ConnectionConfig source = initial != null
                ? initial
                : ConnectionConfig.mysql("localhost", ConnectionConfig.Driver.MYSQL.defaultPort(), "", "root", "");

        applyEndpoint(source.driver(), source.host(), source.port(), source.database(), source.user());
        applyExtras(source.environment(), source.tunnel(), source.jdbcProperties());
        passwordField.clear();
        editingProfileId = null;
        nameField.setText(defaultName(source.driver(), source.host(), source.port(), source.database()));
        saveProfileCheck.setSelected(false);

        typeBox.valueProperty().addListener((observable, previous, current) -> {
            applyTypeVisibility(current);
            refreshUrlPreview();
            if (previous == null || current == null || previous == current) {
                return;
            }
            int previousDefault = previous.isRedis()
                    ? ConnectionConfig.Driver.REDIS.defaultPort()
                    : jdbcDefaultPort();
            if (portField.getText().equals(Integer.toString(previousDefault))) {
                int next = current.isRedis()
                        ? ConnectionConfig.Driver.REDIS.defaultPort()
                        : jdbcDefaultPort();
                portField.setText(Integer.toString(next));
            }
        });
        driverBox.valueProperty().addListener((observable, previous, current) -> {
            refreshUrlPreview();
            if (currentType().isRedis()) {
                return;
            }
            if (current != null && (previous == null || portField.getText().equals(
                    Integer.toString(previous.defaultPort())))) {
                portField.setText(Integer.toString(current.defaultPort()));
            }
        });
        applyTypeVisibility(currentType());
        refreshUrlPreview();
    }

    private void applyExtras(Environment environment, TunnelSettings tunnel, Map<String, String> properties) {
        environmentBox.setValue(environment == null ? Environment.NONE : environment);
        TunnelSettings settings = tunnel == null ? TunnelSettings.disabled() : tunnel;
        sshToggle.setSelected(settings.sshEnabled());
        sshHostField.setText(settings.sshHost());
        sshPortField.setText(Integer.toString(settings.sshPort()));
        sshUserField.setText(settings.sshUser());
        sshKeyField.setText(settings.sshPrivateKeyPath());
        sslToggle.setSelected(settings.sslEnabled());
        sslCaField.setText(settings.sslCaCertPath());
        sslClientField.setText(settings.sslClientCertPath());
        setGroupEnabled(sshFields, settings.sshEnabled());
        setGroupEnabled(sslFields, settings.sslEnabled());
        jdbcRows.setAll(rowsFrom(properties));
    }

    private void wireSavedProfiles() {
        savedBox.setCellFactory(list -> profileCell());
        savedBox.setButtonCell(profileCell());

        savedBox.valueProperty().addListener((observable, previous, selected) -> {
            if (selected == null) {
                editingProfileId = null;
                passwordField.clear();
                deleteProfileButton.setDisable(true);
                return;
            }
            applyProfile(selected);
        });

        deleteProfileButton.setDisable(true);
        deleteProfileButton.setOnAction(event -> deleteSelectedProfile());
    }

    private static ListCell<ConnectionProfile> profileCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.displayName());
            }
        };
    }

    private void reloadSavedProfiles() {
        ConnectionProfile selected = savedBox.getValue();
        String selectedId = selected == null ? null : selected.id();
        List<ConnectionProfile> profiles = profileManager.loadProfiles();
        savedBox.getItems().setAll(profiles);
        if (selectedId != null) {
            profiles.stream()
                    .filter(profile -> selectedId.equals(profile.id()))
                    .findFirst()
                    .ifPresentOrElse(savedBox::setValue, () -> savedBox.setValue(null));
        }
    }

    /**
     * IntelliJ-style: fill host / port / db / user / driver from the profile and
     * <strong>always</strong> wipe the password field.
     */
    private void applyProfile(ConnectionProfile profile) {
        editingProfileId = profile.id();
        nameField.setText(profile.name().isBlank() ? profile.displayName() : profile.name());
        applyEndpoint(
                parseDriver(profile.driver()),
                profile.host(),
                profile.port(),
                profile.database(),
                profile.username());
        applyExtras(profile.environmentTag(), profile.tunnel(), profile.jdbcProperties());
        passwordField.clear();
        passwordField.setText("");
        deleteProfileButton.setDisable(false);
        saveProfileCheck.setSelected(true);
        showFeedback("Password not stored \u2014 enter it to connect.", false);
        refreshUrlPreview();
    }

    private void applyEndpoint(
            ConnectionConfig.Driver driver, String host, int port, String database, String user) {
        ConnectionConfig.Driver resolved = driver == null ? ConnectionConfig.Driver.MYSQL : driver;
        typeBox.setValue(resolved.connectionType());
        if (resolved.isJdbc()) {
            driverBox.setValue(resolved);
        } else if (driverBox.getValue() == null) {
            driverBox.setValue(ConnectionConfig.Driver.MYSQL);
        }
        hostField.setText(host);
        portField.setText(Integer.toString(port));
        databaseField.setText(resolved.isJdbc() ? database : "");
        userField.setText(user);
        applyTypeVisibility(resolved.connectionType());
    }

    private void applyTypeVisibility(ConnectionConfig.ConnectionType type) {
        boolean redis = type != null && type.isRedis();
        setRowVisible(driverLabel, driverBox, !redis);
        setRowVisible(databaseLabel, databaseField, !redis);
        userField.setPromptText(redis ? "ACL username (optional)" : "");
        passwordField.setPromptText(redis ? "Optional" : "");
    }

    private static void setRowVisible(Label label, javafx.scene.Node field, boolean visible) {
        label.setVisible(visible);
        label.setManaged(visible);
        field.setVisible(visible);
        field.setManaged(visible);
    }

    private ConnectionConfig.ConnectionType currentType() {
        ConnectionConfig.ConnectionType type = typeBox.getValue();
        return type == null ? ConnectionConfig.ConnectionType.MYSQL : type;
    }

    private int jdbcDefaultPort() {
        ConnectionConfig.Driver driver = driverBox.getValue();
        return driver == null ? ConnectionConfig.Driver.MYSQL.defaultPort() : driver.defaultPort();
    }

    private void deleteSelectedProfile() {
        ConnectionProfile selected = savedBox.getValue();
        if (selected == null) {
            return;
        }
        profileManager.deleteProfile(selected.id());
        if (selected.id().equals(editingProfileId)) {
            editingProfileId = null;
        }
        savedBox.setValue(null);
        reloadSavedProfiles();
        passwordField.clear();
        showFeedback("Removed saved connection \"" + selected.displayName() + "\".", false);
    }

    private void persistCurrentProfile() {
        try {
            ConnectionProfile profile = toProfile();
            profileManager.saveProfile(profile);
            editingProfileId = profile.id();
            reloadSavedProfiles();
            savedBox.getItems().stream()
                    .filter(item -> profile.id().equals(item.id()))
                    .findFirst()
                    .ifPresent(savedBox::setValue);
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Could not save connection profile", ex);
            showFeedback("Could not save connection profile.", true);
        }
    }

    private void wireValidation() {
        Runnable revalidate = () -> connectButton.setDisable(!isValid());
        hostField.textProperty().addListener((observable, previous, current) -> revalidate.run());
        portField.textProperty().addListener((observable, previous, current) -> revalidate.run());
        revalidate.run();
    }

    private void wireUrlPreview() {
        hostField.textProperty().addListener((obs, previous, current) -> refreshUrlPreview());
        portField.textProperty().addListener((obs, previous, current) -> refreshUrlPreview());
        databaseField.textProperty().addListener((obs, previous, current) -> refreshUrlPreview());
        jdbcRows.addListener((ListChangeListener<PropertyRow>) change -> refreshUrlPreview());
        refreshUrlPreview();
    }

    private void refreshUrlPreview() {
        urlPreview.setText(ConnectionConfig.previewUrl(
                selectedDriver(),
                hostField.getText(),
                portField.getText(),
                currentType().isRedis() ? "" : databaseField.getText(),
                jdbcPropertyMap()));
    }

    private void wireTestButton(DataSourceDriver driver) {
        testButton.setOnAction(event -> {
            if (!isValid()) {
                showFeedback("Host and port are required.", true);
                return;
            }
            testButton.setDisable(true);
            setTesting(true);
            showFeedback("Testing\u2026", false);

            ConnectionConfig config = toConfig();
            DataSourceDriver tester;
            boolean owned = false;
            if (config.connectionType().isRedis()) {
                tester = registry != null ? registry.create(RedisDriver.ID) : new RedisDriver();
                owned = true;
            } else {
                tester = driver;
            }
            DataSourceDriver used = tester;
            boolean closeAfter = owned;
            used.testConnection(config).whenComplete((description, error) -> {
                if (closeAfter) {
                    used.close();
                }
                Platform.runLater(() -> {
                    testButton.setDisable(false);
                    setTesting(false);
                    if (error != null) {
                        showFeedback(rootCauseMessage(error), true);
                    } else {
                        showFeedback("Connected to " + description, false);
                    }
                });
            });
        });
    }

    // ---------------------------------------------------------------- helpers

    private boolean isValid() {
        return !hostField.getText().isBlank() && parsePort() > 0;
    }

    private int parsePort() {
        try {
            int port = Integer.parseInt(portField.getText().trim());
            return port >= 1 && port <= 65_535 ? port : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private int parseSshPort() {
        try {
            int port = Integer.parseInt(sshPortField.getText().trim());
            return port >= 1 && port <= 65_535 ? port : 22;
        } catch (NumberFormatException e) {
            return 22;
        }
    }

    private ConnectionConfig toConfig() {
        ConnectionConfig.Driver driver = selectedDriver();
        return new ConnectionConfig(
                hostField.getText().trim(),
                parsePort(),
                currentType().isRedis() ? "" : databaseField.getText().trim(),
                userField.getText().trim(),
                passwordField.getText(),
                driver,
                currentEnvironment(),
                jdbcPropertyMap(),
                toTunnel());
    }

    private ConnectionProfile toProfile() {
        ConnectionConfig.Driver driver = selectedDriver();
        String id = editingProfileId == null || editingProfileId.isBlank()
                ? UUID.randomUUID().toString()
                : editingProfileId;
        String name = nameField.getText().trim();
        if (name.isBlank()) {
            name = defaultName(driver, hostField.getText().trim(), parsePort(), databaseField.getText().trim());
        }
        return new ConnectionProfile(
                id,
                name,
                driver.name(),
                hostField.getText().trim(),
                parsePort(),
                currentType().isRedis() ? "" : databaseField.getText().trim(),
                userField.getText().trim(),
                currentEnvironment().name(),
                toTunnel(),
                jdbcPropertyMap());
    }

    private TunnelSettings toTunnel() {
        return new TunnelSettings(
                sshToggle.isSelected(),
                sshHostField.getText(),
                parseSshPort(),
                sshUserField.getText(),
                sshKeyField.getText(),
                sslToggle.isSelected(),
                sslCaField.getText(),
                sslClientField.getText());
    }

    private Environment currentEnvironment() {
        Environment environment = environmentBox.getValue();
        return environment == null ? Environment.NONE : environment;
    }

    private Map<String, String> jdbcPropertyMap() {
        Map<String, String> map = new LinkedHashMap<>();
        for (PropertyRow row : jdbcRows) {
            String key = row.key.get();
            if (key == null || key.isBlank()) {
                continue;
            }
            String value = row.value.get();
            map.put(key.trim(), value == null ? "" : value);
        }
        return map;
    }

    private static List<PropertyRow> rowsFrom(Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return List.of();
        }
        return properties.entrySet().stream()
                .map(entry -> new PropertyRow(entry.getKey(), entry.getValue()))
                .toList();
    }

    private ConnectionConfig.Driver selectedDriver() {
        if (currentType().isRedis()) {
            return ConnectionConfig.Driver.REDIS;
        }
        ConnectionConfig.Driver driver = driverBox.getValue();
        return driver == null || !driver.isJdbc() ? ConnectionConfig.Driver.MYSQL : driver;
    }

    private static String defaultName(ConnectionConfig.Driver driver, String host, int port, String database) {
        String schema = database == null || database.isBlank() ? "" : "/" + database;
        return "%s @ %s:%d%s".formatted(driver.displayName(), host, port, schema);
    }

    private static ConnectionConfig.Driver parseDriver(String raw) {
        if (raw == null || raw.isBlank()) {
            return ConnectionConfig.Driver.MYSQL;
        }
        try {
            return ConnectionConfig.Driver.valueOf(raw.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            // fall through — match display names too
        }
        for (ConnectionConfig.Driver driver : ConnectionConfig.Driver.values()) {
            if (driver.displayName().equalsIgnoreCase(raw.trim())
                    || driver.name().equalsIgnoreCase(raw.trim())) {
                return driver;
            }
        }
        return ConnectionConfig.Driver.MYSQL;
    }

    private void showFeedback(String message, boolean error) {
        feedback.setText(message);
        feedback.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), error);
    }

    private void setTesting(boolean testing) {
        testButton.setGraphic(testing ? testSpinner : null);
        testButton.setText(testing ? "Testing\u2026" : "Test Connection");
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }

    private static String stylesheet() {
        return java.util.Objects.requireNonNull(
                        ConnectionDialog.class.getResource("/com/lazaro/sqlide/css/app.css"),
                        "app.css is missing from the classpath")
                .toExternalForm();
    }

    private static final class PropertyRow {
        private final SimpleStringProperty key = new SimpleStringProperty();
        private final SimpleStringProperty value = new SimpleStringProperty();

        private PropertyRow(String key, String value) {
            this.key.set(key == null ? "" : key);
            this.value.set(value == null ? "" : value);
        }
    }
}
