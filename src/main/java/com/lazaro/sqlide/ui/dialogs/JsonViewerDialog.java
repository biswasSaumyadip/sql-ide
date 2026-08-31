package com.lazaro.sqlide.ui.dialogs;

import com.lazaro.sqlide.core.json.JsonPayloads;
import com.lazaro.sqlide.ui.components.JsonSyntaxHighlighter;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import org.fxmisc.flowless.VirtualizedScrollPane;
import org.fxmisc.richtext.CodeArea;

/**
 * Modal pretty-printer for a JSON cell value (AtlantaFX / app dark chrome).
 */
public final class JsonViewerDialog extends Dialog<Void> {

    private final CodeArea codeArea = new CodeArea();
    private final Label status = new Label();

    public JsonViewerDialog(Window owner, String rawJson) {
        setTitle("JSON");
        setHeaderText(null);
        initOwner(owner);
        initStyle(StageStyle.UTILITY);
        setResizable(true);

        String pretty = JsonPayloads.prettyPrint(rawJson);
        boolean valid = JsonPayloads.isValidJson(rawJson);

        codeArea.getStyleClass().add("json-viewer-code");
        codeArea.replaceText(pretty);
        codeArea.setEditable(false);
        codeArea.setWrapText(false);
        codeArea.setStyleSpans(0, JsonSyntaxHighlighter.computeHighlighting(pretty));
        codeArea.getStylesheets().add(stylesheet());

        Button copy = new Button("Copy to Clipboard");
        copy.getStyleClass().add("labelled-button");
        copy.setOnAction(event -> copyToClipboard(codeArea.getText()));

        status.getStyleClass().add("json-viewer-status");
        status.setText(valid ? "Formatted JSON" : "Could not parse — showing raw text");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox top = new HBox(8, status, spacer, copy);
        top.setPadding(new Insets(8, 12, 8, 12));
        top.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        top.getStyleClass().add("json-viewer-toolbar");

        VirtualizedScrollPane<CodeArea> scroll = new VirtualizedScrollPane<>(
                codeArea, ScrollPane.ScrollBarPolicy.AS_NEEDED, ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroll.getStyleClass().add("json-viewer-scroll");

        BorderPane root = new BorderPane();
        root.setTop(top);
        root.setCenter(scroll);
        root.getStyleClass().add("json-viewer-root");
        root.setPrefSize(640, 480);

        getDialogPane().getStyleClass().add("json-viewer-dialog");
        getDialogPane().setContent(root);
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        getDialogPane().getStylesheets().add(stylesheet());
    }

    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text == null ? "" : text);
        Clipboard.getSystemClipboard().setContent(content);
        status.setText("Copied to clipboard");
    }

    private static String stylesheet() {
        return JsonViewerDialog.class
                .getResource("/com/lazaro/sqlide/css/json-viewer.css")
                .toExternalForm();
    }
}
