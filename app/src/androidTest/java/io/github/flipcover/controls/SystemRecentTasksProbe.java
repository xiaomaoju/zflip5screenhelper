package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import java.util.List;

/** app_process shell probe, emulator-only. Uses two owned disposable displays and one test fixture. */
public final class SystemRecentTasksProbe {
    private static int assertions;
    private static void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        if ((!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) || android.os.Process.myUid() != 2000) throw new SecurityException("Disposable emulator shell only");
        Looper.prepareMainLooper();
        Object thread = Class.forName("android.app.ActivityThread").getMethod("systemMain").invoke(null);
        Context system = (Context) thread.getClass().getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        Context owner = system.createPackageContext(BuildConfig.APPLICATION_ID, Context.CONTEXT_IGNORE_SECURITY);
        SystemRecentTasks tasks = new SystemRecentTasks(owner, owner.getApplicationInfo().uid);
        HandlerThread images = new HandlerThread("fixture-images"); images.start();
        ImageReader reader = ImageReader.newInstance(720, 748, PixelFormat.RGBA_8888, 2);
        reader.setOnImageAvailableListener(source -> { android.media.Image image = source.acquireLatestImage(); if (image != null) image.close(); }, new Handler(images.getLooper()));
        ImageReader second = ImageReader.newInstance(720, 748, PixelFormat.RGBA_8888, 2);
        second.setOnImageAvailableListener(source -> { android.media.Image image = source.acquireLatestImage(); if (image != null) image.close(); }, new Handler(images.getLooper()));
        DisplayManager displays = shell.getSystemService(DisplayManager.class);
        VirtualDisplay firstDisplay = null, secondDisplay = null;
        String fixture = BuildConfig.APPLICATION_ID + ".test/io.github.flipcover.controls.RecentTaskFixtureActivity";
        try {
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION | DisplayManager.class.getField("VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL").getInt(null);
            firstDisplay = displays.createVirtualDisplay("FlipCover task check A", 720, 748, 340, reader.getSurface(), flags);
            secondDisplay = displays.createVirtualDisplay("FlipCover task check B", 720, 748, 340, second.getSurface(), flags);
            int display = firstDisplay.getDisplay().getDisplayId(), other = secondDisplay.getDisplay().getDisplayId();
            require(display > 0 && other > 0 && display != other, "owned secondary displays created");
            launch(display, fixture); launch(display, fixture);
            List<RecentTasks.Task> first = tasks.read(display); require(first.size() == 2, "two windows of same application listed separately");
            RecentTasks.Task front = first.get(0), back = first.get(1);
            launch(other, fixture);
            RecentTasks.Task foreign = tasks.read(other).get(0);
            require(tasks.read(display).stream().noneMatch(task -> task.id() == foreign.id()), "other display excluded");
            org.json.JSONObject capabilities = tasks.execute(display, "", false);
            require(capabilities.getBoolean("canOpen") && capabilities.getBoolean("canClear"), "shell task operations detected");
            require(capabilities.getBoolean("canSnapshot"), "shell task snapshot contract detected");
            android.os.Bundle foreground = tasks.snapshot(display, SystemRecentTasks.json(front).toString());
            android.graphics.Bitmap foregroundBitmap = foreground.getParcelable("bitmap");
            require(foregroundBitmap != null && foregroundBitmap.getWidth() <= 256 && foregroundBitmap.getHeight() <= 256, "visible app gets an on-demand bounded system thumbnail: " + foreground.getString("message")); foregroundBitmap.recycle();
            require(tasks.open(display, SystemRecentTasks.json(back).toString()).getString("state").equals("opened"), "original background task restored on target display");
            List<RecentTasks.Task> restored = tasks.read(display);
            require(restored.size() == 2 && restored.get(0).id() == back.id() && restored.get(0).visible(), "restore does not duplicate task and confirms visibility");
            boolean rejected = false; try { tasks.open(display, SystemRecentTasks.json(foreign).toString()); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected, "cross-display request rejected");
            android.os.Bundle snapshot = tasks.snapshot(display, SystemRecentTasks.json(front).toString());
            android.graphics.Bitmap bitmap = snapshot.getParcelable("bitmap");
            require(bitmap == null || bitmap.getWidth() <= 256 && bitmap.getHeight() <= 256, "snapshot remains bounded or honestly absent");
            System.out.println("snapshot=" + (bitmap == null ? snapshot.getString("message") : bitmap.getWidth() + "x" + bitmap.getHeight())); if (bitmap != null) bitmap.recycle();
            for (int attempt = 0; attempt < 20 && tasks.read(display).stream().anyMatch(task -> task.id() == front.id() && task.visible()); attempt++) Thread.sleep(100);
            require(tasks.read(display).stream().anyMatch(task -> task.id() == front.id() && !task.visible()), "window transition settled before destructive test");
            org.json.JSONArray request = new org.json.JSONArray().put(SystemRecentTasks.json(front)).put(SystemRecentTasks.json(back));
            org.json.JSONObject cleared = tasks.execute(display, request.toString(), true);
            require(cleared.getInt("removed") == 1 && cleared.getInt("retained") == 1, "only background window removed; visible window retained: " + cleared);
            require(tasks.read(display).size() == 1 && tasks.read(display).get(0).id() == back.id(), "remaining window identity verified");
            require(tasks.read(other).get(0).id() == foreign.id(), "other display unaffected by clear");
            require(tasks.open(display, SystemRecentTasks.json(front).toString()).getString("state").equals("gone"), "removed task reports explicit recovery state");
            org.json.JSONObject explicit = tasks.execute(display, new org.json.JSONArray().put(SystemRecentTasks.json(back)).toString(), true, true);
            require(explicit.getInt("removed") == 1 && explicit.getInt("retained") == 0 && tasks.read(display).isEmpty(), "explicit dismissal removes the foreground task on the selected display");
            require(tasks.read(other).stream().anyMatch(task -> RecentTasks.sameTask(foreign, task)), "explicit foreground dismissal preserves a sibling task on another display");
            launchSecure(display, fixture);
            RecentTasks.Task secure = tasks.read(display).get(0);
            android.os.Bundle protectedSnapshot = tasks.snapshot(display, SystemRecentTasks.json(secure).toString());
            require(protectedSnapshot.getParcelable("bitmap") == null && !protectedSnapshot.getBoolean("retryable"), "secure visible task remains an honest non-retrying placeholder");
            rejected = false; try { tasks.read(0); } catch (IllegalArgumentException expected) { rejected = true; } require(rejected, "main display rejected");
            System.out.println("PASS: " + assertions + " real Android shell/task assertions; two disposable virtual displays, not Samsung or Shizuku transport validation");
        } finally { if (firstDisplay != null) firstDisplay.release(); if (secondDisplay != null) secondDisplay.release(); reader.setOnImageAvailableListener(null, null); second.setOnImageAvailableListener(null, null); images.quitSafely(); images.join(1000); reader.close(); second.close(); }
        System.exit(0);
    }
    private static void launch(int display, String fixture) throws Exception {
        // Fixed emulator fixture only; no user-supplied executable or shell text.
        Process process = new ProcessBuilder("/system/bin/am", "start", "-W", "--display", String.valueOf(display), "-f", "0x18000000", "-n", fixture).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.waitFor() == 0 && output.contains("Status: ok"), "fixture launched on owned display: " + output); Thread.sleep(250);
    }
    private static void launchSecure(int display, String fixture) throws Exception {
        Process process = new ProcessBuilder("/system/bin/am", "start", "-W", "--display", String.valueOf(display), "-f", "0x18000000", "-n", fixture, "--ez", "secure", "true").redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        require(process.waitFor() == 0 && output.contains("Status: ok"), "secure fixture launched on owned display"); Thread.sleep(300);
    }
}
