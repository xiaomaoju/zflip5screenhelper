package io.github.flipcover.controls;

/** Fixed-size task force graph. Positions are pixels, velocities pixels/second; no Android clock or allocation per frame. */
final class TaskForce {
    static final int CARDS = 4, CLEAR = 4, BACKGROUND = 5;
    final float[] x = new float[6], y = new float[6], vx = new float[6], vy = new float[6], tx = new float[6], ty = new float[6];
    final int[] order = new int[4];
    int count;
    private final float[] ax = new float[6], ay = new float[6];
    float offset, velocity, maximum, step = 1, target;
    boolean snapping, pending;
    private float snapLimit = 18;
    void bounds(float maximum, float step) { this.maximum = Math.max(0, maximum); this.step = Math.max(1, step); offset = clamp(offset); target = clamp(target); }
    private float clamp(float value) { return Math.max(0, Math.min(maximum, value)); }
    float nearest() { return clamp(Math.round(offset / step) * step); }
    int destination() { return Math.round((snapping ? target : clamp(Math.round((offset + velocity / 5) / step) * step)) / step); }
    void interrupt() { snapping = pending = false; velocity = 0; for (int i = 0; i < 6; i++) tx[i] = 0; }
    void release(float speed) { velocity = speed; pending = true; snapLimit = step * (Math.abs(speed) > step * 4 ? .018f : .073f); }
    boolean fastCapture() { return snapping && snapLimit < step * .06f; }
    /** The moving page loads the lenses; the clear node and existing texture share the reaction. */
    void sceneImpulse(float deltaX, float deltaY) {
        float unit = step / 245, ix = Math.max(-900 * unit, Math.min(900 * unit, -deltaX * .45f)), iy = Math.max(-900 * unit, Math.min(900 * unit, -deltaY * .45f));
        for (int i = 0; i < count; i++) { vx[order[i]] += ix; vy[order[i]] += iy; }
        vx[CLEAR] += ix * .60f; vy[CLEAR] += iy * .60f; vx[BACKGROUND] -= ix * .45f; vy[BACKGROUND] -= iy * .45f;
    }
    void drag(float next) {
        offset = clamp(next); float excess = next - offset, unit = step / 245;
        float pull = Math.signum(excess) * 85 * unit * (1 - (float) Math.exp(-Math.abs(excess) / (170 * unit)));
        for (int i = 0; i < 4; i++) tx[i] = pull; tx[CLEAR] = pull * .025f; tx[BACKGROUND] = -pull * .06f;
    }
    void resetNode(int i) { x[i] = y[i] = vx[i] = vy[i] = tx[i] = ty[i] = 0; }
    void reset() { interrupt(); for (int i = 0; i < 6; i++) resetNode(i); }
    boolean advance(float elapsed, boolean held, boolean centerAllowed, int anchor) {
        float dt = Math.min(.032f, Math.max(.001f, elapsed)), unit = step / 245;
        if (!held && !snapping && Math.abs(velocity) > 1) {
            float next = offset + velocity * dt; offset = clamp(next); velocity *= (float) Math.exp(-5 * dt);
            if (offset != next) { for (int i = 0; i < count; i++) vx[order[i]] += Math.max(-180 * unit, Math.min(180 * unit, (next - offset) * 8)); velocity = 0; }
        } else if (Math.abs(velocity) <= 1) velocity = 0;
        if (!held && centerAllowed && !snapping && Math.abs(velocity) <= 300 * unit && (pending || offset != nearest())) {
            pending = false; target = clamp(Math.round((offset + velocity / 5) / step) * step);
            for (int i = 0; i < 6; i++) tx[i] = 0;
            snapping = Math.abs(offset - target) >= .05f * unit || Math.abs(velocity) >= 1;
            if (!snapping) { offset = target; velocity = 0; }
        }
        for (int sub = 0; sub < 4; sub++) {
            float h = dt / 4, load = 0;
            if (snapping && !held) {
                boolean fastCapture = fastCapture();
                float acceleration = -(offset - target + (anchor < 0 ? 0 : x[anchor])) * 484 - velocity * (fastCapture ? 46.2f : 31.68f);
                velocity += acceleration * h; float next = offset + velocity * h; offset = clamp(next); if (next != offset) velocity = 0;
                float loadLimit = (fastCapture ? 1200 : 6000) * unit;
                load = Math.max(-loadLimit, Math.min(loadLimit, -acceleration * (fastCapture ? .12f : .40f)));
            }
            for (int i = 0; i < 6; i++) { ax[i] = (tx[i] - x[i]) * (i == CLEAR ? 60 : i == BACKGROUND ? 100 : 220) - vx[i] * (i == CLEAR ? 9 : i == BACKGROUND ? 12 : 16); ay[i] = (ty[i] - y[i]) * 220 - vy[i] * 16; }
            if (anchor >= 0) { ax[anchor] += load; ax[BACKGROUND] -= load * .18f; }
            for (int i = 0; i < count; i++) { int node = order[i]; if (i > 0) connect(order[i - 1], node, .24f); connect(node, CLEAR, .045f); connect(node, BACKGROUND, .025f); }
            connect(CLEAR, BACKGROUND, .022f);
            for (int i = 0; i < 6; i++) {
                vx[i] += ax[i] * h; vy[i] += ay[i] * h; x[i] += vx[i] * h; y[i] += vy[i] * h;
                if (snapping && !held && i < CARDS) { float limit = Math.max(step * .01f, snapLimit); if (Math.abs(x[i]) > limit) { x[i] = Math.copySign(limit, x[i]); if (vx[i] * x[i] > 0) vx[i] *= .25f; } }
            }
        }
        if (snapping && Math.abs(offset - target) < .06f * unit && Math.abs(velocity) < .5f && (anchor < 0 || Math.abs(x[anchor]) < .03f * unit && Math.abs(vx[anchor]) < .5f)) { offset = target; velocity = 0; snapping = false; }
        boolean moving = snapping || Math.abs(velocity) > 1 || !held && centerAllowed && offset != nearest();
        for (int i = 0; i < 6; i++) moving |= Math.abs(vx[i]) + Math.abs(vy[i]) + Math.abs(x[i] - tx[i]) + Math.abs(y[i] - ty[i]) > .09f * unit;
        if (!moving) settleNodes(); return moving;
    }
    private void connect(int a, int b, float weight) { float k = 220 * weight, fx = (x[b] - x[a]) * k, fy = (y[b] - y[a]) * k; ax[a] += fx; ay[a] += fy; ax[b] -= fx; ay[b] -= fy; }
    void settleNodes() { for (int i = 0; i < 6; i++) { x[i] = tx[i]; y[i] = ty[i]; vx[i] = vy[i] = 0; } }
    void reduced(boolean held, boolean centerAllowed) { if (!held && centerAllowed) { offset = nearest(); velocity = 0; snapping = pending = false; } settleNodes(); }
    static boolean dismiss(float dy, float height, float upwardVelocity, boolean fresh) { return dy < 0 && (-dy > height * .30f || fresh && -dy >= 12 && upwardVelocity <= -600); }
}
