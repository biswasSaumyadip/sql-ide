package com.lazaro.sqlide.core.sql;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Text-replacement folding: collapsed blocks are physically swapped for a short
 * summary string in the document, and restored on expand / for execution.
 */
public final class FoldManager {

    /**
     * One active fold in document coordinates. {@code start}/{@code end} cover the
     * summary currently in the buffer ({@code end} exclusive).
     */
    public record ActiveFold(int start, int end, String originalText, String summary) {
        public ActiveFold {
            Objects.requireNonNull(originalText, "originalText");
            Objects.requireNonNull(summary, "summary");
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid fold range [%d, %d)".formatted(start, end));
            }
        }

        public int startLineIn(String document) {
            return lineOfOffset(document, start);
        }
    }

    public record Replacement(int start, int end, String text) {
    }

    private final List<ActiveFold> folds = new ArrayList<>();

    public List<ActiveFold> folds() {
        return List.copyOf(folds);
    }

    public boolean hasFolds() {
        return !folds.isEmpty();
    }

    public void clear() {
        folds.clear();
    }

    public Optional<ActiveFold> foldStartingOnLine(String document, int line) {
        for (ActiveFold fold : folds) {
            if (fold.startLineIn(document) == line) {
                return Optional.of(fold);
            }
        }
        return Optional.empty();
    }

    public Optional<ActiveFold> foldAtCaretLine(String document, int caretOffset) {
        int line = lineOfOffset(document, Math.max(0, Math.min(caretOffset, Math.max(0, document.length() - 1))));
        return foldStartingOnLine(document, line);
    }

    /**
     * Prepares a collapse of {@code [openOffset, closeOffset]} inclusive.
     * Caller must apply {@link Replacement} via {@code replaceText}, then
     * {@link #commitCollapse(Replacement, String)}.
     */
    public Optional<Replacement> prepareCollapse(String document, int openOffset, int closeOffset) {
        if (document == null || openOffset < 0 || closeOffset < openOffset || closeOffset >= document.length()) {
            return Optional.empty();
        }
        int endExclusive = closeOffset + 1;
        for (ActiveFold existing : folds) {
            if (rangesOverlap(existing.start(), existing.end(), openOffset, endExclusive)) {
                return Optional.empty();
            }
        }
        String original = document.substring(openOffset, endExclusive);
        if (!original.contains("\n") && !original.contains("\r")) {
            return Optional.empty();
        }
        String summary = FoldSummaries.generateFoldSummary(original);
        if (summary.isBlank() || summary.equals(original)) {
            return Optional.empty();
        }
        return Optional.of(new Replacement(openOffset, endExclusive, summary));
    }

    public void commitCollapse(Replacement replacement, String originalText) {
        Objects.requireNonNull(replacement, "replacement");
        folds.add(new ActiveFold(
                replacement.start(),
                replacement.start() + replacement.text().length(),
                originalText,
                replacement.text()));
        folds.sort(Comparator.comparingInt(ActiveFold::start));
    }

    /**
     * Locates the summary span for an active fold (re-validates against {@code document}).
     */
    public Optional<Replacement> prepareExpand(String document, ActiveFold fold) {
        if (fold == null || document == null) {
            return Optional.empty();
        }
        int start = fold.start();
        int end = fold.end();
        if (start < 0 || end > document.length() || start > end) {
            // Summary may have drifted — search for exact summary near recorded start.
            int at = document.indexOf(fold.summary());
            if (at < 0) {
                return Optional.empty();
            }
            start = at;
            end = at + fold.summary().length();
        } else {
            String current = document.substring(start, end);
            if (!current.equals(fold.summary())) {
                int at = document.indexOf(fold.summary());
                if (at < 0) {
                    return Optional.empty();
                }
                start = at;
                end = at + fold.summary().length();
            }
        }
        return Optional.of(new Replacement(start, end, fold.originalText()));
    }

    public void commitExpand(ActiveFold fold) {
        folds.removeIf(f -> f.start() == fold.start()
                && f.summary().equals(fold.summary())
                && f.originalText().equals(fold.originalText()));
    }

    /** After a document edit that is not a fold op, drop folds whose summary is gone. */
    public void reconcile(String document) {
        if (document == null) {
            folds.clear();
            return;
        }
        folds.removeIf(fold -> {
            if (fold.end() <= document.length()
                    && document.substring(fold.start(), fold.end()).equals(fold.summary())) {
                return false;
            }
            return document.indexOf(fold.summary()) < 0;
        });
        // Re-anchor folds that moved.
        List<ActiveFold> reanchored = new ArrayList<>(folds.size());
        for (ActiveFold fold : folds) {
            if (fold.end() <= document.length()
                    && document.substring(fold.start(), fold.end()).equals(fold.summary())) {
                reanchored.add(fold);
                continue;
            }
            int at = document.indexOf(fold.summary());
            if (at >= 0) {
                reanchored.add(new ActiveFold(at, at + fold.summary().length(), fold.originalText(), fold.summary()));
            }
        }
        folds.clear();
        folds.addAll(reanchored);
        folds.sort(Comparator.comparingInt(ActiveFold::start));
    }

    /**
     * Expands all fold summaries back to original text (for execution / parsing).
     * Does not mutate manager state.
     */
    public String expandAll(String document) {
        if (document == null || folds.isEmpty()) {
            return document == null ? "" : document;
        }
        StringBuilder sb = new StringBuilder(document);
        List<ActiveFold> descending = new ArrayList<>(folds);
        descending.sort(Comparator.comparingInt(ActiveFold::start).reversed());
        for (ActiveFold fold : descending) {
            int start = fold.start();
            int end = fold.end();
            if (end <= sb.length() && sb.substring(start, end).equals(fold.summary())) {
                sb.replace(start, end, fold.originalText());
                continue;
            }
            int at = sb.indexOf(fold.summary());
            if (at >= 0) {
                sb.replace(at, at + fold.summary().length(), fold.originalText());
            }
        }
        return sb.toString();
    }

    /** Placeholder ranges currently in the document, for syntax highlighting. */
    public List<int[]> placeholderRanges(String document) {
        if (document == null || folds.isEmpty()) {
            return List.of();
        }
        List<int[]> ranges = new ArrayList<>();
        for (ActiveFold fold : folds) {
            if (fold.end() <= document.length()
                    && document.substring(fold.start(), fold.end()).equals(fold.summary())) {
                ranges.add(new int[]{fold.start(), fold.end()});
                continue;
            }
            int at = document.indexOf(fold.summary());
            if (at >= 0) {
                ranges.add(new int[]{at, at + fold.summary().length()});
            }
        }
        ranges.sort(Comparator.comparingInt(r -> r[0]));
        return List.copyOf(ranges);
    }

    private static boolean rangesOverlap(int a0, int a1, int b0, int b1) {
        return a0 < b1 && b0 < a1;
    }

    static int lineOfOffset(String text, int offset) {
        int line = 0;
        int limit = Math.min(offset, text.length());
        for (int i = 0; i < limit; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
