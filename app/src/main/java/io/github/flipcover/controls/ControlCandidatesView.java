package io.github.flipcover.controls;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/** Candidate catalog only: virtualized cells and subscriptions scoped to the visible source. */
final class ControlCandidatesView extends LinearLayout implements AppCatalogCache.Listener {
    private final AppCatalogCache catalog;
    private final List<String> selected;
    private final Consumer<String> choose;
    private final BiPredicate<String, View> drag;
    private final Runnable collapse;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Bundle states;
    private final ArrayList<String> all = new ArrayList<>(), visible = new ArrayList<>();
    private final GridView grid;
    private final Adapter adapter = new Adapter();
    private final LinearLayout header;
    private final TextView status;
    private String source;
    private EditText search;
    private boolean searching, restoring, loading, failed;
    private int generation;

    ControlCandidatesView(Context c, int columns, List<String> selected, Bundle state, Consumer<String> choose, BiPredicate<String, View> drag, Runnable collapse) {
        super(c); this.selected = selected; this.choose = choose; this.drag = drag; this.collapse = collapse; catalog = CoverApp.catalog(c); states = new Bundle(state); source = state.getString("source", "builtin"); searching = state.getBoolean("searching");
        setOrientation(VERTICAL); setTag("control-candidates"); setBackground(Ui.background(c, SettingsUi.SURFACE, 20)); setClipToOutline(true);
        header = SettingsUi.row(c); addView(header);
        status = SettingsUi.text(c, "", 14, SettingsUi.MUTED); status.setSingleLine(); status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setTag("control-candidate-status"); status.setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 12), Ui.dp(c, 4)); status.setOnClickListener(v -> { if (source.equals("apps")) catalog.warm(); else load(); }); addView(status);
        grid = new GridView(c); grid.setTag("control-candidate-grid"); grid.setNumColumns(columns); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setVerticalSpacing(Ui.dp(c, 2)); grid.setClipToPadding(false); grid.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), Ui.dp(c, 6)); grid.setAdapter(adapter); addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        header(); load();
    }
    GridView grid() { return grid; }
    int chromeHeight(int widthSpec) {
        int natural = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
        header.measure(widthSpec, natural); int height = header.getMeasuredHeight();
        if (status.getVisibility() != GONE) { status.measure(widthSpec, natural); height += status.getMeasuredHeight(); }
        return height;
    }
    Bundle snapshot() { saveSource(); Bundle result = new Bundle(states); result.putString("source", source); result.putBoolean("searching", searching); return result; }
    private Bundle current() { Bundle state = states.getBundle(source); if (state == null) { state = new Bundle(); states.putBundle(source, state); } return state; }
    private void saveSource() { if (search != null) current().putString("query", search.getText().toString()); current().putParcelable("grid", grid.onSaveInstanceState()); }
    private void header() {
        header.removeAllViews(); search = null; Context c = getContext();
        if (searching) {
            search = SettingsUi.search(c, source.equals("apps") ? "搜索应用" : "搜索按钮", current().getString("query", "")); search.setTag("control-candidate-query"); header.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
            search.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { current().putString("query", s.toString()); filter(); grid.setSelection(0); } public void afterTextChanged(android.text.Editable text) { } });
            View close = SettingsUi.iconButton(c, R.drawable.ic_ms_close, "结束搜索", this::closeSearch); close.setTag("control-search-close"); header.addView(close);
        } else {
            View tabs = SettingsUi.sourceTabs(c, source, this::source); tabs.setPadding(0, 0, 0, 0); header.addView(tabs, new LinearLayout.LayoutParams(0, -2, 1));
            View find = SettingsUi.iconButton(c, R.drawable.ic_ms_search, "搜索候选按钮", this::search); find.setTag("control-candidates-search"); header.addView(find);
            View hide = SettingsUi.iconButton(c, R.drawable.ic_ms_keyboard_arrow_down, "收起候选区", collapse); hide.setTag("control-candidates-collapse"); header.addView(hide); compactTabs(getWidth());
        }
    }
    void search() { if (searching) { search.requestFocus(); return; } saveSource(); searching = true; header(); search.requestFocus(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); }
    boolean closeSearch() {
        if (!searching) return false; saveSource(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0); searching = false; header(); requestFocus(); return true;
    }
    private void source(String next) { saveSource(); catalog.unobserve(this); source = next; searching = false; header(); load(); if (isAttachedToWindow() && source.equals("apps")) catalog.observe(this); }
    private void load() {
        generation++; all.clear(); failed = false; loading = false; restoring = true;
        switch (source) {
            case "apps" -> { for (AppCatalogCache.Entry entry : catalog.snapshot()) all.add(entry.id()); filter(); restorePosition(); }
            case "tiles" -> {
                loading = true; filter(); int request = generation;
                worker.execute(() -> { List<String> ids; try { ids = ActionCatalog.applicationTiles(getContext()); } catch (RuntimeException error) { ids = null; } List<String> result = ids;
                    post(() -> { if (!isAttachedToWindow() || request != generation) return; loading = false; failed = result == null; all.clear(); if (result != null) all.addAll(result); filter(); restorePosition(); });
                });
            }
            default -> { for (ActionCatalog.Action action : ActionCatalog.BUILT_INS) all.add(action.id()); filter(); restorePosition(); }
        }
    }
    private void filter() {
        String query = current().getString("query", "").trim().toLowerCase(Locale.ROOT); visible.clear();
        for (String id : all) if (query.isEmpty() || (ActionCatalog.label(getContext(), id) + " " + id).toLowerCase(Locale.ROOT).contains(query)) visible.add(id);
        boolean waiting = loading || source.equals("apps") && !catalog.ready() && !catalog.failed();
        String message = waiting ? "正在读取…" : failed || source.equals("apps") && catalog.failed() ? "读取失败，点此重试" : visible.isEmpty() ? query.isEmpty() ? source.equals("tiles") ? "没有可添加的应用磁贴" : "当前没有候选项" : "没有找到匹配项" : source.equals("tiles") ? "实验功能 · 点按查看添加与注册" : query.isEmpty() ? "" : "筛选：" + query;
        status.setText(message); status.setVisibility(message.isEmpty() ? GONE : VISIBLE); adapter.notifyDataSetChanged();
    }
    private void restorePosition() {
        if (!restoring || source.equals("apps") && !catalog.ready() || loading) return;
        restoring = false; android.os.Parcelable position = current().getParcelable("grid"); if (position != null) grid.onRestoreInstanceState(position);
    }
    void selectionChanged() { adapter.notifyDataSetChanged(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); if (source.equals("apps")) { catalog.observe(this); catalogChanged(); } }
    @Override protected void onDetachedFromWindow() { generation++; catalog.unobserve(this); worker.shutdownNow(); super.onDetachedFromWindow(); }
    @Override public void catalogChanged() { if (!source.equals("apps")) return; all.clear(); for (AppCatalogCache.Entry entry : catalog.snapshot()) all.add(entry.id()); filter(); restorePosition(); }
    @Override public void iconsChanged(Set<String> ids) { for (int i = 0; i < grid.getChildCount(); i++) { View child = grid.getChildAt(i); if (child.getTag() instanceof String tag && tag.startsWith("control-candidate-") && ids.contains(tag.substring(18)) && child instanceof ControlEditGrid.Cell cell) { android.graphics.drawable.Drawable icon = catalog.cachedIcon(getContext(), tag.substring(18)); if (icon != null) cell.icon.setImageDrawable(icon); } } }
    private void compactTabs(int width) { boolean compact = width > 0 && width < Ui.dp(getContext(), 300); String[] ids = {"builtin", "tiles", "apps"}, names = compact ? new String[]{"内置", "磁贴", "应用"} : new String[]{"内置功能", "应用磁贴", "应用"}; for (int i = 0; i < ids.length; i++) { Button tab = header.findViewWithTag("library-tab-" + ids[i]); if (tab != null) tab.setText(names[i]); } }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (w != oldw) compactTabs(w); }
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return visible.get(position).hashCode(); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            String id = visible.get(position); ControlEditGrid.Cell cell = recycled instanceof ControlEditGrid.Cell value ? value : new ControlEditGrid.Cell(getContext());
            android.graphics.drawable.Drawable icon = source.equals("apps") ? catalog.cachedIcon(getContext(), id) : ActionCatalog.icon(getContext(), id); if (source.equals("apps") && icon == null) catalog.requestIcon(id);
            cell.bind(id, ActionCatalog.label(getContext(), id), icon, selected.contains(id), false); cell.setTag("control-candidate-" + id); cell.setOnClickListener(v -> choose.accept(id)); cell.setOnLongClickListener(v -> !selected.contains(id) && drag.test(id, v)); return cell;
        }
    }
}
