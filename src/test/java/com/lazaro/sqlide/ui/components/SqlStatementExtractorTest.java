package com.lazaro.sqlide.ui.components;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    @DisplayName("custom DELIMITER keeps procedure body as one statement")
    void procedureBodyNotSplitOnInternalSemicolons() {
        String sql = """
                DELIMITER $$
                CREATE PROCEDURE foo()
                BEGIN
                  SELECT 1;
                  INSERT INTO t VALUES ('a;b');
                END$$
                DELIMITER ;
                SELECT 2;
                """;
        int caret = sql.indexOf("CREATE PROCEDURE");
        assertEquals(
                """
                CREATE PROCEDURE foo()
                BEGIN
                  SELECT 1;
                  INSERT INTO t VALUES ('a;b');
                END""".strip(),
                SqlStatementExtractor.statementAt(sql, caret));

        SqlStatementExtractor.Span span = SqlStatementExtractor.rangeAt(sql, caret);
        String highlighted = sql.substring(span.start(), span.end());
        assertEquals(
                """
                CREATE PROCEDURE foo()
                BEGIN
                  SELECT 1;
                  INSERT INTO t VALUES ('a;b');
                END$$""".strip(),
                highlighted);

        assertEquals(
                java.util.List.of(
                        """
                        CREATE PROCEDURE foo()
                        BEGIN
                          SELECT 1;
                          INSERT INTO t VALUES ('a;b');
                        END""".strip(),
                        "SELECT 2"),
                SqlStatementExtractor.statements(sql));
    }

    @Test
    @DisplayName("DELIMITER command is not sent as SQL")
    void delimiterLineIsMeta() {
        String sql = "DELIMITER $$\nSELECT 1$$\nDELIMITER ;\n";
        int caret = sql.indexOf("DELIMITER");
        assertEquals("", SqlStatementExtractor.statementAt(sql, caret));
        assertEquals(java.util.List.of("SELECT 1"), SqlStatementExtractor.statements(sql));
    }

    @Test
    @DisplayName("slash delimiter and quoted delimiter text")
    void slashDelimiterAndQuotedFalsePositive() {
        String sql = """
                DELIMITER //
                CREATE FUNCTION bar() RETURNS INT
                BEGIN
                  RETURN 1;
                END//
                DELIMITER ;
                INSERT INTO t VALUES ('END//');
                """;
        int caret = sql.indexOf("CREATE FUNCTION");
        assertTrue(SqlStatementExtractor.statementAt(sql, caret).startsWith("CREATE FUNCTION"));
        assertTrue(SqlStatementExtractor.statementAt(sql, caret).endsWith("END"));
        assertFalse(SqlStatementExtractor.statementAt(sql, caret).endsWith("//"));

        SqlStatementExtractor.Span span = SqlStatementExtractor.rangeAt(sql, caret);
        assertTrue(sql.substring(span.start(), span.end()).endsWith("END//"));

        assertEquals(
                java.util.List.of(
                        """
                        CREATE FUNCTION bar() RETURNS INT
                        BEGIN
                          RETURN 1;
                        END""".strip(),
                        "INSERT INTO t VALUES ('END//')"),
                SqlStatementExtractor.statements(sql));
    }

    @Test
    @DisplayName("DELIMITER inside a comment does not change the terminator")
    void delimiterInCommentIsIgnored() {
        String sql = """
                -- DELIMITER $$
                SELECT 1;
                SELECT 2;
                """;
        java.util.List<String> parts = SqlStatementExtractor.statements(sql);
        assertEquals(2, parts.size());
        assertTrue(parts.getFirst().contains("SELECT 1"));
        assertEquals("SELECT 2", parts.get(1));
    }

    @Test
    @DisplayName("executableRanges skip DELIMITER commands")
    void executableRangesSkipDelimiter() {
        String sql = """
                DELIMITER $$
                CREATE PROCEDURE foo()
                BEGIN
                    SELECT 1;
                END$$
                DELIMITER ;
                SELECT 2;
                """;
        java.util.List<SqlStatementExtractor.Span> ranges = SqlStatementExtractor.executableRanges(sql);
        assertEquals(2, ranges.size());
        assertTrue(sql.substring(ranges.get(0).start(), ranges.get(0).end()).contains("CREATE PROCEDURE"));
        assertEquals("SELECT 2;", sql.substring(ranges.get(1).start(), ranges.get(1).end()));
        assertTrue(ranges.stream().noneMatch(span ->
                sql.substring(span.start(), span.end()).strip().toUpperCase().startsWith("DELIMITER")));
    }
}
