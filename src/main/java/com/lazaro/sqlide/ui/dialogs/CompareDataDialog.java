package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.diff.DataCompareService;
import com.lazaro.sqlide.core.diff.DataCompareService.DataDiff;
import com.lazaro.sqlide.core.diff.DataCompareService.RowDiff;
import com.lazaro.sqlide.core.diff.DataCompareService.RowStatus;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Compares two query result sets by key columns.
 */
public final class CompareDataDialog extends Dialog<Void> {

    public record NamedResult(String name, QueryResult result) {
        @Override
        public String toString() {
            return name;
        }
    }

    private final ComboBox<NamedResult> leftBox = new ComboBox<>();
    private final ComboBox<NamedResult> rightBox = new ComboBox<>();
    private final TextField keyField = new TextField();
    private final Label summary = new Label();
    private final TableView<Map<String, String>> grid = new TableView<>();

    public CompareDataDialog(Window owner, List<NamedResult> results) {
        setTitle("Compare Data");
        setHeaderText("Diff two result sets by key columns");
        if (owner != null) {
            initOwner(owner);
        }
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);

        List<NamedResult> items = results == null ? List.of() : List.copyOf(results);
        leftBox.setItems(FXCollections.observableArrayList(items));
        rightBox.setItems(FXCollections.observableArrayList(items));
        if (items.size() >= 1) {
            leftBox.getSelectionModel().selectFirst();
        }
        if (items.size() >= 2) {
            rightBox.getSelectionModel().select(1);
        }
        keyField.setPromptText("id, code  (comma-separated; blank = first column)");

        javafx.scene.control.Button compare = new javafx.scene.control.Button("Compare");
        compare.getStyleClass().add(Styles.ACCENT);
        compare.setOnAction(e -> runCompare());

        GridPane pickers = new GridPane();
        pickers.setHgap(12);
        pickers.setVgap(8);
        pickers.add(new Label("Left"), 0, 0);
        pickers.add(leftBox, 1, 0);
        pickers.add(new Label("Right"), 0, 1);
        pickers.add(rightBox, 1, 1);
        pickers.add(new Label("Keys"), 0, 2);
        pickers.add(keyField, 1, 2);
        pickers.add(compare, 1, 3);
        GridPane.setHgrow(leftBox, Priority.ALWAYS);
        GridPane.setHgrow(rightBox, Priority.ALWAYS);
        GridPane.setHgrow(keyField, Priority.ALWAYS);

        summary.getStyleClass().add("import-wizard-hint");
        VBox.setVgrow(grid, Priority.ALWAYS);

        VBox root = new VBox(12, pickers, summary, grid);
        root.setPadding(new Insets(8));
        getDialogPane().setContent(root);
        getDialogPane().setPrefSize(860, 560);
        getDialogPane().getStyleClass().add("compare-data-dialog");

        setResultConverter(b -> null);
        if (items.size() >= 2) {
            runCompare();
        }
    }

    private void runCompare() {
        NamedResult left = leftBox.getValue();
        NamedResult right = rightBox.getValue();
        if (left == null || right == null) {
            summary.setText("Select two results");
            return;
        }
        List<String> keys = parseKeys(keyField.getText());
        DataDiff diff = DataCompareService.compare(left.result(), right.result(), keys);
        summary.setText("Match %d · Changed %d · Left-only %d · Right-only %d".formatted(
                diff.matchCount(), diff.changedCount(), diff.leftOnlyCount(), diff.rightOnlyCount()));

        grid.getColumns().clear();
        TableColumn<Map<String, String>, String> statusCol = new TableColumn<>("Status");
        statusCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().get("Status")));
        statusCol.setPrefWidth(100);
        grid.getColumns().add(statusCol);
        TableColumn<Map<String, String>, String> keyCol = new TableColumn<>("Key");
        keyCol.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().get("Key")));
        keyCol.setPrefWidth(120);
        grid.getColumns().add(keyCol);
        for (String col : diff.columns()) {
            TableColumn<Map<String, String>, String> tc = new TableColumn<>(col);
            tc.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getOrDefault(col, "")));
            tc.setPrefWidth(110);
            grid.getColumns().add(tc);
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (RowDiff row : diff.rows()) {
            if (row.status() == RowStatus.MATCH) {
                continue;
            }
            Map<String, String> map = new LinkedHashMap<>();
            map.put("Status", row.status().name());
            map.put("Key", row.key());
            List<String> values = row.leftValues() != null ? row.leftValues() : row.rightValues();
            List<String> other = row.rightValues() != null ? row.rightValues() : row.leftValues();
            for (int i = 0; i < diff.columns().size(); i++) {
                String col = diff.columns().get(i);
                String a = values != null && i < values.size() ? Objects.toString(values.get(i), "NULL") : "";
                String b = other != null && i < other.size() ? Objects.toString(other.get(i), "NULL") : "";
                if (row.status() == RowStatus.CHANGED && !Objects.equals(a, b)) {
                    map.put(col, a + " \u2192 " + b);
                } else if (row.status() == RowStatus.LEFT_ONLY) {
                    map.put(col, a);
                } else {
                    map.put(col, b);
                }
            }
            rows.add(map);
        }
        grid.setItems(FXCollections.observableArrayList(rows));
    }

    private static List<String> parseKeys(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String part : text.split(",")) {
            String trimmed = part.strip();
            if (!trimmed.isEmpty()) {
                keys.add(trimmed);
            }
        }
        return keys;
    }
}
