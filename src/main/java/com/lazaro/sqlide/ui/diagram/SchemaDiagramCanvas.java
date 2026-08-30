package com.lazaro.sqlide.ui.diagram;

import com.lazaro.sqlide.core.diagram.SchemaDiagramLayout;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Column;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Edge;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Table;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.image.PixelReader;
import javafx.scene.image.WritableImage;
import javafx.scene.image.WritablePixelFormat;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.CubicCurve;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Interactive DataGrip-style schema diagram: pan, zoom, drag tables, select, highlight FKs.
 */
public final class SchemaDiagramCanvas extends BorderPane {

    private static final Color EDGE = Color.web("#6b7280");
    private static final Color EDGE_ACTIVE = Color.web("#4fa6ee");
    private static final Color EDGE_DIM = Color.web("#3a3f48", 0.45);
    private static final Color SNAPSHOT_FILL = Color.web("#1a1b1e");

    private final Pane edgeLayer = new Pane();
    private final Pane nodeLayer = new Pane();
    private final Group world = new Group(edgeLayer, nodeLayer);
    private final Pane viewport = new Pane(world);
    private final Label status = new Label();
    private final Label banner = new Label();
    private final TextField filterField = new TextField();

    private SchemaDiagramModel model =
            new SchemaDiagramModel("", null, java.util.List.of(), java.util.List.of());
    private final Map<String, VBox> tableNodes = new HashMap<>();
    private final Set<String> expandedTableIds = new HashSet<>();
    private String selectedTableId;
    private String filterQuery = "";
    private Consumer<Table> onOpenTable = table -> { };
    private Consumer<SchemaDiagramModel> onLayoutChanged = m -> { };
    private Runnable onLayoutReset = () -> { };

    private double scale = 1.0;
    private double panX;
    private double panY;
    private double dragStartX;
    private double dragStartY;
    private double panAtDragStartX;
    private double panAtDragStartY;
    private boolean panning;

    private String draggingTableId;
    private double tableDragOffsetX;
    private double tableDragOffsetY;
    private boolean tableMoved;

    public SchemaDiagramCanvas() {
        getStyleClass().add("schema-diagram-canvas");

        filterField.setPromptText("Filter tables\u2026");
        filterField.getStyleClass().add("schema-diagram-filter");
        filterField.textProperty().addListener((obs, o, n) -> {
            filterQuery = n == null ? "" : n.strip();
            applyFilterHighlight();
        });

        Button fit = new Button("Fit");
        fit.getStyleClass().add("labelled-button");
        fit.setOnAction(event -> fitToView());

        Button resetZoom = new Button("Reset zoom");
        resetZoom.getStyleClass().add("labelled-button");
        resetZoom.setOnAction(event -> {
            scale = 1.0;
            panX = 0;
            panY = 0;
            applyTransform();
        });

        Button resetLayout = new Button("Reset layout");
        resetLayout.getStyleClass().add("labelled-button");
        resetLayout.setTooltip(new Tooltip("Restore automatic layered layout"));
        resetLayout.setOnAction(event -> resetLayout());

        Button exportPng = new Button("Export PNG");
        exportPng.getStyleClass().add("labelled-button");
        exportPng.setOnAction(event -> promptExportPng());

        status.getStyleClass().add("schema-diagram-status");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, filterField, fit, resetZoom, resetLayout, exportPng, spacer, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 12, 8, 12));
        toolbar.getStyleClass().add("schema-diagram-toolbar");
        HBox.setHgrow(filterField, Priority.ALWAYS);

        banner.getStyleClass().add("schema-diagram-banner");
        banner.setWrapText(true);
        banner.managedProperty().bind(banner.visibleProperty());
        banner.setVisible(false);

        VBox top = new VBox(toolbar, banner);
        setTop(top);

        edgeLayer.setMouseTransparent(true);
        viewport.getStyleClass().add("schema-diagram-viewport");
        viewport.setMinSize(200, 200);
        StackPane clipHost = new StackPane(viewport);
        clipHost.getStyleClass().add("schema-diagram-host");
        clipHost.setMinSize(400, 300);
        setCenter(clipHost);

        viewport.addEventFilter(ScrollEvent.SCROLL, event -> {
            double factor = event.getDeltaY() > 0 ? 1.1 : 1 / 1.1;
            zoomAt(event.getX(), event.getY(), factor);
            event.consume();
        });

        viewport.setOnMousePressed(event -> {
            if (draggingTableId != null) {
                return;
            }
            if (event.getButton() != MouseButton.PRIMARY && event.getButton() != MouseButton.MIDDLE) {
                return;
            }
            panning = true;
            dragStartX = event.getX();
            dragStartY = event.getY();
            panAtDragStartX = panX;
            panAtDragStartY = panY;
            viewport.setCursor(Cursor.MOVE);
        });
        viewport.setOnMouseDragged(event -> {
            if (!panning || draggingTableId != null) {
                return;
            }
            panX = panAtDragStartX + (event.getX() - dragStartX);
            panY = panAtDragStartY + (event.getY() - dragStartY);
            applyTransform();
        });
        viewport.setOnMouseReleased(event -> {
            panning = false;
            viewport.setCursor(Cursor.DEFAULT);
        });

        viewport.widthProperty().addListener((obs, o, n) -> applyTransform());
        viewport.heightProperty().addListener((obs, o, n) -> applyTransform());
    }

    public void setOnOpenTable(Consumer<Table> action) {
        this.onOpenTable = action == null ? table -> { } : action;
    }

    public void setOnLayoutChanged(Consumer<SchemaDiagramModel> action) {
        this.onLayoutChanged = action == null ? m -> { } : action;
    }

    public void setOnLayoutReset(Runnable action) {
        this.onLayoutReset = action == null ? () -> { } : action;
    }

    public void setModel(SchemaDiagramModel next) {
        this.model = Objects.requireNonNullElseGet(next,
                () -> new SchemaDiagramModel("", null, java.util.List.of(), java.util.List.of()));
        this.selectedTableId = model.focusTableId();
        this.expandedTableIds.clear();
        rebuild();
        javafx.application.Platform.runLater(this::fitToView);
    }

    public SchemaDiagramModel getModel() {
        return model;
    }

    public void resetLayout() {
        onLayoutReset.run();
        expandedTableIds.clear();
        model = SchemaDiagramLayout.relayout(model);
        rebuild();
        javafx.application.Platform.runLater(this::fitToView);
    }

    public void exportPng(File file) throws IOException {
        Objects.requireNonNull(file, "file");
        double savedScaleX = world.getScaleX();
        double savedScaleY = world.getScaleY();
        double savedTx = world.getTranslateX();
        double savedTy = world.getTranslateY();
        try {
            world.setScaleX(1);
            world.setScaleY(1);
            world.setTranslateX(0);
            world.setTranslateY(0);
            SnapshotParameters parameters = new SnapshotParameters();
            parameters.setFill(SNAPSHOT_FILL);
            WritableImage image = world.snapshot(parameters, null);
            writePng(image, file);
        } finally {
            world.setScaleX(savedScaleX);
            world.setScaleY(savedScaleY);
            world.setTranslateX(savedTx);
            world.setTranslateY(savedTy);
        }
    }

    static void writePng(WritableImage image, File file) throws IOException {
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new IOException("Diagram snapshot was empty");
        }
        int width = (int) Math.round(image.getWidth());
        int height = (int) Math.round(image.getHeight());
        PixelReader reader = image.getPixelReader();
        int[] pixels = new int[width * height];
        reader.getPixels(0, 0, width, height, WritablePixelFormat.getIntArgbInstance(), pixels, 0, width);
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, pixels, 0, width);
        if (!ImageIO.write(buffered, "png", file)) {
            throw new IOException("No PNG writer available");
        }
    }

    private void promptExportPng() {
        Window window = getScene() == null ? null : getScene().getWindow();
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Export schema diagram");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG", "*.png"));
        String base = model.catalog().isBlank() ? "schema" : model.catalog();
        chooser.setInitialFileName(base + "-diagram.png");
        File file = chooser.showSaveDialog(window);
        if (file == null) {
            return;
        }
        try {
            exportPng(file);
            status.setText("Exported " + file.getName());
        } catch (IOException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR, "Could not export PNG: " + ex.getMessage(), ButtonType.OK);
            alert.setHeaderText(null);
            if (window != null) {
                alert.initOwner(window);
            }
            alert.showAndWait();
        }
    }

    private void rebuild() {
        edgeLayer.getChildren().clear();
        nodeLayer.getChildren().clear();
        tableNodes.clear();

        for (Table table : model.tables()) {
            VBox card = buildTableCard(table);
            card.setLayoutX(table.x());
            card.setLayoutY(table.y());
            tableNodes.put(table.id(), card);
            nodeLayer.getChildren().add(card);
        }
        redrawEdges();
        applyFilterHighlight();
        applySelectionStyles();
        updateStatus();
        applyTransform();
    }

    private void updateStatus() {
        status.setText(model.tables().size() + " tables \u00b7 " + model.edges().size() + " relations");
        if (model.truncated()) {
            banner.setText("Showing " + model.tables().size() + " of " + model.availableTableCount()
                    + " tables (related tables kept first). Drag tables to rearrange.");
            banner.setVisible(true);
        } else {
            banner.setVisible(false);
        }
    }

    private VBox buildTableCard(Table table) {
        boolean expanded = expandedTableIds.contains(table.id());
        int limit = expanded ? table.columns().size() : SchemaDiagramLayout.MAX_VISIBLE_COLUMNS;

        VBox card = new VBox(0);
        card.getStyleClass().add("schema-diagram-table");
        if (table.view()) {
            card.getStyleClass().add("schema-diagram-view");
        }
        card.setPrefWidth(table.width());
        card.setMinWidth(table.width());
        card.setMaxWidth(table.width());
        card.setCursor(Cursor.MOVE);

        Label title = new Label((table.view() ? "view " : "") + table.name());
        title.getStyleClass().add("schema-diagram-table-title");
        title.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, 12));
        title.setMaxWidth(Double.MAX_VALUE);
        VBox header = new VBox(title);
        header.getStyleClass().add("schema-diagram-table-header");
        header.setPadding(new Insets(6, 10, 6, 10));
        card.getChildren().add(header);

        int visible = Math.min(table.columns().size(), limit);
        for (int i = 0; i < visible; i++) {
            Column column = table.columns().get(i);
            HBox row = new HBox(6);
            row.getStyleClass().add("schema-diagram-column");
            row.setPadding(new Insets(1, 10, 1, 10));
            row.setAlignment(Pos.CENTER_LEFT);

            String marker = column.primaryKey() ? "PK" : (column.foreignKey() ? "FK" : "  ");
            Label key = new Label(marker);
            key.getStyleClass().add("schema-diagram-key");
            if (column.primaryKey()) {
                key.getStyleClass().add("pk");
            } else if (column.foreignKey()) {
                key.getStyleClass().add("fk");
            }
            key.setMinWidth(22);

            Label name = new Label(column.name());
            name.getStyleClass().add("schema-diagram-column-name");
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);

            Label type = new Label(column.dataType());
            type.getStyleClass().add("schema-diagram-column-type");

            row.getChildren().addAll(key, name, type);
            card.getChildren().add(row);
        }
        if (table.columns().size() > SchemaDiagramLayout.MAX_VISIBLE_COLUMNS) {
            Label more = new Label(expanded
                    ? "Show less"
                    : "+" + (table.columns().size() - SchemaDiagramLayout.MAX_VISIBLE_COLUMNS) + " more");
            more.getStyleClass().add("schema-diagram-more");
            more.setPadding(new Insets(2, 10, 6, 10));
            more.setOnMousePressed(MouseEvent::consume);
            more.setOnMouseClicked(event -> {
                if (event.getButton() != MouseButton.PRIMARY) {
                    return;
                }
                toggleExpanded(table.id());
                event.consume();
            });
            card.getChildren().add(more);
        } else {
            Region bottom = new Region();
            bottom.setMinHeight(6);
            card.getChildren().add(bottom);
        }

        String tip = table.catalog().isBlank() ? table.name() : table.catalog() + "." + table.name();
        Tooltip.install(card, new Tooltip(tip + "\nDrag to rearrange"));

        card.setOnMousePressed(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            panning = false;
            draggingTableId = table.id();
            tableMoved = false;
            Point2D local = nodeLayer.sceneToLocal(event.getSceneX(), event.getSceneY());
            tableDragOffsetX = local.getX() - card.getLayoutX();
            tableDragOffsetY = local.getY() - card.getLayoutY();
            selectedTableId = table.id();
            applySelectionStyles();
            redrawEdges();
            event.consume();
        });
        card.setOnMouseDragged(event -> {
            if (!table.id().equals(draggingTableId)) {
                return;
            }
            Point2D local = nodeLayer.sceneToLocal(event.getSceneX(), event.getSceneY());
            double x = local.getX() - tableDragOffsetX;
            double y = local.getY() - tableDragOffsetY;
            if (Math.hypot(x - card.getLayoutX(), y - card.getLayoutY()) > 1) {
                tableMoved = true;
            }
            card.setLayoutX(x);
            card.setLayoutY(y);
            moveTable(table.id(), x, y);
            redrawEdges();
            event.consume();
        });
        card.setOnMouseReleased(event -> {
            if (!table.id().equals(draggingTableId)) {
                return;
            }
            draggingTableId = null;
            if (tableMoved) {
                onLayoutChanged.accept(model);
            }
            event.consume();
        });
        card.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY || tableMoved) {
                return;
            }
            if (event.getClickCount() >= 2) {
                Table current = findTable(table.id());
                onOpenTable.accept(current == null ? table : current);
            }
            event.consume();
        });

        return card;
    }

    private void toggleExpanded(String tableId) {
        if (!expandedTableIds.add(tableId)) {
            expandedTableIds.remove(tableId);
        }
        Table table = findTable(tableId);
        if (table == null) {
            return;
        }
        int cap = expandedTableIds.contains(tableId)
                ? Integer.MAX_VALUE
                : SchemaDiagramLayout.MAX_VISIBLE_COLUMNS;
        Table measured = SchemaDiagramLayout.withMeasuredSize(table, cap);
        replaceTable(table.withBounds(table.x(), table.y(), measured.width(), measured.height()));
        rebuild();
    }

    private void moveTable(String id, double x, double y) {
        Table table = findTable(id);
        if (table == null) {
            return;
        }
        replaceTable(table.withBounds(x, y, table.width(), table.height()));
    }

    private void replaceTable(Table updated) {
        List<Table> next = new ArrayList<>(model.tables().size());
        for (Table table : model.tables()) {
            next.add(table.id().equals(updated.id()) ? updated : table);
        }
        model = model.withTables(next);
    }

    private void redrawEdges() {
        edgeLayer.getChildren().clear();
        Set<String> related = relatedTableIds(selectedTableId);
        for (Edge edge : model.edges()) {
            Table from = findTable(edge.fromTableId());
            Table to = findTable(edge.toTableId());
            if (from == null || to == null) {
                continue;
            }
            boolean active = selectedTableId != null
                    && (related.contains(edge.fromTableId()) && related.contains(edge.toTableId())
                    && (edge.fromTableId().equals(selectedTableId) || edge.toTableId().equals(selectedTableId)));
            boolean dim = selectedTableId != null && !active;

            Point2D start = port(from, to);
            Point2D end = port(to, from);
            CubicCurve curve = new CubicCurve();
            curve.setStartX(start.getX());
            curve.setStartY(start.getY());
            curve.setEndX(end.getX());
            curve.setEndY(end.getY());
            double dx = Math.abs(end.getX() - start.getX()) * 0.4;
            double midY = (start.getY() + end.getY()) / 2;
            curve.setControlX1(start.getX());
            curve.setControlY1(midY);
            curve.setControlX2(end.getX());
            curve.setControlY2(midY);
            if (Math.abs(end.getY() - start.getY()) < 8) {
                curve.setControlX1(start.getX() + dx);
                curve.setControlY1(start.getY() - 28);
                curve.setControlX2(end.getX() - dx);
                curve.setControlY2(end.getY() - 28);
            }
            Color stroke = active ? EDGE_ACTIVE : (dim ? EDGE_DIM : EDGE);
            curve.setStroke(stroke);
            curve.setStrokeWidth(active ? 2.2 : 1.3);
            curve.setFill(null);
            curve.setStrokeLineCap(StrokeLineCap.ROUND);
            curve.getStyleClass().add("schema-diagram-edge");
            if (active) {
                curve.getStyleClass().add("active");
            }

            Polygon arrow = arrowHead(end.getX(), end.getY(),
                    curve.getControlX2(), curve.getControlY2(), stroke);

            Label fromCard = cardinalityLabel(edge.fromCardinality().label(), start, end);
            Label toCard = cardinalityLabel(edge.toCardinality().label(), end, start);
            fromCard.setOpacity(dim ? 0.45 : 1);
            toCard.setOpacity(dim ? 0.45 : 1);

            String tip = (edge.name().isBlank() ? "FK" : edge.name()) + "  " + edge.columnSummary()
                    + "  (" + edge.fromCardinality().label() + " : " + edge.toCardinality().label() + ")";
            if (edge.composite()) {
                Label mid = new Label(edge.fkColumn().replace(",", ", "));
                mid.getStyleClass().add("schema-diagram-edge-label");
                mid.setLayoutX((start.getX() + end.getX()) / 2 - 24);
                mid.setLayoutY((start.getY() + end.getY()) / 2 - 18);
                mid.setMouseTransparent(true);
                Tooltip.install(mid, new Tooltip(tip));
                edgeLayer.getChildren().addAll(curve, arrow, fromCard, toCard, mid);
            } else {
                Tooltip.install(fromCard, new Tooltip(tip));
                edgeLayer.getChildren().addAll(curve, arrow, fromCard, toCard);
            }
        }
    }

    private static Label cardinalityLabel(String text, Point2D at, Point2D toward) {
        Label label = new Label(text);
        label.getStyleClass().add("schema-diagram-cardinal");
        label.setMouseTransparent(true);
        double dx = toward.getX() - at.getX();
        double dy = toward.getY() - at.getY();
        double len = Math.hypot(dx, dy);
        if (len < 1) {
            len = 1;
        }
        double nx = at.getX() + dx / len * 16;
        double ny = at.getY() + dy / len * 12;
        label.setLayoutX(nx - 10);
        label.setLayoutY(ny - 8);
        return label;
    }

    private static Polygon arrowHead(double x, double y, double fromX, double fromY, Color color) {
        double angle = Math.atan2(y - fromY, x - fromX);
        double size = 8;
        double x1 = x - size * Math.cos(angle - Math.PI / 7);
        double y1 = y - size * Math.sin(angle - Math.PI / 7);
        double x2 = x - size * Math.cos(angle + Math.PI / 7);
        double y2 = y - size * Math.sin(angle + Math.PI / 7);
        Polygon head = new Polygon(x, y, x1, y1, x2, y2);
        head.setFill(color);
        return head;
    }

    private Point2D port(Table self, Table other) {
        double cx = self.x() + self.width() / 2;
        double cy = self.y() + self.height() / 2;
        double ox = other.x() + other.width() / 2;
        double oy = other.y() + other.height() / 2;
        if (Math.abs(oy - cy) >= Math.abs(ox - cx)) {
            if (oy < cy) {
                return new Point2D(cx, self.y());
            }
            return new Point2D(cx, self.y() + self.height());
        }
        if (ox < cx) {
            return new Point2D(self.x(), cy);
        }
        return new Point2D(self.x() + self.width(), cy);
    }

    private Table findTable(String id) {
        for (Table table : model.tables()) {
            if (table.id().equals(id)) {
                return table;
            }
        }
        return null;
    }

    private Set<String> relatedTableIds(String tableId) {
        Set<String> related = new HashSet<>();
        if (tableId == null) {
            return related;
        }
        related.add(tableId);
        for (Edge edge : model.edges()) {
            if (edge.fromTableId().equals(tableId)) {
                related.add(edge.toTableId());
            }
            if (edge.toTableId().equals(tableId)) {
                related.add(edge.fromTableId());
            }
        }
        return related;
    }

    private void applySelectionStyles() {
        Set<String> related = relatedTableIds(selectedTableId);
        for (Map.Entry<String, VBox> entry : tableNodes.entrySet()) {
            VBox card = entry.getValue();
            card.getStyleClass().removeAll("selected", "related", "dimmed");
            if (selectedTableId == null) {
                continue;
            }
            if (entry.getKey().equals(selectedTableId)) {
                card.getStyleClass().add("selected");
            } else if (related.contains(entry.getKey())) {
                card.getStyleClass().add("related");
            } else {
                card.getStyleClass().add("dimmed");
            }
        }
    }

    private void applyFilterHighlight() {
        String q = filterQuery.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, VBox> entry : tableNodes.entrySet()) {
            Table table = findTable(entry.getKey());
            boolean match = q.isEmpty()
                    || (table != null && table.name().toLowerCase(Locale.ROOT).contains(q));
            entry.getValue().setOpacity(match ? 1.0 : 0.25);
        }
    }

    private void zoomAt(double viewX, double viewY, double factor) {
        double next = clamp(scale * factor, 0.25, 2.5);
        if (next == scale) {
            return;
        }
        double contentX = (viewX - panX) / scale;
        double contentY = (viewY - panY) / scale;
        scale = next;
        panX = viewX - contentX * scale;
        panY = viewY - contentY * scale;
        applyTransform();
    }

    private void applyTransform() {
        world.setScaleX(scale);
        world.setScaleY(scale);
        world.setTranslateX(panX);
        world.setTranslateY(panY);
    }

    public void fitToView() {
        if (model.tables().isEmpty()) {
            return;
        }
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (Table table : model.tables()) {
            minX = Math.min(minX, table.x());
            minY = Math.min(minY, table.y());
            maxX = Math.max(maxX, table.x() + table.width());
            maxY = Math.max(maxY, table.y() + table.height());
        }
        double contentW = Math.max(1, maxX - minX + 80);
        double contentH = Math.max(1, maxY - minY + 80);
        double viewW = Math.max(200, viewport.getWidth());
        double viewH = Math.max(200, viewport.getHeight());
        if (viewW <= 0 || viewH <= 0) {
            return;
        }
        scale = clamp(Math.min(viewW / contentW, viewH / contentH), 0.35, 1.4);
        panX = (viewW - contentW * scale) / 2 - minX * scale + 20 * scale;
        panY = (viewH - contentH * scale) / 2 - minY * scale + 20 * scale;
        applyTransform();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
