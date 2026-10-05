package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/** Quantity changes must preserve the selected columns and four-row viewport. */
final class ControlDashboardChecks {
    static String run(Instrumentation test) throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        int[] cases = {0};
        try {
            test.runOnMainSync(() -> {
                Configuration configuration = new Configuration(activity.getResources().getConfiguration()); configuration.densityDpi = 340;
                List<String> actions = new ArrayList<>(); for (ActionCatalog.Action action : ActionCatalog.BUILT_INS) actions.add(action.id());
                for (float fontScale : new float[]{1f, 1.5f}) for (int width : new int[]{720, 748}) for (int columns : new int[]{3, 4, 5}) for (String position : new String[]{"side", "bottom", "none"}) {
                    configuration.fontScale = fontScale;
                    Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme);
                    Prefs prefs = new Prefs(context); prefs.data.edit().clear().putInt("panel_columns", columns).putBoolean("panel_media", false).putBoolean("panel_brightness", !position.equals("none")).putBoolean("panel_volume", !position.equals("none")).putString("panel_tools_position", position.equals("none") ? "auto" : position).commit();
                    int[] baseline = null;
                    for (int count : new int[]{1, columns * 4 - 1, columns * 4, columns * 4 + 1, actions.size()}) {
                        List<String> selected = actions.subList(0, count); prefs.saveActions("panel", selected);
                        CoverService owner = new CoverService(); owner.screenContext = context; owner.prefs = prefs;
                        int height = width == 720 ? 748 : 720;
                        owner.placement = new DockGeometry.Placement(new DockGeometry.Box(0, 0, 0, 0), new DockGeometry.Box(0, 0, 0, 0), new DockGeometry.Box(0, 38, width, height - 104), DockGeometry.TOP, true);
                        View panel = owner.buildPanelContent("controls", new DockGeometry.Box(0, 0, width, height), 38);
                        panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); panel.layout(0, 0, width, height);
                        ScrollView scroll = panel.findViewWithTag("control-grid-scroll"); ViewGroup grid = panel.findViewWithTag("control-tile-grid"), tile = panel.findViewWithTag("control-" + selected.get(0));
                        ViewGroup face = (ViewGroup) tile.getChildAt(0); View glyph = face.getChildAt(0);
                        int[] geometry = {tile.getWidth(), tile.getHeight(), face.getWidth(), face.getHeight(), glyph.getWidth(), glyph.getHeight(), scroll.getWidth(), scroll.getHeight()};
                        String sample = "columns=" + columns + ", count=" + count + ", tools=" + position + ", font=" + fontScale + ", width=" + width;
                        if (baseline == null) baseline = geometry; else require(java.util.Arrays.equals(baseline, geometry), "quantity changes geometry: " + sample);
                        require(scroll.getHeight() == tile.getHeight() * 4, "viewport is exactly four rows: " + sample);
                        int firstRow = 0; for (int i = 0; i < grid.getChildCount(); i++) if (grid.getChildAt(i).getTop() == 0) firstRow++;
                        require(firstRow == Math.min(count, columns) && prefs.panelColumns() == columns, "selected column count changes: " + sample);
                        require(scroll.canScrollVertically(1) == (count > columns * 4), "only overflow scrolls: " + sample);
                        View sliders = panel.findViewWithTag("control-sliders"); int toolTop = sliders == null ? 0 : sliders.getTop();
                        scroll.scrollTo(0, grid.getHeight());
                        View tail = grid.getChildAt(grid.getChildCount() - 1);
                        require(tail.getBottom() - scroll.getScrollY() <= scroll.getHeight() && tail.getTop() - scroll.getScrollY() >= 0, "last row cannot be reached: " + sample);
                        require(sliders == null || sliders.getTop() == toolTop, "grid scroll moves tools: " + sample);
                        cases[0]++;
                    }
                }
            });
            dragGrid(test, activity);
            return "PASS: fixed four-row dashboard; " + cases[0] + " native quantity/layout cases; 3/4/5 columns, sparse/full/overflow, two orientations and font scales, side/bottom/no tools";
        } finally { test.runOnMainSync(activity::finish); }
    }
    private static void dragGrid(Instrumentation test, Activity activity) {
        PanelSurface[] surface = {null}; ScrollView[] outer = {null}; float[] progress = {1};
        test.runOnMainSync(() -> {
            Prefs prefs = new Prefs(activity); prefs.data.edit().clear().putInt("panel_columns", 4).putBoolean("panel_media", false).commit();
            List<String> actions = new ArrayList<>(); for (ActionCatalog.Action action : ActionCatalog.BUILT_INS) actions.add(action.id()); prefs.saveActions("panel", actions);
            CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs;
            Panels panels = new Panels(owner, true);
            surface[0] = new PanelSurface(activity, DockGeometry.TOP, 720, false, new PanelHeaderView.Listener() {
                @Override public void begin() { }
                @Override public float currentProgress() { return progress[0]; }
                @Override public void progress(float value) { progress[0] = value; }
                @Override public void finish(boolean close) { if (close) progress[0] = 0; }
            });
            outer[0] = new ScrollView(activity) {
                @Override protected void onMeasure(int widthSpec, int heightSpec) { panels.controlViewportHeight(View.MeasureSpec.getSize(heightSpec)); super.onMeasure(widthSpec, heightSpec); }
            };
            outer[0].setFillViewport(true); outer[0].addView(panels.build("controls")); surface[0].addView(outer[0], new android.widget.LinearLayout.LayoutParams(-1, 0, 1)); activity.setContentView(surface[0]);
        });
        test.waitForIdleSync();
        test.runOnMainSync(() -> {
            ScrollView scroll = surface[0].findViewWithTag("control-grid-scroll"); ViewGroup grid = surface[0].findViewWithTag("control-tile-grid"); View sliders = surface[0].findViewWithTag("control-sliders");
            android.graphics.Rect bounds = new android.graphics.Rect(); scroll.getDrawingRect(bounds); surface[0].offsetDescendantRectToMyCoords(scroll, bounds);
            android.graphics.Rect tools = new android.graphics.Rect(); sliders.getGlobalVisibleRect(tools);
            long start = android.os.SystemClock.uptimeMillis(); float x = bounds.exactCenterX(), y = bounds.top + bounds.height() * .8f; int distance = grid.getChildAt(0).getHeight();
            send(surface[0], start, start, android.view.MotionEvent.ACTION_DOWN, x, y);
            for (int i = 1; i <= 8; i++) send(surface[0], start, start + i * 16, android.view.MotionEvent.ACTION_MOVE, x, y - distance * i / 8f);
            send(surface[0], start, start + 144, android.view.MotionEvent.ACTION_CANCEL, x, y - distance);
            android.graphics.Rect after = new android.graphics.Rect(); sliders.getGlobalVisibleRect(after);
            require(scroll.getScrollY() > 0, "vertical drag must scroll overflow controls");
            require(progress[0] == 1 && outer[0].getScrollY() == 0 && tools.equals(after), "grid drag must keep panel and side tools stationary");
            activity.setContentView(new android.widget.FrameLayout(activity));
        });
    }
    private static void send(View target, long start, long at, int action, float x, float y) { android.view.MotionEvent event = android.view.MotionEvent.obtain(start, at, action, x, y, 0); target.dispatchTouchEvent(event); event.recycle(); }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
