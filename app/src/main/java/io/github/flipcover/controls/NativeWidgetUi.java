package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Compact vocabulary for the native-widget editor, without changing other settings or hosts. */
final class NativeWidgetUi {
    static TextView text(Context c, String value, int size, int color) {
        TextView view = Ui.text(c, value, size, color); view.setLineSpacing(SettingsUi.dp(c, 1), 1); return view;
    }
    static TextView heading(Context c, String value, int size) {
        TextView view = text(c, value, size, SettingsUi.TEXT); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return view;
    }
    static void add(LinearLayout parent, View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(view instanceof Button ? -2 : -1, -2); lp.bottomMargin = SettingsUi.dp(parent.getContext(), 4); parent.addView(view, lp);
    }
    static Button button(Context c, String label, Runnable action) {
        Button button = SettingsUi.button(c, label, action); button.setTextSize(13); button.setMinHeight(SettingsUi.dp(c, 36)); button.setMinimumHeight(SettingsUi.dp(c, 36)); button.setPadding(SettingsUi.dp(c, 8), SettingsUi.dp(c, 4), SettingsUi.dp(c, 8), SettingsUi.dp(c, 4)); button.setBackground(Ui.ripple(c, SettingsUi.SURFACE, 12)); return button;
    }
    static ImageButton iconButton(Context c, int icon, String label, Runnable action) {
        ImageButton button = SettingsUi.iconButton(c, icon, label, action); button.setMinimumWidth(SettingsUi.dp(c, 40)); button.setMinimumHeight(SettingsUi.dp(c, 40)); button.setPadding(SettingsUi.dp(c, 10), SettingsUi.dp(c, 10), SettingsUi.dp(c, 10), SettingsUi.dp(c, 10)); button.setLayoutParams(new LinearLayout.LayoutParams(SettingsUi.dp(c, 40), SettingsUi.dp(c, 40))); return button;
    }
    static Button placementButton(Context c, String label, Runnable action) {
        Button button = button(c, label, action); button.setTextSize(12); button.setMinHeight(SettingsUi.dp(c, 32)); button.setMinimumHeight(SettingsUi.dp(c, 32)); button.setPadding(SettingsUi.dp(c, 6), SettingsUi.dp(c, 3), SettingsUi.dp(c, 6), SettingsUi.dp(c, 3)); return button;
    }
    static ImageButton placementIcon(Context c, int icon, String label, Runnable action) {
        ImageButton button = iconButton(c, icon, label, action); button.setMinimumWidth(SettingsUi.dp(c, 32)); button.setMinimumHeight(SettingsUi.dp(c, 32)); button.setPadding(SettingsUi.dp(c, 7), SettingsUi.dp(c, 7), SettingsUi.dp(c, 7), SettingsUi.dp(c, 7)); button.setLayoutParams(new LinearLayout.LayoutParams(SettingsUi.dp(c, 32), SettingsUi.dp(c, 32))); return button;
    }
    static void preview(LinearLayout parent, View grid, int width) {
        android.widget.FrameLayout frame = new android.widget.FrameLayout(parent.getContext()); frame.setTag("widget-preview-frame");
        frame.addView(grid, new android.widget.FrameLayout.LayoutParams(width, -2, android.view.Gravity.CENTER_HORIZONTAL)); add(parent, frame);
    }
    static View settingRow(Context c, int icon, String title, String summary, Runnable action) {
        LinearLayout row = SettingsUi.row(c); row.setMinimumHeight(SettingsUi.dp(c, 44)); row.setPadding(SettingsUi.dp(c, 8), SettingsUi.dp(c, 4), SettingsUi.dp(c, 8), SettingsUi.dp(c, 4));
        ImageView symbol = new ImageView(c); symbol.setImageDrawable(Ui.icon(c, icon, SettingsUi.ACCENT)); symbol.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(SettingsUi.dp(c, 20), SettingsUi.dp(c, 20)); image.setMarginEnd(SettingsUi.dp(c, 10)); row.addView(symbol, image);
        LinearLayout words = SettingsUi.column(c); words.addView(heading(c, title, 13)); words.addView(text(c, summary, 11, SettingsUi.MUTED)); row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.setBackground(Ui.ripple(c, 0, 0)); row.setFocusable(true); row.setOnClickListener(v -> action.run()); return row;
    }
    static int previewHeight(Context c, int maximumDp) {
        var metrics = c.getSystemService(WindowManager.class).getCurrentWindowMetrics();
        var safe = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        int height = metrics.getBounds().height() - safe.top - safe.bottom;
        return Math.min(SettingsUi.dp(c, maximumDp), Math.max(SettingsUi.dp(c, 64), Math.round(height * .30f)));
    }
    static int contentWidth(Context c) {
        var metrics = c.getSystemService(WindowManager.class).getCurrentWindowMetrics();
        var safe = metrics.getWindowInsets().getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        return metrics.getBounds().width() - safe.left - safe.right - SettingsUi.dp(c, 16);
    }
    private NativeWidgetUi() { }
}
