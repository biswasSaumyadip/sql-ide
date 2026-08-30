package com.lazaro.sqlide.ui.dialogs;

import com.lazaro.sqlide.ui.WorkspaceState;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.VBox;

/**
 * Application settings. Persists through {@link WorkspaceState}.
 */
public final class SettingsDialog extends Dialog<Boolean> {

    private final WorkspaceState state;
    private final CheckBox lowerKeywords = new CheckBox("Lowercase SQL keywords on insert");
    private final CheckBox autoQuote = new CheckBox("Auto-quote reserved identifiers");
    private final CheckBox preserveCasing = new CheckBox("Preserve database object casing");

    /**
     * @return {@code true} when the user saved changes
     */
    public SettingsDialog(WorkspaceState state) {
        this.state = state;
        setTitle("Settings");
        setHeaderText("SqlIDE preferences");
        setResizable(false);

        getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        ((javafx.scene.control.Button) getDialogPane().lookupButton(ButtonType.OK)).setText("Save");

        getDialogPane().getStyleClass().add("settings-dialog");
        getDialogPane().setContent(buildForm());
        loadFromState();

        setResultConverter(button -> {
            if (button == ButtonType.OK) {
                saveToState();
                return Boolean.TRUE;
            }
            return Boolean.FALSE;
        });
    }

    private VBox buildForm() {
        Label section = new Label("SQL completion");
        section.getStyleClass().add("settings-section-title");

        Label hint = new Label(
                "These options apply when you accept an autocomplete suggestion.");
        hint.getStyleClass().add("settings-hint");
        hint.setWrapText(true);

        lowerKeywords.setTooltip(new Tooltip(
                "Insert SELECT / FROM / WHERE as select / from / where."));
        autoQuote.setTooltip(new Tooltip(
                "Wrap reserved words (order, user, …) in dialect quotes when inserting identifiers."));
        preserveCasing.setTooltip(new Tooltip(
                "Keep table/column names exactly as the database returned them. "
                        + "When off, identifiers are lowercased."));

        VBox box = new VBox(10,
                section,
                hint,
                new Separator(),
                lowerKeywords,
                autoQuote,
                preserveCasing);
        box.setPadding(new Insets(12, 16, 8, 16));
        box.setPrefWidth(420);
        return box;
    }

    private void loadFromState() {
        lowerKeywords.setSelected(state.lowerKeywords());
        autoQuote.setSelected(state.autoQuoteReserved());
        preserveCasing.setSelected(state.preserveDbCasing());
    }

    private void saveToState() {
        state.saveLowerKeywords(lowerKeywords.isSelected());
        state.saveAutoQuoteReserved(autoQuote.isSelected());
        state.savePreserveDbCasing(preserveCasing.isSelected());
    }
}
