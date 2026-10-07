package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.MotionEvent;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** Opt-in runtime paint and feedback. Never changes measurement, padding or touch bounds. */
final class RuntimeVisuals {
    private RuntimeVisuals() { }
    static void launcher(View view, LauncherForce force, int node) {
        if (view instanceof Cell cell) { cell.force = force; cell.node = node; }
        else if (view instanceof Button button) { button.force = force; button.node = node; }
    }
    static ControlFeedback control(View view) { if (!(view instanceof Button button)) return null; if (button.controlFeedback == null) button.controlFeedback = new ControlFeedback(button, true); return button.controlFeedback; }
    static int blend(int from, int to, float progress) {
        float p = Math.max(0, Math.min(1, progress));
        return Color.argb(Math.round(Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * p), Math.round(Color.red(from) + (Color.red(to) - Color.red(from)) * p), Math.round(Color.green(from) + (Color.green(to) - Color.green(from)) * p), Math.round(Color.blue(from) + (Color.blue(to) - Color.blue(from)) * p));
    }
    static void surface(View view, int color, float radius) {
        if (view.getBackground() instanceof Surface old) { old.onViewDetachedFromWindow(view); view.removeOnAttachStateChangeListener(old); }
        view.setBackground(new Surface(view, color, color, radius));
    }
    static ImageButton button(Context context, int icon, String label, Runnable action) {
        Button button = new Button(context); button.setImageDrawable(Ui.icon(context, icon, Ui.TEXT)); button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad = Ui.dp(context, 8); button.setPadding(pad, pad, pad, pad); surface(button, Color.TRANSPARENT, 14);
        button.setContentDescription(label); button.setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(context, 36), Ui.dp(context, 36))); button.setOnClickListener(v -> action.run()); return button;
    }
    static final class Press implements View.OnAttachStateChangeListener {
        final View view; float value; private ValueAnimator animation; private boolean pressed;
        Press(View view) { this.view = view; view.addOnAttachStateChangeListener(this); }
        void set(boolean next) {
            if (pressed == next) return; pressed = next; if (animation != null) animation.cancel();
            float target = next ? 1 : 0;
            if (!view.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { value = target; view.invalidate(); return; }
            animation = ValueAnimator.ofFloat(value, target); animation.setDuration(next ? 80 : 140); animation.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animation.addUpdateListener(frame -> { value = (float) frame.getAnimatedValue(); view.invalidate(); }); animation.start();
        }
        void draw(Canvas canvas, Runnable draw) { int save = canvas.save(); float scale = 1 - .04f * value; canvas.scale(scale, scale, view.getWidth() / 2f, view.getHeight() / 2f); draw.run(); canvas.restoreToCount(save); }
        public void onViewAttachedToWindow(View view) { }
        public void onViewDetachedFromWindow(View view) { if (animation != null) animation.cancel(); animation = null; value = 0; pressed = false; }
    }
    static final class Button extends ImageButton {
        private LauncherForce force; private int node;
        private final Press press = new Press(this);
        private ControlFeedback controlFeedback;
        private boolean canceled;
        Button(Context context) { super(context); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) canceled = false;
            if (!canceled && (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
                canceled = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
            }
            boolean handled = canceled || super.dispatchTouchEvent(event); if (controlFeedback != null && (canceled || event.getActionMasked() == MotionEvent.ACTION_CANCEL)) controlFeedback.cancel(); return handled;
        }
        @Override public void setPressed(boolean value) { super.setPressed(value); if (controlFeedback != null) controlFeedback.pressed(value); else if (press != null) press.set(value); }
        @Override public boolean performClick() { if (!isEnabled()) return false; if (controlFeedback != null) controlFeedback.pulse(); return super.performClick(); }
        @Override public void draw(Canvas canvas) { if (controlFeedback == null) { super.draw(canvas); return; } int saved = controlFeedback.save(canvas, getWidth() / 2f, getHeight() / 2f); super.draw(canvas); canvas.restoreToCount(saved); }
        @Override protected void onDraw(Canvas canvas) {
            int saved = canvas.save(); if (force != null) canvas.scale(force.scaleX(node), force.scaleY(node), getWidth() / 2f, getHeight() / 2f);
            if (controlFeedback == null) press.draw(canvas, () -> super.onDraw(canvas)); else super.onDraw(canvas); canvas.restoreToCount(saved);
        }
    }
    static class Cell extends LinearLayout {
        ControlFeedback controlFeedback;
        private LauncherForce force; private int node;
        private final Press press = new Press(this);
        private boolean canceled;
        Cell(Context context) { super(context); setOrientation(VERTICAL); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) canceled = false;
            if (!canceled && (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
                canceled = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
            }
            boolean handled = canceled || super.dispatchTouchEvent(event); if (controlFeedback != null && (canceled || event.getActionMasked() == MotionEvent.ACTION_CANCEL)) controlFeedback.cancel(); return handled;
        }
        @Override public void setPressed(boolean value) { super.setPressed(value); if (controlFeedback != null) controlFeedback.pressed(value); else if (press != null) press.set(value); }
        @Override public boolean performClick() { if (controlFeedback != null) controlFeedback.pulse(); return super.performClick(); }
        @Override protected void dispatchDraw(Canvas canvas) { if (controlFeedback == null) press.draw(canvas, () -> super.dispatchDraw(canvas)); else super.dispatchDraw(canvas); }
        @Override protected boolean drawChild(Canvas canvas, View child, long time) {
            if (controlFeedback != null && child == controlFeedback.view) { int saved = controlFeedback.save(canvas, child.getLeft() + child.getWidth() / 2f, child.getTop() + child.getHeight() / 2f); boolean drawn = super.drawChild(canvas, child, time); canvas.restoreToCount(saved); return drawn; }
            if (force == null || !(child instanceof ImageView)) return super.drawChild(canvas, child, time);
            int saved = canvas.save(); canvas.scale(force.scaleX(node), force.scaleY(node), child.getLeft() + child.getWidth() / 2f, child.getTop() + child.getHeight() / 2f);
            boolean drawn = super.drawChild(canvas, child, time); canvas.restoreToCount(saved); return drawn;
        }
    }
    static final class Surface extends Drawable implements View.OnAttachStateChangeListener {
        private final View owner;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final int normal, selected;
        private final float radius, line;
        private int color, target, opacity = 255;
        private boolean working;
        private ValueAnimator animation;
        Surface(View owner, int normal, int selected, float radius) {
            this.owner = owner; this.normal = normal; this.selected = selected; color = target = normal;
            this.radius = Ui.dp(owner.getContext(), radius); line = Math.max(1, owner.getResources().getDisplayMetrics().density * .5f); owner.addOnAttachStateChangeListener(this);
        }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] states) {
            boolean active = false, pressed = false; working = false;
            for (int state : states) { active |= state == android.R.attr.state_selected; pressed |= state == android.R.attr.state_pressed; working |= state == android.R.attr.state_activated; }
            int next = active ? selected : normal;
            if (pressed) next = blend(next, Color.WHITE, Color.alpha(normal) == 0 ? .13f : active ? .08f : .16f);
            if (next != target) {
                target = next; if (animation != null) animation.cancel();
                if (!owner.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) color = next;
                else { int start = color; animation = ValueAnimator.ofFloat(0, 1); animation.setDuration(pressed ? 80 : 160); animation.addUpdateListener(frame -> { color = blend(start, target, (float) frame.getAnimatedValue()); invalidateSelf(); }); animation.start(); }
            }
            invalidateSelf(); return true;
        }
        @Override public void draw(Canvas canvas) {
            box.set(getBounds()); paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setAlpha(Color.alpha(color) * opacity / 255); canvas.drawRoundRect(box, radius, radius, paint);
            if (Color.alpha(normal) == 0 && !working) return;
            box.inset(line / 2, line / 2); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(line);
            paint.setColor(working ? 0xFFAFD3FF : 0x24FFFFFF); paint.setAlpha(Color.alpha(paint.getColor()) * opacity / 255); canvas.drawRoundRect(box, radius, radius, paint);
            // A shadow inside the existing surface avoids clipping and consumes no layout space.
            int save = canvas.save(); canvas.clipRect(getBounds().left, getBounds().centerY(), getBounds().right, getBounds().bottom);
            paint.setColor(working ? 0xFFAFD3FF : 0x30000000); paint.setAlpha(Color.alpha(paint.getColor()) * opacity / 255); canvas.drawRoundRect(box, radius, radius, paint); canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        public void onViewAttachedToWindow(View view) { }
        public void onViewDetachedFromWindow(View view) { if (animation != null) animation.cancel(); animation = null; color = target; }
    }
}
