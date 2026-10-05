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

/** Horizontal handles open inward from the selected top or bottom corner. */
@android.annotation.SuppressLint({"ViewConstructor", "ClickableViewAccessibility"}) // Programmatic gesture strip; equivalent named accessibility actions below.
final class PanelEntryView extends View {
    private final DockView.Listener listener;
    private final Prefs prefs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private DockGeometry.Chrome chrome;
    private final boolean gesturesEnabled;
    private final int slop;
    private final int edge;
    private final int artworkTopInset;
    private float startX, startY, distance;
    private String page;
    private boolean pending, pulling, panelOpen, revealed;
    private long hideAt;
    private VelocityTracker velocity;
    private final Runnable hold;
    private final Runnable hideHandles = () -> {
        if (panelOpen) return;
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) animate().alpha(0).setDuration(BuildConfig.MOTION_HANDLES_FADE_OUT_MS).withEndAction(() -> revealed = false).start(); else { setAlpha(0); revealed = false; }
    };
    PanelEntryView(Context context, Prefs prefs, DockGeometry.Placement placement, DockView.Listener listener) {
        super(context); this.listener = listener; this.prefs = prefs;
        edge = placement.edge(); artworkTopInset = placement.visual().y() - placement.touch().y(); chrome = DockGeometry.panelEntryChrome(placement, getResources().getDisplayMetrics().density);
        hold = () -> { if (pending && getAlpha() > 0) { revealHandles(); reset(); performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); listener.toggleVisibility(); settleHandles(); } };
        gesturesEnabled = prefs.gesturesEnabled(); slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setLayerType(LAYER_TYPE_SOFTWARE, null); setHapticFeedbackEnabled(prefs.haptics()); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        setAlpha(0);
        setContentDescription("双白条入口：左条打开通知，右条打开控制中心；" + (edge == DockGeometry.TOP ? "向下滑入" : "向上滑入") + "；白条可见时长按显示或隐藏快捷按钮");
    }
    @Override protected void onDraw(Canvas canvas) {
        paint.setColor(Ui.chromeColor(prefs)); paint.setAlpha(230);
        ChromeShadowDrawable.applyShadow(paint, prefs.chromeStyle().equals("contrast") ? getResources().getDisplayMetrics().density * 1.25f : 0);
        drawHandle(canvas, chrome.firstHandle()); drawHandle(canvas, chrome.secondHandle());
    }
    private void drawHandle(Canvas canvas, DockGeometry.Box bar) { canvas.drawRoundRect(bar.x(), bar.y(), bar.right(), bar.bottom(), Ui.dp(getContext(), 1), Ui.dp(getContext(), 1), paint); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                cancel(); removeCallbacks(hideHandles); if (!panelOpen) animate().cancel(); pending = true; startX = event.getRawX(); startY = event.getRawY(); distance = 0;
                page = event.getX() < getWidth() / 2f ? "notifications" : "controls";
                velocity = VelocityTracker.obtain(); velocity.addMovement(event);
                if (getAlpha() > 0) postDelayed(hold, ViewConfiguration.getLongPressTimeout());
            }
            case MotionEvent.ACTION_MOVE -> move(event);
            case MotionEvent.ACTION_UP -> {
                move(event);
                if (pulling) {
                    velocity.computeCurrentVelocity(1000); String target = page; float traveled = distance, speed = PanelDrag.inward(edge, velocity.getXVelocity(), velocity.getYVelocity());
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
        float dx = event.getRawX() - startX, dy = event.getRawY() - startY, inward = PanelDrag.inward(edge, dx, dy), along = dx;
        if (pending && Math.hypot(dx, dy) > slop) {
            removeCallbacks(hold);
            if (gesturesEnabled && inward > slop && inward >= Math.abs(along) * .75f) {
                pending = false; pulling = true; distance = inward;
                revealHandles();
                listener.beginPull(page, distance, startY); performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
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
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) animate().alpha(1).setDuration(BuildConfig.MOTION_HANDLES_FADE_IN_MS).start(); else setAlpha(1);
    }
    void panelVisible(boolean visible) {
        panelOpen = visible;
        if (visible) revealHandles(); else settleHandles();
    }
    private void settleHandles() {
        removeCallbacks(hideHandles);
        if (!panelOpen) {
            if (revealed) dimHandles();
            hideAt = android.os.SystemClock.uptimeMillis() + BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS; postDelayed(hideHandles, BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS);
        }
    }
    private void dimHandles() { animate().cancel(); if (android.animation.ValueAnimator.areAnimatorsEnabled()) animate().alpha(.5f).setDuration(BuildConfig.MOTION_HANDLES_FADE_IN_MS).start(); else setAlpha(.5f); }
    private void hideImmediately() { removeCallbacks(hideHandles); animate().cancel(); setAlpha(0); revealed = false; }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh); cancel(); if (!panelOpen) hideImmediately();
        int top = Math.min(artworkTopInset, Math.max(0, h - 1));
        DockGeometry.Box box = new DockGeometry.Box(0, 0, w, h), visual = new DockGeometry.Box(0, top, w, h - top);
        chrome = DockGeometry.panelEntryChrome(new DockGeometry.Placement(visual, box, box, edge, true), getResources().getDisplayMetrics().density);
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
