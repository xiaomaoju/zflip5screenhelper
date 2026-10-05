package io.github.flipcover.controls;

import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Virtualized settings application list, backed by the process catalog and no usage history. */
final class SettingsApplications extends LinearLayout implements AppCatalogCache.Listener {
    private final AppCatalogCache catalog;
    private final Prefs prefs;
    private final String mode;
    private final EditText search;
    private final ListView list;
    private final TextView status;
    private final LinearLayout listHeader;
    private final Consumer<AppCatalogCache.Entry> select;
    private final List<AppCatalogCache.Entry> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private final Bundle saved;
    private boolean restored;
    private Predicate<String> pickerSelection;

    SettingsApplications(Context context, Prefs prefs, String mode, Bundle state, Consumer<AppCatalogCache.Entry> select) {
        super(context); this.prefs = prefs; this.mode = mode; this.select = select; saved = state; catalog = CoverApp.catalog(context);
        setOrientation(VERTICAL); setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 12));
        listHeader = SettingsUi.column(context);
        search = SettingsUi.search(context, "搜索应用", state.getString("settings-query", "")); search.setTag("settings-query"); SettingsUi.add(listHeader, search);
        status = SettingsUi.text(context, "正在读取应用…", 14, SettingsUi.MUTED); status.setPadding(Ui.dp(context, 12), 0, Ui.dp(context, 12), Ui.dp(context, 8)); listHeader.addView(status);
        list = new ListView(context); list.setTag("settings-app-list"); list.addHeaderView(listHeader, null, false); list.setAdapter(adapter); list.setDivider(new android.graphics.drawable.ColorDrawable(0xFF38383D)); list.setDividerHeight(1); list.setBackground(Ui.background(context, SettingsUi.SURFACE, 20)); list.setClipToOutline(true); list.setItemsCanFocus(true); addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        search.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { update(); } public void afterTextChanged(android.text.Editable text) { } });
        update();
    }
    void saveUiState(Bundle state) {
        state.putString("settings-query", search.getText().toString());
        state.putBoolean("settings-query-visible", true);
        state.putParcelable("list-settings-app-list", list.onSaveInstanceState());
        if (search.isFocused()) state.putString("focus", "settings-query");
    }
    void pickerSelection(Predicate<String> selected) { pickerSelection = selected; adapter.notifyDataSetChanged(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); catalog.observe(this); }
    @Override protected void onDetachedFromWindow() { catalog.unobserve(this); super.onDetachedFromWindow(); }
    @Override public void catalogChanged() { update(); }
    @Override public void iconsChanged(Set<String> ids) {
        for (int i = 0; i < list.getChildCount(); i++) { View row = list.getChildAt(i); if (row.getTag() instanceof String id && ids.contains(id)) { ImageView icon = row.findViewWithTag("app-icon"); if (icon != null) icon.setImageDrawable(catalog.cachedIcon(getContext(), id)); } }
    }
    private void update() {
        Map<String, AppCatalogCache.Entry> all = new LinkedHashMap<>();
        for (AppCatalogCache.Entry entry : catalog.snapshot()) all.putIfAbsent(mode.equals("picker") ? entry.id() : entry.packageName(), entry);
        if (!mode.equals("picker")) {
            Set<String> packages = new java.util.LinkedHashSet<>();
            // These system hosts have no launcher entry; unchecked rules must remain selectable.
            if (mode.equals("visibility") || mode.equals("status")) packages.addAll(Set.of("com.android.systemui", "com.sec.android.app.launcher"));
            if (mode.equals("visibility")) packages.addAll(prefs.compactApps());
            else if (mode.equals("status")) packages.addAll(prefs.statusHiddenApps());
            else { java.util.Iterator<String> keys = prefs.rotationRules().keys(); while (keys.hasNext()) packages.add(keys.next()); }
            for (String name : packages) if (!all.containsKey(name)) {
                String label = name.equals("com.android.systemui") ? "三星外屏与系统面板" : name.equals("com.sec.android.app.launcher") ? "三星 One UI 桌面" : name;
                all.put(name, new AppCatalogCache.Entry("", label, name, label.toLowerCase(Locale.ROOT)));
            }
        }
        items.clear(); String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (AppCatalogCache.Entry entry : all.values()) if ((entry.searchKey() + entry.packageName()).contains(query)) items.add(entry);
        String message = !catalog.ready() ? catalog.failed() ? "读取失败，点此重试" : "正在读取应用…" : items.isEmpty() ? query.isEmpty() ? "当前没有可选应用" : "没有找到应用" : mode.equals("orientations") ? "选择应用设置方向 · " + items.size() + " 个" : mode.equals("visibility") ? "开启后，该应用只显示横条" : mode.equals("status") ? "进入选中应用时隐藏外屏常驻状态栏，离开后恢复；控制中心状态行保留" : "选择应用 · " + items.size() + " 个";
        if (!message.contentEquals(status.getText())) status.setText(message); status.setOnClickListener(catalog.failed() ? v -> catalog.warm() : null);
        adapter.notifyDataSetChanged();
        if (!restored && catalog.ready()) { restored = true; android.os.Parcelable position = saved.getParcelable("list-settings-app-list"); if (position != null) list.onRestoreInstanceState(position); if ("settings-query".equals(saved.getString("focus"))) search.requestFocus(); }
    }
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { String key = items.get(position).id().isEmpty() ? items.get(position).packageName() : items.get(position).id(); long hash = 0xcbf29ce484222325L; for (int i = 0; i < key.length(); i++) hash = (hash ^ key.charAt(i)) * 0x100000001b3L; return hash; }
        @Override public boolean hasStableIds() { return true; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            if (pickerSelection != null) {
                AppCatalogCache.Entry entry = items.get(position); android.graphics.drawable.Drawable drawable = catalog.cachedIcon(getContext(), entry.id()); if (drawable == null) catalog.requestIcon(entry.id());
                String summary = items.stream().filter(e -> e.label().equals(entry.label())).count() > 1 ? entry.packageName() : "";
                View row = SettingsUi.shortcutRow(getContext(), entry.id(), entry.label(), summary, drawable, pickerSelection.test(entry.id()), () -> select.accept(entry)); row.setTag(entry.id()); return row;
            }
            AppCatalogCache.Entry entry = items.get(position); Context c = getContext(); LinearLayout row = SettingsUi.row(c); row.setGravity(Gravity.CENTER_VERTICAL); row.setTag(entry.id()); row.setMinimumHeight(Ui.dp(c, 72)); row.setPadding(Ui.dp(c, 14), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 12)); row.setBackground(Ui.ripple(c, android.graphics.Color.TRANSPARENT, 0));
            ImageView icon = new ImageView(c); icon.setTag("app-icon"); icon.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); android.graphics.drawable.Drawable drawable = entry.id().isEmpty() ? null : catalog.cachedIcon(c, entry.id()); icon.setImageDrawable(drawable == null ? Ui.icon(c, R.drawable.ic_ms_apps, SettingsUi.ACCENT) : drawable); if (drawable == null && !entry.id().isEmpty()) catalog.requestIcon(entry.id());
            LinearLayout.LayoutParams image = new LinearLayout.LayoutParams(Ui.dp(c, 32), Ui.dp(c, 32)); image.setMarginEnd(Ui.dp(c, 12)); row.addView(icon, image);
            LinearLayout words = SettingsUi.column(c); words.addView(SettingsUi.heading(c, entry.label(), 16));
            String summary = mode.equals("orientations") ? Prefs.rotationLabel(prefs.appRotation(entry.packageName())) : "";
            if (entry.id().isEmpty() && !entry.packageName().startsWith("com.android.systemui") && !entry.packageName().equals("com.sec.android.app.launcher")) summary += "\n应用当前不可用 · 可修改已保存规则";
            long sameNames = items.stream().filter(e -> e.label().equals(entry.label())).count(); if (sameNames > 1) summary += (summary.isEmpty() ? "" : "\n") + entry.packageName();
            if (!summary.isEmpty()) { TextView value = SettingsUi.text(c, summary.trim(), 14, SettingsUi.ACCENT); value.setPadding(0, Ui.dp(c, 4), 0, 0); words.addView(value); } row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
            if (mode.equals("visibility") || mode.equals("status")) {
                boolean statusRule = mode.equals("status");
                Switch toggle = new Switch(c); toggle.setTag("app-toggle-" + entry.packageName()); toggle.setContentDescription(entry.label() + (statusRule ? "，隐藏外屏常驻状态栏" : "，只显示横条")); toggle.setMinimumWidth(Ui.dp(c, 48)); toggle.setMinimumHeight(Ui.dp(c, 48)); toggle.setChecked((statusRule ? prefs.statusHiddenApps() : prefs.compactApps()).contains(entry.packageName())); toggle.setThumbTintList(android.content.res.ColorStateList.valueOf(SettingsUi.TEXT)); toggle.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{SettingsUi.CONTROL, 0xFF65656C}));
                toggle.setOnCheckedChangeListener((button, enabled) -> { Set<String> selected = statusRule ? prefs.statusHiddenApps() : prefs.compactApps(); if (enabled) selected.add(entry.packageName()); else selected.remove(entry.packageName()); if (statusRule) prefs.saveStatusHiddenApps(selected); else prefs.data.edit().putStringSet("dock_compact_apps", selected).apply(); }); row.addView(toggle, new LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48))); row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked())); words.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            } else { row.setOnClickListener(v -> select.accept(entry)); row.setFocusable(true); }
            return row;
        }
    }
}
