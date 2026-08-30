package com.lazaro.sqlide.core.io;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON reader/writer for flat object arrays. Avoids a third-party JSON
 * dependency for local history and snippets persistence.
 */
public final class SimpleJson {

    private SimpleJson() {
    }

    public static void writeObjectArray(Path path, List<Map<String, Object>> rows) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder json = new StringBuilder();
        json.append("[\n");
        for (int i = 0; i < rows.size(); i++) {
            if (i > 0) {
                json.append(",\n");
            }
            json.append("  ").append(writeObject(rows.get(i)));
        }
        json.append("\n]\n");
        Files.writeString(path, json.toString(), StandardCharsets.UTF_8);
    }

    public static List<Map<String, Object>> readObjectArray(Path path) throws IOException {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        String text = Files.readString(path, StandardCharsets.UTF_8).strip();
        if (text.isEmpty() || text.equals("[]")) {
            return List.of();
        }
        return parseObjectArray(text);
    }

    static String writeObject(Map<String, Object> object) {
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : object.entrySet()) {
            if (!first) {
                json.append(", ");
            }
            first = false;
            json.append(quote(entry.getKey())).append(": ").append(writeValue(entry.getValue()));
        }
        json.append('}');
        return json.toString();
    }

    private static String writeValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        if (value instanceof Number number) {
            return Long.toString(number.longValue());
        }
        return quote(String.valueOf(value));
    }

    static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u%04x".formatted((int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
        return out.toString();
    }

    static List<Map<String, Object>> parseObjectArray(String json) {
        Parser parser = new Parser(json);
        parser.skipWs();
        parser.expect('[');
        List<Map<String, Object>> rows = new ArrayList<>();
        parser.skipWs();
        if (parser.peek() == ']') {
            parser.advance();
            return rows;
        }
        while (true) {
            rows.add(parser.parseObject());
            parser.skipWs();
            char c = parser.peek();
            if (c == ',') {
                parser.advance();
                parser.skipWs();
                continue;
            }
            parser.expect(']');
            break;
        }
        return rows;
    }

    private static final class Parser {
        private final String text;
        private int i;

        Parser(String text) {
            this.text = text;
        }

        Map<String, Object> parseObject() {
            skipWs();
            expect('{');
            Map<String, Object> object = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                advance();
                return object;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                object.put(key, parseValue());
                skipWs();
                char c = peek();
                if (c == ',') {
                    advance();
                    continue;
                }
                expect('}');
                break;
            }
            return object;
        }

        Object parseValue() {
            char c = peek();
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                expectWord("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                expectWord("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                expectWord("null");
                return null;
            }
            return parseNumber();
        }

        String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (i < text.length()) {
                char c = text.charAt(i++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (i >= text.length()) {
                        break;
                    }
                    char esc = text.charAt(i++);
                    switch (esc) {
                        case '"', '\\', '/' -> out.append(esc);
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> {
                            out.append((char) Integer.parseInt(text.substring(i, i + 4), 16));
                            i += 4;
                        }
                        default -> out.append(esc);
                    }
                    continue;
                }
                out.append(c);
            }
            throw new IllegalArgumentException("Unterminated string at " + i);
        }

        long parseNumber() {
            int start = i;
            if (peek() == '-') {
                advance();
            }
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                i++;
            }
            return Long.parseLong(text.substring(start, i));
        }

        void expectWord(String word) {
            if (!text.startsWith(word, i)) {
                throw new IllegalArgumentException("Expected " + word + " at " + i);
            }
            i += word.length();
        }

        void expect(char c) {
            skipWs();
            if (peek() != c) {
                throw new IllegalArgumentException("Expected '" + c + "' at " + i);
            }
            advance();
        }

        void skipWs() {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
        }

        char peek() {
            return i < text.length() ? text.charAt(i) : 0;
        }

        void advance() {
            i++;
        }
    }
}
