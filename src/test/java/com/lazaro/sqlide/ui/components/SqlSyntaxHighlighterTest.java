package com.lazaro.sqlide.ui.components;

import com.lazaro.sqlide.core.db.ConnectionConfig;
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
    @DisplayName("CALL is highlighted as a command, not left as an identifier")
    void recognisesCallKeyword() {
        assertTrue(textOf("CALL greet_user()", SqlSyntaxHighlighter.KEYWORD).contains("CALL"));
        assertTrue(textOf("call greet_user()", SqlSyntaxHighlighter.KEYWORD).contains("call"));
        assertEquals(List.of("'CALL me'"), textOf("SELECT 'CALL me'", SqlSyntaxHighlighter.STRING));
        assertFalse(textOf("SELECT 'CALL me'", SqlSyntaxHighlighter.KEYWORD).contains("CALL"));
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

    @Test
    @DisplayName("JSON object literals inside single quotes get nested token styles")
    void injectsJsonObjectHighlighting() {
        String sql = "SELECT '{\"name\":\"Ada\",\"id\":42,\"ok\":true}'";
        var spans = SqlSyntaxHighlighter.computeHighlighting(sql);
        assertEquals(sql.length(), spans.length());

        assertTrue(hasStyleAt(sql, spans, "name", JsonSyntaxHighlighter.KEY));
        assertTrue(hasStyleAt(sql, spans, "\"Ada\"", JsonSyntaxHighlighter.STRING));
        assertTrue(hasStyleAt(sql, spans, "42", JsonSyntaxHighlighter.NUMBER));
        assertTrue(hasStyleAt(sql, spans, "true", JsonSyntaxHighlighter.LITERAL));
        assertTrue(hasStyleAt(sql, spans, "{", SqlSyntaxHighlighter.INJECTED_LANGUAGE));
        assertFalse(hasStyleAt(sql, spans, "name", SqlSyntaxHighlighter.STRING));
    }

    @Test
    @DisplayName("JSON array literals inside single quotes are injected too")
    void injectsJsonArrayHighlighting() {
        String sql = "INSERT INTO t VALUES ('[1, false, null]');";
        var spans = SqlSyntaxHighlighter.computeHighlighting(sql);
        assertEquals(sql.length(), spans.length());
        assertTrue(hasStyleAt(sql, spans, "1", JsonSyntaxHighlighter.NUMBER));
        assertTrue(hasStyleAt(sql, spans, "false", JsonSyntaxHighlighter.LITERAL));
        assertTrue(hasStyleAt(sql, spans, "[", SqlSyntaxHighlighter.INJECTED_LANGUAGE));
    }

    @Test
    @DisplayName("plain string literals stay sql-string without injection")
    void plainStringsAreNotInjected() {
        String sql = "SELECT 'hello' FROM t";
        var spans = SqlSyntaxHighlighter.computeHighlighting(sql);
        assertTrue(hasStyleAt(sql, spans, "'hello'", SqlSyntaxHighlighter.STRING));
        assertFalse(hasStyleAt(sql, spans, "hello", SqlSyntaxHighlighter.INJECTED_LANGUAGE));
    }

    @Test
    @DisplayName("double-quoted identifiers are never treated as JSON injection")
    void doubleQuotedStringsAreNotInjected() {
        String sql = "SELECT \"{\\\"a\\\":1}\" FROM t";
        var spans = SqlSyntaxHighlighter.computeHighlighting(sql);
        assertFalse(hasStyleAt(sql, spans, "{", SqlSyntaxHighlighter.INJECTED_LANGUAGE));
    }

    @Test
    @DisplayName("findJsonLiteralAt resolves the string under the caret")
    void findsJsonLiteralAtCaret() {
        String sql = "SELECT '{\"a\":1}' FROM t";
        int caret = sql.indexOf('{') + 1;
        var found = SqlSyntaxHighlighter.findJsonLiteralAt(sql, caret, caret, caret);
        assertTrue(found.isPresent());
        assertEquals("{\"a\":1}", found.get().json());
        assertEquals(sql.indexOf('\''), found.get().literalStart());
    }

    @Test
    @DisplayName("findJsonLiteralAt accepts a bare JSON selection")
    void findsJsonFromBareSelection() {
        String sql = "SELECT x FROM t";
        String json = "{\"x\":1}";
        var found = SqlSyntaxHighlighter.findJsonLiteralAt(json, 0, 0, json.length());
        assertTrue(found.isPresent());
        assertEquals(json, found.get().json());
    }

    @Test
    @DisplayName("fold summary strings are styled as fold-placeholder")
    void stylesFoldPlaceholders() {
        String sql = "VALUES { 3 keys } AND [ 2 items ] AND ( 'Thrall'... )";
        var spans = SqlSyntaxHighlighter.computeHighlighting(sql);
        assertTrue(hasStyleAt(sql, spans, "{ 3 keys }", SqlSyntaxHighlighter.FOLD_PLACEHOLDER));
        assertTrue(hasStyleAt(sql, spans, "[ 2 items ]", SqlSyntaxHighlighter.FOLD_PLACEHOLDER));
        assertTrue(hasStyleAt(sql, spans, "( 'Thrall'... )", SqlSyntaxHighlighter.FOLD_PLACEHOLDER));
    }

    @Test
    @DisplayName("explicit fold ranges style summaries the lexer may miss")
    void stylesExplicitFoldRanges() {
        String summary = "( 1, 2 )";
        String sql = "VALUES " + summary;
        int start = sql.indexOf(summary);
        var spans = SqlSyntaxHighlighter.computeHighlighting(sql, List.of(new int[]{start, start + summary.length()}));
        assertTrue(hasStyleAt(sql, spans, summary, SqlSyntaxHighlighter.FOLD_PLACEHOLDER));
    }

    @Test
    @DisplayName("Redis commands are keywords when the Redis dialect is active")
    void highlightsRedisCommands() {
        String redis = "SET mykey \"hello world\"\nGET mykey\nHGETALL user\nLRANGE q 0 -1\nDEL mykey\nEXPIRE mykey 60";
        List<String> keywords = textOf(redis, SqlSyntaxHighlighter.KEYWORD, ConnectionConfig.Driver.REDIS);
        assertTrue(keywords.containsAll(List.of("SET", "GET", "HGETALL", "LRANGE", "DEL", "EXPIRE")),
                "got " + keywords);
        assertEquals(List.of("\"hello world\""), textOf(redis, SqlSyntaxHighlighter.STRING, ConnectionConfig.Driver.REDIS));
        assertFalse(textOf("JOIN users", SqlSyntaxHighlighter.KEYWORD, ConnectionConfig.Driver.REDIS).contains("JOIN"));
        assertTrue(textOf("SELECT 1 FROM t JOIN u", SqlSyntaxHighlighter.KEYWORD, ConnectionConfig.Driver.MYSQL)
                .contains("JOIN"));
    }

    @Test
    @DisplayName("tab and file names pick SQL vs Redis highlighting")
    void driverFollowsDocumentExtension() {
        assertEquals(ConnectionConfig.Driver.REDIS,
                SqlSyntaxHighlighter.driverForDocumentName("console_1.redis"));
        assertEquals(ConnectionConfig.Driver.REDIS,
                SqlSyntaxHighlighter.driverForDocumentName("redis-new-string.redis"));
        assertEquals(ConnectionConfig.Driver.MYSQL,
                SqlSyntaxHighlighter.driverForDocumentName("console_1.sql"));
        assertEquals(ConnectionConfig.Driver.MYSQL,
                SqlSyntaxHighlighter.driverForDocumentName("query-new-table.sql"));
        assertEquals(".redis", SqlSyntaxHighlighter.untitledExtension(ConnectionConfig.Driver.REDIS));
        assertEquals(".sql", SqlSyntaxHighlighter.untitledExtension(ConnectionConfig.Driver.MYSQL));
    }

    @Test
    @DisplayName("hash comments are Redis comments, not SQL line comments")
    void highlightsRedisHashComments() {
        String text = "# get the key\nGET foo";
        assertEquals(List.of("# get the key"), textOf(text, SqlSyntaxHighlighter.COMMENT, ConnectionConfig.Driver.REDIS));
        assertEquals(List.of("GET"), textOf(text, SqlSyntaxHighlighter.KEYWORD, ConnectionConfig.Driver.REDIS));
    }

    private static boolean hasStyleAt(
            String sql,
            org.fxmisc.richtext.model.StyleSpans<java.util.Collection<String>> spans,
            String fragment,
            String styleClass) {
        int index = sql.indexOf(fragment);
        assertTrue(index >= 0, "fragment not found: " + fragment);
        int pos = 0;
        for (org.fxmisc.richtext.model.StyleSpan<java.util.Collection<String>> span : spans) {
            int end = pos + span.getLength();
            if (index >= pos && index < end) {
                return span.getStyle().contains(styleClass);
            }
            pos = end;
        }
        return false;
    }

    private static List<String> textOf(String sql, String styleClass) {
        return textOf(sql, styleClass, ConnectionConfig.Driver.MYSQL);
    }

    private static List<String> textOf(String sql, String styleClass, ConnectionConfig.Driver driver) {
        return SqlSyntaxHighlighter.tokenize(sql, driver).stream()
                .filter(token -> token.styleClass().equals(styleClass))
                .map(token -> sql.substring(token.start(), token.end()))
                .toList();
    }
}
