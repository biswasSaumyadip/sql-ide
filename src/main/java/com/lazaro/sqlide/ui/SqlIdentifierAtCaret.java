package com.lazaro.sqlide.ui;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves a SQL identifier (and optional qualifier) under the caret or selection.
 */
public final class SqlIdentifierAtCaret {

    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*|`[^`]+`|\"[^\"]+\"|\\[[^\\]]+\\]");
    private static final Pattern QUALIFIED = Pattern.compile(
            "(" + IDENT.pattern() + ")\\s*\\.\\s*(" + IDENT.pattern() + ")(?:\\s*\\.\\s*(" + IDENT.pattern() + "))?");

    private SqlIdentifierAtCaret() {
    }

    public record Ref(String catalogOrSchema, String tableOrColumn, String column) {
        public boolean hasColumn() {
            return column != null && !column.isBlank();
        }
    }

    /**
     * Prefer non-empty single-line selection; otherwise parse tokens spanning {@code caret}.
     */
    public static Optional<Ref> resolve(String text, int caret, String selection) {
        if (selection != null && !selection.isBlank() && !selection.contains("\n")) {
            Optional<Ref> fromSelection = parse(selection.strip());
            if (fromSelection.isPresent()) {
                return fromSelection;
            }
        }
        if (text == null || text.isEmpty()) {
            return Optional.empty();
        }
        int pos = Math.max(0, Math.min(caret, text.length()));
        int start = pos;
        int end = pos;
        while (start > 0 && isTokenChar(text.charAt(start - 1))) {
            start--;
        }
        while (end < text.length() && isTokenChar(text.charAt(end))) {
            end++;
        }
        while (start < end && text.charAt(start) == '.') {
            start++;
        }
        while (end > start && text.charAt(end - 1) == '.') {
            end--;
        }
        if (start >= end) {
            return Optional.empty();
        }
        return parse(text.substring(start, end).strip());
    }

    static Optional<Ref> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = QUALIFIED.matcher(raw.trim());
        if (matcher.matches()) {
            String a = unquote(matcher.group(1));
            String b = unquote(matcher.group(2));
            String c = matcher.group(3) == null ? null : unquote(matcher.group(3));
            if (c != null) {
                return Optional.of(new Ref(a, b, c));
            }
            return Optional.of(new Ref(a, b, null));
        }
        Matcher single = IDENT.matcher(raw.trim());
        if (single.matches()) {
            return Optional.of(new Ref(null, unquote(single.group()), null));
        }
        return Optional.empty();
    }

    private static boolean isTokenChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '`' || c == '"'
                || c == '[' || c == ']' || c == '.';
    }

    private static String unquote(String name) {
        String trimmed = name.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '`' && last == '`')
                    || (first == '"' && last == '"')
                    || (first == '[' && last == ']')) {
                return trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed;
    }

    public static String normalize(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT);
    }
}
