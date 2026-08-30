package com.lazaro.sqlide.core.json;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonPayloadsTest {

    @Test
    void detectsObjectAndArrayShapes() {
        assertTrue(JsonPayloads.looksLikeJson("{\"a\":1}"));
        assertTrue(JsonPayloads.looksLikeJson("  [1, 2]  "));
        assertFalse(JsonPayloads.looksLikeJson("not json"));
        assertFalse(JsonPayloads.looksLikeJson("{incomplete"));
        assertFalse(JsonPayloads.looksLikeJson(null));
    }

    @Test
    void prettyPrintsWithTwoSpaceIndent() {
        String pretty = JsonPayloads.prettyPrint("{\"name\":\"Ada\",\"ok\":true}");
        assertTrue(pretty.contains("\n  \"name\""));
        assertTrue(pretty.contains("\"Ada\""));
        assertTrue(JsonPayloads.isValidJson("{\"a\":1}"));
        assertFalse(JsonPayloads.isValidJson("{nope"));
    }

    @Test
    void coercesScalarsAndNestedJson() {
        assertEquals(Boolean.TRUE, JsonPayloads.coerceValue("true"));
        assertEquals(42L, JsonPayloads.coerceValue("42"));
        assertEquals(null, JsonPayloads.coerceValue(null));
        Object nested = JsonPayloads.coerceValue("{\"x\":1}");
        assertTrue(nested.toString().contains("x"));
    }
}
