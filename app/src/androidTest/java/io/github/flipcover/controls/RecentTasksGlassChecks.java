package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Emulator-only rendering of production task materials, with synthetic task snapshots. */
final class RecentTasksGlassChecks {
    private final Instrumentation test;
    private final int rotation;
    private Activity activity;
    private FrameLayout root;
    private AppHubView hub;
    private RecentTasksView page;
    private PanelGlassSession session;
    private final List<Bitmap> snapshots = new ArrayList<>();
    private final List<java.util.function.Consumer<ShizukuBridge.Snapshot>> waiting = new ArrayList<>();
    private boolean deferSnapshots, managedEntrance;
    private int assertions, changes, requests, clearRequests, closeRequests;
    private long revealRequestMillis;
    RecentTasksGlassChecks(Instrumentation test, int rotation) { this.test = test; this.rotation = rotation; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private void idle() { test.waitForIdleSync(); SystemClock.sleep(280); test.waitForIdleSync(); }
    String run() throws Exception {
        if (rotation >= 0) require(test.getUiAutomation().setRotation(rotation), "rotation request accepted");
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        main(() -> { activity.getWindow().setDecorFitsSystemWindows(false); activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); }); idle();
        require(rotation < 0 || activity.getDisplay().getRotation() == rotation, "actual display rotation matches requested orientation");
        Prefs prefs = new Prefs(activity); main(() -> prefs.data.edit().clear().commit());
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); require(apps.size() >= 3, "installed label and icon fixtures");
        List<RecentTasks.Task> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) { AppCatalogCache.Entry app = apps.get(i % apps.size()); tasks.add(new RecentTasks.Task(200 + i, 2, 0, app.id().substring(4), app.packageName(), false)); }
        try {
            main(() -> {
                root = new FrameLayout(activity); root.setBackgroundColor(0xFF1A2037);
                hub = new AppHubView(activity, prefs, new AppHubView.Listener() {
                    public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { closeRequests++; }
                    public boolean managesTaskEntrance() { return managedEntrance; }
                    public void taskPageChanged(RecentTasksView next) { changes++; if (session != null) { session.close(); session = null; } page = next; if (page != null && root.getWidth() > 0) insetPage(); if (page != null && managedEntrance) hub.prepareTaskEntrance(); }
                    public void clearRecents(List<RecentTasks.Task> tasks) { clearRequests++; }
                    public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) {
                        requests++; if (deferSnapshots) { waiting.add(callback); return; } Bitmap bitmap = screen(task.id() - 200); snapshots.add(bitmap); callback.accept(new ShizukuBridge.Snapshot(bitmap, "synthetic"));
                    }
                });
                root.addView(hub, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root);
                hub.recentCapabilities(true, true, true); hub.recentResult(tasks, null); hub.showTasks(true);
            }); idle();
            main(this::insetPage); idle();
            require(changes == 1 && page != null, "task entry emits exactly one material lifecycle event");
            require(page.findViewWithTag("tasks-toolbar") == null && page.findViewWithTag("tasks-apps") == null && page.findViewWithTag("tasks-close") == null, "task page has no top toolbar or header actions");
            for (String tag : new String[]{"tasks-clear"}) {
                View button = page.findViewWithTag(tag), slot = (View) button.getParent();
                require(slot instanceof PanelActionSlot && slot.getWidth() == Ui.dp(activity, PanelUi.SLOT) && slot.getHeight() == Ui.dp(activity, PanelUi.SLOT), "action uses the same 36dp control center slot: " + tag);
                require(Math.abs(button.getWidth() * button.getScaleX() - Ui.dp(activity, PanelUi.SLOT) * .8f) < 1, "visible action diameter matches 28.8dp control center artwork: " + tag);
            }
            require(requests == 3, "only three visible tasks request previews");
            View clearSlot = (View) page.findViewWithTag("tasks-clear").getParent();
            require(Math.abs(clearSlot.getX() + clearSlot.getWidth() / 2f - ((View) clearSlot.getParent()).getWidth() / 2f) < 1, "compact clear action is horizontally centered");
            main(() -> tapSlot(clearSlot)); require(clearRequests == 1, "outer corner of compact action remains clickable");
            require(closeRequests == 0, "clear action is not treated as a blank tap"); checkBlankTaps();
            Rect geometry = bounds(page.findViewWithTag("task-card:200"));
            attachGlass(); idle();
            require(page.getBackground() instanceof GlassSurface, "task page uses shared production glass");
            require(((GlassSurface) page.getBackground()).role == GlassSurface.Role.NOTIFICATION_PAGE, "full page uses notification transparency and corner geometry");
            require(page.getWidth() == root.getWidth() && page.getHeight() == root.getHeight(), "background covers the full host independently of content insets");
            require(page.findViewWithTag("task-card:200").getBackground() == null, "no nested outer glass card behind the app preview");
            require(geometry.equals(bounds(page.findViewWithTag("task-card:200"))), "glass keeps card position and dimensions");
            TaskPreviewView first = page.findViewWithTag("task-preview:200"); require(first.refracting(), "visible snapshot uses hardware convex optics");
            Rect previewBounds = bounds(first); Bitmap cornerPixels = pixels();
            int corner = cornerPixels.getPixel(previewBounds.left + Ui.dp(activity, 10), previewBounds.top + 2), floor = cornerPixels.getPixel(previewBounds.left - 1, previewBounds.top + 2); cornerPixels.recycle();
            require(Math.abs(Color.red(corner) - Color.red(floor)) < 8 && Math.abs(Color.blue(corner) - Color.blue(floor)) < 8, "preview uses the page-sized corner arc, not the tiny proportionally scaled corner");
            capture("tasks-glass");
            View rippleCard = page.findViewWithTag("task-card:201"), ripplePreview = page.findViewWithTag("task-preview:201");
            Rect rippleBounds = bounds(ripplePreview); Bitmap beforePress = pixels();
            main(() -> { rippleCard.setPressed(true); ripplePreview.jumpDrawablesToCurrentState(); });
            SystemClock.sleep(450); test.waitForIdleSync(); Bitmap afterPress = pixels();
            for (int x : new int[]{rippleBounds.left + Ui.dp(activity, 8), rippleBounds.right - Ui.dp(activity, 8)}) {
                int y = rippleBounds.top + rippleBounds.height() / 5;
                require(Color.red(afterPress.getPixel(x, y)) > Color.red(beforePress.getPixel(x, y)) + 5, "press highlight covers rectangular preview edges over the snapshot");
            }
            capture("tasks-pressed"); beforePress.recycle(); afterPress.recycle();
            main(() -> rippleCard.setPressed(false)); idle();

            Rect pullingCard = bounds(page.findViewWithTag("task-card:200")); long pullStart = SystemClock.uptimeMillis();
            main(() -> {
                pageEvent(pullStart, android.view.MotionEvent.ACTION_DOWN, pullingCard.centerX(), pullingCard.centerY());
                pageEvent(pullStart, android.view.MotionEvent.ACTION_MOVE, pullingCard.centerX(), pullingCard.centerY() - Ui.dp(activity, 24));
                pageEvent(pullStart, android.view.MotionEvent.ACTION_MOVE, pullingCard.centerX(), pullingCard.centerY() - Ui.dp(activity, 72));
            });
            require(first.getScaleX() < .99f && first.getScaleY() > 1.02f, "upward drag narrows and stretches the glass lens before release"); capture("tasks-pull");
            main(() -> pageEvent(pullStart, android.view.MotionEvent.ACTION_CANCEL, pullingCard.centerX(), pullingCard.centerY() - Ui.dp(activity, 72)));
            long reboundDeadline = SystemClock.uptimeMillis() + 2000;
            while (SystemClock.uptimeMillis() < reboundDeadline) {
                boolean[] resting = {false}; main(() -> resting[0] = first.getScaleX() == 1 && first.getScaleY() == 1 && page.findViewWithTag("task-card:200").getTranslationY() == 0);
                if (resting[0]) break; SystemClock.sleep(20); test.waitForIdleSync();
            }
            require(first.getScaleX() == 1 && first.getScaleY() == 1 && page.findViewWithTag("task-card:200").getTranslationY() == 0, "cancel returns lens and card to exact resting size and position");

            main(() -> {
                pageEvent(pullStart, android.view.MotionEvent.ACTION_DOWN, pullingCard.centerX(), pullingCard.centerY());
                pageEvent(pullStart, android.view.MotionEvent.ACTION_MOVE, pullingCard.centerX(), pullingCard.centerY() - Ui.dp(activity, 24));
                pageEvent(pullStart, android.view.MotionEvent.ACTION_MOVE, pullingCard.centerX(), pullingCard.centerY() - Ui.dp(activity, 72));
                View carousel = page.findViewWithTag("task-carousel"); carousel.layout(carousel.getLeft(), carousel.getTop(), carousel.getRight() - 2, carousel.getBottom());
                pageEvent(pullStart, android.view.MotionEvent.ACTION_UP, pullingCard.centerX(), pullingCard.centerY() - Ui.dp(activity, 72));
                require(first.getScaleX() == 1 && first.getScaleY() == 1 && clearRequests == 1, "a size change cancels the old drag and resets elasticity without closing"); carousel.requestLayout();
            }); idle();
            CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs;
            WindowManager.LayoutParams layout = new WindowManager.LayoutParams(); layout.flags = WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = .3f;
            main(() -> owner.applyHubBlur(hub, layout, true));
            require((layout.flags & (WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND)) == 0 && layout.dimAmount == 0 && layout.getBlurBehindRadius() == 0, "task glass has no duplicate window blur or dim");
            SystemClock.sleep(400); test.waitForIdleSync(); int draws = session.draws; SystemClock.sleep(1000); test.waitForIdleSync(); require(session.draws - draws < 5, "idle task glass has no frame loop");
            main(() -> page.findViewWithTag("task-card:200").performAccessibilityAction(io.github.flipcover.controls.R.id.action_task_lock, null)); idle();
            require(page.findViewWithTag("task-lock:200").isShown() && first.refracting(), "locking preserves lens and overlays a clear lock badge"); capture("tasks-locked");
            int prior = requests; List<Bitmap> old = new ArrayList<>(snapshots);
            main(() -> page.select(tasks.get(5))); idle();
            require(requests == prior + 3, "paging requests only the new visible trio");
            for (Bitmap bitmap : old) require(!bitmap.isRecycled(), "loaded snapshots remain cached when they leave the viewport");
            int cachedRequests = requests;
            main(() -> page.select(tasks.get(0))); idle();
            require(requests == cachedRequests && ((TaskPreviewView) page.findViewWithTag("task-preview:200")).refracting(), "scrolling back restores cached previews without a new system read");
            main(() -> page.select(tasks.get(5))); idle(); require(requests == cachedRequests, "repeated navigation reuses the same page cache");
            require(((TaskPreviewView) page.findViewWithTag("task-preview:205")).refracting(), "new cards retain glass without reopening the session");
            field(owner, "taskGlass", session); field(owner, "taskSurface", page); field(owner, "hubHost", root);
            main(owner::onLowMemory); idle(); require(session.bytes() == 0, "service low-memory callback releases the owned task background texture");
            require(!((TaskPreviewView) page.findViewWithTag("task-preview:205")).refracting(), "closed session disables snapshot optics"); capture("tasks-fallback");
            main(() -> page.performAccessibilityAction(R.id.action_task_apps, null)); idle(); require(changes == 2 && page == null && hub.findViewWithTag("hub-grid").isShown(), "return action remains available and restores app surface");
            for (Bitmap bitmap : snapshots) require(bitmap.isRecycled(), "leaving task page releases all task bitmaps");
            main(() -> owner.applyHubBlur(hub, layout, true)); require((layout.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0, "app center regains its original blur");
            main(() -> hub.showTasks(true)); idle(); attachGlass(true); idle();
            TaskPreviewView lightPreview = page.findViewWithTag("task-preview:200"); Rect lightBounds = bounds(lightPreview); Bitmap lightPixels = pixels();
            int plate = lightPixels.getPixel(lightBounds.left + 1, lightBounds.top + 1); lightPixels.recycle();
            require(Math.abs(Color.red(plate) - 194) < 5, "preview corners reveal the undistorted notification floor, not another card"); capture("tasks-light");
            main(() -> { hub.recentCapabilities(true, false, true); hub.recentResult(tasks, null); }); idle();
            View disabledClear = page.findViewWithTag("tasks-clear");
            require(!disabledClear.isEnabled() && disabledClear.getAlpha() == 1, "unavailable clear action stays visible instead of fading the entire glass circle");
            Rect buttonBounds = bounds(disabledClear); Bitmap buttonPixels = pixels();
            int buttonX = buttonBounds.left + Math.round(disabledClear.getWidth() * disabledClear.getScaleX() / 2), buttonY = buttonBounds.top + Math.round(disabledClear.getHeight() * disabledClear.getScaleY() / 2);
            int centerPixel = buttonPixels.getPixel(buttonX, buttonY);
            int buttonFloor = buttonPixels.getPixel(buttonX + Ui.dp(activity, 6), buttonY);
            buttonPixels.recycle();
            capture("tasks-clear-disabled"); require(Color.red(centerPixel) > Color.red(buttonFloor) + 45, "disabled X keeps visible contrast against its glass plate on a white background: " + Color.red(centerPixel) + "/" + Color.red(buttonFloor));
            main(() -> { hub.recentCapabilities(true, true, true); hub.recentResult(tasks, null); });
            main(() -> page.onLowMemory()); idle();
            for (Bitmap bitmap : snapshots) require(bitmap.isRecycled(), "memory trim releases visible preview pixels");
            require(!((TaskPreviewView) page.findViewWithTag("task-preview:200")).refracting(), "icon fallback never magnifies the icon");
            int trimmedRequests = requests; main(() -> hub.recentResult(tasks, null)); idle(); require(requests == trimmedRequests, "memory trim does not immediately reload previews");
            main(() -> hub.showTasks(false)); checkRevealRequests(tasks); checkExitEffects(); checkOptics();
            return "PASS: " + assertions + " task glass assertions; rotation=" + rotation + "; reveal-to-request=" + revealRequestMillis + "ms; production hardware rendering, crop/convex pixels, lifecycle, bounded previews";
        } finally { main(() -> { if (session != null) session.close(); if (hub != null) hub.dispose(); if (root != null) root.removeAllViews(); }); }
    }
    private void attachGlass() { attachGlass(false); }
    private void checkExitEffects() throws Exception {
        main(() -> hub.showTasks(true)); idle();
        if (session != null) main(() -> session.close());
        attachGlass(); idle();
        long bytes = session.bytes(); int captures = session.captures, preparations = session.preparations;
        int[] completed = {0}; android.animation.ValueAnimator[] exit = {null};
        main(() -> {
            hub.dismissForTaskClear(() -> { completed[0]++; session.close(); });
            try { java.lang.reflect.Field field = RecentTasksView.class.getDeclaredField("exitAnimation"); field.setAccessible(true); exit[0] = (android.animation.ValueAnimator) field.get(page); }
            catch (Exception error) { throw new AssertionError(error); }
            exit[0].pause(); exit[0].setCurrentPlayTime(60);
            View slot = (View) page.findViewWithTag("tasks-clear").getParent();
            require(slot.getScaleX() > 1 && slot.getAlpha() > 0 && slot.getAlpha() < 1, "X elastically pops and fades during the dissolve");
            require(page.findViewWithTag("task-carousel").getAlpha() == 0 && hub.getTranslationY() == 0, "task cards disappear first without moving the full screenshot");
            require(session.bytes() == bytes && session.captures == captures && session.preparations == preparations, "dissolve borrows the existing Gaussian capture without more pixels or preparation");
        });
        capture("tasks-exit-pop");
        main(() -> exit[0].setCurrentPlayTime(120)); capture("tasks-exit-blur");
        main(() -> exit[0].end()); idle();
        require(completed[0] == 1 && session.bytes() == 0, "exit completion releases the texture once"); capture("tasks-exit-gone");
        main(() -> hub.reopen()); idle();
        require(((View) page.findViewWithTag("tasks-clear").getParent()).getScaleX() == 1 && page.findViewWithTag("task-carousel").getAlpha() == 1, "reopening restores resting controls and cancels the old exit");
        main(() -> hub.showTasks(false));
    }
    private void checkBlankTaps() {
        float x = page.getWidth() / 2f, y = 20;
        main(() -> pageGesture(x, y, x, y, 40, false)); require(closeRequests == 1, "one blank tap invokes the existing downward dismissal exactly once");
        main(() -> pageGesture(x, y, x + 60, y, 80, false)); require(closeRequests == 1, "moving across blank space does not become a tap dismissal");
        main(() -> pageGesture(x, y, x, y, 40, true)); require(closeRequests == 1, "cancelled blank touch never dismisses");
        main(() -> pageGesture(x, y, x, y, android.view.ViewConfiguration.getLongPressTimeout() + 50, false)); require(closeRequests == 1, "long hold is not a blank tap");
        Rect card = bounds(page.findViewWithTag("task-card:200"));
        main(() -> pageGesture(card.centerX(), card.centerY(), card.centerX(), card.centerY(), 40, false)); require(closeRequests == 1, "tapping a task remains exclusive to task restoration");
    }
    private void pageGesture(float x, float y, float endX, float endY, long duration, boolean cancel) {
        long now = SystemClock.uptimeMillis();
        for (int index = 0; index < 3; index++) {
            int action = index == 0 ? android.view.MotionEvent.ACTION_DOWN : index == 1 ? android.view.MotionEvent.ACTION_MOVE : cancel ? android.view.MotionEvent.ACTION_CANCEL : android.view.MotionEvent.ACTION_UP;
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now + duration * index / 2, action, index == 0 ? x : endX, index == 0 ? y : endY, 0); page.dispatchTouchEvent(event); event.recycle();
        }
    }
    private void pageEvent(long down, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); page.dispatchTouchEvent(event); event.recycle();
    }
    private void insetPage() { page.safeArea(new DockGeometry.Box(0, 36, root.getWidth(), Math.max(1, root.getHeight() - 110)), new DockGeometry.Box(0, 0, root.getWidth(), root.getHeight())); }
    private void checkRevealRequests(List<RecentTasks.Task> tasks) {
        int before = requests;
        main(() -> { root.setVisibility(View.INVISIBLE); deferSnapshots = true; managedEntrance = true; hub.showTasks(true); }); idle();
        require(hub.getTranslationY() >= hub.getHeight(), "prepared task entrance stays below the display until capture completion");
        require(requests == before, "hidden capture preparation does not start snapshot requests");
        long revealed = SystemClock.uptimeMillis();
        main(() -> { root.setVisibility(View.VISIBLE); hub.revealTasks(page); require(hub.getTranslationY() >= hub.getHeight(), "task entrance begins fully below the screen instead of at a partial-height offset"); });
        long deadline = revealed + 500;
        while (requests == before && SystemClock.uptimeMillis() < deadline) { SystemClock.sleep(10); test.waitForIdleSync(); }
        revealRequestMillis = SystemClock.uptimeMillis() - revealed;
        require(requests == before + 1 && revealRequestMillis < 500, "visible page requests immediately without waiting for the 3-second task refresh");
        SystemClock.sleep(140); test.waitForIdleSync();
        require(hub.getTranslationY() > 0 && hub.getAlpha() == 1, "thumbnail loading begins while the 500ms task entrance is still sliding upward");
        Bitmap early = screen(0); snapshots.add(early);
        main(() -> { root.setVisibility(View.INVISIBLE); waiting.remove(0).accept(new ShizukuBridge.Snapshot(early, "arrived while preparing")); }); idle();
        require(!early.isRecycled() && requests == before + 1, "valid in-flight snapshot survives temporary capture invisibility");
        main(() -> { deferSnapshots = false; root.setVisibility(View.VISIBLE); page.previewsVisible(); }); idle();
        require(requests == before + 3, "hidden callback clears pending state and remaining two previews start on reveal");
        SystemClock.sleep(300); test.waitForIdleSync();
        require(hub.getTranslationY() == 0 && hub.getAlpha() == 1, "task entrance settles at its original geometry");
        main(() -> { deferSnapshots = true; page.select(tasks.get(5)); }); idle();
        require(requests == before + 4 && waiting.size() == 1, "new uncached tasks still read snapshots serially");
        Bitmap offscreen = screen(3); snapshots.add(offscreen);
        main(() -> { page.select(tasks.get(0)); waiting.remove(0).accept(new ShizukuBridge.Snapshot(offscreen, "arrived after scrolling away")); }); idle();
        require(!offscreen.isRecycled() && requests == before + 4, "same-page late result is cached even after scrolling out of view");
        main(() -> { deferSnapshots = false; page.select(tasks.get(5)); }); idle();
        require(requests == before + 6, "returning to the late cached task only reads the two remaining snapshots");
        main(() -> hub.showTasks(false)); require(early.isRecycled() && offscreen.isRecycled(), "closing task page releases visible and offscreen cached snapshots");
        int closedRequests = requests;
        main(() -> { managedEntrance = false; hub.showTasks(true); }); idle(); require(requests == closedRequests + 3, "new task-page session reloads after the old page cache was cleared");
        main(() -> hub.showTasks(false));
    }
    private void attachGlass(boolean light) {
        session = new PanelGlassSession(activity, activity.getDisplay());
        Bitmap backdrop = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(backdrop); Paint paint = new Paint();
        paint.setShader(new android.graphics.LinearGradient(0, 0, backdrop.getWidth(), backdrop.getHeight(), new int[]{0xFF405374, 0xFF212E47, 0xFF694861}, new float[]{0, .58f, 1}, android.graphics.Shader.TileMode.CLAMP)); canvas.drawRect(0, 0, backdrop.getWidth(), backdrop.getHeight(), paint);
        if (light) canvas.drawColor(Color.WHITE);
        session.fixture(backdrop); main(() -> session.attach(page, false));
    }
    private void checkOptics() throws Exception {
        TaskPreviewView preview = new TaskPreviewView(activity); Bitmap gradient = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 256; y++) for (int x = 0; x < 256; x++) gradient.setPixel(x, y, Color.rgb(x, y, 90));
        main(() -> { root.removeAllViews(); FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(360, 420, android.view.Gravity.CENTER); root.addView(preview, bounds); preview.content(gradient, null); }); idle();
        Rect rect = bounds(preview); Bitmap plain = pixels();
        main(() -> preview.glass(true)); idle(); Bitmap lens = pixels();
        int center = rect.centerX(), y = rect.centerY(), dx = 30;
        int plainDelta = Color.red(plain.getPixel(center + dx, y)) - Color.red(plain.getPixel(center - dx, y));
        int lensDelta = Color.red(lens.getPixel(center + dx, y)) - Color.red(lens.getPixel(center - dx, y));
        require(lensDelta > 0 && lensDelta < plainDelta * .96f && lensDelta > plainDelta * .75f, "convex image is upright and magnifies central detail: " + plainDelta + "/" + lensDelta);
        int cropX = rect.left + 90;
        int expectedRed = Math.round(128 + (90 - 180) / (420f / 256));
        require(Math.abs(Color.red(plain.getPixel(cropX, y)) - expectedRed) < 4, "fallback uses undistorted center crop rather than stretching or letterboxing");
        require(plain.getPixel(rect.left + 2, rect.top + 2) == lens.getPixel(rect.left + 2, rect.top + 2), "lens obeys capsule corner clipping");
        int edgeX = Ui.dp(activity, 6);
        float t = (180f - edgeX) / 180f, scale = (1f / 1.15f) + (1 - 1f / 1.15f) * t * t * (3 - 2 * t);
        int undistorted = Math.round(128 + (edgeX - 180) * scale / (420f / 256));
        require(Color.red(lens.getPixel(rect.left + edgeX, y)) > undistorted + 5, "thick edge substantially displaces detail inward instead of only outlining the card");
        int priorRed = -1;
        for (int offset = Ui.dp(activity, 4); offset <= Ui.dp(activity, 22); offset += 2) {
            int red = Color.red(lens.getPixel(rect.left + offset, y)); require(red >= priorRed - 1, "strong edge mapping stays upright without a reversed sampling seam"); priorRed = red;
        }
        capture("convex-gradient"); plain.recycle(); lens.recycle();
        Bitmap flat = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888); flat.eraseColor(Color.rgb(32, 56, 72));
        main(() -> preview.content(flat, null)); idle(); Bitmap clearLens = pixels();
        int centerColor = clearLens.getPixel(rect.centerX(), rect.centerY()), innerColor = clearLens.getPixel(rect.left + Ui.dp(activity, 22), rect.centerY());
        require(Math.abs(Color.red(centerColor) - 32) <= 1 && Math.abs(Color.green(centerColor) - 56) <= 1, "clear lens does not add a milky white wash to flat content");
        require(Math.abs(Color.red(innerColor) - 32) <= 1 && Math.abs(Color.green(innerColor) - 56) <= 1, "interior outside the thick optical rim preserves the original color");
        int rimColor = clearLens.getPixel(rect.left + Math.max(1, Ui.dp(activity, .55f)), rect.centerY());
        require(Color.red(rimColor) > Color.red(innerColor) + 8, "fine specular edge remains distinct from the clear interior");
        for (int edgeY : new int[] {rect.top, rect.bottom - 1}) {
            int inward = edgeY == rect.top ? 1 : -1;
            int arcColor = clearLens.getPixel(rect.centerX(), edgeY + inward * Ui.dp(activity, .85f));
            require(Color.red(arcColor) > Color.red(centerColor) + 12, "thin top and bottom reflections remain visible");
            int bevelColor = clearLens.getPixel(rect.centerX(), edgeY + inward * Ui.dp(activity, 3));
            require(Math.abs(Color.red(bevelColor) - Color.red(centerColor)) <= 4, "top and bottom bevels preserve content instead of a frosted white band");
        }
        capture("clear-lens-rim"); clearLens.recycle();
        main(() -> { preview.glass(false); preview.content(null, Ui.icon(activity, R.drawable.ic_ms_apps, Ui.ACCENT)); }); flat.recycle(); idle(); require(!preview.refracting(), "missing snapshot restores centered icon drawing");
        main(() -> root.removeView(preview)); gradient.recycle();
    }
    private static Rect bounds(View view) { int[] point = new int[2]; view.getLocationOnScreen(point); return new Rect(point[0], point[1], point[0] + view.getWidth(), point[1] + view.getHeight()); }
    private static void field(CoverService owner, String name, Object value) throws Exception { java.lang.reflect.Field field = CoverService.class.getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
    private static void tapSlot(View slot) {
        long now = SystemClock.uptimeMillis(); android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 1, 1, 0), up = android.view.MotionEvent.obtain(now, now + 30, android.view.MotionEvent.ACTION_UP, 1, 1, 0);
        slot.dispatchTouchEvent(down); slot.dispatchTouchEvent(up); down.recycle(); up.recycle();
    }
    private Bitmap screen(int index) {
        int width = index % 2 == 0 ? 160 : 256, height = index % 2 == 0 ? 256 : 160;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(bitmap); Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] colors = {0xFFEAF2FA, 0xFF122534, 0xFFF1EDE8}; canvas.drawColor(colors[index % 3]);
        paint.setColor(index % 3 == 1 ? 0xFFB7DFFE : 0xFF244A69); paint.setTextSize(15); canvas.drawText(new String[]{"Messages", "Weather", "Notes"}[index % 3], 12, 30, paint);
        paint.setColor(index % 3 == 1 ? 0xFF4C7991 : 0xFFD1DFEA);
        for (int y = 50; y < height; y += 40) canvas.drawRoundRect(12, y, width - 12, y + 28, 8, 8, paint);
        paint.setColor(index % 3 == 1 ? 0xFFDEEFFF : 0xFF6C98B4); paint.setStrokeWidth(3);
        for (int y = 60; y < height; y += 40) canvas.drawLine(22, y, width - 30, y, paint);
        return bitmap;
    }
    private void capture(String name) throws Exception {
        Bitmap bitmap = pixels();
        File directory = new File(activity.getFilesDir(), "recent-tasks-glass"); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + "-r" + rotation + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); } finally { bitmap.recycle(); }
    }
    private Bitmap pixels() throws Exception {
        View decor = activity.getWindow().getDecorView(); Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1); int[] status = {-1};
        android.view.PixelCopy.request(activity.getWindow(), bitmap, result -> { status[0] = result; ready.countDown(); }, new android.os.Handler(android.os.Looper.getMainLooper()));
        require(ready.await(3, java.util.concurrent.TimeUnit.SECONDS) && status[0] == android.view.PixelCopy.SUCCESS, "hardware window pixels available without emulator system overlays");
        return bitmap;
    }
}
