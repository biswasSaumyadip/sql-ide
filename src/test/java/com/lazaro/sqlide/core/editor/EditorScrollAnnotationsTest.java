package com.lazaro.sqlide.core.editor;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorScrollAnnotationsTest {

    @Test
    void firstLineSitsAtTheTopOfTheTrack() {
        assertEquals(0, EditorScrollAnnotations.lineFraction(0, 10));
        assertEquals(0, EditorScrollAnnotations.markerY(200, 4, 0, 50));
    }

    @Test
    void laterLinesMoveDownTheTrack() {
        double top = EditorScrollAnnotations.markerY(200, 4, 0, 100);
        double mid = EditorScrollAnnotations.markerY(200, 4, 50, 100);
        double bottom = EditorScrollAnnotations.markerY(200, 4, 99, 100);
        assertTrue(mid > top);
        assertTrue(bottom > mid);
        assertTrue(bottom <= 200 - 4);
    }

    @Test
    void clickMapsBackToALine() {
        assertEquals(0, EditorScrollAnnotations.lineAtY(0, 100, 20));
        assertEquals(19, EditorScrollAnnotations.lineAtY(99, 100, 20));
        assertEquals(0, EditorScrollAnnotations.lineAtY(10, 0, 20));
    }

    @Test
    void minimapStaysCollapsedForShortDocuments() {
        assertFalse(EditorScrollAnnotations.shouldAutoShowMinimap(1));
        assertFalse(EditorScrollAnnotations.shouldAutoShowMinimap(
                EditorScrollAnnotations.MINIMAP_AUTO_SHOW_LINES - 1));
        assertTrue(EditorScrollAnnotations.shouldAutoShowMinimap(
                EditorScrollAnnotations.MINIMAP_AUTO_SHOW_LINES));
        assertTrue(EditorScrollAnnotations.shouldAutoShowMinimap(400));
    }
}
