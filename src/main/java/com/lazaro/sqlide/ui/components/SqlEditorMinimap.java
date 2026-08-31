package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.editor.EditorScrollAnnotations;
import com.lazaro.sqlide.core.inspection.InspectionIssue;
import com.lazaro.sqlide.core.inspection.Severity;
import javafx.animation.PauseTransition;
import javafx.geometry.Orientation;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

import java.util.List;

/**
 * Scaled-down map of the editor buffer, painted onto a {@link Canvas} so large
 * scripts stay cheap to redraw. Click / drag jumps to that line.
 */
final class SqlEditorMinimap extends Region {

    static final double WIDTH = 68;

    private static final Color BACKGROUND = Color.web("#16171a");
    private static final Color TEXT_TICK = Color.web("#4a5160");
    private static final Color VIEWPORT_FILL = Color.web("#4fa6ee", 0.12);
    private static final Color VIEWPORT_STROKE = Color.web("#4fa6ee", 0.45);
    private static final Color ERROR_TICK = Color.web("#e06c75");

    private final CodeArea codeArea;
    private final VirtualizedScrollPane<?> scroll;
    private final Canvas canvas = new Canvas();
    private final PauseTransition debounce = new PauseTransition(Duration.millis(90));
    private List<InspectionIssue> issues = List.of();

    SqlEditorMinimap(CodeArea codeArea, VirtualizedScrollPane<?> scroll) {
        this.codeArea = codeArea;
        this.scroll = scroll;
        getStyleClass().add("sql-editor-minimap");
        setPrefWidth(WIDTH);
        setMinWidth(0);
        setMaxWidth(WIDTH);
        managedProperty().bind(visibleProperty());
        setVisible(false);
        getChildren().add(canvas);
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        canvas.setMouseTransparent(true);

        debounce.setOnFinished(event -> paint());
        canvas.widthProperty().addListener((observable, previous, next) -> requestPaint());
        canvas.heightProperty().addListener((observable, previous, next) -> requestPaint());
        codeArea.textProperty().addListener((observable, previous, next) -> requestPaint());
        codeArea.estimatedScrollYProperty().addListener((observable, previous, next) -> requestPaint());
        codeArea.totalHeightEstimateProperty().addListener((observable, previous, next) -> requestPaint());
        scroll.heightProperty().addListener((observable, previous, next) -> requestPaint());

        setOnMousePressed(this::jumpTo);
        setOnMouseDragged(this::jumpTo);
    }

    void setIssues(List<InspectionIssue> issues) {
        this.issues = issues == null ? List.of() : List.copyOf(issues);
        requestPaint();
    }

    void dispose() {
        debounce.stop();
    }

    private void requestPaint() {
        debounce.playFromStart();
    }

    private void jumpTo(MouseEvent event) {
        int lines = Math.max(1, codeArea.getParagraphs().size());
        int line = EditorScrollAnnotations.lineAtY(event.getY(), getHeight(), lines);
        codeArea.showParagraphAtTop(line);
        codeArea.moveTo(line, 0);
        codeArea.requestFollowCaret();
        event.consume();
    }

    private void paint() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        GraphicsContext graphics = canvas.getGraphicsContext2D();
        graphics.setFill(BACKGROUND);
        graphics.fillRect(0, 0, width, height);
        if (width <= 0 || height <= 0) {
            return;
        }

        int paragraphs = Math.max(1, codeArea.getParagraphs().size());
        int samples = (int) Math.max(1, Math.floor(height));
        graphics.setFill(TEXT_TICK);
        for (int y = 0; y < samples; y++) {
            int line = EditorScrollAnnotations.lineAtY(y, height, paragraphs);
            String text = codeArea.getParagraph(line).getText();
            if (text == null || text.isBlank()) {
                continue;
            }
            int indent = leadingSpaces(text);
            double x = 4 + Math.min(indent * 1.15, width * 0.42);
            double bar = Math.max(10, (width - x - 8) * Math.min(1.0, text.strip().length() / 48.0));
            graphics.fillRect(x, y, bar, 1.15);
        }

        paintViewport(graphics, width, height);
        paintErrors(graphics, width, height, paragraphs);
    }

    private void paintViewport(GraphicsContext graphics, double width, double height) {
        double total = nullSafe(codeArea.totalHeightEstimateProperty().getValue());
        double scrollY = nullSafe(codeArea.estimatedScrollYProperty().getValue());
        if (total <= 0) {
            return;
        }
        double visible = scroll.getHeight() <= 0 ? height * 0.2 : scroll.getHeight() / total * height;
        visible = Math.clamp(visible, 10, height);
        double y = (scrollY / total) * Math.max(0, height - visible);
        graphics.setFill(VIEWPORT_FILL);
        graphics.fillRect(0, y, width, visible);
        graphics.setStroke(VIEWPORT_STROKE);
        graphics.setLineWidth(1);
        graphics.strokeRect(0.5, y + 0.5, width - 1, Math.max(1, visible - 1));
    }

    private void paintErrors(GraphicsContext graphics, double width, double height, int paragraphs) {
        graphics.setFill(ERROR_TICK);
        for (InspectionIssue issue : issues) {
            if (issue.severity() != Severity.ERROR) {
                continue;
            }
            int line = lineOf(issue.startOffset());
            double y = EditorScrollAnnotations.markerY(height, 3, line, paragraphs);
            graphics.fillRect(width - 6, y, 4, 3);
        }
    }

    private int lineOf(int offset) {
        int length = codeArea.getLength();
        int clamped = Math.clamp(offset, 0, length);
        return codeArea.offsetToPosition(clamped, TwoDimensional.Bias.Forward).getMajor();
    }

    private static int leadingSpaces(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == ' ') {
                count++;
            } else if (ch == '\t') {
                count += 4;
            } else {
                break;
            }
        }
        return count;
    }

    private static double nullSafe(Double value) {
        return value == null || value.isNaN() ? 0 : value;
    }

    @Override
    protected void layoutChildren() {
        canvas.resizeRelocate(0, 0, getWidth(), getHeight());
    }

    @Override
    public Orientation getContentBias() {
        return Orientation.VERTICAL;
    }
}
