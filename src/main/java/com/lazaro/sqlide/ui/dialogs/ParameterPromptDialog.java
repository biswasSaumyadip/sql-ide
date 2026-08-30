package com.lazaro.sqlide.ui.dialogs;

import atlantafx.base.theme.Styles;
import com.lazaro.sqlide.core.sql.SqlParameterParser;
import com.lazaro.sqlide.core.sql.SqlParameterParser.Kind;
import com.lazaro.sqlide.core.sql.SqlParameterParser.Parameter;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Prompts for {@code :name} / {@code ?} values before running a statement.
 */
public final class ParameterPromptDialog extends Dialog<Map<String, String>> {

    private static final ButtonType RUN = new ButtonType("Run", ButtonBar.ButtonData.OK_DONE);

    private final List<Parameter> parameters;
    private final List<TextField> fields = new ArrayList<>();

    public ParameterPromptDialog(Window owner, List<Parameter> parameters) {
        this(owner, parameters, Map.of());
    }

    public ParameterPromptDialog(Window owner, List<Parameter> parameters, Map<String, String> defaults) {
        this.parameters = List.copyOf(Objects.requireNonNullElse(parameters, List.of()));
        Map<String, String> seed = defaults == null ? Map.of() : defaults;
        initStyle(StageStyle.UNDECORATED);
        setTitle("Query Parameters");
        setHeaderText(null);
        if (owner != null) {
            initOwner(owner);
        }

        getDialogPane().getButtonTypes().setAll(RUN, ButtonType.CANCEL);
        getDialogPane().setContent(buildContent(seed));
        getDialogPane().getStyleClass().add("parameter-prompt-dialog");
        getDialogPane().setPrefWidth(440);

        Node runButton = getDialogPane().lookupButton(RUN);
        if (runButton != null) {
            runButton.getStyleClass().add(Styles.ACCENT);
        }

        setResultConverter(button -> {
            if (button != RUN) {
                return null;
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (int i = 0; i < this.parameters.size(); i++) {
                Parameter parameter = this.parameters.get(i);
                String text = fields.get(i).getText();
                if (parameter.kind() == Kind.NAMED) {
                    values.put(parameter.name(), text);
                    values.put(parameter.name().toLowerCase(Locale.ROOT), text);
                } else {
                    values.put("?" + parameter.index(), text);
                }
            }
            return values;
        });

        if (!fields.isEmpty()) {
            javafx.application.Platform.runLater(() -> fields.getFirst().requestFocus());
        }
    }

    private VBox buildContent(Map<String, String> defaults) {
        Label title = new Label("Query Parameters");
        title.getStyleClass().add("parameter-prompt-title");

        Button close = new Button("\u00D7");
        close.getStyleClass().addAll(Styles.FLAT, "parameter-prompt-close");
        close.setFocusTraversable(false);
        close.setOnAction(e -> {
            setResult(null);
            close();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(8, title, spacer, close);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("parameter-prompt-header");
        enableDrag(header);

        Label instruction = new Label(
                "Enter values for " + parameters.size() + " parameter(s)");
        instruction.getStyleClass().add("parameter-prompt-instruction");

        Separator separator = new Separator();
        separator.getStyleClass().add("parameter-prompt-separator");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(12);
        ColumnConstraints c0 = new ColumnConstraints();
        c0.setMinWidth(88);
        c0.setPrefWidth(100);
        ColumnConstraints c1 = new ColumnConstraints();
        c1.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(c0, c1);

        for (int i = 0; i < parameters.size(); i++) {
            Parameter parameter = parameters.get(i);
            Label name = new Label(parameter.displayName());
            name.getStyleClass().add("parameter-prompt-name");
            GridPane.setValignment(name, VPos.CENTER);

            TextField field = new TextField();
            field.setPromptText("NULL for SQL NULL");
            String preset = defaultFor(parameter, defaults);
            if (preset != null) {
                field.setText(preset);
            }
            fields.add(field);

            grid.add(name, 0, i);
            grid.add(field, 1, i);
        }

        VBox root = new VBox(12, header, instruction, separator, grid);
        root.setPadding(new Insets(14, 16, 8, 16));
        root.getStyleClass().add("parameter-prompt-root");
        return root;
    }

    private static String defaultFor(Parameter parameter, Map<String, String> defaults) {
        if (defaults == null || defaults.isEmpty()) {
            return null;
        }
        if (parameter.kind() == Kind.NAMED) {
            String value = defaults.get(parameter.name());
            if (value == null) {
                value = defaults.get(parameter.name().toLowerCase(Locale.ROOT));
            }
            return value;
        }
        return defaults.get("?" + parameter.index());
    }

    private void enableDrag(Node handle) {
        final double[] drag = new double[2];
        handle.setOnMousePressed(e -> {
            Window window = getDialogPane().getScene().getWindow();
            drag[0] = e.getScreenX() - window.getX();
            drag[1] = e.getScreenY() - window.getY();
        });
        handle.setOnMouseDragged(e -> {
            Window window = getDialogPane().getScene().getWindow();
            window.setX(e.getScreenX() - drag[0]);
            window.setY(e.getScreenY() - drag[1]);
        });
    }

    /**
     * Applies dialog values to SQL. Returns empty when the user cancels.
     */
    public static Optional<String> promptAndSubstitute(Window owner, String sql) {
        return promptAndSubstitute(owner, sql, Map.of());
    }

    public static Optional<String> promptAndSubstitute(Window owner, String sql, Map<String, String> defaults) {
        List<Parameter> parameters = SqlParameterParser.find(sql);
        if (parameters.isEmpty()) {
            return Optional.of(sql);
        }
        ParameterPromptDialog dialog = new ParameterPromptDialog(owner, parameters, defaults);
        Optional<Map<String, String>> values = dialog.showAndWait();
        if (values.isEmpty()) {
            return Optional.empty();
        }
        Map<String, String> map = values.get();
        List<String> positional = new ArrayList<>();
        for (Parameter parameter : parameters) {
            if (parameter.kind() == Kind.POSITIONAL) {
                positional.add(map.getOrDefault("?" + parameter.index(), ""));
            }
        }
        Map<String, String> named = new LinkedHashMap<>();
        for (Parameter parameter : parameters) {
            if (parameter.kind() == Kind.NAMED) {
                named.put(parameter.name(), map.getOrDefault(parameter.name(), ""));
            }
        }
        return Optional.of(SqlParameterParser.substitute(sql, named, positional));
    }
}
