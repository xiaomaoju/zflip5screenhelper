package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.ContextThemeWrapper;
import android.view.DisplayCutout;
import android.view.View;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

/** Real WindowInsets and View measurement; admitted only by the disposable-emulator runner. */
final class RuntimeSafeAreaChecks {
    private static final String[] FLAGS = {"status_enabled", "panel_media", "panel_blur"};
    private final Instrumentation test;
    private int assertions, cases;
    private File directory;
    private RuntimeSafeAreaChecks(Instrumentation test) { this.test = test; }
    static String run(Instrumentation test) throws Exception { return new RuntimeSafeAreaChecks(test).run(); }
    private String run() throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        SharedPreferences data = new Prefs(activity).data; Map<String, ?> before = data.getAll();
        directory = new File(activity.getFilesDir(), "runtime-safe-area");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create runtime-safe-area");
        try {
            test.runOnMainSync(() -> {
                Configuration config = new Configuration(activity.getResources().getConfiguration()); config.densityDpi = 340; config.fontScale = 1;
                Context context = new ContextThemeWrapper(activity.createConfigurationContext(config), R.style.AppTheme);
                Prefs prefs = new Prefs(context); prefs.data.edit().putBoolean("panel_media", false).putBoolean("panel_blur", false).commit();
                for (int rotation = 0; rotation < 4; rotation++) for (boolean cutout : new boolean[]{false, true}) for (boolean status : new boolean[]{false, true}) for (boolean navigation : new boolean[]{false, true}) {
                    try { check(context, prefs, rotation, cutout, status, navigation); } catch (Exception failure) { throw new AssertionError(failure); }
                }
            });
            return "PASS: runtime-safe-area; " + assertions + " assertions; " + cases + " cases; raw renders in files/runtime-safe-area; no Samsung window-routing proof";
        } finally {
            test.runOnMainSync(() -> {
                SharedPreferences.Editor restore = data.edit();
                for (String key : FLAGS) if (before.containsKey(key)) restore.putBoolean(key, (Boolean) before.get(key)); else restore.remove(key);
                restore.commit(); activity.finish();
            });
        }
    }
    private void check(Context context, Prefs prefs, int rotation, boolean hasCutout, boolean showStatus, boolean showNavigation) throws Exception {
        int width = rotation % 2 == 0 ? 748 : 720, height = rotation % 2 == 0 ? 720 : 748;
        Rect cut = switch (rotation) { case 0 -> new Rect(379, 654, 748, 720); case 1 -> new Rect(654, 0, 720, 369); case 2 -> new Rect(0, 0, 369, 66); default -> new Rect(0, 379, 66, 748); };
        Insets physical = !hasCutout ? Insets.NONE : switch (rotation) { case 0 -> Insets.of(0, 0, 0, 66); case 1 -> Insets.of(0, 0, 66, 0); case 2 -> Insets.of(0, 66, 0, 0); default -> Insets.of(66, 0, 0, 0); };
        DisplayCutout displayCutout = hasCutout ? new DisplayCutout(new Rect(physical.left, physical.top, physical.right, physical.bottom), List.of(cut)) : null;
        Insets navigation = switch (rotation) { case 0 -> Insets.of(84, 0, 0, 0); case 1 -> Insets.of(0, 0, 0, 84); case 2 -> Insets.of(0, 0, 84, 0); default -> Insets.of(0, 84, 0, 0); };
        Insets mandatory = Insets.of(0, 0, 0, 12), status = Insets.of(0, 22, 0, 0);
        WindowInsets metrics = new WindowInsets.Builder().setDisplayCutout(displayCutout)
            .setInsets(WindowInsets.Type.statusBars(), status).setVisible(WindowInsets.Type.statusBars(), true)
            .setInsets(WindowInsets.Type.mandatorySystemGestures(), mandatory)
            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), navigation)
            .setInsets(WindowInsets.Type.navigationBars(), showNavigation ? navigation : Insets.NONE).setVisible(WindowInsets.Type.navigationBars(), showNavigation).build();
        Insets expected = Insets.max(physical, Insets.max(status, Insets.max(mandatory, showNavigation ? navigation : Insets.NONE)));
        Insets actual = CoverService.contentInsets(metrics, displayCutout);
        require(expected.equals(actual), "current bars and mandatory edges only; hidden navigation must not reserve its old bounds");
        require(CoverService.contentInsets(null, displayCutout).equals(physical), "physical safe insets survive missing window metrics");
        DockGeometry.Box system = new DockGeometry.Box(actual.left, actual.top, width - actual.left - actual.right, height - actual.top - actual.bottom);
        List<DockGeometry.Box> cuts = hasCutout ? List.of(new DockGeometry.Box(cut.left, cut.top, cut.width(), cut.height())) : List.of();
        CoverService owner = new CoverService(); owner.screenContext = context; owner.prefs = prefs;
        prefs.data.edit().putBoolean("status_enabled", showStatus).commit();
        DockGeometry.Placement anchor = DockGeometry.resolve(width, height, cuts, 2.125f, (rotation + 3) % 4, .46f, .088f, hasCutout);
        owner.placement = DockGeometry.edgeTouch(anchor, width, height);
        DockGeometry.Box widget = owner.resolveContentGeometry(new Point(width, height), rotation, cuts, anchor, actual, Math.max(mandatory.bottom, showNavigation ? navigation.bottom : 0));
        DockGeometry.Box panel = owner.placement.panel(), hub = box(owner, "hubFrame"), statusBox = box(owner, "controlStatusBox"), frame = box(owner, "panelFrame");
        DockGeometry.Box available = box(owner, "hubAvailableFrame"), taskArea = box(owner, "taskFrame");
        for (DockGeometry.Box area : List.of(panel, hub, available, taskArea, widget, statusBox, box(owner, "cardSafeFrame"))) inside(area, system);
        if (rotation != android.view.Surface.ROTATION_180) { require(available.equals(hub), "other rotations keep the original launcher bounds"); require(taskArea.equals(hub), "other rotations keep the original task bounds"); }
        else {
            require(Math.abs((taskArea.y() - available.y()) - (available.bottom() - taskArea.bottom())) <= 1, "task viewport redistributes only spare vertical space");
            require(taskArea.height() == Math.min(hub.height(), available.height()), "task preview viewport keeps its previous height when it fits");
            require(Math.abs((panel.y() - available.y()) - (system.bottom() - panel.bottom())) <= 1, "panel viewport has balanced top and bottom gaps");
        }
        require(frame.equals(new DockGeometry.Box(0, 0, width, height)), "the backdrop viewport stays full screen");
        DockGeometry.Placement entry = (DockGeometry.Placement) field(owner, "panelEntryPlacement");
        if (entry.edge() == DockGeometry.TOP) {
            require(entry.visual().y() >= statusBox.bottom(), "entry artwork follows the status row below system top safety");
            require(entry.touch().y() == DockGeometry.panelContent(owner.placement, width, height, cuts).y(), "entry keeps its physical touch origin independently of content insets");
        }
        for (String page : List.of("controls", "notifications")) {
            InterfaceCard card = owner.buildPanelCard(page, frame, panel.y());
            try {
                measure(card, width, height); View content = card.getChildAt(0);
                inside(new DockGeometry.Box(content.getPaddingLeft(), content.getPaddingTop(), width - content.getPaddingLeft() - content.getPaddingRight(), height - content.getPaddingTop() - content.getPaddingBottom()), system);
                if (card.statusBar() != null) inside(new DockGeometry.Box(card.statusBar().getLeft(), card.statusBar().getTop(), card.statusBar().getWidth(), card.statusBar().getHeight()), system);
                RectF visual = new RectF(); card.visualBounds(visual); require(visual.equals(new RectF(0, 0, width, height)), "system edges do not shrink the glass backdrop");
                if (hasCutout && showStatus && showNavigation) {
                    picture(card, page + "-" + rotation);
                    if (rotation == android.view.Surface.ROTATION_180) {
                        int top = content.getPaddingTop(), bottom = content.getPaddingBottom();
                        int originalTop = entry.visual().y() + Ui.dp(context, 24);
                        DockGeometry.Box originalArea = new DockGeometry.Box(panel.x(), originalTop, panel.width(), system.bottom() - originalTop);
                        content.setPadding(content.getPaddingLeft(), originalTop + Ui.dp(context, 6), content.getPaddingRight(), height - system.bottom()); card.contentSafeBounds(originalArea);
                        measure(card, width, height); picture(card, page + "-" + rotation + "-before");
                        content.setPadding(content.getPaddingLeft(), top, content.getPaddingRight(), bottom); card.contentSafeBounds(panel); measure(card, width, height);
                    }
                }
                if (rotation == android.view.Surface.ROTATION_180 && page.equals("notifications")) {
                    NotificationCenterView center = card.findViewWithTag("notification-center"); View header = card.findViewWithTag("panel-header");
                    int headerTop = header.getTop(), viewportTop = content.getPaddingTop(), viewportBottom = content.getHeight() - content.getPaddingBottom();
                    java.util.ArrayList<android.service.notification.StatusBarNotification> messages = new java.util.ArrayList<>();
                    for (int i = 0; i < 12; i++) {
                        android.app.Notification notification = new android.app.Notification.Builder(context, "centering-fixture").setSmallIcon(R.drawable.ic_ms_notifications).setContentTitle("通知 " + i).setContentText("用于检查列表滚动时的稳定位置").build();
                        messages.add(new android.service.notification.StatusBarNotification(context.getPackageName(), context.getPackageName(), i, "centering", 10001, 0, 0, notification, android.os.Process.myUserHandle(), 0));
                    }
                    for (java.util.List<android.service.notification.StatusBarNotification> items : List.of(messages, java.util.List.<android.service.notification.StatusBarNotification>of())) {
                        center.update(true, items); measure(card, width, height);
                        require(header.getTop() == headerTop && content.getPaddingTop() == viewportTop && content.getHeight() - content.getPaddingBottom() == viewportBottom, "empty and long notification lists keep the same centered viewport");
                    }
                }
            } finally { card.release(); }
        }
        AppHubView.Listener listener = new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } };
        AppHubView launcher = new AppHubView(context, prefs, listener);
        FrameLayout host = owner.attachHubContent(launcher); InterfaceCard card = (InterfaceCard) host.getChildAt(0);
        try {
            measure(host, width, height);
            require(new DockGeometry.Box(launcher.getLeft(), launcher.getTop(), launcher.getWidth(), launcher.getHeight()).equals(available), "launcher consumes the resolved safe bounds once");
            AppHubView original = new AppHubView(context, prefs, listener);
            try {
                measure(original, hub.width(), Math.min(hub.height(), available.height()));
                View body = (View) launcher.findViewWithTag("hub-catalog").getParent(), oldBody = (View) original.findViewWithTag("hub-catalog").getParent();
                View dock = launcher.getChildAt(launcher.getChildCount() - 1), oldDock = original.getChildAt(original.getChildCount() - 1);
                require(body.getHeight() == oldBody.getHeight(), "centering retains sidebar and application body height");
                require(launcher.getTop() + dock.getTop() == hub.bottom() - original.getHeight() + oldDock.getTop(), "Dock stays at its original screen position");
                require(body.getWidth() == oldBody.getWidth(), "centering never changes horizontal geometry");
                int above = body.getTop() - launcher.getPaddingTop(), below = dock.getTop() - body.getBottom();
                require(Math.abs(above - below) <= 1, "launcher body divides released space equally above and below");
                launcher.setExpanded(false); measure(host, width, height); launcher.setExpanded(true); measure(host, width, height);
                require(launcher.getTop() + dock.getTop() == hub.bottom() - original.getHeight() + oldDock.getTop(), "Dock anchor survives collapse and expansion");
            } finally { original.dispose(); }
            if (hasCutout && showStatus && showNavigation) {
                picture(host, "launcher-" + rotation);
                if (rotation == android.view.Surface.ROTATION_180) {
                    launcher.centeringSpace(0); card.contentBounds(hub, frame); measure(host, width, height); picture(host, "launcher-" + rotation + "-before");
                    launcher.centeringSpace(hub.y() - available.y()); card.contentBounds(available, frame); measure(host, width, height);
                }
            }
        } finally { card.release(); host.removeAllViews(); }
        AppHubView tasks = new AppHubView(context, prefs, listener); tasks.showTasks(true);
        FrameLayout taskHost = owner.attachHubContent(tasks); InterfaceCard taskCard = (InterfaceCard) taskHost.getChildAt(0);
        try {
            java.lang.reflect.Method changed = CoverService.class.getDeclaredMethod("taskPageChanged", RecentTasksView.class); changed.setAccessible(true); changed.invoke(owner, tasks.taskPage());
            measure(taskHost, width, height);
            View stage = tasks.findViewWithTag("tasks-stage"); Rect bounds = new Rect(); stage.getDrawingRect(bounds); taskCard.offsetDescendantRectToMyCoords(stage, bounds);
            require(new DockGeometry.Box(bounds.left, bounds.top, bounds.width(), bounds.height()).equals(taskArea), "task stage consumes its centered safe frame without double insets");
            tasks.taskPage().data(List.of(new RecentTasks.Task(701, 2, 0, context.getPackageName() + "/.MainActivity", context.getPackageName(), false)), true, true, false, true, true, true, "图标模式");
            measure(taskHost, width, height);
            if (hasCutout && showStatus && showNavigation) {
                picture(taskHost, "tasks-" + rotation);
                if (rotation == android.view.Surface.ROTATION_180) {
                    tasks.taskPage().safeArea(hub, frame); taskCard.contentSafeBounds(hub); measure(taskHost, width, height); picture(taskHost, "tasks-" + rotation + "-before");
                    tasks.taskPage().safeArea(taskArea, frame); taskCard.contentSafeBounds(taskArea); measure(taskHost, width, height);
                }
            }
            for (int count : new int[]{0, 6}) {
                java.util.ArrayList<RecentTasks.Task> items = new java.util.ArrayList<>();
                for (int i = 0; i < count; i++) items.add(new RecentTasks.Task(701 + i, 2, 0, context.getPackageName() + "/.MainActivity", context.getPackageName(), false));
                tasks.taskPage().data(items, true, true, false, true, true, true, "图标模式"); measure(taskHost, width, height);
                Rect next = new Rect(); stage.getDrawingRect(next); taskCard.offsetDescendantRectToMyCoords(stage, next);
                require(next.equals(bounds), "empty and populated task lists keep the same centered viewport");
            }
        } finally { taskCard.release(); taskHost.removeAllViews(); }
        cases++;
    }
    private static Object field(CoverService owner, String name) throws Exception { Field field = CoverService.class.getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private static DockGeometry.Box box(CoverService owner, String name) throws Exception { return (DockGeometry.Box) field(owner, name); }
    private static void measure(View view, int width, int height) { view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height); }
    private void inside(DockGeometry.Box area, DockGeometry.Box safe) { require(area.width() > 0 && area.height() > 0 && area.x() >= safe.x() && area.y() >= safe.y() && area.right() <= safe.right() && area.bottom() <= safe.bottom(), "content " + area + " must stay inside " + safe); }
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private void picture(View view, String name) throws Exception {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { view.draw(new Canvas(bitmap)); bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { bitmap.recycle(); }
    }
}
