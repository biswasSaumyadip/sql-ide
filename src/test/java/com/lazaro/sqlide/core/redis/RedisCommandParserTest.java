package com.lazaro.sqlide.core.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisCommandParserTest {

    @Test
    @DisplayName("quoted arguments keep internal spaces")
    void tokenizesQuotedArguments() {
        var parsed = RedisCommandParser.parse("SET mykey \"hello world\"").orElseThrow();

        assertEquals("SET", parsed.command());
        assertEquals(List.of("mykey", "hello world"), parsed.arguments());
    }

    @Test
    @DisplayName("single quotes are unwrapped the same way")
    void tokenizesSingleQuotes() {
        var parsed = RedisCommandParser.parse("SET k 'a b c'").orElseThrow();
        assertEquals(List.of("k", "a b c"), parsed.arguments());
    }

    @Test
    @DisplayName("backslash escapes a quote inside a string")
    void respectsEscapedQuotes() {
        var parsed = RedisCommandParser.parse("SET k \"say \\\"hi\\\"\"").orElseThrow();
        assertEquals(List.of("k", "say \"hi\""), parsed.arguments());
    }

    @Test
    @DisplayName("blank lines and hash comments are skipped")
    void skipsBlankAndComments() {
        List<String> lines = RedisCommandParser.commandLines("""
                # comment
                GET foo

                SET bar baz
                """);
        assertEquals(List.of("GET foo", "SET bar baz"), lines);
    }

    @Test
    @DisplayName("commands without arguments still parse")
    void parsesBareCommand() {
        var parsed = RedisCommandParser.parse("PING").orElseThrow();
        assertEquals("PING", parsed.command());
        assertTrue(parsed.arguments().isEmpty());
    }
}
