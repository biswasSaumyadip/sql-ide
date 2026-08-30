package com.lazaro.sqlide.ui.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonSyntaxHighlighterTest {

    @Test
    void tokenizesKeysStringsNumbersAndLiterals() {
        String json = """
                {
                  "name": "Ada",
                  "id": 42,
                  "ok": true
                }
                """;
        List<JsonSyntaxHighlighter.Token> tokens = JsonSyntaxHighlighter.tokenize(json);
        assertFalse(tokens.isEmpty());
        assertTrue(tokens.stream().anyMatch(t -> JsonSyntaxHighlighter.KEY.equals(t.styleClass())));
        assertTrue(tokens.stream().anyMatch(t -> JsonSyntaxHighlighter.STRING.equals(t.styleClass())));
        assertTrue(tokens.stream().anyMatch(t -> JsonSyntaxHighlighter.NUMBER.equals(t.styleClass())));
        assertTrue(tokens.stream().anyMatch(t -> JsonSyntaxHighlighter.LITERAL.equals(t.styleClass())));
    }
}
