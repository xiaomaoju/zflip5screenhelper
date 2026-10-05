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

/** One dismissal owner; notification pulls transfer only after reaching the list boundary. */
class PanelSurface extends LinearLayout {
    private final int outward;
    private final float extent, slop;
    private PanelHeaderView.Listener listener;
    private final ArrayList<View> hitPath = new ArrayList<>();
    private VelocityTracker velocity;
    private NotificationScrollView handoffScroll;
    private float startX, startY, startProgress, startDistance, notificationStartX, notificationStartY;
    private float blankX, blankY;
    private long blankDown;
    private boolean blankTap;
    private boolean dragging, protectedTouch, blocked, cancelCloses, multiplePointers, notificationGesture;
    PanelSurface(Context context, int edge, float extent, boolean haptics, PanelHeaderView.Listener listener) {
        super(context); setOrientation(VERTICAL); outward = (edge + 2) % 4; this.extent = Math.max(1, extent); this.listener = listener;
        slop = ViewConfiguration.get(context).getScaledTouchSlop(); setHapticFeedbackEnabled(haptics);
    }
    protected final void dismissalListener(PanelHeaderView.Listener value) { listener = value; }
    protected float dismissalExtent() { return extent; }
    protected boolean dismissalEnabled() { return true; }
    protected boolean blankTapDismissalEnabled() { return false; }
    void prepareScenePush() {
        dispose(); blocked = true; cancelPendingInputEvents();
        long now = android.os.SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        super.dispatchTouchEvent(cancel); cancel.recycle();
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    protected boolean shouldDismiss(float distance, float extent, float speed) { return PanelDrag.shouldOpen(distance, extent, speed, getResources().getDisplayMetrics().density); }
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
            for (View view : hitPath) if (view instanceof LevelSlider || view instanceof ControlScrollView || view instanceof SeekBar || view instanceof EditText || view instanceof ControlEditorView || view instanceof LauncherSidebarView.Content || view.getTag() instanceof String tag && (tag.startsWith("task-card:") || tag.equals("tasks-clear") || tag.equals("tasks-action-glass"))) protectedTouch = true;
            blankX = startX; blankY = startY; blankDown = event.getEventTime();
            blankTap = blankTapDismissalEnabled() && !protectedTouch && listener.currentProgress() == 1;
            for (View view : hitPath) if (view.isClickable() || view.isLongClickable() || view instanceof NotificationSwipeRow || view instanceof PanelActionSlot) blankTap = false;
            boolean onNotification = false; for (View view : hitPath) if (view instanceof NotificationSwipeRow) onNotification = true;
            View center = findViewWithTag("notification-center"); if (!onNotification && center instanceof NotificationCenterView notifications) notifications.closeActions();
            notificationGesture = center instanceof NotificationCenterView; notificationStartX = startX; notificationStartY = startY;
            velocity = VelocityTracker.obtain();
        }
        if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || Math.hypot(event.getRawX() - blankX, event.getRawY() - blankY) > slop) blankTap = false;
        boolean dismissBlank = event.getActionMasked() == MotionEvent.ACTION_UP && blankTap && !dragging && !blocked && dismissalEnabled() && blankTapDismissalEnabled() && event.getEventTime() - blankDown < ViewConfiguration.getLongPressTimeout();
        if (velocity != null) { MotionEvent screen = MotionEvent.obtain(event); screen.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY()); velocity.addMovement(screen); screen.recycle(); }
        boolean handled = super.dispatchTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) dispose();
        if (dismissBlank) listener.finish(true);
        return handled || dismissBlank;
    }
    private NotificationScrollView notificationScroll() {
        View center = findViewWithTag("notification-center");
        if (center != null) for (android.view.ViewParent parent = center.getParent(); parent != null && parent != this; parent = parent.getParent()) if (parent instanceof NotificationScrollView scroll) return scroll;
        return null;
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
        // Observe lists so only continued pulls beyond their boundary transfer to this surface.
        if (protectedTouch || blocked) super.requestDisallowInterceptTouchEvent(disallow);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        if (dragging) return true;
        if (!dismissalEnabled()) return false;
        if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) blocked = true;
        if (event.getActionMasked() != MotionEvent.ACTION_MOVE || protectedTouch || blocked) return false;
        return startDrag(event);
    }
    private boolean startDrag(MotionEvent event) {
        float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
        // Notification actions own the horizontal axis even with a side-mounted dock.
        if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f) for (View view : hitPath) if (view instanceof NotificationSwipeRow) { blocked = true; return false; }
        float current = listener.currentProgress();
        if (notificationGesture && current == 1 && (outward == DockGeometry.TOP || outward == DockGeometry.BOTTOM)) {
            NotificationScrollView scroll = notificationScroll(); if (scroll == null) return false;
            int direction = outward == DockGeometry.TOP ? -1 : 1;
            if (scroll.canScrollVertically(direction)) { notificationStartX = event.getRawX(); notificationStartY = event.getRawY(); return false; }
            boolean onList = hitPath.contains(scroll) && scroll.hasCards();
            float distance = onList ? scroll.closingPullDistance(event, outward) : PanelDrag.inward(outward, event.getRawX() - notificationStartX, event.getRawY() - notificationStartY);
            float threshold = Ui.dp(getContext(), 96);
            if (distance <= threshold || distance <= Math.abs(dx) * 1.15f) return false;
            float handoff = distance - threshold + Math.max(0, PanelDrag.inward(outward, 0, scroll.transferToPanel()));
            handoffScroll = scroll;
            startX = event.getRawX(); startY = event.getRawY(); startProgress = current; startDistance = -handoff;
            dragging = true; cancelCloses = listener.cancelCloses(); listener.begin(); listener.progress(PanelDrag.progress(current, -handoff, dismissalExtent())); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); return true;
        }
        float distance = PanelDrag.inward(outward, dx, dy), along = Math.abs(outward == DockGeometry.TOP || outward == DockGeometry.BOTTOM ? dx : dy);
        if (scrollsAlong(distance)) { startX = event.getRawX(); startY = event.getRawY(); return false; }
        if ((distance > slop || current < 1 && distance < -slop) && Math.abs(distance) > along * 1.15f) {
            dragging = true; startProgress = current; startDistance = current < 1 ? distance : 0; cancelCloses = listener.cancelCloses();
            listener.begin(); listener.progress(PanelDrag.progress(startProgress, startDistance - distance, dismissalExtent())); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); return true;
        }
        // Permit changing from an inward list scroll to an outward pull in the same gesture.
        if (distance < -slop) { startX = event.getRawX(); startY = event.getRawY(); }
        return false;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && !dragging && !blocked && !protectedTouch && dismissalEnabled()) startDrag(event);
        float extent = Math.max(1, dismissalExtent());
        float distance = PanelDrag.inward(outward, event.getRawX() - startX, event.getRawY() - startY);
        float progress = PanelDrag.progress(startProgress, startDistance - distance, extent);
        if (dragging && action == MotionEvent.ACTION_MOVE) listener.progress(progress);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            boolean active = dragging; float speed = 0;
            if (velocity != null) { velocity.computeCurrentVelocity(1000); speed = PanelDrag.inward(outward, velocity.getXVelocity(), velocity.getYVelocity()); }
            releaseHandoff(action != MotionEvent.ACTION_UP); dispose(); blocked = true;
            if (active) listener.finish(action == MotionEvent.ACTION_UP ? shouldDismiss((1 - progress) * extent, extent, speed) : cancelCloses);
            else if (action == MotionEvent.ACTION_UP) performClick();
        }
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }
    private void releaseHandoff(boolean cancel) { if (handoffScroll != null) handoffScroll.releasePanelHandoff(cancel); handoffScroll = null; }
    private void dispose() { releaseHandoff(true); if (velocity != null) velocity.recycle(); velocity = null; blankTap = dragging = notificationGesture = false; hitPath.clear(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        blankTap = false;
        if (dragging && (w != oldw || h != oldh)) { dispose(); blocked = true; listener.finish(cancelCloses); }
        else if (notificationGesture && (w != oldw || h != oldh)) {
            long now = android.os.SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0); super.dispatchTouchEvent(cancel); cancel.recycle(); dispose(); blocked = true;
        }
    }
    @Override protected void onDetachedFromWindow() { dispose(); super.onDetachedFromWindow(); }
}
