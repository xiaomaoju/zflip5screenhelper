package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Draft-only spatial editor. A cancelled or invalid drag never changes a saved layout. */
final class WidgetGridEditor extends ViewGroup {
    private final List<WidgetGrid.Item> items;
    private final Consumer<WidgetGrid.Item> moved;
    private final IntConsumer selected;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final WidgetGrid.Item candidate;
    private WidgetGrid.Item dragging;
    private float startX, startY;
    private int offsetX, offsetY;
    private boolean active, changed;
    WidgetGridEditor(Context c, NativeWidgetBridge bridge, int outer, List<WidgetGrid.Item> items, WidgetGrid.Item candidate, Consumer<WidgetGrid.Item> moved, IntConsumer selected) {
        super(c); this.items = List.copyOf(items); this.candidate = candidate; this.moved = moved; this.selected = selected;
        setTag("widget-grid"); setWillNotDraw(false); setBackground(Ui.background(c, SettingsUi.SURFACE, 20)); setClipChildren(true);
        setContentDescription("4乘4布局。点按组件编辑，拖动改变位置；也可在组件详情中使用方向按钮。");
        for (WidgetGrid.Item item : items) {
            FrameLayout tile = new FrameLayout(c); tile.setBackground(Ui.background(c, 0xff262a34, 12));
            WidgetPreview preview = new WidgetPreview(c); android.appwidget.AppWidgetProviderInfo info = bridge.info(item.id()); android.widget.RemoteViews remote = bridge.preview(item.id());
            android.util.SizeF size = bridge.size(outer, item.width(), item.height());
            try { if (info != null && remote != null) preview.remote(info, remote, size.getWidth(), size.getHeight()); else if (info != null && android.os.Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0) preview.remote(info, new android.widget.RemoteViews(info.provider.getPackageName(), info.previewLayout), size.getWidth(), size.getHeight()); else preview.unavailable(""); } catch (RuntimeException ignored) { preview.unavailable(""); }
            tile.addView(preview, new FrameLayout.LayoutParams(-1, -1));
            boolean hasPreview = remote != null || (info != null && android.os.Build.VERSION.SDK_INT >= 31 && info.previewLayout != 0);
            TextView label = SettingsUi.text(c, hasPreview ? item.sizeLabel() : bridge.widgetLabel(item.id()) + " · " + item.sizeLabel(), 14, SettingsUi.TEXT); label.setMaxLines(hasPreview ? 1 : 2); label.setEllipsize(android.text.TextUtils.TruncateAt.END); label.setBackgroundColor(0xda171719); label.setPadding(Ui.dp(c, 4), 0, Ui.dp(c, 4), 0);
            tile.addView(label, new FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM)); tile.setTag(item); tile.setContentDescription(bridge.widgetLabel(item.id()) + "，" + item.sizeLabel() + "，第" + (item.y() + 1) + "行第" + (item.x() + 1) + "列"); tile.setFocusable(true); tile.setOnClickListener(v -> selected.accept(item.id())); addView(tile);
        }
    }
    private float cellWidth() { return getWidth() / 4f; }
    private float cellHeight() { return getHeight() / 4f; }
    @Override protected void onMeasure(int w, int h) {
        int width = MeasureSpec.getSize(w), height = resolveSize(Math.min(width, Ui.dp(getContext(), 220)), h); setMeasuredDimension(width, height);
        for (int i = 0; i < getChildCount(); i++) { View child = getChildAt(i); WidgetGrid.Item item = (WidgetGrid.Item) child.getTag(); child.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width * item.width() / 4 - Ui.dp(getContext(), 6)), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(Math.max(1, height * item.height() / 4 - Ui.dp(getContext(), 6)), MeasureSpec.EXACTLY)); }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (w != oldw || h != oldh) cancelDrag(); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int gap = Ui.dp(getContext(), 3);
        for (int i = 0; i < getChildCount(); i++) { View child = getChildAt(i); WidgetGrid.Item item = (WidgetGrid.Item) child.getTag(); int x = Math.round(item.x() * cellWidth()) + gap, y = Math.round(item.y() * cellHeight()) + gap; child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight()); }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); paint.setColor(0xff414550); paint.setStrokeWidth(Ui.dp(getContext(), 1));
        for (int i = 1; i < 4; i++) { canvas.drawLine(i * cellWidth(), 0, i * cellWidth(), getHeight(), paint); canvas.drawLine(0, i * cellHeight(), getWidth(), i * cellHeight(), paint); }
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas); WidgetGrid.Item mark = dragging != null ? dragging : candidate; if (mark == null) return;
        boolean fits = WidgetGrid.fits(items, mark); paint.setStyle(Paint.Style.FILL); paint.setColor(fits ? 0x6080aaff : 0x60ff6c73);
        RectF rect = new RectF(mark.x() * cellWidth() + 3, mark.y() * cellHeight() + 3, (mark.x() + mark.width()) * cellWidth() - 3, (mark.y() + mark.height()) * cellHeight() - 3); canvas.drawRoundRect(rect, 12, 12, paint);
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(), 2)); paint.setColor(fits ? SettingsUi.ACCENT : 0xffff6c73); canvas.drawRoundRect(rect, 12, 12, paint); paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return true; }
    @Override public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            startX = e.getX(); startY = e.getY(); int x = Math.min(3, (int) (startX / cellWidth())), y = Math.min(3, (int) (startY / cellHeight()));
            dragging = candidate;
            if (dragging == null) for (WidgetGrid.Item item : items) if (x >= item.x() && x < item.x() + item.width() && y >= item.y() && y < item.y() + item.height()) { dragging = item; break; }
            active = dragging != null; changed = false;
            if (active) { offsetX = candidate == null ? x - dragging.x() : 0; offsetY = candidate == null ? y - dragging.y() : 0; getParent().requestDisallowInterceptTouchEvent(true); if (candidate != null) update(e); }
            return true;
        }
        if (e.getPointerCount() > 1 || e.getActionMasked() == MotionEvent.ACTION_CANCEL) { cancelDrag(); return true; }
        if (!active) return true;
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE) { if (Math.hypot(e.getX() - startX, e.getY() - startY) > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) changed = true; if (changed) update(e); }
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            WidgetGrid.Item result = dragging; boolean shouldMove = changed || candidate != null; cancelDrag();
            if (shouldMove) { if (WidgetGrid.fits(items, result)) moved.accept(result); else announceForAccessibility("该位置放不下，请选择连续空位"); }
            else { performClick(); selected.accept(result.id()); }
        }
        return true;
    }
    private void update(MotionEvent e) { int x = Math.max(0, Math.min(4 - dragging.width(), (int) (e.getX() / cellWidth()) - offsetX)), y = Math.max(0, Math.min(4 - dragging.height(), (int) (e.getY() / cellHeight()) - offsetY)); dragging = dragging.at(x, y); invalidate(); }
    private void cancelDrag() { active = false; dragging = null; if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false); invalidate(); }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDetachedFromWindow() { cancelDrag(); super.onDetachedFromWindow(); }
}
