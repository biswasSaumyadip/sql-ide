package com.lazaro.sqlide.ui.autocomplete;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abbreviation snippets ({@code sel}, {@code ins}, …) and {@code $name$} placeholder parsing.
 */
public final class SqlSnippetCatalog {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$([A-Za-z_][A-Za-z0-9_]*)\\$");

    record Snippet(String abbrev, String name, String template, String documentation) {
    }

    /** Text ready to insert plus absolute placeholder ranges within that text. */
    public record AppliedTemplate(String text, List<int[]> ranges) {
    }

    private static final List<Snippet> SNIPPETS = List.of(
            new Snippet("sel", "sel", "SELECT * FROM $table$",
                    "SELECT * FROM … — query every column of a table."),
            new Snippet("seli", "seli", "SELECT $columns$ FROM $table$ WHERE $condition$",
                    "SELECT … FROM … WHERE … — filtered projection."),
            new Snippet("ins", "ins", "INSERT INTO $table$ ($columns$) VALUES ($values$)",
                    "INSERT INTO … (…) VALUES (…) — insert a row."),
            new Snippet("upd", "upd", "UPDATE $table$ SET $column$ = $value$ WHERE $condition$",
                    "UPDATE … SET … WHERE … — modify matching rows."),
            new Snippet("del", "del", "DELETE FROM $table$ WHERE $condition$",
                    "DELETE FROM … WHERE … — remove matching rows."),
            new Snippet("cnt", "cnt", "SELECT COUNT(*) FROM $table$",
                    "SELECT COUNT(*) FROM … — row count."),
            new Snippet("join", "join", "JOIN $table$ ON $left$ = $right$",
                    "JOIN … ON … — inner join with equality."),
            new Snippet("ljoin", "ljoin", "LEFT JOIN $table$ ON $left$ = $right$",
                    "LEFT JOIN … ON … — preserve left rows."),
            new Snippet("cte", "cte", "WITH $name$ AS (\n    $query$\n)\nSELECT * FROM $name$",
                    "WITH … AS (…) — common table expression.")
    );

    private SqlSnippetCatalog() {
    }

    static List<Snippet> all() {
        return SNIPPETS;
    }

    /**
     * Replaces {@code $name$} markers with {@code name} and returns caret-selectable ranges
     * for each placeholder (linked by label order).
     */
    public static AppliedTemplate apply(String template) {
        if (template == null || template.isEmpty()) {
            return new AppliedTemplate("", List.of());
        }
        StringBuilder out = new StringBuilder();
        List<int[]> ranges = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        int last = 0;
        while (matcher.find()) {
            out.append(template, last, matcher.start());
            String label = matcher.group(1);
            int start = out.length();
            out.append(label);
            ranges.add(new int[]{start, start + label.length()});
            last = matcher.end();
        }
        out.append(template, last, template.length());
        return new AppliedTemplate(out.toString(), List.copyOf(ranges));
    }

    /** Placeholder labels in template order (for Suggestion metadata). */
    static List<String> placeholderLabels(String template) {
        if (template == null || template.isEmpty()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(template);
        while (matcher.find()) {
            labels.add(matcher.group(1));
        }
        return List.copyOf(labels);
    }
}
