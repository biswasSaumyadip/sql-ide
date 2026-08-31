package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.editor.EditorScrollAnnotations;
import com.lazaro.sqlide.core.inspection.InspectionIssue;
import com.lazaro.sqlide.core.inspection.Severity;
import javafx.geometry.Pos;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

import java.util.List;

/**
 * Red ticks over the editor's vertical scrollbar track, one per syntax / inspection
 * error. Clicking a tick jumps to that line.
 */
final class EditorScrollbarMarkers extends Pane {

    private static final double TRACK_WIDTH = 11;
    private static final double MARKER_HEIGHT = 3;

    private final CodeArea codeArea;
    private List<InspectionIssue> issues = List.of();

    EditorScrollbarMarkers(CodeArea codeArea) {
        this.codeArea = codeArea;
        getStyleClass().add("editor-scrollbar-markers");
        setMinWidth(0);
        setPrefWidth(0);
        setMaxWidth(0);
        setPickOnBounds(false);
        setMouseTransparent(true);
        StackPane.setAlignment(this, Pos.CENTER_RIGHT);
        heightProperty().addListener((observable, previous, next) -> rebuild());
        setOnMouseClicked(this::jumpTo);
    }

    void setIssues(List<InspectionIssue> issues) {
        this.issues = issues == null ? List.of() : List.copyOf(issues);
        rebuild();
    }

    private void rebuild() {
        getChildren().clear();
        int paragraphs = Math.max(1, codeArea.getParagraphs().size());
        double height = overlayHeight();
        for (InspectionIssue issue : issues) {
            if (issue.severity() != Severity.ERROR) {
                continue;
            }
            int line = lineOf(issue.startOffset());
            Rectangle tick = new Rectangle(8, MARKER_HEIGHT);
            tick.setFill(Color.web("#e06c75"));
            tick.getStyleClass().add("editor-scrollbar-error");
            tick.setX(1.5);
            tick.setY(EditorScrollAnnotations.markerY(Math.max(height, 1), MARKER_HEIGHT, line, paragraphs));
            tick.setUserData(line);
            tick.setOnMouseClicked(event -> {
                jumpToLine(line);
                event.consume();
            });
            getChildren().add(tick);
        }
        boolean show = !getChildren().isEmpty();
        setPrefWidth(show ? TRACK_WIDTH : 0);
        setMaxWidth(show ? TRACK_WIDTH : 0);
        setMouseTransparent(!show);
    }

    private double overlayHeight() {
        if (getHeight() > 0) {
            return getHeight();
        }
        if (getParent() instanceof Region parent && parent.getHeight() > 0) {
            return parent.getHeight();
        }
        return 0;
    }

    private void jumpTo(MouseEvent event) {
        if (event.getTarget() instanceof Rectangle rectangle && rectangle.getUserData() instanceof Integer line) {
            jumpToLine(line);
            event.consume();
        }
    }

    private void jumpToLine(int line) {
        int last = Math.max(0, codeArea.getParagraphs().size() - 1);
        int target = Math.clamp(line, 0, last);
        codeArea.showParagraphAtCenter(target);
        codeArea.moveTo(target, 0);
        codeArea.requestFollowCaret();
    }

    private int lineOf(int offset) {
        int length = codeArea.getLength();
        int clamped = Math.clamp(offset, 0, length);
        return codeArea.offsetToPosition(clamped, TwoDimensional.Bias.Forward).getMajor();
    }
}
