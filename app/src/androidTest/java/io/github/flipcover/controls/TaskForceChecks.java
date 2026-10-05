package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Real native views and events with task-identity fixtures. Never calls a system task removal API. */
final class TaskForceChecks {
    private final Instrumentation instrumentation;
    private Activity activity;
    private RecentTasksView page;
    private ViewGroup strip;
    private PanelGlassSession session;
    private final List<RecentTasks.Task> source = new ArrayList<>(), requests = new ArrayList<>();
    private final Set<View> shells = Collections.newSetFromMap(new IdentityHashMap<>());
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private int assertions, previewReads, empty;
    private boolean busy;
    private long downTime;
    TaskForceChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void ui(Runnable action) { Throwable[] error = {null}; instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } }); if (error[0] != null) throw new AssertionError(error[0]); instrumentation.waitForIdleSync(); }
    private void data() { page.data(List.copyOf(source), true, true, busy, true, true, true, "图标模式"); }
    private float contentCenter() { return strip.getWidth() / 2f; }
    private View center() { View nearest = null; float distance = Float.MAX_VALUE; for (int i = 0; i < strip.getChildCount(); i++) { View card = strip.getChildAt(i); if (card.getTag() == null) continue; float d = Math.abs(card.getX() + card.getWidth() / 2f - contentCenter()); if (d < distance) { nearest = card; distance = d; } } return nearest; }
    private void pool() { require(strip.getChildCount() <= 4, "at most four mounted cards"); for (int i = 0; i < strip.getChildCount(); i++) shells.add(strip.getChildAt(i)); require(shells.size() <= 4, "four original shells reused across list"); }
    private void centered(String label) { ui(() -> { pool(); View card = center(); require(card != null && Math.abs(card.getX() + card.getWidth() / 2f - contentCenter()) < .55f, label + " centers native card"); View picture = ((ViewGroup) card).getChildAt(1); require(picture.getScaleX() == 1 && picture.getScaleY() == 1 && picture.getRotation() == 0, label + " settles lens deformation"); }); }
    private void waitRest() {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] rest = {false}; ui(() -> { View card = center(); if (card instanceof ViewGroup group) { View picture = group.getChildAt(1), clear = page.findViewWithTag("tasks-clear"); rest[0] = Math.abs(card.getX() + card.getWidth() / 2f - contentCenter()) < .55f && picture.getScaleX() == 1 && picture.getScaleY() == 1 && picture.getRotation() == 0 && clear.getTranslationX() == 0 && clear.getTranslationY() == 0; } });
            if (rest[0]) return; SystemClock.sleep(50);
        }
        throw new AssertionError("native graph did not reach centered, undeformed rest within six seconds");
    }
    private void event(int action, float x, float y) { ui(() -> { long time = SystemClock.uptimeMillis(); MotionEvent e = MotionEvent.obtain(downTime, time, action, x, y, 0); strip.dispatchTouchEvent(e); e.recycle(); }); }
    private void drag(float dx, float dy, boolean fast, boolean cancel) {
        float x = contentCenter(), y = strip.getHeight() * .4f; downTime = SystemClock.uptimeMillis(); event(MotionEvent.ACTION_DOWN, x, y);
        for (int i = 1; i <= 5; i++) { SystemClock.sleep(fast ? 6 : 45); event(MotionEvent.ACTION_MOVE, x + dx * i / 5, y + dy * i / 5); }
        if (!fast) SystemClock.sleep(150); event(cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, x + dx, y + dy);
    }
    private void dismiss(View card) { ui(() -> require(card.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_DISMISS, null), "independent close action accepted")); }
    private void ack(boolean success) {
        ui(() -> { require(!requests.isEmpty() && busy, "one actual backend request active"); RecentTasks.Task task = requests.get(requests.size() - 1); if (success) source.removeIf(item -> RecentTasks.sameTask(item, task)); busy = false; data(); });
        SystemClock.sleep(100); instrumentation.waitForIdleSync();
    }
    private void capture(String name) throws Exception {
        View decor = activity.getWindow().getDecorView(); android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1); int[] status = {-1};
        android.view.PixelCopy.request(activity.getWindow(), bitmap, result -> { status[0] = result; ready.countDown(); }, main);
        require(ready.await(3, java.util.concurrent.TimeUnit.SECONDS) && status[0] == android.view.PixelCopy.SUCCESS, "actual hardware window pixels available");
        java.io.File directory = new java.io.File(activity.getFilesDir(), "task-force"); directory.mkdirs();
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); } finally { bitmap.recycle(); }
    }
    String runCloseFailure() throws Exception {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Prefs prefs = new Prefs(activity); CoverApp.taskLocks(activity).clear();
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); require(!apps.isEmpty(), "application fixture catalog");
        for (int i = 0; i < 3; i++) { AppCatalogCache.Entry app = apps.get(i % apps.size()); source.add(new RecentTasks.Task(700 + i, 2, 0, app.id().substring(4), app.packageName(), false)); }
        ui(() -> {
            page = new RecentTasksView(activity, prefs, DockGeometry.BOTTOM, new RecentTasksView.Listener() {
                public void open(RecentTasks.Task task) { } public void clear(List<RecentTasks.Task> tasks) { } public void refresh() { } public void apps() { } public void close() { } public void reopen(RecentTasks.Task task) { }
                public void closeTask(RecentTasks.Task task) { require(!busy, "rapid requests remain serialized"); requests.add(task); busy = true; data(); }
                public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { callback.accept(new ShizukuBridge.Snapshot(null, "fixture")); }
            });
            FrameLayout root = new FrameLayout(activity); root.addView(page, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root); data(); strip = page.findViewWithTag("task-carousel");
        }); waitRest();
        RecentTasks.Task removed = source.get(0), rejected = source.get(1);
        dismiss(page.findViewWithTag("task-card:" + removed.id())); dismiss(page.findViewWithTag("task-card:" + rejected.id()));
        require(requests.size() == 1, "second close waits for first response");
        ui(() -> {
            source.removeIf(task -> RecentTasks.sameTask(task, removed)); busy = false; data();
            // A later request/connection failure can arrive before the confirmed card retires.
            page.data(List.copyOf(source), false, true, false, true, true, false, "fixture request failure");
        });
        SystemClock.sleep(900); instrumentation.waitForIdleSync();
        ui(() -> {
            require(page.findViewWithTag("task-card:" + removed.id()) == null, "later failure cannot restore a system-confirmed removal");
            require(page.findViewWithTag("task-card:" + rejected.id()) != null, "unconfirmed close returns its own card");
            data();
        }); waitRest();
        dismiss(page.findViewWithTag("task-card:" + rejected.id())); require(requests.size() == 2, "surviving task can be closed after recovery"); ack(true); waitRest();
        ui(() -> { require(page.findViewWithTag("task-card:" + rejected.id()) == null, "retry removes the surviving task without reviving the prior task"); pool(); page.dispose(); });
        return "task-close-failure: " + assertions + " assertions; confirmed removal, later failure, recovery and card reuse PASS";
    }
    private void checkEmptyAndPreviewRetry(Prefs prefs) {
        int[] reads = new int[3];
        int emptyBefore = empty;
        List<RecentTasks.Task> sample = List.copyOf(source.subList(0, 3));
        ui(() -> {
            FrameLayout root = new FrameLayout(activity); activity.setContentView(root);
            page = new RecentTasksView(activity, prefs, DockGeometry.BOTTOM, new RecentTasksView.Listener() {
                public void open(RecentTasks.Task task) { } public void clear(List<RecentTasks.Task> tasks) { } public void refresh() { } public void apps() { } public void close() { } public void reopen(RecentTasks.Task task) { } public void empty() { empty++; }
                public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) {
                    int index = sample.indexOf(task); reads[index]++;
                    if (index == 0 && reads[index] == 1) main.post(() -> callback.accept(new ShizukuBridge.Snapshot(null, "transient fixture", true)));
                    else if (index == 2) main.post(() -> callback.accept(new ShizukuBridge.Snapshot(null, "protected fixture")));
                    else { android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888); main.post(() -> callback.accept(new ShizukuBridge.Snapshot(bitmap, "fixture"))); }
                }
            }); root.addView(page, new FrameLayout.LayoutParams(-1, -1)); page.data(List.of(), true, true, false, true, true, true, "fixture"); strip = page.findViewWithTag("task-carousel");
        });
        SystemClock.sleep(300);
        boolean[] joinedEntrance = {false};
        ui(() -> { View hint = page.findViewWithTag("tasks-empty"); require(hint instanceof android.widget.TextView && ((android.widget.TextView) hint).getText().toString().equals("当前没有后台任务") && hint.isShown(), "initial empty page remains visible with centered requested wording"); require(Math.abs(hint.getY() + hint.getHeight() / 2f - strip.getHeight() / 2f) < 1, "empty hint occupies centered stage"); require(empty == emptyBefore, "empty opening never triggers automatic exit"); page.sceneMotion(0, 400); page.postOnAnimation(() -> { page.sceneMotion(0, 200); page.data(sample, true, true, false, true, true, true, "fixture"); joinedEntrance[0] = page.findViewWithTag("task-card:" + sample.get(0).id()) != null; }); });
        SystemClock.sleep(800);
        ui(() -> { require(joinedEntrance[0], "first task data joins moving page without waiting for background reaction to settle"); require(reads[0] == 2 && reads[1] == 1 && reads[2] == 1, "transient preview retries once; protected preview is never retried"); require(page.findViewWithTag("task-preview:" + sample.get(0).id()) instanceof TaskPreviewView, "central thumbnail surface remains bound"); });
        SystemClock.sleep(400); require(reads[0] == 2 && reads[2] == 1, "failed preview reads do not poll"); ui(page::dispose);
    }
    String run() throws Exception {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        Prefs prefs = new Prefs(activity); CoverApp.taskLocks(activity).clear();
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); require(!apps.isEmpty(), "application fixture catalog");
        for (int i = 0; i < 20; i++) { AppCatalogCache.Entry app = apps.get(i % apps.size()); source.add(new RecentTasks.Task(100 + i, 2, 0, app.id().substring(4), app.packageName(), i == 0)); }
        ui(() -> {
            page = new RecentTasksView(activity, prefs, DockGeometry.BOTTOM, new RecentTasksView.Listener() {
                public void open(RecentTasks.Task task) { } public void clear(List<RecentTasks.Task> tasks) { throw new AssertionError("batch not used in gesture fixture"); }
                public void closeTask(RecentTasks.Task task) { require(!busy, "requests serialized"); requests.add(task); busy = true; data(); }
                public void refresh() { } public void apps() { } public void close() { } public void reopen(RecentTasks.Task task) { } public void empty() { empty++; }
                public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { previewReads++; android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xFF305080 + task.id()); main.post(() -> callback.accept(new ShizukuBridge.Snapshot(bitmap, "fixture"))); }
            });
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(0xFF102033); root.addView(page, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root); data(); strip = page.findViewWithTag("task-carousel");
            page.select(source.get(7));
            session = new PanelGlassSession(activity, activity.getDisplay());
            android.graphics.Bitmap backdrop = android.graphics.Bitmap.createBitmap(720, 748, android.graphics.Bitmap.Config.ARGB_8888); android.graphics.Canvas canvas = new android.graphics.Canvas(backdrop); android.graphics.Paint paint = new android.graphics.Paint();
            paint.setShader(new android.graphics.LinearGradient(0, 0, 720, 748, 0xFF304A6C, 0xFF6D405F, android.graphics.Shader.TileMode.CLAMP)); canvas.drawRect(0, 0, 720, 748, paint); session.fixture(backdrop); session.attach(page, false);
        });
        waitRest(); centered("entry focus"); require(center().getTag().equals("task-card:107"), "entry selection is retained while initial frame is queued");
        ui(() -> page.select(source.get(0))); waitRest(); centered("latest"); require(center().getTag().equals("task-card:100"), "newest endpoint is centered"); capture("initial-raw");
        float[] entryPeak = new float[3];
        java.util.concurrent.CountDownLatch entryComplete = new java.util.concurrent.CountDownLatch(1);
        ui(() -> { page.sceneMotion(0, 400); page.postOnAnimation(new Runnable() {
            int frame;
            public void run() { float p = Math.min(1, ++frame / 24f); page.sceneMotion(0, 400 * (1 - p) * (1 - p)); View card = center(); entryPeak[0] = Math.max(entryPeak[0], Math.abs(card.getTranslationY())); entryPeak[1] = Math.max(entryPeak[1], Math.abs(session.taskReactionY())); entryPeak[2] = Math.max(entryPeak[2], Math.abs(page.findViewWithTag("tasks-clear").getTranslationY())); if (frame < 45) page.postOnAnimation(this); else entryComplete.countDown(); }
        }); });
        require(entryComplete.await(6, java.util.concurrent.TimeUnit.SECONDS), "entry frame replay completes");
        ui(() -> { require(entryPeak[0] > Ui.dp(activity, 5), "native entry card displacement is clearly visible: " + java.util.Arrays.toString(entryPeak)); require(entryPeak[1] > Ui.dp(activity, 2), "entry background reaction is clearly visible without another capture: " + java.util.Arrays.toString(entryPeak)); require(entryPeak[2] > Ui.dp(activity, 1), "centered clear action participates in entry inertia: " + java.util.Arrays.toString(entryPeak)); }); waitRest(); centered("entry reaction");
        SystemClock.sleep(200); instrumentation.waitForIdleSync(); int draws = session.draws; SystemClock.sleep(500); instrumentation.waitForIdleSync(); require(session.draws - draws < 5, "idle task page stops extra drawing");
        drag(90, 0, false, false); waitRest(); centered("slow drag");
        drag(330, 0, true, false); capture("fling-raw"); waitRest(); centered("fling");
        drag(-95, 0, false, true); waitRest(); centered("cancellation");
        // Interrupt a correcting spring, then lift without moving enough to acquire another axis.
        drag(85, 0, false, false); SystemClock.sleep(45); float x = contentCenter(), y = strip.getHeight() * .4f; downTime = SystemClock.uptimeMillis(); event(MotionEvent.ACTION_DOWN, x, y); SystemClock.sleep(180); event(MotionEvent.ACTION_UP, x, y); waitRest(); centered("interrupted capture and tap");
        for (RecentTasks.Task task : List.copyOf(source)) { ui(() -> { page.select(task); pool(); }); SystemClock.sleep(35); }
        waitRest(); centered("oldest"); require(center().getTag().equals("task-card:119"), "oldest endpoint is centered");
        ui(() -> page.select(source.get(0))); waitRest();
        int reads = previewReads; ui(() -> page.select(source.get(1))); waitRest(); ui(() -> page.select(source.get(0))); waitRest(); require(previewReads <= reads + 1, "loaded previews survive returning to same area");
        // Two requests can animate before the first response; the boundary submits only one at a time.
        View first = page.findViewWithTag("task-card:100"), second = page.findViewWithTag("task-card:101"); require(first != null && second != null, "two close fixtures in viewport");
        dismiss(first); dismiss(second); require(requests.size() == 1, "second close queued while first request pending");
        ui(() -> {
            android.graphics.Rect frame = new android.graphics.Rect(0, 0, page.getWidth(), page.getHeight()); session.sourceWindow(71, frame, "foreground fixture");
            require(session.sourceChanged(71, frame, "SubLauncherWindow"), "Home replacing content in the same window invalidates its captured title");
            require(session.sourceStateChanged(71) && !session.sourceStateChanged(72), "same-window state event retires source while unrelated window events do not");
            require(!session.sourceChanged(71, frame), "overlay focus and unrelated task removal keep the same background source");
            require(session.sourceChanged(72, frame) && session.sourceChanged(-1, new android.graphics.Rect()), "replacement or removed source window invalidates the background");
            require(session.sourceChanged(71, new android.graphics.Rect(0, 0, frame.right - 1, frame.bottom)), "same window with changed bounds invalidates captured coordinates");
            session.invalidateSource(); session.invalidateSource();
            require(!session.closingBackdropValid() && session.active(), "source invalidation is idempotent and prevents obsolete exit screenshots without closing the page");
        });
        drag(75, 0, false, false); require(strip.getChildCount() <= 4, "strip stays interactive during pending closes");
        ui(() -> { require(session.bytes() == 0 && session.taskOptics(), "source fade releases its textures but retains independent task optics"); require(page.findViewWithTag("task-carousel") == strip, "background invalidation keeps the same gesture container"); });
        checkRetiredBackgroundPixels();
        ack(true); require(requests.size() == 2 && requests.get(1).id() == 101, "next task submitted after first acknowledgement"); ack(true); waitRest(); centered("two rapid closes"); require(source.size() == 18 && empty == 0, "confirmed removals keep remaining tasks");
        View failed = center(); String failedTag = failed.getTag().toString(); dismiss(failed); ack(false); waitRest(); centered("rejected close"); require(page.findViewWithTag(failedTag) != null, "failed task remains and returns from current position");
        int size = source.size(); drag(0, -95, true, true); waitRest(); require(source.size() == size, "cancelled fling never dismisses");
        ui(() -> page.select(source.get(0))); waitRest(); int beforeRequests = requests.size();
        for (int i = 0; i < 6; i++) { dismiss(center()); SystemClock.sleep(180); instrumentation.waitForIdleSync(); ui(this::pool); }
        require(requests.size() == beforeRequests + 1, "six visible closures do not send overlapping system requests");
        for (int i = 0; i < 6; i++) ack(true);
        waitRest(); centered("six closes before slow acknowledgements"); require(source.size() == size - 6, "all six distinct queued tasks confirmed");
        drag(80, 0, false, false);
        ui(() -> { View card = center(); float before = card.getX(), beforeY = card.getY(), shape = ((ViewGroup) card).getChildAt(1).getScaleX(); page.exit(() -> { }); require(card.getX() == before && card.getY() == beforeY && ((ViewGroup) card).getChildAt(1).getScaleX() == shape, "exit freezes current pose without snapping or flattening"); });
        SystemClock.sleep(250); ui(page::cancelExit); waitRest(); centered("cancelled exit resumes centering");
        require(session.captures == 0 && session.preparations == 1 && session.bytes() == 0, "rapid closes never recapture or prepare a replacement background after source loss");
        List<RecentTasks.Task> retryFixtures = List.copyOf(source);
        ui(() -> { RecentTasks.Task last = source.get(0); source.clear(); source.add(last); data(); }); waitRest(); dismiss(center()); ack(true); SystemClock.sleep(300); require(empty == 1, "explicit successful last-task dismissal still exits exactly once");
        ui(() -> { page.onLowMemory(); page.dispose(); require(strip.getChildCount() == 0, "dispose releases mounted and recycled content"); require(session.taskReactionX() == 0 && session.taskReactionY() == 0, "dispose clears background reaction"); session.close(); require(session.bytes() == 0, "session releases background textures"); });
        source.addAll(retryFixtures);
        checkEmptyAndPreviewRetry(prefs);
        checkLatePreviewDuringClose(prefs);
        return "task-force: " + assertions + " assertions; native geometry, release capture, twenty-task reuse, serialized close fixtures, rollback and disposal PASS; previews=" + previewReads + "; entry card/background/clear peak px=" + java.util.Arrays.toString(entryPeak);
    }
    private void checkLatePreviewDuringClose(Prefs prefs) {
        List<RecentTasks.Task> sample = List.copyOf(source.subList(0, 3));
        List<java.util.function.Consumer<ShizukuBridge.Snapshot>> waiting = new ArrayList<>();
        int[] reads = {0}, submitted = {0};
        ui(() -> {
            FrameLayout root = new FrameLayout(activity); activity.setContentView(root);
            page = new RecentTasksView(activity, prefs, DockGeometry.BOTTOM, new RecentTasksView.Listener() {
                public void open(RecentTasks.Task task) { } public void clear(List<RecentTasks.Task> tasks) { } public void refresh() { } public void apps() { } public void close() { } public void reopen(RecentTasks.Task task) { }
                public void closeTask(RecentTasks.Task task) { submitted[0]++; page.data(sample, true, true, true, true, true, true, "fixture"); }
                public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { reads[0]++; waiting.add(callback); }
            });
            root.addView(page, new FrameLayout.LayoutParams(-1, -1)); page.data(sample, true, true, false, true, true, true, "fixture"); strip = page.findViewWithTag("task-carousel");
            session = new PanelGlassSession(activity, activity.getDisplay()); android.graphics.Bitmap background = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888); background.eraseColor(0xFF223344); session.fixture(background); session.attach(page, false);
        }); waitRest();
        require(reads[0] == 1, "one preview is in flight before closing begins");
        View closing = page.findViewWithTag("task-card:" + sample.get(1).id()); dismiss(closing);
        android.graphics.Bitmap late = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888);
        ui(() -> {
            waiting.remove(0).accept(new ShizukuBridge.Snapshot(late, "late fixture"));
            TaskPreviewView picture = page.findViewWithTag("task-preview:" + sample.get(0).id());
            require(!late.isRecycled() && !picture.refracting(), "late surviving preview is cached without applying optics during a close");
        }); SystemClock.sleep(200); instrumentation.waitForIdleSync();
        require(reads[0] == 1 && submitted[0] == 1, "pending close neither starts another preview read nor blocks the close submission");
        ui(() -> page.data(List.of(sample.get(0), sample.get(2)), true, true, false, true, true, true, "fixture")); waitRest();
        long previewDeadline = SystemClock.uptimeMillis() + 2000;
        while (SystemClock.uptimeMillis() < previewDeadline) {
            boolean[] shown = {false}; ui(() -> shown[0] = ((TaskPreviewView) page.findViewWithTag("task-preview:" + sample.get(0).id())).refracting());
            if (shown[0]) break; SystemClock.sleep(20);
        }
        ui(() -> { TaskPreviewView picture = page.findViewWithTag("task-preview:" + sample.get(0).id()); require(picture.refracting() && !late.isRecycled(), "cached late preview is applied after closing and replacement motion settle"); page.dispose(); session.close(); });
        require(late.isRecycled() && session.bytes() == 0, "page disposal releases late preview and background ownership");
        if (!waiting.isEmpty()) { android.graphics.Bitmap stale = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888); ui(() -> waiting.remove(0).accept(new ShizukuBridge.Snapshot(stale, "closed fixture"))); require(stale.isRecycled(), "callback after disposal cannot revive task preview pixels"); }
    }
    private void checkRetiredBackgroundPixels() throws Exception {
        View decor = activity.getWindow().getDecorView(); android.graphics.Bitmap pixels = android.graphics.Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1); int[] status = {-1}, origin = new int[2];
        ui(() -> { page.getLocationInWindow(origin); android.view.PixelCopy.request(activity.getWindow(), pixels, result -> { status[0] = result; ready.countDown(); }, main); });
        try {
            require(ready.await(3, java.util.concurrent.TimeUnit.SECONDS) && status[0] == android.view.PixelCopy.SUCCESS, "retired background hardware pixels available");
            int pixel = pixels.getPixel(origin[0] + page.getWidth() / 2, origin[1] + Ui.dp(activity, 10));
            require(Math.abs(android.graphics.Color.red(pixel) - 12) <= 2 && Math.abs(android.graphics.Color.green(pixel) - 24) <= 2 && Math.abs(android.graphics.Color.blue(pixel) - 39) <= 2, "source fade reveals the real underlying surface with basic dim instead of the captured app: " + Integer.toHexString(pixel));
        } finally { pixels.recycle(); }
    }
}
