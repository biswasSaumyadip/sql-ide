package com.lazaro.sqlide.ui.components;

import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableValue;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.layout.BorderPane;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.LineNumberFactory;
import org.fxmisc.richtext.model.StyleSpans;
import org.reactfx.Subscription;

import java.time.Duration;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;

/**
 * SQL editor built on a RichTextFX {@link CodeArea}, with line numbers and
 * regex-driven syntax highlighting.
 *
 * <p>Highlighting is computed on a background thread and applied back on the
 * JavaFX Application Thread, so a large script never stalls typing. Call
 * {@link #dispose()} when the editor is discarded to release that thread.
 */
public final class SqlEditorPane extends BorderPane {

    private static final Duration HIGHLIGHT_DELAY = Duration.ofMillis(120);

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

    public SqlEditorPane() {
        this("");
    }

    public SqlEditorPane(String initialSql) {
        highlightExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "sqlide-highlighter");
            thread.setDaemon(true);
            return thread;
        });

        // RichTextFX styles the gutter labels itself, which outranks a stylesheet
        // rule; an inline style is the only reliable way to recolour them.
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

        getStyleClass().add("sql-editor-pane");
        getStylesheets().add(stylesheet());
        setCenter(new VirtualizedScrollPane<>(codeArea));

        if (initialSql != null && !initialSql.isEmpty()) {
            setSql(initialSql);
        }
    }

    // ---------------------------------------------------------------- public API

    /**
     * The SQL the user intends to run: the selection when there is one, otherwise
     * the entire buffer. This is what the Execute action should send to the server.
     */
    public String getEffectiveSql() {
        String selection = codeArea.getSelectedText();
        return selection == null || selection.isBlank() ? codeArea.getText() : selection;
    }

    public String getSql() {
        return codeArea.getText();
    }

    public void setSql(String sql) {
        codeArea.replaceText(Objects.requireNonNullElse(sql, ""));
        codeArea.moveTo(0);
        codeArea.requestFollowCaret();
    }

    /** Appends text at the caret, e.g. a table name dragged in from the schema tree. */
    public void insertAtCaret(String text) {
        if (text != null && !text.isEmpty()) {
            codeArea.insertText(codeArea.getCaretPosition(), text);
        }
    }

    public void clear() {
        codeArea.clear();
    }

    /** The underlying editor, for callers that need to attach key bindings. */
    public CodeArea getCodeArea() {
        return codeArea;
    }

    /** Editor content, for observing modifications. */
    public ObservableValue<String> textProperty() {
        return codeArea.textProperty();
    }

    /** Caret position rendered for a status bar, e.g. {@code Ln 3, Col 12}. */
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

    /** Stops the highlighting thread. Call once when the editor is no longer used. */
    public void dispose() {
        highlightSubscription.unsubscribe();
        highlightExecutor.shutdownNow();
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
        // The document may have shrunk while the task was in flight; applying spans
        // of the wrong total length would throw.
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
