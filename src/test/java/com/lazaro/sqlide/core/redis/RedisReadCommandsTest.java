package com.lazaro.sqlide.core.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisReadCommandsTest {

    @Test
    @DisplayName("TYPE selects GET, HGETALL or LRANGE")
    void mapsTypeToReadCommand() {
        assertEquals("GET mykey", RedisReadCommands.forType("mykey", "string"));
        assertEquals("HGETALL user", RedisReadCommands.forType("user", "hash"));
        assertEquals("LRANGE q 0 -1", RedisReadCommands.forType("q", "list"));
        assertEquals("SMEMBERS tags", RedisReadCommands.forType("tags", "set"));
    }

    @Test
    @DisplayName("keys with spaces are quoted")
    void quotesWhitespace() {
        assertEquals("GET \"hello world\"", RedisReadCommands.forType("hello world", "string"));
    }
}

class RedisMutatingCommandsTest {

    @Test
    void detectsFlushAndDelete() {
        assertTrue(RedisMutatingCommands.mutates("FLUSHDB"));
        assertTrue(RedisMutatingCommands.any(java.util.List.of("GET a", "DEL k")));
        assertFalse(RedisMutatingCommands.mutates("GET foo"));
    }
}
