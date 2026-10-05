package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Finite paint-only feedback. No module physics, layout transforms, or persistent frame loop. */
final class ControlFeedback implements View.OnAttachStateChangeListener {
    final View view;
    private final boolean bounce;
    private ValueAnimator press, recoil, direction;
    private boolean held;
    private float sx = 1, sy = 1, rx = 1, ry = 1, y, dragY;
    ControlFeedback(View view, boolean bounce) { this.view = view; this.bounce = bounce; view.setTag(R.id.control_feedback, this); view.addOnAttachStateChangeListener(this); }
    void pressed(boolean next) {
        if (held == next) return; held = next; if (press != null) press.cancel();
        float startX = sx, startY = sy;
        if (!view.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { sx = sy = 1; invalidatePaint(); return; }
        press = ValueAnimator.ofFloat(0, 1); press.setDuration(next ? bounce ? 80 : 180 : bounce ? 320 : 480);
        press.setInterpolator(new PathInterpolator(.2f, 0, .25f, 1));
        press.addUpdateListener(frame -> {
            float p = (float) frame.getAnimatedValue();
            if (next) { sx = startX + ((bounce ? .89f : .96f) - startX) * p; sy = startY + ((bounce ? .86f : .975f) - startY) * p; }
            else if (bounce) { sx = pop(startX, 1.13f, .975f, p); sy = pop(startY, 1.10f, .98f, p); }
            else { sx = startX + (1 - startX) * p; sy = startY + (1 - startY) * p; }
            invalidatePaint();
        }); press.start();
    }
    private static float pop(float start, float peak, float trough, float p) { return p < .42f ? start + (peak - start) * p / .42f : p < .72f ? peak + (trough - peak) * (p - .42f) / .3f : trough + (1 - trough) * (p - .72f) / .28f; }
    void pulse() { if (held || press != null && press.isRunning()) return; sx = bounce ? .89f : .96f; sy = bounce ? .86f : .975f; held = true; pressed(false); }
    void recoil(float amplitude, int edge, long delay, long duration, float offset, float scale) {
        stopRecoil(); if (!view.isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) return;
        float pixels = offset * view.getResources().getDisplayMetrics().density * amplitude * edge;
        recoil = ValueAnimator.ofFloat(0, 1); recoil.setStartDelay(delay); recoil.setDuration(duration); recoil.setInterpolator(new android.view.animation.DecelerateInterpolator());
        recoil.addUpdateListener(frame -> { float p = (float) frame.getAnimatedValue(), wave = p < .3f ? -p / .3f : p < .64f ? -1 + 1.32f * (p - .3f) / .34f : .32f * (1 - (p - .64f) / .36f); y = pixels * wave; ry = 1 + scale * amplitude * wave; rx = 1 - scale * amplitude * wave * .4f; invalidatePaint(); }); recoil.start();
    }
    void stopRecoil() { if (recoil != null) recoil.cancel(); recoil = null; y = 0; rx = ry = 1; invalidatePaint(); }
    void drag(float speed) { if (!ValueAnimator.areAnimatorsEnabled()) { if (direction != null) direction.cancel(); direction = null; dragY = 0; invalidatePaint(); return; } if (direction != null) direction.cancel(); direction = null; dragY = ControlElastic.clamp(speed * .004f, -2.7f, 2.7f) * view.getResources().getDisplayMetrics().density; invalidatePaint(); }
    void releaseDrag() {
        if (direction != null) direction.cancel(); if (!ValueAnimator.areAnimatorsEnabled()) { direction = null; dragY = 0; invalidatePaint(); return; } if (dragY == 0) return;
        float start = dragY; direction = ValueAnimator.ofFloat(0, 1); direction.setDuration(280); direction.setInterpolator(new android.view.animation.DecelerateInterpolator());
        direction.addUpdateListener(frame -> { float p = (float) frame.getAnimatedValue(); dragY = p < .45f ? start * (1 - 1.3f * p / .45f) : p < .72f ? start * (-.3f + .38f * (p - .45f) / .27f) : start * .08f * (1 - (p - .72f) / .28f); invalidatePaint(); }); direction.start();
    }
    int save(Canvas canvas, float px, float py) { int checkpoint = canvas.save(); canvas.translate(0, y + dragY); canvas.scale(sx * rx, sy * ry, px, py); return checkpoint; }
    private void invalidatePaint() { view.invalidate(); if (view.getParent() instanceof RuntimeVisuals.Cell cell && cell.controlFeedback == this) cell.invalidate(); }
    void matrix(Matrix result) { result.setScale(sx * rx, sy * ry, view.getWidth() / 2f, view.getHeight() / 2f); result.postTranslate(0, y + dragY); }
    void cancel() { if (press != null) press.cancel(); if (direction != null) direction.cancel(); press = direction = null; stopRecoil(); held = false; sx = sy = 1; dragY = 0; invalidatePaint(); }
    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { cancel(); }
    static boolean hasPaint(View owner) {
        for (View view = owner; view != null; view = view.getParent() instanceof View parent ? parent : null) if (view.getTag(R.id.control_feedback) instanceof ControlFeedback || view instanceof ControlScrollView || view instanceof InterfaceCard card && card.definition().id().equals("controls")) return true;
        return false;
    }
    static void materialPosition(View owner, Matrix result, Matrix base, Matrix inverse, Matrix local, Matrix combined) {
        for (View view = owner; view != null; view = view.getParent() instanceof View parent ? parent : null) {
            boolean active = false;
            if (view.getTag(R.id.control_feedback) instanceof ControlFeedback feedback) { feedback.matrix(local); active = true; }
            else if (view instanceof ControlScrollView scroll) { scroll.paintMatrix(local); active = true; }
            else if (view instanceof InterfaceCard card) active = card.controlMatrix(local);
            if (!active) continue;
            base.reset(); view.transformMatrixToGlobal(base); if (!base.invert(inverse)) continue;
            combined.setConcat(inverse, result); inverse.setConcat(local, combined); result.setConcat(base, inverse);
        }
    }
}
