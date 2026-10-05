package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.Map;

/** Real Activity interactions on the explicitly guarded disposable emulator. */
final class SettingsUiChecks {
    private final Instrumentation test;
    private volatile MainActivity activity;
    private Prefs prefs;
    private volatile NativeWidgetActivity widget;
    private int assertions;
    SettingsUiChecks(Instrumentation test) { this.test = test; }
    private final Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity value, Bundle state) { if (value instanceof MainActivity main) activity = main; if (value instanceof NativeWidgetActivity nativeActivity) widget = nativeActivity; }
        public void onActivityStarted(Activity value) { } public void onActivityResumed(Activity value) { } public void onActivityPaused(Activity value) { } public void onActivityStopped(Activity value) { } public void onActivitySaveInstanceState(Activity value, Bundle out) { } public void onActivityDestroyed(Activity value) { }
    };
    private interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception { Throwable[] failure = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new AssertionError("Main-thread check", failure[0]); test.waitForIdleSync(); }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private View tag(String name) { return root().findViewWithTag(name); }
    private void click(String name) throws Exception { main(() -> { View target = tag(name); require(target != null && target.isEnabled(), "clickable " + name); target.performClick(); }); SystemClock.sleep(80); }
    private void label(String name) throws Exception { main(() -> { View target = findText(root(), name); require(target != null, "text found: " + name); while (!target.isClickable() && target.getParent() instanceof View view) target = view; require(target.isClickable(), "action for " + name); target.performClick(); }); }
    private View findText(View view, String text) { if (view instanceof TextView value && value.getText().toString().equals(text)) return view; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = findText(group.getChildAt(i), text); if (found != null) return found; } return null; }
    private void open(String page) throws Exception { if (activity != null) main(() -> activity.finish()); activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", page)); test.waitForIdleSync(); SystemClock.sleep(180); }
    private void back() throws Exception { main(() -> activity.onBackPressed()); SystemClock.sleep(100); }
    private void screenshot(String name) throws Exception { SystemClock.sleep(150); Bitmap image = test.getUiAutomation().takeScreenshot(); require(image != null, "capture " + name); File dir = new File(test.getTargetContext().getFilesDir(), "settings-ui-raw"); if (!dir.isDirectory() && !dir.mkdirs()) throw new java.io.IOException("Cannot create screenshot directory"); try (FileOutputStream stream = new FileOutputStream(new File(dir, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, stream); } finally { image.recycle(); } }
    String run() throws Exception {
        Application application = (Application) test.getTargetContext().getApplicationContext(); application.registerActivityLifecycleCallbacks(lifecycle);
        SharedPreferences data = test.getTargetContext().getSharedPreferences("cover", android.content.Context.MODE_PRIVATE); Map<String, ?> original = data.getAll();
        try {
            data.edit().clear().putBoolean("enabled", false).putInt("display", -1).commit(); prefs = new Prefs(test.getTargetContext());
            open("main"); for (String page : new String[]{"group_dock", "group_appearance", "panel", "group_apps", "group_device", "group_backup"}) require(tag("settings-link-" + page) != null, "home category " + page); screenshot("home");
            main(() -> { EditText input = (EditText) tag("settings-query"); input.setVisibility(View.VISIBLE); input.setText("电量"); }); click("search-status");
            require(tag("battery_percent") != null, "search reaches actual battery setting"); View stable = tag("settings-scroll"); boolean before = prefs.batteryPercent(); click("battery_percent"); require(prefs.batteryPercent() != before && tag("settings-scroll") == stable, "toggle updates in place once");
            int initial = prefs.statusScale(); main(() -> { SettingsUi.Slider slider = (SettingsUi.Slider) tag("status_scale"); long now = SystemClock.uptimeMillis(); MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, slider.getWidth() / 2f, slider.getHeight() / 2f, 0); MotionEvent move = MotionEvent.obtain(now, now + 30, MotionEvent.ACTION_MOVE, slider.getWidth() - 8, slider.getHeight() / 2f, 0); MotionEvent cancel = MotionEvent.obtain(now, now + 60, MotionEvent.ACTION_CANCEL, slider.getWidth() - 8, slider.getHeight() / 2f, 0); slider.onTouchEvent(down); slider.onTouchEvent(move); require(prefs.statusScale() == initial, "drag preview does not save"); slider.onTouchEvent(cancel); require(slider.getProgress() == initial && prefs.statusScale() == initial, "cancel restores preview and preference"); down.recycle(); move.recycle(); cancel.recycle();
                Bundle args = new Bundle(); args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 90); require(slider.performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args), "accessible slider accepts adjustment"); require(prefs.statusScale() == 90, "accessible adjustment commits without touch-up"); });
            click("status_enabled"); require(!prefs.statusEnabled() && tag("status_safe_left").isEnabled() && tag("status_safe_right").isEnabled(), "shared safe areas remain editable when overlay off");
            click("status-preset"); String preset = prefs.statusPreset(); back(); require(prefs.statusPreset().equals(preset), "single-choice cancel leaves state unchanged");
            main(() -> { tag("settings-scroll").requestFocus(); ((ScrollView) tag("settings-scroll")).scrollTo(0, tag("status-preview-group").getTop()); }); screenshot("status");
            back(); require("电量".contentEquals(((EditText) tag("settings-query")).getText()), "back restores query");
            MainActivity old = activity; main(() -> activity.recreate()); for (int i = 0; i < 40 && (activity == old || tag("settings-query") == null); i++) SystemClock.sleep(50); test.waitForIdleSync(); require(activity != old && "电量".contentEquals(((EditText) tag("settings-query")).getText()), "recreation restores route and search");
            open("panel"); stable = tag("settings-scroll"); int columns = prefs.panelColumns(); click("panel-option-columns"); back(); require(prefs.panelColumns() == columns, "column choice cancels without writes"); click("panel-option-columns"); click("settings-choice-5"); require(prefs.panelColumns() == 5 && tag("settings-scroll") == stable, "column choice updates without page rebuild"); click("panel-preview-toggle"); require(tag("panel-settings-preview") != null, "shared preview opens"); click("panel-option-volume"); require(!prefs.panelVolume() && tag("panel-settings-preview").findViewWithTag("control-volume") == null, "preview follows option in place"); screenshot("panel-preview"); click("panel-preview-toggle"); require(tag("panel-settings-preview") == null, "collapsed preview releases views"); screenshot("panel");
            click("panel-reset"); back(); require(prefs.panelColumns() == 5, "reset requires concrete confirmation"); click("panel-reset"); click("settings-confirm"); require(prefs.panelColumns() == 4, "confirmed reset applies"); click("panel-undo"); require(prefs.panelColumns() == 5, "reset remains undoable");
            open("dock"); List<String> originalOrder = prefs.scrollingActions(); label("按钮顺序"); main(() -> LinearLayoutAccess.moveDown(tag("order-list"))); require(prefs.scrollingActions().equals(originalOrder), "reorder remains a draft"); back(); click("settings-confirm"); require(prefs.scrollingActions().equals(originalOrder), "discard preserves original order"); label("按钮顺序"); main(() -> LinearLayoutAccess.moveDown(tag("order-list"))); click("order-done"); require(!prefs.scrollingActions().equals(originalOrder), "done saves changed order");
            open("orientations"); for (int i = 0; i < 80 && ((ListView) tag("settings-app-list")).getAdapter().getCount() <= 1; i++) SystemClock.sleep(50); test.waitForIdleSync();
            main(() -> { ((EditText) tag("settings-query")).setText("com"); ((ListView) tag("settings-app-list")).setSelection(1); }); SystemClock.sleep(100);
            int observers = CoverApp.catalog(test.getTargetContext()).observerCount(); main(() -> { ListView list = (ListView) tag("settings-app-list"); require(list.getChildCount() > 0, "catalog entries displayed"); list.getChildAt(0).performClick(); });
            require(CoverApp.catalog(test.getTargetContext()).observerCount() < observers, "leaving list releases catalog observer"); click("orientation-choice-1"); screenshot("orientation"); require(tag("orientation-choice-1").isSelected(), "visual selection retains page and selection"); back(); require(tag("settings-app-list") != null, "app rule returns to application list"); main(() -> ((ListView) tag("settings-app-list")).setSelection(0)); SystemClock.sleep(100); require("com".contentEquals(((EditText) tag("settings-query")).getText()), "offscreen list header retains search after returning");
            org.json.JSONObject rules = new org.json.JSONObject(); for (int i = 0; i < 200; i++) rules.put("test.settings.app" + i, 1);
            prefs.data.edit().putString("app_rotations", rules.toString()).commit();
            main(() -> activity.finish()); activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "app_orientation").putExtra("orientation_package", "test.settings.overflow")); test.waitForIdleSync();
            click("orientation-choice-1"); require(prefs.appRotation("test.settings.overflow") == -1 && tag("orientation-choice--1").isSelected() && !tag("orientation-choice-1").isSelected(), "rejected 201st direction rule does not show unsaved selection"); prefs.data.edit().remove("app_rotations").commit();
            open("group_backup"); File importFile = new File(test.getTargetContext().getCacheDir(), "settings-import-check.json");
            try (FileOutputStream output = new FileOutputStream(importFile)) { output.write(activity.exportConfigurationData().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            main(() -> activity.onActivityResult(202, Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(importFile)))); waitForConfirmation();
            old = activity; main(() -> activity.recreate()); for (int i = 0; i < 40 && activity == old; i++) SystemClock.sleep(50); waitForConfirmation(); require(tag("settings-confirm") != null, "import confirmation resumes after recreation"); Map<String, ?> beforeImport = data.getAll(); back(); require(beforeImport.equals(data.getAll()), "import cancellation preserves preferences");
            open("main"); main(() -> { activity.onActivityResult(202, Activity.RESULT_OK, new Intent().setData(android.net.Uri.fromFile(importFile))); tag("settings-link-group_appearance").performClick(); }); SystemClock.sleep(350); require(tag("settings-confirm") == null, "stale import read cannot replace a later page"); importFile.delete();
            widget = (NativeWidgetActivity) test.startActivitySync(new Intent(test.getTargetContext(), NativeWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync();
            main(() -> widget.onActivityResult(51, Activity.RESULT_CANCELED, null)); NativeWidgetActivity oldWidget = widget; main(() -> widget.recreate()); for (int i = 0; i < 40 && widget == oldWidget; i++) SystemClock.sleep(50); test.waitForIdleSync(); require(findText(widget.findViewById(android.R.id.content), "已取消本次授权，原布局保持不变") != null, "widget cancellation explanation survives recreation"); main(() -> widget.finish());
            open("gestures"); View entryScroll = tag("settings-scroll");
            SettingsIllustration entryPreview = (SettingsIllustration) ((ViewGroup) tag("gesture-preview")).getChildAt(0);
            require(tag("entry-position-2") == null && findText(root(), "180° · 固定左顶部") != null, "180 degree entry has no unsupported choices");
            click("entry-position-0"); require(tag("settings-choice-top_right") == null, "0 degrees cannot select top right"); back();
            String savedEntry = prefs.entryPosition(0); click("entry-position-0"); back(); require(prefs.entryPosition(0).equals(savedEntry), "canceling position picker preserves choice");
            click("entry-position-0"); click("settings-choice-top_left");
            click("entry-position-1"); require(tag("settings-choice-bottom_right") == null, "side rotation cannot select bottom entry"); click("settings-choice-top_right");
            click("entry-position-3"); click("settings-choice-top_left");
            require(tag("settings-scroll") == entryScroll && prefs.entryPosition(0).equals("top_left") && prefs.entryPosition(1).equals("top_right") && prefs.entryPosition(3).equals("top_left"), "entry choices update independently without rebuilding the page");
            require(entryPreview.getContentDescription().toString().contains("90°：右顶部") && entryPreview.getContentDescription().toString().contains("270°：左顶部"), "live preview announces selected corners");
            screenshot("gesture-positions-changed"); open("gestures"); require(prefs.entryPosition(1).equals("top_right"), "entry selection survives reopening settings");
            require(prefs.displayId() == -1 && !prefs.enabled(), "settings do not authorize or select main display");
            for (String page : new String[]{"group_dock", "group_appearance", "group_apps", "group_device", "group_backup", "gestures", "visibility", "appearance", "hand", "layout_backup", "hub", "favorites", "permissions", "display", "calibrate", "diagnostics", "about"}) { open(page); main(() -> checkText(root())); if (page.equals("permissions") || page.equals("gestures")) screenshot(page); }
            return "PASS: One UI settings; " + assertions + " assertions; real Activity, navigation, cancellation, accessibility slider, draft, preview and readable text; physical Samsung validation pending";
        } finally {
            if (activity != null) main(() -> activity.finish()); application.unregisterActivityLifecycleCallbacks(lifecycle);
            SharedPreferences.Editor restore = data.edit().clear(); for (Map.Entry<String, ?> e : original.entrySet()) { Object value = e.getValue(); if (value instanceof Boolean b) restore.putBoolean(e.getKey(), b); else if (value instanceof Integer n) restore.putInt(e.getKey(), n); else if (value instanceof Long n) restore.putLong(e.getKey(), n); else if (value instanceof Float n) restore.putFloat(e.getKey(), n); else if (value instanceof String s) restore.putString(e.getKey(), s); else if (value instanceof java.util.Set<?> values) { java.util.Set<String> strings = new java.util.HashSet<>(); for (Object item : values) strings.add((String) item); restore.putStringSet(e.getKey(), strings); } } restore.commit();
        }
    }
    private void waitForConfirmation() { for (int i = 0; i < 80 && tag("settings-confirm") == null; i++) SystemClock.sleep(50); test.waitForIdleSync(); require(tag("settings-confirm") != null, "import confirmation is visible"); }
    private void checkText(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView text && !(text instanceof EditText) && text.getLayout() != null && text.getLayout().getLineCount() > 0 && text.getWidth() > 0) { require(text.getLayout().getEllipsisCount(text.getLayout().getLineCount() - 1) == 0, "no truncated setting: " + text.getText()); require(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2, "text height fits: " + text.getText()); }
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) checkText(group.getChildAt(i));
    }
    private static final class LinearLayoutAccess {
        static void moveDown(View list) { ViewGroup group = (ViewGroup) list; ViewGroup row = (ViewGroup) group.getChildAt(0); row.getChildAt(1).performAccessibilityAction(R.id.order_move_down, null); }
    }
}
