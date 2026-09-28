package io.github.flipcover.controls;

import android.content.Context;
import android.content.Intent;
import android.media.MediaRoute2Info;
import android.media.MediaRouter2;
import android.media.RouteDiscoveryPreference;
import android.net.Uri;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.List;

/** Real controls in the common sheet; readers and route callbacks end with the sheet. */
final class ControlDetails implements AutoCloseable {
    private final CoverService owner;
    private final Context context;
    private final DetailSheet sheet;
    private boolean closed, loading;
    private MediaRouter2 router;
    private String pendingRoute;
    private final Runnable routeTimeout = () -> { if (!closed && pendingRoute != null) { pendingRoute = null; outputs(); note("切换未确认，请使用系统输出选择"); } };
    private final MediaRouter2.RouteCallback routes = new MediaRouter2.RouteCallback() { @Override public void onRoutesUpdated(List<MediaRoute2Info> list) { if (!closed) outputs(); } };
    private final MediaRouter2.ControllerCallback controller = new MediaRouter2.ControllerCallback() { @Override public void onControllerUpdated(MediaRouter2.RoutingController c) { if (!closed) outputs(); } };
    private final MediaRouter2.TransferCallback transfers = new MediaRouter2.TransferCallback() {
        @Override public void onTransfer(MediaRouter2.RoutingController old, MediaRouter2.RoutingController next) { pendingRoute = null; owner.main.removeCallbacks(routeTimeout); if (!closed) outputs(); }
        @Override public void onTransferFailure(MediaRoute2Info route) { pendingRoute = null; owner.main.removeCallbacks(routeTimeout); if (!closed) { outputs(); note("切换失败，请使用系统输出选择"); } }
    };
    ControlDetails(CoverService owner, DetailSheet sheet) { this.owner = owner; context = owner.screenContext; this.sheet = sheet; }
    void build(String id) {
        switch (id) {
            case "wifi" -> { sheet.extraHeader(Ui.iconButton(context, R.drawable.ic_ms_refresh, "刷新网络", () -> wifi(true))); sheet.footer("更多 WLAN 设置", () -> settings(Settings.ACTION_WIFI_SETTINGS)); wifi(false); }
            case "volume" -> {
                sheet.title.setText("媒体输出"); sheet.footer("系统媒体输出", () -> owner.launch(new Intent(context, SystemOutputActivity.class).putExtra("display", owner.display.getDisplayId())));
                try { router = MediaRouter2.getInstance(context); router.registerRouteCallback(context.getMainExecutor(), routes, new RouteDiscoveryPreference.Builder(List.of(MediaRoute2Info.FEATURE_LIVE_AUDIO), false).build()); router.registerControllerCallback(context.getMainExecutor(), controller); router.registerTransferCallback(context.getMainExecutor(), transfers); outputs(); }
                catch (RuntimeException e) { note("无法读取输出，请使用系统选择器"); }
            }
            case "rotation" -> rotation();
            case "system_controls" -> {
                note("开启/关闭内外屏控制中心");
                note("三星原生下拉面板 · 本工具仍可使用");
                Boolean state = owner.on(id); note("系统标记：" + (state == null ? "未知" : state ? "已开启" : "已关闭"));
                if (!context.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) row("允许安卓提示（Toast）", "系统通知权限关闭，切换提示会被拦截", R.drawable.ic_ms_notifications, () -> owner.launch(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())));
                row("开启内外屏控制中心", "解除本工具的面板限制", R.drawable.ic_ms_toggle_on, () -> { owner.setSystemControls(1); owner.dismissDetails(); });
                row("关闭内外屏控制中心", "使用本工具的控制中心", R.drawable.ic_ms_toggle_off, () -> { owner.setSystemControls(0); owner.dismissDetails(); });
                note("需要 Shizuku。关闭选择会保留，折叠、亮屏或重新连接后自动恢复；服务未运行时限制会暂时失效。");
            }
            case "bluetooth" -> { switches(id); row("连接 / 配对设备", "由系统管理蓝牙设备", R.drawable.ic_ms_bluetooth, () -> settings(Settings.ACTION_BLUETOOTH_SETTINGS)); sheet.footer("更多蓝牙设置", () -> settings(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            case "brightness" -> { sheet.title.setText("亮度与显示"); row("显示设置", "自动亮度 / 护眼由系统提供", R.drawable.ic_ms_brightness_6, () -> settings(Settings.ACTION_DISPLAY_SETTINGS)); }
            case "data" -> { switches(id); sheet.footer("SIM 与移动网络", () -> settings(Settings.ACTION_WIRELESS_SETTINGS)); }
            case "dnd" -> { switches(id); sheet.footer("勿扰时段与允许打扰", () -> settings(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)); }
            case "airplane" -> { switches(id); row("Wi-Fi", "单独管理无线连接", R.drawable.ic_ms_wifi, () -> owner.showDetails("wifi", null)); row("蓝牙", "单独管理蓝牙设备", R.drawable.ic_ms_bluetooth, () -> owner.showDetails("bluetooth", null)); }
            case "torch" -> { row("手电筒开关", "与控制中心开关相同", R.drawable.ic_ms_flashlight_on, () -> owner.act("torch")); note("亮度档位由设备支持情况决定"); }
            case "screenshot" -> { row("立即截图", "保存外屏画面", R.drawable.ic_ms_screenshot, () -> owner.act("screenshot")); sheet.footer("查看截图", () -> owner.launch(new Intent(Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI))); }
            case "lock" -> { row("立即锁屏", "", R.drawable.ic_ms_lock, () -> owner.act("lock")); row("暂停外屏助手", "可在设置中重新开启", R.drawable.ic_ms_accessibility_new, () -> owner.prefs.data.edit().putBoolean("enabled", false).apply()); }
            case "media" -> { MediaDetailView media = new MediaDetailView(owner); sheet.content.addView(media); sheet.mediaHeader(media.toolbar()); sheet.onContentHeight(media::availableHeight); }
            case "apps", "app_hub" -> { row("常用应用", "添加 / 排序", R.drawable.ic_ms_apps, () -> owner.openSettings("favorites")); row("应用方向", "单独设置启动角度", R.drawable.ic_ms_screen_rotation, () -> owner.openSettings("orientations")); }
            default -> {
                if (id.startsWith("tile:")) { row("磁贴偏好", "应用提供的设置入口", R.drawable.ic_ms_settings, () -> owner.launch(new Intent(android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES).setPackage(ActionCatalog.component(id).getPackageName()).putExtra(Intent.EXTRA_COMPONENT_NAME, ActionCatalog.component(id)))); sheet.footer("应用信息", () -> owner.launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ActionCatalog.component(id).getPackageName())))); }
                else row("控制中心设置", "按钮与显示配置", R.drawable.ic_ms_tune, () -> owner.openSettings("panel"));
            }
        }
    }
    private void settings(String action) { owner.launch(new Intent(action)); }
    private void note(String text) { TextView view = Ui.text(context, text, 11, Ui.MUTED); view.setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6)); sheet.content.addView(view, new LinearLayout.LayoutParams(-1, -2)); }
    private void switches(String id) {
        Boolean state = owner.on(id); note("整机开关 · " + (state == null ? "状态未知" : state ? "已开启" : "已关闭"));
        row("开启", "", R.drawable.ic_ms_toggle_on, () -> { owner.setSwitch(id, true); owner.dismissDetails(); }); row("关闭", "", R.drawable.ic_ms_toggle_off, () -> { owner.setSwitch(id, false); owner.dismissDetails(); });
    }
    View row(String title, String subtitle, int icon, Runnable action) {
        LinearLayout row = Ui.row(context); row.setMinimumHeight(Ui.dp(context, 36)); row.setPadding(0, Ui.dp(context, 5), 0, Ui.dp(context, 5));
        ImageView image = new ImageView(context); image.setImageDrawable(Ui.icon(context, icon, Ui.TEXT)); image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(Ui.dp(context, 18), Ui.dp(context, 18)); imageParams.rightMargin = Ui.dp(context, 8); row.addView(image, imageParams);
        LinearLayout words = Ui.column(context); words.addView(Ui.text(context, title, 12, Ui.TEXT)); if (!subtitle.isEmpty()) { TextView sub = Ui.text(context, subtitle, 10, Ui.MUTED); sub.setPadding(0, Ui.dp(context, 2), 0, 0); words.addView(sub); }
        row.addView(words, new LinearLayout.LayoutParams(-2, -2, 1)); row.setBackground(Ui.ripple(context, android.graphics.Color.TRANSPARENT, 8)); row.setFocusable(true); row.setOnClickListener(v -> action.run()); sheet.content.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }
    private void wifi(boolean refresh) {
        if (closed || loading) return; loading = true; sheet.content.removeAllViews(); note("正在读取网络…");
        CoverApp.bridge(context).run("wifi_details", -1, refresh ? 1 : 0, "", result -> {
            if (closed) return; loading = false; sheet.content.removeAllViews();
            if (result.ok) try {
                JSONObject data = new JSONObject(result.output); List<WifiNetworks.Network> networks = WifiNetworks.read(data.optString("status"), data.optString("saved"), data.optString("nearby"));
                for (WifiNetworks.Network network : networks) {
                    LinearLayout line = (LinearLayout) row(network.name(), "", R.drawable.ic_ms_wifi, () -> settings(Settings.Panel.ACTION_WIFI));
                    line.setMinimumHeight(Ui.dp(context, 32)); line.setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4));
                    TextView state = Ui.text(context, network.connected() ? "已连接" : network.saved() ? "已保存" : "附近", 10, network.connected() ? Ui.ACCENT : Ui.MUTED); state.setPadding(Ui.dp(context, 8), 0, 0, 0); line.addView(state, new LinearLayout.LayoutParams(-2, -2));
                    line.setContentDescription(network.name() + "，" + state.getText() + "，点按使用系统选网");
                }
                if (networks.isEmpty()) note("未读取到网络，使用系统选网"); else note("点按网络进入系统选网");
                if (refresh) note(data.optString("scanMessage"));
            } catch (Exception e) { note("此系统的网络格式暂不兼容"); }
            else note(result.message);
            row("系统 Wi-Fi 选择", "", R.drawable.ic_ms_wifi, () -> settings(Settings.Panel.ACTION_WIFI));
        });
    }
    private void outputs() {
        if (closed || router == null) return; sheet.content.removeAllViews();
        try {
            MediaRouter2.RoutingController active = router.getSystemController();
            for (MediaRoute2Info route : active.getSelectedRoutes()) row(route.getName().toString(), "本机当前输出", R.drawable.ic_ms_volume_up, () -> { });
            List<MediaRoute2Info> available = android.os.Build.VERSION.SDK_INT >= 35 ? active.getTransferableRoutes() : List.of();
            for (MediaRoute2Info route : available) row(route.getName().toString(), route.getId().equals(pendingRoute) ? "切换中…" : "点按切换", R.drawable.ic_ms_volume_up, () -> {
                if (pendingRoute != null) return; pendingRoute = route.getId(); outputs();
                try { router.transferTo(route); owner.main.postDelayed(routeTimeout, 5000); } catch (RuntimeException e) { pendingRoute = null; outputs(); note("系统拒绝切换"); }
            });
            if (active.getSelectedRoutes().isEmpty()) note("没有可读取的本机输出"); note("投屏等远程会话请用系统选择器");
        } catch (RuntimeException e) { note("媒体路由暂不可用"); }
    }
    @android.annotation.SuppressLint("WrongConstant") // Four validated Surface rotation values, indexed 0..3.
    private void rotation() {
        row("外屏自动旋转", owner.rotationAutomatic() ? "开启 · 点按锁定" : "关闭 · 点按开启", R.drawable.ic_ms_screen_rotation, () -> { owner.toggleRotation(); owner.dismissDetails(); });
        for (int start = 0; start < 4; start += 2) {
            LinearLayout angles = Ui.row(context);
            for (int i = start; i < start + 2; i++) { int angle = i; android.widget.Button button = Ui.button(context, i * 90 + "°", () -> { owner.lockRotation(angle); owner.dismissDetails(); }); button.setTag("detail-angle-" + i); Ui.select(button, !owner.rotationAutomatic() && owner.display.getRotation() == i); LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(Ui.dp(context, 60), Ui.dp(context, 42), 1); p.setMargins(Ui.dp(context, 2), Ui.dp(context, 3), Ui.dp(context, 2), Ui.dp(context, 3)); angles.addView(button, p); }
            sheet.content.addView(angles);
        }
        sheet.footer("交回系统旋转", () -> { owner.stopAutomaticRotation(); owner.shell("rotation_auto", 0, "", null); owner.dismissDetails(); });
    }
    @Override public void close() { closed = true; owner.main.removeCallbacks(routeTimeout); if (router != null) { router.unregisterRouteCallback(routes); router.unregisterControllerCallback(controller); router.unregisterTransferCallback(transfers); router = null; } }
}
