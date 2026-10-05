package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import android.widget.LinearLayout;
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry;

/** Header actions pull out from right to left; layout and input slots stay fixed. */
class PanelActionHeader extends LinearLayout implements SharedGlassHost {
    static final long SPLIT_DURATION_MS = BuildConfig.MOTION_HEADER_SPLIT_DURATION_MS;
    static final long HIGHLIGHT_DURATION_MS = LiquidTensionGeometry.HIGHLIGHT_DURATION_MS;
    private final LiquidTensionGeometry tension = new LiquidTensionGeometry();
    private final android.graphics.Matrix feedbackMatrix = new android.graphics.Matrix();
    private final android.graphics.RectF faceBounds = new android.graphics.RectF();
    private View[] slots = new View[0], faces = new View[0], actions = new View[0];
    private PanelGlassSession glass;
    private ValueAnimator animation;
    private float separation = 1, appearance = 1;
    private boolean presented, pending, liquidDrawing, entryComplete;

    PanelActionHeader(Context context) { super(context); setClipChildren(false); }
    void actions() {
        int count = getChildCount() - 1;
        slots = new View[count]; faces = new View[count]; actions = new View[Math.max(0, count - 1)];
        for (int i = 0; i < count; i++) {
            slots[i] = getChildAt(getChildCount() - 1 - i);
            faces[i] = ((PanelActionSlot) slots[i]).getChildAt(0);
            if (i > 0) actions[i - 1] = faces[i];
        }
    }
    @Override public View glassBody() { return faces[0]; }
    @Override public GlassSurface.Role glassBodyRole() { return GlassSurface.Role.BUTTON; }
    @Override public View[] glassActions() { return actions; }
    @Override public boolean drawsSharedGlass(View surface) {
        if (!liquidDrawing) return false;
        for (View face : faces) if (surface == face) return true;
        return false;
    }
    @Override public float independentGlassAlpha(View surface) { return drawsSharedGlass(surface) ? appearance : 1; }
    @Override public void glass(PanelGlassSession session) {
        if (glass == session) return;
        glass = session;
        if (session == null) { stopSplit(); separation(1); setLiquidDrawing(false); }
        else { session.prepareTension(); requestLayout(); }
        invalidate();
    }
    private void reveal() {
        if (presented || faces.length < 2 || glass == null || !isAttachedToWindow() || !isShown()) return;
        presented = true;
        if (android.os.Build.VERSION.SDK_INT < 33 || !ValueAnimator.areAnimatorsEnabled()) return;
        separation(0); pending = true;
        if (entryComplete) startSplit();
    }
    void entryProgress(float progress) {
        entryComplete = progress >= 1;
        if (!entryComplete) return;
        if (pending) startSplit(); else reveal();
    }
    private void startSplit() {
        pending = false;
        if (!isAttachedToWindow() || !isShown() || !ValueAnimator.areAnimatorsEnabled() || glass == null || !glass.ready()) { separation(1); return; }
        long movement = SPLIT_DURATION_MS * (faces.length - 1);
        animation = ValueAnimator.ofFloat(0, movement + HIGHLIGHT_DURATION_MS);
        animation.setDuration(movement + HIGHLIGHT_DURATION_MS);
        animation.setInterpolator(new android.view.animation.LinearInterpolator());
        animation.addUpdateListener(frame -> {
            float time = (float) frame.getAnimatedValue();
            separation(time / movement);
            appearance = smooth(Math.max(0, Math.min(1, (time - movement) / HIGHLIGHT_DURATION_MS)));
            invalidate(); for (View face : faces) face.invalidate();
        });
        animation.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator ended) { if (animation != ended) return; animation = null; separation(1); }
        }); animation.start();
    }
    void separation(float value) { separation = Math.max(0, Math.min(1, value)); appearance = separation == 1 ? 1 : 0; invalidate(); }
    float separation() { return separation; }
    boolean animating() { return pending || animation != null; }
    private float progress(int index) { return index == 0 ? 1 : Math.max(0, Math.min(1, separation * (faces.length - 1) - (index - 1))); }
    private static float smooth(float value) { return value * value * (3 - 2 * value); }
    protected boolean growsActions() { return false; }
    float actionScale(int index) { return index == 0 || !growsActions() ? 1 : .62f + .38f * smooth(progress(index)); }
    float appearance() { return appearance; }
    float actionTranslationX(int index) {
        if (index == 0) return 0;
        float progress = progress(index), ease = progress * progress * (3 - 2 * progress);
        View slot = slots[index], previous = slots[index - 1];
        return (previous.getLeft() + previous.getWidth() / 2f - slot.getLeft() - slot.getWidth() / 2f) * (1 - ease);
    }
    protected float actionForceX(View slot) { return 0; }
    protected float actionForceY(View slot) { return 0; }
    void geometry(LiquidTensionGeometry result) {
        result.reset();
        float density = getResources().getDisplayMetrics().density;
        result.setConnectionRange(48 * density); result.setBevel(22 * density); result.setRefraction(18.7f * density);
        int active = -1;
        for (int i = 0; i < faces.length; i++) {
            View face = faces[i], slot = slots[i];
            if (face.getVisibility() != VISIBLE || face.getWidth() <= 0 || progress(i) == 0) continue;
            faceBounds.set(0, 0, face.getWidth(), face.getHeight());
            if (face.getTag(R.id.control_feedback) instanceof ControlFeedback feedback) { feedback.matrix(feedbackMatrix); feedbackMatrix.mapRect(faceBounds); }
            float width = faceBounds.width() * face.getScaleX() * actionScale(i), height = faceBounds.height() * face.getScaleY() * actionScale(i);
            float x = slot.getLeft() + face.getLeft() + face.getWidth() / 2f + (faceBounds.centerX() - face.getWidth() / 2f) * face.getScaleX() + actionTranslationX(i) + actionForceX(slot);
            float y = slot.getTop() + face.getTop() + face.getHeight() / 2f + (faceBounds.centerY() - face.getHeight() / 2f) * face.getScaleY() + actionForceY(slot);
            result.add(x - width / 2, y - height / 2, x + width / 2, y + height / 2, height / 2);
            if (progress(i) < 1) active = i;
        }
        if (result.getCount() > 1) {
            float amount = active < 0 ? 1 : progress(active);
            float ease = amount * amount * (3 - 2 * amount);
            result.mergePair(result.getCount() - 2, result.getCount() - 1, .3f + .7f * ease, 48 * density);
        }
    }
    private void setLiquidDrawing(boolean value) {
        if (liquidDrawing == value) return;
        liquidDrawing = value;
        for (View face : faces) face.invalidate();
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        boolean transitioning = separation < 1 || appearance < 1;
        if (transitioning && (!canvas.isHardwareAccelerated() || glass == null || !glass.ready() || !ValueAnimator.areAnimatorsEnabled())) { stopSplit(); separation(1); transitioning = false; }
        if (transitioning) geometry(tension);
        int layer = transitioning && appearance > 0 ? canvas.saveLayerAlpha(0, 0, getWidth(), getHeight(), Math.round(255 * (1 - appearance))) : -1;
        setLiquidDrawing(transitioning && glass != null && glass.ready() && glass.drawTension(canvas, this, tension));
        if (layer != -1) canvas.restoreToCount(layer);
        if (!liquidDrawing && transitioning) { stopSplit(); separation(1); }
        super.dispatchDraw(canvas);
    }
    @Override public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        int action = event.getActionMasked();
        if ((separation < 1 || appearance < 1) && (action == android.view.MotionEvent.ACTION_DOWN || action == android.view.MotionEvent.ACTION_CANCEL || event.getPointerCount() > 1)) { stopSplit(); separation(1); }
        return super.dispatchTouchEvent(event);
    }
    @Override protected boolean drawChild(Canvas canvas, View child, long time) {
        for (int i = 1; i < slots.length; i++) if (child == slots[i]) {
            float progress = progress(i);
            if (progress == 0) return true;
            int saved = canvas.save(); canvas.translate(actionTranslationX(i), 0);
            float scale = actionScale(i); canvas.scale(scale, scale, child.getLeft() + child.getWidth() / 2f, child.getTop() + child.getHeight() / 2f);
            float alpha = growsActions() ? smooth(Math.min(1, progress / .55f)) : Math.min(1, progress / .3f);
            int layer = alpha < 1 ? canvas.saveLayerAlpha(child.getLeft(), child.getTop(), child.getRight(), child.getBottom(), Math.round(255 * alpha)) : -1;
            boolean drawn = super.drawChild(canvas, child, time);
            if (layer != -1) canvas.restoreToCount(layer);
            canvas.restoreToCount(saved); return drawn;
        }
        return super.drawChild(canvas, child, time);
    }
    void stopSplit() {
        pending = false;
        if (animation == null) return;
        ValueAnimator stopped = animation; animation = null; stopped.removeAllListeners(); stopped.removeAllUpdateListeners(); stopped.cancel();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); requestLayout(); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { super.onLayout(changed, l, t, r, b); reveal(); }
    @Override protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        if (faces == null) return;
        if (!isShown()) { stopSplit(); separation(1); presented = false; }
        else requestLayout();
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (oldw > 0 && (w != oldw || h != oldh)) { stopSplit(); separation(1); }
    }
    @Override protected void onDetachedFromWindow() { stopSplit(); separation = appearance = 1; setLiquidDrawing(false); glass = null; presented = entryComplete = false; super.onDetachedFromWindow(); }
}
