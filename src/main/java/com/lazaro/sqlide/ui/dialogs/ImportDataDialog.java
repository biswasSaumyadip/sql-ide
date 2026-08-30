package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.SchemaNode.NodeType;
import com.lazaro.sqlide.core.importdata.ImportFileReader;
import com.lazaro.sqlide.core.importdata.ImportFormat;
import com.lazaro.sqlide.core.importdata.ImportPlan;
import com.lazaro.sqlide.core.importdata.ImportPlan.ErrorHandling;
import com.lazaro.sqlide.core.importdata.ImportPreview;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Multi-step Import Data wizard (source → preview/mapping → options).
 */
public final class ImportDataDialog extends Dialog<Void> {

    private static final ButtonType BACK = new ButtonType("Back", ButtonBar.ButtonData.BACK_PREVIOUS);
    private static final ButtonType NEXT = new ButtonType("Next", ButtonBar.ButtonData.NEXT_FORWARD);
    private static final ButtonType FINISH = new ButtonType("Finish", ButtonBar.ButtonData.FINISH);

    private final String targetTableQualified;
    private final List<String> targetColumns;

    private final Label step1Nav = navLabel("1. Source");
    private final Label step2Nav = navLabel("2. Mapping");
    private final Label step3Nav = navLabel("3. Options");

    private final StackPane contentHost = new StackPane();
    private final Label feedback = new Label();

    // Step 1
    private final TextField fileField = new TextField();
    private final Button browseButton = new Button("Browse\u2026");
    private final ComboBox<ImportFormat> formatBox = new ComboBox<>();
    private final CheckBox headerCheck = new CheckBox("First row is header");

    // Step 2
    private final TableView<ObservableList<String>> previewTable = new TableView<>();
    private final GridPane mappingGrid = new GridPane();
    private final List<ComboBox<String>> mappingBoxes = new ArrayList<>();
    private ImportPreview preview;

    // Step 3
    private final CheckBox truncateCheck = new CheckBox("Truncate table before import");
    private final Spinner<Integer> batchSizeSpinner = new Spinner<>();
    private final ComboBox<ErrorHandling> errorHandlingBox = new ComboBox<>();

    private int step = 1;
    private ImportPlan finishedPlan;

    public ImportDataDialog(Window owner, SchemaNode table, List<String> targetColumns) {
        Objects.requireNonNull(table, "table");
        this.targetTableQualified = table.qualifiedName();
        this.targetColumns = List.copyOf(targetColumns == null ? List.of() : targetColumns);

        setTitle("Import Data");
        setHeaderText("Import into " + targetTableQualified);
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        getDialogPane().getButtonTypes().setAll(BACK, NEXT, FINISH, ButtonType.CANCEL);
        getDialogPane().setContent(buildRoot());
        getDialogPane().getStyleClass().add("import-data-dialog");
        getDialogPane().setPrefSize(920, 620);

        wireButtons();
        wireStep1();
        showStep(1);
        setResultConverter(button -> null);
    }

    /** Plan produced when the user clicks Finish; empty if cancelled. */
    public Optional<ImportPlan> importPlan() {
        return Optional.ofNullable(finishedPlan);
    }

    private BorderPane buildRoot() {
        VBox nav = new VBox(6, step1Nav, step2Nav, step3Nav);
        nav.getStyleClass().add("import-wizard-nav");
        nav.setPadding(new Insets(16, 12, 16, 12));
        nav.setPrefWidth(150);
        step1Nav.setOnMouseClicked(event -> {
            if (step >= 1) {
                showStep(1);
            }
        });
        step2Nav.setOnMouseClicked(event -> {
            if (step >= 2 || canEnterStep2()) {
                if (ensurePreviewLoaded()) {
                    showStep(2);
                }
            }
        });
        step3Nav.setOnMouseClicked(event -> {
            if (step >= 3 || (ensurePreviewLoaded() && mappingsValid(false))) {
                showStep(3);
            }
        });

        feedback.getStyleClass().add("dialog-feedback");
        feedback.setWrapText(true);
        feedback.setMaxWidth(Double.MAX_VALUE);

        VBox right = new VBox(10, contentHost, feedback);
        right.setPadding(new Insets(16));
        VBox.setVgrow(contentHost, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);

        contentHost.getChildren().setAll(buildStep1(), buildStep2(), buildStep3());

        BorderPane root = new BorderPane();
        root.setLeft(nav);
        root.setCenter(right);
        root.getStyleClass().add("import-wizard-root");
        return root;
    }

    private Node buildStep1() {
        fileField.setPromptText("Select a CSV, TSV, JSON, or Excel file\u2026");
        fileField.setEditable(false);
        HBox.setHgrow(fileField, Priority.ALWAYS);

        browseButton.getStyleClass().addAll(Styles.FLAT, "import-wizard-button");
        HBox fileRow = new HBox(8, fileField, browseButton);
        fileRow.setAlignment(Pos.CENTER_LEFT);

        formatBox.getItems().setAll(ImportFormat.values());
        formatBox.getSelectionModel().select(ImportFormat.AUTO);
        formatBox.setMaxWidth(220);

        headerCheck.setSelected(true);

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(12);
        form.add(label("File"), 0, 0);
        form.add(fileRow, 1, 0);
        form.add(label("Format"), 0, 1);
        form.add(formatBox, 1, 1);
        form.add(headerCheck, 1, 2);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(80);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        form.getColumnConstraints().addAll(c0, c1);

        Label hint = new Label("Choose a source file. Format is auto-detected from the extension.");
        hint.getStyleClass().add("import-wizard-hint");

        VBox box = new VBox(16, sectionTitle("Source Selection"), hint, form);
        box.getStyleClass().add("import-wizard-step");
        return box;
    }

    private Node buildStep2() {
        previewTable.getStyleClass().add("result-table");
        previewTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        previewTable.setPlaceholder(new Label("Load a file in Step 1 to preview rows."));

        mappingGrid.setHgap(12);
        mappingGrid.setVgap(8);
        mappingGrid.setPadding(new Insets(8, 0, 0, 0));
        Label mapHint = new Label("Map each source column to a target database column.");
        mapHint.getStyleClass().add("import-wizard-hint");

        VBox mappingBox = new VBox(8, sectionTitle("Column Mapping"), mapHint, mappingGrid);
        mappingBox.setPadding(new Insets(4, 8, 8, 8));

        SplitPane split = new SplitPane(previewTable, mappingBox);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.55);
        VBox.setVgrow(split, Priority.ALWAYS);

        VBox box = new VBox(10, sectionTitle("Data Preview & Mapping"), split);
        box.getStyleClass().add("import-wizard-step");
        VBox.setVgrow(box, Priority.ALWAYS);
        return box;
    }

    private Node buildStep3() {
        truncateCheck.setSelected(false);

        batchSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100_000, 1000, 100));
        batchSizeSpinner.setEditable(true);
        batchSizeSpinner.setPrefWidth(120);
        batchSizeSpinner.setMaxWidth(140);

        errorHandlingBox.getItems().setAll(ErrorHandling.values());
        errorHandlingBox.getSelectionModel().select(ErrorHandling.ABORT);
        errorHandlingBox.setMaxWidth(260);

        ToggleGroup unused = new ToggleGroup(); // reserved for future insert/update modes
        RadioButton insertOnly = new RadioButton("Insert rows");
        insertOnly.setSelected(true);
        insertOnly.setToggleGroup(unused);
        insertOnly.setDisable(true);
        insertOnly.setTooltip(new Tooltip("Update/upsert modes will arrive in a later release"));

        GridPane form = new GridPane();
        form.setHgap(12);
        form.setVgap(14);
        form.add(truncateCheck, 0, 0, 2, 1);
        form.add(label("Batch size"), 0, 1);
        form.add(batchSizeSpinner, 1, 1);
        form.add(label("Error handling"), 0, 2);
        form.add(errorHandlingBox, 1, 2);
        form.add(insertOnly, 0, 3, 2, 1);

        Label hint = new Label("Review options, then click Finish to confirm the import plan.");
        hint.getStyleClass().add("import-wizard-hint");

        VBox box = new VBox(16, sectionTitle("Import Options"), hint, form);
        box.getStyleClass().add("import-wizard-step");
        return box;
    }

    private void wireButtons() {
        Button back = (Button) getDialogPane().lookupButton(BACK);
        Button next = (Button) getDialogPane().lookupButton(NEXT);
        Button finish = (Button) getDialogPane().lookupButton(FINISH);
        for (Button button : List.of(back, next, finish)) {
            button.getStyleClass().addAll(Styles.FLAT, "import-wizard-button");
        }

        back.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (step > 1) {
                showStep(step - 1);
            }
        });
        next.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            if (step == 1) {
                if (ensurePreviewLoaded()) {
                    showStep(2);
                }
            } else if (step == 2) {
                if (mappingsValid(true)) {
                    showStep(3);
                }
            }
        });
        finish.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            if (!ensurePreviewLoaded() || !mappingsValid(true)) {
                event.consume();
                showStep(2);
                return;
            }
            finishedPlan = buildPlan();
            clearFeedback();
        });
    }

    private void wireStep1() {
        browseButton.setOnAction(event -> browse());
        fileField.textProperty().addListener((observable, previous, next) -> {
            preview = null;
            Path path = currentPath();
            if (path != null && formatBox.getValue() == ImportFormat.AUTO) {
                // Keep AUTO selected; detection happens on load.
            }
            autoDetectFormatLabel();
        });
        formatBox.valueProperty().addListener((observable, previous, next) -> preview = null);
        headerCheck.selectedProperty().addListener((observable, previous, next) -> preview = null);
    }

    private void browse() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Select import file");
        chooser.getExtensionFilters().setAll(
                new FileChooser.ExtensionFilter("Import files", "*.csv", "*.tsv", "*.json", "*.xlsx"),
                new FileChooser.ExtensionFilter("CSV", "*.csv"),
                new FileChooser.ExtensionFilter("TSV", "*.tsv"),
                new FileChooser.ExtensionFilter("JSON", "*.json"),
                new FileChooser.ExtensionFilter("Excel", "*.xlsx"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        var file = chooser.showOpenDialog(getDialogPane().getScene() == null
                ? null
                : getDialogPane().getScene().getWindow());
        if (file != null) {
            fileField.setText(file.getAbsolutePath());
            if (formatBox.getValue() == ImportFormat.AUTO || formatBox.getValue() == null) {
                formatBox.getSelectionModel().select(ImportFormat.AUTO);
            }
            autoDetectFormatLabel();
            clearFeedback();
        }
    }

    private void autoDetectFormatLabel() {
        Path path = currentPath();
        if (path == null) {
            return;
        }
        ImportFormat detected = ImportFormat.fromPath(path);
        if (detected != ImportFormat.AUTO && formatBox.getValue() == ImportFormat.AUTO) {
            formatBox.setTooltip(new Tooltip("Detected: " + detected.label()));
        }
    }

    private boolean canEnterStep2() {
        return currentPath() != null;
    }

    private boolean ensurePreviewLoaded() {
        Path path = currentPath();
        if (path == null) {
            showError("Select a source file first.");
            showStep(1);
            return false;
        }
        if (preview != null && path.equals(preview.path())) {
            rebuildPreviewUi();
            return true;
        }
        try {
            ImportFormat format = formatBox.getValue() == null ? ImportFormat.AUTO : formatBox.getValue();
            preview = ImportFileReader.readPreview(path, format, headerCheck.isSelected());
            rebuildPreviewUi();
            clearFeedback();
            return true;
        } catch (Exception error) {
            showError(error.getMessage());
            showStep(1);
            return false;
        }
    }

    private void rebuildPreviewUi() {
        previewTable.getColumns().clear();
        previewTable.getItems().clear();
        mappingBoxes.clear();
        mappingGrid.getChildren().clear();

        if (preview == null) {
            return;
        }

        List<String> sourceColumns = preview.columnNames();
        for (int i = 0; i < sourceColumns.size(); i++) {
            int columnIndex = i;
            TableColumn<ObservableList<String>, String> column = new TableColumn<>(sourceColumns.get(i));
            column.setPrefWidth(Math.clamp(sourceColumns.get(i).length() * 8.5 + 36, 80, 280));
            column.setCellValueFactory(features -> {
                ObservableList<String> row = features.getValue();
                String value = columnIndex < row.size() ? row.get(columnIndex) : null;
                return new SimpleStringProperty(value == null ? "NULL" : value);
            });
            previewTable.getColumns().add(column);
        }
        for (List<String> row : preview.rows()) {
            previewTable.getItems().add(FXCollections.observableArrayList(row));
        }

        mappingGrid.add(muted("Source File Column"), 0, 0);
        mappingGrid.add(muted("Target DB Column"), 1, 0);
        List<String> targets = new ArrayList<>();
        targets.add("(skip)");
        targets.addAll(targetColumns);

        for (int i = 0; i < sourceColumns.size(); i++) {
            Label source = new Label(sourceColumns.get(i));
            source.getStyleClass().add("import-mapping-source");
            ComboBox<String> target = new ComboBox<>(FXCollections.observableArrayList(targets));
            target.setMaxWidth(Double.MAX_VALUE);
            String suggested = suggestTarget(sourceColumns.get(i));
            target.getSelectionModel().select(suggested == null ? "(skip)" : suggested);
            mappingBoxes.add(target);
            mappingGrid.add(source, 0, i + 1);
            mappingGrid.add(target, 1, i + 1);
            GridPane.setHgrow(target, Priority.ALWAYS);
        }
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(45);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(55);
        mappingGrid.getColumnConstraints().setAll(left, right);
    }

    private String suggestTarget(String source) {
        if (source == null) {
            return null;
        }
        for (String target : targetColumns) {
            if (target.equalsIgnoreCase(source)) {
                return target;
            }
        }
        String normalized = source.replace(" ", "_").toLowerCase(Locale.ROOT);
        for (String target : targetColumns) {
            if (target.toLowerCase(Locale.ROOT).equals(normalized)) {
                return target;
            }
        }
        return null;
    }

    private boolean mappingsValid(boolean showError) {
        if (preview == null) {
            if (showError) {
                showError("Load a preview first.");
            }
            return false;
        }
        Map<Integer, String> mapping = collectMapping();
        if (mapping.isEmpty()) {
            if (showError) {
                showError("Map at least one source column to a target column.");
            }
            return false;
        }
        return true;
    }

    private Map<Integer, String> collectMapping() {
        Map<Integer, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < mappingBoxes.size(); i++) {
            String selected = mappingBoxes.get(i).getSelectionModel().getSelectedItem();
            if (selected != null && !selected.equals("(skip)") && !selected.isBlank()) {
                mapping.put(i, selected);
            }
        }
        return mapping;
    }

    private ImportPlan buildPlan() {
        ImportFormat format = preview.format();
        return new ImportPlan(
                preview.path(),
                format,
                headerCheck.isSelected(),
                targetTableQualified,
                targetColumns,
                collectMapping(),
                truncateCheck.isSelected(),
                batchSizeSpinner.getValue() == null ? 1000 : batchSizeSpinner.getValue(),
                errorHandlingBox.getValue() == null ? ErrorHandling.ABORT : errorHandlingBox.getValue(),
                preview);
    }

    private void showStep(int nextStep) {
        this.step = nextStep;
        List<Node> panes = contentHost.getChildren();
        for (int i = 0; i < panes.size(); i++) {
            boolean visible = (i + 1) == nextStep;
            panes.get(i).setVisible(visible);
            panes.get(i).setManaged(visible);
        }
        highlightNav();
        Button back = (Button) getDialogPane().lookupButton(BACK);
        Button next = (Button) getDialogPane().lookupButton(NEXT);
        Button finish = (Button) getDialogPane().lookupButton(FINISH);
        back.setDisable(nextStep <= 1);
        next.setDisable(nextStep >= 3);
        next.setVisible(nextStep < 3);
        next.setManaged(nextStep < 3);
        finish.setDisable(nextStep < 3);
        finish.setDefaultButton(nextStep == 3);
        next.setDefaultButton(nextStep < 3);
    }

    private void highlightNav() {
        for (Label nav : List.of(step1Nav, step2Nav, step3Nav)) {
            nav.getStyleClass().removeAll("import-wizard-nav-active", "import-wizard-nav-done");
        }
        if (step > 1) {
            step1Nav.getStyleClass().add("import-wizard-nav-done");
        }
        if (step > 2) {
            step2Nav.getStyleClass().add("import-wizard-nav-done");
        }
        Label active = switch (step) {
            case 2 -> step2Nav;
            case 3 -> step3Nav;
            default -> step1Nav;
        };
        active.getStyleClass().add("import-wizard-nav-active");
    }

    private Path currentPath() {
        String text = fileField.getText();
        if (text == null || text.isBlank()) {
            return null;
        }
        return Path.of(text.strip());
    }

    private void showError(String message) {
        feedback.setText(message == null ? "" : message);
        feedback.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), true);
    }

    private void clearFeedback() {
        feedback.setText("");
        feedback.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), false);
    }

    private static Label navLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("import-wizard-nav-item");
        label.setMaxWidth(Double.MAX_VALUE);
        return label;
    }

    private static Label sectionTitle(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("import-wizard-title");
        return label;
    }

    private static Label label(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("import-wizard-label");
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("import-wizard-hint");
        return label;
    }

    /** Collects COLUMN names under a table / columns folder. */
    public static List<String> columnsOf(SchemaNode table) {
        List<String> columns = new ArrayList<>();
        if (table == null) {
            return columns;
        }
        collectColumns(table, columns);
        return columns;
    }

    private static void collectColumns(SchemaNode node, List<String> columns) {
        if (node.type() == NodeType.COLUMN) {
            columns.add(node.name());
            return;
        }
        for (SchemaNode child : node.children()) {
            collectColumns(child, columns);
        }
    }
}
