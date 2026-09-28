package io.github.flipcover.controls;

import android.content.ClipData;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Bounded settings draft list. Dragging previews an insertion; only a drop changes the draft. */
final class SettingsOrderList extends LinearLayout {
    private final ArrayList<String> items;
    private final ScrollView scroll;
    private final Consumer<String> options;
    private final Runnable changed;
    private final Paint marker = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String dragging, removed;
    private int removedPosition, insertion = -1, scrollStep;
    private float pointerY;
    private boolean inside;
    private final Runnable autoScroll = new Runnable() {
        @Override public void run() {
            if (dragging == null || !inside || scrollStep == 0 || !isAttachedToWindow()) return;
            int before = scroll.getScrollY(); scroll.scrollBy(0, scrollStep); updateInsertion();
            if (scroll.getScrollY() != before) postOnAnimation(this);
        }
    };

    SettingsOrderList(Context context, ArrayList<String> items, ScrollView scroll, Bundle state, Consumer<String> options, Runnable changed) {
        super(context); this.items = items; this.scroll = scroll; this.options = options; this.changed = changed;
        setOrientation(VERTICAL); setTag("order-list"); setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4));
        setBackground(Ui.background(context, SettingsUi.SURFACE, 20)); setClipToOutline(true);
        marker.setColor(SettingsUi.ACCENT); marker.setStrokeWidth(Ui.dp(context, 3)); marker.setStrokeCap(Paint.Cap.ROUND);
        removed = state.getString("removed"); removedPosition = state.getInt("removed-position");
        scroll.setOnDragListener((v, event) -> drag(event)); render();
    }
    Bundle saveState() { Bundle state = new Bundle(); state.putString("removed", removed); state.putInt("removed-position", removedPosition); return state; }
    String removedLabel() { return canUndo() ? ActionCatalog.label(getContext(), removed) : ""; }
    boolean canUndo() { return removed != null && !items.contains(removed); }
    void undo() {
        if (!canUndo()) return;
        items.add(Math.min(removedPosition, items.size()), removed); String restored = removed; removed = null;
        render(); changed.run(); reveal(restored); announceForAccessibility("已撤销移除");
    }
    void remove(String id) {
        int position = items.indexOf(id); if (position < 0) return;
        removed = items.remove(position); removedPosition = position; render(); changed.run(); announceForAccessibility("已移除，可撤销");
    }
    void move(String id, int to) {
        int from = items.indexOf(id); if (from < 0 || to < 0 || to >= items.size() || from == to) return;
        items.add(to, items.remove(from)); render(); changed.run(); reveal(id);
        announceForAccessibility(ActionCatalog.label(getContext(), id) + "，已移到第 " + (to + 1) + " 项");
    }
    private void reveal(String id) {
        post(() -> { View row = findViewWithTag("order-item-" + id); if (row != null && isAttachedToWindow()) row.requestRectangleOnScreen(new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight()), true); });
    }
    private void render() {
        removeAllViews(); Context c = getContext();
        if (items.isEmpty()) { addView(SettingsUi.settingRow(c, R.drawable.ic_ms_add, "还没有快捷项", "点上方“添加”选择", null)); return; }
        for (String id : items) {
            String label = ActionCatalog.label(c, id); LinearLayout row = SettingsUi.row(c); row.setTag("order-item-" + id);
            row.setMinimumHeight(Ui.dp(c, 56)); row.setPadding(Ui.dp(c, 14), Ui.dp(c, 4), Ui.dp(c, 4), Ui.dp(c, 4));
            View icon = SettingsUi.shortcutIcon(c, ActionCatalog.icon(c, id));
            LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(Ui.dp(c, 40), Ui.dp(c, 40)); image.setMarginEnd(Ui.dp(c, 12)); row.addView(icon, image);
            TextView name = SettingsUi.heading(c, label, 16); name.setTag("order-name-" + id); name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END); name.setGravity(Gravity.CENTER_VERTICAL); name.setMinimumHeight(Ui.dp(c, 48));
            name.setContentDescription(label + "，第 " + (items.indexOf(id) + 1) + " 项，点按查看排序操作"); name.setBackground(Ui.ripple(c, 0, 0)); name.setOnClickListener(v -> options.accept(id)); name.setOnLongClickListener(v -> startDrag(id, row, v));
            name.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info); int position = items.indexOf(id);
                    if (position > 0) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.order_move_up, "上移"));
                    if (position + 1 < items.size()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.order_move_down, "下移"));
                }
                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == R.id.order_move_up || action == R.id.order_move_down) { move(id, items.indexOf(id) + (action == R.id.order_move_up ? -1 : 1)); return true; }
                    return super.performAccessibilityAction(host, action, args);
                }
            });
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            View remove = SettingsUi.iconButton(c, R.drawable.ic_ms_close, "移除 " + label, () -> remove(id)); remove.setTag("order-remove-" + id); row.addView(remove);
            View handle = SettingsUi.iconButton(c, R.drawable.ic_order_drag, "排序 " + label + "，长按拖动或点按选择位置", () -> options.accept(id)); handle.setTag("order-handle-" + id); handle.setOnLongClickListener(v -> startDrag(id, row, v)); row.addView(handle);
            addView(row, new LinearLayout.LayoutParams(-1, -2));
        }
    }
    private boolean startDrag(String id, View row, View pressed) {
        if (dragging != null || !items.contains(id)) return false;
        dragging = id;
        DragShadowBuilder shadow = new DragShadowBuilder(row) {
            @Override public void onProvideShadowMetrics(android.graphics.Point size, android.graphics.Point touch) { size.set(row.getWidth(), row.getHeight()); touch.set(pressed.getLeft() + pressed.getWidth() / 2, row.getHeight() / 2); }
        };
        if (!row.startDragAndDrop(ClipData.newPlainText("shortcut-order", id), shadow, this, 0)) { dragging = null; return false; }
        row.setAlpha(.35f); row.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); return true;
    }
    private boolean drag(DragEvent event) {
        if (event.getLocalState() != this) return false;
        switch (event.getAction()) {
            case DragEvent.ACTION_DRAG_STARTED: return dragging != null;
            case DragEvent.ACTION_DRAG_LOCATION:
                if (dragging == null) return false;
                inside = true; pointerY = event.getY(); updateInsertion();
                int edge = Math.min(Ui.dp(getContext(), 40), scroll.getHeight() / 4);
                scrollStep = pointerY < edge ? -Ui.dp(getContext(), 6) : pointerY > scroll.getHeight() - edge ? Ui.dp(getContext(), 6) : 0;
                removeCallbacks(autoScroll); if (scrollStep != 0) postOnAnimation(autoScroll); return true;
            case DragEvent.ACTION_DRAG_EXITED:
                inside = false; insertion = -1; removeCallbacks(autoScroll); invalidate(); return true;
            case DragEvent.ACTION_DROP:
                if (dragging == null) return false;
                pointerY = event.getY(); updateInsertion(); int from = items.indexOf(dragging), to = insertion > from ? insertion - 1 : insertion;
                String id = dragging; clearDrag(); if (from >= 0 && to >= 0) move(id, Math.min(to, items.size() - 1)); return true;
            case DragEvent.ACTION_DRAG_ENDED: clearDrag(); return true;
            default: return true;
        }
    }
    private void updateInsertion() {
        int[] listLocation = new int[2], scrollLocation = new int[2]; getLocationOnScreen(listLocation); scroll.getLocationOnScreen(scrollLocation);
        float y = pointerY + scrollLocation[1] - listLocation[1]; insertion = items.size();
        for (int i = 0; i < items.size(); i++) { View row = getChildAt(i); if (y < (row.getTop() + row.getBottom()) / 2f) { insertion = i; break; } }
        invalidate();
    }
    void cancelDrag() { if (dragging != null) cancelDragAndDrop(); clearDrag(); }
    private void clearDrag() {
        removeCallbacks(autoScroll); dragging = null; insertion = -1; inside = false; scrollStep = 0;
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).setAlpha(1); invalidate();
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas); float left = Ui.dp(getContext(), 66), right = getWidth() - Ui.dp(getContext(), 14);
        marker.setColor(0xFF38383D); marker.setStrokeWidth(1);
        for (int i = 1; i < getChildCount(); i++) canvas.drawLine(left, getChildAt(i).getTop(), right, getChildAt(i).getTop(), marker);
        if (insertion >= 0 && !items.isEmpty()) {
            float y = insertion == items.size() ? getChildAt(insertion - 1).getBottom() : getChildAt(insertion).getTop();
            marker.setColor(SettingsUi.ACCENT); marker.setStrokeWidth(Ui.dp(getContext(), 3)); canvas.drawLine(Ui.dp(getContext(), 14), y, right, y, marker);
        }
    }
    @Override protected void onDetachedFromWindow() { cancelDrag(); scroll.setOnDragListener(null); super.onDetachedFromWindow(); }
}
