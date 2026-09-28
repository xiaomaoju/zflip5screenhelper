package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Pixel checks of a real ViewRoot/WindowManager surface, without overlay permission. */
final class HubWindowChecks {
    private static final int BACKGROUND = 0xFF10325A;
    private final Instrumentation test;
    private Activity activity;
    private AppHubView hub;
    private WindowManager windows;
    private FrameLayout host;
    private CoverService owner;
    private Bitmap reference;
    private final Rect area = new Rect();
    private int assertions;
    HubWindowChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void main(Runnable action) { Throwable[] failure = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new AssertionError(failure[0]); test.waitForIdleSync(); }
    private Object field(Object owner, String name) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void field(Object owner, String name, Object value) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private ValueAnimator motion() { return (ValueAnimator) field(hub, "revealAnimation"); }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Prefs prefs = new Prefs(activity); prefs.data.edit().clear().commit();
        // Previous launch-failure fixtures leave a system Toast above every window for several seconds.
        // Wait for that unrelated surface before judging the overlay's actual pixel footprint.
        SystemClock.sleep(4000); test.waitForIdleSync();
        try {
            main(() -> { FrameLayout base = new FrameLayout(activity); base.setBackgroundColor(BACKGROUND); activity.setContentView(base); });
            SystemClock.sleep(160); test.waitForIdleSync(); reference = test.getUiAutomation().takeScreenshot();
            require(reference != null, "capture actual background including system navigation insets");
            main(() -> {
                hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } }); hub.setExpanded(false);
                windows = activity.getWindowManager();
                owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs; field(owner, "hub", hub); field(owner, "windows", windows); host = owner.attachHubContent(hub);
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(Math.min(Ui.dp(activity, 220), activity.getResources().getDisplayMetrics().widthPixels - 32), Ui.dp(activity, 160), WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
                params.token = activity.getWindow().getDecorView().getWindowToken(); params.gravity = Gravity.TOP | Gravity.LEFT; params.x = 16; params.y = Ui.dp(activity, 65); params.setFitInsetsTypes(0); params.windowAnimations = 0; windows.addView(host, params);
            });
            SystemClock.sleep(300); test.waitForIdleSync();
            main(() -> {
                require(hub.isAttachedToWindow() && hub.getParent() == host && !(host.getParent() instanceof View), "production host is an actual WindowManager root, not an Activity child");
                int[] position = new int[2]; hub.getLocationOnScreen(position); area.set(position[0], position[1], position[0] + hub.getWidth(), position[1] + hub.getHeight());
            });
            Footprint visible = frame("window-visible"); require(visible.pixels > 100, "opaque Dock is visible in the real window screenshot");
            main(hub::prepareEntrance);
            Footprint hidden = frame("window-prepared"); require(hidden.pixels < visible.pixels / 20, "prepared entry actually hides the rendered root surface: visible=" + visible.pixels + ", hidden=" + hidden.pixels);
            main(() -> { hub.reopen(); if (motion() != null) motion().end(); });
            Footprint settled = frame("window-settled"); require(Math.abs(settled.top - visible.top) <= 1, "entry restores original rendered Dock position");
            main(() -> {
                owner.act("app_dock"); require(hub.isAttachedToWindow() && field(owner, "hub") == hub && motion() != null, "Dock click starts the common exit and retains the actual window"); motion().pause(); motion().setCurrentPlayTime(90);
            });
            Footprint exiting = frame("window-exiting"); require(exiting.pixels > 100 && exiting.top > visible.top + Ui.dp(activity, 8), "actual Dock pixels slide down before window removal");
            main(() -> {
                for (String entry : new String[]{"app_dock", "app_hub", "recents"}) {
                    field(owner, "panel", new android.widget.LinearLayout(activity)); field(owner, "panelPage", "controls");
                    owner.act(entry); require(field(owner, "hub") == hub && !hub.closing() && field(owner, "panel") == null && "".equals(field(owner, "panelPage")), entry + " reopening clears the newly opened panel and reuses Hub");
                    if (motion() != null) motion().end(); hub.showTasks(false); hub.setExpanded(false); owner.act("app_dock"); motion().pause(); motion().setCurrentPlayTime(90);
                }
                field(owner, "panel", new android.widget.LinearLayout(activity)); field(owner, "panelPage", "controls"); owner.act("back");
                require(field(owner, "panel") == null && "".equals(field(owner, "panelPage")) && hub.closing(), "Back closes the current panel instead of operating the exiting Hub");
            });
            main(() -> { motion().end(); require(field(owner, "hub") == null && !hub.isAttachedToWindow(), "common completion removes the real window only after the slide"); });
            require(frame("window-removed").pixels < visible.pixels / 20, "removed window leaves no Dock pixels");
            return "PASS: hub-window; " + assertions + " assertions; real application-panel WindowManager surface and pixels, not Samsung accessibility-overlay routing";
        } finally {
            main(() -> { if (hub != null) hub.dispose(); if (host != null && host.isAttachedToWindow()) windows.removeViewImmediate(host); activity.finish(); });
            if (reference != null) reference.recycle();
        }
    }
    private record Footprint(int pixels, int top) { }
    private Footprint frame(String name) throws Exception {
        SystemClock.sleep(160); test.waitForIdleSync(); Bitmap image = test.getUiAutomation().takeScreenshot(); int count = 0, top = area.bottom;
        try {
            for (int y = area.top; y < Math.min(area.bottom, image.getHeight()); y++) for (int x = area.left; x < Math.min(area.right, image.getWidth()); x++) {
                int pixel = image.getPixel(x, y), background = reference.getPixel(x, y); int difference = Math.abs(android.graphics.Color.red(pixel) - android.graphics.Color.red(background)) + Math.abs(android.graphics.Color.green(pixel) - android.graphics.Color.green(background)) + Math.abs(android.graphics.Color.blue(pixel) - android.graphics.Color.blue(background));
                if (difference > 12) { count++; top = Math.min(top, y); }
            }
            File directory = new File(test.getTargetContext().getFilesDir(), "ui-smoke"); directory.mkdirs(); try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, out); }
            return new Footprint(count, top);
        } finally { image.recycle(); }
    }
}
