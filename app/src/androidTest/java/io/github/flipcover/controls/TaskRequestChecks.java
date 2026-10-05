package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Serial bridge fixtures only; never reads or mutates actual system tasks. */
final class TaskRequestChecks {
    private final Instrumentation test;
    private int assertions;
    TaskRequestChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    String run() throws Exception {
        test.startActivitySync(new android.content.Intent(test.getTargetContext(), MainActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync();
        ShizukuBridge bridge = CoverApp.bridge(test.getTargetContext());
        Field remote = ShizukuBridge.class.getDeclaredField("remote"), worker = ShizukuBridge.class.getDeclaredField("executor"); remote.setAccessible(true); worker.setAccessible(true);
        Object original = remote.get(bridge); ExecutorService executor = (ExecutorService) worker.get(bridge);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), ordinaryDone = new CountDownLatch(1), taskDone = new CountDownLatch(1);
        CountDownLatch held = new CountDownLatch(1), resume = new CountDownLatch(1), cancelled = new CountDownLatch(1);
        List<String> operations = new java.util.concurrent.CopyOnWriteArrayList<>();
        AtomicReference<ShizukuBridge.Result> result = new AtomicReference<>(), duplicate = new AtomicReference<>();
        AtomicReference<ShizukuBridge.Snapshot> preview = new AtomicReference<>();
        AtomicBoolean active = new AtomicBoolean(true), onMain = new AtomicBoolean();
        IShellService fake = new IShellService.Stub() {
            public String execute(String operation, int display, int value, String component) {
                operations.add(operation);
                if (operation.equals("states")) { started.countDown(); try { if (!release.await(3, TimeUnit.SECONDS)) throw new AssertionError("fixture release timeout"); } catch (InterruptedException error) { throw new AssertionError(error); } }
                return "{\"ok\":true,\"message\":\"fixture\",\"output\":\"\"}";
            }
            public Bundle taskSnapshot(int display, String task) { throw new AssertionError("preview cannot get ahead of queued task work"); }
            public void watchConnectivity(IConnectivityListener listener) { }
            public void destroy() { }
        };
        try {
            remote.set(bridge, fake);
            test.runOnMainSync(() -> bridge.run("states", -1, 0, "", value -> ordinaryDone.countDown()));
            require(started.await(3, TimeUnit.SECONDS), "ordinary operation occupies serial worker");
            test.runOnMainSync(() -> {
                bridge.runTask("recent_dismiss", 7, "fixture identity", active::get, value -> { result.set(value); onMain.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()); taskDone.countDown(); });
                bridge.runTask("recent_tasks", 7, "", active::get, duplicate::set);
                bridge.snapshot(7, new RecentTasks.Task(1, 7, 0, "fixture/.Task", "fixture", false), preview::set);
            });
            require(result.get() == null && taskDone.getCount() == 1, "task waits for occupied worker instead of failing immediately");
            require(duplicate.get() != null && duplicate.get().retryable && !duplicate.get().ok, "task queue remains bounded");
            require(preview.get() != null && preview.get().retryable(), "new preview yields to queued task request");
            release.countDown(); require(ordinaryDone.await(3, TimeUnit.SECONDS) && taskDone.await(3, TimeUnit.SECONDS), "both queued callbacks complete");
            require(result.get().ok && onMain.get() && operations.equals(List.of("states", "recent_dismiss")), "task executes exactly once in order and reports on main thread");
            require(!bridge.busy(), "completed task releases busy guard");
            executor.execute(() -> { held.countDown(); try { resume.await(3, TimeUnit.SECONDS); } catch (InterruptedException error) { throw new AssertionError(error); } });
            require(held.await(3, TimeUnit.SECONDS), "worker held for cancellation fixture");
            test.runOnMainSync(() -> bridge.runTask("recent_dismiss", 7, "fixture identity", active::get, value -> { result.set(value); cancelled.countDown(); }));
            active.set(false); resume.countDown(); require(cancelled.await(3, TimeUnit.SECONDS), "obsolete page delivers cancellation");
            require(!result.get().ok && operations.size() == 2 && !bridge.busy(), "queued mutation never reaches service after page cancellation");
        } finally { release.countDown(); resume.countDown(); remote.set(bridge, original); }
        return "task-requests: " + assertions + " assertions; contention, task priority, bounded queue and cancellation PASS";
    }
}
