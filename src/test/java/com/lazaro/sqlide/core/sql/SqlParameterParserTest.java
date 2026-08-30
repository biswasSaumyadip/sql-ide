package com.lazaro.sqlide.core.sql;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlParameterParserTest {

    @Test
    void findsNamedAndPositional() {
        var params = SqlParameterParser.find("SELECT * FROM t WHERE a = :id AND b = ? AND c = :id");
        assertEquals(2, params.size());
        assertEquals("id", params.get(0).name());
        assertEquals(SqlParameterParser.Kind.POSITIONAL, params.get(1).kind());
    }

    @Test
    void ignoresPlaceholdersInsideStrings() {
        assertTrue(SqlParameterParser.find("SELECT ':' , '?' FROM t").isEmpty());
    }

    @Test
    void substitutesValues() {
        String sql = SqlParameterParser.substitute(
                "SELECT * FROM t WHERE a = :name AND b = ?",
                Map.of("name", "Ada"),
                List.of("1"));
        assertEquals("SELECT * FROM t WHERE a = 'Ada' AND b = 1", sql);
    }
}
