package com.lazaro.sqlide.core.mockapi;

import com.lazaro.sqlide.core.db.QueryResult;
import com.lazaro.sqlide.core.db.ResultColumn;
import com.lazaro.sqlide.core.json.JsonPayloads;

import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Converts a {@link QueryResult} into JSON-ready maps: numeric SQL columns become
 * JSON numbers, booleans stay booleans, and temporal values emit ISO-8601 strings.
 */
public final class MockApiJson {

    private MockApiJson() {
    }

    public static List<Map<String, Object>> toRowMaps(QueryResult result) {
        Objects.requireNonNull(result, "result");
        List<ResultColumn> columns = result.columns();
        List<Map<String, Object>> rows = new ArrayList<>(result.rows().size());
        for (List<String> row : result.rows()) {
            Map<String, Object> map = new LinkedHashMap<>(columns.size());
            for (int c = 0; c < columns.size(); c++) {
                ResultColumn column = columns.get(c);
                String raw = c < row.size() ? row.get(c) : null;
                map.put(column.name(), coerce(raw, column));
            }
            rows.add(map);
        }
        return rows;
    }

    public static String toJsonArray(QueryResult result) {
        return JsonPayloads.writeCompact(toRowMaps(result));
    }

    public static String toJsonArray(List<Map<String, Object>> rows) {
        return JsonPayloads.writeCompact(rows);
    }

    static Object coerce(String value, ResultColumn column) {
        if (value == null) {
            return null;
        }
        ResultColumn.Kind kind = column == null ? ResultColumn.Kind.OTHER : column.kind();
        int sqlType = column == null ? java.sql.Types.OTHER : column.sqlType();
        return switch (kind) {
            case NUMERIC -> coerceNumber(value, sqlType);
            case BOOLEAN -> coerceBoolean(value);
            case TEMPORAL -> coerceTemporal(value);
            case BINARY, TEXT, OTHER -> JsonPayloads.coerceValue(value);
        };
    }

    private static Object coerceNumber(String value, int sqlType) {
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return value;
        }
        try {
            return switch (sqlType) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> Integer.parseInt(trimmed);
                case Types.BIGINT -> Long.parseLong(trimmed);
                case Types.FLOAT, Types.REAL, Types.DOUBLE -> Double.parseDouble(trimmed);
                case Types.NUMERIC, Types.DECIMAL -> new BigDecimal(trimmed);
                default -> parseLooseNumber(trimmed);
            };
        } catch (NumberFormatException ex) {
            Object guessed = JsonPayloads.coerceValue(value);
            return guessed instanceof Number ? guessed : value;
        }
    }

    private static Object parseLooseNumber(String trimmed) {
        if (trimmed.indexOf('.') >= 0 || trimmed.toLowerCase().indexOf('e') >= 0) {
            return new BigDecimal(trimmed);
        }
        long asLong = Long.parseLong(trimmed);
        if (asLong >= Integer.MIN_VALUE && asLong <= Integer.MAX_VALUE) {
            return (int) asLong;
        }
        return asLong;
    }

    private static Object coerceBoolean(String value) {
        String trimmed = value.strip();
        if ("true".equalsIgnoreCase(trimmed) || "1".equals(trimmed) || "t".equalsIgnoreCase(trimmed)
                || "yes".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed) || "0".equals(trimmed) || "f".equalsIgnoreCase(trimmed)
                || "no".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        return JsonPayloads.coerceValue(value);
    }

    /**
     * JDBC {@code Timestamp#toString()} uses a space, not {@code T}. Emit ISO-8601
     * local date-times when a zone is not present.
     */
    static String coerceTemporal(String value) {
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return value;
        }
        OffsetDateTime offset = tryOffset(trimmed);
        if (offset != null) {
            return offset.toString();
        }
        LocalDateTime dateTime = tryDateTime(trimmed);
        if (dateTime != null) {
            return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        }
        LocalDate date = tryDate(trimmed);
        if (date != null) {
            return date.format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        LocalTime time = tryTime(trimmed);
        if (time != null) {
            return time.format(DateTimeFormatter.ISO_LOCAL_TIME);
        }
        return value;
    }

    private static OffsetDateTime tryOffset(String text) {
        try {
            return OffsetDateTime.parse(text);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static LocalDateTime tryDateTime(String text) {
        String iso = text.indexOf('T') >= 0 ? text : text.replace(' ', 'T');
        try {
            return LocalDateTime.parse(iso);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static LocalDate tryDate(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static LocalTime tryTime(String text) {
        try {
            return LocalTime.parse(text);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
