package com.lazaro.sqlide.ui.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaSelectionRegistryTest {

    @Test
    void keepsIndependentSelectionsPerConnection() {
        SchemaSelectionRegistry registry = new SchemaSelectionRegistry();
        SchemaSelectionState local = registry.forConnection("local-mysql");
        SchemaSelectionState prod = registry.forConnection("prod-mysql");

        local.setAvailableSchemas(List.of("app", "mysql", "sys"), "app");
        prod.setAvailableSchemas(List.of("orders", "inventory", "mysql"), "orders");

        assertEquals(1, local.selectedCount());
        assertEquals(1, prod.selectedCount());
        assertTrue(local.isSchemaVisible("app"));
        assertFalse(local.isSchemaVisible("orders"));
        assertTrue(prod.isSchemaVisible("orders"));
        assertFalse(prod.isSchemaVisible("app"));

        local.selectAll();
        assertEquals(3, local.selectedCount());
        assertEquals(1, prod.selectedCount());
    }

    @Test
    void removeDropsOnlyThatConnection() {
        SchemaSelectionRegistry registry = new SchemaSelectionRegistry();
        registry.forConnection("a").setAvailableSchemas(List.of("x"), "x");
        registry.forConnection("b").setAvailableSchemas(List.of("y"), "y");
        registry.remove("a");
        assertFalse(registry.hasConnection("a"));
        assertTrue(registry.hasConnection("b"));
    }
}
