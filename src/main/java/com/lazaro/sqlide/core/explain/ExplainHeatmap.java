package com.lazaro.sqlide.core.explain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts numeric cost / row estimates from plan detail text and assigns a
 * 0..1 heat relative to the hottest node in the tree.
 */
public final class ExplainHeatmap {

    private static final Pattern COST = Pattern.compile(
            "cost=([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ROWS = Pattern.compile(
            "rows=([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACTUAL_ROWS = Pattern.compile(
            "actual rows?=([0-9]+(?:\\.[0-9]+)?)", Pattern.CASE_INSENSITIVE);

    private ExplainHeatmap() {
    }

    /** Heat score for {@code node} relative to the max score in {@code root}'s tree. */
    public static double heat(ExplainPlanNode root, ExplainPlanNode node) {
        double max = maxScore(root);
        if (max <= 0) {
            return 0;
        }
        return Math.min(1.0, score(node) / max);
    }

    public static double score(ExplainPlanNode node) {
        if (node == null) {
            return 0;
        }
        String text = (node.label() + " " + node.detail()).toLowerCase(Locale.ROOT);
        OptionalDouble cost = firstNumber(COST, text);
        if (cost.isPresent()) {
            return cost.getAsDouble();
        }
        OptionalDouble actual = firstNumber(ACTUAL_ROWS, text);
        if (actual.isPresent()) {
            return actual.getAsDouble();
        }
        OptionalDouble rows = firstNumber(ROWS, text);
        return rows.orElse(0);
    }

    public static String heatClass(double heat) {
        if (heat >= 0.75) {
            return "explain-heat-high";
        }
        if (heat >= 0.4) {
            return "explain-heat-mid";
        }
        if (heat > 0) {
            return "explain-heat-low";
        }
        return "explain-heat-none";
    }

    private static double maxScore(ExplainPlanNode root) {
        double max = 0;
        for (ExplainPlanNode node : flatten(root)) {
            max = Math.max(max, score(node));
        }
        return max;
    }

    private static List<ExplainPlanNode> flatten(ExplainPlanNode root) {
        List<ExplainPlanNode> all = new ArrayList<>();
        walk(root, all);
        return all;
    }

    private static void walk(ExplainPlanNode node, List<ExplainPlanNode> out) {
        if (node == null) {
            return;
        }
        out.add(node);
        for (ExplainPlanNode child : node.children()) {
            walk(child, out);
        }
    }

    private static OptionalDouble firstNumber(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            try {
                return OptionalDouble.of(Double.parseDouble(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                return OptionalDouble.empty();
            }
        }
        return OptionalDouble.empty();
    }
}
