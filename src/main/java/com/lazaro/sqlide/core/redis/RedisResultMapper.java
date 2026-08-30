package com.lazaro.sqlide.core.redis;

import com.lazaro.sqlide.core.db.QueryResult;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a Jedis {@code sendCommand} reply into the same grid shape the SQL
 * results pane already consumes ({@link QueryResult} of string cells).
 *
 * <p>Internally rows are {@code List<Map<String, Object>>} as specified, then
 * flattened to column-aligned lists for the existing cell factories.
 */
public final class RedisResultMapper {

    static final String COL_VALUE = "Value";
    static final String COL_INDEX = "Index";
    static final String COL_FIELD = "Field";
    static final String NIL = "(nil)";

    private RedisResultMapper() {
    }

    public static QueryResult toQueryResult(Object reply, String command, long executionTimeMs) {
        Mapped mapped = map(reply, command);
        List<List<String>> rows = new ArrayList<>(mapped.rows().size());
        for (Map<String, Object> row : mapped.rows()) {
            List<String> cells = new ArrayList<>(mapped.columns().size());
            for (String column : mapped.columns()) {
                cells.add(cellString(row.get(column)));
            }
            rows.add(cells);
        }
        return QueryResult.ofRows(mapped.columns(), rows, executionTimeMs);
    }

    static Mapped map(Object reply, String command) {
        if (reply == null) {
            return scalar(NIL);
        }
        if (reply instanceof Map<?, ?> map) {
            return hashFromMap(map);
        }
        if (reply instanceof Set<?> set) {
            return listFromCollection(set);
        }
        if (reply instanceof Collection<?> collection) {
            if (isHashCommand(command) && collection.size() % 2 == 0) {
                return hashFromPairs(collection);
            }
            return listFromCollection(collection);
        }
        return scalar(stringify(reply));
    }

    private static Mapped scalar(String value) {
        return new Mapped(List.of(COL_VALUE), List.of(row(COL_VALUE, value)));
    }

    private static Mapped listFromCollection(Collection<?> collection) {
        List<Map<String, Object>> rows = new ArrayList<>(collection.size());
        int index = 0;
        for (Object item : collection) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(COL_INDEX, index);
            row.put(COL_VALUE, stringify(item));
            rows.add(row);
            index++;
        }
        return new Mapped(List.of(COL_INDEX, COL_VALUE), rows);
    }

    private static Mapped hashFromMap(Map<?, ?> map) {
        List<Map<String, Object>> rows = new ArrayList<>(map.size());
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(COL_FIELD, stringify(entry.getKey()));
            row.put(COL_VALUE, stringify(entry.getValue()));
            rows.add(row);
        }
        return new Mapped(List.of(COL_FIELD, COL_VALUE), rows);
    }

    private static Mapped hashFromPairs(Collection<?> collection) {
        List<Object> items = new ArrayList<>(collection);
        List<Map<String, Object>> rows = new ArrayList<>(items.size() / 2);
        for (int i = 0; i + 1 < items.size(); i += 2) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(COL_FIELD, stringify(items.get(i)));
            row.put(COL_VALUE, stringify(items.get(i + 1)));
            rows.add(row);
        }
        return new Mapped(List.of(COL_FIELD, COL_VALUE), rows);
    }

    private static boolean isHashCommand(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        String name = command.trim().toUpperCase(Locale.ROOT);
        return "HGETALL".equals(name);
    }

    private static Map<String, Object> row(String key, Object value) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(key, value);
        return row;
    }

    static String stringify(Object value) {
        if (value == null) {
            return NIL;
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (value instanceof Collection<?> collection) {
            List<String> parts = new ArrayList<>(collection.size());
            for (Object item : collection) {
                parts.add(stringify(item));
            }
            return parts.toString();
        }
        if (value instanceof Map<?, ?> map) {
            List<String> parts = new ArrayList<>(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                parts.add(stringify(entry.getKey()) + "=" + stringify(entry.getValue()));
            }
            return "{" + String.join(", ", parts) + "}";
        }
        return String.valueOf(value);
    }

    /** {@code null} stays SQL-NULL in the grid; Redis nil is the {@code (nil)} token. */
    private static String cellString(Object value) {
        if (value == null) {
            return null;
        }
        return String.valueOf(value);
    }

    record Mapped(List<String> columns, List<Map<String, Object>> rows) {
        Mapped {
            columns = List.copyOf(columns);
            rows = List.copyOf(rows);
        }
    }
}
