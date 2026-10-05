package io.github.flipcover.controls;

/** Eight region springs in dp; icon count never changes the solver or allocates frame state. */
final class LauncherForce {
    static final int BACKGROUND = 0, CATALOG = 1, GRID = 2, HEADER = 3, SIDEBAR = 4, SETTING = 5, DOCK = 6, FOLDER = 7, COUNT = 8;
    static final long ENTRY_ESTIMATE_MS = 900, SPLIT_WAIT_MS = BuildConfig.MOTION_LAUNCHER_SPLIT_WAIT_MS, SPLIT_DURATION_MS = BuildConfig.MOTION_LAUNCHER_SPLIT_DURATION_MS;
    static final int[] FROM = {CATALOG, CATALOG, DOCK, SIDEBAR, SIDEBAR, HEADER, GRID, FOLDER}, TO = {DOCK, BACKGROUND, BACKGROUND, CATALOG, SETTING, CATALOG, CATALOG, GRID};
    private static final float[] MASS = {5, 2.8f, 1.5f, 1.4f, 2, .8f, 3.2f, 1.2f}, STIFFNESS = {450, 650, 340, 460, 430, 320, 550, 340}, DAMPING = {70, 51, 23, 29, 37, 17, 53, 20}, LINK = {65, 25, 35, 28, 42, 48, 68, 70}, LIMIT = {1.5f, 4, 7, 4, 8, 5, 5, 6};
    final float[] x = new float[COUNT], y = new float[COUNT], vx = new float[COUNT], vy = new float[COUNT], tx = new float[COUNT], ty = new float[COUNT];
    private final float[] ax = new float[COUNT], ay = new float[COUNT];
    void impulse(int node, float dx, float dy) { vx[node] += clamp(dx, -280, 280) / MASS[node]; vy[node] += clamp(dy, -280, 280) / MASS[node]; }
    void scene(float dx, float dy) { impulse(GRID, -dx * 3, -dy * 3); impulse(DOCK, -dx, -dy); impulse(BACKGROUND, dx * .4f, dy * .4f); }
    void page(float delta) { impulse(GRID, -delta * 3, 0); impulse(DOCK, delta * .4f, 0); impulse(BACKGROUND, delta * .15f, 0); }
    void sidebar(float pull) { ty[SIDEBAR] = rubber(pull, 8); ty[SETTING] = -ty[SIDEBAR] * .25f; }
    void release() { for (int i = 0; i < COUNT; i++) tx[i] = ty[i] = 0; }
    void reset() { for (int i = 0; i < COUNT; i++) x[i] = y[i] = vx[i] = vy[i] = tx[i] = ty[i] = 0; }
    boolean advance(float seconds) {
        float h = Math.min(.032f, Math.max(.001f, seconds)) / 2;
        for (int sub = 0; sub < 2; sub++) {
            for (int i = 0; i < COUNT; i++) { ax[i] = ((tx[i] - x[i]) * STIFFNESS[i] * BuildConfig.MOTION_LAUNCHER_STIFFNESS_SCALE - vx[i] * DAMPING[i] * BuildConfig.MOTION_LAUNCHER_DAMPING_SCALE) / MASS[i]; ay[i] = ((ty[i] - y[i]) * STIFFNESS[i] * BuildConfig.MOTION_LAUNCHER_STIFFNESS_SCALE - vy[i] * DAMPING[i] * BuildConfig.MOTION_LAUNCHER_DAMPING_SCALE) / MASS[i]; }
            for (int edge = 0; edge < FROM.length; edge++) { int a = FROM[edge], b = TO[edge]; float fx = (x[b] - x[a]) * LINK[edge] * 1.35f, fy = (y[b] - y[a]) * LINK[edge] * 1.35f; ax[a] += fx / MASS[a]; ay[a] += fy / MASS[a]; ax[b] -= fx / MASS[b]; ay[b] -= fy / MASS[b]; }
            for (int i = 0; i < COUNT; i++) {
                vx[i] += ax[i] * h; vy[i] += ay[i] * h; x[i] += vx[i] * h; y[i] += vy[i] * h;
                if (Math.abs(x[i]) > LIMIT[i]) { x[i] = Math.copySign(LIMIT[i], x[i]); if (vx[i] * x[i] > 0) vx[i] *= .3f; }
                if (Math.abs(y[i]) > LIMIT[i]) { y[i] = Math.copySign(LIMIT[i], y[i]); if (vy[i] * y[i] > 0) vy[i] *= .3f; }
            }
        }
        boolean moving = false;
        for (int i = 0; i < COUNT; i++) moving |= Math.abs(vx[i]) + Math.abs(vy[i]) + (Math.abs(ax[i]) + Math.abs(ay[i])) * .01f > .08f;
        boolean held = false; for (int i = 0; i < COUNT; i++) held |= tx[i] != 0 || ty[i] != 0;
        if (!moving) for (int i = 0; i < COUNT; i++) { vx[i] = vy[i] = 0; if (!held) x[i] = y[i] = 0; }
        return moving;
    }
    private float strain(int node) { return clamp((Math.abs(vx[node]) - Math.abs(vy[node])) * .0003f, -.065f, .065f); }
    float scaleX(int node) { float strain = strain(node); return 1 + Math.max(0, strain) + Math.min(0, strain) * .65f; }
    float scaleY(int node) { float strain = strain(node); return 1 - Math.min(0, strain) - Math.max(0, strain) * .65f; }
    static float rubber(float distance, float limit) { return Math.copySign(limit * (1 - (float) Math.exp(-Math.abs(distance) / (limit * 3))), distance); }
    private static float clamp(float value, float low, float high) { return Math.max(low, Math.min(high, value)); }
}
