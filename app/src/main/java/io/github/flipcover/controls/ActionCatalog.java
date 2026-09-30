package io.github.flipcover.controls;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.service.quicksettings.TileService;
import java.util.ArrayList;
import java.util.List;

public final class ActionCatalog {
    public record Action(String id, String label, int iconResource) { }
    public static final List<Action> BUILT_INS = List.of(
        new Action("app_dock", "应用 Dock", R.drawable.ic_app_dock),
        new Action("app_hub", "快捷应用中心", R.drawable.ic_ms_apps),
        new Action("controls", "快捷设置", R.drawable.ic_ms_tune),
        new Action("system_controls", "系统控制中心", R.drawable.ic_ms_toggle_on),
        new Action("rotation", "旋转方向", R.drawable.ic_ms_screen_rotation),
        new Action("notifications", "通知", R.drawable.ic_ms_notifications),
        new Action("notification_list", "应用内通知", R.drawable.ic_ms_notifications),
        new Action("back", "返回", R.drawable.ic_ms_arrow_back),
        new Action("home", "主页", R.drawable.ic_ms_home),
        new Action("recents", "外屏多任务", R.drawable.ic_ms_view_carousel),
        new Action("system_recents", "系统最近任务（显示位置由系统决定）", R.drawable.ic_ms_view_carousel),
        new Action("wifi", "Wi-Fi", R.drawable.ic_ms_wifi),
        new Action("bluetooth", "蓝牙", R.drawable.ic_ms_bluetooth),
        new Action("data", "移动数据", R.drawable.ic_ms_swap_vert),
        new Action("nfc", "NFC", R.drawable.ic_nfc),
        new Action("hotspot", "移动热点", R.drawable.ic_hotspot),
        new Action("torch", "手电筒", R.drawable.ic_ms_flashlight_on),
        new Action("dnd", "勿扰", R.drawable.ic_ms_do_not_disturb_on),
        new Action("airplane", "飞行模式", R.drawable.ic_ms_flight),
        new Action("screenshot", "外屏截图", R.drawable.ic_ms_screenshot),
        new Action("lock", "锁屏", R.drawable.ic_ms_lock),
        new Action("media", "媒体控制", R.drawable.ic_ms_play_pause),
        new Action("apps", "应用", R.drawable.ic_ms_apps),
        new Action("configure", "配置快捷栏", R.drawable.ic_ms_settings)
    );
    public static boolean valid(String id) {
        if (id == null) return false;
        if (BUILT_INS.stream().anyMatch(a -> a.id.equals(id))) return true;
        return (id.startsWith("tile:") || id.startsWith("app:")) && component(id) != null;
    }
    public static ComponentName component(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? null : ComponentName.unflattenFromString(id.substring(separator + 1));
    }
    public static String label(Context context, String id) {
        for (Action action : BUILT_INS) if (action.id.equals(id)) return action.label;
        String cached = CoverApp.catalog(context).label(id); if (cached != null) return cached;
        try {
            ComponentName component = component(id);
            if (id.startsWith("tile:")) return context.getPackageManager().getServiceInfo(component, 0).loadLabel(context.getPackageManager()).toString();
            if (id.startsWith("app:")) return context.getPackageManager().getActivityInfo(component, 0).loadLabel(context.getPackageManager()).toString();
        } catch (Exception ignored) { }
        return "应用已移除";
    }
    public static Drawable icon(Context context, String id) {
        Drawable cached = CoverApp.catalog(context).cachedIcon(context, id); return cached != null ? cached : loadIcon(context, id);
    }
    static Drawable loadIcon(Context context, String id) {
        try {
            if (id.startsWith("tile:")) {
                android.content.pm.ServiceInfo service = context.getPackageManager().getServiceInfo(component(id), 0);
                Drawable icon = service.loadIcon(context.getPackageManager()).mutate();
                // Tile glyphs are masks intended for host tinting; app-icon fallbacks retain their colors.
                if (service.icon != 0) icon.setTint(Ui.TEXT);
                return icon;
            }
            if (id.startsWith("app:")) return context.getPackageManager().getActivityIcon(component(id));
        } catch (Exception ignored) { }
        int resource = R.drawable.ic_ms_help;
        for (Action action : BUILT_INS) if (action.id.equals(id)) resource = action.iconResource;
        Drawable result = context.getDrawable(resource).mutate();
        result.setTint(Ui.TEXT);
        return result;
    }
    public static List<String> applicationTiles(Context context) {
        List<String> ids = new ArrayList<>();
        for (ResolveInfo info : context.getPackageManager().queryIntentServices(new Intent(TileService.ACTION_QS_TILE), PackageManager.GET_META_DATA)) {
            if (info.serviceInfo != null && info.serviceInfo.exported && info.serviceInfo.enabled && "android.permission.BIND_QUICK_SETTINGS_TILE".equals(info.serviceInfo.permission)) {
                ids.add("tile:" + new ComponentName(info.serviceInfo.packageName, info.serviceInfo.name).flattenToString());
            }
        }
        ids.sort((a, b) -> label(context, a).compareToIgnoreCase(label(context, b)));
        return ids;
    }
    public static List<String> applications(Context context) {
        List<String> ids = new ArrayList<>();
        for (AppCatalogCache.Entry entry : CoverApp.catalog(context).entriesBlocking()) ids.add(entry.id());
        return ids;
    }
}
