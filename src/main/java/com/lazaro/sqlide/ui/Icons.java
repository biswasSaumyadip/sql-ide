package com.lazaro.sqlide.ui;

import com.lazaro.sqlide.core.db.SchemaNode;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Ellipse;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;

/**
 * Icons drawn from primitive shapes.
 *
 * <p>Deliberately avoids an icon-font dependency: glyph fonts render inconsistently
 * across platforms, whereas these are exact everywhere and take their colour from
 * CSS via the {@code icon} style class.
 */
public final class Icons {

    private static final String BASE_CLASS = "icon";
    private static final double STROKE = 1.2;

    private Icons() {
    }

    // ---------------------------------------------------------------- schema tree

    public static Node forNode(SchemaNode node) {
        return switch (node.type()) {
            case DATA_SOURCE, DATABASE, SCHEMA -> database();
            case TABLE -> table();
            case VIEW -> view();
            case COLUMN -> node.metadataFlag(SchemaNode.META_PRIMARY_KEY) ? primaryKeyColumn() : column();
        };
    }

    /** Three stacked discs, the conventional database glyph. */
    public static Node database() {
        Ellipse top = outlined(new Ellipse(7, 3.5, 5.5, 2.2), "icon-database");
        Ellipse middle = outlined(new Ellipse(7, 7, 5.5, 2.2), "icon-database");
        Ellipse bottom = outlined(new Ellipse(7, 10.5, 5.5, 2.2), "icon-database");
        return group(top, middle, bottom);
    }

    /** A grid: outer frame plus one row and one column rule. */
    public static Node table() {
        Rectangle frame = outlined(new Rectangle(1.5, 2.5, 11, 9), "icon-table");
        Line row = outlined(new Line(1.5, 5.5, 12.5, 5.5), "icon-table");
        Line divider = outlined(new Line(6, 5.5, 6, 11.5), "icon-table");
        return group(frame, row, divider);
    }

    /** A frame with an eye, distinguishing a view from a stored table. */
    public static Node view() {
        Rectangle frame = outlined(new Rectangle(1.5, 2.5, 11, 9), "icon-view");
        Circle pupil = outlined(new Circle(7, 7, 2), "icon-view");
        return group(frame, pupil);
    }

    /** A short bar, suggesting a single field. */
    public static Node column() {
        Rectangle bar = outlined(new Rectangle(2.5, 5.5, 9, 3), "icon-column");
        bar.setArcWidth(3);
        bar.setArcHeight(3);
        return group(bar);
    }

    /** A key ring with a shaft, marking a primary key column. */
    public static Node primaryKeyColumn() {
        Circle ring = outlined(new Circle(4.5, 7, 2.4), "icon-key");
        Line shaft = outlined(new Line(7, 7, 12, 7), "icon-key");
        Line tooth = outlined(new Line(10.5, 7, 10.5, 9.5), "icon-key");
        return group(ring, shaft, tooth);
    }

    // ---------------------------------------------------------------- toolbar

    /** Filled triangle for Execute. */
    public static Node run() {
        Polygon triangle = new Polygon(3.5, 2.0, 12.0, 7.0, 3.5, 12.0);
        triangle.getStyleClass().addAll(BASE_CLASS, "icon-run");
        return group(triangle);
    }

    /** Filled square for Stop / cancel. */
    public static Node stop() {
        Rectangle square = new Rectangle(3.5, 3.5, 7, 7);
        square.getStyleClass().addAll(BASE_CLASS, "icon-stop");
        return group(square);
    }

    /** Open bracket / play for Begin transaction. */
    public static Node begin() {
        Polygon triangle = new Polygon(4.0, 3.0, 11.0, 7.0, 4.0, 11.0);
        triangle.getStyleClass().addAll(BASE_CLASS, "icon-begin");
        Line bar = outlined(new Line(2.5, 3.0, 2.5, 11.0), "icon-begin-bar");
        return group(triangle, bar);
    }

    /** Check mark for Commit. */
    public static Node commit() {
        Line a = outlined(new Line(2.5, 7.5, 5.5, 11.0), "icon-commit");
        Line b = outlined(new Line(5.5, 11.0, 11.5, 3.0), "icon-commit");
        return group(a, b);
    }

    /** Curved arrow for Rollback. */
    public static Node rollback() {
        Arc arc = outlined(new Arc(7, 7.5, 4.5, 4.5, 40, 220), "icon-rollback");
        arc.setType(ArcType.OPEN);
        Polygon head = new Polygon(3.2, 4.0, 1.5, 8.2, 5.8, 7.5);
        head.getStyleClass().addAll(BASE_CLASS, "icon-rollback-head");
        return group(arc, head);
    }

    /** Branching tree glyph for Explain. */
    public static Node explain() {
        Line trunk = outlined(new Line(7, 2.5, 7, 7), "icon-explain");
        Line left = outlined(new Line(7, 7, 3, 11.5), "icon-explain");
        Line right = outlined(new Line(7, 7, 11, 11.5), "icon-explain");
        Circle node = outlined(new Circle(7, 7, 1.6), "icon-explain");
        return group(trunk, left, right, node);
    }

    /** Power symbol for Connect. */
    public static Node connect() {
        Arc ring = outlined(new Arc(7, 7.5, 5, 5, -60, 300), "icon-connect");
        ring.setType(ArcType.OPEN);
        Line stem = outlined(new Line(7, 1.5, 7, 6.5), "icon-connect");
        return group(ring, stem);
    }

    /** Power symbol struck through, for Disconnect. */
    public static Node disconnect() {
        Arc ring = outlined(new Arc(7, 7.5, 5, 5, -60, 300), "icon-disconnect");
        ring.setType(ArcType.OPEN);
        Line stem = outlined(new Line(7, 1.5, 7, 6.5), "icon-disconnect");
        Line slash = outlined(new Line(2, 12.5, 12, 2.5), "icon-disconnect");
        return group(ring, stem, slash);
    }

    /** Circular arrow for Refresh: an arc broken by an arrowhead at its head. */
    public static Node refresh() {
        Arc arc = outlined(new Arc(7, 7.4, 4.8, 4.8, 55, 265), "icon-refresh");
        arc.setType(ArcType.OPEN);
        // Centred on the arc's start point (9.75, 3.47) so the two read as one stroke.
        Polygon head = new Polygon(7.4, 1.7, 12.5, 2.9, 10.1, 6.0);
        head.getStyleClass().addAll(BASE_CLASS, "icon-refresh-head");
        return group(arc, head);
    }

    /** Panel outline with a filled left rail, for the sidebar toggle. */
    public static Node sidebar() {
        Rectangle frame = outlined(new Rectangle(1.5, 2.5, 11, 9), "icon-sidebar");
        Rectangle rail = new Rectangle(1.5, 2.5, 3.5, 9);
        rail.getStyleClass().addAll(BASE_CLASS, "icon-sidebar-rail");
        return group(frame, rail);
    }

    /** Plus sign for New Query. */
    public static Node newQuery() {
        Line horizontal = outlined(new Line(2.5, 7, 11.5, 7), "icon-new");
        Line vertical = outlined(new Line(7, 2.5, 7, 11.5), "icon-new");
        return group(horizontal, vertical);
    }

    /** Floppy outline for Save. */
    public static Node save() {
        Rectangle body = outlined(new Rectangle(2, 2.5, 10, 9), "icon-save");
        Rectangle shutter = outlined(new Rectangle(4.5, 2.5, 5, 3.5), "icon-save");
        Rectangle label = outlined(new Rectangle(4, 8, 6, 3.5), "icon-save");
        return group(body, shutter, label);
    }

    // ---------------------------------------------------------------- helpers

    private static <T extends Shape> T outlined(T shape, String variantClass) {
        shape.setFill(null);
        shape.setStrokeWidth(STROKE);
        shape.getStyleClass().addAll(BASE_CLASS, variantClass);
        return shape;
    }

    private static Group group(Shape... shapes) {
        Group icon = new Group(shapes);
        // Icons are decoration; clicks belong to the control underneath.
        icon.setMouseTransparent(true);
        return icon;
    }
}
