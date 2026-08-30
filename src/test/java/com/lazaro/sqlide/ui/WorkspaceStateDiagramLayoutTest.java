package com.lazaro.sqlide.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkspaceStateDiagramLayoutTest {

    @Test
    void clipPrefKeyReplacesUnsafeCharacters() {
        assertEquals("host_3306_game_chars", WorkspaceState.clipPrefKey("host:3306/game/chars"));
        assertEquals("default", WorkspaceState.clipPrefKey("///"));
    }

    @Test
    void parseXyReadsFiniteCoordinates() {
        assertArrayEquals(new double[] {12.5, -8}, WorkspaceState.parseXy("12.5,-8"), 0.0001);
        assertNull(WorkspaceState.parseXy("nope"));
        assertNull(WorkspaceState.parseXy("1,NaN"));
    }

    @Test
    void formatXyRoundTrips() {
        String raw = WorkspaceState.formatXy(40, 64);
        assertArrayEquals(new double[] {40, 64}, WorkspaceState.parseXy(raw), 0.0001);
    }
}
