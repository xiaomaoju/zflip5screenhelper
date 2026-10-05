package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.lang.reflect.Field;

/** Actual View lifecycle, shared header and sidebar boundary input on a disposable emulator. */
final class LauncherForceChecks {
    private final Instrumentation test;
    private Activity activity;
    private AppHubView hub;
    private LauncherForce force;
    private int assertions, closes, renderedShift;
    LauncherForceChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private void main(Runnable action) { Throwable[] failure = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new AssertionError(failure[0]); test.waitForIdleSync(); }
    private Object field(Object owner, String name) { try { Field f = owner.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    String run() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(() -> {
                Prefs prefs = new Prefs(activity); prefs.saveActions("favorites", java.util.List.of("rotation", "controls", "torch", "screenshot"));
                hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { closes++; } });
                FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(0xFF244236); root.addView(hub, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root);
                force = (LauncherForce) field(hub, "launcherForce");
            });
            SystemClock.sleep(250);
            java.util.concurrent.CountDownLatch sampled = new java.util.concurrent.CountDownLatch(1); boolean[] propagated = {false};
            main(() -> {
                require(force.x.length == 8 && LauncherForce.FROM.length == 8, "eight region nodes and eight links independent of mounted icon count");
                View header = hub.findViewWithTag("hub-force-header"), summary = hub.findViewWithTag("hub-summary"), sort = hub.findViewWithTag("hub-sort");
                require(summary.getParent() == header && sort.getParent().getParent() == header, "search, sort and both summaries share the same paint-only region");
                require(header.getScaleX() == 1 && sort.getScaleX() == 1, "header labels keep real text proportions");
                force.impulse(LauncherForce.HEADER, 0, 200); hub.wakeLauncherForce();
                hub.postDelayed(() -> { propagated[0] = force.y[LauncherForce.HEADER] != 0 && force.y[LauncherForce.CATALOG] != 0; sampled.countDown(); }, 80);
            });
            try { require(sampled.await(5, java.util.concurrent.TimeUnit.SECONDS) && propagated[0], "native clock propagates header press into its catalog"); } catch (InterruptedException error) { throw new AssertionError(error); }
            SystemClock.sleep(2200);
            main(() -> { require(!(boolean) field(hub, "launcherFramePosted"), "settled springs stop posting frames");
                AppWorkspaceView grid = hub.findViewWithTag("hub-grid"); grid.settlePage(0, false); grid.pageOffset(0, 0, Ui.dp(activity, 100)); force.reset();
                for (int move = 0; move < 120; move++) grid.pageOffset(0, 0, Ui.dp(activity, 100));
                require(force.vx[LauncherForce.GRID] == 0 && force.vx[LauncherForce.DOCK] == 0, "stationary cumulative edge MOVE cannot inject repeated impulses"); grid.settlePage(0, false);
            });
            main(() -> {
                LauncherSidebarView.Content scroll = hub.findViewWithTag("hub-sidebar-scroll"); android.view.ViewGroup items = (android.view.ViewGroup) scroll.getChildAt(0); View overflow = new View(activity); items.addView(overflow, new android.view.ViewGroup.LayoutParams(-1, Ui.dp(activity, 500))); scroll.requestLayout();
            });
            SystemClock.sleep(150);
            android.graphics.Rect glyph = new android.graphics.Rect();
            main(() -> { LauncherSidebarView.Content scroll = hub.findViewWithTag("hub-sidebar-scroll"); android.view.ViewGroup items = (android.view.ViewGroup) scroll.getChildAt(0), cell = (android.view.ViewGroup) items.getChildAt(1); require(cell.getChildAt(0).getGlobalVisibleRect(glyph), "sidebar glyph is actually visible before boundary drag"); });
            android.graphics.Bitmap before = test.getUiAutomation().takeScreenshot();
            main(() -> {
                LauncherSidebarView.Content scroll = hub.findViewWithTag("hub-sidebar-scroll"); require(field(scroll, "force") == force, "sidebar scroll uses the shared region graph"); scroll.scrollTo(0, 0); touch(scroll, MotionEvent.ACTION_DOWN, 20); touch(scroll, MotionEvent.ACTION_MOVE, 110);
                require(force.ty[LauncherForce.SIDEBAR] > 0, "upper list boundary loads the sidebar rubber spring: target=" + force.ty[LauncherForce.SIDEBAR] + ", scroll=" + scroll.getScrollY() + ", pull=" + field(scroll, "pull") + ", height=" + scroll.getHeight() + ", hubY=" + hub.getTranslationY()); require(hub.getTranslationY() == 0 && closes == 0, "sidebar pull cannot dismiss or move the whole launcher");
            });
            SystemClock.sleep(2200); android.graphics.Bitmap held = test.getUiAutomation().takeScreenshot();
            try { int shift = glyphShift(before, held, glyph, Ui.dp(activity, 10)); renderedShift = shift; require(shift >= Ui.dp(activity, 2), "hardware-rendered sidebar glyph moves under held rubber pull: shift=" + shift + ", spring=" + force.y[LauncherForce.SIDEBAR]); } finally { before.recycle(); held.recycle(); }
            main(() -> { require(!(boolean) field(hub, "launcherFramePosted"), "held visible sidebar reaches equilibrium and stops frames"); LauncherSidebarView.Content scroll = hub.findViewWithTag("hub-sidebar-scroll"); touch(scroll, MotionEvent.ACTION_UP, 110); });
            SystemClock.sleep(2000);
            main(() -> { require(force.y[LauncherForce.SIDEBAR] == 0 && !(boolean) field(hub, "launcherFramePosted"), "released sidebar returns to its anchor and stops");
                LauncherSidebarView.Content scroll = hub.findViewWithTag("hub-sidebar-scroll"); scroll.scrollTo(0, Integer.MAX_VALUE); touch(scroll, MotionEvent.ACTION_DOWN, 110); touch(scroll, MotionEvent.ACTION_MOVE, 20);
                require(force.ty[LauncherForce.SIDEBAR] < 0, "lower list boundary loads the opposite rubber spring"); touch(scroll, MotionEvent.ACTION_MOVE, 130);
                require(force.ty[LauncherForce.SIDEBAR] == 0, "reversing past the edge releases rubber instead of loading an interior pull"); touch(scroll, MotionEvent.ACTION_UP, 130);
                force.impulse(LauncherForce.GRID, 200, 100); hub.wakeLauncherForce(); long time = SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(time, time, MotionEvent.ACTION_CANCEL, 20, 20, 0); hub.dispatchTouchEvent(cancel); cancel.recycle(); require(force.vx[LauncherForce.GRID] == 0, "cancel discards stale page momentum");
                force.impulse(LauncherForce.GRID, 120, 80); hub.wakeLauncherForce(); ((android.view.ViewGroup) hub.getParent()).removeView(hub);
                require(!(boolean) field(hub, "launcherFramePosted") && force.vx[LauncherForce.GRID] == 0, "detach cannot restart region frames through content restoration");
                hub.dispose(); require(!(boolean) field(hub, "launcherFramePosted"), "dispose removes the sole region clock");
                LauncherSidebarView sidebar = hub.findViewWithTag("hub-sidebar"); require(!sidebar.animating(), "dispose removes delayed settings split");
            });
            return "PASS: launcher-force; " + assertions + " assertions; native region input, rest and cancellation; sidebar glyph shift=" + renderedShift + "px; physical Samsung frame timing remains unverified";
        } finally { main(() -> { if (hub != null) hub.dispose(); activity.finish(); }); }
    }
    private int glyphShift(android.graphics.Bitmap before, android.graphics.Bitmap after, android.graphics.Rect area, int range) {
        long best = Long.MAX_VALUE; int matched = 0, pixels = 0;
        for (int shift = 0; shift <= range; shift++) { long error = 0; pixels = 0;
            for (int y = area.top; y < area.bottom && y + shift < after.getHeight(); y++) for (int x = area.left; x < area.right; x++) { int a = before.getPixel(x, y); if (android.graphics.Color.red(a) + android.graphics.Color.green(a) + android.graphics.Color.blue(a) < 600) continue; int b = after.getPixel(x, y + shift); error += Math.abs(android.graphics.Color.red(a) - android.graphics.Color.red(b)) + Math.abs(android.graphics.Color.green(a) - android.graphics.Color.green(b)) + Math.abs(android.graphics.Color.blue(a) - android.graphics.Color.blue(b)); pixels++; }
            if (pixels > 0 && error < best) { best = error; matched = shift; }
        }
        require(pixels > 10, "hardware screenshot contains the sidebar glyph template"); return matched;
    }
    private void touch(LauncherSidebarView.Content scroll, int action, float yDp) {
        int[] origin = new int[2], root = new int[2]; scroll.getLocationOnScreen(origin); hub.getLocationOnScreen(root); long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, action, origin[0] + scroll.getWidth() / 2f - root[0], origin[1] + Ui.dp(activity, yDp) - root[1], 0); hub.dispatchTouchEvent(event); event.recycle();
    }
}
