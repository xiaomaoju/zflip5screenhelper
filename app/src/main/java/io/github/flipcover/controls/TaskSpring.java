package io.github.flipcover.controls;

/** Shared task-style damped return for task previews and notification cards. */
final class TaskSpring {
    static final float STRENGTH = BuildConfig.MOTION_TASK_RETURN_STRENGTH;
    static final long DURATION = BuildConfig.MOTION_TASK_RETURN_DURATION_MS;
    private TaskSpring() { }
    static float progress(float time) {
        if (time <= 0) return 0;
        if (time >= 1) return 1;
        double phase = 5 * Math.PI * time;
        return (float) (1 - Math.exp(-6 * time) * (Math.cos(phase) + 6 / (5 * Math.PI) * Math.sin(phase)));
    }
    /** Initial velocity response in seconds, using the same damping and two-return phase. */
    static float velocityOffset(float time) {
        if (time <= 0 || time >= 1) return 0;
        return (float) (DURATION / 1000.0 * Math.exp(-6 * time) * Math.sin(5 * Math.PI * time) / (5 * Math.PI));
    }
}
