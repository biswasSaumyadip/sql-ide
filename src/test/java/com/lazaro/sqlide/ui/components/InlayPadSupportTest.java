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

    @Test
    void strippingBeforeRepadPreventsAccumulation() {
        String logical = "VALUES (1, 2)";
        int first = logical.indexOf('1');
        int second = logical.indexOf('2');
        List<InlayPadSupport.HintSpec> specs = List.of(
                new InlayPadSupport.HintSpec(first, "a", 5),
                new InlayPadSupport.HintSpec(second, "b", 5));

        var once = InlayPadSupport.pad(logical, specs);
        // Bug mode: pad again on already-padded text without stripping.
        var stacked = InlayPadSupport.pad(once.text(), specs);
        assertEquals(false, once.text().equals(stacked.text()));

        // Correct mode: strip then pad from logical offsets again.
        String restored = InlayPadSupport.strip(once.text(), once.pads());
        assertEquals(logical, restored);
        var again = InlayPadSupport.pad(restored, specs);
        assertEquals(once.text(), again.text());
        assertEquals(once.pads().size(), again.pads().size());
        assertEquals(once.pads().getFirst().spaces(), again.pads().getFirst().spaces());
    }
}
