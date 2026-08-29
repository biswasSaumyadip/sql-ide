package com.lazaro.sqlide.ui;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.prefs.Preferences;

/**
 * Remembers window geometry, split positions and the last endpoint across launches,
 * using the Preferences API so there is no file to manage.
 *
 * <p>Passwords are never stored.
 */
public final class WorkspaceState {

    private static final String WINDOW_X = "window.x";
    private static final String WINDOW_Y = "window.y";
    private static final String WINDOW_WIDTH = "window.width";
    private static final String WINDOW_HEIGHT = "window.height";
    private static final String WINDOW_MAXIMIZED = "window.maximized";
    private static final String SPLIT_MAIN = "split.main";
    private static final String SPLIT_RIGHT = "split.right";
    private static final String SIDEBAR_COLLAPSED = "sidebar.collapsed";

    private static final String LAST_DRIVER = "connection.driver";
    private static final String LAST_HOST = "connection.host";
    private static final String LAST_PORT = "connection.port";
    private static final String LAST_DATABASE = "connection.database";
    private static final String LAST_USER = "connection.user";

    private static final double DEFAULT_WIDTH = 1280;
    private static final double DEFAULT_HEIGHT = 800;

    private final Preferences preferences = Preferences.userNodeForPackage(WorkspaceState.class);

    // ---------------------------------------------------------------- window

    public void restoreWindow(Stage stage) {
        double width = preferences.getDouble(WINDOW_WIDTH, DEFAULT_WIDTH);
        double height = preferences.getDouble(WINDOW_HEIGHT, DEFAULT_HEIGHT);
        stage.setWidth(Math.max(width, stage.getMinWidth()));
        stage.setHeight(Math.max(height, stage.getMinHeight()));

        double x = preferences.getDouble(WINDOW_X, Double.NaN);
        double y = preferences.getDouble(WINDOW_Y, Double.NaN);
        if (!Double.isNaN(x) && !Double.isNaN(y) && isOnAVisibleScreen(x, y, width, height)) {
            stage.setX(x);
            stage.setY(y);
        } else {
            stage.centerOnScreen();
        }

        stage.setMaximized(preferences.getBoolean(WINDOW_MAXIMIZED, false));
    }

    public void saveWindow(Stage stage) {
        preferences.putBoolean(WINDOW_MAXIMIZED, stage.isMaximized());
        // A maximized stage reports the screen bounds; keeping the restored size
        // means un-maximizing after a restart returns to the user's own size.
        if (!stage.isMaximized()) {
            preferences.putDouble(WINDOW_X, stage.getX());
            preferences.putDouble(WINDOW_Y, stage.getY());
            preferences.putDouble(WINDOW_WIDTH, stage.getWidth());
            preferences.putDouble(WINDOW_HEIGHT, stage.getHeight());
        }
    }

    /** Guards against restoring onto a monitor that is no longer attached. */
    private static boolean isOnAVisibleScreen(double x, double y, double width, double height) {
        return !Screen.getScreensForRectangle(new Rectangle2D(x, y, Math.max(width, 1), Math.max(height, 1)))
                .isEmpty();
    }

    // ---------------------------------------------------------------- layout

    public double mainDivider(double fallback) {
        return preferences.getDouble(SPLIT_MAIN, fallback);
    }

    public double rightDivider(double fallback) {
        return preferences.getDouble(SPLIT_RIGHT, fallback);
    }

    public boolean sidebarCollapsed() {
        return preferences.getBoolean(SIDEBAR_COLLAPSED, false);
    }

    public void saveLayout(double mainDivider, double rightDivider, boolean sidebarCollapsed) {
        preferences.putDouble(SPLIT_MAIN, mainDivider);
        preferences.putDouble(SPLIT_RIGHT, rightDivider);
        preferences.putBoolean(SIDEBAR_COLLAPSED, sidebarCollapsed);
    }

    // ---------------------------------------------------------------- connection

    /** Last endpoint, for pre-filling the connect dialog. The password is not kept. */
    public ConnectionConfig lastConnection() {
        String host = preferences.get(LAST_HOST, null);
        if (host == null || host.isBlank()) {
            return null;
        }
        ConnectionConfig.Driver driver;
        try {
            driver = ConnectionConfig.Driver.valueOf(
                    preferences.get(LAST_DRIVER, ConnectionConfig.Driver.MYSQL.name()));
        } catch (IllegalArgumentException e) {
            driver = ConnectionConfig.Driver.MYSQL;
        }
        return new ConnectionConfig(
                host,
                preferences.getInt(LAST_PORT, driver.defaultPort()),
                preferences.get(LAST_DATABASE, ""),
                preferences.get(LAST_USER, ""),
                "",
                driver);
    }

    public void saveLastConnection(ConnectionConfig config) {
        preferences.put(LAST_DRIVER, config.driver().name());
        preferences.put(LAST_HOST, config.host());
        preferences.putInt(LAST_PORT, config.port());
        preferences.put(LAST_DATABASE, config.database());
        preferences.put(LAST_USER, config.user());
    }
}
