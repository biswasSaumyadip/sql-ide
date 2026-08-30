package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleSelectAnalyzerTest {

    @Test
    void acceptsSimpleSelect() {
        var info = SimpleSelectAnalyzer.tryAnalyze("SELECT id, name FROM users WHERE id > 1 ORDER BY name LIMIT 10");
        assertTrue(info.isPresent());
        assertEquals("users", info.get().table());
    }

    @Test
    void acceptsQualifiedTable() {
        var info = SimpleSelectAnalyzer.tryAnalyze("select * from sales.orders");
        assertTrue(info.isPresent());
        assertEquals("sales", info.get().catalog());
        assertEquals("orders", info.get().table());
    }

    @Test
    void rejectsJoinsAndAggregates() {
        assertTrue(SimpleSelectAnalyzer.tryAnalyze("SELECT * FROM a JOIN b ON a.id = b.id").isEmpty());
        assertTrue(SimpleSelectAnalyzer.tryAnalyze("SELECT COUNT(*) FROM users").isEmpty());
        assertTrue(SimpleSelectAnalyzer.tryAnalyze("SELECT * FROM users GROUP BY id").isEmpty());
    }
}
