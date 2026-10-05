package io.github.flipcover.controls;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Settings-only visual vocabulary. Runtime overlays continue to use Ui. */
final class SettingsUi {
    static final int BACKGROUND = Color.BLACK, SURFACE = 0xFF171719, MASTER = 0xFF39393C;
    static final int TEXT = 0xFFF5F5F7, MUTED = 0xFFB8B8C0, ACCENT = 0xFF80AAFF, ACTIVE = 0xFF233A60, ON_ACTIVE = TEXT;
    static final int CONTROL = 0xFF3875F6, DANGER = 0xFFFF8A80;
    /** Main settings only: measure a larger logical viewport, then let native View transforms map drawing and touch. */
    static final class Viewport extends FrameLayout {
        private final float scale;
        Viewport(Context context, int percent) { super(context); scale = BuildConfig.APPEARANCE_SETTINGS_BASE_SCALE * percent / 100f; setTag("settings-viewport"); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
            setMeasuredDimension(width, height);
            if (getChildCount() == 0) return;
            View content = getChildAt(0); content.setPivotX(0); content.setPivotY(0); content.setScaleX(scale); content.setScaleY(scale);
            content.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(width / scale)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(height / scale)), MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) { if (getChildCount() > 0) { View content = getChildAt(0); content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight()); } }
        static android.graphics.Rect bounds(View view, android.graphics.Rect local, View root) {
            android.graphics.RectF bounds = new android.graphics.RectF(local == null ? new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()) : local);
            android.graphics.Matrix global = new android.graphics.Matrix(), inverse = new android.graphics.Matrix();
            view.transformMatrixToGlobal(global); global.mapRect(bounds); global.reset(); root.transformMatrixToGlobal(global); if (global.invert(inverse)) inverse.mapRect(bounds);
            android.graphics.Rect result = new android.graphics.Rect(); bounds.roundOut(result); return result;
        }
    }
    static int dp(Context c, float value) { return Ui.dp(c, value); }
    static LinearLayout column(Context c) { return Ui.column(c); }
    static LinearLayout row(Context c) { return Ui.row(c); }
    static TextView text(Context c, String value, int size, int color) {
        TextView view = Ui.text(c, value, Math.max(size, 14), color); view.setLineSpacing(dp(c, 2), 1.08f); return view;
    }
    static TextView heading(Context c, String value, int size) {
        TextView view = text(c, value, Math.max(size, 16), TEXT); view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL)); return view;
    }
    static LinearLayout group(Context c) {
        LinearLayout group = column(c); group.setBackground(Ui.background(c, SURFACE, 20)); group.setClipToOutline(true);
        GradientDrawable line = new GradientDrawable(); line.setColor(0xFF38383D); line.setSize(1, 1);
        group.setDividerDrawable(line); group.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE); group.setDividerPadding(dp(c, 14));
        group.setPadding(0, dp(c, 4), 0, dp(c, 4)); return group;
    }
    static void add(LinearLayout parent, View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = dp(parent.getContext(), 16); parent.addView(view, lp);
    }
    static void section(LinearLayout parent, String label) {
        TextView text = text(parent.getContext(), label, 14, MUTED); text.setAccessibilityHeading(true);
        text.setPadding(dp(parent.getContext(), 14), dp(parent.getContext(), 4), dp(parent.getContext(), 14), dp(parent.getContext(), 8)); parent.addView(text);
    }
    static Button button(Context c, String label, Runnable action) {
        Button button = new Button(c); button.setText(label); button.setAllCaps(false); button.setTextSize(15); button.setMinWidth(0); button.setMinimumWidth(0);
        button.setMinHeight(dp(c, 48)); button.setMinimumHeight(dp(c, 48)); button.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        button.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}}, new int[]{MUTED, ACCENT}));
        button.setBackground(Ui.ripple(c, SURFACE, 20)); button.setStateListAnimator(null); button.setOnClickListener(v -> action.run()); return button;
    }
    static ImageButton iconButton(Context c, int icon, String label, Runnable action) {
        ImageButton button = Ui.iconButton(c, icon, label, action); button.setMinimumWidth(dp(c, 48)); button.setMinimumHeight(dp(c, 48));
        button.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12)); button.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48))); return button;
    }
    /** Same catalog glyph and compact face geometry as the control center, without live toggle state. */
    static FrameLayout shortcutIcon(Context c, android.graphics.drawable.Drawable drawable) {
        FrameLayout face = new FrameLayout(c); face.setBackground(Ui.background(c, 0xFF262A31, Ui.CONTROL_CORNER_DP)); face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        ImageView icon = new ImageView(c); icon.setTag("app-icon"); icon.setImageDrawable(drawable == null ? Ui.icon(c, R.drawable.ic_ms_apps, TEXT) : drawable);
        face.addView(icon, new FrameLayout.LayoutParams(dp(c, 24), dp(c, 24), Gravity.CENTER)); return face;
    }
    static LinearLayout shortcutRow(Context c, String id, String title, String summary, android.graphics.drawable.Drawable drawable, boolean selected, Runnable action) {
        LinearLayout row = row(c); row.setTag("library-item-" + id); row.setMinimumHeight(dp(c, 56)); row.setPadding(dp(c, 12), dp(c, 8), dp(c, 12), dp(c, 8)); row.setBackground(Ui.ripple(c, 0, 0));
        LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)); image.setMarginEnd(dp(c, 12)); row.addView(shortcutIcon(c, drawable), image);
        LinearLayout words = column(c); words.addView(text(c, title, 16, TEXT));
        if (!summary.isEmpty()) { TextView detail = text(c, summary, 14, MUTED); detail.setPadding(0, dp(c, 2), 0, 0); words.addView(detail); }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1)); ImageView state = new ImageView(c); state.setImageDrawable(Ui.icon(c, selected ? R.drawable.ic_order_check : R.drawable.ic_ms_add, ACCENT)); state.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams badge = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)); badge.setMarginStart(dp(c, 8)); row.addView(state, badge);
        row.setSelected(selected); row.setStateDescription(selected ? "已添加" : "可添加"); row.setContentDescription(title + (summary.isEmpty() ? "" : "，" + summary)); row.setFocusable(true);
        if (!selected) row.setOnClickListener(v -> action.run()); return row;
    }
    static View sourceTabs(Context c, String selected, Consumer<String> select) {
        android.widget.HorizontalScrollView viewport = new android.widget.HorizontalScrollView(c); viewport.setTag("library-tabs"); viewport.setHorizontalScrollBarEnabled(false); viewport.setFillViewport(true); viewport.setPadding(dp(c, 12), 0, dp(c, 12), dp(c, 8));
        LinearLayout tabs = row(c); tabs.setBackground(Ui.background(c, SURFACE, 18)); viewport.addView(tabs, new android.widget.HorizontalScrollView.LayoutParams(-2, -2));
        String[] ids = {"builtin", "tiles", "apps"}, labels = {"内置功能", "应用磁贴", "应用"};
        for (int i = 0; i < ids.length; i++) { String id = ids[i]; boolean active = id.equals(selected); Button tab = button(c, labels[i], () -> { if (!active) select.accept(id); }); tab.setTag("library-tab-" + id); tab.setSingleLine(); tab.setTextSize(14); tab.setPadding(dp(c, 12), 0, dp(c, 12), 0); select(tab, active); tabs.addView(tab, new LinearLayout.LayoutParams(-2, -2, 1)); if (active) tab.post(() -> tab.requestRectangleOnScreen(new android.graphics.Rect(0, 0, tab.getWidth(), tab.getHeight()), true)); }
        return viewport;
    }
    static void select(Button button, boolean selected) {
        button.setSelected(selected); button.setBackground(Ui.ripple(button.getContext(), selected ? ACTIVE : SURFACE, 16)); button.setTextColor(selected ? TEXT : ACCENT);
        button.setStateDescription(selected ? "已选择" : "未选择");
    }
    static void weighted(LinearLayout parent, View view) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1); lp.setMargins(dp(parent.getContext(), 3), 0, dp(parent.getContext(), 3), 0); parent.addView(view, lp);
    }
    static View settingRow(Context c, int icon, String title, String subtitle, Runnable action) { return new ValueRow(c, icon, title, subtitle, false, action); }
    static ValueRow valueRow(Context c, String title, String value, Runnable action) { return new ValueRow(c, 0, title, value, true, action); }
    static final class ValueRow extends LinearLayout {
        final TextView value;
        ValueRow(Context c, int icon, String title, String summary, boolean currentValue, Runnable action) {
            super(c); setGravity(Gravity.CENTER_VERTICAL); setMinimumHeight(dp(c, summary.isEmpty() ? 56 : 72)); setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
            if (icon != 0) { ImageView symbol = new ImageView(c); symbol.setImageDrawable(Ui.icon(c, icon, ACCENT)); symbol.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)); lp.setMarginEnd(dp(c, 14)); addView(symbol, lp); }
            LinearLayout words = column(c); words.addView(heading(c, title, 16)); value = text(c, summary, 14, currentValue ? ACCENT : MUTED); value.setPadding(0, dp(c, 4), 0, 0); value.setVisibility(summary.isEmpty() ? GONE : VISIBLE); words.addView(value); addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            if (action != null) { setBackground(Ui.ripple(c, Color.TRANSPARENT, 0)); setFocusable(true); setOnClickListener(v -> action.run()); }
        }
        void value(String next) { if (!next.contentEquals(value.getText())) value.setText(next); value.setVisibility(next.isEmpty() ? GONE : VISIBLE); }
    }
    static Switch toggle(LinearLayout parent, String title, String summary, boolean checked, Consumer<Boolean> change) {
        Context c = parent.getContext(); LinearLayout row = row(c); row.setPadding(dp(c, 14), dp(c, 8), dp(c, 10), dp(c, 8)); row.setMinimumHeight(dp(c, 56));
        LinearLayout words = column(c); TextView label = heading(c, title, 16); words.addView(label);
        if (!summary.isEmpty()) { TextView info = text(c, summary, 14, MUTED); info.setPadding(0, dp(c, 4), dp(c, 4), 0); words.addView(info); }
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        Switch toggle = new Switch(c); toggle.setShowText(false); toggle.setMinimumWidth(dp(c, 48)); toggle.setMinimumHeight(dp(c, 48)); toggle.setContentDescription(title + (summary.isEmpty() ? "" : "，" + summary));
        toggle.setThumbTintList(ColorStateList.valueOf(TEXT)); toggle.setTrackTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{CONTROL, 0xFF65656C})); toggle.setChecked(checked);
        // A row click delegates once to the switch; the label itself is not another accessibility action.
        words.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); row.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(c, 52), dp(c, 48))); row.setBackground(Ui.ripple(c, Color.TRANSPARENT, 0)); row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked()));
        toggle.setOnCheckedChangeListener((button, enabled) -> change.accept(enabled)); parent.addView(row); return toggle;
    }
    static TextView toggleSummary(Switch toggle) { LinearLayout words = (LinearLayout) ((LinearLayout) toggle.getParent()).getChildAt(0); return words.getChildCount() > 1 ? (TextView) words.getChildAt(1) : null; }
    static EditText search(Context c, String hint, String query) {
        EditText search = new EditText(c); search.setTextColor(TEXT); search.setHintTextColor(MUTED); search.setTextSize(16); search.setSingleLine(); search.setHint(hint); search.setText(query);
        search.setMinimumHeight(dp(c, 48)); search.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10)); search.setBackground(Ui.background(c, SURFACE, 24)); search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH); return search;
    }
    /** Native SeekBar semantics, including accessibility adjustments and transactional touch cancellation. */
    static final class Slider extends SeekBar {
        private boolean tracking, cancelling;
        private int committed;
        private final IntConsumer preview, save;
        Slider(Context c, int min, int max, int value, IntConsumer preview, IntConsumer save) {
            super(c); this.preview = preview; this.save = save; committed = value; setMin(min); setMax(max); setProgress(value); setMinimumHeight(dp(c, 48)); setPadding(dp(c, 14), 0, dp(c, 14), 0);
            setProgressTintList(ColorStateList.valueOf(CONTROL)); setThumbTintList(ColorStateList.valueOf(ACCENT)); setProgressBackgroundTintList(ColorStateList.valueOf(0xFF65656C));
            setOnSeekBarChangeListener(new OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) { preview.accept(progress); if (fromUser && !tracking && !cancelling) commit(); }
                @Override public void onStartTrackingTouch(SeekBar bar) { tracking = true; }
                @Override public void onStopTrackingTouch(SeekBar bar) { if (!cancelling) commit(); tracking = false; }
            });
        }
        private void commit() { if (committed != getProgress()) { save.accept(getProgress()); committed = getProgress(); } }
        private void cancelPreview() { cancelling = true; setProgress(committed); preview.accept(committed); tracking = false; cancelling = false; }
        @Override public boolean onTouchEvent(MotionEvent event) {
            boolean cancel = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) getParent().requestDisallowInterceptTouchEvent(true);
            if (cancel) cancelling = true;
            boolean handled = super.onTouchEvent(event);
            if (cancel) cancelPreview();
            if (cancel || event.getActionMasked() == MotionEvent.ACTION_UP) getParent().requestDisallowInterceptTouchEvent(false);
            return handled;
        }
        @Override protected void onDetachedFromWindow() { if (tracking) cancelPreview(); super.onDetachedFromWindow(); }
    }
}
