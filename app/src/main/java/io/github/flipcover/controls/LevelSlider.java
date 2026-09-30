package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.function.IntConsumer;

/** A compact vertical level control, with a 44dp touch width and accessible range actions. */
final class LevelSlider extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path outline = new Path();
    private final RectF bounds = new RectF();
    private final Drawable icon;
    private final String label;
    private final int minimum, maximum;
    private final IntConsumer commit;
    private int value, startValue;
    private GlassSurface glass;
    void glass(GlassSurface surface) { glass = surface; invalidate(); }
    private float downX, downY;
    private boolean tracking, moving, held, canceled;
    private final RuntimeVisuals.Press emphasis = new RuntimeVisuals.Press(this);
    private final Runnable hold = () -> { if (tracking && !moving && !canceled) { held = performLongClick(); emphasis.set(false); } };
    LevelSlider(Context context, String label, int iconResource, int minimum, int maximum, int value, IntConsumer commit) {
        super(context); this.label = label; this.minimum = minimum; this.maximum = Math.max(minimum + 1, maximum); this.commit = commit;
        icon = Ui.icon(context, iconResource, Ui.TEXT); setFocusable(true); setClickable(true); setValue(value);
    }
    void setValue(int value) { this.value = Math.max(minimum, Math.min(maximum, value)); updateDescription(); invalidate(); }
    @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); updateDescription(); invalidate(); }
    private void updateDescription() { setContentDescription(label + (isEnabled() ? " " + Math.round(value * 100f / maximum) + "%" : "暂不可用")); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float labelSpace = Ui.dp(getContext(), 18), bottom = getHeight() - labelSpace;
        bounds.set(0, 0, getWidth(), bottom); float radius = Math.min(Math.min(getWidth(), bottom) / 2f, Ui.dp(getContext(), Ui.CONTROL_CORNER_DP));
        if (glass != null) { glass.setState(getDrawableState()); glass.setBounds(0,0,getWidth(),Math.round(bottom)); glass.draw(canvas,bounds); radius = Math.min(getWidth(),bottom)/2f; }
        else { paint.setStyle(Paint.Style.FILL); paint.setColor(RuntimeVisuals.blend(0xE6262A31, 0xFF363E49, emphasis.value)); canvas.drawRoundRect(bounds, radius, radius, paint); }
        if (isEnabled()) {
            outline.reset(); outline.addRoundRect(bounds, radius, radius, Path.Direction.CW);
            canvas.save(); canvas.clipPath(outline); paint.setColor(Ui.TEXT);
            canvas.drawRect(0, bottom * (1 - value / (float) maximum), getWidth(), bottom, paint); canvas.restore();
        }
        float stroke = Math.max(1, getResources().getDisplayMetrics().density * .5f); bounds.inset(stroke / 2, stroke / 2);
        if (glass == null) { paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); paint.setColor(isActivated() ? 0xFFAFD3FF : RuntimeVisuals.blend(0x24FFFFFF, 0xCCDBEAFF, emphasis.value)); canvas.drawRoundRect(bounds, radius, radius, paint); paint.setStyle(Paint.Style.FILL); }
        int size = Ui.dp(getContext(), 21), x = (getWidth() - size) / 2, y = Math.round(bottom) - size - Ui.dp(getContext(), 13);
        icon.setTint(Ui.TEXT); icon.setAlpha(isEnabled() ? 255 : 95); icon.setBounds(x, y, x + size, y + size); icon.draw(canvas);
        // Short tracks can put the fill boundary through the icon; tint each covered portion.
        if (isEnabled()) { canvas.save(); canvas.clipRect(0, bottom * (1 - value / (float) maximum), getWidth(), bottom); icon.setTint(Ui.ON_ACTIVE); icon.draw(canvas); canvas.restore(); }
        paint.setColor(RuntimeVisuals.blend(Ui.MUTED, Ui.TEXT, emphasis.value)); paint.setTextSize(Ui.dp(getContext(), 9)); paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(isEnabled() ? Math.round(value * 100f / maximum) + "%" : "—", getWidth() / 2f, getHeight() - Ui.dp(getContext(), 3), paint);
    }
    private void track(float y) { float height = Math.max(1, getHeight() - Ui.dp(getContext(), 18)); setValue(Math.round(maximum * (1 - Math.max(0, Math.min(height, y)) / height))); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> { emphasis.set(true); startValue = value; downX = event.getX(); downY = event.getY(); tracking = true; moving = held = canceled = false; getParent().requestDisallowInterceptTouchEvent(true); if (isLongClickable()) postDelayed(hold, android.view.ViewConfiguration.getLongPressTimeout()); return true; }
            case MotionEvent.ACTION_MOVE -> { if (!tracking || canceled || held) return true; if (Math.hypot(event.getX() - downX, event.getY() - downY) > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) { moving = true; removeCallbacks(hold); } if (moving) track(event.getY()); return true; }
            case MotionEvent.ACTION_POINTER_DOWN -> { emphasis.set(false); canceled = true; removeCallbacks(hold); setValue(startValue); return true; }
            case MotionEvent.ACTION_UP -> { emphasis.set(false); removeCallbacks(hold); if (tracking && !held && !canceled) { track(event.getY()); performClick(); commit.accept(value); } tracking = false; getParent().requestDisallowInterceptTouchEvent(false); return true; }
            case MotionEvent.ACTION_CANCEL -> { emphasis.set(false); removeCallbacks(hold); tracking = false; canceled = true; setValue(startValue); getParent().requestDisallowInterceptTouchEvent(false); return true; }
        }
        return super.onTouchEvent(event);
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDetachedFromWindow() { removeCallbacks(hold); tracking = false; super.onDetachedFromWindow(); }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.SeekBar");
        info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT, minimum, maximum, value));
        if (isEnabled()) { info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD); info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD); }
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (isEnabled()) {
            if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId() && arguments != null) { setValue(Math.round(arguments.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))); commit.accept(value); return true; }
            if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { setValue(value + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1) * Math.max(1, maximum / 10)); commit.accept(value); return true; }
        }
        return super.performAccessibilityAction(action, arguments);
    }
    @Override public boolean onKeyDown(int key, KeyEvent event) {
        if (isEnabled() && (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN)) { setValue(value + (key == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1) * Math.max(1, maximum / 10)); commit.accept(value); return true; }
        return super.onKeyDown(key, event);
    }
}
