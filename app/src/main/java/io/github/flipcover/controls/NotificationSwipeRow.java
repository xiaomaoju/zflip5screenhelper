package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.VelocityTracker;
import android.widget.FrameLayout;

/** Horizontal gestures reveal actions; no distance or velocity ever deletes a notification. */
final class NotificationSwipeRow extends FrameLayout {
    final View surface;
    private final View rail;
    private final Runnable reveal;
    private final float slop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float startX, startY, startOffset;
    private boolean dragging, vertical, startedOpen, railTouch;
    private int actionWidth, layers;
    private VelocityTracker velocity;
    private boolean targetOpen, sequenceCanceled;
    NotificationSwipeRow(Context context, View surface, View rail, Runnable reveal) {
        super(context); this.surface = surface; this.rail = rail; this.reveal = reveal;
        slop = ViewConfiguration.get(context).getScaledTouchSlop(); setWillNotDraw(false);
        addView(rail, new LayoutParams(Ui.dp(context, 96), Ui.dp(context, 48), Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        addView(surface, new LayoutParams(-1, -2)); setActionsWidth(96); setRailVisible(false);
    }
    void setActionsWidth(int dp) {
        int width = Ui.dp(getContext(), dp); if (actionWidth == width) return;
        actionWidth = width; rail.getLayoutParams().width = width; rail.requestLayout();
        if (opened()) { surface.animate().cancel(); surface.setTranslationX(-actionWidth); }
    }
    boolean opened() { return surface.getTranslationX() < -1; }
    void showActions(boolean show) {
        targetOpen = show; surface.animate().withEndAction(null).cancel(); if (show) { reveal.run(); setRailVisible(true); }
        float target = show ? -actionWidth : 0;
        if (!ValueAnimator.areAnimatorsEnabled()) { surface.setTranslationX(target); setRailVisible(show); return; }
        long duration = Math.max(60, Math.round(150 * Math.abs(target - surface.getTranslationX()) / Math.max(1, actionWidth)));
        surface.animate().translationX(target).setDuration(duration).setInterpolator(new android.view.animation.DecelerateInterpolator()).withEndAction(() -> { if (!targetOpen) setRailVisible(false); }).start();
    }
    private void setRailVisible(boolean visible) {
        rail.setVisibility(visible ? VISIBLE : INVISIBLE);
        rail.setImportantForAccessibility(visible ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    void setLayers(int count) {
        int next = Math.min(2, Math.max(0, count - 1)); if (next == layers) return; layers = next;
        LayoutParams params = (LayoutParams) surface.getLayoutParams(); params.bottomMargin = Ui.dp(getContext(), layers * 3); surface.setLayoutParams(params); invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        for (int i = layers; i > 0; i--) {
            float inset = Ui.dp(getContext(), i * 4), bottom = getHeight() - Ui.dp(getContext(), (layers - i) * 3);
            paint.setColor(i == 1 ? 0xFF23262C : 0xFF191C21);
            canvas.drawRoundRect(inset, Ui.dp(getContext(), 8), getWidth() - inset, bottom, Ui.dp(getContext(), 14), Ui.dp(getContext(), 14), paint);
        }
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            surface.animate().cancel(); startX = event.getX(); startY = event.getY(); startOffset = surface.getTranslationX();
            startedOpen = targetOpen; dragging = false; vertical = false; railTouch = opened() && startX >= getWidth() + startOffset;
            return opened() && !railTouch;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) { vertical = true; showActions(startedOpen); return false; }
        if (action == MotionEvent.ACTION_MOVE && !vertical && !railTouch) {
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) vertical = true;
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f) { dragging = true; getParent().requestDisallowInterceptTouchEvent(true); reveal.run(); setRailVisible(true); return true; }
        }
        return dragging;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && !vertical) {
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (!dragging && Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) { vertical = true; showActions(false); }
            if (!vertical && (dragging || Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f)) {
                dragging = true; getParent().requestDisallowInterceptTouchEvent(true); reveal.run(); setRailVisible(true);
                surface.setTranslationX(Math.max(-actionWidth, Math.min(0, startOffset + dx)));
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            float speed = 0; if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = velocity.getXVelocity(); }
            float threshold = Ui.dp(getContext(), 450);
            boolean open = action == MotionEvent.ACTION_UP && !vertical ? dragging && (Math.abs(speed) > threshold ? speed < 0 : surface.getTranslationX() < -actionWidth * .4f) : startedOpen;
            showActions(open); dragging = false; if (action != MotionEvent.ACTION_UP) vertical = true;
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(); if (action == MotionEvent.ACTION_DOWN) { sequenceCanceled = false; releaseVelocity(); velocity = VelocityTracker.obtain(); }
        if (!sequenceCanceled && (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN)) {
            sequenceCanceled = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
            dragging = false; vertical = true; showActions(startedOpen); releaseVelocity();
        }
        if (sequenceCanceled) return true;
        if (velocity != null) velocity.addMovement(event); boolean result = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) releaseVelocity(); return result;
    }
    private void releaseVelocity() { if (velocity != null) velocity.recycle(); velocity = null; }
    @Override protected void onDetachedFromWindow() { releaseVelocity(); targetOpen = false; surface.animate().withEndAction(null).cancel(); surface.setTranslationX(0); setRailVisible(false); super.onDetachedFromWindow(); }
}
