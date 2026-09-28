package io.github.flipcover.controls;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.SizeF;
import android.util.SparseArray;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;

/** Owns widget identities and atomic card layouts; Samsung owns the displayed outer surfaces. */
final class NativeWidgetBridge implements DisplayManager.DisplayListener {
    private static final int HOST_ID = 0x464343;
    private static final long DRAFT_LIFETIME = 24 * 60 * 60 * 1000L;
    private final Context context;
    private final SharedPreferences data;
    private final Prefs prefs;
    private final AppWidgetManager manager;
    private final RelayHost host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SparseArray<RelayView> views = new SparseArray<>();
    private final Set<IntConsumer> observers = new HashSet<>();
    private final Set<Integer> dirty = new HashSet<>();
    private boolean observing, listening, restoreBeforePublish;
    private int displayId = -1;
    private record ChromeArea(int displayId, int rotation, int width, int height, DockGeometry.Box safe) { }
    private ChromeArea chromeArea;
    private final Runnable reconcile = this::refresh, expireDraft = this::refresh;
    private final Runnable publishDirty = () -> { Set<Integer> pending = Set.copyOf(dirty); dirty.clear(); for (int outer : pending) publish(outer); };
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (p, key) -> { if ("display".equals(key)) schedule(); };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent intent) { if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) stop(); else schedule(); }
    };
    NativeWidgetBridge(Context context) {
        this.context = context.getApplicationContext(); prefs = new Prefs(context); data = context.getSharedPreferences("native_widgets", Context.MODE_PRIVATE);
        manager = AppWidgetManager.getInstance(context); host = new RelayHost(context);
    }
    private final class RelayHost extends AppWidgetHost {
        RelayHost(Context context) { super(context, HOST_ID); }
        @Override protected AppWidgetHostView onCreateView(Context c, int id, AppWidgetProviderInfo info) { return new RelayView(c, id); }
        @Override protected void onProvidersChanged() { stop(); schedule(); }
        void releaseViews() { clearViews(); }
    }
    AppWidgetHost host() { return host; }
    int[] cards() {
        List<Integer> ids = new ArrayList<>(); for (Class<?> type : NativeWidgetProvider.TYPES) for (int id : manager.getAppWidgetIds(new ComponentName(context, type))) ids.add(id);
        return ids.stream().mapToInt(Integer::intValue).toArray();
    }
    int slot(int outer) { AppWidgetProviderInfo info = info(outer); if (info != null) for (int i = 0; i < NativeWidgetProvider.TYPES.length; i++) if (info.provider.getClassName().equals(NativeWidgetProvider.TYPES[i].getName())) return i + 1; return 0; }
    JSONArray exportTemplates() throws org.json.JSONException {
        List<WidgetTemplates.Card> templates = new ArrayList<>(WidgetTemplates.saved(prefs));
        for (int outer : cards()) {
            List<WidgetTemplates.Entry> entries = new ArrayList<>();
            for (WidgetGrid.Item item : items(outer)) {
                AppWidgetProviderInfo info = info(item.id());
                if (info == null) throw new IllegalStateException("组合卡片" + slot(outer) + "含不可用组件，请先移除或更换后导出");
                entries.add(new WidgetTemplates.Entry(info.provider.flattenToString(), item.x(), item.y(), item.width(), item.height()));
            }
            WidgetTemplates.Card card = new WidgetTemplates.Card(slot(outer), entries); if (!templates.contains(card)) templates.add(card);
        }
        JSONArray result = WidgetTemplates.json(templates); WidgetTemplates.read(result); return result;
    }
    boolean owns(int outer) { for (int id : cards()) if (id == outer) return true; return false; }
    int inner(int outer) { List<WidgetGrid.Item> items = items(outer); return items.isEmpty() ? -1 : items.get(0).id(); }
    int pending(int outer) { return data.getInt("pending_" + outer, -1); }
    AppWidgetProviderInfo info(int id) { return id > 0 ? manager.getAppWidgetInfo(id) : null; }
    String label(int outer) { List<WidgetGrid.Item> items = items(outer); return items.isEmpty() ? "空白布局" : items.size() + "个组件 · 已用" + WidgetGrid.used(items) + "/16格"; }
    String widgetLabel(int id) { AppWidgetProviderInfo info = info(id); return info == null ? "组件不可用" : info.loadLabel(context.getPackageManager()); }
    List<WidgetGrid.Item> items(int outer) {
        String value = data.getString("layout_" + outer, null);
        if (value == null) { int legacy = data.getInt("inner_" + outer, -1); return legacy > 0 ? List.of(new WidgetGrid.Item(legacy, 0, 0, 4, 4)) : List.of(); }
        try { JSONArray json = new JSONArray(value); int[] values = new int[json.length()]; for (int i = 0; i < values.length; i++) values[i] = json.getInt(i); List<WidgetGrid.Item> result = WidgetGrid.unpack(values); if (result.size() * 5 != values.length) throw new IllegalStateException("卡片布局数据无效"); return result; }
        catch (org.json.JSONException failure) { throw new IllegalStateException("卡片布局无法读取", failure); }
    }
    private Set<String> draftIds(int outer) { return new HashSet<>(data.getStringSet("draft_" + outer, Set.of())); }
    String startEditing(int outer) {
        if (!owns(outer)) throw new IllegalArgumentException("卡片已移除");
        discardDraft(outer); String owner = java.util.UUID.randomUUID().toString();
        if (!data.edit().putString("editor_" + outer, owner).commit()) throw new IllegalStateException("无法开始编辑"); return owner;
    }
    boolean editing(int outer, String owner) { return !owner.isEmpty() && owner.equals(data.getString("editor_" + outer, "")); }
    void endEditing(int outer, String owner) { if (editing(outer, owner)) { discardDraft(outer); data.edit().remove("editor_" + outer).apply(); } }
    int allocate(int outer) {
        if (!owns(outer)) throw new IllegalArgumentException("外屏卡片已被移除");
        cancel(outer); int id = host.allocateAppWidgetId(); if (id <= 0) throw new IllegalStateException("系统未分配小组件");
        if (!data.edit().putInt("pending_" + outer, id).putLong("pending_at_" + outer, System.currentTimeMillis()).commit()) { host.deleteAppWidgetId(id); throw new IllegalStateException("无法保存小组件"); }
        schedule(); return id;
    }
    void cancel(int outer) {
        int id = pending(outer); data.edit().remove("pending_" + outer).commit();
        if (id > 0 && items(outer).stream().noneMatch(item -> item.id() == id)) host.deleteAppWidgetId(id);
    }
    boolean stage(int outer, int id) {
        if (!owns(outer) || pending(outer) != id || info(id) == null) return false;
        Set<String> drafts = draftIds(outer); if (drafts.size() >= 16) return false; drafts.add(Integer.toString(id));
        return data.edit().putStringSet("draft_" + outer, drafts).remove("pending_" + outer).putLong("pending_at_" + outer, System.currentTimeMillis()).commit();
    }
    void discardDraft(int outer) {
        cancel(outer); Set<String> drafts = draftIds(outer);
        data.edit().remove("draft_" + outer).remove("pending_at_" + outer).commit();
        for (String value : drafts) { int id = Integer.parseInt(value); if (items(outer).stream().noneMatch(item -> item.id() == id)) { views.remove(id); host.deleteAppWidgetId(id); } }
    }
    void releaseDraft(int outer, int id) {
        Set<String> drafts = draftIds(outer); if (!drafts.remove(Integer.toString(id))) return;
        data.edit().putStringSet("draft_" + outer, drafts).commit(); views.remove(id); host.deleteAppWidgetId(id);
    }
    boolean commitLayout(int outer, List<WidgetGrid.Item> proposed) {
        if (!owns(outer) || !WidgetGrid.valid(proposed)) return false;
        if (Build.VERSION.SDK_INT < 31 && (proposed.size() > 1 || (!proposed.isEmpty() && proposed.get(0).cells() != 16))) return false;
        List<WidgetGrid.Item> old = items(outer); Set<Integer> allowed = new HashSet<>(); for (WidgetGrid.Item item : old) allowed.add(item.id());
        for (String value : draftIds(outer)) allowed.add(Integer.parseInt(value));
        for (WidgetGrid.Item item : proposed) {
            boolean unchangedSize = old.stream().anyMatch(i -> i.id() == item.id() && i.width() == item.width() && i.height() == item.height());
            // A rotation/safe-area change must not invalidate an already authorized
            // instance. New or resized items still obey the current usable dimensions.
            if (!allowed.contains(item.id()) || info(item.id()) == null || (!unchangedSize && !supports(outer, info(item.id()), item.width(), item.height()))) return false;
        }
        JSONArray json = new JSONArray(); for (int value : WidgetGrid.pack(proposed)) json.put(value);
        String oldLayout = data.getString("layout_" + outer, null); int oldLegacy = data.getInt("inner_" + outer, -1); Set<String> staged = draftIds(outer); long oldTime = data.getLong("pending_at_" + outer, 0);
        if (!data.edit().putString("layout_" + outer, json.toString()).remove("inner_" + outer).remove("draft_" + outer).remove("pending_at_" + outer).commit()) {
            // SharedPreferences changes its in-memory map even when its disk write fails.
            SharedPreferences.Editor rollback = data.edit().putString("layout_" + outer, oldLayout).putStringSet("draft_" + outer, staged).putLong("pending_at_" + outer, oldTime);
            if (oldLegacy > 0) rollback.putInt("inner_" + outer, oldLegacy); else rollback.remove("inner_" + outer); rollback.commit(); return false;
        }
        Set<Integer> kept = new HashSet<>(); for (WidgetGrid.Item item : proposed) kept.add(item.id());
        for (int id : allowed) if (!kept.contains(id)) { views.remove(id); host.deleteAppWidgetId(id); }
        refresh(); return true;
    }
    // Retains the original one-widget migration/test path while the editor uses complete drafts.
    boolean save(int outer, int id) { return stage(outer, id) && commitLayout(outer, List.of(new WidgetGrid.Item(id, 0, 0, 4, 4))); }
    void remove(int outer) {
        List<WidgetGrid.Item> old = items(outer); discardDraft(outer); data.edit().remove("inner_" + outer).remove("layout_" + outer).commit();
        for (WidgetGrid.Item item : old) { views.remove(item.id()); host.deleteAppWidgetId(item.id()); } refresh();
    }
    void observe(IntConsumer observer) { observers.add(observer); }
    void unobserve(IntConsumer observer) { observers.remove(observer); }
    RemoteViews preview(int id) { RelayView view = views.get(id); return view == null || view.latest == null ? null : new RemoteViews(view.latest); }
    void watchDraft(int id) { AppWidgetProviderInfo info = info(id); if (info != null && views.get(id) == null) views.put(id, (RelayView) host.createView(context, id, info)); }
    void trim() { for (int i = 0; i < views.size(); i++) views.valueAt(i).latest = null; restoreBeforePublish = true; }
    void safeArea(Display display, int width, int height, DockGeometry.Box safe) {
        ChromeArea next = display == null || safe == null ? null : new ChromeArea(display.getDisplayId(), display.getRotation(), width, height, safe);
        if (java.util.Objects.equals(chromeArea, next)) return;
        chromeArea = next; schedule(); CoverApp.launcherWidgets(context).schedule();
    }
    private void observeSystem(boolean enabled) {
        if (observing == enabled) return; observing = enabled; DisplayManager displays = context.getSystemService(DisplayManager.class);
        if (enabled) {
            displays.registerDisplayListener(this, main); prefs.data.registerOnSharedPreferenceChangeListener(preferenceListener);
            IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_USER_PRESENT);
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED); else context.registerReceiver(screenReceiver, filter);
        } else { displays.unregisterDisplayListener(this); prefs.data.unregisterOnSharedPreferenceChangeListener(preferenceListener); context.unregisterReceiver(screenReceiver); main.removeCallbacks(reconcile); }
    }
    void refresh() {
        try {
            int[] cards = cards(); Set<Integer> active = new HashSet<>(), retained = new HashSet<>(); main.removeCallbacks(expireDraft); long next = DRAFT_LIFETIME; boolean drafts = false;
            for (int outer : cards) {
                active.add(outer); for (WidgetGrid.Item item : items(outer)) retained.add(item.id());
                if (pending(outer) > 0 || !draftIds(outer).isEmpty()) {
                    long remaining = DRAFT_LIFETIME - (System.currentTimeMillis() - data.getLong("pending_at_" + outer, 0));
                    if (remaining <= 0 || remaining > DRAFT_LIFETIME) discardDraft(outer);
                    else { if (pending(outer) > 0) retained.add(pending(outer)); for (String id : draftIds(outer)) retained.add(Integer.parseInt(id)); drafts = true; next = Math.min(next, remaining); }
                }
            }
            if (drafts) main.postDelayed(expireDraft, next);
            for (int id : host.getAppWidgetIds()) if (!retained.contains(id)) { host.deleteAppWidgetId(id); views.remove(id); }
            SharedPreferences.Editor cleanup = data.edit();
            for (String key : data.getAll().keySet()) { int split = key.lastIndexOf('_'); if (split >= 0) try { if (!active.contains(Integer.parseInt(key.substring(split + 1)))) cleanup.remove(key); } catch (NumberFormatException ignored) { } }
            cleanup.apply(); observeSystem(cards.length > 0); Display display = Displays.selected(context, prefs);
            if (cards.length == 0 || display == null || display.getState() != Display.STATE_ON) { stop(); if (display == null) for (int outer : cards) message(outer, "请先选择目标外屏", "在外屏助手设置中确认显示器后再配置"); return; }
            if (displayId != display.getDisplayId()) { stop(); displayId = display.getDisplayId(); }
            for (int outer : cards) { for (WidgetGrid.Item item : items(outer)) watchDraft(item.id()); resize(outer); }
            if (!listening) { host.startListening(); listening = true; }
        } catch (RuntimeException failure) { stop(); android.util.Log.w("NativeWidgets", "Widget connection unavailable", failure); }
    }
    void resize(int outer) {
        for (WidgetGrid.Item item : items(outer)) updateSize(item.id(), size(outer, item.width(), item.height()));
        dirty.add(outer); main.removeCallbacks(publishDirty); main.post(publishDirty);
    }
    void updateSize(int id, SizeF size) {
        if (info(id) == null) return; Bundle old = manager.getAppWidgetOptions(id);
        if (old.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) != (int) size.getWidth() || old.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) != (int) size.getHeight() || old.getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY) != AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD) manager.updateAppWidgetOptions(id, options(size));
    }
    Bundle options(SizeF size) {
        Bundle options = new Bundle(); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (int) size.getWidth()); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, (int) size.getWidth()); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, (int) size.getHeight()); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (int) size.getHeight());
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 31) options.putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, new ArrayList<>(List.of(size))); return options;
    }
    private SizeF canvasSize(int outer) {
        Bundle options = manager.getAppWidgetOptions(outer); Display display = Displays.selected(context, prefs); float maxWidth = 352, maxHeight = 339;
        if (display != null) { Point pixels = Displays.size(display); float density = context.createDisplayContext(display).getResources().getDisplayMetrics().density; maxWidth = pixels.x / density; maxHeight = pixels.y / density; }
        int width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, (int) maxWidth), height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, (int) maxHeight);
        if (maxWidth > maxHeight) { width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width); height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height); }
        return new SizeF(Math.max(1, Math.min(maxWidth, width)), Math.max(1, Math.min(maxHeight, height)));
    }
    WidgetSafeArea.Frame frame(int outer) {
        SizeF canvas = canvasSize(outer); Display display = Displays.selected(context, prefs);
        if (display == null || Build.VERSION.SDK_INT < 31) return new WidgetSafeArea.Frame(0, 0, canvas.getWidth(), canvas.getHeight());
        Point pixels = Displays.size(display); float density = context.createDisplayContext(display).getResources().getDisplayMetrics().density;
        DockGeometry.Box safe = null;
        if (chromeArea != null && chromeArea.displayId == display.getDisplayId() && chromeArea.rotation == display.getRotation() && chromeArea.width == pixels.x && chromeArea.height == pixels.y) safe = chromeArea.safe;
        // When our overlays are absent, the native host owns system-bar insets. The
        // physical cutout is still authoritative; never invent a camera-side margin.
        if (safe == null && display.getCutout() != null) {
            android.view.DisplayCutout cutout = display.getCutout(); int left = cutout.getSafeInsetLeft(), top = cutout.getSafeInsetTop();
            safe = new DockGeometry.Box(left, top, Math.max(0, pixels.x - left - cutout.getSafeInsetRight()), Math.max(0, pixels.y - top - cutout.getSafeInsetBottom()));
        }
        return WidgetSafeArea.fit(canvas.getWidth(), canvas.getHeight(), pixels.x, pixels.y, density, safe);
    }
    SizeF size(int outer) { WidgetSafeArea.Frame frame = frame(outer); return new SizeF(Math.max(1, frame.width()), Math.max(1, frame.height())); }
    SizeF size(int outer, int width, int height) { SizeF full = size(outer); return new SizeF(full.getWidth() * width / 4, full.getHeight() * height / 4); }
    WidgetGrid.Item defaultItem(int outer, AppWidgetProviderInfo info) {
        float density = context.getResources().getDisplayMetrics().density; SizeF full = size(outer);
        int width = Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0 ? info.targetCellWidth : Math.max(1, (int) Math.ceil(info.minWidth / density / (full.getWidth() / 4)));
        int height = Build.VERSION.SDK_INT >= 31 && info.targetCellHeight > 0 ? info.targetCellHeight : Math.max(1, (int) Math.ceil(info.minHeight / density / (full.getHeight() / 4)));
        if (width > 4 && (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0) width = 4;
        if (height > 4 && (info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0) height = 4;
        return new WidgetGrid.Item(1, 0, 0, width, height);
    }
    boolean supports(int outer, AppWidgetProviderInfo info, int width, int height) {
        if (width < 1 || width > 4 || height < 1 || height > 4 || info == null) return false;
        WidgetGrid.Item initial = defaultItem(outer, info);
        if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) == 0 && width != initial.width()) return false;
        if ((info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) == 0 && height != initial.height()) return false;
        if (Build.VERSION.SDK_INT >= 31) {
            float density = context.getResources().getDisplayMetrics().density; SizeF actual = size(outer, width, height);
            if (info.maxResizeWidth > 0 && info.maxResizeWidth >= info.minWidth && actual.getWidth() > info.maxResizeWidth / density + 1) return false;
            if (info.maxResizeHeight > 0 && info.maxResizeHeight >= info.minHeight && actual.getHeight() > info.maxResizeHeight / density + 1) return false;
        }
        return fits(info, size(outer, width, height));
    }
    boolean fits(AppWidgetProviderInfo info, SizeF size) {
        if (info == null || info.provider.getPackageName().equals(context.getPackageName()) || !info.getProfile().equals(android.os.Process.myUserHandle())) return false;
        if (Build.VERSION.SDK_INT >= 31 && ((info.targetCellWidth > 4 && (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) == 0) || (info.targetCellHeight > 4 && (info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) == 0))) return false;
        float density = context.getResources().getDisplayMetrics().density;
        int width = (info.resizeMode & AppWidgetProviderInfo.RESIZE_HORIZONTAL) != 0 && info.minResizeWidth > 0 ? info.minResizeWidth : info.minWidth;
        int height = (info.resizeMode & AppWidgetProviderInfo.RESIZE_VERTICAL) != 0 && info.minResizeHeight > 0 ? info.minResizeHeight : info.minHeight;
        return width / density <= size.getWidth() + 1 && height / density <= size.getHeight() + 1;
    }
    private void publish(int outer) {
        Display display = Displays.selected(context, prefs); if (!owns(outer) || display == null || display.getState() != Display.STATE_ON) return;
        if (restoreBeforePublish) { stop(); refresh(); return; }
        List<WidgetGrid.Item> items = items(outer); if (items.isEmpty()) { message(outer, "布置这张卡片", "点按添加组件 · 4×4自由布局"); return; }
        try {
            WidgetSafeArea.Frame frame = frame(outer); SizeF canvas = canvasSize(outer);
            if (frame.width() < 1 || frame.height() < 1) { message(outer, "安全区空间不足", "请调整快捷栏大小或位置后重试"); return; }
            WidgetGrid.Item only = items.get(0); RemoteViews single = preview(only.id());
            if (!frame.inset(canvas.getWidth(), canvas.getHeight()) && items.size() == 1 && only.width() == 4 && only.height() == 4 && single != null && fits(info(only.id()), size(outer))) { manager.updateAppWidget(outer, single); return; }
            RemoteViews grid = new RemoteViews(context.getPackageName(), R.layout.native_widget_grid); grid.removeAllViews(R.id.widget_grid_root); SizeF size = size(outer);
            for (int i = 0; i < items.size(); i++) {
                WidgetGrid.Item item = items.get(i); int slot = R.id.widget_cell; RemoteViews cell = new RemoteViews(context.getPackageName(), R.layout.native_widget_cell);
                if (Build.VERSION.SDK_INT >= 31) {
                    // Layout dimensions round while RemoteViews margins truncate. A half-dp
                    // allowance prevents neighbouring cells from overlapping by one pixel.
                    cell.setViewLayoutWidth(slot, Math.max(1, size.getWidth() * item.width() / 4 - .5f), TypedValue.COMPLEX_UNIT_DIP); cell.setViewLayoutHeight(slot, Math.max(1, size.getHeight() * item.height() / 4 - .5f), TypedValue.COMPLEX_UNIT_DIP);
                    cell.setViewLayoutMargin(slot, RemoteViews.MARGIN_LEFT, frame.left() + size.getWidth() * item.x() / 4, TypedValue.COMPLEX_UNIT_DIP); cell.setViewLayoutMargin(slot, RemoteViews.MARGIN_TOP, frame.top() + size.getHeight() * item.y() / 4, TypedValue.COMPLEX_UNIT_DIP);
                }
                RemoteViews content = preview(item.id());
                if (content == null || info(item.id()) == null) content = placeholder(outer, widgetLabel(item.id()), "点按编辑此卡片");
                cell.removeAllViews(slot); cell.addView(slot, content); grid.addView(R.id.widget_grid_root, cell);
            }
            manager.updateAppWidget(outer, grid);
        } catch (RuntimeException failure) { message(outer, "暂时无法组合这些组件", "内容或图片可能过大，点按调整布局"); }
    }
    private RemoteViews placeholder(int outer, String title, String detail) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.native_widget_message); view.setTextViewText(R.id.native_widget_title, title); view.setTextViewText(R.id.native_widget_detail, detail);
        Display display = Displays.selected(context, prefs);
        if (display != null) { Intent intent = new Intent(context, NativeWidgetActivity.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outer).setData(android.net.Uri.parse("flipcover://widget/" + outer)); view.setOnClickPendingIntent(R.id.native_widget_message, PendingIntent.getActivity(context, outer, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle())); }
        return view;
    }
    void message(int outer, String title, String detail) { manager.updateAppWidget(outer, placeholder(outer, title, detail)); }
    private final class RelayView extends AppWidgetHostView {
        private final int id; private RemoteViews latest;
        RelayView(Context context, int id) { super(context); this.id = id; }
        @Override public void updateAppWidget(RemoteViews remote) {
            latest = remote == null ? null : new RemoteViews(remote);
            for (int outer : cards()) if (items(outer).stream().anyMatch(item -> item.id() == id)) dirty.add(outer);
            main.removeCallbacks(publishDirty); main.post(publishDirty); for (IntConsumer observer : List.copyOf(observers)) observer.accept(id);
        }
    }
    private void stop() { restoreBeforePublish = false; if (listening) try { host.stopListening(); } catch (RuntimeException ignored) { } listening = false; host.releaseViews(); views.clear(); displayId = -1; main.removeCallbacks(publishDirty); dirty.clear(); }
    private void schedule() { main.removeCallbacks(reconcile); main.post(reconcile); }
    @Override public void onDisplayAdded(int id) { schedule(); }
    @Override public void onDisplayRemoved(int id) { schedule(); }
    @Override public void onDisplayChanged(int id) { schedule(); }
}
