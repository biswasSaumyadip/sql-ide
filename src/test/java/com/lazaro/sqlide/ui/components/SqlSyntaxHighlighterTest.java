package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.ui.components.SqlSyntaxHighlighter.Token;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSyntaxHighlighterTest {

    @Test
    @DisplayName("the core statement keywords are recognised regardless of case")
    void recognisesKeywords() {
        String sql = "select id from users where id = 1 join orders on 1 = 1";

        List<String> keywords = textOf(sql, SqlSyntaxHighlighter.KEYWORD);

        assertTrue(keywords.containsAll(List.of("select", "from", "where", "join", "on")),
                "got " + keywords);
    }

    @Test
    @DisplayName("INSERT, UPDATE and DELETE are keywords too")
    void recognisesWriteKeywords() {
        assertTrue(textOf("INSERT INTO t VALUES (1)", SqlSyntaxHighlighter.KEYWORD).contains("INSERT"));
        assertTrue(textOf("UPDATE t SET a = 1", SqlSyntaxHighlighter.KEYWORD).contains("UPDATE"));
        assertTrue(textOf("DELETE FROM t", SqlSyntaxHighlighter.KEYWORD).contains("DELETE"));
    }

    @Test
    @DisplayName("a keyword inside a string literal is not highlighted as code")
    void keywordsInsideStringsAreNotKeywords() {
        String sql = "SELECT 'select from where' AS label";

        assertEquals(List.of("'select from where'"), textOf(sql, SqlSyntaxHighlighter.STRING));
        assertEquals(List.of("SELECT", "AS"), textOf(sql, SqlSyntaxHighlighter.KEYWORD));
    }

    @Test
    @DisplayName("a keyword inside a comment is not highlighted as code")
    void keywordsInsideCommentsAreNotKeywords() {
        String lineComment = "-- select everything\nSELECT 1";
        assertEquals(List.of("-- select everything"), textOf(lineComment, SqlSyntaxHighlighter.COMMENT));
        assertEquals(List.of("SELECT"), textOf(lineComment, SqlSyntaxHighlighter.KEYWORD));

        String blockComment = "/* delete\n   from */ SELECT 1";
        assertEquals(List.of("/* delete\n   from */"), textOf(blockComment, SqlSyntaxHighlighter.COMMENT));
        assertEquals(List.of("SELECT"), textOf(blockComment, SqlSyntaxHighlighter.KEYWORD));
    }

    @Test
    @DisplayName("escaped quotes keep the literal intact")
    void handlesEscapedQuotes() {
        String sql = "SELECT 'it''s fine' FROM t";

        assertEquals(List.of("'it''s fine'"), textOf(sql, SqlSyntaxHighlighter.STRING));
        assertEquals(List.of("SELECT", "FROM"), textOf(sql, SqlSyntaxHighlighter.KEYWORD));
    }

    @Test
    @DisplayName("functions are only recognised when actually called")
    void recognisesFunctionCalls() {
        String sql = "SELECT COUNT(id), max_value FROM t";

        assertEquals(List.of("COUNT"), textOf(sql, SqlSyntaxHighlighter.FUNCTION));
        assertFalse(textOf(sql, SqlSyntaxHighlighter.FUNCTION).contains("max_value"));
    }

    @Test
    @DisplayName("numbers, operators and punctuation are classified")
    void classifiesLiteralsAndSymbols() {
        String sql = "SELECT 1, 2.5 FROM t WHERE a >= 3;";

        assertEquals(List.of("1", "2.5", "3"), textOf(sql, SqlSyntaxHighlighter.NUMBER));
        assertEquals(List.of(">="), textOf(sql, SqlSyntaxHighlighter.OPERATOR));
        assertEquals(List.of(",", ";"), textOf(sql, SqlSyntaxHighlighter.PUNCTUATION));
    }

    @Test
    @DisplayName("identifiers merely containing a keyword are left alone")
    void doesNotMatchInsideIdentifiers() {
        assertTrue(textOf("SELECT selected_on FROM t", SqlSyntaxHighlighter.KEYWORD)
                .containsAll(List.of("SELECT", "FROM")));
        assertFalse(textOf("SELECT selected_on FROM t", SqlSyntaxHighlighter.KEYWORD).contains("selected_on"));
    }

    @Test
    @DisplayName("tokens never overlap and stay inside the document")
    void tokensAreWellFormed() {
        String sql = "SELECT COUNT(*) FROM t WHERE name LIKE 'a%' -- note\n";

        int previousEnd = 0;
        for (Token token : SqlSyntaxHighlighter.tokenize(sql)) {
            assertTrue(token.start() >= previousEnd, "tokens overlap at " + token);
            assertTrue(token.end() <= sql.length(), "token runs past the end: " + token);
            previousEnd = token.end();
        }
    }

    @Test
    @DisplayName("spans always cover the document exactly, including when empty")
    void spansCoverWholeDocument() {
        assertEquals(0, SqlSyntaxHighlighter.computeHighlighting("").length());
        assertEquals(0, SqlSyntaxHighlighter.computeHighlighting(null).length());

        String sql = "SELECT 1 FROM t;";
        assertEquals(sql.length(), SqlSyntaxHighlighter.computeHighlighting(sql).length());
    }

    private static List<String> textOf(String sql, String styleClass) {
        return SqlSyntaxHighlighter.tokenize(sql).stream()
                .filter(token -> token.styleClass().equals(styleClass))
                .map(token -> sql.substring(token.start(), token.end()))
                .toList();
    }
}
