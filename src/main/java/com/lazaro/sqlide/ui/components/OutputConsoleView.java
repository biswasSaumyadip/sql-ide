package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.ScriptResult;
import javafx.application.Platform;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

/**
 * JetBrains-style Output console: a persistent log of statement runs, timings,
 * and errors living as its own result-pane tab.
 */
public final class OutputConsoleView extends BorderPane {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_CHARS = 400_000;

    private final TextArea log = new TextArea();

    public OutputConsoleView() {
        getStyleClass().add("output-console");
        log.getStyleClass().add("output-console-text");
        log.setEditable(false);
        log.setWrapText(true);
        log.setFocusTraversable(true);

        MenuItem clear = new MenuItem("Clear");
        clear.setOnAction(event -> clear());
        MenuItem copyAll = new MenuItem("Copy All");
        copyAll.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(log.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        log.setContextMenu(new ContextMenu(copyAll, new SeparatorMenuItem(), clear));
        setCenter(log);

        appendInfo("Output");
        appendRaw("Statement results and messages appear here.");
    }

    public void clear() {
        log.clear();
    }

    public void appendRunning(List<String> statements) {
        appendSeparator();
        appendInfo("Running\u2026");
        if (statements != null) {
            for (String statement : statements) {
                if (statement != null && !statement.isBlank()) {
                    appendSql(statement.strip());
                }
            }
        }
    }

    public void appendCancelling() {
        appendInfo("Cancelling\u2026");
    }

    public void appendScript(ScriptResult script) {
        if (script == null || script.isEmpty()) {
            appendInfo("Nothing executed");
            return;
        }
        List<QueryResult> results = script.results();
        for (int i = 0; i < results.size(); i++) {
            QueryResult result = results.get(i);
            String prefix = results.size() > 1 ? "[" + (i + 1) + "/" + results.size() + "] " : "";
            if (result.isError()) {
                appendError(prefix + result.summary());
            } else {
                appendOk(prefix + result.successMessage());
            }
        }
        if (results.size() > 1) {
            appendInfo(script.summary());
        }
    }

    public void appendResult(QueryResult result) {
        if (result == null) {
            return;
        }
        if (result.isError()) {
            appendError(result.summary());
        } else {
            appendOk(result.successMessage());
        }
    }

    public void appendInfo(String message) {
        appendLine("INFO", message);
    }

    public void appendOk(String message) {
        appendLine("OK", message);
    }

    public void appendError(String message) {
        appendLine("ERROR", message);
    }

    public void appendSql(String sql) {
        appendLine("SQL", collapseWhitespace(sql));
    }

    /** Visible blank line before a new log block. */
    public void appendSeparator() {
        if (!log.getText().isEmpty()) {
            appendRaw("");
        }
    }

    private void appendLine(String level, String message) {
        String stamp = LocalDateTime.now().format(TIME);
        appendRaw("[" + stamp + "] " + level + "  " + Objects.requireNonNullElse(message, ""));
    }

    private void appendRaw(String line) {
        Runnable write = () -> {
            if (!log.getText().isEmpty()) {
                log.appendText("\n");
            }
            log.appendText(line);
            trimIfNeeded();
            log.positionCaret(log.getLength());
            log.setScrollTop(Double.MAX_VALUE);
        };
        if (Platform.isFxApplicationThread()) {
            write.run();
        } else {
            Platform.runLater(write);
        }
    }

    private void trimIfNeeded() {
        String text = log.getText();
        if (text.length() <= MAX_CHARS) {
            return;
        }
        int cut = text.length() - MAX_CHARS;
        int newline = text.indexOf('\n', cut);
        log.deleteText(0, newline > 0 ? newline + 1 : cut);
    }

    private static String collapseWhitespace(String sql) {
        String flat = sql.replace('\r', ' ').replace('\n', ' ').replaceAll(" +", " ").strip();
        if (flat.length() > 500) {
            return flat.substring(0, 497) + "\u2026";
        }
        return flat;
    }
}
