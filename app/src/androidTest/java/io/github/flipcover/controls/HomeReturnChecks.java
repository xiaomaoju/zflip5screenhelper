package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises queued Home cancellation with a fake service, never a privileged command. */
final class HomeReturnChecks {
    private final Instrumentation test;
    private int assertions;
    HomeReturnChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    String run() throws Exception {
        settings();
        test.waitForIdleSync();
        ShizukuBridge bridge = CoverApp.bridge(test.getTargetContext());
        Field remote = ShizukuBridge.class.getDeclaredField("remote"), worker = ShizukuBridge.class.getDeclaredField("executor"); remote.setAccessible(true); worker.setAccessible(true);
        Object original = remote.get(bridge); ExecutorService executor = (ExecutorService) worker.get(bridge);
        CountDownLatch waiting = new CountDownLatch(1), release = new CountDownLatch(1), cancelled = new CountDownLatch(1), completed = new CountDownLatch(1);
        AtomicBoolean active = new AtomicBoolean(true), callbackOnMain = new AtomicBoolean(); AtomicInteger calls = new AtomicInteger();
        AtomicReference<ShizukuBridge.Result> result = new AtomicReference<>(), duplicate = new AtomicReference<>();
        IShellService fake = new IShellService.Stub() {
            public void watchConnectivity(IConnectivityListener listener) { }
            public String execute(String operation, int display, int value, String item) {
                if (!operation.equals("native_home") || display != 7 || value != 0 || !item.isEmpty()) throw new AssertionError("fixed Home request retained");
                calls.incrementAndGet(); return "{\"ok\":true,\"message\":\"accepted\",\"output\":\"\"}";
            }
            public Bundle taskSnapshot(int display, String item) { return null; }
            public void destroy() { }
        };
        try {
            remote.set(bridge, fake);
            executor.execute(() -> { waiting.countDown(); try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } });
            require(waiting.await(3, TimeUnit.SECONDS), "executor held before Home submission");
            test.runOnMainSync(() -> {
                bridge.run("native_home", 7, 0, "", active::get, value -> { callbackOnMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()); result.set(value); cancelled.countDown(); });
                bridge.run("native_home", 7, 0, "", active::get, duplicate::set);
            });
            require(duplicate.get() != null && duplicate.get().retryable && !duplicate.get().ok, "repeated Home cannot enqueue another request while busy");
            active.set(false); release.countDown();
            require(cancelled.await(3, TimeUnit.SECONDS), "cancelled request delivers its result");
            require(calls.get() == 0 && !result.get().ok, "a target invalidated while queued never reaches the service");
            require(callbackOnMain.get(), "cancelled result stays on the main callback thread");
            active.set(true);
            test.runOnMainSync(() -> bridge.run("native_home", 7, 0, "", active::get, value -> { result.set(value); completed.countDown(); }));
            require(completed.await(3, TimeUnit.SECONDS), "a fresh request can execute after cancellation");
            require(calls.get() == 1 && result.get().ok, "valid request executes exactly once and releases the busy guard");
        } finally { release.countDown(); remote.set(bridge, original); }
        return "PASS " + assertions + " Home request checks";
    }
    private void settings() throws Exception {
        Prefs prefs = new Prefs(test.getTargetContext()); String original = prefs.data.getString("home_action", null);
        prefs.data.edit().remove("home_action").commit();
        MainActivity activity = (MainActivity) test.startActivitySync(new android.content.Intent(test.getTargetContext(), MainActivity.class).putExtra("section", "dock").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.waitForIdleSync(); android.view.View root = activity.getWindow().getDecorView();
            android.view.View row = root.findViewWithTag("dock-home-action"), scroll = root.findViewWithTag("settings-scroll");
            require(row != null && prefs.homeAction().equals("cards"), "Home settings defaults to the validated native-card behavior");
            test.runOnMainSync(() -> row.requestRectangleOnScreen(new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight()), true)); android.os.SystemClock.sleep(350); test.waitForIdleSync();
            test.runOnMainSync(() -> labels(row)); screenshot(activity, "settings");
            test.runOnMainSync(row::performClick); android.os.SystemClock.sleep(350); test.waitForIdleSync();
            require(root.findViewWithTag("settings-choice-clock") != null && root.findViewWithTag("settings-choice-cards") != null, "both Home behaviors appear as radio choices");
            screenshot(activity, "choices"); test.runOnMainSync(() -> { labels(root.findViewWithTag("settings-choice-clock")); labels(root.findViewWithTag("settings-choice-cards")); });
            test.runOnMainSync(activity::onBackPressed); android.os.SystemClock.sleep(300); test.waitForIdleSync();
            require(prefs.homeAction().equals("cards"), "cancelling the picker does not save");
            for (String mode : new String[]{"clock", "cards"}) {
                test.runOnMainSync(row::performClick); test.waitForIdleSync();
                test.runOnMainSync(() -> root.findViewWithTag("settings-choice-" + mode).performClick()); android.os.SystemClock.sleep(300); test.waitForIdleSync();
                require(prefs.homeAction().equals(mode) && root.findViewWithTag("settings-scroll") == scroll, "selection saves " + mode + " without rebuilding the page");
            }
        } finally {
            test.runOnMainSync(activity::finish); test.waitForIdleSync();
            if (original == null) prefs.data.edit().remove("home_action").commit(); else prefs.data.edit().putString("home_action", original).commit();
        }
    }
    private void labels(android.view.View view) {
        if (view instanceof android.widget.TextView text && text.getText().length() > 0) {
            require(text.getLayout() != null, "Home option text is laid out: " + text.getText() + " " + text.getWidth() + "x" + text.getHeight() + " shown=" + text.isShown());
            for (int line = 0; line < text.getLayout().getLineCount(); line++) require(text.getLayout().getEllipsisCount(line) == 0, "Home option text is not truncated");
        }
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) labels(group.getChildAt(i));
    }
    private void screenshot(MainActivity activity, String name) throws Exception {
        android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
        java.io.File directory = new java.io.File(activity.getFilesDir(), "home-settings-raw"); directory.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { image.recycle(); }
    }
}
