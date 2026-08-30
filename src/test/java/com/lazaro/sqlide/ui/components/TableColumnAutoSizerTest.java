package com.lazaro.sqlide.ui.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TableColumnAutoSizerTest {

    @Test
    void shortValuesAreWiderThanBareCharacterGuess() {
        double width = TableColumnAutoSizer.estimate("status", List.of(
                List.of("Daemon"),
                List.of("Active"),
                List.of("OK")), 0);
        // Must comfortably fit "Daemon" without ellipsis at 12px mono.
        assertTrue(width >= 90, "expected roomy width for short strings, got " + width);
    }

    @Test
    void longValuesIncreaseWidthUpToCap() {
        String longValue = "a".repeat(200);
        double width = TableColumnAutoSizer.estimate("id", List.of(List.of(longValue)), 0);
        assertTrue(width >= 200);
        assertTrue(width <= 480);
    }

    @Test
    void nullCellsCountAsNullLiteral() {
        double withNull = TableColumnAutoSizer.estimate("x", List.of(CollectionsNullRow()), 0);
        double withEmpty = TableColumnAutoSizer.estimate("x", List.of(List.of("")), 0);
        assertTrue(withNull >= withEmpty);
    }

    private static List<String> CollectionsNullRow() {
        return java.util.Arrays.asList((String) null);
    }
}
