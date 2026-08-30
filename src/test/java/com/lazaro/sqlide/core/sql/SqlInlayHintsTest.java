package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlInlayHintsTest {

    @Test
    void mapsInsertValuesToColumnNames() {
        String sql = "INSERT INTO users (id, name) VALUES (1, 'Ada')";
        List<SqlInlayHints.Hint> hints = SqlInlayHints.extract(sql);
        assertEquals(2, hints.size());
        assertEquals("id", hints.get(0).label());
        assertEquals("name", hints.get(1).label());
        assertTrue(hints.get(0).offset() < hints.get(1).offset());
        assertEquals('1', sql.charAt(hints.get(0).offset()));
        assertEquals('\'', sql.charAt(hints.get(1).offset()));
    }

    @Test
    void mapsJdbcParametersToComparedColumns() {
        String sql = "SELECT * FROM users WHERE id = ? AND name = ?";
        List<SqlInlayHints.Hint> hints = SqlInlayHints.extract(sql);
        assertEquals(2, hints.size());
        assertEquals("id", hints.get(0).label());
        assertEquals("name", hints.get(1).label());
        assertEquals('?', sql.charAt(hints.get(0).offset()));
        assertEquals('?', sql.charAt(hints.get(1).offset()));
    }

    @Test
    void skipsIncompleteSql() {
        assertTrue(SqlInlayHints.extract("INSERT INTO users (id,").isEmpty());
    }

    @Test
    void multiRowInsertHintsEachRow() {
        String sql = """
                INSERT INTO t (a, b) VALUES
                (1, 2),
                (3, 4)
                """;
        List<SqlInlayHints.Hint> hints = SqlInlayHints.extract(sql);
        assertEquals(4, hints.size());
        assertEquals(List.of("a", "b", "a", "b"),
                hints.stream().map(SqlInlayHints.Hint::label).toList());
    }

    @Test
    void emptySqlYieldsNoHints() {
        assertTrue(SqlInlayHints.extract("").isEmpty());
        assertTrue(SqlInlayHints.extract(null).isEmpty());
    }
}
