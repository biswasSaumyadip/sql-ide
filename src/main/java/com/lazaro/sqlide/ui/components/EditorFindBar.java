package com.lazaro.sqlide.ui.components;

import atlantafx.base.theme.Styles;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import org.fxmisc.richtext.CodeArea;

/**
 * Compact find / replace strip for {@link SqlEditorPane}, DataGrip-style.
 */
public final class EditorFindBar extends VBox {

    private final CodeArea codeArea;
    private final TextField findField = new TextField();
    private final TextField replaceField = new TextField();
    private final CheckBox matchCase = new CheckBox("Match case");
    private final Label status = new Label();
    private final HBox replaceRow = new HBox(4);

    private int lastMatchStart = -1;

    public EditorFindBar(CodeArea codeArea) {
        this.codeArea = codeArea;
        getStyleClass().add("editor-find-bar");
        setSpacing(4);
        setPadding(new Insets(4, 8, 4, 8));

        findField.setPromptText("Find");
        findField.getStyleClass().add("editor-find-field");
        findField.setPrefWidth(180);
        findField.setOnAction(event -> findNext(true));
        findField.textProperty().addListener((observable, previous, next) -> {
            lastMatchStart = -1;
            if (isVisible() && next != null && !next.isEmpty()) {
                findNext(true);
            } else if (isVisible()) {
                status.setText("");
            }
        });
        matchCase.selectedProperty().addListener((observable, previous, next) -> {
            lastMatchStart = -1;
            if (isVisible() && findField.getText() != null && !findField.getText().isEmpty()) {
                findNext(true);
            }
        });

        replaceField.setPromptText("Replace");
        replaceField.getStyleClass().add("editor-find-field");
        replaceField.setPrefWidth(180);
        replaceField.setOnAction(event -> replaceCurrent());

        Button prev = textAction("\u25B2", "Previous match (Shift+F3)", () -> findNext(false));
        Button next = textAction("\u25BC", "Next match (F3)", () -> findNext(true));
        Button close = textAction("\u00D7", "Close (Esc)", this::hide);

        matchCase.getStyleClass().add("editor-find-checkbox");
        status.getStyleClass().add("editor-find-status");

        HBox findRow = new HBox(4,
                new Label("Find"),
                findField,
                prev,
                next,
                matchCase,
                status,
                close);
        findRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(findField, Priority.SOMETIMES);

        Button replace = new Button("Replace");
        replace.getStyleClass().addAll(Styles.FLAT, "editor-find-text-button");
        replace.setOnAction(event -> replaceCurrent());

        Button replaceAll = new Button("Replace all");
        replaceAll.getStyleClass().addAll(Styles.FLAT, "editor-find-text-button");
        replaceAll.setOnAction(event -> replaceAll());

        replaceRow.setAlignment(Pos.CENTER_LEFT);
        replaceRow.getChildren().addAll(new Label("Replace"), replaceField, replace, replaceAll);
        replaceRow.setVisible(false);
        replaceRow.setManaged(false);

        getChildren().addAll(findRow, replaceRow);
        setVisible(false);
        setManaged(false);

        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hide();
                event.consume();
            } else if (new KeyCodeCombination(KeyCode.F3, KeyCombination.SHIFT_DOWN).match(event)) {
                findNext(false);
                event.consume();
            } else if (event.getCode() == KeyCode.F3) {
                findNext(true);
                event.consume();
            }
        });
    }

    public void show(boolean replaceMode) {
        setVisible(true);
        setManaged(true);
        replaceRow.setVisible(replaceMode);
        replaceRow.setManaged(replaceMode);
        String selected = codeArea.getSelectedText();
        if (selected != null && !selected.isEmpty() && !selected.contains("\n")) {
            findField.setText(selected);
        }
        findField.requestFocus();
        findField.selectAll();
        if (!findField.getText().isEmpty()) {
            findNext(true);
        } else {
            status.setText("");
        }
    }

    public void hide() {
        setVisible(false);
        setManaged(false);
        status.setText("");
        lastMatchStart = -1;
        codeArea.requestFocus();
    }

    public boolean isShowing() {
        return isVisible();
    }

    /**
     * Moves to the next/previous match. Opens the bar if it is hidden and a needle
     * (selection or prior text) is available.
     */
    public void findNext(boolean forward) {
        if (!isShowing()) {
            show(false);
            return;
        }
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            status.setText("");
            return;
        }

        String haystack = codeArea.getText();
        boolean caseSensitive = matchCase.isSelected();
        String searchIn = caseSensitive ? haystack : haystack.toLowerCase();
        String seek = caseSensitive ? needle : needle.toLowerCase();
        int total = countMatches(searchIn, seek);
        if (total == 0) {
            status.setText("No matches");
            lastMatchStart = -1;
            return;
        }

        int from;
        if (forward) {
            from = codeArea.getCaretPosition();
            if (codeArea.getSelectedText() != null && !codeArea.getSelectedText().isEmpty()) {
                from = Math.max(from, codeArea.getSelection().getEnd());
            }
            from = Math.max(from, lastMatchStart + 1);
        } else {
            from = codeArea.getCaretPosition() - 1;
            if (codeArea.getSelectedText() != null && !codeArea.getSelectedText().isEmpty()) {
                from = codeArea.getSelection().getStart() - 1;
            }
        }

        boolean wrapped = false;
        int found = forward
                ? searchIn.indexOf(seek, Math.max(0, from))
                : searchIn.lastIndexOf(seek, Math.max(0, from));
        if (found < 0) {
            found = forward ? searchIn.indexOf(seek) : searchIn.lastIndexOf(seek);
            wrapped = true;
        }

        lastMatchStart = found;
        codeArea.selectRange(found, found + needle.length());
        codeArea.requestFollowCaret();

        int index = matchIndexAt(searchIn, seek, found);
        String base = index + " of " + total;
        status.setText(wrapped ? base + " (wrapped)" : base);
    }

    private void replaceCurrent() {
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            return;
        }
        String selected = codeArea.getSelectedText();
        if (selected != null && equalsNeedle(selected, needle)) {
            String replacement = replaceField.getText() == null ? "" : replaceField.getText();
            codeArea.replaceSelection(replacement);
        }
        findNext(true);
    }

    private void replaceAll() {
        String needle = findField.getText();
        if (needle == null || needle.isEmpty()) {
            return;
        }
        String replacement = replaceField.getText() == null ? "" : replaceField.getText();
        String text = codeArea.getText();
        int count = 0;
        StringBuilder out = new StringBuilder();
        if (matchCase.isSelected()) {
            int from = 0;
            while (from < text.length()) {
                int at = text.indexOf(needle, from);
                if (at < 0) {
                    out.append(text, from, text.length());
                    break;
                }
                out.append(text, from, at).append(replacement);
                from = at + needle.length();
                count++;
            }
        } else {
            String lower = text.toLowerCase();
            String seek = needle.toLowerCase();
            int from = 0;
            while (from < text.length()) {
                int at = lower.indexOf(seek, from);
                if (at < 0) {
                    out.append(text, from, text.length());
                    break;
                }
                out.append(text, from, at).append(replacement);
                from = at + needle.length();
                count++;
            }
        }
        if (count > 0) {
            codeArea.replaceText(out.toString());
        }
        status.setText(count == 0 ? "No matches" : count + " replaced");
        lastMatchStart = -1;
    }

    private boolean equalsNeedle(String selected, String needle) {
        return matchCase.isSelected() ? selected.equals(needle) : selected.equalsIgnoreCase(needle);
    }

    private static int countMatches(String haystack, String seek) {
        if (seek.isEmpty()) {
            return 0;
        }
        int count = 0;
        int from = 0;
        while (from <= haystack.length() - seek.length()) {
            int at = haystack.indexOf(seek, from);
            if (at < 0) {
                break;
            }
            count++;
            from = at + Math.max(1, seek.length());
        }
        return count;
    }

    /** 1-based index of the match that starts at {@code start}, or 0 if unknown. */
    private static int matchIndexAt(String haystack, String seek, int start) {
        if (seek.isEmpty() || start < 0) {
            return 0;
        }
        int index = 0;
        int from = 0;
        while (from <= haystack.length() - seek.length()) {
            int at = haystack.indexOf(seek, from);
            if (at < 0) {
                break;
            }
            index++;
            if (at == start) {
                return index;
            }
            from = at + Math.max(1, seek.length());
        }
        return index;
    }

    private static Button textAction(String text, String tip, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().addAll(Styles.FLAT, "editor-find-icon-button");
        button.setTooltip(new Tooltip(tip));
        button.setOnAction(event -> action.run());
        return button;
    }
}
