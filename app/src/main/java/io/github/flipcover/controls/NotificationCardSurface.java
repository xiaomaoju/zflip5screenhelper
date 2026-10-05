package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.widget.LinearLayout;

/** Draw the whole card, including its content, with task-style elastic deformation. */
final class NotificationCardSurface extends LinearLayout {
    static final float ELASTICITY = .35f;
    private float stretchX = 1, stretchY = 1, anchor = 1;
    NotificationCardSurface(Context context) { super(context); setOrientation(VERTICAL); }
    void horizontalPull(float pull, float anchor) {
        float amount = Math.max(-.4f, Math.min(1.35f, pull)); this.anchor = anchor;
        stretchX = 1 + .18f * amount * ELASTICITY; stretchY = 1 - .10f * amount * ELASTICITY; invalidate();
    }
    void visualBounds(RectF bounds) {
        float pivotX = getWidth() * anchor, pivotY = getHeight() * .5f;
        bounds.set(pivotX + (bounds.left - pivotX) * stretchX, pivotY + (bounds.top - pivotY) * stretchY, pivotX + (bounds.right - pivotX) * stretchX, pivotY + (bounds.bottom - pivotY) * stretchY);
    }
    @Override public void draw(Canvas canvas) {
        int saved = canvas.save(); canvas.scale(stretchX, stretchY, getWidth() * anchor, getHeight() * .5f);
        super.draw(canvas); canvas.restoreToCount(saved);
    }
}
