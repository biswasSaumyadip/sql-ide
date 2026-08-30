package com.lazaro.sqlide.ui.diagram;

import com.lazaro.sqlide.core.diagram.SchemaDiagramModel;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Column;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Edge;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel.Table;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
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

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Interactive DataGrip-style schema diagram: pan, zoom, select, highlight FKs.
 */
public final class SchemaDiagramCanvas extends BorderPane {

    private static final int MAX_VISIBLE_COLUMNS = 14;
    private static final Color EDGE = Color.web("#6b7280");
    private static final Color EDGE_ACTIVE = Color.web("#4fa6ee");
    private static final Color EDGE_DIM = Color.web("#3a3f48", 0.45);

    private final Pane edgeLayer = new Pane();
    private final Pane nodeLayer = new Pane();
    private final Group world = new Group(edgeLayer, nodeLayer);
    private final Pane viewport = new Pane(world);
    private final Label status = new Label();
    private final TextField filterField = new TextField();

    private SchemaDiagramModel model =
            new SchemaDiagramModel("", null, java.util.List.of(), java.util.List.of());
    private final Map<String, VBox> tableNodes = new HashMap<>();
    private String selectedTableId;
    private String filterQuery = "";
    private Consumer<Table> onOpenTable = table -> { };

    private double scale = 1.0;
    private double panX;
    private double panY;
    private double dragStartX;
    private double dragStartY;
    private double panAtDragStartX;
    private double panAtDragStartY;
    private boolean panning;

    public SchemaDiagramCanvas() {
        getStyleClass().add("schema-diagram-canvas");

        filterField.setPromptText("Filter tables\u2026");
        filterField.getStyleClass().add("schema-diagram-filter");
        filterField.textProperty().addListener((obs, o, n) -> {
            filterQuery = n == null ? "" : n.strip();
            applyFilterHighlight();
        });

        javafx.scene.control.Button fit = new javafx.scene.control.Button("Fit");
        fit.getStyleClass().add("labelled-button");
        fit.setOnAction(event -> fitToView());

        javafx.scene.control.Button reset = new javafx.scene.control.Button("Reset zoom");
        reset.getStyleClass().add("labelled-button");
        reset.setOnAction(event -> {
            scale = 1.0;
            panX = 0;
            panY = 0;
            applyTransform();
        });

        status.getStyleClass().add("schema-diagram-status");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, filterField, fit, reset, spacer, status);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(8, 12, 8, 12));
        toolbar.getStyleClass().add("schema-diagram-toolbar");
        HBox.setHgrow(filterField, Priority.ALWAYS);
        setTop(toolbar);

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
            if (event.getButton() != MouseButton.PRIMARY && event.getButton() != MouseButton.MIDDLE) {
                return;
            }
            if (event.getTarget() != viewport && !(event.getTarget() instanceof Pane)) {
                // allow pan only on empty chrome; table cards handle their own clicks
            }
            panning = true;
            dragStartX = event.getX();
            dragStartY = event.getY();
            panAtDragStartX = panX;
            panAtDragStartY = panY;
            viewport.setCursor(Cursor.MOVE);
        });
        viewport.setOnMouseDragged(event -> {
            if (!panning) {
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

    public void setModel(SchemaDiagramModel next) {
        this.model = Objects.requireNonNullElseGet(next,
                () -> new SchemaDiagramModel("", null, java.util.List.of(), java.util.List.of()));
        this.selectedTableId = model.focusTableId();
        rebuild();
        javafx.application.Platform.runLater(this::fitToView);
    }

    public SchemaDiagramModel getModel() {
        return model;
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
        status.setText(model.tables().size() + " tables \u00b7 " + model.edges().size() + " relations");
        applyTransform();
    }

    private VBox buildTableCard(Table table) {
        VBox card = new VBox(0);
        card.getStyleClass().add("schema-diagram-table");
        if (table.view()) {
            card.getStyleClass().add("schema-diagram-view");
        }
        card.setPrefWidth(table.width());
        card.setMinWidth(table.width());
        card.setMaxWidth(table.width());

        Label title = new Label((table.view() ? "view " : "") + table.name());
        title.getStyleClass().add("schema-diagram-table-title");
        title.setFont(Font.font("JetBrains Mono", FontWeight.BOLD, 12));
        title.setMaxWidth(Double.MAX_VALUE);
        VBox header = new VBox(title);
        header.getStyleClass().add("schema-diagram-table-header");
        header.setPadding(new Insets(6, 10, 6, 10));
        card.getChildren().add(header);

        int limit = Math.min(table.columns().size(), MAX_VISIBLE_COLUMNS);
        for (int i = 0; i < limit; i++) {
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
        if (table.columns().size() > MAX_VISIBLE_COLUMNS) {
            Label more = new Label("+" + (table.columns().size() - MAX_VISIBLE_COLUMNS) + " more");
            more.getStyleClass().add("schema-diagram-more");
            more.setPadding(new Insets(2, 10, 6, 10));
            card.getChildren().add(more);
        } else {
            Region bottom = new Region();
            bottom.setMinHeight(6);
            card.getChildren().add(bottom);
        }

        String tip = table.catalog().isBlank() ? table.name() : table.catalog() + "." + table.name();
        Tooltip.install(card, new Tooltip(tip));

        card.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                return;
            }
            selectedTableId = table.id();
            applySelectionStyles();
            redrawEdges();
            if (event.getClickCount() >= 2) {
                onOpenTable.accept(table);
            }
            event.consume();
        });
        card.setOnMousePressed(event -> event.consume());

        return card;
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
            curve.setStroke(active ? EDGE_ACTIVE : (dim ? EDGE_DIM : EDGE));
            curve.setStrokeWidth(active ? 2.2 : 1.3);
            curve.setFill(null);
            curve.setStrokeLineCap(StrokeLineCap.ROUND);
            curve.getStyleClass().add("schema-diagram-edge");
            if (active) {
                curve.getStyleClass().add("active");
            }

            Polygon arrow = arrowHead(end.getX(), end.getY(),
                    curve.getControlX2(), curve.getControlY2(),
                    active ? EDGE_ACTIVE : (dim ? EDGE_DIM : EDGE));
            edgeLayer.getChildren().addAll(curve, arrow);
        }
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
        // Keep the point under the cursor stable.
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
