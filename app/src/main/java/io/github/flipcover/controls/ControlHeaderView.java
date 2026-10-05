package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;

/** A control-only paint surface; title and actions keep their original slots. */
final class ControlHeaderView extends PanelActionHeader {
    final ControlFeedback feedback = new ControlFeedback(this, false);
    ControlHeaderView(Context context) { super(context); setOrientation(HORIZONTAL); setGravity(android.view.Gravity.CENTER_VERTICAL); }
    @Override public void draw(Canvas canvas) { int checkpoint = feedback.save(canvas, getWidth() / 2f, getHeight() / 2f); super.draw(canvas); canvas.restoreToCount(checkpoint); }
}
