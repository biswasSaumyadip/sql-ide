package com.lazaro.sqlide.core.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.Locale;
import java.util.Objects;

/**
 * Lightweight JSON helpers for cell detection, pretty-printing, and typed coercion.
 */
public final class JsonPayloads {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private static final DefaultPrettyPrinter PRETTY = prettyPrinter();

    private JsonPayloads() {
    }

    /**
     * Fast heuristic: trimmed text starts/ends with {@code {}} or {@code []}.
     * Does not fully validate JSON.
     */
    public static boolean looksLikeJson(String text) {
        if (text == null) {
            return false;
        }
        String trimmed = text.strip();
        if (trimmed.length() < 2) {
            return false;
        }
        char first = trimmed.charAt(0);
        char last = trimmed.charAt(trimmed.length() - 1);
        return (first == '{' && last == '}') || (first == '[' && last == ']');
    }

    /**
     * Parses and pretty-prints with 2-space indentation. Returns the original
     * trimmed text when parsing fails.
     */
    public static String prettyPrint(String text) {
        if (text == null) {
            return "";
        }
        String trimmed = text.strip();
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            return MAPPER.writer(PRETTY).writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            return trimmed;
        }
    }

    /** True when {@link #prettyPrint(String)} would successfully reformat. */
    public static boolean isValidJson(String text) {
        if (!looksLikeJson(text)) {
            return false;
        }
        try {
            MAPPER.readTree(text.strip());
            return true;
        } catch (JsonProcessingException ex) {
            return false;
        }
    }

    /**
     * Coerces a result-cell string into a JSON-friendly value (null, boolean,
     * number, nested JSON, or plain string).
     */
    public static Object coerceValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return value;
        }
        if ("true".equalsIgnoreCase(trimmed)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(trimmed)) {
            return Boolean.FALSE;
        }
        if (looksLikeJson(trimmed)) {
            try {
                return MAPPER.readTree(trimmed);
            } catch (JsonProcessingException ignored) {
                // fall through to string / number
            }
        }
        if (isIntegral(trimmed)) {
            try {
                return Long.parseLong(trimmed);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        if (isDecimal(trimmed)) {
            try {
                return Double.parseDouble(trimmed);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return value;
    }

    public static String writePretty(Object value) {
        Objects.requireNonNull(value, "value");
        try {
            return MAPPER.writer(PRETTY).writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("Cannot serialize value as JSON", ex);
        }
    }

    private static boolean isIntegral(String text) {
        if (text.isEmpty()) {
            return false;
        }
        int i = text.charAt(0) == '-' || text.charAt(0) == '+' ? 1 : 0;
        if (i >= text.length()) {
            return false;
        }
        for (; i < text.length(); i++) {
            if (!Character.isDigit(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isDecimal(String text) {
        try {
            Double.parseDouble(text);
            return text.indexOf('.') >= 0 || text.toLowerCase(Locale.ROOT).indexOf('e') >= 0;
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    private static DefaultPrettyPrinter prettyPrinter() {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter();
        DefaultIndenter indenter = new DefaultIndenter("  ", DefaultIndenter.SYS_LF);
        printer.indentObjectsWith(indenter);
        printer.indentArraysWith(indenter);
        return printer;
    }
}
