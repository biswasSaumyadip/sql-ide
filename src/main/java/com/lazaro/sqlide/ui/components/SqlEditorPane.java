package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.doc.SqlDocResolver;
import com.lazaro.sqlide.core.doc.SqlDocResolver.Doc;
import com.lazaro.sqlide.core.inspection.InspectionHighlights;
import com.lazaro.sqlide.core.inspection.InspectionIssue;
import com.lazaro.sqlide.core.inspection.Severity;
import com.lazaro.sqlide.core.inspection.SqlInspector;
import com.lazaro.sqlide.core.sql.SqlFoldRegions;
import com.lazaro.sqlide.core.sql.SqlInlayHints;
import com.lazaro.sqlide.core.sql.SqlParameterParser;
import com.lazaro.sqlide.ui.Icons;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine;
import com.lazaro.sqlide.ui.dialogs.JsonViewerDialog;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Kind;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.SuggestResult;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Suggestion;
import com.lazaro.sqlide.ui.autocomplete.SqlCompletionHygiene.Style;
import com.lazaro.sqlide.ui.autocomplete.SqlSnippetCatalog;
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
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexRange;
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
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.util.Duration;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.event.MouseOverTextEvent;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;
import org.fxmisc.richtext.model.TwoDimensional;
import org.reactfx.Subscription;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * SQL editor built on a RichTextFX {@link CodeArea}, with line numbers, regex-driven
 * syntax highlighting, code folding, inlay hints, schema-aware autocomplete and
 * debounced AST inspections.
 */
public final class SqlEditorPane extends BorderPane {

    private static final Duration HIGHLIGHT_DELAY = Duration.millis(120);
    /** Wait for a typing pause before parsing; avoids inspecting mid-keystroke. */
    private static final Duration INSPECTION_DELAY = Duration.millis(500);
    private static final Duration INLAY_DELAY = Duration.millis(420);
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
    private final Pane inlayLayer = new Pane();
    private final EditorFindBar findBar;
    private final ExecutorService highlightExecutor;
    private final ExecutorService inspectionExecutor;
    private final ExecutorService inlayExecutor;
    private final ExecutorService docExecutor;
    private final ExecutorService completionExecutor;
    private final Subscription highlightSubscription;
    private final PauseTransition inspectionDebounce = new PauseTransition(INSPECTION_DELAY);
    private final PauseTransition inlayDebounce = new PauseTransition(INLAY_DELAY);
    private final PauseTransition docHideDebounce = new PauseTransition(Duration.millis(220));
    private final Popup completionPopup = new Popup();
    private final ListView<Suggestion> completionList = new ListView<>();
    private final Label completionDocs = new Label();
    private final Label completionFooter = new Label();
    private final VBox completionChrome = new VBox();
    private final Tooltip inspectionTooltip = new Tooltip();
    private final SqlDocPopup docPopup = new SqlDocPopup();
    private final AtomicLong inspectionGeneration = new AtomicLong();
    private final AtomicLong inlayGeneration = new AtomicLong();
    private final AtomicLong docGeneration = new AtomicLong();
    private final AtomicLong completionGeneration = new AtomicLong();
    private Subscription autocompleteSubscription;
    private Task<List<InspectionIssue>> activeInspectionTask;
    private Task<List<SqlInlayHints.Hint>> activeInlayTask;
    private Task<SuggestResult> activeCompletionTask;

    private Supplier<SchemaCache> schemaCache = SchemaCache::new;
    private Supplier<String> activeCatalog = () -> null;
    private Supplier<ConnectionConfig.Driver> dialect = () -> ConnectionConfig.Driver.MYSQL;
    private Supplier<Style> completionStyle = Style::defaults;
    private Map<String, String> runConfigParams = Map.of();
    private SqlAutocompleteEngine engine = new SqlAutocompleteEngine(new SchemaCache());
    /** Set when we insert '.' ourselves during chain-completion, so KEY_TYPED does not duplicate it. */
    private boolean suppressNextDotTyped;
    /** Absolute [start,end) ranges for the last applied snippet / function placeholders. */
    private List<int[]> pendingPlaceholders = List.of();
    private int placeholderIndex = -1;
    private Runnable onSelectInDatabase = () -> { };
    private Consumer<Doc> onShowTablePreview = doc -> { };

    private final StringProperty boundSessionId = new SimpleStringProperty();
    private final ComboBox<SessionChoice> sessionBox = new ComboBox<>();
    private final HBox sessionBar = new HBox(8);
    private boolean updatingSessionBox;

    private volatile StyleSpans<Collection<String>> lastHighlighting =
            StyleSpans.singleton(Collections.emptyList(), 0);
    private volatile List<InspectionIssue> lastIssues = List.of();
    private volatile List<SqlInlayHints.Hint> lastInlays = List.of();
    private volatile Map<Integer, SqlFoldRegions.Region> foldsByStart = Map.of();
    /** Open-bracket character offsets for folds that are currently collapsed. */
    private final Set<Integer> collapsedOpenOffsets = ConcurrentHashMap.newKeySet();
    private IntFunction<Node> lineNumbers;
    private List<InlayPadSupport.Pad> activeInlayPads = List.of();
    private boolean mutatingDocument;
    private boolean ignoreFoldDocumentEvents;
    private final PauseTransition foldEventSuppress = new PauseTransition(Duration.millis(180));
    private double monospaceCharWidth = 7.8;

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
        inlayExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sqlide-inlays");
            thread.setDaemon(true);
            return thread;
        });
        docExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sqlide-doc");
            thread.setDaemon(true);
            return thread;
        });
        completionExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sqlide-completion");
            thread.setDaemon(true);
            return thread;
        });

        // Prefer workspace hygiene prefs when available — MainController overrides via setCompletionStyle.
        completionStyle = Style::defaults;

        lineNumbers = LineNumberFactory.get(
                codeArea,
                digits -> "%1$" + digits + "s",
                null,
                null);
        codeArea.setParagraphGraphicFactory(this::buildParagraphGraphic);
        codeArea.getStyleClass().add("sql-editor");
        codeArea.setWrapText(false);
        Platform.runLater(this::measureMonospaceWidth);

        highlightSubscription = codeArea.multiPlainChanges()
                .successionEnds(java.time.Duration.ofMillis((long) HIGHLIGHT_DELAY.toMillis()))
                .retainLatestUntilLater(highlightExecutor)
                .supplyTask(this::computeHighlightingAsync)
                .awaitLatest(codeArea.multiPlainChanges())
                .filterMap(attempt -> attempt.isSuccess() ? Optional.of(attempt.get()) : Optional.empty())
                .subscribe(this::applyHighlighting);

        wireInspectionDebounce();
        wireInlayHints();
        wireFolding();

        configureAutocompletePopup();
        wireAutocomplete();
        wireInspectionTooltips();
        wireQuickDocumentation();

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

        inlayLayer.setMouseTransparent(true);
        inlayLayer.getStyleClass().add("inlay-layer");
        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(codeArea);
        StackPane editorStack = new StackPane(scroll, inlayLayer);
        editorStack.getStyleClass().add("sql-editor-stack");
        setCenter(editorStack);
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

    /** Opens table data preview from Quick Documentation (table hover). */
    public void setOnShowTablePreview(Consumer<Doc> action) {
        this.onShowTablePreview = action == null ? doc -> { } : action;
        docPopup.setOnShowPreview(this.onShowTablePreview);
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

    /** Supplies the JDBC dialect so keyword / function completions match the driver. */
    public void setDialect(Supplier<ConnectionConfig.Driver> dialect) {
        this.dialect = dialect == null ? () -> ConnectionConfig.Driver.MYSQL : dialect;
        refreshAutocompleteEngine();
    }

    /**
     * Default parameter names/values from the run configuration opened into this console.
     * Merged with {@code :name} placeholders already present in the buffer.
     */
    public void setRunConfigParams(Map<String, String> params) {
        this.runConfigParams = params == null ? Map.of() : Map.copyOf(params);
        refreshAutocompleteEngine();
    }

    /** Optional override for completion hygiene (keyword case / quoting). */
    public void setCompletionStyle(Supplier<Style> completionStyle) {
        this.completionStyle = completionStyle == null ? Style::defaults : completionStyle;
        refreshAutocompleteEngine();
    }

    public void refreshAutocompleteEngine() {
        this.engine = newEngine();
    }

    private SqlAutocompleteEngine newEngine() {
        return new SqlAutocompleteEngine(
                schemaCache.get(),
                activeCatalog,
                dialect,
                this::knownParameters,
                completionStyle.get());
    }

    /** Run-config defaults plus named params already typed in the buffer. */
    private Map<String, String> knownParameters() {
        Map<String, String> merged = new java.util.LinkedHashMap<>();
        if (runConfigParams != null) {
            merged.putAll(runConfigParams);
        }
        for (SqlParameterParser.Parameter parameter : SqlParameterParser.find(logicalSql())) {
            if (parameter.kind() == SqlParameterParser.Kind.NAMED) {
                merged.putIfAbsent(parameter.name(), "");
            }
        }
        return merged;
    }

    /**
     * The SQL the user intends to run: the selection when there is one, otherwise
     * the single statement under the caret. Never the whole buffer when it contains
     * several statements — JDBC rejects that with a misleading syntax error.
     */
    public String getEffectiveSql() {
        String selection = codeArea.getSelectedText();
        if (selection != null && !selection.isBlank()) {
            return InlayPadSupport.strip(selection, padsOverlappingSelection()).strip();
        }
        return SqlStatementExtractor.statementAt(logicalSql(), logicalCaret());
    }

    /**
     * Statements to execute: splits a multi-statement selection, otherwise the
     * single caret statement.
     */
    public java.util.List<String> getEffectiveStatements() {
        String selection = codeArea.getSelectedText();
        if (selection != null && !selection.isBlank()) {
            String cleaned = InlayPadSupport.strip(selection, padsOverlappingSelection());
            java.util.List<String> parts = SqlStatementExtractor.statements(cleaned);
            return parts.isEmpty() ? java.util.List.of(cleaned.strip()) : parts;
        }
        String one = SqlStatementExtractor.statementAt(logicalSql(), logicalCaret());
        return one.isBlank() ? java.util.List.of() : java.util.List.of(one);
    }

    public String getSql() {
        return logicalSql();
    }

    /** Document text with inlay pad spaces removed (safe for execution / parsing). */
    private String logicalSql() {
        return InlayPadSupport.strip(codeArea.getText(), activeInlayPads);
    }

    private int logicalCaret() {
        return InlayPadSupport.toLogicalOffset(codeArea.getCaretPosition(), activeInlayPads);
    }

    private List<InlayPadSupport.Pad> padsOverlappingSelection() {
        IndexRange selection = codeArea.getSelection();
        if (selection.getLength() == 0 || activeInlayPads.isEmpty()) {
            return List.of();
        }
        int start = selection.getStart();
        int end = selection.getEnd();
        List<InlayPadSupport.Pad> overlapping = new ArrayList<>();
        for (InlayPadSupport.Pad pad : activeInlayPads) {
            int padEnd = pad.offset() + pad.spaces();
            if (pad.offset() >= start && padEnd <= end) {
                overlapping.add(new InlayPadSupport.Pad(pad.offset() - start, pad.spaces(), pad.label()));
            }
        }
        return overlapping;
    }

    public void setSql(String sql) {
        mutatingDocument = true;
        try {
            activeInlayPads = List.of();
            lastInlays = List.of();
            inlayLayer.getChildren().clear();
            collapsedOpenOffsets.clear();
            codeArea.replaceText(Objects.requireNonNullElse(sql, ""));
            codeArea.moveTo(0);
            codeArea.requestFollowCaret();
        } finally {
            mutatingDocument = false;
        }
        recomputeFoldMap();
        refreshParagraphGraphics();
    }

    /** Replaces editor content and selects the first occurrence of {@code placeholder}. */
    public void setSqlSelecting(String sql, String placeholder) {
        mutatingDocument = true;
        try {
            activeInlayPads = List.of();
            lastInlays = List.of();
            inlayLayer.getChildren().clear();
            collapsedOpenOffsets.clear();
            String text = Objects.requireNonNullElse(sql, "");
            codeArea.replaceText(text);
            if (placeholder != null && !placeholder.isEmpty()) {
                int start = text.indexOf(placeholder);
                if (start >= 0) {
                    codeArea.selectRange(start, start + placeholder.length());
                    codeArea.requestFollowCaret();
                    mutatingDocument = false;
                    recomputeFoldMap();
                    refreshParagraphGraphics();
                    return;
                }
            }
            codeArea.moveTo(0);
            codeArea.requestFollowCaret();
        } finally {
            mutatingDocument = false;
        }
        recomputeFoldMap();
        refreshParagraphGraphics();
    }

    public void insertAtCaret(String text) {
        if (text != null && !text.isEmpty()) {
            codeArea.insertText(codeArea.getCaretPosition(), text);
        }
    }

    public void clear() {
        setSql("");
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
        hideQuickDocumentation();
        cancelActiveCompletion();
        if (autocompleteSubscription != null) {
            autocompleteSubscription.unsubscribe();
        }
        highlightSubscription.unsubscribe();
        inspectionDebounce.stop();
        inlayDebounce.stop();
        docHideDebounce.stop();
        cancelActiveInspection();
        cancelActiveInlay();
        highlightExecutor.shutdownNow();
        inspectionExecutor.shutdownNow();
        inlayExecutor.shutdownNow();
        docExecutor.shutdownNow();
        completionExecutor.shutdownNow();
    }

    private void installEditorContextMenu() {
        MenuItem selectInDatabase = new MenuItem("Select in Database");
        selectInDatabase.setAccelerator(new KeyCodeCombination(KeyCode.F1, KeyCombination.ALT_DOWN));
        selectInDatabase.setOnAction(event -> onSelectInDatabase.run());
        MenuItem viewAsJson = new MenuItem("View as JSON\u2026");
        viewAsJson.setOnAction(event -> openJsonViewerAtCaret());
        MenuItem find = new MenuItem("Find\u2026");
        find.setAccelerator(new KeyCodeCombination(KeyCode.F, KeyCombination.SHORTCUT_DOWN));
        find.setOnAction(event -> showFind(false));
        MenuItem replace = new MenuItem("Replace\u2026");
        replace.setAccelerator(new KeyCodeCombination(KeyCode.H, KeyCombination.SHORTCUT_DOWN));
        replace.setOnAction(event -> showFind(true));

        ContextMenu menu = new ContextMenu(selectInDatabase, viewAsJson, find, replace);
        menu.setOnShowing(event -> viewAsJson.setDisable(findJsonAtCaret().isEmpty()));
        codeArea.setContextMenu(menu);
    }

    private Optional<SqlSyntaxHighlighter.JsonStringLiteral> findJsonAtCaret() {
        IndexRange selection = codeArea.getSelection();
        int selStart = InlayPadSupport.toLogicalOffset(selection.getStart(), activeInlayPads);
        int selEnd = InlayPadSupport.toLogicalOffset(selection.getEnd(), activeInlayPads);
        return SqlSyntaxHighlighter.findJsonLiteralAt(
                logicalSql(),
                logicalCaret(),
                selStart,
                selEnd);
    }

    private void openJsonViewerAtCaret() {
        findJsonAtCaret().ifPresent(literal -> {
            javafx.stage.Window owner = getScene() == null ? null : getScene().getWindow();
            new JsonViewerDialog(owner, literal.json()).showAndWait();
        });
    }

    // ---------------------------------------------------------------- autocomplete

    private void configureAutocompletePopup() {
        completionList.getStyleClass().addAll("sql-completion-list", "autocomplete-list-view");
        completionList.setPrefWidth(440);
        completionList.setFocusTraversable(false);
        completionList.setCellFactory(view -> new CompletionCell());
        completionList.setOnMouseClicked(event -> {
            if (event.getClickCount() >= 1) {
                applySelectedCompletion();
            }
        });
        completionList.getSelectionModel().selectedItemProperty().addListener(
                (observable, previous, current) -> updateCompletionDocs(current));

        completionDocs.getStyleClass().add("sql-completion-docs");
        completionDocs.setWrapText(true);
        completionDocs.setMaxWidth(440);
        completionDocs.setMinHeight(28);

        completionFooter.getStyleClass().add("sql-completion-footer");

        completionChrome.getStyleClass().add("sql-completion-popup");
        completionChrome.getStylesheets().add(stylesheet());
        completionChrome.getChildren().addAll(completionList, completionDocs, completionFooter);
        completionPopup.getContent().add(completionChrome);
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
            clearPlaceholderSession();
            updateCompletions(true);
            return;
        }
        // Tab cycles linked snippet placeholders when the popup is closed.
        if (event.getCode() == KeyCode.TAB && !completionPopup.isShowing() && hasPendingPlaceholders()) {
            event.consume();
            advancePlaceholder(event.isShiftDown() ? -1 : 1);
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
                if (selected != null && isChainable(selected.kind())) {
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

    private static boolean isChainable(Kind kind) {
        return kind == Kind.TABLE || kind == Kind.VIEW || kind == Kind.SCHEMA
                || kind == Kind.COLUMN || kind == Kind.JOIN;
    }

    private void updateCompletions(boolean invoked) {
        final String sql = logicalSql();
        final int caret = logicalCaret();

        // Cheap gate on the FX thread — avoid scheduling work that will no-op.
        SqlAutocompleteEngine probe = newEngine();
        if (!invoked && !completionPopup.isShowing() && !probe.shouldAutoPopup(sql, caret)) {
            return;
        }

        final String previouslySelected = Optional.ofNullable(completionList.getSelectionModel().getSelectedItem())
                .map(Suggestion::selectionKey)
                .orElse(null);

        cancelActiveCompletion();
        final long generation = completionGeneration.incrementAndGet();
        final SchemaCache cache = schemaCache.get();
        final String catalog = activeCatalog.get();
        final ConnectionConfig.Driver driver = dialect.get();
        final Map<String, String> params = knownParameters();
        final Style style = completionStyle.get();

        Task<SuggestResult> task = new Task<>() {
            @Override
            protected SuggestResult call() {
                if (isCancelled() || generation != completionGeneration.get()) {
                    return SuggestResult.empty();
                }
                SqlAutocompleteEngine eng = new SqlAutocompleteEngine(
                        cache, () -> catalog, () -> driver, () -> params, style);
                return eng.suggest(sql, caret, invoked);
            }
        };
        activeCompletionTask = task;
        task.setOnSucceeded(event -> {
            if (generation != completionGeneration.get() || task.isCancelled()) {
                return;
            }
            // Drop stale results if the user kept typing.
            if (!sql.equals(logicalSql()) || caret != logicalCaret()) {
                return;
            }
            applyCompletionResult(task.getValue(), previouslySelected);
        });
        task.setOnFailed(event -> {
            // Ignore — next keystroke will retry.
        });
        completionExecutor.execute(task);
    }

    private void applyCompletionResult(SuggestResult result, String previouslySelected) {
        if (result == null || result.isEmpty()) {
            hideCompletions();
            return;
        }
        List<Suggestion> suggestions = result.items();
        String currentPrefix = completionPrefixAt(logicalSql(), logicalCaret());

        if (!currentPrefix.isEmpty() && !suggestions.isEmpty()) {
            List<Suggestion> remaining = new ArrayList<>(suggestions.size());
            for (Suggestion suggestion : suggestions) {
                if (!isFullyTypedSuggestion(suggestion, currentPrefix)) {
                    remaining.add(suggestion);
                }
            }
            if (remaining.isEmpty()) {
                hideCompletions();
                return;
            }
            suggestions = remaining;
        }

        if (suggestions.isEmpty()) {
            hideCompletions();
            return;
        }

        completionList.setItems(FXCollections.observableArrayList(suggestions));

        int selectIndex = 0;
        if (previouslySelected != null) {
            for (int i = 0; i < suggestions.size(); i++) {
                if (suggestions.get(i).selectionKey().equals(previouslySelected)) {
                    selectIndex = i;
                    break;
                }
            }
        }
        completionList.getSelectionModel().select(selectIndex);
        completionList.scrollTo(selectIndex);
        updateCompletionDocs(completionList.getSelectionModel().getSelectedItem());

        int shown = suggestions.size();
        int total = Math.max(result.totalMatched(), shown);
        completionFooter.setText(total > shown
                ? "Showing %d of %d".formatted(shown, total)
                : "Showing %d".formatted(shown));

        int rows = Math.min(suggestions.size(), POPUP_MAX_ROWS);
        completionList.setPrefHeight(rows * ROW_HEIGHT + 6);
        showPopupAtCaret();
    }

    private void cancelActiveCompletion() {
        completionGeneration.incrementAndGet();
        Task<SuggestResult> task = activeCompletionTask;
        activeCompletionTask = null;
        if (task != null && task.isRunning()) {
            task.cancel(true);
        }
    }

    private void updateCompletionDocs(Suggestion suggestion) {
        if (suggestion == null) {
            completionDocs.setText("");
            completionDocs.setVisible(false);
            completionDocs.setManaged(false);
            return;
        }
        String text = suggestion.documentation();
        if (text == null || text.isBlank()) {
            // Fall back to Quick Doc resolution for schema objects.
            text = resolveCompletionDoc(suggestion).orElse(suggestion.detail() == null ? "" : suggestion.detail());
        }
        if (text == null || text.isBlank()) {
            completionDocs.setVisible(false);
            completionDocs.setManaged(false);
            completionDocs.setText("");
            return;
        }
        completionDocs.setText(text);
        completionDocs.setVisible(true);
        completionDocs.setManaged(true);
    }

    private Optional<String> resolveCompletionDoc(Suggestion suggestion) {
        if (suggestion.kind() != Kind.TABLE && suggestion.kind() != Kind.VIEW && suggestion.kind() != Kind.COLUMN) {
            return Optional.empty();
        }
        SchemaCache cache = schemaCache.get();
        if (cache == null || !cache.isReady()) {
            return Optional.empty();
        }
        String catalog = activeCatalog.get();
        Optional<Doc> doc = SqlDocResolver.resolve(
                suggestion.name(),
                Math.max(0, suggestion.name().length() - 1),
                cache,
                catalog,
                "");
        return doc.map(d -> {
            if (d.isTable()) {
                return (suggestion.kind() == Kind.VIEW ? "View" : "Table")
                        + " `" + d.table() + "`"
                        + (d.schema().isBlank() ? "" : " · " + d.schema());
            }
            return "Column `" + d.column() + "` of `" + d.table() + "`"
                    + (d.code().isBlank() ? "" : "\n" + d.code().lines().findFirst().orElse(""));
        });
    }

    /**
     * True when the caret prefix already equals this suggestion's insert text or
     * display name — the user finished typing that token.
     */
    private static boolean isFullyTypedSuggestion(Suggestion suggestion, String prefix) {
        if (suggestion == null || prefix == null || prefix.isEmpty()) {
            return false;
        }
        // Snippets / functions keep placeholders — never treat as "fully typed".
        if (suggestion.kind() == Kind.SNIPPET || suggestion.kind() == Kind.FUNCTION
                || !suggestion.placeholders().isEmpty()) {
            return suggestion.name().equalsIgnoreCase(prefix)
                    && suggestion.insertText().equalsIgnoreCase(prefix);
        }
        if (suggestion.insertText().equalsIgnoreCase(prefix)) {
            return true;
        }
        return suggestion.name() != null && suggestion.name().equalsIgnoreCase(prefix);
    }

    private void showPopupAtCaret() {
        Optional<Bounds> caretBounds = codeArea.getCaretBounds();
        if (caretBounds.isEmpty()) {
            hideCompletions();
            return;
        }
        Bounds caret = caretBounds.get();
        double x = caret.getMinX();
        double y = caret.getMaxY() + 2;

        Screen screen = Screen.getScreensForRectangle(x, y, 1, 1).stream()
                .findFirst()
                .orElse(Screen.getPrimary());
        Rectangle2D visible = screen.getVisualBounds();
        double popupWidth = completionChrome.prefWidth(-1);
        if (popupWidth <= 0) {
            popupWidth = completionList.getPrefWidth();
        }
        double popupHeight = completionChrome.prefHeight(-1);
        if (popupHeight <= 0) {
            popupHeight = completionList.getPrefHeight() + 48;
        }

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
        int start = InlayPadSupport.toDocumentOffset(selected.replaceStart(), activeInlayPads);
        int end = InlayPadSupport.toDocumentOffset(selected.replaceEnd(), activeInlayPads);
        start = Math.max(0, Math.min(start, codeArea.getLength()));
        end = Math.max(start, Math.min(end, codeArea.getLength()));

        String raw = selected.insertText();
        boolean templated = selected.kind() == Kind.SNIPPET
                || selected.kind() == Kind.FUNCTION
                || !selected.placeholders().isEmpty()
                || raw.indexOf('$') >= 0;

        if (templated) {
            SqlSnippetCatalog.AppliedTemplate applied = SqlSnippetCatalog.apply(raw);
            String text = applied.text();
            if (selected.trailingSpace()
                    && (end >= codeArea.getLength() || !Character.isWhitespace(codeArea.getText().charAt(end)))) {
                text = text + " ";
            }
            codeArea.replaceText(start, end, text);
            hideCompletions();
            beginPlaceholderSession(start, applied.ranges());
            return;
        }

        String text = raw;
        if (selected.trailingSpace()
                && (end >= codeArea.getLength() || !Character.isWhitespace(codeArea.getText().charAt(end)))) {
            text = text + " ";
        }
        codeArea.replaceText(start, end, text);
        clearPlaceholderSession();
        hideCompletions();
    }

    private void beginPlaceholderSession(int insertOffset, List<int[]> relativeRanges) {
        if (relativeRanges == null || relativeRanges.isEmpty()) {
            clearPlaceholderSession();
            return;
        }
        List<int[]> absolute = new ArrayList<>(relativeRanges.size());
        for (int[] range : relativeRanges) {
            absolute.add(new int[]{insertOffset + range[0], insertOffset + range[1]});
        }
        pendingPlaceholders = List.copyOf(absolute);
        placeholderIndex = 0;
        selectPlaceholder(0);
    }

    private boolean hasPendingPlaceholders() {
        return placeholderIndex >= 0 && placeholderIndex < pendingPlaceholders.size();
    }

    private void advancePlaceholder(int delta) {
        if (pendingPlaceholders.isEmpty()) {
            clearPlaceholderSession();
            return;
        }
        int next = placeholderIndex + delta;
        if (next < 0 || next >= pendingPlaceholders.size()) {
            clearPlaceholderSession();
            return;
        }
        placeholderIndex = next;
        selectPlaceholder(placeholderIndex);
    }

    private void selectPlaceholder(int index) {
        if (index < 0 || index >= pendingPlaceholders.size()) {
            return;
        }
        int[] range = pendingPlaceholders.get(index);
        int start = Math.max(0, Math.min(range[0], codeArea.getLength()));
        int end = Math.max(start, Math.min(range[1], codeArea.getLength()));
        codeArea.selectRange(start, end);
        codeArea.requestFollowCaret();
    }

    private void clearPlaceholderSession() {
        pendingPlaceholders = List.of();
        placeholderIndex = -1;
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
        cancelActiveCompletion();
        completionPopup.hide();
    }

    /** Identifier fragment immediately before the caret (empty when none). */
    private static String completionPrefixAt(String sql, int caret) {
        if (sql == null || caret <= 0 || caret > sql.length()) {
            return "";
        }
        int end = caret;
        int start = end;
        while (start > 0) {
            char c = sql.charAt(start - 1);
            if (Character.isLetterOrDigit(c) || c == '_') {
                start--;
            } else {
                break;
            }
        }
        return sql.substring(start, end);
    }

    /** IntelliJ-style row: sticky kind icon · name ………… detail */
    private static final class CompletionCell extends ListCell<Suggestion> {

        private final javafx.scene.layout.StackPane iconSlot = new javafx.scene.layout.StackPane();
        private final Label name = new Label();
        private final Label detail = new Label();
        private final HBox root = new HBox(8);

        CompletionCell() {
            iconSlot.getStyleClass().add("sql-completion-icon");
            iconSlot.setMinWidth(18);
            iconSlot.setPrefWidth(18);
            iconSlot.setMaxWidth(18);
            name.getStyleClass().add("sql-completion-name");
            detail.getStyleClass().add("sql-completion-detail");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            root.setAlignment(Pos.CENTER_LEFT);
            root.setPadding(new Insets(0, 8, 0, 4));
            root.getChildren().addAll(iconSlot, name, spacer, detail);
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
            iconSlot.getChildren().setAll(kindIcon(item.kind()));
            name.setText(item.name());
            detail.setText(item.detail() == null ? "" : item.detail());
            setGraphic(root);
        }
    }

    private static Node kindIcon(Kind kind) {
        return switch (kind) {
            case KEYWORD -> Icons.keyword();
            case TABLE -> Icons.table();
            case VIEW -> Icons.view();
            case COLUMN -> Icons.column();
            case SCHEMA -> Icons.schema();
            case INDEX -> Icons.index();
            case FUNCTION -> Icons.function();
            case JOIN -> Icons.join();
            case SNIPPET -> Icons.snippet();
            case PARAMETER -> Icons.parameter();
        };
    }

    // ---------------------------------------------------------------- folding / inlays

    private void measureMonospaceWidth() {
        var text = new javafx.scene.text.Text("M");
        text.setFont(javafx.scene.text.Font.font("JetBrains Mono", 13));
        monospaceCharWidth = Math.max(6.0, text.getLayoutBounds().getWidth());
    }

    private Node buildParagraphGraphic(int index) {
        HBox gutter = new HBox(0);
        gutter.setAlignment(Pos.CENTER_LEFT);
        gutter.getStyleClass().add("sql-gutter");

        StackPane foldSlot = new StackPane();
        foldSlot.getStyleClass().add("sql-fold-slot");
        foldSlot.setMinWidth(14);
        foldSlot.setPrefWidth(14);
        foldSlot.setMaxWidth(14);
        foldSlot.setPickOnBounds(true);

        SqlFoldRegions.Region region = foldsByStart.get(index);
        if (region != null) {
            boolean collapsed = isRegionCollapsed(region, index);
            Label chevron = new Label();
            chevron.setGraphic(collapsed ? Icons.foldCollapsed() : Icons.foldExpanded());
            chevron.getStyleClass().add("sql-fold-chevron");
            chevron.setCursor(Cursor.HAND);
            chevron.setPickOnBounds(true);
            final int openOffset = region.openOffset();
            Runnable toggle = () -> toggleFoldByOpenOffset(openOffset);
            chevron.setOnMouseClicked(event -> {
                toggle.run();
                event.consume();
            });
            foldSlot.setOnMouseClicked(event -> {
                toggle.run();
                event.consume();
            });
            foldSlot.getChildren().setAll(chevron);
        }

        Node lineNo = lineNumbers.apply(index);
        lineNo.setStyle(GUTTER_STYLE);
        gutter.getChildren().addAll(foldSlot, lineNo);
        return gutter;
    }

    private boolean isRegionCollapsed(SqlFoldRegions.Region region, int startParagraph) {
        if (collapsedOpenOffsets.contains(region.openOffset())) {
            return true;
        }
        int next = startParagraph + 1;
        return next < codeArea.getParagraphs().size() && codeArea.isFolded(next);
    }

    private void toggleFoldByOpenOffset(int openOffset) {
        SqlFoldRegions.Region region = findRegionByOpenOffset(openOffset);
        if (region == null || !region.spansMultipleLines()) {
            return;
        }
        int length = codeArea.getLength();
        if (length <= 0) {
            return;
        }
        int open = Math.max(0, Math.min(region.openOffset(), length - 1));
        int close = Math.max(0, Math.min(region.closeOffset(), length - 1));
        int startPar = codeArea.offsetToPosition(open, TwoDimensional.Bias.Forward).getMajor();
        int endPar = codeArea.offsetToPosition(close, TwoDimensional.Bias.Forward).getMajor();
        if (endPar <= startPar) {
            return;
        }

        boolean currentlyCollapsed = (startPar + 1 < codeArea.getParagraphs().size()
                && codeArea.isFolded(startPar + 1))
                || collapsedOpenOffsets.contains(openOffset);

        ignoreFoldDocumentEvents = true;
        try {
            if (currentlyCollapsed) {
                codeArea.unfoldParagraphs(startPar);
                collapsedOpenOffsets.remove(openOffset);
            } else {
                codeArea.foldParagraphs(startPar, endPar);
                collapsedOpenOffsets.add(openOffset);
            }
        } finally {
            armFoldEventSuppress();
        }
        codeArea.recreateParagraphGraphic(startPar);
        Platform.runLater(this::paintInlayOverlay);
    }

    private SqlFoldRegions.Region findRegionByOpenOffset(int openOffset) {
        for (SqlFoldRegions.Region region : foldsByStart.values()) {
            if (region.openOffset() == openOffset) {
                return region;
            }
        }
        for (SqlFoldRegions.Region region : SqlFoldRegions.find(codeArea.getText())) {
            if (region.openOffset() == openOffset) {
                return region;
            }
        }
        return null;
    }

    private void wireFolding() {
        recomputeFoldMap();
        codeArea.multiPlainChanges().successionEnds(java.time.Duration.ofMillis(80)).subscribe(changes -> {
            if (ignoreFoldDocumentEvents || mutatingDocument) {
                return;
            }
            Platform.runLater(this::refreshFoldsAfterEdit);
        });
    }

    private void refreshFoldsAfterEdit() {
        if (ignoreFoldDocumentEvents || mutatingDocument) {
            return;
        }
        // Real edits invalidate paragraph indices — drop all folds and rebuild anchors.
        ignoreFoldDocumentEvents = true;
        try {
            unfoldAllCollapsed();
            collapsedOpenOffsets.clear();
            recomputeFoldMap();
            refreshParagraphGraphics();
        } finally {
            armFoldEventSuppress();
        }
    }

    private void unfoldAllCollapsed() {
        try {
            for (int i = 0; i < codeArea.getParagraphs().size(); i++) {
                if (i + 1 < codeArea.getParagraphs().size()
                        && codeArea.isFolded(i + 1)
                        && !codeArea.isFolded(i)) {
                    codeArea.unfoldParagraphs(i);
                }
            }
        } catch (RuntimeException ignored) {
            // paragraph graph may be mid-update
        }
    }

    private void armFoldEventSuppress() {
        ignoreFoldDocumentEvents = true;
        foldEventSuppress.stop();
        foldEventSuppress.setOnFinished(event -> ignoreFoldDocumentEvents = false);
        foldEventSuppress.playFromStart();
    }

    private void recomputeFoldMap() {
        foldsByStart = SqlFoldRegions.byStartLine(codeArea.getText());
    }

    private void refreshParagraphGraphics() {
        codeArea.setParagraphGraphicFactory(null);
        codeArea.setParagraphGraphicFactory(this::buildParagraphGraphic);
    }

    private void wireInlayHints() {
        inlayDebounce.setOnFinished(event -> runInlayExtraction());
        codeArea.plainTextChanges().subscribe(change -> {
            if (mutatingDocument || ignoreFoldDocumentEvents) {
                return;
            }
            // User typed: drop pad spaces immediately so they never become "real" SQL.
            if (!activeInlayPads.isEmpty()) {
                Platform.runLater(this::stripInlayPadsNow);
            }
            cancelActiveInlay();
            inlayDebounce.stop();
            inlayDebounce.playFromStart();
        });
        codeArea.estimatedScrollYProperty().addListener((obs, o, n) -> paintInlayOverlay());
        codeArea.estimatedScrollXProperty().addListener((obs, o, n) -> paintInlayOverlay());
        inlayLayer.widthProperty().addListener((obs, o, n) -> paintInlayOverlay());
        inlayLayer.heightProperty().addListener((obs, o, n) -> paintInlayOverlay());
        Platform.runLater(this::runInlayExtraction);
    }

    private void stripInlayPadsNow() {
        if (mutatingDocument || activeInlayPads.isEmpty()) {
            return;
        }
        mutatingDocument = true;
        try {
            int logicalCaret = logicalCaret();
            String logical = logicalSql();
            activeInlayPads = List.of();
            inlayLayer.getChildren().clear();
            if (!logical.equals(codeArea.getText())) {
                codeArea.replaceText(logical);
                codeArea.moveTo(Math.max(0, Math.min(logicalCaret, logical.length())));
            }
            lastInlays = List.of();
        } finally {
            mutatingDocument = false;
        }
    }

    private void runInlayExtraction() {
        Task<List<SqlInlayHints.Hint>> previous = activeInlayTask;
        activeInlayTask = null;
        if (previous != null && previous.isRunning()) {
            previous.cancel(true);
        }
        final long generation = inlayGeneration.incrementAndGet();
        final String snapshot = logicalSql();
        Task<List<SqlInlayHints.Hint>> task = new Task<>() {
            @Override
            protected List<SqlInlayHints.Hint> call() {
                if (isCancelled() || generation != inlayGeneration.get()) {
                    return List.of();
                }
                return SqlInlayHints.extract(snapshot);
            }
        };
        activeInlayTask = task;
        task.setOnSucceeded(event -> {
            if (generation != inlayGeneration.get() || task.isCancelled()) {
                return;
            }
            if (!snapshot.equals(logicalSql())) {
                return;
            }
            lastInlays = List.copyOf(task.getValue());
            applyInlayPaddingAndPaint(lastInlays);
        });
        inlayExecutor.execute(task);
    }

    private void cancelActiveInlay() {
        inlayGeneration.incrementAndGet();
        Task<List<SqlInlayHints.Hint>> task = activeInlayTask;
        activeInlayTask = null;
        if (task != null && task.isRunning()) {
            task.cancel(true);
        }
    }

    private void applyInlayPaddingAndPaint(List<SqlInlayHints.Hint> hints) {
        Runnable apply = () -> {
            // Pads shift character offsets — drop folds first so anchors stay valid.
            unfoldAllCollapsed();
            collapsedOpenOffsets.clear();

            String logical = logicalSql();
            int logicalCaretPos = logicalCaret();
            List<InlayPadSupport.HintSpec> specs = new ArrayList<>(hints.size());
            for (SqlInlayHints.Hint hint : hints) {
                specs.add(new InlayPadSupport.HintSpec(
                        hint.offset(),
                        hint.label(),
                        spacesForHintLabel(hint.label())));
            }
            InlayPadSupport.Result result = InlayPadSupport.pad(logical, specs);

            mutatingDocument = true;
            ignoreFoldDocumentEvents = true;
            try {
                activeInlayPads = result.pads();
                if (!result.text().equals(codeArea.getText())) {
                    codeArea.replaceText(result.text());
                    int docCaret = InlayPadSupport.toDocumentOffset(logicalCaretPos, activeInlayPads);
                    codeArea.moveTo(Math.max(0, Math.min(docCaret, result.text().length())));
                }
            } finally {
                mutatingDocument = false;
                armFoldEventSuppress();
            }
            lastHighlighting = SqlSyntaxHighlighter.computeHighlighting(codeArea.getText());
            paintCombinedStyles();
            recomputeFoldMap();
            refreshParagraphGraphics();
            paintInlayOverlay();
        };
        if (Platform.isFxApplicationThread()) {
            apply.run();
        } else {
            Platform.runLater(apply);
        }
    }

    private int spacesForHintLabel(String label) {
        Label probe = new Label(label == null ? "" : label);
        probe.getStyleClass().add("inlay-hint");
        // Ensure CSS metrics apply when the layer is already in a scene.
        if (inlayLayer.getScene() != null) {
            inlayLayer.getChildren().add(probe);
            inlayLayer.applyCss();
            inlayLayer.layout();
            double width = probe.prefWidth(-1);
            inlayLayer.getChildren().remove(probe);
            return Math.max(1, (int) Math.ceil(width / monospaceCharWidth) + 1);
        }
        int chars = label == null ? 1 : label.length();
        return Math.max(1, chars + 2);
    }

    private void paintInlayOverlay() {
        Runnable paint = () -> {
            inlayLayer.getChildren().clear();
            if (activeInlayPads.isEmpty() || codeArea.getLength() == 0) {
                return;
            }
            for (InlayPadSupport.Pad pad : activeInlayPads) {
                int start = pad.offset();
                int end = Math.min(start + pad.spaces(), codeArea.getLength());
                if (start < 0 || start >= codeArea.getLength() || end <= start) {
                    continue;
                }
                int paragraph = codeArea.offsetToPosition(start, TwoDimensional.Bias.Forward).getMajor();
                if (codeArea.isFolded(paragraph)) {
                    continue;
                }
                Optional<Bounds> screenBounds = codeArea.getCharacterBoundsOnScreen(start, end);
                if (screenBounds.isEmpty()) {
                    continue;
                }
                Bounds bounds = screenBounds.get();
                Point2D local = inlayLayer.screenToLocal(bounds.getMinX(), bounds.getMinY());
                if (local == null) {
                    continue;
                }
                Label pill = new Label(pad.label());
                pill.getStyleClass().add("inlay-hint");
                pill.setMouseTransparent(true);
                // Sit inside the reserved space so SQL text stays to the right.
                pill.relocate(local.getX(), local.getY() + 1);
                inlayLayer.getChildren().add(pill);
            }
        };
        if (Platform.isFxApplicationThread()) {
            paint.run();
        } else {
            Platform.runLater(paint);
        }
    }

    // ---------------------------------------------------------------- highlighting / inspections

    private void wireInspectionDebounce() {
        inspectionDebounce.setOnFinished(event -> runInspection());
        codeArea.textProperty().addListener((observable, previous, current) -> {
            if (mutatingDocument) {
                return;
            }
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
        final String snapshot = logicalSql();
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
            if (!snapshot.equals(logicalSql())) {
                return;
            }
            applyInspections(mapInspectionOffsetsToDocument(task.getValue()));
        });
        inspectionExecutor.execute(task);
    }

    /** Inspection offsets are logical; map them onto the padded document for underlines. */
    private List<InspectionIssue> mapInspectionOffsetsToDocument(List<InspectionIssue> issues) {
        if (issues == null || issues.isEmpty() || activeInlayPads.isEmpty()) {
            return issues;
        }
        List<InspectionIssue> mapped = new ArrayList<>(issues.size());
        for (InspectionIssue issue : issues) {
            int start = InlayPadSupport.toDocumentOffset(issue.startOffset(), activeInlayPads);
            int end = InlayPadSupport.toDocumentOffset(issue.endOffset(), activeInlayPads);
            mapped.add(new InspectionIssue(start, Math.max(start + 1, end), issue.message(), issue.severity()));
        }
        return List.copyOf(mapped);
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

    private void wireQuickDocumentation() {
        codeArea.setMouseOverTextDelay(java.time.Duration.ofMillis(500));
        docHideDebounce.setOnFinished(e -> {
            if (!isPointerOverDocPopup()) {
                docPopup.hide();
            }
        });
        docPopup.getContent().forEach(node -> {
            node.setOnMouseEntered(e -> docHideDebounce.stop());
            node.setOnMouseExited(e -> docHideDebounce.playFromStart());
        });
        docPopup.setOnShowPreview(doc -> onShowTablePreview.accept(doc));

        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_BEGIN, event -> {
            docHideDebounce.stop();
            int charIndex = event.getCharacterIndex();
            if (charIndex < 0) {
                return;
            }
            javafx.geometry.Point2D screen = event.getScreenPosition();
            if (screen == null) {
                return;
            }
            scheduleQuickDocumentation(charIndex, screen.getX(), screen.getY());
        });
        codeArea.addEventHandler(MouseOverTextEvent.MOUSE_OVER_TEXT_END, event ->
                docHideDebounce.playFromStart());
        codeArea.addEventFilter(KeyEvent.KEY_TYPED, event -> hideQuickDocumentation());
        codeArea.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hideQuickDocumentation();
            }
        });
        codeArea.caretPositionProperty().addListener((obs, prev, next) -> hideQuickDocumentation());
    }

    private void scheduleQuickDocumentation(int charIndex, double screenX, double screenY) {
        final long generation = docGeneration.incrementAndGet();
        final String sql = logicalSql();
        final int logicalIndex = InlayPadSupport.toLogicalOffset(charIndex, activeInlayPads);
        final SchemaCache cache = schemaCache.get();
        final String catalog = activeCatalog.get();
        final String dataSource = currentDataSourceLabel();
        docExecutor.execute(() -> {
            Optional<Doc> resolved;
            try {
                resolved = SqlDocResolver.resolve(sql, logicalIndex, cache, catalog, dataSource);
            } catch (RuntimeException ex) {
                resolved = Optional.empty();
            }
            Optional<Doc> doc = resolved;
            Platform.runLater(() -> {
                if (generation != docGeneration.get()) {
                    return;
                }
                if (doc.isEmpty()) {
                    docPopup.hide();
                    return;
                }
                docPopup.showDoc(doc.get(), codeArea, screenX, screenY);
            });
        });
    }

    private String currentDataSourceLabel() {
        SessionChoice choice = sessionBox.getValue();
        if (choice == null || choice.label() == null || choice.label().isBlank()) {
            return "\u2014";
        }
        String label = choice.label();
        int sep = label.indexOf('\u2014');
        return sep > 0 ? label.substring(0, sep).strip() : label;
    }

    private boolean isPointerOverDocPopup() {
        if (!docPopup.isShowing() || docPopup.getContent().isEmpty()) {
            return false;
        }
        Node content = docPopup.getContent().getFirst();
        return content.isHover();
    }

    private void hideQuickDocumentation() {
        docGeneration.incrementAndGet();
        docHideDebounce.stop();
        docPopup.hide();
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
