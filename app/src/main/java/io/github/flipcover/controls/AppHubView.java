package io.github.flipcover.controls;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ComponentName;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Independent scrolling rail/grid and a content-sized, centered fixed/recent Dock. */
final class AppHubView extends LinearLayout {
    interface Listener {
        void action(String id); void editFavorites(); void editPinned(); void expand(boolean expanded); void close();
        default void settings() { }
        default void applicationSettings(String id, boolean uninstall) { }
        default void refreshRecents() { }
        default void clearRecents(List<RecentTasks.Task> tasks) { }
        default void closeTask(RecentTasks.Task task) { clearRecents(List.of(task)); }
        default void openTask(RecentTasks.Task task) { }
        default void taskPageChanged(RecentTasksView page) { }
        default boolean managesTaskEntrance() { return false; }
        default void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { callback.accept(new ShizukuBridge.Snapshot(null, "预览不可用")); }
    }
    private record ShortcutBinding(String id, String prefix) { }
    private record IconBinding(String id, int generation, boolean loaded) { }
    private final Listener listener;
    private final Prefs prefs;
    private final AppCatalogCache cache;
    private final LinearLayout rail, catalog, summaryRow, favoriteItems;
    private final AppDockView dockHost;
    private final LinearLayout appContent;
    private RecentTasksView taskPage;
    private int dockEdge = DockGeometry.BOTTOM;
    private boolean canOpen = true, canClear = true, canSnapshot;
    private String taskMessage = "正在读取外屏任务…";
    private RecentTasks.Task pendingSelection;
    private final View dismissBackdrop;
    private Boolean backdropBlur;
    private final TextView information, sort, recentStatus;
    private final EditText search;
    private final AppWorkspaceView grid;
    private final AppWorkspaceTools workspaceTools;
    private final AppSearchIndex searchIndex = new AppSearchIndex();
    private final java.util.Map<String, Integer> badges = new HashMap<>();
    private org.json.JSONObject aliases = new org.json.JSONObject();
    private final List<AppCatalogCache.Entry> filtered = new ArrayList<>();
    private List<RecentTasks.Task> tasks = List.of();
    private List<String> shownPins = List.of();
    private final AppAdapter adapter = new AppAdapter();
    private boolean expanded = true, disposed, recentBusy, recentKnown, recentFailed;
    private final boolean rightRail;
    private ValueAnimator revealAnimation;
    private ValueAnimator contentAnimation;
    private float contentProgress = 1;
    private float revealProgress = 1;
    private boolean closing, dockHint;
    private String dockHeld;
    private boolean dockMoving;
    private float pointerX, pointerY, dockDownX, dockDownY;
    private final Runnable enter = () -> animateReveal(true, null);
    private final Runnable refreshPins = this::refreshDockPins;
    private long refreshDelay = 3000;
    private final AppCatalogCache.Listener catalogListener = new AppCatalogCache.Listener() {
        @Override public void catalogChanged() { if (!disposed) { searchIndex.clear(); renderFavorites(); filter(); dockHost.catalogChanged(); renderDock(); updateIcons(AppHubView.this, null); workspaceTools.changed(); workspaceTools.icons(null); if (taskPage != null) taskPage.catalogChanged(); } }
        @Override public void iconsChanged(Set<String> ids) { if (!disposed) { updateIcons(AppHubView.this, ids); workspaceTools.icons(ids); if (taskPage != null) taskPage.catalogChanged(); } }
    };
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (disposed || closing || !isAttachedToWindow()) return;
            if (!recentBusy) listener.refreshRecents();
            postDelayed(this, refreshDelay); // Mounted Dock/catalog/task page only; failures back off.
        }
    };
    static int railWidth(Context context) { return Ui.dp(context, AppLauncherStyle.RAIL_WIDTH); }
    AppHubView(Context context, Prefs prefs, Listener listener) {
        super(AppLauncherStyle.fixedFontContext(context)); Context uiContext = getContext(); this.listener = listener; this.prefs = prefs; cache = CoverApp.catalog(uiContext); rightRail = prefs.handSide().equals("right"); setOrientation(VERTICAL); setFocusableInTouchMode(true);
        dismissBackdrop = AppDockView.dismissArea(uiContext, "dock-dismiss-backdrop", listener::close); addView(dismissBackdrop, new LayoutParams(-1, 0, 1)); dismissBackdrop.setVisibility(GONE);
        LinearLayout top = Ui.row(uiContext); appContent = top; top.setGravity(Gravity.TOP); addView(top, new LayoutParams(-1, 0, 1));
        int railPadding = dp(AppLauncherStyle.RAIL_FRAME_PADDING);
        rail = Ui.column(uiContext); rail.setBackground(Ui.background(uiContext, Ui.SURFACE, AppLauncherStyle.panelRadius(uiContext))); rail.setPadding(railPadding, railPadding, railPadding, railPadding); rail.setTag("hub-rail"); top.addView(rail, new LayoutParams(railWidth(uiContext), -1));
        ScrollView favorites = new ScrollView(uiContext); favoriteItems = Ui.column(uiContext); favorites.addView(favoriteItems); renderFavorites();
        rail.addView(favorites, new LayoutParams(-1, 0, 1));
        LinearLayout tools = Ui.row(uiContext); tools.setTag("hub-tools");
        ImageButton edit = compactButton(R.drawable.ic_ms_edit, "侧栏编辑与设置", () -> { }); edit.setTag("hub-edit");
        edit.setOnClickListener(v -> {
            PopupMenu menu = new PopupMenu(uiContext, edit);
            menu.getMenu().add("编辑侧栏").setOnMenuItemClickListener(item -> { listener.editFavorites(); return true; });
            menu.getMenu().add("设置底部常用（最多" + AppDockPlacement.LIMIT + "个）").setOnMenuItemClickListener(item -> { listener.editPinned(); return true; });
            menu.getMenu().add("助手设置").setOnMenuItemClickListener(item -> { listener.settings(); return true; }); menu.show();
        });
        tools.addView(edit, new LayoutParams(-1, dp(AppLauncherStyle.RAIL_TOOLS_HEIGHT))); rail.addView(tools, new LayoutParams(-1, -2));
        catalog = Ui.column(uiContext); catalog.setBackground(Ui.background(uiContext, Ui.SURFACE, AppLauncherStyle.panelRadius(uiContext))); catalog.setPadding(dp(AppLauncherStyle.PANEL_PADDING_X), dp(AppLauncherStyle.PANEL_PADDING_Y), dp(AppLauncherStyle.PANEL_PADDING_X), dp(AppLauncherStyle.PANEL_PADDING_Y));
        LayoutParams catalogParams = new LayoutParams(0, -1, 1); catalogParams.leftMargin = dp(AppLauncherStyle.RAIL_GAP); top.addView(catalog, catalogParams);
        if (rightRail) { top.removeView(catalog); catalogParams.leftMargin = 0; catalogParams.rightMargin = dp(AppLauncherStyle.RAIL_GAP); top.addView(catalog, 0, catalogParams); }
        LinearLayout header = Ui.row(uiContext), field = Ui.row(uiContext); field.setBackgroundResource(R.drawable.launcher_widget_search);
        search = new EditText(uiContext); search.setTag("hub-search"); search.setTextSize(AppLauncherStyle.HUB_SEARCH_TEXT); search.setTextColor(Ui.TEXT); search.setHintTextColor(Ui.MUTED); search.setHint("搜索应用"); search.setSingleLine(); search.setBackgroundColor(android.graphics.Color.TRANSPARENT); search.setPadding(dp(AppLauncherStyle.HUB_SEARCH_PADDING), dp(AppLauncherStyle.HUB_FIELD_PADDING), dp(AppLauncherStyle.HUB_FIELD_PADDING), dp(AppLauncherStyle.HUB_FIELD_PADDING)); search.setMinimumHeight(dp(AppLauncherStyle.HUB_HEADER_HEIGHT)); search.setContentDescription("搜索全部应用"); field.addView(search, new LayoutParams(0, -2, 1));
        sort = Ui.text(uiContext, sortLabel(), AppLauncherStyle.HUB_SORT_TEXT, Ui.TEXT); sort.setTag("hub-sort"); sort.setGravity(Gravity.CENTER); sort.setMinHeight(dp(AppLauncherStyle.HUB_HEADER_HEIGHT)); sort.setMinWidth(dp(AppLauncherStyle.HUB_SORT_WIDTH)); sort.setPadding(dp(AppLauncherStyle.HUB_SORT_PADDING), dp(AppLauncherStyle.HUB_FIELD_PADDING), dp(AppLauncherStyle.HUB_SORT_PADDING), dp(AppLauncherStyle.HUB_FIELD_PADDING)); sort.setContentDescription("应用排序"); sort.setBackground(Ui.ripple(uiContext, android.graphics.Color.TRANSPARENT, 10)); sort.setOnClickListener(v -> showSort()); field.addView(sort, new LayoutParams(-2, -2)); header.addView(field, new LayoutParams(0, -2, 1));
        View taskButton = compactButton(R.drawable.ic_ms_view_carousel, "外屏多任务", () -> showTasks(true)); taskButton.setTag("hub-tasks"); taskButton.setPadding(dp(AppLauncherStyle.HUB_TASK_PADDING), dp(AppLauncherStyle.HUB_TASK_PADDING), dp(AppLauncherStyle.HUB_TASK_PADDING), dp(AppLauncherStyle.HUB_TASK_PADDING)); header.addView(taskButton, new LayoutParams(dp(AppLauncherStyle.HUB_TASK_WIDTH), dp(AppLauncherStyle.HUB_HEADER_HEIGHT)));
        View close = compactButton(R.drawable.ic_ms_close, "关闭应用中心", listener::close); close.setTag("hub-close"); close.setPadding(dp(AppLauncherStyle.HUB_BUTTON_PADDING), dp(AppLauncherStyle.HUB_BUTTON_PADDING), dp(AppLauncherStyle.HUB_BUTTON_PADDING), dp(AppLauncherStyle.HUB_BUTTON_PADDING)); header.addView(close, new LayoutParams(dp(AppLauncherStyle.HUB_CLOSE_WIDTH), dp(AppLauncherStyle.HUB_HEADER_HEIGHT))); catalog.addView(header, new LayoutParams(-1, dp(AppLauncherStyle.HUB_HEADER_HEIGHT)));
        summaryRow = Ui.row(uiContext); summaryRow.setTag("hub-summary"); catalog.addView(summaryRow, new LayoutParams(-1, dp(AppLauncherStyle.HUB_SUMMARY_HEIGHT)));
        information = Ui.text(uiContext, "正在读取应用…", AppLauncherStyle.HUB_SUMMARY_TEXT, Ui.MUTED); information.setPadding(dp(2), dp(1), 0, dp(1)); information.setSingleLine(); information.setEllipsize(android.text.TextUtils.TruncateAt.END); summaryRow.addView(information, new LayoutParams(0, -2, 1));
        recentStatus = Ui.text(uiContext, "最近任务需连接 Shizuku", AppLauncherStyle.HUB_RECENT_TEXT, Ui.MUTED); recentStatus.setTag("hub-recent-status"); recentStatus.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); recentStatus.setMinimumHeight(dp(AppLauncherStyle.HUB_SUMMARY_HEIGHT)); recentStatus.setPadding(dp(4), dp(1), dp(2), dp(1)); recentStatus.setSingleLine(); recentStatus.setEllipsize(android.text.TextUtils.TruncateAt.END); recentStatus.setOnClickListener(v -> { if (!recentBusy) listener.refreshRecents(); }); summaryRow.addView(recentStatus, new LayoutParams(0, -2, 1));
        grid = new AppWorkspaceView(uiContext, prefs, adapter, new AppWorkspaceView.Listener() {
            public void launch(String id) { if (grid.layoutSnapshot().folder(id) != null) workspaceTools.folder(id); else listener.action(id); }
            public void menu(View anchor, String id) { appMenu(anchor, id); }
            public void changed() { if (workspaceTools != null) workspaceTools.changed(); clearDockDrop(); }
            public boolean dockDrag(String id, float x, float y, boolean commit) { return dropDock(id, x, y, commit); }
            public boolean outsideDragVisual() { return true; }
            public void dragVisualChanged() { invalidate(); }
        });
        workspaceTools = new AppWorkspaceTools(this, grid, prefs);
        catalog.addView(grid, new LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { filter(); }
            @Override public void afterTextChanged(Editable editable) { }
        });
        dockHost = new AppDockView(uiContext, prefs, new AppDockView.Listener() {
            public View application(String id, int width, String prefix, Runnable action) { return dockApp(id, width, prefix, action); }
            public void launch(String id) { listener.action(id); }
            public void openTask(RecentTasks.Task task) { listener.openTask(task); }
            public void clear(List<RecentTasks.Task> tasks) { recentStatus.setText("正在请求系统清理…"); listener.clearRecents(tasks); }
            public void toggleApps() { toggle(); }
            public void editPinned() { listener.editPinned(); }
            public void refresh() { if (!recentBusy) listener.refreshRecents(); }
            public void menu() { AppWorkspaceTools.Actions actions = workspaceTools.actions("Dock", null); actions.add("编辑固定应用（最多" + AppDockPlacement.LIMIT + "个）", listener::editPinned); actions.add("刷新最近任务", !recentBusy, listener::refreshRecents); }
            public void close() { listener.close(); }
        }); addView(dockHost, new LayoutParams(-1, -2)); dockHost.expanded(true); renderDock();
        filter(); requestFocus();
    }
    void prepareEntrance() { if (ValueAnimator.areAnimatorsEnabled()) revealProgress(0); }
    void enter() { removeCallbacks(enter); postOnAnimation(enter); }
    void prepareTaskEntrance() { removeCallbacks(enter); cancelReveal(); prepareEntrance(); }
    void revealTasks(RecentTasksView page) {
        if (taskPage != page || closing || disposed) return;
        prepareTaskEntrance(); page.previewsVisible(); enter();
    }
    boolean closing() { return closing; }
    void reopen() {
        if (disposed) return; closing = false; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        removeCallbacks(enter); animateReveal(true, null); removeCallbacks(refresh); if (isAttachedToWindow()) post(refresh);
    }
    void dismiss(Runnable finished) {
        if (disposed || closing) return; closing = true; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        dockHeld = null; dockMoving = false;
        grid.cancelInteraction(); workspaceTools.close();
        long now = android.os.SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
        super.dispatchTouchEvent(cancel); cancel.recycle(); cancelPendingInputEvents();
        removeCallbacks(enter); removeCallbacks(refresh); search.clearFocus(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0);
        animateReveal(false, finished);
    }
    private void cancelReveal() {
        if (revealAnimation == null) return; ValueAnimator current = revealAnimation; revealAnimation = null; current.removeAllListeners(); current.removeAllUpdateListeners(); current.cancel();
    }
    private void revealProgress(float value) {
        revealProgress = value;
        float travel = showingTasks() ? Math.max(1, getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels) : dp(52);
        setTranslationY(travel * (1 - value)); setAlpha(showingTasks() ? 1 : Math.max(0, Math.min(1, value)));
    }
    private void animateReveal(boolean showing, Runnable finished) {
        if (disposed) return; cancelReveal();
        float target = showing ? 1 : 0;
        if (!isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled() || Math.abs(revealProgress - target) < .001f) { revealProgress(target); if (finished != null) finished.run(); return; }
        revealAnimation = ValueAnimator.ofFloat(revealProgress, target);
        revealAnimation.setDuration(showing ? showingTasks() ? 500 : 340 : 180);
        revealAnimation.setInterpolator(showing ? showingTasks() ? new android.view.animation.PathInterpolator(.2f, 0, .2f, 1) : new android.view.animation.OvershootInterpolator(1.1f) : new android.view.animation.AccelerateInterpolator());
        revealAnimation.addUpdateListener(animation -> revealProgress((float) animation.getAnimatedValue()));
        revealAnimation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { if (revealAnimation != animation) return; revealAnimation = null; revealProgress(target); if (finished != null) finished.run(); }
        }); revealAnimation.start();
    }
    private boolean multiplePointers;
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (showingTasks()) revealProgress(revealProgress); if (grid != null && (w != oldw || h != oldh)) { dockHeld = null; dockMoving = false; grid.cancelInteraction(); if (workspaceTools != null) workspaceTools.resize(); } }
    @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
        super.dispatchDraw(canvas);
        if (grid.dragging()) { int[] source = new int[2], origin = new int[2]; grid.getLocationOnScreen(source); getLocationOnScreen(origin); int saved = canvas.save(); canvas.translate(source[0] - origin[0], source[1] - origin[1]); grid.drawDragVisual(canvas); canvas.restoreToCount(saved); }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        pointerX = event.getX(); pointerY = event.getY();
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) multiplePointers = false;
        if (!multiplePointers && (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN)) {
            multiplePointers = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
        }
        if (dockHeld != null) {
            if (multiplePointers || closing || event.getActionMasked() == MotionEvent.ACTION_CANCEL) { grid.cancelInteraction(); dockHeld = null; dockMoving = false; return true; }
            int[] origin = new int[2], source = new int[2]; getLocationOnScreen(origin); grid.getLocationOnScreen(source); float x = pointerX + origin[0] - source[0], y = pointerY + origin[1] - source[1];
            if (!dockMoving && event.getActionMasked() == MotionEvent.ACTION_MOVE && Math.hypot(pointerX - dockDownX, pointerY - dockDownY) > android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop()) {
                MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); dockMoving = true; grid.externalDockDrag(dockHeld, x, y);
            }
            if (dockMoving) { MotionEvent copy = MotionEvent.obtain(event); copy.setLocation(x, y); grid.dispatchTouchEvent(copy); copy.recycle(); }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) { String id = dockHeld; boolean moved = dockMoving; dockHeld = null; dockMoving = false; if (!moved) { MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); appMenu(dockHost, id); } }
            return true;
        }
        return multiplePointers || closing || !expanded && contentAnimation != null && event.getY() < dockHost.getTop() || super.dispatchTouchEvent(event);
    }
    void setBackdropBlur(boolean enabled) {
        if (backdropBlur != null && backdropBlur == enabled) return; backdropBlur = enabled;
        rail.setBackground(Ui.background(getContext(), enabled ? 0xD916181C : Ui.SURFACE, AppLauncherStyle.panelRadius(getContext()))); catalog.setBackground(Ui.background(getContext(), enabled ? 0xD916181C : Ui.SURFACE, AppLauncherStyle.panelRadius(getContext())));
        dockHost.backdrop(enabled);
    }
    String label(String id) { return AppLauncherModel.label(id, aliases, cache); }
    void launchApplication(String id) { listener.action(id); }
    void manualMode() { prefs.data.edit().putString("hub_sort", "manual").apply(); search.setText(""); sort.setText(sortLabel()); filter(); }
    void locate(String id) { manualMode(); String parent = grid.layoutSnapshot().parent(id); int slot = grid.projected().slot(parent == null ? id : parent); if (slot >= 0) grid.settlePage(slot / grid.capacity(), true); }
    void workspaceOptionsChanged() { dockHeld = null; dockMoving = false; grid.cancelInteraction(); grid.preferencesChanged(); searchIndex.clear(); filter(); renderFavorites(); dockHost.catalogChanged(); renderDock(); notificationsChanged(); }
    void dockPreferencesChanged() { removeCallbacks(refreshPins); post(refreshPins); }
    private void refreshDockPins() { if (!disposed && isAttachedToWindow()) { dockHeld = null; dockMoving = false; grid.cancelInteraction(); clearDockDrop(); grid.preferencesChanged(); filter(); renderDock(); } }
    void notificationsChanged() {
        notificationBadges(CoverNotifications.ready(), CoverNotifications.snapshot());
    }
    void notificationBadges(boolean ready, List<android.service.notification.StatusBarNotification> snapshot) {
        badges.clear();
        badges.putAll(AppLauncherModel.badges(prefs.workspaceBadges(), ready, snapshot));
        if (grid != null) grid.refresh();
    }
    private int badge(String id) { return AppLauncherModel.badge(id, badges); }
    private String catalogStatus(int count) { return AppLauncherModel.catalogStatus(count, cache.ready(), cache.failed()); }
    private void clearDockDrop() { dockHost.clearDrop(); if (dockHint) { dockHint = false; information.setText(catalogStatus(filtered.size())); } }
    private boolean dropDock(String id, float x, float y, boolean commit) {
        int[] origin = new int[2], dock = new int[2]; grid.getLocationOnScreen(origin); dockHost.getLocationOnScreen(dock);
        int index = dockHost.dropTarget(id, x + origin[0] - dock[0], y + origin[1] - dock[1]);
        boolean remove = dockMoving && index == -1 && x >= 0 && x < grid.getWidth() && y >= 0 && y < grid.gridHeight();
        if (index == -1 && !remove) { clearDockDrop(); return false; }
        List<String> pins = prefs.hubPins(); String hint = remove ? "松手移回桌面" : index == -2 ? "底部常用已满" + AppDockPlacement.LIMIT + "个，请先移出一个" : "松手" + (pins.contains(id) ? "调整常驻位置" : "固定到底部") + " · 第 " + (index + 1) + " 位";
        dockHint = true; if (!hint.contentEquals(information.getText())) { information.setText(hint); grid.announceForAccessibility(hint); }
        if (commit) {
            List<String> next = remove ? new ArrayList<>(pins) : dockHost.dropOrder(); if (remove) next.remove(id);
            if (index != -2 && next != null && !next.equals(pins)) {
                AppWorkspaceLayout before = prefs.workspace(); boolean compact = prefs.workspaceCompact(); AppWorkspaceLayout layout = AppDockPlacement.workspace(remove ? grid.projected() : before, pins, next, compact);
                if (remove && grid.editable()) layout = layout.move(id, grid.dropSlot(x, y), compact);
                List<String> committedPins = List.copyOf(next); AppWorkspaceLayout committedLayout = layout;
                // Publish after the releasing touch sequence has finished; preference callbacks
                // can then refresh ownership without re-entering endDrag while it is committing.
                post(() -> { if (!prefs.hubPins().equals(pins) || !prefs.workspace().equals(before) || prefs.workspaceCompact() != compact) { if (!disposed) toast("布局已改变，请重新拖动"); return; } prefs.saveDockPlacement(committedPins, committedLayout); dockPreferencesChanged(); });
            }
            clearDockDrop();
        }
        return true;
    }
    private void renderFavorites() {
        favoriteItems.removeAllViews();
        for (String id : prefs.actions("favorites")) {
            LinearLayout cell = (LinearLayout) shortcut(id, label(id), AppLauncherStyle.RAIL_ICON, 8, () -> listener.action(id));
            cell.setMinimumHeight(dp(AppLauncherStyle.RAIL_CELL)); cell.setPadding(dp(AppLauncherStyle.RAIL_PADDING), dp(AppLauncherStyle.RAIL_PADDING), dp(AppLauncherStyle.RAIL_PADDING), dp(AppLauncherStyle.RAIL_PADDING));
            TextView label = (TextView) cell.getChildAt(1); label.setTextSize(AppLauncherStyle.RAIL_LABEL_SP); label.setIncludeFontPadding(false); label.setPadding(0, dp(AppLauncherStyle.RAIL_LABEL_GAP), 0, 0); favoriteItems.addView(cell);
        }
        if (prefs.actions("favorites").isEmpty()) favoriteItems.addView(Ui.text(getContext(), "添加常用", 8, Ui.MUTED));
    }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    private String sortLabel() { return AppLauncherModel.sortLabel(prefs.hubSort()); }
    private void showSort() {
        grid.cancelInteraction(); PopupMenu menu = new PopupMenu(getContext(), sort); String[] values = {"manual", "name", "reverse", "recent"}, labels = {"手动布局 · 长按拖动", "名称升序", "名称降序", "系统最近任务优先"};
        for (int i = 0; i < values.length; i++) { String value = values[i]; menu.getMenu().add(labels[i]).setCheckable(true).setChecked(prefs.hubSort().equals(value)).setOnMenuItemClickListener(item -> { prefs.data.edit().putString("hub_sort", value).apply(); sort.setText(sortLabel()); filter(); return true; }); }
        menu.getMenu().add("自动补位（手动布局）").setEnabled(!prefs.workspaceLocked()).setCheckable(true).setChecked(prefs.workspaceCompact()).setOnMenuItemClickListener(item -> { grid.compact(!prefs.workspaceCompact()); return true; });
        menu.getMenu().add("新建文件夹 / 多选整理").setEnabled(!prefs.workspaceLocked()).setOnMenuItemClickListener(item -> { manualMode(); workspaceTools.organize(null); return true; });
        menu.getMenu().add(grid.editing() ? "完成整理" : "编辑布局").setEnabled(!prefs.workspaceLocked()).setOnMenuItemClickListener(item -> { manualMode(); grid.editing(!grid.editing()); return true; });
        menu.getMenu().add("撤销上次整理").setEnabled(grid.canUndo()).setOnMenuItemClickListener(item -> { grid.undo(); return true; });
        menu.getMenu().add("页面管理").setOnMenuItemClickListener(item -> { manualMode(); workspaceTools.pages(); return true; });
        menu.getMenu().add("锁定布局").setCheckable(true).setChecked(prefs.workspaceLocked()).setOnMenuItemClickListener(item -> { grid.cancelInteraction(); prefs.data.edit().putBoolean("hub_workspace_locked", !prefs.workspaceLocked()).apply(); return true; });
        menu.getMenu().add("显示应用名称").setCheckable(true).setChecked(prefs.workspaceLabels()).setOnMenuItemClickListener(item -> { prefs.data.edit().putBoolean("hub_workspace_labels", !prefs.workspaceLabels()).apply(); workspaceOptionsChanged(); return true; });
        menu.getMenu().add("活动通知角标（非系统未读数）").setCheckable(true).setChecked(prefs.workspaceBadges()).setOnMenuItemClickListener(item -> { prefs.data.edit().putBoolean("hub_workspace_badges", !prefs.workspaceBadges()).apply(); notificationsChanged(); if (prefs.workspaceBadges() && !CoverNotifications.ready()) toast("尚未连接通知访问，请在助手设置中授权后显示"); return true; });
        android.view.SubMenu density = menu.getMenu().addSubMenu("桌面密度"); String[] styles = {"compact", "normal", "easy"}, titles = {"紧凑", "普通", "易点"}; for (int i = 0; i < styles.length; i++) { String value = styles[i]; density.add(titles[i]).setCheckable(true).setChecked(value.equals(prefs.workspaceDensity())).setOnMenuItemClickListener(item -> { prefs.data.edit().putString("hub_workspace_density", value).apply(); workspaceOptionsChanged(); return true; }); }
        menu.show();
    }
    private void appMenu(View anchor, String id) {
        if (id.isEmpty()) { manualMode(); grid.editing(true); showSort(); return; }
        if (grid.layoutSnapshot().folder(id) != null) { workspaceTools.menu(anchor, id); return; }
        AppWorkspaceTools.Actions menu = workspaceTools.actions(label(id), null);
        String owner = grid.layoutSnapshot().parent(id);
        if (owner != null) menu.add("定位到：" + grid.layoutSnapshot().folder(owner).name(), () -> workspaceTools.locate(id));
        menu.add("设置别名 / 搜索别名", !prefs.workspaceLocked(), () -> workspaceTools.alias(id));
        menu.add("应用详情（系统页）", () -> listener.applicationSettings(id, false));
        menu.add("卸载应用（系统确认）", () -> listener.applicationSettings(id, true));
        if (grid.editable() && grid.layoutSnapshot().slot(id) >= 0) {
            menu.add("移动到…", () -> { AppWorkspaceTools.Actions moves = workspaceTools.actions("移动 " + label(id), () -> appMenu(anchor, id)); int position = grid.projected().slot(id);
                moves.add("前一格", position > 0, () -> grid.moveTo(id, position - 1));
                moves.add("后一格", position + 1 < AppWorkspaceLayout.MAX_SLOTS, () -> grid.moveTo(id, position + 1));
                moves.add("上一页同位置", position >= grid.capacity(), () -> grid.moveTo(id, position - grid.capacity()));
                moves.add("下一页同位置", position + grid.capacity() < AppWorkspaceLayout.MAX_SLOTS, () -> grid.moveTo(id, position + grid.capacity()));
            });
        }
        menu.add(prefs.hubPins().contains(id) ? "取消底部固定" : "固定到底部常用", !prefs.workspaceLocked(), () -> {
            List<String> ids = prefs.hubPins(); if (!ids.remove(id)) { if (ids.size() >= AppDockPlacement.LIMIT) { toast("底部常用最多" + AppDockPlacement.LIMIT + "个，请先移除一个"); return; } ids.add(id); } prefs.saveHubPins(ids); dockPreferencesChanged();
        });
        menu.add("加入侧栏", () -> { List<String> ids = prefs.actions("favorites"); if (!ids.contains(id) && ids.size() < 30) { ids.add(id); prefs.saveActions("favorites", ids); toast("已加入侧栏"); } });
    }
    private void toast(String text) { android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT).show(); }
    private ImageButton compactButton(int icon, String label, Runnable action) { ImageButton button = RuntimeVisuals.button(getContext(), icon, label, action); int pad = dp(AppLauncherStyle.HUB_BUTTON_PADDING); button.setPadding(pad, pad, pad, pad); return button; }
    View shortcut(String id, String label, int iconSize, int textSize, Runnable action) {
        LinearLayout cell = new RuntimeVisuals.Cell(getContext()); cell.setTag(new ShortcutBinding(id, "")); cell.setGravity(Gravity.CENTER); cell.setPadding(dp(AppLauncherStyle.APP_PADDING), dp(AppLauncherStyle.APP_PADDING), dp(AppLauncherStyle.APP_PADDING), dp(AppLauncherStyle.APP_PADDING));
        ImageView image = new ImageView(getContext()); cell.addView(image, new LayoutParams(dp(iconSize), dp(iconSize))); bindIcon(image, id);
        if (textSize > 0) { TextView title = Ui.text(getContext(), label, textSize, Ui.TEXT); title.setGravity(Gravity.CENTER); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setPadding(0, dp(AppLauncherStyle.APP_LABEL_GAP), 0, 0); cell.addView(title, new LayoutParams(-1, -2)); }
        cell.setMinimumHeight(dp(42)); cell.setBackground(Ui.ripple(getContext(), android.graphics.Color.TRANSPARENT, 12)); cell.setFocusable(true); cell.setContentDescription(label); cell.setOnClickListener(v -> action.run()); return cell;
    }
    void bindIcon(ImageView image, String id) {
        int generation = cache.contentGeneration();
        if (image.getTag() instanceof IconBinding bound && bound.id.equals(id) && bound.generation == generation && bound.loaded) return;
        if (!id.startsWith("app:") && !id.startsWith("tile:")) { image.setTag(new IconBinding(id, generation, true)); image.setImageDrawable(ActionCatalog.loadIcon(getContext(), id)); return; }
        Drawable cached = cache.cachedIcon(getContext(), id); image.setTag(new IconBinding(id, generation, cached != null)); image.setImageDrawable(cached == null ? Ui.icon(getContext(), R.drawable.ic_ms_apps, Ui.MUTED) : cached);
        if (cached == null && !disposed) cache.requestIcon(id);
    }
    void updateIcons(View view, Set<String> ids) {
        if (view instanceof LinearLayout cell && cell.getTag() instanceof ShortcutBinding binding && (ids == null || ids.contains(binding.id))) { String name = label(binding.id); cell.setContentDescription(binding.prefix + name); if (cell.getChildCount() > 1 && cell.getChildAt(1) instanceof TextView title && !name.contentEquals(title.getText())) title.setText(name); }
        if (view instanceof ImageView image && image.getTag() instanceof IconBinding bound) {
            if (ids == null) { bindIcon(image, bound.id); return; }
            if (bound.loaded) return; // A displayed bitmap stays valid after LRU eviction or memory trim.
            Drawable cached = cache.cachedIcon(getContext(), bound.id);
            if (cached != null) { image.setImageDrawable(cached); image.setTag(new IconBinding(bound.id, cache.contentGeneration(), true)); }
            else cache.requestIcon(bound.id); // Retry only placeholders deferred by queue pressure/trim.
        }
        else if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) updateIcons(group.getChildAt(i), ids);
    }
    private Set<String> pinnedPackages() { Set<String> result = new java.util.HashSet<>(); for (String id : prefs.hubPins()) { ComponentName name = ActionCatalog.component(id); if (name != null) result.add(name.getPackageName()); } return result; }
    private String taskAction(RecentTasks.Task task) { String launcher = cache.launcher(task.packageName()); return launcher == null ? "app:" + task.component() : launcher; }
    private void renderDock() {
        if (disposed) return; shownPins = List.copyOf(prefs.hubPins()); dockHost.data(tasks, recentKnown, recentBusy, canOpen, canClear, recentFailed);
    }
    private View dockApp(String id, int width, String prefix, Runnable action) {
        String name = label(id); View item = shortcut(id, name, AppLauncherStyle.dockIconSize(width / getResources().getDisplayMetrics().density), 0, action); item.setTag(new ShortcutBinding(id, prefix)); item.setMinimumHeight(0); item.setContentDescription(prefix + name);
        item.setOnLongClickListener(v -> { if (expanded && grid.draggable() && prefs.hubPins().contains(id) && cache.ready() && search.length() == 0) { dockHeld = id; dockMoving = false; dockDownX = pointerX; dockDownY = pointerY; getParent().requestDisallowInterceptTouchEvent(true); } else appMenu(v, id); return true; }); return item;
    }
    void recentBusy(boolean busy) { recentBusy = busy; renderDock(); syncTaskPage(); }
    boolean recentBusy() { return recentBusy; }
    void recentCapabilities(boolean open, boolean clear, boolean snapshot) { canOpen = open; canClear = clear; canSnapshot = snapshot; }
    void recentResult(List<RecentTasks.Task> result, String message) {
        CoverApp.launcherWidgets(getContext()).recent(result, canOpen, canClear);
        recentBusy = false; recentFailed = false; refreshDelay = 3000; boolean changed = !tasks.equals(result) || !shownPins.equals(prefs.hubPins()); recentKnown = true; tasks = List.copyOf(result); String status = message == null ? AppLauncherModel.recentStatus(prefs.hubPins().size(), RecentTasks.apps(tasks, pinnedPackages()).size()) : message; if (!status.contentEquals(recentStatus.getText())) recentStatus.setText(status);
        taskMessage = message != null ? message : !canOpen ? "系统不支持返回原任务" : !canClear ? "可切换，系统未开放关闭权限" : canSnapshot ? "左右切换 · 点击返回原窗口" : "图标模式 · 点击返回原窗口";
        recentStatus.setContentDescription(recentStatus.getText());
        if (changed) { if (prefs.hubSort().equals("recent")) filter(); renderDock(); } else recentBusy(false);
        syncTaskPage();
        if (pendingSelection != null && taskPage != null) { taskPage.select(pendingSelection); pendingSelection = null; }
    }
    void recentFailure(String reason) { CoverApp.launcherWidgets(getContext()).recentFailure(reason); recentBusy = false; recentKnown = false; recentFailed = true; refreshDelay = 15000; taskMessage = reason; String status = tasks.isEmpty() ? "最近任务不可用 · 点击重试" : "同步失败 · 上次结果不可操作"; if (!status.contentEquals(recentStatus.getText())) recentStatus.setText(status); recentStatus.setContentDescription(reason); recentBusy(false); }
    private void syncTaskPage() { if (taskPage != null) taskPage.data(tasks, recentKnown, recentBusy, canOpen, canClear, canSnapshot, taskMessage); }
    void dockEdge(int edge) { dockEdge = edge; }
    boolean showingTasks() { return taskPage != null; }
    RecentTasks.Task selectedTask() { return taskPage == null ? null : taskPage.selectedTask(); }
    void selectTask(RecentTasks.Task task) { pendingSelection = task; if (taskPage != null && recentKnown) { taskPage.select(task); pendingSelection = null; } }
    void taskOpenFailure(RecentTasks.Task task, boolean gone, String message) {
        recentBusy = false; showTasks(true); syncTaskPage(); taskPage.openFailure(task, gone, message);
    }
    void showTasks(boolean show) {
        if (show == (taskPage != null)) return;
        grid.cancelInteraction(); workspaceTools.close();
        if (show) {
            setExpanded(true); cancelContentAnimation(); contentProgress(1); search.clearFocus(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0);
            taskPage = new RecentTasksView(getContext(), prefs, dockEdge, new RecentTasksView.Listener() {
                public void open(RecentTasks.Task task) { listener.openTask(task); }
                public void clear(List<RecentTasks.Task> tasks) { listener.clearRecents(tasks); }
                public void closeTask(RecentTasks.Task task) { listener.closeTask(task); }
                public void refresh() { listener.refreshRecents(); }
                public void apps() { showTasks(false); }
                public void close() { listener.close(); }
                public void reopen(RecentTasks.Task task) { listener.action(taskAction(task)); }
                public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { listener.snapshot(task, callback); }
            });
            appContent.setVisibility(GONE); dockHost.setVisibility(GONE); addView(taskPage, 0, new LayoutParams(-1, 0, 1)); syncTaskPage();
        } else { taskPage.dispose(); removeView(taskPage); taskPage = null; appContent.setVisibility(VISIBLE); dockHost.setVisibility(VISIBLE); recentBusy(recentBusy); }
        listener.taskPageChanged(taskPage);
        if (taskPage != null && !listener.managesTaskEntrance()) revealTasks(taskPage);
    }
    void toggle() {
        setExpanded(!expanded);
    }
    void setExpanded(boolean value) {
        if (!value) { dockHeld = null; dockMoving = false; grid.cancelInteraction(); workspaceTools.close(); }
        if (!value && taskPage != null) showTasks(false);
        if (expanded == value) return; expanded = value; cancelContentAnimation(); dockHost.expanded(expanded); if (expanded) notificationsChanged();
        if (!expanded) {
            long now = android.os.SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0); super.dispatchTouchEvent(cancel); cancel.recycle(); appContent.cancelPendingInputEvents();
            search.clearFocus(); requestFocus(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0);
        }
        appContent.setImportantForAccessibility(expanded ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        float target = expanded ? 1 : 0;
        if (!isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { contentProgress(target); finishContent(); }
        else {
            appContent.setVisibility(VISIBLE); dismissBackdrop.setVisibility(GONE);
            contentAnimation = ValueAnimator.ofFloat(contentProgress, target); contentAnimation.setDuration(Math.max(1, Math.round(200 * Math.abs(target - contentProgress)))); contentAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());
            contentAnimation.addUpdateListener(animation -> contentProgress((float) animation.getAnimatedValue()));
            contentAnimation.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { if (contentAnimation != animation) return; contentAnimation = null; contentProgress(target); finishContent(); } }); contentAnimation.start();
        }
        listener.expand(expanded);
    }
    private void contentProgress(float value) { contentProgress = value; appContent.setAlpha(value); appContent.setTranslationY(dp(12) * (1 - value)); }
    private void finishContent() { appContent.setVisibility(expanded && taskPage == null ? VISIBLE : GONE); dismissBackdrop.setVisibility(!expanded && taskPage == null ? VISIBLE : GONE); }
    private void cancelContentAnimation() { if (contentAnimation != null) { contentAnimation.removeAllListeners(); contentAnimation.cancel(); contentAnimation = null; } }
    void back() { if (dockHeld != null || grid.dragging()) { dockHeld = null; dockMoving = false; grid.cancelInteraction(); } else if (workspaceTools.back()) { } else if (grid.editing()) grid.editing(false); else if (expanded) setExpanded(false); else listener.close(); }
    record WorkspaceState(String query, AppWorkspaceView.State position, String folder) { WorkspaceState(String query, AppWorkspaceView.State position) { this(query, position, null); } }
    WorkspaceState workspaceState() { return new WorkspaceState(search.getText().toString(), grid.state(), workspaceTools.opened()); }
    void restoreWorkspaceState(WorkspaceState state) { if (state != null) { search.setText(state.query()); grid.restore(state.position()); if (state.folder() != null) post(() -> { if (!disposed && isAttachedToWindow() && expanded) workspaceTools.folder(state.folder()); }); } }
    void workspacePreferencesChanged() { grid.preferencesChanged(); }
    boolean expanded() { return expanded; }
    void widgetEntry(String operation) {
        if (operation.equals("sort")) showSort();
        else if (operation.equals("search")) { search.requestFocus(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); }
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        if (expanded && taskPage == null && MeasureSpec.getMode(widthSpec) == MeasureSpec.EXACTLY && MeasureSpec.getMode(heightSpec) == MeasureSpec.EXACTLY) {
            AppLauncherStyle.HubGeometry geometry = AppLauncherStyle.hubGeometry(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec), getResources().getDisplayMetrics().density, rightRail, false);
            LayoutParams body = (LayoutParams) appContent.getLayoutParams(); body.height = geometry.bodyHeight(); body.weight = 0;
            LayoutParams workspace = (LayoutParams) grid.getLayoutParams(); workspace.height = geometry.gridHeight() + geometry.pagerHeight(); workspace.weight = 0;
        }
        super.onMeasure(widthSpec, heightSpec);
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (disposed) return; cache.observe(catalogListener); catalogListener.catalogChanged(); notificationsChanged(); removeCallbacks(refresh); post(refresh); }
    @Override protected void onDetachedFromWindow() { listener.taskPageChanged(null); dockHeld = null; dockMoving = false; grid.cancelInteraction(); workspaceTools.close(); removeCallbacks(refreshPins); removeCallbacks(enter); cancelReveal(); cancelContentAnimation(); contentProgress(expanded ? 1 : 0); finishContent(); cache.unobserve(catalogListener); removeCallbacks(refresh); super.onDetachedFromWindow(); }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) { if (closing) return true; if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK) { if (event.getAction() == android.view.KeyEvent.ACTION_UP) back(); return true; } return super.dispatchKeyEvent(event); }
    private void filter() {
        aliases = prefs.workspaceAliases(); String query = search.getText().toString().trim().toLowerCase(Locale.ROOT), order = prefs.hubSort();
        List<AppCatalogCache.Entry> next = AppLauncherModel.select(cache.snapshot(), prefs.hubPins(), aliases, query, order, tasks, searchIndex);
        String status = catalogStatus(next.size()); if (!status.contentEquals(information.getText())) information.setText(status);
        grid.setContentDescription(status);
        grid.mode(query.isEmpty() && order.equals("manual"));
        if (!filtered.equals(next)) { filtered.clear(); filtered.addAll(next); adapter.notifyDataSetChanged(); }
        else grid.refresh();
    }
    private final class AppAdapter extends AppWorkspaceView.CellAdapter {
        private int iconSize(int width) { return AppLauncherStyle.iconSize(prefs.workspaceDensity(), width / getResources().getDisplayMetrics().density); }
        private TextView measuredLabel;
        @Override int minimumHeight(int width) {
            if (measuredLabel == null) { measuredLabel = Ui.text(getContext(), "应用 Ag", AppLauncherStyle.LABEL_SP, Ui.TEXT); measuredLabel.setSingleLine(); measuredLabel.setPadding(0, dp(AppLauncherStyle.APP_LABEL_GAP), 0, 0); }
            // TextView includes font padding and Android's nonlinear sp scaling; Paint spacing does not.
            measuredLabel.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            return dp(iconSize(width)) + 2 * dp(AppLauncherStyle.APP_PADDING) + (prefs.workspaceLabels() ? measuredLabel.getMeasuredHeight() : 0);
        }
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int position) { return filtered.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reusable, ViewGroup parent) {
            return bind(filtered.get(position), reusable, parent);
        }
        @Override View bind(AppCatalogCache.Entry entry, View reusable, ViewGroup parent) {
            LinearLayout cell;
            if (reusable instanceof LinearLayout) cell = (LinearLayout) reusable;
            else { cell = new RuntimeVisuals.Cell(getContext()); RuntimeVisuals.surface(cell, android.graphics.Color.TRANSPARENT, 12); cell.setGravity(Gravity.CENTER); cell.setPadding(dp(AppLauncherStyle.APP_PADDING), dp(AppLauncherStyle.APP_PADDING), dp(AppLauncherStyle.APP_PADDING), dp(AppLauncherStyle.APP_PADDING)); cell.addView(new ImageView(getContext()), new LayoutParams(dp(30), dp(30))); TextView title = Ui.text(getContext(), "", AppLauncherStyle.LABEL_SP, Ui.TEXT); title.setGravity(Gravity.CENTER); title.setSingleLine(); title.setEllipsize(android.text.TextUtils.TruncateAt.END); title.setPadding(0, dp(AppLauncherStyle.APP_LABEL_GAP), 0, 0); cell.addView(title, new LayoutParams(-1, -2)); }
            int count = badge(entry.id()); String name = label(entry.id()); String parentId = grid.layoutSnapshot().parent(entry.id());
            TextView title = (TextView) cell.getChildAt(1); title.setText(name + (count > 0 ? " · " + count : "")); title.setVisibility(prefs.workspaceLabels() ? VISIBLE : GONE); int size = dp(iconSize(parent.getMeasuredWidth() / AppLauncherStyle.GRID_COLUMNS)); cell.getChildAt(0).setLayoutParams(new LayoutParams(size, size));
            cell.setContentDescription(name + (parentId == null ? "" : "，位于：" + grid.layoutSnapshot().folder(parentId).name()) + (count > 0 ? "，" + count + "条活动通知" : "")); cell.setTooltipText(parentId == null ? name : "位于：" + grid.layoutSnapshot().folder(parentId).name()); cell.setForeground(count > 0 ? new AppNotificationBadge(getContext(), count) : null); bindIcon((ImageView) cell.getChildAt(0), entry.id()); return cell;
        }
        @Override View folder(AppWorkspaceLayout.Folder folder, View reusable, ViewGroup parent) { AppFolderTile tile = reusable instanceof AppFolderTile ? (AppFolderTile) reusable : new AppFolderTile(getContext()); int count = 0; Set<String> packages = new java.util.HashSet<>(); for (String id : folder.members()) { ComponentName component = ActionCatalog.component(id); if (component != null && packages.add(component.getPackageName())) count += badge(id); } tile.bind(folder, AppHubView.this::bindIcon, count, grid.capacity() / grid.getNumColumns() < 2); return tile; }
    }
    void dispose() { disposed = true; dockHeld = null; dockMoving = false; grid.dispose(); workspaceTools.close(); searchIndex.clear(); removeCallbacks(refreshPins); removeCallbacks(enter); cancelReveal(); cancelContentAnimation(); appContent.animate().cancel(); cache.unobserve(catalogListener); removeCallbacks(refresh); if (taskPage != null) taskPage.dispose(); tasks = List.of(); }
}
