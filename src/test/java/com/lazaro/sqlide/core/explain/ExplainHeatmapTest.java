package com.lazaro.sqlide.core.explain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplainHeatmapTest {

    @Test
    void hotterNodeGetsHigherHeat() {
        ExplainPlanNode hot = ExplainPlanNode.leaf("Seq Scan", "(cost=1000.00 rows=50000)");
        ExplainPlanNode cool = ExplainPlanNode.leaf("Index Scan", "(cost=10.00 rows=1)");
        ExplainPlanNode root = new ExplainPlanNode("Plan", "", List.of(hot, cool));

        assertTrue(ExplainHeatmap.heat(root, hot) > ExplainHeatmap.heat(root, cool));
        assertEquals("explain-heat-high", ExplainHeatmap.heatClass(ExplainHeatmap.heat(root, hot)));
    }
}
