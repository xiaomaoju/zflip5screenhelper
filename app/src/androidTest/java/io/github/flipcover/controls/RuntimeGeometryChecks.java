package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Same probe is run against the baseline APK and candidate. Geometry excludes paint/class names. */
final class RuntimeGeometryChecks {
    private final Instrumentation test;
    private final JSONArray cases = new JSONArray();
    private File directory;
    RuntimeGeometryChecks(Instrumentation test) { this.test = test; }
    String run() throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        directory = new File(test.getTargetContext().getFilesDir(), "runtime-geometry"); directory.mkdirs();
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking();
        try {
            test.runOnMainSync(() -> {
                try {
                    for (int density : new int[]{340, 440}) for (float font : new float[]{1, 1.3f, 2}) for (int rotation : new int[]{0, 1}) {
                        Configuration config = new Configuration(activity.getResources().getConfiguration()); config.densityDpi = density; config.fontScale = font;
                        Context context = new ContextThemeWrapper(activity.createConfigurationContext(config), android.R.style.Theme_Material_NoActionBar);
                        int width = rotation == 0 ? 720 : 748, height = rotation == 0 ? 624 : 596;
                        Prefs prefs = new Prefs(context); prefs.data.edit().clear().putBoolean("panel_media_idle", false).commit();
                        for (String hand : new String[]{"left", "right"}) for (int columns : new int[]{3, 4, 5}) {
                            prefs.data.edit().putString("hand_side", hand).putInt("panel_columns", columns).commit();
                            CoverService owner = new CoverService(); owner.screenContext = context; owner.prefs = prefs;
                            View controls = new Panels(owner, true).build("controls");
                            String name = "controls-" + density + "-" + font + "-" + rotation + "-" + hand + "-" + columns;
                            capture(name, controls, width, height);
                            if (font == 1 && rotation == 0 && columns == 4 && hand.equals("right")) picture(name, controls);
                        }
                        prefs.saveHubPins(apps.stream().limit(3).map(AppCatalogCache.Entry::id).toList());
                        AppHubView hub = new AppHubView(context, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
                        List<RecentTasks.Task> tasks = List.of(new RecentTasks.Task(100, 2, 0, apps.get(3).id().substring(4), apps.get(3).packageName(), false), new RecentTasks.Task(101, 2, 0, apps.get(4).id().substring(4), apps.get(4).packageName(), false), new RecentTasks.Task(102, 2, 0, apps.get(5).id().substring(4), apps.get(5).packageName(), false));
                        hub.recentResult(tasks, null); String key = density + "-" + font + "-" + rotation;
                        capture("hub-" + key, hub, width, height); hub.setExpanded(false); capture("dock-" + key, hub, width, height); hub.showTasks(true); capture("tasks-" + key, hub, width, height); hub.dispose();
                    }
                } catch (Exception error) { throw new AssertionError(error); }
            });
            Files.write(new File(directory, "geometry.json").toPath(), cases.toString().getBytes(StandardCharsets.UTF_8));
            return "PASS: runtime geometry captured; " + cases.length() + " cases; compare baseline and candidate JSON separately";
        } finally { test.runOnMainSync(activity::finish); }
    }
    private void capture(String name, View view, int width, int height) throws Exception {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height);
        JSONArray nodes = new JSONArray(); collect(view, "0", nodes); cases.put(new JSONObject().put("case", name).put("nodes", nodes));
    }
    private void collect(View view, String path, JSONArray nodes) throws Exception {
        if (view.getVisibility() == View.GONE) return;
        JSONArray values = new JSONArray(); values.put(path).put(view.getLeft()).put(view.getTop()).put(view.getWidth()).put(view.getHeight()).put(view.getPaddingLeft()).put(view.getPaddingTop()).put(view.getPaddingRight()).put(view.getPaddingBottom()).put(view.getVisibility());
        if (view instanceof TextView text) values.put(text.getTextSize()); nodes.put(values);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), path + "/" + i, nodes);
    }
    private void picture(String name, View view) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap); canvas.drawColor(0xFF07090D); view.draw(canvas);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); } finally { bitmap.recycle(); }
    }
}
