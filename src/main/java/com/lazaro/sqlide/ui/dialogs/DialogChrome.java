package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import javafx.event.EventTarget;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;

/**
 * Shared undecorated caption + resize chrome for large IDE dialogs.
 * JavaFX 21 / AtlantaFX cannot hint a dark OS title bar on Windows.
 */
final class DialogChrome {

    private enum ResizeEdge {
        NONE, N, S, E, W, NE, NW, SE, SW
    }

    private static final double RESIZE_BAND = 6;

    private final Dialog<?> dialog;
    private final double minWidth;
    private final double minHeight;

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

    DialogChrome(Dialog<?> dialog, double minWidth, double minHeight) {
        this.dialog = dialog;
        this.minWidth = minWidth;
        this.minHeight = minHeight;
    }

    HBox titleBar(String title) {
        Label label = new Label(title);
        label.getStyleClass().add("dialog-title-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button minimize = windowButton("\u2013", "dialog-window-min", "Minimize");
        minimize.setOnAction(event -> {
            Stage stage = stage();
            if (stage != null) {
                stage.setIconified(true);
            }
        });

        maximizeButton = windowButton("\u25A1", "dialog-window-max", "Maximize");
        maximizeButton.setOnAction(event -> toggleMaximize());

        Button close = windowButton("\u00D7", "dialog-window-close", "Close");
        close.setOnAction(event -> {
            Node cancel = dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
            if (cancel instanceof Button cancelButton && cancelButton.getScene() != null) {
                ((Stage) cancelButton.getScene().getWindow()).close();
            } else {
                Stage stage = stage();
                if (stage != null) {
                    stage.close();
                } else {
                    dialog.hide();
                }
            }
        });

        HBox bar = new HBox(label, spacer, minimize, maximizeButton, close);
        bar.getStyleClass().add("dialog-title-bar");
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

    void installResize() {
        Stage stage = stage();
        if (stage == null) {
            return;
        }
        stage.setMinWidth(minWidth);
        stage.setMinHeight(minHeight);
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
            Window window = dialog.getDialogPane().getScene().getWindow();
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
            Window window = dialog.getDialogPane().getScene().getWindow();
            window.setX(event.getScreenX() - drag[0]);
            window.setY(event.getScreenY() - drag[1]);
        });
    }

    private void toggleMaximize() {
        Stage stage = stage();
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
        double ratio = restoreWidth <= 0
                ? 0.5
                : Math.clamp((screenX - window.getX()) / Math.max(window.getWidth(), 1), 0, 1);
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

    private void applyResize(Stage stage, double screenX, double screenY) {
        double dx = screenX - resizeAnchorX;
        double dy = screenY - resizeAnchorY;
        double x = resizeStartX;
        double y = resizeStartY;
        double w = resizeStartWidth;
        double h = resizeStartHeight;
        double minW = Math.max(stage.getMinWidth(), minWidth);
        double minH = Math.max(stage.getMinHeight(), minHeight);

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
        stage.setX(x);
        stage.setY(y);
        stage.setWidth(w);
        stage.setHeight(h);
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

    private static Button windowButton(String glyph, String extraClass, String tooltip) {
        Button button = new Button(glyph);
        button.getStyleClass().addAll(Styles.FLAT, "dialog-window-button", extraClass);
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
            if (current.getStyleClass().contains("dialog-window-button")) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private Stage stage() {
        Window window = dialog.getDialogPane().getScene() == null
                ? null
                : dialog.getDialogPane().getScene().getWindow();
        return window instanceof Stage s ? s : null;
    }

    private static Screen screenFor(Window window) {
        var screens = Screen.getScreensForRectangle(
                window.getX(), window.getY(), Math.max(window.getWidth(), 1), Math.max(window.getHeight(), 1));
        return screens.isEmpty() ? Screen.getPrimary() : screens.getFirst();
    }
}
