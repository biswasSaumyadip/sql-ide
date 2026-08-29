package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Kind;
import com.lazaro.sqlide.ui.autocomplete.SqlAutocompleteEngine.Suggestion;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Popup;
import javafx.stage.Screen;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.reactfx.Subscription;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Task;
import javafx.scene.Node;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import java.util.function.Supplier;

/**
 * SQL editor built on a RichTextFX {@link CodeArea}, with line numbers, regex-driven
 * syntax highlighting and schema-aware autocomplete.
 */
public final class SqlEditorPane extends BorderPane {

    private static final Duration HIGHLIGHT_DELAY = Duration.ofMillis(120);
    private static final Duration AUTOCOMPLETE_DELAY = Duration.ofMillis(45);
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
    private final ExecutorService highlightExecutor;
    private final Subscription highlightSubscription;
    private final Popup completionPopup = new Popup();
    private final ListView<Suggestion> completionList = new ListView<>();
    private Subscription autocompleteSubscription;

    private Supplier<SchemaCache> schemaCache = SchemaCache::new;
    private SqlAutocompleteEngine engine = new SqlAutocompleteEngine(new SchemaCache());
    /** Set when we insert '.' ourselves during chain-completion, so KEY_TYPED does not duplicate it. */
    private boolean suppressNextDotTyped;

    public SqlEditorPane() {
        this("");
    }

    public SqlEditorPane(String initialSql) {
        highlightExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sqlide-highlighter");
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
                .successionEnds(HIGHLIGHT_DELAY)
                .retainLatestUntilLater(highlightExecutor)
                .supplyTask(this::computeHighlightingAsync)
                .awaitLatest(codeArea.multiPlainChanges())
                .filterMap(attempt -> attempt.isSuccess() ? Optional.of(attempt.get()) : Optional.empty())
                .subscribe(this::applyHighlighting);

        configureAutocompletePopup();
        wireAutocomplete();

        getStyleClass().add("sql-editor-pane");
        getStylesheets().add(stylesheet());
        setCenter(new VirtualizedScrollPane<>(codeArea));

        if (initialSql != null && !initialSql.isEmpty()) {
            setSql(initialSql);
        }
    }

    // ---------------------------------------------------------------- public API

    /** Swaps the schema snapshot used for completions. Safe to call at any time. */
    public void setSchemaCache(Supplier<SchemaCache> schemaCache) {
        this.schemaCache = schemaCache == null ? SchemaCache::new : schemaCache;
        this.engine = new SqlAutocompleteEngine(this.schemaCache.get());
    }

    public void refreshAutocompleteEngine() {
        this.engine = new SqlAutocompleteEngine(schemaCache.get());
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

    public String getSql() {
        return codeArea.getText();
    }

    public void setSql(String sql) {
        codeArea.replaceText(Objects.requireNonNullElse(sql, ""));
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
        highlightExecutor.shutdownNow();
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
                .successionEnds(AUTOCOMPLETE_DELAY)
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
        engine = new SqlAutocompleteEngine(schemaCache.get());
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

    // ---------------------------------------------------------------- highlighting

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
        if (spans.length() == codeArea.getLength()) {
            codeArea.setStyleSpans(0, spans);
        }
    }

    private static String stylesheet() {
        return Objects.requireNonNull(
                        SqlEditorPane.class.getResource("/com/lazaro/sqlide/css/sql-editor.css"),
                        "sql-editor.css is missing from the classpath")
                .toExternalForm();
    }
}
