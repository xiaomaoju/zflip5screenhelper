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
    private ConnectivityDetails connectivity;
    private final java.util.ArrayList<SwitchRow> switches=new java.util.ArrayList<>();
    void torchChanged() { if (!closed) for (SwitchRow control:switches) if (control.id.equals("torch") && (!control.pending || java.util.Objects.equals(control.current(),control.requested))) control.complete(); }
    void statesChanged() { if (!closed) for (SwitchRow control:switches) if (!control.pending && !control.id.equals("torch")) control.complete(); }
    void stateChanged(String id) { if (!closed) for (SwitchRow control:switches) if (!control.pending && control.id.equals(id)) control.complete(); }
    void statesInvalidated() { if (!closed) for (SwitchRow control:switches) { if (control.id.equals("rotation")) control.complete(); else if (!control.id.equals("torch")) { control.requestGeneration++; control.finish(null,"状态未知 · 请刷新连接状态"); } } }
    private final Runnable routeTimeout = () -> { if (!closed && pendingRoute != null) { pendingRoute = null; outputs(); note("切换未确认，请使用系统输出选择"); } };
    private final MediaRouter2.RouteCallback routes = new MediaRouter2.RouteCallback() { @Override public void onRoutesUpdated(List<MediaRoute2Info> list) { if (!closed) outputs(); } };
    private final MediaRouter2.ControllerCallback controller = new MediaRouter2.ControllerCallback() { @Override public void onControllerUpdated(MediaRouter2.RoutingController c) { if (!closed) outputs(); } };
    private final MediaRouter2.TransferCallback transfers = new MediaRouter2.TransferCallback() {
        @Override public void onTransfer(MediaRouter2.RoutingController old, MediaRouter2.RoutingController next) { pendingRoute = null; owner.main.removeCallbacks(routeTimeout); if (!closed) outputs(); }
        @Override public void onTransferFailure(MediaRoute2Info route) { pendingRoute = null; owner.main.removeCallbacks(routeTimeout); if (!closed) { outputs(); note("切换失败，请使用系统输出选择"); } }
    };
    ControlDetails(CoverService owner, DetailSheet sheet) { this.owner = owner; context = owner.screenContext; this.sheet = sheet; sheet.panelStyle(); }
    void build(String id) {
        switch (id) {
            case "external_devices" -> {
                sheet.compactWidth(296, 1); sheet.stableWidth(); sheet.glassControls();
                sheet.extraHeader(PanelUi.icon(context, R.drawable.ic_ms_settings, "鼠标与触控板设置", () -> owner.openSettings("input_devices")));
                sheet.content.addView(new InputDevicePanel(context, true, owner.panelGlass));
            }
            case "nfc", "hotspot" -> { connectivity = new ConnectivityDetails(owner, sheet, this, id); connectivity.open(); }
            case "wifi" -> { sheet.stableViewport(300); sheet.extraHeader(Ui.iconButton(context, R.drawable.ic_ms_refresh, "刷新网络", () -> wifi(true))); sheet.footer("更多 WLAN 设置", () -> settings(Settings.ACTION_WIFI_SETTINGS)); wifi(false); }
            case "volume" -> {
                sheet.stableViewport(232);
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
                switches(id);
                note("需要 Shizuku。关闭选择会保留，折叠、亮屏或重新连接后自动恢复；服务未运行时限制会暂时失效。");
            }
            case "bluetooth" -> { switches(id); row("连接 / 配对设备", "由系统管理蓝牙设备", R.drawable.ic_ms_bluetooth, () -> settings(Settings.ACTION_BLUETOOTH_SETTINGS)); sheet.footer("更多蓝牙设置", () -> settings(Settings.ACTION_BLUETOOTH_SETTINGS)); }
            case "brightness" -> { sheet.title.setText("亮度与显示"); row("显示设置", "自动亮度 / 护眼由系统提供", R.drawable.ic_ms_brightness_6, () -> settings(Settings.ACTION_DISPLAY_SETTINGS)); }
            case "data" -> { switches(id); sheet.footer("SIM 与移动网络", () -> settings(Settings.ACTION_WIRELESS_SETTINGS)); }
            case "dnd" -> { switches(id); sheet.footer("勿扰时段与允许打扰", () -> settings(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS)); }
            case "airplane" -> { switches(id); row("Wi-Fi", "单独管理无线连接", R.drawable.ic_ms_wifi, () -> owner.showDetails("wifi", null)); row("蓝牙", "单独管理蓝牙设备", R.drawable.ic_ms_bluetooth, () -> owner.showDetails("bluetooth", null)); }
            case "torch" -> { switches("torch"); note("亮度档位由设备支持情况决定"); }
            case "screenshot" -> { row("立即截图", "保存外屏画面", R.drawable.ic_ms_screenshot, () -> owner.act("screenshot")); sheet.footer("查看截图", () -> owner.launch(new Intent(Intent.ACTION_VIEW, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI))); }
            case "lock" -> { row("立即锁屏", "", R.drawable.ic_ms_lock, () -> owner.act("lock")); row("暂停外屏助手", "可在设置中重新开启", R.drawable.ic_ms_accessibility_new, () -> owner.prefs.data.edit().putBoolean("enabled", false).apply()); }
            case "media" -> { sheet.compactWidth(228, 1); sheet.stableViewport(272); MediaDetailView media = new MediaDetailView(owner); sheet.content.addView(media); sheet.mediaHeader(media.toolbar()); sheet.onContentHeight(media::availableHeight); }
            case "apps", "app_hub" -> { row("常用应用", "添加 / 排序", R.drawable.ic_ms_apps, () -> owner.openSettings("favorites")); row("应用方向", "单独设置启动角度", R.drawable.ic_ms_screen_rotation, () -> owner.openSettings("orientations")); }
            default -> {
                if (id.startsWith("tile:")) { row("磁贴偏好", "应用提供的设置入口", R.drawable.ic_ms_settings, () -> owner.launch(new Intent(android.service.quicksettings.TileService.ACTION_QS_TILE_PREFERENCES).setPackage(ActionCatalog.component(id).getPackageName()).putExtra(Intent.EXTRA_COMPONENT_NAME, ActionCatalog.component(id)))); sheet.footer("应用信息", () -> owner.launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ActionCatalog.component(id).getPackageName())))); }
                else row("控制中心设置", "按钮与显示配置", R.drawable.ic_ms_tune, () -> owner.openSettings("panel"));
            }
        }
    }
    private void settings(String action) { owner.launch(new Intent(action)); }
    void note(String text) { TextView view = Ui.text(context, text, PanelUi.SECONDARY, Ui.MUTED); view.setPadding(0, Ui.dp(context, PanelUi.GAP), 0, Ui.dp(context, PanelUi.GAP)); sheet.content.addView(view, new LinearLayout.LayoutParams(-1, -2)); }
    private void switches(String id) {
        sheet.stableWidth();
        SwitchRow control=new SwitchRow(id,id.equals("rotation") ? "外屏自动旋转" : id.equals("system_controls") ? "内外屏控制中心" : ActionCatalog.label(context,id)); switches.add(control);
    }
    private final class SwitchRow {
        final String id; final LinearLayout line,choices; final TextView status; final GlassToggle toggle; boolean pending; Boolean confirmed,requested; int requestGeneration; final Runnable timeout=() -> { if (pending) finish(null,"状态未确认，请刷新或使用系统设置"); };
        SwitchRow(String id,String title) {
            this.id=id; line=(LinearLayout)row(title,"状态未知 · 整机开关",R.drawable.ic_ms_tune,this::click); status=(TextView)((LinearLayout)line.getChildAt(1)).getChildAt(1); status.setMinLines(2); status.setMaxLines(2); status.setEllipsize(android.text.TextUtils.TruncateAt.END);
            toggle=new GlassToggle(context,title,current(),this::click); toggle.setTag("detail-switch-"+id); line.addView(toggle); choices=Ui.row(context); choices.setVisibility(View.GONE); choices.addView(PanelUi.button(context,"开启",() -> request(true)),new LinearLayout.LayoutParams(0,-2,1)); choices.addView(PanelUi.button(context,"关闭",() -> request(false)),new LinearLayout.LayoutParams(0,-2,1)); sheet.content.addView(choices); complete();
        }
        Boolean current() { return id.equals("rotation") ? Boolean.valueOf(owner.rotationAutomatic()) : owner.on(id); }
        void click() { if (pending || closed) return; if (confirmed==null) { choices.setVisibility(choices.getVisibility()==View.VISIBLE ? View.GONE : View.VISIBLE); return; } request(!confirmed); }
        void request(boolean value) {
            if (pending || closed) return; pending=true; requested=value; int generation=++requestGeneration; Runnable finished=() -> { if (!closed && pending && generation==requestGeneration && line.isAttachedToWindow()) complete(); }; choices.setVisibility(View.GONE); line.setEnabled(false); toggle.setEnabled(false); toggle.pending(value); status.setText("正在切换…"); owner.main.removeCallbacks(timeout); owner.main.postDelayed(timeout,4000);
            if (id.equals("rotation")) { if (owner.rotationAutomatic()!=value) owner.toggleRotation(); complete(); }
            else if (id.equals("system_controls")) owner.setSystemControls(value ? 1 : 0,finished);
            else if (id.equals("torch")) owner.setTorch(value);
            else owner.setSwitch(id,value,finished);
        }
        void complete() { Boolean state=current(); finish(state,(state==null ? "状态未知 · 请选择开启或关闭" : state ? "已开启" : "已关闭")+(id.equals("rotation") ? " · 所选外屏" : " · 整机开关")); }
        void finish(Boolean state,String message) { if (closed) return; owner.main.removeCallbacks(timeout); pending=false; confirmed=state; line.setEnabled(true); toggle.setEnabled(true); toggle.state(state); status.setText(message); }
    }
    View row(String title, String subtitle, int icon, Runnable action) {
        LinearLayout row=PanelUi.row(context,icon,title,subtitle,action); sheet.content.addView(row,new LinearLayout.LayoutParams(-1,-2)); return row;
    }
    private void wifi(boolean refresh) {
        if (closed || loading) return; loading = true; for (SwitchRow control:switches) owner.main.removeCallbacks(control.timeout); switches.clear(); sheet.content.removeAllViews(); note("正在读取网络…");
        CoverApp.bridge(context).run("wifi_details", -1, refresh ? 1 : 0, "", result -> {
            if (closed) return; loading = false; sheet.content.removeAllViews(); switches("wifi");
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
        sheet.compactWidth(ControlRotationTabs.WIDTH+6*PanelUi.INSET,1);
        switches("rotation");
        // The pressed liquid thumb extends beyond the strip; retain the sheet's outer clipping.
        sheet.content.setClipChildren(false); sheet.content.setClipToPadding(false);
        ControlRotationTabs angles=new ControlRotationTabs(context,owner.display.getRotation(),angle -> { owner.lockRotation(angle); sheet.close(); });
        LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(-2,Ui.dp(context,PanelUi.SLOT)); size.gravity=android.view.Gravity.CENTER_HORIZONTAL; size.setMargins(Ui.dp(context,PanelUi.INSET),Ui.dp(context,3),Ui.dp(context,PanelUi.INSET),Ui.dp(context,3)); sheet.content.addView(angles,size);
        note("更改屏幕方向可能会让液态玻璃临时失效,届时请手动重启带有液态玻璃的页面");
        sheet.footer("交回系统旋转", () -> { owner.stopAutomaticRotation(); owner.shell("rotation_auto", 0, "", null); sheet.close(); });
    }
    @Override public void close() { closed = true; for (SwitchRow control:switches) owner.main.removeCallbacks(control.timeout); switches.clear(); if (connectivity != null) { connectivity.close(); connectivity = null; } owner.main.removeCallbacks(routeTimeout); if (router != null) { router.unregisterRouteCallback(routes); router.unregisterControllerCallback(controller); router.unregisterTransferCallback(transfers); router = null; } }
}
