package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.util.ArrayList;

/** Two-edge rubber band. Drawing changes; layout, reading anchors and hit regions do not. */
final class NotificationScrollView extends ScrollView {
    private final float slop;
    private final int maximumVelocity;
    private float startX, startY, lastY, lastRawY, pull, boundaryPull, deformation;
    private int edge = 1;
    private boolean pulling, canceled, touching, flingImpact, panelHandoff;
    private VelocityTracker velocity;
    private ValueAnimator spring;
    private final ArrayList<NotificationSwipeRow> forceRows = new ArrayList<>();
    private final ArrayList<NotificationSwipeRow> previousRows = new ArrayList<>();
    private boolean readingTouch, readingFling, forceScheduled;
    private float scrollDrive, sceneY;
    private boolean sceneStarted;
    private long sceneAt;
    private float sideDrive, sideTop;
    private long lastSideAt;
    private NotificationSwipeRow sideSource;
    private NotificationForceHeader headerForce;
    private long lastScrollAt, forceFrameAt;
    private final Runnable forceFrame = this::advanceScrollForce;

    NotificationScrollView(Context context) {
        super(context); ViewConfiguration config = ViewConfiguration.get(context);
        slop = config.getScaledTouchSlop(); maximumVelocity = config.getScaledMaximumFlingVelocity();
        setOverScrollMode(OVER_SCROLL_NEVER);
    }
    boolean ownsPull() { return pulling; }
    boolean hasCards() {
        android.view.View list = findViewWithTag("notification-list");
        return list instanceof android.view.ViewGroup cards && cards.getChildCount() > 0;
    }
    private int boundaryDirection(float dy) { return !hasCards() ? 0 : dy < 0 && !canScrollVertically(1) ? 1 : dy > 0 && !canScrollVertically(-1) ? -1 : 0; }
    float transferToPanel() { panelHandoff = true; invalidate(); return NotificationForce.translation(deformation); }
    float visualTranslation() { return panelHandoff ? 0 : NotificationForce.translation(deformation); }
    void releasePanelHandoff(boolean cancel) {
        if (!panelHandoff) return;
        if (cancel) reset(); else settle(0);
    }
    float closingPullDistance(MotionEvent event, int outward) {
        float dy = event.getRawY() - lastRawY; int direction = pulling ? edge : boundaryDirection(dy);
        if (direction == 0 || PanelDrag.inward(outward, 0, -direction) <= 0) return 0;
        return Math.max(0, boundaryPull - dy * direction);
    }
    private float limit() { return Math.max(1, Math.min(Ui.dp(getContext(), 56), getHeight() * .18f)); }
    private void deform(float value) { float maximum = limit(); deformation = edge * Math.max(-maximum * .16f, Math.min(maximum, value * edge)); applyForce(); invalidate(); if (deformation != 0) wakeScrollForce(); }
    private void applyForce() {
        float height = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
        for (int i = 0; i < forceRows.size(); i++) { NotificationSwipeRow row = forceRows.get(i); row.boundaryForce(NotificationForce.card(deformation, edge, row.forceViewportTop - getPaddingTop(), row.getHeight(), height), deformation * edge / limit(), edge); }
    }
    private void refreshForceRows() {
        previousRows.clear(); for (int i = 0; i < forceRows.size(); i++) previousRows.add(forceRows.get(i));
        forceRows.clear(); if (getChildCount() > 0) collectForceRows(getChildAt(0), getChildAt(0).getTop() - getScrollY()); applyForce();
        for (int i = 0; i < previousRows.size(); i++) { NotificationSwipeRow row = previousRows.get(i); if (!forceRows.contains(row)) { row.scrollForce.reset(); row.sideForce.reset(); row.boundaryForce(0, 0, edge); } } previousRows.clear();
    }
    private void collectForceRows(View view, float top) {
        if (view.getVisibility() != VISIBLE || top > getHeight() + limit() || top + view.getHeight() < -limit()) return;
        if (view instanceof NotificationSwipeRow row) { row.forceViewportTop = top; forceRows.add(row); return; }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View child = group.getChildAt(i); collectForceRows(child, top + child.getTop() - group.getScrollY()); }
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) { super.onLayout(changed, left, top, right, bottom); refreshForceRows(); if (getParent() instanceof View parent) { View header = parent.findViewWithTag("panel-header"); if (header instanceof NotificationForceHeader force) { headerForce = force; force.forceOwner(this); } } }
    @Override protected void onScrollChanged(int x, int y, int oldX, int oldY) {
        super.onScrollChanged(x, y, oldX, oldY); refreshForceRows();
        if (y == oldY || pulling || panelHandoff || !(readingTouch || readingFling) || !ValueAnimator.areAnimatorsEnabled()) return;
        long now = android.os.SystemClock.uptimeMillis(); float seconds = Math.max(.008f, Math.min(.08f, (now - lastScrollAt) / 1000f));
        float maximum = Ui.dp(getContext(), 40); scrollDrive = Math.max(-maximum, Math.min(maximum, (oldY - y) / seconds * .14f)); lastScrollAt = now; wakeScrollForce();
    }
    void sceneMotion(float y) {
        long now = android.os.SystemClock.uptimeMillis(); float seconds = Math.max(.008f, Math.min(.08f, (now - sceneAt) / 1000f)); sceneAt = now;
        float previous = sceneY; sceneY = y;
        if (!sceneStarted) { sceneStarted = true; return; }
        if (previous == y || touching || pulling || panelHandoff || !ValueAnimator.areAnimatorsEnabled()) return;
        if (forceRows.isEmpty()) refreshForceRows();
        float maximum = Ui.dp(getContext(), 42); scrollDrive = Math.max(-maximum, Math.min(maximum, (previous - y) / seconds * .07f));
        lastScrollAt = now; wakeScrollForce();
    }
    void sideMotion(NotificationSwipeRow source, float delta) {
        if (!ValueAnimator.areAnimatorsEnabled() || !isShown() || !forceRows.contains(source)) return;
        sideSource = source; sideTop = source.forceViewportTop; float maximum = Ui.dp(getContext(), 18); sideDrive = Math.max(-maximum, Math.min(maximum, delta * 3));
        lastSideAt = android.os.SystemClock.uptimeMillis(); wakeScrollForce();
    }
    void pressFeedback(float x) {
        if (!ValueAnimator.areAnimatorsEnabled() || !isShown() || headerForce == null) return;
        headerForce.pulse(x);
        for (int i = 0; i < forceRows.size(); i++) { NotificationSwipeRow row = forceRows.get(i); row.scrollForce.velocity += Ui.dp(getContext(), 150) * Math.max(.15f, 1 - row.forceViewportTop / Math.max(1, getHeight())); }
        wakeScrollForce();
    }
    void cardFeedback(NotificationSwipeRow source) {
        if (!ValueAnimator.areAnimatorsEnabled() || !isShown()) return;
        float height = Math.max(1, getHeight());
        for (int i = 0; i < forceRows.size(); i++) { NotificationSwipeRow row = forceRows.get(i); row.scrollForce.velocity += Ui.dp(getContext(), 220) * Math.max(.1f, 1 - Math.abs(row.forceViewportTop - source.forceViewportTop) / height); }
        if (headerForce != null) headerForce.pulse(headerForce.getWidth() * .5f);
        wakeScrollForce();
    }
    void cancelLinkedForce() { resetScrollForce(); }
    private void wakeScrollForce() { if (!forceScheduled && isAttachedToWindow() && getWindowVisibility() == VISIBLE) { forceScheduled = true; postOnAnimation(forceFrame); } }
    private void advanceScrollForce() {
        forceScheduled = false; long now = android.os.SystemClock.uptimeMillis();
        if (!isAttachedToWindow() || !isShown() || forceRows.isEmpty() && headerForce == null || !ValueAnimator.areAnimatorsEnabled()) { resetScrollForce(); return; }
        if (now - lastScrollAt > 80) { scrollDrive = 0; if (!touching) readingFling = false; }
        if (now - lastSideAt > 80) sideDrive = 0;
        float elapsed = forceFrameAt == 0 ? 1 / 60f : Math.max(.001f, Math.min(.032f, (now - forceFrameAt) / 1000f)); forceFrameAt = now;
        float height = Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
        for (int step = 0; step < 4; step++) {
            for (int i = 0; i < forceRows.size(); i++) { NotificationSwipeRow row = forceRows.get(i); row.scrollForce.load(NotificationForce.scrollTarget(scrollDrive, row.forceViewportTop - getPaddingTop(), row.getHeight(), height)); row.sideForce.load(row == sideSource ? 0 : sideDrive * Math.max(.15f, 1 - Math.abs(row.forceViewportTop - sideTop) / height)); }
            for (int i = 1; i < forceRows.size(); i++) { NotificationForce.connect(forceRows.get(i - 1).scrollForce, forceRows.get(i).scrollForce); NotificationForce.connect(forceRows.get(i - 1).sideForce, forceRows.get(i).sideForce); }
            if (headerForce != null) { headerForce.load(sideDrive, scrollDrive + visualTranslation() * .35f); if (!forceRows.isEmpty()) headerForce.connect(forceRows.get(0)); headerForce.advance(elapsed / 4); }
            for (int i = 0; i < forceRows.size(); i++) { forceRows.get(i).scrollForce.advance(elapsed / 4); forceRows.get(i).sideForce.advance(elapsed / 4); }
        }
        boolean moving = scrollDrive != 0 || sideDrive != 0 || deformation != 0;
        if (headerForce != null) { moving |= headerForce.moving(); headerForce.invalidateForce(); }
        for (int i = 0; i < forceRows.size(); i++) { NotificationSwipeRow row = forceRows.get(i); moving |= row.scrollForce.moving() || row.sideForce.moving(); row.invalidateForce(); }
        if (moving) wakeScrollForce(); else resetScrollForce();
    }
    private void releaseScrollDrive() { readingTouch = readingFling = false; scrollDrive = 0; if (forceScheduled) wakeScrollForce(); }
    private void resetScrollForce() {
        removeCallbacks(forceFrame); forceScheduled = readingTouch = readingFling = false; scrollDrive = sideDrive = 0; lastScrollAt = lastSideAt = forceFrameAt = 0; sideSource = null;
        if (headerForce != null) headerForce.resetForce();
        for (int i = 0; i < forceRows.size(); i++) { NotificationSwipeRow row = forceRows.get(i); row.scrollForce.reset(); row.sideForce.reset(); row.invalidateForce(); }
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        int saved = canvas.save();
        canvas.translate(0, visualTranslation());
        super.dispatchDraw(canvas); canvas.restoreToCount(saved);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            stopSpring(); releaseVelocity(); velocity = VelocityTracker.obtain(); canceled = false; pulling = false; touching = true; flingImpact = false; panelHandoff = false;
            startX = event.getX(); startY = lastY = event.getY(); lastRawY = event.getRawY(); boundaryPull = 0;
            readingTouch = readingFling = false; scrollDrive = 0; lastScrollAt = android.os.SystemClock.uptimeMillis();
            pull = NotificationForce.distance(deformation * edge, limit());
        }
        if (canceled) return true;
        if (action == MotionEvent.ACTION_CANCEL && panelHandoff) {
            // This CANCEL only transfers ownership. The finger is still down on PanelSurface.
            touching = pulling = false; canceled = true; releaseScrollDrive(); releaseVelocity(); return super.dispatchTouchEvent(event);
        }
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) {
            cancelChildren(event); reset(); canceled = true; return true;
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_MOVE) {
            float dy = event.getY() - lastY, dx = event.getX() - startX, totalY = event.getY() - startY;
            int direction = boundaryDirection(dy);
            if (!pulling && Math.abs(totalY) > slop && Math.abs(totalY) > Math.abs(dx) * 1.15f) readingTouch = true;
            if (!pulling && direction != 0 && Math.abs(totalY) > slop && Math.abs(totalY) > Math.abs(dx) * 1.15f) {
                cancelChildren(event); releaseScrollDrive(); if (edge != direction) { pull = 0; deform(0); } edge = direction; pulling = true;
            }
            lastY = event.getY(); lastRawY = event.getRawY();
            if (pulling) {
                float previousPull = pull;
                boundaryPull = Math.max(0, boundaryPull - dy * edge); pull = Math.max(0, pull - dy * edge);
                if (dy * edge > 0 && pull == 0 && canScrollVertically(-edge)) {
                    // The outward stretch is exhausted. Give the remaining inward motion
                    // back to ScrollView, including its own touch and fling tracking.
                    float remaining = dy - previousPull * edge;
                    pulling = false; readingTouch = true; lastScrollAt = android.os.SystemClock.uptimeMillis(); boundaryPull = 0; deform(0);
                    MotionEvent down = MotionEvent.obtain(event); down.setAction(MotionEvent.ACTION_DOWN); down.offsetLocation(0, -remaining); super.dispatchTouchEvent(down); down.recycle();
                    return super.dispatchTouchEvent(event);
                }
                deform(edge * NotificationForce.pull(pull, limit())); return true;
            }
            // Catching a rebound must not turn a normal reading fling into a rebound release.
            if (direction == 0 && Math.abs(totalY) > slop && Math.abs(totalY) > Math.abs(dx) * 1.15f && deformation != 0) { pull = boundaryPull = 0; deform(0); }
        }
        if (action == MotionEvent.ACTION_UP) {
            touching = false;
            if (pulling) {
                float speed = 0;
                if (velocity != null && pulling) { velocity.computeCurrentVelocity(1000, maximumVelocity); speed = -velocity.getYVelocity() * NotificationForce.slope(deformation * edge, limit()); }
                pulling = false; releaseVelocity(); settle(speed); return true;
            }
        }
        boolean result = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP) { readingTouch = false; releaseVelocity(); if (deformation != 0) settle(0); } return result;
    }
    private void cancelChildren(MotionEvent event) {
        MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
    }
    @Override public void fling(int velocityY) { flingImpact = false; readingFling = velocityY != 0; lastScrollAt = android.os.SystemClock.uptimeMillis(); super.fling(velocityY); }
    @Override protected boolean overScrollBy(int dx, int dy, int x, int y, int rangeX, int rangeY, int overX, int overY, boolean isTouch) {
        boolean clamped = super.overScrollBy(dx, dy, x, y, rangeX, rangeY, overX, overY, isTouch);
        if (!isTouch && !touching && !flingImpact && hasCards() && (dy > 0 && y + dy > rangeY || dy < 0 && y + dy < 0)) {
            releaseScrollDrive(); flingImpact = true; edge = dy > 0 ? 1 : -1; settle(dy * 60f * .55f);
        }
        return clamped;
    }
    private void settle(float speed) {
        stopSpring();
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) { deform(0); return; }
        float maximumSpeed = Ui.dp(getContext(), 900);
        float initial = deformation, initialSpeed = Math.max(-maximumSpeed, Math.min(maximumSpeed, speed));
        if (Math.abs(initial) < .1f && Math.abs(initialSpeed) < 1) { deform(0); return; }
        spring = ValueAnimator.ofFloat(0, .65f); spring.setDuration(650); spring.setInterpolator(new android.view.animation.LinearInterpolator());
        spring.addUpdateListener(animation -> {
            float time = (float) animation.getAnimatedValue();
            deform(time >= .65f ? 0 : (float) (Math.exp(-10 * time) * (initial * Math.cos(14 * time) + (initialSpeed + 10 * initial) / 14 * Math.sin(14 * time))));
        }); spring.start();
    }
    private void stopSpring() { if (spring != null) { spring.cancel(); spring = null; } }
    private void releaseVelocity() { if (velocity != null) { velocity.recycle(); velocity = null; } }
    private void reset() { stopSpring(); releaseVelocity(); resetScrollForce(); touching = pulling = panelHandoff = sceneStarted = false; pull = boundaryPull = 0; deform(0); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw || h != oldh) { if (touching) { MotionEvent cancel = MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0); cancelChildren(cancel); cancel.recycle(); } reset(); canceled = true; }
    }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE) { reset(); canceled = true; } }
    @Override protected void onDetachedFromWindow() { reset(); sceneStarted = false; forceRows.clear(); if (headerForce != null) headerForce.forceOwner(null); headerForce = null; super.onDetachedFromWindow(); }
}
