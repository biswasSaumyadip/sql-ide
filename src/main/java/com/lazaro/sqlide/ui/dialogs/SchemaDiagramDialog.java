package com.lazaro.sqlide.ui.dialogs;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.diagram.SchemaDiagramBuilder;
import com.lazaro.sqlide.core.diagram.SchemaDiagramLayout;
import com.lazaro.sqlide.core.diagram.SchemaDiagramModel;
import com.lazaro.sqlide.ui.WorkspaceState;
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
        this(owner, cache, catalog, focusTable, layoutKey(catalog, focusTable), null, onOpenTable);
    }

    public SchemaDiagramDialog(
            Window owner,
            SchemaCache cache,
            String catalog,
            String focusTable,
            String layoutKey,
            WorkspaceState workspace,
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
        if (workspace != null && layoutKey != null && !layoutKey.isBlank()) {
            model = SchemaDiagramLayout.applyPositions(model, workspace.diagramLayout(layoutKey));
        }

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
        if (workspace != null && layoutKey != null && !layoutKey.isBlank()) {
            canvas.setOnLayoutChanged(next ->
                    workspace.saveDiagramLayout(layoutKey, SchemaDiagramLayout.positionsOf(next)));
            canvas.setOnLayoutReset(() -> workspace.clearDiagramLayout(layoutKey));
        }

        getDialogPane().getStyleClass().add("schema-diagram-dialog");
        getDialogPane().setContent(canvas);
        getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        getDialogPane().getStylesheets().add(stylesheet());
        getDialogPane().setPrefSize(1000, 700);
    }

    public static String layoutKey(String hostPort, String catalog, String focusTable) {
        String host = hostPort == null || hostPort.isBlank() ? "local" : hostPort;
        String cat = catalog == null || catalog.isBlank() ? "_" : catalog;
        String focus = focusTable == null || focusTable.isBlank() ? "*" : focusTable;
        return host + "__" + cat + "__" + focus;
    }

    private static String layoutKey(String catalog, String focusTable) {
        return layoutKey("local", catalog, focusTable);
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
