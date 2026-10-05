package io.github.flipcover.controls;

/** One dp-space spring for the entire control grid; all other deformation is derived. */
final class ControlElastic {
    static final float LIMIT = 22, TRANSFER = 68, MAXIMUM = 182;
    float load, velocity;
    private float returnLoad, returnGrid, returnWhole;
    private boolean returning;
    static float clamp(float value, float min, float max) { return Math.max(min, Math.min(max, value)); }
    private static float pull(float value) { return Math.signum(value) * LIMIT * (1 - (float) Math.exp(-Math.min(Math.abs(value), TRANSFER) / 20.5f)) / (1 - (float) Math.exp(-TRANSFER / 20.5f)); }
    private static float stretch(float value) { return clamp((Math.abs(value) - TRANSFER) / (MAXIMUM - TRANSFER), 0, 1) * .055f; }
    float grid() { return returning ? returnGrid * progress() : pull(load); }
    float whole() { return returning ? returnWhole * progress() : stretch(load); }
    float progress() { return clamp(load / returnLoad, -.15f, 1); }
    int edge() { return (returning ? returnLoad : load) < 0 ? -1 : 1; }
    boolean moving() { return load != 0 || velocity != 0; }
    void freeze() { velocity = 0; }
    void drag(float delta) {
        load = clamp(load + delta, -MAXIMUM, MAXIMUM); velocity = 0;
        if (returning && (load * returnLoad <= 0 || Math.abs(load) >= Math.abs(returnLoad))) returning = false;
    }
    void release(float speed) {
        if (!returning && Math.abs(load) > .001f) { returning = true; returnLoad = load; returnGrid = pull(load); returnWhole = stretch(load); }
        velocity = clamp(speed, -270, 270);
    }
    boolean advance(float seconds) {
        int count = Math.max(1, (int) Math.ceil(clamp(seconds, 0, .032f) / .008f)); float dt = clamp(seconds, 0, .032f) / count;
        for (int i = 0; i < count && moving(); i++) { velocity += (-BuildConfig.MOTION_CONTROL_STIFFNESS * load - BuildConfig.MOTION_CONTROL_DAMPING * velocity) * dt; load = clamp(load + velocity * dt, -MAXIMUM, MAXIMUM); if (Math.abs(load) < .025f && Math.abs(velocity) < .1f) reset(); }
        return moving();
    }
    void reset() { load = velocity = returnLoad = returnGrid = returnWhole = 0; returning = false; }
}
