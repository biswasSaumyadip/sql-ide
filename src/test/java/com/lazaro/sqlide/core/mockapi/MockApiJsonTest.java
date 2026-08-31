package com.lazaro.sqlide.core.mockapi;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.ResultColumn;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Types;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MockApiJsonTest {

    @Test
    void serializesNumbersBooleansAndIsoTimestamps() {
        QueryResult result = QueryResult.ofRows(
                List.of("id", "price", "active", "created", "name"),
                List.of(List.of("42", "19.50", "true", "2024-03-15 09:30:00", "Ada")),
                1L,
                false,
                List.of(
                        new ResultColumn("id", "INT", Types.INTEGER, true, false),
                        new ResultColumn("price", "DECIMAL", Types.DECIMAL, false, false),
                        new ResultColumn("active", "BOOLEAN", Types.BOOLEAN, false, false),
                        new ResultColumn("created", "TIMESTAMP", Types.TIMESTAMP, false, false),
                        new ResultColumn("name", "VARCHAR", Types.VARCHAR, false, false)));

        List<Map<String, Object>> rows = MockApiJson.toRowMaps(result);
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.getFirst();
        assertEquals(42, row.get("id"));
        assertInstanceOf(Number.class, row.get("id"));
        assertEquals(new BigDecimal("19.50"), row.get("price"));
        assertEquals(Boolean.TRUE, row.get("active"));
        assertEquals("2024-03-15T09:30:00", row.get("created"));
        assertEquals("Ada", row.get("name"));

        String json = MockApiJson.toJsonArray(result);
        assertTrue(json.contains("\"id\":42"));
        assertTrue(json.contains("\"price\":19.50") || json.contains("\"price\":19.5"));
        assertTrue(json.contains("\"active\":true"));
        assertTrue(json.contains("2024-03-15T09:30:00"));
        assertTrue(!json.contains("\n"));
    }

    @Test
    void preservesSqlNull() {
        List<String> row = new java.util.ArrayList<>();
        row.add(null);
        QueryResult result = QueryResult.ofRows(
                List.of("note"),
                List.of(row),
                1L,
                false,
                List.of(new ResultColumn("note", "TEXT", Types.VARCHAR, false, false)));
        assertNull(MockApiJson.toRowMaps(result).getFirst().get("note"));
        assertTrue(MockApiJson.toJsonArray(result).contains("null"));
    }

    @Test
    void formatsDateAndOffsetTimestamps() {
        assertEquals("2024-01-02", MockApiJson.coerceTemporal("2024-01-02"));
        assertEquals("2024-01-02T03:04:05", MockApiJson.coerceTemporal("2024-01-02T03:04:05"));
        assertTrue(MockApiJson.coerceTemporal("2024-01-02T03:04:05Z").contains("2024-01-02T03:04:05"));
    }
}
