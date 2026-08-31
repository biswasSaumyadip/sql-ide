package com.lazaro.sqlide.core.runtime;

/**
 * Formats JVM heap usage for the status bar ({@code 254 MB of 2048 MB}).
 */
public final class HeapMemory {

    private static final long MEGABYTE = 1024L * 1024L;

    private HeapMemory() {
    }

    public record Snapshot(long usedBytes, long maxBytes) {
        public String display() {
            return HeapMemory.format(usedBytes, maxBytes);
        }
    }

    public static Snapshot current() {
        Runtime runtime = Runtime.getRuntime();
        long used = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        return new Snapshot(used, runtime.maxMemory());
    }

    public static String format(long usedBytes, long maxBytes) {
        return formatMegabytes(usedBytes) + " of " + formatMegabytes(maxBytes);
    }

    static String formatMegabytes(long bytes) {
        long mb = Math.max(0L, bytes) / MEGABYTE;
        return mb + " MB";
    }
}
