package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;

/** Counts only current authorized notifications. Decorative; the cell exposes the precise accessible value. */
final class AppNotificationBadge extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float radius;
    private final String count;
    AppNotificationBadge(Context context, int count) { radius = Ui.dp(context, 7); this.count = count > 99 ? "99+" : String.valueOf(count); paint.setTextSize(Ui.dp(context, count > 99 ? 6 : 8)); paint.setTextAlign(Paint.Align.CENTER); }
    @Override public void draw(Canvas canvas) { float x = getBounds().right - radius - 2, y = getBounds().top + radius + 2; paint.setColor(0xFF9D364B); canvas.drawCircle(x, y, radius, paint); paint.setColor(0xFFFFFFFF); canvas.drawText(count, x, y - (paint.ascent() + paint.descent()) / 2, paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
