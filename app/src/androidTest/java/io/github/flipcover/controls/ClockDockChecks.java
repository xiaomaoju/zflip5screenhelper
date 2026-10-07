package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** View input/lifecycle checks on a disposable emulator, not Samsung keyguard acceptance. */
final class ClockDockChecks {
    private final Instrumentation test;
    private Activity activity;
    private Prefs prefs;
    private FrameLayout root;
    private ClockDockHandleView arrow;
    private int clicks, outsideClicks, assertions;
    ClockDockChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String reason) { assertions++; if (!value) throw new AssertionError(reason); }
    private void waitAnimation() { SystemClock.sleep(BuildConfig.MOTION_HANDLES_FADE_IN_MS + 120); test.waitForIdleSync(); }
    String run() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.runOnMainSync(() -> {
                prefs = new Prefs(activity); prefs.data.edit().putBoolean("haptics", false).commit();
                root = new FrameLayout(activity); root.setBackgroundColor(0xFF101418);
                View underneath = new View(activity); underneath.setOnClickListener(v -> outsideClicks++); root.addView(underneath, new FrameLayout.LayoutParams(-1, -1));
                arrow = new ClockDockHandleView(activity, prefs, DockGeometry.BOTTOM, () -> { clicks++; arrow.expanded(!arrow.expanded()); });
                root.addView(arrow, new FrameLayout.LayoutParams(160, 72)); activity.setContentView(root);
                root.measure(View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 480, 480);
                require(Math.abs(arrow.getAlpha() - .5f) < .01f && !arrow.expanded(), "new clock entry briefly shows its location without expanding the dock");
                tap(root, 240, 100);
            });
            test.waitForIdleSync();
            SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + BuildConfig.MOTION_HANDLES_FADE_OUT_MS + 200); test.waitForIdleSync();
            test.runOnMainSync(() -> {
                require(outsideClicks == 1 && clicks == 0, "outside arrow passes to clock-page content");
                require(arrow.getAlpha() < .01f, "first appearance fades after the shared delay");
                tap(root, 80, 36); require(clicks == 1 && arrow.expanded(), "invisible hit target opens in one tap");
            });
            waitAnimation(); require(arrow.getAlpha() > .99f, "opened arrow reaches full white-bar alpha");
            test.runOnMainSync(() -> capture("clock-dock-open.png"));
            SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + 200); test.waitForIdleSync();
            require(arrow.getAlpha() > .99f && arrow.expanded(), "open toolbar never auto-collapses or fades its arrow");
            test.runOnMainSync(() -> tap(root, 80, 36)); waitAnimation();
            require(!arrow.expanded() && Math.abs(arrow.getAlpha() - .5f) < .01f, "closing dims the arrow to half");
            test.runOnMainSync(() -> capture("clock-dock-collapsed.png"));
            test.runOnMainSync(() -> tap(root, 80, 36)); waitAnimation();
            SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + 200); test.waitForIdleSync();
            require(arrow.expanded() && arrow.getAlpha() > .99f, "reopening cancels old hide deadline");
            test.runOnMainSync(() -> tap(root, 80, 36));
            SystemClock.sleep(BuildConfig.MOTION_HANDLES_HIDE_DELAY_MS + BuildConfig.MOTION_HANDLES_FADE_OUT_MS + 200); test.waitForIdleSync();
            require(!arrow.expanded() && arrow.getAlpha() < .01f && arrow.getVisibility() == View.VISIBLE, "collapsed arrow fades but remains hittable");
            test.runOnMainSync(() -> {
                int before = clicks;
                event(arrow, MotionEvent.ACTION_DOWN, 80, 36); event(arrow, MotionEvent.ACTION_MOVE, 150, 36); event(arrow, MotionEvent.ACTION_UP, 150, 36);
                require(clicks == before, "drag is not a toggle");
                for (int cancel : new int[]{MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN}) {
                    event(arrow, MotionEvent.ACTION_DOWN, 80, 36); event(arrow, cancel, 80, 36); event(arrow, MotionEvent.ACTION_UP, 80, 36);
                    require(clicks == before, "cancel and multi-touch do not toggle");
                }
                event(arrow, MotionEvent.ACTION_DOWN, 80, 36); arrow.layout(0, 0, 170, 72); event(arrow, MotionEvent.ACTION_UP, 80, 36);
                require(clicks == before, "resize cancels pending click");
                arrow.performClick(); require(arrow.expanded() && clicks == before + 1, "accessibility click opens toolbar");
                root.removeView(arrow); event(arrow, MotionEvent.ACTION_UP, 80, 36); require(clicks == before + 1, "detach cancels pending input");
                verifyWindowCleanup();
                verifyStableDock();
            });
            verifyCoupledMotion();
            return "PASS: " + assertions + " clock arrow input, fade, accessibility and cleanup checks; Samsung physical clock routing not verified";
        } finally { test.runOnMainSync(activity::finish); }
    }
    private void verifyStableDock() {
        DockGeometry.Box box = new DockGeometry.Box(0, 360, 320, 60);
        DockGeometry.Placement placement = new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, false);
        DockView dock = new DockView(activity, prefs, placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, true);
        root.addView(dock, new FrameLayout.LayoutParams(320, 60));
        View fixed = dock.findViewWithTag("fixed-action");
        require(dock.compact() && fixed.getVisibility() == View.GONE, "clock dock is hidden before first attachment draw");
        dock.compact(false); require(!dock.compact() && fixed.getVisibility() == View.VISIBLE, "clock arrow expands the existing dock content");
        dock.compact(true); require(dock.compact() && dock.getVisibility() == View.GONE, "returning to clock hides immediately without reconstructing buttons");
        dock.compact(false); require(dock.findViewWithTag("fixed-action") == fixed, "all arrow toggles preserve button identity and window stacking");
        root.removeView(dock);
    }
    private void verifyCoupledMotion() {
        CoverService[] service = {null}; ClockDockHandleView[] handle = {null}; DockView[] moving = {null};
        int[] samples = {0}; String[] mismatch = {null};
        android.view.WindowManager windows = activity.getWindowManager();
        DockGeometry.Box closed = new DockGeometry.Box(20, 360, 160, 60), open = new DockGeometry.Box(20, 280, 160, 60);
        try {
            test.runOnMainSync(() -> {
                CoverService owner = new CoverService(); service[0] = owner; owner.prefs = prefs; owner.screenContext = activity;
                try { var attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class); attach.setAccessible(true); attach.invoke(owner, activity); }
                catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                DockGeometry.Placement placement = new DockGeometry.Placement(closed, closed, closed, DockGeometry.BOTTOM, false); owner.placement = placement;
                DockView dock = new DockView(activity, prefs, placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, true); moving[0] = dock;
                ClockDockHandleView arrow = new ClockDockHandleView(activity, prefs, DockGeometry.BOTTOM, () -> { }); handle[0] = arrow;
                windows.addView(dock, testWindow(closed)); windows.addView(arrow, testWindow(closed));
                setField(owner, "dock", dock); setField(owner, "dockPlacement", placement); setField(owner, "clockDockWindow", true);
                setField(owner, "clockHandle", arrow); setField(owner, "clockHandleBox", closed); setField(owner, "clockHandlePositions", new DockGeometry.ClockHandle(closed, open)); setField(owner, "clockDockProgress", 0f);
                setField(owner, "windows", java.lang.reflect.Proxy.newProxyInstance(android.view.WindowManager.class.getClassLoader(), new Class<?>[]{android.view.WindowManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("removeViewImmediate")) windows.removeViewImmediate((View) args[0]);
                    if (method.getName().equals("updateViewLayout")) { var source = (android.view.WindowManager.LayoutParams) args[1]; var layout = testWindow(new DockGeometry.Box(source.x, source.y, source.width, source.height)); layout.flags = source.flags; windows.updateViewLayout((View) args[0], layout); }
                    return null;
                }));
            });
            waitAnimation();
            test.runOnMainSync(() -> {
                CoverService owner = service[0]; DockView dock = moving[0];
                invokeMotion(owner, true);
                android.animation.ValueAnimator motion = (android.animation.ValueAnimator) getField(owner, "clockHandleMotion");
                require(motion != null && (((android.view.WindowManager.LayoutParams) dock.getLayoutParams()).flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0, "shared motion disables moving dock input");
                motion.addUpdateListener(frame -> {
                    float progress = (float) frame.getAnimatedValue();
                    if (progress > 0 && progress < 1) samples[0]++;
                    if (Math.abs(dock.getTranslationY() - (1 - progress) * 60) > .1f || !getField(owner, "clockHandleBox").equals(DockGeometry.interpolate(closed, open, progress))) mismatch[0] = "arrow and dock used different progress";
                });
                motion.setCurrentFraction(.25f); motion.setCurrentFraction(.5f); motion.setCurrentFraction(.75f);
            });
            waitAnimation();
            test.runOnMainSync(() -> {
                require(samples[0] > 0 && mismatch[0] == null, "arrow and dock share intermediate lerp frames: samples=" + samples[0] + ", error=" + mismatch[0]);
                require(getField(service[0], "clockHandleBox").equals(open) && moving[0].getTranslationY() == 0 && !moving[0].compact(), "shared expansion finishes at both target positions");
                invokeMotion(service[0], false); invokeNoArgs(service[0], "removeClockHandle");
                int flags = ((android.view.WindowManager.LayoutParams) moving[0].getLayoutParams()).flags;
                require(!moving[0].compact() && moving[0].getTranslationY() == 0 && (flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0, "canceling into a visible app restores dock touch and drawing");
            });
        } finally {
            test.runOnMainSync(() -> {
                if (service[0] != null) { invokeNoArgs(service[0], "removeWindows"); service[0].main.removeCallbacksAndMessages(null); ((java.util.concurrent.ExecutorService) getField(service[0], "files")).shutdown(); }
                else { if (handle[0] != null && handle[0].isAttachedToWindow()) windows.removeViewImmediate(handle[0]); if (moving[0] != null && moving[0].isAttachedToWindow()) windows.removeViewImmediate(moving[0]); }
            });
        }
    }
    private android.view.WindowManager.LayoutParams testWindow(DockGeometry.Box box) {
        var layout = new android.view.WindowManager.LayoutParams(box.width(), box.height(), android.view.WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, android.graphics.PixelFormat.TRANSLUCENT);
        layout.token = activity.getWindow().getDecorView().getWindowToken(); layout.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT; layout.x = box.x(); layout.y = box.y(); return layout;
    }
    private void setField(Object target, String name, Object value) { try { var field = CoverService.class.getDeclaredField(name); field.setAccessible(true); field.set(target, value); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private Object getField(Object target, String name) { try { var field = CoverService.class.getDeclaredField(name); field.setAccessible(true); return field.get(target); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void invokeMotion(CoverService owner, boolean expanded) { try { var method = CoverService.class.getDeclaredMethod("moveClockHandle", boolean.class); method.setAccessible(true); method.invoke(owner, expanded); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void invokeNoArgs(CoverService owner, String name) { try { var method = CoverService.class.getDeclaredMethod(name); method.setAccessible(true); method.invoke(owner); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void verifyWindowCleanup() {
        CoverService owner = new CoverService(); owner.prefs = prefs; owner.screenContext = activity;
        try {
            var attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", android.content.Context.class); attach.setAccessible(true); attach.invoke(owner, activity);
            root.addView(arrow, new FrameLayout.LayoutParams(160, 72));
            var mode = CoverService.class.getDeclaredField("coverClockVisible"); mode.setAccessible(true); mode.set(owner, true);
            var handle = CoverService.class.getDeclaredField("clockHandle"); handle.setAccessible(true); handle.set(owner, arrow);
            var windows = CoverService.class.getDeclaredField("windows"); windows.setAccessible(true);
            windows.set(owner, java.lang.reflect.Proxy.newProxyInstance(android.view.WindowManager.class.getClassLoader(), new Class<?>[]{android.view.WindowManager.class}, (proxy, method, args) -> { if (method.getName().equals("removeViewImmediate")) root.removeView((View) args[0]); return null; }));
            var remove = CoverService.class.getDeclaredMethod("removeWindows"); remove.setAccessible(true); remove.invoke(owner);
            require(handle.get(owner) == null && !(boolean) mode.get(owner) && !arrow.isAttachedToWindow(), "service teardown releases arrow and clock scene");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally {
            owner.main.removeCallbacksAndMessages(null);
            try { var files = CoverService.class.getDeclaredField("files"); files.setAccessible(true); ((java.util.concurrent.ExecutorService) files.get(owner)).shutdown(); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        }
    }
    private void tap(View view, float x, float y) { event(view, MotionEvent.ACTION_DOWN, x, y); event(view, MotionEvent.ACTION_UP, x, y); }
    private void capture(String name) {
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        try (var stream = new java.io.FileOutputStream(new java.io.File(activity.getFilesDir(), name))) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream); }
        catch (java.io.IOException error) { throw new AssertionError(error); }
        finally { bitmap.recycle(); }
    }
    private void event(View view, int action, float x, float y) { long now = SystemClock.uptimeMillis(); MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0); view.dispatchTouchEvent(event); event.recycle(); }
}
