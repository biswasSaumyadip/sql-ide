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

    @Test
    @DisplayName("statements() splits a multi-statement script")
    void splitsAllStatements() {
        String sql = "USE app; SELECT 1; INSERT INTO t VALUES (1);";
        assertEquals(
                java.util.List.of("USE app", "SELECT 1", "INSERT INTO t VALUES (1)"),
                SqlStatementExtractor.statements(sql));
    }

    @Test
    @DisplayName("statements() keeps semicolons inside strings")
    void statementsIgnoresQuotedSemicolons() {
        assertEquals(
                java.util.List.of("INSERT INTO t VALUES ('a;b')", "SELECT 1"),
                SqlStatementExtractor.statements("INSERT INTO t VALUES ('a;b'); SELECT 1;"));
    }

    @Test
    @DisplayName("rangeAt hugs the statement including its trailing semicolon")
    void rangeCoversStatementAndSemicolon() {
        String sql = """
                USE warcraft;

                Insert into race (id, name) VALUES (1, 'Human');
                """;
        int caret = sql.indexOf("Insert");
        SqlStatementExtractor.Span span = SqlStatementExtractor.rangeAt(sql, caret);
        assertEquals("Insert into race (id, name) VALUES (1, 'Human');",
                sql.substring(span.start(), span.end()));
    }

    @Test
    @DisplayName("rangeAt ignores semicolons inside strings")
    void rangeIgnoresQuotedSemicolon() {
        String sql = "INSERT INTO t VALUES ('a;b'); SELECT 1;";
        int caret = sql.indexOf("SELECT");
        SqlStatementExtractor.Span span = SqlStatementExtractor.rangeAt(sql, caret);
        assertEquals("SELECT 1;", sql.substring(span.start(), span.end()));
    }
}
