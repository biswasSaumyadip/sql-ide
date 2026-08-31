package com.lazaro.sqlide.core.runtime;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeapMemoryTest {

    @Test
    void formatsUsedAndMaxAsMegabytes() {
        long used = 254L * 1024 * 1024;
        long max = 2048L * 1024 * 1024;
        assertEquals("254 MB of 2048 MB", HeapMemory.format(used, max));
    }

    @Test
    void floorsPartialMegabytes() {
        assertEquals("0 MB", HeapMemory.formatMegabytes(1024 * 1024 - 1));
        assertEquals("1 MB", HeapMemory.formatMegabytes(1024 * 1024));
    }

    @Test
    void currentSnapshotIsNonNegative() {
        HeapMemory.Snapshot snapshot = HeapMemory.current();
        assertTrue(snapshot.usedBytes() >= 0);
        assertTrue(snapshot.maxBytes() >= snapshot.usedBytes());
        assertTrue(snapshot.display().contains(" of "));
        assertTrue(snapshot.display().endsWith(" MB"));
    }
}
