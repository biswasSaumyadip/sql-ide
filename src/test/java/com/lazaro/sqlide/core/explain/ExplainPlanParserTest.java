package com.lazaro.sqlide.core.explain;

import com.lazaro.sqlide.core.db.ConnectionConfig;
import com.lazaro.sqlide.core.db.QueryResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExplainPlanParserTest {

    @Test
    @DisplayName("indented text plans become a nested tree")
    void parsesIndentedText() {
        List<String> lines = List.of(
                "Hash Join",
                "  Seq Scan on users",
                "  Index Scan on orders");
        ExplainPlanNode root = ExplainPlanParser.parseIndentedText(lines);
        assertEquals("Hash Join", root.label());
        assertEquals(2, root.children().size());
        assertEquals("Seq Scan on users", root.children().get(0).label());
        assertEquals("Index Scan on orders", root.children().get(1).label());
    }

    @Test
    @DisplayName("cost suffixes move into the detail column")
    void splitsCostDetail() {
        ExplainPlanNode root = ExplainPlanParser.parseIndentedText(List.of(
                "Seq Scan on widget  (cost=0.00..1.10 rows=10)"));
        assertEquals("Seq Scan on widget", root.label());
        assertTrue(root.detail().contains("cost="));
    }

    @Test
    @DisplayName("classic MySQL tabular EXPLAIN nests by id")
    void parsesMysqlTabular() {
        List<String> row1 = new java.util.ArrayList<>(List.of("1", "PRIMARY", "users", "ALL", "key", "100", "extra"));
        row1.set(4, null);
        row1.set(6, null);
        List<String> row2 = new java.util.ArrayList<>(List.of(
                "2", "DEPENDENT SUBQUERY", "orders", "ref", "user_id", "3", "Using where"));
        QueryResult result = QueryResult.ofRows(
                List.of("id", "select_type", "table", "type", "key", "rows", "Extra"),
                List.of(row1, row2),
                12L);
        assertTrue(ExplainPlanParser.isClassicMysqlExplain(result));
        ExplainPlanNode plan = ExplainPlanParser.parseMysqlTabular(result);
        assertEquals("EXPLAIN", plan.label());
        assertFalse(plan.children().isEmpty());
        assertTrue(plan.children().getFirst().label().contains("users"));
    }

    @Test
    @DisplayName("tryParse recognizes single-column plan dumps")
    void tryParseTextDump() {
        QueryResult result = QueryResult.ofRows(
                List.of("PLAN"),
                List.of(
                        List.of("SELECT"),
                        List.of("    TABLE SCAN ON WIDGET")),
                5L);
        ExplainPlanNode plan = ExplainPlanParser.tryParse(result).orElseThrow();
        assertEquals("SELECT", plan.label());
        assertEquals(1, plan.children().size());
        assertTrue(plan.children().getFirst().label().contains("TABLE SCAN"));
    }

    @Test
    @DisplayName("ExplainSql wraps by dialect and skips already-explained SQL")
    void wrapByDialect() {
        assertEquals(
                "EXPLAIN SELECT 1",
                ExplainSql.wrap("SELECT 1", ConnectionConfig.Driver.H2_MEMORY, true));
        assertTrue(ExplainSql.wrap("SELECT 1", ConnectionConfig.Driver.POSTGRESQL, true)
                .startsWith("EXPLAIN (ANALYZE"));
        assertEquals(
                "EXPLAIN SELECT 1",
                ExplainSql.wrap("EXPLAIN SELECT 1", ConnectionConfig.Driver.MYSQL, false));
    }
}
