package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Isolated wake lifecycle checks; never locks or changes a physical device. */
final class LockWakeChecks {
    private record Registration(BroadcastReceiver receiver, IntentFilter filter, String permission, int flags) { }
    private final Instrumentation test;
    private final List<Registration> registrations = new ArrayList<>();
    private CoverService owner;
    private int assertions;
    LockWakeChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private Object field(String name) throws Exception { Field field = CoverService.class.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private void call(String name) throws Exception { Method method = CoverService.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); }
    private void main(Runnable action) { test.runOnMainSync(action); }
    private void send(String action) { registrations.get(0).receiver.onReceive(test.getTargetContext(), new Intent(action)); }
    private int pending() { try { return (int) field("pendingLauncherDisplay"); } catch (Exception failure) { throw new AssertionError(failure); } }
    private Runnable recovery() { try { return (Runnable) field("restoreFromLauncherCard"); } catch (Exception failure) { throw new AssertionError(failure); } }
    String run() throws Exception {
        Context context = test.getTargetContext(); Prefs prefs = new Prefs(context); SharedPreferences data = prefs.data;
        boolean hadEnabled = data.contains("enabled"), hadDisplay = data.contains("display"); boolean enabled = prefs.enabled(); int display = prefs.displayId();
        CoverService original = CoverService.instance; String status = CoverService.status;
        ContextWrapper recording = new ContextWrapper(context) {
            @Override public Intent registerReceiver(BroadcastReceiver receiver, IntentFilter filter, String permission, Handler scheduler, int flags) { registrations.add(new Registration(receiver, filter, permission, flags)); return null; }
        };
        main(() -> { owner = new CoverService(); owner.prefs = prefs; CoverService.instance = owner; });
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(owner, recording);
        try {
            // An absent target exercises cancellation without mounting any window.
            data.edit().putBoolean("enabled", true).putInt("display", Integer.MAX_VALUE).commit();
            main(() -> { try { call("registerScreenEvents"); } catch (Exception failure) { throw new AssertionError(failure); } });
            require(registrations.size() == 2, "wake and Home receivers register independently");
            Registration wake = registrations.get(0), home = registrations.get(1);
            require(wake.permission == null && wake.flags == Context.RECEIVER_EXPORTED, "protected unlock broadcasts accept vendor senders without a Home permission");
            require(wake.filter.countActions() == 4 && wake.filter.hasAction(Intent.ACTION_SCREEN_ON) && wake.filter.hasAction(Intent.ACTION_SCREEN_OFF) && wake.filter.hasAction(Intent.ACTION_USER_PRESENT) && wake.filter.hasAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), "wake receiver accepts only four protected system actions");
            require(!wake.filter.hasAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS) && home.filter.countActions() == 1 && home.filter.hasAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), "Home cannot enter the wake receiver");
            require(CoverService.systemDialogsPermission(android.os.Build.VERSION.SDK_INT).equals(home.permission), "Home retains its platform sender permission");
            require(CoverService.systemDialogsPermission(30).equals("android.permission.STATUS_BAR"), "Android 11 uses a defined system permission rather than a claimable name");
            require(CoverService.systemDialogsPermission(31).equals("android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS") && CoverService.systemDialogsPermission(36).equals("android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS"), "Android 12 through 16 retain the dedicated permission");
            require((context.getPackageManager().getPermissionInfo("android.permission.STATUS_BAR", 0).protectionLevel & android.content.pm.PermissionInfo.PROTECTION_MASK_BASE) == android.content.pm.PermissionInfo.PROTECTION_SIGNATURE, "the older Home guard is signature protected");
            boolean rejected = false; try { context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT)); } catch (SecurityException expected) { rejected = true; }
            require(rejected, "Android rejects an application forging the protected unlock action");
            main(() -> send("io.github.flipcover.controls.UNRELATED")); require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "unknown actions cannot start recovery");
            main(() -> send(Intent.ACTION_SCREEN_ON)); require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "wake does not start a recovery polling loop");
            main(() -> send(Intent.ACTION_USER_PRESENT)); require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "unlock does not start a recovery polling loop");
            main(() -> { owner.launcherCardVisible(1); owner.launcherCardVisible(1); require(pending() == 1 && owner.main.hasCallbacks(recovery()), "card visibility queues a coalesced one-shot check"); });
            SystemClock.sleep(300); require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "a missing target consumes the check without retrying");
            require(owner.display == null, "missing target never falls back to primary display");
            SystemClock.sleep(1200); require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "idle time never repeats a card recovery check");
            main(() -> { owner.launcherCardVisible(0); require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "primary display cannot queue recovery"); });
            main(() -> { owner.launcherCardVisible(1); send(Intent.ACTION_SCREEN_OFF); require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "screen off immediately cancels a queued card check"); });
            SystemClock.sleep(650); require(pending() == -1 && CoverService.status.equals("息屏期间已隐藏"), "an old card callback cannot restore after screen off");
            main(() -> { owner.launcherCardVisible(1); data.edit().putBoolean("enabled", false).commit(); }); SystemClock.sleep(300);
            require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "a paused assistant consumes its pending check without mounting");
            data.edit().putBoolean("enabled", true).commit();
            main(() -> { owner.launcherCardVisible(1); CoverService.instance = original; }); SystemClock.sleep(300);
            require(pending() == -1 && !owner.main.hasCallbacks(recovery()), "a retired service consumes an old check without rescheduling");
            main(() -> { owner.launcherCardVisible(1); require(!owner.main.hasCallbacks(recovery()), "new card events cannot queue against a retired service"); CoverService.instance = owner; send(Intent.ACTION_USER_PRESENT); });
            require(owner.windowDiagnostics().contains(Intent.ACTION_USER_PRESENT), "diagnostics retain the latest unlock event");
            require(owner.windowDiagnostics().contains("最近应用中心恢复检查：尚未收到"), "rejected targets do not report a successful card check");
            return "PASS: " + assertions + " event-driven card recovery and screen receiver checks; no recovery polling; vendor physical unlock remains unverified";
        } finally {
            main(() -> { owner.main.removeCallbacksAndMessages(null); CoverService.instance = original; CoverService.status = status; });
            ((java.util.concurrent.ExecutorService) field("files")).shutdown();
            SharedPreferences.Editor edit = data.edit(); if (hadEnabled) edit.putBoolean("enabled", enabled); else edit.remove("enabled"); if (hadDisplay) edit.putInt("display", display); else edit.remove("display"); edit.commit();
        }
    }
}
