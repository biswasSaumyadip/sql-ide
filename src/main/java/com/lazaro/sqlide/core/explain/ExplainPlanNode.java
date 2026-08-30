package com.lazaro.sqlide.core.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * One node in an execution plan tree. Free of JavaFX types so parsers can be
 * tested headlessly.
 *
 * @param label    primary line shown in the tree (operator / table / step)
 * @param detail   secondary text (cost, rows, filter), may be blank
 * @param children nested plan steps, possibly empty
 */
public record ExplainPlanNode(String label, String detail, List<ExplainPlanNode> children) {

    public ExplainPlanNode {
        label = Objects.requireNonNullElse(label, "").strip();
        detail = Objects.requireNonNullElse(detail, "").strip();
        children = List.copyOf(Objects.requireNonNullElse(children, List.of()));
    }

    public static ExplainPlanNode leaf(String label, String detail) {
        return new ExplainPlanNode(label, detail, List.of());
    }

    public ExplainPlanNode withChildren(List<ExplainPlanNode> newChildren) {
        return new ExplainPlanNode(label, detail, newChildren);
    }

    /** Mutable builder used while parsing indented text plans. */
    public static final class Builder {
        private final String label;
        private final String detail;
        private final List<Builder> children = new ArrayList<>();

        public Builder(String label, String detail) {
            this.label = label;
            this.detail = detail;
        }

        public void add(Builder child) {
            children.add(child);
        }

        public ExplainPlanNode build() {
            List<ExplainPlanNode> built = new ArrayList<>(children.size());
            for (Builder child : children) {
                built.add(child.build());
            }
            return new ExplainPlanNode(label, detail, built);
        }
    }
}
