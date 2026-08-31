package com.lazaro.sqlide.core.editor;

/**
 * Maps document lines onto a vertical scrollbar / minimap track so error ticks
 * land at the same relative position as in the editor.
 */
public final class EditorScrollAnnotations {

    private EditorScrollAnnotations() {
    }

    /**
     * {@code 0} at the first line, approaching {@code 1} at the last. A single-line
     * document maps to the top of the track.
     */
    public static double lineFraction(int lineIndex, int lineCount) {
        if (lineCount <= 1) {
            return 0;
        }
        int index = Math.clamp(lineIndex, 0, lineCount - 1);
        return (double) index / lineCount;
    }

    public static double markerY(double trackHeight, double markerHeight, int lineIndex, int lineCount) {
        double usable = Math.max(0, trackHeight - markerHeight);
        return lineFraction(lineIndex, lineCount) * usable;
    }

    public static int lineAtY(double y, double trackHeight, int lineCount) {
        if (lineCount <= 0 || trackHeight <= 0) {
            return 0;
        }
        int line = (int) (y / trackHeight * lineCount);
        return Math.clamp(line, 0, lineCount - 1);
    }

    /**
     * Short buffers keep the minimap collapsed so an empty gutter is not reserved
     * on the right of the editor. Longer scripts reveal it automatically.
     */
    public static final int MINIMAP_AUTO_SHOW_LINES = 40;

    public static boolean shouldAutoShowMinimap(int lineCount) {
        return lineCount >= MINIMAP_AUTO_SHOW_LINES;
    }
}
