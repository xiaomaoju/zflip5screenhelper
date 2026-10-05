package io.github.flipcover.controls;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

public final class Ui {
    public static final float CONTROL_CORNER_DP = BuildConfig.APPEARANCE_PANEL_CONTROL_CORNER_DP;
    public static final int BACKGROUND = Color.BLACK, SURFACE = Color.rgb(22, 24, 28),
        TEXT = Color.rgb(244, 245, 247), MUTED = Color.rgb(151, 158, 170), ACTIVE = Color.rgb(204, 222, 240),
        ACCENT = Color.rgb(175, 205, 235), ON_ACTIVE = Color.rgb(16, 29, 43);
    public static int dp(Context context, float value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    public static GradientDrawable background(Context context, int color, float radius) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(context, radius)); return shape;
    }
    public static Drawable ripple(Context context, int color, float radius) {
        return new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF), background(context, color, radius), background(context, Color.WHITE, radius));
    }
    public static Drawable icon(Context context, int resource, int color) {
        Drawable drawable = context.getDrawable(resource).mutate(); drawable.setTint(color); return drawable;
    }
    public static int chromeColor(Prefs prefs) { return prefs.chromeStyle().equals("dark") ? Color.rgb(25, 28, 32) : TEXT; }
    public static Drawable chromeIcon(Context context, Drawable source, Prefs prefs, boolean tint) {
        if (tint) source.setTint(chromeColor(prefs));
        return prefs.chromeStyle().equals("contrast") ? ChromeShadowDrawable.forIcon(source, context.getResources().getDisplayMetrics().density) : source;
    }
    public static LinearLayout column(Context context) {
        LinearLayout view = new LinearLayout(context); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    public static LinearLayout row(Context context) {
        LinearLayout view = new LinearLayout(context); view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    public static LinearLayout group(Context context) {
        LinearLayout group = column(context); group.setBackground(background(context, SURFACE, 16)); group.setClipToOutline(true); return group;
    }
    public static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("kern"); view.setIncludeFontPadding(false); view.setLineSpacing(0, 1); return view;
    }
    public static TextView heading(Context context, String value, int size) {
        TextView text = text(context, value, size, TEXT); text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return text;
    }
    public static void section(LinearLayout parent, String label) {
        TextView title = text(parent.getContext(), label, 12, MUTED);
        title.setPadding(dp(parent.getContext(), 4), dp(parent.getContext(), 8), 0, dp(parent.getContext(), 2)); add(parent, title);
    }
    public static Button button(Context context, String label, Runnable action) {
        Button button = new Button(context); button.setText(label); button.setTextSize(13); button.setAllCaps(false);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{MUTED, TEXT}));
        button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(dp(context, 40)); button.setMinimumHeight(dp(context, 40));
        button.setPadding(dp(context, 10), dp(context, 6), dp(context, 10), dp(context, 6));
        button.setBackground(ripple(context, SURFACE, 16)); button.setStateListAnimator(null);
        button.setOnClickListener(v -> action.run()); return button;
    }
    public static void select(Button button, boolean selected) {
        button.setSelected(selected); button.setBackground(ripple(button.getContext(), selected ? ACTIVE : SURFACE, 16));
        button.setTextColor(selected ? ON_ACTIVE : TEXT);
    }
    public static ImageButton iconButton(Context context, int icon, String label, Runnable action) {
        ImageButton button = new ImageButton(context); button.setImageDrawable(icon(context, icon, TEXT));
        button.setScaleType(ImageView.ScaleType.FIT_CENTER); button.setPadding(dp(context, 8), dp(context, 8), dp(context, 8), dp(context, 8));
        button.setBackground(ripple(context, Color.TRANSPARENT, 14)); button.setContentDescription(label);
        button.setLayoutParams(new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36))); button.setOnClickListener(v -> action.run()); return button;
    }
    public static void iconOnButton(Button button, Drawable icon) {
        int size = dp(button.getContext(), 22); icon.setBounds(0, 0, size, size);
        button.setCompoundDrawablesRelative(icon, null, null, null); button.setCompoundDrawablePadding(dp(button.getContext(), 10));
        button.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
    }
    public static View settingRow(Context context, int icon, String title, String subtitle, Runnable action) {
        LinearLayout row = row(context); row.setMinimumHeight(dp(context, 50)); row.setPadding(dp(context, 12), dp(context, 6), dp(context, 10), dp(context, 6));
        ImageView symbol = new ImageView(context); symbol.setImageDrawable(icon(context, icon, ACCENT)); symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(dp(context, 23), dp(context, 23)); image.setMarginEnd(dp(context, 14)); row.addView(symbol, image);
        LinearLayout words = column(context); words.addView(heading(context, title, 14));
        if (!subtitle.isEmpty()) { TextView detail = text(context, subtitle, 11, MUTED); detail.setPadding(0, dp(context, 2), 0, 0); detail.setMaxLines(2); detail.setEllipsize(TextUtils.TruncateAt.END); words.addView(detail); }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        if (action != null) {
            ImageView arrow = new ImageView(context); arrow.setImageDrawable(icon(context, R.drawable.ic_ms_chevron_right, MUTED)); row.addView(arrow, new LinearLayout.LayoutParams(dp(context, 18), dp(context, 18)));
            arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); row.setBackground(ripple(context, Color.TRANSPARENT, 0)); row.setFocusable(true); row.setOnClickListener(v -> action.run());
        }
        return row;
    }
    public static void styleSlider(SeekBar slider) {
        slider.setProgressTintList(ColorStateList.valueOf(ACCENT)); slider.setThumbTintList(ColorStateList.valueOf(ACCENT));
        slider.setProgressBackgroundTintList(ColorStateList.valueOf(0xFF3A3F47)); slider.setMinimumHeight(dp(slider.getContext(), 40));
    }
    public static void add(LinearLayout parent, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(parent.getContext(), 4); parent.addView(view, params);
    }
    public static void weighted(LinearLayout parent, View view) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
        params.setMargins(dp(parent.getContext(), 3), 0, dp(parent.getContext(), 3), 0); parent.addView(view, params);
    }
}
