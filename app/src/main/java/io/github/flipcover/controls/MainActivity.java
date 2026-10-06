package io.github.flipcover.controls;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Display;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private Prefs prefs;
    private LinearLayout body, outer, statusPreviewHost, panelPreviewHost;
    private ScrollView scroll;
    private FrameLayout rootLayer;
    private DetailSheet sheet;
    private UpdateSettings updateSettings;
    private TextView statusLabel;
    private StatusBarView statusPreview;
    private boolean panelPreviewExpanded, bindingUI;
    private String editing = "dock", route = "main", category = "builtin", diagnosticText = "";
    private String orientationPackage, orientationTitle, draftKind;
    private ArrayList<String> draft;
    private SettingsOrderList orderList;
    private Bundle orderState = new Bundle();
    private TextView orderCount, orderUndoLabel;
    private View orderUndoBar, orderAdd;
    private Bundle libraryStates = new Bundle();
    private String renderedLibrary;
    private String pendingImportUri;
    private int importRequest;
    private Bundle pageState = new Bundle();
    private final SettingsNavigator navigator = new SettingsNavigator();
    private final List<Runnable> statusUpdates = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable statusRefresh = new Runnable() {
        @Override public void run() { refreshStatus(); main.postDelayed(this, 1200); }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); prefs = new Prefs(this);
        if (android.os.Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, () -> { if (rootLayer instanceof InputSurface input) input.cancelCommand(); else back(); });
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (saved != null) {
            inputMemory.restore(saved.getBundle("input_navigation")); navigator.restore(saved.getBundle("navigation")); panelPreviewExpanded = saved.getBoolean("panel_preview");
            pendingImportUri = saved.getString("import_uri"); draft = saved.getStringArrayList("draft"); draftKind = saved.getString("draft_kind");
            Bundle restoredOrder = saved.getBundle("order_state"); if (restoredOrder != null) orderState = restoredOrder;
            Bundle restoredLibrary = saved.getBundle("library_states"); if (restoredLibrary != null) libraryStates = restoredLibrary;
        }
        Bundle arguments = navigator.current();
        if (arguments != null) applyArguments(arguments);
        else { route = getIntent().getStringExtra("section"); if (route == null) route = "main"; orientationPackage = getIntent().getStringExtra("orientation_package"); orientationTitle = orientationPackage; }
        showRoute(route);
        if (pendingImportUri != null) readImport(android.net.Uri.parse(pendingImportUri));
    }
    @Override protected void onResume() {
        super.onResume(); main.removeCallbacks(statusRefresh); main.post(statusRefresh); CoverApp.bridge(this).connect();
        if (updateSettings != null) updateSettings.refresh();
        if (statusPreview != null && statusPreviewHost != null && statusPreview.getParent() == null) statusPreviewHost.addView(statusPreview, new LinearLayout.LayoutParams(-1, statusPreview.heightPixels()));
    }
    @Override protected void onPause() {
        main.removeCallbacks(statusRefresh);
        if (updateSettings != null) updateSettings.pause();
        if (orderList != null) orderList.cancelDrag();
        if (statusPreview != null && statusPreview.getParent() instanceof android.view.ViewGroup parent) parent.removeView(statusPreview);
        super.onPause();
    }
    @Override protected void onDestroy() { if (updateSettings != null) updateSettings.close(); main.removeCallbacksAndMessages(null); worker.shutdown(); super.onDestroy(); }
    @Override protected void onSaveInstanceState(Bundle out) {
        saveLibraryState(); out.putBundle("input_navigation", inputMemory.state()); out.putBundle("library_states", libraryStates);
        out.putBundle("order_state", orderList == null ? orderState : orderList.saveState());
        out.putString("import_uri", pendingImportUri); out.putBundle("navigation", navigator.save(scroll, body)); out.putBoolean("panel_preview", panelPreviewExpanded); out.putStringArrayList("draft", draft); out.putString("draft_kind", draftKind); super.onSaveInstanceState(out);
    }
    private void applyArguments(Bundle args) {
        route = args.getString("page", "main"); editing = args.getString("editing", "dock"); category = args.getString("category", "builtin");
        orientationPackage = args.getString("package"); orientationTitle = args.getString("title");
    }
    private void showRoute(String page) {
        switch (page) {
            case "dock", "panel", "favorites" -> editor(page, false);
            case "group_dock", "group_appearance", "group_apps", "group_device", "group_backup" -> categoryPage(page);
            case "status" -> statusSettings();
            case "settings_scale" -> settingsScaleSettings();
            case "status_apps" -> statusApplications();
            case "appearance" -> appearanceSettings();
            case "background" -> backgroundSettings();
            case "gestures" -> gestureSettings();
            case "visibility" -> visibilitySettings();
            case "visibility_apps" -> applicationList(false);
            case "layout_backup" -> layoutSettings();
            case "hand" -> handSettings();
            case "orientations" -> applicationList(true);
            case "app_orientation" -> { if (orientationPackage == null) applicationList(true); else appOrientation(); }
            case "hub" -> hubSettings();
            case "input_devices" -> { begin("鼠标与触控板", "input_devices"); body.addView(new InputDevicePanel(this, false, null)); }
            case "hub_pin" -> orderEditor("hub_pin");
            case "order" -> orderEditor(editing);
            case "library" -> library(category, false);
            case "permissions" -> permissions();
            case "display" -> chooseDisplay();
            case "about" -> about();
            case "calibrate" -> calibrate();
            case "apps" -> library("apps", true);
            case "diagnostics" -> diagnostics();
            default -> home();
        }
    }
    private final InputNavigation.Memory inputMemory = new InputNavigation.Memory();
    private void begin(String title, String page) {
        if (updateSettings != null) { updateSettings.close(); updateSettings = null; }
        saveLibraryState(); renderedLibrary = null;
        if (orderList != null) { orderState = orderList.saveState(); orderList.cancelDrag(); orderList = null; }
        if (!page.equals(route)) { pendingImportUri = null; importRequest++; }
        if (rootLayer != null) getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(rootLayer.getWindowToken(), 0);
        Bundle args = new Bundle(); args.putString("page", page); args.putString("editing", editing); args.putString("category", category); args.putString("package", orientationPackage); args.putString("title", orientationTitle);
        pageState = navigator.enter(args, scroll, body); route = page; statusLabel = null; statusPreview = null; statusPreviewHost = null; panelPreviewHost = null; statusUpdates.clear(); sheet = null;
        InputSurface inputRoot = new InputSurface(this); inputRoot.memory(inputMemory, "settings:" + page); inputRoot.navigation(this::back, () -> { });
        inputRoot.shortcuts(id -> {
            Runnable execute = () -> { CoverService service = CoverService.instance; if (id.equals("back")) back(); else if (service == null || getDisplay() == null || !service.inputShortcut(id, getDisplay().getDisplayId())) toast("请在已选择的外屏开启助手后使用快捷栏"); };
            if (route.equals("order") && draft != null && !draft.equals(savedActions(draftKind))) confirm("放弃这次编辑？", "尚未完成的修改不会保存。", "放弃并继续", () -> { draft = null; draftKind = null; popPage(); execute.run(); }); else execute.run();
        }); rootLayer = inputRoot; rootLayer.setBackgroundColor(SettingsUi.BACKGROUND);
        rootLayer.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets safe = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime()); view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets; });
        outer = SettingsUi.column(this); SettingsUi.Viewport viewport = new SettingsUi.Viewport(this, prefs.settingsScale()); viewport.addView(outer, new FrameLayout.LayoutParams(-1, -1)); rootLayer.addView(viewport, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout header = SettingsUi.row(this); header.setMinimumHeight(Ui.dp(this, 52)); header.setPadding(Ui.dp(this, page.equals("main") ? 16 : 2), Ui.dp(this, 2), Ui.dp(this, 8), Ui.dp(this, 2));
        if (!page.equals("main")) { View back = SettingsUi.iconButton(this, R.drawable.ic_ms_arrow_back, "返回", this::back); back.setTag("settings-back"); header.addView(back); }
        TextView heading = SettingsUi.heading(this, title, 20); heading.setAccessibilityHeading(true); heading.setTag("settings-title"); header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (page.equals("main")) header.addView(SettingsUi.iconButton(this, R.drawable.ic_ms_search, "搜索设置", () -> { EditText input = body.findViewWithTag("settings-query"); input.setVisibility(View.VISIBLE); input.requestFocus(); getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT); }));
        outer.addView(header);
        scroll = new ScrollView(this); scroll.setTag("settings-scroll"); scroll.setFillViewport(false);
        body = SettingsUi.column(this); body.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 20)); body.setFocusableInTouchMode(true); scroll.addView(body); body.requestFocus();
        outer.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); setContentView(rootLayer); rootLayer.requestApplyInsets(); SettingsNavigator.restoreViews(scroll, body, pageState);
    }
    // Android 13+ uses the registered OnBackInvokedDispatcher; this is only the API 30–32 fallback.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Override public void onBackPressed() { if (rootLayer instanceof InputSurface input) input.cancelCommand(); else back(); }
    private void back() {
        if (sheet != null) { dismissSheet(); return; }
        if (route.equals("order") && draft != null && !draft.equals(savedActions(draftKind))) {
            confirm("放弃这次编辑？", "尚未完成的顺序和增删不会保存。", "放弃更改", () -> { draft = null; draftKind = null; popPage(); }); return;
        }
        if (route.equals("order")) { draft = null; draftKind = null; }
        popPage();
    }
    private void popPage() {
        pendingImportUri = null; importRequest++;
        Bundle previous = navigator.back();
        if (previous == null) { finish(); return; }
        applyArguments(previous); showRoute(route);
    }
    private LinearLayout group(String tag) { LinearLayout group = SettingsUi.group(this); group.setTag(tag); SettingsUi.add(body, group); return group; }
    private void note(String message) { TextView text = SettingsUi.text(this, message, 14, SettingsUi.MUTED); text.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), 0); SettingsUi.add(body, text); }
    private void link(LinearLayout parent, int icon, String title, String summary, String page) { View row = SettingsUi.settingRow(this, icon, title, summary, () -> showRoute(page)); row.setTag("settings-link-" + page); parent.addView(row); }
    private void related(String title, String page) { SettingsUi.section(body, "相关设置"); LinearLayout group = group("related"); group.setBackground(Ui.background(this, 0xFF0C192F, 20)); link(group, 0, title, "", page); }
    private record Setting(String group, String page, int icon, String title, String keywords, Supplier<String> value) { }
    private List<Setting> settings() {
        return List.of(
            new Setting("快捷栏与手势", "dock", R.drawable.ic_ms_tune, "按钮与布局", "数量 固定键 顺序 阻尼 快捷栏 Home 主页 锁屏 应用中心", () -> prefs.perPage() + " 个 / 页 · " + ActionCatalog.label(this, prefs.pinnedAction())),
            new Setting("快捷栏与手势", "visibility", R.drawable.ic_ms_home, "自动显示与收起", "隐藏 桌面 键盘 应用名单", () -> prefs.autoHideDock() ? "按前台应用自动收起" : "手动切换"),
            new Setting("快捷栏与手势", "gestures", R.drawable.ic_ms_swap_vert, "手势与导航避让", "震动 偏移 白条 摄像头 上滑 通知 旋转 位置 安全区 Home 返回", () -> prefs.gesturesEnabled() ? "白条拖动已开启" : "白条拖动已关闭"),
            new Setting("快捷栏与手势", "hand", R.drawable.ic_ms_accessibility_new, "单手布局", "左手 右手", () -> handLabel()),
            new Setting("状态栏与外观", "settings_scale", R.drawable.ic_ms_accessibility_new, "设置界面缩放", "大小 字号 缩放", () -> prefs.settingsScale() + "%"),
            new Setting("状态栏与外观", "status", R.drawable.ic_ms_wifi, "状态栏", "电量 电池 百分比 大小 时间 通知 网速 左右留白 安全区", () -> prefs.statusScale() + "% · " + (prefs.statusEnabled() ? "常驻已开启" : "常驻已关闭")),
            new Setting("状态栏与外观", "appearance", R.drawable.ic_ms_brightness_6, "悬浮栏风格", "透明 阴影 描边 黑底 黑白 图标", this::appearanceLabel),
            new Setting("状态栏与外观", "background", R.drawable.ic_ms_view_carousel, "面板背景", "模糊 毛玻璃 应用中心", () -> prefs.panelBlur() ? "背景模糊已开启" : "纯黑背景"),
            new Setting("控制中心", "panel", R.drawable.ic_ms_tune, "控制中心", "列数 密度 名称 字号 亮度 音量 媒体 预设 工具 按钮 撤销", () -> prefs.panelColumns() + " 列 · " + new String[]{"紧凑", "标准", "宽松"}[prefs.panelDensity()]),
            new Setting("应用与小组件", "hub", R.drawable.ic_ms_apps, "应用 Dock 与中心", "固定应用 底部 清理 九宫格", () -> "固定应用 " + prefs.hubPins().size() + "/" + AppDockPlacement.LIMIT),
            new Setting("应用与小组件", "favorites", R.drawable.ic_ms_edit, "侧栏常用项", "收藏 工具 排序", () -> prefs.actions("favorites").size() + " 个常用项"),
            new Setting("应用与小组件", "orientations", R.drawable.ic_ms_screen_rotation, "应用方向", "旋转 横屏 竖屏 记忆", () -> prefs.rotationRules().length() + " 个应用规则"),
            new Setting("设备与权限", "display", R.drawable.ic_ms_phone_android, "目标外屏", "显示器 选择 屏幕", this::displaySummary),
            new Setting("设备与权限", "calibrate", R.drawable.ic_ms_display_settings, "屏幕适配", "缺口 角落 长度 厚度 校准", () -> prefs.data.getBoolean("auto_placement", true) ? "根据系统缺口定位" : "手动校准"),
            new Setting("设备与权限", "input_devices", R.drawable.ic_input_devices, "鼠标与触控板", "外接设备 蓝牙 输入 指针 方向 灵敏度", () -> CoverApp.inputs(this).enabled() ? "设备规则已开启" : "系统指针操作"),
            new Setting("设备与权限", "permissions", R.drawable.ic_ms_shield, "权限中心", "授权 无障碍 Shizuku 通知 电话 手电筒", () -> CoverService.instance == null ? "无障碍未连接" : "无障碍已连接"),
            new Setting("设备与权限", "diagnostics", R.drawable.ic_ms_info, "问题诊断", "兼容性 检测 复制", () -> "设备与窗口信息"),
            new Setting("备份与关于", "layout_backup", R.drawable.ic_ms_refresh, "布局备份与恢复", "保存 重置 撤销 默认", () -> prefs.data.contains("layout_backup") ? "已保存布局" : "尚未保存布局"),
            new Setting("备份与关于", "about", R.drawable.ic_ms_info, "关于与更新日志", "版本 隐私 许可", () -> "版本 " + BuildConfig.VERSION_NAME)
        );
    }
    private void home() {
        begin(getString(R.string.app_name), "main");
        LinearLayout master = group("master"); master.setBackground(Ui.background(this, SettingsUi.MASTER, 26));
        Switch enabled = SettingsUi.toggle(master, "开启外屏助手", "已暂停 · 配置保留", prefs.enabled(), checked -> { prefs.data.edit().putBoolean("enabled", checked).apply(); refreshStatus(); }); enabled.setTag("settings-enabled"); enabled.setTrackTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{0xFF4A88FF, 0xFF65656C}));
        statusLabel = SettingsUi.toggleSummary(enabled); refreshStatus();
        EditText search = search("搜索设置", "settings-query"); SettingsUi.add(body, search); search.setVisibility(pageState.getBoolean("settings-query-visible", false) || search.length() > 0 ? View.VISIBLE : View.GONE);
        LinearLayout results = SettingsUi.column(this); results.setTag("settings-results"); body.addView(results);
        LinearLayout footer = SettingsUi.column(this); footer.setTag("settings-footer"); footer.setGravity(Gravity.CENTER_HORIZONTAL); footer.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), 0); body.addView(footer);
        Button quickUpdate = SettingsUi.button(this, getString(R.string.update_check), () -> { about(); updateSettings.check(); }); quickUpdate.setTag("settings-check-update"); quickUpdate.setTextSize(14); quickUpdate.setBackground(Ui.ripple(this, 0, 12)); footer.addView(quickUpdate, new LinearLayout.LayoutParams(-2, -2));
        TextView support = SettingsUi.text(this, getString(R.string.settings_support), 14, SettingsUi.MUTED); support.setTag("settings-support"); support.setTextSize(12); support.setGravity(Gravity.CENTER); support.setPadding(0, Ui.dp(this, 4), 0, Ui.dp(this, 8)); footer.addView(support, new LinearLayout.LayoutParams(-1, -2));
        Runnable filter = () -> {
            results.removeAllViews(); String query = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT); footer.setVisibility(query.isEmpty() ? View.VISIBLE : View.GONE);
            if (query.isEmpty()) {
                LinearLayout group = SettingsUi.group(this); results.addView(group);
                String[][] groups = {{"group_dock", "快捷栏与手势", prefs.perPage() + " 个 / 页 · " + (prefs.autoHideDock() ? "自动收起" : "手动切换")}, {"group_appearance", "状态栏与外观", prefs.statusScale() + "% · " + appearanceLabel()}, {"panel", "控制中心", prefs.panelColumns() + " 列 · 布局与工具"}, {"group_apps", "应用与小组件", "固定应用 " + prefs.hubPins().size() + "/" + AppDockPlacement.LIMIT}, {"group_device", "设备与权限", displaySummary()}, {"input_devices", "鼠标与触控板", "按设备选择方向导航或指针操作"}};
                int[] icons = {R.drawable.ic_ms_tune, R.drawable.ic_ms_brightness_6, R.drawable.ic_ms_view_carousel, R.drawable.ic_ms_apps, R.drawable.ic_ms_shield, R.drawable.ic_input_devices};
                for (int i = 0; i < groups.length; i++) link(group, icons[i], groups[i][1], groups[i][2], groups[i][0]);
                link(group, R.drawable.ic_ms_refresh, "备份与关于", "版本 " + BuildConfig.VERSION_NAME, "group_backup");
            } else {
                LinearLayout group = SettingsUi.group(this); results.addView(group); int count = 0;
                for (Setting item : settings()) if ((item.title + item.keywords + item.group).toLowerCase(java.util.Locale.ROOT).contains(query)) {
                    View row = SettingsUi.settingRow(this, item.icon, item.title, item.value.get() + "\n" + item.group, () -> { showRoute(item.page); if (query.contains("电量") || query.contains("百分比")) main.post(() -> focusSetting("battery_percent")); }); row.setTag("search-" + item.page); group.addView(row); count++;
                }
                if (("原生外屏小组件 三星卡片 桌面组件 组合卡片".contains(query))) { group.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_apps, "原生外屏小组件", "应用与小组件", this::openWidgets)); count++; }
                if (("导入 导出 配置文件 备份".contains(query))) { link(group, R.drawable.ic_ms_upload, "配置文件", "备份与关于", "group_backup"); count++; }
                if (count == 0) group.addView(SettingsUi.settingRow(this, 0, "没有找到设置", "试试“电量”“手势”或“应用”", null));
            }
        }; watch(search, filter); filter.run();
    }
    private void categoryPage(String page) {
        String title = switch (page) { case "group_dock" -> "快捷栏与手势"; case "group_appearance" -> "状态栏与外观"; case "group_apps" -> "应用与小组件"; case "group_device" -> "设备与权限"; default -> "备份与关于"; };
        begin(title, page); LinearLayout group = group("category-options");
        for (Setting item : settings()) if (item.group.equals(title)) { SettingsUi.ValueRow row = new SettingsUi.ValueRow(this, item.icon, item.title, item.value.get(), true, () -> showRoute(item.page)); row.setTag("settings-link-" + item.page); group.addView(row); }
        if (page.equals("group_apps")) group.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_apps, "原生外屏小组件", "多张组合卡片 · 每张4×4自由布局", this::openWidgets));
        if (page.equals("group_backup")) { SettingsUi.section(body, "配置文件"); LinearLayout files = group("configuration-files"); files.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_upload, "导出配置", "保存全部偏好、布局和小组件模板", this::exportConfiguration)); files.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_download, "导入配置", "完整校验后覆盖，保留目标外屏与权限", this::importConfiguration)); }
    }
    private void openWidgets() { startActivity(new Intent(this, NativeWidgetActivity.class)); }
    private EditText search(String hint, String tag) { EditText input = SettingsUi.search(this, hint, pageState.getString(tag, "")); input.setTag(tag); return input; }
    private void watch(EditText input, Runnable update) { input.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { update.run(); } public void afterTextChanged(android.text.Editable text) { } }); }
    private void focusSetting(String key) { View view = body.findViewWithTag(key); if (view != null) { android.graphics.Rect bounds = new android.graphics.Rect(); view.getDrawingRect(bounds); body.offsetDescendantRectToMyCoords(view, bounds); scroll.smoothScrollTo(0, bounds.top); view.requestFocus(); } }
    private Switch toggle(LinearLayout group, String title, String summary, String key, boolean initial) {
        Switch toggle = SettingsUi.toggle(group, title, summary, prefs.data.getBoolean(key, initial), value -> { if (bindingUI) return; prefs.data.edit().putBoolean(key, value).apply(); if (statusPreview != null) statusPreview.updateOptions(); }); toggle.setTag(key); return toggle;
    }
    private void choice(LinearLayout group, String title, String tag, String[] labels, Object[] values, Supplier<Object> selected, Consumer<Object> save) {
        SettingsUi.ValueRow row = SettingsUi.valueRow(this, title, choiceLabel(labels, values, selected.get()), null); row.setTag(tag); row.setFocusable(true); row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0));
        row.setOnClickListener(v -> choose(title, labels, values, selected.get(), value -> { save.accept(value); row.value(choiceLabel(labels, values, selected.get())); })); group.addView(row);
    }
    private String choiceLabel(String[] labels, Object[] values, Object selected) { for (int i = 0; i < values.length; i++) if (values[i].equals(selected)) return labels[i]; return "自定义"; }
    private void choose(String title, String[] labels, Object[] values, Object selected, Consumer<Object> save) {
        DetailSheet modal = openSheet(title);
        for (int i = 0; i < labels.length; i++) { Object value = values[i]; boolean active = value.equals(selected); LinearLayout row = SettingsUi.row(this); row.setMinimumHeight(Ui.dp(this, 56)); row.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            android.widget.RadioButton radio = new android.widget.RadioButton(this); radio.setChecked(active); radio.setClickable(false); radio.setFocusable(false); radio.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); radio.setButtonTintList(android.content.res.ColorStateList.valueOf(SettingsUi.ACCENT)); row.addView(radio);
            TextView label = SettingsUi.text(this, labels[i], 16, active ? SettingsUi.ACCENT : SettingsUi.TEXT); row.addView(label, new LinearLayout.LayoutParams(0, -2, 1)); row.setContentDescription(labels[i]); row.setStateDescription(active ? "已选择" : "未选择"); row.setFocusable(true); row.setTag("settings-choice-" + value); row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0)); row.setOnClickListener(v -> { try { save.accept(value); dismissSheet(); } catch (RuntimeException error) { toast("未保存：" + error.getMessage()); } }); modal.content.addView(row);
        }
    }
    private DetailSheet openSheet(String title) { dismissSheet(); DetailSheet modal = new DetailSheet(this, title, this::dismissSheet); modal.settingsStyle(); sheet = modal; SettingsUi.Viewport viewport = new SettingsUi.Viewport(this, prefs.settingsScale()); viewport.addView(modal, new FrameLayout.LayoutParams(-1, -1)); rootLayer.addView(viewport, new FrameLayout.LayoutParams(-1, -1)); modal.enter(null); return modal; }
    private void dismissSheet() { if (sheet != null) { pendingImportUri = null; rootLayer.removeView(sheet.getParent() instanceof SettingsUi.Viewport viewport ? viewport : sheet); sheet = null; } }
    private void confirm(String title, String message, String action, Runnable commit) { DetailSheet modal = openSheet(title); TextView words = SettingsUi.text(this, message, 14, SettingsUi.MUTED); words.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 14)); modal.content.addView(words); Button okay = SettingsUi.button(this, action, () -> { dismissSheet(); commit.run(); }); okay.setTag("settings-confirm"); modal.content.addView(okay); modal.content.addView(SettingsUi.button(this, "取消", this::dismissSheet)); }
    private void settingsScaleSettings() {
        begin("设置界面缩放", "settings_scale"); LinearLayout options = group("settings-scale-options");
        slider(options, "设置界面大小", "settings_scale", 70, 130, prefs.settingsScale(), "%", value -> { }, value -> { prefs.settingsScale(value); settingsScaleSettings(); });
        Button reset = SettingsUi.button(this, "恢复100%", () -> { prefs.settingsScale(100); settingsScaleSettings(); }); reset.setTag("settings-scale-reset"); options.addView(reset);
        note("100% 为默认大小，松手后应用。组合卡片及其子页面保持原有大小。");
    }
    private void statusSettings() {
        begin("状态栏", "status"); LinearLayout master = group("status-master"); toggle(master, "常驻状态栏", "亮屏且解锁时显示；以下大小与留白也用于控制中心", "status_enabled", BuildConfig.DEFAULTS_STATUS_ENABLED);
        link(master, 0, "指定应用隐藏状态栏", prefs.statusHiddenApps().size() + " 个应用 · 外屏常驻栏", "status_apps");
        LinearLayout appearance = group("status-preview-group"); statusPreviewHost = SettingsUi.column(this); statusPreviewHost.setPadding(0, Ui.dp(this, 16), 0, Ui.dp(this, 16)); appearance.addView(statusPreviewHost);
        statusPreview = new StatusBarView(this, prefs); StatusBarView rendering = statusPreview; statusPreview.setTag("status-preview"); statusPreview.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); statusPreviewHost.addView(statusPreview, new LinearLayout.LayoutParams(-1, statusPreview.heightPixels()));
        slider(appearance, "显示大小", "status_scale", 50, 150, prefs.statusScale(), "%", value -> { rendering.previewScale(value); rendering.getLayoutParams().height = rendering.heightPixels(); rendering.requestLayout(); }, value -> prefs.data.edit().putInt("status_scale", value).apply());
        LinearLayout safe = group("status-safe-area"); slider(safe, "左侧留白", "status_safe_left", 0, 48, prefs.statusSafeLeft(), "dp", value -> rendering.previewSafeArea(value, prefs.statusSafeRight()), value -> prefs.data.edit().putInt("status_safe_left", value).apply());
        slider(safe, "右侧留白", "status_safe_right", 0, 48, prefs.statusSafeRight(), "dp", value -> rendering.previewSafeArea(prefs.statusSafeLeft(), value), value -> prefs.data.edit().putInt("status_safe_right", value).apply());
        note("留白不随大小缩小；常驻栏关闭后仍影响控制中心。");
        LinearLayout contents = group("status-content");
        choice(contents, "显示项目预设", "status-preset", new String[]{"极简", "日常", "全部"}, new Object[]{"minimal", "daily", "full"}, () -> prefs.statusPreset(), value -> { prefs.statusPreset((String) value); bindingUI = true; for (String item : Prefs.STATUS_ITEMS) { Switch toggle = body.findViewWithTag("status_" + item); if (toggle != null) toggle.setChecked(prefs.statusItem(item)); } bindingUI = false; statusPreview.updateOptions(); });
        String[][] items = {{"time", "时间", "使用系统时间格式"}, {"wifi", "Wi-Fi", "当前网络连接"}, {"battery", "电池与充电", "常驻电量百分比在下方单独设置"}, {"notifications", "通知应用图标", "最多 4 个应用，需要通知使用权"}, {"alarm", "闹钟", "存在下次闹钟时显示"}, {"speed", "实时网速", "整个手机的收发流量，不是网络测速"}, {"cellular", "移动网络", "最多双卡，需要电话状态权限"}};
        for (String[] item : items) { Switch control = toggle(contents, item[1], item[2], "status_" + item[0], !item[0].equals("speed") && !item[0].equals("cellular")); control.setOnCheckedChangeListener((button, checked) -> { if (bindingUI) return; prefs.data.edit().putBoolean("status_" + item[0], checked).apply(); statusPreview.updateOptions(); SettingsUi.ValueRow row = body.findViewWithTag("status-preset"); row.value(choiceLabel(new String[]{"极简", "日常", "全部"}, new Object[]{"minimal", "daily", "full"}, prefs.statusPreset())); }); }
        LinearLayout battery = group("battery-options"); toggle(battery, "常驻电量百分比", "控制中心始终显示电量百分比", "battery_percent", BuildConfig.DEFAULTS_STATUS_BATTERY_PERCENT);
        LinearLayout links = group("status-links"); link(links, 0, "权限中心", "通知使用权与移动网络授权", "permissions"); link(links, 0, "悬浮栏风格", appearanceLabel(), "appearance");
    }
    private void slider(LinearLayout parent, String title, String key, int min, int max, int value, String unit, java.util.function.IntConsumer preview, java.util.function.IntConsumer save) {
        LinearLayout block = SettingsUi.column(this); block.setTag("slider-group-" + key); block.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 8)); block.addView(SettingsUi.heading(this, title, 16));
        TextView number = SettingsUi.text(this, value + " " + unit, 14, SettingsUi.ACCENT); number.setPadding(0, Ui.dp(this, 4), 0, 0); block.addView(number);
        SettingsUi.Slider bar = new SettingsUi.Slider(this, min, max, value, next -> { number.setText(next + " " + unit); preview.accept(next); }, save); bar.setTag(key); bar.setContentDescription(title + "，单位 " + unit); block.addView(bar, new LinearLayout.LayoutParams(-1, Ui.dp(this, 48))); parent.addView(block);
    }
    private String appearanceLabel() { return switch (prefs.chromeStyle()) { case "black" -> "纯黑实底"; case "light" -> "透明 · 浅色图标"; case "dark" -> "透明 · 深色图标"; default -> "透明 · 柔和阴影"; }; }
    private String handLabel() { return prefs.leftHand() ? "左手" : prefs.handSide().equals("right") ? "右手" : "默认"; }
    private void appearanceSettings() {
        begin("悬浮栏风格", "appearance"); LinearLayout preview = group("chrome-preview"); preview.addView(new SettingsIllustration(this, SettingsIllustration.CHROME, prefs)); note("本地图形示意 · 明暗背景对比");
        LinearLayout options = group("chrome-options"); String[] values = {"contrast", "black", "light", "dark"}, labels = {"透明 · 柔和阴影", "纯黑实底", "透明 · 浅色图标", "透明 · 深色图标"};
        visualOptions(options, values, labels, prefs.chromeStyle(), value -> { prefs.data.edit().putString("chrome_style", value).apply(); preview.invalidate(); ((SettingsIllustration) preview.getChildAt(0)).invalidate(); }, "chrome-");
        note("柔和阴影使用浅色图标和轻微暗影。白底可选深色图标，深底可选浅色图标；颜色需手动选择，不会根据背景自动切换。"); related("面板背景", "background");
    }
    private void visualOptions(LinearLayout parent, String[] values, String[] labels, String selected, Consumer<String> save, String prefix) {
        List<TextView> indicators = new ArrayList<>();
        for (int i = 0; i < values.length; i++) { String value = values[i]; LinearLayout row = SettingsUi.row(this); row.setMinimumHeight(Ui.dp(this, 56)); row.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
            TextView mark = SettingsUi.text(this, value.equals(selected) ? "●" : "○", 20, SettingsUi.ACCENT); mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); indicators.add(mark); row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(this, 32), -2)); TextView label = SettingsUi.text(this, labels[i], 16, SettingsUi.TEXT); row.addView(label, new LinearLayout.LayoutParams(0, -2, 1)); row.setTag(prefix + value); row.setFocusable(true); row.setSelected(value.equals(selected)); row.setContentDescription(labels[i]); row.setStateDescription(value.equals(selected) ? "已选择" : "未选择"); row.setBackground(Ui.ripple(this, android.graphics.Color.TRANSPARENT, 0));
            row.setOnClickListener(v -> { try { save.accept(value); } catch (RuntimeException error) { toast("未保存：" + error.getMessage()); return; } for (int j = 0; j < values.length; j++) { boolean active = values[j].equals(value); indicators.get(j).setText(active ? "●" : "○"); View sibling = (View) indicators.get(j).getParent(); sibling.setSelected(active); sibling.setStateDescription(active ? "已选择" : "未选择"); } }); parent.addView(row);
        }
    }
    private void backgroundSettings() { begin("面板背景", "background"); toggle(group("background-options"), "背景模糊", "通知、控制中心与多任务使用液态玻璃，应用中心使用背景模糊；关闭后使用基础背景", "panel_blur", BuildConfig.DEFAULTS_CONTROL_CENTER_BLUR); note("通知、控制中心与多任务在打开时取一帧所选外屏背景，仅存内存，关闭即释放；省电或取样不可用时降低效果。"); related("问题诊断", "diagnostics"); }
    private void gestureSettings() {
        begin("手势与导航避让", "gestures");
        LinearLayout illustration = group("gesture-preview"); SettingsIllustration preview = new SettingsIllustration(this, SettingsIllustration.GESTURES, prefs); illustration.addView(preview);
        note("预览随下面的位置选择更新，不会旋转手机。两根白条始终横排：左条打开通知，右条打开控制中心。");
        SettingsUi.section(body, "白条位置 · 按旋转方向分别设置");
        LinearLayout positions = group("gesture-positions");
        for (int rotation = 0; rotation < 4; rotation++) {
            final int angle = rotation;
            if (angle == 2) { positions.addView(SettingsUi.settingRow(this, 0, "180° · 固定左顶部", "向下滑入，面板从上方展开", null)); continue; }
            String[] values = angle == 0 ? new String[]{"top_left", "bottom_right"} : new String[]{"top_left", "top_right"};
            String[] labels = new String[]{SettingsIllustration.entryLabel(values[0]), SettingsIllustration.entryLabel(values[1])};
            choice(positions, angle * 90 + "° · 白条位置", "entry-position-" + angle, labels, values, () -> prefs.entryPosition(angle), value -> { prefs.entryPosition(angle, (String) value); preview.refreshGestures(); });
        }
        note("顶部白条向下滑，通知和控制中心从上方跟手展开、向上收起；右底部白条向上滑，面板从下方展开、向下收起。白条平时隐藏，触摸入口仍保留。");
        LinearLayout gestures = group("gesture-options"); toggle(gestures, "白条滑入", "白条可见时长按可切换快捷按钮，隐藏时长按无效", "gestures_enabled", BuildConfig.DEFAULTS_DOCK_GESTURES); toggle(gestures, "震动反馈", "遵循系统震动设置", "haptics", BuildConfig.DEFAULTS_DOCK_HAPTICS);
        LinearLayout navigation = group("navigation-options"); toggle(navigation, "快捷按钮避让导航区", "仅移动快捷按钮；白条位置由上方各方向选项设置", "avoid_navigation", false); slider(navigation, "快捷按钮额外向内偏移", "navigation_gap", 0, 48, prefs.navigationGap(), "dp", value -> { }, value -> prefs.data.edit().putInt("navigation_gap", value).apply());
        SettingsUi.section(body, "为什么各方向的选项不同？");
        note("三星外屏侧边滑入仍可能触发系统返回，底边上滑也可能回到时钟。现在改用顶部或右底部的横向入口，并结合摄像头缺口避让；可按握持习惯分别选择位置。180° 固定左顶部。");
        note("这是调整本应用的入口，不会关闭系统 Home 或返回。不同系统版本、保护壳和起手位置仍可能影响识别；请从预览所示的白条区域起手。");
        note("面板打开时白条淡入并保持显示，关闭后先淡到 50%，等 3 秒再淡出。快捷按钮滑动只翻页；收起按钮后仍保留白条入口。未报告缺口时使用校准估计，需在本机确认。"); related("按钮与布局", "dock");
    }
    private void handSettings() {
        begin("单手布局", "hand"); LinearLayout preview = group("hand-preview"); addDockPreview(preview); visualOptions(group("hand-options"), new String[]{"auto", "left", "right"}, new String[]{"默认", "左手", "右手"}, prefs.handSide(), value -> { prefs.data.edit().putString("hand_side", value).apply(); preview.removeAllViews(); addDockPreview(preview); }, "hand-");
        note("调整固定键、应用侧栏和工具区的顺序。真实摄像头缺口不会镜像；纵向固定键仍在底部。");
    }
    private void addDockPreview(LinearLayout parent) {
        FrameLayout preview = new FrameLayout(this); preview.setTag("dock-preview"); parent.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 72)));
        preview.post(() -> { if (isDestroyed() || !preview.isAttachedToWindow()) return; int width = Math.min(preview.getWidth(), Ui.dp(this, prefs.perPage() * 44)), height = Ui.dp(this, 44); if (width <= 0) return;
            DockGeometry.Placement placement = new DockGeometry.Placement(new DockGeometry.Box(0, Ui.dp(this, 8), width, Ui.dp(this, 32)), new DockGeometry.Box(0, 0, width, height), new DockGeometry.Box(0, 0, width, height), DockGeometry.BOTTOM, false);
            DockView dock = new DockView(this, prefs, placement, 0, new DockView.Listener() { @Override public void action(String id) { toast("预览：" + ActionCatalog.label(MainActivity.this, id)); } @Override public void configure() { } }); preview.addView(dock, new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        });
    }
    private void visibilitySettings() { begin("自动显示与收起", "visibility"); LinearLayout group = group("visibility-options"); toggle(group, "按前台应用自动收起", "桌面与系统面板隐藏快捷按钮，底部上滑入口保留", "dock_auto_hide", BuildConfig.DEFAULTS_DOCK_AUTO_HIDE); toggle(group, "键盘出现时收起", "仅检测外屏输入法窗口，收起后恢复", "avoid_keyboard", BuildConfig.DEFAULTS_DOCK_AVOID_KEYBOARD); link(group, 0, "指定应用隐藏快捷按钮", prefs.compactApps().size() + " 个应用", "visibility_apps"); note("白条可见时长按临时切换，隐藏时长按无效；换应用后恢复自动规则。只读取外屏窗口元数据，不读取页面文字或输入内容。"); }
    private void applicationList(boolean orientations) {
        begin(orientations ? "应用方向" : "指定应用隐藏快捷按钮", orientations ? "orientations" : "visibility_apps");
        SettingsApplications list = new SettingsApplications(this, prefs, orientations ? "orientations" : "visibility", pageState, entry -> { orientationPackage = entry.packageName(); orientationTitle = entry.label(); appOrientation(); });
        showApplications(list);
    }
    private void statusApplications() {
        begin("隐藏状态栏的应用", "status_apps");
        showApplications(new SettingsApplications(this, prefs, "status", pageState, null));
    }
    private void showApplications(SettingsApplications list) { outer.removeView(scroll); body = list; outer.addView(list, new LinearLayout.LayoutParams(-1, 0, 1)); }
    private void appOrientation() {
        begin("应用方向", "app_orientation"); LinearLayout entity = group("orientation-app"); entity.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_phone_android, orientationTitle == null ? orientationPackage : orientationTitle, "下次从本工具打开时生效", null));
        LinearLayout preview = group("orientation-preview"); SettingsIllustration illustration = new SettingsIllustration(this, SettingsIllustration.ROTATION, prefs); illustration.setOrientation(prefs.appRotation(orientationPackage)); preview.addView(illustration);
        visualOptions(group("orientation-options"), new String[]{"-1", "4", "0", "1", "2", "3"}, new String[]{"沿用当前方向", "跟随系统", "0°", "90°", "180°", "270°"}, String.valueOf(prefs.appRotation(orientationPackage)), value -> { try { int angle = Integer.parseInt(value); prefs.saveAppRotation(orientationPackage, angle); illustration.setOrientation(angle); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage(), e); } }, "orientation-choice-"); note("这里只保存规则，不立即旋转。退出应用后不会自动改回；其他工具或应用自身可能再次改变方向。");
    }
    private void editor(String kind, boolean ignored) {
        editing = kind;
        if (kind.equals("favorites")) { begin("侧栏常用项", "favorites"); link(group("favorite-options"), 0, "编辑常用项", prefs.actions("favorites").size() + " 个 · 应用与工具", "order"); note("侧栏常用项与底部固定应用独立。"); return; }
        begin(kind.equals("dock") ? "按钮与布局" : "控制中心", kind);
        if (kind.equals("dock")) {
            LinearLayout preview = group("dock-preview-group"); addDockPreview(preview);
            choice(preview, "每页按钮", "dock-count", new String[]{"2 个", "3 个", "4 个", "5 个"}, new Object[]{2, 3, 4, 5}, () -> prefs.perPage(), value -> { prefs.data.edit().putInt("per_page", (int) value).apply(); View old = preview.findViewWithTag("dock-preview"); preview.removeView(old); addDockPreview(preview); });
            LinearLayout options = group("dock-options"); options.addView(SettingsUi.valueRow(this, "固定按钮", ActionCatalog.label(this, prefs.pinnedAction()), () -> { editing = "pinned"; library("builtin", false); }));
            choice(options, "Home 按钮效果", "dock-home-action", new String[]{"返回锁屏页面（时钟首页）", "返回应用中心（需 Shizuku）"}, new Object[]{"clock", "cards"}, () -> prefs.homeAction(), value -> prefs.data.edit().putString("home_action", (String) value).apply());
            choice(options, "起步阻尼", "dock-damping", new String[]{"轻", "标准", "强"}, new Object[]{0, 1, 2}, () -> prefs.damping(), value -> prefs.data.edit().putInt("damping", (int) value).apply());
            options.addView(SettingsUi.valueRow(this, "按钮顺序", prefs.scrollingActions().size() + " 个翻页按钮", () -> orderEditor("dock"))); note("每页包含 1 个固定按钮，其余按钮可滑动翻页。"); note("返回锁屏页面：回到三星时钟首页，不主动锁定手机。返回应用中心：需要 Shizuku，返回三星保留的原卡片页；如果已切到其他卡片，会返回那一页。"); related("自动显示与收起", "visibility"); return;
        }
        LinearLayout preview = group("panel-preview-group"); Button toggle = SettingsUi.button(this, panelPreviewExpanded ? "收起布局预览" : "展开布局预览", () -> { panelPreviewExpanded = !panelPreviewExpanded; updatePanelPreview(); }); toggle.setTag("panel-preview-toggle"); preview.addView(toggle); panelPreviewHost = SettingsUi.column(this); preview.addView(panelPreviewHost); updatePanelPreview();
        LinearLayout layout = group("panel-layout-options");
        choice(layout, "布局预设", "panel-preset", new String[]{"标准 · 4 列", "紧凑 · 5 列", "易点 · 3 列"}, new Object[]{"standard", "compact", "easy"}, () -> prefs.panelPreset(), value -> { try { prefs.panelPreset((String) value); updatePanelRows(); } catch (Exception e) { throw new IllegalArgumentException(e.getMessage()); } });
        panelChoice(layout, "每行图标数量", "columns", new String[]{"3 列", "4 列", "5 列"}, new Object[]{3, 4, 5}, () -> prefs.panelColumns());
        panelChoice(layout, "排列疏密", "density", new String[]{"紧凑", "标准", "宽松"}, new Object[]{0, 1, 2}, () -> prefs.panelDensity());
        panelToggle(layout, "显示按钮名称", "隐藏后仍保留无障碍名称与长按详情", "labels", prefs.panelLabels());
        panelChoice(layout, "名称大小", "labelSize", new String[]{"小", "标准", "大"}, new Object[]{0, 1, 2}, () -> prefs.panelLabelSize());
        LinearLayout tools = group("panel-tool-options"); panelChoice(tools, "工具区位置", "toolsPosition", new String[]{"自动", "侧边", "下方"}, new Object[]{"auto", "side", "bottom"}, () -> prefs.panelToolsPosition());
        panelToggle(tools, "外屏亮度", "仅控制所选外屏", "brightness", prefs.panelBrightness()); panelToggle(tools, "媒体音量", "调节整个手机的媒体音量", "volume", prefs.panelVolume()); panelToggle(tools, "媒体卡", "关闭后释放会话订阅", "media", prefs.panelMedia()); panelToggle(tools, "闲置媒体收纳", "没有会话时缩为入口", "mediaIdle", prefs.panelMediaIdle());
        LinearLayout buttons = group("panel-buttons"); buttons.addView(SettingsUi.valueRow(this, "快捷按钮", prefs.actions("panel").size() + " 个 · 排序、添加和移除", () -> orderEditor("panel")));
        LinearLayout restore = group("panel-restore"); View reset = SettingsUi.settingRow(this, 0, "恢复布局默认", "只恢复布局、名称与工具区，可撤销", () -> confirm("恢复控制中心布局？", "不会替换按钮、目标外屏、权限或共享外观。", "恢复默认", () -> { try { prefs.resetPanelSettings(); updatePanelRows(); } catch (Exception e) { toast("恢复失败：" + e.getMessage()); } })); reset.setTag("panel-reset"); restore.addView(reset);
        View undo = SettingsUi.settingRow(this, 0, "撤销上次布局调节", "恢复最近一次调整前的布局", () -> { try { prefs.undoPanelSettings(); updatePanelRows(); } catch (Exception e) { toast("撤销失败：" + e.getMessage()); } }); undo.setTag("panel-undo"); restore.addView(undo);
        LinearLayout links = group("panel-links"); link(links, 0, "状态栏", "时间与通知在左，网络与电池在右", "status"); link(links, 0, "面板背景", "与应用中心共用", "background"); updatePanelRows();
    }
    private void panelChoice(LinearLayout parent, String title, String key, String[] labels, Object[] values, Supplier<Object> selected) { choice(parent, title, "panel-option-" + key, labels, values, selected, value -> changePanelOption(key, value)); statusUpdates.add(() -> { SettingsUi.ValueRow row = body.findViewWithTag("panel-option-" + key); if (row != null) row.value(choiceLabel(labels, values, selected.get())); }); }
    private void panelToggle(LinearLayout parent, String title, String summary, String key, boolean checked) { Switch toggle = SettingsUi.toggle(parent, title, summary, checked, value -> { if (!bindingUI) changePanelOption(key, value); }); toggle.setTag("panel-option-" + key); }
    private void changePanelOption(String key, Object value) { try { prefs.applyPanelSettings(prefs.panelSnapshot().put(key, value)); updatePanelRows(); } catch (Exception error) { throw new IllegalArgumentException(error.getMessage()); } }
    private void updatePanelRows() {
        if (!route.equals("panel")) return; for (Runnable update : List.copyOf(statusUpdates)) update.run();
        SettingsUi.ValueRow preset = body.findViewWithTag("panel-preset"); if (preset != null) preset.value(choiceLabel(new String[]{"标准 · 4 列", "紧凑 · 5 列", "易点 · 3 列"}, new Object[]{"standard", "compact", "easy"}, prefs.panelPreset()));
        bindingUI = true; try { JSONObject snapshot = prefs.panelSnapshot(); for (String key : new String[]{"labels", "brightness", "volume", "media", "mediaIdle"}) { Switch toggle = body.findViewWithTag("panel-option-" + key); if (toggle != null) toggle.setChecked(snapshot.getBoolean(key)); } } catch (Exception ignored) { } finally { bindingUI = false; }
        View labelSize = body.findViewWithTag("panel-option-labelSize"); if (labelSize != null) labelSize.setVisibility(prefs.panelLabels() ? View.VISIBLE : View.GONE);
        Switch idle = body.findViewWithTag("panel-option-mediaIdle"); if (idle != null) ((View) idle.getParent()).setVisibility(prefs.panelMedia() ? View.VISIBLE : View.GONE);
        View undo = body.findViewWithTag("panel-undo"); if (undo != null) { undo.setEnabled(prefs.hasPanelUndo()); undo.setAlpha(prefs.hasPanelUndo() ? 1 : .55f); } updatePanelPreview();
    }
    private void updatePanelPreview() {
        if (panelPreviewHost == null) return; Button button = body.findViewWithTag("panel-preview-toggle"); if (button != null) button.setText(panelPreviewExpanded ? "收起布局预览" : "展开布局预览"); panelPreviewHost.removeAllViews();
        if (panelPreviewExpanded) { TextView info = SettingsUi.text(this, "布局示意 · 操作停用，非实时设备状态", 14, SettingsUi.MUTED); info.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 14), Ui.dp(this, 8)); panelPreviewHost.addView(info); CoverService previewOwner = new CoverService(); previewOwner.screenContext = this; previewOwner.prefs = prefs; View content = new Panels(previewOwner, true).build("controls"); content.setTag("panel-settings-preview"); content.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); panelPreviewHost.addView(content); }
    }
    private List<String> savedActions(String kind) { return kind.equals("hub_pin") ? prefs.hubPins() : kind.equals("dock") ? prefs.scrollingActions() : prefs.actions(kind); }
    private void orderEditor(String kind) {
        editing = kind; boolean fresh = draft == null || !kind.equals(draftKind);
        if (fresh) { draft = new ArrayList<>(savedActions(kind)); draftKind = kind; }
        begin(getResources().getConfiguration().fontScale > 1.3f ? "编辑" : kind.equals("hub_pin") ? "编辑固定应用" : "编辑快捷项", "order");
        if (fresh) orderState = new Bundle();
        Button done = SettingsUi.button(this, "完成", () -> { try { if (draftKind.equals("hub_pin")) prefs.saveHubPins(draft); else prefs.saveActions(draftKind, draft); draft = null; draftKind = null; popPage(); } catch (RuntimeException e) { toast("未保存：" + e.getMessage()); } }); done.setTag("order-done"); done.setBackground(Ui.ripple(this, 0, 16)); ((LinearLayout) outer.getChildAt(0)).addView(done);
        LinearLayout actions = SettingsUi.row(this); actions.setTag("order-actions"); actions.setPadding(Ui.dp(this, 14), 0, 0, 0);
        orderCount = SettingsUi.text(this, "", 14, SettingsUi.MUTED); orderCount.setTag("order-count"); actions.addView(orderCount, new LinearLayout.LayoutParams(0, -2, 1));
        orderAdd = SettingsUi.button(this, "+ 添加", () -> library(kind.equals("hub_pin") ? "apps" : "builtin", false)); orderAdd.setTag("order-add"); actions.addView(orderAdd); body.addView(actions);
        actions.setPadding(Ui.dp(this, 14), 0, 0, Ui.dp(this, 8));
        orderList = new SettingsOrderList(this, draft, scroll, orderState, this::orderOptions, this::updateOrderState); body.addView(orderList);
        LinearLayout undo = SettingsUi.row(this); undo.setTag("order-undo-bar"); undo.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 8), Ui.dp(this, 4)); undo.setBackgroundColor(SettingsUi.SURFACE);
        orderUndoLabel = SettingsUi.text(this, "", 14, SettingsUi.TEXT); orderUndoLabel.setSingleLine(); orderUndoLabel.setEllipsize(android.text.TextUtils.TruncateAt.END); undo.addView(orderUndoLabel, new LinearLayout.LayoutParams(0, -2, 1));
        Button undoButton = SettingsUi.button(this, "撤销", () -> orderList.undo()); undoButton.setTag("order-undo"); undo.addView(undoButton); orderUndoBar = undo; outer.addView(undo); updateOrderState();
    }
    private void updateOrderState() {
        int limit = "hub_pin".equals(draftKind) ? AppDockPlacement.LIMIT : 30;
        orderCount.setText(draft.size() + " / " + limit + (getResources().getConfiguration().fontScale > 1.3f ? "" : " · 长按拖动")); orderAdd.setEnabled(draft.size() < limit);
        orderAdd.setContentDescription(draft.size() < limit ? "添加快捷项" : "已达到 " + limit + " 项上限");
        boolean undo = orderList.canUndo() && draft.size() < limit; orderUndoBar.setVisibility(undo ? View.VISIBLE : View.GONE);
        orderUndoLabel.setText(undo ? "已移除 " + orderList.removedLabel() : "");
    }
    private void orderOptions(String id) {
        int position = draft.indexOf(id); if (position < 0) return;
        ArrayList<String> labels = new ArrayList<>(); ArrayList<Object> positions = new ArrayList<>();
        if (position > 0) { labels.add("上移"); positions.add(position - 1); if (position > 1) { labels.add("移到顶部"); positions.add(0); } }
        if (position + 1 < draft.size()) { labels.add("下移"); positions.add(position + 1); if (position + 2 < draft.size()) { labels.add("移到底部"); positions.add(draft.size() - 1); } }
        labels.add("移除"); positions.add(-1);
        DetailSheet modal = openSheet(ActionCatalog.label(this, id));
        for (int i = 0; i < labels.size(); i++) { int target = (int) positions.get(i); View action = SettingsUi.settingRow(this, target < 0 ? R.drawable.ic_ms_close : target < position ? R.drawable.ic_ms_keyboard_arrow_up : R.drawable.ic_ms_keyboard_arrow_down, labels.get(i), "", () -> { dismissSheet(); if (target < 0) orderList.remove(id); else orderList.move(id, target); }); action.setTag("order-position-" + target); modal.content.addView(action); }
    }
    private void hubSettings() {
        begin("应用 Dock 与中心", "hub"); LinearLayout group = group("hub-options"); group.addView(SettingsUi.valueRow(this, "底部固定应用", prefs.hubPins().size() + "/" + AppDockPlacement.LIMIT + " 个", () -> orderEditor("hub_pin"))); link(group, 0, "侧栏常用项", prefs.actions("favorites").size() + " 个 · 与固定应用独立", "favorites"); group.addView(SettingsUi.settingRow(this, 0, "固定键使用应用中心", ActionCatalog.label(this, prefs.pinnedAction()), () -> { prefs.pin("app_hub"); hubSettings(); }));
        if (!prefs.hubPins().isEmpty()) group.addView(SettingsUi.settingRow(this, 0, "移除全部固定应用", "不会移除侧栏常用项", () -> confirm("移除全部固定应用？", "只清空底部固定列表，不卸载应用。", "移除", () -> { prefs.saveHubPins(List.of()); hubSettings(); })));
        note("应用 Dock 临时呼出底栏，九宫格原位展开全部应用。打开应用或确认恢复任务后关闭；失败保留界面。清理保留可见、固定与锁定任务。"); related("应用方向", "orientations");
    }
    private void library(String kind, boolean launchOnly) {
        category = kind; begin(launchOnly ? "打开应用" : editing.equals("pinned") ? "选择固定按钮" : "添加快捷项", launchOnly ? "apps" : "library");
        if (!launchOnly) {
            renderedLibrary = kind; Bundle remembered = libraryStates.getBundle(kind); pageState = remembered == null ? new Bundle() : new Bundle(remembered);
            if (!editing.equals("hub_pin")) libraryTabs(kind);
            SettingsNavigator.restoreViews(scroll, body, pageState);
        }
        if (kind.equals("apps")) {
            SettingsApplications list = new SettingsApplications(this, prefs, "picker", pageState, entry -> { if (launchOnly) launchApplication(entry.id()); else addSelected(entry.id()); });
            if (!launchOnly) list.pickerSelection(this::librarySelected); showApplications(list); return;
        }
        if (kind.equals("tiles")) note("应用磁贴为实验功能；添加到本工具不会自动注册或授权。");
        LinearLayout list = group("library-items");
        worker.execute(() -> { List<String> ids = kind.equals("tiles") ? ActionCatalog.applicationTiles(this) : ActionCatalog.BUILT_INS.stream().map(ActionCatalog.Action::id).collect(java.util.stream.Collectors.toList());
            main.post(() -> { if (isDestroyed() || !list.isAttachedToWindow()) return;
                for (String id : ids) {
                    LinearLayout row = SettingsUi.shortcutRow(this, id, ActionCatalog.label(this, id), "", ActionCatalog.icon(this, id), librarySelected(id), () -> addSelected(id)); list.addView(row);
                    if (id.startsWith("tile:")) { Button register = SettingsUi.button(this, "注册到系统快捷设置", () -> CoverApp.bridge(this).run("tile_add", -1, 0, ActionCatalog.component(id).flattenToString(), result -> toast(result.message))); register.setContentDescription("将“" + ActionCatalog.label(this, id) + "”注册到系统快捷设置"); LinearLayout.LayoutParams entry = new LinearLayout.LayoutParams(-1, -2); entry.setMarginStart(Ui.dp(this, 64)); list.addView(register, entry); }
                } if (ids.isEmpty()) list.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_apps, "没有可添加的应用磁贴", "安装提供快捷设置磁贴的应用后，可在这里选择", null));
                SettingsNavigator.restoreViews(scroll, body, pageState);
            });
        });
    }
    private boolean librarySelected(String id) { return editing.equals("pinned") ? prefs.pinnedAction().equals(id) : draft != null ? draft.contains(id) || editing.equals("dock") && prefs.pinnedAction().equals(id) : savedActions(editing).contains(id); }
    private void saveLibraryState() { if (renderedLibrary != null) libraryStates.putBundle(renderedLibrary, SettingsNavigator.capture(scroll, body)); }
    private void libraryTabs(String selected) {
        outer.addView(SettingsUi.sourceTabs(this, selected, id -> library(id, false)), 1, new LinearLayout.LayoutParams(-1, -2));
    }
    private void addSelected(String id) {
        if (editing.equals("pinned")) { prefs.pin(id); popPage(); return; }
        if (draft == null) { draftKind = editing; draft = new ArrayList<>(savedActions(editing)); }
        int limit = editing.equals("hub_pin") ? AppDockPlacement.LIMIT : 30;
        if (draft.contains(id) || editing.equals("dock") && prefs.pinnedAction().equals(id)) { toast("已在列表中"); return; }
        if (draft.size() >= limit) { toast("最多 " + limit + " 个"); return; }
        draft.add(id); toast("已添加到编辑列表，点“完成”保存"); popPage();
    }
    private void launchApplication(String id) {
        Display display = Displays.selected(this, prefs); if (display == null) { toast("请先选择外屏"); return; }
        if (prefs.enabled() && CoverService.instance != null && CoverService.instance.display != null && CoverService.instance.display.getDisplayId() == display.getDisplayId()) { CoverService.instance.launchApp(id); return; }
        if (prefs.appRotation(ActionCatalog.component(id).getPackageName()) >= 0) toast("外屏助手未运行，将沿用当前方向打开");
        try { startActivity(AppLauncher.applicationIntent(ActionCatalog.component(id)), ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle()); }
        catch (RuntimeException e) { toast("应用不能在此外屏启动：" + e.getClass().getSimpleName()); }
    }
    private void permissions() {
        begin("权限中心", "permissions"); LinearLayout group = group("permissions-options");
        permission(group, "无障碍服务", () -> CoverService.instance == null ? "未连接 · 导航与外屏窗口识别" : "已连接", () -> open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        permission(group, "系统控制（Shizuku）", () -> CoverApp.bridge(this).status(), () -> { try { CoverApp.bridge(this).requestPermission(); } catch (RuntimeException e) { toast("请先启动 Shizuku"); } });
        permission(group, "安卓提示（Toast）", () -> getSystemService(android.app.NotificationManager.class).areNotificationsEnabled() ? "已允许 · 系统原生提示" : "未允许 · 通知权限关闭会拦截后台提示", () -> open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName())));
        permission(group, "通知使用权", () -> CoverNotifications.ready() ? "已连接" : "未授权 · 通知面板与应用图标", () -> { Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new ComponentName(this, CoverNotifications.class).flattenToString()); if (!open(detail)) open(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); });
        permission(group, "手电筒", () -> checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED ? "已授权" : "可选 · 相机权限仅用于闪光灯", () -> requestPermissions(new String[]{Manifest.permission.CAMERA}, 101));
        permission(group, "移动网络信息", () -> checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED ? "已授权" : "可选 · 显示信号，不读取号码", () -> requestPermissions(new String[]{Manifest.permission.READ_PHONE_STATE}, 102));
        note("每项权限由你决定。缺少可选权限不影响其他功能。无障碍被限制时，可在系统应用信息的更多菜单中允许受限设置。");
    }
    private void permission(LinearLayout group, String title, Supplier<String> status, Runnable action) { SettingsUi.ValueRow row = SettingsUi.valueRow(this, title, status.get(), action); group.addView(row); statusUpdates.add(() -> row.value(status.get())); }
    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) { super.onRequestPermissionsResult(request, permissions, grants); if (CoverService.instance != null) CoverService.instance.permissionsChanged(); refreshStatus(); }
    private void refreshStatus() {
        if (statusLabel != null) { String state = !prefs.enabled() ? "已暂停 · 配置保留" : CoverService.instance == null ? "待授权：无障碍服务" : Displays.selected(this, prefs) == null ? "待选择目标外屏" : CoverService.status; if (!state.contentEquals(statusLabel.getText())) statusLabel.setText(state); View enabled = body.findViewWithTag("settings-enabled"); if (enabled != null) enabled.setContentDescription("开启外屏助手，" + state); }
        if (route.equals("permissions")) for (Runnable update : List.copyOf(statusUpdates)) update.run();
    }
    private String displaySummary() { Display display = Displays.selected(this, prefs); return display == null ? "尚未选择可用外屏" : display.getName() + (display.getState() == Display.STATE_ON ? " · 已亮起" : " · 未亮起"); }
    private void chooseDisplay() {
        begin("目标外屏", "display"); LinearLayout group = group("display-options");
        group.addView(SettingsUi.valueRow(this, "自动识别外屏", prefs.displayId() == -1 ? "已选择" : "只识别可靠的副屏", () -> { prefs.data.edit().putInt("display", -1).apply(); chooseDisplay(); }));
        for (Display display : Displays.all(this)) { boolean mainDisplay = display.getDisplayId() == Display.DEFAULT_DISPLAY; View row = SettingsUi.settingRow(this, R.drawable.ic_ms_phone_android, display.getName(), mainDisplay ? "主屏 · 不作为目标外屏" : (prefs.displayId() == display.getDisplayId() ? "已选择 · " : "") + Displays.describe(display), mainDisplay ? null : () -> { prefs.data.edit().putInt("display", display.getDisplayId()).apply(); chooseDisplay(); }); group.addView(row); }
        note("仅操作明确选择或可靠识别的外屏。目标失联时保留选择，不回退到主屏。");
    }
    private void calibrate() {
        begin("屏幕适配", "calibrate"); Display display = Displays.selected(this, prefs);
        if (display == null) { link(group("calibrate-unavailable"), 0, "先选择外屏", "未发现可用的目标显示器", "display"); return; }
        LinearLayout preview = group("calibration-preview"); preview.addView(new SettingsIllustration(this, SettingsIllustration.SAFE_AREA, prefs)); note(display.getCutout() == null ? "系统尚未报告缺口。示意为估计，需确认触摸位置在发光区域内。" : "已读取系统缺口；边界与触摸范围仍需本机确认。");
        LinearLayout options = group("calibration-options"); LinearLayout manual = SettingsUi.group(this); manual.setTag("calibration-manual"); SettingsUi.add(body, manual);
        Switch automatic = SettingsUi.toggle(options, "根据系统缺口定位", "每个旋转方向使用对应的安全区域", prefs.data.getBoolean("auto_placement", true), enabled -> { prefs.data.edit().putBoolean("auto_placement", enabled).apply(); manual.setVisibility(enabled ? View.GONE : View.VISIBLE); }); automatic.setTag("auto_placement"); manual.setVisibility(automatic.isChecked() ? View.GONE : View.VISIBLE);
        choice(manual, "当前方向的停靠角落", "calibration-corner", new String[]{"左上", "右上", "右下", "左下"}, new Object[]{0, 1, 2, 3}, () -> prefs.corner(display.getRotation()), value -> { if (Displays.selected(this, prefs) == null || Displays.selected(this, prefs).getDisplayId() != display.getDisplayId()) throw new IllegalStateException("外屏已改变，请重新打开设置"); prefs.data.edit().putInt("corner_" + display.getRotation(), (int) value).apply(); });
        slider(manual, "快捷栏长度", "dock_width", 25, 70, Math.round(prefs.widthRatio() * 100), "%", value -> { }, value -> prefs.data.edit().putFloat("dock_width", value / 100f).apply());
        slider(manual, "可见区域厚度", "dock_height", 5, 22, Math.round(prefs.heightRatio() * 100), "%", value -> { }, value -> prefs.data.edit().putFloat("dock_height", value / 100f).apply());
        group("calibrate-stop").addView(SettingsUi.settingRow(this, 0, "暂停快捷栏", "保留当前布局配置", () -> { prefs.data.edit().putBoolean("enabled", false).apply(); toast("已暂停快捷栏"); }));
    }
    private void layoutSettings() {
        begin("布局备份与恢复", "layout_backup"); long saved = prefs.data.getLong("layout_saved_at", 0); note(saved == 0 ? "尚未保存布局" : "已保存：" + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(new java.util.Date(saved)));
        LinearLayout backup = group("layout-actions"); backup.addView(SettingsUi.settingRow(this, 0, "保存当前布局", saved == 0 ? "保存按钮、面板、侧栏、状态栏与位置" : "替换已有本机备份", () -> { Runnable save = () -> { try { prefs.saveLayout(); layoutSettings(); toast("布局已保存"); } catch (Exception e) { toast("保存失败：" + e.getMessage()); } }; if (saved == 0) save.run(); else confirm("替换布局备份？", "当前布局将覆盖之前保存的本机备份。", "替换", save); }));
        View restore = SettingsUi.settingRow(this, 0, "恢复已保存布局", "保留外屏选择、权限和系统显示设置", () -> confirm("恢复已保存布局？", "覆盖当前布局，完成后可撤销。", "恢复", () -> restoreLayout(false))); restore.setEnabled(prefs.data.contains("layout_backup")); backup.addView(restore);
        View undo = SettingsUi.settingRow(this, 0, "撤销上次恢复", "返回恢复前的布局", () -> restoreLayout(true)); undo.setEnabled(prefs.data.contains("layout_undo")); backup.addView(undo);
        group("layout-reset").addView(SettingsUi.settingRow(this, 0, "恢复默认布局", "仅重置本工具布局，可撤销", () -> confirm("恢复默认布局？", "不会改变目标外屏、权限或系统分辨率。", "恢复默认", () -> { try { prefs.resetLayout(); layoutSettings(); } catch (Exception e) { toast("恢复失败：" + e.getMessage()); } })));
        related("屏幕适配", "calibrate");
    }
    private void restoreLayout(boolean undo) { try { prefs.restoreLayout(undo); layoutSettings(); toast(undo ? "已撤销恢复" : "已恢复，可撤销"); } catch (Exception e) { toast("恢复失败：" + e.getMessage()); } }
    private void about() {
        begin("关于与更新日志", "about"); LinearLayout info = group("about-info"); info.addView(SettingsUi.settingRow(this, R.drawable.ic_launcher, getString(R.string.app_name), "版本 " + BuildConfig.VERSION_NAME + "（构建 " + BuildConfig.VERSION_CODE + "）\n" + (BuildConfig.DEBUG ? "验证版" : "正式版") + " · Android 11 及以上", null));
        updateSettings = new UpdateSettings(this, this::back); SettingsUi.add(body, updateSettings.view);
        SettingsUi.section(body, "更新日志"); LinearLayout changelog = group("about-changelog"); try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(getAssets().open("changelog.txt"), StandardCharsets.UTF_8))) { TextView log = SettingsUi.text(this, reader.lines().collect(java.util.stream.Collectors.joining("\n")), 14, SettingsUi.TEXT); log.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14)); changelog.addView(log); } catch (java.io.IOException e) { note("更新日志暂不可用"); }
        note("通知仅在内存中处理；仅手动更新检查和下载使用网络，不上传通知、配置或设备信息。Shizuku 使用 MIT 许可，Material Symbols 与 AndroidX 使用 Apache 2.0 许可，许可原文随应用打包。"); related("问题诊断", "diagnostics");
    }
    private void diagnostics() {
        begin("问题诊断", "diagnostics"); Display selected = Displays.selected(this, prefs); LinearLayout summary = group("diagnostic-summary"); summary.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_info, "当前运行状态", CoverService.status, null)); summary.addView(SettingsUi.settingRow(this, R.drawable.ic_ms_phone_android, "目标外屏", displaySummary(), null));
        StringBuilder report = new StringBuilder(getString(R.string.app_name)).append(' ').append(BuildConfig.VERSION_NAME).append(" / ").append(BuildConfig.VERSION_CODE).append("\nAndroid ").append(android.os.Build.VERSION.RELEASE).append(" / API ").append(android.os.Build.VERSION.SDK_INT).append("\n设备：").append(android.os.Build.MANUFACTURER).append(' ').append(android.os.Build.MODEL).append("\n系统版本：").append(android.os.Build.DISPLAY).append('\n');
        report.append("快捷栏状态：").append(CoverService.status).append("\n显示开关：").append(prefs.enabled()).append("\n系统报告锁屏：").append(getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()).append('\n');
        report.append("面板模糊开关：").append(prefs.panelBlur()).append("\n系统允许跨窗口模糊：").append(android.os.Build.VERSION.SDK_INT >= 31 && getSystemService(android.view.WindowManager.class).isCrossWindowBlurEnabled()).append("\n省电模式：").append(getSystemService(android.os.PowerManager.class).isPowerSaveMode()).append('\n');
        for (Display display : Displays.all(this)) report.append(Displays.describe(display)).append("\n状态：").append(display.getState()).append("\n缺口：").append(display.getCutout()).append('\n');
        CoverService service = CoverService.instance; if (service != null) { report.append(service.windowDiagnostics()).append(service.blurDiagnostics()); if (service.placement != null) report.append("可见区：").append(service.placement.visual()).append("\n触控区：").append(service.placement.touch()).append("\n缺口定位：").append(service.placement.measured()); } report.append(CoverService.lifecycleDiagnostics());
        diagnosticText = report.toString(); TextView details = SettingsUi.text(this, diagnosticText + CoverApp.launcher(this).diagnostics.report(), 14, SettingsUi.MUTED); details.setTextIsSelectable(true); details.setVisibility(View.GONE);
        LinearLayout actions = group("diagnostic-actions"); actions.addView(SettingsUi.settingRow(this, 0, "显示原始详情", "显示器、窗口与兼容信息", () -> details.setVisibility(details.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        View probe = SettingsUi.settingRow(this, 0, "通过 Shizuku 检测", "只读检测，不改变设备设置", null); probe.setOnClickListener(v -> { v.setEnabled(false); CoverApp.bridge(this).run("diagnostics", selected == null ? -1 : selected.getDisplayId(), 0, "", result -> { if (isDestroyed() || !v.isAttachedToWindow()) return; v.setEnabled(true); diagnosticText = report + "\n" + result.message + "\n" + result.output; details.setText(diagnosticText + CoverApp.launcher(this).diagnostics.report()); details.setVisibility(View.VISIBLE); }); }); actions.addView(probe);
        actions.addView(SettingsUi.settingRow(this, 0, "复制检测结果", "含本次运行的启动诊断，不含通知正文", () -> { getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("外屏检测", diagnosticText + CoverApp.launcher(this).diagnostics.report())); toast("已复制"); })); SettingsUi.add(body, details);
    }
    private void exportConfiguration() { open(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "flip-cover-config.json"), 201); }
    private void importConfiguration() { open(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE), 202); }
    JSONObject exportConfigurationData() throws org.json.JSONException {
        JSONObject config = new JSONObject().put("version", 15).put("rotations", prefs.rotationRules()).put("layout", prefs.layoutSnapshot()).put("avoidKeyboard", prefs.avoidKeyboard()).put("haptics", prefs.haptics()).put("favorites", new JSONArray(prefs.actions("favorites"))).put("hubPinned", prefs.hubPinned()).put("statusEnabled", prefs.statusEnabled()).put("gestures", prefs.gesturesEnabled()).put("pinned", prefs.pinnedAction()).put("blur", prefs.panelBlur()).put("dock", new JSONArray(prefs.actions("dock"))).put("panel", new JSONArray(prefs.actions("panel"))).put("perPage", prefs.perPage()).put("damping", prefs.damping());
        JSONObject statusItems = new JSONObject();
        for (String item : new String[]{"time", "wifi", "battery", "notifications", "alarm", "speed", "cellular"}) statusItems.put(item, prefs.statusItem(item));
        config.put("statusItems", statusItems);
        config.put("dockAutoHide", prefs.autoHideDock()).put("compactApps", new JSONArray(prefs.compactApps()));
        config.put("widgetTemplates", CoverApp.widgets(this).exportTemplates()).put("inputEnabled", CoverApp.inputs(this).enabled());
        return config;
    }
    void applyConfigurationData(JSONObject config) throws org.json.JSONException {
        for (String key : new String[]{"version", "perPage", "damping"}) {
            Object raw = config.get(key); if (!(raw instanceof Number value) || value.doubleValue() != value.intValue()) throw new IllegalArgumentException("配置数字无效：" + key);
        }
        for (String key : new String[]{"avoidKeyboard", "haptics", "statusEnabled", "gestures", "blur", "dockAutoHide", "inputEnabled"}) if (config.has(key) && !(config.get(key) instanceof Boolean)) throw new IllegalArgumentException("配置开关值无效：" + key);
        for (String key : new String[]{"favorites", "compactApps"}) if (config.has(key) && !(config.get(key) instanceof JSONArray)) throw new IllegalArgumentException("配置列表无效：" + key);
        if (config.has("statusItems")) {
            JSONObject items = config.getJSONObject("statusItems");
            for (String item : Prefs.STATUS_ITEMS) if (items.has(item) && !(items.get(item) instanceof Boolean)) throw new IllegalArgumentException("状态栏开关值无效");
        }
        if (config.getInt("version") < 1 || config.getInt("version") > 15) throw new IllegalArgumentException("不支持的配置版本");
        java.util.Map<String, List<String>> parsed = new java.util.HashMap<>();
        for (String key : new String[]{"dock", "panel"}) {
            JSONArray array = config.getJSONArray(key); List<String> ids = new ArrayList<>();
            if (array.length() > 30) throw new IllegalArgumentException("快捷项过多");
            for (int i = 0; i < array.length(); i++) { String id = array.getString(i); if (!ActionCatalog.valid(id)) throw new IllegalArgumentException("无效快捷项"); if (!ids.contains(id)) ids.add(id); }
            parsed.put(key, ids);
        }
        JSONArray favorites = config.optJSONArray("favorites"); List<String> savedFavorites = new ArrayList<>();
        if (favorites != null) { if (favorites.length() > 30) throw new IllegalArgumentException("常用项过多"); for (int i = 0; i < favorites.length(); i++) { String id = favorites.getString(i); if (!ActionCatalog.valid(id)) throw new IllegalArgumentException("无效常用项"); if (!savedFavorites.contains(id)) savedFavorites.add(id); } }
        String hubPinned = config.optString("hubPinned", "");
        if (!hubPinned.isEmpty() && (!hubPinned.startsWith("app:") || !ActionCatalog.valid(hubPinned))) throw new IllegalArgumentException("无效固定应用");
        String pinned = config.optString("pinned", "app_hub");
        if (!ActionCatalog.valid(pinned)) throw new IllegalArgumentException("无效固定按钮");
        int count = config.getInt("perPage"), damping = config.getInt("damping");
        if (count < 2 || count > 5 || damping < 0 || damping > 2) throw new IllegalArgumentException("无效设置");
        android.content.SharedPreferences.Editor update = prefs.data.edit().putString("hub_pinned", hubPinned).putBoolean("status_enabled", config.optBoolean("statusEnabled", true)).putBoolean("gestures_enabled", config.optBoolean("gestures", true));
        org.json.JSONArray legacyPins = new org.json.JSONArray(); if (!hubPinned.isEmpty()) legacyPins.put(hubPinned); prefs.prepareHubPins(legacyPins, update);
        JSONObject statusItems = config.optJSONObject("statusItems");
        if (statusItems != null) for (String item : new String[]{"time", "wifi", "battery", "notifications", "alarm", "speed", "cellular"}) update.putBoolean("status_" + item, statusItems.optBoolean(item, !item.equals("speed") && !item.equals("cellular")));
        JSONArray compactApps = config.optJSONArray("compactApps");
        if (compactApps != null) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (int i = 0; i < compactApps.length(); i++) { String name = compactApps.getString(i); if (!name.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+")) throw new IllegalArgumentException("无效应用规则"); names.add(name); }
            update.putStringSet("dock_compact_apps", names);
        }
        update.putBoolean("input_enabled", config.optBoolean("inputEnabled", false));
        update.putBoolean("dock_auto_hide", config.optBoolean("dockAutoHide", true)).putString("home_action", "cards");
        if (favorites != null) update.putString("favorites", new JSONArray(savedFavorites).toString());
        update.putString("pinned_action", pinned).putBoolean("panel_blur", config.optBoolean("blur", true)).putString("dock", new JSONArray(parsed.get("dock")).toString()).putString("panel", new JSONArray(parsed.get("panel")).toString()).putInt("per_page", count).putInt("damping", damping);
        update.putBoolean("avoid_keyboard", config.optBoolean("avoidKeyboard", prefs.avoidKeyboard())).putBoolean("haptics", config.optBoolean("haptics", prefs.haptics()));
        if (config.getInt("version") >= 4) prefs.prepareLayout(config.getJSONObject("layout"), update);
        else {
            update.putInt("panel_columns", 4);
            update.remove("status_hidden_apps");
            for (int rotation = 0; rotation < 4; rotation++) update.putString("panel_entry_" + rotation, Prefs.defaultEntryPosition(rotation));
            for (String key : new String[]{"panel_density", "panel_labels", "panel_label_size", "panel_tools_position", "panel_brightness", "panel_volume", "panel_media", "panel_media_idle", "panel_undo", "hub_workspace", "hub_workspace_compact", "hub_workspace_locked", "hub_workspace_labels", "hub_workspace_badges", "hub_workspace_density", "hub_workspace_aliases", "status_safe_left", "status_safe_right"}) update.remove(key);
        }
        if (config.getInt("version") >= 5) prefs.prepareRotations(config.getJSONObject("rotations"), update);
        if (config.getInt("version") >= 13) update.putString("widget_templates", WidgetTemplates.json(WidgetTemplates.read(config.getJSONArray("widgetTemplates"))).toString());
        update.apply();
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (result != RESULT_OK || intent == null || intent.getData() == null) return;
        if (request != 201 && request != 202) return;
        if (request == 202) { readImport(intent.getData()); return; }
        toast("正在导出配置…");
        worker.execute(() -> {
            try {
                JSONObject config = exportConfigurationData(); byte[] encoded = config.toString(2).getBytes(StandardCharsets.UTF_8);
                if (encoded.length > 262144) throw new IllegalArgumentException("配置过大");
                try (java.io.OutputStream stream = getContentResolver().openOutputStream(intent.getData())) { stream.write(encoded); }
                toast("配置已导出");
            } catch (Exception error) { toast("配置操作失败：" + error.getMessage()); }
        });
    }
    private void readImport(android.net.Uri uri) {
        pendingImportUri = uri.toString(); int request = ++importRequest; String source = route;
        toast("正在读取配置…");
        worker.execute(() -> {
            try {
                byte[] data;
                try (java.io.InputStream stream = getContentResolver().openInputStream(uri); java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[1024]; int count;
                    while (bytes.size() <= 262144 && (count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
                    data = bytes.toByteArray();
                }
                if (data.length > 262144) throw new IllegalArgumentException("配置过大");
                JSONObject config = new JSONObject(new String(data, StandardCharsets.UTF_8));
                main.post(() -> {
                    if (isDestroyed() || isFinishing() || request != importRequest || !source.equals(route)) return;
                    confirm("导入配置？", "将覆盖按钮、布局与偏好；保留目标外屏和权限。小组件模板需在组合卡片编辑页逐项恢复，不自动替换现有卡片。无效内容不会部分写入。", "导入", () -> {
                        try { applyConfigurationData(config); toast("配置已导入；小组件模板可在组合卡片编辑页恢复"); showRoute(route); }
                        catch (Exception error) { toast("配置未导入：" + error.getMessage()); }
                    });
                    pendingImportUri = uri.toString();
                });
            } catch (Exception error) { main.post(() -> { if (request == importRequest && !isDestroyed()) { pendingImportUri = null; toast("配置操作失败：" + error.getMessage()); } }); }
        });
    }
    private void toast(String message) { main.post(() -> { if (!isDestroyed()) Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }); }
    private boolean open(Intent intent) { try { startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(getDisplay().getDisplayId()).toBundle()); return true; } catch (RuntimeException e) { toast("系统未允许打开此入口"); return false; } }
    private void open(Intent intent, int request) { try { startActivityForResult(intent, request, ActivityOptions.makeBasic().setLaunchDisplayId(getDisplay().getDisplayId()).toBundle()); } catch (RuntimeException e) { toast("无法打开系统入口：" + e.getClass().getSimpleName()); } }
}
