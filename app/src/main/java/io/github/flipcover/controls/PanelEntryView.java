package io.github.flipcover.controls;

import android.content.Context;
import android.os.Bundle;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

/** Horizontal white handles; every display rotation opens panels with an upward pull. */
@android.annotation.SuppressLint({"ViewConstructor", "ClickableViewAccessibility"}) // Programmatic gesture strip; equivalent named accessibility actions below.
final class PanelEntryView extends View {
    private final DockView.Listener listener;
    private final Prefs prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private DockGeometry.Chrome chrome;
    private final boolean gesturesEnabled;
    private final int slop;
    private float startX, startY, distance;
    private String page;
    private boolean pending, pulling, panelOpen, revealed;
    private long hideAt;
    private VelocityTracker velocity;
    private final Runnable hold;
    private final Runnable hideHandles = () -> {
        if (panelOpen) return;
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) animate().alpha(0).setDuration(160).withEndAction(() -> revealed = false).start(); else { setAlpha(0); revealed = false; }
    };
    PanelEntryView(Context context, Prefs prefs, DockGeometry.Placement placement, DockView.Listener listener) {
        super(context); this.listener = listener; this.prefs = prefs;
        chrome = DockGeometry.chrome(new DockGeometry.Placement(placement.visual(), placement.touch(), placement.panel(), DockGeometry.BOTTOM, placement.measured()), getResources().getDisplayMetrics().density);
        hold = () -> { if (pending && getAlpha() > 0) { revealHandles(); reset(); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); listener.toggleVisibility(); settleHandles(); } };
        gesturesEnabled = prefs.data.getBoolean("gestures_enabled", true); slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setLayerType(LAYER_TYPE_SOFTWARE, null); setHapticFeedbackEnabled(prefs.haptics()); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAlpha(0);
        setContentDescription("双白条入口：左条上滑打开通知，右条上滑打开控制中心；白条可见时长按显示或隐藏快捷按钮");
    }
    @Override protected void onDraw(Canvas canvas) {
        paint.setColor(Ui.chromeColor(prefs)); paint.setAlpha(230);
        if (prefs.chromeStyle().equals("contrast")) paint.setShadowLayer(Ui.dp(getContext(), 1), 0, 0, android.graphics.Color.BLACK);
        drawHandle(canvas, chrome.firstHandle()); drawHandle(canvas, chrome.secondHandle());
    }
    private void drawHandle(Canvas canvas, DockGeometry.Box bar) { canvas.drawRoundRect(bar.x(), bar.y(), bar.right(), bar.bottom(), Ui.dp(getContext(), 1), Ui.dp(getContext(), 1), paint); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                cancel(); removeCallbacks(hideHandles); if (!panelOpen) animate().cancel(); pending = true; startX = event.getX(); startY = event.getY(); distance = 0;
                page = startX < getWidth() / 2f ? "notifications" : "controls";
                velocity = VelocityTracker.obtain(); velocity.addMovement(event);
                if (getAlpha() > 0) postDelayed(hold, ViewConfiguration.getLongPressTimeout());
            }
            case MotionEvent.ACTION_MOVE -> move(event);
            case MotionEvent.ACTION_UP -> {
                move(event);
                if (pulling) {
                    velocity.computeCurrentVelocity(1000); String target = page; float traveled = distance, speed = -velocity.getYVelocity();
                    reset(); listener.release(target, traveled, speed, false);
                } else reset();
                settleHandles();
            }
            case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> cancel();
        }
        return true;
    }
    private void move(MotionEvent event) {
        if (velocity == null) return;
        velocity.addMovement(event);
        float dx = event.getX() - startX, dy = event.getY() - startY, inward = -dy;
        if (pending && Math.hypot(dx, dy) > slop) {
            removeCallbacks(hold);
            if (gesturesEnabled && inward > slop && inward >= Math.abs(dx) * .75f) {
                pending = false; pulling = true; distance = inward;
                revealHandles();
                listener.beginPull(page, distance); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            } else if (inward < -slop * 2) { reset(); return; }
        }
        if (pulling) { distance = inward; listener.pull(page, distance); }
    }
    private void reset() {
        removeCallbacks(hold); pending = pulling = false;
        if (velocity != null) { velocity.recycle(); velocity = null; }
    }
    private void cancel() {
        boolean active = pulling; String target = page; float traveled = distance; reset();
        if (active) listener.release(target, traveled, 0, true);
        settleHandles();
    }
    private void revealHandles() {
        removeCallbacks(hideHandles); hideAt = 0; animate().cancel(); revealed = true;
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) animate().alpha(1).setDuration(120).start(); else setAlpha(1);
    }
    void panelVisible(boolean visible) {
        panelOpen = visible;
        if (visible) revealHandles(); else settleHandles();
    }
    void suspended(boolean suspended) {
        if (suspended) { cancel(); hideImmediately(); }
        setVisibility(suspended ? INVISIBLE : VISIBLE);
    }
    private void settleHandles() {
        removeCallbacks(hideHandles);
        if (!panelOpen) {
            if (revealed) dimHandles();
            hideAt = android.os.SystemClock.uptimeMillis() + 3000; postDelayed(hideHandles, 3000);
        }
    }
    private void dimHandles() { animate().cancel(); if (android.animation.ValueAnimator.areAnimatorsEnabled()) animate().alpha(.5f).setDuration(120).start(); else setAlpha(.5f); }
    private void hideImmediately() { removeCallbacks(hideHandles); animate().cancel(); setAlpha(0); revealed = false; }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); cancel(); if (!panelOpen) hideImmediately();
        DockGeometry.Box box = new DockGeometry.Box(0, 0, w, h); chrome = DockGeometry.chrome(new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, true), getResources().getDisplayMetrics().density);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (panelOpen) revealHandles();
        else if (hideAt > 0) {
            long remaining = Math.max(0, hideAt - android.os.SystemClock.uptimeMillis());
            if (revealed && remaining > 0) dimHandles();
            postDelayed(hideHandles, remaining);
        }
    }
    @Override protected void onDetachedFromWindow() {
        boolean active = pulling; String target = page; float traveled = distance; reset(); removeCallbacks(hideHandles); animate().cancel();
        if (active) listener.release(target, traveled, 0, true);
        super.onDetachedFromWindow();
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.open_notifications, "打开通知中心"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.open_controls, "打开控制中心"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.toggle_dock, "显示或隐藏快捷按钮"));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == R.id.open_notifications) { listener.action("notification_list"); return true; }
        if (action == R.id.open_controls) { listener.action("controls"); return true; }
        if (action == R.id.toggle_dock) { listener.toggleVisibility(); return true; }
        return super.performAccessibilityAction(action, args);
    }
}
