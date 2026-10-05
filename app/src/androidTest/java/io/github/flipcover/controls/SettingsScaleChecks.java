package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import java.io.File;
import java.io.FileOutputStream;

/** Settings zoom, transformed input and widget exclusion on a disposable emulator. */
final class SettingsScaleChecks {
    private final Instrumentation test;
    private MainActivity activity;
    private Prefs prefs;
    private int assertions;
    SettingsScaleChecks(Instrumentation test) { this.test = test; }
    private void require(boolean okay, String message) { assertions++; if (!okay) throw new AssertionError(message); }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private View tag(String name) { return root().findViewWithTag(name); }
    private void open(String page) { if (activity != null) main(() -> activity.finish()); activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", page)); test.waitForIdleSync(); SystemClock.sleep(180); }
    private void checkViewport(int percent) {
        main(() -> {
            SettingsUi.Viewport viewport = (SettingsUi.Viewport) tag("settings-viewport"); require(viewport != null, "settings viewport present"); View content = viewport.getChildAt(0); float expected = .7f * percent / 100f;
            require(Math.abs(content.getScaleX() - expected) < .0001f && Math.abs(content.getScaleY() - expected) < .0001f, "one uniform zoom at " + percent);
            require(Math.abs(content.getWidth() * expected - viewport.getWidth()) <= 1, "expanded logical width fills physical viewport");
            require(Math.abs(content.getHeight() * expected - viewport.getHeight()) <= 1, "expanded logical height fills physical viewport");
            View title = tag("settings-title"); Rect mapped = new InputNavigation.Target(title, "title", InputNavigation.Region.LIST, null, null).bounds(root());
            require(Math.abs(mapped.width() - title.getWidth() * expected) <= 2 && Math.abs(mapped.height() - title.getHeight() * expected) <= 2, "six-key bounds match scaled drawing");
            Rect physical = new Rect(); require(title.getGlobalVisibleRect(physical), "title visible"); int[] origin = new int[2]; root().getLocationOnScreen(origin); physical.offset(-origin[0], -origin[1]);
            require(Math.abs(mapped.left - physical.left) <= 1 && Math.abs(mapped.top - physical.top) <= 1, "focus bounds keep actual inset origin");
        });
    }
    private void capture(String name) throws Exception { Bitmap bitmap = test.getUiAutomation().takeScreenshot(); require(bitmap != null, "capture " + name); File dir = new File(test.getTargetContext().getFilesDir(), "settings-scale-raw"); if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("capture directory"); try (FileOutputStream stream = new FileOutputStream(new File(dir, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream); } finally { bitmap.recycle(); } }
    private void physicalTap(String name) {
        View view = tag(name); require(view != null, "tap target " + name); Rect bounds = new Rect(); main(() -> view.getGlobalVisibleRect(bounds)); long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, bounds.exactCenterX(), bounds.exactCenterY(), 0), up = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_UP, bounds.exactCenterX(), bounds.exactCenterY(), 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN); test.getUiAutomation().injectInputEvent(down, true); test.getUiAutomation().injectInputEvent(up, true); down.recycle(); up.recycle(); test.waitForIdleSync(); SystemClock.sleep(100);
    }
    private void progress(int percent) { main(() -> { Bundle args = new Bundle(); args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, percent); require(tag("settings_scale").performAccessibilityAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), args), "accessible zoom accepts " + percent); }); SystemClock.sleep(120); require(prefs.settingsScale() == percent, "zoom preference commits " + percent); checkViewport(percent); }
    String run() throws Exception {
        prefs = new Prefs(test.getTargetContext()); boolean hadScale = prefs.data.contains("settings_scale"); int original = prefs.settingsScale(); Activity widget = null;
        try {
            main(() -> prefs.data.edit().remove("settings_scale").commit()); require(prefs.settingsScale() == 100, "new zoom default 100");
            for (String page : new String[]{"main", "group_backup", "layout_backup", "about", "dock", "status", "orientations", "settings_scale"}) { open(page); checkViewport(100); if (page.equals("main") || page.equals("group_backup") || page.equals("about")) capture(page); }
            main(() -> { SettingsUi.Slider slider = (SettingsUi.Slider) tag("settings_scale"); long now = SystemClock.uptimeMillis(); MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, slider.getWidth() / 2f, slider.getHeight() / 2f, 0), move = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_MOVE, slider.getWidth() - 8, slider.getHeight() / 2f, 0), cancel = MotionEvent.obtain(now, now + 80, MotionEvent.ACTION_CANCEL, slider.getWidth() - 8, slider.getHeight() / 2f, 0); slider.dispatchTouchEvent(down); slider.dispatchTouchEvent(move); require(prefs.settingsScale() == 100, "drag does not resize under finger"); slider.dispatchTouchEvent(cancel); require(slider.getProgress() == 100 && prefs.settingsScale() == 100, "cancel restores zoom"); down.recycle(); move.recycle(); cancel.recycle(); });
            for (int percent : new int[]{70, 130, 100}) { progress(percent); capture("scale-" + percent); open("settings_scale"); checkViewport(percent); }
            progress(70); physicalTap("settings-scale-reset"); require(prefs.settingsScale() == 100, "scaled native hit resets 100"); checkViewport(100);
            open("group_appearance"); physicalTap("settings-link-settings_scale"); require(tag("settings_scale") != null, "category enters zoom route"); physicalTap("settings-back"); require(tag("settings-link-settings_scale") != null, "back returns to category");
            open("panel"); physicalTap("panel-option-columns"); main(() -> { View choice = tag("settings-choice-5"); require(choice != null, "settings dialog open"); SettingsUi.Viewport modal = InputNavigation.parent(choice, SettingsUi.Viewport.class); require(modal != null && Math.abs(modal.getChildAt(0).getScaleX() - .7f) < .0001f, "dialog scales once"); }); main(() -> activity.onBackPressed()); for (int i = 0; i < 50 && tag("settings-choice-5") != null; i++) SystemClock.sleep(30); test.waitForIdleSync(); require(tag("settings-choice-5") == null && tag("settings-viewport") != null, "dismiss removes only modal viewport");
            require(!prefs.layoutSnapshot().has("settings_scale"), "zoom remains local, outside runtime layout");
            for (int percent : new int[]{70, 130}) { main(() -> prefs.settingsScale(percent)); widget = test.startActivitySync(new Intent(test.getTargetContext(), NativeWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync(); Activity current = widget; main(() -> { require(current.findViewById(android.R.id.content).findViewWithTag("settings-viewport") == null, "widget editor excluded at " + percent); View scroll = current.findViewById(android.R.id.content).findViewWithTag("widget-scroll"); require(scroll != null && scroll.getScaleX() == 1f && ((View) scroll.getParent()).getScaleX() == 1f, "widget keeps existing 100% geometry"); }); capture("widget-" + percent); main(current::finish); widget = null; }
            return "PASS: settings-scale; " + assertions + " assertions on disposable emulator";
        } finally { if (widget != null) { Activity current = widget; main(current::finish); } if (activity != null) main(() -> activity.finish()); main(() -> { if (hadScale) prefs.settingsScale(original); else prefs.data.edit().remove("settings_scale").commit(); }); }
    }
}
