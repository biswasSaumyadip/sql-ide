package com.lazaro.sqlide.ui.components;

import atlantafx.base.theme.Styles;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

/**
 * Live URL strip + request log shown under a result grid while the mock API is running.
 */
final class MockApiPanel extends VBox {

    private static final int LOG_CAP = 200;

    private final Circle liveDot = new Circle(4.5);
    private final Label urlLabel = new Label();
    private final Button copyButton = new Button("Copy URL");
    private final ObservableList<String> logItems = FXCollections.observableArrayList();
    private final ListView<String> trafficLog = new ListView<>(logItems);

    private String url = "";

    MockApiPanel() {
        getStyleClass().add("mock-api-panel");
        setSpacing(0);

        liveDot.getStyleClass().add("mock-api-live-dot");

        Label liveLabel = new Label("Live");
        liveLabel.getStyleClass().add("mock-api-live-label");

        urlLabel.getStyleClass().add("mock-api-url");
        urlLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(urlLabel, Priority.ALWAYS);

        copyButton.getStyleClass().addAll(Styles.FLAT, "mock-api-copy");
        copyButton.setTooltip(new Tooltip("Copy the mock endpoint URL"));
        copyButton.setOnAction(event -> copyUrl());

        HBox status = new HBox(8, liveDot, liveLabel, urlLabel, copyButton);
        status.getStyleClass().add("mock-api-status");
        status.setAlignment(Pos.CENTER_LEFT);
        status.setPadding(new Insets(5, 10, 5, 10));

        trafficLog.getStyleClass().add("mock-api-log");
        trafficLog.setFocusTraversable(false);
        trafficLog.setPrefHeight(72);
        trafficLog.setPlaceholder(new Label("Waiting for requests\u2026"));

        getChildren().addAll(status, trafficLog);
        setVisible(false);
        setManaged(false);
    }

    void show(String endpointUrl) {
        this.url = endpointUrl == null ? "" : endpointUrl;
        urlLabel.setText(url);
        urlLabel.setTooltip(new Tooltip(url));
        logItems.clear();
        setVisible(true);
        setManaged(true);
    }

    void hide() {
        setVisible(false);
        setManaged(false);
        url = "";
        urlLabel.setText("");
        logItems.clear();
    }

    void appendLog(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        Runnable append = () -> {
            logItems.add(line);
            while (logItems.size() > LOG_CAP) {
                logItems.removeFirst();
            }
            trafficLog.scrollTo(logItems.size() - 1);
        };
        if (Platform.isFxApplicationThread()) {
            append.run();
        } else {
            Platform.runLater(append);
        }
    }

    private void copyUrl() {
        if (url.isBlank()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(url);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
