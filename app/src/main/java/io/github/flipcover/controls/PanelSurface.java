package io.github.flipcover.controls;

import android.content.Context;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import java.util.ArrayList;

/** One dismissal owner for header, blank space and lists at their outward scroll boundary. */
final class PanelSurface extends LinearLayout {
    private final int outward;
    private final float extent, slop;
    private final PanelHeaderView.Listener listener;
    private final ArrayList<View> hitPath = new ArrayList<>();
    private VelocityTracker velocity;
    private float startX, startY, startProgress, startDistance;
    private boolean dragging, protectedTouch, blocked, cancelCloses, multiplePointers;
    PanelSurface(Context context, int edge, float extent, boolean haptics, PanelHeaderView.Listener listener) {
        super(context); setOrientation(VERTICAL); outward = (edge + 2) % 4; this.extent = Math.max(1, extent); this.listener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop(); setHapticFeedbackEnabled(haptics);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) multiplePointers = false;
        if (!multiplePointers && (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
            View editor = findViewWithTag("control-editor"); if (editor instanceof ControlEditorView editing) editing.cancelDrag();
            multiplePointers = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); dispose();
        }
        if (multiplePointers) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            dispose(); startX = event.getRawX(); startY = event.getRawY(); blocked = false;
            trace(this, event.getX(), event.getY()); protectedTouch = false;
            for (View view : hitPath) if (view instanceof LevelSlider || view instanceof SeekBar || view instanceof EditText || view instanceof ControlEditorView || view.getTag() instanceof String tag && tag.startsWith("task-card:")) protectedTouch = true;
            boolean onNotification = false; for (View view : hitPath) if (view instanceof NotificationSwipeRow) onNotification = true;
            View center = findViewWithTag("notification-center"); if (!onNotification && center instanceof NotificationCenterView notifications) notifications.closeActions();
            velocity = VelocityTracker.obtain();
        }
        if (velocity != null) { MotionEvent screen = MotionEvent.obtain(event); screen.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY()); velocity.addMovement(screen); screen.recycle(); }
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) dispose();
        return handled;
    }
    private void trace(View view, float x, float y) {
        hitPath.add(view);
        if (view instanceof ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i); float cx = x + group.getScrollX() - child.getX(), cy = y + group.getScrollY() - child.getY();
            if (child.getVisibility() == VISIBLE && cx >= 0 && cy >= 0 && cx < child.getWidth() && cy < child.getHeight()) { trace(child, cx, cy); break; }
        }
    }
    private boolean scrollsAlong(float distance) {
        int direction = outward == DockGeometry.TOP || outward == DockGeometry.LEFT ? -1 : 1;
        if (distance < 0) direction = -direction;
        for (View view : hitPath) if (outward == DockGeometry.TOP || outward == DockGeometry.BOTTOM ? view.canScrollVertically(direction) : view.canScrollHorizontally(direction)) return true;
        return false;
    }
    @Override public void requestDisallowInterceptTouchEvent(boolean disallow) {
        // Keep observing lists so a pull can transfer to this surface after they reach the edge.
        if (protectedTouch || blocked) super.requestDisallowInterceptTouchEvent(disallow);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (dragging) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) blocked = true;
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE || protectedTouch || blocked) return false;
        return startDrag(event);
    }
    private boolean startDrag(MotionEvent event) {
        float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
        // Notification actions own the horizontal axis even with a side-mounted dock.
        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f) for (View view : hitPath) if (view instanceof NotificationSwipeRow) { blocked = true; return false; }
        float distance = PanelDrag.inward(outward, dx, dy), along = Math.abs(outward == DockGeometry.TOP || outward == DockGeometry.BOTTOM ? dx : dy);
        if (scrollsAlong(distance)) { startX = event.getRawX(); startY = event.getRawY(); return false; }
        float current = listener.currentProgress();
        if ((distance > slop || current < 1 && distance < -slop) && Math.abs(distance) > along * 1.15f) {
            dragging = true; startProgress = current; startDistance = current < 1 ? distance : 0; cancelCloses = listener.cancelCloses();
            listener.begin(); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); return true;
        }
        // Permit changing from an inward list scroll to an outward pull in the same gesture.
        if (distance < -slop) { startX = event.getRawX(); startY = event.getRawY(); }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && !dragging && !blocked && !protectedTouch) startDrag(event);
        float distance = PanelDrag.inward(outward, event.getRawX() - startX, event.getRawY() - startY);
        float progress = PanelDrag.progress(startProgress, startDistance - distance, extent);
        if (dragging && action == MotionEvent.ACTION_MOVE) listener.progress(progress);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            boolean active = dragging; float speed = 0;
            if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = PanelDrag.inward(outward, velocity.getXVelocity(), velocity.getYVelocity()); }
            dispose(); blocked = true;
            if (active) listener.finish(action == MotionEvent.ACTION_UP ? PanelDrag.shouldOpen((1 - progress) * extent, extent, speed, getResources().getDisplayMetrics().density) : cancelCloses);
            else if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }
    private void dispose() { if (velocity != null) velocity.recycle(); velocity = null; dragging = false; hitPath.clear(); }
    @Override protected void onDetachedFromWindow() { dispose(); super.onDetachedFromWindow(); }
}
