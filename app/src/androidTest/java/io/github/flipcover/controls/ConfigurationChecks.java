package io.github.flipcover.controls;

import android.content.Intent;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Complete portable-preference roundtrip, executed only by the emulator-guarded runner. */
final class ConfigurationChecks {
    private final UiSmokeInstrumentation test;
    private int assertions;
    private MainActivity activity;
    private Prefs prefs;
    private static final String APP = "app:com.example.first/.Main";
    private static final String SECOND = "app:com.example.second/.Main";
    private static final String THIRD = "app:com.example.third/.Main";
    private static final String PIN = "app:com.example.pin/.Main";
    private static final String FOLDER = "folder:12345678-1234-1234-1234-123456789abc";

    ConfigurationChecks(UiSmokeInstrumentation test) { this.test = test; }

    String run() throws Exception {
        SharedPreferences data = test.getTargetContext().getSharedPreferences("cover", 0);
        Map<String, ?> original = new HashMap<>(data.getAll());
        try {
            data.edit().clear().commit(); prefs = new Prefs(test.getTargetContext());
            activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            test.waitForIdleSync();
            roundtrip();
            homeBehavior();
            templates();
            malformed();
            compatibility();
            return "PASS: configuration; " + assertions + " assertions; all portable preferences, atomic rejection and legacy versions";
        } finally {
            if (activity != null) test.runOnMainSync(activity::finish);
            test.waitForIdleSync(); restore(data, original);
        }
    }

    private void roundtrip() throws Exception {
        prefs.data.edit().putBoolean("gestures_enabled", false).putBoolean("avoid_keyboard", false).putBoolean("haptics", false)
            .putBoolean("dock_auto_hide", false).putStringSet("dock_compact_apps", Set.of("com.example.first", "com.example.second"))
            .putString("app_rotations", "{\"com.example.first\":3,\"com.example.second\":4}")
            .putString("dock", "[\"home\",\"back\",\"app_dock\"]").putString("panel", "[\"wifi\",\"media\",\"system_controls\"]")
            .putString("favorites", "[\"torch\",\"screenshot\"]").putString("pinned_action", "home").putInt("per_page", 5).putInt("damping", 2)
            .putBoolean("auto_placement", false).putFloat("dock_width", .63f).putFloat("dock_height", .17f)
            .putInt("corner_0", 0).putInt("corner_1", 1).putInt("corner_2", 2).putInt("corner_3", 3)
            .putString("hand_side", "right").putString("chrome_style", "light").putBoolean("panel_blur", false)
            .putBoolean("status_enabled", false).putInt("status_scale", 137).putInt("status_safe_left", 21).putInt("status_safe_right", 31)
            .putBoolean("battery_percent", true).putBoolean("avoid_navigation", true).putInt("navigation_gap", 19)
            .putString("hub_sort", "reverse").putBoolean("hub_workspace_locked", true).putBoolean("hub_workspace_labels", false)
            .putBoolean("hub_workspace_badges", true).putString("hub_workspace_density", "easy").commit();
        for (String item : Prefs.STATUS_ITEMS) prefs.data.edit().putBoolean("status_" + item, item.equals("speed") || item.equals("cellular")).commit();
        prefs.applyPanelSettings(new JSONObject().put("columns", 5).put("density", 2).put("labels", false).put("labelSize", 0).put("toolsPosition", "bottom")
            .put("brightness", false).put("volume", false).put("media", false).put("mediaIdle", true));
        prefs.saveDockPlacement(List.of(PIN), new AppWorkspaceLayout(Map.of(FOLDER, 0, THIRD, 4), Map.of(FOLDER, new AppWorkspaceLayout.Folder("工具", List.of(APP, SECOND), 3)), 5, 3));
        prefs.workspaceAlias(APP, "自定义名称");
        // Ensure every exported default is materialized too, then compare every persisted portable key.
        JSONObject config = activity.exportConfigurationData(); activity.applyConfigurationData(config);
        Map<String, ?> expected = new HashMap<>(prefs.data.getAll());
        for (String key : List.of("hub_default_migrated", "app_dock_migrated", "system_controls_migrated", "panel_undo")) expected.remove(key);
        Map<String, Object> local = Map.of("display", 77, "enabled", false, "system_controls_disabled", true, "layout_backup", "local-backup", "layout_undo", "local-undo", "layout_saved_at", 123L);
        restore(prefs.data, local); activity.applyConfigurationData(new JSONObject(config.toString()));
        for (String key : expected.keySet()) require(expected.get(key).equals(prefs.data.getAll().get(key)), "roundtrip preserves " + key);
        for (String key : local.keySet()) require(local.get(key).equals(prefs.data.getAll().get(key)), "import preserves local " + key);
        require(prefs.workspace().folder(FOLDER).members().equals(List.of(APP, SECOND)), "folder members and ordering survive");
        for (String key : local.keySet()) require(!config.has(key), "export omits local " + key);
        require(!config.has("native_widgets") && !config.has("recentTasks") && !config.has("notifications"), "export omits widget identities and transient contents");
        for (boolean compact : new boolean[]{true, false}) {
            prefs.saveWorkspace(prefs.workspace().compact(), compact); JSONObject saved = activity.exportConfigurationData();
            prefs.data.edit().putBoolean("hub_workspace_compact", !compact).commit(); activity.applyConfigurationData(saved);
            require(prefs.workspaceCompact() == compact, "auto packing roundtrip " + compact);
        }
        for (float width : new float[]{.25f, .70f}) for (float height : new float[]{.05f, .22f}) {
            prefs.data.edit().putFloat("dock_width", width).putFloat("dock_height", height).commit(); activity.applyConfigurationData(activity.exportConfigurationData());
            require(prefs.widthRatio() == width && prefs.heightRatio() == height, "boundary dimensions roundtrip");
        }
    }

    private void malformed() throws Exception {
        JSONObject config = activity.exportConfigurationData();
        for (String key : new String[]{"version", "perPage", "damping"}) for (Object value : new Object[]{1.5, "2", JSONObject.NULL}) reject(new JSONObject(config.toString()).put(key, value), "invalid " + key);
        for (String key : new String[]{"gestures", "haptics", "avoidKeyboard", "dockAutoHide", "statusEnabled", "blur"}) reject(new JSONObject(config.toString()).put(key, "false"), "non-boolean " + key);
        for (String key : new String[]{"favorites", "compactApps", "statusItems"}) reject(new JSONObject(config.toString()).put(key, JSONObject.NULL), "null " + key);
        for (String key : new String[]{"version", "perPage", "damping"}) { JSONObject bad = new JSONObject(config.toString()); bad.getJSONObject("layout").put(key, 1.5); reject(bad, "fractional layout " + key); }
        JSONObject bad = new JSONObject(config.toString()); bad.getJSONObject("layout").getJSONArray("corners").put(0, 1.5); reject(bad, "fractional corner");
        bad = new JSONObject(config.toString()); bad.getJSONObject("layout").put("blur", "true"); reject(bad, "non-boolean layout");
        bad = new JSONObject(config.toString()); bad.getJSONObject("layout").getJSONObject("statusItems").put("wifi", "true"); reject(bad, "non-boolean status");
        for (String duplicate : new String[]{APP, THIRD}) {
            bad = new JSONObject(config.toString()); bad.getJSONObject("layout").put("hubPins", new JSONArray(List.of(duplicate)));
            reject(bad, "duplicate Dock/desktop or folder ownership");
        }
        bad = new JSONObject(config.toString()); bad.getJSONObject("rotations").put("com.example.first", 2.5); reject(bad, "late rotation failure");
    }

    private void homeBehavior() throws Exception {
        for (String mode : new String[]{"clock", "cards"}) {
            prefs.data.edit().putString("home_action", mode).commit(); JSONObject saved = activity.exportConfigurationData();
            prefs.data.edit().putString("home_action", mode.equals("cards") ? "clock" : "cards").commit();
            activity.applyConfigurationData(saved); require(prefs.homeAction().equals(mode), "configuration roundtrip keeps Home " + mode);
            prefs.saveLayout(); prefs.data.edit().putString("home_action", mode.equals("cards") ? "clock" : "cards").commit();
            prefs.restoreLayout(false); require(prefs.homeAction().equals(mode), "layout backup keeps Home " + mode);
            prefs.restoreLayout(true); require(!prefs.homeAction().equals(mode), "layout undo restores prior Home behavior");
        }
        JSONObject saved = activity.exportConfigurationData();
        for (Object invalid : new Object[]{"unknown", 1, true, JSONObject.NULL}) {
            JSONObject bad = new JSONObject(saved.toString()); bad.getJSONObject("layout").put("homeAction", invalid); reject(bad, "invalid Home behavior");
        }
        JSONObject missing = new JSONObject(saved.toString()); missing.getJSONObject("layout").remove("homeAction"); reject(missing, "new layout requires Home behavior");
        JSONObject legacy = new JSONObject(saved.toString()); legacy.getJSONObject("layout").put("version", 9).remove("homeAction");
        activity.applyConfigurationData(legacy); require(prefs.homeAction().equals("cards"), "older layout defaults Home to native cards");
    }

    private void templates() throws Exception {
        WidgetTemplates.Card card = new WidgetTemplates.Card(6, List.of(new WidgetTemplates.Entry("com.example.widgets/.Provider", 0, 0, 2, 2), new WidgetTemplates.Entry("com.example.widgets/.Provider", 2, 2, 2, 2)));
        JSONObject config = activity.exportConfigurationData().put("widgetTemplates", WidgetTemplates.json(List.of(card)));
        activity.applyConfigurationData(config);
        require(WidgetTemplates.saved(prefs).equals(WidgetTemplates.read(config.getJSONArray("widgetTemplates"))), "templates import without installed provider or authorization");
        JSONArray exported = activity.exportConfigurationData().getJSONArray("widgetTemplates");
        require(WidgetTemplates.read(exported).containsAll(WidgetTemplates.saved(prefs)), "unrestored templates survive re-export");
        JSONObject bad = new JSONObject(config.toString()); bad.getJSONArray("widgetTemplates").getJSONObject(0).getJSONArray("items").getJSONObject(1).put("x", 0).put("y", 0); reject(bad, "overlapping widget template");
        for (String key : new String[]{"x", "y", "width", "height"}) {
            bad = new JSONObject(config.toString()); bad.getJSONArray("widgetTemplates").getJSONObject(0).getJSONArray("items").getJSONObject(0).put(key, 1.5); reject(bad, "fractional widget " + key);
        }
        bad = new JSONObject(config.toString()); bad.getJSONArray("widgetTemplates").getJSONObject(0).getJSONArray("items").getJSONObject(0).put("provider", "not-a-component"); reject(bad, "invalid widget provider");
        bad = new JSONObject(config.toString()); bad.remove("widgetTemplates"); reject(bad, "v13 requires explicit template list");
        bad = new JSONObject(config.toString()); JSONArray tooMany = new JSONArray(); for (int i = 0; i < 4000; i++) tooMany.put(config.getJSONArray("widgetTemplates").getJSONObject(0)); bad.put("widgetTemplates", tooMany); reject(bad, "template byte limit");
        JSONArray library = new JSONArray(); for (int i = 0; i < 33; i++) library.put(config.getJSONArray("widgetTemplates").getJSONObject(0));
        activity.applyConfigurationData(new JSONObject(config.toString()).put("widgetTemplates", library)); require(activity.exportConfigurationData().getJSONArray("widgetTemplates").length() >= 33, "template library has no artificial 32-card export cliff");
        JSONArray apps = new JSONArray(); for (int i = 0; i < 1001; i++) apps.put("com.example.app" + i);
        activity.applyConfigurationData(new JSONObject(config.toString()).put("compactApps", apps));
        activity.applyConfigurationData(activity.exportConfigurationData()); require(prefs.compactApps().size() == 1001, "large existing application-rule list roundtrips");
        JSONObject legacy = new JSONObject(config.toString()).put("version", 12); legacy.remove("widgetTemplates"); activity.applyConfigurationData(legacy);
        require(!WidgetTemplates.saved(prefs).isEmpty(), "older config preserves imported templates");
    }

    private void compatibility() throws Exception {
        JSONObject current = activity.exportConfigurationData();
        for (int version = 1; version <= 14; version++) {
            JSONObject legacy = new JSONObject(current.toString()).put("version", version);
            if (version < 4) legacy.remove("layout");
            if (version < 5) legacy.remove("rotations");
            activity.applyConfigurationData(legacy);
            require(prefs.actions("dock").equals(List.of("home", "back", "app_dock")), "legacy configuration " + version);
        }
    }

    private void reject(JSONObject config, String message) throws Exception {
        Map<String, ?> before = new HashMap<>(prefs.data.getAll()); boolean rejected = false;
        try { activity.applyConfigurationData(config); } catch (IllegalArgumentException | org.json.JSONException expected) { rejected = true; }
        require(rejected && before.equals(prefs.data.getAll()), message + " rejects atomically");
    }
    private void require(boolean passed, String message) { assertions++; if (!passed) throw new AssertionError(message); }
    private static void restore(SharedPreferences data, Map<String, ?> values) {
        SharedPreferences.Editor edit = data.edit().clear();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean v) edit.putBoolean(key, v);
            else if (value instanceof Integer v) edit.putInt(key, v);
            else if (value instanceof Long v) edit.putLong(key, v);
            else if (value instanceof Float v) edit.putFloat(key, v);
            else if (value instanceof String v) edit.putString(key, v);
            else if (value instanceof Set<?> v) { Set<String> strings = new java.util.HashSet<>(); for (Object item : v) strings.add((String) item); edit.putStringSet(key, strings); }
        }
        edit.commit();
    }
}
