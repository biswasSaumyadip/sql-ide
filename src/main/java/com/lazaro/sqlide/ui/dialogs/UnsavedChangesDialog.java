package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.ui.Icons;
import javafx.event.EventTarget;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.Objects;
import java.util.Optional;

/**
 * Dark, undecorated confirm prompt used when a dirty editor (or similar) is closed.
 */
public final class UnsavedChangesDialog extends Dialog<UnsavedChangesDialog.Choice> {

    public enum Choice {
        SAVE, DISCARD, CANCEL
    }

    public UnsavedChangesDialog(Window owner, String documentTitle) {
        this(owner, documentTitle, "Your changes will be lost if you don't save them.", "Save");
    }

    public UnsavedChangesDialog(Window owner, String documentTitle, String body, String confirmLabel) {
        initStyle(StageStyle.UNDECORATED);
        setTitle("Unsaved changes");
        setHeaderText(null);
        setGraphic(null);
        if (owner != null) {
            initOwner(owner);
        }

        String headerText = "%s changes to \"%s\"?".formatted(
                confirmLabel == null || confirmLabel.isBlank() ? "Save" : confirmLabel,
                documentTitle == null ? "Untitled" : documentTitle);

        getDialogPane().getStyleClass().add("unsaved-dialog");
        getDialogPane().getStylesheets().add(stylesheet());
        getDialogPane().setContent(buildRoot(headerText, body, confirmLabel));
        getDialogPane().getButtonTypes().clear();
        getDialogPane().setPrefWidth(460);
    }

    /**
     * @return empty / {@link Choice#CANCEL} when the user backs out
     */
    public static Optional<Choice> confirm(Window owner, String documentTitle) {
        return new UnsavedChangesDialog(owner, documentTitle).showAndWait();
    }

    public static Optional<Choice> confirm(
            Window owner, String documentTitle, String body, String confirmLabel) {
        return new UnsavedChangesDialog(owner, documentTitle, body, confirmLabel).showAndWait();
    }

    private VBox buildRoot(String headerText, String bodyText, String confirmLabel) {
        VBox root = new VBox();
        root.getStyleClass().add("unsaved-root");
        root.getChildren().addAll(
                buildTitleBar(),
                buildBody(headerText, bodyText, confirmLabel));
        return root;
    }

    private HBox buildTitleBar() {
        Label title = new Label("Unsaved changes");
        title.getStyleClass().add("unsaved-title-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button close = new Button("\u00D7");
        close.getStyleClass().addAll(Styles.FLAT, "unsaved-window-button", "unsaved-window-close");
        close.setFocusTraversable(false);
        close.setOnAction(event -> finish(Choice.CANCEL));

        HBox bar = new HBox(title, spacer, close);
        bar.getStyleClass().add("unsaved-title-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        enableDrag(bar);
        return bar;
    }

    private VBox buildBody(String headerText, String bodyText, String confirmLabel) {
        StackPane iconSlot = new StackPane(Icons.unsavedWarning());
        iconSlot.getStyleClass().add("unsaved-icon");
        iconSlot.setMinSize(36, 36);
        iconSlot.setPrefSize(36, 36);
        iconSlot.setMaxSize(36, 36);

        Label header = new Label(headerText);
        header.getStyleClass().add("unsaved-header");
        header.setWrapText(true);

        Label body = new Label(bodyText);
        body.getStyleClass().add("unsaved-body");
        body.setWrapText(true);

        VBox copy = new VBox(8, header, body);
        HBox.setHgrow(copy, Priority.ALWAYS);

        HBox message = new HBox(14, iconSlot, copy);
        message.setAlignment(Pos.TOP_LEFT);

        Button discard = new Button("Discard");
        discard.getStyleClass().add("unsaved-button");
        discard.setCancelButton(false);
        discard.setOnAction(event -> finish(Choice.DISCARD));

        Button cancel = new Button("Cancel");
        cancel.getStyleClass().add("unsaved-button");
        cancel.setCancelButton(true);
        cancel.setOnAction(event -> finish(Choice.CANCEL));

        Button save = new Button(confirmLabel == null || confirmLabel.isBlank() ? "Save" : confirmLabel);
        save.getStyleClass().addAll("unsaved-button", Styles.ACCENT);
        save.setDefaultButton(true);
        save.setOnAction(event -> finish(Choice.SAVE));

        HBox buttons = new HBox(8, discard, cancel, save);
        buttons.getStyleClass().add("unsaved-buttons");
        buttons.setAlignment(Pos.BOTTOM_RIGHT);

        VBox bodyBox = new VBox(20, message, buttons);
        bodyBox.getStyleClass().add("unsaved-content");
        return bodyBox;
    }

    private void finish(Choice choice) {
        setResult(choice);
        close();
    }

    private void enableDrag(Node handle) {
        final double[] drag = new double[2];
        handle.setOnMousePressed(event -> {
            if (isCloseButton(event.getTarget())) {
                return;
            }
            Window window = getDialogPane().getScene().getWindow();
            drag[0] = event.getScreenX() - window.getX();
            drag[1] = event.getScreenY() - window.getY();
        });
        handle.setOnMouseDragged(event -> {
            if (isCloseButton(event.getTarget())) {
                return;
            }
            Window window = getDialogPane().getScene().getWindow();
            window.setX(event.getScreenX() - drag[0]);
            window.setY(event.getScreenY() - drag[1]);
        });
    }

    private static boolean isCloseButton(EventTarget target) {
        if (!(target instanceof Node node)) {
            return false;
        }
        Node current = node;
        while (current != null) {
            if (current.getStyleClass().contains("unsaved-window-button")) {
                return true;
            }
            current = current.getParent();
        }
        return false;
    }

    private static String stylesheet() {
        return Objects.requireNonNull(
                        UnsavedChangesDialog.class.getResource("/com/lazaro/sqlide/css/app.css"),
                        "app.css is missing from the classpath")
                .toExternalForm();
    }
}
