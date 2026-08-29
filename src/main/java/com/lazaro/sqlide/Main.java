package com.lazaro.sqlide;

import atlantafx.base.theme.CupertinoDark;
import com.lazaro.sqlide.core.db.DriverRegistry;
import com.lazaro.sqlide.ui.MainController;
import com.lazaro.sqlide.ui.WorkspaceState;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

/** Application entry point: theme, window geometry and controller wiring. */
public final class Main extends Application {

    private static final String APP_TITLE = "SQL IDE";
    private static final int MIN_WINDOW_WIDTH = 640;
    private static final int MIN_WINDOW_HEIGHT = 460;

    private MainController controller;
    private WorkspaceState workspaceState;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());

        workspaceState = new WorkspaceState();
        controller = new MainController(DriverRegistry.withDefaults(), workspaceState);

        Scene scene = new Scene(controller.createView());
        scene.getStylesheets().add(stylesheet());
        controller.installShortcuts(scene);

        stage.setScene(scene);
        stage.setTitle(APP_TITLE);
        stage.setMinWidth(MIN_WINDOW_WIDTH);
        stage.setMinHeight(MIN_WINDOW_HEIGHT);
        workspaceState.restoreWindow(stage);

        stage.setOnCloseRequest(event -> {
            if (controller.confirmExit()) {
                controller.saveState(stage);
            } else {
                event.consume();
            }
        });
        stage.show();

        // Divider positions only stick once the split panes have been laid out.
        controller.restoreLayout();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static String stylesheet() {
        return Objects.requireNonNull(
                        Main.class.getResource("/com/lazaro/sqlide/css/app.css"),
                        "app.css is missing from the classpath")
                .toExternalForm();
    }
}
