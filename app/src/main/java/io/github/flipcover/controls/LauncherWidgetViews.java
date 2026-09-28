package io.github.flipcover.controls;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Display;
import android.view.View;
import android.widget.RemoteViews;
import android.widget.TextView;
import java.util.HashMap;
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
    private final int widget;
    private final LauncherWidgetBridge.State state;
    private final List<RecentTasks.Task> tasks;
    private final Set<String> shownIcons;
    private final Map<String, Bitmap> bitmaps = new HashMap<>();
    private final JSONObject aliases;
    private final Map<String, Integer> badges;
    LauncherWidgetViews(Context context, Prefs prefs, Display display, int widget, LauncherWidgetBridge.State state, List<RecentTasks.Task> tasks, Set<String> shownIcons) {
        this.context = context.createDisplayContext(display); this.prefs = prefs; this.display = display; this.widget = widget; this.state = state; this.tasks = tasks; this.shownIcons = shownIcons;
        cache = CoverApp.catalog(context); aliases = prefs.workspaceAliases(); badges = AppLauncherModel.badges(prefs.workspaceBadges(), CoverNotifications.ready(), CoverNotifications.snapshot());
    }
    static RemoteViews message(Context context, String text) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.launcher_widget);
        view.setTextViewText(R.id.launcher_empty, text); view.setViewVisibility(R.id.launcher_pager, View.GONE); view.setViewVisibility(R.id.launcher_edit, View.GONE); return view;
    }
    RemoteViews render() {
        if (Build.VERSION.SDK_INT < 31) return message(context, "原生启动器卡片需要 Android 12 或更新版本");
        WidgetSafeArea.Frame frame = CoverApp.widgets(context).frame(widget);
        List<String> pins = prefs.hubPins();
        float width = frame.width() - 10, height = frame.height() - 7 - 38 - 32 - (pins.isEmpty() ? 0 : 44);
        TextView measure = Ui.text(context, "应用 Ag", AppLauncherStyle.LABEL_SP, Ui.TEXT); measure.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, Ui.dp(context, width)), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        float labelHeight = prefs.workspaceLabels() ? measure.getMeasuredHeight() / context.getResources().getDisplayMetrics().density + 2 : 0;
        int columns = AppLauncherStyle.columns(width, prefs.workspaceDensity());
        int rows = Math.max(1, Math.min(6, (int) (height / (AppLauncherStyle.iconSize(prefs.workspaceDensity()) + labelHeight + 4))));
        if (width < 80 || height < AppLauncherStyle.iconSize(prefs.workspaceDensity()) + labelHeight + 4) return message(context, "卡片空间不足，请调整尺寸或桌面密度");
        AppWorkspaceLayout saved = prefs.workspace();
        if (cache.ready() && !cache.failed()) saved = AppLauncherModel.reconcile(saved, cache.snapshot(), pins, prefs.workspaceCompact());
        AppWorkspaceLayout.Folder opened = saved.folder(state.folder);
        if (opened == null) state.folder = null;
        List<AppCatalogCache.Entry> selected = AppLauncherModel.select(cache.snapshot(), pins, aliases, "", prefs.hubSort(), tasks, new AppSearchIndex());
        AppWorkspaceLayout layout;
        if (opened != null) { columns = Math.min(3, columns); layout = AppWorkspaceLayout.sequential(opened.members()).project(columns, rows); }
        else if (prefs.hubSort().equals("manual")) layout = saved.project(columns, rows);
        else layout = AppWorkspaceLayout.sequential(selected.stream().map(AppCatalogCache.Entry::id).collect(java.util.stream.Collectors.toList())).project(columns, rows);
        int capacity = columns * rows; state.pages = layout.pages(capacity); state.page = Math.max(0, Math.min(state.page, state.pages - 1));
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.launcher_widget);
        geometry(view, R.id.launcher_panel, frame.left(), frame.top(), frame.width(), frame.height());
        view.setTextViewText(R.id.launcher_title, opened == null ? "应用中心" : opened.name() + " · " + opened.members().size() + "/9");
        view.setViewVisibility(R.id.launcher_back, opened == null ? View.GONE : View.VISIBLE);
        view.setViewVisibility(R.id.launcher_empty, layout.size() == 0 ? View.VISIBLE : View.GONE);
        view.setTextViewText(R.id.launcher_empty, cache.ready() ? "未找到应用" : cache.failed() ? "应用目录暂不可用" : "正在读取应用…");
        view.setTextViewText(R.id.launcher_page, (state.page + 1) + " / " + state.pages + (prefs.hubSort().equals("recent") && tasks.isEmpty() ? " · 最近排序待浮窗刷新" : ""));
        view.setBoolean(R.id.launcher_previous, "setEnabled", state.page > 0); view.setBoolean(R.id.launcher_next, "setEnabled", state.page + 1 < state.pages);
        view.setOnClickPendingIntent(R.id.launcher_edit, action("edit", "")); view.setOnClickPendingIntent(R.id.launcher_back, action("back", ""));
        view.setOnClickPendingIntent(R.id.launcher_previous, action("previous", "")); view.setOnClickPendingIntent(R.id.launcher_next, action("next", ""));
        view.removeAllViews(R.id.launcher_grid); view.removeAllViews(R.id.launcher_pins);
        float cellWidth = width / columns, cellHeight = height / rows;
        for (String id : layout.ordered()) {
            int slot = layout.slot(id); if (slot / capacity != state.page) continue;
            int local = slot % capacity, span = layout.span(id);
            RemoteViews cell = cell(id, layout.folder(id), cellWidth * span, cellHeight * span, false);
            geometry(cell, R.id.launcher_cell, local % columns * cellWidth, local / columns * cellHeight, cellWidth * span - .5f, cellHeight * span - .5f); view.addView(R.id.launcher_grid, cell);
        }
        view.setViewVisibility(R.id.launcher_pins, pins.isEmpty() ? View.GONE : View.VISIBLE);
        float pinWidth = Math.min(52, width / Math.max(1, pins.size())), left = (width - pins.size() * pinWidth) / 2;
        for (int i = 0; i < pins.size(); i++) { RemoteViews pin = cell(pins.get(i), null, pinWidth, 44, true); geometry(pin, R.id.launcher_cell, left + i * pinWidth, 0, pinWidth, 44); view.addView(R.id.launcher_pins, pin); }
        view.setViewVisibility(R.id.launcher_hint, state.hint ? View.VISIBLE : View.GONE);
        view.setViewVisibility(R.id.launcher_panel, state.hint ? View.INVISIBLE : View.VISIBLE);
        view.setTextViewText(R.id.launcher_hint_text, AppLauncherModel.EDIT_HINT); view.setOnClickPendingIntent(R.id.launcher_hint_close, action("dismiss", ""));
        view.setViewLayoutWidth(R.id.launcher_hint, Math.max(1, frame.width()), TypedValue.COMPLEX_UNIT_DIP);
        view.setViewLayoutMargin(R.id.launcher_hint, RemoteViews.MARGIN_LEFT, frame.left(), TypedValue.COMPLEX_UNIT_DIP);
        return view;
    }
    @android.annotation.TargetApi(31)
    private RemoteViews cell(String id, AppWorkspaceLayout.Folder folder, float width, float height, boolean pin) {
        RemoteViews view = new RemoteViews(context.getPackageName(), R.layout.launcher_widget_cell);
        int count = AppLauncherModel.badge(id, badges);
        String name = folder == null ? AppLauncherModel.label(id, aliases, cache) : folder.name();
        if (folder != null) { Set<String> packages = new HashSet<>(); count = 0; for (String member : folder.members()) { android.content.ComponentName component = ActionCatalog.component(member); if (component != null && packages.add(component.getPackageName())) count += badges.getOrDefault(component.getPackageName(), 0); } }
        view.setTextViewText(R.id.launcher_label, name + (count > 0 ? " · " + count : "")); view.setTextColor(R.id.launcher_label, Ui.TEXT);
        view.setTextViewTextSize(R.id.launcher_label, TypedValue.COMPLEX_UNIT_SP, AppLauncherStyle.LABEL_SP);
        view.setContentDescription(R.id.launcher_cell, name + (folder == null ? "" : "，文件夹，" + folder.members().size() + "个应用") + (count > 0 ? "，" + count + "条活动通知" : ""));
        view.setViewVisibility(R.id.launcher_label, !pin && (prefs.workspaceLabels() || folder != null) ? View.VISIBLE : View.GONE);
        int iconSize = pin ? 28 : AppLauncherStyle.iconSize(prefs.workspaceDensity());
        Bitmap icon;
        if (folder == null) icon = icon(id);
        else {
            iconSize = Math.max(20, (int) Math.min(width - 8, height - 30)); icon = folderIcon(folder);
            view.setInt(R.id.launcher_cell, "setBackgroundResource", R.drawable.launcher_widget_folder);
            view.setColorStateList(R.id.launcher_cell, "setBackgroundTintList", ColorStateList.valueOf(AppLauncherStyle.FOLDER_COLORS[folder.color()]));
        }
        view.setViewLayoutWidth(R.id.launcher_icon, iconSize, TypedValue.COMPLEX_UNIT_DIP); view.setViewLayoutHeight(R.id.launcher_icon, iconSize, TypedValue.COMPLEX_UNIT_DIP);
        if (icon == null) view.setImageViewResource(R.id.launcher_icon, R.drawable.ic_ms_apps); else view.setImageViewBitmap(R.id.launcher_icon, icon);
        view.setOnClickPendingIntent(R.id.launcher_cell, folder == null ? launch(id) : action("folder", id)); return view;
    }
    private Bitmap icon(String id) {
        shownIcons.add(id); if (bitmaps.containsKey(id)) return bitmaps.get(id);
        Drawable drawable = cache.cachedIcon(context, id);
        if (!(drawable instanceof BitmapDrawable bitmap)) { cache.requestIcon(id); return null; }
        Bitmap result = Bitmap.createScaledBitmap(bitmap.getBitmap(), 64, 64, true); result.setDensity(Bitmap.DENSITY_NONE); bitmaps.put(id, result); return result;
    }
    private Bitmap folderIcon(AppWorkspaceLayout.Folder folder) {
        Bitmap preview = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888); preview.setDensity(Bitmap.DENSITY_NONE); Canvas canvas = new Canvas(preview);
        for (int i = 0; i < folder.members().size(); i++) { Bitmap icon = icon(folder.members().get(i)); if (icon != null) canvas.drawBitmap(icon, null, new android.graphics.Rect(i % 3 * 32 + 3, i / 3 * 32 + 3, i % 3 * 32 + 29, i / 3 * 32 + 29), null); }
        return preview;
    }
    private Intent intent(String operation, String item) {
        return new Intent().setData(new Uri.Builder().scheme("flipcover").authority("launcher-widget").appendPath(Integer.toString(widget)).appendPath(Integer.toString(display.getDisplayId())).appendPath(operation).appendPath(item).build())
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget).putExtra("display", display.getDisplayId()).putExtra("operation", operation).putExtra("item", item);
    }
    private PendingIntent action(String operation, String item) {
        return PendingIntent.getBroadcast(context, 0, intent(operation, item).setClass(context, LauncherWidgetProvider.class).setAction(LauncherWidgetProvider.ACTION), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private PendingIntent launch(String item) {
        return PendingIntent.getActivity(context, 0, intent("launch", item).setClass(context, LauncherWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE, ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle());
    }
    @android.annotation.TargetApi(31)
    private static void geometry(RemoteViews view, int id, float x, float y, float width, float height) {
        view.setViewLayoutWidth(id, width, TypedValue.COMPLEX_UNIT_DIP); view.setViewLayoutHeight(id, height, TypedValue.COMPLEX_UNIT_DIP);
        view.setViewLayoutMargin(id, RemoteViews.MARGIN_LEFT, x, TypedValue.COMPLEX_UNIT_DIP); view.setViewLayoutMargin(id, RemoteViews.MARGIN_TOP, y, TypedValue.COMPLEX_UNIT_DIP);
    }
}
