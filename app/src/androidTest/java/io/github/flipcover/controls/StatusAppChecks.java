package io.github.flipcover.controls;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local settings/configuration and production visibility checks; no system-bar operations. */
final class StatusAppChecks {
    private static final String PACKAGE = "com.example.status";
    private final UiSmokeInstrumentation test;
    private int assertions;
    private MainActivity activity;
    private Prefs prefs;
    StatusAppChecks(UiSmokeInstrumentation test) { this.test = test; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void field(CoverService service, String name, Object value) throws Exception { Field field = CoverService.class.getDeclaredField(name); field.setAccessible(true); field.set(service, value); }
    private void sync(CoverService service) throws Exception { java.lang.reflect.Method method = CoverService.class.getDeclaredMethod("syncCardStatus"); method.setAccessible(true); method.invoke(service); }
    String run() throws Exception {
        test.runOnMainSync(() -> {});
        SharedPreferences data = test.getTargetContext().getSharedPreferences("cover", 0);
        Map<String, ?> original = new HashMap<>(data.getAll());
        try {
            data.edit().clear().putBoolean("enabled", false).commit(); prefs = new Prefs(test.getTargetContext());
            require(prefs.statusHiddenApps().isEmpty(), "old/default preferences hide no applications");
            prefs.saveStatusHiddenApps(Set.of(PACKAGE));
            activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "status")); test.waitForIdleSync();
            test.runOnMainSync(() -> activity.findViewById(android.R.id.content).findViewWithTag("settings-link-status_apps").performClick()); test.waitForIdleSync();
            test.runOnMainSync(() -> ((EditText) activity.findViewById(android.R.id.content).findViewWithTag("settings-query")).setText(PACKAGE)); test.waitForIdleSync();
            for (int i = 0; i < 60 && activity.findViewById(android.R.id.content).findViewWithTag("app-toggle-" + PACKAGE) == null; i++) SystemClock.sleep(50);
            Set<String> compact = prefs.compactApps();
            test.runOnMainSync(() -> {
                View root = activity.findViewById(android.R.id.content); Switch toggle = root.findViewWithTag("app-toggle-" + PACKAGE);
                require(toggle != null && toggle.isChecked(), "saved unavailable application can be edited");
                toggle.performClick(); require(prefs.statusHiddenApps().isEmpty(), "one switch click removes one status rule");
                require(compact.equals(prefs.compactApps()), "status selection is independent of shortcut visibility");
                toggle.performClick(); require(prefs.statusHiddenApps().equals(Set.of(PACKAGE)), "second click restores the selection");
                ((EditText) root.findViewWithTag("settings-query")).setText(PACKAGE);
            });
            android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot(); require(image != null, "rendered application settings captured");
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(activity.getFilesDir(), "status-apps-raw.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); } finally { image.recycle(); }
            test.runOnMainSync(() -> {
                activity.onBackPressed();
                require(activity.findViewById(android.R.id.content).findViewWithTag("status_enabled") != null, "back returns to status settings");
            });
            configuration();
            test.runOnMainSync(() -> { try { visibility(); } catch (Exception error) { throw new AssertionError(error); } });
            return "PASS: " + assertions + " own status-bar application, settings and backup assertions; no native system-bar control";
        } finally {
            if (activity != null) test.runOnMainSync(activity::finish);
            SharedPreferences.Editor edit = data.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof Boolean v) edit.putBoolean(key, v); else if (value instanceof Integer v) edit.putInt(key, v); else if (value instanceof Long v) edit.putLong(key, v); else if (value instanceof Float v) edit.putFloat(key, v); else if (value instanceof String v) edit.putString(key, v); else if (value instanceof Set<?> v) { Set<String> values = new java.util.HashSet<>(); for (Object item : v) values.add((String) item); edit.putStringSet(key, values); }
            }
            edit.commit();
        }
    }
    private void configuration() throws Exception {
        JSONObject config = activity.exportConfigurationData(); prefs.saveStatusHiddenApps(Set.of()); activity.applyConfigurationData(config);
        require(prefs.statusHiddenApps().equals(Set.of(PACKAGE)), "configuration roundtrip preserves the rule");
        prefs.saveLayout(); prefs.saveStatusHiddenApps(Set.of()); prefs.restoreLayout(false);
        require(prefs.statusHiddenApps().equals(Set.of(PACKAGE)), "layout backup restores the rule");
        prefs.restoreLayout(true); require(prefs.statusHiddenApps().isEmpty(), "layout undo restores the previous rule");
        prefs.saveStatusHiddenApps(Set.of(PACKAGE)); JSONObject legacy = new JSONObject(config.toString()); legacy.getJSONObject("layout").remove("statusHiddenApps"); activity.applyConfigurationData(legacy);
        require(prefs.statusHiddenApps().isEmpty(), "legacy layout defaults to no hidden applications");
        for (Object invalid : new Object[]{JSONObject.NULL, new JSONObject(), new JSONArray().put(1), new JSONArray().put("invalid/package")}) {
            JSONObject bad = new JSONObject(config.toString()); bad.getJSONObject("layout").put("statusHiddenApps", invalid); Map<String, ?> before = new HashMap<>(prefs.data.getAll()); boolean rejected = false;
            try { activity.applyConfigurationData(bad); } catch (IllegalArgumentException | org.json.JSONException expected) { rejected = true; }
            require(rejected && before.equals(prefs.data.getAll()), "invalid application list rejected atomically");
        }
        prefs.saveStatusHiddenApps(Set.of(PACKAGE));
    }
    private void visibility() throws Exception {
        CoverService service = new CoverService(); service.prefs = prefs;
        Field stateField = CoverService.class.getDeclaredField("statusVisibility"); stateField.setAccessible(true);
        StatusAppVisibility state = (StatusAppVisibility) stateField.get(service); state.applications(prefs.statusHiddenApps());
        StatusBarView status = new StatusBarView(activity, prefs); field(service, "statusBar", status);
        state.observed(PACKAGE, 0); sync(service); require(status.getVisibility() == View.INVISIBLE, "selected app hides only the own overlay");
        state.observed(null, 100); sync(service); require(status.getVisibility() == View.INVISIBLE, "window replacement does not flash the existing overlay");
        state.observed(PACKAGE, 200); sync(service); require(status.getVisibility() == View.INVISIBLE, "new window identity keeps the same application hidden");
        state.observed("com.example.other", 300); sync(service); require(status.getVisibility() == View.VISIBLE, "another app restores the overlay immediately");
        state.reset(); sync(service); require(status.getVisibility() == View.VISIBLE, "unknown initial foreground defaults to visible");
        state.observed(PACKAGE, 400); prefs.saveStatusHiddenApps(Set.of()); state.applications(prefs.statusHiddenApps()); sync(service); require(status.getVisibility() == View.VISIBLE, "removing the last rule restores the existing view");
        require(!state.enabled(), "empty list disables the application rule");
        prefs.saveStatusHiddenApps(Set.of(PACKAGE)); InterfaceCard controls = new InterfaceCard(activity, prefs, InterfaceCard.CONTROLS, new View(activity), () -> {}, () -> {});
        try { field(service, "panelCard", controls); sync(service); require(status.getVisibility() == View.INVISIBLE && controls.statusBar().getVisibility() == View.VISIBLE, "control-center status row stays independent"); }
        finally { field(service, "panelCard", null); controls.release(); }
    }
}
