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

/** Native-card lifecycle and ephemeral navigation only. All durable content belongs to Prefs. */
final class LauncherWidgetBridge implements DisplayManager.DisplayListener {
    static final class State { int page, pages = 1; String folder; boolean hint; }
    private final Context context;
    private final Prefs prefs;
    private final AppCatalogCache catalog;
    private final AppWidgetManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, State> states = new HashMap<>();
    private final Set<String> shownIcons = new HashSet<>();
    private List<RecentTasks.Task> tasks = List.of();
    private boolean observing, listening;
    private int displayId = -1;
    private final Runnable update = this::refresh;
    private final AppCatalogCache.Listener apps = new AppCatalogCache.Listener() {
        public void catalogChanged() { schedule(); }
        public void iconsChanged(Set<String> ids) { if (!java.util.Collections.disjoint(ids, shownIcons)) schedule(); }
    };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferences = (data, key) -> {
        if (key == null || key.startsWith("hub_") || key.equals("display")) schedule();
    };
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        public void onReceive(Context c, Intent intent) { if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) { stop(); reset(); } else schedule(); }
    };
    LauncherWidgetBridge(Context context) { this.context = context.getApplicationContext(); prefs = new Prefs(context); catalog = CoverApp.catalog(context); manager = AppWidgetManager.getInstance(context); }
    int[] cards() { return manager.getAppWidgetIds(new ComponentName(context, LauncherWidgetProvider.class)); }
    boolean owns(int id) { for (int card : cards()) if (card == id) return true; return false; }
    void schedule() { if (!main.hasCallbacks(update)) main.postDelayed(update, 60); }
    void recent(List<RecentTasks.Task> value) { if (!tasks.equals(value)) { tasks = List.copyOf(value); if (prefs.hubSort().equals("recent")) schedule(); } }
    void trim() { tasks = List.of(); shownIcons.clear(); }
    private void reset() { states.clear(); trim(); displayId = -1; }
    private void observe(boolean enabled) {
        if (observing == enabled) return; observing = enabled;
        DisplayManager displays = context.getSystemService(DisplayManager.class);
        if (enabled) {
            displays.registerDisplayListener(this, main); prefs.data.registerOnSharedPreferenceChangeListener(preferences);
            IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED); else context.registerReceiver(screen, filter);
        } else { displays.unregisterDisplayListener(this); prefs.data.unregisterOnSharedPreferenceChangeListener(preferences); context.unregisterReceiver(screen); }
    }
    private void stop() { main.removeCallbacks(update); if (listening) { listening = false; catalog.unobserve(apps); } shownIcons.clear(); }
    void refresh() {
        main.removeCallbacks(update); int[] cards = cards(); observe(cards.length > 0);
        Set<Integer> retained = new HashSet<>(); for (int id : cards) retained.add(id); states.keySet().retainAll(retained);
        if (cards.length == 0) { stop(); reset(); return; }
        Display selected = Displays.selected(context, prefs);
        if (selected == null) {
            stop(); reset(); for (int id : cards) manager.updateAppWidget(id, LauncherWidgetViews.message(context, "请先选择目标外屏")); return;
        }
        if (displayId != selected.getDisplayId()) { reset(); displayId = selected.getDisplayId(); }
        if (selected.getState() != Display.STATE_ON) { stop(); reset(); return; }
        if (!listening) { listening = true; catalog.observe(apps); }
        shownIcons.clear();
        for (int id : cards) {
            State state = states.computeIfAbsent(id, ignored -> new State());
            try { manager.updateAppWidget(id, new LauncherWidgetViews(context, prefs, selected, id, state, tasks, shownIcons).render()); }
            catch (RuntimeException error) { manager.updateAppWidget(id, LauncherWidgetViews.message(context, "卡片暂不可用，请重新打开或调整尺寸")); android.util.Log.w("LauncherWidget", "Unable to render card", error); }
        }
    }
    void resize(int id) { State state = states.get(id); if (state != null) state.hint = false; schedule(); }
    void remove(int id) { states.remove(id); schedule(); }
    void action(int id, int target, String operation, String item) {
        Display selected = Displays.selected(context, prefs);
        if (!owns(id) || selected == null || selected.getDisplayId() != target || selected.getState() != Display.STATE_ON || operation == null) return;
        State state = states.computeIfAbsent(id, ignored -> new State());
        if (state.hint && !operation.equals("dismiss")) return;
        switch (operation) {
            case "edit" -> state.hint = true;
            case "dismiss" -> state.hint = false;
            case "next" -> state.page = Math.min(state.pages - 1, state.page + 1);
            case "previous" -> state.page = Math.max(0, state.page - 1);
            case "folder" -> { if (prefs.workspace().folder(item) == null) return; state.folder = item; state.page = 0; }
            case "back" -> { state.folder = null; state.page = 0; }
            default -> { return; }
        }
        schedule();
    }
    @Override public void onDisplayAdded(int id) { schedule(); }
    @Override public void onDisplayRemoved(int id) { if (id == displayId) { stop(); reset(); } schedule(); }
    @Override public void onDisplayChanged(int id) { if (id == displayId || displayId < 0) schedule(); }
}
