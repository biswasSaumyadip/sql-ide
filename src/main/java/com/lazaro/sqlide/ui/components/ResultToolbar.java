package com.lazaro.sqlide.ui.components;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.ui.Icons;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.value.ChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.util.Duration;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Sleek DataGrip-style action bar above the result tabs.
 */
public final class ResultToolbar extends HBox {

    public enum AutoRefreshInterval {
        OFF("Off", 0),
        SEC_5("5s", 5),
        SEC_10("10s", 10),
        MIN_1("60s", 60);

        private final String label;
        private final int seconds;

        AutoRefreshInterval(String label, int seconds) {
            this.label = label;
            this.seconds = seconds;
        }

        public int seconds() {
            return seconds;
        }

        public String menuLabel() {
            return label;
        }
    }

    public enum MaxRowsOption {
        R_100(100),
        R_500(500),
        R_1000(1_000),
        R_5000(5_000),
        R_10000(10_000);

        private final int rows;

        MaxRowsOption(int rows) {
            this.rows = rows;
        }

        public int rows() {
            return rows;
        }

        public static MaxRowsOption closest(int value) {
            MaxRowsOption best = R_1000;
            int bestDelta = Integer.MAX_VALUE;
            for (MaxRowsOption option : values()) {
                int delta = Math.abs(option.rows - value);
                if (delta < bestDelta) {
                    bestDelta = delta;
                    best = option;
                }
            }
            return best;
        }
    }

    private final MenuButton exportButton = new MenuButton("Export", Icons.export());
    private final Button copyButton = new Button();
    private final ToggleButton findButton = new ToggleButton();
    private final TextField findField = new TextField();
    private final Button fitColumnsButton = new Button();
    private final ToggleButton viewToggle = new ToggleButton();
    private final ToggleButton pinButton = new ToggleButton();
    private final Button refreshButton = new Button();
    private final Button clearButton = new Button();
    private final Button addRowButton = new Button();
    private final Button deleteRowsButton = new Button();
    private final Button submitEditsButton = new Button();
    private final Button revertEditsButton = new Button();
    private final MenuButton autoRefreshButton = new MenuButton();
    private final CheckMenuItem stopOnErrorItem = new CheckMenuItem("Stop on error");
    private final MenuButton maxRowsButton = new MenuButton();
    private final Label summaryLabel = new Label();

    private final Timeline autoRefreshTimeline = new Timeline();
    private AutoRefreshInterval autoRefreshInterval = AutoRefreshInterval.OFF;
    private MaxRowsOption maxRowsOption = MaxRowsOption.R_1000;

    private Runnable onRefresh = () -> { };
    private Runnable onClear = () -> { };
    private Runnable onTogglePin = () -> { };
    private Runnable onToggleView = () -> { };
    private Runnable onAddRow = () -> { };
    private Runnable onDeleteRows = () -> { };
    private Runnable onSubmitEdits = () -> { };
    private Runnable onRevertEdits = () -> { };
    private Consumer<QueryResult> onExportToFile = result -> { };
    private IntConsumer onMaxRowsChanged = rows -> { };
    private Consumer<Boolean> onStopOnErrorChanged = stop -> { };
    private Supplier<DynamicResultTable> tableSupplier = () -> null;

    private DynamicResultTable boundTable;
    private BooleanBinding tableEmptyBinding;
    private ListChangeListener<ObservableList<String>> itemsListener;
    private ChangeListener<ObservableList<ObservableList<String>>> itemsPropertyListener;
    private Separator dataEditSeparator;
    private boolean dataEditMode;

    public ResultToolbar() {
        getStyleClass().add("result-toolbar");
        setAlignment(Pos.CENTER_LEFT);
        setSpacing(2);
        setPadding(new Insets(3, 8, 0, 8));

        buildExportMenu();
        exportButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-button", "export-menu-button");
        exportButton.setGraphicTextGap(4);
        exportButton.setTooltip(new Tooltip("Export results (Ctrl+Shift+C / Ctrl+Shift+X)"));

        copyButton.setGraphic(Icons.copy());
        copyButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        copyButton.setTooltip(new Tooltip("Copy as TSV (selection if any)"));
        copyButton.setOnAction(event -> copyAsTsv());

        findButton.setGraphic(Icons.find());
        findButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        findButton.setTooltip(new Tooltip("Find in results (Ctrl+F)"));
        findButton.setOnAction(event -> toggleFindBar(findButton.isSelected()));

        findField.getStyleClass().add("result-toolbar-find");
        findField.setPromptText("Find in results\u2026");
        findField.setPrefWidth(160);
        findField.setMaxWidth(220);
        findField.setVisible(false);
        findField.setManaged(false);
        findField.textProperty().addListener((observable, previous, next) -> applyFind(next));
        findField.setOnAction(event -> applyFind(findField.getText()));

        fitColumnsButton.setGraphic(Icons.fitColumns());
        fitColumnsButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        fitColumnsButton.setTooltip(new Tooltip("Fit column widths to content"));
        fitColumnsButton.setOnAction(event -> {
            DynamicResultTable table = tableSupplier.get();
            if (table != null) {
                table.fitColumnWidths();
            }
        });

        viewToggle.setGraphic(Icons.planTree());
        viewToggle.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        viewToggle.setTooltip(new Tooltip("Toggle grid / execution plan"));
        viewToggle.setOnAction(event -> onToggleView.run());

        pinButton.setGraphic(Icons.pin());
        pinButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        pinButton.setTooltip(new Tooltip("Pin current result tab"));
        pinButton.setOnAction(event -> onTogglePin.run());

        refreshButton.setGraphic(Icons.refresh());
        refreshButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        refreshButton.setTooltip(new Tooltip("Re-run last query now"));
        refreshButton.setOnAction(event -> onRefresh.run());

        clearButton.setGraphic(Icons.clear());
        clearButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        clearButton.setTooltip(new Tooltip("Clear unpinned result tabs"));
        clearButton.setOnAction(event -> onClear.run());

        addRowButton.setGraphic(Icons.newQuery());
        addRowButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        addRowButton.setTooltip(new Tooltip("Insert row"));
        addRowButton.setOnAction(event -> onAddRow.run());

        deleteRowsButton.setGraphic(Icons.clear());
        deleteRowsButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        deleteRowsButton.setTooltip(new Tooltip("Delete selected row(s)"));
        deleteRowsButton.setOnAction(event -> onDeleteRows.run());

        submitEditsButton.setGraphic(Icons.commit());
        submitEditsButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        submitEditsButton.setTooltip(new Tooltip("Submit pending INSERT / UPDATE / DELETE"));
        submitEditsButton.setOnAction(event -> onSubmitEdits.run());

        revertEditsButton.setGraphic(Icons.rollback());
        revertEditsButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-icon-button");
        revertEditsButton.setTooltip(new Tooltip("Revert unsaved edits"));
        revertEditsButton.setOnAction(event -> onRevertEdits.run());

        dataEditSeparator = subtleSeparator();
        setDataEditControlsVisible(false);

        buildAutoRefreshMenu();
        autoRefreshButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-button");
        autoRefreshButton.setTooltip(new Tooltip("Automatically re-run the last query on an interval"));
        updateAutoRefreshLabel();

        buildMaxRowsMenu();
        maxRowsButton.getStyleClass().addAll(Styles.FLAT, "result-toolbar-button");
        maxRowsButton.setTooltip(new Tooltip("Maximum rows fetched per query"));
        updateMaxRowsLabel();

        summaryLabel.getStyleClass().add("result-toolbar-summary");
        summaryLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(summaryLabel, Priority.ALWAYS);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        getChildren().addAll(
                exportButton,
                copyButton,
                findButton,
                findField,
                fitColumnsButton,
                viewToggle,
                subtleSeparator(),
                pinButton,
                refreshButton,
                clearButton,
                dataEditSeparator,
                addRowButton,
                deleteRowsButton,
                submitEditsButton,
                revertEditsButton,
                subtleSeparator(),
                autoRefreshButton,
                maxRowsButton,
                spacer,
                summaryLabel);

        autoRefreshTimeline.setCycleCount(Timeline.INDEFINITE);
        bindActiveTable(null, false);
        setViewToggleAvailable(false, false);
    }

    public void setTableSupplier(Supplier<DynamicResultTable> tableSupplier) {
        this.tableSupplier = tableSupplier == null ? () -> null : tableSupplier;
    }

    public void setOnExportToFile(Consumer<QueryResult> action) {
        this.onExportToFile = action == null ? result -> { } : action;
    }

    public void setOnRefresh(Runnable action) {
        this.onRefresh = action == null ? () -> { } : action;
    }

    public void setOnClear(Runnable action) {
        this.onClear = action == null ? () -> { } : action;
    }

    public void setOnTogglePin(Runnable action) {
        this.onTogglePin = action == null ? () -> { } : action;
    }

    public void setOnToggleView(Runnable action) {
        this.onToggleView = action == null ? () -> { } : action;
    }

    public void setOnAddRow(Runnable action) {
        this.onAddRow = action == null ? () -> { } : action;
    }

    public void setOnDeleteRows(Runnable action) {
        this.onDeleteRows = action == null ? () -> { } : action;
    }

    public void setOnSubmitEdits(Runnable action) {
        this.onSubmitEdits = action == null ? () -> { } : action;
    }

    public void setOnRevertEdits(Runnable action) {
        this.onRevertEdits = action == null ? () -> { } : action;
    }

    public void setOnMaxRowsChanged(IntConsumer action) {
        this.onMaxRowsChanged = action == null ? rows -> { } : action;
    }

    public void setOnStopOnErrorChanged(Consumer<Boolean> action) {
        this.onStopOnErrorChanged = action == null ? stop -> { } : action;
    }

    public void setStopAutoRefreshOnError(boolean stop) {
        stopOnErrorItem.setSelected(stop);
    }

    public boolean stopAutoRefreshOnError() {
        return stopOnErrorItem.isSelected();
    }

    public void setMaxRows(int rows) {
        maxRowsOption = MaxRowsOption.closest(rows);
        updateMaxRowsLabel();
    }

    public int maxRows() {
        return maxRowsOption.rows();
    }

    /**
     * Call after a query finishes. When auto-refresh is active and the run had an
     * error, turns auto-refresh off if {@link #stopAutoRefreshOnError()} is set.
     */
    public void notifyQueryFinished(boolean hadError) {
        if (hadError && stopOnErrorItem.isSelected() && autoRefreshInterval != AutoRefreshInterval.OFF) {
            stopAutoRefresh();
        }
    }

    public void setPinnedSelected(boolean pinned) {
        pinButton.setSelected(pinned);
    }

    public void setViewToggleAvailable(boolean available, boolean showingPlan) {
        viewToggle.setDisable(!available);
        viewToggle.setSelected(showingPlan);
        viewToggle.setGraphic(showingPlan ? Icons.grid() : Icons.planTree());
        viewToggle.setTooltip(new Tooltip(showingPlan
                ? "Show result grid"
                : "Show execution plan"));
    }

    /**
     * Shows INSERT / DELETE / Submit / Revert when the active tab is table data.
     */
    public void setDataEditMode(boolean dataTab, boolean editable, boolean dirty) {
        this.dataEditMode = dataTab;
        setDataEditControlsVisible(dataTab);
        addRowButton.setDisable(!editable);
        deleteRowsButton.setDisable(!editable);
        submitEditsButton.setDisable(!editable || !dirty);
        revertEditsButton.setDisable(!dirty);
        if (dataTab) {
            refreshButton.setDisable(false);
            refreshButton.setTooltip(new Tooltip("Reload table rows from the database"));
        } else {
            refreshButton.setTooltip(new Tooltip("Re-run last query now"));
        }
    }

    public void setSummary(String text) {
        summaryLabel.setText(Objects.requireNonNullElse(text, ""));
    }

    /**
     * Rebinds data-dependent controls to the active result grid. Call whenever the
     * selected result tab changes so empty/idle tabs disable Export / Find / etc.
     */
    public void bindActiveTable(DynamicResultTable table, boolean hasPage) {
        detachTableListeners();
        unbindDataActions();
        this.boundTable = table;

        pinButton.setDisable(!hasPage);
        clearButton.setDisable(!hasPage);

        if (table == null || !hasPage) {
            setDataActionsDisabled(true);
            closeFindIfNeeded();
            return;
        }

        bindEmptyState(table);
        attachItemsListeners(table);
    }

    /** Enables Clear independently of the active grid (e.g. while Output is focused). */
    public void setClearEnabled(boolean enabled) {
        clearButton.setDisable(!enabled);
    }

    public void setRefreshEnabled(boolean enabled) {
        if (dataEditMode) {
            refreshButton.setDisable(false);
        } else {
            refreshButton.setDisable(!enabled);
        }
        autoRefreshButton.setDisable(!enabled);
        if (!enabled && autoRefreshInterval != AutoRefreshInterval.OFF) {
            // Keep selection label but pause ticking while refresh is unavailable.
            autoRefreshTimeline.stop();
        } else if (enabled && autoRefreshInterval.seconds() > 0) {
            applyAutoRefresh(autoRefreshInterval);
        }
    }

    public AutoRefreshInterval autoRefreshInterval() {
        return autoRefreshInterval;
    }

    public void stopAutoRefresh() {
        autoRefreshInterval = AutoRefreshInterval.OFF;
        autoRefreshTimeline.stop();
        autoRefreshTimeline.getKeyFrames().clear();
        updateAutoRefreshLabel();
    }

    /** Opens the find field and focuses it (e.g. Ctrl+F). */
    public void focusFind() {
        if (findButton.isDisabled()) {
            return;
        }
        findButton.setSelected(true);
        toggleFindBar(true);
        findField.requestFocus();
        findField.selectAll();
    }

    /** Re-applies the find filter after switching result tabs. */
    public void reapplyFindIfOpen() {
        if (findField.isVisible()) {
            applyFind(findField.getText());
        }
    }

    public boolean copyAsTsv() {
        DynamicResultTable table = tableSupplier.get();
        return table != null && table.copyAsTsv();
    }

    public boolean copyAsCsv() {
        DynamicResultTable table = tableSupplier.get();
        return table != null && table.copyAsCsv();
    }

    private void bindEmptyState(DynamicResultTable table) {
        unbindDataActions();
        tableEmptyBinding = Bindings.createBooleanBinding(
                () -> isTableDataEmpty(table),
                table.itemsProperty(),
                table.getItems());
        exportButton.disableProperty().bind(tableEmptyBinding);
        copyButton.disableProperty().bind(tableEmptyBinding);
        findButton.disableProperty().bind(tableEmptyBinding);
        fitColumnsButton.disableProperty().bind(tableEmptyBinding);
        if (tableEmptyBinding.get()) {
            closeFindIfNeeded();
        }
        tableEmptyBinding.addListener((observable, wasEmpty, isEmpty) -> {
            if (Boolean.TRUE.equals(isEmpty)) {
                closeFindIfNeeded();
            }
        });
    }

    private void attachItemsListeners(DynamicResultTable table) {
        itemsListener = change -> {
            if (tableEmptyBinding != null) {
                tableEmptyBinding.invalidate();
            }
        };
        itemsPropertyListener = (observable, previous, next) -> {
            if (previous != null && itemsListener != null) {
                previous.removeListener(itemsListener);
            }
            if (next != null && itemsListener != null) {
                next.addListener(itemsListener);
            }
            // List instance swapped via setItems — rebuild the binding dependencies.
            bindEmptyState(table);
        };
        table.itemsProperty().addListener(itemsPropertyListener);
        ObservableList<ObservableList<String>> items = table.getItems();
        if (items != null) {
            items.addListener(itemsListener);
        }
    }

    private void detachTableListeners() {
        if (boundTable != null) {
            if (itemsPropertyListener != null) {
                boundTable.itemsProperty().removeListener(itemsPropertyListener);
            }
            if (itemsListener != null) {
                ObservableList<ObservableList<String>> items = boundTable.getItems();
                if (items != null) {
                    items.removeListener(itemsListener);
                }
            }
        }
        itemsListener = null;
        itemsPropertyListener = null;
        boundTable = null;
    }

    private void unbindDataActions() {
        exportButton.disableProperty().unbind();
        copyButton.disableProperty().unbind();
        findButton.disableProperty().unbind();
        fitColumnsButton.disableProperty().unbind();
        if (tableEmptyBinding != null) {
            tableEmptyBinding.dispose();
            tableEmptyBinding = null;
        }
    }

    private void setDataActionsDisabled(boolean disabled) {
        exportButton.setDisable(disabled);
        copyButton.setDisable(disabled);
        findButton.setDisable(disabled);
        fitColumnsButton.setDisable(disabled);
    }

    private void closeFindIfNeeded() {
        toggleFindBar(false);
        findButton.setSelected(false);
    }

    private static boolean isTableDataEmpty(DynamicResultTable table) {
        if (table == null || !table.hasExportableResult()) {
            return true;
        }
        ObservableList<?> items = table.getItems();
        return items == null || items.isEmpty();
    }

    private void toggleFindBar(boolean show) {
        findField.setVisible(show);
        findField.setManaged(show);
        if (show) {
            findField.requestFocus();
            applyFind(findField.getText());
        } else {
            findField.clear();
            applyFind("");
        }
    }

    private void applyFind(String query) {
        DynamicResultTable table = tableSupplier.get();
        if (table != null) {
            table.applyRowFilter(query);
        }
    }

    private void buildExportMenu() {
        MenuItem copyTsv = new MenuItem("Copy as TSV");
        copyTsv.setOnAction(event -> copyAsTsv());

        MenuItem copyCsv = new MenuItem("Copy as CSV");
        copyCsv.setOnAction(event -> copyAsCsv());

        MenuItem exportAll = new MenuItem("Export all to File\u2026");
        exportAll.setOnAction(event -> exportSlice(false));

        MenuItem exportSelection = new MenuItem("Export selection to File\u2026");
        exportSelection.setOnAction(event -> exportSlice(true));

        exportButton.getItems().setAll(copyTsv, copyCsv, new SeparatorMenuItem(), exportAll, exportSelection);
        exportButton.setOnShowing(event -> {
            DynamicResultTable table = tableSupplier.get();
            boolean ready = table != null && table.hasExportableResult() && !table.getItems().isEmpty();
            boolean selection = table != null && table.hasRowSelection();
            copyTsv.setDisable(!ready);
            copyCsv.setDisable(!ready);
            exportAll.setDisable(!ready);
            exportSelection.setDisable(!ready || !selection);
            copyTsv.setText(selection ? "Copy selection as TSV" : "Copy as TSV");
            copyCsv.setText(selection ? "Copy selection as CSV" : "Copy as CSV");
        });
    }

    private void buildAutoRefreshMenu() {
        for (AutoRefreshInterval interval : AutoRefreshInterval.values()) {
            MenuItem item = new MenuItem(interval == AutoRefreshInterval.OFF
                    ? "Off"
                    : interval.menuLabel());
            item.setOnAction(event -> {
                autoRefreshInterval = interval;
                updateAutoRefreshLabel();
                applyAutoRefresh(interval);
            });
            autoRefreshButton.getItems().add(item);
        }
        stopOnErrorItem.setSelected(true);
        stopOnErrorItem.setOnAction(event -> onStopOnErrorChanged.accept(stopOnErrorItem.isSelected()));
        autoRefreshButton.getItems().addAll(new SeparatorMenuItem(), stopOnErrorItem);
    }

    private void buildMaxRowsMenu() {
        for (MaxRowsOption option : MaxRowsOption.values()) {
            MenuItem item = new MenuItem(formatRows(option.rows()));
            item.setOnAction(event -> {
                maxRowsOption = option;
                updateMaxRowsLabel();
                onMaxRowsChanged.accept(option.rows());
            });
            maxRowsButton.getItems().add(item);
        }
    }

    private void updateAutoRefreshLabel() {
        autoRefreshButton.setText("Auto: " + autoRefreshInterval.menuLabel());
    }

    private void updateMaxRowsLabel() {
        maxRowsButton.setText("Rows: " + formatRows(maxRowsOption.rows()));
    }

    private static String formatRows(int rows) {
        if (rows >= 1000 && rows % 1000 == 0) {
            return (rows / 1000) + "k";
        }
        return Integer.toString(rows);
    }

    private void exportSlice(boolean selectionOnly) {
        DynamicResultTable table = tableSupplier.get();
        if (table == null) {
            return;
        }
        QueryResult slice = table.exportableResult(selectionOnly);
        if (slice != null) {
            onExportToFile.accept(slice);
        }
    }

    private void applyAutoRefresh(AutoRefreshInterval interval) {
        autoRefreshTimeline.stop();
        autoRefreshTimeline.getKeyFrames().clear();
        if (interval == null || interval.seconds() <= 0) {
            return;
        }
        autoRefreshTimeline.getKeyFrames().add(new KeyFrame(
                Duration.seconds(interval.seconds()),
                event -> onRefresh.run()));
        autoRefreshTimeline.playFromStart();
    }

    private static Separator subtleSeparator() {
        Separator separator = new Separator(Orientation.VERTICAL);
        separator.getStyleClass().add("result-toolbar-separator");
        separator.setOpacity(0.5);
        separator.setMaxHeight(16);
        return separator;
    }

    private void setDataEditControlsVisible(boolean visible) {
        for (javafx.scene.Node node : List.of(
                dataEditSeparator, addRowButton, deleteRowsButton, submitEditsButton, revertEditsButton)) {
            node.setVisible(visible);
            node.setManaged(visible);
        }
    }
}
