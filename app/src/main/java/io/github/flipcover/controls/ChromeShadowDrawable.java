package io.github.flipcover.controls;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

/** Local icon shadow; one alpha mask per size, with no screen sampling. */
final class ChromeShadowDrawable extends Drawable {
    private static final int ICON_SHADOW_COLOR = 0xA6000000;
    private static final float ICON_RADIUS_DP = BuildConfig.APPEARANCE_STATUS_ICON_SHADOW_RADIUS_DP;
    private final Drawable foreground;
    private final float radius;
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final int[] offset = new int[2];
    private Bitmap shadow;
    ChromeShadowDrawable(Drawable source, float radius) { foreground = source; this.radius = Math.max(.5f, radius); shadowPaint.setColor(ICON_SHADOW_COLOR); }
    static ChromeShadowDrawable forIcon(Drawable source, float density) { return new ChromeShadowDrawable(source, density * ICON_RADIUS_DP); }
    void invalidateShadow() { shadow = null; invalidateSelf(); }
    static void applyShadow(Paint paint, float radius) {
        if (radius > 0) paint.setShadowLayer(radius, 0, radius / 4, 0xA6000000); else paint.clearShadowLayer();
    }
    @Override protected void onBoundsChange(Rect bounds) {
        Rect previous = foreground.getBounds();
        if (previous.width() != bounds.width() || previous.height() != bounds.height()) shadow = null;
        foreground.setBounds(bounds);
    }
    private void prepareShadow() {
        Rect bounds = getBounds();
        if (shadow != null || bounds.isEmpty()) return;
        Bitmap source = Bitmap.createBitmap(bounds.width(), bounds.height(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(source); canvas.translate(-bounds.left, -bounds.top);
        int alpha = foreground.getAlpha(); foreground.setAlpha(255);
        foreground.draw(canvas); foreground.setAlpha(alpha);
        Paint blur = new Paint(Paint.ANTI_ALIAS_FLAG); blur.setMaskFilter(new BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL));
        shadow = source.extractAlpha(blur, offset); source.recycle();
    }
    @Override public void draw(Canvas canvas) {
        prepareShadow(); Rect bounds = getBounds();
        if (shadow != null) canvas.drawBitmap(shadow, bounds.left + offset[0], bounds.top + offset[1] + radius / 4, shadowPaint);
        foreground.draw(canvas);
    }
    @Override public void setAlpha(int alpha) { foreground.setAlpha(alpha); shadowPaint.setAlpha(Math.round(android.graphics.Color.alpha(ICON_SHADOW_COLOR) * alpha / 255f)); invalidateSelf(); }
    @Override public int getAlpha() { return foreground.getAlpha(); }
    @Override public void setTint(int color) { foreground.setTint(color); invalidateShadow(); }
    @Override public void setColorFilter(ColorFilter filter) { foreground.setColorFilter(filter); invalidateShadow(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return foreground.getIntrinsicWidth(); }
    @Override public int getIntrinsicHeight() { return foreground.getIntrinsicHeight(); }
}
