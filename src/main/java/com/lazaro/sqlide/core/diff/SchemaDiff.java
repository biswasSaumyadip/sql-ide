package com.lazaro.sqlide.core.diff;

import java.util.List;
import java.util.Objects;

/**
 * Structural difference between two tables (or schemas).
 */
public final class SchemaDiff {

    public enum Kind {
        COLUMN_ADDED,
        COLUMN_REMOVED,
        COLUMN_TYPE_CHANGED,
        COLUMN_NULLABLE_CHANGED,
        PRIMARY_KEY_CHANGED,
        INDEX_ADDED,
        INDEX_REMOVED,
        FOREIGN_KEY_ADDED,
        FOREIGN_KEY_REMOVED
    }

    public record Change(Kind kind, String path, String leftDetail, String rightDetail) {
        public Change {
            Objects.requireNonNull(kind, "kind");
            path = Objects.requireNonNullElse(path, "");
            leftDetail = Objects.requireNonNullElse(leftDetail, "");
            rightDetail = Objects.requireNonNullElse(rightDetail, "");
        }

        public String summary() {
            return switch (kind) {
                case COLUMN_ADDED -> "ADD COLUMN " + path + " " + rightDetail;
                case COLUMN_REMOVED -> "DROP COLUMN " + path;
                case COLUMN_TYPE_CHANGED -> "MODIFY " + path + ": " + leftDetail + " \u2192 " + rightDetail;
                case COLUMN_NULLABLE_CHANGED -> "NULLABLE " + path + ": " + leftDetail + " \u2192 " + rightDetail;
                case PRIMARY_KEY_CHANGED -> "PRIMARY KEY: " + leftDetail + " \u2192 " + rightDetail;
                case INDEX_ADDED -> "ADD INDEX " + path + " " + rightDetail;
                case INDEX_REMOVED -> "DROP INDEX " + path;
                case FOREIGN_KEY_ADDED -> "ADD FK " + path + " " + rightDetail;
                case FOREIGN_KEY_REMOVED -> "DROP FK " + path;
            };
        }
    }

    private final String leftName;
    private final String rightName;
    private final List<Change> changes;

    public SchemaDiff(String leftName, String rightName, List<Change> changes) {
        this.leftName = Objects.requireNonNullElse(leftName, "left");
        this.rightName = Objects.requireNonNullElse(rightName, "right");
        this.changes = List.copyOf(Objects.requireNonNullElse(changes, List.of()));
    }

    public String leftName() {
        return leftName;
    }

    public String rightName() {
        return rightName;
    }

    public List<Change> changes() {
        return changes;
    }

    public boolean isEmpty() {
        return changes.isEmpty();
    }
}
