package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.ScrollView;

/** Native scrolling first, then one grid rubber band. Input and layout never deform. */
final class ControlScrollView extends ScrollView {
    final ControlElastic elastic = new ControlElastic();
    private final float density, slop;
    private final ControlFeedback[] modules = new ControlFeedback[6];
    private static final long[] DELAYS = {0, 12, 42, 0, 30, 55}, DURATIONS = {330, 380, 420, 370, 410, 470};
    private static final float[] SHIFTS = {.7f, 1, 1.3f, 1.8f, 1.45f, 1.7f}, SCALES = {.006f, .012f, .014f, .018f, .014f, .012f};
    private View scene;
    private boolean touching, pulling, blocked, scheduled, impact;
    private float startX, startY, lastY;
    private long frameAt;
    private VelocityTracker velocity;
    private final Runnable frame = this::advance;
    ControlScrollView(Context context) { super(context); density = getResources().getDisplayMetrics().density; slop = ViewConfiguration.get(context).getScaledTouchSlop(); setOverScrollMode(OVER_SCROLL_NEVER); setMotionEventSplittingEnabled(false); }
    void module(int index, ControlFeedback feedback) { modules[index] = feedback; }
    void card(View view) { scene = view; }
    private void redraw() { invalidate(); if (scene != null) scene.invalidate(); }
    private void cancelReturns() { for (ControlFeedback module : modules) if (module != null) module.stopRecoil(); }
    private void returns(float speed) { float strength = ControlElastic.clamp(Math.abs(elastic.load) / ControlElastic.MAXIMUM + Math.abs(speed) / 1100, .08f, 1); for (int i = 0; i < modules.length; i++) if (modules[i] != null) modules[i].recoil(strength, elastic.edge(), DELAYS[i], DURATIONS[i], SHIFTS[i], SCALES[i]); }
    private void wake() { if (!scheduled && isAttachedToWindow() && isShown() && !touching) { scheduled = true; frameAt = android.os.SystemClock.uptimeMillis(); postOnAnimation(frame); } }
    private void advance() {
        scheduled = false; long now = android.os.SystemClock.uptimeMillis();
        if (!isAttachedToWindow() || !isShown() || touching || !android.animation.ValueAnimator.areAnimatorsEnabled()) { if (!touching) reset(); return; }
        boolean moving = elastic.advance((now - frameAt) / 1000f); redraw(); if (moving) { scheduled = true; frameAt = now; postOnAnimation(frame); }
    }
    private void settle(float speed) { touching = pulling = false; elastic.release(speed); returns(speed); releaseVelocity(); if (!android.animation.ValueAnimator.areAnimatorsEnabled()) reset(); else wake(); redraw(); }
    void reset() { removeCallbacks(frame); scheduled = touching = pulling = false; elastic.reset(); releaseVelocity(); for (ControlFeedback module : modules) if (module != null) module.cancel(); redraw(); }
    private void releaseVelocity() { if (velocity != null) velocity.recycle(); velocity = null; }
    private void cancelChildren(MotionEvent event) { MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) { removeCallbacks(frame); scheduled = false; cancelReturns(); elastic.freeze(); touching = true; pulling = blocked = impact = false; startX = event.getX(); startY = lastY = event.getY(); releaseVelocity(); velocity = VelocityTracker.obtain(); }
        if (blocked) return true;
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) { cancelChildren(event); reset(); blocked = true; return true; }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_MOVE) {
            float dy = event.getY() - lastY, dx = event.getX() - startX, total = event.getY() - startY; lastY = event.getY();
            boolean vertical = Math.abs(total) > slop && Math.abs(total) > Math.abs(dx) * 1.15f;
            boolean boundary = dy > 0 && !canScrollVertically(-1) || dy < 0 && !canScrollVertically(1);
            if (!pulling && vertical && (boundary || elastic.moving())) { cancelChildren(event); pulling = true; getParent().requestDisallowInterceptTouchEvent(true); }
            if (pulling) {
                float old = elastic.load, delta = dy / density; elastic.drag(delta);
                if (old != 0 && old * elastic.load <= 0 && canScrollVertically(delta < 0 ? 1 : -1)) {
                    float remaining = (old + delta) * density; elastic.reset(); pulling = false;
                    MotionEvent down = MotionEvent.obtain(event); down.setAction(MotionEvent.ACTION_DOWN); down.offsetLocation(0, -remaining); super.onTouchEvent(down); down.recycle(); redraw(); return super.onTouchEvent(event);
                }
                if (!android.animation.ValueAnimator.areAnimatorsEnabled()) elastic.reset(); redraw(); return true;
            }
        }
        if (action == MotionEvent.ACTION_UP) {
            if (pulling || elastic.moving()) { float speed = 0; if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = velocity.getYVelocity() / density * .25f; } if (!pulling) super.dispatchTouchEvent(event); settle(speed); return true; }
            touching = false; releaseVelocity();
        }
        return super.dispatchTouchEvent(event);
    }
    @Override protected boolean overScrollBy(int dx, int dy, int x, int y, int rangeX, int rangeY, int maxX, int maxY, boolean touch) {
        if (!touching && !impact && (y + dy < 0 || y + dy > rangeY) && dy != 0) { impact = true; elastic.reset(); elastic.drag(ControlElastic.clamp(-dy / density, -24, 24)); settle(0); }
        return super.overScrollBy(dx, dy, x, y, rangeX, rangeY, 0, 0, touch);
    }
    void paintMatrix(Matrix result) { float strain = Math.abs(elastic.grid()) / ControlElastic.LIMIT; result.setScale(1 - strain * .012f, 1 + strain * .025f, getWidth() / 2f, elastic.edge() < 0 ? getHeight() : 0); result.postTranslate(0, elastic.grid() * density); }
    @Override protected void dispatchDraw(Canvas canvas) { int checkpoint = canvas.save(); canvas.translate(0, elastic.grid() * density); float strain = Math.abs(elastic.grid()) / ControlElastic.LIMIT; canvas.scale(1 - strain * .012f, 1 + strain * .025f, getWidth() / 2f, getScrollY() + (elastic.edge() < 0 ? getHeight() : 0)); super.dispatchDraw(canvas); canvas.restoreToCount(checkpoint); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); for (android.view.ViewParent parent = getParent(); parent instanceof View ancestor; parent = ancestor.getParent()) { if (ancestor instanceof PanelSurface) scene = ancestor; if (ancestor instanceof InterfaceCard) { scene = ancestor; break; } } }
    @Override protected void onDetachedFromWindow() { reset(); scene = null; super.onDetachedFromWindow(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (oldw != 0 && (w != oldw || h != oldh)) { if (touching) { long now = android.os.SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0); cancelChildren(cancel); cancel.recycle(); } reset(); blocked = true; } }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE && elastic != null) reset(); }
    boolean framePending() { return scheduled; }
}
