package io.github.flipcover.controls;

import android.os.Binder;
import android.os.IBinder;

/** Whole-device SystemUI restriction, owned only by the live Shizuku UserService. */
final class SystemControlCenter {
    private final IBinder token = new Binder();

    void setEnabled(boolean enabled) throws Exception {
        apply(enabled, this::writeFlags);
    }
    interface FlagWriter { void write(int flags) throws Exception; }
    static void apply(boolean enabled, FlagWriter writer) throws Exception {
        if (enabled) { writer.write(0); return; }
        // Samsung clears its cover gesture gate on fold changes without clearing the
        // server flags. Pulse SHADE as well so SystemUI receives a fresh disable callback.
        // QS stays disabled throughout; never briefly enable it to force a refresh.
        try { writer.write(1 | 4); } finally { writer.write(1); }
    }
    private void writeFlags(int flags) throws Exception {
        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "statusbar");
        if (binder == null) throw new IllegalStateException("Status bar service unavailable");
        Object service = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub").getMethod("asInterface", IBinder.class).invoke(null, binder);
        // A dedicated token preserves restrictions owned by ADB and other applications.
        // Binder death releases only this tool's restriction when Shizuku exits.
        Class.forName("com.android.internal.statusbar.IStatusBarService").getMethod("disable2", int.class, IBinder.class, String.class)
            .invoke(service, flags, token, BuildConfig.APPLICATION_ID);
    }

    /** Reads the shared policy on display 0, not the potentially stale cover/DeX copy. */
    static int state(String dump) {
        if (dump == null) return -1;
        boolean primary = false; Long first = null, second = null;
        for (String raw : dump.split("\\R", 256)) {
            String line = raw.trim();
            if (line.equals("displayId=0")) { if (primary) return -1; primary = true; continue; }
            if (!primary) continue;
            if (line.startsWith("mDisabled1=")) { if (first != null) return -1; first = flags(line.substring(11)); if (first == null) return -1; }
            else if (line.startsWith("mDisabled2=")) { if (second != null) return -1; second = flags(line.substring(11)); if (second == null) return -1; }
            else break;
        }
        if (first == null || second == null) return -1;
        return (first & 0x10000) != 0 || (second & 0x5) != 0 ? 0 : 1;
    }
    private static Long flags(String text) {
        if (!text.matches("0[xX][0-9a-fA-F]{1,8}")) return null;
        return Long.parseLong(text.substring(2), 16);
    }
}
