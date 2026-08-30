package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.inspection.InspectionHighlights;
import com.lazaro.sqlide.core.inspection.InspectionIssue;
import com.lazaro.sqlide.core.inspection.Severity;
import com.lazaro.sqlide.core.inspection.SqlInspector;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Kind;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Suggestion;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableValue;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.reactfx.Subscription;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * SQL editor built on a RichTextFX {@link CodeArea}, with line numbers, regex-driven
 * syntax highlighting, schema-aware autocomplete and debounced AST inspections.
 */
public final class SqlEditorPane extends BorderPane {

    private static final Duration HIGHLIGHT_DELAY = Duration.millis(120);
    /** Wait for a typing pause before parsing; avoids inspecting mid-keystroke. */
    private static final Duration INSPECTION_DELAY = Duration.millis(500);
    private static final Duration AUTOCOMPLETE_DELAY = Duration.millis(45);
    private static final int POPUP_MAX_ROWS = 12;
    private static final double ROW_HEIGHT = 26;

    private static final String GUTTER_STYLE = """
            -fx-text-fill: #5c626b; \
            -fx-background-color: #1e1f22; \
            -fx-font-weight: normal; \
            -fx-font-family: "JetBrains Mono", "Cascadia Mono", "Consolas", monospace; \
            -fx-font-size: 12px; \
            -fx-padding: 0 10 0 8;""";

    private final CodeArea codeArea = new CodeArea();
    private final EditorFindBar findBar;
    private final ExecutorService highlightExecutor;
    private final ExecutorService inspectionExecutor;
    private final Subscription highlightSubscription;
    private final PauseTransition inspectionDebounce = new PauseTransition(INSPECTION_DELAY);
    private final Popup completionPopup = new Popup();
    private final ListView<Suggestion> completionList = new ListView<>();
    private final Tooltip inspectionTooltip = new Tooltip();
    private final AtomicLong inspectionGeneration = new AtomicLong();
    private Subscription autocompleteSubscription;
    private Task<List<InspectionIssue>> activeInspectionTask;

    private Supplier<SchemaCache> schemaCache = SchemaCache::new;
    private Supplier<String> activeCatalog = () -> null;
    private SqlAutocompleteEngine engine = new SqlAutocompleteEngine(new SchemaCache());
    /** Set when we insert '.' ourselves during chain-completion, so KEY_TYPED does not duplicate it. */
    private boolean suppressNextDotTyped;
    private Runnable onSelectInDatabase = () -> { };

    private final StringProperty boundSessionId = new SimpleStringProperty();
    private final ComboBox<SessionChoice> sessionBox = new ComboBox<>();
    private final HBox sessionBar = new HBox(8);
    private boolean updatingSessionBox;

    private volatile StyleSpans<Collection<String>> lastHighlighting =
            StyleSpans.singleton(Collections.emptyList(), 0);
    private volatile List<InspectionIssue> lastIssues = List.of();

    /** One entry in the per-console session picker. */
    public record SessionChoice(String id, String label) {
        @Override
        public String toString() {
            return label == null ? "" : label;
        }
    }

    public SqlEditorPane() {
        this("");
    }

    public SqlEditorPane(String initialSql) {
        highlightExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sqlide-highlighter");
            thread.setDaemon(true);
            return thread;
        });
        inspectionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sqlide-inspector");
            thread.setDaemon(true);
            return thread;
        });

        IntFunction<Node> lineNumbers = LineNumberFactory.get(codeArea);
        codeArea.setParagraphGraphicFactory(index -> {
            Node node = lineNumbers.apply(index);
            node.setStyle(GUTTER_STYLE);
            return node;
        });
        codeArea.getStyleClass().add("sql-editor");
        codeArea.setWrapText(false);

        highlightSubscription = codeArea.multiPlainChanges()
                .successionEnds(java.time.Duration.ofMillis((long) HIGHLIGHT_DELAY.toMillis()))
                .retainLatestUntilLater(highlightExecutor)
                .supplyTask(this::computeHighlightingAsync)
                .awaitLatest(codeArea.multiPlainChanges())
                .filterMap(attempt -> attempt.isSuccess() ? Optional.of(attempt.get()) : Optional.empty())
                .subscribe(this::applyHighlighting);

        wireInspectionDebounce();

        configureAutocompletePopup();
        wireAutocomplete();
        wireInspectionTooltips();

        getStyleClass().add("sql-editor-pane");
        getStylesheets().add(stylesheet());
        EditorFindBar findBar = new EditorFindBar(codeArea);
        this.findBar = findBar;

        Label sessionLabel = new Label("Data source");
        sessionLabel.getStyleClass().add("console-session-label");
        sessionBox.setPromptText("Not connected");
        sessionBox.getStyleClass().add("console-session-box");
        sessionBox.setOnAction(event -> {
            if (updatingSessionBox) {
                return;
            }
            SessionChoice choice = sessionBox.getValue();
            boundSessionId.set(choice == null ? null : choice.id());
        });
        Region sessionSpacer = new Region();
        HBox.setHgrow(sessionSpacer, Priority.ALWAYS);
        sessionBar.getChildren().setAll(sessionSpacer, sessionLabel, sessionBox);
        sessionBar.getStyleClass().add("console-session-bar");
        sessionBar.setAlignment(Pos.CENTER_RIGHT);
        sessionBar.setPadding(new Insets(2, 8, 2, 8));

        VBox top = new VBox(sessionBar, findBar);
        setTop(top);
        setCenter(new VirtualizedScrollPane<>(codeArea));
        installEditorContextMenu();

        if (initialSql != null && !initialSql.isEmpty()) {
            setSql(initialSql);
        }
    }

    // ---------------------------------------------------------------- public API

    public StringProperty boundSessionIdProperty() {
        return boundSessionId;
    }

    public String getBoundSessionId() {
        return boundSessionId.get();
    }

    public void setBoundSessionId(String sessionId) {
        boundSessionId.set(sessionId);
        selectSessionInBox(sessionId);
    }

    /** Refreshes the console data-source picker from live sessions. */
    public void setSessionChoices(List<SessionChoice> choices, String preferredId) {
        updatingSessionBox = true;
        try {
            String current = preferredId != null ? preferredId : boundSessionId.get();
            sessionBox.setItems(FXCollections.observableArrayList(
                    choices == null ? List.of() : choices));
            selectSessionInBox(current);
            if (sessionBox.getValue() != null) {
                boundSessionId.set(sessionBox.getValue().id());
            } else if (choices != null && !choices.isEmpty()) {
                sessionBox.getSelectionModel().selectFirst();
                boundSessionId.set(sessionBox.getValue().id());
            } else {
                boundSessionId.set(null);
            }
        } finally {
            updatingSessionBox = false;
        }
    }

    private void selectSessionInBox(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        for (SessionChoice choice : sessionBox.getItems()) {
            if (sessionId.equals(choice.id())) {
                sessionBox.setValue(choice);
                return;
            }
        }
    }

    /** Shows the find strip; pass {@code true} for replace mode (Ctrl+H). */
    public void showFind(boolean replaceMode) {
        findBar.show(replaceMode);
    }

    public void hideFind() {
        findBar.hide();
    }

    public boolean isFindShowing() {
        return findBar.isShowing();
    }

    /** F3 / Shift+F3 — next or previous match (opens the bar if needed). */
    public void findNext(boolean forward) {
        findBar.findNext(forward);
    }

    public void setOnSelectInDatabase(Runnable action) {
        this.onSelectInDatabase = action == null ? () -> { } : action;
    }

    /** Swaps the schema snapshot used for completions. Safe to call at any time. */
    public void setSchemaCache(Supplier<SchemaCache> schemaCache) {
        this.schemaCache = schemaCache == null ? SchemaCache::new : schemaCache;
        refreshAutocompleteEngine();
        scheduleInspectionNow();
    }

    /** Supplies the session's active database so table completions stay scoped to it. */
    public void setActiveCatalog(Supplier<String> activeCatalog) {
        this.activeCatalog = activeCatalog == null ? () -> null : activeCatalog;
        refreshAutocompleteEngine();
        scheduleInspectionNow();
    }

    public void refreshAutocompleteEngine() {
        this.engine = new SqlAutocompleteEngine(schemaCache.get(), activeCatalog);
    }

    /**
     * The SQL the user intends to run: the selection when there is one, otherwise
     * the single statement under the caret. Never the whole buffer when it contains
     * several statements — JDBC rejects that with a misleading syntax error.
     */
    public String getEffectiveSql() {
        String selection = codeArea.getSelectedText();
        if (selection != null && !selection.isBlank()) {
            return selection.strip();
        }
        return SqlStatementExtractor.statementAt(codeArea.getText(), codeArea.getCaretPosition());
    }

    /**
     * Statements to execute: splits a multi-statement selection, otherwise the
     * single caret statement.
     */
    public java.util.List<String> getEffectiveStatements() {
        String selection = codeArea.getSelectedText();
        if (selection != null && !selection.isBlank()) {
            java.util.List<String> parts = SqlStatementExtractor.statements(selection);
            return parts.isEmpty() ? java.util.List.of(selection.strip()) : parts;
        }
        String one = SqlStatementExtractor.statementAt(codeArea.getText(), codeArea.getCaretPosition());
        return one.isBlank() ? java.util.List.of() : java.util.List.of(one);
    }

    public String getSql() {
        return codeArea.getText();
    }

    public void setSql(String sql) {
        codeArea.replaceText(Objects.requireNonNullElse(sql, ""));
        codeArea.moveTo(0);
        codeArea.requestFollowCaret();
    }

    /** Replaces editor content and selects the first occurrence of {@code placeholder}. */
    public void setSqlSelecting(String sql, String placeholder) {
        String text = Objects.requireNonNullElse(sql, "");
        codeArea.replaceText(text);
        if (placeholder != null && !placeholder.isEmpty()) {
            int start = text.indexOf(placeholder);
            if (start >= 0) {
                codeArea.selectRange(start, start + placeholder.length());
                codeArea.requestFollowCaret();
                return;
            }
        }
        codeArea.moveTo(0);
        codeArea.requestFollowCaret();
    }

    public void insertAtCaret(String text) {
        if (text != null && !text.isEmpty()) {
            codeArea.insertText(codeArea.getCaretPosition(), text);
        }
    }

    public void clear() {
        codeArea.clear();
    }

    public CodeArea getCodeArea() {
        return codeArea;
    }

    public ObservableValue<String> textProperty() {
        return codeArea.textProperty();
    }

    public ObservableValue<String> caretLocation() {
        return Bindings.createStringBinding(
                () -> "Ln %d, Col %d".formatted(codeArea.getCurrentParagraph() + 1, codeArea.getCaretColumn() + 1),
                codeArea.currentParagraphProperty(),
                codeArea.caretColumnProperty());
    }

    @Override
    public void requestFocus() {
        codeArea.requestFocus();
    }

    public void dispose() {
        hideCompletions();
        if (autocompleteSubscription != null) {
            autocompleteSubscription.unsubscribe();
        }
        highlightSubscription.unsubscribe();
        inspectionDebounce.stop();
        cancelActiveInspection();
        highlightExecutor.shutdownNow();
        inspectionExecutor.shutdownNow();
    }

    private void installEditorContextMenu() {
        MenuItem selectInDatabase = new MenuItem("Select in Database");
        selectInDatabase.setAccelerator(new KeyCodeCombination(KeyCode.F1, KeyCombination.ALT_DOWN));
        selectInDatabase.setOnAction(event -> onSelectInDatabase.run());
        MenuItem find = new MenuItem("Find\u2026");
        find.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN));
        find.setOnAction(event -> showFind(false));
        MenuItem replace = new MenuItem("Replace\u2026");
        replace.setAccelerator(new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN));
        replace.setOnAction(event -> showFind(true));
        codeArea.setContextMenu(new ContextMenu(selectInDatabase, find, replace));
    }

    // ---------------------------------------------------------------- autocomplete

    private void configureAutocompletePopup() {
        completionList.getStyleClass().add("sql-completion-list");
        completionList.getStylesheets().add(stylesheet());
        completionList.setPrefWidth(420);
        completionList.setFocusTraversable(false);
        completionList.setCellFactory(view -> new CompletionCell());
        completionList.setOnMouseClicked(event -> {
            if (event.getClickCount() >= 1) {
                applySelectedCompletion();
            }
        });
        completionPopup.getContent().add(completionList);
        completionPopup.setAutoHide(true);
        completionPopup.setAutoFix(false);

        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, this::handleCompletionKeys);
        // Dot should open the column list immediately — waiting for the debounce feels laggy.
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, event -> {
            if (".".equals(event.getCharacter())) {
                if (suppressNextDotTyped) {
                    event.consume();
                    suppressNextDotTyped = false;
                }
                Platform.runLater(() -> updateCompletions(false));
            }
        });
    }

    private void wireAutocomplete() {
        autocompleteSubscription = codeArea.multiPlainChanges()
                .successionEnds(java.time.Duration.ofMillis((long) AUTOCOMPLETE_DELAY.toMillis()))
                .subscribe(changes -> Platform.runLater(() -> updateCompletions(false)));
    }

    private void handleCompletionKeys(KeyEvent event) {
        if (event.getCode() == KeyCode.SPACE && event.isControlDown()) {
            event.consume();
            updateCompletions(true);
            return;
        }
        if (!completionPopup.isShowing()) {
            return;
        }
        switch (event.getCode()) {
            case ESCAPE -> {
                event.consume();
                hideCompletions();
            }
            case ENTER, TAB -> {
                event.consume();
                applySelectedCompletion();
            }
            case UP -> {
                event.consume();
                moveCompletion(-1);
            }
            case DOWN -> {
                event.consume();
                moveCompletion(1);
            }
            case PERIOD -> {
                // Accept current schema item then continue with '.', like IntelliJ chain completion.
                Suggestion selected = completionList.getSelectionModel().getSelectedItem();
                if (selected != null && selected.kind() != Kind.KEYWORD) {
                    event.consume();
                    applySelectedCompletion();
                    codeArea.insertText(codeArea.getCaretPosition(), ".");
                    suppressNextDotTyped = true;
                    Platform.runLater(() -> updateCompletions(false));
                }
            }
            default -> {
                // Typing continues in the editor; the debounce refreshes the filtered list.
            }
        }
    }

    private void updateCompletions(boolean invoked) {
        engine = new SqlAutocompleteEngine(schemaCache.get(), activeCatalog);
        String sql = codeArea.getText();
        int caret = codeArea.getCaretPosition();

        if (!invoked && !completionPopup.isShowing() && !engine.shouldAutoPopup(sql, caret)) {
            return;
        }

        List<Suggestion> suggestions = engine.suggest(sql, caret, invoked);
        if (suggestions.isEmpty()) {
            hideCompletions();
            return;
        }

        String previouslySelected = Optional.ofNullable(completionList.getSelectionModel().getSelectedItem())
                .map(Suggestion::insertText)
                .orElse(null);

        completionList.setItems(FXCollections.observableArrayList(suggestions));

        int selectIndex = 0;
        if (previouslySelected != null) {
            for (int i = 0; i < suggestions.size(); i++) {
                if (suggestions.get(i).insertText().equalsIgnoreCase(previouslySelected)) {
                    selectIndex = i;
                    break;
                }
            }
        }
        completionList.getSelectionModel().select(selectIndex);
        completionList.scrollTo(selectIndex);

        int rows = Math.min(suggestions.size(), POPUP_MAX_ROWS);
        completionList.setPrefHeight(rows * ROW_HEIGHT + 6);
        showPopupAtCaret();
    }

    private void showPopupAtCaret() {
        Optional<Bounds> caretBounds = codeArea.getCaretBounds();
        if (caretBounds.isEmpty()) {
            hideCompletions();
            return;
        }
        // RichTextFX caret bounds are already in screen coordinates.
        Bounds caret = caretBounds.get();
        double x = caret.getMinX();
        double y = caret.getMaxY() + 2;

        Screen screen = Screen.getScreensForRectangle(x, y, 1, 1).stream()
                .findFirst()
                .orElse(Screen.getPrimary());
        Rectangle2D visible = screen.getVisualBounds();
        double popupWidth = completionList.getPrefWidth();
        double popupHeight = completionList.getPrefHeight();

        if (y + popupHeight > visible.getMaxY()) {
            y = caret.getMinY() - popupHeight - 2;
        }
        if (x + popupWidth > visible.getMaxX()) {
            x = visible.getMaxX() - popupWidth;
        }
        x = Math.max(visible.getMinX(), x);
        y = Math.max(visible.getMinY(), y);

        if (!completionPopup.isShowing()) {
            completionPopup.show(codeArea, x, y);
        } else {
            completionPopup.setAnchorX(x);
            completionPopup.setAnchorY(y);
        }
    }

    private void applySelectedCompletion() {
        Suggestion selected = completionList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            hideCompletions();
            return;
        }
        int start = Math.max(0, Math.min(selected.replaceStart(), codeArea.getLength()));
        int end = Math.max(start, Math.min(selected.replaceEnd(), codeArea.getLength()));
        String text = selected.insertText();
        if (selected.trailingSpace() && (end >= codeArea.getLength() || !Character.isWhitespace(codeArea.getText().charAt(end)))) {
            text = text + " ";
        }
        codeArea.replaceText(start, end, text);
        hideCompletions();
    }

    private void moveCompletion(int delta) {
        int size = completionList.getItems().size();
        if (size == 0) {
            return;
        }
        int next = Math.floorMod(completionList.getSelectionModel().getSelectedIndex() + delta, size);
        completionList.getSelectionModel().select(next);
        completionList.scrollTo(next);
    }

    private void hideCompletions() {
        completionPopup.hide();
    }

    /** IntelliJ-style row: kind badge · name ………… detail */
    private static final class CompletionCell extends ListCell<Suggestion> {

        private final Label badge = new Label();
        private final Label name = new Label();
        private final Label detail = new Label();
        private final HBox root = new HBox(8);

        CompletionCell() {
            badge.getStyleClass().add("sql-completion-badge");
            name.getStyleClass().add("sql-completion-name");
            detail.getStyleClass().add("sql-completion-detail");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new Insets(0, 8, 0, 4));
            root.getChildren().addAll(badge, name, spacer, detail);
            setGraphic(null);
            setText(null);
        }

        @Override
        protected void updateItem(Suggestion item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            badge.setText(kindTag(item.kind()));
            badge.getStyleClass().removeAll(
                    "sql-completion-badge-keyword", "sql-completion-badge-table",
                    "sql-completion-badge-column", "sql-completion-badge-join");
            badge.getStyleClass().add(switch (item.kind()) {
                case KEYWORD -> "sql-completion-badge-keyword";
                case TABLE -> "sql-completion-badge-table";
                case COLUMN -> "sql-completion-badge-column";
                case JOIN -> "sql-completion-badge-join";
            });
            name.setText(item.name());
            detail.setText(item.detail() == null ? "" : item.detail());
            setGraphic(root);
        }
    }

    private static String kindTag(Kind kind) {
        return switch (kind) {
            case KEYWORD -> "K";
            case TABLE -> "T";
            case COLUMN -> "C";
            case JOIN -> "J";
        };
    }

    // ---------------------------------------------------------------- highlighting / inspections

    private void wireInspectionDebounce() {
        inspectionDebounce.setOnFinished(event -> runInspection());
        codeArea.textProperty().addListener((observable, previous, current) -> {
            // IntelliJ clears squiggles while typing, then re-runs after a pause.
            cancelActiveInspection();
            inspectionDebounce.stop();
            if (!lastIssues.isEmpty()) {
                lastIssues = List.of();
                paintCombinedStyles();
            }
            inspectionDebounce.playFromStart();
        });
    }

    private Task<StyleSpans<Collection<String>>> computeHighlightingAsync() {
        String snapshot = codeArea.getText();
        Task<StyleSpans<Collection<String>>> task = new Task<>() {
            @Override
            protected StyleSpans<Collection<String>> call() {
                return SqlSyntaxHighlighter.computeHighlighting(snapshot);
            }
        };
        highlightExecutor.execute(task);
        return task;
    }

    private void applyHighlighting(StyleSpans<Collection<String>> spans) {
        lastHighlighting = spans;
        paintCombinedStyles();
    }

    private void applyInspections(List<InspectionIssue> issues) {
        lastIssues = List.copyOf(issues);
        paintCombinedStyles();
    }

    /** Immediate inspect (schema/catalog change) — still cancels redundant in-flight work. */
    private void scheduleInspectionNow() {
        cancelActiveInspection();
        inspectionDebounce.stop();
        runInspection();
    }

    private void runInspection() {
        // Invalidate any in-flight task, then mint the generation for this run.
        Task<List<InspectionIssue>> previous = activeInspectionTask;
        activeInspectionTask = null;
        if (previous != null && previous.isRunning()) {
            previous.cancel(true);
        }
        final long generation = inspectionGeneration.incrementAndGet();
        final String snapshot = codeArea.getText();
        final SchemaCache cache = schemaCache.get();
        final String catalog = activeCatalog.get();

        Task<List<InspectionIssue>> task = new Task<>() {
            @Override
            protected List<InspectionIssue> call() {
                if (isCancelled() || generation != inspectionGeneration.get()) {
                    return List.of();
                }
                return SqlInspector.inspect(snapshot, cache, catalog);
            }
        };
        activeInspectionTask = task;
        task.setOnSucceeded(event -> {
            if (generation != inspectionGeneration.get() || task.isCancelled()) {
                return;
            }
            if (!snapshot.equals(codeArea.getText())) {
                return;
            }
            applyInspections(task.getValue());
        });
        inspectionExecutor.execute(task);
    }

    private void cancelActiveInspection() {
        inspectionGeneration.incrementAndGet();
        Task<List<InspectionIssue>> task = activeInspectionTask;
        activeInspectionTask = null;
        if (task != null && task.isRunning()) {
            task.cancel(true);
        }
    }

    /**
     * Merges syntax colours with inspection underlines. Must preserve token colours
     * (IntelliJ keeps highlighting under the squiggle).
     */
    private void paintCombinedStyles() {
        Runnable paint = () -> {
            int length = codeArea.getLength();
            StyleSpans<Collection<String>> highlighting = lastHighlighting;
            if (highlighting == null || highlighting.length() != length) {
                highlighting = SqlSyntaxHighlighter.computeHighlighting(codeArea.getText());
                lastHighlighting = highlighting;
            }
            StyleSpans<Collection<String>> inspections = toInspectionSpans(lastIssues, length);
            StyleSpans<Collection<String>> combined = highlighting.overlay(inspections, SqlEditorPane::mergeStyles);
            if (combined.length() == length) {
                codeArea.setStyleSpans(0, combined);
            }
        };
        if (Platform.isFxApplicationThread()) {
            paint.run();
        } else {
            Platform.runLater(paint);
        }
    }

    private static Collection<String> mergeStyles(Collection<String> syntax, Collection<String> inspection) {
        if (inspection == null || inspection.isEmpty()) {
            return syntax == null || syntax.isEmpty() ? List.of() : syntax;
        }
        if (syntax == null || syntax.isEmpty()) {
            return inspection;
        }
        Set<String> merged = new HashSet<>(syntax.size() + inspection.size());
        merged.addAll(syntax);
        merged.addAll(inspection);
        return List.copyOf(merged);
    }

    static StyleSpans<Collection<String>> toInspectionSpans(List<InspectionIssue> issues, int length) {
        if (length <= 0) {
            return StyleSpans.singleton(List.of(), 0);
        }
        List<InspectionHighlights.Run> runs = InspectionHighlights.merge(issues, length);
        StyleSpansBuilder<Collection<String>> builder = new StyleSpansBuilder<>();
        int cursor = 0;
        for (InspectionHighlights.Run run : runs) {
            if (run.start() > cursor) {
                builder.add(List.of(), run.start() - cursor);
            }
            builder.add(List.of(run.styleClass()), run.end() - run.start());
            cursor = run.end();
        }
        if (cursor < length) {
            builder.add(List.of(), length - cursor);
        } else if (runs.isEmpty()) {
            builder.add(List.of(), length);
        }
        return builder.create();
    }

    private void wireInspectionTooltips() {
        inspectionTooltip.getStyleClass().add("sql-inspection-tooltip");
        inspectionTooltip.setWrapText(true);
        inspectionTooltip.setMaxWidth(420);
        inspectionTooltip.setShowDelay(javafx.util.Duration.millis(200));
        inspectionTooltip.setShowDuration(javafx.util.Duration.seconds(10));
        codeArea.addEventFilter(MouseEvent.MOUSE_MOVED, event -> updateInspectionTooltip(event));
        codeArea.addEventFilter(MouseEvent.MOUSE_EXITED, event ->
                Tooltip.uninstall(codeArea, inspectionTooltip));
    }

    private void updateInspectionTooltip(MouseEvent event) {
        int offset = codeArea.hit(event.getX(), event.getY()).getInsertionIndex();
        Optional<InspectionIssue> issue = issueAt(offset);
        if (issue.isEmpty()) {
            Tooltip.uninstall(codeArea, inspectionTooltip);
            return;
        }
        String text = issue.get().message();
        if (!text.equals(inspectionTooltip.getText())) {
            inspectionTooltip.setText(text);
        }
        Tooltip.install(codeArea, inspectionTooltip);
    }

    private Optional<InspectionIssue> issueAt(int offset) {
        InspectionIssue best = null;
        for (InspectionIssue issue : lastIssues) {
            if (offset >= issue.startOffset() && offset < issue.endOffset()) {
                if (best == null || severityRank(issue.severity()) > severityRank(best.severity())) {
                    best = issue;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static int severityRank(Severity severity) {
        return switch (severity) {
            case ERROR -> 3;
            case WARNING -> 2;
            case WEAK_WARNING -> 1;
        };
    }

    private static String stylesheet() {
        return Objects.requireNonNull(
                        SqlEditorPane.class.getResource("/com/lazaro/sqlide/css/sql-editor.css"),
                        "sql-editor.css is missing from the classpath")
                .toExternalForm();
    }
}
