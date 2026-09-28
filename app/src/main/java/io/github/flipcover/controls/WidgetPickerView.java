package io.github.flipcover.controls;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RemoteViews;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Lazy application groups and provider previews. No binding occurs until the user chooses Add. */
final class WidgetPickerView extends LinearLayout implements AppCatalogCache.Listener {
    record Entry(AppWidgetProviderInfo info, String name, String app, String packageName) { }
    private record Preview(RemoteViews remote, Drawable image, String caption) { }
    private final NativeWidgetBridge bridge;
    private final int outer;
    private final List<WidgetGrid.Item> placed;
    private final Consumer<AppWidgetProviderInfo> choose;
    private final AppCatalogCache catalog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final LruCache<String, Preview> previews = new LruCache<>(8);
    private final LruCache<String, Drawable> groupIcons = new LruCache<>(16);
    private final List<Entry> entries = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();
    private final Set<String> expanded = new java.util.HashSet<>();
    private final ListView list;
    private final EditText search;
    private final TextView status;
    private final Adapter adapter = new Adapter();
    private boolean closed, loaded, restored;
    private int generation;
    private final Bundle saved;
    WidgetPickerView(Context c, NativeWidgetBridge bridge, int outer, List<WidgetGrid.Item> placed, Bundle saved, Consumer<AppWidgetProviderInfo> choose) {
        super(c); this.bridge = bridge; this.outer = outer; this.placed = placed; this.saved = saved; this.choose = choose; catalog = CoverApp.catalog(c);
        setOrientation(VERTICAL); setPadding(Ui.dp(c, 12), Ui.dp(c, 4), Ui.dp(c, 12), 0);
        expanded.addAll(saved.getStringArrayList("expanded") == null ? List.of() : saved.getStringArrayList("expanded"));
        search = SettingsUi.search(c, "搜索应用或小组件", saved.getString("query", "")); search.setTag("widget-search"); addView(search, new LayoutParams(-1, -2));
        status = SettingsUi.text(c, "正在读取小组件…", 14, SettingsUi.MUTED); status.setPadding(0, Ui.dp(c, 8), 0, Ui.dp(c, 8)); addView(status);
        list = new ListView(c); list.setTag("widget-picker-list"); list.setDivider(null); list.setItemsCanFocus(true); list.setAdapter(adapter); addView(list, new LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { rebuild(); } public void afterTextChanged(android.text.Editable text) { } });
        load();
    }
    void saveState(Bundle out) { out.putString("query", search.getText().toString()); out.putStringArrayList("expanded", new ArrayList<>(expanded)); out.putParcelable("scroll", list.onSaveInstanceState()); }
    private void load() {
        int requested = ++generation; Context app = getContext().getApplicationContext();
        worker.execute(() -> {
            List<Entry> found = new ArrayList<>(); String error = null;
            try {
                Map<String, String> labels = new java.util.HashMap<>(); for (AppCatalogCache.Entry entry : catalog.entriesBlocking()) labels.putIfAbsent(entry.packageName(), entry.label());
                for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(app).getInstalledProvidersForProfile(android.os.Process.myUserHandle())) {
                    String pkg = info.provider.getPackageName(); if (pkg.equals(app.getPackageName())) continue;
                    String label = labels.get(pkg); if (label == null) { try { label = app.getPackageManager().getApplicationLabel(app.getPackageManager().getApplicationInfo(pkg, 0)).toString(); } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { continue; } labels.put(pkg, label); }
                    found.add(new Entry(info, info.loadLabel(app.getPackageManager()), label, pkg));
                }
                java.text.Collator order = java.text.Collator.getInstance(); found.sort((a, b) -> { int compared = order.compare(a.app, b.app); return compared == 0 ? order.compare(a.name, b.name) : compared; });
            } catch (RuntimeException failure) { error = "读取失败，点此重试"; }
            String failure = error;
            main.post(() -> { if (closed || requested != generation) return; entries.clear(); entries.addAll(found); loaded = true; rebuild(); if (failure != null) { status.setText(failure); status.setOnClickListener(v -> load()); } });
        });
    }
    private void rebuild() {
        if (!loaded) return;
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT); Map<String, List<Entry>> groups = new LinkedHashMap<>(); rows.clear();
        for (Entry entry : entries) if ((entry.app + entry.name + entry.packageName).toLowerCase(Locale.ROOT).contains(query)) groups.computeIfAbsent(entry.packageName, key -> new ArrayList<>()).add(entry);
        for (Map.Entry<String, List<Entry>> group : groups.entrySet()) { rows.add(group.getValue()); if (expanded.contains(group.getKey()) || !query.isEmpty()) rows.addAll(group.getValue()); }
        status.setText(groups.isEmpty() ? query.isEmpty() ? "没有可用的小组件" : "没有找到匹配的小组件" : groups.size() + "个应用 · 点按展开"); status.setOnClickListener(null); adapter.notifyDataSetChanged();
        if (!restored) { restored = true; android.os.Parcelable state = saved.getParcelable("scroll"); if (state != null) list.onRestoreInstanceState(state); }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); catalog.observe(this); }
    @Override protected void onDetachedFromWindow() { closed = true; generation++; catalog.unobserve(this); worker.shutdownNow(); main.removeCallbacksAndMessages(null); previews.evictAll(); groupIcons.evictAll(); super.onDetachedFromWindow(); }
    @Override public void catalogChanged() { if (!closed && loaded) load(); }
    @Override public void iconsChanged(Set<String> ids) {
        for (int i = 0; i < list.getChildCount(); i++) { View row = list.getChildAt(i); ImageView icon = row.findViewWithTag("widget-app-icon"); if (icon != null && icon.getContentDescription() != null) { String id = icon.getContentDescription().toString(); if (ids.contains(id)) icon.setImageDrawable(catalog.cachedIcon(getContext(), id)); } }
    }
    private void groupIcon(String pkg, ImageView view) {
        int requested = generation; Context app = getContext().getApplicationContext();
        worker.execute(() -> { Drawable icon; try { icon = app.getPackageManager().getApplicationIcon(pkg); } catch (android.content.pm.PackageManager.NameNotFoundException | RuntimeException ignored) { return; }
            main.post(() -> { if (closed || generation != requested) return; groupIcons.put(pkg, icon); if (pkg.contentEquals(view.getContentDescription())) view.setImageDrawable(icon); });
        });
    }
    private void preview(Entry entry, WidgetPreview view, TextView caption, WidgetGrid.Item span) {
        String key = entry.info.provider.flattenToString(); view.setTag(key); Preview cached = previews.get(key);
        if (cached != null) { applyPreview(entry, view, caption, span, cached); return; }
        view.unavailable("正在加载预览…"); Context app = getContext().getApplicationContext(); int requested = generation;
        worker.execute(() -> {
            RemoteViews remote = null; Drawable image = null; String label = "应用提供的预览";
            try { if (Build.VERSION.SDK_INT >= 35) remote = AppWidgetManager.getInstance(app).getWidgetPreview(entry.info.provider, entry.info.getProfile(), AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN); } catch (RuntimeException ignored) { }
            if (remote == null && Build.VERSION.SDK_INT >= 31 && entry.info.previewLayout != 0) remote = new RemoteViews(entry.packageName, entry.info.previewLayout);
            if (remote == null) try { image = entry.info.loadPreviewImage(app, app.getResources().getDisplayMetrics().densityDpi); } catch (RuntimeException ignored) { }
            if (remote == null && image == null) { label = "未提供样式预览"; try { image = entry.info.loadIcon(app, app.getResources().getDisplayMetrics().densityDpi); } catch (RuntimeException ignored) { } }
            Preview result = new Preview(remote, image, label);
            main.post(() -> { if (closed || requested != generation) return; previews.put(key, result); if (key.equals(view.getTag())) applyPreview(entry, view, caption, span, result); });
        });
    }
    private void applyPreview(Entry entry, WidgetPreview view, TextView caption, WidgetGrid.Item span, Preview preview) {
        caption.setText(preview.caption); android.util.SizeF size = bridge.size(outer, Math.min(4, span.width()), Math.min(4, span.height()));
        try { if (preview.remote != null) view.remote(entry.info, preview.remote, size.getWidth(), size.getHeight()); else if (preview.image != null) view.image(preview.image); else view.unavailable(span.sizeLabel()); }
        catch (RuntimeException failure) { view.unavailable("预览暂不可用"); caption.setText("仍可查看尺寸并尝试添加"); }
    }
    private final class Adapter extends BaseAdapter {
        public int getCount() { return rows.size(); }
        public Object getItem(int p) { return rows.get(p); }
        public long getItemId(int p) { Object row = rows.get(p); return (row instanceof Entry entry ? entry.info.provider.flattenToString() : ((List<?>) row).get(0).toString()).hashCode(); }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int p) { return rows.get(p) instanceof Entry ? 1 : 0; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Context c = getContext(); Object value = rows.get(position);
            if (value instanceof List<?> group) {
                Entry first = (Entry) group.get(0); LinearLayout row = SettingsUi.row(c); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12)); row.setMinimumHeight(Ui.dp(c, 64)); row.setBackground(Ui.ripple(c, SettingsUi.SURFACE, 20)); row.setTag("widget-group-" + first.packageName);
                ImageView icon = new ImageView(c); icon.setTag("widget-app-icon"); String launcher = catalog.launcher(first.packageName); Drawable image = launcher == null ? groupIcons.get(first.packageName) : catalog.cachedIcon(c, launcher);
                icon.setImageDrawable(image == null ? Ui.icon(c, R.drawable.ic_ms_apps, SettingsUi.ACCENT) : image); icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); icon.setContentDescription(launcher == null ? first.packageName : launcher); if (image == null) { if (launcher != null) catalog.requestIcon(launcher); else groupIcon(first.packageName, icon); }
                row.addView(icon, new LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32))); LinearLayout words = SettingsUi.column(c); words.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 8), 0); words.addView(SettingsUi.heading(c, first.app, 16)); words.addView(SettingsUi.text(c, group.size() + "种小组件", 14, SettingsUi.ACCENT)); row.addView(words, new LayoutParams(0, -2, 1));
                TextView arrow = SettingsUi.text(c, (expanded.contains(first.packageName) || !search.getText().toString().isEmpty()) ? "⌃" : "⌄", 20, SettingsUi.MUTED); row.addView(arrow); row.setFocusable(true); row.setOnClickListener(v -> { int index = list.getFirstVisiblePosition(), top = list.getChildCount() == 0 ? 0 : list.getChildAt(0).getTop(); if (!expanded.add(first.packageName)) expanded.remove(first.packageName); rebuild(); list.setSelectionFromTop(index, top); }); return row;
            }
            Entry entry = (Entry) value; WidgetGrid.Item span = bridge.defaultItem(outer, entry.info); LinearLayout tile = SettingsUi.column(c); tile.setTag("widget-option-" + entry.info.provider.flattenToString()); tile.setPadding(Ui.dp(c, 12), Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 18));
            tile.addView(SettingsUi.heading(c, entry.name, 16)); TextView size = SettingsUi.text(c, span.sizeLabel() + " · 占" + span.cells() + "格", 14, SettingsUi.ACCENT); size.setTag("widget-size-label"); size.setPadding(0, Ui.dp(c, 4), 0, 0); tile.addView(size);
            WidgetPreview picture = new WidgetPreview(c); picture.setBackground(Ui.background(c, SettingsUi.SURFACE, 18)); int previewHeight = Math.max(56, Math.min(112, 220 * span.height() / Math.max(1, span.width()))); LayoutParams previewParams = new LayoutParams(-1, Ui.dp(c, previewHeight)); previewParams.topMargin = Ui.dp(c, 10); tile.addView(picture, previewParams);
            TextView caption = SettingsUi.text(c, "", 14, SettingsUi.MUTED); SettingsUi.add(tile, caption); preview(entry, picture, caption, span);
            boolean fits = bridge.supports(outer, entry.info, span.width(), span.height()), room = fits && WidgetGrid.find(placed, Integer.MAX_VALUE, span.width(), span.height()) != null;
            Button add = SettingsUi.button(c, room ? "添加" : "选择尺寸与位置", () -> choose.accept(entry.info)); add.setTag("widget-add-" + entry.info.provider.flattenToString()); SettingsUi.add(tile, add);
            if (!room) SettingsUi.add(tile, SettingsUi.text(c, fits ? "当前没有连续的" + span.sizeLabel() + "空位" : "此样式需要调整尺寸或使用较大卡片", 14, SettingsUi.MUTED)); return tile;
        }
    }
}
