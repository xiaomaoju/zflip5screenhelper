package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Samsung rule rows remain selectable independently of their saved switch state. */
final class SettingsVisibilityChecks {
    private static final Set<String> SYSTEM_PACKAGES = Set.of("com.android.systemui", "com.sec.android.app.launcher");
    private final UiSmokeInstrumentation test;
    private volatile MainActivity activity;
    private Prefs prefs;
    private volatile EditText query;
    private boolean statusMode;
    private int assertions;
    SettingsVisibilityChecks(UiSmokeInstrumentation test) { this.test = test; }
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity value, Bundle state) { if (value instanceof MainActivity main) activity = main; }
        public void onActivityResumed(Activity value) { if (value instanceof MainActivity main) { activity = main; EditText input = main.findViewById(android.R.id.content).findViewWithTag("settings-query"); if (input != null) query = input; } }
        public void onActivityStarted(Activity value) { } public void onActivityPaused(Activity value) { } public void onActivityStopped(Activity value) { } public void onActivitySaveInstanceState(Activity value, Bundle out) { } public void onActivityDestroyed(Activity value) { }
    };
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private Set<String> selection() { return statusMode ? prefs.statusHiddenApps() : prefs.compactApps(); }
    private void open() { open("visibility"); }
    private void open(String page) {
        statusMode = page.equals("status");
        openSettings(page);
        test.runOnMainSync(() -> root().findViewWithTag("settings-link-" + (statusMode ? "status_apps" : "visibility_apps")).performClick()); test.waitForIdleSync();
        query = root().findViewWithTag("settings-query");
    }
    private void openSettings(String page) {
        if (activity != null) test.runOnMainSync(activity::finish);
        test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", page)); test.waitForIdleSync();
        for (int i = 0; i < 40 && !root().isAttachedToWindow(); i++) SystemClock.sleep(50); test.waitForIdleSync();
    }
    private Switch search(String name, boolean checked) {
        test.runOnMainSync(() -> { query.setText(name); ((ListView) root().findViewWithTag("settings-app-list")).setSelection(1); }); test.waitForIdleSync();
        for (int i = 0; i < 40 && root().findViewWithTag("app-toggle-" + name) == null; i++) SystemClock.sleep(50);
        Switch toggle = root().findViewWithTag("app-toggle-" + name);
        require(toggle != null && toggle.isShown(), "selectable rule remains in list: " + name + "; attached=" + root().isAttachedToWindow() + "; count=" + ((ListView) root().findViewWithTag("settings-app-list")).getAdapter().getCount());
        require(((ListView) root().findViewWithTag("settings-app-list")).getAdapter().getCount() == 2, "one search result plus header, with no duplicate rule: " + name);
        require(toggle.isChecked() == checked, "saved switch state is displayed: " + name); return toggle;
    }
    private void toggle(String name, boolean before, boolean rowClick) {
        Switch toggle = search(name, before);
        test.runOnMainSync(() -> { if (rowClick) ((View) toggle.getParent()).performClick(); else toggle.performClick(); }); test.waitForIdleSync();
        require(selection().contains(name) != before, "one click saves one rule change: " + name);
        search(name, !before);
    }
    private void settingsRoundtrip() {
        Map<String, List<String>> pages = Map.of(
            "visibility", List.of("dock_auto_hide", "avoid_keyboard"),
            "status", List.of("status_enabled", "status_time", "status_wifi", "status_battery", "status_notifications", "status_alarm", "status_speed", "status_cellular", "battery_percent"),
            "gestures", List.of("gestures_enabled", "haptics", "avoid_navigation"),
            "background", List.of("panel_blur"),
            "panel", List.of("panel-option-labels", "panel-option-brightness", "panel-option-volume", "panel-option-media", "panel-option-mediaIdle"));
        for (Map.Entry<String, List<String>> page : pages.entrySet()) for (String key : page.getValue()) {
            openSettings(page.getKey()); Switch toggle = root().findViewWithTag(key);
            require(toggle != null && toggle.isShown() && toggle.isEnabled(), "setting can be changed: " + key); boolean before = toggle.isChecked();
            test.runOnMainSync(toggle::performClick); test.waitForIdleSync();
            require(root().findViewWithTag(key) == toggle && toggle.isShown() && toggle.isChecked() != before, "setting remains available after switching: " + key);
            openSettings(page.getKey()); Switch reopened = root().findViewWithTag(key);
            require(reopened != null && reopened.isShown() && reopened.isChecked() != before, "changed setting remains available after reopening: " + key);
            test.runOnMainSync(reopened::performClick); test.waitForIdleSync(); openSettings(page.getKey());
            Switch restored = root().findViewWithTag(key); require(restored != null && restored.isShown() && restored.isChecked() == before, "setting can be switched back and reopened: " + key);
        }
    }
    String run() throws Exception {
        Application application = (Application) test.getTargetContext().getApplicationContext(); application.registerActivityLifecycleCallbacks(lifecycle);
        SharedPreferences data = test.getTargetContext().getSharedPreferences("cover", 0); Map<String, ?> original = data.getAll();
        try {
            data.edit().clear().putBoolean("enabled", false).commit(); prefs = new Prefs(test.getTargetContext());
            require(prefs.compactApps().equals(SYSTEM_PACKAGES), "default Samsung rules remain checked");
            CoverApp.catalog(test.getTargetContext()).entriesBlocking(); open();
            for (String name : SYSTEM_PACKAGES) toggle(name, true, false);
            require(prefs.compactApps().isEmpty(), "empty selection remains explicitly saved");
            open(); for (String name : SYSTEM_PACKAGES) search(name, false);
            test.runOnMainSync(() -> CoverApp.catalog(test.getTargetContext()).invalidate());
            CoverApp.catalog(test.getTargetContext()).entriesBlocking(); test.waitForIdleSync();
            for (String name : SYSTEM_PACKAGES) search(name, false);
            for (String name : SYSTEM_PACKAGES) toggle(name, false, true);
            open(); for (String name : SYSTEM_PACKAGES) search(name, true);
            for (String name : SYSTEM_PACKAGES) toggle(name, true, true);
            for (String name : SYSTEM_PACKAGES) toggle(name, false, false);
            require(prefs.statusHiddenApps().isEmpty(), "shortcut rules do not change status-bar rules");
            Set<String> compact = prefs.compactApps();
            open("status"); for (String name : SYSTEM_PACKAGES) toggle(name, false, false);
            open("status"); for (String name : SYSTEM_PACKAGES) toggle(name, true, true);
            open("status"); for (String name : SYSTEM_PACKAGES) search(name, false);
            for (String name : SYSTEM_PACKAGES) toggle(name, false, true);
            require(prefs.statusHiddenApps().equals(SYSTEM_PACKAGES) && prefs.compactApps().equals(compact), "status rules can be re-enabled independently of shortcut rules");
            prefs.data.edit().putStringSet("dock_compact_apps", Set.of("com.example.unavailable")).commit();
            open(); search("com.example.unavailable", true); for (String name : SYSTEM_PACKAGES) search(name, false);
            require(prefs.compactApps().equals(Set.of("com.example.unavailable")), "opening the list does not restore defaults or change saved rules");
            int observers = CoverApp.catalog(test.getTargetContext()).observerCount();
            test.runOnMainSync(() -> activity.onBackPressed()); test.waitForIdleSync();
            require(root().findViewWithTag("settings-link-visibility_apps") != null, "back returns to the source visibility settings");
            require(CoverApp.catalog(test.getTargetContext()).observerCount() == observers - 1, "leaving the application list releases its catalog observer");
            settingsRoundtrip();
            test.runOnMainSync(() -> activity.finish()); test.waitForIdleSync(); activity = null;
            return "PASS: " + assertions + " Samsung shortcut/status rules and 20 setting roundtrip assertions; default, disable, reopen, search, catalog refresh, re-enable and saved missing app";
        } finally {
            if (activity != null) test.runOnMainSync(activity::finish);
            application.unregisterActivityLifecycleCallbacks(lifecycle);
            SharedPreferences.Editor restore = data.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof Boolean v) restore.putBoolean(key, v); else if (value instanceof Integer v) restore.putInt(key, v); else if (value instanceof Long v) restore.putLong(key, v); else if (value instanceof Float v) restore.putFloat(key, v); else if (value instanceof String v) restore.putString(key, v); else if (value instanceof Set<?> v) { Set<String> strings = new java.util.HashSet<>(); for (Object item : v) strings.add((String) item); restore.putStringSet(key, strings); }
            }
            restore.commit();
        }
    }
}
