package io.github.flipcover.controls;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import org.json.JSONException;
import org.json.JSONObject;

/** Preference contracts only; the instrumentation entry point enforces the emulator-only guard. */
final class PanelSettingsChecks {
    private static final String[] BOOLEAN_FIELDS = {"labels", "brightness", "volume", "media", "mediaIdle"};
    private static final String[] PROTECTED_FIELDS = {"display", "enabled", "panel_blur", "panel", "dock", "favorites", "pinned_action", "dock_width", "dock_height", "corner_0", "hand_side", "status_scale", "app_rotations", "layout_backup", "layout_undo", "layout_saved_at"};
    private int assertions;

    static int run(Context context) throws Exception {
        SharedPreferences data = context.getSharedPreferences("cover", Context.MODE_PRIVATE);
        Map<String, ?> original = new HashMap<>(data.getAll());
        PanelSettingsChecks checks = new PanelSettingsChecks();
        try {
            Prefs prefs = new Prefs(context);
            checks.defaultsAndPresets(prefs);
            checks.atomicValidation(prefs);
            checks.localResetAndUndo(prefs);
            checks.layoutCompatibility(prefs);
        } finally {
            restore(data, original);
        }
        checks.require(original.equals(data.getAll()), "all original preferences restored after panel checks");
        return checks.assertions;
    }

    private void defaultsAndPresets(Prefs prefs) throws Exception {
        prefs.resetPanelSettings();
        prefs.panelPreset("standard");
        panelEquals(defaults(), prefs.panelSnapshot(), "standard defaults");
        require(prefs.panelDensity() == 1 && prefs.panelLabels() && prefs.panelLabelSize() == 1, "standard readability getters match snapshot");
        require(prefs.panelToolsPosition().equals("auto"), "standard tools use adaptive placement");
        for (String preset : new String[]{"standard", "compact", "easy"}) {
            prefs.panelPreset(preset);
            require(prefs.panelPreset().equals(preset), "preset has truthful selected state: " + preset);
            JSONObject saved = prefs.panelSnapshot();
            int expectedColumns = preset.equals("compact") ? 5 : preset.equals("easy") ? 3 : 4;
            int expectedDensity = preset.equals("compact") ? 0 : preset.equals("easy") ? 2 : 1;
            require(saved.getInt("columns") == expectedColumns && saved.getInt("density") == expectedDensity && saved.getInt("labelSize") == expectedDensity && saved.getBoolean("labels"), "preset uses documented readable dimensions: " + preset);
            int selectedColumns = saved.getInt("columns");
            int nextColumns = selectedColumns == 5 ? 3 : 5;
            prefs.applyPanelSettings(copy(saved).put("columns", nextColumns));
            require(prefs.panelColumns() == nextColumns, "manual column selection preserved after " + preset);
            require(prefs.panelDensity() == saved.getInt("density"), "manual columns do not change density after " + preset);
            panelEquals(copy(saved).put("columns", nextColumns), prefs.panelSnapshot(), "manual columns change only their own field after " + preset);
            require(prefs.panelPreset().equals("custom"), "manual deviation shows custom preset after " + preset);
            prefs.panelPreset(preset);
            panelEquals(saved, prefs.panelSnapshot(), "reselecting preset restores its exact values: " + preset);
        }
        Map<String, ?> before = new HashMap<>(prefs.data.getAll());
        boolean rejected = false;
        try { prefs.panelPreset("unknown"); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected && before.equals(prefs.data.getAll()), "unknown preset is rejected atomically");
    }

    private void atomicValidation(Prefs prefs) throws Exception {
        prefs.applyPanelSettings(custom());
        for (Object invalid : new Object[]{2, 6, 3.5, "4", true, JSONObject.NULL}) rejectField(prefs, "columns", invalid);
        for (String field : new String[]{"density", "labelSize"}) {
            for (Object invalid : new Object[]{-1, 3, 1.5, "1", true, JSONObject.NULL}) rejectField(prefs, field, invalid);
        }
        for (String field : BOOLEAN_FIELDS) {
            for (Object invalid : new Object[]{0, "true", JSONObject.NULL}) rejectField(prefs, field, invalid);
        }
        for (Object invalid : new Object[]{"", "left", "AUTO", 1, true, JSONObject.NULL}) rejectField(prefs, "toolsPosition", invalid);
        JSONObject complete = prefs.panelSnapshot();
        Iterator<String> keys = complete.keys();
        while (keys.hasNext()) {
            String missing = keys.next();
            JSONObject invalid = copy(complete).put("columns", 3);
            invalid.remove(missing);
            rejectedWithoutMutation(prefs, invalid, "missing field " + missing);
        }
        Map<String, ?> before = new HashMap<>(prefs.data.getAll());
        prefs.applyPanelSettings(copy(prefs.panelSnapshot()));
        require(before.equals(prefs.data.getAll()), "applying an unchanged snapshot preserves pending undo and all storage");
        prefs.applyPanelSettings(copy(prefs.panelSnapshot()).put("columns", 5.0).put("density", 0.0).put("labelSize", 2.0));
        require(before.equals(prefs.data.getAll()), "numerically equivalent JSON values do not overwrite pending undo");
    }

    private void rejectField(Prefs prefs, String field, Object invalid) throws Exception {
        JSONObject input = prefs.panelSnapshot().put("columns", 3).put("density", 1).put(field, invalid);
        rejectedWithoutMutation(prefs, input, "invalid " + field + ": " + invalid);
    }

    private void rejectedWithoutMutation(Prefs prefs, JSONObject input, String message) throws Exception {
        Map<String, ?> before = new HashMap<>(prefs.data.getAll());
        boolean rejected = false;
        try { prefs.applyPanelSettings(input); } catch (IllegalArgumentException | JSONException expected) { rejected = true; }
        require(rejected, message + " rejected");
        require(before.equals(prefs.data.getAll()), message + " leaves live settings and undo untouched");
    }

    private void localResetAndUndo(Prefs prefs) throws Exception {
        prefs.data.edit().putInt("display", 71).putBoolean("enabled", false).putBoolean("panel_blur", false)
            .putString("panel", "[\"torch\",\"wifi\"]").putString("dock", "[\"home\",\"back\"]").putString("favorites", "[\"rotation\"]")
            .putString("pinned_action", "home").putFloat("dock_width", .51f).putFloat("dock_height", .12f).putInt("corner_0", 1)
            .putString("hand_side", "left").putInt("status_scale", 120).putString("app_rotations", "{\"com.example.player\":2}")
            .putString("layout_backup", "existing-global-backup").putString("layout_undo", "existing-global-undo").putLong("layout_saved_at", 1234567L).commit();
        Map<String, Object> protectedBefore = protectedValues(prefs);
        prefs.panelPreset("standard");
        prefs.applyPanelSettings(custom());
        JSONObject beforeReset = prefs.panelSnapshot();
        require(prefs.hasPanelUndo(), "changed panel settings create local undo");
        prefs.resetPanelSettings();
        panelEquals(defaults(), prefs.panelSnapshot(), "local reset returns all panel settings to defaults");
        require(protectedBefore.equals(protectedValues(prefs)), "local reset preserves shared blur, actions, display, dock and global backup/undo");
        require(prefs.hasPanelUndo(), "local reset is undoable");
        Map<String, ?> afterReset = new HashMap<>(prefs.data.getAll());
        prefs.resetPanelSettings();
        require(afterReset.equals(prefs.data.getAll()), "no-op reset preserves original reset undo");
        prefs.undoPanelSettings();
        panelEquals(beforeReset, prefs.panelSnapshot(), "undo restores exact pre-reset panel settings");
        require(!prefs.hasPanelUndo(), "local undo consumed once");
        require(protectedBefore.equals(protectedValues(prefs)), "local undo preserves unrelated settings and global undo");
        prefs.panelPreset("easy");
        require(protectedBefore.equals(protectedValues(prefs)), "preset changes remain local to panel settings");
        JSONObject easy = prefs.panelSnapshot();
        require(easy.getString("toolsPosition").equals("bottom") && !easy.getBoolean("brightness") && !easy.getBoolean("volume") && !easy.getBoolean("media") && easy.getBoolean("mediaIdle"), "readability preset preserves user's tool placement and visibility choices");
    }

    private void layoutCompatibility(Prefs prefs) throws Exception {
        prefs.applyPanelSettings(custom());
        JSONObject savedPanel = prefs.panelSnapshot();
        JSONObject savedLayout = prefs.layoutSnapshot();
        require(savedLayout.getInt("version") == 9, "current layout schema includes workspace positions");
        JSONObject options = savedLayout.getJSONObject("panelOptions");
        require(options.length() == 8 && !options.has("columns"), "panel options have eight fields and no duplicate column authority");
        JSONObject expectedOptions = copy(savedPanel); expectedOptions.remove("columns");
        panelEquals(expectedOptions, options, "layout exports each panel option");
        prefs.panelPreset("standard");
        prefs.prepareLayout(copy(savedLayout), prefs.data.edit()).commit();
        panelEquals(savedPanel, prefs.panelSnapshot(), "current layout round-trip restores exact options and columns");
        require(!prefs.hasPanelUndo(), "full layout restore invalidates stale local undo");
        JSONObject duplicateColumns = copy(savedLayout); duplicateColumns.getJSONObject("panelOptions").put("columns", 3);
        prefs.prepareLayout(duplicateColumns, prefs.data.edit()).commit();
        require(prefs.panelColumns() == savedLayout.getInt("panelColumns"), "top-level panelColumns remains the only layout column authority");
        prefs.saveLayout();
        prefs.panelPreset("easy");
        JSONObject beforeRestore = prefs.panelSnapshot();
        prefs.restoreLayout(false);
        panelEquals(savedPanel, prefs.panelSnapshot(), "layout backup restores panel options");
        prefs.restoreLayout(true);
        panelEquals(beforeRestore, prefs.panelSnapshot(), "global layout undo restores previous panel options");

        for (int version = 1; version <= 5; version++) {
            prefs.applyPanelSettings(custom());
            JSONObject legacy = copy(savedLayout).put("version", version);
            legacy.remove("panelOptions");
            if (version < 5) legacy.remove("panelColumns");
            if (version < 4) { legacy.remove("hubPins"); legacy.remove("hubSort"); }
            if (version < 3) for (String key : new String[]{"chrome", "statusScale", "batteryPercent", "avoidNavigation", "navigationGap", "tapHandles"}) legacy.remove(key);
            if (version == 1) legacy.remove("hand");
            prefs.prepareLayout(legacy, prefs.data.edit()).commit();
            panelEquals(defaults().put("columns", version == 5 ? savedPanel.getInt("columns") : 4), prefs.panelSnapshot(), "legacy layout " + version + " uses defaults for new options");
            require(prefs.displayId() == 71, "legacy layout " + version + " preserves selected display");
        }
        for (Object invalid : new Object[]{JSONObject.NULL, "invalid", new JSONObject().put("density", 0)}) {
            Map<String, ?> before = new HashMap<>(prefs.data.getAll());
            boolean rejected = false;
            try { prefs.prepareLayout(copy(savedLayout).put("panelOptions", invalid), prefs.data.edit()).commit(); }
            catch (IllegalArgumentException | JSONException expected) { rejected = true; }
            require(rejected && before.equals(prefs.data.getAll()), "malformed current panel options reject entire layout atomically");
        }
    }

    private static JSONObject defaults() throws JSONException {
        return new JSONObject().put("columns", 4).put("density", 1).put("labels", true).put("labelSize", 1).put("toolsPosition", "auto").put("brightness", true).put("volume", true).put("media", true).put("mediaIdle", false);
    }
    private static JSONObject custom() throws JSONException {
        return defaults().put("columns", 5).put("density", 0).put("labels", false).put("labelSize", 2).put("toolsPosition", "bottom").put("brightness", false).put("volume", false).put("media", false).put("mediaIdle", true);
    }
    private static JSONObject copy(JSONObject value) throws JSONException { return new JSONObject(value.toString()); }
    private void panelEquals(JSONObject expected, JSONObject actual, String message) throws JSONException {
        require(expected.length() == actual.length(), message + ": same field count");
        Iterator<String> keys = expected.keys();
        while (keys.hasNext()) { String key = keys.next(); require(actual.has(key) && expected.get(key).equals(actual.get(key)), message + ": " + key); }
    }
    private static Map<String, Object> protectedValues(Prefs prefs) {
        Map<String, ?> all = prefs.data.getAll(); Map<String, Object> values = new HashMap<>();
        for (String key : PROTECTED_FIELDS) values.put(key, all.get(key)); return values;
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static void restore(SharedPreferences data, Map<String, ?> original) {
        SharedPreferences.Editor update = data.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof Boolean bool) update.putBoolean(key, bool);
            else if (value instanceof Integer integer) update.putInt(key, integer);
            else if (value instanceof Long number) update.putLong(key, number);
            else if (value instanceof Float number) update.putFloat(key, number);
            else if (value instanceof String text) update.putString(key, text);
            else if (value instanceof Set<?> set) { Set<String> strings = new HashSet<>(); for (Object item : set) strings.add((String) item); update.putStringSet(key, strings); }
            else throw new AssertionError("Unsupported preference type for " + key);
        }
        if (!update.commit()) throw new AssertionError("Unable to restore original preferences after panel checks");
    }
}
