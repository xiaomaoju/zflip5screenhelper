package io.github.flipcover.controls;

import android.os.Handler;

/** Event-driven recovery of the user's choice, bounded to two passes per transition. */
final class SystemControlGuard {
    interface Host {
        boolean disabled();
        void saveDisabled(boolean disabled);
        boolean connected();
        void post(Runnable action, long delay);
        void cancel(Runnable action);
        void apply(ShizukuBridge.Callback callback);
        void result(ShizukuBridge.Result result);
    }
    private final Host host;
    private final Runnable restore = this::restore;
    private long generation;
    private int busyAttempts;
    private boolean manual, closed, settled;

    SystemControlGuard(Prefs prefs, Handler main, ShizukuBridge bridge, ShizukuBridge.Callback result) {
        this(new Host() {
            public boolean disabled() { return prefs.systemControlsDisabled(); }
            public void saveDisabled(boolean disabled) { prefs.systemControlsDisabled(disabled); }
            public boolean connected() { return bridge.connected() && bridge.granted(); }
            public void post(Runnable action, long delay) { main.postDelayed(action, delay); }
            public void cancel(Runnable action) { main.removeCallbacks(action); }
            public void apply(ShizukuBridge.Callback callback) { bridge.run("system_controls", -1, 0, "", callback); }
            public void result(ShizukuBridge.Result value) { result.accept(value); }
        });
    }
    SystemControlGuard(Host host) { this.host = host; }

    void changed() {
        if (closed || manual) return;
        cancel(); busyAttempts = 0; settled = false;
        if (host.disabled() && host.connected()) host.post(restore, 300);
    }
    int begin(int requested) {
        cancel(); manual = true;
        int value = requested == -1 && host.disabled() ? 1 : requested;
        // Cancel persistence before enabling, including a failed/disconnected request.
        // An old restore completion must never reinstate the disabled choice.
        if (value == 1) host.saveDisabled(false);
        return value;
    }
    void finish(ShizukuBridge.Result result) {
        if (closed) return;
        manual = false;
        if (result.ok && (result.output.equals("0") || result.output.equals("1"))) host.saveDisabled(result.output.equals("0"));
        changed();
    }
    private void restore() {
        if (closed || manual || !host.disabled() || !host.connected()) return;
        long request = generation;
        host.apply(result -> {
            if (closed || manual || request != generation) return;
            if (result.retryable && ++busyAttempts < 5) { host.post(restore, 300); return; }
            host.result(result);
            // Samsung's display and folding callbacks settle asynchronously. One final
            // pass follows the initial one; there is no periodic background polling.
            if (!settled && !result.retryable && host.disabled() && host.connected()) {
                settled = true; busyAttempts = 0; host.post(restore, 1200);
            }
        });
    }
    private void cancel() { generation++; host.cancel(restore); }
    void close() { closed = true; cancel(); }
}
