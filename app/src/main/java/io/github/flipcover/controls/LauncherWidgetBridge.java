package io.github.flipcover.controls;

import android.appwidget.AppWidgetManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Native-card lifecycle; the shared workspace page belongs to Prefs, folders remain ephemeral. */
final class LauncherWidgetBridge implements DisplayManager.DisplayListener {
    static final class State {
        int page, pages = 1, workspacePage, pendingPage = -1; String folder;
        long launchGeneration;
        void openFolder(String id) { if (folder == null) workspacePage = page; folder = id; page = 0; }
        boolean closeFolder() { if (folder == null) return false; folder = null; page = workspacePage; return true; }
    }
    record Recents(List<RecentTasks.Task> tasks, boolean known, boolean busy, boolean canOpen, boolean canClear, String error) { }
    private final Context context;
    private final Prefs prefs;
    private final AppCatalogCache catalog;
    private final AppWidgetManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, State> states = new HashMap<>();
    private final Set<Integer> visibleCards = new HashSet<>();
    private long inputEpoch;
    private long launchGeneration;
    long inputEpoch() { return inputEpoch; }
    String inputFolder(int id) { State state = states.get(id); return state == null || state.folder == null ? "desktop" : state.folder; }
    private android.widget.RemoteViews inputViews;
    private int inputViewsId = -1;
    int inputCard() { return visibleCards.size() == 1 ? visibleCards.iterator().next() : -1; }
    android.widget.RemoteViews inputViews() { return inputViewsId == inputCard() ? inputViews : null; }
    private final Set<String> shownIcons = new HashSet<>();
    private final Set<String> requestedIcons = new HashSet<>();
    // Only current card pages; at most the 1000-app catalog plus four stale pins, sharing catalog-resolution bitmaps.
    private final Map<String, android.graphics.Bitmap> visibleIcons = new HashMap<>();
    private List<RecentTasks.Task> tasks = List.of();
    private boolean recentKnown, recentBusy, canOpen, canClear;
    private String recentError = "";
    private int recentGeneration, pendingWidget = -1;
    private boolean observing, listening;
    private int displayId = -1;
    private android.widget.Toast editToast;
    private final Runnable update = this::refresh;
    private final AppCatalogCache.Listener apps = new AppCatalogCache.Listener() {
        public void catalogChanged() { requestedIcons.clear(); visibleIcons.clear(); schedule(); }
        public void iconsChanged(Set<String> ids) { if (!java.util.Collections.disjoint(ids, shownIcons) || !requestedIcons.containsAll(shownIcons)) schedule(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (data, key) -> {
        if (key == null || key.startsWith("hub_") || key.equals("display") || key.equals("favorites") || key.equals("hand_side")) { requestedIcons.clear(); schedule(); }
    };
    private final Runnable connection = this::connectionChanged;
    private void connectionChanged() { if (!CoverApp.bridge(context).connected()) recentFailure("Shizuku 连接中断"); }
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) { if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { stop(); reset(); } else schedule(); }
    };
    LauncherWidgetBridge(Context context) { this.context = context.getApplicationContext(); prefs = new Prefs(context); catalog = CoverApp.catalog(context); manager = AppWidgetManager.getInstance(context); }
    private State state(int id) { return states.computeIfAbsent(id, ignored -> { State value = new State(); value.page = value.pendingPage = prefs.launcherPage(); return value; }); }
    int[] cards() { return manager.getAppWidgetIds(new ComponentName(context, LauncherWidgetProvider.class)); }
    boolean owns(int id) { for (int card : cards()) if (card == id) return true; return false; }
    void schedule() { if (observing && !main.hasCallbacks(update)) main.postDelayed(update, 60); }
    void recent(List<RecentTasks.Task> value, boolean open, boolean clear) { tasks = List.copyOf(value); recentKnown = true; canOpen = open; canClear = clear; recentError = ""; schedule(); }
    void recentFailure(String reason) { recentKnown = false; recentError = reason == null ? "最近任务不可用" : reason; schedule(); }
    void trim() { recentGeneration++; pendingWidget = -1; tasks = List.of(); recentKnown = false; recentBusy = false; recentError = ""; shownIcons.clear(); requestedIcons.clear(); visibleIcons.clear(); inputViews = null; inputViewsId = -1; if (CoverService.instance != null) CoverService.instance.nativeInputChanged(); }
    private void reset() { cancelToast(); states.clear(); trim(); displayId = -1; visibleCards.clear(); notifyVisibility(); }
    boolean visible(int target) { return target != Display.DEFAULT_DISPLAY && target == displayId && !visibleCards.isEmpty(); }
    private void notifyVisibility() { inputEpoch++; if (inputViewsId != inputCard()) { inputViews = null; inputViewsId = -1; } if (CoverService.instance != null) CoverService.instance.launcherVisibilityChanged(); }
    void optionsChanged(int id, android.os.Bundle options) {
        if (!owns(id)) return;
        Display selected = Displays.selected(context, prefs);
        if (selected != null && selected.getState() == Display.STATE_ON && selected.getDisplayId() != displayId) refresh();
        // Samsung opts this provider into per-instance options callbacks. Never infer
        // visibility from the host package, a card click, or cached options on restart.
        if (options.containsKey("visible")) {
            boolean visible = options.getBoolean("visible", false) && selected != null && selected.getDisplayId() == displayId && selected.getState() == Display.STATE_ON;
            State state = states.get(id); if (!visible && state != null) state.launchGeneration++;
            if (visible ? visibleCards.add(id) : visibleCards.remove(id)) {
                notifyVisibility();
                if (visible && CoverService.instance != null) CoverService.instance.launcherCardVisible(selected.getDisplayId());
            }
        }
        resize(id);
    }
    private void cancelToast() { if (editToast != null) { editToast.cancel(); editToast = null; } }
    private void observe(boolean enabled) {
        if (observing == enabled) return; observing = enabled;
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        if (enabled) {
            displays.registerDisplayListener(this, main); prefs.data.registerOnSharedPreferenceChangeListener(preferences);
            CoverApp.bridge(context).addObserver(connection);
            IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED); else context.registerReceiver(screen, filter);
        } else { displays.unregisterDisplayListener(this); prefs.data.unregisterOnSharedPreferenceChangeListener(preferences); context.unregisterReceiver(screen); CoverApp.bridge(context).removeObserver(connection); }
    }
    private void stop() { launchGeneration++; cancelToast(); main.removeCallbacks(update); if (listening) { listening = false; catalog.unobserve(apps); } shownIcons.clear(); requestedIcons.clear(); visibleIcons.clear(); }
    void refresh() {
        main.removeCallbacks(update); int[] cards = cards(); observe(cards.length > 0);
        Set<Integer> retained = new HashSet<>(); for (int id : cards) retained.add(id); states.keySet().retainAll(retained);
        if (visibleCards.retainAll(retained)) notifyVisibility();
        if (cards.length == 0) { stop(); reset(); return; }
        Display selected = Displays.selected(context, prefs);
        if (selected == null) {
            stop(); reset(); for (int id : cards) manager.updateAppWidget(id, LauncherWidgetViews.message(context, "请先选择目标外屏")); return;
        }
        if (displayId != selected.getDisplayId()) { reset(); displayId = selected.getDisplayId(); }
        if (selected.getState() != Display.STATE_ON) { stop(); reset(); return; }
        if (!listening) { listening = true; catalog.observe(apps); }
        if (CoverService.instance != null && CoverService.instance.deferNativeCardUpdate(selected)) return;
        shownIcons.clear();
        for (int id : cards) {
            State state = state(id);
            if (state.folder == null && state.pendingPage >= 0 && catalog.ready() && !catalog.failed()) { state.page = state.pendingPage; state.pendingPage = -1; }
            try { android.widget.RemoteViews rendered = new LauncherWidgetViews(context, prefs, selected, id, state, new Recents(tasks, recentKnown, recentBusy, canOpen, canClear, recentError), shownIcons, requestedIcons, visibleIcons).render(); manager.updateAppWidget(id, rendered); if (id == inputCard()) { inputViews = rendered; inputViewsId = id; if (CoverService.instance != null) CoverService.instance.nativeInputChanged(); } }
            catch (RuntimeException error) { manager.updateAppWidget(id, LauncherWidgetViews.message(context, "卡片暂不可用，请重新打开或调整尺寸")); android.util.Log.w("LauncherWidget", "Unable to render card", error); }
        }
        visibleIcons.keySet().retainAll(shownIcons);
        requestedIcons.retainAll(shownIcons);
    }
    void resize(int id) { cancelToast(); requestedIcons.clear(); schedule(); }
    void remove(int id) { cancelToast(); states.remove(id); if (visibleCards.remove(id)) notifyVisibility(); if (pendingWidget == id) { recentGeneration++; pendingWidget = -1; recentBusy = false; } schedule(); }
    boolean inputBack(int id, int target) { State current = states.get(id); if (current == null || current.folder == null) return false; action(id, target, "back", null); return true; }
    void action(int id, int target, String operation, String item) {
        if (!owns(id) || operation == null) return;
        if (operation.equals("launch") || operation.equals("side") && item != null && item.startsWith("app:")) {
            if (operation.equals("side") && !prefs.actions("favorites").contains(item)) return;
            launch(id, item); return;
        }
        Display selected = Displays.selected(context, prefs);
        if (!owns(id) || selected == null || selected.getDisplayId() != target || selected.getState() != Display.STATE_ON || operation == null) return;
        State state = state(id);
        switch (operation) {
            case "surface" -> {
                if (item == null || !Set.of("search", "sort", "tasks").contains(item)) return;
                // These are our accessibility overlays, not application launches. Avoid a
                // transient Activity taking focus and changing Samsung's bars/insets.
                if (!AppLauncher.ready(context, prefs, target)) { notice(selected, "请先解锁外屏后使用此功能"); return; }
                CoverService service = CoverService.instance;
                if (service == null || !service.launcherSurface(item, target)) notice(selected, "请开启浮窗服务后使用此功能");
                return;
            }
            case "edit" -> { notice(selected, AppLauncherModel.EDIT_HINT); return; }
            case "apps" -> { return; } // Ignore stale Dock toggle PendingIntents from older card views.
            case "side" -> {
                if (!prefs.actions("favorites").contains(item) || !ActionCatalog.valid(item)) return;
                if (item.equals("app_hub")) state.closeFolder();
                else { CoverService service = CoverService.instance; if (service == null || !service.launcherAction(item, target)) notice(selected, "请解锁外屏并开启浮窗服务后使用此快捷操作"); return; }
            }
            case "refresh" -> { requestTasks(id, target, null); return; }
            case "clear" -> {
                if (!recentKnown || !canClear || item == null || item.length() > 16384) return;
                try { requestTasks(id, target, AppRecentTasks.tasks(new org.json.JSONArray(item), target)); }
                catch (Exception error) { notice(selected, "任务数据已改变，请刷新最近应用"); }
                return;
            }
            case "next" -> state.page = Math.min(state.pages - 1, state.page + 1);
            case "previous" -> state.page = Math.max(0, state.page - 1);
            case "folder" -> { if (prefs.workspace().folder(item) == null) return; state.openFolder(item); }
            case "back" -> state.closeFolder();
            default -> { return; }
        }
        if (state.folder == null && state.pendingPage < 0 && (operation.equals("next") || operation.equals("previous"))) prefs.launcherPage(state.page);
        requestedIcons.clear();
        schedule();
    }
    private void launch(int widget, String item) {
        AppLauncher launcher = CoverApp.launcher(context); long request = launcher.diagnostics.begin("native_card_direct");
        State owner = state(widget); long generation = launchGeneration, cardGeneration = owner.launchGeneration;
        java.util.function.BooleanSupplier active = () -> generation == launchGeneration && cardGeneration == owner.launchGeneration && states.get(widget) == owner && owns(widget);
        CoverService service = CoverService.instance;
        if (service != null) {
            launcher.diagnostics.event(request, "dispatch via=floating_service transport=broadcast");
            service.launchApp(item, request, active, accepted -> { });
        } else {
            // A stale broadcast may arrive while the host is receiving the no-service views.
            // Preserve the actual click; Android still decides whether this start is allowed.
            launcher.diagnostics.event(request, "dispatch via=standalone transport=broadcast");
            Display selected = Displays.selected(context, prefs); int target = selected == null ? -1 : selected.getDisplayId();
            launcher.launch(context, prefs, item, target, request, active, message -> { if (selected != null && active.getAsBoolean()) notice(selected, message); }, accepted -> { });
        }
    }
    private void notice(Display display, String message) { cancelToast(); editToast = android.widget.Toast.makeText(context.createDisplayContext(display), message, android.widget.Toast.LENGTH_SHORT); editToast.show(); }
    private void requestTasks(int widget, int target, List<RecentTasks.Task> clearing) {
        if (recentBusy) return; State owner = states.get(widget); int generation = ++recentGeneration; pendingWidget = widget; recentBusy = true; schedule();
        AppRecentTasks.request(context, prefs, target, clearing, () -> states.get(widget) == owner && owns(widget) && displayId == target, result -> {
            if (generation != recentGeneration) return;
            recentBusy = false; pendingWidget = -1;
            if (states.get(widget) != owner || displayId != target) { schedule(); return; }
            if (result.unchanged()) schedule();
            else if (result.ok()) { AppRecentTasks.Snapshot snapshot = result.snapshot(); recent(snapshot.tasks(), snapshot.canOpen(), snapshot.canClear()); }
            else recentFailure(result.message());
            Display selected = Displays.selected(context, prefs); if (selected != null && selected.getDisplayId() == target && selected.getState() == Display.STATE_ON) notice(selected, result.message() == null ? "最近应用已刷新" : result.message());
        });
    }
    @Override public void onDisplayAdded(int id) { schedule(); }
    @Override public void onDisplayRemoved(int id) { if (id == displayId) { stop(); reset(); } schedule(); }
    @Override public void onDisplayChanged(int id) { if (id == displayId || displayId < 0) schedule(); }
}
