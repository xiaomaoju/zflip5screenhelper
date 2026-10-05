package com.kyant.backdrop.catalog.components;

/** Ordered horizontal release physics. Pixel units; the host owns the clock, drawing and input. */
public final class LiquidTensionMotion {
    public static final int MAX_NODES = 7;
    private final float[] positions = new float[MAX_NODES], velocities = new float[MAX_NODES];
    private final boolean[] detached = new boolean[MAX_NODES];
    private int count;
    private float spacing, travel, restingGap, breakGap, overshoot, distance, inputSpeed;
    private boolean reversing;

    /** Nodes are ordered by first exposure, independently of the host's registration indices. */
    public void configure(int count, float spacing, float travel, float restingGap, float breakGap, float overshoot) {
        if (count < 0 || count > MAX_NODES || spacing <= 0 || travel < 0 || restingGap <= breakGap || breakGap < 0 || overshoot < 0) throw new IllegalArgumentException("Invalid liquid tension motion geometry");
        if (this.count == count && this.spacing == spacing && this.travel == travel && this.restingGap == restingGap && this.breakGap == breakGap && this.overshoot == overshoot) return;
        this.count = count; this.spacing = spacing; this.travel = travel; this.restingGap = restingGap; this.breakGap = breakGap; this.overshoot = overshoot;
        snap(0);
    }

    public int getCount() { return count; }
    public float position(int node) { return positions[node]; }
    public float velocity(int node) { return velocities[node]; }
    public boolean detached(int node) { return detached[node]; }
    public float emergence(int node) { return travel == 0 ? 1 : Math.max(0, Math.min(1, (positions[node] + travel) / travel)); }
    /** The whole decorative button grows late in its release, not just its glass outline. */
    public float scale(int node) { return circleScale((emergence(node) - .65f) / .35f); }
    public static float circleScale(float progress) {
        float t = Math.max(0, Math.min(1, progress));
        return .2f + .8f * t * t * (3 - 2 * t);
    }
    public boolean ready() {
        for (int i = 0; i < count; i++) if (!detached[i] || Math.abs(positions[i]) > .03f * spacing || Math.abs(velocities[i]) > .15f * spacing) return false;
        return count > 0;
    }

    public void drive(float distance, float speed) {
        reversing = distance < this.distance - .001f;
        this.distance = Math.max(0, distance); inputSpeed = Math.max(0, Math.min(spacing * 50, speed));
        if (reversing) for (int i = count - 1; i >= 0; i--) if (gap(i) < breakGap - spacing * .02f) detached[i] = false;
    }

    /** Cancellation/reduced motion ends immediately at the host's chosen stable pose. */
    public void snap(float distance) {
        this.distance = Math.max(0, distance); inputSpeed = 0; reversing = false;
        java.util.Arrays.fill(detached, false);
        for (int i = 0; i < count; i++) {
            positions[i] = target(i); velocities[i] = 0;
            detached[i] = gap(i) > breakGap && (i == 0 || detached[i - 1]);
        }
    }

    public boolean moving() {
        for (int i = 0; i < count; i++) if (!detached[i] && (i == 0 || detached[i - 1]) && gap(i) > breakGap || Math.abs(target(i) - positions[i]) > spacing * .0008f || Math.abs(velocities[i]) > spacing * .006f) return true;
        return false;
    }

    /** Bounded substeps keep the response consistent at 30/60/120Hz and after a delayed frame. */
    public boolean advance(float seconds) {
        float remaining = Math.max(0, Math.min(.064f, seconds));
        // A newly detached node unlocks its successor on the next frame, never this frame.
        int unlocked = 0;
        while (unlocked < count && detached[unlocked]) unlocked++;
        while (remaining > 0) {
            float dt = Math.min(remaining, 1f / 240); remaining -= dt;
            for (int i = 0; i < count; i++) {
                float goal = i > unlocked ? -travel : target(i);
                velocities[i] += ((goal - positions[i]) * 650 - velocities[i] * 28) * dt;
                positions[i] += velocities[i] * dt;
                if (positions[i] > overshoot) { positions[i] = overshoot; velocities[i] = Math.min(0, velocities[i]); }
                if (!detached[i] && i <= unlocked && gap(i) > breakGap) {
                    detached[i] = true;
                    velocities[i] = Math.max(velocities[i], spacing * 2.2f + inputSpeed * .035f);
                }
                if (detached[i] && !reversing) {
                    float minimum = Math.min(overshoot, breakGap - (distance - (i + 1) * spacing + restingGap) + spacing * .001f);
                    if (positions[i] < minimum) { positions[i] = minimum; velocities[i] = Math.max(0, velocities[i]); }
                }
            }
        }
        for (int i = 0; i < count; i++) if (Math.abs(target(i) - positions[i]) <= spacing * .0008f && Math.abs(velocities[i]) <= spacing * .006f) { positions[i] = target(i); velocities[i] = 0; }
        return moving();
    }

    private float gap(int node) { return distance - (node + 1) * spacing + restingGap + positions[node]; }
    private float target(int node) {
        if (node > 0 && !detached[node - 1]) return -travel;
        if (detached[node] && !reversing) return Math.max(0, Math.min(overshoot, breakGap - (distance - (node + 1) * spacing + restingGap) + spacing * .001f));
        float local = Math.max(0, distance - node * spacing), phase = Math.max(0, Math.min(1, (local / spacing - .18f) / .7f));
        float ease = phase * phase * phase * (phase * (phase * 6 - 15) + 10);
        return -travel * (1 - ease) - Math.min(local, spacing * .18f) * .12f * (1 - ease);
    }
}
