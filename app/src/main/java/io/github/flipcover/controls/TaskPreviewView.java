package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;

/** A crop-filled convex lens over a borrowed task snapshot. The task page owns bitmap lifetime. */
final class TaskPreviewView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds = new RectF();
    private final Matrix crop = new Matrix();
    private Bitmap bitmap;
    private BitmapShader image;
    private Drawable icon;
    private Optics optics;
    private boolean glass, locked;
    private android.animation.ValueAnimator reveal;
    private android.animation.ValueAnimator elasticity;
    private float pull;
    private float pictureAlpha = 1;

    TaskPreviewView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        setForeground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x30FFFFFF), null, new android.graphics.drawable.Drawable() {
            private final Paint mask = new Paint(Paint.ANTI_ALIAS_FLAG);
            private int opacity = 255;
            @Override public void draw(Canvas canvas) { mask.setColor(Color.WHITE); mask.setAlpha(opacity); android.graphics.Rect r = getBounds(); float radius = cornerRadius(TaskPreviewView.this, r.width(), r.height()); canvas.drawRoundRect(r.left, r.top, r.right, r.bottom, radius, radius, mask); }
            @Override public void setAlpha(int alpha) { opacity = alpha; invalidateSelf(); } @Override public void setColorFilter(android.graphics.ColorFilter filter) { mask.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }));
    }
    void content(Bitmap bitmap, Drawable icon) {
        content(bitmap, icon, true);
    }
    void content(Bitmap bitmap, Drawable icon, boolean animateReveal) {
        if (this.bitmap == bitmap) { if (this.icon != icon) { this.icon = icon; if (bitmap == null) invalidate(); } return; }
        if (reveal != null) { reveal.cancel(); reveal = null; }
        this.bitmap = bitmap; this.icon = icon; optics = null;
        image = bitmap == null ? null : new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (image != null && Build.VERSION.SDK_INT >= 33) image.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        pictureAlpha = 1;
        if (bitmap != null && animateReveal && isAttachedToWindow() && isShown() && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            pictureAlpha = 0; reveal = android.animation.ValueAnimator.ofFloat(0, 1); reveal.setDuration(120);
            reveal.addUpdateListener(animation -> { pictureAlpha = (float) animation.getAnimatedValue(); invalidate(); }); reveal.start();
        }
        invalidate();
    }
    void locked(boolean value) { if (locked != value) { locked = value; invalidate(); } }
    void pull(float value) { pull(value, false, .5f); }
    /** Driven by the task page's shared solver; labels and lock badges retain their original geometry. */
    void force(float stretch, boolean horizontal, float rotation) {
        if (elasticity != null) { elasticity.cancel(); elasticity = null; }
        float nextPull = Math.min(TaskSpring.STRENGTH, stretch / .18f);
        setPivotX(getWidth() * .5f); setPivotY(getHeight() * .5f);
        setScaleX(1 + (horizontal ? stretch : -stretch * .55f)); setScaleY(1 + (horizontal ? -stretch * .55f : stretch)); setRotation(rotation);
        if (pull != nextPull) { pull = nextPull; invalidate(); }
    }
    void pull(float value, boolean horizontal, float anchor) {
        if (elasticity != null) { elasticity.cancel(); elasticity = null; }
        pull = Math.max(0, Math.min(1, value)) * TaskSpring.STRENGTH; setPivotX(getWidth() * anchor); setPivotY(getHeight() * .5f);
        setScaleX(1 + (horizontal ? .18f : -.10f) * pull); setScaleY(1 + (horizontal ? -.10f : .18f) * pull); invalidate();
    }
    void releaseUp() {
        if (elasticity != null) { elasticity.cancel(); elasticity = null; }
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) { resetPull(); return; }
        elasticity = android.animation.ValueAnimator.ofFloat(pull, 1.35f * TaskSpring.STRENGTH, 0); elasticity.setDuration(320);
        elasticity.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, .25f, 1));
        elasticity.addUpdateListener(animation -> { pull = (float) animation.getAnimatedValue(); setScaleX(1 - .10f * pull); setScaleY(1 + .18f * pull); invalidate(); }); elasticity.start();
    }
    void spring() {
        if (elasticity != null) { elasticity.cancel(); elasticity = null; }
        if (!android.animation.ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) { resetPull(); return; }
        if (pull == 0 && getScaleX() == 1 && getScaleY() == 1) return;
        float from = pull, x = getScaleX(), y = getScaleY(); elasticity = android.animation.ValueAnimator.ofFloat(0, 1); elasticity.setDuration(TaskSpring.DURATION); elasticity.setInterpolator(TaskSpring::progress);
        elasticity.addUpdateListener(animation -> { float value = (float) animation.getAnimatedValue(); pull = Math.max(0, from * (1 - value)); setScaleX(x + (1 - x) * value); setScaleY(y + (1 - y) * value); invalidate(); }); elasticity.start();
    }
    void rebound(float x, float y, float remaining) {
        if (elasticity != null) { elasticity.cancel(); elasticity = null; }
        pull = Math.max(0, Math.abs(x - 1) / .18f * remaining);
        setScaleX(1 + (x - 1) * remaining); setScaleY(1 + (y - 1) * remaining); invalidate();
    }
    void resetPull() { if (elasticity != null) { elasticity.cancel(); elasticity = null; } pull = 0; setScaleX(1); setScaleY(1); setRotation(0); invalidate(); }
    void glass(boolean enabled) { if (glass == enabled) return; glass = enabled; optics = null; invalidate(); }
    boolean refracting() { return glass && bitmap != null && Build.VERSION.SDK_INT >= 33 && isHardwareAccelerated(); }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    /** Keep the page's physical corner radius, without shrinking it with the card. */
    static float cornerRadius(View owner, float width, float height) {
        float radius = Ui.dp(owner.getContext(), 24);
        if (Build.VERSION.SDK_INT >= 31 && owner.getRootWindowInsets() != null) {
            float physical = Float.MAX_VALUE;
            for (int i = 0; i < 4; i++) { android.view.RoundedCorner corner = owner.getRootWindowInsets().getRoundedCorner(i); if (corner != null && corner.getRadius() > 0) physical = Math.min(physical, corner.getRadius()); }
            if (physical != Float.MAX_VALUE) radius = Math.max(0, physical - 1);
        }
        return Math.min(Math.min(width, height) / 2f, radius);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        bounds.set(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getHeight() - getPaddingBottom());
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float width = bounds.width(), height = bounds.height(), radius = cornerRadius(this, width, height);
        int save = canvas.save(); canvas.translate(bounds.left, bounds.top);
        paint.setShader(null); paint.setColor(0xFF252E39); paint.setAlpha(255); paint.setStyle(Paint.Style.FILL);
        if (!glass || !(getBackground() instanceof GlassSurface)) canvas.drawRoundRect(0, 0, width, height, radius, radius, paint);
        if (icon != null && (image == null || pictureAlpha < 1)) {
            int side = Math.max(1, Math.min(dp(40), Math.round(Math.min(width, height) * .65f)));
            int x = Math.round((width - side) / 2), y = Math.round((height - side) / 2), alpha = icon.getAlpha();
            icon.setBounds(x, y, x + side, y + side); icon.setAlpha(image == null ? 255 : Math.round(255 * (1 - pictureAlpha))); icon.draw(canvas); icon.setAlpha(alpha);
        }
        if (image != null && bitmap != null && !bitmap.isRecycled()) {
            float scale = Math.max(width / bitmap.getWidth(), height / bitmap.getHeight());
            crop.setScale(scale, scale); crop.postTranslate((width - bitmap.getWidth() * scale) / 2, (height - bitmap.getHeight() * scale) / 2); image.setLocalMatrix(crop);
            paint.setColor(Color.WHITE); paint.setAlpha(Math.round(255 * pictureAlpha));
            if (glass && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated()) {
                if (optics == null) optics = new Optics(image);
                optics.shader.setInputShader("image", image);
                optics.shader.setFloatUniform("size", width, height); optics.shader.setFloatUniform("radius", radius);
                optics.shader.setFloatUniform("bevel", Math.min(dp(16), radius * .85f));
                optics.shader.setFloatUniform("pull", pull);
                optics.shader.setFloatUniform("pixel", getResources().getDisplayMetrics().density);
                paint.setShader(optics.shader);
            } else paint.setShader(image);
            canvas.drawRoundRect(0, 0, width, height, radius, radius, paint); paint.setShader(null);
        }
        if (locked) { float stroke = Math.max(1, dp(1)); paint.setShader(null); paint.setColor(0xFFFFCE75); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(stroke); canvas.drawRoundRect(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2, radius, radius, paint); }
        canvas.restoreToCount(save);
    }
    @Override protected void onDetachedFromWindow() { resetPull(); if (reveal != null) { reveal.cancel(); reveal = null; } content(null, null); super.onDetachedFromWindow(); }

    // Shared by snapshot lenses and the icon-only glass surface; no lighting wash in the interior.
    static final String EDGE_LIGHTING = """
        half3 taskEdgeLight(half3 color, float alpha, float depth, float2 normal, float2 relative, float unit, float pull) {
            float vertical = pow(abs(normal.y), 4.0);
            float shoulder = pow(clamp(abs(relative.x), 0.0, 1.0), 3.0);
            float light = dot(normal, float2(-.60, -.80));
            // A narrow directional reflection leaves the refracted image visible beneath the bevel.
            float width = unit * (.48 + .10 * vertical);
            float arc = exp(-pow(abs((depth - unit * .85) / max(width, .55)), 2.0));
            float glint = arc * (.10 + .36 * pow(abs(light), 8.0) + .18 * shoulder * vertical + pull * .025);
            float rim = exp(-pow(abs((depth - unit * .45) / max(unit * .60, .6)), 2.0));
            float contact = exp(-pow(abs((depth - unit * 1.9) / max(unit * .40, .55)), 2.0));
            color *= 1.0 - contact * .06 * (.35 + .65 * max(-light, 0.0));
            color = mix(color, half3(.99, 1.0, 1.0) * alpha, clamp(glint, 0.0, .88));
            color = mix(color, half3(.52, .90, 1.0) * alpha, rim * (.12 + .16 * pow(abs(normal.x), 3.0)));
            return color;
        }
        """;

    @androidx.annotation.RequiresApi(33)
    private static final class Optics {
        final RuntimeShader shader = new RuntimeShader(EDGE_LIGHTING + """
            uniform shader image;
            uniform float2 size;
            uniform float radius;
            uniform float bevel;
            uniform float pull;
            uniform float pixel;
            half4 main(float2 p) {
                float2 center = size * .5;
                float2 c = p - center;
                float2 q = abs(c) - (center - radius);
                float2 outside = max(q, 0.0);
                float distance = length(outside) + min(max(q.x, q.y), 0.0) - radius;
                float2 normal = length(outside) > .001 ? sign(c) * normalize(outside) : sign(c) * (q.x > q.y ? float2(1,0) : float2(0,1));
                float t = clamp(max(abs(c.x) / center.x, abs(c.y) / center.y), 0.0, 1.0);
                // Positive, monotonic mapping: 1.15x in the center, continuous into the rim.
                float scale = mix(1.0 / (1.15 + pull * .05), 1.0, t * t * (3.0 - 2.0 * t));
                float depth = max(-distance, 0.0);
                float edge = clamp(1.0 - depth / max(bevel, 1.0), 0.0, 1.0);
                float curve = edge * edge * (3.0 - 2.0 * edge);
                float2 uv = center + c * scale - normal * bevel * .55 * curve;
                half4 sample = image.eval(uv);
                half3 color = sample.rgb;
                // Strong edge displacement remains monotonic (maximum added slope .825).
                // Dispersed edges and bright localized arcs leave the center unwashed.
                float spread = pixel * (.70 + pull * .10) * curve;
                if (spread > .01) color = half3(image.eval(uv + normal * spread).r, sample.g, image.eval(uv - normal * spread).b);
                color = taskEdgeLight(color, sample.a, depth, normal, c / center, pixel, pull);
                return half4(color, sample.a);
            }
            """);
        Optics(BitmapShader image) { shader.setInputShader("image", image); }
    }
}
