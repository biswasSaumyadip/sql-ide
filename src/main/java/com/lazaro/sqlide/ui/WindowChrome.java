package com.lazaro.sqlide.ui;

import javafx.event.EventTarget;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/**
 * Custom caption bar and edge-resize for the undecorated main window.
 * JavaFX 21 / AtlantaFX cannot hint a dark OS title bar on Windows.
 */
final class WindowChrome {

    static final String MAXIMIZED_KEY = "sql-ide.maximized";

    private enum ResizeEdge {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    private static final double RESIZE_BAND = 6;
    private static final double TITLE_HEIGHT = 32;

    private Stage stage;
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

    HBox createTitleBar() {
        StackPane logoSlot = new StackPane(Icons.appLogo());
        logoSlot.setMinSize(16, 16);
        logoSlot.setPrefSize(16, 16);
        logoSlot.setMaxSize(16, 16);
        logoSlot.setMouseTransparent(true);

        Label title = new Label("SQL IDE");
        title.getStyleClass().add("app-title-label");

        HBox brand = new HBox(8, logoSlot, title);
        brand.setAlignment(Pos.CENTER_LEFT);
        brand.setMouseTransparent(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = windowButton(Icons.windowMinimize(), "app-window-min", "Minimize");
        minimize.setOnAction(event -> {
            Stage current = stage();
            if (current != null) {
                current.setIconified(true);
            }
        });

        maximizeButton = windowButton(Icons.windowMaximize(), "app-window-max", "Maximize");
        maximizeButton.setOnAction(event -> toggleMaximize());

        Button close = windowButton(Icons.windowClose(), "app-window-close", "Close");
        close.setOnAction(event -> {
            Stage current = stage();
            if (current != null) {
                current.fireEvent(new WindowEvent(current, WindowEvent.WINDOW_CLOSE_REQUEST));
            }
        });

        HBox bar = new HBox(brand, spacer, minimize, maximizeButton, close);
        bar.getStyleClass().add("app-title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setMinHeight(TITLE_HEIGHT);
        bar.setPrefHeight(TITLE_HEIGHT);
        bar.setMaxHeight(TITLE_HEIGHT);
        HBox.setHgrow(bar, Priority.ALWAYS);
        enableDrag(bar);
        bar.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2
                    && !isWindowButton(event.getTarget())) {
                toggleMaximize();
            }
        });
        return bar;
    }

    void attach(Stage stage) {
        this.stage = stage;
        installResize(stage);
        if (stage.isMaximized()) {
            maximized = true;
            refreshMaximizeButton();
            publishMaximized();
        }
    }

    boolean isMaximized() {
        return maximized || (stage != null && stage.isMaximized());
    }

    private void installResize(Stage stage) {
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

    private void enableDrag(Node handle) {
        final double[] drag = new double[2];
        handle.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY || isWindowButton(event.getTarget())) {
                return;
            }
            if (resizeEdge != ResizeEdge.NONE) {
                return;
            }
            Window window = windowOf(handle);
            if (window == null) {
                return;
            }
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
            Window window = windowOf(handle);
            if (window == null) {
                return;
            }
            window.setX(event.getScreenX() - drag[0]);
            window.setY(event.getScreenY() - drag[1]);
        });
    }

    void toggleMaximize() {
        Stage current = stage();
        if (current == null) {
            return;
        }
        if (maximized) {
            current.setX(restoreX);
            current.setY(restoreY);
            current.setWidth(restoreWidth);
            current.setHeight(restoreHeight);
            maximized = false;
        } else {
            restoreX = current.getX();
            restoreY = current.getY();
            restoreWidth = current.getWidth();
            restoreHeight = current.getHeight();
            Rectangle2D bounds = screenFor(current).getVisualBounds();
            current.setX(bounds.getMinX());
            current.setY(bounds.getMinY());
            current.setWidth(bounds.getWidth());
            current.setHeight(bounds.getHeight());
            maximized = true;
        }
        refreshMaximizeButton();
        publishMaximized();
    }

    private void restoreFromMaximize(Window window, double screenX) {
        if (!maximized) {
            return;
        }
        double ratio = restoreWidth <= 0
                ? 0.5
                : Math.clamp((screenX - window.getX()) / Math.max(window.getWidth(), 1), 0, 1);
        maximized = false;
        window.setWidth(restoreWidth);
        window.setHeight(restoreHeight);
        window.setX(screenX - restoreWidth * ratio);
        window.setY(restoreY);
        refreshMaximizeButton();
        publishMaximized();
    }

    private void refreshMaximizeButton() {
        if (maximizeButton == null) {
            return;
        }
        maximizeButton.setGraphic(maximized ? Icons.windowRestore() : Icons.windowMaximize());
        maximizeButton.setTooltip(new Tooltip(maximized ? "Restore" : "Maximize"));
    }

    private void publishMaximized() {
        Stage current = stage();
        if (current != null) {
            current.getProperties().put(MAXIMIZED_KEY, maximized);
        }
    }

    private void applyResize(Stage current, double screenX, double screenY) {
        double dx = screenX - resizeAnchorX;
        double dy = screenY - resizeAnchorY;
        double x = resizeStartX;
        double y = resizeStartY;
        double w = resizeStartWidth;
        double h = resizeStartHeight;
        double minW = Math.max(current.getMinWidth(), 640);
        double minH = Math.max(current.getMinHeight(), 460);

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
                y = restoreStartY(resizeStartHeight, h);
            }
            case NW -> {
                w = Math.max(minW, resizeStartWidth - dx);
                x = resizeStartX + (resizeStartWidth - w);
                h = Math.max(minH, resizeStartHeight - dy);
                y = restoreStartY(resizeStartHeight, h);
            }
            case NONE -> {
                return;
            }
        }
        current.setX(x);
        current.setY(y);
        current.setWidth(w);
        current.setHeight(h);
    }

    private double restoreStartY(double startHeight, double newHeight) {
        return resizeStartY + (startHeight - newHeight);
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

    private static Button windowButton(Node graphic, String extraClass, String tooltip) {
        Button button = new Button();
        button.setGraphic(graphic);
        button.getStyleClass().addAll("app-window-button", extraClass);
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private static boolean isWindowButton(EventTarget target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        Node current = node;
        while (current != null) {
            if (current.getStyleClass().contains("app-window-button")) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private Stage stage() {
        if (stage != null) {
            return stage;
        }
        return null;
    }

    private static Window windowOf(Node node) {
        if (node.getScene() == null) {
            return null;
        }
        return node.getScene().getWindow();
    }

    private static Screen screenFor(Window window) {
        var screens = Screen.getScreensForRectangle(
                window.getX(), window.getY(), Math.max(window.getWidth(), 1), Math.max(window.getHeight(), 1));
        return screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();
    }
}
