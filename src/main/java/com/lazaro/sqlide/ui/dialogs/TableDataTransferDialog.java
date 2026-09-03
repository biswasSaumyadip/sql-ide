package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.config.ConnectionProfile;
import com.lazaro.sqlide.core.config.ConnectionProfileManager;
import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.JdbcMetadataLayout;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.transfer.TransferJdbc;
import com.lazaro.sqlide.core.transfer.TransferRequest;
import com.lazaro.sqlide.core.transfer.TransferRequest.ErrorHandling;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.PasswordField;
import javafx.scene.control.RadioButton;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Wizard for exporting / transferring one table into another (same or cross connection).
 */
public final class TableDataTransferDialog extends Dialog<TransferRequest> {

    private static final String CURRENT_SESSION = "Current session";
    private static final String CREATE_NEW_TABLE_OPTION = "<Create New Table...>";

    private final ConnectionConfig sourceConfig;
    private final String sourceCatalog;
    private final String sourceTable;
    private final List<String> sourceColumns;
    private final long sourceRowCount;
    private final ConnectionProfileManager profileManager;
    private final ExecutorService metaExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "transfer-meta");
        t.setDaemon(true);
        return t;
    });

    private final Label sourceConnectionLabel = new Label();
    private final Label sourceSchemaLabel = new Label();
    private final Label sourceTableLabel = new Label();
    private final Label sourceRowsLabel = new Label();

    private final ComboBox<String> targetConnectionBox = new ComboBox<>();
    private final PasswordField targetPassword = new PasswordField();
    private final ComboBox<String> targetSchemaBox = new ComboBox<>();
    private final ComboBox<String> targetTableBox = new ComboBox<>();
    private final GridPane mappingGrid = new GridPane();
    private final List<ComboBox<String>> mappingBoxes = new ArrayList<>();
    private final CheckBox truncateCheck = new CheckBox("Truncate target table before transfer");
    private final CheckBox createIfMissingCheck = new CheckBox("Create target table if missing");
    private final RadioButton abortRadio = new RadioButton("On Error: Abort Transfer");
    private final RadioButton skipRadio = new RadioButton("On Error: Skip & Log Error");
    private final Spinner<Integer> batchSizeSpinner = new Spinner<>();
    private final Label feedback = new Label();
    private final Label duplicateTargetWarning = new Label("Source and Target destinations cannot be identical.");
    private final Label strategyHint = new Label();

    private final Map<String, ConnectionProfile> profilesByLabel = new LinkedHashMap<>();
    private List<String> targetColumns = List.of();
    private final JdbcMetadataLayout targetLayout = new JdbcMetadataLayout();
    private final DialogChrome chrome;
    private Button transferButton;

    public TableDataTransferDialog(
            Window owner,
            ConnectionConfig sourceConfig,
            SchemaNode sourceTableNode,
            List<String> sourceColumns,
            long sourceRowCount,
            ConnectionProfileManager profileManager) {
        this.sourceConfig = Objects.requireNonNull(sourceConfig, "sourceConfig");
        Objects.requireNonNull(sourceTableNode, "sourceTableNode");
        this.sourceCatalog = blankToNull(sourceTableNode.metadata(SchemaNode.META_CATALOG));
        this.sourceTable = sourceTableNode.name();
        this.sourceColumns = List.copyOf(sourceColumns == null ? List.of() : sourceColumns);
        this.sourceRowCount = Math.max(0, sourceRowCount);
        this.profileManager = profileManager == null ? new ConnectionProfileManager() : profileManager;
        this.chrome = new DialogChrome(this, 760, 640);

        setTitle("Export / Transfer to Table");
        initStyle(StageStyle.UNDECORATED);
        setResizable(true);
        if (owner != null) {
            initOwner(owner);
        }

        getDialogPane().getButtonTypes().clear();
        getDialogPane().setContent(buildContent());
        getDialogPane().getStyleClass().add("transfer-data-dialog");
        getDialogPane().setPrefSize(900, 700);
        getDialogPane().setMinSize(760, 640);

        populateSourcePanel();
        populateConnections();
        wireTargetSelectors();
        updateStrategyHint();

        setOnShown(event -> chrome.installResize());
        setOnHidden(event -> metaExecutor.shutdownNow());
    }

    private BorderPane buildContent() {
        feedback.getStyleClass().add("dialog-feedback");
        feedback.setWrapText(true);
        duplicateTargetWarning.getStyleClass().add("dialog-feedback");
        duplicateTargetWarning.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), true);
        duplicateTargetWarning.setVisible(false);
        duplicateTargetWarning.setManaged(false);
        duplicateTargetWarning.setWrapText(true);

        VBox content = new VBox(14,
                section("Source"),
                sourceGrid(),
                section("Target Destination"),
                targetGrid(),
                section("Column Mapping"),
                mappingGrid,
                section("Execution Options"),
                optionsBox(),
                strategyHint,
                feedback);
        content.setPadding(new Insets(12));
        content.getStyleClass().add("transfer-wizard-root");
        VBox.setVgrow(mappingGrid, Priority.ALWAYS);

        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background-color: transparent; -fx-background: transparent;");
        content.setPadding(new Insets(12, 20, 12, 12));

        HBox header = chrome.titleBar("Export / Transfer to Table");

        transferButton = new Button("Transfer");
        transferButton.getStyleClass().addAll(Styles.FLAT, "import-wizard-button");
        transferButton.setOnAction(event -> {
            Optional<String> error = validate();
            if (error.isPresent()) {
                showError(error.get());
                return;
            }
            setResult(buildRequest());
            close();
        });

        Button cancelButton = new Button("Cancel");
        cancelButton.setOnAction(event -> {
            setResult(null);
            ((javafx.stage.Stage) cancelButton.getScene().getWindow()).close();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footerButtons = new HBox(10, spacer, transferButton, cancelButton);
        footerButtons.setAlignment(Pos.CENTER_RIGHT);
        footerButtons.setPadding(new Insets(10, 12, 12, 12));

        Separator separator = new Separator();
        VBox footer = new VBox(separator, duplicateTargetWarning, footerButtons);

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(scrollPane);
        root.setBottom(footer);
        return root;
    }

    private GridPane sourceGrid() {
        GridPane grid = labeledGrid();
        grid.add(muted("Connection"), 0, 0);
        grid.add(sourceConnectionLabel, 1, 0);
        grid.add(muted("Schema"), 0, 1);
        grid.add(sourceSchemaLabel, 1, 1);
        grid.add(muted("Table"), 0, 2);
        grid.add(sourceTableLabel, 1, 2);
        grid.add(muted("Rows"), 0, 3);
        grid.add(sourceRowsLabel, 1, 3);
        sourceConnectionLabel.getStyleClass().add("transfer-readonly");
        sourceSchemaLabel.getStyleClass().add("transfer-readonly");
        sourceTableLabel.getStyleClass().add("transfer-readonly");
        sourceRowsLabel.getStyleClass().add("transfer-readonly");
        return grid;
    }

    private GridPane targetGrid() {
        GridPane grid = labeledGrid();
        targetConnectionBox.setMaxWidth(Double.MAX_VALUE);
        targetSchemaBox.setMaxWidth(Double.MAX_VALUE);
        targetTableBox.setMaxWidth(Double.MAX_VALUE);
        targetPassword.setPromptText("Password (required for other connections)");
        targetPassword.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(targetConnectionBox, Priority.ALWAYS);
        grid.add(muted("Connection"), 0, 0);
        grid.add(targetConnectionBox, 1, 0);
        grid.add(muted("Password"), 0, 1);
        grid.add(targetPassword, 1, 1);
        grid.add(muted("Schema"), 0, 2);
        grid.add(targetSchemaBox, 1, 2);
        grid.add(muted("Table"), 0, 3);
        grid.add(targetTableBox, 1, 3);
        return grid;
    }

    private VBox optionsBox() {
        ToggleGroup errors = new ToggleGroup();
        abortRadio.setToggleGroup(errors);
        skipRadio.setToggleGroup(errors);
        abortRadio.setSelected(true);

        batchSizeSpinner.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(1, 100_000, 1000, 100));
        batchSizeSpinner.setEditable(true);
        batchSizeSpinner.setPrefWidth(120);

        HBox batchRow = new HBox(10, muted("Batch size"), batchSizeSpinner);
        batchRow.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, truncateCheck, createIfMissingCheck, abortRadio, skipRadio, batchRow);
        return box;
    }

    private void populateSourcePanel() {
        sourceConnectionLabel.setText(sourceConfig.displayLabel());
        sourceSchemaLabel.setText(sourceCatalog == null ? "(default)" : sourceCatalog);
        sourceTableLabel.setText(sourceTable);
        sourceRowsLabel.setText(sourceRowCount > 0 ? "%,d".formatted(sourceRowCount) : "unknown");
    }

    private void populateConnections() {
        targetConnectionBox.getItems().add(CURRENT_SESSION);
        for (ConnectionProfile profile : profileManager.loadProfiles()) {
            String label = profile.displayName();
            profilesByLabel.put(label, profile);
            targetConnectionBox.getItems().add(label);
        }
        targetConnectionBox.getSelectionModel().select(CURRENT_SESSION);
        targetPassword.setDisable(true);
    }

    private void wireTargetSelectors() {
        targetConnectionBox.valueProperty().addListener((o, p, n) -> {
            boolean current = CURRENT_SESSION.equals(n);
            targetPassword.setDisable(current);
            if (current) {
                targetPassword.clear();
            }
            targetLayout.clear();
            reloadSchemas();
            updateStrategyHint();
        });
        targetSchemaBox.valueProperty().addListener((o, p, n) -> reloadTables());
        targetSchemaBox.valueProperty().addListener((o, p, n) -> updateDuplicateDestinationWarning());
        targetTableBox.valueProperty().addListener((o, p, n) -> {
            if (CREATE_NEW_TABLE_OPTION.equals(n)) {
                promptForNewTargetTable();
                return;
            }
            reloadTargetColumns();
            updateDuplicateDestinationWarning();
        });
        reloadSchemas();
    }

    private void reloadSchemas() {
        targetSchemaBox.getItems().clear();
        targetTableBox.getItems().clear();
        targetColumns = List.of();
        rebuildMapping(List.of());
        ConnectionConfig config = resolveTargetConfigOrNull();
        if (config == null) {
            return;
        }
        metaExecutor.execute(() -> {
            try (Connection connection = TransferJdbc.open(config)) {
                List<String> catalogs = TransferJdbc.listCatalogs(connection, targetLayout).stream()
                        .filter(name -> !TransferJdbc.looksLikeSystemCatalog(name))
                        .toList();
                Platform.runLater(() -> {
                    targetSchemaBox.getItems().setAll(catalogs);
                    String prefer = config.database();
                    if (prefer != null && !prefer.isBlank() && catalogs.stream().anyMatch(c -> c.equalsIgnoreCase(prefer))) {
                        targetSchemaBox.getSelectionModel().select(
                                catalogs.stream().filter(c -> c.equalsIgnoreCase(prefer)).findFirst().orElse(prefer));
                    } else if (sourceCatalog != null && catalogs.stream().anyMatch(c -> c.equalsIgnoreCase(sourceCatalog))) {
                        targetSchemaBox.getSelectionModel().select(
                                catalogs.stream().filter(c -> c.equalsIgnoreCase(sourceCatalog)).findFirst().orElse(null));
                    } else if (!catalogs.isEmpty()) {
                        targetSchemaBox.getSelectionModel().selectFirst();
                    }
                });
            } catch (Exception error) {
                Platform.runLater(() -> showError("Could not load schemas: " + error.getMessage()));
            }
        });
    }

    private void reloadTables() {
        targetTableBox.getItems().clear();
        String schema = targetSchemaBox.getValue();
        ConnectionConfig config = resolveTargetConfigOrNull();
        if (config == null || schema == null || schema.isBlank()) {
            return;
        }
        metaExecutor.execute(() -> {
            try (Connection connection = TransferJdbc.open(config)) {
                List<String> tables = TransferJdbc.listTables(connection, schema, targetLayout);
                Platform.runLater(() -> {
                    List<String> options = new ArrayList<>();
                    options.add(CREATE_NEW_TABLE_OPTION);
                    options.addAll(tables);
                    targetTableBox.getItems().setAll(options);
                    if (tables.stream().anyMatch(t -> t.equalsIgnoreCase(sourceTable))) {
                        targetTableBox.getSelectionModel().select(
                                tables.stream().filter(t -> t.equalsIgnoreCase(sourceTable)).findFirst().orElse(null));
                    } else if (!tables.isEmpty()) {
                        targetTableBox.getSelectionModel().selectFirst();
                    }
                });
            } catch (Exception error) {
                Platform.runLater(() -> showError("Could not load tables: " + error.getMessage()));
            }
        });
    }

    private void reloadTargetColumns() {
        String schema = targetSchemaBox.getValue();
        String table = targetTableBox.getValue();
        ConnectionConfig config = resolveTargetConfigOrNull();
        if (config == null || table == null || table.isBlank()) {
            targetColumns = List.of();
            rebuildMapping(List.of());
            return;
        }
        metaExecutor.execute(() -> {
            try (Connection connection = TransferJdbc.open(config)) {
                List<String> columns = TransferJdbc.listColumns(connection, schema, table, targetLayout);
                Platform.runLater(() -> {
                    targetColumns = columns;
                    rebuildMapping(columns);
                    clearFeedback();
                });
            } catch (Exception error) {
                Platform.runLater(() -> showError("Could not load columns: " + error.getMessage()));
            }
        });
    }

    private void rebuildMapping(List<String> targets) {
        mappingBoxes.clear();
        mappingGrid.getChildren().clear();
        mappingGrid.setHgap(12);
        mappingGrid.setVgap(12);
        mappingGrid.add(muted("Source Column"), 0, 0);
        mappingGrid.add(muted("Target Column"), 1, 0);

        List<String> choices = new ArrayList<>();
        choices.add("(skip)");
        choices.addAll(targets);

        for (int i = 0; i < sourceColumns.size(); i++) {
            String sourceCol = sourceColumns.get(i);
            Label source = new Label(sourceCol);
            source.getStyleClass().add("import-mapping-source");
            source.setMaxHeight(Double.MAX_VALUE);
            source.setAlignment(Pos.CENTER_LEFT);
            ComboBox<String> box = new ComboBox<>(FXCollections.observableArrayList(choices));
            box.setMaxWidth(Double.MAX_VALUE);
            String match = targets.stream()
                    .filter(t -> t.equalsIgnoreCase(sourceCol))
                    .findFirst()
                    .orElse("(skip)");
            box.getSelectionModel().select(match);
            mappingBoxes.add(box);
            source.setStyle("-fx-padding: 6 8 6 8;");
            mappingGrid.add(source, 0, i + 1);
            mappingGrid.add(box, 1, i + 1);
            GridPane.setHgrow(box, Priority.ALWAYS);
            GridPane.setValignment(source, javafx.geometry.VPos.CENTER);
            GridPane.setValignment(box, javafx.geometry.VPos.CENTER);
        }
        ColumnConstraints left = new ColumnConstraints();
        left.setPercentWidth(50);
        ColumnConstraints right = new ColumnConstraints();
        right.setPercentWidth(50);
        mappingGrid.getColumnConstraints().setAll(left, right);
    }

    private void updateStrategyHint() {
        ConnectionConfig target = resolveTargetConfigOrNull();
        if (target == null) {
            strategyHint.setText("");
            return;
        }
        if (TransferRequest.sameServer(sourceConfig, target)) {
            strategyHint.setText("Strategy: same connection — server-side INSERT…SELECT");
        } else {
            strategyHint.setText("Strategy: cross-connection — streaming SELECT → batch INSERT");
        }
        strategyHint.getStyleClass().add("import-wizard-hint");
    }

    private Optional<String> validate() {
        if (resolveTargetConfigOrNull() == null) {
            if (!CURRENT_SESSION.equals(targetConnectionBox.getValue())
                    && (targetPassword.getText() == null || targetPassword.getText().isEmpty())) {
                return Optional.of("Enter the password for the target connection.");
            }
            return Optional.of("Select a valid target connection.");
        }
        if (targetSchemaBox.getValue() == null || targetSchemaBox.getValue().isBlank()) {
            return Optional.of("Select a target schema.");
        }
        if (targetTableBox.getValue() == null || targetTableBox.getValue().isBlank()) {
            return Optional.of("Select a target table.");
        }
        if (!createIfMissingCheck.isSelected()
                && targetColumns.isEmpty()
                && targetTableBox.getItems().stream().noneMatch(t -> t.equalsIgnoreCase(targetTableBox.getValue()))) {
            return Optional.of("Target table does not exist. Enable \"Create target table if missing\" to create it.");
        }
        if (collectMapping().isEmpty()) {
            return Optional.of("Map at least one source column to a target column.");
        }
        String targetTable = targetTableBox.getValue();
        String targetSchema = targetSchemaBox.getValue();
        if (sourceTable.equalsIgnoreCase(targetTable)
                && Objects.equals(
                nullToEmpty(sourceCatalog).toLowerCase(Locale.ROOT),
                nullToEmpty(targetSchema).toLowerCase(Locale.ROOT))
                && TransferRequest.sameServer(sourceConfig, resolveTargetConfigOrNull())) {
            return Optional.of("Source and target table are the same. Choose a different target.");
        }
        return Optional.empty();
    }

    private TransferRequest buildRequest() {
        ConnectionConfig targetConfig = Objects.requireNonNull(resolveTargetConfigOrNull());
        return new TransferRequest(
                sourceConfig,
                sourceCatalog,
                sourceTable,
                sourceColumns,
                targetConfig,
                targetSchemaBox.getValue(),
                targetTableBox.getValue(),
                collectMapping(),
                truncateCheck.isSelected(),
                createIfMissingCheck.isSelected(),
                skipRadio.isSelected() ? ErrorHandling.SKIP : ErrorHandling.ABORT,
                batchSizeSpinner.getValue() == null ? 1000 : batchSizeSpinner.getValue(),
                sourceRowCount);
    }

    private Map<String, String> collectMapping() {
        Map<String, String> mapping = new LinkedHashMap<>();
        for (int i = 0; i < sourceColumns.size() && i < mappingBoxes.size(); i++) {
            String target = mappingBoxes.get(i).getSelectionModel().getSelectedItem();
            if (target != null && !target.equals("(skip)") && !target.isBlank()) {
                mapping.put(sourceColumns.get(i), target);
            }
        }
        return mapping;
    }

    private ConnectionConfig resolveTargetConfigOrNull() {
        String selected = targetConnectionBox.getValue();
        if (selected == null || CURRENT_SESSION.equals(selected)) {
            return sourceConfig;
        }
        ConnectionProfile profile = profilesByLabel.get(selected);
        if (profile == null) {
            return null;
        }
        ConnectionConfig.Driver driver;
        try {
            driver = ConnectionConfig.Driver.valueOf(profile.driver());
        } catch (Exception ex) {
            driver = ConnectionConfig.Driver.MYSQL;
        }
        String password = targetPassword.getText();
        if (password == null) {
            password = "";
        }
        // Same host/user as source — reuse source password when field left blank.
        if (password.isEmpty()
                && profile.host().equalsIgnoreCase(sourceConfig.host())
                && profile.port() == sourceConfig.port()
                && profile.username().equals(sourceConfig.user())) {
            password = sourceConfig.password();
        }
        if (password.isEmpty() && !CURRENT_SESSION.equals(selected)) {
            return null;
        }
        return new ConnectionConfig(
                profile.host(),
                profile.port(),
                profile.database(),
                profile.username(),
                password,
                driver);
    }

    private void showError(String message) {
        feedback.setText(message == null ? "" : message);
        feedback.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), true);
    }

    private void clearFeedback() {
        feedback.setText("");
        feedback.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("error"), false);
    }

    private void updateDuplicateDestinationWarning() {
        boolean duplicate = isDuplicateDestination();
        duplicateTargetWarning.setVisible(duplicate);
        duplicateTargetWarning.setManaged(duplicate);
        if (transferButton != null) {
            transferButton.setDisable(duplicate);
        }
    }

    private boolean isDuplicateDestination() {
        String targetSchema = targetSchemaBox.getValue();
        String targetTable = targetTableBox.getValue();
        if (targetTable == null || targetTable.isBlank() || CREATE_NEW_TABLE_OPTION.equals(targetTable)) {
            return false;
        }
        return sourceTable.equalsIgnoreCase(targetTable)
                && Objects.equals(
                nullToEmpty(sourceCatalog).toLowerCase(Locale.ROOT),
                nullToEmpty(targetSchema).toLowerCase(Locale.ROOT));
    }

    private void promptForNewTargetTable() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.setTitle("Create Target Table");
        dialog.setHeaderText("Create New Target Table");
        dialog.setContentText("Table name:");
        Optional<String> input = dialog.showAndWait()
                .map(String::trim)
                .filter(name -> !name.isBlank());
        if (input.isPresent()) {
            String newTable = input.get();
            if (targetTableBox.getItems().stream().noneMatch(item -> item.equalsIgnoreCase(newTable))) {
                targetTableBox.getItems().add(newTable);
            }
            targetTableBox.getSelectionModel().select(
                    targetTableBox.getItems().stream()
                            .filter(item -> item.equalsIgnoreCase(newTable))
                            .findFirst()
                            .orElse(newTable));
        } else {
            if (targetTableBox.getItems().size() > 1) {
                targetTableBox.getSelectionModel().select(1);
            } else {
                targetTableBox.getSelectionModel().clearSelection();
                targetColumns = List.of();
                rebuildMapping(List.of());
            }
        }
        updateDuplicateDestinationWarning();
    }

    private static GridPane labeledGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(10);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(100);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c0, c1);
        return grid;
    }

    private static Label section(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("import-wizard-title");
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("import-wizard-label");
        return label;
    }

    private static String qualify(String catalog, String table) {
        return catalog == null || catalog.isBlank() ? table : catalog + "." + table;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    /**
     * Counts rows for the source table off the FX thread (optional pre-load).
     */
    public static Task<Long> countRowsTask(ConnectionConfig config, String catalog, String table) {
        return new Task<>() {
            @Override
            protected Long call() throws Exception {
                try (Connection connection = TransferJdbc.open(config)) {
                    return TransferJdbc.countRows(connection, catalog, table, config.driver());
                }
            }
        };
    }
}
