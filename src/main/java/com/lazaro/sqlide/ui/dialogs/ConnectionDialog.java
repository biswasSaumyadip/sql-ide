package com.lazaro.sqlide.ui.dialogs;

import com.lazaro.sqlide.core.config.ConnectionProfile;
import com.lazaro.sqlide.core.config.ConnectionProfileManager;
import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Modal credential prompt with IntelliJ-style saved connections. Profiles store
 * everything except the password.
 */
public final class ConnectionDialog extends Dialog<ConnectionConfig> {

    private static final Logger LOG = Logger.getLogger(ConnectionDialog.class.getName());
    private static final ButtonType TEST = new ButtonType("Test", ButtonBar.ButtonData.LEFT);
    private static final String NEW_CONNECTION = "New connection";

    private final ConnectionProfileManager profileManager;
    private final ComboBox<ConnectionProfile> savedBox = new ComboBox<>();
    private final TextField nameField = new TextField();
    private final CheckBox saveProfileCheck = new CheckBox("Save connection");
    private final Button deleteProfileButton = new Button("Delete");

    private final ComboBox<ConnectionConfig.Driver> driverBox = new ComboBox<>();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final TextField databaseField = new TextField();
    private final TextField userField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final Label feedback = new Label();
    private final ProgressIndicator testActivity = new ProgressIndicator();

    /** Id of the profile currently loaded into the form (for update-on-save). */
    private String editingProfileId;

    public ConnectionDialog(ConnectionConfig initial, DataSourceDriver driver) {
        this(initial, driver, new ConnectionProfileManager());
    }

    ConnectionDialog(ConnectionConfig initial, DataSourceDriver driver, ConnectionProfileManager profileManager) {
        this.profileManager = profileManager;
        setTitle("Connect to database");
        setHeaderText("Data source settings");
        setResizable(true);

        getDialogPane().getButtonTypes().setAll(TEST, ButtonType.OK, ButtonType.CANCEL);
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        ok.setText("Connect");

        getDialogPane().setContent(buildForm());
        getDialogPane().getStyleClass().add("connection-dialog");

        reloadSavedProfiles();
        populate(initial);
        wireSavedProfiles();
        wireValidation();
        wireTestButton(driver);

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
        Platform.runLater(hostField::requestFocus);
    }

    // ---------------------------------------------------------------- form

    private VBox buildForm() {
        driverBox.getItems().setAll(ConnectionConfig.Driver.values());
        driverBox.setMaxWidth(Double.MAX_VALUE);
        portField.setPrefColumnCount(6);
        feedback.getStyleClass().add("dialog-feedback");
        feedback.setWrapText(true);

        testActivity.setMaxSize(16, 16);
        testActivity.getStyleClass().add("dialog-activity");
        testActivity.setVisible(false);
        testActivity.setManaged(false);

        nameField.setPromptText("Connection name");
        savedBox.setMaxWidth(Double.MAX_VALUE);
        savedBox.setPromptText(NEW_CONNECTION);
        HBox.setHgrow(savedBox, Priority.ALWAYS);
        deleteProfileButton.setTooltip(new Tooltip("Remove the selected saved connection"));
        deleteProfileButton.getStyleClass().add("connection-delete");

        HBox savedRow = new HBox(8, savedBox, deleteProfileButton);
        savedRow.setAlignment(Pos.CENTER_LEFT);

        HBox saveRow = new HBox(12, saveProfileCheck);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        GridPane savedGrid = new GridPane();
        savedGrid.setHgap(10);
        savedGrid.setVgap(8);
        ColumnConstraints labels = labelColumn();
        ColumnConstraints fields = fieldColumn();
        savedGrid.getColumnConstraints().addAll(labels, fields);
        savedGrid.addRow(0, new Label("Saved"), savedRow);
        savedGrid.addRow(1, new Label("Name"), nameField);
        savedGrid.add(saveRow, 1, 2);

        GridPane detailGrid = new GridPane();
        detailGrid.setHgap(10);
        detailGrid.setVgap(8);
        detailGrid.getColumnConstraints().addAll(labelColumn(), fieldColumn());
        int row = 0;
        detailGrid.addRow(row++, new Label("Driver"), driverBox);
        detailGrid.addRow(row++, new Label("Host"), hostField);
        detailGrid.addRow(row++, new Label("Port"), portField);
        detailGrid.addRow(row++, new Label("Database"), databaseField);
        detailGrid.addRow(row++, new Label("User"), userField);
        detailGrid.addRow(row++, new Label("Password"), passwordField);

        HBox feedbackRow = new HBox(8, testActivity, feedback);
        feedbackRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(feedback, Priority.ALWAYS);
        detailGrid.add(feedbackRow, 1, row);

        Separator divider = new Separator();
        divider.getStyleClass().add("connection-separator");

        VBox root = new VBox(12, savedGrid, divider, detailGrid);
        root.setPadding(new Insets(4, 0, 0, 0));
        return root;
    }

    private static ColumnConstraints labelColumn() {
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(78);
        return labels;
    }

    private static ColumnConstraints fieldColumn() {
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        return fields;
    }

    private void populate(ConnectionConfig initial) {
        ConnectionConfig source = initial != null
                ? initial
                : ConnectionConfig.mysql("localhost", ConnectionConfig.Driver.MYSQL.defaultPort(), "", "root", "");

        applyEndpoint(source.driver(), source.host(), source.port(), source.database(), source.user());
        // Never carry a remembered password into the dialog.
        passwordField.clear();
        editingProfileId = null;
        nameField.setText(defaultName(source.driver(), source.host(), source.port(), source.database()));
        saveProfileCheck.setSelected(false);

        driverBox.valueProperty().addListener((observable, previous, current) -> {
            if (current != null && (previous == null || portField.getText().equals(
                    Integer.toString(previous.defaultPort())))) {
                portField.setText(Integer.toString(current.defaultPort()));
            }
        });
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
        passwordField.clear();
        passwordField.setText("");
        deleteProfileButton.setDisable(false);
        saveProfileCheck.setSelected(true);
        showFeedback("Password not stored — enter it to connect.", false);
    }

    private void applyEndpoint(
            ConnectionConfig.Driver driver, String host, int port, String database, String user) {
        driverBox.setValue(driver);
        hostField.setText(host);
        portField.setText(Integer.toString(port));
        databaseField.setText(database);
        userField.setText(user);
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
        Button ok = (Button) getDialogPane().lookupButton(ButtonType.OK);
        Runnable revalidate = () -> ok.setDisable(!isValid());
        hostField.textProperty().addListener((observable, previous, current) -> revalidate.run());
        portField.textProperty().addListener((observable, previous, current) -> revalidate.run());
        revalidate.run();
    }

    private void wireTestButton(DataSourceDriver driver) {
        Button test = (Button) getDialogPane().lookupButton(TEST);
        test.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (!isValid()) {
                showFeedback("Host and port are required.", true);
                return;
            }
            test.setDisable(true);
            setTesting(true);
            showFeedback("Testing\u2026", false);

            driver.testConnection(toConfig()).whenComplete((description, error) -> Platform.runLater(() -> {
                test.setDisable(false);
                setTesting(false);
                if (error != null) {
                    showFeedback(rootCauseMessage(error), true);
                } else {
                    showFeedback("Connected to " + description, false);
                }
            }));
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

    private ConnectionConfig toConfig() {
        return new ConnectionConfig(
                hostField.getText().trim(),
                parsePort(),
                databaseField.getText().trim(),
                userField.getText().trim(),
                passwordField.getText(),
                driverBox.getValue());
    }

    private ConnectionProfile toProfile() {
        ConnectionConfig.Driver driver = driverBox.getValue() == null
                ? ConnectionConfig.Driver.MYSQL
                : driverBox.getValue();
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
                databaseField.getText().trim(),
                userField.getText().trim());
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
        testActivity.setVisible(testing);
        testActivity.setManaged(testing);
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
    }
}
