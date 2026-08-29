package com.lazaro.sqlide.ui.components;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlStatementExtractorTest {

    @Test
    @DisplayName("caret in the second statement returns only that statement")
    void picksStatementUnderCaret() {
        String sql = """
                USE warcraft;

                Insert into race (id, name) VALUES (1, 'Human');
                """;
        int caret = sql.indexOf("Insert");
        assertEquals(
                "Insert into race (id, name) VALUES (1, 'Human')",
                SqlStatementExtractor.statementAt(sql, caret));
    }

    @Test
    @DisplayName("semicolons inside strings do not split statements")
    void ignoresSemicolonInString() {
        String sql = "INSERT INTO t VALUES ('a;b'); SELECT 1;";
        int caret = sql.indexOf("SELECT");
        assertEquals("SELECT 1", SqlStatementExtractor.statementAt(sql, caret));
    }

    @Test
    @DisplayName("empty input yields empty statement")
    void emptyInput() {
        assertEquals("", SqlStatementExtractor.statementAt("   ", 0));
    }
}
