package io.github.flipcover.controls;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.LocationManager;

/** Location master switch only: never requests coordinates or location permissions. */
final class SystemLocation implements AutoCloseable {
    private final Context context;
    private boolean registered;
    private final BroadcastReceiver receiver;
    SystemLocation(Context context, Runnable changed) {
        this.context = context;
        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (registered && LocationManager.MODE_CHANGED_ACTION.equals(intent.getAction())) changed.run();
            }
        };
        // Protected system broadcast; read the service instead of trusting extras.
        context.registerReceiver(receiver, new IntentFilter(LocationManager.MODE_CHANGED_ACTION), Context.RECEIVER_EXPORTED);
        registered = true;
        changed.run();
    }
    static Boolean enabled(Context context) {
        try { LocationManager manager = context.getSystemService(LocationManager.class); return manager == null ? null : manager.isLocationEnabled(); }
        catch (RuntimeException error) { return null; }
    }
    @Override public void close() {
        if (!registered) return;
        registered = false;
        context.unregisterReceiver(receiver);
    }

    interface Access {
        int read() throws Exception;
        boolean write(boolean enabled) throws Exception;
    }
    record Result(boolean ok, String message, int state) { }
    static int state(boolean ok, String output) {
        if (!ok || output == null) return -1;
        return switch (output.trim()) { case "true" -> 1; case "false" -> 0; default -> -1; };
    }
    /** Read and write run serially in ShellService, with the owning Android user fixed. */
    static Result change(int value, Access access) throws Exception {
        if (value < -1 || value > 1) throw new IllegalArgumentException("Expected toggle, off or on");
        int before = access.read();
        if (value == -1 && before < 0) return new Result(false, "定位状态未知，请使用系统定位设置", -1);
        int wanted = value == -1 ? 1 - before : value;
        if (before == wanted) return new Result(true, wanted == 1 ? "整机定位已开启" : "整机定位已关闭", before);
        boolean accepted = access.write(wanted == 1);
        int after = access.read();
        if (!accepted) return new Result(false, "系统拒绝切换定位，请检查 Shizuku 或系统限制", after);
        if (after != wanted) return new Result(false, "定位请求已提交，尚未确认生效，请查看系统定位设置", after);
        return new Result(true, after == 1 ? "已开启整机定位" : "已关闭整机定位", after);
    }
}
