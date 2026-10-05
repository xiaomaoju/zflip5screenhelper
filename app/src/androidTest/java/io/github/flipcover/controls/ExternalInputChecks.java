package io.github.flipcover.controls;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.lang.reflect.Field;

/** Disposable-emulator checks use real view geometry; device capture gets a separate uinput run. */
final class ExternalInputChecks {
    private final UiSmokeInstrumentation test;
    private int assertions;
    ExternalInputChecks(UiSmokeInstrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); assertions++; }
    String run() throws Exception {
        MainActivity activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).putExtra("section", "input_devices").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync();
        InputDevices devices = CoverApp.inputs(activity); Prefs prefs = new Prefs(activity); String saved = prefs.data.getString("input_devices", "{}"); boolean enabled = devices.enabled();
        try {
            test.runOnMainSync(() -> {
                require(activity.findViewById(android.R.id.content).findViewWithTag("input-device-panel") != null, "direct settings route");
                InputDevices.Device fixture = new InputDevices.Device(-10, "external-input-fixture", "Fixture", "mouse", false);
                devices.save(fixture, "direction", 2); require(devices.mode(fixture).equals("direction") && devices.sensitivity(fixture) == 2, "device rule persists");
                require(devices.mode(new InputDevices.Device(-11, fixture.key(), fixture.name(), fixture.type(), true)).equals("pointer"), "ambiguous identity cannot inherit direction capture");
                require(devices.mode(new InputDevices.Device(-12, "new-device", "Fixture", "mouse", false)).equals("pointer"), "unknown same-name device defaults to pointer");
                devices.forget(fixture.key()); require(devices.mode(fixture).equals("pointer"), "remove device restores pointer");
                String panel = prefs.data.getString("panel", null); boolean migrated = prefs.data.getBoolean("input_tile_migrated", false);
                prefs.data.edit().putString("panel", "[\"wifi\"]").putBoolean("input_tile_migrated", false).commit(); prefs.migrateInputTile();
                require(prefs.actions("panel").contains("external_devices"), "new control tile registered during one-time migration");
                prefs.data.edit().putString("panel", panel).putBoolean("input_tile_migrated", migrated).commit();
                require(ActionCatalog.valid("external_devices"), "configuration recognizes control tile");
            });
            InputSurface[] surface = new InputSurface[1]; Button[] buttons = new Button[4]; int[] clicks = {0};
            test.runOnMainSync(() -> {
                surface[0] = new InputSurface(activity); surface[0].navigation(() -> { }, () -> { });
                for (int i = 0; i < 4; i++) { int index = i; Button button = new Button(activity); button.setText("target " + i); button.setContentDescription("target " + i); button.setOnClickListener(v -> clicks[0] = index + 1); buttons[i] = button; FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(120, 100); p.leftMargin = (i % 2) * 140; p.topMargin = (i / 2) * 120; surface[0].addView(button, p); } activity.setContentView(surface[0]);
            }); test.waitForIdleSync();
            Field selected = InputSurface.class.getDeclaredField("selected"); selected.setAccessible(true);
            test.runOnMainSync(() -> {
                surface[0].move(View.FOCUS_RIGHT); require(value(selected, surface[0]) == buttons[1], "right selects spatial neighbor");
                surface[0].move(View.FOCUS_DOWN); require(value(selected, surface[0]) == buttons[3], "down selects next row");
                buttons[2].setEnabled(false); surface[0].move(View.FOCUS_LEFT); require(value(selected, surface[0]) != buttons[2], "disabled target excluded");
                require(clicks[0] == 0, "navigation does not activate targets");
                DetailSheet modal = new DetailSheet(activity, "Modal", () -> { }); Button modalButton = new Button(activity); modalButton.setText("modal only"); modalButton.setOnClickListener(v -> { }); modal.content.addView(modalButton); surface[0].addView(modal, new FrameLayout.LayoutParams(-1, -1));
            }); test.waitForIdleSync();
            test.runOnMainSync(() -> { surface[0].move(View.FOCUS_DOWN); View chosen = (View) value(selected, surface[0]); require(chosen != null && chosen != buttons[0] && chosen != buttons[1] && chosen != buttons[3], "modal excludes background controls"); surface[0].release(); require(value(selected, surface[0]) == null, "release clears selection"); });
            SettingsUi.Slider[] slider = {null}; android.widget.EditText[] editor = {null}; int[] committed = {10};
            test.runOnMainSync(() -> {
                surface[0].removeAllViews(); slider[0] = new SettingsUi.Slider(activity, 0, 20, 10, value -> { }, value -> committed[0] = value);
                surface[0].addView(slider[0], new FrameLayout.LayoutParams(280, 100));
                editor[0] = new android.widget.EditText(activity); editor[0].setText("abcd"); FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(280, 100); p.topMargin = 140; surface[0].addView(editor[0], p);
                surface[0].requestFocus();
            }); test.waitForIdleSync();
            test.runOnMainSync(() -> {
                key(surface[0], android.view.KeyEvent.KEYCODE_DPAD_UP); require(value(selected, surface[0]) == slider[0], "settings slider can be selected");
                key(surface[0], android.view.KeyEvent.KEYCODE_ENTER); key(surface[0], android.view.KeyEvent.KEYCODE_DPAD_RIGHT); require(committed[0] > 10, "keyboard slider adjustment commits");
                key(surface[0], android.view.KeyEvent.KEYCODE_ESCAPE); key(surface[0], android.view.KeyEvent.KEYCODE_DPAD_DOWN); require(value(selected, surface[0]) == editor[0], "Esc exits slider adjustment");
                key(surface[0], android.view.KeyEvent.KEYCODE_ENTER); editor[0].setSelection(2); key(surface[0], android.view.KeyEvent.KEYCODE_DPAD_LEFT); require(editor[0].getSelectionStart() == 1, "text arrows preserve caret operation");
            });
        } finally { test.runOnMainSync(() -> { prefs.data.edit().putString("input_devices", saved).putBoolean("input_enabled", enabled).apply(); activity.finish(); }); }
        return "PASS: " + assertions + " external input view/configuration checks (not Bluetooth hardware validation)";
    }
    private static Object value(Field field, Object object) { try { return field.get(object); } catch (Exception error) { throw new AssertionError(error); } }
    private static void key(View view, int key) { view.dispatchKeyEvent(new android.view.KeyEvent(0, key)); view.dispatchKeyEvent(new android.view.KeyEvent(1, key)); }
    String capture() throws Exception {
        android.os.ParcelFileDescriptor[] channel = test.getUiAutomation().executeShellCommandRw("uinput -");
        java.io.FileOutputStream writer = new java.io.FileOutputStream(channel[1].getFileDescriptor());
        android.os.ParcelFileDescriptor[] other = test.getUiAutomation().executeShellCommandRw("uinput -");
        java.io.FileOutputStream writerB = new java.io.FileOutputStream(other[1].getFileDescriptor());
        MainActivity activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).putExtra("section", "input_devices").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        InputDevices devices = CoverApp.inputs(activity); Prefs prefs = new Prefs(activity); String saved = prefs.data.getString("input_devices", "{}"); boolean enabled = devices.enabled();
        InputSurface[] root = {null}; Button[] buttons = new Button[4]; int[] clicks = {0};
        try {
            for (int id = 1; id <= 2; id++) write(id == 1 ? writer : writerB, "{\"id\":" + id + ",\"command\":\"register\",\"name\":\"External input fixture " + id + "\",\"vid\":6353,\"pid\":" + (210 + id) + ",\"bus\":\"usb\",\"configuration\":[{\"type\":100,\"data\":[1,2]},{\"type\":101,\"data\":[272,273]},{\"type\":102,\"data\":[0,1,8]}]}");
            for (int i = 0; i < 50 && devices.connected().stream().filter(d -> d.name().startsWith("External input fixture")).count() != 2; i++) android.os.SystemClock.sleep(100);
            test.runOnMainSync(() -> {
                require(devices.connected().stream().filter(d -> d.name().startsWith("External input fixture")).count() == 2, "two uinput devices are independently enumerated");
                for (InputDevices.Device device : devices.connected()) if (device.name().startsWith("External input fixture")) devices.save(device, device.name().endsWith("1") ? "direction" : "pointer", 1);
                devices.enabled(true); root[0] = new InputSurface(activity); root[0].navigation(() -> { }, () -> { });
                for (int i = 0; i < 4; i++) { int index = i; Button button = new Button(activity); button.setText("target " + i); button.setContentDescription("target " + i); button.setOnClickListener(v -> clicks[0] = index + 1); buttons[i] = button; FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(120, 100); p.leftMargin = (i % 2) * 140; p.topMargin = (i / 2) * 120; root[0].addView(button, p); } activity.setContentView(root[0]); root[0].requestFocus();
            }); test.waitForIdleSync();
            for (int i = 0; i < 50 && !root[0].hasWindowFocus(); i++) android.os.SystemClock.sleep(50);
            require(root[0].hasWindowFocus(), "capture window has input focus");
            test.runOnMainSync(() -> root[0].requestPointerCapture());
            for (int i = 0; i < 40 && !root[0].hasPointerCapture(); i++) android.os.SystemClock.sleep(50);
            require(root[0].hasPointerCapture(), "system granted real pointer capture");
            inject(writer, 1, "2,0,35,0,0,0"); android.os.SystemClock.sleep(180); test.waitForIdleSync();
            Field selected = InputSurface.class.getDeclaredField("selected"); selected.setAccessible(true);
            test.runOnMainSync(() -> require(value(selected, root[0]) == buttons[1], "device A relative movement selects right target"));
            inject(writer, 1, "1,272,1,0,0,0"); android.os.SystemClock.sleep(50); inject(writer, 1, "1,272,0,0,0,0"); android.os.SystemClock.sleep(150);
            require(clicks[0] == 2, "device A confirms selected target");
            inject(writerB, 2, "2,0,-330,2,1,-300,0,0,0"); android.os.SystemClock.sleep(100);
            inject(writerB, 2, "1,272,1,0,0,0"); android.os.SystemClock.sleep(50); inject(writerB, 2, "1,272,0,0,0,0"); android.os.SystemClock.sleep(150);
            require(clicks[0] == 1, "device B pointer clicks its position instead of A selection");
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ESCAPE); test.waitForIdleSync();
            require(root[0].hasPointerCapture() && !devices.suspended, "short Esc leaves per-device rules enabled");
            test.runOnMainSync(() -> devices.suspend(true)); test.waitForIdleSync();
            require(!root[0].hasPointerCapture() && devices.suspended, "explicit pause restores system pointer");
            test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_DPAD_RIGHT); test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER); test.waitForIdleSync();
            require(clicks[0] == 2, "keyboard arrows and Enter work without mouse conversion");
            test.runOnMainSync(() -> { devices.suspend(false); root[0].requestPointerCapture(); }); android.os.SystemClock.sleep(200);
            inject(writer, 1, "1,272,1,0,0,0"); android.os.SystemClock.sleep(60); writer.close(); channel[1].close();
            for (int i = 0; i < 40 && devices.connected().stream().anyMatch(d -> d.name().endsWith("fixture 1")); i++) android.os.SystemClock.sleep(50);
            test.waitForIdleSync(); Field heldButtons = InputSurface.class.getDeclaredField("buttons"); heldButtons.setAccessible(true);
            require(((Integer) value(heldButtons, root[0])) == 0 && !root[0].hasPointerCapture(), "unplug while pressed cancels sequence and releases last direction device: buttons=" + value(heldButtons, root[0]) + ", capture=" + root[0].hasPointerCapture() + ", devices=" + devices.connected());
        } finally { writer.close(); writerB.close(); for (android.os.ParcelFileDescriptor[] channels : new android.os.ParcelFileDescriptor[][]{channel, other}) for (android.os.ParcelFileDescriptor fd : channels) if (fd != null) try { fd.close(); } catch (Exception ignored) { } test.runOnMainSync(() -> { devices.suspend(false); prefs.data.edit().putString("input_devices", saved).putBoolean("input_enabled", enabled).apply(); activity.finish(); }); }
        return "PASS: " + assertions + " real uinput capture, two-device and keyboard assertions; simulated hardware, not Bluetooth radio validation";
    }
    private static void write(java.io.FileOutputStream output, String json) throws java.io.IOException { output.write((json + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8)); output.flush(); }
    private static void inject(java.io.FileOutputStream output, int id, String events) throws java.io.IOException { write(output, "{\"id\":" + id + ",\"command\":\"updateTimeBase\"}"); write(output, "{\"id\":" + id + ",\"command\":\"inject\",\"events\":[" + events + "]}"); }
}
