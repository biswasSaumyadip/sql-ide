package com.lazaro.sqlide;

import atlantafx.base.theme.CupertinoDark;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.ui.components.DynamicResultTable;
import com.lazaro.sqlide.ui.components.SqlEditorPane;
import javafx.application.Application;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;

import java.util.Arrays;
import java.util.List;

/**
 * Application entry point. Currently shows a preview of the editor and result grid;
 * Phase 4 replaces this with the real layout driven by live connections.
 */
public final class Main extends Application {

    private static final String APP_TITLE = "SQL IDE";
    private static final int WINDOW_WIDTH = 1024;
    private static final int WINDOW_HEIGHT = 768;
    private static final int MIN_WINDOW_WIDTH = 800;
    private static final int MIN_WINDOW_HEIGHT = 600;

    private SqlEditorPane editor;

    @Override
    public void start(Stage stage) {
        Application.setUserAgentStylesheet(new CupertinoDark().getUserAgentStylesheet());

        editor = new SqlEditorPane(SAMPLE_SQL);
        var results = new DynamicResultTable();
        results.setResult(SAMPLE_RESULT);

        var split = new SplitPane(editor, results);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.45);

        var root = new BorderPane(split);
        var scene = new Scene(root, WINDOW_WIDTH, WINDOW_HEIGHT);

        stage.setScene(scene);
        stage.setTitle(APP_TITLE);
        stage.setMinWidth(MIN_WINDOW_WIDTH);
        stage.setMinHeight(MIN_WINDOW_HEIGHT);
        stage.show();
        editor.requestFocus();
    }

    @Override
    public void stop() {
        if (editor != null) {
            editor.dispose();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }

    private static final String SAMPLE_SQL = """
            -- Phase 3 preview: highlighting is computed off the UI thread
            SELECT c.id,
                   c.email,
                   COUNT(o.id)   AS order_count,
                   SUM(o.total)  AS lifetime_value
            FROM customer AS c
                     LEFT JOIN orders AS o ON o.customer_id = c.id
            WHERE c.signed_up >= '2026-01-01'
              AND c.status NOT IN ('banned', 'deleted')
            GROUP BY c.id, c.email
            HAVING COUNT(o.id) > 3
            ORDER BY lifetime_value DESC
            LIMIT 100;
            """;

    private static final QueryResult SAMPLE_RESULT = QueryResult.ofRows(
            List.of("id", "email", "order_count", "lifetime_value"),
            List.of(
                    List.of("1", "ada@example.com", "12", "4820.50"),
                    List.of("2", "grace@example.com", "9", "3110.00"),
                    Arrays.asList("3", "alan@example.com", "7", null),
                    List.of("4", "edsger@example.com", "4", "612.25")),
            7L);
}
