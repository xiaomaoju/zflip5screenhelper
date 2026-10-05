package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;

/** Packaged config, generated consumers and saved-preference priority on a disposable emulator. */
final class AppConfigChecks {
    private int assertions;
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void constants(String path, JSONObject section) throws Exception {
        java.util.Iterator<String> keys = section.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            String name = path.isEmpty() ? key : path + "." + key; Object value = section.get(key);
            if (value instanceof JSONObject object) { constants(name, object); continue; }
            String field = name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2").replaceAll("([a-z0-9])([A-Z])", "$1_$2").replace('.', '_').toUpperCase(java.util.Locale.ROOT);
            Object generated = BuildConfig.class.getField(field).get(null);
            if (value instanceof JSONArray array) {
                require(new JSONArray(Arrays.asList((String[]) generated)).toString().equals(array.toString()), name + " array matches packaged source");
                for (String id : (String[]) generated) require(ActionCatalog.valid(id), name + " contains valid action " + id);
            } else if (value instanceof Number number) require(Math.abs(((Number) generated).doubleValue() - number.doubleValue()) < .000001, name + " number matches packaged source");
            else require(value.equals(generated), name + " matches packaged source");
        }
    }
    private void defaults(Prefs prefs, JSONObject root) throws Exception {
        JSONObject dock = root.getJSONObject("dock"), status = root.getJSONObject("status"), panel = root.getJSONObject("controlCenter"), launcher = root.getJSONObject("launcher");
        require(prefs.perPage() == dock.getInt("perPage") && prefs.damping() == dock.getInt("damping"), "Dock numeric defaults");
        require(prefs.pinnedAction().equals(dock.getString("pinnedAction")) && ActionCatalog.valid(prefs.pinnedAction()), "first-run pinned default");
        require(new JSONArray(prefs.actions("dock")).toString().equals(dock.getJSONArray("actions").toString()), "Dock default order");
        require(new JSONArray(prefs.actions("favorites")).toString().equals(dock.getJSONArray("favorites").toString()), "sidebar default order");
        require(new JSONArray(prefs.actions("panel")).toString().equals(panel.getJSONArray("actions").toString()), "control default order before historical input migration");
        require(prefs.autoHideDock() == dock.getBoolean("autoHide") && prefs.avoidKeyboard() == dock.getBoolean("avoidKeyboard") && prefs.haptics() == dock.getBoolean("haptics") && prefs.gesturesEnabled() == dock.getBoolean("gestures"), "Dock boolean defaults");
        require(Math.abs(prefs.widthRatio() - dock.getDouble("widthRatio")) < .000001 && Math.abs(prefs.heightRatio() - dock.getDouble("heightRatio")) < .000001, "Dock default ratios");
        require(prefs.homeAction().equals(dock.getString("homeAction")), "Home default");
        require(prefs.statusEnabled() == status.getBoolean("enabled") && prefs.statusScale() == status.getInt("scale") && prefs.batteryPercent() == status.getBoolean("batteryPercent"), "status defaults");
        require(prefs.statusSafeLeft() == status.getInt("safeLeftDp") && prefs.statusSafeRight() == status.getInt("safeRightDp") && prefs.chromeStyle().equals(status.getString("chromeStyle")), "status appearance defaults");
        JSONObject expected = new JSONObject(panel.toString()); expected.remove("actions"); expected.remove("blur");
        JSONObject actual = prefs.panelSnapshot(); java.util.Iterator<String> keys = expected.keys(); while (keys.hasNext()) { String key = keys.next(); require(expected.get(key).equals(actual.get(key)), "control default " + key); }
        require(prefs.panelBlur() == panel.getBoolean("blur"), "blur default");
        require(prefs.hubSort().equals(launcher.getString("sort")) && prefs.workspaceDensity().equals(launcher.getString("density")), "launcher enum defaults");
        require(prefs.workspaceLabels() == launcher.getBoolean("labels") && prefs.workspaceBadges() == launcher.getBoolean("badges") && prefs.workspaceLocked() == launcher.getBoolean("locked") && prefs.workspaceCompact() == launcher.getBoolean("compact"), "launcher boolean defaults");
    }
    String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext(); JSONObject source;
        try (InputStream stream = context.getAssets().open("app-config.json")) { source = new JSONObject(new String(stream.readAllBytes(), StandardCharsets.UTF_8)); }
        for (String group : new String[]{"defaults", "appearance", "motion"}) constants(group, source.getJSONObject(group));
        require(AppConfig.load(context).update.catalogUri().toString().equals(source.getJSONObject("update").getString("catalogUrl")), "update continues reading packaged config");
        TypedValue radius = new TypedValue(); context.getResources().getValue(R.dimen.launcher_panel_radius, radius, true);
        require(Math.abs(TypedValue.complexToFloat(radius.data) - source.getJSONObject("appearance").getJSONObject("launcher").getInt("panelRadiusDp")) < .001, "generated launcher resource keeps dp units");
        context.getResources().getValue(R.dimen.launcher_folder_radius, radius, true);
        require(Math.abs(TypedValue.complexToFloat(radius.data) - BuildConfig.APPEARANCE_LAUNCHER_FOLDER_RADIUS_DP) < .001, "folder radius resource");
        context.getResources().getValue(R.dimen.launcher_dock_radius, radius, true);
        require(Math.abs(TypedValue.complexToFloat(radius.data) - BuildConfig.APPEARANCE_LAUNCHER_DOCK_RADIUS_DP) < .001, "Dock radius resource");
        require(AppLauncherStyle.GRID_COLUMNS == 5 && AppLauncherStyle.GRID_ROWS == 3 && AppDockPlacement.LIMIT == 4 && WidgetGrid.SIDE == 4, "fixed layout contracts remain in owners");
        SharedPreferences data = context.getSharedPreferences("cover", Context.MODE_PRIVATE); Map<String, ?> original = data.getAll();
        try {
            data.edit().clear().putBoolean("enabled", false).putInt("display", -1).commit(); Prefs prefs = new Prefs(context);
            defaults(prefs, source.getJSONObject("defaults"));
            int savedColumns = prefs.panelColumns() == 5 ? 3 : 5, savedScale = prefs.statusScale() == 150 ? 50 : 150;
            boolean savedLabels = !prefs.workspaceLabels(), savedGestures = !prefs.gesturesEnabled();
            data.edit().putInt("panel_columns", savedColumns).putInt("status_scale", savedScale).putBoolean("hub_workspace_labels", savedLabels).putBoolean("gestures_enabled", savedGestures).putString("dock", "[\"home\",\"back\"]").putString("pinned_action", "torch").putString("hub_workspace", "[]").putString("input_devices", "{}").commit();
            Map<String, ?> saved = data.getAll(); prefs = new Prefs(context);
            require(saved.equals(data.getAll()), "loading defaults preserves every saved preference");
            require(prefs.panelColumns() == savedColumns && prefs.statusScale() == savedScale && prefs.workspaceLabels() == savedLabels && prefs.gesturesEnabled() == savedGestures, "saved values override developer defaults");
            require(prefs.actions("dock").equals(Arrays.asList("home", "back")) && prefs.pinnedAction().equals("torch"), "saved action order and pinned action override defaults");
            prefs.resetPanelSettings(); require(prefs.panelColumns() == source.getJSONObject("defaults").getJSONObject("controlCenter").getInt("columns"), "explicit reset applies configured default");
            prefs.undoPanelSettings(); require(prefs.panelColumns() == savedColumns, "reset remains undoable");
            data.edit().remove("status_scale").commit(); require(prefs.statusScale() == BuildConfig.DEFAULTS_STATUS_SCALE, "absent preference uses new default");
        } finally {
            SharedPreferences.Editor restore = data.edit().clear();
            for (Map.Entry<String, ?> entry : original.entrySet()) {
                Object value = entry.getValue(); String key = entry.getKey();
                if (value instanceof Boolean flag) restore.putBoolean(key, flag); else if (value instanceof Integer number) restore.putInt(key, number); else if (value instanceof Long number) restore.putLong(key, number); else if (value instanceof Float number) restore.putFloat(key, number); else if (value instanceof String text) restore.putString(key, text); else if (value instanceof Set<?> items) restore.putStringSet(key, new java.util.HashSet<>((Set<String>) items));
            }
            restore.commit();
        }
        return "PASS app-config assertions=" + assertions;
    }
}
