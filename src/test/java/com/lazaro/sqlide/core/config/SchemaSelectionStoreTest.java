package com.lazaro.sqlide.core.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaSelectionStoreTest {

    @TempDir
    Path tempDir;

    private SchemaSelectionStore store;
    private Path file;

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("schema-selections.json");
        store = new SchemaSelectionStore(file);
    }

    @Test
    void loadCreatesEmptyFileWhenMissing() throws Exception {
        Map<String, List<String>> loaded = store.loadAll();
        assertTrue(loaded.isEmpty());
        assertTrue(Files.exists(file));
    }

    @Test
    void saveAndReloadPreservesSelection() {
        store.saveSelection("conn-1", List.of("warcraft", "mysql"));
        store.saveSelection("conn-2", List.of("app"));

        SchemaSelectionStore again = new SchemaSelectionStore(file);
        assertEquals(List.of("warcraft", "mysql"), again.loadSelection("conn-1"));
        assertEquals(List.of("app"), again.loadSelection("conn-2"));
        assertTrue(again.hasSelection("conn-1"));
        assertFalse(again.hasSelection("missing"));
    }

    @Test
    void saveEmptySelectionIsRemembered() {
        store.saveSelection("conn-1", List.of());
        assertTrue(store.hasSelection("conn-1"));
        assertTrue(store.loadSelection("conn-1").isEmpty());
    }

    @Test
    void removeDropsConnection() {
        store.saveSelection("conn-1", List.of("a"));
        store.saveSelection("conn-2", List.of("b"));
        store.remove("conn-1");
        assertFalse(store.hasSelection("conn-1"));
        assertEquals(List.of("b"), store.loadSelection("conn-2"));
    }
}
