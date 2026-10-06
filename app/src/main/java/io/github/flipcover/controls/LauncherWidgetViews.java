package io.github.flipcover.controls;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.widget.RemoteViews;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONObject;

/** RemoteViews adapter for the same workspace, folder tree, launcher rules and design tokens. */
final class LauncherWidgetViews {
    private final Context context;
    private final Prefs prefs;
    private final AppCatalogCache cache;
    private final Display display;
    private final boolean directApps;
    private final int widget;
    private final LauncherWidgetBridge.State state;
    private final LauncherWidgetBridge.Recents recents;
    private final Set<String> shownIcons;
    private final Set<String> requestedIcons;
    private final Map<String, Bitmap> bitmaps;
    private final JSONObject aliases;
    private final Map<String, Integer> badges;
    LauncherWidgetViews(Context context, Prefs prefs, Display display, int widget, LauncherWidgetBridge.State state, LauncherWidgetBridge.Recents recents, Set<String> shownIcons, Set<String> requestedIcons, Map<String, Bitmap> bitmaps) {
        this.context = AppLauncherStyle.fixedFontContext(context.createDisplayContext(display)); this.prefs = prefs; this.display = display; this.widget = widget; this.state = state; this.recents = recents; this.shownIcons = shownIcons; this.requestedIcons = requestedIcons;
        this.bitmaps = bitmaps;
        directApps = CoverService.instance != null;
        cache = CoverApp.catalog(context); aliases = prefs.workspaceAliases(); badges = AppLauncherModel.badges(prefs.workspaceBadges(), CoverNotifications.ready(), CoverNotifications.snapshot());
    }
    static RemoteViews message(Context context, String text) {
        RemoteViews view = base(context);
        view.setTextViewText(R.id.launcher_empty, text); view.setViewVisibility(R.id.launcher_pager, View.GONE); view.setViewVisibility(R.id.launcher_edit, View.GONE); return view;
    }
    private static RemoteViews base(Context context) {
        return base(context, false);
    }
    private static RemoteViews base(Context context, boolean hubLayout) {
        RemoteViews view = new RemoteViews(context.getPackageName(), hubLayout ? R.layout.launcher_widget_native_hub_compact_search : R.layout.launcher_widget);
        view.setInt(R.id.launcher_background, "setBackgroundColor", hubLayout ? Ui.BACKGROUND : Ui.SURFACE);
        view.setViewPadding(hubLayout ? R.id.launcher_catalog : R.id.launcher_panel, Ui.dp(context, AppLauncherStyle.PANEL_PADDING_X), Ui.dp(context, AppLauncherStyle.PANEL_PADDING_Y), Ui.dp(context, AppLauncherStyle.PANEL_PADDING_X), Ui.dp(context, AppLauncherStyle.PANEL_PADDING_Y));
        view.setTextColor(R.id.launcher_title, hubLayout ? Ui.MUTED : Ui.TEXT); view.setTextColor(R.id.launcher_back, Ui.TEXT);
        view.setTextColor(R.id.launcher_empty, Ui.MUTED); if (!hubLayout) view.setTextColor(R.id.launcher_page, Ui.MUTED);
        if (hubLayout && Build.VERSION.SDK_INT >= 31) {
            view.setTextColor(R.id.launcher_search, Ui.MUTED); view.setTextColor(R.id.launcher_sort, Ui.TEXT); view.setTextColor(R.id.launcher_refresh, Ui.MUTED);
            view.setColorStateList(R.id.launcher_catalog, "setBackgroundTintList", ColorStateList.valueOf(Ui.SURFACE));
            view.setTextViewTextSize(R.id.launcher_search, TypedValue.COMPLEX_UNIT_DIP, AppLauncherStyle.HUB_SEARCH_TEXT);
            view.setTextViewTextSize(R.id.launcher_sort, TypedValue.COMPLEX_UNIT_DIP, AppLauncherStyle.HUB_SORT_TEXT);
            view.setTextViewTextSize(R.id.launcher_title, TypedValue.COMPLEX_UNIT_DIP, AppLauncherStyle.HUB_SUMMARY_TEXT);
            view.setTextViewTextSize(R.id.launcher_refresh, TypedValue.COMPLEX_UNIT_DIP, AppLauncherStyle.HUB_RECENT_TEXT);
            view.setViewLayoutWidth(R.id.launcher_sort, AppLauncherStyle.HUB_SORT_WIDTH, TypedValue.COMPLEX_UNIT_DIP);
            view.setViewLayoutWidth(R.id.launcher_back, AppLauncherStyle.HUB_BACK_WIDTH, TypedValue.COMPLEX_UNIT_DIP);
            int field = Ui.dp(context, AppLauncherStyle.HUB_FIELD_PADDING), button = Ui.dp(context, AppLauncherStyle.HUB_BUTTON_PADDING), sort = Ui.dp(context, AppLauncherStyle.HUB_SORT_PADDING);
            view.setViewPadding(R.id.launcher_search, Ui.dp(context, AppLauncherStyle.HUB_SEARCH_PADDING), field, field, field);
            view.setViewPadding(R.id.launcher_sort, sort, field, sort, field);
            view.setViewPadding(R.id.launcher_edit, button, button, button, button);
        }
        if (Build.VERSION.SDK_INT >= 31) {
            view.setColorStateList(hubLayout ? R.id.launcher_rail_surface : R.id.launcher_rail, "setBackgroundTintList", ColorStateList.valueOf(Ui.SURFACE));
            if (!hubLayout) view.setInt(R.id.launcher_refresh, "setColorFilter", Ui.TEXT);
            for (int id : new int[]{R.id.launcher_edit, R.id.launcher_previous, R.id.launcher_next}) view.setInt(id, "setColorFilter", Ui.TEXT);
        }
        return view;
    }
    RemoteViews render() {
        if (Build.VERSION.SDK_INT < 31) return message(context, "原生启动器卡片需要 Android 12 或更新版本");
        boolean hubLayout = display.getRotation() != android.view.Surface.ROTATION_0;
        WidgetSafeArea.Frame frame = hubLayout ? CoverApp.widgets(context).launcherFrame(widget) : CoverApp.widgets(context).frame(widget);
        float density = context.getResources().getDisplayMetrics().density; boolean right = prefs.handSide().equals("right");
        AppLauncherStyle.HubGeometry hubGeometry = AppLauncherStyle.hubGeometry(Ui.dp(context, frame.width()), Ui.dp(context, frame.height()), density, right, true);
        List<String> pins = prefs.hubPins();
        float headerHeight = hubLayout ? AppLauncherStyle.HUB_HEADER_HEIGHT : dimension(R.dimen.launcher_header_height), pageHeight = dimension(R.dimen.launcher_page_height);
        float dockWidth = frame.width() - (hubLayout ? 2 * AppLauncherStyle.SURFACE_INSET : 2 * AppLauncherStyle.PANEL_PADDING_X), width = frame.width() - 2 * AppLauncherStyle.PANEL_PADDING_X - AppLauncherStyle.RAIL_WIDTH - AppLauncherStyle.RAIL_GAP, height = frame.height() - 2 * AppLauncherStyle.PANEL_PADDING_Y - headerHeight - pageHeight - AppLauncherStyle.dockHeight() - (hubLayout ? AppLauncherStyle.HUB_SUMMARY_HEIGHT : 0);
        if (hubLayout) { width = hubGeometry.gridWidth() / density; height = hubGeometry.gridHeight() / density; headerHeight = hubGeometry.headerHeight() / density; pageHeight = hubGeometry.pagerHeight() / density; }
        int columns = AppLauncherStyle.GRID_COLUMNS, rows = AppLauncherStyle.GRID_ROWS;
        if (width < 80 || height < 60) return message(context, "卡片空间不足，请调整尺寸");
        AppWorkspaceLayout saved = prefs.workspace();
        if (cache.ready() && !cache.failed()) saved = AppLauncherModel.reconcile(saved, cache.snapshot(), pins, prefs.workspaceCompact());
        AppWorkspaceLayout.Folder opened = saved.folder(state.folder);
        if (opened == null) state.closeFolder();
        if (opened != null) {
            if (!hubLayout) height = Math.max(0, height - (AppLauncherStyle.FOLDER_RETURN_HEIGHT - headerHeight));
            headerHeight = AppLauncherStyle.FOLDER_RETURN_HEIGHT;
        }
        float gridHeight = height;
        List<AppCatalogCache.Entry> selected = AppLauncherModel.select(cache.snapshot(), pins, aliases, "", prefs.hubSort(), recents.tasks(), new AppSearchIndex());
        AppWorkspaceLayout layout;
        if (opened != null) { columns = AppLauncherStyle.FOLDER_COLUMNS; layout = AppWorkspaceLayout.sequential(opened.members()).project(columns, rows); }
        else if (prefs.hubSort().equals("manual")) layout = saved.project(columns, rows);
        else layout = AppWorkspaceLayout.sequential(selected.stream().map(AppCatalogCache.Entry::id).collect(java.util.stream.Collectors.toList())).project(columns, rows);
        int capacity = columns * rows; state.pages = layout.pages(capacity); state.page = Math.max(0, Math.min(state.page, state.pages - 1));
        RemoteViews view = base(context, hubLayout);
        geometry(view, R.id.launcher_panel, frame.left(), frame.top(), frame.width(), frame.height());
        view.setViewLayoutHeight(R.id.launcher_header, headerHeight, TypedValue.COMPLEX_UNIT_DIP);
        view.setViewLayoutHeight(R.id.launcher_pager, pageHeight, TypedValue.COMPLEX_UNIT_DIP);
        view.setViewLayoutHeight(R.id.launcher_pins, hubLayout ? hubGeometry.dockHeight() / density + AppLauncherStyle.SURFACE_INSET : AppLauncherStyle.dockHeight(), TypedValue.COMPLEX_UNIT_DIP);
        if (hubLayout) { int inset = Ui.dp(context, AppLauncherStyle.SURFACE_INSET); view.setViewPadding(R.id.launcher_pins, inset, 0, inset, inset); }
        float contentLeft = hubLayout || right ? 0 : AppLauncherStyle.RAIL_WIDTH + AppLauncherStyle.RAIL_GAP;
        geometry(view, R.id.launcher_grid, contentLeft, 0, width, gridHeight);
        geometry(view, R.id.launcher_empty, contentLeft, 0, width, height);
        if (hubLayout) {
            geometry(view, R.id.launcher_catalog, hubGeometry.catalogLeft() / density, AppLauncherStyle.SURFACE_INSET, hubGeometry.catalogWidth() / density, hubGeometry.bodyHeight() / density);
            geometry(view, R.id.launcher_rail_panel, hubGeometry.railLeft() / density, AppLauncherStyle.SURFACE_INSET, hubGeometry.railWidth() / density, hubGeometry.bodyHeight() / density);
            view.setViewLayoutHeight(R.id.launcher_summary, AppLauncherStyle.HUB_SUMMARY_HEIGHT, TypedValue.COMPLEX_UNIT_DIP);
            view.setViewVisibility(R.id.launcher_summary, opened == null ? View.VISIBLE : View.GONE);
            view.setViewVisibility(R.id.launcher_search_field, opened == null ? View.VISIBLE : View.GONE);
            view.setViewVisibility(R.id.launcher_sort, opened == null ? View.VISIBLE : View.GONE);
            android.graphics.Rect search = AppLauncherStyle.searchBounds(hubGeometry.gridWidth(), density);
            geometry(view, R.id.launcher_search_field, search.left / density, 0, search.width() / density, search.height() / density);
            view.setViewLayoutHeight(R.id.launcher_rail_tools, AppLauncherStyle.RAIL_TOOLS_HEIGHT, TypedValue.COMPLEX_UNIT_DIP);
            view.setViewLayoutMargin(R.id.launcher_rail_tools, RemoteViews.MARGIN_TOP, AppLauncherStyle.RAIL_GAP, TypedValue.COMPLEX_UNIT_DIP);
            view.setViewLayoutWidth(R.id.launcher_edit, AppLauncherStyle.RAIL_WIDTH, TypedValue.COMPLEX_UNIT_DIP);
            view.setViewLayoutHeight(R.id.launcher_edit, AppLauncherStyle.RAIL_TOOLS_HEIGHT, TypedValue.COMPLEX_UNIT_DIP);
            int railPadding = Ui.dp(context, AppLauncherStyle.RAIL_FRAME_PADDING);
            view.setViewPadding(R.id.launcher_rail_surface, railPadding, railPadding, railPadding, railPadding);
            view.setOnClickPendingIntent(R.id.launcher_search, action("surface", "search")); view.setOnClickPendingIntent(R.id.launcher_sort, action("surface", "sort"));
            view.setTextViewText(R.id.launcher_sort, AppLauncherModel.sortLabel(prefs.hubSort()));
            view.setTextViewText(R.id.launcher_refresh, recents.busy() ? "正在刷新…" : !recents.error().isEmpty() ? "刷新失败 · 重试" : !recents.known() ? "最近应用 · 刷新" : AppLauncherModel.recentStatus(pins.size(), RecentTasks.apps(recents.tasks(), AppDockLayout.packages(pins)).size()));
            view.setViewVisibility(R.id.launcher_body, View.VISIBLE);
        } else geometry(view, R.id.launcher_rail, (right ? width + AppLauncherStyle.RAIL_GAP : 0) + AppLauncherStyle.railOffset(density, right) / density, 0, AppLauncherStyle.RAIL_WIDTH, height);
        view.setTextViewText(R.id.launcher_title, opened == null ? "应用中心" : opened.name() + " · " + opened.members().size() + "/9");
        if (hubLayout && opened == null) view.setTextViewText(R.id.launcher_title, AppLauncherModel.catalogStatus(selected.size(), cache.ready(), cache.failed()));
        view.setViewVisibility(R.id.launcher_back, opened == null ? View.GONE : View.VISIBLE);
        if (!hubLayout) {
            view.setViewVisibility(R.id.launcher_title, opened == null ? View.VISIBLE : View.GONE);
            view.setViewVisibility(R.id.launcher_edit, opened == null ? View.VISIBLE : View.GONE);
            view.setViewVisibility(R.id.launcher_refresh, opened == null ? View.VISIBLE : View.GONE);
        }
        view.setViewLayoutWidth(R.id.launcher_back, -1, TypedValue.COMPLEX_UNIT_PX);
        view.setTextViewTextSize(R.id.launcher_back, TypedValue.COMPLEX_UNIT_DIP, AppLauncherStyle.FOLDER_RETURN_TEXT);
        view.setBoolean(R.id.launcher_back, "setSingleLine", true);
        view.setInt(R.id.launcher_back, "setGravity", android.view.Gravity.CENTER);
        int returnPadding = Ui.dp(context, AppLauncherStyle.HUB_SEARCH_PADDING); view.setViewPadding(R.id.launcher_back, returnPadding, 0, returnPadding, 0);
        view.setTextViewText(R.id.launcher_back, opened == null ? "" : "‹ 全部应用 · " + opened.name());
        view.setContentDescription(R.id.launcher_back, opened == null ? "返回应用列表" : "返回全部应用，当前文件夹：" + opened.name());
        view.setViewVisibility(R.id.launcher_empty, layout.size() == 0 ? View.VISIBLE : View.GONE);
        view.setTextViewText(R.id.launcher_empty, cache.ready() ? "未找到应用" : cache.failed() ? "应用目录暂不可用" : "正在读取应用…");
        if (hubLayout) {
            int dotsWidth = Ui.dp(context, Math.min(state.pages, AppLauncherStyle.PAGE_DOT_LIMIT) * AppLauncherStyle.PAGE_DOT_GAP), dotsHeight = Ui.dp(context, AppLauncherStyle.WORKSPACE_PAGER_HEIGHT);
            Bitmap dots = Bitmap.createBitmap(dotsWidth, dotsHeight, Bitmap.Config.ARGB_8888); dots.setDensity(Bitmap.DENSITY_NONE);
            AppLauncherStyle.drawPageDots(new Canvas(dots), dotsWidth, dotsHeight, state.page, state.pages, density, new Paint(Paint.ANTI_ALIAS_FLAG)); view.setImageViewBitmap(R.id.launcher_page, dots);
            view.setViewLayoutWidth(R.id.launcher_page, dotsWidth / density, TypedValue.COMPLEX_UNIT_DIP);
        } else view.setTextViewText(R.id.launcher_page, (state.page + 1) + " / " + state.pages);
        view.setContentDescription(R.id.launcher_page, "第" + (state.page + 1) + "页，共" + state.pages + "页");
        String status = recents.busy() ? "正在刷新最近应用" : !recents.error().isEmpty() ? "最近应用不可用，点击重试" : !recents.known() ? "最近应用待刷新，点击刷新" : "刷新最近应用";
        view.setContentDescription(R.id.launcher_refresh, status);
        view.setTextViewText(R.id.launcher_refresh_status, recents.busy() ? "…" : !recents.error().isEmpty() ? "!" : "·");
        view.setViewVisibility(R.id.launcher_refresh_status, opened != null && !hubLayout ? View.GONE : recents.busy() || !recents.known() || !recents.error().isEmpty() ? View.VISIBLE : View.GONE);
        view.setBoolean(R.id.launcher_previous, "setEnabled", state.page > 0); view.setBoolean(R.id.launcher_next, "setEnabled", state.page + 1 < state.pages);
        view.setFloat(R.id.launcher_previous, "setAlpha", state.page > 0 ? 1f : .5f); view.setFloat(R.id.launcher_next, "setAlpha", state.page + 1 < state.pages ? 1f : .5f);
        view.setOnClickPendingIntent(R.id.launcher_edit, action("edit", "")); view.setOnClickPendingIntent(R.id.launcher_back, action("back", ""));
        view.setOnClickPendingIntent(R.id.launcher_refresh, action("refresh", "")); view.setBoolean(R.id.launcher_refresh, "setEnabled", !recents.busy());
        view.setOnClickPendingIntent(R.id.launcher_previous, action("previous", "")); view.setOnClickPendingIntent(R.id.launcher_next, action("next", ""));
        view.removeAllViews(R.id.launcher_grid); view.removeAllViews(R.id.launcher_dock_row);
        view.setOnClickPendingIntent(R.id.launcher_grid, opened == null ? null : action("back", ""));
        view.setContentDescription(R.id.launcher_grid, opened == null ? null : "文件夹，点击空白处返回全部应用");
        view.setOnClickPendingIntent(R.id.launcher_pager, opened == null ? null : action("back", ""));
        for (int id : new int[]{R.id.launcher_previous, R.id.launcher_page, R.id.launcher_next}) view.setViewVisibility(id, opened == null ? View.VISIBLE : View.INVISIBLE);
        if (opened != null) {
            android.graphics.Rect surface = AppLauncherStyle.folderMemberSurface(Ui.dp(context, width), Ui.dp(context, gridHeight), density);
            RemoteViews backplate = new RemoteViews(context.getPackageName(), R.layout.launcher_widget_folder_backplate);
            geometry(backplate, R.id.launcher_folder_backplate, surface.left / density, surface.top / density, surface.width() / density, surface.height() / density);
            backplate.setColorStateList(R.id.launcher_folder_backplate, "setBackgroundTintList", ColorStateList.valueOf(AppLauncherStyle.FOLDER_COLORS[opened.color()]));
            view.addView(R.id.launcher_grid, backplate);
        }
        for (String id : layout.ordered()) {
            int slot = layout.slot(id); if (slot / capacity != state.page) continue;
            android.graphics.Rect box = opened == null ? AppLauncherStyle.gridCell(Ui.dp(context, width), Ui.dp(context, gridHeight), columns, rows, slot, layout.span(id), state.page) : AppLauncherStyle.folderMemberCell(Ui.dp(context, width), Ui.dp(context, gridHeight), opened.members().size(), slot, density);
            RemoteViews cell = cell(id, layout.folder(id), box.width() / density, box.height() / density, false, false);
            geometry(cell, R.id.launcher_cell, box.left / density, box.top / density, box.width() / density, box.height() / density); view.addView(R.id.launcher_grid, cell);
        }
        view.setViewVisibility(R.id.launcher_grid, View.VISIBLE);
        view.setViewVisibility(R.id.launcher_pager, View.VISIBLE);
        renderRail(view);
        renderDock(view, dockWidth, pins);
        return view;
    }
    private float dimension(int resource) { return context.getResources().getDimension(resource) / context.getResources().getDisplayMetrics().density; }
    @androidx.annotation.RequiresApi(31)
    private void renderRail(RemoteViews view) {
        view.setViewVisibility(R.id.launcher_rail, View.VISIBLE);
        int padding = Ui.dp(context, AppLauncherStyle.RAIL_FRAME_PADDING); boolean hubLayout = display.getRotation() != android.view.Surface.ROTATION_0;
        view.setViewPadding(R.id.launcher_rail, hubLayout ? 0 : padding, hubLayout ? 0 : padding, hubLayout ? 0 : padding, hubLayout ? 0 : padding);
        List<String> favorites = prefs.actions("favorites");
        float height = AppLauncherStyle.RAIL_CELL;
        RemoteViews.RemoteCollectionItems.Builder items = new RemoteViews.RemoteCollectionItems.Builder().setHasStableIds(true).setViewTypeCount(1);
        for (String id : favorites) {
            int rowWidth = AppLauncherStyle.RAIL_WIDTH - 2 * AppLauncherStyle.RAIL_FRAME_PADDING;
            RemoteViews row = cell(id, null, rowWidth, height, false, true);
            row.setViewLayoutWidth(R.id.launcher_cell, rowWidth, TypedValue.COMPLEX_UNIT_DIP); row.setViewLayoutHeight(R.id.launcher_cell, height, TypedValue.COMPLEX_UNIT_DIP);
            row.setViewPadding(R.id.launcher_cell, Ui.dp(context, AppLauncherStyle.RAIL_PADDING), Ui.dp(context, AppLauncherStyle.RAIL_PADDING), Ui.dp(context, AppLauncherStyle.RAIL_PADDING), Ui.dp(context, AppLauncherStyle.RAIL_PADDING)); row.setViewPadding(R.id.launcher_label, 0, Ui.dp(context, AppLauncherStyle.RAIL_LABEL_GAP), 0, 0);
            row.setViewVisibility(R.id.launcher_label, View.VISIBLE); row.setTextViewTextSize(R.id.launcher_label, TypedValue.COMPLEX_UNIT_DIP, AppLauncherStyle.RAIL_LABEL_SP);
            row.setViewLayoutWidth(R.id.launcher_icon, AppLauncherStyle.RAIL_ICON, TypedValue.COMPLEX_UNIT_DIP); row.setViewLayoutHeight(R.id.launcher_icon, AppLauncherStyle.RAIL_ICON, TypedValue.COMPLEX_UNIT_DIP);
            row.setContentDescription(R.id.launcher_cell, "侧栏：" + AppLauncherModel.label(id, aliases, cache));
            row.setBoolean(R.id.launcher_cell, "setFocusable", false);
            row.setOnClickFillInIntent(R.id.launcher_cell, new Intent().putExtra("item", id));
            items.addItem(AppLauncherModel.itemId(id), row);
        }
        // A service-backed click reaches the shared launcher without an Activity trampoline.
        Intent click = intent("side", null);
        PendingIntent template = directApps
                ? PendingIntent.getBroadcast(context, 0, click.setClass(context, LauncherWidgetProvider.class).setAction(LauncherWidgetProvider.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE)
                : PendingIntent.getActivity(context, 0, click.setClass(context, LauncherWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE, ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle());
        view.setPendingIntentTemplate(R.id.launcher_rail, template);
        view.setRemoteAdapter(R.id.launcher_rail, items.build());
    }
    @androidx.annotation.RequiresApi(31)
    private void renderDock(RemoteViews view, float width, List<String> pins) {
        float density = context.getResources().getDisplayMetrics().density;
        List<RecentTasks.Task> apps = RecentTasks.apps(recents.tasks(), AppDockLayout.packages(pins));
        AppDockLayout.Geometry geometry = AppDockLayout.fit(Ui.dp(context, width), pins.size(), apps.size(), density);
        List<RecentTasks.Task> shown = apps.subList(0, geometry.recentCount()); float size = geometry.cell() / density;
        float total = (geometry.chrome() + (pins.size() + shown.size()) * geometry.cell()) / density, x = AppLauncherStyle.DOCK_EDGE;
        geometry(view, R.id.launcher_dock_row, (width - total) / 2, AppLauncherStyle.DOCK_TOP, total, AppLauncherStyle.DOCK_ROW);
        view.setColorStateList(R.id.launcher_dock_row, "setBackgroundTintList", ColorStateList.valueOf(AppLauncherStyle.DOCK_COLOR));
        RemoteViews toggle = new RemoteViews(context.getPackageName(), R.layout.launcher_widget_disabled_apps);
        toggle.setContentDescription(R.id.launcher_cell, "全部应用（固定显示），九宫格按钮已禁用"); toggle.setOnClickPendingIntent(R.id.launcher_cell, null);
        toggle.setBoolean(R.id.launcher_cell, "setEnabled", false); toggle.setBoolean(R.id.launcher_cell, "setFocusable", false);
        for (int id : new int[]{R.id.launcher_icon, R.id.launcher_disabled_mark}) { toggle.setViewLayoutWidth(id, AppLauncherStyle.DOCK_APPS_ICON, TypedValue.COMPLEX_UNIT_DIP); toggle.setViewLayoutHeight(id, AppLauncherStyle.DOCK_APPS_ICON, TypedValue.COMPLEX_UNIT_DIP); toggle.setInt(id, "setColorFilter", Ui.TEXT); }
        toggle.setViewLayoutWidth(R.id.launcher_disabled_mark, AppLauncherStyle.DOCK_APPS_ICON * AppLauncherStyle.DOCK_DISABLED_SCALE, TypedValue.COMPLEX_UNIT_DIP);
        toggle.setViewLayoutHeight(R.id.launcher_disabled_mark, AppLauncherStyle.DOCK_APPS_ICON * AppLauncherStyle.DOCK_DISABLED_SCALE, TypedValue.COMPLEX_UNIT_DIP);
        toggle.setFloat(R.id.launcher_disabled_mark, "setAlpha", AppLauncherStyle.DOCK_DISABLED_ALPHA);
        place(view, R.id.launcher_dock_row, toggle, x, 0, AppLauncherStyle.DOCK_TOOL_WIDTH, AppLauncherStyle.DOCK_ROW); x += AppLauncherStyle.DOCK_TOOL_WIDTH;
        for (String id : pins) { RemoteViews pin = cell(id, null, size, AppLauncherStyle.DOCK_ROW, true, false); pin.setContentDescription(R.id.launcher_cell, "常用：" + AppLauncherModel.label(id, aliases, cache)); place(view, R.id.launcher_dock_row, pin, x, 0, size, AppLauncherStyle.DOCK_ROW); x += size; }
        if (!pins.isEmpty() && !shown.isEmpty()) {
            x += geometry.margin() / density; RemoteViews separator = new RemoteViews(context.getPackageName(), R.layout.launcher_widget_cell);
            separator.setViewVisibility(R.id.launcher_icon, View.GONE); separator.setViewVisibility(R.id.launcher_label, View.GONE); separator.setInt(R.id.launcher_cell, "setBackgroundColor", AppLauncherStyle.DOCK_SEPARATOR); separator.setBoolean(R.id.launcher_cell, "setFocusable", false);
            place(view, R.id.launcher_dock_row, separator, x, (AppLauncherStyle.DOCK_ROW - AppLauncherStyle.DOCK_SEPARATOR_HEIGHT) / 2f, geometry.separator() / density, AppLauncherStyle.DOCK_SEPARATOR_HEIGHT); x += (geometry.separator() + geometry.margin()) / density;
        }
        for (RecentTasks.Task task : shown) {
            String id = cache.launcher(task.packageName()); if (id == null) id = "app:" + task.component();
            RemoteViews recent = cell(id, null, size, AppLauncherStyle.DOCK_ROW, true, false); recent.setContentDescription(R.id.launcher_cell, "最近任务：" + AppLauncherModel.label(id, aliases, cache));
            try { recent.setOnClickPendingIntent(R.id.launcher_cell, activity("task", SystemRecentTasks.json(task).toString())); } catch (Exception error) { recent.setOnClickPendingIntent(R.id.launcher_cell, null); }
            recent.setBoolean(R.id.launcher_cell, "setEnabled", recents.known() && recents.canOpen() && !recents.busy()); recent.setFloat(R.id.launcher_cell, "setAlpha", recents.known() && recents.canOpen() ? 1 : .4f);
            place(view, R.id.launcher_dock_row, recent, x, 0, size, AppLauncherStyle.DOCK_ROW); x += size;
        }
        if (!shown.isEmpty()) {
            List<RecentTasks.Task> targets = AppDockLayout.clearTargets(recents.tasks(), pins, shown, CoverApp.taskLocks(context));
            String encoded = "[]"; try { encoded = AppRecentTasks.encode(targets); } catch (Exception ignored) { }
            RemoteViews clear = tool(R.drawable.ic_hub_clean, "清理可见最近应用的外屏后台任务，保留可见、固定和锁定任务", action("clear", encoded));
            clear.setViewLayoutWidth(R.id.launcher_icon, AppLauncherStyle.DOCK_CLEAR_ICON, TypedValue.COMPLEX_UNIT_DIP); clear.setViewLayoutHeight(R.id.launcher_icon, AppLauncherStyle.DOCK_CLEAR_ICON, TypedValue.COMPLEX_UNIT_DIP);
            clear.setBoolean(R.id.launcher_cell, "setEnabled", recents.known() && recents.canClear() && !recents.busy() && !targets.isEmpty()); place(view, R.id.launcher_dock_row, clear, x, 0, AppLauncherStyle.DOCK_TOOL_WIDTH, AppLauncherStyle.DOCK_ROW);
        }
        view.setViewVisibility(R.id.launcher_pins, View.VISIBLE);
    }
    private RemoteViews tool(int icon, String description, PendingIntent click) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.launcher_widget_cell); view.setImageViewResource(R.id.launcher_icon, icon); view.setInt(R.id.launcher_icon, "setColorFilter", Ui.TEXT);
        view.setViewPadding(R.id.launcher_cell, 0, 0, 0, 0);
        view.setViewVisibility(R.id.launcher_label, View.GONE); view.setContentDescription(R.id.launcher_cell, description); view.setOnClickPendingIntent(R.id.launcher_cell, click); return view;
    }
    @androidx.annotation.RequiresApi(31)
    private void place(RemoteViews parent, int area, RemoteViews cell, float x, float y, float width, float height) { geometry(cell, R.id.launcher_cell, x, y, width, height); parent.addView(area, cell); }
    @androidx.annotation.RequiresApi(31)
    private RemoteViews cell(String id, AppWorkspaceLayout.Folder folder, float width, float height, boolean pin, boolean collection) {
        RemoteViews view = new RemoteViews(context.getPackageName(), folder == null ? R.layout.launcher_widget_cell : R.layout.launcher_widget_folder);
        int count = AppLauncherModel.badge(id, badges);
        String name = folder == null ? AppLauncherModel.label(id, aliases, cache) : folder.name();
        if (folder != null) { Set<String> packages = new HashSet<>(); count = 0; for (String member : folder.members()) { android.content.ComponentName component = ActionCatalog.component(member); if (component != null && packages.add(component.getPackageName())) count += badges.getOrDefault(component.getPackageName(), 0); } }
        view.setTextViewText(R.id.launcher_label, name + (count > 0 ? " · " + count : "")); view.setTextColor(R.id.launcher_label, Ui.TEXT);
        view.setTextViewTextSize(R.id.launcher_label, TypedValue.COMPLEX_UNIT_DIP, AppLauncherStyle.LABEL_SP);
        view.setContentDescription(R.id.launcher_cell, name + (folder == null ? "" : "，文件夹，" + folder.members().size() + "个应用") + (count > 0 ? "，" + count + "条活动通知" : ""));
        view.setViewVisibility(R.id.launcher_label, !pin && (prefs.workspaceLabels() || folder != null) ? View.VISIBLE : View.GONE);
        int iconSize = pin ? AppLauncherStyle.dockIconSize(width) : AppLauncherStyle.iconSize(prefs.workspaceDensity(), width);
        if (!pin && !collection && state.folder != null) iconSize = AppLauncherStyle.folderMemberIconSize(width);
        Bitmap icon;
        if (folder == null) icon = icon(id);
        else {
            android.widget.TextView label = Ui.text(context, name, AppLauncherStyle.LABEL_SP, Ui.TEXT); label.setSingleLine(); label.measure(0, 0);
            AppLauncherStyle.FolderGeometry layout = AppLauncherStyle.folderGeometry(context, Ui.dp(context, width), Ui.dp(context, height), label.getMeasuredHeight());
            float density = context.getResources().getDisplayMetrics().density; android.graphics.Rect surface = layout.surface(), preview = layout.preview(), text = layout.label();
            geometry(view, R.id.launcher_folder_surface, surface.left / density, surface.top / density, surface.width() / density, surface.height() / density);
            geometry(view, R.id.launcher_icon, (preview.left - surface.left) / density, (preview.top - surface.top) / density, preview.width() / density, preview.height() / density);
            geometry(view, R.id.launcher_label, (text.left - surface.left) / density, (text.top - surface.top) / density, text.width() / density, text.height() / density); icon = folderIcon(folder, preview.width());
            view.setColorStateList(R.id.launcher_folder_surface, "setBackgroundTintList", ColorStateList.valueOf(AppLauncherStyle.FOLDER_COLORS[folder.color()]));
        }
        if (folder == null) {
            view.setViewPadding(R.id.launcher_cell, Ui.dp(context, AppLauncherStyle.APP_PADDING), Ui.dp(context, AppLauncherStyle.APP_PADDING), Ui.dp(context, AppLauncherStyle.APP_PADDING), Ui.dp(context, AppLauncherStyle.APP_PADDING));
            view.setViewPadding(R.id.launcher_label, 0, Ui.dp(context, AppLauncherStyle.APP_LABEL_GAP), 0, 0);
            view.setViewLayoutWidth(R.id.launcher_icon, iconSize, TypedValue.COMPLEX_UNIT_DIP); view.setViewLayoutHeight(R.id.launcher_icon, iconSize, TypedValue.COMPLEX_UNIT_DIP);
        }
        if (icon == null) view.setImageViewResource(R.id.launcher_icon, R.drawable.ic_ms_apps); else view.setImageViewBitmap(R.id.launcher_icon, icon);
        if (!collection) view.setOnClickPendingIntent(R.id.launcher_cell, folder == null ? activity("launch", id) : action("folder", id)); return view;
    }
    private Bitmap icon(String id) {
        if (!id.startsWith("app:") && !id.startsWith("tile:")) {
            Drawable drawable = ActionCatalog.loadIcon(context, id); Bitmap result = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888); result.setDensity(Bitmap.DENSITY_NONE); drawable.setBounds(0, 0, 48, 48); drawable.draw(new Canvas(result)); return result;
        }
        shownIcons.add(id); if (bitmaps.containsKey(id)) { requestedIcons.add(id); return bitmaps.get(id); }
        Drawable drawable = cache.cachedIcon(context, id);
        // Completion updates must not restart decodes for icons evicted by another card.
        if (!(drawable instanceof BitmapDrawable bitmap)) { if (!requestedIcons.contains(id) && cache.requestIcon(id)) requestedIcons.add(id); return null; }
        requestedIcons.add(id); Bitmap result = bitmap.getBitmap();
        if (bitmaps.size() < AppWorkspaceLayout.MAX_APPS + AppDockPlacement.LIMIT) bitmaps.put(id, result); return result;
    }
    private Bitmap folderIcon(AppWorkspaceLayout.Folder folder, int side) {
        // Rasterize at the shared geometry's physical size; a fixed thumbnail loses member detail.
        Bitmap preview = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888); preview.setDensity(Bitmap.DENSITY_NONE); Canvas canvas = new Canvas(preview); Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        for (int i = 0; i < folder.members().size(); i++) { Bitmap icon = icon(folder.members().get(i)); if (icon != null) canvas.drawBitmap(icon, null, AppLauncherStyle.folderIconBounds(preview.getWidth(), i), paint); }
        return preview;
    }
    private Intent intent(String operation, String item) {
        Intent intent = new Intent().setData(new Uri.Builder().scheme("flipcover").authority("launcher-widget").appendPath(Integer.toString(widget)).appendPath(Integer.toString(display.getDisplayId())).appendPath(operation).appendPath(item == null ? "" : item).build())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget).putExtra("display", display.getDisplayId()).putExtra("operation", operation);
        if (item != null) intent.putExtra("item", item); return intent;
    }
    private PendingIntent action(String operation, String item) {
        return PendingIntent.getBroadcast(context, 0, intent(operation, item).setClass(context, LauncherWidgetProvider.class).setAction(LauncherWidgetProvider.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent activity(String operation, String item) {
        if (directApps && operation.equals("launch")) return action(operation, item);
        return PendingIntent.getActivity(context, 0, intent(operation, item).setClass(context, LauncherWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle());
    }
    @androidx.annotation.RequiresApi(31)
    private static void geometry(RemoteViews view, int id, float x, float y, float width, float height) {
        view.setViewLayoutWidth(id, width, TypedValue.COMPLEX_UNIT_DIP); view.setViewLayoutHeight(id, height, TypedValue.COMPLEX_UNIT_DIP);
        view.setViewLayoutMargin(id, RemoteViews.MARGIN_LEFT, x, TypedValue.COMPLEX_UNIT_DIP); view.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, y, TypedValue.COMPLEX_UNIT_DIP);
    }
}
