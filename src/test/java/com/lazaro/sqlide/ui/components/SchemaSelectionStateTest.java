package com.lazaro.sqlide.ui.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaSelectionStateTest {

    @Test
    void defaultsToPreferredSchemaWhenPresent() {
        SchemaSelectionState state = new SchemaSelectionState();
        state.setAvailableSchemas(List.of("mysql", "app", "shop", "sys", "test"), "app");
        assertEquals(1, state.selectedCount());
        assertEquals(5, state.availableCount());
        assertTrue(state.isSchemaVisible("app"));
        assertFalse(state.isSchemaVisible("mysql"));
    }

    @Test
    void defaultsToAllWhenNoPreferred() {
        SchemaSelectionState state = new SchemaSelectionState();
        state.setAvailableSchemas(List.of("a", "b", "c"), null);
        assertEquals(3, state.selectedCount());
        assertTrue(state.isSchemaVisible("b"));
    }

    @Test
    void keepsSelectionAcrossRefresh() {
        SchemaSelectionState state = new SchemaSelectionState();
        state.setAvailableSchemas(List.of("app", "shop"), "app");
        state.setAvailableSchemas(List.of("app", "shop", "analytics"), "app");
        assertTrue(state.isSchemaVisible("app"));
        assertFalse(state.isSchemaVisible("shop"));
        assertFalse(state.isSchemaVisible("analytics"));
    }

    @Test
    void allAndNone() {
        SchemaSelectionState state = new SchemaSelectionState();
        state.setAvailableSchemas(List.of("a", "b"), "a");
        state.selectAll();
        assertEquals(2, state.selectedCount());
        state.selectNone();
        assertEquals(0, state.selectedCount());
        assertFalse(state.isSchemaVisible("a"));
    }

    @Test
    void restoredSelectionBeatsPreferredOnLoad() {
        SchemaSelectionState state = new SchemaSelectionState();
        state.applyRestoredSelection(List.of("shop", "test"));
        state.setAvailableSchemas(List.of("mysql", "app", "shop", "sys", "test"), "app");
        assertEquals(2, state.selectedCount());
        assertTrue(state.isSchemaVisible("shop"));
        assertTrue(state.isSchemaVisible("test"));
        assertFalse(state.isSchemaVisible("app"));
    }

    @Test
    void restoredEmptyMeansNoneSelected() {
        SchemaSelectionState state = new SchemaSelectionState();
        state.applyRestoredSelection(List.of());
        state.setAvailableSchemas(List.of("a", "b"), "a");
        assertEquals(0, state.selectedCount());
        assertFalse(state.isSchemaVisible("a"));
    }
}
