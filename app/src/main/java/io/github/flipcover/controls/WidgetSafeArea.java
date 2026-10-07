package io.github.flipcover.controls;

/** Card-local dp bounds. Runtime chrome reserves each physical edge once for the whole grid. */
final class WidgetSafeArea {
    record Frame(float left, float top, float width, float height) {
        boolean inset(float outerWidth, float outerHeight) { return left > 0 || top > 0 || width < outerWidth || height < outerHeight; }
    }
    static Frame canvas(float cardWidth, float cardHeight, float displayWidth, float displayHeight, boolean quarterTurn) {
        // Samsung retains the unrotated full-screen options on quarter turns. Match
        // both measured axes (options are integer dp); never stretch a smaller card.
        if (quarterTurn && Math.abs(cardWidth - displayHeight) <= 1 && Math.abs(cardHeight - displayWidth) <= 1) return new Frame(0, 0, displayWidth, displayHeight);
        return new Frame(0, 0, Math.max(1, Math.min(displayWidth, cardWidth)), Math.max(1, Math.min(displayHeight, cardHeight)));
    }
    static Frame fit(float cardWidth, float cardHeight, int displayWidth, int displayHeight, float density, DockGeometry.Box safe) {
        if (safe == null || density <= 0 || displayWidth <= 0 || displayHeight <= 0) return new Frame(0, 0, cardWidth, cardHeight);
        float left = Math.max(0, safe.x()) / density, top = Math.max(0, safe.y()) / density;
        float right = Math.max(0, displayWidth - safe.right()) / density, bottom = Math.max(0, displayHeight - safe.bottom()) / density;
        // A card may be smaller than the screen. Reserve measured edge depths, never scale
        // or mirror the physical notch, and keep an explicit empty frame when it cannot fit.
        return new Frame(Math.min(cardWidth, left), Math.min(cardHeight, top), Math.max(0, cardWidth - left - right), Math.max(0, cardHeight - top - bottom));
    }
    static Frame fit(float cardWidth, float cardHeight, int displayWidth, int displayHeight, float density, DockGeometry.Box safe, DockGeometry.Box host) {
        if (host == null || density <= 0 || host.width() <= 0 || host.height() <= 0 || host.x() < 0 || host.y() < 0 || host.right() > displayWidth || host.bottom() > displayHeight
            || host.equals(new DockGeometry.Box(0, 0, displayWidth, displayHeight))) return fit(cardWidth, cardHeight, displayWidth, displayHeight, density, safe);
        boolean screenSized = Math.abs(cardWidth - displayWidth / density) <= 1 && Math.abs(cardHeight - displayHeight / density) <= 1;
        boolean hostSized = Math.abs(cardWidth - host.width() / density) <= 1 && Math.abs(cardHeight - host.height() / density) <= 1;
        // Only a full-host card has a known origin. Samsung can retain full-display
        // options after moving its host below the notch; do not move smaller cards.
        if (!screenSized && !hostSized) return fit(cardWidth, cardHeight, displayWidth, displayHeight, density, safe);
        DockGeometry.Box area = safe == null ? host : safe;
        int left = Math.max(0, Math.min(host.width(), area.x() - host.x()));
        int top = Math.max(0, Math.min(host.height(), area.y() - host.y()));
        int right = Math.max(left, Math.min(host.width(), area.right() - host.x()));
        int bottom = Math.max(top, Math.min(host.height(), area.bottom() - host.y()));
        return new Frame(left / density, top / density, (right - left) / density, (bottom - top) / density);
    }
}
