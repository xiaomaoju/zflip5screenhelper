package io.github.flipcover.controls;

/** Card-local dp bounds. Runtime chrome reserves each physical edge once for the whole grid. */
final class WidgetSafeArea {
    record Frame(float left, float top, float width, float height) {
        boolean inset(float outerWidth, float outerHeight) { return left > 0 || top > 0 || width < outerWidth || height < outerHeight; }
    }
    static Frame fit(float cardWidth, float cardHeight, int displayWidth, int displayHeight, float density, DockGeometry.Box safe) {
        if (safe == null || density <= 0 || displayWidth <= 0 || displayHeight <= 0) return new Frame(0, 0, cardWidth, cardHeight);
        float left = Math.max(0, safe.x()) / density, top = Math.max(0, safe.y()) / density;
        float right = Math.max(0, displayWidth - safe.right()) / density, bottom = Math.max(0, displayHeight - safe.bottom()) / density;
        // A card may be smaller than the screen. Reserve measured edge depths, never scale
        // or mirror the physical notch, and keep an explicit empty frame when it cannot fit.
        return new Frame(Math.min(cardWidth, left), Math.min(cardHeight, top), Math.max(0, cardWidth - left - right), Math.max(0, cardHeight - top - bottom));
    }
}
