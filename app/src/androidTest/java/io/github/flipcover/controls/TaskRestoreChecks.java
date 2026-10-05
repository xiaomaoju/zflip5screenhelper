package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.SystemClock;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Native task-page lifecycle with a captured restore boundary; never opens real tasks. */
final class TaskRestoreChecks {
    private final Instrumentation test;
    private int assertions;
    TaskRestoreChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void field(Object object, String name, Object value) { try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private Object field(Object object, String name) { try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void invoke(CoverService owner, String name, Class<?> type, Object value) { try { Method method = CoverService.class.getDeclaredMethod(name, type); method.setAccessible(true); method.invoke(owner, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void main(Runnable action) { Throwable[] failure = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new AssertionError(failure[0]); test.waitForIdleSync(); }
    String run() throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Prefs prefs = new Prefs(activity); int oldDisplay = prefs.displayId(); boolean oldEnabled = prefs.enabled();
        ImageReader image = ImageReader.newInstance(720, 748, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay virtual = activity.getSystemService(DisplayManager.class).createVirtualDisplay("Task restore fixture", 720, 748, 340, image.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        require(virtual != null && virtual.getDisplay().getDisplayId() > 0, "explicit fixture secondary display");
        ShizukuBridge bridge = CoverApp.bridge(activity); Object originalRemote = field(bridge, "remote"); CoverService previous = CoverService.instance;
        CoverService[] owner = {null}; AppHubView[] page = {null}; CountDownLatch[] release = {new CountDownLatch(0)};
        try {
            prefs.data.edit().putInt("display", virtual.getDisplay().getDisplayId()).putBoolean("enabled", true).commit();
            List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); require(!apps.isEmpty(), "application catalog fixture");
            for (int count : new int[]{1, 3}) {
                CountDownLatch started = new CountDownLatch(1); release[0] = new CountDownLatch(1); CountDownLatch gate = release[0];
                field(bridge, "remote", new IShellService.Stub() {
                    public String execute(String operation, int display, int value, String component) {
                        require(operation.equals("recent_open") && display == virtual.getDisplay().getDisplayId(), "restore uses exact selected display and task boundary"); started.countDown();
                        try { if (!gate.await(5, TimeUnit.SECONDS)) throw new AssertionError("fixture response timeout"); } catch (InterruptedException error) { throw new AssertionError(error); }
                        return "{\"ok\":true,\"output\":\"{\\\"state\\\":\\\"opened\\\"}\"}";
                    }
                    public Bundle taskSnapshot(int display, String task) { return new Bundle(); }
                    public void watchConnectivity(IConnectivityListener listener) { } public void destroy() { }
                });
                List<RecentTasks.Task> tasks = new ArrayList<>(); for (int i = 0; i < count; i++) { AppCatalogCache.Entry app = apps.get(i % apps.size()); tasks.add(new RecentTasks.Task(800 + i, virtual.getDisplay().getDisplayId(), 0, app.id().substring(4), app.packageName(), false)); }
                main(() -> {
                    owner[0] = new CoverService(); try { Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(owner[0], activity); } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                    CoverService.instance = owner[0]; owner[0].prefs = prefs; owner[0].display = virtual.getDisplay(); owner[0].screenContext = activity.createDisplayContext(virtual.getDisplay());
                    DockGeometry.Box frame = new DockGeometry.Box(0, 0, 720, 748); field(owner[0], "panelFrame", frame); field(owner[0], "hubFrame", frame); field(owner[0], "cardSafeFrame", frame);
                    field(owner[0], "launcherFrameRotation", virtual.getDisplay().getRotation()); field(owner[0], "placement", DockGeometry.resolve(720, 748, List.of(), 340f / 160, prefs.corner(0), prefs.widthRatio(), prefs.heightRatio(), true)); field(owner[0], "hubSceneProgress", 1f);
                    page[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } public void openTask(RecentTasks.Task task) { invoke(owner[0], "openHubTask", RecentTasks.Task.class, task); } });
                    page[0].recentCapabilities(true, true, false); page[0].recentResult(tasks, null); page[0].showTasks(true); activity.setContentView(owner[0].attachHubContent(page[0])); field(owner[0], "hub", page[0]);
                }); SystemClock.sleep(250); test.waitForIdleSync();
                main(() -> {
                    RecentTasksView tasksPage = page[0].taskPage(); android.view.View carousel = tasksPage.findViewWithTag("task-carousel");
                    long down = SystemClock.uptimeMillis(); float x = carousel.getWidth() / 2f, y = carousel.getHeight() / 2f;
                    for (int i = 0; i < 2; i++) { android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + i * 20, i == 0 ? android.view.MotionEvent.ACTION_DOWN : android.view.MotionEvent.ACTION_MOVE, x + i * 45, y, 0); carousel.dispatchTouchEvent(event); event.recycle(); }
                    TaskForce force = (TaskForce) field(carousel, "force"); float offset = force.offset, edgePull = force.tx[0];
                    require((boolean) field(carousel, "tracking"), count + " tasks: active pointer before foreground source change");
                    page[0].recentBusy(true); field(owner[0], "signature", "fixture removed foreground navigation");
                    invoke(owner[0], "reconcile", boolean.class, false);
                    require(field(owner[0], "hub") == page[0] && page[0].taskPage() == tasksPage && carousel.isAttachedToWindow(), count + " tasks: removing foreground retains carousel and pending task owner");
                    require((boolean) field(owner[0], "taskInsetsDeferred") && (boolean) field(carousel, "tracking") && force.offset == offset, count + " tasks: inset changes defer without cancelling pointer or resetting position");
                    android.view.MotionEvent move = android.view.MotionEvent.obtain(down, down + 40, android.view.MotionEvent.ACTION_MOVE, x + 80, y, 0); carousel.dispatchTouchEvent(move); move.recycle();
                    require((force.offset != offset || force.tx[0] != edgePull) && (boolean) field(carousel, "tracking"), count + " tasks: same pointer continues moving after foreground removal");
                    android.view.MotionEvent cancel = android.view.MotionEvent.obtain(down, down + 60, android.view.MotionEvent.ACTION_CANCEL, x + 80, y, 0); carousel.dispatchTouchEvent(cancel); cancel.recycle();
                    page[0].recentBusy(false); invoke(owner[0], "reconcile", boolean.class, false);
                    require(field(owner[0], "hub") == page[0] && !page[0].recentBusy(), count + " tasks: idle result also retains page until dismissal");
                    tasksPage.select(tasks.get(0));
                }); SystemClock.sleep(300); test.waitForIdleSync();
                main(() -> { android.view.View card = page[0].findViewWithTag("task-card:" + tasks.get(0).id()); require(card != null && card.performClick(), count + " tasks: card click reaches restore boundary"); });
                require(started.await(3, TimeUnit.SECONDS), count + " tasks: restore waits for system confirmation");
                main(() -> {
                    field(owner[0], "signature", "fixture previous navigation insets"); invoke(owner[0], "reconcile", boolean.class, false);
                    require(field(owner[0], "hub") == page[0] && page[0].isAttachedToWindow() && !page[0].closing(), count + " tasks: navigation-inset reconciliation keeps the pending restore owner");
                });
                gate.countDown(); SystemClock.sleep(700); test.waitForIdleSync();
                main(() -> { require(field(owner[0], "hub") == null && !page[0].isAttachedToWindow(), count + " tasks: successful restore closes page without recreating it"); owner[0].main.removeCallbacksAndMessages(null); });
                SystemClock.sleep(500); main(() -> require(field(owner[0], "hub") == null, count + " tasks: later display settle cannot revive task page"));
            }
            main(() -> {
                page[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
                page[0].setExpanded(false); activity.setContentView(page[0]);
                RecentTasks.Task task = new RecentTasks.Task(900, virtual.getDisplay().getDisplayId(), 0, apps.get(0).id().substring(4), apps.get(0).packageName(), false);
                page[0].recentCapabilities(true, true, false); page[0].recentResult(List.of(task), null); page[0].recentBusy(true);
                page[0].taskOpenFailure(task, false, "fixture unconfirmed recovery");
                require(page[0].isAttachedToWindow() && !page[0].showingTasks() && !page[0].expanded() && !page[0].recentBusy(), "failed Dock recovery retains its source instead of opening a task page");
                page[0].recentResult(List.of(task), null); page[0].showTasks(true); RecentTasksView surface = page[0].taskPage();
                page[0].taskOpenFailure(task, true, "fixture missing task");
                require(page[0].taskPage() == surface && surface.getContentDescription().toString().contains("重新打开"), "failed task-page recovery keeps the same native task surface with explicit recovery");
            });
            return "task-restore: " + assertions + " assertions; one/multiple tasks, foreground removal during held pointer, deferred inset updates, in-flight navigation changes, confirmed exit, failure retention and no resurrection PASS";
        } finally {
            release[0].countDown(); field(bridge, "remote", originalRemote); CoverService.instance = previous;
            main(() -> { if (owner[0] != null) owner[0].main.removeCallbacksAndMessages(null); if (page[0] != null) page[0].dispose(); activity.finish(); });
            prefs.data.edit().putInt("display", oldDisplay).putBoolean("enabled", oldEnabled).commit(); virtual.release(); image.close();
        }
    }
}
