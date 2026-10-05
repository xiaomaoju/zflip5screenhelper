package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry;

/** One sidebar material; its bottom settings glyph pulls downward into an independent circle. */
final class LauncherSidebarView extends LinearLayout implements SharedGlassHost {
    /** The scrolling glyphs follow the inset capsule, with a soft top and bottom boundary. */
    static final class Content extends android.widget.ScrollView {
        private LauncherForce force; private Runnable wake; private float fingerY, pull;
        void launcherForce(LauncherForce value, Runnable callback) { force = value; wake = callback; }
        private final android.graphics.Path capsule = new android.graphics.Path();
        private final android.graphics.Paint mask = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Matrix maskPosition = new android.graphics.Matrix();
        private float viewportBottom = Float.POSITIVE_INFINITY;
        void viewportBottom(float value) { if (viewportBottom != value) { viewportBottom = value; invalidate(); } }
        Content(Context context) { super(context); setVerticalScrollBarEnabled(false); setOverScrollMode(OVER_SCROLL_NEVER); setTag("hub-sidebar-scroll"); mask.setBlendMode(android.graphics.BlendMode.DST_IN); }
        @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
            int action = event.getActionMasked();
            if (force != null && ValueAnimator.areAnimatorsEnabled()) {
                if (action == android.view.MotionEvent.ACTION_DOWN) { fingerY = event.getY(); pull = 0; }
                else if (action == android.view.MotionEvent.ACTION_MOVE) {
                    float delta = event.getY() - fingerY; fingerY = event.getY();
                    if (pull != 0 || delta > 0 && !canScrollVertically(-1) || delta < 0 && !canScrollVertically(1)) { float next = pull + delta / getResources().getDisplayMetrics().density; pull = pull == 0 || next * pull > 0 ? next : 0; force.sidebar(pull); wake.run(); }
                } else if (action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL || event.getPointerCount() > 1) { pull = 0; force.release(); wake.run(); }
            }
            return super.dispatchTouchEvent(event);
        }
        @Override protected void onScrollChanged(int l, int t, int oldl, int oldt) { super.onScrollChanged(l, t, oldl, oldt); if (force != null && ValueAnimator.areAnimatorsEnabled()) { force.impulse(LauncherForce.SIDEBAR, 0, -(t - oldt) / getResources().getDisplayMetrics().density * 2); wake.run(); } }
        @Override protected void onDetachedFromWindow() { pull = 0; if (force != null) force.release(); super.onDetachedFromWindow(); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            float band = Math.min(.25f, Ui.dp(getContext(), AppLauncherStyle.railFade(getContext())) / (float) Math.max(1, h));
            mask.setShader(new android.graphics.LinearGradient(0, 0, 0, Math.max(1, h), new int[]{0x00000000, 0xFF000000, 0xFF000000, 0x00000000}, new float[]{0, band, 1 - band, 1}, android.graphics.Shader.TileMode.CLAMP));
        }
        @Override protected void dispatchDraw(Canvas canvas) {
            float height = Math.min(getHeight(), viewportBottom);
            if (getWidth() <= 0 || height <= 0) return;
            int y = getScrollY(), saved = canvas.saveLayer(0, y, getWidth(), y + height, null);
            capsule.reset(); capsule.addRoundRect(0, y, getWidth(), y + height, getWidth() / 2f, getWidth() / 2f, android.graphics.Path.Direction.CW);
            canvas.clipPath(capsule); super.dispatchDraw(canvas);
            maskPosition.setScale(1, height / getHeight()); maskPosition.postTranslate(0, y); mask.getShader().setLocalMatrix(maskPosition);
            canvas.drawRect(0, y, getWidth(), y + height, mask); canvas.restoreToCount(saved);
        }
    }
    private final LiquidTensionGeometry tension = new LiquidTensionGeometry();
    private final View[] actions = new View[1];
    private final Runnable split = this::startSplit;
    private View body;
    private PanelGlassSession glass;
    private ValueAnimator animation;
    private float separation, highlight = 1, viewportBottom = Float.POSITIVE_INFINITY;
    private boolean presented, liquidDrawing;
    private boolean splitPending;
    private LauncherForce force;
    void launcherForce(LauncherForce value, Runnable wake) { force = value; View scroll = findViewWithTag("hub-sidebar-scroll"); if (scroll instanceof Content content) content.launcherForce(value, wake); RuntimeVisuals.launcher(actions[0], value, LauncherForce.SETTING); }

    LauncherSidebarView(Context context) { super(context); setOrientation(VERTICAL); setTag("hub-sidebar"); }
    void surfaces(View body, View setting) { this.body = body; actions[0] = setting; }
    @Override public View glassBody() { return body; }
    @Override public GlassSurface.Role glassBodyRole() { return GlassSurface.Role.LAUNCHER_PANEL; }
    @Override public View[] glassActions() { return actions; }
    @Override public float glassOffsetX() { return force == null ? 0 : force.x[LauncherForce.SIDEBAR] * getResources().getDisplayMetrics().density; }
    @Override public float glassOffsetY() { return force == null ? 0 : force.y[LauncherForce.SIDEBAR] * getResources().getDisplayMetrics().density; }
    @Override public boolean drawsSharedGlass(View surface) { return liquidDrawing && (surface == body || surface == actions[0]); }
    @Override public void glass(PanelGlassSession session) {
        glass = session;
        if (session == null) { stopEntrance(); separation(1); setLiquidDrawing(false); }
        else session.prepareTension();
        invalidate();
    }
    void reveal() {
        if (body == null || !isAttachedToWindow() || !isShown() || splitPending || animation != null || presented && separation == 1) return;
        if (!presented) separation(0);
        presented = true;
        if (!ValueAnimator.areAnimatorsEnabled()) { separation(1); return; }
        splitPending = true; postDelayed(split, LauncherForce.ENTRY_ESTIMATE_MS + LauncherForce.SPLIT_WAIT_MS);
    }
    private void startSplit() {
        splitPending = false;
        if (!isAttachedToWindow() || !isShown() || !ValueAnimator.areAnimatorsEnabled() || glass == null || !glass.ready()) { separation(1); return; }
        animation = ValueAnimator.ofFloat(0, LauncherForce.SPLIT_DURATION_MS + LiquidTensionGeometry.HIGHLIGHT_DURATION_MS); animation.setDuration(LauncherForce.SPLIT_DURATION_MS + LiquidTensionGeometry.HIGHLIGHT_DURATION_MS);
        animation.setInterpolator(new android.view.animation.LinearInterpolator());
        animation.addUpdateListener(frame -> {
            float time = (float) frame.getAnimatedValue(); separation(time / LauncherForce.SPLIT_DURATION_MS);
            float progress = Math.max(0, Math.min(1, (time - LauncherForce.SPLIT_DURATION_MS) / LiquidTensionGeometry.HIGHLIGHT_DURATION_MS));
            highlight = progress * progress * (3 - 2 * progress); invalidate();
        });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator ended) { if (animation != ended) return; animation = null; separation(1); }
        }); animation.start();
    }
    void separation(float value) { separation = Math.max(0, Math.min(1, value)); highlight = separation == 1 ? 1 : 0; updateContentViewport(); invalidate(); }
    float separation() { return separation; }
    float highlight() { return highlight; }
    float settingTranslationY() {
        if (body == null || actions[0] == null) return 0;
        View setting = actions[0], tools = (View) setting.getParent();
        float travel = tools.getTop() + setting.getTop() - (body.getBottom() - setting.getHeight());
        float progress = separation * separation * (3 - 2 * separation);
        return -travel * (1 - progress);
    }
    private void updateContentViewport() {
        if (body == null || actions[0] == null) return;
        View scroll = body.findViewWithTag("hub-sidebar-scroll"), setting = actions[0], tools = (View) setting.getParent();
        if (scroll instanceof Content content) content.viewportBottom(Math.min(viewportBottom, tools.getTop() + setting.getTop() + settingTranslationY() - Ui.dp(getContext(), AppLauncherStyle.RAIL_GAP)) - body.getTop() - scroll.getTop());
    }
    void viewportBottom(float value) { if (viewportBottom == value) return; viewportBottom = value; updateContentViewport(); invalidate(); }
    float settingScale() {
        float fit = 1;
        if (actions[0] != null && actions[0].getHeight() > 0) fit = Math.max(0, Math.min(1, (viewportBottom - ((View) actions[0].getParent()).getY() - actions[0].getY() - settingTranslationY()) / actions[0].getHeight()));
        return fit;
    }
    boolean animating() { return splitPending || animation != null; }
    void stopEntrance() {
        removeCallbacks(split); splitPending = false;
        if (animation == null) return;
        ValueAnimator stopped = animation; animation = null; stopped.removeAllListeners(); stopped.removeAllUpdateListeners(); stopped.cancel();
    }
    private void setLiquidDrawing(boolean active) {
        if (liquidDrawing == active) return;
        liquidDrawing = active; if (body != null) body.invalidate(); if (actions[0] != null) actions[0].invalidate();
    }
    void geometry(LiquidTensionGeometry result) {
        result.reset(); if (body == null || body.getWidth() <= 0 || getHeight() <= 0) return;
        float density = getResources().getDisplayMetrics().density;
        result.setConnectionRange(48 * density); result.setBevel(22 * density); result.setRefraction(18.7f * density);
        result.setHighlight(highlight);
        float bottom = Math.min(body.getBottom(), viewportBottom);
        result.add(body.getLeft(), body.getTop(), body.getRight(), bottom, AppLauncherStyle.panelRadius(getContext()) * density);
        if (separation > 0 && actions[0] != null) {
            View setting = actions[0], tools = (View) setting.getParent();
            float top = tools.getTop() + setting.getTop() + settingTranslationY() + (force == null ? 0 : force.y[LauncherForce.SETTING] * density), left = tools.getLeft() + setting.getLeft() + (force == null ? 0 : force.x[LauncherForce.SETTING] * density);
            float size = setting.getWidth() * settingScale(), center = left + setting.getWidth() / 2f;
            result.add(center - size / 2f, top, center + size / 2f, top + size, size / 2f);
            result.splitVerticallyInOrder(density, tools.getTop() + setting.getTop() - body.getBottom());
        }
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        // STACK surfaces use the hardware dispatchDraw path even when draw() is skipped.
        int saved = canvas.save();
        canvas.translate(glassOffsetX(), glassOffsetY());
        geometry(tension);
        boolean drawn = glass != null && glass.ready() && glass.drawTension(canvas, this, tension, true);
        setLiquidDrawing(drawn); super.dispatchDraw(canvas); canvas.restoreToCount(saved);
    }
    @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        if (actions[0] == null || child != actions[0].getParent()) return super.drawChild(canvas, child, drawingTime);
        float scale = settingScale();
        if (scale == 0) return true;
        if (scale == 1 && settingTranslationY() == 0 && (force == null || force.x[LauncherForce.SETTING] == 0 && force.y[LauncherForce.SETTING] == 0)) return super.drawChild(canvas, child, drawingTime);
        // Pull the same artwork down with its material; leave the input view fixed.
        int saved = canvas.save();
        canvas.translate(0, settingTranslationY());
        if (force != null) { float density = getResources().getDisplayMetrics().density; canvas.translate(force.x[LauncherForce.SETTING] * density, force.y[LauncherForce.SETTING] * density); }
        canvas.scale(scale, scale, child.getLeft() + actions[0].getLeft() + actions[0].getWidth() / 2f, child.getTop() + actions[0].getTop());
        boolean drawn = super.drawChild(canvas, child, drawingTime); canvas.restoreToCount(saved); return drawn;
    }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (body != null && !isShown()) { stopEntrance(); presented = false; separation(0); }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); if (oldw > 0 && (w != oldw || h != oldh)) { stopEntrance(); presented = true; separation(1); }
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) { super.onLayout(changed, left, top, right, bottom); updateContentViewport(); }
    @Override protected void onDetachedFromWindow() { stopEntrance(); setLiquidDrawing(false); glass = null; presented = false; super.onDetachedFromWindow(); }
}
