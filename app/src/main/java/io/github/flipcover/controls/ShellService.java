package io.github.flipcover.controls;

import android.content.ComponentName;
import android.content.Context;
import android.os.Binder;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Runs only as a Shizuku UserService. No arbitrary command or shell string enters this boundary. */
public final class ShellService extends IShellService.Stub {
    private final int ownerUid;
    private final Context ownerContext;
    private final SystemControlCenter systemControlCenter = new SystemControlCenter();
    private long lastWifiScan;
    private SystemConnectivity connectivity;
    private SystemConnectivity connectivity() { if (connectivity == null) connectivity = new SystemConnectivity(ownerContext); return connectivity; }
    @Override public synchronized void watchConnectivity(IConnectivityListener listener) { checkCaller(); connectivity().watch(listener); }
    public ShellService() { ownerUid = -1; ownerContext = null; }
    public ShellService(Context context) { ownerUid = context.getApplicationInfo().uid; ownerContext = context; }
    private void checkCaller() {
        if (ownerUid < 0 || Binder.getCallingUid() != ownerUid) throw new SecurityException("Caller is not the owning application");
    }
    @Override public synchronized String execute(String operation, int displayId, int value, String component) {
        checkCaller();
        try {
            return switch (operation) {
                case "rotation" -> rotation(displayId, value, false);
                case "rotation_auto" -> rotation(displayId, 0, true);
                case "brightness", "brightness_read" -> brightness(displayId, value, operation.equals("brightness"));
                case "states" -> states();
                case "system_controls" -> systemControls(value);
                case "wifi_details" -> wifiDetails(value);
                case "connectivity_states", "nfc_details", "hotspot_details", "nfc", "hotspot", "nfc_secure", "hotspot_config" -> connectivity().execute(operation, displayId, value, component);
                case "wifi", "bluetooth", "data", "airplane", "dnd" -> switchSetting(operation, value);
                case "tile_add", "tile_click" -> tile(operation, component);
                case "diagnostics" -> diagnostics(displayId);
                case "recent_tasks", "recent_clear" -> recentTasks(displayId, component, operation.equals("recent_clear"));
                case "recent_open" -> reply(true, "任务恢复结果", new SystemRecentTasks(ownerContext, ownerUid).open(displayId, component).toString());
                case "native_home" -> nativeHome(displayId);
                default -> reply(false, "不支持的操作", "");
            };
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            return reply(false, "系统拒绝或接口不可用：" + cause.getClass().getSimpleName(), String.valueOf(cause.getMessage()));
        }
    }
    @Override public synchronized android.os.Bundle taskSnapshot(int displayId, String task) {
        checkCaller();
        try { return new SystemRecentTasks(ownerContext, ownerUid).snapshot(displayId, task); }
        catch (Exception error) { android.os.Bundle result = new android.os.Bundle(); result.putString("message", "预览不可用"); return result; }
    }
    private String recentTasks(int display, String request, boolean clear) throws Exception {
        JSONObject result = new SystemRecentTasks(ownerContext, ownerUid).execute(display, request, clear);
        String message = clear ? "已从系统移除 " + result.getInt("removed") + " 个任务" + (result.getInt("retained") > 0 ? "，部分任务仍保留" : "") : "已同步外屏最近任务";
        return reply(true, message, result.toString());
    }
    private String rotation(int display, int rotation, boolean automatic) throws Exception {
        secondary(display);
        if (rotation < 0 || rotation > 3) throw new IllegalArgumentException("Invalid rotation");
        Output fixed = run("wm", "fixed-to-user-rotation", "-d", "" + display, automatic ? "default" : "enabled");
        if (!fixed.ok()) return reply(false, "外屏旋转策略设置失败", fixed.text);
        Output write = automatic ? run("cmd", "window", "user-rotation", "-d", "" + display, "free")
            : run("cmd", "window", "user-rotation", "-d", "" + display, "lock", "" + rotation);
        if (!write.ok()) return reply(false, "外屏方向设置失败，可尝试交回系统", write.text);
        Output read = run("cmd", "window", "user-rotation", "-d", "" + display);
        String expected = automatic ? "free" : "lock " + rotation;
        boolean verified = read.ok() && read.text.trim().equals(expected);
        return reply(verified, verified ? automatic ? "外屏旋转已交回系统" : "系统已确认锁定 " + (rotation * 90) + "°" : "已提交旋转，但无法确认结果", read.text);
    }
    private String brightness(int display, int value, boolean write) throws Exception {
        activeSecondaryDisplay(display);
        Class<?> type = Class.forName("android.hardware.display.DisplayManagerGlobal");
        Object manager = type.getMethod("getInstance").invoke(null);
        if (write) {
            if (value < 5 || value > 100) throw new IllegalArgumentException("Brightness must be 5..100");
            java.lang.reflect.Method temporary = type.getMethod("setTemporaryBrightness", int.class, float.class);
            java.lang.reflect.Method persist = type.getMethod("setBrightness", int.class, float.class);
            // Match SystemUI's display-scoped slider: immediate brightness, then saved setting.
            temporary.invoke(manager, display, value / 100f);
            try { activeSecondaryDisplay(display); persist.invoke(manager, display, value / 100f); }
            catch (Exception error) {
                try { temporary.invoke(manager, display, Float.NaN); } catch (Exception ignored) { }
                throw error;
            }
        }
        java.lang.reflect.Method read = type.getMethod("getBrightness", int.class);
        activeSecondaryDisplay(display);
        float actual = (float) read.invoke(manager, display);
        // DPC applies updates asynchronously; this bounded confirmation is only for this request.
        for (int attempt = 0; write && attempt < 4 && (!Float.isFinite(actual) || Math.abs(actual - value / 100f) > .02f); attempt++) {
            android.os.SystemClock.sleep(60); activeSecondaryDisplay(display); actual = (float) read.invoke(manager, display);
        }
        if (!Float.isFinite(actual) || actual < 0 || actual > 1) return reply(false, "此显示器没有返回可用亮度", "");
        boolean confirmed = !write || Math.abs(actual - value / 100f) <= .02f;
        return reply(confirmed, !write ? "外屏亮度" : confirmed ? "已提交外屏亮度，系统设置返回 " + Math.round(actual * 100) + "%" : "请求 " + value + "% 后系统仍返回 " + Math.round(actual * 100) + "%，请检查系统自动亮度或显示限制", String.valueOf(actual));
    }
    private String nativeHome(int display) throws Exception {
        activeSecondaryDisplay(display);
        // Fixed component and owner user only; callers cannot supply an intent or shell command.
        Output result = run("am", "start", "-W", "--user", String.valueOf(ownerUid / 100000), "--display", String.valueOf(display), "-n", "com.android.systemui/.subscreen.SubHomeActivity", "-f", "0x10000000");
        activeSecondaryDisplay(display);
        boolean accepted = result.ok() && result.text.contains("Status: ok") && result.text.contains("Activity: com.android.systemui/.subscreen.SubHomeActivity");
        return reply(accepted, accepted ? "已返回三星原生卡片" : "三星未确认返回原生卡片，请重试", result.text);
    }
    private void activeSecondaryDisplay(int id) {
        secondary(id);
        if (ownerContext == null) throw new IllegalStateException("外屏服务未就绪");
        android.view.Display target = ownerContext.getSystemService(android.hardware.display.DisplayManager.class).getDisplay(id);
        if (target == null || !target.isValid() || target.getState() != android.view.Display.STATE_ON || ownerContext.getSystemService(android.app.KeyguardManager.class).isKeyguardLocked()) throw new IllegalStateException("目标外屏已改变、息屏或锁定");
    }
    private String states() throws Exception {
        JSONObject values = new JSONObject();
        String[][] pairs = {{"wifi", "wifi_on"}, {"bluetooth", "bluetooth_on"}, {"airplane", "airplane_mode_on"}, {"dnd", "zen_mode"}};
        // A generic mobile_data value can refer to the wrong SIM; keep it unknown.
        values.put("data", -1);
        for (String[] pair : pairs) {
            Output read = run("settings", "get", "global", pair[1]);
            String value = read.text.trim();
            values.put(pair[0], read.ok() && value.matches("[0-3]") ? Integer.parseInt(value) : -1);
        }
        values.put("system_controls", systemControlsState());
        values.put("nfc", connectivity().state("nfc")).put("hotspot", connectivity().state("hotspot"));
        return reply(true, "已读取系统状态", values.toString());
    }
    private int systemControlsState() throws Exception {
        Output read = run("dumpsys", "statusbar");
        return read.ok() ? SystemControlCenter.state(read.text) : -1;
    }
    private String systemControls(int value) throws Exception {
        if (value < -1 || value > 1) throw new IllegalArgumentException("Expected toggle, off or on");
        int before = systemControlsState();
        if (value == -1 && before < 0) return reply(false, "内外屏控制中心状态未知，请长按选择开启或关闭", "-1");
        boolean enabled = value == -1 ? before == 0 : value == 1;
        systemControlCenter.setEnabled(enabled);
        int after = systemControlsState();
        boolean confirmed = after == (enabled ? 1 : 0);
        String message = confirmed ? enabled ? "已开启内外屏控制中心" : "已关闭内外屏控制中心"
            : after < 0 ? "请求已提交，无法确认内外屏控制中心状态" : "已解除本工具限制，内外屏控制中心仍受其他系统限制";
        return reply(confirmed, message, String.valueOf(after));
    }
    private String wifiDetails(int refresh) throws Exception {
        if (refresh != 0 && refresh != 1) throw new IllegalArgumentException("Invalid scan request");
        String scanMessage = "扫描缓存";
        if (refresh == 1) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (lastWifiScan != 0 && now - lastWifiScan < 30000) scanMessage = "刷新过于频繁，稍后再试";
            else { lastWifiScan = now; Output scan = run("cmd", "wifi", "start-scan"); scanMessage = scan.ok() ? "扫描已请求，再次刷新可读取更新" : "系统未接受扫描"; }
        }
        Output status = run("cmd", "wifi", "status"), saved = run("cmd", "wifi", "list-networks"), nearby = run("cmd", "wifi", "list-scan-results");
        JSONObject data = new JSONObject().put("status", status.ok() ? status.text : "").put("saved", saved.ok() ? saved.text : "").put("nearby", nearby.ok() ? nearby.text : "").put("scanMessage", scanMessage);
        return reply(status.ok() || saved.ok() || nearby.ok(), "Wi-Fi 读取完成", data.toString());
    }
    private String switchSetting(String operation, int value) throws Exception {
        if (value != 0 && value != 1) throw new IllegalArgumentException("Expected 0 or 1");
        String verb = value == 1 ? "enable" : "disable";
        Output output = switch (operation) {
            case "wifi", "data" -> run("svc", operation, verb);
            case "bluetooth" -> run("cmd", "bluetooth_manager", verb);
            case "airplane" -> run("cmd", "connectivity", "airplane-mode", verb);
            case "dnd" -> run("cmd", "notification", "set_dnd", value == 1 ? "priority" : "all");
            default -> throw new IllegalArgumentException("Unknown setting");
        };
        return reply(output.ok(), output.ok() ? "请求已提交，请以刷新后的系统状态为准" : "系统开关操作失败", output.text);
    }
    private String tile(String operation, String encoded) throws Exception {
        ComponentName name = ComponentName.unflattenFromString(encoded);
        if (name == null || encoded.length() > 512) throw new IllegalArgumentException("Invalid tile component");
        Output help = run("cmd", "statusbar", "help");
        String command = operation.equals("tile_add") ? "add-tile" : "click-tile";
        if (!help.ok() || !help.text.contains(command)) return reply(false, "本机未提供此磁贴接口", help.text);
        if (operation.equals("tile_click")) {
            Output registered = run("settings", "get", "secure", "sysui_qs_tiles");
            if (!registered.ok() || !hasTile(registered.text, name)) return reply(false, "无法确认磁贴已在系统中注册，请先添加到系统快捷设置", registered.text);
        }
        Output output = run("cmd", "statusbar", command, name.flattenToString());
        return reply(output.ok(), output.ok() ? operation.equals("tile_add") ? "已提交系统磁贴注册，稍后可尝试点击" : "磁贴点击已发送；完成情况和状态由原应用决定" : "系统磁贴请求失败", output.text);
    }
    static boolean hasTile(String list, ComponentName wanted) {
        for (String entry : list.trim().split(",")) {
            String item = entry.trim();
            if (item.startsWith("custom(") && item.endsWith(")")) {
                ComponentName found = ComponentName.unflattenFromString(item.substring(7, item.length() - 1));
                if (wanted.equals(found)) return true;
            }
        }
        return false;
    }
    private String diagnostics(int display) throws Exception {
        StringBuilder report = new StringBuilder("Shizuku UserService uid=").append(android.os.Process.myUid()).append('\n');
        if (display > 0) {
            report.append("外屏尺寸：\n").append(run("wm", "size", "-d", "" + display).text);
            report.append("外屏密度：\n").append(run("wm", "density", "-d", "" + display).text);
            report.append("外屏旋转：\n").append(run("cmd", "window", "user-rotation", "-d", "" + display).text);
        }
        Output help = run("cmd", "statusbar", "help");
        report.append("系统磁贴 add-tile：").append(help.text.contains("add-tile")).append('\n');
        report.append("系统磁贴 click-tile：").append(help.text.contains("click-tile")).append('\n');
        return reply(true, "只读检测完成", report.toString());
    }
    private static void secondary(int id) { if (id <= 0) throw new IllegalArgumentException("A secondary display is required"); }
    private record Output(int code, String text) { boolean ok() { return code == 0 && !text.contains("Exception") && !text.contains("Error:"); } }
    private static Output run(String... arguments) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream stream = process.getInputStream()) {
                byte[] buffer = new byte[1024]; int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (bytes) { if (bytes.size() < 16384) bytes.write(buffer, 0, Math.min(count, 16384 - bytes.size())); }
                }
            } catch (Exception ignored) { }
        }, "shell-output");
        reader.setDaemon(true); reader.start();
        try {
            if (!process.waitFor(4, TimeUnit.SECONDS)) { process.destroyForcibly(); return new Output(-1, "操作超时"); }
            reader.join(500);
            synchronized (bytes) { return new Output(process.exitValue(), new String(bytes.toByteArray(), StandardCharsets.UTF_8)); }
        } finally {
            process.destroy();
            try { process.getInputStream().close(); process.getOutputStream().close(); } catch (Exception ignored) { }
        }
    }
    private static String reply(boolean ok, String message, String output) {
        try { return new JSONObject().put("ok", ok).put("message", message).put("output", output).toString(); }
        catch (Exception e) { return "{\"ok\":false,\"message\":\"结果编码失败\"}"; }
    }
    @Override public void destroy() {
        int caller = Binder.getCallingUid();
        if (caller == ownerUid || caller == 0 || caller == 2000) System.exit(0);
    }
}
