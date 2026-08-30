package com.lazaro.sqlide.core.redis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Splits a Redis buffer into commands. Lines are the statement boundary (not
 * semicolons); each line is tokenised with quote-aware splitting so
 * {@code SET mykey "hello world"} becomes {@code SET}, {@code mykey}, {@code hello world}.
 */
public final class RedisCommandParser {

    private RedisCommandParser() {
    }

    /**
     * Non-empty, non-comment lines ready to execute. {@code #} starts a line comment.
     */
    public static List<String> commandLines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R", -1)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            lines.add(line);
        }
        return List.copyOf(lines);
    }

    public static Optional<ParsedCommand> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        List<String> tokens = tokenize(line.strip());
        if (tokens.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ParsedCommand(tokens.getFirst(), List.copyOf(tokens.subList(1, tokens.size()))));
    }

    /**
     * Splits {@code line} on whitespace, keeping quoted spans intact. Both single
     * and double quotes are recognised; a backslash escapes the next character
     * inside quotes.
     */
    public static List<String> tokenize(String line) {
        if (line == null || line.isEmpty()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Character quote = null;
        boolean escaped = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != null) {
                if (escaped) {
                    current.append(c);
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == quote) {
                    quote = null;
                    tokens.add(current.toString());
                    current.setLength(0);
                    continue;
                }
                current.append(c);
                continue;
            }
            if (c == '"' || c == '\'') {
                flush(tokens, current);
                quote = c;
                continue;
            }
            if (Character.isWhitespace(c)) {
                flush(tokens, current);
                continue;
            }
            current.append(c);
        }
        if (escaped) {
            current.append('\\');
        }
        flush(tokens, current);
        return List.copyOf(tokens);
    }

    private static void flush(List<String> tokens, StringBuilder current) {
        if (current.length() == 0) {
            return;
        }
        tokens.add(current.toString());
        current.setLength(0);
    }

    /**
     * @param command   first token (e.g. {@code SET})
     * @param arguments remaining tokens, already unquoted
     */
    public record ParsedCommand(String command, List<String> arguments) {

        public ParsedCommand {
            command = command == null ? "" : command;
            arguments = List.copyOf(arguments == null ? List.of() : arguments);
        }

        public String[] argumentArray() {
            return arguments.toArray(String[]::new);
        }
    }
}
