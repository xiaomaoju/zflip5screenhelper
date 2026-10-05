package io.github.flipcover.controls;

/** Device-independent distance quantizer. No timers or motion after input stops. */
final class InputSteps {
    private float x, y;
    private int axis;
    void reset() { x = y = 0; axis = 0; }
    int move(float dx, float dy, float threshold) {
        if (!Float.isFinite(dx) || !Float.isFinite(dy) || threshold <= 0) { reset(); return 0; }
        x += dx; y += dy;
        if (axis == 0 || (axis == 1 && Math.abs(y) > Math.abs(x) * 1.5f) || (axis == 2 && Math.abs(x) > Math.abs(y) * 1.5f)) axis = Math.abs(x) >= Math.abs(y) ? 1 : 2;
        float distance = axis == 1 ? x : y;
        if (Math.abs(distance) < threshold) return 0;
        int direction = axis == 1 ? (distance < 0 ? 17 : 66) : (distance < 0 ? 33 : 130);
        if (axis == 1) { x = Math.copySign(Math.min(Math.abs(x) - threshold, threshold), x); y = 0; }
        else { y = Math.copySign(Math.min(Math.abs(y) - threshold, threshold), y); x = 0; }
        return direction;
    }
}
