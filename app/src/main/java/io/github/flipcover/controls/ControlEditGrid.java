package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** At most 30 selected controls. Preview slots never mutate the owning editor's draft. */
final class ControlEditGrid extends ViewGroup {
    private final int columns;
    private final Consumer<String> click;
    private final BiPredicate<String, View> drag;
    private final ArrayList<String> items = new ArrayList<>(), positions = new ArrayList<>();
    private final Paint outline = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int cellHeight, marker = -1;
    private String moving;

    ControlEditGrid(Context c, int columns, Consumer<String> click, BiPredicate<String, View> drag) {
        super(c); this.columns = columns; this.click = click; this.drag = drag; setTag("control-selected-grid"); setClipChildren(false);
        outline.setColor(SettingsUi.ACCENT); outline.setStyle(Paint.Style.STROKE); outline.setStrokeWidth(Ui.dp(c, 2));
    }
    void items(List<String> values) {
        clearPreview(); items.clear(); items.addAll(values); positions.clear(); positions.addAll(items); for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().cancel(); removeAllViews();
        setContentDescription(items.isEmpty() ? "点按或拖入下方按钮" : null);
        for (String id : items) {
            Cell cell = new Cell(getContext()); cell.bind(id, ActionCatalog.label(getContext(), id), ActionCatalog.icon(getContext(), id), false, true);
            cell.setContentDescription(ActionCatalog.label(getContext(), id) + "，第 " + (items.indexOf(id) + 1) + " 项");
            cell.setTag("control-selected-" + id); cell.setOnClickListener(v -> click.accept(id)); cell.setOnLongClickListener(v -> drag.test(id, v));
            cell.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(host, info); info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, "管理顺序或移除")); }
            }); addView(cell);
        }
        requestLayout(); invalidate();
    }
    int columns() { return columns; }
    int slot(float x, float y) { return Math.min(items.size(), Math.max(0, (int) (y / Math.max(1, cellHeight))) * columns + Math.min(columns - 1, Math.max(0, (int) (x * columns / Math.max(1, getWidth()))))); }
    void preview(String id, int target) {
        if (target == marker && id.equals(moving)) return;
        moving = id; positions.clear(); positions.addAll(items); positions.remove(id); marker = Math.min(target, positions.size()); positions.add(marker, null);
        requestLayout(); invalidate();
    }
    void clearPreview() { if (moving == null && marker == -1) return; moving = null; marker = -1; positions.clear(); positions.addAll(items); requestLayout(); invalidate(); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), cellWidth = Math.max(1, width / columns); cellHeight = Ui.dp(getContext(), 72);
        for (int i = 0; i < getChildCount(); i++) { View child = getChildAt(i); child.measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)); cellHeight = Math.max(cellHeight, child.getMeasuredHeight()); }
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cellWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, Math.max(1, (positions.size() + columns - 1) / columns) * cellHeight);
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < items.size(); i++) {
            View child = getChildAt(i); int position = positions.indexOf(items.get(i)); child.setAlpha(position < 0 ? 0 : 1); if (position < 0) continue;
            int x = position % columns * getWidth() / columns, y = position / columns * cellHeight; float previousX = child.getX(), previousY = child.getY(); boolean animate = child.isLaidOut() && !changed && marker >= 0;
            child.animate().cancel(); child.layout(x, y, (position % columns + 1) * getWidth() / columns, y + cellHeight); child.setTranslationX(0); child.setTranslationY(0);
            if (animate && ValueAnimator.areAnimatorsEnabled()) { child.setTranslationX(previousX - x); child.setTranslationY(previousY - y); child.animate().translationX(0).translationY(0).setDuration(120).start(); }
        }
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (marker >= 0) { float inset = Ui.dp(getContext(), 3), x = marker % columns * getWidth() / (float) columns, y = marker / columns * cellHeight; canvas.drawRoundRect(x + inset, y + inset, x + getWidth() / (float) columns - inset, y + cellHeight - inset, Ui.dp(getContext(), 15), Ui.dp(getContext(), 15), outline); }
        if (items.isEmpty() && marker < 0) {
            outline.setStyle(Paint.Style.FILL); outline.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 13, getResources().getDisplayMetrics())); outline.setTextAlign(Paint.Align.CENTER); canvas.drawText("点按或拖入下方按钮", getWidth() / 2f, getHeight() / 2f, outline); outline.setStyle(Paint.Style.STROKE);
        }
    }
    @Override protected void onDetachedFromWindow() { for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().cancel(); super.onDetachedFromWindow(); }

    /** Shared glyph, face and label geometry for selected and virtualized candidate cells. */
    static final class Cell extends LinearLayout {
        final ImageView icon;
        private final FrameLayout face;
        private final TextView label, badge;
        Cell(Context c) {
            super(c); setOrientation(VERTICAL); setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL); setPadding(Ui.dp(c, 2), Ui.dp(c, 5), Ui.dp(c, 2), Ui.dp(c, 5)); setMinimumHeight(Ui.dp(c, 72)); setBackground(Ui.ripple(c, 0, 12));
            face = SettingsUi.shortcutIcon(c, null); icon = face.findViewWithTag("app-icon"); addView(face, new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)));
            badge = Ui.text(c, "", 10, SettingsUi.ACCENT); badge.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.dp(c, 10)); badge.setGravity(Gravity.CENTER); badge.setIncludeFontPadding(false); badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            face.addView(badge, new FrameLayout.LayoutParams(Ui.dp(c, 14), Ui.dp(c, 14), Gravity.TOP | Gravity.RIGHT));
            label = Ui.text(c, "", 12, SettingsUi.TEXT); label.setGravity(Gravity.CENTER); label.setMaxLines(2); label.setEllipsize(TextUtils.TruncateAt.END); label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); LinearLayout.LayoutParams words = new LinearLayout.LayoutParams(-1, -2); words.topMargin = Ui.dp(c, 4); addView(label, words); setFocusable(true);
        }
        void bind(String id, String name, Drawable drawable, boolean added, boolean editable) {
            setAlpha(1); icon.setImageDrawable(drawable == null ? Ui.icon(getContext(), R.drawable.ic_ms_apps, SettingsUi.TEXT) : drawable); label.setText(name); badge.setText(editable ? "⋮" : added ? "✓" : "+"); setSelected(added);
            setContentDescription(name); setStateDescription(editable ? "点按管理，长按拖动" : added ? "已添加" : "点按添加，也可拖入上方"); setTooltipText(name);
        }
    }
}
