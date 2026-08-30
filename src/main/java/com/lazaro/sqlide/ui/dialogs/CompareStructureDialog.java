package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.diff.AlterScriptGenerator;
import com.lazaro.sqlide.core.diff.SchemaDiff;
import com.lazaro.sqlide.core.diff.SchemaDiffService;
import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.session.ConnectionSession;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Compares structure of two tables and optionally emits an ALTER migration script.
 */
public final class CompareStructureDialog extends Dialog<String> {

    public record TableRef(ConnectionSession session, SchemaNode table) {
        @Override
        public String toString() {
            String catalog = table.metadata(SchemaNode.META_CATALOG);
            String q = catalog == null || catalog.isBlank() ? table.name() : catalog + "." + table.name();
            return session.displayName() + " / " + q;
        }
    }

    private final ComboBox<TableRef> leftBox = new ComboBox<>();
    private final ComboBox<TableRef> rightBox = new ComboBox<>();
    private final ListView<String> changesList = new ListView<>();
    private final TextArea alterArea = new TextArea();

    public CompareStructureDialog(Window owner, List<TableRef> tables) {
        setTitle("Compare Structure");
        setHeaderText("Diff two tables and generate ALTER migration (left \u2192 right)");
        if (owner != null) {
            initOwner(owner);
        }
        getDialogPane().getButtonTypes().setAll(
                new ButtonType("Open ALTER in Editor", ButtonType.OK.getButtonData()),
                ButtonType.CLOSE);
        getDialogPane().lookupButton(getDialogPane().getButtonTypes().getFirst())
                .getStyleClass().add(Styles.ACCENT);

        List<TableRef> items = tables == null ? List.of() : List.copyOf(tables);
        leftBox.setItems(FXCollections.observableArrayList(items));
        rightBox.setItems(FXCollections.observableArrayList(items));
        leftBox.setMaxWidth(Double.MAX_VALUE);
        rightBox.setMaxWidth(Double.MAX_VALUE);
        if (items.size() >= 1) {
            leftBox.getSelectionModel().selectFirst();
        }
        if (items.size() >= 2) {
            rightBox.getSelectionModel().select(1);
        } else if (items.size() == 1) {
            rightBox.getSelectionModel().selectFirst();
        }

        leftBox.valueProperty().addListener((o, a, b) -> refreshDiff());
        rightBox.valueProperty().addListener((o, a, b) -> refreshDiff());

        alterArea.setEditable(false);
        alterArea.setWrapText(true);
        alterArea.setPrefRowCount(12);
        alterArea.getStyleClass().add("mono-area");
        changesList.setPrefHeight(140);

        GridPane pickers = new GridPane();
        pickers.setHgap(12);
        pickers.setVgap(8);
        pickers.add(new Label("Left (current)"), 0, 0);
        pickers.add(leftBox, 1, 0);
        pickers.add(new Label("Right (desired)"), 0, 1);
        pickers.add(rightBox, 1, 1);
        GridPane.setHgrow(leftBox, Priority.ALWAYS);
        GridPane.setHgrow(rightBox, Priority.ALWAYS);

        VBox root = new VBox(12, pickers, new Label("Differences"), changesList,
                new Label("ALTER script"), alterArea);
        root.setPadding(new Insets(8));
        VBox.setVgrow(alterArea, Priority.ALWAYS);
        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(720, 560);
        getDialogPane().getStyleClass().add("compare-structure-dialog");

        setResultConverter(button -> {
            if (button == null || button == ButtonType.CLOSE) {
                return null;
            }
            String sql = alterArea.getText();
            return sql == null || sql.isBlank() || sql.startsWith("-- No structural") ? null : sql;
        });

        refreshDiff();
    }

    private void refreshDiff() {
        TableRef left = leftBox.getValue();
        TableRef right = rightBox.getValue();
        if (left == null || right == null) {
            changesList.getItems().clear();
            alterArea.setText("-- Select two tables");
            return;
        }
        SchemaDiff diff = SchemaDiffService.diffTables(left.table(), right.table());
        List<String> lines = new ArrayList<>();
        for (SchemaDiff.Change change : diff.changes()) {
            lines.add(change.summary());
        }
        if (lines.isEmpty()) {
            lines.add("(no differences)");
        }
        changesList.setItems(FXCollections.observableArrayList(lines));
        String table = right.toString().contains("/")
                ? right.toString().substring(right.toString().lastIndexOf('/') + 1).trim()
                : right.table().name();
        alterArea.setText(AlterScriptGenerator.generate(table, diff));
    }

    public static List<TableRef> collectTables(List<ConnectionSession> sessions) {
        List<TableRef> refs = new ArrayList<>();
        for (ConnectionSession session : sessions) {
            SchemaCache cache = session.schemaCache();
            for (SchemaNode table : cache.tables()) {
                refs.add(new TableRef(session, table));
            }
        }
        return refs;
    }

    public static Optional<String> showAndGetAlter(Window owner, List<ConnectionSession> sessions) {
        List<TableRef> tables = collectTables(sessions);
        if (tables.size() < 1) {
            return Optional.empty();
        }
        CompareStructureDialog dialog = new CompareStructureDialog(owner, tables);
        return dialog.showAndWait().filter(sql -> sql != null && !sql.isBlank());
    }
}
