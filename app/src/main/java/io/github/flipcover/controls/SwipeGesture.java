package io.github.flipcover.controls;

/** Pure gesture state, shared by the native dock and local regression tests. */
public final class SwipeGesture {
    public enum Result { TAP, PREVIOUS, NEXT, CANCEL }
    private final float slop;
    private final float threshold;
    private boolean moved;
    private boolean wrongAxis;
    private float primary;
    public SwipeGesture(float slop, float threshold) { this.slop = slop; this.threshold = threshold; }
    public void reset() { moved = false; wrongAxis = false; primary = 0; }
    public boolean moved() { return moved; }
    public boolean armed() { return !wrongAxis && Math.abs(primary) >= threshold; }
    public float move(float along, float across) {
        if (Math.hypot(along, across) > slop) moved = true;
        if (!moved || wrongAxis) return 0;
        if (Math.abs(primary) < threshold && Math.abs(across) > Math.abs(along) * 1.25f && Math.abs(across) > slop) wrongAxis = true;
        primary = along;
        if (wrongAxis) return 0;
        float distance = Math.abs(along);
        return Math.signum(along) * (Math.min(distance, threshold) * .25f + Math.max(0, distance - threshold));
    }
    public Result finish() {
        if (!moved) return Result.TAP;
        if (!armed()) return Result.CANCEL;
        return primary < 0 ? Result.NEXT : Result.PREVIOUS;
    }
}
