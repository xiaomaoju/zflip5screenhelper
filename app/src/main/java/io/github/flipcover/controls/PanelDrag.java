package io.github.flipcover.controls;

/** Locks a gesture to inward motion; coordinates and velocity are display pixels. */
final class PanelDrag {
    private final int edge;
    private final float slop;
    private boolean active, rejected;
    PanelDrag(int edge, float slop) { this.edge = edge; this.slop = slop; }
    float move(float dx, float dy) {
        float inward = inward(edge, dx, dy), along = Math.abs(edge == DockGeometry.TOP || edge == DockGeometry.BOTTOM ? dx : dy);
        if (!active && !rejected) {
            if (inward > slop * .7f && inward >= along * .65f) active = true;
            else if (along > slop * 2.5f && along > Math.max(slop, inward) * 2 || inward < -slop * 1.5f) rejected = true;
        }
        return active ? inward : 0;
    }
    boolean active() { return active; }
    static float progress(float start, float distance, float extent) {
        return Math.max(0, Math.min(1, start + distance / Math.max(1, extent)));
    }
    /** Align a fresh panel's leading edge with the actual DOWN in display coordinates. */
    static float entryStart(int edge, float originY, float frameTop, float extent) {
        return edge == DockGeometry.TOP ? progress(0, originY - frameTop, extent) : entryStart(originY, frameTop, extent);
    }
    static float entryStart(float originY, float frameTop, float extent) {
        return progress(0, frameTop + extent - originY, extent);
    }
    static float inward(int edge, float dx, float dy) {
        return switch (edge) { case DockGeometry.BOTTOM -> -dy; case DockGeometry.TOP -> dy; case DockGeometry.LEFT -> dx; default -> -dx; };
    }
    static boolean shouldOpen(float distance, float extent, float velocity, float density) {
        if (extent <= 0) return false;
        if (velocity < -450 * density) return false;
        return distance >= Math.min(extent * .22f, 40 * density) || distance >= 12 * density && velocity >= 450 * density;
    }
    static boolean shouldDismissDock(float distance, float height, float velocity, float minimumFlingVelocity) {
        return distance > height * .4f || distance > 0 && velocity >= minimumFlingVelocity;
    }
}
