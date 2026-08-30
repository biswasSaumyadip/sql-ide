package com.lazaro.sqlide.core.inspection;

import java.util.Objects;

/**
 * One finding over a character range in the editor buffer.
 *
 * @param startOffset inclusive UTF-16 offset
 * @param endOffset   exclusive UTF-16 offset
 * @param message     human-readable description for tooltips
 * @param severity    how the span is styled
 */
public record InspectionIssue(int startOffset, int endOffset, String message, Severity severity) {

    public InspectionIssue {
        if (startOffset < 0 || endOffset < startOffset) {
            throw new IllegalArgumentException(
                    "Invalid range [%d, %d)".formatted(startOffset, endOffset));
        }
        message = Objects.requireNonNullElse(message, "").strip();
        severity = Objects.requireNonNull(severity, "severity");
    }

    public int length() {
        return endOffset - startOffset;
    }

    /** CSS class applied to the decorated span in the editor. */
    public String styleClass() {
        return switch (severity) {
            case ERROR -> "sql-error-underline";
            case WARNING -> "sql-warning-underline";
            case WEAK_WARNING -> "sql-weak-warning-underline";
        };
    }
}
