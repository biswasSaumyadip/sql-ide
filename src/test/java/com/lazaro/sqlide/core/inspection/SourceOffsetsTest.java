package com.lazaro.sqlide.core.inspection;

import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.PlainSelect;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceOffsetsTest {

    @Test
    void absoluteOffsetsMapToExactIdentifier() throws Exception {
        String sql = "SELECT nope FROM users";
        PlainSelect plain = (PlainSelect) CCJSqlParserUtil.parse(sql);
        Object column = plain.getSelectItems().getFirst().getExpression();
        int[] range = SourceOffsets.rangeOf(column, sql, "nope");
        assertEquals("nope", sql.substring(range[0], range[1]));
        assertEquals(7, range[0]);
        assertEquals(11, range[1]);
    }

    @Test
    void prefersWordInsideNodeSpan() {
        String sql = "UPDATE users SET email = 'x'";
        int[] range = SourceOffsets.rangeOf(null, sql, "UPDATE");
        assertEquals("UPDATE", sql.substring(range[0], range[1]));
        assertEquals(0, range[0]);
        assertEquals(6, range[1]);
    }

    @Test
    void fallbackIsFirstTokenNotWholeBuffer() {
        String sql = "   SELECT";
        int[] range = SourceOffsets.rangeOf(null, sql, null);
        assertEquals("SELECT", sql.substring(range[0], range[1]));
    }
}

class InspectionHighlightsTest {

    @Test
    void strongerSeverityWinsOnOverlap() {
        List<InspectionIssue> issues = List.of(
                new InspectionIssue(0, 5, "weak", Severity.WEAK_WARNING),
                new InspectionIssue(2, 4, "err", Severity.ERROR));
        List<InspectionHighlights.Run> runs = InspectionHighlights.merge(issues, 5);
        assertEquals(3, runs.size());
        assertEquals(Severity.WEAK_WARNING, runs.get(0).severity());
        assertEquals(Severity.ERROR, runs.get(1).severity());
        assertEquals("err", runs.get(1).message());
        assertEquals(Severity.WEAK_WARNING, runs.get(2).severity());
    }

    @Test
    void emptyIssuesYieldNoRuns() {
        assertTrue(InspectionHighlights.merge(List.of(), 10).isEmpty());
    }
}
