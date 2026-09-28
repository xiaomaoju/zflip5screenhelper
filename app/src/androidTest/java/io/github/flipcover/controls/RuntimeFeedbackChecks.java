package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.lang.reflect.Field;
import java.util.List;

/** Interaction invariants, cancellation and settled bounds; not Samsung compositor/frame-rate proof. */
final class RuntimeFeedbackChecks {
    private final Instrumentation test;
    private Activity activity;
    private int assertions, launches;
    RuntimeFeedbackChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String label) { if (!value) throw new AssertionError(label); assertions++; }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private Object field(Object object, String name) { try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object); } catch (Exception error) { throw new AssertionError(error); } }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            Prefs prefs = new Prefs(activity); prefs.data.edit().clear().commit(); List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); prefs.saveHubPins(List.of(apps.get(0).id()));
            main(() -> {
                CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs;
                Panels panels = new Panels(owner, true); View controls = panels.build("controls"); mount(controls);
                View wifi = controls.findViewWithTag("control-wifi"); int width = wifi.getWidth(), height = wifi.getHeight();
                panels.working("wifi", true);
                require(wifi.isActivated() && !wifi.isEnabled() && !wifi.isSelected(), "pending is distinct from enabled system state");
                require(wifi.getStateDescription().toString().contains("正在执行"), "pending has truthful accessibility state");
                require(wifi.getWidth() == width && wifi.getHeight() == height, "pending does not resize tile");
                panels.working("wifi", false); require(!wifi.isActivated() && wifi.isEnabled() && !wifi.isSelected(), "pending completion does not invent success");
                int[] commits = {0}; LevelSlider slider = new LevelSlider(activity, "外屏亮度", R.drawable.ic_ms_brightness_6, 5, 100, 40, value -> commits[0]++); mount(slider);
                long now = SystemClock.uptimeMillis(); event(slider, now, 0, MotionEvent.ACTION_DOWN, 20, 200); event(slider, now, 40, MotionEvent.ACTION_MOVE, 20, 50); event(slider, now, 60, MotionEvent.ACTION_CANCEL, 20, 50);
                require(commits[0] == 0 && (int) field(slider, "value") == 40, "slider cancel restores value without commit");
                event(slider, now, 100, MotionEvent.ACTION_DOWN, 20, 200); event(slider, now, 120, MotionEvent.ACTION_MOVE, 20, 60); event(slider, now, 160, MotionEvent.ACTION_UP, 20, 60); require(commits[0] == 1, "slider release commits once");
                View button = RuntimeVisuals.button(activity, R.drawable.ic_ms_apps, "应用", () -> launches++); mount(button); int left = button.getLeft(), top = button.getTop();
                event(button, now, 200, MotionEvent.ACTION_DOWN, 30, 30); event(button, now, 220, MotionEvent.ACTION_CANCEL, 30, 30); event(button, now, 240, MotionEvent.ACTION_UP, 30, 30);
                require(launches == 0 && !button.isPressed(), "canceled animated button cannot click");
                require(button.getScaleX() == 1 && button.getScaleY() == 1 && button.getLeft() == left && button.getTop() == top, "press changes drawing only, not hit geometry");
                event(button, now, 250, MotionEvent.ACTION_DOWN, 30, 30); secondPointer(button, now, 270); event(button, now, 290, MotionEvent.ACTION_UP, 30, 30);
                require(launches == 0 && !button.isPressed(), "second pointer cancels runtime button without a later click");
                RuntimeVisuals.Cell cell = new RuntimeVisuals.Cell(activity); cell.setOnClickListener(v -> launches++); mount(cell);
                event(cell, now, 300, MotionEvent.ACTION_DOWN, 30, 30); secondPointer(cell, now, 320); event(cell, now, 340, MotionEvent.ACTION_UP, 30, 30);
                require(launches == 0 && !cell.isPressed(), "second pointer cancels tile or application cell");
                LinearLayout rowSurface = Ui.column(activity); rowSurface.setMinimumHeight(Ui.dp(activity, 60)); LinearLayout actions = Ui.row(activity);
                View action = Ui.button(activity, "清除", () -> launches++); actions.addView(action);
                NotificationSwipeRow row = new NotificationSwipeRow(activity, rowSurface, actions, () -> { }); mount(row);
                event(row, now, 300, MotionEvent.ACTION_DOWN, 400, 60); event(row, now, 330, MotionEvent.ACTION_MOVE, 220, 60); event(row, now, 360, MotionEvent.ACTION_CANCEL, 220, 60);
                rowSurface.animate().cancel(); require(!(boolean) field(row, "targetOpen") && launches == 0, "notification cancel retains closed target and never deletes");
                rowSurface.setTranslationX(0); event(row, now, 400, MotionEvent.ACTION_DOWN, 400, 60); event(row, now, 430, MotionEvent.ACTION_MOVE, 220, 60); event(row, now, 460, MotionEvent.ACTION_UP, 180, 60);
                require((boolean) field(row, "targetOpen") && launches == 0, "notification fling reveals actions without deleting");
            });
            checkCrossControlCancellation();
            AppHubView[] holder = {null};
            main(() -> { holder[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { launches++; } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { } }); holder[0].setExpanded(false); mount(holder[0]); });
            AppHubView hub = holder[0];
            main(() -> {
                View dock = hub.findViewWithTag("hub-dock"), content = (View) field(hub, "appContent"); int[] origin = new int[2]; dock.getLocationInWindow(origin);
                hub.setExpanded(true);
                if (ValueAnimator.areAnimatorsEnabled()) {
                    ValueAnimator opening = (ValueAnimator) field(hub, "contentAnimation"); require(opening != null, "expanded content has transition"); opening.pause(); opening.setCurrentFraction(.45f);
                    float alpha = content.getAlpha(), y = content.getTranslationY(); hub.setExpanded(false);
                    require(Math.abs(content.getAlpha() - alpha) < .001f && Math.abs(content.getTranslationY() - y) < .001f, "reverse collapse starts from visible frame");
                    ValueAnimator closing = (ValueAnimator) field(hub, "contentAnimation"); closing.pause(); closing.setCurrentFraction(.4f); alpha = content.getAlpha(); hub.setExpanded(true);
                    require(Math.abs(content.getAlpha() - alpha) < .001f, "reopen starts at partial collapse"); ((ValueAnimator) field(hub, "contentAnimation")).end();
                }
                require(content.getVisibility() == View.VISIBLE && content.getAlpha() == 1 && content.getTranslationY() == 0, "opening settles exactly");
                hub.measure(View.MeasureSpec.makeMeasureSpec(hub.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(hub.getHeight(), View.MeasureSpec.EXACTLY));
                hub.layout(hub.getLeft(), hub.getTop(), hub.getRight(), hub.getBottom());
                AppWorkspaceView workspace = hub.findViewWithTag("hub-grid"); int beforeLaunches = launches;
                require(workspace.getWidth() > 120, "workspace fixture has completed its visible layout");
                workspace.pageOffset(0, -60); float beforeOffset = (float) field(workspace, "offset"); long time = SystemClock.uptimeMillis(); event(workspace, time, 0, MotionEvent.ACTION_DOWN, 60, 40);
                require(Math.abs((float) field(workspace, "offset") - beforeOffset) < .001f, "workspace DOWN preserves in-flight position");
                event(workspace, time, 30, MotionEvent.ACTION_UP, 60, 40); require(launches == beforeLaunches, "tap taking over moving page cannot launch displaced icon"); workspace.cancelInteraction();
                workspace.mode(true); workspace.moveTo(apps.get(0).id(), workspace.capacity());
                int last = workspace.pageCount() - 1; require(last >= 1, "interruption fixture contains adjacent real application pages");
                workspace.settlePage(last, false); workspace.pageOffset(120, -40);
                require(Math.abs((float) field(workspace, "offset") - 80) < .001f, "last-page takeover follows finger one to one before reaching boundary");
                workspace.pageOffset(workspace.getWidth() * .8f, 0);
                require(workspace.interruptedRelease(0, false) == last - 1 && workspace.interruptedRelease(-900, true) == last, "interrupted release uses visible position and velocity without skipping a page");
                workspace.settlePage(0, false); workspace.pageOffset(-120, 40);
                require(Math.abs((float) field(workspace, "offset") + 80) < .001f, "first-page takeover follows finger one to one between pages");
                workspace.pageOffset(40, 0); require(Math.abs((float) field(workspace, "offset") - 40) < .001f, "taking over resisted edge does not jump");
                workspace.pageOffset(40, 20); require(Math.abs((float) field(workspace, "offset") - 44.4f) < .001f, "only movement beyond actual page boundary is damped");
                workspace.moveTo(apps.get(0).id(), workspace.capacity() * 3); workspace.settlePage(2, false);
                float pageWidth = workspace.getWidth(); workspace.pageOffset(2, pageWidth * .75f, pageWidth * .8f);
                require(workspace.page() == 1 && Math.abs((float) field(workspace, "offset") - pageWidth * .55f) < .01f, "reverse takeover rebases before an unmounted page could be exposed");
                workspace.layout(workspace.getLeft(), workspace.getTop(), workspace.getRight(), workspace.getBottom());
                require(workspace.getChildCount() <= workspace.capacity() * 3, "rebasing preserves the three-page mount limit");
                workspace.pageOffset(2, pageWidth * .75f, pageWidth * .9f);
                require(workspace.page() == 1 && Math.abs((float) field(workspace, "offset") - pageWidth * .65f) < .01f, "continued finger delta stays continuous after rebasing");
                workspace.stopPaging();
                int[] after = new int[2]; dock.getLocationInWindow(after); require(java.util.Arrays.equals(origin, after), "Dock remains anchored through expansion");
                hub.setExpanded(false);
                if (ValueAnimator.areAnimatorsEnabled()) { require(content.getVisibility() == View.VISIBLE, "collapsing content is retained until visual completion"); ((ValueAnimator) field(hub, "contentAnimation")).end(); }
                require(content.getVisibility() == View.GONE && !hub.expanded(), "collapse reaches original compact state");
                hub.showTasks(true); require(field(hub, "contentAnimation") == null && content.getVisibility() == View.GONE, "task page cancels catalog reveal and keeps catalog hidden");
                hub.showTasks(false); hub.setExpanded(false); hub.setExpanded(true); hub.dispose(); require(field(hub, "contentAnimation") == null, "dispose releases content animator");
            });
            return "PASS: runtime feedback; " + assertions + " assertions; animations=" + ValueAnimator.areAnimatorsEnabled();
        } finally { main(activity::finish); }
    }
    private void checkCrossControlCancellation() {
        PanelSurface[] holder = {null}; int[] clicks = {0}; long now = SystemClock.uptimeMillis();
        main(() -> {
            PanelSurface panel = new PanelSurface(activity, DockGeometry.BOTTOM, 620, false, new PanelHeaderView.Listener() { public void begin() { } public void progress(float value) { } public void finish(boolean close) { } });
            holder[0] = panel; panel.setOrientation(LinearLayout.HORIZONTAL); panel.setMotionEventSplittingEnabled(true);
            panel.addView(Ui.button(activity, "A", () -> clicks[0]++), new LinearLayout.LayoutParams(300, 120)); panel.addView(Ui.button(activity, "B", () -> clicks[0]++), new LinearLayout.LayoutParams(300, 120)); mount(panel);
            event(panel, now, 0, MotionEvent.ACTION_DOWN, 30, 30); twoPointers(panel, now, 20, MotionEvent.ACTION_POINTER_DOWN, 400);
            twoPointers(panel, now, 40, MotionEvent.ACTION_POINTER_UP, 400); event(panel, now, 60, MotionEvent.ACTION_UP, 30, 30);
        });
        require(clicks[0] == 0, "panel cancels before pointers can split between two separate buttons");
        main(() -> { event(holder[0], now, 100, MotionEvent.ACTION_DOWN, 30, 30); event(holder[0], now, 140, MotionEvent.ACTION_UP, 30, 30); });
        require(clicks[0] == 1, "new single-finger sequence works after cross-control cancellation");
    }
    private void mount(View view) {
        FrameLayout root = new FrameLayout(activity); root.addView(view, new FrameLayout.LayoutParams(700, 620)); activity.setContentView(root);
        root.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(748, View.MeasureSpec.EXACTLY)); root.layout(0, 0, 720, 748);
    }
    private void event(View target, long time, int offset, int action, float x, float y) { MotionEvent event = MotionEvent.obtain(time, time + offset, action, x, y, 0); target.dispatchTouchEvent(event); event.recycle(); }
    private void secondPointer(View target, long time, int offset) {
        twoPointers(target, time, offset, MotionEvent.ACTION_POINTER_DOWN, 40);
    }
    private void twoPointers(View target, long time, int offset, int action, float secondX) {
        MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
        MotionEvent.PointerCoords[] coords = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
        for (int i = 0; i < 2; i++) { properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER; coords[i].x = i == 0 ? 30 : secondX; coords[i].y = 30; coords[i].pressure = 1; coords[i].size = 1; }
        MotionEvent event = MotionEvent.obtain(time, time + offset, action | 1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT, 2, properties, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        target.dispatchTouchEvent(event); event.recycle();
    }
}
