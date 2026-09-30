package io.github.flipcover.controls;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

/** Fixed NFC / Wi-Fi tethering operations behind ShellService's owning UID check. */
final class SystemConnectivity implements AutoCloseable {
    private static final String SHELL = "com.android.shell";
    private final Context context;
    private WifiManager wifi;
    private Object nfc;
    private HandlerThread eventThread;
    private Object softApCallback;
    private volatile IConnectivityListener listener;
    private volatile List<String> clients;
    private volatile int[] bands = new int[0];
    private volatile int watchGeneration;
    private final IBinder.DeathRecipient listenerDeath = this::close;

    SystemConnectivity(Context context) { this.context = context; }
    static Object service(String name, String contract) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, name);
        if (binder == null) throw new IllegalStateException("系统服务暂不可用");
        return Class.forName(contract + "$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
    }
    private Object nfc() throws Exception { if (nfc == null) nfc = service("nfc", "android.nfc.INfcAdapter"); return nfc; }
    private Object nfcCall(String name, Class<?>[] types, Object... args) throws Exception { return Class.forName("android.nfc.INfcAdapter").getMethod(name, types).invoke(nfc(), args); }
    private WifiManager wifi() throws Exception {
        if (wifi == null) {
            Context identity = Build.VERSION.SDK_INT >= 31 ? new ShellContext(context) : new ContextWrapper(context) { @Override public String getOpPackageName() { return SHELL; } @Override public String getPackageName() { return SHELL; } };
            Class<?> contract = Class.forName("android.net.wifi.IWifiManager"); Object remote = service("wifi", contract.getName());
            try { wifi = (WifiManager) WifiManager.class.getConstructor(Context.class, contract).newInstance(identity, remote); }
            catch (NoSuchMethodException old) { wifi = (WifiManager) WifiManager.class.getConstructor(Context.class, contract, android.os.Looper.class).newInstance(identity, remote, android.os.Looper.getMainLooper()); }
        }
        return wifi;
    }
    @android.annotation.TargetApi(31)
    private static final class ShellContext extends ContextWrapper {
        ShellContext(Context base) { super(base); }
        @Override public String getPackageName() { return SHELL; }
        @Override public String getOpPackageName() { return SHELL; }
        @Override public android.content.AttributionSource getAttributionSource() { return new android.content.AttributionSource.Builder(Process.myUid()).setPackageName(SHELL).build(); }
    }
    private Object wifiCall(String name, Class<?>[] types, Object... args) throws Exception { return WifiManager.class.getMethod(name, types).invoke(wifi(), args); }
    int state(String id) {
        try { return id.equals("nfc") ? HotspotConfiguration.nfcState((int) nfcCall("getState", new Class<?>[0])) : HotspotConfiguration.hotspotState((int) wifiCall("getWifiApState", new Class<?>[0])); }
        catch (Exception error) { return -1; }
    }
    private void target(int id) {
        if (context == null || id <= 0) throw new IllegalStateException("需要可用的所选外屏");
        Display display = context.getSystemService(DisplayManager.class).getDisplay(id);
        if (display == null || !display.isValid() || display.getState() != Display.STATE_ON || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new IllegalStateException("目标外屏已改变、息屏或锁定");
    }
    String execute(String operation, int display, int value, String input) {
        try {
            target(display);
            return switch (operation) {
                case "connectivity_states" -> reply(true, "已读取状态", new JSONObject().put("nfc", state("nfc")).put("hotspot", state("hotspot")));
                case "nfc_details" -> reply(true, "已读取 NFC", nfcDetails());
                case "hotspot_details" -> reply(true, "已读取热点", hotspotDetails());
                case "nfc", "hotspot" -> change(operation, display, value);
                case "nfc_secure" -> secure(display, value);
                case "hotspot_config" -> configure(display, input);
                default -> reply(false, "不支持的连接操作", null);
            };
        } catch (IllegalArgumentException error) { return reply(false, error.getMessage(), null); }
        catch (IllegalStateException error) { return reply(false, error.getMessage(), null); }
        catch (Exception error) { return reply(false, "系统拒绝或接口不可用，请使用详情中的系统入口", null); }
    }
    private JSONObject nfcDetails() throws Exception {
        boolean supported = context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_NFC);
        JSONObject data = new JSONObject().put("supported", supported).put("state", state("nfc")).put("secure", -1).put("secureSupported", false).put("payment", "暂不可用");
        if (!supported) return data;
        try { boolean secure = (boolean) nfcCall("deviceSupportsNfcSecure", new Class<?>[0]); data.put("secureSupported", secure); if (secure) data.put("secure", (boolean) nfcCall("isNfcSecureEnabled", new Class<?>[0]) ? 1 : 0); } catch (Exception ignored) { }
        try {
            String saved = Settings.Secure.getString(context.getContentResolver(), "nfc_payment_default_component"); ComponentName component = saved == null ? null : ComponentName.unflattenFromString(saved);
            data.put("payment", component == null ? "未设置" : context.getPackageManager().getServiceInfo(component, 0).loadLabel(context.getPackageManager()).toString());
        } catch (Exception ignored) { }
        return data;
    }
    private String change(String id, int display, int value) throws Exception {
        if (value < -1 || value > 1) throw new IllegalArgumentException("无效开关值");
        int before = state(id); if (value == -1 && before < 0) return reply(false, "状态未知或正在切换，请长按选择开启或关闭", null);
        boolean enabled = value == -1 ? before == 0 : value == 1; target(display);
        if (id.equals("nfc")) {
            Object accepted;
            try { accepted = enabled ? nfcCall("enable", new Class<?>[]{String.class}, SHELL) : nfcCall("disable", new Class<?>[]{boolean.class, String.class}, true, SHELL); }
            catch (NoSuchMethodException old) { accepted = enabled ? nfcCall("enable", new Class<?>[0]) : nfcCall("disable", new Class<?>[]{boolean.class}, true); }
            if (!Boolean.TRUE.equals(accepted)) return reply(false, "系统未接受 NFC 开关请求", null);
        } else tether(enabled);
        boolean confirmed = waitState(id, enabled ? 1 : 0, display);
        return reply(confirmed, confirmed ? "已" + (enabled ? "开启" : "关闭") + (id.equals("nfc") ? " NFC" : "移动热点") : "请求已提交，状态尚未确认，请刷新", new JSONObject().put(id, state(id)));
    }
    private String secure(int display, int value) throws Exception {
        if (value != 0 && value != 1) throw new IllegalArgumentException("无效开关值");
        if (!(boolean) nfcCall("deviceSupportsNfcSecure", new Class<?>[0])) return reply(false, "此设备不支持仅解锁时使用 NFC", null);
        target(display); boolean accepted = (boolean) nfcCall("setNfcSecure", new Class<?>[]{boolean.class}, value == 1);
        boolean actual = (boolean) nfcCall("isNfcSecureEnabled", new Class<?>[0]);
        return reply(accepted && actual == (value == 1), accepted && actual == (value == 1) ? "已更新 NFC 使用限制" : "系统未确认使用限制，请刷新", nfcDetails());
    }
    private boolean waitState(String id, int wanted, int display) {
        long end = SystemClock.uptimeMillis() + 3000;
        do { target(display); if (state(id) == wanted) return true; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < end);
        return false;
    }
    /** Uses the tethering service, not startSoftAp (which alone cannot share internet). */
    private void tether(boolean enabled) throws Exception {
        Class<?> contract = Class.forName("android.net.ITetheringConnector"), callbackType = Class.forName("android.net.IIntResultListener");
        Object connector = service("tethering", contract.getName()); ResultCallback callback = new ResultCallback();
        Object listener = Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType}, (proxy, method, args) -> {
            if (method.getName().equals("asBinder")) return callback;
            if (method.getName().equals("onResult")) { callback.result = (int) args[0]; callback.done.countDown(); return null; }
            return objectMethod(proxy, method, args);
        });
        if (enabled) {
            Object builder = Class.forName("android.net.TetheringManager$TetheringRequest$Builder").getConstructor(int.class).newInstance(0);
            Object request = builder.getClass().getMethod("build").invoke(builder); Object parcel = request.getClass().getMethod("getParcel").invoke(request);
            // Default builder preserves saved hotspot settings and required carrier provisioning.
            try { contract.getMethod("startTethering", parcel.getClass(), String.class, String.class, callbackType).invoke(connector, parcel, SHELL, null, listener); }
            catch (NoSuchMethodException old) { contract.getMethod("startTethering", parcel.getClass(), String.class, callbackType).invoke(connector, parcel, SHELL, listener); }
        } else {
            try { contract.getMethod("stopTethering", int.class, String.class, String.class, callbackType).invoke(connector, 0, SHELL, null, listener); }
            catch (NoSuchMethodException old) { contract.getMethod("stopTethering", int.class, String.class, callbackType).invoke(connector, 0, SHELL, listener); }
        }
        if (!callback.done.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("热点请求尚未确认，请刷新状态");
        if (callback.result != 0) throw new IllegalStateException("系统未接受热点请求，错误码 " + callback.result);
    }
    private static final class ResultCallback extends Binder {
        final CountDownLatch done = new CountDownLatch(1); volatile int result = -1;
        ResultCallback() { attachInterface(null, "android.net.IIntResultListener"); }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString("android.net.IIntResultListener"); return true; }
            if (code == FIRST_CALL_TRANSACTION) { data.enforceInterface("android.net.IIntResultListener"); result = data.readInt(); done.countDown(); return true; }
            return super.onTransact(code, data, reply, flags);
        }
    }
    private SoftApConfiguration config() throws Exception { return (SoftApConfiguration) wifiCall("getSoftApConfiguration", new Class<?>[0]); }
    private static Object field(SoftApConfiguration config, String getter) throws Exception { return SoftApConfiguration.class.getMethod(getter).invoke(config); }
    private static String revision(SoftApConfiguration config) throws Exception {
        Parcel parcel = Parcel.obtain(); byte[] bytes = null;
        try { config.writeToParcel(parcel, 0); bytes = parcel.marshall(); byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder text = new StringBuilder(); for (byte item : digest) text.append(String.format(java.util.Locale.ROOT, "%02x", item)); return text.toString(); }
        finally { if (bytes != null) Arrays.fill(bytes, (byte) 0); parcel.recycle(); }
    }
    private JSONObject hotspotDetails() throws Exception {
        JSONObject data = new JSONObject().put("state", state("hotspot")).put("configAvailable", false).put("clientsAvailable", clients != null).put("clients", new JSONArray(clients == null ? List.of() : clients)).put("bands", new JSONArray()).put("timeoutAvailable", false);
        JSONArray supported = new JSONArray(); for (int band : bands) supported.put(band); data.put("bands", supported);
        try {
            SoftApConfiguration saved = config(); data.put("name", saved.getSsid()).put("password", saved.getPassphrase() == null ? "" : saved.getPassphrase()).put("security", saved.getSecurityType()).put("hidden", saved.isHiddenSsid()).put("revision", revision(saved)).put("configAvailable", true);
            try { data.put("band", field(saved, "getBand")); } catch (Exception ignored) { data.put("band", -1); }
            try { boolean automatic = (boolean) field(saved, "isAutoShutdownEnabled"); data.put("timeout", HotspotConfiguration.timeoutValue(automatic, (long) field(saved, "getShutdownTimeoutMillis"))).put("timeoutAvailable", true); } catch (Exception ignored) { }
        } catch (Exception ignored) { }
        return data;
    }
    private String configure(int display, String input) throws Exception {
        if (input == null || input.length() > 4096) throw new IllegalArgumentException("热点配置请求无效");
        JSONObject patch = new JSONObject(input);
        Set<String> allowed = Set.of("revision", "restart", "name", "password", "band", "timeout");
        for (var keys = patch.keys(); keys.hasNext();) if (!allowed.contains(keys.next())) throw new IllegalArgumentException("包含不支持的热点配置");
        if (!(patch.opt("revision") instanceof String token) || !token.matches("[0-9a-f]{64}") || !(patch.opt("restart") instanceof Boolean)) throw new IllegalArgumentException("热点配置身份无效");
        SoftApConfiguration before = config(); if (!revision(before).equals(token)) return configFailure("热点配置已被其他操作修改，请重新读取后编辑", "conflict", display);
        Object builder = Class.forName("android.net.wifi.SoftApConfiguration$Builder").getConstructor(SoftApConfiguration.class).newInstance(before);
        boolean changed = false;
        if (patch.has("name")) { if (!(patch.get("name") instanceof String name)) throw new IllegalArgumentException("热点名称无效"); HotspotConfiguration.validateName(name); builder.getClass().getMethod("setSsid", String.class).invoke(builder, name); changed = true; }
        if (patch.has("password")) { if (!(patch.get("password") instanceof String password)) throw new IllegalArgumentException("热点密码无效"); HotspotConfiguration.validatePassword(password, before.getSecurityType()); builder.getClass().getMethod("setPassphrase", String.class, int.class).invoke(builder, password, before.getSecurityType()); changed = true; }
        if (patch.has("band")) { int band = integer(patch.get("band")); if ((band != 1 && band != 2 && band != 4) || Arrays.stream(bands).noneMatch(item -> item == band)) throw new IllegalArgumentException("此热点频段尚未确认受设备支持"); builder.getClass().getMethod("setBand", int.class).invoke(builder, band); changed = true; }
        if (patch.has("timeout")) { long timeout = integer(patch.get("timeout")); if (!HotspotConfiguration.validTimeout(timeout)) throw new IllegalArgumentException("无效自动关闭时间"); builder.getClass().getMethod("setAutoShutdownEnabled", boolean.class).invoke(builder, timeout >= 0); if (timeout >= 0) builder.getClass().getMethod("setShutdownTimeoutMillis", long.class).invoke(builder, timeout == 0 && Build.VERSION.SDK_INT >= 33 ? -1L : timeout); changed = true; }
        if (!changed) throw new IllegalArgumentException("没有可应用的热点修改");
        SoftApConfiguration after = (SoftApConfiguration) builder.getClass().getMethod("build").invoke(builder);
        int active = state("hotspot"); if (active < 0) return reply(false, "热点正在切换或状态未知，请稍后再试", null);
        if (active == 1 && !patch.getBoolean("restart")) return reply(false, "热点开启中，请确认应用并重启", null);
        target(display);
        if (before.equals(after)) return reply(true, "配置没有变化", null);
        boolean stopped = false, stored = false;
        try {
            if (active == 1) {
                tether(false); stopped = waitState("hotspot", 0, display);
                if (!stopped) return configFailure("尚未确认热点关闭，未提交配置", "unchanged", display);
            }
            target(display);
            if (!revision(config()).equals(token)) return configFailure("检测到外部配置变化，已停止提交；请重新读取", "conflict", display);
            if (!Boolean.TRUE.equals(wifiCall("setSoftApConfiguration", new Class<?>[]{SoftApConfiguration.class}, after))) {
                boolean restored = !stopped || restoreIfUnchanged(before, display);
                return configFailure(restored ? "系统未接受配置，原设置已保留" : "系统未接受配置，请检查热点状态", "unchanged", display);
            }
            stored = true;
            SoftApConfiguration actual = config();
            if (!requestedFieldsMatch(patch, before, actual)) return configFailure("系统采用了不同配置，请重新读取后确认", "changed", display);
            if (active == 1) {
                target(display); tether(true);
                if (!waitState("hotspot", 1, display)) return configFailure("配置已保存，热点开启尚未确认", "saved", display);
            }
            boolean normalizedBand = patch.has("band") && (int) field(actual, "getBand") != patch.getInt("band");
            return reply(true, (active == 1 ? "配置已保存并重新开启热点" : "热点配置已保存") + (normalizedBand ? "；系统已调整为兼容频段" : ""), new JSONObject().put("stage", "saved").put("state", state("hotspot")));
        } catch (Exception error) {
            boolean restored = !stored && stopped && restoreIfUnchanged(before, display);
            return configFailure(stored ? "配置已提交，但热点重启或回读未完成" : restored ? "提交未完成，原热点已恢复" : "提交未完成，请检查当前热点状态", stored ? "submitted" : "unknown", display);
        }
    }
    /** Android normalizes non-user fields (for example random MAC and userConfiguration). */
    private boolean requestedFieldsMatch(JSONObject patch, SoftApConfiguration before, SoftApConfiguration actual) throws Exception {
        if (actual.getSecurityType() != before.getSecurityType()) return false;
        if (patch.has("name") && !patch.getString("name").equals(actual.getSsid())) return false;
        if (patch.has("password") && !patch.getString("password").equals(actual.getPassphrase())) return false;
        if (patch.has("band") && (((int) field(actual, "getBand")) & patch.getInt("band")) == 0) return false;
        if (patch.has("timeout") && HotspotConfiguration.timeoutValue((boolean) field(actual, "isAutoShutdownEnabled"), (long) field(actual, "getShutdownTimeoutMillis")) != patch.getLong("timeout")) return false;
        return true;
    }
    private boolean restoreIfUnchanged(SoftApConfiguration original, int display) {
        try { target(display); if (!original.equals(config())) return false; if (state("hotspot") == 1) return true; if (state("hotspot") != 0) return false; tether(true); return waitState("hotspot", 1, display); }
        catch (Exception ignored) { return false; }
    }
    private String configFailure(String message, String stage, int display) {
        int actual = state("hotspot");
        try { return reply(false, message + "；热点" + (actual == 0 ? "已关闭" : actual == 1 ? "已开启" : "状态待确认"), new JSONObject().put("stage", stage).put("state", actual).put("reload", true)); }
        catch (Exception ignored) { return reply(false, message, null); }
    }
    private static int integer(Object raw) {
        if (!(raw instanceof Number value) || value.doubleValue() != value.intValue()) throw new IllegalArgumentException("配置数值无效");
        return value.intValue();
    }
    synchronized void watch(IConnectivityListener next) {
        close(); if (next == null) return;
        listener = next; final int generation = watchGeneration;
        try {
            next.asBinder().linkToDeath(listenerDeath, 0); eventThread = new HandlerThread("hotspot-events"); eventThread.start(); Handler handler = new Handler(eventThread.getLooper());
            Class<?> type = Class.forName("android.net.wifi.WifiManager$SoftApCallback");
            softApCallback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) return objectMethod(proxy, method, args);
                if (generation != watchGeneration) return null;
                if (method.getName().equals("onConnectedClientsChanged") && args.length == 1 && args[0] instanceof List<?> list) {
                    List<String> found = new ArrayList<>();
                    for (Object client : list) { if (found.size() == 64) break; try { String address = String.valueOf(client.getClass().getMethod("getMacAddress").invoke(client)); found.add(address.length() >= 5 ? "地址尾号 " + address.substring(address.length() - 5) : "连接设备"); } catch (Exception ignored) { found.add("连接设备"); } }
                    clients = List.copyOf(found);
                } else if (method.getName().equals("onCapabilityChanged")) {
                    ArrayList<Integer> supported = new ArrayList<>(); for (int band : new int[]{1, 2, 4}) try { if (((int[]) args[0].getClass().getMethod("getSupportedChannelList", int.class).invoke(args[0], band)).length > 0) supported.add(band); } catch (Exception ignored) { }
                    bands = supported.stream().mapToInt(Integer::intValue).toArray();
                }
                IConnectivityListener current = listener; if (current != null) try { current.onChanged(); } catch (RemoteException ignored) { }
                return null;
            });
            wifiCall("registerSoftApCallback", new Class<?>[]{Executor.class, type}, (Executor) handler::post, softApCallback);
        } catch (Exception error) { close(); }
    }
    private static Object objectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) { case "hashCode" -> System.identityHashCode(proxy); case "equals" -> proxy == args[0]; case "toString" -> "ConnectivityCallback"; default -> null; };
    }
    @Override public synchronized void close() {
        watchGeneration++; IConnectivityListener old = listener; listener = null;
        if (old != null) try { old.asBinder().unlinkToDeath(listenerDeath, 0); } catch (RuntimeException ignored) { }
        if (softApCallback != null && wifi != null) try { wifiCall("unregisterSoftApCallback", new Class<?>[]{Class.forName("android.net.wifi.WifiManager$SoftApCallback")}, softApCallback); } catch (Exception ignored) { }
        softApCallback = null; clients = null; bands = new int[0];
        if (eventThread != null) { eventThread.quitSafely(); eventThread = null; }
    }
    private static String reply(boolean ok, String message, JSONObject output) {
        try { return new JSONObject().put("ok", ok).put("message", message).put("output", output == null ? "" : output.toString()).toString(); }
        catch (Exception error) { return "{\"ok\":false,\"message\":\"结果不可用\"}"; }
    }
}
