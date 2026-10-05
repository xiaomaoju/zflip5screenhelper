package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Deterministic native animation frames and cancellation on a disposable emulator. */
final class HubMotionChecks {
    private final Instrumentation test;
    private Activity activity;
    private AppHubView hub;
    private FrameLayout root;
    private int assertions, closes, launches;
    HubMotionChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private void main(Runnable action) { Throwable[] failure = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new AssertionError(failure[0]); test.waitForIdleSync(); }
    private Object field(Object owner, String name) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void field(Object owner, String name, Object value) { try { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private ValueAnimator motion() { return (ValueAnimator) field(hub, "revealAnimation"); }
    private boolean near(float a, float b) { return Math.abs(a - b) < .01f; }
    String run() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            Prefs prefs = new Prefs(activity); prefs.data.edit().clear().commit(); java.util.List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); prefs.saveHubPins(java.util.List.of(apps.get(0).id()));
            main(() -> {
                hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { launches++; } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { closes++; } });
                hub.setExpanded(false); hub.prepareEntrance();
                require(ValueAnimator.areAnimatorsEnabled() ? hub.getAlpha() == 0 && hub.getTranslationY() > 0 : hub.getAlpha() == 1, "first frame is prepared before the window is shown");
                root = new FrameLayout(activity); root.setBackgroundColor(0xFF172234); FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(-1, -1); bounds.setMargins(8, 72, 8, 80); root.addView(hub, bounds); activity.setContentView(root);
            });
            main(() -> {
                hub.reopen();
                if (!ValueAnimator.areAnimatorsEnabled()) {
                    require(motion() == null && hub.getAlpha() == 1 && near(hub.getTranslationY(), 0), "disabled animations show final frame immediately");
                    hub.dismiss(() -> closes++); require(closes == 1 && motion() == null && hub.getAlpha() == 0, "disabled animations finish dismissal once");
                    hub.reopen(); require(!hub.closing() && hub.getAlpha() == 1, "disabled animations can reopen without a stuck exit"); return;
                }
                ValueAnimator entrance = motion(); require(entrance != null && entrance.getDuration() == 340, "entry uses a bounded 340ms animator"); entrance.pause();
                entrance.setCurrentPlayTime(0); frame("entry-000"); float start = hub.getTranslationY();
                entrance.setCurrentPlayTime(85); frame("entry-085"); require(hub.getTranslationY() > 0 && hub.getTranslationY() < start && hub.getAlpha() > 0, "entry slides upward and becomes visible");
                entrance.setCurrentPlayTime(221); frame("entry-221"); require(hub.getTranslationY() < 0 && hub.getTranslationY() > -Ui.dp(activity, 5) && hub.getAlpha() == 1, "entry overshoots by less than five dp without alpha overshoot");
                entrance.end(); frame("entry-340"); require(motion() == null && near(hub.getTranslationY(), 0) && hub.getAlpha() == 1, "spring settles exactly at the original Dock position");
                View dock = hub.findViewWithTag("hub-dock"); int left = dock.getLeft(), top = dock.getTop(); hub.setExpanded(true); hub.setExpanded(false);
                require(hub.findViewWithTag("hub-dock") == dock && dock.getLeft() == left && dock.getTop() == top, "catalog toggle retains Dock identity and settled geometry");
                View pin = ((android.view.ViewGroup) dock).getChildAt(1); int[] origin = new int[2], position = new int[2]; hub.getLocationOnScreen(origin); pin.getLocationOnScreen(position);
                float x = position[0] - origin[0] + pin.getWidth() / 2f, touchY = position[1] - origin[1] + pin.getHeight() / 2f; long pressed = android.os.SystemClock.uptimeMillis();
                MotionEvent held = MotionEvent.obtain(pressed, pressed, MotionEvent.ACTION_DOWN, x, touchY, 0); hub.dispatchTouchEvent(held); held.recycle();
                hub.dismiss(() -> closes++); require(!pin.isPressed(), "dismiss cancels already-pressed application"); motion().pause(); motion().setCurrentPlayTime(90); hub.reopen(); motion().end();
                MotionEvent released = MotionEvent.obtain(pressed, pressed + 100, MotionEvent.ACTION_UP, x, touchY, 0); hub.dispatchTouchEvent(released); released.recycle();
                require(launches == 0 && closes == 0, "old touch release cannot activate app after reopening"); pin.performClick(); require(launches == 1, "fresh explicit app action remains usable after cancellation");
                hub.dismiss(() -> closes++); ValueAnimator exit = motion(); require(exit != null && exit.getDuration() == 180 && hub.closing(), "exit is a bounded 180ms transition"); exit.pause(); exit.setCurrentPlayTime(90); frame("exit-090");
                float y = hub.getTranslationY(), alpha = hub.getAlpha(); require(y > 0 && alpha < 1 && closes == 0, "exit slides down before removal");
                long time = android.os.SystemClock.uptimeMillis(); MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 20, 20, 0), up = MotionEvent.obtain(time, time + 30, MotionEvent.ACTION_UP, 20, 20, 0);
                require(hub.dispatchTouchEvent(down) && hub.dispatchTouchEvent(up) && closes == 0, "exiting surface consumes taps instead of activating hidden content"); down.recycle(); up.recycle();
                hub.reopen(); require(!hub.closing() && near(y, hub.getTranslationY()) && near(alpha, hub.getAlpha()) && closes == 0, "reopen starts at the current exit frame without calling stale removal");
                motion().pause(); motion().setCurrentPlayTime(80); require(hub.getTranslationY() < y, "reversed exit moves back toward Dock position"); motion().end();
                hub.prepareEntrance(); hub.reopen(); motion().pause(); motion().setCurrentPlayTime(65); y = hub.getTranslationY(); alpha = hub.getAlpha();
                hub.dismiss(() -> closes++); require(near(y, hub.getTranslationY()) && near(alpha, hub.getAlpha()), "dismiss during entry does not reset the visual position"); motion().pause(); motion().end(); require(closes == 1 && hub.getAlpha() == 0 && hub.closing(), "exit completion removes exactly once");
                hub.dismiss(() -> closes++); require(closes == 1, "duplicate exit does not call completion twice");
                hub.reopen(); motion().end(); hub.dismiss(() -> closes++); motion().pause(); hub.dispose(); require(motion() == null && closes == 1, "forced teardown cancels pending completion");
            });
            main(() -> {
                require(launches == (ValueAnimator.areAnimatorsEnabled() ? 1 : 0), "canceled touch does not enqueue a delayed app click");
                hub.dispose(); root.removeAllViews();
                hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { } }); hub.setExpanded(false); root.addView(hub, new FrameLayout.LayoutParams(-1, -1));
            });
            main(() -> checkDismissal(prefs));
            main(() -> {
                CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs; field(owner, "hub", hub);
                owner.act("app_dock");
                if (ValueAnimator.areAnimatorsEnabled()) {
                    ValueAnimator exit = motion(); exit.pause(); exit.setCurrentPlayTime(90); float y = hub.getTranslationY();
                    owner.act("app_dock"); require(field(owner, "hub") == hub && !hub.closing() && near(y, hub.getTranslationY()), "service quick toggle reuses the partially dismissed Dock");
                    motion().end(); owner.act("app_dock"); motion().end();
                }
                require(field(owner, "hub") == null, "service removes the window after the exit completes");
                require(CoverApp.catalog(activity).observerCount() == 0, "service dismissal releases the application subscription");
            });
            return "PASS: hub-motion; " + assertions + " assertions; animations=" + ValueAnimator.areAnimatorsEnabled() + "; native frames and service transition boundaries, not Samsung frame-rate validation";
        } finally { main(() -> { if (hub != null) hub.dispose(); activity.finish(); }); }
    }
    private void checkDismissal(Prefs prefs) {
        hub.dispose(); root.removeAllViews();
        hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { launches++; } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { closes++; hub.dismiss(() -> { }); } });
        root.addView(hub, new FrameLayout.LayoutParams(-1, -1));
        root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY)); root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
        View grid = hub.findViewWithTag("hub-grid"); int[] location = new int[2], origin = new int[2]; grid.getLocationOnScreen(location); hub.getLocationOnScreen(origin);
        float x = location[0] + grid.getWidth() / 2f, y = location[1] + grid.getHeight() / 2f, small = Ui.dp(activity, 20), large = Ui.dp(activity, 64);
        View dock = (View) field(hub, "dockHost"), content = (View) field(hub, "appContent");
        float dockTop = dock.getY(), appTravel = dock.getTop() - content.getY();
        long time = android.os.SystemClock.uptimeMillis(); int before = closes, beforeLaunches = launches;
        touch(time, 0, MotionEvent.ACTION_DOWN, x, y); touch(time, 200, MotionEvent.ACTION_MOVE, x, y + small);
        require(near(hub.getTranslationY(), small) && hub.getAlpha() == 1, "launcher follows a slow pull in screen pixels while remaining opaque");
        require(near(hub.getTranslationY() + dock.getY(), dockTop), "Dock stays fixed during the application's first-stage pull");
        frame("two-stage-app");
        touch(time, 400, MotionEvent.ACTION_MOVE, x, y + small / 2);
        require(near(hub.getTranslationY(), small / 2), "reversing the pull follows the finger immediately");
        require(near(hub.getTranslationY() + dock.getY(), dockTop), "Dock stays fixed while reversing the first-stage pull");
        touch(time, 800, MotionEvent.ACTION_UP, x, y + small / 2); if (motion() != null) motion().end();
        require(closes == before && near(hub.getTranslationY(), 0), "short slow pull returns to the original position");
        require(near(dock.getTranslationY(), 0), "rebound restores the original Dock transform");
        touch(time, 1000, MotionEvent.ACTION_DOWN, x, y); touch(time, 1200, MotionEvent.ACTION_MOVE, x, y + large);
        require(near(hub.getTranslationY(), large), "long slow pull follows before release");
        touch(time, 1400, MotionEvent.ACTION_CANCEL, x, y + large); if (motion() != null) motion().end();
        require(closes == before && near(hub.getTranslationY(), 0), "system cancellation restores launcher without closing");
        touch(time, 1450, MotionEvent.ACTION_DOWN, x, y); touch(time, 1500, MotionEvent.ACTION_MOVE, x, y + appTravel - small);
        require(near(hub.getTranslationY() + dock.getY(), dockTop), "Dock remains fixed until the application page reaches it");
        touch(time, 1520, MotionEvent.ACTION_MOVE, x, y + appTravel + small);
        require(near(hub.getTranslationY() + dock.getY(), dockTop + small), "Dock follows only the travel beyond the application stage");
        frame("two-stage-dock");
        touch(time, 1540, MotionEvent.ACTION_MOVE, x, y + appTravel - small);
        require(near(hub.getTranslationY() + dock.getY(), dockTop), "reversing across the stage boundary fixes Dock in place again");
        touch(time, 1560, MotionEvent.ACTION_CANCEL, x, y); if (motion() != null) motion().end();
        touch(time, 1570, MotionEvent.ACTION_DOWN, x, y); touch(time, 1580, MotionEvent.ACTION_MOVE, x, y + appTravel + dock.getHeight() * .39f); touch(time, 1780, MotionEvent.ACTION_UP, x, y + appTravel + dock.getHeight() * .39f); if (motion() != null) motion().end();
        require(closes == before && near(hub.getTranslationY(), 0), "the Dock stage uses its own forty-percent threshold after the application page slides away");
        touch(time, 1800, MotionEvent.ACTION_DOWN, x, y); touch(time, 2000, MotionEvent.ACTION_MOVE, x, y + appTravel + dock.getHeight() * .41f); touch(time, 2400, MotionEvent.ACTION_UP, x, y + appTravel + dock.getHeight() * .41f);
        require(closes == before + 1 && hub.closing(), "the Dock stage closes beyond forty percent independently of the application travel");
        before++; hub.reopen(); if (motion() != null) motion().end(); time += 1000;
        touch(time, 1600, MotionEvent.ACTION_DOWN, x, y); touch(time, 1800, MotionEvent.ACTION_MOVE, x, y + large); touch(time, 2000, MotionEvent.ACTION_UP, x, y + large);
        require(closes == before + 1, "long slow release requests closure once");
        hub.reopen(); if (motion() != null) motion().end();
        touch(time, 2200, MotionEvent.ACTION_DOWN, x, y); touch(time, 2210, MotionEvent.ACTION_MOVE, x, y + small); touch(time, 2220, MotionEvent.ACTION_UP, x, y + small * 1.5f);
        require(closes == before + 2 && launches == beforeLaunches, "short fast downward fling closes without launching the touched app");
        hub.reopen(); if (motion() != null) motion().end(); hub.setExpanded(false);
        ValueAnimator collapse = (ValueAnimator) field(hub, "contentAnimation"); if (collapse != null) collapse.end();
        root.measure(View.MeasureSpec.makeMeasureSpec(root.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.EXACTLY)); root.layout(root.getLeft(), root.getTop(), root.getRight(), root.getBottom());
        dock.getLocationOnScreen(location); x = location[0] + dock.getWidth() / 2f; y = location[1] + dock.getHeight() / 2f;
        float below = dock.getHeight() * .39f, above = dock.getHeight() * .41f; int dockBefore = closes;
        touch(time, 2400, MotionEvent.ACTION_DOWN, x, y); touch(time, 2600, MotionEvent.ACTION_MOVE, x, y + below); touch(time, 3000, MotionEvent.ACTION_UP, x, y + below);
        require(closes == dockBefore && !hub.closing(), "Dock-only slow pull below forty percent returns without closing"); if (motion() != null) motion().end();
        require(near(hub.getTranslationY(), 0), "short Dock pull returns exactly to its original position");
        touch(time, 3200, MotionEvent.ACTION_DOWN, x, y); touch(time, 3400, MotionEvent.ACTION_MOVE, x, y + above); touch(time, 3800, MotionEvent.ACTION_UP, x, y + above);
        require(closes == dockBefore + 1 && hub.closing(), "Dock-only slow pull beyond forty percent closes without velocity"); if (motion() != null) motion().end();
        require(hub.getTranslationY() + dock.getTop() >= hub.getHeight() - 1, "Dock exit travels fully below the screen");
        hub.reopen(); if (motion() != null) motion().end();
        touch(time, 4000, MotionEvent.ACTION_DOWN, x, y); touch(time, 4010, MotionEvent.ACTION_MOVE, x, y + below * .7f); touch(time, 4020, MotionEvent.ACTION_UP, x, y + below);
        require(closes == dockBefore + 2 && hub.closing(), "Dock-only downward fling closes below the distance threshold");
        hub.reopen(); if (motion() != null) motion().end();
        touch(time, 4200, MotionEvent.ACTION_DOWN, x, y); touch(time, 4400, MotionEvent.ACTION_MOVE, x, y + above); touch(time, 4600, MotionEvent.ACTION_CANCEL, x, y + above); if (motion() != null) motion().end();
        require(closes == dockBefore + 2 && near(hub.getTranslationY(), 0), "system cancellation keeps Dock even beyond its distance threshold");
    }
    private void touch(long down, int offset, int action, float x, float y) {
        int[] origin = new int[2]; hub.getLocationOnScreen(origin);
        MotionEvent event = MotionEvent.obtain(down, down + offset, action, x, y, 0); event.setLocation(x - origin[0], y - origin[1]); hub.dispatchTouchEvent(event); event.recycle();
    }
    private void frame(String name) {
        Bitmap bitmap = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new Canvas(bitmap)); File directory = new File(test.getTargetContext().getFilesDir(), "ui-smoke"); directory.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(directory, "dock-motion-" + name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } catch (java.io.IOException error) { throw new AssertionError(error); } finally { bitmap.recycle(); }
    }
}
