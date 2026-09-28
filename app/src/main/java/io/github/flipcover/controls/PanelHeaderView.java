package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;
import android.widget.TextView;

/** Panel dismissal uses screen coordinates because its containing panel follows the finger. */
final class PanelHeaderView extends TextView {
    interface Listener {
        void begin(); void progress(float value); void finish(boolean close);
        default float currentProgress() { return 1; }
        default boolean cancelCloses() { return false; }
    }
    private final int outward;
    private final float extent;
    private final Listener listener;
    private PanelDrag drag;
    private VelocityTracker velocity;
    private float startX, startY, startProgress;
    private boolean cancelCloses;
    PanelHeaderView(Context context, String title, int dockEdge, float extent, boolean haptics, Listener listener) {
        super(context); this.outward = (dockEdge + 2) % 4; this.extent = Math.max(1, extent); this.listener = listener;
        setText(title); setTextSize(17); setTextColor(Ui.TEXT); setIncludeFontPadding(false); setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        setMinimumHeight(Ui.dp(context, 36)); setGravity(android.view.Gravity.CENTER_VERTICAL); setHapticFeedbackEnabled(haptics); setAccessibilityHeading(true);
        setContentDescription(title + "，向停靠边缘拖动可收起；也可使用关闭按钮");
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            dispose(); startX = event.getRawX(); startY = event.getRawY();
            drag = new PanelDrag(outward, ViewConfiguration.get(getContext()).getScaledTouchSlop()); velocity = VelocityTracker.obtain();
            startProgress = listener.currentProgress(); cancelCloses = listener.cancelCloses(); listener.begin();
        }
        if (drag == null || velocity == null) return true;
        MotionEvent screen = MotionEvent.obtain(event); screen.offsetLocation(event.getRawX() - event.getX(), event.getRawY() - event.getY()); velocity.addMovement(screen); screen.recycle();
        boolean wasActive = drag.active(); float distance = drag.move(event.getRawX() - startX, event.getRawY() - startY);
        if (action == MotionEvent.ACTION_MOVE && drag.active()) {
            if (!wasActive) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            listener.progress(PanelDrag.progress(startProgress, -distance, extent));
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            velocity.computeCurrentVelocity(1000); float speed = PanelDrag.inward(outward, velocity.getXVelocity(), velocity.getYVelocity());
            boolean active = drag.active(), close = action == MotionEvent.ACTION_UP ? active && PanelDrag.shouldOpen((1 - PanelDrag.progress(startProgress, -distance, extent)) * extent, extent, speed, getResources().getDisplayMetrics().density) : cancelCloses;
            dispose(); listener.finish(close);
            if (action == MotionEvent.ACTION_UP && !active) performClick();
        }
        return true;
    }
    private void dispose() { if (velocity != null) velocity.recycle(); velocity = null; drag = null; }
    @Override public boolean performClick() { return super.performClick(); }
    @Override protected void onDetachedFromWindow() { dispose(); super.onDetachedFromWindow(); }
}
