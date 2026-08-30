package com.lazaro.sqlide.core.inspection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Turns overlapping {@link InspectionIssue}s into non-overlapping style runs, keeping
 * the strongest severity on each character (IntelliJ-style).
 */
public final class InspectionHighlights {

    public record Run(int start, int end, Severity severity, String message) {
        public String styleClass() {
            return switch (severity) {
                case ERROR -> "sql-error-underline";
                case WARNING -> "sql-warning-underline";
                case WEAK_WARNING -> "sql-weak-warning-underline";
            };
        }
    }

    private InspectionHighlights() {
    }

    public static List<Run> merge(List<InspectionIssue> issues, int length) {
        if (length <= 0 || issues == null || issues.isEmpty()) {
            return List.of();
        }
        Severity[] severities = new Severity[length];
        String[] messages = new String[length];
        for (InspectionIssue issue : issues) {
            int start = Math.max(0, Math.min(issue.startOffset(), length));
            int end = Math.max(start, Math.min(issue.endOffset(), length));
            for (int i = start; i < end; i++) {
                if (severities[i] == null || rank(issue.severity()) > rank(severities[i])) {
                    severities[i] = issue.severity();
                    messages[i] = issue.message();
                }
            }
        }
        List<Run> runs = new ArrayList<>();
        int i = 0;
        while (i < length) {
            if (severities[i] == null) {
                i++;
                continue;
            }
            int start = i;
            Severity severity = severities[i];
            String message = messages[i];
            i++;
            while (i < length && severities[i] == severity
                    && java.util.Objects.equals(messages[i], message)) {
                i++;
            }
            runs.add(new Run(start, i, severity, message));
        }
        runs.sort(Comparator.comparingInt(Run::start));
        return List.copyOf(runs);
    }

    private static int rank(Severity severity) {
        return switch (severity) {
            case ERROR -> 3;
            case WARNING -> 2;
            case WEAK_WARNING -> 1;
        };
    }
}
