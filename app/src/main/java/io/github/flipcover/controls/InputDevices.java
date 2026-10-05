package io.github.flipcover.controls;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.InputEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.IntConsumer;
import org.json.JSONObject;

/** Local device preferences and event-driven discovery. Never owns a window or injects input. */
final class InputDevices implements InputManager.InputDeviceListener {
    record Device(int id, String key, String name, String type, boolean ambiguous) { }
    private final InputManager manager;
    final Prefs prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Set<Runnable> observers = new LinkedHashSet<>();
    private final SharedPreferences.OnSharedPreferenceChangeListener preference = (data, key) -> { if (key == null || key.startsWith("input_")) changed(); };
    private IntConsumer identify;
    private List<Device> inventory = List.of();
    private JSONObject records = new JSONObject();
    private boolean keyboard;
    private java.lang.ref.WeakReference<InputSurface> captureOwner = new java.lang.ref.WeakReference<>(null);
    private final Runnable stopIdentify = () -> { identify = null; changed(); };
    int lastDevice = -1;
    boolean suspended;
    String status = "等待设备操作";
    InputDevices(Context context) { manager = context.getSystemService(InputManager.class); prefs = new Prefs(context); reload(); }
    static boolean pointer(InputDevice d) { return d != null && !d.isVirtual() && (d.supportsSource(InputDevice.SOURCE_MOUSE) || d.supportsSource(InputDevice.SOURCE_MOUSE_RELATIVE) || d.supportsSource(InputDevice.SOURCE_TOUCHPAD) || d.supportsSource(InputDevice.SOURCE_TOUCH_NAVIGATION)); }
    private List<Device> scan() {
        List<Device> result = new ArrayList<>();
        for (int id : manager.getInputDeviceIds()) { InputDevice d = manager.getInputDevice(id); if (!pointer(d)) continue; String key = d.getDescriptor();
            int duplicates = 0; for (int other : manager.getInputDeviceIds()) { InputDevice peer = manager.getInputDevice(other); if (pointer(peer) && key.equals(peer.getDescriptor())) duplicates++; }
            result.add(new Device(id, key, d.getName(), d.supportsSource(InputDevice.SOURCE_TOUCHPAD) ? "触控板" : d.supportsSource(InputDevice.SOURCE_TOUCH_NAVIGATION) ? "触摸导航设备" : "鼠标类设备", duplicates > 1));
        }
        return result;
    }
    List<Device> connected() { return inventory; }
    private void reload() { inventory = List.copyOf(scan()); records = saved(); keyboard = false; for (int id : manager.getInputDeviceIds()) if (navigationKeyboard(manager.getInputDevice(id))) keyboard = true; }
    private static boolean navigationKeyboard(InputDevice d) {
        if (d == null || d.isVirtual()) return false;
        if (d.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC || d.supportsSource(InputDevice.SOURCE_DPAD)) return true;
        if (!d.supportsSource(InputDevice.SOURCE_KEYBOARD)) return false;
        for (boolean present : d.hasKeys(android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_DPAD_DOWN)) if (!present) return false;
        return true;
    }
    boolean keyboardAvailable() { return keyboard && !suspended; }
    Device device(int id) { for (Device d : inventory) if (d.id == id) return d; return null; }
    JSONObject saved() { try { return new JSONObject(prefs.data.getString("input_devices", "{}")); } catch (Exception ignored) { return new JSONObject(); } }
    String mode(Device d) { JSONObject record = records.optJSONObject(d.key); return !d.ambiguous && record != null && "direction".equals(record.optString("mode")) ? "direction" : "pointer"; }
    int sensitivity(Device d) { JSONObject record = records.optJSONObject(d.key); return record == null ? 1 : Math.max(0, Math.min(2, record.optInt("sensitivity", 1))); }
    boolean enabled() { return prefs.data.getBoolean("input_enabled", false); }
    void enabled(boolean value) { prefs.data.edit().putBoolean("input_enabled", value).apply(); if (!value) suspended = false; }
    boolean direction(int id) { Device d = device(id); return enabled() && !suspended && d != null && "direction".equals(mode(d)); }
    boolean needsCapture() { if (!enabled() || suspended) return false; for (Device d : connected()) if ("direction".equals(mode(d))) return true; return false; }
    void save(Device d, String mode, int sensitivity) {
        if (d.ambiguous) { status("同名设备标识重复，暂用指针操作"); return; }
        try { JSONObject records = saved(); if (!records.has(d.key) && records.length() >= 32) { status("最多保存32台设备，请移除旧配置"); return; }
            records.put(d.key, new JSONObject().put("name", d.name).put("mode", "direction".equals(mode) ? "direction" : "pointer").put("sensitivity", Math.max(0, Math.min(2, sensitivity)))); prefs.data.edit().putString("input_devices", records.toString()).apply();
        } catch (org.json.JSONException ignored) { status("设备配置未保存"); }
    }
    void forget(String key) { JSONObject records = saved(); records.remove(key); prefs.data.edit().putString("input_devices", records.toString()).apply(); }
    void suspend(boolean value) { suspended = value; status(value ? "已临时恢复系统指针" : "等待设备操作"); }
    void status(String value) { if (!status.equals(value)) { status = value; changed(); } }
    void capture(InputSurface surface, boolean active) {
        if (active) { captureOwner = new java.lang.ref.WeakReference<>(surface); status("助手窗口已接管输入"); }
        else if (captureOwner.get() == surface) { captureOwner.clear(); status(suspended ? "已临时恢复系统指针" : "系统指针操作"); }
    }
    boolean observeEvent(InputEvent event) {
        if (!pointer(event.getDevice())) return false; lastDevice = event.getDeviceId();
        if (identify == null) return false;
        IntConsumer callback = identify; identify = null; main.removeCallbacks(stopIdentify); callback.accept(lastDevice); changed(); return true;
    }
    void identify(IntConsumer callback) { identify = callback; main.removeCallbacks(stopIdentify); main.postDelayed(stopIdentify, 10000); status("请移动要识别的设备（10秒内）"); }
    void cancelIdentify() { identify = null; main.removeCallbacks(stopIdentify); }
    void observe(Runnable callback) { if (observers.isEmpty()) { reload(); manager.registerInputDeviceListener(this, main); prefs.data.registerOnSharedPreferenceChangeListener(preference); } observers.add(callback); }
    void unobserve(Runnable callback) { observers.remove(callback); if (observers.isEmpty()) { manager.unregisterInputDeviceListener(this); prefs.data.unregisterOnSharedPreferenceChangeListener(preference); cancelIdentify(); } }
    private void changed() { records = saved(); for (Runnable callback : List.copyOf(observers)) callback.run(); }
    @Override public void onInputDeviceAdded(int id) { reload(); changed(); }
    @Override public void onInputDeviceRemoved(int id) { if (lastDevice == id) lastDevice = -1; reload(); changed(); }
    @Override public void onInputDeviceChanged(int id) { reload(); changed(); }
}
