package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/** Raw native component renders only; UiSmokeInstrumentation supplies the emulator-only guard. */
final class StatusSafeAreaChecks {
    private final Instrumentation test;
    private final JSONArray samples = new JSONArray();
    private MainActivity activity;
    private Prefs prefs;
    private File directory;
    private int assertions, renders;

    private StatusSafeAreaChecks(Instrumentation test) { this.test = test; }
    static String run(Instrumentation test) throws Exception { return new StatusSafeAreaChecks(test).run(); }
    private interface Check { void run() throws Exception; }
    private void main(Check action) throws Exception {
        Throwable[] failure = {null};
        test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof Exception error) throw error;
        if (failure[0] != null) throw new AssertionError(failure[0]);
    }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }

    private String run() throws Exception {
        SharedPreferences data = test.getTargetContext().getSharedPreferences("cover", android.content.Context.MODE_PRIVATE);
        Map<String, ?> original = data.getAll();
        directory = new File(test.getTargetContext().getFilesDir(), "status-safe-area-raw");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create status-safe-area-raw");
        try {
            activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> {
                prefs = new Prefs(activity);
                prefs.data.edit().putString("chrome_style", "light").putBoolean("battery_percent", true)
                    .putBoolean("status_time", true).putBoolean("status_battery", true).putBoolean("status_wifi", true)
                    .putBoolean("status_speed", false).putBoolean("status_alarm", false).putBoolean("status_cellular", false).commit();
                checkPixels(); checkConfiguration();
            });
            evidence("PASS");
            return "PASS: status safe area; " + assertions + " assertions; " + renders + " raw component renders; files/status-safe-area-raw; no Samsung overlay or physical-corner proof";
        } catch (Exception | AssertionError failure) {
            evidence("FAIL: " + failure); throw failure;
        } finally {
            try { if (activity != null) main(() -> activity.finish()); }
            finally { restore(data, original); }
        }
    }

    private void checkPixels() throws Exception {
        int[][] insets = {{0, 0}, {0, 16}, {0, 48}, {16, 0}, {16, 16}, {16, 48}, {48, 0}, {48, 16}, {48, 48}, {8, 32}};
        for (int scale : new int[]{50, 70, 150}) {
            prefs.data.edit().putInt("status_scale", scale).commit();
            int[] baseline = null;
            for (int[] safe : insets) {
                prefs.data.edit().putInt("status_safe_left", safe[0]).putInt("status_safe_right", safe[1]).putString("hand_side", "left").commit();
                Bitmap normal = render(false, null), panel = null, rightNormal = null, rightPanel = null;
                try {
                    int[] edges = checkBounds(normal, safe[0], safe[1]);
                    if (baseline == null) baseline = edges;
                    require(Math.abs(edges[0] - baseline[0] - Ui.dp(activity, safe[0])) <= 1, "left inset remains physical dp at scale " + scale);
                    require(Math.abs(edges[1] - baseline[1] + Ui.dp(activity, safe[1])) <= 1, "right inset remains physical dp at scale " + scale);
                    panel = render(true, null); checkBounds(panel, safe[0], safe[1]);
                    require(normal.sameAs(panel), "panel and overlay pixels match with percent enabled at scale " + scale);
                    prefs.data.edit().putString("hand_side", "right").commit();
                    rightNormal = render(false, null); rightPanel = render(true, null);
                    checkBounds(rightNormal, safe[0], safe[1]); checkBounds(rightPanel, safe[0], safe[1]);
                    require(normal.sameAs(rightNormal) && panel.sameAs(rightPanel), "hand selection preserves physical left/right at " + safe[0] + "/" + safe[1]);
                    if (safe[0] == 8 && safe[1] == 32) { save(normal, "overlay-" + scale + "-8-32.png"); save(panel, "panel-" + scale + "-8-32.png"); }
                    samples.put(new JSONObject().put("scale", scale).put("safeLeftDp", safe[0]).put("safeRightDp", safe[1]).put("foregroundMinX", edges[0]).put("foregroundMaxX", edges[1]).put("widthPx", normal.getWidth()));
                } finally { recycle(normal, panel, rightNormal, rightPanel); }
            }
            prefs.data.edit().putInt("status_safe_left", 0).putInt("status_safe_right", 0).commit();
            Map<String, ?> before = prefs.data.getAll();
            Bitmap preview = render(false, new int[]{8, 32});
            try {
                checkBounds(preview, 8, 32); require(before.equals(prefs.data.getAll()), "safe-area preview does not persist preferences");
                prefs.data.edit().putInt("status_safe_left", 8).putInt("status_safe_right", 32).commit();
                Bitmap saved = render(false, null);
                try { require(preview.sameAs(saved), "preview pixels equal committed safe area at " + scale); } finally { saved.recycle(); }
            } finally { preview.recycle(); }
        }
    }

    private Bitmap render(boolean panel, int[] preview) throws Exception {
        StatusBarView view = new StatusBarView(activity, prefs, panel);
        for (String key : new String[]{"clock", "battery", "wifi", "charging"}) {
            Field field = StatusBarView.class.getDeclaredField(key); field.setAccessible(true);
            field.set(view, key.equals("clock") ? "09:41" : key.equals("battery") ? 86 : true);
        }
        if (preview != null) view.previewSafeArea(preview[0], preview[1]);
        int width = Ui.dp(activity, 360), height = view.heightPixels();
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        view.draw(new Canvas(bitmap)); renders++; return bitmap;
    }

    private int[] checkBounds(Bitmap bitmap, int leftDp, int rightDp) {
        int width = bitmap.getWidth(), height = bitmap.getHeight(), left = Ui.dp(activity, leftDp), right = width - Ui.dp(activity, rightDp);
        int min = width, max = -1, foreground = 0; boolean clear = true;
        int[] pixels = new int[width * height]; bitmap.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) if ((pixels[y * width + x] >>> 24) != 0) {
            foreground++; min = Math.min(min, x); max = Math.max(max, x); if (x < left || x >= right) clear = false;
        }
        require(clear, "safe-area pixels remain transparent: " + leftDp + "/" + rightDp);
        require(foreground > 0 && min < left + (right - left) / 3 && max > right - (right - left) / 3, "clock and battery remain visible inside safe area");
        return new int[]{min, max};
    }

    private void checkConfiguration() throws Exception {
        prefs.data.edit().remove("status_safe_left").remove("status_safe_right").commit();
        require(prefs.statusSafeLeft() == 16 && prefs.statusSafeRight() == 16, "fresh safe-area defaults are 16 dp");
        prefs.data.edit().putInt("status_safe_left", 8).putInt("status_safe_right", 32).commit();
        JSONObject config = activity.exportConfigurationData(), layout = config.getJSONObject("layout");
        require(config.getInt("version") == 13 && layout.getInt("version") == 9, "configuration 13 embeds layout 9");
        require(layout.getInt("statusSafeLeft") == 8 && layout.getInt("statusSafeRight") == 32, "export preserves asymmetric safe area");
        prefs.data.edit().putInt("status_safe_left", 48).putInt("status_safe_right", 0).commit(); activity.applyConfigurationData(config);
        require(prefs.statusSafeLeft() == 8 && prefs.statusSafeRight() == 32, "new configuration round trip");
        for (String key : new String[]{"statusSafeLeft", "statusSafeRight"}) for (Object invalid : new Object[]{-1, 49, 1.5, "16", true, JSONObject.NULL}) {
            JSONObject corrupt = new JSONObject(config.toString()); corrupt.getJSONObject("layout").put(key, invalid);
            corrupt.put("haptics", !prefs.haptics()); Map<String, ?> before = prefs.data.getAll(); boolean rejected = false;
            try { activity.applyConfigurationData(corrupt); } catch (IllegalArgumentException | org.json.JSONException expected) { rejected = true; }
            require(rejected && before.equals(prefs.data.getAll()), "invalid " + key + " atomically rejects configuration: " + invalid);
            JSONObject badLayout = new JSONObject(layout.toString()).put(key, invalid); rejected = false;
            try { prefs.prepareLayout(badLayout, prefs.data.edit()).commit(); } catch (IllegalArgumentException | org.json.JSONException expected) { rejected = true; }
            require(rejected && before.equals(prefs.data.getAll()), "invalid " + key + " atomically rejects layout: " + invalid);
        }
        for (int version = 1; version <= 6; version++) {
            JSONObject legacy = new JSONObject(layout.toString()).put("version", version); legacy.remove("statusSafeLeft"); legacy.remove("statusSafeRight");
            prefs.data.edit().putInt("status_safe_left", 48).putInt("status_safe_right", 0).commit(); prefs.prepareLayout(legacy, prefs.data.edit()).commit();
            require(prefs.statusSafeLeft() == 16 && prefs.statusSafeRight() == 16, "layout " + version + " restores default safe area");
        }
        JSONObject legacyConfig = new JSONObject(config.toString()).put("version", 9); JSONObject legacyLayout = legacyConfig.getJSONObject("layout").put("version", 6); legacyLayout.remove("statusSafeLeft"); legacyLayout.remove("statusSafeRight");
        prefs.data.edit().putInt("status_safe_left", 48).putInt("status_safe_right", 0).commit(); activity.applyConfigurationData(legacyConfig);
        require(prefs.statusSafeLeft() == 16 && prefs.statusSafeRight() == 16, "configuration 9/layout 6 imports safe defaults");
        prefs.data.edit().putInt("status_safe_left", 8).putInt("status_safe_right", 32).commit(); prefs.resetPanelSettings();
        require(prefs.statusSafeLeft() == 8 && prefs.statusSafeRight() == 32, "panel-only reset preserves status safe area");
        prefs.saveLayout(); prefs.data.edit().putInt("status_safe_left", 48).putInt("status_safe_right", 0).commit(); prefs.restoreLayout(false);
        require(prefs.statusSafeLeft() == 8 && prefs.statusSafeRight() == 32, "layout backup restores both safe areas");
        prefs.restoreLayout(true); require(prefs.statusSafeLeft() == 48 && prefs.statusSafeRight() == 0, "layout restore undo preserves prior safe areas");
        prefs.resetLayout(); require(prefs.statusSafeLeft() == 16 && prefs.statusSafeRight() == 16, "global layout reset restores defaults");
        prefs.restoreLayout(true); require(prefs.statusSafeLeft() == 48 && prefs.statusSafeRight() == 0, "global reset can undo safe area changes");
    }

    private void save(Bitmap bitmap, String name) throws Exception { try (FileOutputStream output = new FileOutputStream(new File(directory, name))) { require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output), "raw PNG written"); } }
    private void evidence(String result) throws Exception {
        JSONObject value = new JSONObject().put("result", result).put("assertions", assertions).put("renders", renders).put("scope", "raw native components; not Samsung physical validation").put("samples", samples);
        Files.write(new File(directory, "result.json").toPath(), value.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private static void recycle(Bitmap... bitmaps) { for (Bitmap bitmap : bitmaps) if (bitmap != null) bitmap.recycle(); }
    private static void restore(SharedPreferences data, Map<String, ?> original) {
        SharedPreferences.Editor editor = data.edit().clear();
        for (Map.Entry<String, ?> entry : original.entrySet()) {
            String key = entry.getKey(); Object value = entry.getValue();
            if (value instanceof String text) editor.putString(key, text);
            else if (value instanceof Integer number) editor.putInt(key, number);
            else if (value instanceof Long number) editor.putLong(key, number);
            else if (value instanceof Float number) editor.putFloat(key, number);
            else if (value instanceof Boolean enabled) editor.putBoolean(key, enabled);
            else if (value instanceof Set<?> values) { Set<String> strings = new HashSet<>(); for (Object item : values) strings.add((String) item); editor.putStringSet(key, strings); }
            else throw new AssertionError("Unexpected preference type for " + key);
        }
        if (!editor.commit()) throw new AssertionError("Cannot restore preferences after safe-area check");
    }
}
