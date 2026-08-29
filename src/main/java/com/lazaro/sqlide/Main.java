package com.lazaro.sqlide;

import atlantafx.base.theme.CupertinoDark;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

/**
 * Application entry point. Phase 1 only bootstraps the window and the theme;
 * the schema tree, SQL editor and result grid are wired in later phases.
 */
public final class Main extends Application {

    private static final String APP_TITLE = "SQL IDE";
    private static final int WINDOW_WIDTH = 1024;
    private static final int WINDOW_HEIGHT = 768;
    private static final int MIN_WINDOW_WIDTH = 800;
    private static final int MIN_WINDOW_HEIGHT = 600;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());

        var root = new BorderPane();
        var scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);

        stage.setScene(scene);
        stage.setTitle(APP_TITLE);
        stage.setMinWidth(MIN_WINDOW_WIDTH);
        stage.setMinHeight(MIN_WINDOW_HEIGHT);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
