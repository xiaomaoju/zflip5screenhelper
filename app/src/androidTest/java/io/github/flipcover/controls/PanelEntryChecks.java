package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;

/** Native entry, drawing and lifecycle regression on a disposable emulator only. */
final class PanelEntryChecks {
    private final Instrumentation test;
    private Activity activity;
    private Prefs prefs;
    private FrameLayout root;
    private PanelEntryView entry;
    private int assertions, begins, releases, toggles, clicks, rightClicks;
    private boolean canceled;
    private String page;
    private float distance;
    PanelEntryChecks(Instrumentation test) { this.test = test; }
    String run() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            test.runOnMainSync(() -> {
                prefs = new Prefs(activity); prefs.data.edit().clear().putBoolean("haptics", false).commit();
                for (int zone = 0; zone < 2; zone++) {
                    mount(); float x = zone == 0 ? 100 : 300;
                    drag(entry, x, 0, 120, MotionEvent.ACTION_UP);
                    require(begins == 1 && releases == 1 && !canceled && distance == 120 && page.equals(zone == 0 ? "notifications" : "controls"), "left/right upward routing");
                    require(clicks == 0 && toggles == 0, "pull does not also tap or hold");
                }
                mount(); drag(entry, 100, 0, 0, MotionEvent.ACTION_UP);
                require(begins == 0 && clicks == 0 && toggles == 0, "handle taps do not open or toggle");
                mount(); drag(entry, 100, 0, -120, MotionEvent.ACTION_UP); require(begins == 0, "downward input cannot open");
                mount(); drag(entry, 100, 120, 0, MotionEvent.ACTION_UP); require(begins == 0, "sideways input cannot open");
                mount(); drag(entry, 100, 50, 120, MotionEvent.ACTION_UP); require(begins == 1 && page.equals("notifications"), "diagonal left-hand pull works");
                mount(); drag(entry, 180, 80, 120, MotionEvent.ACTION_UP); require(page.equals("notifications"), "crossing midpoint keeps initial target");
                for (int terminal : new int[]{MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN}) {
                    mount(); drag(entry, 300, 0, 120, terminal); require(releases == 1 && canceled && toggles == 0, "cancel or second finger releases once");
                }
                mount(); long time = SystemClock.uptimeMillis(); event(entry, time, 0, MotionEvent.ACTION_DOWN, 100, 2); event(entry, time, 40, MotionEvent.ACTION_MOVE, 100, -100);
                entry.layout(0, 0, 420, 48); event(entry, time, 80, MotionEvent.ACTION_UP, 100, -100); require(releases == 1 && canceled, "size change cancels old pull");
                mount(); time = SystemClock.uptimeMillis(); event(entry, time, 0, MotionEvent.ACTION_DOWN, 100, 2); event(entry, time, 40, MotionEvent.ACTION_MOVE, 100, -100);
                root.removeView(entry); require(releases == 1 && canceled, "detach cancels old pull");
                prefs.data.edit().putBoolean("gestures_enabled", false).commit(); mount(); drag(entry, 100, 0, 120, MotionEvent.ACTION_UP); require(begins == 0, "disabled pulls remain disabled");
                prefs.data.edit().putBoolean("gestures_enabled", true).commit();
                mount(); drag(root, 100, 0, 120, MotionEvent.ACTION_UP); require(begins == 1 && releases == 1, "transparent entry receives upward input through parent dispatch");
                mount(); drag(root, 500, 0, 0, MotionEvent.ACTION_UP); require(rightClicks == 1 && begins == 0, "area outside entry reaches underlying view");
                mount(); time = SystemClock.uptimeMillis(); event(entry, time, 0, MotionEvent.ACTION_DOWN, 100, 2); event(entry, time, 40, MotionEvent.ACTION_MOVE, 100, -100);
                entry.suspended(true); require(releases == 1 && canceled && entry.getVisibility() == View.INVISIBLE && entry.getAlpha() == 0, "Hub suspension cancels old pull and hides entry");
                drag(root, 100, 0, 120, MotionEvent.ACTION_UP); require(begins == 1 && rightClicks == 1, "suspended entry passes input to underlying Hub area");
                entry.suspended(false); drag(root, 100, 0, 120, MotionEvent.ACTION_UP); require(begins == 2 && releases == 2, "closing Hub restores transparent upward entry");
                checkCameraEdges(); checkStatusClearance(); checkWindowFlags(); checkPanelWindowContinuity();
            });
            test.runOnMainSync(() -> { mount(); require(entry.getAlpha() == 0 && entry.getVisibility() == View.VISIBLE, "idle handles are invisible while input remains enabled"); event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_DOWN, 300, 2); });
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); test.waitForIdleSync();
            test.runOnMainSync(() -> { event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_UP, 300, 2); require(toggles == 0 && entry.getAlpha() == 0, "hidden long press does not toggle or reveal handles"); entry.panelVisible(true); });
            SystemClock.sleep(180); test.waitForIdleSync();
            test.runOnMainSync(() -> event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_DOWN, 300, 2));
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); test.waitForIdleSync();
            test.runOnMainSync(() -> { event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_UP, 300, 2); require(toggles == 1 && begins == 0 && clicks == 0, "second handle hold toggles once"); });
            test.runOnMainSync(() -> { mount(); event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_DOWN, 100, 2); root.removeView(entry); });
            SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); test.waitForIdleSync();
            require(toggles == 0, "detach removes pending long press");
            checkVisibilityTiming();
            return "PASS: " + assertions + " panel-entry assertions; left/right routing, right-side pass-through, status clearance, cancellation and window flags; Samsung system routing not verified";
        } finally { test.runOnMainSync(activity::finish); }
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private void checkVisibilityTiming() {
        test.runOnMainSync(() -> { mount(); long time = SystemClock.uptimeMillis(); event(entry, time, 0, MotionEvent.ACTION_DOWN, 100, 24); event(entry, time, 60, MotionEvent.ACTION_MOVE, 100, -100); entry.panelVisible(true); });
        SystemClock.sleep(40); test.waitForIdleSync();
        test.runOnMainSync(() -> { event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_DOWN, 100, 24); event(entry, SystemClock.uptimeMillis(), 20, MotionEvent.ACTION_UP, 100, 24); });
        SystemClock.sleep(180); test.waitForIdleSync();
        test.runOnMainSync(() -> { require(entry.getAlpha() > .99f, "trigger fades handles fully in"); event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_UP, 100, -100); });
        SystemClock.sleep(3200); test.waitForIdleSync();
        require(entry.getAlpha() > .99f, "open panel keeps handles visible past three seconds");
        test.runOnMainSync(() -> { entry.panelVisible(false); root.removeView(entry); root.addView(entry, new FrameLayout.LayoutParams(400, 48)); entry.layout(0, 0, 400, 48); });
        SystemClock.sleep(1000); test.waitForIdleSync(); require(Math.abs(entry.getAlpha() - .5f) < .01f, "closing and reattaching dims to half during countdown");
        int previousToggles = toggles;
        test.runOnMainSync(() -> event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_DOWN, 300, 2));
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); test.waitForIdleSync();
        test.runOnMainSync(() -> { event(entry, SystemClock.uptimeMillis(), 0, MotionEvent.ACTION_UP, 300, 2); require(toggles == previousToggles + 1, "half-visible handle accepts long press"); });
        test.runOnMainSync(() -> entry.panelVisible(true));
        SystemClock.sleep(2300); test.waitForIdleSync(); require(entry.getAlpha() > .99f, "reopening cancels old fade deadline");
        test.runOnMainSync(() -> entry.panelVisible(false));
        SystemClock.sleep(2800); test.waitForIdleSync(); require(Math.abs(entry.getAlpha() - .5f) < .01f, "handles stay half-visible before three-second deadline");
        SystemClock.sleep(500); test.waitForIdleSync(); require(entry.getAlpha() < .01f, "handles fade out after close plus three seconds");
        test.runOnMainSync(() -> drag(entry, 100, 0, 0, MotionEvent.ACTION_UP));
        SystemClock.sleep(180); test.waitForIdleSync(); require(entry.getAlpha() == 0, "tap after fade-out cannot reveal half-opacity handles");
    }
    private void mount() {
        if (root != null) root.removeAllViews();
        begins = releases = toggles = clicks = rightClicks = 0; page = ""; distance = 0; canceled = false;
        root = new FrameLayout(activity); root.setBackgroundColor(0xFF243448);
        View right = new View(activity); right.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_UP) rightClicks++; return true; });
        root.addView(right, new FrameLayout.LayoutParams(600, 48));
        entry = new PanelEntryView(activity, prefs, entryPlacement(DockGeometry.BOTTOM), new DockView.Listener() {
            public void action(String id) { clicks++; } public void configure() { }
            public void beginPull(String name, float value) { begins++; page = name; }
            public void pull(String name, float value) { distance = value; }
            public void release(String name, float value, float speed, boolean cancel) { releases++; canceled = cancel; }
            public void toggleVisibility() { toggles++; }
        });
        root.addView(entry, new FrameLayout.LayoutParams(400, 48)); activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 600, 600);
    }
    private DockGeometry.Placement entryPlacement(int edge) {
        DockGeometry.Box box = new DockGeometry.Box(0, 0, 400, 48);
        return new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, true);
    }
    private void checkCameraEdges() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(351, 682, 369, 66), new DockGeometry.Box(0, 351, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(682, 0, 66, 369)};
        for (int edge = 0; edge < 4; edge++) for (int half = 0; half < 2; half++) {
            mount(); root.removeView(entry);
            int width = edge % 2 == 0 ? 720 : 748, height = edge % 2 == 0 ? 748 : 720; float density = activity.getResources().getDisplayMetrics().density;
            DockGeometry.Placement dock = DockGeometry.resolve(width, height, java.util.List.of(cuts[edge]), density, (edge + 3) % 4, .46f, .088f, true);
            DockGeometry.Placement placement = DockGeometry.panelEntry(dock, dock, width, height, java.util.List.of(cuts[edge]), density, 24);
            require(placement.edge() == DockGeometry.BOTTOM && !placement.vertical(), "every rotation uses a bottom horizontal entry");
            entry = new PanelEntryView(activity, prefs, placement, new DockView.Listener() {
                public void action(String id) { clicks++; } public void configure() { }
                public void beginPull(String name, float value) { begins++; page = name; }
                public void pull(String name, float value) { distance = value; }
                public void release(String name, float value, float speed, boolean cancel) { releases++; canceled = cancel; }
            });
            root.addView(entry, new FrameLayout.LayoutParams(placement.touch().width(), placement.touch().height()));
            entry.layout(0, 0, placement.touch().width(), placement.touch().height());
            float x = entry.getWidth() * (half == 0 ? .25f : .75f), y = entry.getHeight() / 2f;
            float dx = 0, dy = -120;
            long time = SystemClock.uptimeMillis(); event(entry, time, 0, MotionEvent.ACTION_DOWN, x, y); event(entry, time, 60, MotionEvent.ACTION_MOVE, x + dx, y + dy); event(entry, time, 120, MotionEvent.ACTION_UP, x + dx, y + dy);
            require(begins == 1 && releases == 1 && !canceled && distance == 120 && page.equals(half == 0 ? "notifications" : "controls"), "camera-edge pull routes correct half on edge " + edge);
            event(entry, time, 160, MotionEvent.ACTION_DOWN, x, y); event(entry, time, 200, MotionEvent.ACTION_MOVE, x + 120, y); event(entry, time, 240, MotionEvent.ACTION_UP, x + 120, y);
            require(begins == 1 && releases == 1, "horizontal swipe never opens a panel on rotation " + edge);
            Bitmap bitmap = Bitmap.createBitmap(entry.getWidth(), entry.getHeight(), Bitmap.Config.ARGB_8888); entry.draw(new Canvas(bitmap));
            DockGeometry.Chrome chrome = DockGeometry.chrome(placement, activity.getResources().getDisplayMetrics().density);
            for (DockGeometry.Box bar : new DockGeometry.Box[]{chrome.firstHandle(), chrome.secondHandle()}) require(android.graphics.Color.alpha(bitmap.getPixel(bar.x() + bar.width() / 2, bar.y() + bar.height() / 2)) > 0, "white handle is drawn on edge " + edge);
            bitmap.recycle();
        }
    }
    private void checkStatusClearance() {
        DockGeometry.Box[] cuts = {new DockGeometry.Box(351, 682, 369, 66), new DockGeometry.Box(0, 351, 66, 369), new DockGeometry.Box(0, 0, 369, 66), new DockGeometry.Box(682, 0, 66, 369)};
        for (int edge = 0; edge < 4; edge++) {
            int width = edge % 2 == 0 ? 720 : 748, height = edge % 2 == 0 ? 748 : 720; float density = activity.getResources().getDisplayMetrics().density;
            DockGeometry.Placement dock = DockGeometry.resolve(width, height, java.util.List.of(cuts[edge]), density, (edge + 3) % 4, .46f, .088f, true);
            DockGeometry.Placement placement = DockGeometry.panelEntry(dock, dock, width, height, java.util.List.of(cuts[edge]), density, 24);
            root.removeAllViews(); StatusBarView status = new StatusBarView(activity, prefs);
            FrameLayout.LayoutParams statusParams = new FrameLayout.LayoutParams(placement.panel().width(), status.heightPixels()); statusParams.leftMargin = placement.panel().x(); statusParams.topMargin = placement.panel().y(); root.addView(status, statusParams);
            root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); root.layout(0, 0, width, height);
            Bitmap before = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); root.draw(new Canvas(before));
            PanelEntryView bars = new PanelEntryView(activity, prefs, placement, new DockView.Listener() { public void action(String id) { } public void configure() { } });
            DockGeometry.Box box = placement.touch(); FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(box.width(), box.height()); params.leftMargin = box.x(); params.topMargin = box.y(); root.addView(bars, params); bars.layout(box.x(), box.y(), box.right(), box.bottom());
            bars.setAlpha(1);
            Bitmap after = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); root.draw(new Canvas(after));
            Bitmap beforeStatus = Bitmap.createBitmap(before, status.getLeft(), status.getTop(), status.getWidth(), status.getHeight()), afterStatus = Bitmap.createBitmap(after, status.getLeft(), status.getTop(), status.getWidth(), status.getHeight());
            require(beforeStatus.sameAs(afterStatus), "relocated handles do not cover status pixels on edge " + edge);
            beforeStatus.recycle(); afterStatus.recycle(); before.recycle(); after.recycle();
        }
    }
    private void checkWindowFlags() {
        CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs;
        DockGeometry.Box box = new DockGeometry.Box(0, 500, 300, 48); DockGeometry.Placement placement = new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, false);
        DockView compact = new DockView(activity, prefs, placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, true);
        try {
            for (String name : new String[]{"dock", "dockPlacement", "panelEntryPlacement"}) {
                java.lang.reflect.Field field = CoverService.class.getDeclaredField(name); field.setAccessible(true); field.set(owner, name.equals("dock") ? compact : name.equals("dockPlacement") ? placement : entryPlacement(DockGeometry.BOTTOM));
            }
            java.lang.reflect.Method method = CoverService.class.getDeclaredMethod("dockParameters"); method.setAccessible(true);
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) method.invoke(owner);
            require((layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0, "hidden bottom dock window cannot intercept Home input");
            method = CoverService.class.getDeclaredMethod("panelEntryParameters"); method.setAccessible(true); layout = (WindowManager.LayoutParams) method.invoke(owner);
            require(layout.width == 400 && layout.height == 48 && layout.x == 0 && layout.y == 0, "entry input window ends before area outside entry");
            AppHubView hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
            java.lang.reflect.Field hubField = CoverService.class.getDeclaredField("hub"); hubField.setAccessible(true); hubField.set(owner, hub);
            layout = (WindowManager.LayoutParams) method.invoke(owner); require((layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0, "Hub disables the overlapping entry window");
            java.lang.reflect.Field frameField = CoverService.class.getDeclaredField("hubFrame"); frameField.setAccessible(true); frameField.set(owner, new DockGeometry.Box(0, 39, 748, 615));
            java.lang.reflect.Field windowsField = CoverService.class.getDeclaredField("windows"); windowsField.setAccessible(true); windowsField.set(owner, activity.getWindowManager());
            java.lang.reflect.Method hubMethod = CoverService.class.getDeclaredMethod("hubParameters", boolean.class); hubMethod.setAccessible(true);
            layout = (WindowManager.LayoutParams) hubMethod.invoke(owner, true); require(layout.y == 39 && layout.height == 615, "production Hub window uses reclaimed safe frame");
            hubField.set(owner, null); hub.dispose(); layout = (WindowManager.LayoutParams) method.invoke(owner);
            require((layout.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0, "closing Hub restores entry input flags");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void checkPanelWindowContinuity() {
        mount(); prefs.data.edit().putBoolean("panel_blur", false).commit();
        CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs;
        DockGeometry.Box box = new DockGeometry.Box(0, 500, 400, 48), content = new DockGeometry.Box(0, 0, 600, 450);
        owner.placement = new DockGeometry.Placement(box, box, content, DockGeometry.BOTTOM, false);
        DockView dock = new DockView(activity, prefs, owner.placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, true);
        FrameLayout host = new FrameLayout(activity); host.setLayoutParams(new WindowManager.LayoutParams(600, 600));
        int[] replacements = {0}, updates = {0};
        WindowManager windows = (WindowManager) java.lang.reflect.Proxy.newProxyInstance(WindowManager.class.getClassLoader(), new Class<?>[]{WindowManager.class}, (proxy, method, args) -> {
            if (method.getName().equals("addView") || method.getName().equals("removeView") || method.getName().equals("removeViewImmediate")) { replacements[0]++; return null; }
            if (method.getName().equals("updateViewLayout")) { updates[0]++; if (args[0] == host) host.setLayoutParams((android.view.ViewGroup.LayoutParams) args[1]); return null; }
            if (method.getName().equals("isCrossWindowBlurEnabled")) return false;
            return null;
        });
        try {
            String[] names = {"windows", "dock", "dockPlacement", "panelEntry", "panelEntryPlacement", "panelHost", "panelFrame"};
            Object[] values = {windows, dock, owner.placement, entry, entryPlacement(DockGeometry.BOTTOM), host, new DockGeometry.Box(0, 0, 600, 600)};
            for (int i = 0; i < names.length; i++) { java.lang.reflect.Field field = CoverService.class.getDeclaredField(names[i]); field.setAccessible(true); field.set(owner, values[i]); }
            owner.showPanel("notifications");
            require(host.getChildCount() == 1 && updates[0] > 0, "service opens notification panel through persistent host");
            require(replacements[0] == 0, "opening panel never removes or re-adds entry or chrome windows");
            java.lang.reflect.Method settle = CoverService.class.getDeclaredMethod("settlePanel", boolean.class); settle.setAccessible(true); settle.invoke(owner, true);
            java.lang.reflect.Field motion = CoverService.class.getDeclaredField("panelAnimation"); motion.setAccessible(true);
            if (motion.get(owner) instanceof android.animation.ValueAnimator animation) animation.end();
            require(replacements[0] == 0 && entry.isAttachedToWindow(), "panel settle keeps entry continuously attached");
        } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        finally { owner.closePanel(); }
    }
    private void drag(View target, float x, float dx, float dy, int terminal) {
        long time = SystemClock.uptimeMillis(); event(target, time, 0, MotionEvent.ACTION_DOWN, x, 2); event(target, time, 60, MotionEvent.ACTION_MOVE, x + dx, 2 - dy); event(target, time, 120, terminal, x + dx, 2 - dy);
        if (terminal == MotionEvent.ACTION_POINTER_DOWN) event(target, time, 160, MotionEvent.ACTION_UP, x + dx, 2 - dy);
    }
    private void event(View target, long down, int offset, int action, float x, float y) {
        MotionEvent event;
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2]; MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
            for (int i = 0; i < 2; i++) { properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER; coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = x + i; coordinates[i].y = y + i; coordinates[i].pressure = 1; coordinates[i].size = 1; }
            event = MotionEvent.obtain(down, down + offset, action | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, 2, properties, coordinates, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        } else event = MotionEvent.obtain(down, down + offset, action, x, y, 0);
        target.dispatchTouchEvent(event); event.recycle();
    }
}
