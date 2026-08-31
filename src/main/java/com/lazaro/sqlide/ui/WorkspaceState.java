package com.lazaro.sqlide.ui;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.ui.autocomplete.SqlCompletionHygiene.KeywordCasing;
import javafx.geometry.Rectangle2D;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.prefs.BackingStoreException;
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
    private static final String AUTO_COMMIT = "execution.autoCommit";
    private static final String STOP_AUTO_REFRESH_ON_ERROR = "results.stopAutoRefreshOnError";
    private static final String MAX_ROWS = "results.maxRows";
    private static final String LOWER_KEYWORDS = "completion.lowerKeywords";
    private static final String KEYWORD_CASING = "completion.keywordCasing";
    private static final String AUTO_QUOTE = "completion.autoQuoteReserved";
    private static final String PRESERVE_DB_CASING = "completion.preserveDbCasing";
    private static final String AUTO_TABLE_ALIASES = "completion.autoGenerateTableAliases";
    private static final String SUGGEST_JOIN_COLUMNS = "completion.suggestJoinColumns";
    private static final String EDITOR_FONT_FAMILY = "editor.fontFamily";
    private static final String EDITOR_FONT_SIZE = "editor.fontSize";
    private static final String EDITOR_WORD_WRAP = "editor.wordWrap";
    private static final String CONFIRM_DANGEROUS_DML = "execution.confirmDangerousDml";
    private static final String DEFAULT_EDITOR_FONT = "JetBrains Mono";

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

    // ---------------------------------------------------------------- execution

    public boolean autoCommit() {
        return preferences.getBoolean(AUTO_COMMIT, true);
    }

    public void saveAutoCommit(boolean autoCommit) {
        preferences.putBoolean(AUTO_COMMIT, autoCommit);
    }

    public boolean stopAutoRefreshOnError() {
        return preferences.getBoolean(STOP_AUTO_REFRESH_ON_ERROR, true);
    }

    public void saveStopAutoRefreshOnError(boolean stop) {
        preferences.putBoolean(STOP_AUTO_REFRESH_ON_ERROR, stop);
    }

    public int maxRows() {
        return preferences.getInt(MAX_ROWS, 1_000);
    }

    public void saveMaxRows(int maxRows) {
        preferences.putInt(MAX_ROWS, Math.max(1, maxRows));
    }

    public boolean confirmDangerousDml() {
        return preferences.getBoolean(CONFIRM_DANGEROUS_DML, true);
    }

    public void saveConfirmDangerousDml(boolean confirm) {
        preferences.putBoolean(CONFIRM_DANGEROUS_DML, confirm);
    }

    // ---------------------------------------------------------------- editor

    public String editorFontFamily() {
        String family = preferences.get(EDITOR_FONT_FAMILY, DEFAULT_EDITOR_FONT);
        return family == null || family.isBlank() ? DEFAULT_EDITOR_FONT : family;
    }

    public void saveEditorFontFamily(String family) {
        String value = family == null || family.isBlank() ? DEFAULT_EDITOR_FONT : family.strip();
        preferences.put(EDITOR_FONT_FAMILY, value);
    }

    public int editorFontSize() {
        return Math.clamp(preferences.getInt(EDITOR_FONT_SIZE, 13), 10, 22);
    }

    public void saveEditorFontSize(int size) {
        preferences.putInt(EDITOR_FONT_SIZE, Math.clamp(size, 10, 22));
    }

    public boolean editorWordWrap() {
        return preferences.getBoolean(EDITOR_WORD_WRAP, false);
    }

    public void saveEditorWordWrap(boolean wrap) {
        preferences.putBoolean(EDITOR_WORD_WRAP, wrap);
    }

    // ---------------------------------------------------------------- completion hygiene

    public KeywordCasing keywordCasing() {
        String stored = preferences.get(KEYWORD_CASING, null);
        if (stored != null && !stored.isBlank()) {
            return KeywordCasing.parse(stored);
        }
        return preferences.getBoolean(LOWER_KEYWORDS, false)
                ? KeywordCasing.LOWERCASE
                : KeywordCasing.UPPERCASE;
    }

    public void saveKeywordCasing(KeywordCasing casing) {
        KeywordCasing value = casing == null ? KeywordCasing.UPPERCASE : casing;
        preferences.put(KEYWORD_CASING, value.name());
        preferences.putBoolean(LOWER_KEYWORDS, value == KeywordCasing.LOWERCASE);
    }

    public boolean lowerKeywords() {
        return keywordCasing() == KeywordCasing.LOWERCASE;
    }

    public void saveLowerKeywords(boolean lowerKeywords) {
        saveKeywordCasing(lowerKeywords ? KeywordCasing.LOWERCASE : KeywordCasing.UPPERCASE);
    }

    public boolean autoGenerateTableAliases() {
        return preferences.getBoolean(AUTO_TABLE_ALIASES, false);
    }

    public void saveAutoGenerateTableAliases(boolean enabled) {
        preferences.putBoolean(AUTO_TABLE_ALIASES, enabled);
    }

    public boolean suggestJoinColumns() {
        return preferences.getBoolean(SUGGEST_JOIN_COLUMNS, true);
    }

    public void saveSuggestJoinColumns(boolean enabled) {
        preferences.putBoolean(SUGGEST_JOIN_COLUMNS, enabled);
    }

    public boolean autoQuoteReserved() {
        return preferences.getBoolean(AUTO_QUOTE, true);
    }

    public void saveAutoQuoteReserved(boolean autoQuote) {
        preferences.putBoolean(AUTO_QUOTE, autoQuote);
    }

    public boolean preserveDbCasing() {
        return preferences.getBoolean(PRESERVE_DB_CASING, true);
    }

    public void savePreserveDbCasing(boolean preserve) {
        preferences.putBoolean(PRESERVE_DB_CASING, preserve);
    }

    // ---------------------------------------------------------------- schema diagram layout

    /**
     * Saved table positions for one diagram ({@code tableId → [x, y]}).
     * Empty when the user has not rearranged that diagram yet.
     */
    public Map<String, double[]> diagramLayout(String layoutKey) {
        Map<String, double[]> positions = new LinkedHashMap<>();
        Preferences node = diagramNode(layoutKey);
        try {
            for (String key : node.keys()) {
                double[] xy = parseXy(node.get(key, null));
                if (xy != null) {
                    positions.put(key, xy);
                }
            }
        } catch (BackingStoreException ignored) {
            return Map.of();
        }
        return positions;
    }

    public void saveDiagramLayout(String layoutKey, Map<String, double[]> positions) {
        Preferences node = diagramNode(layoutKey);
        try {
            node.clear();
        } catch (BackingStoreException ignored) {
            return;
        }
        if (positions == null || positions.isEmpty()) {
            return;
        }
        for (Map.Entry<String, double[]> entry : positions.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null
                    || entry.getValue().length < 2) {
                continue;
            }
            String key = clipPrefKey(entry.getKey());
            node.put(key, formatXy(entry.getValue()[0], entry.getValue()[1]));
        }
    }

    public void clearDiagramLayout(String layoutKey) {
        try {
            diagramNode(layoutKey).removeNode();
        } catch (BackingStoreException | IllegalStateException ignored) {
            // next open falls back to automatic layout
        }
    }

    private Preferences diagramNode(String layoutKey) {
        return preferences.node("diagram").node(clipPrefKey(layoutKey == null ? "default" : layoutKey));
    }

    static String clipPrefKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "default";
        }
        String sanitized = raw.replaceAll("[^A-Za-z0-9._-]", "_")
                .replaceAll("_+", "_")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (sanitized.isBlank()) {
            return "default";
        }
        return sanitized.length() <= 80 ? sanitized : sanitized.substring(0, 80);
    }

    static String formatXy(double x, double y) {
        return x + "," + y;
    }

    static double[] parseXy(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        int comma = raw.indexOf(',');
        if (comma <= 0 || comma == raw.length() - 1) {
            return null;
        }
        try {
            double x = Double.parseDouble(raw.substring(0, comma).strip());
            double y = Double.parseDouble(raw.substring(comma + 1).strip());
            if (!Double.isFinite(x) || !Double.isFinite(y)) {
                return null;
            }
            return new double[] {x, y};
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
