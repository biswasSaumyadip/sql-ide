package com.lazaro.sqlide.ui.components;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InlayPadSupportTest {

    @Test
    void padsAndStripsRoundTrip() {
        String logical = "INSERT INTO t (a, b) VALUES (1, 2)";
        int first = logical.indexOf('1');
        int second = logical.indexOf('2');
        var result = InlayPadSupport.pad(logical, List.of(
                new InlayPadSupport.HintSpec(first, "a", 3),
                new InlayPadSupport.HintSpec(second, "b", 4)));
        assertEquals(2, result.pads().size());
        assertEquals(logical, InlayPadSupport.strip(result.text(), result.pads()));
    }

    @Test
    void mapsCaretThroughPads() {
        String logical = "VALUES (1)";
        int at = logical.indexOf('1');
        var result = InlayPadSupport.pad(logical, List.of(
                new InlayPadSupport.HintSpec(at, "id", 4)));
        int docAtValue = InlayPadSupport.toDocumentOffset(at, result.pads());
        assertEquals('1', result.text().charAt(docAtValue));
        assertEquals(at, InlayPadSupport.toLogicalOffset(docAtValue, result.pads()));
        assertEquals(at, InlayPadSupport.toLogicalOffset(result.pads().getFirst().offset() + 1, result.pads()));
    }
}
