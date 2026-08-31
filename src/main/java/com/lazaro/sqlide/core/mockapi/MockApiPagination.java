package com.lazaro.sqlide.core.mockapi;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses {@code ?page=X&limit=Y} and slices an in-memory list. Page numbers are
 * 1-based. Missing both parameters returns the full list.
 */
public final class MockApiPagination {

    static final int DEFAULT_PAGE_SIZE = 50;

    private MockApiPagination() {
    }

    public record Slice(int page, int limit, int total, int fromInclusive, int toExclusive) {
        public <T> List<T> apply(List<T> rows) {
            if (rows == null || rows.isEmpty() || fromInclusive >= rows.size()) {
                return List.of();
            }
            int to = Math.min(toExclusive, rows.size());
            return List.copyOf(rows.subList(fromInclusive, to));
        }
    }

    public static Slice parse(String rawQuery, int total) {
        Map<String, String> query = parseQuery(rawQuery);
        boolean hasPage = query.containsKey("page");
        boolean hasLimit = query.containsKey("limit");
        int size = Math.max(0, total);

        if (!hasPage && !hasLimit) {
            return new Slice(1, size, size, 0, size);
        }

        Integer pageValue = parsePositiveInt(query.get("page"));
        Integer limitValue = parseNonNegativeInt(query.get("limit"));
        if (hasPage && query.get("page") != null && pageValue == null) {
            throw new IllegalArgumentException("page must be a positive integer");
        }
        if (hasLimit && query.get("limit") != null && limitValue == null) {
            throw new IllegalArgumentException("limit must be a non-negative integer");
        }

        int page = pageValue == null ? 1 : Math.max(1, pageValue);
        int limit = limitValue == null ? DEFAULT_PAGE_SIZE : limitValue;
        int from = Math.multiplyExact(page - 1, limit);
        if (from < 0) {
            from = 0;
        }
        int to = limit == Integer.MAX_VALUE ? size : Math.addExact(from, limit);
        return new Slice(page, limit, size, from, to);
    }

    static Map<String, String> parseQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isBlank()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(urlDecode(key).toLowerCase(Locale.ROOT), urlDecode(value));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Integer parsePositiveInt(String text) {
        Integer value = parseInt(text);
        if (value == null || value < 1) {
            return null;
        }
        return value;
    }

    private static Integer parseNonNegativeInt(String text) {
        Integer value = parseInt(text);
        if (value == null || value < 0) {
            return null;
        }
        return value;
    }

    private static Integer parseInt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String urlDecode(String value) {
        try {
            return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return value;
        }
    }
}
