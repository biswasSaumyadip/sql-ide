package com.lazaro.sqlide.ui.dialogs;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.DataSourceDriver;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

/**
 * Modal credential prompt. Offers a Test button that probes the endpoint without
 * disturbing whatever connection is currently open.
 */
public final class ConnectionDialog extends Dialog<ConnectionConfig> {

    private static final ButtonType TEST = new ButtonType("Test", ButtonBar.ButtonData.LEFT);

    private final ComboBox<ConnectionConfig.Driver> driverBox = new ComboBox<>();
    private final TextField hostField = new TextField();
    private final TextField portField = new TextField();
    private final TextField databaseField = new TextField();
    private final TextField userField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final Label feedback = new Label();
    private final ProgressIndicator testActivity = new ProgressIndicator();

    public ConnectionDialog(ConnectionConfig initial, DataSourceDriver driver) {
        setTitle("Connect to database");
        setHeaderText("Enter the JDBC connection details.");
        setResizable(true);

        getDialogPane().getButtonTypes().setAll(TEST, ButtonType.OK, ButtonType.CANCEL);
        getDialogPane().setContent(buildForm());
        getDialogPane().getStyleClass().add("connection-dialog");

        populate(initial);
        wireValidation();
        wireTestButton(driver);

        setResultConverter(button -> button == ButtonType.OK ? toConfig() : null);
        Platform.runLater(hostField::requestFocus);
    }

    // ---------------------------------------------------------------- form

    private GridPane buildForm() {
        driverBox.getItems().setAll(ConnectionConfig.Driver.values());
        driverBox.setMaxWidth(Double.MAX_VALUE);
        portField.setPrefColumnCount(6);
        feedback.getStyleClass().add("dialog-feedback");
        feedback.setWrapText(true);

        testActivity.setMaxSize(16, 16);
        testActivity.getStyleClass().add("dialog-activity");
        testActivity.setVisible(false);
        testActivity.setManaged(false);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setPadding(new Insets(4, 0, 0, 0));

        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(78);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setFillWidth(true);
        grid.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        grid.addRow(row++, new Label("Driver"), driverBox);
        grid.addRow(row++, new Label("Host"), hostField);
        grid.addRow(row++, new Label("Port"), portField);
        grid.addRow(row++, new Label("Database"), databaseField);
        grid.addRow(row++, new Label("User"), userField);
        grid.addRow(row++, new Label("Password"), passwordField);

        HBox feedbackRow = new HBox(8, testActivity, feedback);
        feedbackRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(feedback, Priority.ALWAYS);
        grid.add(feedbackRow, 1, row);

        return grid;
    }

    private void populate(ConnectionConfig initial) {
        ConnectionConfig source = initial != null
                ? initial
                : ConnectionConfig.mysql("localhost", ConnectionConfig.Driver.MYSQL.defaultPort(), "", "root", "");

        driverBox.setValue(source.driver());
        hostField.setText(source.host());
        portField.setText(Integer.toString(source.port()));
        databaseField.setText(source.database());
        userField.setText(source.user());
        passwordField.setText(source.password());

        // Switching driver moves the port to that driver's default, unless the user
        // has already typed a port that is not simply another driver's default.
        driverBox.valueProperty().addListener((observable, previous, current) -> {
            if (current != null && (previous == null || portField.getText().equals(
                    Integer.toString(previous.defaultPort())))) {
                portField.setText(Integer.toString(current.defaultPort()));
            }
        });
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
            // Keep the dialog open: this button reports, it does not commit.
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
