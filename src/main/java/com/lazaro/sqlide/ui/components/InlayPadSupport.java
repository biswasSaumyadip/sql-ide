package com.lazaro.sqlide.ui.components;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Inserts / strips artificial spaces that reserve horizontal room for inlay hint
 * overlays, without changing the logical SQL used for execution.
 */
final class InlayPadSupport {

    record Pad(int offset, int spaces, String label) {
        Pad {
            if (spaces < 1) {
                throw new IllegalArgumentException("spaces");
            }
        }
    }

    private InlayPadSupport() {
    }

    /** Removes known pad runs (only when those ranges are still blank). */
    static String strip(String text, List<Pad> pads) {
        if (text == null) {
            return "";
        }
        if (pads == null || pads.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        List<Pad> descending = new ArrayList<>(pads);
        descending.sort(Comparator.comparingInt(Pad::offset).reversed());
        for (Pad pad : descending) {
            int start = pad.offset();
            int end = start + pad.spaces();
            if (start < 0 || end > sb.length()) {
                continue;
            }
            if (isBlank(sb, start, end)) {
                sb.delete(start, end);
            }
        }
        return sb.toString();
    }

    /**
     * Builds a padded document from logical SQL and hint offsets (logical coordinates).
     * Hints must be sorted by ascending offset.
     */
    static Result pad(String logical, List<HintSpec> hints) {
        String source = logical == null ? "" : logical;
        if (hints == null || hints.isEmpty()) {
            return new Result(source, List.of());
        }
        StringBuilder out = new StringBuilder(source.length() + hints.size() * 8);
        List<Pad> pads = new ArrayList<>(hints.size());
        int cursor = 0;
        for (HintSpec hint : hints) {
            int at = Math.max(0, Math.min(hint.logicalOffset(), source.length()));
            if (at < cursor) {
                continue;
            }
            out.append(source, cursor, at);
            int spaces = Math.max(1, hint.spaces());
            pads.add(new Pad(out.length(), spaces, hint.label()));
            out.append(" ".repeat(spaces));
            cursor = at;
        }
        out.append(source, cursor, source.length());
        return new Result(out.toString(), List.copyOf(pads));
    }

    static int toLogicalOffset(int documentOffset, List<Pad> pads) {
        if (pads == null || pads.isEmpty()) {
            return documentOffset;
        }
        int logical = documentOffset;
        for (Pad pad : pads) {
            if (documentOffset <= pad.offset()) {
                break;
            }
            if (documentOffset >= pad.offset() + pad.spaces()) {
                logical -= pad.spaces();
            } else {
                // Caret inside a pad → clamp to the logical insertion point.
                logical = pad.offset();
                for (Pad earlier : pads) {
                    if (earlier.offset() >= pad.offset()) {
                        break;
                    }
                    logical -= earlier.spaces();
                }
                return Math.max(0, logical);
            }
        }
        return Math.max(0, logical);
    }

    static int toDocumentOffset(int logicalOffset, List<Pad> pads) {
        if (pads == null || pads.isEmpty()) {
            return logicalOffset;
        }
        int document = logicalOffset;
        for (Pad pad : pads) {
            if (pad.offset() <= document) {
                document += pad.spaces();
            } else {
                break;
            }
        }
        return document;
    }

    record HintSpec(int logicalOffset, String label, int spaces) {
    }

    record Result(String text, List<Pad> pads) {
    }

    private static boolean isBlank(StringBuilder sb, int start, int end) {
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(sb.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
