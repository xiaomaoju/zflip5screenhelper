package io.github.flipcover.controls;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class SystemControlGuardTest {
    private static final ShizukuBridge.Result OFF = new ShizukuBridge.Result(true, "disabled", "0");
    private static final ShizukuBridge.Result ON = new ShizukuBridge.Result(true, "enabled", "1");
    private static final ShizukuBridge.Result BUSY = new ShizukuBridge.Result(false, "busy", "", true);
    private static final class Host implements SystemControlGuard.Host {
        boolean disabled, connected = true, hold;
        int calls, results;
        ShizukuBridge.Result answer = OFF;
        ShizukuBridge.Callback pending;
        final List<Runnable> queued = new ArrayList<>();
        public boolean disabled() { return disabled; }
        public void saveDisabled(boolean value) { disabled = value; }
        public boolean connected() { return connected; }
        public void post(Runnable action, long delay) { queued.add(action); }
        public void cancel(Runnable action) { queued.removeIf(item -> item == action); }
        public void apply(ShizukuBridge.Callback callback) { calls++; if (hold) pending = callback; else callback.accept(answer); }
        public void result(ShizukuBridge.Result value) { results++; }
        void next() { queued.remove(0).run(); }
        void drain() { for (int i = 0; !queued.isEmpty() && i < 30; i++) next(); assertTrue("No permanent polling", queued.isEmpty()); }
    }
    @Test public void onlyAConfirmedDisableEnablesRecoveryAndEventsCoalesce() {
        Host host = new Host(); SystemControlGuard guard = new SystemControlGuard(host);
        guard.changed(); assertTrue(host.queued.isEmpty());
        guard.begin(0); guard.finish(new ShizukuBridge.Result(false, "failed", ""));
        assertFalse(host.disabled); assertTrue(host.queued.isEmpty());
        guard.begin(0); guard.finish(OFF);
        for (int i = 0; i < 20; i++) guard.changed();
        assertEquals(1, host.queued.size()); host.drain(); assertEquals(2, host.calls);
        guard.changed(); host.drain(); assertEquals(4, host.calls);
    }
    @Test public void enableCancelsQueuedAndInFlightRecovery() {
        Host host = new Host(); host.disabled = true; host.hold = true;
        SystemControlGuard guard = new SystemControlGuard(host);
        guard.changed(); host.next();
        assertEquals(1, guard.begin(-1)); assertFalse(host.disabled);
        host.pending.accept(OFF); assertEquals(0, host.results);
        guard.finish(ON); guard.changed(); assertTrue(host.queued.isEmpty());
    }
    @Test public void failedEnableDoesNotSilentlyRearmTheGuard() {
        Host host = new Host(); host.disabled = true;
        SystemControlGuard guard = new SystemControlGuard(host);
        guard.changed(); guard.begin(1); guard.finish(new ShizukuBridge.Result(false, "disconnected", ""));
        guard.changed(); assertFalse(host.disabled); assertTrue(host.queued.isEmpty());
    }
    @Test public void reconnectAndNewServiceRestoreTheSavedChoice() {
        Host host = new Host(); host.disabled = true; host.connected = false;
        SystemControlGuard guard = new SystemControlGuard(host);
        guard.changed(); assertTrue(host.queued.isEmpty());
        host.connected = true; guard.changed(); host.drain(); assertEquals(2, host.calls);
        guard.changed(); guard.close(); assertTrue(host.queued.isEmpty());
        new SystemControlGuard(host).changed(); host.drain(); assertEquals(4, host.calls);
    }
    @Test public void busyRetriesAreBoundedAndCloseInvalidatesCompletion() {
        Host host = new Host(); host.disabled = true; host.answer = BUSY;
        SystemControlGuard guard = new SystemControlGuard(host);
        guard.changed(); host.drain(); assertEquals(5, host.calls);
        host.hold = true; guard.changed(); host.next(); guard.close();
        host.pending.accept(OFF); assertTrue(host.queued.isEmpty()); assertEquals(1, host.results);
    }
    @Test public void oldTransitionCannotAddAnotherSettlementPass() {
        Host host = new Host(); host.disabled = true; host.hold = true;
        SystemControlGuard guard = new SystemControlGuard(host);
        guard.changed(); host.next(); ShizukuBridge.Callback old = host.pending;
        guard.changed(); old.accept(OFF);
        assertEquals(1, host.queued.size()); assertEquals(0, host.results);
        host.hold = false; host.drain(); assertEquals(3, host.calls);
    }
}
