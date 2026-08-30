package com.lazaro.sqlide.ui.dialogs;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.diagram.SchemaDiagramBuilder;
import com.lazaro.sqlide.core.diagram.SchemaDiagramLayout;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel;
import com.lazaro.sqlide.ui.diagram.SchemaDiagramCanvas;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.stage.StageStyle;
import javafx.stage.Window;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * Modal schema visualization (DataGrip-style ER diagram).
 */
public final class SchemaDiagramDialog extends Dialog<Void> {

    public SchemaDiagramDialog(
            Window owner,
            SchemaCache cache,
            String catalog,
            String focusTable,
            Consumer<SchemaNode> onOpenTable) {
        Objects.requireNonNull(cache, "cache");
        setTitle(titleFor(catalog, focusTable));
        setHeaderText(null);
        initOwner(owner);
        initStyle(StageStyle.DECORATED);
        setResizable(true);

        SchemaDiagramModel model = focusTable == null || focusTable.isBlank()
                ? SchemaDiagramBuilder.buildCatalog(cache, catalog)
                : SchemaDiagramBuilder.buildNeighborhood(cache, catalog, focusTable);
        model = SchemaDiagramLayout.layout(model);

        SchemaDiagramCanvas canvas = new SchemaDiagramCanvas();
        canvas.setPrefSize(960, 640);
        canvas.setMinSize(640, 420);
        canvas.setModel(model);
        canvas.setOnOpenTable(table -> {
            if (onOpenTable == null) {
                return;
            }
            cache.findTable(table.name(), table.catalog().isBlank() ? catalog : table.catalog())
                    .ifPresent(onOpenTable);
        });

        getDialogPane().getStyleClass().add("schema-diagram-dialog");
        getDialogPane().setContent(canvas);
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        getDialogPane().getStylesheets().add(stylesheet());
        getDialogPane().setPrefSize(1000, 700);
    }

    private static String titleFor(String catalog, String focusTable) {
        if (focusTable != null && !focusTable.isBlank()) {
            if (catalog != null && !catalog.isBlank()) {
                return "Diagrams — " + catalog + "." + focusTable;
            }
            return "Diagrams — " + focusTable;
        }
        if (catalog != null && !catalog.isBlank()) {
            return "Diagrams — " + catalog;
        }
        return "Diagrams — Schema";
    }

    private static String stylesheet() {
        return Objects.requireNonNull(
                        SchemaDiagramDialog.class.getResource("/com/lazaro/sqlide/css/schema-diagram.css"),
                        "schema-diagram.css is missing from the classpath")
                .toExternalForm();
    }
}
