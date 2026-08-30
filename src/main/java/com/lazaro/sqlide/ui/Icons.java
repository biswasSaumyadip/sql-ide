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
            case DATA_SOURCE -> database();
            case DATABASE, SCHEMA -> schema();
            case FOLDER -> folder();
            case TABLE -> table();
            case VIEW -> view();
            case COLUMN -> node.metadataFlag(SchemaNode.META_PRIMARY_KEY) ? primaryKeyColumn() : column();
            case KEY -> key();
            case INDEX -> index();
        };
    }

    /** Three stacked discs, the conventional database glyph. */
    public static Node database() {
        Ellipse top = outlined(new Ellipse(7, 3.5, 5.5, 2.2), "icon-database");
        Ellipse middle = outlined(new Ellipse(7, 7, 5.5, 2.2), "icon-database");
        Ellipse bottom = outlined(new Ellipse(7, 10.5, 5.5, 2.2), "icon-database");
        return group(top, middle, bottom);
    }

    /**
     * Folder glyph for a schema/namespace — distinct from the database cylinder stack.
     */
    public static Node schema() {
        // Tab
        Rectangle tab = outlined(new Rectangle(2.0, 3.0, 4.5, 2.2), "icon-schema");
        tab.setArcWidth(1.5);
        tab.setArcHeight(1.5);
        // Body
        Rectangle body = outlined(new Rectangle(2.0, 5.0, 10.0, 7.0), "icon-schema");
        body.setArcWidth(1.5);
        body.setArcHeight(1.5);
        return group(tab, body);
    }

    /** Standard folder for logical groupings (tables / columns / keys / indexes). */
    public static Node folder() {
        Rectangle tab = outlined(new Rectangle(2.0, 3.0, 4.5, 2.2), "icon-folder");
        tab.setArcWidth(1.5);
        tab.setArcHeight(1.5);
        Rectangle body = outlined(new Rectangle(2.0, 5.0, 10.0, 7.0), "icon-folder");
        body.setArcWidth(1.5);
        body.setArcHeight(1.5);
        return group(tab, body);
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

    /** Gold-tinted key glyph for KEY nodes under the keys folder. */
    public static Node key() {
        Circle ring = outlined(new Circle(4.5, 7, 2.4), "icon-key-gold");
        Line shaft = outlined(new Line(7, 7, 12, 7), "icon-key-gold");
        Line tooth = outlined(new Line(10.5, 7, 10.5, 9.5), "icon-key-gold");
        return group(ring, shaft, tooth);
    }

    /** Lightning bolt for indexes. */
    public static Node index() {
        Polygon bolt = new Polygon(
                8.5, 1.5,
                4.0, 7.5,
                7.0, 7.5,
                5.5, 12.5,
                11.0, 6.0,
                8.0, 6.0);
        bolt.setFill(null);
        bolt.setStrokeWidth(STROKE);
        bolt.getStyleClass().addAll(BASE_CLASS, "icon-index");
        return group(bolt);
    }

    /** Small {@code f( )} glyph for SQL functions / aggregates. */
    public static Node function() {
        // stylized f
        Line stem = outlined(new Line(5.5, 3.0, 5.5, 11.5), "icon-function");
        Line cross = outlined(new Line(4.0, 6.0, 8.5, 6.0), "icon-function");
        Arc bowl = outlined(new Arc(5.5, 4.5, 2.2, 1.8, 200, 140), "icon-function");
        bowl.setType(ArcType.OPEN);
        // trailing ()
        Arc left = outlined(new Arc(10.5, 7.5, 1.6, 3.2, 90, 180), "icon-function");
        left.setType(ArcType.OPEN);
        Arc right = outlined(new Arc(12.8, 7.5, 1.6, 3.2, 270, 180), "icon-function");
        right.setType(ArcType.OPEN);
        return group(stem, cross, bowl, left, right);
    }

    /** Puzzle-piece-ish mark for abbreviation snippets. */
    public static Node snippet() {
        Rectangle body = outlined(new Rectangle(2.5, 3.5, 9, 8), "icon-snippet");
        body.setArcWidth(2);
        body.setArcHeight(2);
        Line notch = outlined(new Line(7, 3.5, 7, 11.5), "icon-snippet");
        return group(body, notch);
    }

    /** Keyword glyph — small diamond. */
    public static Node keyword() {
        Polygon diamond = new Polygon(7, 2.5, 11.5, 7, 7, 11.5, 2.5, 7);
        diamond.setFill(null);
        diamond.setStrokeWidth(STROKE);
        diamond.getStyleClass().addAll(BASE_CLASS, "icon-keyword");
        return group(diamond);
    }

    /** Join / relationship chevron. */
    public static Node join() {
        Line a = outlined(new Line(2.5, 4.0, 7.0, 7.0), "icon-join");
        Line b = outlined(new Line(2.5, 10.0, 7.0, 7.0), "icon-join");
        Line c = outlined(new Line(7.0, 7.0, 12.0, 7.0), "icon-join");
        return group(a, b, c);
    }

    /** Colon mark for named query parameters. */
    public static Node parameter() {
        Circle dot = outlined(new Circle(7, 4.5, 1.4), "icon-parameter");
        Line shaft = outlined(new Line(7, 7.0, 7, 11.5), "icon-parameter");
        return group(dot, shaft);
    }

    /** Gear for Settings. */
    public static Node settings() {
        Circle hub = outlined(new Circle(7, 7, 2.2), "icon-settings");
        // eight short teeth
        Line n = outlined(new Line(7, 1.5, 7, 3.2), "icon-settings");
        Line s = outlined(new Line(7, 10.8, 7, 12.5), "icon-settings");
        Line e = outlined(new Line(10.8, 7, 12.5, 7), "icon-settings");
        Line w = outlined(new Line(1.5, 7, 3.2, 7), "icon-settings");
        Line ne = outlined(new Line(10.2, 3.8, 11.4, 2.6), "icon-settings");
        Line nw = outlined(new Line(3.8, 3.8, 2.6, 2.6), "icon-settings");
        Line se = outlined(new Line(10.2, 10.2, 11.4, 11.4), "icon-settings");
        Line sw = outlined(new Line(3.8, 10.2, 2.6, 11.4), "icon-settings");
        return group(hub, n, s, e, w, ne, nw, se, sw);
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

    /** Tray with a downward arrow for Export. */
    public static Node export() {
        Line shaft = outlined(new Line(7, 2.0, 7, 8.5), "icon-export");
        Polygon head = new Polygon(4.0, 7.0, 7.0, 11.0, 10.0, 7.0);
        head.setFill(null);
        head.setStrokeWidth(STROKE);
        head.getStyleClass().addAll(BASE_CLASS, "icon-export");
        Line tray = outlined(new Line(2.5, 12.0, 11.5, 12.0), "icon-export");
        return group(shaft, head, tray);
    }

    /** Pushpin for pinning a result tab. */
    public static Node pin() {
        Line shaft = outlined(new Line(7, 8.5, 7, 12.5), "icon-pin");
        Ellipse head = outlined(new Ellipse(7, 5.5, 3.2, 2.6), "icon-pin");
        Line arm = outlined(new Line(4.0, 5.5, 10.0, 5.5), "icon-pin");
        return group(head, arm, shaft);
    }

    /** Trash / clear glyph for clearing result tabs. */
    public static Node clear() {
        Line lid = outlined(new Line(3.5, 4.5, 10.5, 4.5), "icon-clear");
        Line handle = outlined(new Line(5.5, 3.0, 8.5, 3.0), "icon-clear");
        Rectangle body = outlined(new Rectangle(4.0, 5.0, 6.0, 7.5), "icon-clear");
        body.setArcWidth(1.5);
        body.setArcHeight(1.5);
        Line crease = outlined(new Line(7.0, 6.5, 7.0, 10.5), "icon-clear");
        return group(lid, handle, body, crease);
    }

    /** Funnel glyph for the schema-filter toolbar action. */
    public static Node schemaFilter() {
        Polygon funnel = new Polygon(
                2.0, 2.5,
                12.0, 2.5,
                8.2, 7.0,
                8.2, 11.5,
                5.8, 11.5,
                5.8, 7.0);
        funnel.setFill(null);
        funnel.setStrokeWidth(STROKE);
        funnel.getStyleClass().addAll(BASE_CLASS, "icon-schema-filter");
        return group(funnel);
    }

    /** Magnifying glass for find-in-results. */
    public static Node find() {
        Circle lens = outlined(new Circle(6.0, 6.0, 3.8), "icon-find");
        Line handle = outlined(new Line(8.8, 8.8, 12.0, 12.0), "icon-find");
        return group(lens, handle);
    }

    /** Two overlapping sheets for quick copy. */
    public static Node copy() {
        Rectangle back = outlined(new Rectangle(4.0, 2.0, 7.0, 8.0), "icon-copy");
        Rectangle front = outlined(new Rectangle(2.5, 4.0, 7.0, 8.0), "icon-copy");
        return group(back, front);
    }

    /** Horizontal arrows for fit-column-widths. */
    public static Node fitColumns() {
        Line leftShaft = outlined(new Line(1.5, 7.0, 5.5, 7.0), "icon-fit");
        Polygon leftHead = new Polygon(1.5, 7.0, 4.0, 4.8, 4.0, 9.2);
        leftHead.setFill(null);
        leftHead.setStrokeWidth(STROKE);
        leftHead.getStyleClass().addAll(BASE_CLASS, "icon-fit");
        Line rightShaft = outlined(new Line(8.5, 7.0, 12.5, 7.0), "icon-fit");
        Polygon rightHead = new Polygon(12.5, 7.0, 10.0, 4.8, 10.0, 9.2);
        rightHead.setFill(null);
        rightHead.setStrokeWidth(STROKE);
        rightHead.getStyleClass().addAll(BASE_CLASS, "icon-fit");
        Line mid = outlined(new Line(6.2, 3.5, 6.2, 10.5), "icon-fit");
        Line mid2 = outlined(new Line(7.8, 3.5, 7.8, 10.5), "icon-fit");
        return group(leftShaft, leftHead, rightShaft, rightHead, mid, mid2);
    }

    /** Compact grid for result-table view. */
    public static Node grid() {
        Rectangle frame = outlined(new Rectangle(2.0, 2.5, 10.0, 9.0), "icon-grid");
        Line v = outlined(new Line(7.0, 2.5, 7.0, 11.5), "icon-grid");
        Line h = outlined(new Line(2.0, 7.0, 12.0, 7.0), "icon-grid");
        return group(frame, v, h);
    }

    /** Branching tree for EXPLAIN plan view. */
    public static Node planTree() {
        Line trunk = outlined(new Line(4.0, 3.0, 4.0, 11.0), "icon-plan");
        Line branch = outlined(new Line(4.0, 7.0, 10.0, 7.0), "icon-plan");
        Line leaf = outlined(new Line(10.0, 7.0, 10.0, 11.0), "icon-plan");
        Circle root = outlined(new Circle(4.0, 3.0, 1.4), "icon-plan");
        Circle mid = outlined(new Circle(10.0, 7.0, 1.4), "icon-plan");
        Circle tip = outlined(new Circle(10.0, 11.0, 1.4), "icon-plan");
        return group(trunk, branch, leaf, root, mid, tip);
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
