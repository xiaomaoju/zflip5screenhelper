package io.github.flipcover.controls;

import android.view.View;

/** A group owns its shared material; ordinary surfaces keep input, content and fallback drawing. */
interface SharedGlassHost {
    void glass(PanelGlassSession session);
    View glassBody();
    default GlassSurface.Role glassBodyRole() { return GlassSurface.Role.CARD; }
    View[] glassActions();
    default float glassOffsetX() { return 0; }
    default float glassOffsetY() { return 0; }
    boolean drawsSharedGlass(View surface);
    default float independentGlassAlpha(View surface) { return drawsSharedGlass(surface) ? 0 : 1; }
}
