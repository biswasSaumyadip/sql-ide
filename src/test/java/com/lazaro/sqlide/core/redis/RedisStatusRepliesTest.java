package com.lazaro.sqlide.core.redis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedisStatusRepliesTest {

    @Test
    @DisplayName("write commands with a Value cell are status replies")
    void setAndDelAreStatus() {
        assertTrue(RedisStatusReplies.isStatusResult("SET", List.of("Value"), 1));
        assertTrue(RedisStatusReplies.isStatusResult("DEL", List.of("Value"), 1));
        assertTrue(RedisStatusReplies.isStatusResult("PING", List.of("Value"), 1));
        assertTrue(RedisStatusReplies.isStatusResult("EXPIRE", List.of("Value"), 1));
    }

    @Test
    @DisplayName("GET and collection reads stay tabular")
    void readsAreNotStatus() {
        assertFalse(RedisStatusReplies.isStatusResult("GET", List.of("Value"), 1));
        assertFalse(RedisStatusReplies.isStatusResult("HGETALL", List.of("Field", "Value"), 2));
        assertFalse(RedisStatusReplies.isStatusResult("LRANGE", List.of("Index", "Value"), 2));
        assertFalse(RedisStatusReplies.isReadLike("SET"));
        assertTrue(RedisStatusReplies.isReadLike("GET foo"));
    }

    @Test
    @DisplayName("replies format like redis-cli")
    void formatsReplies() {
        assertEquals("OK", RedisStatusReplies.formatReply("OK", "OK"));
        assertEquals("(integer) 1", RedisStatusReplies.formatReply(1L, "1"));
        assertEquals("(nil)", RedisStatusReplies.formatReply(null, null));
        assertEquals("PONG", RedisStatusReplies.formatReply("PONG", "PONG"));
    }
}
