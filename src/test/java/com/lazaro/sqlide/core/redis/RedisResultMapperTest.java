package com.lazaro.sqlide.core.redis;

import com.lazaro.sqlide.core.db.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RedisResultMapperTest {

    @Test
    @DisplayName("string and integer replies become a Value column")
    void mapsScalars() {
        QueryResult ok = RedisResultMapper.toQueryResult("OK", "SET", 1L);
        assertEquals(List.of("Value"), ok.columnNames());
        assertEquals(List.of(List.of("OK")), ok.rows());

        QueryResult count = RedisResultMapper.toQueryResult(3L, "DEL", 1L);
        assertEquals(List.of(List.of("3")), count.rows());
    }

    @Test
    @DisplayName("nil replies render as (nil)")
    void mapsNil() {
        QueryResult result = RedisResultMapper.toQueryResult(null, "GET", 1L);
        assertEquals(List.of(List.of("(nil)")), result.rows());
    }

    @Test
    @DisplayName("list replies become Index and Value")
    void mapsLists() {
        QueryResult result = RedisResultMapper.toQueryResult(List.of("a", "b"), "LRANGE", 1L);
        assertEquals(List.of("Index", "Value"), result.columnNames());
        assertEquals(List.of(List.of("0", "a"), List.of("1", "b")), result.rows());
    }

    @Test
    @DisplayName("map replies become Field and Value")
    void mapsHashes() {
        Map<String, String> hash = new LinkedHashMap<>();
        hash.put("name", "Ada");
        hash.put("id", "42");
        QueryResult result = RedisResultMapper.toQueryResult(hash, "HGETALL", 1L);
        assertEquals(List.of("Field", "Value"), result.columnNames());
        assertEquals(List.of(List.of("name", "Ada"), List.of("id", "42")), result.rows());
    }

    @Test
    @DisplayName("HGETALL even-length lists are treated as field/value pairs")
    void mapsHgetallPairs() {
        QueryResult result = RedisResultMapper.toQueryResult(List.of("f1", "v1", "f2", "v2"), "HGETALL", 1L);
        assertEquals(List.of("Field", "Value"), result.columnNames());
        assertEquals(List.of(List.of("f1", "v1"), List.of("f2", "v2")), result.rows());
    }

    @Test
    @DisplayName("an even-length LRANGE stays a list, not a hash")
    void evenLrangeIsNotAHash() {
        QueryResult result = RedisResultMapper.toQueryResult(List.of("a", "b"), "LRANGE", 1L);
        assertEquals(List.of("Index", "Value"), result.columnNames());
        assertNull(result.errorMessage());
    }
}
