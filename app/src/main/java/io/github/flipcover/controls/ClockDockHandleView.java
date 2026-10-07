package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** A transparent, bounded clock-page touch target; only its chevron fades. */
final class ClockDockHandleView extends View {
    private final Prefs prefs;
    private final int edge, touchSlop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private boolean expanded, pending;
    private float startX, startY;
    private final Runnable hide = () -> { if (!expanded && !pending) fade(0, BuildConfig.MOTION_HANDLES_FADE_OUT_MS); };

    ClockDockHandleView(Context context, Prefs prefs, int edge, Runnable toggle) {
        super(context); this.prefs = prefs; this.edge = edge;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setLayerType(LAYER_TYPE_SOFTWARE, null); setAlpha(.5f);
        setHapticFeedbackEnabled(prefs.haptics());
        setOnClickListener(v -> { performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); toggle.run(); });
        describe();
    }
    boolean expanded() { return expanded; }
    void expanded(boolean value) {
        if (expanded == value) return;
        expanded = value; removeCallbacks(hide); describe(); invalidate();
        fade(value ? 1 : .5f, BuildConfig.MOTION_HANDLES_FADE_IN_MS);
        if (!value) postDelayed(hide, BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS);
    }
    private void describe() { setContentDescription(expanded ? "收起时钟页快捷栏" : "展开时钟页快捷栏"); }
    private void fade(float alpha, int duration) {
        animate().cancel();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) animate().alpha(alpha).setDuration(duration).start(); else setAlpha(alpha);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        paint.setColor(Ui.chromeColor(prefs)); paint.setAlpha(230);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Math.max(1, 2 * density));
        paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        ChromeShadowDrawable.applyShadow(paint, prefs.chromeStyle().equals("contrast") ? density * 1.25f : 0);
        float half = Math.min(10 * density, Math.min(getWidth(), getHeight()) * .35f), rise = half * .55f;
        canvas.save(); canvas.translate(getWidth() / 2f, getHeight() / 2f);
        float rotation = switch (edge) { case DockGeometry.TOP -> 180; case DockGeometry.LEFT -> 90; case DockGeometry.RIGHT -> -90; default -> 0; };
        canvas.rotate(rotation + (expanded ? 180 : 0));
        arrow.rewind(); arrow.moveTo(-half, rise / 2); arrow.lineTo(0, -rise / 2); arrow.lineTo(half, rise / 2);
        canvas.drawPath(arrow, paint); canvas.restore();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                pending = true; startX = event.getX(); startY = event.getY(); removeCallbacks(hide); animate().cancel(); setPressed(true);
            }
            case MotionEvent.ACTION_MOVE -> {
                if (Math.hypot(event.getX() - startX, event.getY() - startY) > touchSlop) { pending = false; setPressed(false); }
            }
            case MotionEvent.ACTION_UP -> {
                boolean click = pending && event.getX() >= 0 && event.getY() >= 0 && event.getX() < getWidth() && event.getY() < getHeight();
                pending = false; setPressed(false);
                if (click) performClick(); else settle();
            }
            case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { pending = false; setPressed(false); settle(); }
        }
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }
    private void settle() {
        if (expanded) fade(1, BuildConfig.MOTION_HANDLES_FADE_IN_MS);
        else { if (getAlpha() > 0) fade(.5f, BuildConfig.MOTION_HANDLES_FADE_IN_MS); removeCallbacks(hide); postDelayed(hide, BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS); }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); pending = false; setPressed(false); if (oldw > 0 && oldh > 0) settle(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); settle(); }
    @Override protected void onDetachedFromWindow() { pending = false; setPressed(false); removeCallbacks(hide); animate().cancel(); super.onDetachedFromWindow(); }
}
