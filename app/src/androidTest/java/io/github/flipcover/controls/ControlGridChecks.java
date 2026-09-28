package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ScrollView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Exercises native cross-zone drags in the real panel container on a disposable emulator. */
final class ControlGridChecks {
    private final Instrumentation test;
    private Activity activity;
    private Prefs prefs;
    private CoverService owner;
    private int assertions;
    private static final List<String> ACTIONS = List.of("wifi", "bluetooth", "data", "torch", "dnd", "airplane", "rotation", "media", "screenshot", "lock", "notifications", "apps", "configure");
    ControlGridChecks(Instrumentation test) { this.test = test; }
    private void main(Runnable work) { test.runOnMainSync(work); test.waitForIdleSync(); }
    private View tag(String id) { return activity.findViewById(android.R.id.content).findViewWithTag(id); }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void click(String id) { main(() -> { View view = tag(id); require(view != null && view.isEnabled(), "click " + id); view.performClick(); }); SystemClock.sleep(100); }
    private ArrayList<String> draft() { return ((ControlEditorView) tag("control-editor")).snapshot().getStringArrayList("draft"); }
    private float[] center(View view) { Rect rect = new Rect(); main(() -> require(view != null && view.getGlobalVisibleRect(rect), "visible drag target")); return new float[]{rect.exactCenterX(), rect.exactCenterY()}; }
    private void input(long down, int action, float x, float y) { MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN); require(test.getUiAutomation().injectInputEvent(event, true), "native input accepted"); event.recycle(); }
    private void drag(View from, View to, int dwell, boolean cancel) {
        float[] start = center(from), end = center(to); long down = SystemClock.uptimeMillis(); input(down, MotionEvent.ACTION_DOWN, start[0], start[1]); SystemClock.sleep(650);
        for (int i = 1; i <= 10; i++) { input(down, MotionEvent.ACTION_MOVE, start[0] + (end[0] - start[0]) * i / 10, start[1] + (end[1] - start[1]) * i / 10); SystemClock.sleep(20); }
        if (cancel) main(() -> ((ControlEditorView) tag("control-editor")).cancelDrag()); SystemClock.sleep(dwell); input(down, MotionEvent.ACTION_UP, end[0], end[1]); SystemClock.sleep(250); test.waitForIdleSync();
    }
    private void screenshot(String name) throws Exception { SystemClock.sleep(250); Bitmap bitmap = test.getUiAutomation().takeScreenshot(); try (FileOutputStream out = new FileOutputStream(new File(test.getTargetContext().getExternalFilesDir(null), "grid-" + name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } bitmap.recycle(); }
    private void mount() {
        main(() -> {
            owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs; owner.display = activity.getDisplay();
            int width = activity.getResources().getDisplayMetrics().widthPixels, height = activity.getResources().getDisplayMetrics().heightPixels;
            DockGeometry.Box area = new DockGeometry.Box(0, 0, width, height); owner.placement = new DockGeometry.Placement(new DockGeometry.Box(0, height, width, 1), new DockGeometry.Box(0, height, width, 1), area, DockGeometry.BOTTOM, false);
            FrameLayout host = new FrameLayout(activity); host.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets safe = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout()); view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets; });
            View panel = owner.buildPanelContent("controls", area, 0); panel.setTag("test-panel-surface"); host.addView(panel); activity.setContentView(host); host.requestApplyInsets();
        });
    }
    String run(boolean layoutOnly) throws Exception {
        prefs = new Prefs(test.getTargetContext()); prefs.data.edit().putBoolean("enabled", false).commit(); prefs.saveActions("panel", ACTIONS);
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync(); int previousColumns = prefs.panelColumns();
        try { if (layoutOnly) { for (int columns : new int[]{3, 4, 5}) { prefs.data.edit().putInt("panel_columns", columns).commit(); mount(); check(true); } } else { mount(); check(false); } return "PASS: same-screen control grid; " + assertions + " assertions; " + (layoutOnly ? "layout with 3/4/5 columns" : "native reorder/add/remove/cancel, undo, search, save/discard, subscription lifecycle") + "; density=" + activity.getResources().getDisplayMetrics().densityDpi + ", font=" + activity.getResources().getConfiguration().fontScale; }
        finally { main(() -> activity.finish()); prefs.data.edit().putInt("panel_columns", previousColumns).commit(); }
    }
    int runOnPanel(Activity activity, Prefs prefs, CoverService owner, boolean layoutOnly) throws Exception { this.activity = activity; this.prefs = prefs; this.owner = owner; check(layoutOnly); return assertions; }
    private void check(boolean layoutOnly) throws Exception {
        View panel = tag("test-panel-surface"); List<String> initial = prefs.actions("panel"); click("control-add"); screenshot("both-grids");
        require(tag("control-selected-grid") != null && tag("control-candidates") != null, "selected and candidates share the screen");
        require(((ControlEditGrid) tag("control-selected-grid")).columns() == prefs.panelColumns(), "selected grid preserves configured column count");
        require(((GridView) tag("control-candidate-grid")).getNumColumns() == prefs.panelColumns(), "candidate columns=" + ((GridView) tag("control-candidate-grid")).getNumColumns() + ", configured=" + prefs.panelColumns());
        require(owner.mediaSessions().observerCount() == 0, "hidden media subscriptions released");
        require(tag("control-selected-scroll").getHeight() >= Ui.dp(activity, 48) && tag("control-candidate-grid").getHeight() >= Ui.dp(activity, 48), "both viewports >=48dp: selected=" + tag("control-selected-scroll").getHeight() + ", candidates=" + tag("control-candidate-grid").getHeight() + ", target=" + Ui.dp(activity, 48) + ", workspace=" + tag("control-editor-workspace").getHeight() + ", columns=" + prefs.panelColumns());
        if (layoutOnly) {
            click("library-tab-apps"); waitApps(); screenshot("apps-layout"); click("control-candidates-search"); main(() -> ((android.widget.EditText) tag("control-candidate-query")).setText("外屏"));
            require(tag("control-editor-done").getGlobalVisibleRect(new Rect()), "save action remains reachable with search"); screenshot("search-layout");
            return;
        }
        String first = initial.get(0), third = initial.get(2);
        drag(tag("control-selected-" + first), tag("control-selected-" + third), 0, false);
        require(draft().get(2).equals(first), "native drag reorders a grid slot"); require(prefs.actions("panel").equals(initial), "reorder remains a draft");
        ArrayList<String> beforeCancel = draft(); drag(tag("control-selected-" + first), tag("control-editor-done"), 0, false); require(draft().equals(beforeCancel), "drop over toolbar cancels");
        drag(tag("control-selected-" + first), tag("control-candidate-grid"), 0, true); require(draft().equals(beforeCancel), "explicit drag cancellation preserves draft");
        drag(tag("control-selected-" + first), tag("control-candidate-grid"), 0, false); require(!draft().contains(first), "dragging back into candidate area removes"); click("control-editor-undo"); require(draft().equals(beforeCancel), "undo restores exact ordering");
        main(() -> ((ScrollView) tag("control-selected-scroll")).scrollTo(0, 0));
        drag(tag("control-candidate-app_hub"), tag("control-selected-" + draft().get(0)), 0, false);
        require(draft().get(0).equals("app_hub") && draft().size() == initial.size() + 1, "candidate drag inserts at exact target slot"); screenshot("after-add"); click("control-editor-undo"); require(draft().equals(beforeCancel), "add is undoable");
        main(() -> ((ScrollView) tag("control-selected-scroll")).scrollTo(0, 0));
        String edgeId = draft().get(0); float[] start = center(tag("control-selected-" + edgeId)); Rect viewport = new Rect(); main(() -> tag("control-selected-scroll").getGlobalVisibleRect(viewport));
        long down = SystemClock.uptimeMillis(); input(down, MotionEvent.ACTION_DOWN, start[0], start[1]); SystemClock.sleep(650); input(down, MotionEvent.ACTION_MOVE, start[0], viewport.bottom - 5); SystemClock.sleep(850);
        require(((ScrollView) tag("control-selected-scroll")).getScrollY() > 0, "held drag scrolls selected grid near edge"); require(tag("control-selected-scroll").getHeight() == viewport.height(), "drag preview preserves viewport geometry"); screenshot("drag-edge"); input(down, MotionEvent.ACTION_UP, start[0], viewport.bottom - 5); SystemClock.sleep(250); test.waitForIdleSync(); require(draft().indexOf(edgeId) >= prefs.panelColumns(), "edge drop reaches a later row, index=" + draft().indexOf(edgeId)); click("control-editor-undo"); require(draft().equals(beforeCancel), "edge reorder is undoable");
        click("control-candidate-app_dock"); require(draft().contains("app_dock") && tag("control-candidates") != null, "tap add keeps candidates open");
        click("control-candidate-app_dock"); require(draft().size() == initial.size() + 1, "selected candidate never duplicates");
        click("library-tab-tiles"); SystemClock.sleep(200); require(tag("library-tab-tiles").isSelected(), "tile source selected directly");
        click("library-tab-apps"); waitApps(); require(CoverApp.catalog(activity).observerCount() > 0, "visible app source observes catalog");
        click("control-candidates-search"); main(() -> ((android.widget.EditText) tag("control-candidate-query")).setText(test.getTargetContext().getPackageName())); SystemClock.sleep(150); require(((GridView) tag("control-candidate-grid")).getAdapter().getCount() > 0, "application search finds own app"); screenshot("search");
        click("control-search-close"); click("library-tab-builtin"); require(CoverApp.catalog(activity).observerCount() == 0, "leaving apps releases catalog"); click("library-tab-apps"); click("control-candidates-search"); require(((android.widget.EditText) tag("control-candidate-query")).getText().toString().equals(test.getTargetContext().getPackageName()), "per-source query survives tab switch");
        main(() -> ((ControlEditorView) tag("control-editor")).dispatchKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))); require(tag("control-candidate-query") == null, "back closes search with field focused");
        click("control-candidates-collapse"); require(tag("control-candidates") == null && CoverApp.catalog(activity).observerCount() == 0, "collapse releases candidate subscriptions"); screenshot("collapsed");
        main(() -> ((ScrollView) tag("control-selected-scroll")).scrollTo(0, 0)); click("control-selected-" + first); click("order-move-up");
        ArrayList<String> saved = draft(); click("control-editor-done"); require(prefs.actions("panel").equals(saved), "done atomically commits final list"); require(tag("test-panel-surface") == panel && tag("control-dashboard") != null, "same panel restored without Activity navigation"); require(owner.mediaSessions().observerCount() == 1, "normal media subscription resumes once");
        click("control-edit"); click("control-selected-" + saved.get(0)); click("order-menu-remove"); main(() -> owner.act("back")); main(() -> owner.act("back")); click("control-editor-discard"); require(prefs.actions("panel").equals(saved), "discard preserves saved configuration");
        click("control-edit"); click("control-selected-" + saved.get(0)); click("order-menu-remove"); Bundle state = ((ControlEditorView) tag("control-editor")).snapshot();
        main(() -> activity.setContentView(new ControlEditorView(activity, prefs, state, id -> { throw new AssertionError("unexpected tile registration"); }, () -> { })));
        click("control-editor-undo"); require(draft().equals(saved), "window recreation preserves draft and undo");
        Bundle empty = ((ControlEditorView) tag("control-editor")).snapshot(); empty.putStringArrayList("draft", new ArrayList<>()); empty.putBundle("candidates", new Bundle());
        main(() -> activity.setContentView(new ControlEditorView(activity, prefs, empty, id -> { }, () -> { }))); drag(tag("control-candidate-app_hub"), tag("control-selected-scroll"), 0, false); require(draft().equals(List.of("app_hub")), "drag can add into an empty selected grid");
        Bundle full = ((ControlEditorView) tag("control-editor")).snapshot(); full.putBundle("candidates", new Bundle()); ArrayList<String> thirty = new ArrayList<>(); for (int i = 0; i < 30; i++) thirty.add("app:test.fixture" + i + "/.Main"); full.putStringArrayList("draft", thirty);
        main(() -> activity.setContentView(new ControlEditorView(activity, prefs, full, id -> { }, () -> { }))); click("control-candidate-app_hub"); require(draft().size() == 30 && !draft().contains("app_hub"), "30 item capacity enforced without partial addition");
        main(() -> activity.setContentView(new FrameLayout(activity))); require(owner.mediaSessions().observerCount() == 0 && CoverApp.catalog(activity).observerCount() == 0, "detach leaves no catalog or media subscriptions"); SystemClock.sleep(2500);
    }
    private void waitApps() { for (int i = 0; i < 60 && ((GridView) tag("control-candidate-grid")).getAdapter().getCount() == 0; i++) SystemClock.sleep(50); test.waitForIdleSync(); }
}
