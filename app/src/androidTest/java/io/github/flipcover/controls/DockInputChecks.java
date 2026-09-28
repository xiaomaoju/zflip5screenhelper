package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/** Native input regression. No real overlays, system actions or physical-device settings. */
final class DockInputChecks {
    private final Instrumentation test;
    private Activity activity;
    private Prefs prefs;
    private DockView dock;
    private FrameLayout drawingRoot;
    private int edge, assertions, begins, releases, toggles, configurations;
    private boolean canceled;
    private String panel;
    private final List<String> actions = new ArrayList<>();
    DockInputChecks(Instrumentation test) { this.test = test; }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.runOnMainSync(() -> {
                prefs = new Prefs(activity); prefs.data.edit().clear().putInt("per_page", 5).putBoolean("haptics", false).putBoolean("tap_handles", true).commit();
                for (edge = 0; edge < 4; edge++) for (String hand : List.of("left", "right")) {
                    prefs.data.edit().putString("hand_side", hand).commit(); checkButtonTaps(); checkPaging(); checkNoPanels();
                }
                prefs.data.edit().putBoolean("gestures_enabled", false).commit(); edge = DockGeometry.BOTTOM; mount(false, 1);
                drag(point(.25f, .5f), 0, -dp(40), MotionEvent.ACTION_UP);
                require(begins == 0 && actions.isEmpty() && dock.page() == 2, "disabled panel gestures still allow button paging");
                prefs.data.edit().putBoolean("gestures_enabled", true).commit();
            });
            for (int side = 0; side < 4; side++) { edge = side; checkHolds(); }
            require(!prefs.tapHandles(), "stored legacy tap setting cannot reactivate handle clicks");
            org.json.JSONObject legacy = prefs.layoutSnapshot().put("tapHandles", true); prefs.prepareLayout(legacy, prefs.data.edit()).commit();
            require(!prefs.tapHandles() && !prefs.layoutSnapshot().getBoolean("tapHandles"), "legacy import is compatible and re-export disables handle clicks");
            for (boolean compact : new boolean[]{false, true}) {
                android.graphics.Bitmap[] rendered = {null};
                test.runOnMainSync(() -> {
                    checkHandleDrawing(compact);
                    rendered[0] = android.graphics.Bitmap.createBitmap(drawingRoot.getWidth(), drawingRoot.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
                    drawingRoot.draw(new android.graphics.Canvas(rendered[0]));
                });
                java.io.File folder = new java.io.File(activity.getFilesDir(), "dock-input"); folder.mkdirs();
                try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(folder, compact ? "handles.png" : "buttons.png"))) { rendered[0].compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream); }
                rendered[0].recycle();
            }
            return "PASS: " + assertions + " dock input assertions; five buttons, four edges, both hands, hidden dock, no bottom panel pulls and cancellation; native components only";
        } finally { test.runOnMainSync(() -> activity.finish()); }
    }
    private int dp(float value) { return Ui.dp(activity, value); }
    private void checkHandleDrawing(boolean compact) {
        int width = activity.getResources().getDisplayMetrics().widthPixels, height = activity.getResources().getDisplayMetrics().heightPixels;
        float density = activity.getResources().getDisplayMetrics().density;
        DockGeometry.Placement p = DockGeometry.edgeTouch(DockGeometry.resolve(width, height, List.of(), density, 3, .46f, .088f, false), width, height);
        if (compact) p = DockGeometry.handlesOnly(p, density);
        DockGeometry.Chrome chrome = DockGeometry.chrome(p, density);
        DockView sample = new DockView(activity, prefs, p, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, compact);
        FrameLayout root = new FrameLayout(activity); drawingRoot = root; root.setBackgroundColor(0xFF124D76);
        FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(p.touch().width(), p.touch().height()); position.leftMargin = p.touch().x(); position.topMargin = p.touch().y(); root.addView(sample, position); activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height);
        View fixed = sample.findViewWithTag("fixed-action");
        require(compact || fixed.getHeight() - fixed.getPaddingBottom() <= chrome.firstHandle().y() - dp(3), "native icon padding leaves a separate handle lane");
        if (compact) {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(sample.getWidth(), sample.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
            sample.draw(new android.graphics.Canvas(bitmap));
            int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()]; bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
            for (int pixel : pixels) if (android.graphics.Color.alpha(pixel) != 0) throw new AssertionError("hidden dock still draws a handle");
            bitmap.recycle(); require(true, "hidden dock is fully transparent");
        }
        require(chrome.firstHandle().width() == chrome.secondHandle().width() && chrome.firstHandle().y() == chrome.secondHandle().y(), "legacy spacing anchors remain stable after removing white bars");
    }
    private boolean vertical() { return edge == DockGeometry.LEFT || edge == DockGeometry.RIGHT; }
    private float inwardX() { return edge == DockGeometry.LEFT ? 1 : edge == DockGeometry.RIGHT ? -1 : 0; }
    private float inwardY() { return edge == DockGeometry.TOP ? 1 : edge == DockGeometry.BOTTOM ? -1 : 0; }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message + " [edge=" + edge + ", hand=" + prefs.handSide() + "]"); assertions++; }
    private void mount(boolean compact, int page) {
        actions.clear(); begins = releases = toggles = configurations = 0; panel = ""; canceled = false;
        int width = dp(vertical() ? 44 : 300), height = dp(vertical() ? 300 : 44);
        DockGeometry.Box box = new DockGeometry.Box(0, 0, width, height); DockGeometry.Placement place = new DockGeometry.Placement(box, box, box, edge, false);
        if (compact) place = DockGeometry.handlesOnly(place, activity.getResources().getDisplayMetrics().density);
        dock = new DockView(activity, prefs, place, page, new DockView.Listener() {
            public void action(String id) { actions.add(id); }
            public void configure() { configurations++; }
            public void toggleVisibility() { toggles++; }
            public void beginPull(String name, float distance) { begins++; panel = name; }
            public void release(String name, float distance, float speed, boolean cancel) { releases++; canceled = cancel; }
        }, compact);
        FrameLayout root = new FrameLayout(activity); root.addView(dock, new FrameLayout.LayoutParams(place.touch().width(), place.touch().height())); activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(place.touch().width(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(place.touch().height(), View.MeasureSpec.EXACTLY)); root.layout(0, 0, place.touch().width(), place.touch().height());
    }
    private float[] point(float alongFraction, float depth) {
        return switch (edge) {
            case DockGeometry.TOP -> new float[]{dock.getWidth() * alongFraction, depth};
            case DockGeometry.BOTTOM -> new float[]{dock.getWidth() * alongFraction, dock.getHeight() - depth};
            case DockGeometry.LEFT -> new float[]{depth, dock.getHeight() * alongFraction};
            default -> new float[]{dock.getWidth() - depth, dock.getHeight() * alongFraction};
        };
    }
    private float[] center(View view) {
        int[] origin = new int[2], position = new int[2]; dock.getLocationOnScreen(origin); view.getLocationOnScreen(position);
        return new float[]{position[0] - origin[0] + view.getWidth() / 2f, position[1] - origin[1] + view.getHeight() / 2f};
    }
    private void checkButtonTaps() {
        mount(false, 0); FrameLayout pager = dock.findViewWithTag("paging-area"); LinearLayout first = (LinearLayout) ((LinearLayout) pager.getChildAt(0)).getChildAt(0);
        List<View> buttons = new ArrayList<>(); for (int i = 0; i < first.getChildCount(); i++) buttons.add(first.getChildAt(i)); buttons.add(dock.findViewWithTag("fixed-action"));
        List<String> expected = new ArrayList<>(prefs.scrollingActions().subList(0, 4)); expected.add(prefs.pinnedAction());
        for (int i = 0; i < buttons.size(); i++) {
            float[] center = center(buttons.get(i)); float fraction = vertical() ? center[1] / dock.getHeight() : center[0] / dock.getWidth();
            for (float depth : new float[]{.5f, dp(8), dp(22)}) {
                float[] start = point(fraction, depth); int count = actions.size();
                float jitter = ViewConfiguration.get(activity).getScaledTouchSlop() * .8f;
                drag(start, inwardX() * jitter, inwardY() * jitter, MotionEvent.ACTION_UP);
                require(actions.size() == count + 1 && actions.get(count).equals(expected.get(i)) && begins == 0 && dock.page() == 0, "tap/jitter reaches button " + i + " at edge depth " + depth);
            }
        }
        mount(false, 0); float[] start = point(.25f, .5f); drag(start, 0, 0, MotionEvent.ACTION_CANCEL);
        require(actions.isEmpty() && begins == 0 && releases == 0, "canceled edge tap does nothing");
        mount(false, 1); drag(point(.3f, dp(22)), -dp(12), 0, MotionEvent.ACTION_UP);
        require(actions.isEmpty() && begins == 0 && dock.page() == 1, "short button drag does not become a tap or panel");
    }
    private void checkPaging() {
        for (boolean fixed : new boolean[]{false, true}) for (float[] delta : new float[][]{{-40, 0}, {40, 0}, {0, -40}, {0, 40}, {-40, -25}, {25, 40}}) {
            mount(false, 1); float[] start = fixed ? center(dock.findViewWithTag("fixed-action")) : point(.3f, dp(22));
            float dominant = Math.abs(delta[0]) > Math.abs(delta[1]) ? delta[0] : delta[1]; int expected = dominant < 0 ? 2 : 0;
            drag(start, dp(delta[0]), dp(delta[1]), MotionEvent.ACTION_UP);
            require(dock.page() == expected && begins == 0 && actions.isEmpty(), "non-edge swipe pages including fixed area, direction " + delta[0] + "," + delta[1]);
        }
        mount(false, 1); float[] start = point(.3f, .5f); long time = SystemClock.uptimeMillis();
        float dx = vertical() ? 0 : -dp(35), dy = vertical() ? -dp(35) : 0;
        event(time, 0, MotionEvent.ACTION_DOWN, start[0], start[1]); event(time, 50, MotionEvent.ACTION_MOVE, start[0] + dx, start[1] + dy);
        event(time, 100, MotionEvent.ACTION_MOVE, start[0] + dx + inwardX() * dp(50), start[1] + dy + inwardY() * dp(50));
        event(time, 150, MotionEvent.ACTION_UP, start[0] + dx + inwardX() * dp(50), start[1] + dy + inwardY() * dp(50));
        require(dock.page() == 2 && begins == 0 && actions.isEmpty(), "along-edge paging cannot turn into a panel pull midway");
        for (int terminal : new int[]{MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN}) {
            mount(false, 1); drag(point(.3f, dp(22)), -dp(40), 0, terminal);
            require(dock.page() == 1 && begins == 0 && actions.isEmpty(), "cancel and second finger abandon page change");
        }
    }
    private void checkNoPanels() {
        for (boolean compact : new boolean[]{false, true}) for (int terminal : new int[]{MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN}) {
            mount(compact, 1); drag(point(.25f, .5f), inwardX() * dp(45), inwardY() * dp(45), terminal);
            require(begins == 0 && releases == 0 && actions.isEmpty(), "old edge cannot open a panel");
            if (compact) require(dock.page() == 1, "hidden buttons cannot page");
        }
    }
    private void checkHolds() {
        test.runOnMainSync(() -> { mount(false, 0); float[] start = point(.3f, dp(22)); event(SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_DOWN, start[0], start[1]); });
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 70); test.waitForIdleSync();
        test.runOnMainSync(() -> { float[] start = point(.3f, dp(22)); event(SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_UP, start[0], start[1]); });
        require(configurations == 1 && actions.isEmpty() && toggles == 0 && begins == 0, "button long press remains configuration only");
        test.runOnMainSync(() -> { mount(true, 0); float[] start = point(.25f, .5f); drag(start, 0, 0, MotionEvent.ACTION_CANCEL); });
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 70); test.waitForIdleSync();
        require(toggles == 0 && configurations == 0 && begins == 0 && actions.isEmpty(), "cancellation removes pending handle long press");
    }
    private void drag(float[] start, float dx, float dy, int terminal) {
        long time = SystemClock.uptimeMillis(); event(time, 0, MotionEvent.ACTION_DOWN, start[0], start[1]);
        event(time, 60, MotionEvent.ACTION_MOVE, start[0] + dx, start[1] + dy); event(time, 120, terminal, start[0] + dx, start[1] + dy);
        if (terminal == MotionEvent.ACTION_POINTER_DOWN) event(time, 160, MotionEvent.ACTION_UP, start[0] + dx, start[1] + dy);
    }
    private void event(long time, int offset, int action, float x, float y) {
        MotionEvent event;
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2]; MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) { properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER; coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = x + i; coordinates[i].y = y + i; coordinates[i].pressure = 1; coordinates[i].size = 1; }
            event = MotionEvent.obtain(time, time + offset, action | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, 2, properties, coordinates, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        } else event = MotionEvent.obtain(time, time + offset, action, x, y, 0);
        dock.dispatchTouchEvent(event); event.recycle();
    }
}
