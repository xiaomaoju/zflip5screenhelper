package io.github.flipcover.controls;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** A local outline around an icon, without sampling or retaining screen content. */
final class ContrastDrawable extends Drawable {
    private final Drawable foreground, outline;
    private final int radius;
    ContrastDrawable(Drawable source, int radius) {
        foreground = source; this.radius = Math.max(1, radius);
        outline = source.getConstantState() == null ? null : source.getConstantState().newDrawable().mutate();
        if (outline != null) outline.setTint(android.graphics.Color.BLACK);
    }
    @Override protected void onBoundsChange(Rect bounds) { foreground.setBounds(bounds); if (outline != null) outline.setBounds(bounds); }
    @Override public void draw(Canvas canvas) {
        if (outline != null) for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            if (x == 0 && y == 0) continue; int save = canvas.save(); canvas.translate(x * radius, y * radius); outline.draw(canvas); canvas.restoreToCount(save);
        }
        foreground.draw(canvas);
    }
    @Override public void setAlpha(int alpha) { foreground.setAlpha(alpha); if (outline != null) outline.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { foreground.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return foreground.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return foreground.getIntrinsicHeight(); }
}
