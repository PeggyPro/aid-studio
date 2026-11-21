package com.aid.canvas.runtime;

import java.util.Comparator;
import java.util.List;

/** Deterministic placement of a new rectangle only. Existing nodes are never rearranged. */
public final class CanvasAssistantNodePlacement {
    public static final String AUTO = "_assistantAutoPlacement";
    private CanvasAssistantNodePlacement() { }

    public record Box(double x, double y, double width, double height) {
        public Box {
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(width)
                    || !Double.isFinite(height) || width <= 0 || height <= 0
                    || !Double.isFinite(x + width) || !Double.isFinite(y + height))
                throw new IllegalArgumentException("Invalid node bounds");
        }
    }

    public static Box place(Box preferred, List<Box> occupied, double gap, double rowWidth) {
        if (preferred == null || occupied == null || !Double.isFinite(gap) || gap < 0
                || !Double.isFinite(rowWidth) || rowWidth <= 0) throw new IllegalArgumentException("Invalid placement request");
        List<Box> sorted = occupied.stream().sorted(Comparator.comparingDouble(Box::x).thenComparingDouble(Box::y)
                .thenComparingDouble(Box::width).thenComparingDouble(Box::height)).toList();
        double right = preferred.x() + Math.max(rowWidth, preferred.width());
        if (!Double.isFinite(right)) throw new IllegalArgumentException("Placement bounds overflow");
        double y = preferred.y();
        // Each exhausted row advances beyond at least one obstacle; no pixel-by-pixel search.
        for (int row = 0; row <= occupied.size(); row++) {
            double x = preferred.x(), nextY = Double.POSITIVE_INFINITY;
            for (Box box : sorted) {
                if (box.y() >= y + preferred.height() + gap || box.y() + box.height() + gap <= y) continue;
                if (box.x() >= right + gap || box.x() + box.width() + gap <= preferred.x()) continue;
                nextY = Math.min(nextY, box.y() + box.height() + gap);
                if (box.x() >= x + preferred.width() + gap) return new Box(x, y, preferred.width(), preferred.height());
                if (box.x() + box.width() + gap > x) x = box.x() + box.width() + gap;
                if (x + preferred.width() > right) break;
            }
            if (x + preferred.width() <= right) return new Box(x, y, preferred.width(), preferred.height());
            if (!Double.isFinite(nextY) || nextY <= y) throw new IllegalStateException("Placement did not advance");
            y = nextY;
        }
        throw new IllegalStateException("Placement search exhausted");
    }
}
