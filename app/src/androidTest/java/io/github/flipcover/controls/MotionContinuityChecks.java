package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Real MotionEvents and native views; no accessibility overlay, Shizuku or external app actions. */
final class MotionContinuityChecks {
    private final Instrumentation test;
    private Activity activity;
    private Prefs prefs;
    private int assertions;
    MotionContinuityChecks(Instrumentation test) { this.test = test; }
    String run() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.runOnMainSync(() -> {
                prefs = new Prefs(activity); prefs.data.edit().clear().putInt("per_page", 4).putBoolean("haptics", false).commit();
                for (int edge = 0; edge < 4; edge++) {
                    checkDockPage(edge);
                    checkSurface(edge, MotionEvent.ACTION_CANCEL);
                    checkSurface(edge, MotionEvent.ACTION_POINTER_DOWN);
                    checkPanelPull(edge, false);
                    checkPanelPull(edge, true);
                    checkFreshPanelPull(edge, "notifications");
                    checkFreshPanelPull(edge, "controls");
                }
            });
            return "PASS: " + assertions + " motion continuity assertions; native component gestures, not Samsung overlay routing or frame-rate validation";
        } finally { test.runOnMainSync(() -> activity.finish()); }
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private boolean near(float actual, float expected) { return Math.abs(actual - expected) < .001f; }
    private boolean vertical(int edge) { return edge == DockGeometry.LEFT || edge == DockGeometry.RIGHT; }
    private float inwardX(int edge) { return edge == DockGeometry.LEFT ? 1 : edge == DockGeometry.RIGHT ? -1 : 0; }
    private float inwardY(int edge) { return edge == DockGeometry.TOP ? 1 : edge == DockGeometry.BOTTOM ? -1 : 0; }
    private DockGeometry.Placement placement(int edge) {
        int width = Ui.dp(activity, vertical(edge) ? 96 : 300), height = Ui.dp(activity, vertical(edge) ? 300 : 96);
        DockGeometry.Box box = new DockGeometry.Box(0, 0, width, height);
        return new DockGeometry.Placement(box, box, box, edge, false);
    }
    private void mount(View view, int width, int height) {
        FrameLayout root = new FrameLayout(activity); root.addView(view, new FrameLayout.LayoutParams(width, height)); activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height);
    }
    private void checkDockPage(int edge) {
        DockGeometry.Placement place = placement(edge);
        DockView dock = new DockView(activity, prefs, place, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } });
        mount(dock, place.touch().width(), place.touch().height());
        View track = (View) field(dock, "track"), pager = dock.findViewWithTag("paging-area");
        int extent = (int) field(dock, "extent"); float midpoint = -extent * .5f;
        // Deterministic in-flight frame, with a real ViewPropertyAnimator for DOWN to cancel.
        if (vertical(edge)) { track.setTranslationY(midpoint); track.animate().translationY(-extent).setDuration(1000).start(); }
        else { track.setTranslationX(midpoint); track.animate().translationX(-extent).setDuration(1000).start(); }
        long time = SystemClock.uptimeMillis(); float x = pager.getX() + pager.getWidth() / 2f, y = pager.getY() + pager.getHeight() / 2f;
        event(dock, time, 0, MotionEvent.ACTION_DOWN, x, y);
        event(dock, time, 30, MotionEvent.ACTION_MOVE, x + (vertical(edge) ? 0 : 1), y + (vertical(edge) ? 1 : 0));
        require(near(vertical(edge) ? track.getTranslationY() : track.getTranslationX(), midpoint), "dock keeps interrupted frame before slop on edge " + edge);
        float movement = Ui.dp(activity, 36);
        event(dock, time, 60, MotionEvent.ACTION_MOVE, x + (vertical(edge) ? 0 : movement), y + (vertical(edge) ? movement : 0));
        float after = vertical(edge) ? track.getTranslationY() : track.getTranslationX();
        require(after > midpoint && after < 0 && after - midpoint <= movement, "dock continues from visible position on edge " + edge);
        event(dock, time, 90, MotionEvent.ACTION_POINTER_DOWN, x, y);
        event(dock, time, 120, MotionEvent.ACTION_UP, x, y);
        require(dock.page() == 0, "second finger cancels dock page change on edge " + edge);
        track.animate().cancel();
    }
    private void checkSurface(int edge, int terminal) {
        float[] progress = {.5f}; int[] starts = {0}, finishes = {0}; boolean[] closed = {false};
        PanelSurface surface = new PanelSurface(activity, edge, 400, false, new PanelHeaderView.Listener() {
            public void begin() { starts[0]++; }
            public float currentProgress() { return progress[0]; }
            public boolean cancelCloses() { return true; }
            public void progress(float value) { progress[0] = value; }
            public void finish(boolean close) { finishes[0]++; closed[0] = close; }
        });
        mount(surface, 600, 600);
        long time = SystemClock.uptimeMillis(); float dx = -inwardX(edge), dy = -inwardY(edge);
        event(surface, time, 0, MotionEvent.ACTION_DOWN, 300, 300);
        event(surface, time, 40, MotionEvent.ACTION_MOVE, 300 + dx * 40, 300 + dy * 40);
        require(starts[0] == 1 && near(progress[0], .5f), "surface starts at partial progress on edge " + edge);
        event(surface, time, 80, MotionEvent.ACTION_MOVE, 300 + dx * 80, 300 + dy * 80);
        require(near(progress[0], .4f), "surface follows outward movement on edge " + edge);
        event(surface, time, 120, MotionEvent.ACTION_MOVE, 300 - dx * 20, 300 - dy * 20);
        require(near(progress[0], .65f), "surface reverses past touch origin on edge " + edge);
        event(surface, time, 160, terminal, 300 - dx * 20, 300 - dy * 20);
        if (terminal == MotionEvent.ACTION_POINTER_DOWN) event(surface, time, 200, MotionEvent.ACTION_UP, 300, 300);
        require(finishes[0] == 1 && closed[0], "surface cancellation restores previous closing target once on edge " + edge);
        // A new inward gesture can also take over a partially closed surface.
        progress[0] = .5f;
        event(surface, time, 240, MotionEvent.ACTION_DOWN, 300, 300);
        event(surface, time, 280, MotionEvent.ACTION_MOVE, 300 - dx * 40, 300 - dy * 40);
        event(surface, time, 320, MotionEvent.ACTION_MOVE, 300 - dx * 80, 300 - dy * 80);
        require(starts[0] == 2 && near(progress[0], .6f), "inward takeover can reopen partial surface on edge " + edge);
        event(surface, time, 360, MotionEvent.ACTION_CANCEL, 300, 300);
    }
    private void checkPanelPull(int edge, boolean previousTargetOpen) {
        DockGeometry.Placement place = placement(edge); CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs; owner.placement = place;
        LinearLayout panel = new LinearLayout(activity);
        field(owner, "panel", panel); field(owner, "panelFrame", new DockGeometry.Box(0, 0, 400, 400)); field(owner, "panelPage", "controls");
        field(owner, "panelProgress", .5f); field(owner, "panelDragging", true); field(owner, "panelTargetOpen", previousTargetOpen);
        DockGeometry.Placement entryPlace = placement(edge == DockGeometry.TOP ? DockGeometry.TOP : DockGeometry.BOTTOM); field(owner, "panelEntryPlacement", entryPlace);
        PanelEntryView dock = new PanelEntryView(activity, prefs, entryPlace, new DockView.Listener() {
            public void action(String id) { } public void configure() { }
            public void beginPull(String page, float distance, float originY) { invoke(owner, "beginPanelPull", new Class<?>[]{String.class, float.class, float.class}, page, distance, originY); }
            public void pull(String page, float distance) { invoke(owner, "pullPanel", new Class<?>[]{String.class, float.class}, page, distance); }
            public void release(String page, float distance, float speed, boolean canceled) { invoke(owner, "releasePanel", new Class<?>[]{float.class, float.class, boolean.class}, distance, speed, canceled); }
        });
        mount(dock, entryPlace.touch().width(), entryPlace.touch().height());
        float x = dock.getWidth() * .75f, y = dock.getHeight() - 2;
        float dx = 0, dy = entryPlace.edge() == DockGeometry.TOP ? 1 : -1; long time = SystemClock.uptimeMillis();
        try {
            event(dock, time, 0, MotionEvent.ACTION_DOWN, x, y);
            event(dock, time, 40, MotionEvent.ACTION_MOVE, x + dx * 40, y + dy * 40);
            require(near((float) field(owner, "panelProgress"), .5f) && field(owner, "panel") == panel, "service reuses partial panel without jump on edge " + edge);
            event(dock, time, 80, MotionEvent.ACTION_MOVE, x + dx * 80, y + dy * 80);
            require(near((float) field(owner, "panelProgress"), .6f), "service follows continued pull on edge " + edge);
            require(near(panel.getTranslationX(), -dx * 160) && near(panel.getTranslationY(), -dy * 160), "panel follows the entry inward axis " + edge);
            event(dock, time, 120, MotionEvent.ACTION_MOVE, x - dx * 40, y - dy * 40);
            require(near((float) field(owner, "panelProgress"), .3f), "service supports reverse pull on edge " + edge);
            event(dock, time, 160, MotionEvent.ACTION_CANCEL, x, y);
            require((boolean) field(owner, "panelTargetOpen") == previousTargetOpen && !(boolean) field(owner, "panelPulling"), "service cancellation restores prior target on edge " + edge);
        } finally { owner.closePanel(); }
    }
    private void checkFreshPanelPull(int rotation, String page) {
        int width = rotation % 2 == 0 ? 720 : 748, height = rotation % 2 == 0 ? 748 : 720;
        DockGeometry.Box[] cuts = {new DockGeometry.Box(351, 682, 369, 66), new DockGeometry.Box(0, 351, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(682, 0, 66, 369)};
        float density = activity.getResources().getDisplayMetrics().density;
        DockGeometry.Placement dockPlace = DockGeometry.resolve(width, height, java.util.List.of(cuts[rotation]), density, (rotation + 3) % 4, .46f, .088f, true);
        DockGeometry.Placement entryPlace = DockGeometry.panelEntry(dockPlace, dockPlace, width, height, java.util.List.of(cuts[rotation]), density, 24, rotation == 0 ? "bottom_right" : "top_left");
        CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs; owner.placement = dockPlace;
        try { Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class); attach.setAccessible(true); attach.invoke(owner, activity); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        prefs.data.edit().putBoolean("panel_blur", false).commit();
        FrameLayout host = new FrameLayout(activity); host.setLayoutParams(new android.view.WindowManager.LayoutParams(width, height));
        android.view.WindowManager windows = (android.view.WindowManager) java.lang.reflect.Proxy.newProxyInstance(android.view.WindowManager.class.getClassLoader(), new Class<?>[]{android.view.WindowManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("updateViewLayout") && args[0] == host) host.setLayoutParams((android.view.ViewGroup.LayoutParams) args[1]);
            if (method.getName().equals("isCrossWindowBlurEnabled")) return false;
            return null;
        });
        DockView dock = new DockView(activity, prefs, dockPlace, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, true);
        field(owner, "windows", windows); field(owner, "dock", dock); field(owner, "panelHost", host); field(owner, "panelFrame", new DockGeometry.Box(0, 0, width, height)); field(owner, "panelEntryPlacement", entryPlace);
        PanelEntryView entry = new PanelEntryView(activity, prefs, entryPlace, new DockView.Listener() {
            public void action(String id) { } public void configure() { }
            public void beginPull(String name, float distance, float originY) { invoke(owner, "beginPanelPull", new Class<?>[]{String.class, float.class, float.class}, name, distance, originY); }
            public void pull(String name, float distance) { invoke(owner, "pullPanel", new Class<?>[]{String.class, float.class}, name, distance); }
            public void release(String name, float distance, float speed, boolean canceled) { invoke(owner, "releasePanel", new Class<?>[]{float.class, float.class, boolean.class}, distance, speed, canceled); }
        });
        mount(entry, entryPlace.touch().width(), entryPlace.touch().height());
        float fraction = page.equals("notifications") ? .25f : .75f;
        float x = entry.getWidth() * (entryPlace.vertical() ? .5f : fraction), y = entry.getHeight() * (entryPlace.vertical() ? fraction : .5f);
        float inwardX = 0, inwardY = entryPlace.edge() == DockGeometry.TOP ? 1 : -1;
        try {
            for (float finishDistance : new float[]{0, 20, 180}) {
                owner.closePanel(); long time = SystemClock.uptimeMillis();
                entryEvent(entry, entryPlace.touch(), time, 0, MotionEvent.ACTION_DOWN, x, y);
                for (int i = 0; i < 3; i++) {
                    float traveled = i == 0 ? 40 : i == 1 ? 180 : finishDistance;
                    entryEvent(entry, entryPlace.touch(), time, 100 + i * 100, MotionEvent.ACTION_MOVE, x + inwardX * traveled, y + inwardY * traveled);
                    View panel = (View) field(owner, "panel");
                    float expectedTop = entryPlace.edge() == DockGeometry.TOP ? -height + entryPlace.touch().y() + y + traveled : entryPlace.touch().y() + y - traveled;
                    require(panel != null && Math.abs(panel.getTranslationY() - expectedTop) < .01f, "fresh " + page + " follows entry travel without an initial jump at rotation " + rotation);
                    require(page.equals(field(owner, "panelPage")), "screen offset preserves left/right routing");
                }
                entryEvent(entry, entryPlace.touch(), time, 1000, MotionEvent.ACTION_UP, x + inwardX * finishDistance, y + inwardY * finishDistance);
                require((boolean) field(owner, "panelTargetOpen") == (finishDistance == 180), "cutout compensation never commits a short/reversed pull: " + page + " / " + rotation);
            }
            owner.closePanel(); long time = SystemClock.uptimeMillis();
            entryEvent(entry, entryPlace.touch(), time, 0, MotionEvent.ACTION_DOWN, x, y);
            entryEvent(entry, entryPlace.touch(), time, 100, MotionEvent.ACTION_MOVE, x + inwardX * 180, y + inwardY * 180);
            entryEvent(entry, entryPlace.touch(), time, 200, MotionEvent.ACTION_CANCEL, x + inwardX * 180, y + inwardY * 180);
            require(!(boolean) field(owner, "panelTargetOpen") && !(boolean) field(owner, "panelPulling"), "fresh pull cancellation closes at rotation " + rotation);
        } finally { owner.closePanel(); }
    }
    private void entryEvent(View entry, DockGeometry.Box box, long down, int offset, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, down + offset, action, box.x() + x, box.y() + y, 0);
        event.setLocation(x, y); entry.dispatchTouchEvent(event); event.recycle();
    }
    private void event(View target, long down, int offset, int action, float x, float y) {
        MotionEvent event;
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2]; MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) { properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER; coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = x + i * 5; coordinates[i].y = y + i * 5; coordinates[i].pressure = 1; coordinates[i].size = 1; }
            event = MotionEvent.obtain(down, down + offset, action | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, 2, properties, coordinates, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        } else event = MotionEvent.obtain(down, down + offset, action, x, y, 0);
        target.dispatchTouchEvent(event); event.recycle();
    }
    private Object field(Object owner, String name) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); } }
    private void field(Object owner, String name, Object value) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); } }
    private void invoke(Object owner, String name, Class<?>[] types, Object... values) { try { Method method = owner.getClass().getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(owner, values); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); } }
}
