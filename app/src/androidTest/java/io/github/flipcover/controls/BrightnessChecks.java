package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Display;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import android.view.Surface;

/** Owned secondary displays and fake privileged replies; never changes physical brightness. */
final class BrightnessChecks {
    private final Instrumentation instrumentation;
    private CoverService owner;
    private Panels panels;
    private LevelSlider slider;
    private int assertions;
    private BrightnessChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    static String run(Instrumentation instrumentation) throws Exception { return new BrightnessChecks(instrumentation).run(); }
    private String run() throws Exception {
        Context context = instrumentation.getTargetContext();
        Prefs prefs = new Prefs(context);
        Map<String, ?> saved = prefs.data.getAll();
        CoverService originalOwner = CoverService.instance;
        ShizukuBridge bridge = CoverApp.bridge(context);
        Object originalRemote = field(ShizukuBridge.class, "remote").get(bridge);
        AtomicBoolean busy = (AtomicBoolean) field(ShizukuBridge.class, "busy").get(bridge);
        require(!bridge.busy(), "shared bridge starts idle");
        FakeService fake = new FakeService();
        OwnedDisplay first = new OwnedDisplay(), second = new OwnedDisplay();
        try {
            first.create(context, "Brightness checks A"); second.create(context, "Brightness checks B");
            require(first.display().getDisplayId() > 0 && second.display().getDisplayId() > 0, "owned secondary displays exist");
            waitFor(() -> first.display().getState() == Display.STATE_ON && second.display().getState() == Display.STATE_ON);
            prefs.data.edit().putInt("display", first.display().getDisplayId()).putBoolean("enabled", true).putBoolean("panel_brightness", true).commit();
            field(ShizukuBridge.class, "remote").set(bridge, fake);
            main(() -> {
                owner = new CoverService();
                Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(owner, context);
                owner.prefs = prefs; owner.screenContext = context; owner.display = first.display(); CoverService.instance = owner;
                DockGeometry.Box box = new DockGeometry.Box(0, 0, 400, 400);
                owner.placement = new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, false);
                openPanel();
                panels.brightness(new ShizukuBridge.Result(true, "read", ".4")); checkValue(40, "valid read enables slider");
                slider.setValue(85); panels.brightnessWritten(new ShizukuBridge.Result(false, "busy", "", true)); checkValue(40, "busy write restores confirmed value");
                panels.brightness(new ShizukuBridge.Result(false, "busy", "", true)); checkValue(40, "busy read preserves availability");
                panels.brightness(new ShizukuBridge.Result(true, "invalid", "NaN")); require(!slider.isEnabled(), "NaN read disables slider");
                panels.brightness(new ShizukuBridge.Result(true, "reconnected", ".4")); checkValue(40, "valid read recovers availability");
                slider.setValue(80); panels.brightnessWritten(new ShizukuBridge.Result(false, "system limited value", ".55")); checkValue(55, "unconfirmed write displays actual system readback");
                panels.brightness(new ShizukuBridge.Result(false, "disconnected", "")); require(!slider.isEnabled(), "fatal read disables slider");
                panels.brightnessWritten(new ShizukuBridge.Result(false, "late failed write", "")); require(!slider.isEnabled(), "late failure cannot restore stale confirmation");
                panels.brightness(new ShizukuBridge.Result(true, "reconnected", ".4"));
            });
            // A shared service operation delays writes. Refresh must not replace the latest release.
            busy.set(true);
            main(() -> { releaseValue(20); releaseValue(65); releaseValue(80); read(); });
            require(fake.values().isEmpty(), "busy bridge receives no early writes");
            busy.set(false); idleAfterWrites(fake, busy, 1);
            require(fake.values().equals(List.of(80)), "waiting writes coalesce and read cannot displace them");
            main(() -> checkValue(80, "latest successful reply updates UI"));
            // Observe the interval after the old callback, while the latest write is still blocked.
            Reply old = fake.block(), latest = fake.block();
            main(() -> releaseValue(30)); await(old.entered, "old write entered");
            main(() -> { releaseValue(70); read(); }); old.release.countDown(); await(latest.entered, "latest write entered");
            main(() -> checkValue(70, "old callback cannot replace pending user value"));
            latest.release.countDown(); idleAfterWrites(fake, busy, 3);
            require(fake.values().equals(List.of(80, 30, 70)), "latest release survives active write and refresh");
            main(() -> checkValue(70, "latest callback wins"));
            // Timeout is recoverable, does not queue a late surprise write, and allows the next release.
            busy.set(true); main(() -> releaseValue(45));
            waitPendingEmpty();
            main(() -> checkValue(70, "waiting timeout restores confirmed value without disabling"));
            busy.set(false); instrumentation.waitForIdleSync(); require(fake.values().size() == 3, "timed out request does not execute later");
            main(() -> releaseValue(60)); idleAfterWrites(fake, busy, 4); main(() -> checkValue(60, "new release succeeds after timeout"));
            // A selected-display change discards queued work and ignores a late in-flight callback.
            busy.set(true); main(() -> { releaseValue(35); select(second.display()); }); busy.set(false);
            waitPendingEmpty(); require(fake.values().size() == 4, "display change discards waiting write");
            main(() -> select(first.display())); Reply changedDisplay = fake.block();
            main(() -> releaseValue(25)); await(changedDisplay.entered, "display-change write entered");
            main(() -> { select(second.display()); panels.brightness(new ShizukuBridge.Result(true, "new display", ".9")); });
            changedDisplay.release.countDown(); idleAfterWrites(fake, busy, 5);
            main(() -> checkValue(90, "late callback cannot alter new display value"));
            // Closing discards pending work; a fresh panel also ignores the old panel callback.
            busy.set(true); main(() -> { releaseValue(45); owner.closePanel(); }); busy.set(false);
            waitPendingEmpty(); require(fake.values().size() == 5, "closed panel cancels waiting write");
            main(() -> { openPanel(); panels.brightness(new ShizukuBridge.Result(true, "read", ".5")); });
            Reply closedPanel = fake.block(); main(() -> releaseValue(20)); await(closedPanel.entered, "closing write entered");
            Panels oldPanel = panels;
            main(() -> { owner.closePanel(); openPanel(); panels.brightness(new ShizukuBridge.Result(true, "new panel", ".85")); });
            closedPanel.release.countDown(); idleAfterWrites(fake, busy, 6);
            main(() -> checkValue(85, "late callback cannot alter fresh panel"));
            // Invalid old-target calls must not increment the generation of a valid request.
            Reply valid = fake.block(); main(() -> releaseValue(75)); await(valid.entered, "valid write entered");
            main(() -> owner.setBrightness(oldPanel, 30)); valid.release.countDown(); idleAfterWrites(fake, busy, 7);
            main(() -> checkValue(75, "invalid old target cannot suppress current callback"));
            require(fake.operations().stream().allMatch(value -> value.equals("brightness")), "no refresh displaced pending or active writes");
            // Permission can remain granted after the UserService disappears. Drop waiting writes,
            // then recover only from a fresh real read after that connection returns.
            busy.set(true);
            main(() -> {
                releaseValue(35); field(ShizukuBridge.class, "remote").set(bridge, null); connection(true, false);
                require(!slider.isEnabled(), "UserService disconnect disables slider even with permission granted");
                require(field(CoverService.class, "pendingBrightness").get(owner) == null, "UserService disconnect cancels waiting release");
            });
            busy.set(false);
            main(() -> { field(ShizukuBridge.class, "remote").set(bridge, fake); connection(true, true); });
            waitValue(42);
            require(fake.values().size() == 7, "reconnection never replays discarded waiting release");
            require(fake.operations().contains("states") && fake.operations().contains("brightness_read"), "reconnection refreshes states and reads brightness");
            // Reconnect while the old service call is still running. Its reply is stale; a fresh
            // read must wait for the shared bridge, then be the only reply to enable the slider.
            Reply disconnectedWrite = fake.block(), freshRead = fake.blockRead();
            main(() -> releaseValue(25)); await(disconnectedWrite.entered, "disconnect write entered");
            main(() -> {
                releaseValue(65); field(ShizukuBridge.class, "remote").set(bridge, null); connection(true, false);
                require(!slider.isEnabled(), "disconnect invalidates active write confirmation");
                field(ShizukuBridge.class, "remote").set(bridge, fake); connection(true, true);
                require(!slider.isEnabled(), "reconnection waits for fresh confirmation");
            });
            disconnectedWrite.release.countDown(); await(freshRead.entered, "fresh read survives old active callback");
            main(() -> require(!slider.isEnabled(), "old service callback cannot re-enable slider before fresh read"));
            freshRead.release.countDown(); waitValue(42); idleAfterWrites(fake, busy, 8);
            require(fake.values().equals(List.of(80, 30, 70, 60, 25, 20, 75, 25)), "disconnect cancels old pending value without replay");
            main(() -> { owner.display = context.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY); owner.setBrightness(panels, 60); });
            require(fake.values().size() == 8, "primary display is rejected");
            return "PASS: brightness recovery and bounded request flow; " + assertions + " assertions; fake replies, not Samsung brightness validation";
        } finally {
            // Cancel every producer before releasing the fake replies. Always restore global fixtures,
            // even if an assertion failed while this test deliberately held the busy flag.
            try {
                if (owner != null) main(() -> { owner.closePanel(); owner.main.removeCallbacksAndMessages(null); });
                fake.unblockAll();
                ExecutorService executor = (ExecutorService) field(ShizukuBridge.class, "executor").get(bridge);
                executor.submit(() -> { }).get(5, TimeUnit.SECONDS);
                instrumentation.waitForIdleSync();
            } finally {
                field(ShizukuBridge.class, "remote").set(bridge, originalRemote); busy.set(false); CoverService.instance = originalOwner;
                SharedPreferences.Editor edit = prefs.data.edit();
                for (String key : new String[]{"display", "enabled", "panel_brightness"}) {
                    if (!saved.containsKey(key)) edit.remove(key);
                    else if (saved.get(key) instanceof Boolean value) edit.putBoolean(key, value);
                    else edit.putInt(key, (Integer) saved.get(key));
                }
                edit.commit(); second.close(); first.close();
            }
        }
    }
    private void openPanel() throws Exception {
        owner.buildPanelContent("controls", new DockGeometry.Box(0, 0, 400, 400), 0);
        panels = (Panels) field(CoverService.class, "panels").get(owner);
        slider = (LevelSlider) field(Panels.class, "brightness").get(panels);
        require(slider != null && panels.hasBrightness(), "real control builder contains brightness slider");
    }
    private void select(Display display) { owner.display = display; owner.prefs.data.edit().putInt("display", display.getDisplayId()).commit(); }
    private void releaseValue(int value) { slider.setValue(value); owner.setBrightness(panels, value); }
    private void read() throws Exception { Method method = CoverService.class.getDeclaredMethod("requestBrightness", Panels.class, int.class); method.setAccessible(true); method.invoke(owner, panels, -1); }
    private void connection(boolean granted, boolean connected) throws Exception { Method method = CoverService.class.getDeclaredMethod("handleBridgeChanged", boolean.class, boolean.class); method.setAccessible(true); method.invoke(owner, granted, connected); }
    private void waitValue(int value) throws Exception {
        waitFor(() -> { boolean[] matched = {false}; try { main(() -> matched[0] = slider.isEnabled() && slider.getContentDescription().toString().contains(value + "%")); } catch (Exception error) { throw new AssertionError(error); } return matched[0]; });
        main(() -> checkValue(value, "fresh read restores availability"));
    }
    private void checkValue(int value, String message) { require(slider.isEnabled() && slider.getContentDescription().toString().contains(value + "%"), message); }
    private void idleAfterWrites(FakeService fake, AtomicBoolean busy, int count) {
        waitFor(() -> {
            boolean[] completed = {false};
            try { main(() -> completed[0] = fake.values().size() == count && !busy.get() && field(CoverService.class, "activeBrightness").get(owner) == null); }
            catch (Exception error) { throw new AssertionError(error); }
            return completed[0];
        });
    }
    private void waitPendingEmpty() throws Exception {
        // Reading the field on the UI thread gives a happens-before relation with its Handler.
        waitFor(() -> { boolean[] empty = {false}; try { main(() -> empty[0] = field(CoverService.class, "pendingBrightness").get(owner) == null); } catch (Exception error) { throw new AssertionError(error); } return empty[0]; });
    }
    private static Field field(Class<?> owner, String name) throws Exception { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
    private interface Action { void run() throws Exception; }
    private void main(Action action) throws Exception {
        Throwable[] error = {null}; instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } });
        if (error[0] != null) throw new AssertionError(error[0]);
    }
    private interface Condition { boolean met(); }
    private static void waitFor(Condition condition) { long until = SystemClock.uptimeMillis() + 4500; while (!condition.met() && SystemClock.uptimeMillis() < until) SystemClock.sleep(20); if (!condition.met()) throw new AssertionError("Timed out waiting for fake brightness flow"); }
    private void await(CountDownLatch latch, String message) throws Exception { require(latch.await(2, TimeUnit.SECONDS), message); }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private static final class OwnedDisplay {
        SurfaceTexture texture;
        Surface surface;
        VirtualDisplay virtual;
        void create(Context context, String name) {
            texture = new SurfaceTexture(false); texture.setDefaultBufferSize(400, 400); surface = new Surface(texture);
            virtual = context.getSystemService(DisplayManager.class).createVirtualDisplay(name, 400, 400, 160, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            if (virtual == null) throw new AssertionError("Owned display unavailable");
        }
        Display display() { return virtual.getDisplay(); }
        void close() { if (virtual != null) virtual.release(); if (surface != null) surface.release(); if (texture != null) texture.release(); }
    }
    private static final class Reply { final String operation; final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); Reply(String operation) { this.operation = operation; } }
    private static final class FakeService extends IShellService.Stub {
        @Override public void watchConnectivity(IConnectivityListener listener) { }
        private final List<Integer> writes = new ArrayList<>();
        private final List<String> calls = new ArrayList<>();
        private final List<Reply> allReplies = new ArrayList<>();
        private final LinkedBlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
        synchronized Reply block() { return block("brightness"); }
        synchronized Reply blockRead() { return block("brightness_read"); }
        private Reply block(String operation) { Reply reply = new Reply(operation); allReplies.add(reply); replies.add(reply); return reply; }
        synchronized void unblockAll() { for (Reply reply : allReplies) reply.release.countDown(); }
        synchronized List<Integer> values() { return List.copyOf(writes); }
        synchronized List<String> operations() { return List.copyOf(calls); }
        @Override public String execute(String operation, int display, int value, String component) {
            synchronized (this) { calls.add(operation); if (operation.equals("brightness")) writes.add(value); }
            if (operation.equals("states")) return "{\"ok\":true,\"message\":\"fake states\",\"output\":\"{}\"}";
            if ((!operation.equals("brightness") && !operation.equals("brightness_read")) || display <= 0) return "{\"ok\":false,\"message\":\"unexpected fake operation\"}";
            Reply next = replies.peek(); Reply reply = next != null && next.operation.equals(operation) ? replies.poll() : null;
            if (reply != null) {
                reply.entered.countDown();
                try { if (!reply.release.await(4, TimeUnit.SECONDS)) return "{\"ok\":false,\"message\":\"blocked fake timed out\"}"; }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); return "{\"ok\":false,\"message\":\"fake interrupted\"}"; }
            }
            return "{\"ok\":true,\"message\":\"fake brightness\",\"output\":\"" + (operation.equals("brightness_read") ? .42f : value / 100f) + "\"}";
        }
        @Override public Bundle taskSnapshot(int display, String task) { return new Bundle(); }
        @Override public void destroy() { }
    }
}
