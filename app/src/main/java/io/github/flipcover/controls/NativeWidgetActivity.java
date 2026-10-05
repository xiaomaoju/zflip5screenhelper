package io.github.flipcover.controls;

import android.app.Activity;
import android.app.ActivityOptions;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/** Native cover-card editor. Bindings are user-authorized; complete layouts commit only on Done. */
public final class NativeWidgetActivity extends Activity {
    private static final int BIND = 51, CONFIGURE = 52, NEW_ITEM = Integer.MAX_VALUE;
    private NativeWidgetBridge bridge;
    private int outer = -1, allocated = -1, target = -1;
    private boolean awaiting, reconfiguring, forwarded;
    private String page = "editor", notice = "", provider = "", owner = "";
    private List<WidgetGrid.Item> draft = new ArrayList<>();
    private WidgetGrid.Item placement;
    private LinearLayout root, body;
    private WidgetPickerView picker;
    private Bundle pickerState = new Bundle(), positions = new Bundle();
    private ScrollView pageScroll;
    private String renderedPage = "";
    private boolean restoringScroll;
    private WidgetTemplates.Card template;
    private int templateIndex;
    private int templatePage;
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); bridge = CoverApp.widgets(this); setResult(RESULT_CANCELED);
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::inputBack);
        outer = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
        if (outer > 0 && !bridge.owns(outer)) { finish(); return; }
        if (saved == null) saved = getIntent().getBundleExtra("editor_state");
        if (saved != null) restore(saved);
        else if (outer > 0) { owner = bridge.startEditing(outer); draft = new ArrayList<>(bridge.items(outer)); }
        show();
    }
    private void restore(Bundle saved) {
        draft = new ArrayList<>(WidgetGrid.unpack(saved.getIntArray("draft"))); List<WidgetGrid.Item> pending = WidgetGrid.unpack(saved.getIntArray("placement")); placement = pending.isEmpty() ? null : pending.get(0);
        owner = saved.getString("owner", ""); page = saved.getString("page", "editor"); notice = saved.getString("notice", ""); provider = saved.getString("provider", ""); allocated = saved.getInt("allocated", -1); target = saved.getInt("target", -1); awaiting = saved.getBoolean("awaiting"); reconfiguring = saved.getBoolean("reconfiguring");
        Bundle state = saved.getBundle("picker"); if (state != null) pickerState = state; Bundle scrolls = saved.getBundle("positions"); if (scrolls != null) positions = scrolls;
        try { List<WidgetTemplates.Card> templates = WidgetTemplates.read(new org.json.JSONArray(saved.getString("template", "[]"))); template = templates.isEmpty() ? null : templates.get(0); templateIndex = saved.getInt("template_index"); templatePage = saved.getInt("template_page"); }
        catch (org.json.JSONException | IllegalArgumentException error) { template = null; page = "editor"; notice = "模板无法恢复，请重新选择"; }
    }
    private Bundle state() {
        if (picker != null) picker.saveState(pickerState); capturePosition();
        Bundle saved = new Bundle(); saved.putString("owner", owner); saved.putIntArray("draft", WidgetGrid.pack(draft)); saved.putIntArray("placement", WidgetGrid.pack(placement == null ? List.of() : List.of(placement))); saved.putString("page", page); saved.putString("notice", notice); saved.putString("provider", provider); saved.putInt("allocated", allocated); saved.putInt("target", target); saved.putBoolean("awaiting", awaiting); saved.putBoolean("reconfiguring", reconfiguring); saved.putBundle("picker", pickerState); saved.putBundle("positions", positions);
        try { saved.putString("template", WidgetTemplates.json(template == null ? List.of() : List.of(template)).toString()); } catch (org.json.JSONException error) { throw new IllegalStateException(error); }
        saved.putInt("template_index", templateIndex); saved.putInt("template_page", templatePage); return saved;
    }
    @Override protected void onSaveInstanceState(Bundle saved) { saved.putAll(state()); super.onSaveInstanceState(saved); }
    @Override protected void onResume() { super.onResume(); if (!awaiting) show(); }
    @Override protected void onDestroy() { if (isFinishing() && !forwarded && outer > 0) bridge.endEditing(outer, owner); super.onDestroy(); }
    private Display selected() { return Displays.selected(this, new Prefs(this)); }
    private boolean ready() { Display d = selected(); return bridge.editing(outer, owner) && d != null && d.getDisplayId() > 0 && d.getState() == Display.STATE_ON && getDisplay() != null && getDisplay().getDisplayId() == d.getDisplayId(); }
    private boolean dirty() { return outer > 0 && !draft.equals(bridge.items(outer)); }
    @android.annotation.SuppressLint("GestureBackNavigation") // API 30–32 fallback; newer versions use the dispatcher.
    @Override public void onBackPressed() { inputBack(); }
    private void inputBack() { if (root != null && root.getParent() instanceof InputSurface input) input.cancelCommand(); else back(); }
    private void back() {
        if (awaiting) return;
        if (page.equals("template-restore")) { go("template-stop"); return; }
        if (page.equals("template-stop")) { go("template-restore"); return; }
        if (page.equals("template-confirm")) { template = null; go("templates"); return; }
        if (page.equals("discard")) { go("editor"); return; }
        if (page.equals("place")) { go(placement != null && placement.id() == NEW_ITEM ? "picker" : "editor"); return; }
        if (!page.equals("editor")) { go("editor"); return; }
        if (dirty()) go("discard"); else finish();
    }
    private void go(String next) { page = next; notice = ""; show(); }
    private void capturePosition() { if (!restoringScroll && pageScroll != null && body != null) positions.putBundle(renderedPage, SettingsNavigator.capture(pageScroll, body)); }
    private void show() {
        if (isFinishing()) return;
        capturePosition(); renderedPage = page; pageScroll = null; body = null;
        if (picker != null) { picker.saveState(pickerState); picker = null; }
        root = SettingsUi.column(this); root.setBackgroundColor(SettingsUi.BACKGROUND);
        root.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()); v.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets; });
        LinearLayout header = SettingsUi.row(this); header.setMinimumHeight(SettingsUi.dp(this, 40)); header.addView(NativeWidgetUi.iconButton(this, R.drawable.ic_ms_arrow_back, "返回", this::back));
        String title = outer <= 0 ? "组合卡片" : page.equals("picker") ? "添加小组件" : page.equals("place") ? "尺寸与位置" : "组合卡片" + bridge.slot(outer);
        android.widget.TextView heading = NativeWidgetUi.heading(this, title, 16); heading.setAccessibilityHeading(true); header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (outer > 0 && page.equals("editor") && ready()) {
            android.widget.ImageButton add = NativeWidgetUi.iconButton(this, R.drawable.ic_ms_add, "添加小组件", () -> go("picker")); add.setTag("widget-open-picker"); add.setEnabled(!awaiting); header.addView(add);
            android.widget.Button done = NativeWidgetUi.button(this, "完成", this::done); done.setTag("widget-done"); done.setEnabled(!awaiting); header.addView(done);
        }
        root.addView(header); InputSurface inputRoot = new InputSurface(this); inputRoot.navigation(this::back, () -> { }); inputRoot.shortcuts(id -> { if ("back".equals(id)) back(); else if (outer > 0) android.widget.Toast.makeText(this, "请先完成或取消卡片编辑", android.widget.Toast.LENGTH_SHORT).show(); else { CoverService service = CoverService.instance; if (service != null && getDisplay() != null) service.inputShortcut(id, getDisplay().getDisplayId()); } }); inputRoot.addView(root, new android.widget.FrameLayout.LayoutParams(-1, -1)); setContentView(inputRoot);
        if (outer > 0 && ready() && page.equals("picker")) { picker = new WidgetPickerView(this, bridge, outer, List.copyOf(draft), pickerState, this::choose); root.addView(picker, new LinearLayout.LayoutParams(-1, 0, 1)); return; }
        ScrollView scroll = new ScrollView(this); pageScroll = scroll; scroll.setTag("widget-scroll"); body = SettingsUi.column(this); body.setPadding(SettingsUi.dp(this, 8), SettingsUi.dp(this, 2), SettingsUi.dp(this, 8), SettingsUi.dp(this, 8)); scroll.addView(body); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); restoringScroll = true; if (positions.getBundle(page) != null) SettingsNavigator.restoreViews(scroll, body, positions.getBundle(page)); scroll.post(() -> { if (pageScroll == scroll) restoringScroll = false; });
        if (!notice.isEmpty()) addText(notice, SettingsUi.ACCENT);
        if (outer <= 0) { management(); return; }
        if (!bridge.owns(outer)) { addText("此卡片已移除，请从三星外屏重新添加。", SettingsUi.TEXT); return; }
        if (!bridge.editing(outer, owner)) { addText("这张卡片已在另一处打开编辑。请返回最新的编辑页继续。", SettingsUi.TEXT); addButton("关闭此页", this::finish); return; }
        if (!ready()) { continuation(); return; }
        if (android.os.Build.VERSION.SDK_INT < 31) { addText("组合布局需要Android 12或更新版本。原有整卡组件仍可显示。", SettingsUi.TEXT); return; }
        if (page.equals("discard")) {
            addText("放弃这次布局修改？已保存的卡片保持不变。", SettingsUi.TEXT);
            addButton("继续编辑", () -> go("editor")); addButton("放弃修改", this::finish); return;
        }
        if (page.equals("help")) {
            addText("每张卡片有4×4格。只要有连续空位，就能继续放组件；同一应用的组件也能添加多次。", SettingsUi.TEXT);
            addText("点按组件编辑尺寸和位置，长按后拖动；直接上下滑动可浏览页面。红色表示放不下，松手会恢复。点击完成后，整张卡片才会保存。", SettingsUi.MUTED);
            addText("预览由组件应用提供，实际样式可能随尺寸变化。组合或安全区内显示时，部分自适应组件会使用紧凑样式；旧式滚动列表可能不兼容。布局会随屏幕方向避让实际缺口、状态栏、快捷按钮和手势入口。", SettingsUi.MUTED);
            addText("组件内容可能在锁屏外屏可见，解锁与应用打开由三星系统控制。移除整张卡片请使用三星原生操作。", SettingsUi.MUTED); return;
        }
        if (page.equals("place") && placement != null) { placementPage(); return; }
        if (page.equals("templates")) { templatesPage(); return; }
        if (page.equals("template-confirm") && template != null) {
            addText("使用这份模板替换当前草稿？", SettingsUi.TEXT);
            addText("组件需逐项重新授权和配置。只有最后点击完成才替换已保存卡片，返回放弃可保留原布局。第三方组件内部设置不会迁移。", SettingsUi.MUTED);
            addButton("开始恢复模板", () -> { if (!ready()) { fail("请在所选外屏继续"); return; } bridge.discardDraft(outer); draft.clear(); templateIndex = 0; go("template-restore"); }).setTag("widget-template-start");
            addButton("取消", () -> { template = null; go("templates"); }); return;
        }
        if (page.equals("template-stop")) {
            addText("停止恢复剩余组件？已恢复部分仍是草稿，完成后才保存。", SettingsUi.TEXT);
            addButton("继续恢复", () -> go("template-restore")); addButton("停止并查看草稿", () -> { template = null; go("editor"); }); return;
        }
        if (page.equals("template-restore") && template != null) { restoreTemplatePage(); return; }
        page = "editor"; editor();
    }
    private void addText(String text, int color) { NativeWidgetUi.add(body, NativeWidgetUi.text(this, text, 12, color)); }
    private android.widget.Button addButton(String label, Runnable action) { android.widget.Button b = NativeWidgetUi.button(this, label, action); NativeWidgetUi.add(body, b); return b; }
    private int previewWidth(int width) { android.util.SizeF size = bridge.size(outer); return Math.max(1, Math.min(width, Math.round(SettingsUi.dp(this, 144) * size.getWidth() / Math.max(1, size.getHeight())))); }
    private void editor() {
        addText(draft.size() + "个组件 · 已用" + WidgetGrid.used(draft) + "/16格", SettingsUi.ACCENT);
        WidgetGridEditor grid = new WidgetGridEditor(this, bridge, outer, draft, null, item -> { draft = new ArrayList<>(WidgetGrid.replace(draft, item)); show(); }, this::edit); NativeWidgetUi.preview(body, grid, previewWidth(Math.min(SettingsUi.dp(this, 160), Math.round(NativeWidgetUi.contentWidth(this) * .52f))));
        addText(draft.isEmpty() ? "点右上角＋添加组件" : "点按编辑 · 长按拖动 · 上下滑动浏览", SettingsUi.MUTED);
        if (WidgetGrid.used(draft) == 16) addText("这张卡片已放满。可调整组件，或添加另一张组合卡片。", SettingsUi.MUTED);
        // Equivalent accessible route for cells whose visual preview is small.
        LinearLayout list = SettingsUi.group(this); NativeWidgetUi.add(body, list);
        for (WidgetGrid.Item item : draft) { View row = NativeWidgetUi.settingRow(this, R.drawable.ic_ms_apps, bridge.widgetLabel(item.id()), item.sizeLabel() + " · 第" + (item.y() + 1) + "行第" + (item.x() + 1) + "列", () -> edit(item.id())); row.setTag("widget-edit-" + item.id()); list.addView(row); }
        addButton("布局与兼容说明", () -> go("help"));
        addButton("从配置模板恢复", () -> go("templates")).setTag("widget-open-templates");
    }
    private void templatesPage() {
        addText("选择导入配置中的布局模板；不会复制原设备的授权。", SettingsUi.MUTED);
        List<WidgetTemplates.Card> templates;
        try { templates = WidgetTemplates.saved(new Prefs(this)); } catch (IllegalStateException error) { addText(error.getMessage(), SettingsUi.TEXT); return; }
        if (templates.isEmpty()) { addText("暂无模板，请先在外屏助手的备份与关于中导入配置。", SettingsUi.TEXT); return; }
        LinearLayout group = SettingsUi.group(this); NativeWidgetUi.add(body, group);
        templatePage = Math.max(0, Math.min(templatePage, (templates.size() - 1) / 20));
        for (int i = templatePage * 20; i < Math.min(templates.size(), (templatePage + 1) * 20); i++) {
            WidgetTemplates.Card card = templates.get(i);
            View row = NativeWidgetUi.settingRow(this, R.drawable.ic_ms_apps, "模板" + (i + 1) + " · 原组合卡片" + card.slot(), card.items().size() + "个组件 · 点按后确认替换草稿", () -> { template = card; go("template-confirm"); });
            row.setTag("widget-template-" + i); group.addView(row);
        }
        if (templatePage > 0) addButton("上一页模板", () -> { templatePage--; show(); });
        if ((templatePage + 1) * 20 < templates.size()) addButton("下一页模板", () -> { templatePage++; show(); });
    }
    private void restoreTemplatePage() {
        if (templateIndex >= template.items().size()) { template = null; page = "editor"; notice = "模板已处理，请检查草稿并点击完成保存"; show(); return; }
        WidgetTemplates.Entry entry = template.items().get(templateIndex);
        placement = entry.placement(NEW_ITEM); provider = entry.provider();
        AppWidgetProviderInfo info = chosenInfo();
        addText("待恢复 " + (templateIndex + 1) + "/" + template.items().size(), SettingsUi.ACCENT);
        addText(info == null ? provider + "\n应用未安装或组件不可用" : bridge.providerLabel(info), SettingsUi.TEXT);
        addText(placement.sizeLabel() + " · 第" + (placement.y() + 1) + "行第" + (placement.x() + 1) + "列", SettingsUi.MUTED);
        boolean fits = acceptable();
        if (info != null && !fits) addText("此组件在当前外屏不支持模板尺寸，可跳过后手动添加。", SettingsUi.MUTED);
        android.widget.Button restore = addButton("授权并恢复这个组件", this::bind); restore.setTag("widget-template-bind"); restore.setEnabled(!awaiting && fits);
        addButton("跳过这个组件", () -> { templateIndex++; go("template-restore"); }).setTag("widget-template-skip");
        addText("拒绝授权可重试；跳过会保留空位。已保存卡片在完成前保持不变。", SettingsUi.MUTED);
    }
    private void choose(AppWidgetProviderInfo info) {
        getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(root.getWindowToken(), 0);
        positions.remove("place"); provider = info.provider.flattenToString(); WidgetGrid.Item size = bridge.defaultItem(outer, info); WidgetGrid.Item available = null;
        if (bridge.supports(outer, info, size.width(), size.height())) available = WidgetGrid.find(draft, NEW_ITEM, size.width(), size.height());
        if (available == null) for (int h = 1; h <= 4 && available == null; h++) for (int w = 1; w <= 4 && available == null; w++) if (bridge.supports(outer, info, w, h)) available = WidgetGrid.find(draft, NEW_ITEM, w, h);
        placement = available != null ? available : new WidgetGrid.Item(NEW_ITEM, 0, 0, Math.min(4, size.width()), Math.min(4, size.height())); go("place");
    }
    private void edit(int id) { for (WidgetGrid.Item item : draft) if (item.id() == id) { positions.remove("place"); placement = item; AppWidgetProviderInfo info = bridge.info(id); provider = info == null ? "" : info.provider.flattenToString(); go("place"); return; } }
    private AppWidgetProviderInfo chosenInfo() {
        if (placement != null && placement.id() != NEW_ITEM) return bridge.info(placement.id());
        ComponentName component = ComponentName.unflattenFromString(provider); if (component == null) return null;
        for (AppWidgetProviderInfo info : AppWidgetManager.getInstance(getApplicationContext()).getInstalledProvidersForProfile(android.os.Process.myUserHandle())) if (component.equals(info.provider)) return info; return null;
    }
    private boolean acceptable() {
        AppWidgetProviderInfo info = chosenInfo(); if (placement == null || info == null || !WidgetGrid.fits(draft, placement)) return false;
        // Preserve legacy full-card dimensions unless the user actually changes its size.
        boolean unchangedSize = draft.stream().anyMatch(i -> i.id() == placement.id() && i.width() == placement.width() && i.height() == placement.height());
        return unchangedSize || bridge.supports(outer, info, placement.width(), placement.height());
    }
    private void placementPage() {
        AppWidgetProviderInfo info = chosenInfo();
        addText(placement.sizeLabel() + " · 占" + placement.cells() + "格 · 第" + (placement.y() + 1) + "行第" + (placement.x() + 1) + "列", SettingsUi.ACCENT);
        android.widget.Button[] moves = {NativeWidgetUi.placementButton(this, "←", () -> nudge(-1, 0)), NativeWidgetUi.placementButton(this, "→", () -> nudge(1, 0)), NativeWidgetUi.placementButton(this, "↑", () -> nudge(0, -1)), NativeWidgetUi.placementButton(this, "↓", () -> nudge(0, 1))};
        String[] names = {"向左移动", "向右移动", "向上移动", "向下移动"};
        for (int i = 0; i < moves.length; i++) { moves[i].setTextSize(16); moves[i].setContentDescription(names[i]); moves[i].setTag("widget-nudge-" + i); }
        android.widget.Button confirm = NativeWidgetUi.placementButton(this, placement.id() == NEW_ITEM ? "添加" : "应用", () -> { if (!acceptable()) return; if (placement.id() == NEW_ITEM) bind(); else { draft = new ArrayList<>(WidgetGrid.replace(draft, placement)); go("editor"); } }); confirm.setContentDescription(placement.id() == NEW_ITEM ? "授权并将组件放到这里" : "应用此组件的尺寸与位置"); confirm.setTag("widget-place-confirm"); confirm.setEnabled(!awaiting && acceptable());
        android.widget.Button[] crossButtons = {moves[0], moves[1], moves[2], moves[3], confirm}; int cellWidth = SettingsUi.dp(this, 32), cellHeight = cellWidth, spacing = SettingsUi.dp(this, 2);
        for (var button : crossButtons) { cellWidth = Math.max(cellWidth, (int) Math.ceil(button.getPaint().measureText(button.getText().toString()) + button.getPaddingLeft() + button.getPaddingRight())); var font = button.getPaint().getFontMetricsInt(); cellHeight = Math.max(cellHeight, font.bottom - font.top + button.getPaddingTop() + button.getPaddingBottom()); }
        int width = NativeWidgetUi.contentWidth(this), controlsWidth = 3 * (cellWidth + spacing * 2), gap = SettingsUi.dp(this, 8), leftWidth = Math.min(SettingsUi.dp(this, 144), Math.min(Math.round(width * .48f), width - controlsWidth - gap));
        boolean split = leftWidth >= SettingsUi.dp(this, 80);
        LinearLayout layout = split ? SettingsUi.row(this) : SettingsUi.column(this); layout.setTag("widget-placement-layout"); layout.setGravity(android.view.Gravity.TOP); NativeWidgetUi.add(body, layout);
        LinearLayout preview = SettingsUi.column(this); preview.setTag("widget-placement-preview");
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(split ? leftWidth : -1, -2); if (split) previewParams.setMarginEnd(gap); layout.addView(preview, previewParams);
        WidgetGridEditor grid = new WidgetGridEditor(this, bridge, outer, draft, placement, item -> { placement = item; show(); }, id -> { }); NativeWidgetUi.preview(preview, grid, previewWidth(split ? leftWidth : Math.min(SettingsUi.dp(this, 144), width)));
        NativeWidgetUi.add(preview, NativeWidgetUi.heading(this, bridge.providerLabel(info), 12)); NativeWidgetUi.add(preview, NativeWidgetUi.text(this, "点空位移动 · 长按拖动", 11, SettingsUi.MUTED));
        LinearLayout controls = SettingsUi.column(this); controls.setTag("widget-placement-controls"); layout.addView(controls, new LinearLayout.LayoutParams(split ? controlsWidth : -1, -2));
        android.widget.GridLayout cross = new android.widget.GridLayout(this); cross.setTag("widget-move-cross"); cross.setColumnCount(3); cross.setRowCount(3); int[][] cells = {{1, 0}, {1, 2}, {0, 1}, {2, 1}, {1, 1}};
        for (int i = 0; i < crossButtons.length; i++) { var lp = new android.widget.GridLayout.LayoutParams(android.widget.GridLayout.spec(cells[i][0]), android.widget.GridLayout.spec(cells[i][1])); lp.width = cellWidth; lp.height = cellHeight; lp.setMargins(spacing, spacing, spacing, spacing); cross.addView(crossButtons[i], lp); } NativeWidgetUi.add(controls, cross);
        if (info != null) {
            List<WidgetGrid.Item> sizes = new ArrayList<>(); List<String> labels = new ArrayList<>();
            for (int h = 1; h <= 4; h++) for (int w = 1; w <= 4; w++) if (bridge.supports(outer, info, w, h) || (w == placement.width() && h == placement.height() && placement.id() != NEW_ITEM)) { WidgetGrid.Item size = new WidgetGrid.Item(placement.id(), 0, 0, w, h); sizes.add(size); labels.add(size.sizeLabel()); }
            if (sizes.size() == 1) {
                View fixedSize = NativeWidgetUi.text(this, "固定尺寸 · " + labels.get(0), 11, SettingsUi.MUTED); fixedSize.setTag("widget-fixed-size"); NativeWidgetUi.add(controls, fixedSize);
            } else if (!sizes.isEmpty()) {
                LinearLayout sizeRow = SettingsUi.row(this); sizeRow.addView(NativeWidgetUi.text(this, "尺寸", 11, SettingsUi.MUTED));
                android.widget.Spinner select = new android.widget.Spinner(this); select.setTag("widget-size-picker"); select.setContentDescription("组件尺寸"); select.setMinimumHeight(SettingsUi.dp(this, 32));
                android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels) {
                    @Override public View getView(int position, View recycled, android.view.ViewGroup parent) { android.widget.TextView label = (android.widget.TextView) super.getView(position, recycled, parent); label.setTextSize(12); label.setMinHeight(SettingsUi.dp(NativeWidgetActivity.this, 32)); label.setMinimumHeight(SettingsUi.dp(NativeWidgetActivity.this, 32)); label.setPadding(SettingsUi.dp(NativeWidgetActivity.this, 6), SettingsUi.dp(NativeWidgetActivity.this, 2), SettingsUi.dp(NativeWidgetActivity.this, 6), SettingsUi.dp(NativeWidgetActivity.this, 2)); return label; }
                }; adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); select.setAdapter(adapter);
                int current = 0; for (int i = 0; i < sizes.size(); i++) if (sizes.get(i).width() == placement.width() && sizes.get(i).height() == placement.height()) current = i;
                select.setSelection(current); int initial = current; select.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() { private int previous = initial; public void onNothingSelected(android.widget.AdapterView<?> p) { } public void onItemSelected(android.widget.AdapterView<?> p, View v, int position, long id) { if (previous == position) return; previous = position; WidgetGrid.Item size = sizes.get(position); placement = new WidgetGrid.Item(placement.id(), Math.min(placement.x(), 4 - size.width()), Math.min(placement.y(), 4 - size.height()), size.width(), size.height()); show(); } }); sizeRow.addView(select, new LinearLayout.LayoutParams(0, -2, 1)); NativeWidgetUi.add(controls, sizeRow);
            }
        }
        LinearLayout actions = SettingsUi.row(this);
        if (placement.id() != NEW_ITEM) {
            View remove = NativeWidgetUi.placementIcon(this, R.drawable.ic_ms_delete, "从卡片移除", () -> { int id = placement.id(); draft.removeIf(i -> i.id() == id); bridge.releaseDraft(outer, id); go("editor"); }); remove.setTag("widget-remove"); actions.addView(remove);
            if (info != null && info.configure != null) actions.addView(NativeWidgetUi.placementIcon(this, R.drawable.ic_ms_settings, "组件自己的设置", () -> { if (!ready()) { fail("请点亮所选外屏"); return; } allocated = placement.id(); reconfiguring = true; target = selected().getDisplayId(); configure(); }));
        }
        if (actions.getChildCount() > 0) NativeWidgetUi.add(controls, actions);
        if (!acceptable()) NativeWidgetUi.add(controls, NativeWidgetUi.text(this, "放不下，请调整尺寸或先移除已有组件", 11, 0xffffa8ae));
    }
    private void nudge(int x, int y) { WidgetGrid.Item next = placement.at(placement.x() + x, placement.y() + y); if (WidgetGrid.fits(draft, next)) { placement = next; show(); } else { notice = "这个方向没有足够空位"; show(); } }
    private void continuation() {
        addText("请在已选择且亮起的外屏完成布局、授权和配置。", SettingsUi.TEXT); Display display = selected();
        if (display != null && display.getState() == Display.STATE_ON) addButton("在所选外屏继续", () -> {
            Display current = selected(); if (current == null || current.getDisplayId() != display.getDisplayId() || current.getState() != Display.STATE_ON) { fail("所选外屏已改变，请重试"); return; }
            try { startActivity(new Intent(this, NativeWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_FORWARD_RESULT).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outer).putExtra("editor_state", state()), ActivityOptions.makeBasic().setLaunchDisplayId(current.getDisplayId()).toBundle()); forwarded = true; finish(); } catch (RuntimeException failure) { fail("系统未允许配置页在所选外屏打开"); }
        });
        addButton("目标外屏设置", () -> startActivity(new Intent(this, MainActivity.class).putExtra("section", "calibrate")));
    }
    private void management() {
        if (page.equals("clear-templates")) {
            addText("清除导入的模板？已保存卡片和已授权实例保持不变。需要时可重新导入配置。", SettingsUi.TEXT);
            addButton("清除导入模板", () -> { new Prefs(this).data.edit().remove("widget_templates").apply(); go("editor"); }); addButton("取消", () -> go("editor")); return;
        }
        addText("每张卡片4×4格，可放多个组件", SettingsUi.ACCENT);
        try { int count = WidgetTemplates.saved(new Prefs(this)).size(); if (count > 0) addText("已导入" + count + "份布局模板。添加或打开一张组合卡片后，选择“从配置模板恢复”。", SettingsUi.ACCENT); } catch (IllegalStateException error) { addText(error.getMessage(), SettingsUi.TEXT); }
        addText("在三星外屏长按卡片，进入“添加小组件”，分别添加“组合卡片1”到“组合卡片6”。添加不同编号，就能在三星原生列表中左右切换。", SettingsUi.TEXT);
        LinearLayout list = SettingsUi.group(this); NativeWidgetUi.add(body, list);
        for (int slot = 1; slot <= 6; slot++) {
            int number = slot; List<Integer> ids = new ArrayList<>(); for (int id : bridge.cards()) if (bridge.slot(id) == slot) ids.add(id);
            if (ids.isEmpty()) list.addView(NativeWidgetUi.settingRow(this, R.drawable.ic_ms_apps, "组合卡片" + slot, "尚未添加 · 从三星外屏添加", () -> fail("请在三星外屏“添加小组件”中选择组合卡片" + number)));
            for (int id : ids) { View card = NativeWidgetUi.settingRow(this, R.drawable.ic_ms_apps, "组合卡片" + slot, bridge.label(id), () -> { Display d = selected(); if (d == null || d.getState() != Display.STATE_ON) { fail("请先点亮所选外屏"); return; } try { startActivity(new Intent(this, NativeWidgetActivity.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), ActivityOptions.makeBasic().setLaunchDisplayId(d.getDisplayId()).toBundle()); } catch (RuntimeException failure) { fail("系统未允许打开外屏配置页"); } }); card.setTag("widget-card-" + id); list.addView(card); }
        }
        addButton("刷新卡片", () -> { bridge.refresh(); show(); });
        if (new Prefs(this).data.contains("widget_templates")) addButton("管理导入模板：清除", () -> go("clear-templates"));
        addText("保持外屏助手的无障碍服务启用以接收更新。是否显示全部入口由三星固件决定；移除卡片请使用三星原生操作。", SettingsUi.MUTED);
    }
    private void bind() {
        if (awaiting || !ready() || !acceptable()) { fail("外屏状态或布局已改变，请重试"); return; }
        AppWidgetProviderInfo info = chosenInfo(); target = selected().getDisplayId(); reconfiguring = false;
        try {
            allocated = bridge.allocate(outer); Bundle options = bridge.options(bridge.size(outer, placement.width(), placement.height()));
            if (AppWidgetManager.getInstance(this).bindAppWidgetIdIfAllowed(allocated, info.getProfile(), info.provider, options)) configure();
            else { Intent bind = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, allocated).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider).putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.getProfile()).putExtra(AppWidgetManager.EXTRA_APPWIDGET_OPTIONS, options); awaiting = true; startActivityForResult(bind, BIND, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()); }
        } catch (RuntimeException failure) { abandon(); fail("无法打开系统小组件授权，请重试或更换组件"); }
    }
    private boolean sameTarget() { return ready() && selected().getDisplayId() == target && bridge.owns(outer); }
    private void configure() {
        if (!sameTarget()) { abandon(); fail("外屏状态已改变，已取消本次添加"); return; }
        AppWidgetProviderInfo info = bridge.info(allocated); if (info == null) { abandon(); fail("系统未完成小组件授权"); return; }
        if (info.configure == null) { complete(); return; }
        try { awaiting = true; bridge.host().startAppWidgetConfigureActivityForResult(this, allocated, 0, CONFIGURE, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()); }
        catch (RuntimeException failure) { abandon(); fail("组件配置页无法在外屏打开，请选择其他组件"); }
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent); if (request != BIND && request != CONFIGURE) return; awaiting = false;
        if (result != RESULT_OK || !sameTarget()) { abandon(); fail(result != RESULT_OK ? "已取消本次授权，原布局保持不变" : "外屏状态已改变，已取消本次添加"); return; }
        if (request == BIND) configure(); else complete();
    }
    private void complete() {
        awaiting = false; if (!sameTarget()) { abandon(); fail("外屏状态已改变，请重试"); return; }
        if (!reconfiguring) {
            if (placement == null || !acceptable() || !bridge.stage(outer, allocated)) { abandon(); fail("无法加入草稿，请检查组件或重新添加卡片"); return; }
            draft.add(new WidgetGrid.Item(allocated, placement.x(), placement.y(), placement.width(), placement.height())); bridge.watchDraft(allocated);
        }
        allocated = -1; reconfiguring = false;
        if (template != null && page.equals("template-restore")) { templateIndex++; go("template-restore"); } else go("editor");
    }
    private void done() {
        if (awaiting || !ready() || !bridge.commitLayout(outer, draft)) { fail("暂时无法保存，请检查组件是否可用及所选外屏状态"); return; }
        setResult(RESULT_OK, new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outer)); finish();
    }
    private void abandon() { if (!reconfiguring && allocated > 0 && bridge.pending(outer) == allocated) bridge.cancel(outer); allocated = -1; awaiting = false; reconfiguring = false; }
    private void fail(String text) { notice = text; show(); }
}
