package io.github.flipcover.controls;

/** Notification-only rubber band geometry. Distances are pixels; layout and text size stay fixed. */
final class NotificationForce {
    private NotificationForce() { }
    static float pull(float distance, float maximum) { return maximum * (1 - (float) Math.exp(-Math.max(0, distance) * .55f / Math.max(1, maximum))); }
    static float distance(float displacement, float maximum) { return (float) (-maximum / .55f * Math.log(Math.max(.001f, 1 - Math.max(0, displacement) / Math.max(1, maximum)))); }
    static float slope(float displacement, float maximum) { return .55f * (1 - Math.max(0, Math.min(1, displacement / Math.max(1, maximum)))); }
    static float translation(float deformation) { return -deformation * .72f; }
    static float card(float deformation, int edge, float top, float height, float viewport) {
        float progress = Math.max(0, Math.min(1, (top + (edge > 0 ? height : 0)) / Math.max(1, viewport)));
        return -deformation * .28f * (edge < 0 ? progress : 1 - progress);
    }
    static float scrollTarget(float drive, float top, float height, float viewport) {
        float progress = Math.max(0, Math.min(1, (top + height * .5f) / Math.max(1, viewport)));
        return drive * (.12f + .88f * (drive >= 0 ? progress : 1 - progress));
    }
    /** A mounted card's visual response. No clock, View, history or per-frame allocation. */
    static final class Spring {
        float position, velocity, acceleration;
        void reset() { position = velocity = acceleration = 0; }
        void load(float target) { acceleration = (target - position) * 190 - velocity * 17; }
        void advance(float seconds) { velocity += acceleration * seconds; position += velocity * seconds; }
        boolean moving() { return Math.abs(position) > .03f || Math.abs(velocity) > .2f; }
    }
    static void connect(Spring first, Spring second) {
        float tension = (second.position - first.position) * 45.6f;
        first.acceleration += tension; second.acceleration -= tension;
    }
}
