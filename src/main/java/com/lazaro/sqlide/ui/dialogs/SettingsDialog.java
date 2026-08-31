package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.ui.WorkspaceState;
import com.lazaro.sqlide.ui.autocomplete.SqlCompletionHygiene.KeywordCasing;
import javafx.collections.FXCollections;
import javafx.event.EventTarget;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.util.StringConverter;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Application settings. Persists through {@link WorkspaceState}.
 *
 * <p>Left-sidebar categories keep each page small as options grow.
 * The window is undecorated so the caption bar can match the IDE chrome;
 * AtlantaFX cannot hint dark OS decorations on JavaFX 21 / Windows.
 */
public final class SettingsDialog extends Dialog<Boolean> {

    private enum Category {
        GENERAL("General"),
        EDITOR("Editor"),
        COMPLETION("Code Completion"),
        EXECUTION("Execution");

        private final String title;

        Category(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private enum ResizeEdge {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    private static final String[] EDITOR_FONTS = {
            "JetBrains Mono", "Cascadia Mono", "Consolas", "Menlo", "Courier New", "monospace"
    };
    private static final double RESIZE_BAND = 6;

    private final WorkspaceState state;
    private final Map<Category, Node> pages = new EnumMap<>(Category.class);

    private final CheckBox autoCommit = new CheckBox("Auto-commit new connections");
    private final ComboBox<String> fontFamily = new ComboBox<>();
    private final Spinner<Integer> fontSize = new Spinner<>();
    private final CheckBox wordWrap = new CheckBox("Enable word wrap");
    private final ComboBox<KeywordCasing> keywordCasing = new ComboBox<>();
    private final CheckBox autoQuote = new CheckBox("Auto-quote reserved identifiers");
    private final CheckBox preserveCasing = new CheckBox("Preserve database object casing");
    private final CheckBox autoTableAliases = new CheckBox("Auto-generate table aliases");
    private final CheckBox suggestJoinColumns = new CheckBox("Suggest columns on JOIN");
    private final Spinner<Integer> resultLimit = new Spinner<>();
    private final Spinner<Integer> mockApiLatency = new Spinner<>();
    private final CheckBox confirmDangerousDml = new CheckBox(
            "Confirm before executing DELETE/UPDATE without WHERE");

    private Button maximizeButton;
    private boolean maximized;
    private double restoreX;
    private double restoreY;
    private double restoreWidth;
    private double restoreHeight;
    private ResizeEdge resizeEdge = ResizeEdge.NONE;
    private boolean resizing;
    private double resizeAnchorX;
    private double resizeAnchorY;
    private double resizeStartX;
    private double resizeStartY;
    private double resizeStartWidth;
    private double resizeStartHeight;

    /**
     * @return {@code true} when the user saved changes
     */
    public SettingsDialog(WorkspaceState state) {
        this.state = state;
        initStyle(StageStyle.UNDECORATED);
        setTitle("Settings");
        setHeaderText(null);
        setGraphic(null);
        setResizable(true);

        getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        ((Button) getDialogPane().lookupButton(ButtonType.OK)).setText("Save");

        getDialogPane().getStyleClass().add("settings-dialog");
        getDialogPane().getStylesheets().add(stylesheet());
        getDialogPane().setContent(buildRoot());
        getDialogPane().setPrefSize(720, 536);
        getDialogPane().setMinSize(560, 400);
        loadFromState();

        setOnShown(event -> {
            Stage stage = dialogStage();
            if (stage != null) {
                stage.setMinWidth(560);
                stage.setMinHeight(400);
                installResizeSupport(stage);
            }
        });

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                saveToState();
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
    }

    private BorderPane buildRoot() {
        ListView<Category> nav = new ListView<>(FXCollections.observableArrayList(Category.values()));
        nav.getStyleClass().add("settings-nav");
        nav.setPrefWidth(176);
        nav.setMinWidth(148);
        nav.setMaxWidth(200);
        nav.setFixedCellSize(36);

        StackPane content = new StackPane();
        content.getStyleClass().add("settings-content");
        pages.put(Category.GENERAL, generalPage());
        pages.put(Category.EDITOR, editorPage());
        pages.put(Category.COMPLETION, completionPage());
        pages.put(Category.EXECUTION, executionPage());
        content.getChildren().addAll(pages.values());

        nav.getSelectionModel().selectedItemProperty().addListener((observable, previous, current) -> {
            if (current != null) {
                showPage(current);
            }
        });
        nav.getSelectionModel().select(Category.GENERAL);

        BorderPane body = new BorderPane();
        body.getStyleClass().add("settings-body");
        body.setLeft(nav);
        body.setCenter(content);

        BorderPane root = new BorderPane();
        root.getStyleClass().add("settings-root");
        root.setTop(buildTitleBar());
        root.setCenter(body);
        return root;
    }

    private HBox buildTitleBar() {
        Label title = new Label("Settings");
        title.getStyleClass().add("settings-title-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = windowButton("\u2013", "settings-window-min", "Minimize");
        minimize.setOnAction(event -> {
            Stage stage = dialogStage();
            if (stage != null) {
                stage.setIconified(true);
            }
        });

        maximizeButton = windowButton("\u25A1", "settings-window-max", "Maximize");
        maximizeButton.setOnAction(event -> toggleMaximize());

        Button closeButton = windowButton("\u00D7", "settings-window-close", "Close");
        closeButton.setOnAction(event -> {
            Node cancel = getDialogPane().lookupButton(ButtonType.CANCEL);
            if (cancel instanceof Button button) {
                button.fire();
            } else {
                hide();
            }
        });

        HBox bar = new HBox(title, spacer, minimize, maximizeButton, closeButton);
        bar.getStyleClass().add("settings-title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        enableDrag(bar);
        bar.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && !isWindowButton(event.getTarget())) {
                toggleMaximize();
            }
        });
        return bar;
    }

    private static Button windowButton(String glyph, String extraClass, String tooltip) {
        Button button = new Button(glyph);
        button.getStyleClass().addAll(Styles.FLAT, "settings-window-button", extraClass);
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private void showPage(Category category) {
        pages.forEach((key, node) -> {
            boolean visible = key == category;
            node.setVisible(visible);
            node.setManaged(visible);
        });
    }

    private VBox generalPage() {
        autoCommit.setTooltip(new Tooltip(
                "Preferred auto-commit mode when a new connection is opened. "
                        + "The toolbar toggle still controls the live session."));
        return page("General",
                "Defaults that apply across the IDE.",
                autoCommit);
    }

    private VBox editorPage() {
        fontFamily.getItems().setAll(EDITOR_FONTS);
        fontFamily.setEditable(true);
        fontFamily.setMaxWidth(Double.MAX_VALUE);
        fontFamily.setTooltip(new Tooltip("Monospace font used by the SQL editor."));

        fontSize.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(10, 22, 13));
        fontSize.setEditable(true);
        fontSize.setPrefWidth(88);
        fontSize.setTooltip(new Tooltip("Editor font size in points (10–22)."));

        wordWrap.setTooltip(new Tooltip("Wrap long lines instead of scrolling horizontally."));

        HBox sizeRow = labeledRow("Font size", fontSize);
        VBox familyBlock = labeledBlock("Font family", fontFamily);
        return page("Editor",
                "Appearance of query consoles.",
                familyBlock,
                sizeRow,
                wordWrap);
    }

    private VBox completionPage() {
        keywordCasing.getItems().setAll(KeywordCasing.values());
        keywordCasing.setConverter(new StringConverter<>() {
            @Override
            public String toString(KeywordCasing casing) {
                return casing == null ? "" : casing.label();
            }

            @Override
            public KeywordCasing fromString(String raw) {
                return KeywordCasing.parse(raw);
            }
        });
        keywordCasing.setMaxWidth(Double.MAX_VALUE);
        keywordCasing.setTooltip(new Tooltip(
                "How SELECT / FROM / WHERE are written when a suggestion is accepted."));

        autoQuote.setTooltip(new Tooltip(
                "Wrap reserved words (order, user, …) in dialect quotes when inserting identifiers."));
        preserveCasing.setTooltip(new Tooltip(
                "Keep table/column names exactly as the database returned them. "
                        + "When off, identifiers are lowercased."));
        autoTableAliases.setTooltip(new Tooltip(
                "After FROM or JOIN, insert an alias: users becomes users u."));
        suggestJoinColumns.setTooltip(new Tooltip(
                "Offer JOIN … ON snippets from foreign keys or matching column names."));

        VBox casingBlock = labeledBlock("Keyword casing", keywordCasing);
        return page("Code Completion",
                "These options apply when you accept an autocomplete suggestion.",
                casingBlock,
                autoQuote,
                preserveCasing,
                autoTableAliases,
                suggestJoinColumns);
    }

    private VBox executionPage() {
        resultLimit.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(100, 10_000, 1_000, 100));
        resultLimit.setEditable(true);
        resultLimit.setPrefWidth(110);
        resultLimit.setTooltip(new Tooltip("Maximum rows fetched per query (100–10,000)."));

        mockApiLatency.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(0, 5_000, 500, 50));
        mockApiLatency.setEditable(true);
        mockApiLatency.setPrefWidth(110);
        mockApiLatency.setTooltip(new Tooltip(
                "Artificial delay added to the Serve as API mock endpoint, for UI lag testing."));

        confirmDangerousDml.setTooltip(new Tooltip(
                "Ask for confirmation before running DELETE or UPDATE statements that have no WHERE clause."));
        confirmDangerousDml.setWrapText(true);

        HBox limitRow = labeledRow("Default result limit", resultLimit);
        HBox latencyRow = labeledRow("Mock API latency (ms)", mockApiLatency);
        return page("Execution",
                "How statements are run and how large result sets are fetched.",
                limitRow,
                latencyRow,
                confirmDangerousDml);
    }

    private static VBox page(String title, String hint, Node... children) {
        Label heading = new Label(title);
        heading.getStyleClass().add("settings-page-title");

        Label hintLabel = new Label(hint);
        hintLabel.getStyleClass().add("settings-hint");
        hintLabel.setWrapText(true);

        VBox box = new VBox(12);
        box.getStyleClass().add("settings-page");
        box.setPadding(new Insets(18, 20, 16, 20));
        box.getChildren().add(heading);
        box.getChildren().add(hintLabel);
        box.getChildren().addAll(children);
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private static VBox labeledBlock(String caption, Node control) {
        Label label = new Label(caption);
        label.getStyleClass().add("settings-field-label");
        return new VBox(6, label, control);
    }

    private static HBox labeledRow(String caption, Node control) {
        Label label = new Label(caption);
        label.getStyleClass().add("settings-field-label");
        HBox row = new HBox(12, label, control);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private void loadFromState() {
        autoCommit.setSelected(state.autoCommit());

        String family = state.editorFontFamily();
        if (!fontFamily.getItems().contains(family)) {
            fontFamily.getItems().add(0, family);
        }
        fontFamily.getSelectionModel().select(family);
        fontFamily.getEditor().setText(family);
        fontSize.getValueFactory().setValue(state.editorFontSize());
        wordWrap.setSelected(state.editorWordWrap());

        keywordCasing.getSelectionModel().select(state.keywordCasing());
        autoQuote.setSelected(state.autoQuoteReserved());
        preserveCasing.setSelected(state.preserveDbCasing());
        autoTableAliases.setSelected(state.autoGenerateTableAliases());
        suggestJoinColumns.setSelected(state.suggestJoinColumns());

        int rows = Math.clamp(state.maxRows(), 100, 10_000);
        resultLimit.getValueFactory().setValue(rows);
        mockApiLatency.getValueFactory().setValue(state.mockApiLatencyMs());
        confirmDangerousDml.setSelected(state.confirmDangerousDml());
    }

    private void saveToState() {
        commitSpinner(fontSize);
        commitSpinner(resultLimit);
        commitSpinner(mockApiLatency);

        state.saveAutoCommit(autoCommit.isSelected());

        String family = fontFamily.getEditor().getText();
        if (family == null || family.isBlank()) {
            family = fontFamily.getValue();
        }
        state.saveEditorFontFamily(family);
        Integer size = fontSize.getValue();
        state.saveEditorFontSize(size == null ? 13 : size);
        state.saveEditorWordWrap(wordWrap.isSelected());

        KeywordCasing casing = keywordCasing.getValue();
        state.saveKeywordCasing(casing == null ? KeywordCasing.UPPERCASE : casing);
        state.saveAutoQuoteReserved(autoQuote.isSelected());
        state.savePreserveDbCasing(preserveCasing.isSelected());
        state.saveAutoGenerateTableAliases(autoTableAliases.isSelected());
        state.saveSuggestJoinColumns(suggestJoinColumns.isSelected());

        Integer limit = resultLimit.getValue();
        state.saveMaxRows(limit == null ? 1_000 : limit);
        Integer latency = mockApiLatency.getValue();
        state.saveMockApiLatencyMs(latency == null ? 500 : latency);
        state.saveConfirmDangerousDml(confirmDangerousDml.isSelected());
    }

    private static void commitSpinner(Spinner<Integer> spinner) {
        try {
            spinner.commitValue();
        } catch (RuntimeException ignored) {
            // keep the last valid value
        }
    }

    // ---------------------------------------------------------------- window chrome

    private void enableDrag(Node handle) {
        final double[] drag = new double[2];
        handle.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY || isWindowButton(event.getTarget())) {
                return;
            }
            if (resizeEdge != ResizeEdge.NONE) {
                return;
            }
            Window window = getDialogPane().getScene().getWindow();
            if (maximized) {
                restoreFromMaximize(window, event.getScreenX());
            }
            drag[0] = event.getScreenX() - window.getX();
            drag[1] = event.getScreenY() - window.getY();
        });
        handle.setOnMouseDragged(event -> {
            if (event.getButton() != MouseButton.PRIMARY || resizing || isWindowButton(event.getTarget())) {
                return;
            }
            Window window = getDialogPane().getScene().getWindow();
            window.setX(event.getScreenX() - drag[0]);
            window.setY(event.getScreenY() - drag[1]);
        });
    }

    private void toggleMaximize() {
        Stage stage = dialogStage();
        if (stage == null) {
            return;
        }
        if (maximized) {
            stage.setX(restoreX);
            stage.setY(restoreY);
            stage.setWidth(restoreWidth);
            stage.setHeight(restoreHeight);
            maximized = false;
        } else {
            restoreX = stage.getX();
            restoreY = stage.getY();
            restoreWidth = stage.getWidth();
            restoreHeight = stage.getHeight();
            Rectangle2D bounds = screenFor(stage).getVisualBounds();
            stage.setX(bounds.getMinX());
            stage.setY(bounds.getMinY());
            stage.setWidth(bounds.getWidth());
            stage.setHeight(bounds.getHeight());
            maximized = true;
        }
        refreshMaximizeButton();
    }

    private void restoreFromMaximize(Window window, double screenX) {
        if (!maximized) {
            return;
        }
        double ratio = restoreWidth <= 0 ? 0.5 : Math.clamp((screenX - window.getX()) / Math.max(window.getWidth(), 1), 0, 1);
        maximized = false;
        window.setWidth(restoreWidth);
        window.setHeight(restoreHeight);
        window.setX(screenX - restoreWidth * ratio);
        window.setY(restoreY);
        refreshMaximizeButton();
    }

    private void refreshMaximizeButton() {
        if (maximizeButton == null) {
            return;
        }
        maximizeButton.setText(maximized ? "\u29C9" : "\u25A1");
        maximizeButton.setTooltip(new Tooltip(maximized ? "Restore" : "Maximize"));
    }

    private void installResizeSupport(Stage stage) {
        Scene scene = stage.getScene();
        if (scene == null) {
            return;
        }
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            if (maximized || resizing) {
                return;
            }
            if (isWindowButton(event.getTarget())) {
                resizeEdge = ResizeEdge.NONE;
                scene.setCursor(Cursor.DEFAULT);
                return;
            }
            resizeEdge = edgeAt(event.getSceneX(), event.getSceneY(), scene.getWidth(), scene.getHeight());
            scene.setCursor(cursorFor(resizeEdge));
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY || maximized || resizeEdge == ResizeEdge.NONE
                    || isWindowButton(event.getTarget())) {
                return;
            }
            resizing = true;
            resizeAnchorX = event.getScreenX();
            resizeAnchorY = event.getScreenY();
            resizeStartX = stage.getX();
            resizeStartY = stage.getY();
            resizeStartWidth = stage.getWidth();
            resizeStartHeight = stage.getHeight();
            event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (!resizing) {
                return;
            }
            applyResize(stage, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (resizing) {
                resizing = false;
                event.consume();
            }
        });
    }

    private void applyResize(Stage stage, double screenX, double screenY) {
        double dx = screenX - resizeAnchorX;
        double dy = screenY - resizeAnchorY;
        double x = resizeStartX;
        double y = resizeStartY;
        double w = resizeStartWidth;
        double h = resizeStartHeight;
        double minW = Math.max(stage.getMinWidth(), 560);
        double minH = Math.max(stage.getMinHeight(), 400);

        switch (resizeEdge) {
            case E -> w = Math.max(minW, resizeStartWidth + dx);
            case W -> {
                w = Math.max(minW, resizeStartWidth - dx);
                x = resizeStartX + (resizeStartWidth - w);
            }
            case S -> h = Math.max(minH, resizeStartHeight + dy);
            case N -> {
                h = Math.max(minH, resizeStartHeight - dy);
                y = resizeStartY + (resizeStartHeight - h);
            }
            case SE -> {
                w = Math.max(minW, resizeStartWidth + dx);
                h = Math.max(minH, resizeStartHeight + dy);
            }
            case SW -> {
                w = Math.max(minW, resizeStartWidth - dx);
                x = resizeStartX + (resizeStartWidth - w);
                h = Math.max(minH, resizeStartHeight + dy);
            }
            case NE -> {
                w = Math.max(minW, resizeStartWidth + dx);
                h = Math.max(minH, resizeStartHeight - dy);
                y = resizeStartY + (resizeStartHeight - h);
            }
            case NW -> {
                w = Math.max(minW, resizeStartWidth - dx);
                x = resizeStartX + (resizeStartWidth - w);
                h = Math.max(minH, resizeStartHeight - dy);
                y = resizeStartY + (resizeStartHeight - h);
            }
            case NONE -> {
                return;
            }
        }
        stage.setX(x);
        stage.setY(y);
        stage.setWidth(w);
        stage.setHeight(h);
    }

    private static ResizeEdge edgeAt(double x, double y, double width, double height) {
        boolean left = x <= RESIZE_BAND;
        boolean right = x >= width - RESIZE_BAND;
        boolean top = y <= RESIZE_BAND;
        boolean bottom = y >= height - RESIZE_BAND;
        if (top && left) {
            return ResizeEdge.NW;
        }
        if (top && right) {
            return ResizeEdge.NE;
        }
        if (bottom && left) {
            return ResizeEdge.SW;
        }
        if (bottom && right) {
            return ResizeEdge.SE;
        }
        if (left) {
            return ResizeEdge.W;
        }
        if (right) {
            return ResizeEdge.E;
        }
        if (top) {
            return ResizeEdge.N;
        }
        if (bottom) {
            return ResizeEdge.S;
        }
        return ResizeEdge.NONE;
    }

    private static Cursor cursorFor(ResizeEdge edge) {
        return switch (edge) {
            case N, S -> Cursor.V_RESIZE;
            case E, W -> Cursor.H_RESIZE;
            case NE, SW -> Cursor.NE_RESIZE;
            case NW, SE -> Cursor.NW_RESIZE;
            case NONE -> Cursor.DEFAULT;
        };
    }

    private static boolean isWindowButton(EventTarget target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        Node current = node;
        while (current != null) {
            if (current.getStyleClass().contains("settings-window-button")) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private Stage dialogStage() {
        Window window = getDialogPane().getScene() == null ? null : getDialogPane().getScene().getWindow();
        return window instanceof Stage stage ? stage : null;
    }

    private static Screen screenFor(Window window) {
        var screens = Screen.getScreensForRectangle(
                window.getX(), window.getY(), Math.max(window.getWidth(), 1), Math.max(window.getHeight(), 1));
        return screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();
    }

    private static String stylesheet() {
        return Objects.requireNonNull(
                        SettingsDialog.class.getResource("/com/lazaro/sqlide/css/app.css"),
                        "app.css is missing from the classpath")
                .toExternalForm();
    }
}
