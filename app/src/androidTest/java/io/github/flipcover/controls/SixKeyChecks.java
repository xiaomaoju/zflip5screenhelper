package io.github.flipcover.controls;

import android.content.Intent;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import java.util.List;

/** Full key sequences against attached real pages; no real notification/task is changed. */
final class SixKeyChecks {
    private final UiSmokeInstrumentation test;
    private final int rotation;
    private MainActivity activity;
    private InputSurface root;
    private int assertions, backs, primary, secondary;
    SixKeyChecks(UiSmokeInstrumentation test, int rotation) { this.test = test; this.rotation = rotation; }
    private void require(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private void key(int key) { boolean[] ready = {false}; for (int i = 0; i < 100 && !ready[0]; i++) { main(() -> ready[0] = root.isAttachedToWindow() && root.getWidth() > 0 && !root.isLayoutRequested()); if (!ready[0]) SystemClock.sleep(16); } if (!ready[0]) throw new AssertionError("page layout unavailable before key"); main(() -> { root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)); root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key)); }); }
    private InputNavigation.Target target() { try { var field = InputSurface.class.getDeclaredField("target"); field.setAccessible(true); return (InputNavigation.Target) field.get(root); } catch (Exception failure) { throw new AssertionError(failure); } }
    private boolean more() { try { var field = InputSurface.class.getDeclaredField("moreSelected"); field.setAccessible(true); return field.getBoolean(root); } catch (Exception failure) { throw new AssertionError(failure); } }
    private void mount(View content) { root = new InputSurface(activity); root.navigation(() -> backs++, () -> { }); root.addView(content, new FrameLayout.LayoutParams(-1, -1)); activity.setContentView(root); root.requestFocus(); }
    String run() throws Exception {
        if (rotation >= 0) { require(test.getUiAutomation().setRotation(rotation), "rotation accepted"); test.waitForIdleSync(); SystemClock.sleep(1000); }
        android.app.Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
        activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)); test.waitForIdleSync();
        if (rotation >= 0) {
            test.getUiAutomation().setRotation(rotation);
            for (int i = 0; i < 50 && activity.getDisplay().getRotation() != rotation; i++) SystemClock.sleep(100);
            test.waitForIdleSync();
            if (activity.isDestroyed() && monitor.getLastActivity() instanceof MainActivity current) activity = current;
        }
        try { if (rotation >= 0) require(activity.getDisplay().getRotation() == rotation, "actual display rotation=" + activity.getDisplay().getRotation() + ", requested=" + rotation); commands(); settings(); controls(); notifications(); tasks(); launcher(); folder(); components(); identity(); }
        finally { test.removeMonitor(monitor); main(activity::finish); }
        return "PASS: six-key navigation; " + assertions + " assertions; attached pages and real key sequences, not Bluetooth hardware verification";
    }
    private void commands() {
        main(() -> {
            FrameLayout body = new FrameLayout(activity); Button button = new Button(activity); button.setText("Primary"); button.setOnClickListener(v -> primary++);
            InputNavigation.bind(button, "primary", InputNavigation.Region.CONTROL, button::performClick, () -> secondary++);
            body.addView(button, new FrameLayout.LayoutParams(240, 120)); mount(body);
        });
        key(KeyEvent.KEYCODE_ENTER); require(primary == 1, "Enter works before any arrow key");
        main(() -> { root.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, 0)); for (int i = 1; i < 8; i++) root.dispatchKeyEvent(new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER, i)); root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)); });
        require(primary == 2, "held confirmation executes once"); key(KeyEvent.KEYCODE_DPAD_RIGHT); require(more(), "right selects more without executing");
        key(KeyEvent.KEYCODE_ENTER); require(secondary == 1 && primary == 2, "more invokes secondary action");
        key(KeyEvent.KEYCODE_F6); require(root.findViewWithTag("input-shortcuts") != null, "F6 opens same-window shortcuts");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_F6); require(root.findViewWithTag("input-shortcuts") == null && target().key.equals("primary"), "F6 restores original focus");
        int oldBack = backs; main(() -> root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))); SystemClock.sleep(560); test.waitForIdleSync();
        require(root.findViewWithTag("input-shortcuts") != null, "500ms cancel opens shortcuts"); main(() -> root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ESCAPE)));
        require(backs == oldBack && root.findViewWithTag("input-shortcuts") != null, "long cancel release does not also go back"); key(KeyEvent.KEYCODE_ESCAPE);
        require(target().key.equals("primary") && backs == oldBack, "cancel exits shortcut layer only"); key(KeyEvent.KEYCODE_ESCAPE); require(backs == oldBack + 1, "short cancel invokes page back");
        main(() -> root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))); main(root::release); SystemClock.sleep(550); require(root.findViewWithTag("input-shortcuts") == null, "release cancels held key timer");
    }
    private void settings() {
        EditText[] editor = {null}; SettingsUi.Slider[] slider = {null}; int[] saved = {5};
        main(() -> {
            android.widget.LinearLayout content = Ui.column(activity); editor[0] = new EditText(activity); editor[0].setText("abcd"); editor[0].setTag("text"); content.addView(editor[0]);
            slider[0] = new SettingsUi.Slider(activity, 0, 10, 5, value -> { }, value -> saved[0] = value); slider[0].setTag("slider"); content.addView(slider[0]); mount(content);
        });
        main(() -> { editor[0].requestFocus(); editor[0].setSelection(2); }); key(KeyEvent.KEYCODE_DPAD_LEFT); require(editor[0].getSelectionStart() == 1, "touch-focused text arrows stay in editor without prior Enter");
        key(KeyEvent.KEYCODE_F6); key(KeyEvent.KEYCODE_ESCAPE); require(editor[0].hasFocus() && editor[0].getSelectionStart() == 1 && editor[0].getText().toString().equals("abcd"), "shortcut visit preserves text and caret");
        key(KeyEvent.KEYCODE_ESCAPE); key(KeyEvent.KEYCODE_DPAD_DOWN); require(target().view == slider[0], "down reaches slider after exiting text input");
        key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_DPAD_RIGHT); require(saved[0] > 5, "slider commits existing keyboard increment"); key(KeyEvent.KEYCODE_ESCAPE); require(saved[0] > 5, "cancel exits adjustment without falsely undoing value");
        main(() -> { DetailSheet sheet = new DetailSheet(activity, "Modal", () -> { View v = root.findViewWithTag("test-modal"); root.removeView(v); }); sheet.setTag("test-modal"); sheet.content.addView(Ui.button(activity, "Inside", () -> { })); root.addView(sheet, new FrameLayout.LayoutParams(-1, -1)); });
        key(KeyEvent.KEYCODE_ENTER); require(target().view != slider[0], "modal traps focus"); key(KeyEvent.KEYCODE_ESCAPE); SystemClock.sleep(400); test.waitForIdleSync(); require(target().view == slider[0], "modal cancel restores origin slider: " + target().key);
    }
    private void controls() {
        android.media.AudioManager audio = activity.getSystemService(android.media.AudioManager.class); int original = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC), initial = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC) / 2;
        try {
            main(() -> {
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, initial, 0);
                Prefs prefs = new Prefs(activity); prefs.data.edit().putInt("panel_columns", 3).putBoolean("panel_brightness", false).putBoolean("panel_volume", true).putBoolean("panel_media", false).putString("panel_tools_position", "side").commit(); prefs.saveActions("panel", List.of("back", "home", "recents", "app_dock", "app_hub", "input_devices"));
                CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs; Panels panels = new Panels(owner); panels.controlViewportHeight(500); mount(panels.build("controls"));
            });
            key(KeyEvent.KEYCODE_F6); key(KeyEvent.KEYCODE_F6); require(target().key.equals("control:back"), "normal control center starts on first tile");
            key(KeyEvent.KEYCODE_DPAD_RIGHT); require(more(), "control right reaches attached more"); key(KeyEvent.KEYCODE_DPAD_RIGHT); require(target().key.equals("control:home") && !more(), "right continues to next tile body");
            key(KeyEvent.KEYCODE_DPAD_LEFT); require(target().key.equals("control:back") && more(), "left includes preceding tile more"); key(KeyEvent.KEYCODE_DPAD_LEFT);
            for (int i = 0; i < 10 && target().region != InputNavigation.Region.TOOLS; i++) key(KeyEvent.KEYCODE_DPAD_RIGHT);
            require(target().view instanceof LevelSlider, "grid edge reaches real volume tool");
            key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_DPAD_UP); require(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > initial, "six-key adjustment uses original volume commit"); key(KeyEvent.KEYCODE_ESCAPE);
            require(InputNavigation.targets(root).stream().noneMatch(t -> "control-edit".equals(t.view.getTag())), "edit entry excluded from basic control graph");
        } finally { main(() -> audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, original, 0)); }
    }
    private void notifications() {
        int[] opened = {0}, cleared = {0}; NotificationCenterView[] page = {null};
        main(() -> {
            page[0] = new NotificationCenterView(activity, new NotificationCenterView.Actions() {
                public void open(android.service.notification.StatusBarNotification item) { opened[0]++; }
                public void settings(android.service.notification.StatusBarNotification item) { }
                public void permission() { }
                public void clear(List<android.service.notification.StatusBarNotification> items) { cleared[0] += items.size(); }
            });
            android.app.Notification notification = new android.app.Notification.Builder(activity, "fixture").setSmallIcon(R.drawable.ic_ms_notifications).setContentTitle("Input fixture").setContentText("Body").build();
            var item = new android.service.notification.StatusBarNotification(activity.getPackageName(), activity.getPackageName(), 701, "keys", 10001, 0, 0, notification, android.os.Process.myUserHandle(), System.currentTimeMillis());
            ScrollView scroll = new ScrollView(activity); scroll.addView(page[0]); mount(scroll); page[0].update(true, List.of(item));
        });
        key(KeyEvent.KEYCODE_ENTER); require(opened[0] == 1 && target().region == InputNavigation.Region.NOTICE, "notification body is a primary target, not only expand icon");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); key(KeyEvent.KEYCODE_ENTER); require(target().region != InputNavigation.Region.NOTICE, "notification more opens owned menu");
        key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_DPAD_DOWN); key(KeyEvent.KEYCODE_ENTER); require(cleared[0] == 1, "six keys reach clear notification");
    }
    private void tasks() {
        int[] opened = {0}; RecentTasksView[] page = {null};
        List<RecentTasks.Task> tasks = List.of(new RecentTasks.Task(901, 2, 0, "fixture/.A", "fixture", false), new RecentTasks.Task(902, 2, 0, "fixture/.B", "fixture", false), new RecentTasks.Task(903, 2, 0, "fixture/.C", "fixture", false), new RecentTasks.Task(904, 2, 0, "fixture/.D", "fixture", false), new RecentTasks.Task(905, 2, 0, "fixture/.E", "fixture", false), new RecentTasks.Task(906, 2, 0, "fixture/.F", "fixture", false));
        main(() -> {
            page[0] = new RecentTasksView(activity, new Prefs(activity), DockGeometry.BOTTOM, new RecentTasksView.Listener() {
                public void open(RecentTasks.Task task) { opened[0] = task.id(); } public void clear(List<RecentTasks.Task> items) { } public void refresh() { } public void apps() { } public void close() { } public void reopen(RecentTasks.Task task) { } public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> result) { }
            }); mount(page[0]); page[0].data(tasks, true, true, false, true, true, false, "");
        });
        key(KeyEvent.KEYCODE_ENTER); require(opened[0] == 901, "tasks default to current centered card"); key(KeyEvent.KEYCODE_DPAD_LEFT); SystemClock.sleep(350); test.waitForIdleSync(); key(KeyEvent.KEYCODE_ENTER); require(opened[0] == 902, "left centers and opens adjacent task");
        key(KeyEvent.KEYCODE_DPAD_UP); require(more(), "up selects task operations"); key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_ENTER); require(CoverApp.taskLocks(activity).contains(tasks.get(1)), "task menu toggles lock through six keys");
        main(() -> CoverApp.taskLocks(activity).clear());
        for (int i = 0; i < 4; i++) { key(KeyEvent.KEYCODE_DPAD_LEFT); SystemClock.sleep(150); test.waitForIdleSync(); }
        key(KeyEvent.KEYCODE_ENTER); require(opened[0] == 906, "carousel navigation crosses recycled card boundary");
        key(KeyEvent.KEYCODE_DPAD_LEFT); key(KeyEvent.KEYCODE_ENTER); require(opened[0] == 906, "task edge does not wrap");
        key(KeyEvent.KEYCODE_DPAD_DOWN); require(target().key.equals("tasks-clear"), "down selects existing clear action"); key(KeyEvent.KEYCODE_DPAD_UP); require(target().region == InputNavigation.Region.TASK, "up returns to task");
        main(() -> page[0].data(List.of(), true, true, false, true, true, false, "")); key(KeyEvent.KEYCODE_ENTER); require(root.findViewWithTag("tasks-empty") != null && target() != null, "empty task page keeps menu and return path"); main(page[0]::dispose);
    }
    private void launcher() {
        AppHubView[] hub = {null}; main(() -> {
            Prefs prefs = new Prefs(activity); prefs.launcherPage(0); hub[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } }); mount(hub[0]); hub[0].setExpanded(true);
        });
        SystemClock.sleep(500); test.waitForIdleSync(); key(KeyEvent.KEYCODE_F6); key(KeyEvent.KEYCODE_F6);
        AppWorkspaceView workspace = root.findViewWithTag("hub-grid"); require(workspace != null, "real launcher workspace mounted");
        main(() -> { List<InputNavigation.Target> items = InputNavigation.targets(root); require(items.stream().anyMatch(t -> t.region == InputNavigation.Region.PAGE), "workspace exposes logical pager without changing touch layout"); require(items.stream().filter(t -> t.region == InputNavigation.Region.APPS).allMatch(t -> t.view.getParent() == workspace), "workspace targets belong to current page"); });
        require(target().region == InputNavigation.Region.APPS, "launcher starts on application content");
        for (int i = 0; i < 4 && target().region != InputNavigation.Region.PAGE; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
        require(target().region == InputNavigation.Region.PAGE, "grid down boundary reaches explicit pager");
        int page = workspace.page(); key(KeyEvent.KEYCODE_DPAD_RIGHT); SystemClock.sleep(350); test.waitForIdleSync();
        require(workspace.page() == Math.min(page + 1, workspace.pageCount() - 1) && target().region == InputNavigation.Region.PAGE, "pager changes page while keeping pager selection");
        key(KeyEvent.KEYCODE_DPAD_UP); require(target().region == InputNavigation.Region.APPS, "pager returns to application region");
        main(hub[0]::dispose);
    }
    private void folder() {
        int[] launched = {0};
        main(() -> {
            Button opener = Ui.button(activity, "文件夹", () -> {
                DetailSheet sheet = new DetailSheet(activity, "文件夹", () -> { View view = root.findViewWithTag("fixture-folder"); root.removeView(view); }); sheet.setTag("fixture-folder");
                AppFolderGrid grid = new AppFolderGrid(activity, List.of("one", "two", "three"), false, id -> Ui.button(activity, id, null), new AppFolderGrid.Listener() {
                    public void launch(String id) { launched[0]++; } public void menu(View anchor, String id) { } public void reorder(List<String> ids) { } public boolean outside(float x, float y) { return false; } public boolean external(String id, float x, float y) { return false; } public void forward(android.view.MotionEvent event) { } public void endExternal() { }
                }); sheet.content.addView(grid); root.addView(sheet, new FrameLayout.LayoutParams(-1, -1)); sheet.enter(null);
            }); opener.setTag("folder-origin"); FrameLayout page = new FrameLayout(activity); page.addView(opener, new FrameLayout.LayoutParams(250, 100)); mount(page);
        });
        key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_ENTER); require(launched[0] == 1, "folder confirm launches first member");
        key(KeyEvent.KEYCODE_DPAD_RIGHT); String member = target().key; key(KeyEvent.KEYCODE_DPAD_UP); require(target().key.equals(member), "folder boundary stays inside member grid");
        key(KeyEvent.KEYCODE_ESCAPE); SystemClock.sleep(400); test.waitForIdleSync(); require(target().key.equals("folder-origin"), "folder cancel restores desktop folder");
    }
    private void identity() {
        main(() -> {
            android.widget.LinearLayout list = Ui.column(activity);
            for (int i = 0; i < 3; i++) { Button row = new Button(activity); row.setContentDescription("同名设备"); list.addView(row); }
            mount(list);
        });
        key(KeyEvent.KEYCODE_DPAD_DOWN); String key = target().key;
        key(KeyEvent.KEYCODE_F6); key(KeyEvent.KEYCODE_F6); require(key.equals(target().key), "duplicate labels restore the exact target");
        main(() -> {
            InputNavigation.Memory memory = new InputNavigation.Memory(); memory.save("settings:test", new InputNavigation.Position("device:second", 4, false)); memory.shortcut = 7;
            InputNavigation.Memory copy = new InputNavigation.Memory(); copy.restore(memory.state()); require(copy.shortcut == 7 && copy.positions.get("settings:test").key().equals("device:second"), "configuration restoration preserves bounded identity bookmarks");
        });
    }
    private void components() {
        main(() -> { FrameLayout component = new FrameLayout(activity); Button button = new Button(activity); button.setText("Widget action"); button.setOnClickListener(v -> primary++); component.addView(button, new FrameLayout.LayoutParams(200, 120)); InputNavigation.bind(component, "widget:test", InputNavigation.Region.WIDGET, null, null); FrameLayout page = new FrameLayout(activity); page.addView(component, new FrameLayout.LayoutParams(300, 200)); mount(page); });
        key(KeyEvent.KEYCODE_ENTER); require(target().region != InputNavigation.Region.WIDGET, "confirm enters component group"); key(KeyEvent.KEYCODE_ESCAPE); require(target().region == InputNavigation.Region.WIDGET, "cancel returns to component group before page exit");
        int before = primary;
        main(() -> { FrameLayout widget = new FrameLayout(activity); widget.setOnClickListener(v -> primary++); widget.addView(Ui.text(activity, "整卡点击", 14, Ui.TEXT)); InputNavigation.bind(widget, "widget:root", InputNavigation.Region.WIDGET, null, null); FrameLayout page = new FrameLayout(activity); page.addView(widget, new FrameLayout.LayoutParams(300, 200)); mount(page); });
        key(KeyEvent.KEYCODE_ENTER); key(KeyEvent.KEYCODE_ENTER); require(primary == before + 1, "root-only RemoteViews action remains reachable inside component");

    }
}
