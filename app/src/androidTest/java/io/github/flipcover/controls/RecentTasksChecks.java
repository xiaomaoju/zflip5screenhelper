package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Native component and failure-path checks; no real task is opened, captured or removed. */
final class RecentTasksChecks {
    private final Instrumentation instrumentation;
    private Activity activity;
    private AppHubView hub;
    private RecentTasksView page;
    private List<RecentTasks.Task> source;
    private final List<RecentTasks.Task> opened = new ArrayList<>();
    private List<RecentTasks.Task> clearing = List.of();
    private final List<Consumer<ShizukuBridge.Snapshot>> snapshots = new ArrayList<>();
    private int assertions, appLaunches, refreshes;
    RecentTasksChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void main(Runnable action) { Throwable[] error = {null}; instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } }); if (error[0] != null) throw new AssertionError(error[0]); instrumentation.waitForIdleSync(); }
    private <T extends View> T find(String tag) { return hub.findViewWithTag(tag); }
    private void click(String tag) { main(() -> { View view = find(tag); require(view != null && view.isEnabled(), "action available: " + tag); view.performClick(); }); SystemClock.sleep(100); instrumentation.waitForIdleSync(); }
    private RecentTasks.Task task(int id, AppCatalogCache.Entry app, boolean visible) { return new RecentTasks.Task(id, 2, 0, app.id().substring(4), app.packageName(), visible); }
    String run() throws Exception {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Prefs prefs = new Prefs(activity); prefs.data.edit().clear().commit();
        RecentTasks.Locks locks = CoverApp.taskLocks(activity); locks.clear();
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); require(apps.size() >= 4, "installed application fixtures");
        prefs.saveHubPins(List.of(apps.get(3).id()));
        source = List.of(task(100, apps.get(0), true), task(101, apps.get(1), false), task(102, apps.get(1), false), task(103, apps.get(2), false), task(104, apps.get(3), false));
        main(() -> {
            hub = new AppHubView(activity, prefs, new AppHubView.Listener() {
                public void action(String id) { appLaunches++; }
                public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { }
                public void refreshRecents() { refreshes++; }
                public void openTask(RecentTasks.Task task) { opened.add(task); }
                public void clearRecents(List<RecentTasks.Task> tasks) { clearing = tasks; hub.recentBusy(true); }
                public void snapshot(RecentTasks.Task task, Consumer<ShizukuBridge.Snapshot> callback) { snapshots.add(callback); }
            });
            activity.getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(0xFF090B10);
            FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(-1, -1); bounds.setMargins(8, 12, 8, 74); root.addView(hub, bounds); activity.setContentView(root);
            hub.recentCapabilities(true, true, false); hub.recentResult(source, null);
        });
        main(() -> {
            ViewGroup dock = find("hub-dock"); dock.getChildAt(3).performClick();
            require(opened.size() == 1 && appLaunches == 0, "recent Dock restores task instead of launching MAIN"); opened.clear();
        });
        click("hub-tasks"); page = find("recent-tasks");
        screenshot("tasks-initial");
        main(() -> {
            require(page != null && page.selectedTask().id() == 100, "task page opens at current first window");
            require(find("hub-grid").isShown() == false && find("hub-dock").isShown() == false, "task page reclaims catalog and dock space");
            require(!find("task-card:100").performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS, null), "visible task cannot be dismissed");
            require(find("tasks-clear").getContentDescription().toString().contains("3"), "batch count excludes visible and fixed tasks, keeps sibling windows");
            checkThreeCards();
            View clear = find("tasks-clear"), toolbar = find("tasks-toolbar"), cards = find("task-carousel");
            ViewGroup stage = find("tasks-stage"); View card = ((ViewGroup) cards).getChildAt(0);
            require(clear instanceof android.widget.ImageButton && clear.getParent() == stage && clear.getLeft() < Ui.dp(activity, 8) && clear.getTop() >= card.getBottom(), "batch close occupies the lower-left whitespace without overlapping cards");
            require(clear.getWidth() - clear.getPaddingLeft() - clear.getPaddingRight() <= Ui.dp(activity, 20), "batch close graphic is at most twenty dp");
            require(((ViewGroup) toolbar.getParent()).getChildCount() == 2 && cards.getBottom() == stage.getHeight() && card.getTop() > 0, "cards leave upper whitespace and no footer row consumes the stage");
        });
        swipe(-.7f, 0, false);
        main(() -> { require(page.selectedTask().id() == 102, "horizontal swipe reveals next group"); checkThreeCards(); });
        main(() -> { page.select(source.get(2)); require(page.selectedTask().id() == 102, "second window of same app is independently addressable"); });
        main(() -> { find("task-card:102").performClick(); require(!opened.isEmpty() && opened.get(opened.size() - 1).id() == 102 && appLaunches == 0, "any visible card opens directly in one click"); });
        main(() -> require(find("task-preview:102").getHeight() >= Ui.dp(activity, 36), "preview retains readable area at current font and density"));
        screenshot("tasks-cards");
        swipe(0, .6f, false);
        require(locks.contains(source.get(2)) && find("task-lock:102").isShown(), "downward gesture locks the touched window and shows badge"); screenshot("tasks-locked");
        swipe(0, -.6f, false); require(clearing.isEmpty(), "locked task resists upward dismissal");
        swipe(0, .6f, true); require(locks.contains(source.get(2)), "cancelled downward gesture does not unlock");
        swipe(0, .03f, false); require(locks.contains(source.get(2)), "short downward movement does not unlock");
        click("tasks-clear"); require(clearing.equals(List.of(source.get(1), source.get(3))), "corner batch close skips locked, fixed and visible tasks");
        main(() -> { hub.recentResult(source, null); clearing = List.of(); });
        click("tasks-apps"); click("hub-clear"); require(clearing.equals(List.of(source.get(1), source.get(3))), "recent Dock uses the same task locks");
        main(() -> { hub.recentResult(source, null); clearing = List.of(); });
        click("hub-tasks"); page = find("recent-tasks"); main(() -> page.select(source.get(2)));
        require(locks.contains(source.get(2)) && find("task-lock:102").isShown(), "lock survives leaving and reopening task page");
        swipe(0, .6f, false); require(!locks.contains(source.get(2)), "a second downward gesture explicitly unlocks");
        swipe(0, -.8f, true); require(clearing.isEmpty(), "cancelled upward drag never removes a task");
        View gestureArea = find("task-carousel"); float diagonal = .45f * gestureArea.getHeight() / gestureArea.getWidth();
        swipe(diagonal, -.45f, false); require(clearing.isEmpty() && page.selectedTask().id() == 102, "diagonal drag never closes, switches or launches a task");
        swipe(0, -.8f, false);
        main(() -> { require(clearing.size() == 1 && clearing.get(0).id() == 102, "upward swipe only requests touched window"); require(page.selectedTask().id() == 102 && find("task-card:102") != null, "pending removal retains card until system response"); });
        main(() -> hub.recentFailure("系统拒绝关闭，请重试"));
        require(!find("tasks-clear").isEnabled(), "failure disables stale batch actions"); screenshot("tasks-unavailable");
        main(() -> { hub.recentResult(source.stream().filter(task -> task.id() != 102).toList(), null); require(page.selectedTask().id() == 103, "confirmed deletion advances to adjacent window"); });
        main(() -> { hub.taskOpenFailure(source.get(2), true, "原窗口已结束，可重新打开应用"); View recovery = find("tasks-reopen"); require(recovery.isShown(), "explicit recovery offered for missing task: visibility=" + recovery.getVisibility() + ", parent=" + ((View) recovery.getParent()).getVisibility() + ", page=" + page.getVisibility() + ", hub=" + hub.isShown()); require(appLaunches == 0, "missing task does not silently launch new task"); });
        click("tasks-reopen"); require(appLaunches == 1, "only explicit recovery launches application");
        main(() -> hub.showTasks(false)); require(find("hub-grid").isShown(), "return to apps restores catalog");
        click("hub-tasks"); page = find("recent-tasks");
        main(() -> { hub.recentResult(source, null); hub.recentCapabilities(true, true, true); hub.recentResult(source, null); });
        require(snapshots.size() == 1, "preview requests are serialized");
        Bitmap stale = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        click("tasks-apps"); main(() -> snapshots.get(0).accept(new ShizukuBridge.Snapshot(stale, "sample")));
        require(stale.isRecycled(), "late bitmap after page removal is released");
        snapshots.clear(); click("hub-tasks"); page = find("recent-tasks"); require(snapshots.size() == 1, "new page gets fresh preview generation");
        main(() -> page.onLowMemory()); Bitmap trimmed = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888); main(() -> snapshots.get(0).accept(new ShizukuBridge.Snapshot(trimmed, "sample"))); require(trimmed.isRecycled(), "late preview after low-memory trim is discarded");
        main(() -> hub.recentResult(List.of(), null)); screenshot("tasks-empty"); require(!find("tasks-clear").isEnabled(), "empty tasks have no destructive action");
        for (int edge = 0; edge < 4; edge++) {
            int dockEdge = edge;
            main(() -> { hub.showTasks(false); hub.dockEdge(dockEdge); hub.recentCapabilities(true, true, false); hub.recentResult(source, null); hub.showTasks(true); page = find("recent-tasks"); page.select(source.get(1)); clearing = List.of(); });
            swipe(-.7f, 0, false); require(page.selectedTask().id() == 102, "carousel retains horizontal gesture at dock edge " + edge);
            swipe(0, -.8f, true); require(clearing.isEmpty(), "cancel protection at dock edge " + edge);
            swipe(0, .6f, false); require(locks.contains(source.get(2)), "card downward gesture is not stolen by panel at edge " + edge);
            swipe(0, -.6f, false); require(clearing.isEmpty(), "locked card is protected at edge " + edge);
            swipe(0, .6f, false); require(!locks.contains(source.get(2)), "unlock at edge " + edge);
        }
        main(() -> { page.select(source.get(1)); android.view.accessibility.AccessibilityNodeInfo node = find("task-card:101").createAccessibilityNodeInfo(); int id = 0; for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action : node.getActionList()) if ("锁定此窗口".contentEquals(action.getLabel() == null ? "" : action.getLabel())) id = action.getId(); require(id != 0 && find("task-card:101").performAccessibilityAction(id, null) && locks.contains(source.get(1)), "accessible lock action works without a gesture"); });
        swipe(0, .6f, false); require(!locks.contains(source.get(1)), "accessible lock and gesture unlock share state");
        multiTouch(); require(clearing.isEmpty() && !locks.contains(source.get(1)), "multi-touch cannot lock or clear");
        main(() -> {
            View card = find("task-card:101"); Rect bounds = new Rect(0, 0, card.getWidth(), card.getHeight()); hub.offsetDescendantRectToMyCoords(card, bounds); long start = SystemClock.uptimeMillis(); float x = bounds.centerX(), y = bounds.bottom - 12;
            event(start, start, MotionEvent.ACTION_DOWN, x, y);
            RecentTasks.Task current = source.get(1), nowVisible = new RecentTasks.Task(current.id(), current.displayId(), current.userId(), current.component(), current.packageName(), true);
            hub.recentResult(List.of(source.get(3), nowVisible, source.get(0), source.get(2), source.get(4)), null);
            require(find("task-card:101") == card, "task reorder waits until the finger is released");
            event(start, start + 50, MotionEvent.ACTION_MOVE, x, y - Ui.dp(activity, 100)); event(start, start + 100, MotionEvent.ACTION_UP, x, y - Ui.dp(activity, 100));
            require(clearing.isEmpty(), "new visible state blocks dismissal even while old card remains under the finger");
            require(page.selectedTask().id() == 101, "selected identity survives deferred reorder");
            hub.recentResult(source, null);
        });
        main(() -> {
            ShellService shell = new ShellService(activity);
            try { require(!new org.json.JSONObject(shell.execute("recent_open", 0, 0, "{}")).getBoolean("ok"), "unprivileged restore fails closed"); require(shell.taskSnapshot(0, "{}").getParcelable("bitmap") == null, "unprivileged snapshots expose no pixels"); } catch (Exception error) { throw new AssertionError(error); }
            boolean rejected = false; try { new ShellService().execute("recent_open", 2, 0, "{}"); } catch (SecurityException expected) { rejected = true; } require(rejected, "unowned service rejects callers");
            hub.dispose(); activity.setContentView(new FrameLayout(activity)); require(CoverApp.catalog(activity).observerCount() == 0, "hub and task page release subscriptions");
        });
        int stopped = refreshes; SystemClock.sleep(3200); instrumentation.waitForIdleSync(); require(stopped == refreshes, "detached page stops recent-task refresh");
        String environment = "rotation=" + activity.getDisplay().getRotation() + ", density=" + activity.getResources().getDisplayMetrics().densityDpi + ", font=" + activity.getResources().getConfiguration().fontScale;
        main(activity::finish);
        return "PASS: external task page; " + assertions + " assertions; " + environment + "; native components and denied-permission paths, no privileged or Samsung task validation";
    }
    private void checkThreeCards() {
        ViewGroup carousel = find("task-carousel"); require(carousel.getChildCount() == 3, "three complete cards are mounted, including at list ends"); int right = 0;
        for (int i = 0; i < 3; i++) { View card = carousel.getChildAt(i); require(card.getLeft() >= right && card.getRight() <= carousel.getWidth() && card.getWidth() >= Ui.dp(activity, 48), "card is fully visible, nonoverlapping and touchable: " + i + " bounds=" + card.getLeft() + ".." + card.getRight() + " host=" + carousel.getWidth()); right = card.getRight(); }
    }
    private void swipe(float width, float height, boolean cancel) {
        SystemClock.sleep(100); instrumentation.waitForIdleSync();
        main(() -> {
            View carousel = find("task-carousel"); Rect bounds = new Rect(0, 0, carousel.getWidth(), carousel.getHeight()); hub.offsetDescendantRectToMyCoords(carousel, bounds);
            View card = find("task-card:" + page.selectedTask().id()); Rect target = new Rect(0, 0, card.getWidth(), card.getHeight()); hub.offsetDescendantRectToMyCoords(card, target);
            float x = target.centerX(), y = target.top + target.height() * (height > 0 ? .15f : .85f), dx = width * bounds.width(), dy = height * bounds.height(); long start = SystemClock.uptimeMillis();
            event(start, start, MotionEvent.ACTION_DOWN, x, y);
            for (int i = 1; i <= 8; i++) event(start, start + i * 25, MotionEvent.ACTION_MOVE, x + dx * i / 8, y + dy * i / 8);
            event(start, start + 225, cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, x + dx, y + dy);
        });
        SystemClock.sleep(200); instrumentation.waitForIdleSync();
    }
    private void multiTouch() {
        main(() -> {
            View card = find("task-card:" + page.selectedTask().id()); Rect bounds = new Rect(0, 0, card.getWidth(), card.getHeight()); hub.offsetDescendantRectToMyCoords(card, bounds); long start = SystemClock.uptimeMillis();
            float x = bounds.centerX(), y = bounds.top + bounds.height() * .2f; event(start, start, MotionEvent.ACTION_DOWN, x, y);
            MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()}; MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
            for (int i = 0; i < 2; i++) { properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER; coords[i].x = x + i * 10; coords[i].y = y; coords[i].pressure = 1; coords[i].size = 1; }
            MotionEvent second = MotionEvent.obtain(start, start + 20, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0); hub.dispatchTouchEvent(second); second.recycle();
            for (MotionEvent.PointerCoords point : coords) point.y += Ui.dp(activity, 100);
            MotionEvent move = MotionEvent.obtain(start, start + 100, MotionEvent.ACTION_MOVE, 2, properties, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0); hub.dispatchTouchEvent(move); move.recycle();
            MotionEvent lift = MotionEvent.obtain(start, start + 125, MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0); hub.dispatchTouchEvent(lift); lift.recycle();
            event(start, start + 150, MotionEvent.ACTION_UP, x, y + Ui.dp(activity, 100));
        });
    }
    private void event(long down, long time, int action, float x, float y) { MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0); hub.dispatchTouchEvent(event); event.recycle(); }
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync(); SystemClock.sleep(150); Bitmap[] rendered = {null};
        main(() -> { View root = ((ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0); rendered[0] = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new android.graphics.Canvas(rendered[0])); });
        File directory = new File(instrumentation.getTargetContext().getFilesDir(), "ui-smoke"); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { rendered[0].compress(Bitmap.CompressFormat.PNG, 100, output); } finally { rendered[0].recycle(); }
    }
}
