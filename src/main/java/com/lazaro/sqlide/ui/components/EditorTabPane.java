package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.SchemaCache;
import com.lazaro.sqlide.core.db.SchemaNode;
import com.lazaro.sqlide.core.db.ScriptResult;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.stage.FileChooser;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One SQL editor per tab. Tabs close individually and can be dragged to reorder.
 *
 * <p>A tab is dirty when its text differs from the last saved (or loaded) content,
 * shown as a leading dot in the title. Closing a dirty tab asks before discarding.
 */
public final class EditorTabPane extends TabPane {

    private static final String DIRTY_MARK = "\u25CF ";

    private final ReadOnlyObjectWrapper<SqlEditorPane> activeEditor = new ReadOnlyObjectWrapper<>();
    private int untitledCounter;
    private Supplier<SchemaCache> schemaCache = SchemaCache::new;
    private Supplier<String> activeCatalog = () -> null;

    public EditorTabPane() {
        getStyleClass().add("editor-tabs");
        setTabClosingPolicy(TabClosingPolicy.ALL_TABS);
        // Built-in reordering; no custom drag handling required.
        setTabDragPolicy(TabDragPolicy.REORDER);
        setMinHeight(90);

        getSelectionModel().selectedItemProperty().addListener((observable, previous, current) ->
                activeEditor.set(current instanceof QueryTab tab ? tab.editor() : null));

        newTab();
    }

    // ---------------------------------------------------------------- public API

    public SqlEditorPane activeEditor() {
        return activeEditor.get();
    }

    /** Tracks the editor of the selected tab, for binding a status bar to it. */
    public ReadOnlyObjectProperty<SqlEditorPane> activeEditorProperty() {
        return activeEditor.getReadOnlyProperty();
    }

    /** Opens a new empty tab and selects it. */
    public void newTab() {
        QueryTab tab = new QueryTab("query-" + (++untitledCounter) + ".sql", schemaCache, activeCatalog);
        getTabs().add(tab);
        getSelectionModel().select(tab);
        tab.editor().requestFocus();
    }

    /**
     * Opens a new query tab filled with generated SQL and selects the first
     * placeholder so the user can overwrite it immediately.
     */
    public void openGeneratedSql(SqlTemplateGenerator.Template template) {
        if (template == null || template.sql().isBlank()) {
            newTab();
            return;
        }
        String title = template.tabTitle();
        if (title.startsWith("query-new") || title.startsWith("query-modify")) {
            // Keep titles unique when opening several templates in a row.
            int dot = title.lastIndexOf('.');
            String base = dot > 0 ? title.substring(0, dot) : title;
            String ext = dot > 0 ? title.substring(dot) : ".sql";
            title = base + "-" + (++untitledCounter) + ext;
        } else {
            untitledCounter++;
        }
        QueryTab tab = new QueryTab(title, schemaCache, activeCatalog);
        getTabs().add(tab);
        getSelectionModel().select(tab);
        tab.editor().setSqlSelecting(template.sql(), template.placeholder());
        tab.editor().requestFocus();
    }

    /** Supplies the live schema snapshot to every editor for autocomplete. */
    public void setSchemaCache(Supplier<SchemaCache> schemaCache) {
        this.schemaCache = schemaCache == null ? SchemaCache::new : schemaCache;
        getTabs().stream()
                .filter(QueryTab.class::isInstance)
                .map(QueryTab.class::cast)
                .forEach(tab -> tab.editor().setSchemaCache(this.schemaCache));
    }

    /** Supplies the session's active database so table completions stay scoped to it. */
    public void setActiveCatalog(Supplier<String> activeCatalog) {
        this.activeCatalog = activeCatalog == null ? () -> null : activeCatalog;
        getTabs().stream()
                .filter(QueryTab.class::isInstance)
                .map(QueryTab.class::cast)
                .forEach(tab -> tab.editor().setActiveCatalog(this.activeCatalog));
    }

    /** Rebuilds each query editor's autocomplete engine after a schema refresh. */
    public void refreshAutocompleteEngines() {
        getTabs().stream()
                .filter(QueryTab.class::isInstance)
                .map(QueryTab.class::cast)
                .forEach(tab -> tab.editor().refreshAutocompleteEngine());
    }

    /** Opens (or focuses) an object-viewer tab for the given table/view node. */
    public void openObjectViewer(SchemaNode node) {
        if (node == null) {
            return;
        }
        for (Tab tab : getTabs()) {
            if (tab instanceof ObjectTab objectTab && objectTab.matches(node)) {
                getSelectionModel().select(objectTab);
                return;
            }
        }
        ObjectTab tab = new ObjectTab(node);
        getTabs().add(tab);
        getSelectionModel().select(tab);
    }

    /**
     * Opens (or focuses) a table data editor tab.
     *
     * @param scriptRunner executes SQL scripts asynchronously (load / submit)
     * @param background   FX Task executor
     */
    public void openTableData(
            SchemaNode node,
            String qualifiedName,
            List<String> primaryKeyColumns,
            Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner,
            Executor background) {
        if (node == null) {
            return;
        }
        for (Tab tab : getTabs()) {
            if (tab instanceof DataTab dataTab && dataTab.matches(node)) {
                getSelectionModel().select(dataTab);
                return;
            }
        }
        DataTab tab = new DataTab(node, qualifiedName, primaryKeyColumns, scriptRunner, background);
        getTabs().add(tab);
        getSelectionModel().select(tab);
    }

    /** Closes the selected tab, honouring the unsaved-changes prompt. */
    public void closeActiveTab() {
        Tab tab = getSelectionModel().getSelectedItem();
        if (tab instanceof QueryTab queryTab) {
            if (queryTab.confirmClose()) {
                getTabs().remove(queryTab);
                queryTab.dispose();
            }
        } else if (tab instanceof DataTab dataTab) {
            if (dataTab.confirmClose()) {
                getTabs().remove(dataTab);
            }
        } else if (tab != null) {
            getTabs().remove(tab);
        }
    }

    /** Writes the selected tab to disk, asking for a location when it has none. */
    public void saveActiveTab(Window owner) {
        if (getSelectionModel().getSelectedItem() instanceof QueryTab tab) {
            tab.save(owner);
        }
    }

    /** Inserts text into the active editor, e.g. a table name from the schema tree. */
    public void insertIntoActiveEditor(String text) {
        SqlEditorPane editor = activeEditor();
        if (editor != null) {
            editor.insertAtCaret(text);
            editor.requestFocus();
        }
    }

    /**
     * Runs the unsaved-changes prompt for every dirty tab, as when the window is
     * closing.
     *
     * @return {@code false} if the user cancelled at any prompt
     */
    public boolean confirmCloseAll() {
        for (Tab tab : List.copyOf(getTabs())) {
            if (tab instanceof QueryTab queryTab) {
                getSelectionModel().select(queryTab);
                if (!queryTab.confirmClose()) {
                    return false;
                }
            } else if (tab instanceof DataTab dataTab) {
                getSelectionModel().select(dataTab);
                if (!dataTab.confirmClose()) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Releases the highlighting thread of every open editor. */
    public void dispose() {
        getTabs().stream()
                .filter(QueryTab.class::isInstance)
                .map(QueryTab.class::cast)
                .forEach(QueryTab::dispose);
    }

    // ---------------------------------------------------------------- tab

    private static final class QueryTab extends Tab {

        private final SqlEditorPane editor = new SqlEditorPane();
        private final SimpleBooleanProperty dirty = new SimpleBooleanProperty(false);

        private String title;
        private String baseline = "";
        private Path file;

        QueryTab(String title, Supplier<SchemaCache> schemaCache, Supplier<String> activeCatalog) {
            this.title = title;
            editor.setSchemaCache(schemaCache);
            editor.setActiveCatalog(activeCatalog);
            setContent(editor);

            editor.textProperty().addListener((observable, previous, current) ->
                    dirty.set(!baseline.equals(current)));
            dirty.addListener((observable, wasDirty, isDirty) -> refreshTitle());
            refreshTitle();

            setOnCloseRequest(event -> {
                if (!confirmClose()) {
                    event.consume();
                }
            });
            setOnClosed(event -> dispose());
        }

        SqlEditorPane editor() {
            return editor;
        }

        void dispose() {
            editor.dispose();
        }

        private void refreshTitle() {
            setText(dirty.get() ? DIRTY_MARK + title : title);
        }

        /** @return {@code true} when it is safe to close */
        boolean confirmClose() {
            if (!dirty.get()) {
                return true;
            }
            ButtonType save = new ButtonType("Save", ButtonBar.ButtonData.YES);
            ButtonType discard = new ButtonType("Discard", ButtonBar.ButtonData.NO);

            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Unsaved changes");
            alert.setHeaderText("\"%s\" has unsaved changes.".formatted(title));
            alert.setContentText("Save before closing?");
            alert.getButtonTypes().setAll(save, discard, ButtonType.CANCEL);
            alert.initOwner(getTabPane() == null ? null : getTabPane().getScene().getWindow());

            Optional<ButtonType> choice = alert.showAndWait();
            if (choice.isEmpty() || choice.get() == ButtonType.CANCEL) {
                return false;
            }
            if (choice.get() == save) {
                save(getTabPane() == null ? null : getTabPane().getScene().getWindow());
                return !dirty.get();
            }
            return true;
        }

        void save(Window owner) {
            Path target = file;
            if (target == null) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("Save query");
                chooser.setInitialFileName(title);
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("SQL", "*.sql"),
                        new FileChooser.ExtensionFilter("All files", "*.*"));
                java.io.File chosen = chooser.showSaveDialog(owner);
                if (chosen == null) {
                    return;
                }
                target = chosen.toPath();
            }

            String content = editor.getSql();
            try {
                Files.writeString(target, content, StandardCharsets.UTF_8);
            } catch (IOException e) {
                Alert alert = new Alert(Alert.AlertType.ERROR);
                alert.setTitle("Save failed");
                alert.setHeaderText("Could not write " + target);
                alert.setContentText(e.getMessage());
                alert.initOwner(owner);
                alert.showAndWait();
                return;
            }

            file = target;
            title = target.getFileName().toString();
            baseline = content;
            dirty.set(false);
            refreshTitle();
        }
    }

    private static final class ObjectTab extends Tab {

        private final String objectKey;
        private final ObjectViewerPane viewer = new ObjectViewerPane();

        ObjectTab(SchemaNode node) {
            this.objectKey = keyOf(node);
            setText(node.name());
            setContent(viewer);
            viewer.show(node);
            getStyleClass().add("object-viewer-tab");
        }

        boolean matches(SchemaNode node) {
            return objectKey.equals(keyOf(node));
        }

        private static String keyOf(SchemaNode node) {
            String catalog = Objects.requireNonNullElse(node.metadata(SchemaNode.META_CATALOG), "");
            return catalog + "/" + node.name();
        }
    }

    private static final class DataTab extends Tab {

        private final TableDataEditorPane editor;
        private final String baseTitle;

        DataTab(
                SchemaNode node,
                String qualifiedName,
                List<String> primaryKeyColumns,
                Function<List<String>, CompletableFuture<ScriptResult>> scriptRunner,
                Executor background) {
            this.editor = new TableDataEditorPane(node, qualifiedName, primaryKeyColumns, scriptRunner, background);
            this.baseTitle = node.name() + " data";
            setText(baseTitle);
            setContent(editor);
            getStyleClass().add("table-data-tab");
            setOnCloseRequest(event -> {
                if (!confirmClose()) {
                    event.consume();
                }
            });
            editor.setOnDirtyChanged(this::refreshTitle);
            refreshTitle();
        }

        boolean matches(SchemaNode node) {
            return editor.matches(node);
        }

        boolean confirmClose() {
            boolean ok = editor.confirmClose();
            refreshTitle();
            return ok;
        }

        private void refreshTitle() {
            setText(editor.isDirty() ? DIRTY_MARK + baseTitle : baseTitle);
        }
    }
}
