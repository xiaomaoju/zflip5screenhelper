package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Real input and Activity state checks; the instrumentation runner rejects physical devices. */
final class SettingsOrderChecks {
    private final Instrumentation test;
    private volatile MainActivity activity;
    private int assertions;
    private Prefs prefs;
    private final List<String> fixture = List.of("wifi", "bluetooth", "data", "torch", "dnd", "airplane", "rotation", "media", "screenshot", "lock", "notifications", "apps", "configure");
    SettingsOrderChecks(Instrumentation test) { this.test = test; }
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity value, Bundle state) { if (value instanceof MainActivity main) activity = main; }
        public void onActivityStarted(Activity value) { } public void onActivityResumed(Activity value) { } public void onActivityPaused(Activity value) { }
        public void onActivityStopped(Activity value) { } public void onActivitySaveInstanceState(Activity value, Bundle out) { } public void onActivityDestroyed(Activity value) { }
    };
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private View tag(String value) { return activity.findViewById(android.R.id.content).findViewWithTag(value); }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void click(String value) { main(() -> { View view = tag(value); require(view != null && view.isEnabled(), "action exists: " + value); view.performClick(); }); SystemClock.sleep(100); }
    private List<String> order() { ArrayList<String> ids = new ArrayList<>(); ViewGroup list = (ViewGroup) tag("order-list"); for (int i = 0; i < list.getChildCount(); i++) { Object value = list.getChildAt(i).getTag(); if (value instanceof String id) ids.add(id.substring("order-item-".length())); } return ids; }
    private void open() {
        if (activity != null) main(() -> activity.finish());
        activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "panel")); test.waitForIdleSync();
        main(() -> { TextView label = findText(activity.findViewById(android.R.id.content), "快捷按钮"); View parent = label; while (!parent.isClickable()) parent = (View) parent.getParent(); parent.performClick(); }); SystemClock.sleep(150);
    }
    private TextView findText(View view, String label) { if (view instanceof TextView text && label.contentEquals(text.getText())) return text; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView result = findText(group.getChildAt(i), label); if (result != null) return result; } return null; }
    private float[] point(View view, float fraction) { android.graphics.Rect physical = new android.graphics.Rect(); main(() -> { physical.set(SettingsUi.Viewport.bounds(view, null, activity.findViewById(android.R.id.content))); int[] origin = new int[2]; activity.findViewById(android.R.id.content).getLocationOnScreen(origin); physical.offset(origin[0], origin[1]); }); return new float[]{physical.exactCenterX(), physical.top + physical.height() * fraction}; }
    private void input(long down, int action, float x, float y) { MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN); require(test.getUiAutomation().injectInputEvent(event, true), "input accepted"); event.recycle(); }
    private void drag(float[] from, float[] to, int dwell) {
        long down = SystemClock.uptimeMillis(); input(down, MotionEvent.ACTION_DOWN, from[0], from[1]); SystemClock.sleep(700);
        for (int i = 1; i <= 8; i++) { input(down, MotionEvent.ACTION_MOVE, from[0] + (to[0] - from[0]) * i / 8, from[1] + (to[1] - from[1]) * i / 8); SystemClock.sleep(25); }
        SystemClock.sleep(dwell); input(down, MotionEvent.ACTION_UP, to[0], to[1]); SystemClock.sleep(350); test.waitForIdleSync();
    }
    private void recreate() { MainActivity before = activity; main(() -> activity.recreate()); for (int i = 0; i < 60 && activity == before; i++) SystemClock.sleep(50); test.waitForIdleSync(); require(activity != before, "Activity recreated"); SystemClock.sleep(150); }
    private void screenshot(String name) throws Exception { test.waitForIdleSync(); SystemClock.sleep(250); Bitmap bitmap = test.getUiAutomation().takeScreenshot(); File file = new File(test.getTargetContext().getExternalFilesDir(null), "order-" + name + ".png"); try (FileOutputStream out = new FileOutputStream(file)) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } bitmap.recycle(); }
    String run() throws Exception { return run(true); }
    String run(boolean includeRuntimeEditor) throws Exception {
        Application app = (Application) test.getTargetContext().getApplicationContext(); app.registerActivityLifecycleCallbacks(lifecycle); prefs = new Prefs(test.getTargetContext());
        // This scenario owns its disposable emulator; it never enables the overlay or selects a display.
        prefs.data.edit().clear().commit();
        prefs = new Prefs(test.getTargetContext());
        List<String> valid = new ArrayList<>(fixture); for (String id : valid) require(ActionCatalog.valid(id), "valid fixture item");
        require(valid.size() >= 6, "fixture uses actual built-in IDs"); prefs.saveActions("panel", valid);
        try {
            open(); List<String> original = new ArrayList<>(order()); require(original.equals(valid), "saved order loaded");
            ViewGroup list = (ViewGroup) tag("order-list"); ViewGroup row = (ViewGroup) list.getChildAt(0);
            require(row.getChildCount() == 4 && row.getChildAt(0) instanceof android.widget.FrameLayout, "single row has icon, title, remove, handle");
            require(((android.widget.ImageView) row.getChildAt(0).findViewWithTag("app-icon")).getDrawable() != null, "item icon exists");
            require(((TextView) row.getChildAt(1)).getMaxLines() == 1, "name is one line");
            require(row.getChildAt(2).getWidth() >= Ui.dp(activity, 48) && row.getChildAt(3).getWidth() >= Ui.dp(activity, 48), "both controls retain 48dp targets");
            screenshot("compact");
            click("order-add"); click("library-tab-tiles"); require(tag("library-tab-tiles").isSelected(), "tile source selected in one tap");
            click("library-tab-apps"); for (int i = 0; i < 80 && ((android.widget.ListView) tag("settings-app-list")).getAdapter().getCount() <= 1; i++) SystemClock.sleep(50); test.waitForIdleSync();
            main(() -> ((android.widget.EditText) tag("settings-query")).setText("外屏")); screenshot("apps");
            click("library-tab-builtin"); SystemClock.sleep(180); require(tag("library-item-wifi").isSelected(), "built-in selected marker reflects draft"); require(!tag("library-item-wifi").isClickable(), "selected row cannot duplicate"); screenshot("sources");
            click("library-tab-apps"); require("外屏".contentEquals(((TextView) tag("settings-query")).getText()), "tab switch preserves application search");
            recreate(); require(tag("library-tab-apps").isSelected(), "recreation restores selected tab");
            main(() -> activity.onBackPressed()); require(tag("order-list") != null, "back leaves picker directly without replaying tab switches");
            main(() -> ((ScrollView) tag("settings-scroll")).scrollTo(0, tag("order-list").getTop()));
            String first = original.get(0), second = original.get(1);
            drag(point(tag("order-handle-" + first), .5f), point(tag("order-item-" + second), .65f), 0);
            require(order().get(1).equals(first) && order().get(0).equals(second), "real drag moves first below second"); require(prefs.actions("panel").equals(original), "drag does not save preferences");
            List<String> beforeCancel = order(); drag(point(tag("order-handle-" + second), .5f), point(tag("settings-title"), .5f), 0); require(order().equals(beforeCancel), "drop outside list cancels without mutation");
            click("order-handle-" + first); click("order-position-0"); require(order().equals(original), "menu can move back to top");
            main(() -> tag("order-name-" + first).performAccessibilityAction(R.id.order_move_down, null)); require(order().get(1).equals(first), "TalkBack move works");
            click("order-remove-" + first); require(!order().contains(first), "remove changes draft"); require(tag("order-undo-bar").getVisibility() == View.VISIBLE, "undo is visible outside scroll");
            recreate(); require(!order().contains(first), "rotation keeps removed draft"); click("order-undo"); require(order().get(1).equals(first), "undo after recreation restores position");
            click("order-remove-" + first); click("order-add"); SystemClock.sleep(250); main(() -> activity.onBackPressed()); click("order-undo"); require(order().get(1).equals(first), "picker return retains undo");
            main(() -> ((ScrollView) tag("settings-scroll")).scrollTo(0, 0)); String edgeItem = order().get(0); ScrollView scroll = (ScrollView) tag("settings-scroll");
            float[] edge = point(scroll, .95f); drag(point(tag("order-handle-" + edgeItem), .5f), edge, 700); require(scroll.getScrollY() > 0, "holding near lower edge auto scrolls"); require(order().indexOf(edgeItem) > 1, "edge drop reaches later items");
            require(tag("order-done").getGlobalVisibleRect(new android.graphics.Rect()), "done stays visible after scrolling");
            List<String> saved = order(); click("order-done"); require(prefs.actions("panel").equals(saved), "done commits exact draft");
            open(); click("order-remove-" + saved.get(0)); main(() -> activity.onBackPressed()); click("settings-confirm"); require(prefs.actions("panel").equals(saved), "discard preserves saved configuration");
            open(); for (String id : new ArrayList<>(order())) click("order-remove-" + id); require(order().isEmpty(), "empty list supported"); require(findText(tag("order-list"), "还没有快捷项") != null, "empty state is visible"); click("order-undo"); require(order().size() == 1, "empty list can undo last removal");
            prefs.saveActions("panel", original); open(); screenshot("final");
            if (includeRuntimeEditor) checkInline();
            require(prefs.displayId() == -1 && !prefs.enabled(), "no display or permission side effects");
            return "PASS: " + (includeRuntimeEditor ? "shortcut editor" : "settings order") + "; " + assertions + " assertions; native drag and edge scroll, undo/recreation, tabs, search restoration" + (includeRuntimeEditor ? ", inline panel editor" : "");
        } finally { if (activity != null) main(() -> activity.finish()); app.unregisterActivityLifecycleCallbacks(lifecycle); }
    }
    private CoverService mountPanel() {
        CoverService owner = new CoverService();
        main(() -> {
            owner.screenContext = activity; owner.prefs = prefs; owner.display = activity.getDisplay();
            int width = activity.getResources().getDisplayMetrics().widthPixels, height = activity.getResources().getDisplayMetrics().heightPixels - Ui.dp(activity, 72);
            DockGeometry.Box area = new DockGeometry.Box(0, 0, width, height); owner.placement = new DockGeometry.Placement(new DockGeometry.Box(0, height, width, 1), new DockGeometry.Box(0, height, width, 1), area, DockGeometry.BOTTOM, false);
            android.widget.LinearLayout surface = owner.buildPanelContent("controls", area, 0); surface.setTag("test-panel-surface"); android.widget.FrameLayout host = new android.widget.FrameLayout(activity); host.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()); view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets; }); host.addView(surface); activity.setContentView(host); host.requestApplyInsets();
        });
        return owner;
    }
    private void checkInline() throws Exception { assertions += new ControlGridChecks(test).runOnPanel(activity, prefs, mountPanel(), false); }
    String runLayout() throws Exception {
        Application app = (Application) test.getTargetContext().getApplicationContext(); app.registerActivityLifecycleCallbacks(lifecycle); prefs = new Prefs(test.getTargetContext()); prefs.saveActions("panel", fixture);
        try {
            open(); screenshot("layout-order"); require(tag("order-done").getGlobalVisibleRect(new android.graphics.Rect()), "settings save action visible");
            click("order-add"); SystemClock.sleep(150); screenshot("layout-tabs");
            for (String tab : List.of("builtin", "tiles", "apps")) { TextView text = (TextView) tag("library-tab-" + tab); require(text.getLayout() != null && text.getLayout().getEllipsisCount(0) == 0 && text.getLayout().getLineWidth(0) <= text.getWidth() - text.getPaddingLeft() - text.getPaddingRight() + 1, "full tab label remains reachable: " + tab); }
            assertions += new ControlGridChecks(test).runOnPanel(activity, prefs, mountPanel(), true);
            return "PASS: editor layout; " + assertions + " assertions; density=" + activity.getResources().getDisplayMetrics().densityDpi + ", fontScale=" + activity.getResources().getConfiguration().fontScale;
        } finally { if (activity != null) main(() -> activity.finish()); app.unregisterActivityLifecycleCallbacks(lifecycle); }
    }
}
