package io.github.flipcover.controls;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RemoteViews;

/** Scales a provider-owned preview as a View, never a screenshot; preview touches cannot launch apps. */
final class WidgetPreview extends FrameLayout {
    private float logicalWidth = 200, logicalHeight = 100;
    private boolean scaled;
    WidgetPreview(Context context) { super(context); setClipChildren(true); setClipToPadding(true); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); }
    void remote(AppWidgetProviderInfo info, RemoteViews views, float width, float height) {
        removeAllViews(); scaled = true; logicalWidth = Math.max(1, width); logicalHeight = Math.max(1, height);
        AppWidgetHostView host = new AppWidgetHostView(getContext()); host.setAppWidget(-1, info); host.setPadding(0, 0, 0, 0);
        host.updateAppWidget(views); addView(host); requestLayout();
    }
    void image(Drawable image) { removeAllViews(); scaled = false; ImageView view = new ImageView(getContext()); view.setImageDrawable(image); view.setScaleType(ImageView.ScaleType.FIT_CENTER); addView(view, new LayoutParams(-1, -1)); }
    void unavailable(String label) {
        removeAllViews(); scaled = false; android.widget.TextView text = SettingsUi.text(getContext(), label, 14, SettingsUi.MUTED); text.setGravity(Gravity.CENTER); addView(text, new LayoutParams(-1, -1));
    }
    @Override protected void onMeasure(int width, int height) {
        int w = MeasureSpec.getSize(width), h = MeasureSpec.getSize(height); setMeasuredDimension(w, h);
        if (getChildCount() == 0) return;
        int cw = scaled ? Ui.dp(getContext(), logicalWidth) : w, ch = scaled ? Ui.dp(getContext(), logicalHeight) : h;
        getChildAt(0).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        if (getChildCount() == 0) return; View child = getChildAt(0); int w = child.getMeasuredWidth(), h = child.getMeasuredHeight();
        child.layout(0, 0, w, h); float scale = scaled ? Math.min(getWidth() / (float) Math.max(1, w), getHeight() / (float) Math.max(1, h)) : 1;
        child.setPivotX(0); child.setPivotY(0); child.setScaleX(scale); child.setScaleY(scale); child.setTranslationX((getWidth() - w * scale) / 2); child.setTranslationY((getHeight() - h * scale) / 2);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
    @Override public boolean onTouchEvent(MotionEvent event) { return true; }
}
