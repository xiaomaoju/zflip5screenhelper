package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
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
    private final Path bodyClip=new Path();
    private String source;
    private EditText search;
    private boolean searching, restoring, loading, failed;
    private int generation;
    private PanelGlassSession glass;
    void glass(PanelGlassSession session) { glass=session; }

    ControlCandidatesView(Context c, List<String> selected, Bundle state, Consumer<String> choose, BiPredicate<String, View> drag, Runnable collapse) {
        super(c); this.selected = selected; this.choose = choose; this.drag = drag; this.collapse = collapse; catalog = CoverApp.catalog(c); states = new Bundle(state); source = state.getString("source", "builtin"); searching = state.getBoolean("searching");
        setOrientation(VERTICAL); setTag("control-candidates"); setBackground(Ui.background(c, SettingsUi.SURFACE, 20)); setClipChildren(false); setClipToPadding(false); setChildrenDrawingOrderEnabled(true);
        header = SettingsUi.row(c); header.setClipChildren(false); header.setClipToPadding(false); addView(header);
        status = Ui.text(c, "", PanelUi.SECONDARY, Ui.MUTED); status.setSingleLine(); status.setEllipsize(android.text.TextUtils.TruncateAt.END); status.setTag("control-candidate-status"); status.setPadding(Ui.dp(c, PanelUi.INSET), 0, Ui.dp(c, PanelUi.INSET), Ui.dp(c, PanelUi.GAP)); status.setOnClickListener(v -> { if (source.equals("apps")) catalog.warm(); else load(); }); addView(status);
        grid = new GridView(c); grid.setTag("control-candidate-grid"); grid.setNumColumns(1); grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH); grid.setVerticalSpacing(0); grid.setClipToPadding(false); grid.setPadding(Ui.dp(c, 2), 0, Ui.dp(c, 2), Ui.dp(c, 2)); grid.setAdapter(adapter); addView(grid, new LinearLayout.LayoutParams(-1, 0, 1));
        header(); load();
    }
    GridView grid() { return grid; }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int columns = ControlEditGrid.columnsForWidth(getContext(), MeasureSpec.getSize(widthSpec));
        if (grid.getNumColumns() != columns) grid.setNumColumns(columns);
        super.onMeasure(widthSpec, heightSpec);
    }
    // Clip scrolling content to its own viewport as well as the rounded card.
    // Only the header may overflow, so its expanding tab lens stays unobstructed.
    @Override protected int getChildDrawingOrder(int count,int position) { return position==count-1 ? 0 : position+1; }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { super.onSizeChanged(w,h,oldw,oldh); bodyClip.reset(); bodyClip.addRoundRect(0,0,w,h,Ui.dp(getContext(),20),Ui.dp(getContext(),20),Path.Direction.CW); }
    @Override protected boolean drawChild(Canvas canvas,View child,long time) {
        if (child==header) return super.drawChild(canvas,child,time);
        int save=canvas.save(); canvas.clipPath(bodyClip); canvas.clipRect(child.getLeft(),child.getTop(),child.getRight(),child.getBottom()); boolean drawn=super.drawChild(canvas,child,time); canvas.restoreToCount(save); return drawn;
    }
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
            search = PanelUi.search(c, source.equals("apps") ? "搜索应用" : "搜索按钮", current().getString("query", "")); search.setTag("control-candidate-query"); LinearLayout.LayoutParams inputSize=new LinearLayout.LayoutParams(0,Ui.dp(c,PanelUi.SLOT),1); inputSize.setMargins(Ui.dp(c,4),Ui.dp(c,2),0,Ui.dp(c,2)); header.addView(search,inputSize);
            search.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { current().putString("query", s.toString()); filter(); grid.setSelection(0); } public void afterTextChanged(android.text.Editable text) { } });
            View close = PanelUi.icon(c, R.drawable.ic_ms_close, "结束搜索", this::closeSearch); close.setTag("control-search-close"); header.addView(close);
        } else {
            ControlSourceTabs tabs=new ControlSourceTabs(c,source,this::source,this::search,collapse);
            LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-1,Ui.dp(c,ControlSourceTabs.HEIGHT)); int space=Ui.dp(c,4); size.setMargins(space,0,space,0); header.addView(tabs,size);
        }
    }
    void search() { if (searching) { search.requestFocus(); return; } saveSource(); searching = true; header(); search.requestFocus(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(search, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); }
    boolean closeSearch() {
        if (!searching) return false; saveSource(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0); searching = false; header(); requestFocus(); return true;
    }
    private void source(String next) {
        if (source.equals(next)) return; saveSource(); catalog.unobserve(this); source=next;
        if (searching) { searching=false; header(); } else { ControlSourceTabs tabs=header.findViewWithTag("control-source-tabs"); if (tabs!=null) tabs.source(next); }
        load(); if (isAttachedToWindow() && source.equals("apps")) catalog.observe(this);
    }
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
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return visible.size(); }
        @Override public Object getItem(int position) { return visible.get(position); }
        @Override public long getItemId(int position) { return visible.get(position).hashCode(); }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            String id = visible.get(position); ControlEditGrid.Cell cell = recycled instanceof ControlEditGrid.Cell value ? value : new ControlEditGrid.Cell(getContext());
            android.graphics.drawable.Drawable icon = source.equals("apps") ? catalog.cachedIcon(getContext(), id) : ActionCatalog.icon(getContext(), id); if (source.equals("apps") && icon == null) catalog.requestIcon(id);
            cell.bind(id, ActionCatalog.label(getContext(), id), icon, selected.contains(id), false); if (glass!=null && glass.active()) cell.glass(glass); cell.setTag("control-candidate-" + id); cell.setOnClickListener(v -> choose.accept(id)); cell.setOnLongClickListener(v -> !selected.contains(id) && drag.test(id, v)); return cell;
        }
    }
}
