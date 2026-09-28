package io.github.flipcover.controls;

import android.content.ComponentName;
import android.content.Context;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import android.graphics.RectF;

/** One Dock surface, retained while the application catalog expands above it. */
final class AppDockView extends FrameLayout {
    interface Listener {
        View application(String id, int width, String prefix, Runnable action);
        void launch(String id); void openTask(RecentTasks.Task task); void clear(List<RecentTasks.Task> tasks);
        void toggleApps(); void editPinned(); void refresh(); void close();
        void menu();
    }
    private final Prefs prefs;
    private final Listener listener;
    private final LinearLayout area, row;
    private final View dismissLeft, dismissRight;
    private final ImageButton apps;
    private final TextView warning;
    private final FrameLayout appsCell;
    private List<RecentTasks.Task> tasks = List.of(), shownRecent = List.of();
    private boolean known, busy, canOpen = true, canClear = true, failed;
    private String signature = "";
    private ImageButton clear;
    private List<String> dropBase, previewPins, renderedPins = List.of();
    private String dropId;
    private final RectF dropBounds = new RectF();
    private final Map<String, Float> dropCenters = new HashMap<>(), oldPinX = new HashMap<>();
    void clearDrop() {
        if (dropBase == null) return;
        dropBase = previewPins = null; dropId = null; dropCenters.clear(); row.setForeground(null);
        if (getWidth() > 0) render(getWidth()); requestLayout();
    }
    List<String> dropOrder() { return previewPins; }
    int dropTarget(String id, float x, float y) {
        List<String> pins = prefs.hubPins();
        if (dropBase != null && (!dropBase.equals(pins) || !id.equals(dropId))) clearDrop();
        if (dropBase == null) {
            int[] origin = new int[2], location = new int[2]; getLocationOnScreen(origin); row.getLocationOnScreen(location);
            float left = location[0] - origin[0], top = location[1] - origin[1];
            View last = row.getChildAt(pins.size()); if (last == null) return -1;
            dropBounds.set(left, top - dp(10), left + last.getRight(), top + row.getHeight() + dp(6));
            if (!dropBounds.contains(x, y)) return -1;
            dropBase = List.copyOf(pins); dropId = id; dropCenters.clear();
            for (int i = 0; i < pins.size(); i++) { View cell = row.getChildAt(i + 1); dropCenters.put(pins.get(i), left + cell.getLeft() + cell.getWidth() / 2f); }
        }
        // Keep the acquisition region and centers stable while the preview makes room.
        if (!dropBounds.contains(x, y)) { clearDrop(); return -1; }
        if (pins.size() == AppDockPlacement.LIMIT && !pins.contains(id)) { row.setForeground(Ui.background(getContext(), 0x44E5A77D, 16)); return -2; }
        int index = 0; for (String pin : dropBase) if (!pin.equals(id) && x >= dropCenters.get(pin)) index++;
        List<String> next = AppDockPlacement.insert(dropBase, id, index);
        if (!next.equals(previewPins)) { previewPins = next; render(getWidth()); requestLayout(); }
        return index;
    }

    AppDockView(Context context, Prefs prefs, Listener listener) {
        super(context); this.prefs = prefs; this.listener = listener;
        area = Ui.column(context); area.setTag("hub-dock-area"); area.setGravity(Gravity.CENTER_HORIZONTAL); area.setPadding(0, dp(2), 0, 0); addView(area, new LayoutParams(-1, -2));
        row = Ui.row(context); row.setTag("hub-dock"); row.setGravity(Gravity.CENTER); row.setPadding(dp(3), 0, dp(2), 0); backdrop(false); area.addView(row, new LinearLayout.LayoutParams(-2, dp(34)));
        appsCell = new FrameLayout(context);
        apps = RuntimeVisuals.button(context, R.drawable.ic_ms_apps, "展开全部应用", listener::toggleApps); apps.setTag("hub-apps"); apps.setPadding(dp(5), dp(5), dp(5), dp(5)); appsCell.addView(apps, new LayoutParams(-1, -1));
        warning = Ui.text(context, "!", 9, Ui.MUTED); warning.setTag("dock-warning"); warning.setGravity(Gravity.CENTER); warning.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); appsCell.addView(warning, new LayoutParams(dp(10), dp(12), Gravity.TOP | Gravity.RIGHT)); warning.setVisibility(INVISIBLE);
        apps.setOnLongClickListener(v -> { listener.menu(); return true; });
        dismissLeft = dismissArea(context, "hub-dismiss-left", listener::close); dismissRight = dismissArea(context, "hub-dismiss-right", listener::close);
        addView(dismissLeft, new LayoutParams(0, 0)); addView(dismissRight, new LayoutParams(0, 0));
    }
    static View dismissArea(Context context, String tag, Runnable close) {
        int slop = ViewConfiguration.get(context).getScaledTouchSlop();
        View area = new View(context) {
            private float downX, downY; private boolean canceled;
            @Override public boolean onTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); canceled = false; }
                if (!canceled && (event.getPointerCount() > 1 || Math.hypot(event.getX() - downX, event.getY() - downY) > slop)) {
                    canceled = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.onTouchEvent(cancel); cancel.recycle();
                }
                return canceled || super.onTouchEvent(event);
            }
            @Override public boolean performClick() { return super.performClick(); }
        };
        area.setTag(tag); area.setContentDescription("点击空白关闭 Dock 和应用中心"); area.setFocusable(true); area.setOnClickListener(v -> close.run()); return area;
    }
    void data(List<RecentTasks.Task> tasks, boolean known, boolean busy, boolean canOpen, boolean canClear, boolean failed) {
        if (!this.tasks.equals(tasks)) clearDrop();
        this.tasks = tasks; this.known = known; this.busy = busy; this.canOpen = canOpen; this.canClear = canClear; this.failed = failed;
        if (getWidth() > 0) render(getWidth()); requestLayout(); updateState();
    }
    void catalogChanged() { signature = ""; requestLayout(); }
    void expanded(boolean expanded) { apps.setSelected(expanded); apps.setContentDescription(expanded ? "收起全部应用，保留 Dock" : "展开全部应用"); apps.setTooltipText(expanded ? "收起全部应用" : "全部应用（长按编辑或刷新）"); }
    void backdrop(boolean blur) { row.setBackground(Ui.background(getContext(), blur ? 0xD922272F : 0xFF22272F, 16)); }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    private Set<String> pinnedPackages() { Set<String> result = new HashSet<>(); for (String id : prefs.hubPins()) { ComponentName name = ActionCatalog.component(id); if (name != null) result.add(name.getPackageName()); } return result; }
    private List<RecentTasks.Task> clearTargets() {
        Set<String> shown = new HashSet<>(); for (RecentTasks.Task task : shownRecent) shown.add(task.packageName());
        List<RecentTasks.Task> targets = new ArrayList<>(); for (RecentTasks.Task task : RecentTasks.backgroundTargets(tasks, pinnedPackages())) if (shown.contains(task.packageName())) targets.add(task);
        return CoverApp.taskLocks(getContext()).unlocked(targets);
    }
    private void render(int width) {
        List<String> pins = previewPins == null ? prefs.hubPins() : previewPins; List<RecentTasks.Task> recent = RecentTasks.apps(tasks, pinnedPackages());
        int fixedChrome = row.getPaddingLeft() + row.getPaddingRight() + dp(34);
        int separatorWidth = Math.max(1, dp(.5f)), separatorMargin = dp(1);
        int recentChrome = recent.isEmpty() ? 0 : dp(22) + (pins.isEmpty() ? 0 : separatorWidth + 2 * separatorMargin);
        int capacity = Math.max(0, (width - fixedChrome - recentChrome) / Math.max(1, dp(28)) - pins.size());
        shownRecent = List.copyOf(recent.subList(0, Math.min(recent.size(), capacity)));
        boolean both = !pins.isEmpty() && !shownRecent.isEmpty(); int total = pins.size() + shownRecent.size();
        int chrome = fixedChrome + (both ? separatorWidth + 2 * separatorMargin : 0) + (shownRecent.isEmpty() ? 0 : dp(22));
        int cell = RecentTasks.dockCell(width, total, dp(34), chrome);
        List<String> keys = new ArrayList<>(); for (RecentTasks.Task task : shownRecent) keys.add(RecentTasks.key(task));
        String next = cell + ":" + pins + ":" + keys + ":" + (previewPins == null ? "" : dropId);
        if (next.equals(signature)) { updateState(); return; } signature = next;
        oldPinX.clear(); for (int i = 0; i < renderedPins.size() && i + 1 < row.getChildCount(); i++) { View item = row.getChildAt(i + 1); oldPinX.put(renderedPins.get(i), row.getLeft() + item.getX()); item.animate().cancel(); }
        row.removeAllViews(); clear = null; row.addView(appsCell, new LinearLayout.LayoutParams(dp(34), dp(34)));
        renderedPins = List.copyOf(pins);
        for (String id : pins) {
            View item = listener.application(id, cell, "常用：", () -> listener.launch(id));
            if (previewPins != null && id.equals(dropId)) { item.setAlpha(.25f); item.setBackground(Ui.background(getContext(), 0x668FC8EC, 10)); item.setContentDescription("松手固定到此位置"); }
            row.addView(item, new LinearLayout.LayoutParams(cell, dp(34)));
        }
        if (both) { View separator = new View(getContext()); separator.setTag("dock-separator"); separator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); separator.setBackgroundColor(0xFF65717D); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(separatorWidth, dp(18)); p.setMargins(separatorMargin, 0, separatorMargin, 0); row.addView(separator, p); }
        for (RecentTasks.Task task : shownRecent) {
            String launcher = CoverApp.catalog(getContext()).launcher(task.packageName()), id = launcher == null ? "app:" + task.component() : launcher;
            View item = listener.application(id, cell, "最近任务：", () -> { if (known && canOpen && !busy) listener.openTask(task); }); item.setTag(R.id.dock_recent_task, task); row.addView(item, new LinearLayout.LayoutParams(cell, dp(34)));
        }
        if (!shownRecent.isEmpty()) {
            clear = RuntimeVisuals.button(getContext(), R.drawable.ic_hub_clean, "清理可见最近应用的外屏后台任务，保留可见、固定和锁定任务", () -> listener.clear(clearTargets())); clear.setTag("hub-clear"); clear.setPadding(0, dp(8), 0, dp(8)); row.addView(clear, new LinearLayout.LayoutParams(dp(22), dp(34)));
        }
        updateState();
    }
    private void updateState() {
        warning.setVisibility(failed ? VISIBLE : INVISIBLE); appsCell.setContentDescription(failed ? "最近任务不可用，长按全部应用可刷新；固定应用仍可打开" : null);
        for (int i = 0; i < row.getChildCount(); i++) { View item = row.getChildAt(i); if (item.getTag(R.id.dock_recent_task) != null) { item.setEnabled(known && canOpen && !busy); item.setAlpha(known && canOpen ? 1 : .4f); } }
        if (clear != null) clear.setEnabled(known && canClear && !busy && !clearTargets().isEmpty());
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) { render(MeasureSpec.getSize(widthSpec)); super.onMeasure(widthSpec, heightSpec); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        area.layout(0, 0, getWidth(), area.getMeasuredHeight()); layoutDismiss(dismissLeft, 0, row.getLeft()); layoutDismiss(dismissRight, row.getRight(), getWidth());
        for (int i = 0; i < renderedPins.size(); i++) { View item = row.getChildAt(i + 1); Float old = oldPinX.get(renderedPins.get(i)); if (old != null && android.animation.ValueAnimator.areAnimatorsEnabled()) { item.setTranslationX(old - row.getLeft() - item.getLeft()); item.animate().translationX(0).setDuration(150).start(); } } oldPinX.clear();
    }
    @Override protected void onDetachedFromWindow() { clearDrop(); for (int i = 0; i < row.getChildCount(); i++) row.getChildAt(i).animate().cancel(); super.onDetachedFromWindow(); }
    private void layoutDismiss(View view, int left, int right) {
        int width = Math.max(0, right - left), height = row.getBottom(); view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)); view.layout(left, 0, right, height); view.setVisibility(width > 0 ? VISIBLE : INVISIBLE);
    }
}
