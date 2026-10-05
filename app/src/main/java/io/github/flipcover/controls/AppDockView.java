package io.github.flipcover.controls;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry;
import com.kyant.backdrop.catalog.components.LiquidTensionStyle;
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

/** One Dock surface, retained while the application catalog expands above it. */
final class AppDockView extends FrameLayout implements SharedGlassHost {
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
    private List<String> dropBase, previewPins;
    private String dropId;
    private boolean glassHosted;
    private LauncherForce launcherForce;
    private android.animation.ValueAnimator entrance;
    static final long FUSION_WAIT_MS = BuildConfig.MOTION_DOCK_FUSION_WAIT_MS, FUSION_DURATION_MS = BuildConfig.MOTION_DOCK_FUSION_DURATION_MS;
    private boolean entrancePlayed, fusionPending, liquidDrawing;
    private PanelGlassSession glass;
    private final LiquidTensionGeometry tension = new LiquidTensionGeometry();
    private final RectF bodyBounds = new RectF();
    private final Paint fallback = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final View[] NO_ACTION_FACES = new View[0];
    private final Runnable mergeDock = this::startFusion;
    private final Runnable revealDock = this::reveal;
    void launcherForce(LauncherForce force) { launcherForce = force; ((LauncherMotionLayout) row).force(force); RuntimeVisuals.launcher(apps, null, LauncherForce.DOCK); if (!entrancePlayed && android.animation.ValueAnimator.areAnimatorsEnabled()) ((LauncherMotionLayout) row).dockEntrance(0); }
    void reveal() {
        if (launcherForce == null || entrancePlayed || !isAttachedToWindow() || !isShown()) return;
        entrancePlayed = true;
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { stopEntrance(); return; }
        fusionPending = true; postDelayed(mergeDock, LauncherForce.ENTRY_ESTIMATE_MS + FUSION_WAIT_MS);
    }
    private void startFusion() {
        fusionPending = false;
        if (!isAttachedToWindow() || !isShown() || !android.animation.ValueAnimator.areAnimatorsEnabled() || ((LauncherMotionLayout) row).fusionProgress() == 1) { stopEntrance(); return; }
        entrance = android.animation.ValueAnimator.ofFloat(((LauncherMotionLayout) row).fusionProgress(), 1); entrance.setDuration(FUSION_DURATION_MS); entrance.setInterpolator(new android.view.animation.LinearInterpolator());
        entrance.addUpdateListener(frame -> ((LauncherMotionLayout) row).dockEntrance((float) frame.getAnimatedValue()));
        entrance.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator ended) { if (entrance != ended) return; entrance = null; ((LauncherMotionLayout) row).dockEntrance(1); } }); entrance.start();
    }
    void stopEntrance() { removeCallbacks(revealDock); removeCallbacks(mergeDock); fusionPending = false; if (entrance != null) { entrance.removeAllListeners(); entrance.removeAllUpdateListeners(); entrance.cancel(); entrance = null; } ((LauncherMotionLayout) row).dockEntrance(1); }
    @Override public View glassBody() { return row; }
    @Override public GlassSurface.Role glassBodyRole() { return GlassSurface.Role.LAUNCHER_DOCK; }
    @Override public View[] glassActions() { return NO_ACTION_FACES; }
    @Override public boolean drawsSharedGlass(View surface) { return liquidDrawing && surface == row; }
    @Override public void glass(PanelGlassSession session) { glass = session; if (session != null) session.prepareTension(); else { stopEntrance(); liquidDrawing = false; row.invalidate(); } invalidate(); }
    void geometry(LiquidTensionGeometry result) {
        result.reset(); if (row.getWidth() == 0) return;
        LauncherMotionLayout motion = (LauncherMotionLayout) row; float density = getResources().getDisplayMetrics().density;
        float x = area.getLeft() + row.getLeft() + motion.visualX(), y = area.getTop() + row.getTop() + motion.visualY();
        bodyBounds.set(0, 0, row.getWidth(), row.getHeight()); motion.materialBounds(bodyBounds); bodyBounds.offset(x, y);
        result.setConnectionRange(0); result.setBevel(22 * density); result.setRefraction(18.7f * density);
        float p = motion.fusionProgress();
        if (p == 1) { result.add(bodyBounds.left, bodyBounds.top, bodyBounds.right, bodyBounds.bottom, Ui.dp(getContext(), AppLauncherStyle.dockRadius(getContext()))); return; }
        float absorption = Math.max(0, Math.min(1, (p - .65f) / .35f)); absorption = 1 - .35f * absorption * absorption * (3 - 2 * absorption);
        float radius = motion.toolRadius(false) * absorption, centerY = y + row.getHeight() / 2f;
        float centerX = x + appsCell.getLeft() + appsCell.getWidth() / 2f + motion.toolSlideX(false);
        result.add(centerX - radius, centerY - radius, centerX + radius, centerY + radius, radius);
        result.add(bodyBounds.left, bodyBounds.top, bodyBounds.right, bodyBounds.bottom, Ui.dp(getContext(), AppLauncherStyle.dockRadius(getContext())));
        if (clear != null) {
            radius = motion.toolRadius(true) * absorption; centerX = x + clear.getLeft() + clear.getWidth() / 2f + motion.toolSlideX(true);
            result.add(centerX - radius, centerY - radius, centerX + radius, centerY + radius, radius);
        }
        // Two joins in one group; mergePair() would replace the other side's connection.
        float grow = Math.max(0, Math.min(1, (p - .12f) / .3f)), settle = Math.max(0, Math.min(1, (p - .65f) / .35f));
        float join = 18 * density * grow * grow * (3 - 2 * grow) * (1 - settle * settle * (3 - 2 * settle));
        for (int i = 1; i < result.getCount(); i++) result.getJoins()[i] = join;
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        geometry(tension); boolean drawn = glass != null && glass.ready() && glass.drawTension(canvas, this, tension, true, LiquidTensionStyle.LauncherDock);
        if (liquidDrawing != drawn) { liquidDrawing = drawn; row.invalidate(); }
        if (!drawn && ((LauncherMotionLayout) row).fusionProgress() < 1) {
            fallback.setColor(AppLauncherStyle.DOCK_COLOR);
            for (int i = 0; i < tension.getCount(); i += 2) { int at = i * 4; canvas.drawCircle(tension.getShapes()[at], tension.getShapes()[at + 1], tension.getRadii()[i], fallback); }
        }
        super.dispatchDraw(canvas);
    }
    private final RectF dropBounds = new RectF();
    private final Map<String, Float> dropCenters = new HashMap<>();
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
        if (pins.size() == AppDockPlacement.LIMIT && !pins.contains(id)) { row.setForeground(Ui.background(getContext(), 0x44E5A77D, AppLauncherStyle.dockRadius(getContext()))); return -2; }
        int index = 0; for (String pin : dropBase) if (!pin.equals(id) && x >= dropCenters.get(pin)) index++;
        List<String> next = AppDockPlacement.insert(dropBase, id, index);
        if (!next.equals(previewPins)) { previewPins = next; render(getWidth()); requestLayout(); }
        return index;
    }

    AppDockView(Context context, Prefs prefs, Listener listener) {
        super(context); setClipChildren(false); this.prefs = prefs; this.listener = listener;
        area = Ui.column(context); area.setClipChildren(false); area.setTag("hub-dock-area"); area.setGravity(Gravity.CENTER_HORIZONTAL); area.setPadding(0, dp(AppLauncherStyle.DOCK_TOP), 0, 0); addView(area, new LayoutParams(-1, -2));
        row = new LauncherMotionLayout(context, null, LauncherForce.DOCK); row.setOrientation(LinearLayout.HORIZONTAL); row.setTag("hub-dock"); row.setGravity(Gravity.CENTER); row.setPadding(dp(AppLauncherStyle.DOCK_EDGE), 0, dp(AppLauncherStyle.DOCK_EDGE), 0); backdrop(false); area.addView(row, new LinearLayout.LayoutParams(-2, dp(AppLauncherStyle.DOCK_ROW)));
        appsCell = new FrameLayout(context); appsCell.setTag("dock-apps-slot");
        apps = RuntimeVisuals.button(context, R.drawable.ic_ms_apps, "展开全部应用", () -> { stopEntrance(); listener.toggleApps(); }); apps.setTag("hub-apps"); apps.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); int appsXPadding = dp((AppLauncherStyle.DOCK_TOOL_WIDTH - AppLauncherStyle.DOCK_APPS_ICON) / 2f), appsYPadding = dp((AppLauncherStyle.DOCK_ROW - AppLauncherStyle.DOCK_APPS_ICON) / 2f); apps.setPadding(appsXPadding, appsYPadding, appsXPadding, appsYPadding); appsCell.addView(apps, new LayoutParams(-1, -1));
        warning = Ui.text(context, "!", AppLauncherStyle.LABEL_SP, Ui.MUTED); warning.setTag("dock-warning"); warning.setGravity(Gravity.CENTER); warning.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); appsCell.addView(warning, new LayoutParams(dp(10), dp(12), Gravity.TOP | Gravity.RIGHT)); warning.setVisibility(INVISIBLE);
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
    void glassHosted() { if (glassHosted) return; glassHosted = true; row.setBackground(Ui.background(getContext(), 0xEB22272F, AppLauncherStyle.dockRadius(getContext()))); }
    void backdrop(boolean blur) { if (glassHosted) return; row.setBackground(Ui.background(getContext(), blur ? (AppLauncherStyle.DOCK_COLOR & 0x00FFFFFF) | 0xD9000000 : AppLauncherStyle.DOCK_COLOR, AppLauncherStyle.dockRadius(getContext()))); }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    private Set<String> pinnedPackages() { Set<String> result = new HashSet<>(); for (String id : prefs.hubPins()) { ComponentName name = ActionCatalog.component(id); if (name != null) result.add(name.getPackageName()); } return result; }
    private List<RecentTasks.Task> clearTargets() {
        return AppDockLayout.clearTargets(tasks, prefs.hubPins(), shownRecent, CoverApp.taskLocks(getContext()));
    }
    private void render(int width) {
        List<String> pins = previewPins == null ? prefs.hubPins() : previewPins; List<RecentTasks.Task> recent = RecentTasks.apps(tasks, pinnedPackages());
        AppDockLayout.Geometry geometry = AppDockLayout.fit(width, pins.size(), recent.size(), getResources().getDisplayMetrics().density);
        int separatorWidth = geometry.separator(), separatorMargin = geometry.margin();
        shownRecent = List.copyOf(recent.subList(0, geometry.recentCount()));
        boolean both = !pins.isEmpty() && !shownRecent.isEmpty();
        int cell = geometry.cell();
        List<String> keys = new ArrayList<>(); for (RecentTasks.Task task : shownRecent) keys.add(RecentTasks.key(task));
        String next = cell + ":" + pins + ":" + keys + ":" + (previewPins == null ? "" : dropId);
        if (next.equals(signature)) { updateState(); return; } signature = next;
        row.removeAllViews(); clear = null; row.addView(appsCell, new LinearLayout.LayoutParams(dp(AppLauncherStyle.DOCK_TOOL_WIDTH), dp(AppLauncherStyle.DOCK_ROW)));
        for (String id : pins) {
            View item = addApplication(id, cell, "常用：", () -> listener.launch(id));
            if (previewPins != null && id.equals(dropId)) { item.setAlpha(.25f); item.setBackground(Ui.background(getContext(), 0x668FC8EC, 10)); item.setContentDescription("松手固定到此位置"); }
        }
        if (both) { View separator = new View(getContext()); separator.setTag("dock-separator"); separator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); separator.setBackgroundColor(AppLauncherStyle.DOCK_SEPARATOR); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(separatorWidth, dp(AppLauncherStyle.DOCK_SEPARATOR_HEIGHT)); p.setMargins(separatorMargin, 0, separatorMargin, 0); row.addView(separator, p); }
        for (RecentTasks.Task task : shownRecent) {
            String launcher = CoverApp.catalog(getContext()).launcher(task.packageName()), id = launcher == null ? "app:" + task.component() : launcher;
            View item = addApplication(id, cell, "最近任务：", () -> { if (known && canOpen && !busy) listener.openTask(task); }); item.setTag(R.id.dock_recent_task, task);
        }
        if (!shownRecent.isEmpty()) {
            clear = RuntimeVisuals.button(getContext(), R.drawable.ic_hub_clean, "清理可见最近应用的外屏后台任务，保留可见、固定和锁定任务", () -> { stopEntrance(); listener.clear(clearTargets()); }); clear.setTag("hub-clear"); clear.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER); int xPadding = dp((AppLauncherStyle.DOCK_TOOL_WIDTH - AppLauncherStyle.DOCK_CLEAR_ICON) / 2f), yPadding = dp((AppLauncherStyle.DOCK_ROW - AppLauncherStyle.DOCK_CLEAR_ICON) / 2f); clear.setPadding(xPadding, yPadding, xPadding, yPadding); row.addView(clear, new LinearLayout.LayoutParams(dp(AppLauncherStyle.DOCK_TOOL_WIDTH), dp(AppLauncherStyle.DOCK_ROW)));
        }
        ((LauncherMotionLayout) row).dockTools(dp(AppLauncherStyle.DOCK_TOOL_WIDTH), clear == null ? 0 : dp(AppLauncherStyle.DOCK_TOOL_WIDTH));
        if (clear != null) RuntimeVisuals.launcher(clear, null, LauncherForce.DOCK);
        updateState();
    }
    private View addApplication(String id, int width, String prefix, Runnable action) {
        View item = listener.application(id, width, prefix, action); RuntimeVisuals.launcher(item, launcherForce, LauncherForce.DOCK); row.addView(item, new LinearLayout.LayoutParams(width, dp(AppLauncherStyle.DOCK_ROW))); return item;
    }
    private void updateState() {
        apps.setEnabled(true);
        warning.setVisibility(failed ? VISIBLE : INVISIBLE); appsCell.setContentDescription(failed ? "最近任务不可用，长按全部应用可刷新；固定应用仍可打开" : null);
        for (int i = 0; i < row.getChildCount(); i++) { View item = row.getChildAt(i); if (item.getTag(R.id.dock_recent_task) != null) { item.setEnabled(known && canOpen && !busy); item.setAlpha(known && canOpen ? 1 : .4f); } }
        if (clear != null) clear.setEnabled(known && canClear && !busy && !clearTargets().isEmpty());
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) { render(MeasureSpec.getSize(widthSpec)); super.onMeasure(widthSpec, heightSpec); }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        area.layout(0, 0, getWidth(), area.getMeasuredHeight()); ((LauncherMotionLayout) row).dockMargins(row.getLeft(), getWidth() - row.getRight()); layoutDismiss(dismissLeft, 0, row.getLeft()); layoutDismiss(dismissRight, row.getRight(), getWidth());
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || event.getPointerCount() > 1) stopEntrance(); return super.dispatchTouchEvent(event); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (oldw > 0 && (w != oldw || h != oldh)) stopEntrance(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); postOnAnimation(revealDock); }
    @Override protected void onVisibilityChanged(View changed, int visibility) { super.onVisibilityChanged(changed, visibility); if (row != null && (entrance != null || fusionPending) && !isShown()) stopEntrance(); }
    @Override protected void onDetachedFromWindow() { stopEntrance(); liquidDrawing = false; clearDrop(); super.onDetachedFromWindow(); }
    private void layoutDismiss(View view, int left, int right) {
        int width = Math.max(0, right - left), height = row.getBottom(); view.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY)); view.layout(left, 0, right, height); view.setVisibility(width > 0 ? VISIBLE : INVISIBLE);
    }
}
