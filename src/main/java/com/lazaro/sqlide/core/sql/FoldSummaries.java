package com.lazaro.sqlide.core.sql;

/**
 * Builds short contextual labels for collapsed fold regions (JSON / SQL tuples).
 */
public final class FoldSummaries {

    private FoldSummaries() {
    }

    /**
     * Summarises {@code collapsedText} (including its outer brackets) for a fold
     * placeholder. Never returns {@code null}.
     */
    public static String generateFoldSummary(String collapsedText) {
        if (collapsedText == null || collapsedText.isBlank()) {
            return "(...)";
        }
        String trimmed = collapsedText.strip();
        if (trimmed.length() < 2) {
            return trimmed;
        }
        char open = trimmed.charAt(0);
        char close = trimmed.charAt(trimmed.length() - 1);
        if (open == '{' && close == '}') {
            int keys = countTopLevelItems(trimmed);
            return "{ " + keys + (keys == 1 ? " key" : " keys") + " }";
        }
        if (open == '[' && close == ']') {
            int items = countTopLevelItems(trimmed);
            return "[ " + items + (items == 1 ? " item" : " items") + " ]";
        }
        if (open == '(' && close == ')') {
            return sqlTuplePreview(trimmed);
        }
        return ellipsize(flatten(trimmed), 18);
    }

    private static String sqlTuplePreview(String trimmed) {
        String inner = flatten(trimmed.substring(1, trimmed.length() - 1)).strip();
        if (inner.isEmpty()) {
            return "(...)";
        }
        final int limit = 15;
        if (inner.length() <= limit) {
            return "( " + inner + " )";
        }
        // Prefer a clean cut after the first value when the preview would truncate mid-tuple.
        int cut = limit;
        int firstComma = inner.indexOf(',');
        if (firstComma > 0 && firstComma <= limit) {
            cut = firstComma;
        }
        String snippet = inner.substring(0, cut).stripTrailing();
        return "( " + snippet + "... )";
    }

    /**
     * Counts top-level comma-separated elements inside a bracketed span, respecting
     * nested brackets and quoted strings.
     */
    static int countTopLevelItems(String bracketed) {
        if (bracketed == null || bracketed.length() < 2) {
            return 0;
        }
        String inner = bracketed.substring(1, bracketed.length() - 1).strip();
        if (inner.isEmpty()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < inner.length()) {
                    i++;
                    continue;
                }
                if (c == quote) {
                    if (quote == '\'' && i + 1 < inner.length() && inner.charAt(i + 1) == '\'') {
                        i++;
                        continue;
                    }
                    inString = false;
                }
                continue;
            }
            if (c == '\'' || c == '"' || c == '`') {
                inString = true;
                quote = c;
                continue;
            }
            if (c == '{' || c == '[' || c == '(') {
                depth++;
                continue;
            }
            if (c == '}' || c == ']' || c == ')') {
                depth = Math.max(0, depth - 1);
                continue;
            }
            if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    private static String flatten(String text) {
        return text.replace('\r', ' ').replace('\n', ' ').replaceAll("\\s+", " ").strip();
    }

    private static String ellipsize(String text, int max) {
        if (text.length() <= max) {
            return text;
        }
        return text.substring(0, Math.max(1, max - 3)).stripTrailing() + "...";
    }
}
