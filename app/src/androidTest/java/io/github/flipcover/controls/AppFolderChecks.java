package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real touch paths on a disposable emulator. Never runs against a physical phone. */
final class AppFolderChecks {
    private final Instrumentation test;
    private final int rotation;
    private MainActivity activity;
    private AppHubView hub;
    private AppWorkspaceView grid;
    private FrameLayout root;
    private Prefs prefs;
    private List<String> apps;
    private int assertions, launches;
    private boolean fullHost;
    private boolean educationChecked;
    private long down;
    AppFolderChecks(Instrumentation test, int rotation) { this.test = test; this.rotation = rotation; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private void mount() {
        main(() -> { if (hub != null) hub.dispose(); hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { launches++; } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
            // The functional fixture models a cover-sized overlay host, with its own 24dp safe bands.
            // The layout fixture keeps Android's actual bars to exercise a separately constrained host.
            android.view.WindowInsetsController bars = activity.getWindow().getInsetsController(); if (fullHost) { bars.setSystemBarsBehavior(android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE); bars.hide(android.view.WindowInsets.Type.systemBars()); } else bars.show(android.view.WindowInsets.Type.systemBars());
            root = new FrameLayout(activity); root.setBackgroundColor(Ui.BACKGROUND); root.setOnApplyWindowInsetsListener((view, insets) -> { android.graphics.Insets safe = insets.getInsets((fullHost ? 0 : android.view.WindowInsets.Type.systemBars()) | android.view.WindowInsets.Type.displayCutout()); view.setPadding(safe.left, safe.top, safe.right, safe.bottom); return insets; }); FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -1); params.setMargins(Ui.dp(activity, 8), fullHost ? Ui.dp(activity, 24) : 0, Ui.dp(activity, 8), fullHost ? Ui.dp(activity, 24) : 0); root.addView(hub, params); activity.setContentView(root); root.requestApplyInsets(); grid = hub.findViewWithTag("hub-grid"); }); SystemClock.sleep(250); test.waitForIdleSync();
        if (fullHost && !educationChecked) { educationChecked = true; for (int i = 0; i < 8; i++) { android.view.accessibility.AccessibilityNodeInfo window = test.getUiAutomation().getRootInActiveWindow(); boolean closed = false; if (window != null) for (android.view.accessibility.AccessibilityNodeInfo button : window.findAccessibilityNodeInfosByText("Got it")) closed |= button.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK); if (closed) { SystemClock.sleep(150); break; } SystemClock.sleep(100); } }
    }
    private void touch(View view, int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis();
        main(() -> { int[] origin = new int[2]; view.getLocationOnScreen(origin); MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x + origin[0], y + origin[1], 0); event.offsetLocation(-origin[0], -origin[1]); view.dispatchTouchEvent(event); event.recycle(); });
    }
    private float x(int slot) { return ((slot % grid.capacity()) % grid.getNumColumns() + .5f) * (grid.getWidth() / grid.getNumColumns()); }
    private float y(int slot) { return ((slot % grid.capacity()) / grid.getNumColumns() + .5f) * (grid.gridHeight() / (grid.capacity() / grid.getNumColumns())); }
    private void lift(String id) { int slot = grid.projected().slot(id); main(() -> grid.settlePage(slot / grid.capacity(), false)); touch(grid, MotionEvent.ACTION_DOWN, x(slot), y(slot)); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 60); test.waitForIdleSync(); require(grid.dragging(), "long press begins drag"); }
    private void hover(String target) { int slot = grid.projected().slot(target); touch(grid, MotionEvent.ACTION_MOVE, x(slot), y(slot)); }
    private void release(String target) { int slot = grid.projected().slot(target); touch(grid, MotionEvent.ACTION_UP, x(slot), y(slot)); SystemClock.sleep(200); test.waitForIdleSync(); }
    private void reset() { main(() -> { grid.cancelInteraction(); hub.back(); prefs.data.edit().putString("hub_sort", "manual").putBoolean("hub_workspace_locked", false).putBoolean("hub_workspace_compact", false).apply(); prefs.saveHubPins(List.of()); prefs.saveWorkspace(AppWorkspaceLayout.sequential(apps), false); }); mount(); }
    private View text(View view, String title) { if (view instanceof TextView label && title.contentEquals(label.getText())) return view; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = text(group.getChildAt(i), title); if (found != null) return found; } return null; }
    private void click(String title) {
        View direct = text(root, title); if (direct != null) { main(direct::performClick); return; }
        for (int attempt = 0; attempt < 8; attempt++) {
            SystemClock.sleep(150); android.view.accessibility.AccessibilityNodeInfo node = test.getUiAutomation().getRootInActiveWindow();
            if (node != null) {
                for (android.view.accessibility.AccessibilityNodeInfo item : node.findAccessibilityNodeInfosByText(title)) if (title.contentEquals(item.getText())) {
                    for (int parent = 0; item != null && parent < 4; parent++, item = item.getParent()) if (item.isClickable() && item.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) { SystemClock.sleep(150); test.waitForIdleSync(); return; }
                }
                scrollNode(node);
            }
        }
        try { frame("missing-control"); } catch (Exception ignored) { }
        throw new AssertionError("Missing control: " + title);
    }
    private boolean scrollNode(android.view.accessibility.AccessibilityNodeInfo node) { if (node.isScrollable() && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true; for (int i = 0; i < node.getChildCount(); i++) { android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i); if (child != null && scrollNode(child)) return true; } return false; }
    private void frame(String name) throws Exception { SystemClock.sleep(260); Bitmap bitmap = test.getUiAutomation().takeScreenshot(); File dir = new File(activity.getFilesDir(), "folder-raw"); dir.mkdirs(); try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); } finally { bitmap.recycle(); } }
    private void toolsChecks() throws Exception {
        reset(); AppWorkspaceLayout before = prefs.workspace(); main(() -> grid.menu("")); click("新建文件夹 / 多选整理");
        android.widget.ListView selection = root.findViewWithTag("workspace-selection"); require(selection != null && selection.getChildCount() >= 3, "multi-select opens a real bounded application list");
        main(() -> { for (int i = 0; i < 3; i++) selection.getChildAt(i).performClick(); }); require(before.equals(prefs.workspace()), "selection draft does not change layout");
        click("选择操作…"); click("新建文件夹（2–9个）"); require(before.equals(prefs.workspace()), "batch preview still does not persist"); click("完成整理");
        require(prefs.workspace().folders().size() == 1, "batch confirmation creates one folder"); String folder = prefs.workspace().folders().keySet().iterator().next(); require(prefs.workspace().folder(folder).members().size() == 3, "selected three members are moved once");
        main(() -> grid.menu(folder)); click("重命名"); EditText name = root.findViewWithTag("workspace-name"); require(name != null, "rename opens a real text field"); main(() -> name.setText("三件工具")); click("保存"); require(prefs.workspace().folder(folder).name().equals("三件工具"), "rename Save commits name");
        AppFolderGrid members = root.findViewWithTag("folder-grid"); require(members != null, "rename returns to open folder"); String moving = prefs.workspace().folder(folder).members().get(0); View child = members.getChildAt(0);
        touch(members, MotionEvent.ACTION_DOWN, child.getWidth() / 2f, child.getHeight() / 2f); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 70);
        touch(members, MotionEvent.ACTION_MOVE, -Ui.dp(activity, 35), child.getHeight() / 2f); SystemClock.sleep(430); test.waitForIdleSync(); require(grid.dragging(), "holding outside card transfers same member drag to workspace");
        require(prefs.workspace().parent(moving).equals(folder), "external preview retains source ownership until drop");
        main(() -> grid.settlePage(grid.pageCount(), false)); int destination = grid.page() * grid.capacity() + 2; int[] gridOrigin = new int[2], memberOrigin = new int[2]; main(() -> { grid.getLocationOnScreen(gridOrigin); members.getLocationOnScreen(memberOrigin); });
        float dropX = gridOrigin[0] + x(destination) - memberOrigin[0], dropY = gridOrigin[1] + y(destination) - memberOrigin[1]; touch(members, MotionEvent.ACTION_MOVE, dropX, dropY); touch(members, MotionEvent.ACTION_UP, dropX, dropY);
        require(prefs.workspace().parent(moving) == null && prefs.workspace().folder(folder).members().size() == 2, "external release extracts exactly once"); require(root.findViewWithTag("folder-sheet") == null, "external release removes invisible input sheet");
        main(grid::undo); require(folder.equals(prefs.workspace().parent(moving)), "undo extraction restores source folder and positions");
        main(() -> { prefs.saveHubPins(List.of(apps.get(0), apps.get(1), apps.get(2), apps.get(3))); hub.workspaceOptionsChanged(); }); String dockApp = apps.get(10); AppWorkspaceLayout dockBefore = prefs.workspace(); lift(dockApp);
        ViewGroup dock = hub.findViewWithTag("hub-dock"); View target = dock.getChildAt(2); int[] dockOrigin = new int[2]; main(() -> { target.getLocationOnScreen(dockOrigin); grid.getLocationOnScreen(gridOrigin); });
        float dockX = dockOrigin[0] + target.getWidth() / 2f - gridOrigin[0], dockY = dockOrigin[1] + target.getHeight() / 2f - gridOrigin[1]; touch(grid, MotionEvent.ACTION_MOVE, dockX, dockY); require(prefs.hubPins().equals(List.of(apps.get(0), apps.get(1), apps.get(2), apps.get(3))), "full Dock hover does not save");
        touch(grid, MotionEvent.ACTION_UP, dockX, dockY); require(prefs.hubPins().equals(List.of(apps.get(0), apps.get(1), apps.get(2), apps.get(3))), "full Dock rejects an addition without replacing an existing reference"); require(dockBefore.equals(prefs.workspace()), "rejected Dock drop leaves workspace ownership unchanged");
        main(() -> grid.compact(true)); main(grid::undo); require(!prefs.workspaceCompact() && dockBefore.equals(prefs.workspace()), "undo automatic packing restores both cells and compact mode");
        main(() -> hub.findViewWithTag("hub-sort").performClick()); click("页面管理"); require(text(root, "查看") != null, "page management shows navigable pages"); main(hub::back); SystemClock.sleep(200);
        main(() -> hub.findViewWithTag("hub-sort").performClick()); click("桌面密度"); click("易点"); require(grid.getNumColumns() == 5 && grid.capacity() == 15 && dockBefore.equals(prefs.workspace()), "larger icon preset keeps the fixed five-by-three grid and saved positions");
        main(() -> hub.findViewWithTag("hub-sort").performClick()); click("桌面密度"); click("紧凑");
        main(() -> { prefs.data.edit().putBoolean("hub_workspace_badges", true).apply(); grid.settlePage(0, false); });
        String app = apps.get(9), packageName = ActionCatalog.component(app).getPackageName(); android.app.Notification notification = new android.app.Notification.Builder(activity, "fixture").setContentTitle("通知").setSmallIcon(R.drawable.ic_ms_apps).build();
        android.service.notification.StatusBarNotification item = new android.service.notification.StatusBarNotification(packageName, packageName, 9028, "folder-fixture", android.os.Process.myUid(), 0, 0, notification, android.os.Process.myUserHandle(), System.currentTimeMillis());
        main(() -> { hub.notificationBadges(true, List.of(item)); grid.settlePage(grid.projected().slot(app) / grid.capacity(), false); });
        boolean badge = false; for (int i = 0; i < grid.getChildCount(); i++) if (grid.getChildAt(i).getContentDescription().toString().contains("1条活动通知")) badge = true; require(badge, "authorized active notification produces a truthful count badge");
        main(() -> hub.notificationBadges(false, List.of(item))); for (int i = 0; i < grid.getChildCount(); i++) require(!grid.getChildAt(i).getContentDescription().toString().contains("条活动通知"), "disconnected access removes badges without retaining unread history");
        main(() -> { hub.notificationBadges(true, List.of(item)); hub.setExpanded(false); hub.setExpanded(true); });
        for (int i = 0; i < grid.getChildCount(); i++) require(!grid.getChildAt(i).getContentDescription().toString().contains("条活动通知"), "re-expansion re-reads disconnected access instead of showing an old badge");
        frame("tools-complete");
    }
    String runLayout() throws Exception {
        activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); prefs = new Prefs(activity); apps = new ArrayList<>(); for (AppCatalogCache.Entry entry : CoverApp.catalog(activity).entriesBlocking()) apps.add(entry.id());
        require(apps.size() >= 9, "nine installed apps available"); require(rotation < 0 || activity.getDisplay().getRotation() == rotation, "layout uses actual requested direction");
        prefs.data.edit().clear().putString("hub_sort", "manual").commit(); AppWorkspaceLayout stored = AppWorkspaceLayout.sequential(apps).create(apps.subList(0, 9), "九宫格文件夹", 0); prefs.saveWorkspace(stored, false); mount();
        try {
            String id = stored.parent(apps.get(0)); require(grid.projected().folder(id).members().size() == 9 && stored.equals(prefs.workspace()), "resizing keeps canonical 2x2 folder and nine members");
            if (grid.projected().span(id) == 1) { boolean numbered = false; for (int i = 0; i < grid.getChildCount(); i++) if (grid.getChildAt(i) instanceof AppFolderTile tile) numbered |= ((TextView) tile.getChildAt(9)).getText().toString().startsWith("9 · "); require(numbered, "one-row compact folder visibly preserves member count"); }
            main(() -> grid.launch(id)); AppFolderGrid members = root.findViewWithTag("folder-grid"); require(members != null && members.getChildCount() == 9, "open panel always provides all nine members");
            for (int i = 0; i < members.getChildCount(); i++) { ViewGroup cell = (ViewGroup) members.getChildAt(i); TextView name = (TextView) cell.getChildAt(1); require(name.getLayout() != null && name.getHeight() >= name.getLayout().getHeight() + name.getCompoundPaddingTop() + name.getCompoundPaddingBottom(), "member label fully fits its natural row"); require(cell.getWidth() >= Ui.dp(activity, 48), "three-column members keep 48dp width"); }
            android.widget.ScrollView scroll = root.findViewWithTag("detail-scroll"); require(scroll.getHeight() > 0, "body remains scrollable and visible below header"); main(() -> scroll.fullScroll(View.FOCUS_DOWN)); frame("folder-large-bottom");
            View last = members.getChildAt(8); android.graphics.Rect visible = new android.graphics.Rect(); require(last.getGlobalVisibleRect(visible) && visible.height() > 0, "last member can be reached by scrolling");
            main(hub::back); SystemClock.sleep(200); require(root.findViewWithTag("folder-sheet") == null && hub.expanded(), "folder can close under large fonts");
            return "PASS: folder-layout; " + assertions + " assertions; density=" + activity.getResources().getConfiguration().densityDpi + "; font=" + activity.getResources().getConfiguration().fontScale + "; rotation=" + activity.getDisplay().getRotation();
        } finally { main(() -> { hub.dispose(); activity.finish(); }); }
    }
    String runTools() throws Exception {
        fullHost = true; activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); prefs = new Prefs(activity); apps = new ArrayList<>(); for (AppCatalogCache.Entry entry : CoverApp.catalog(activity).entriesBlocking()) apps.add(entry.id()); prefs.data.edit().clear().putString("hub_sort", "manual").commit(); mount();
        try { toolsChecks(); return "PASS: folder-tools; " + assertions + " assertions"; } finally { main(() -> { hub.dispose(); activity.finish(); }); }
    }
    private float[] point(View view, float x, float y) { int[] origin = new int[2], host = new int[2]; main(() -> { view.getLocationOnScreen(origin); root.getLocationOnScreen(host); }); return new float[]{origin[0] - host[0] + x, origin[1] - host[1] + y}; }
    private void rootTouch(int action, float[] point) { touch(root, action, point[0], point[1]); }
    private void assertOwnership() {
        java.util.Set<String> desktop = prefs.workspace().apps(), all = new java.util.HashSet<>(desktop); all.addAll(prefs.hubPins());
        require(all.equals(new java.util.HashSet<>(apps)), "desktop plus Dock retains every installed app");
        require(java.util.Collections.disjoint(desktop, prefs.hubPins()), "fixed apps never duplicate desktop or folder ownership");
    }
    private void rootLift(String id) {
        int slot = grid.projected().slot(id); main(() -> grid.settlePage(slot / grid.capacity(), false)); float[] start = point(grid, x(slot), y(slot)); rootTouch(MotionEvent.ACTION_DOWN, start); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); test.waitForIdleSync(); require(grid.dragging(), "root touch long press lifts real workspace icon");
    }
    private float[] dockPoint(int child, float fraction) { SystemClock.sleep(180); test.waitForIdleSync(); ViewGroup dock = hub.findViewWithTag("hub-dock"); View item = dock.getChildAt(child); return point(item, item.getWidth() * fraction, item.getHeight() / 2f); }
    private void assertActionsInsideHost() {
        View sheet = root.findViewWithTag("workspace-actions"); require(sheet != null, "long hold opens same-window action sheet");
        View card = sheet.findViewWithTag("detail-card"); android.graphics.Rect bounds = new android.graphics.Rect(), host = new android.graphics.Rect(); card.getGlobalVisibleRect(bounds); hub.getGlobalVisibleRect(host);
        require(host.contains(bounds) && bounds.height() == card.getHeight(), "menu card is fully inside external-display host, including bottom row");
        require(sheet.getWindowToken() == hub.getWindowToken(), "context menu never allocates a separate popup window");
        android.widget.ScrollView scroll = sheet.findViewWithTag("detail-scroll"); main(() -> { scroll.setSmoothScrollingEnabled(false); scroll.fullScroll(View.FOCUS_DOWN); });
        View last = ((ViewGroup) scroll.getChildAt(0)).getChildAt(((ViewGroup) scroll.getChildAt(0)).getChildCount() - 1); require(last.getGlobalVisibleRect(bounds) && bounds.height() == last.getHeight(), "last menu action is reachable inside scrolling body");
    }
    String runDockMenu() throws Exception {
        fullHost = true; activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); prefs = new Prefs(activity); apps = new ArrayList<>(); for (AppCatalogCache.Entry entry : CoverApp.catalog(activity).entriesBlocking()) apps.add(entry.id()); prefs.data.edit().clear().putString("hub_sort", "manual").commit(); mount();
        try {
            require(rotation < 0 || activity.getDisplay().getRotation() == rotation, "requested rotation is actually applied"); require(apps.size() >= 10, "real installed application fixture available");
            AppWorkspaceLayout original = prefs.workspace(); String incoming = apps.get(4);
            rootLift(incoming); SystemClock.sleep(1200); int slot = grid.projected().slot(incoming); rootTouch(MotionEvent.ACTION_UP, point(grid, x(slot), y(slot))); SystemClock.sleep(300); assertActionsInsideHost(); frame("menu-bounded"); require(original.equals(prefs.workspace()), "stationary long hold never moves icon"); main(hub::back); SystemClock.sleep(200);
            main(() -> { prefs.saveHubPins(List.of(apps.get(0), apps.get(1))); hub.workspaceOptionsChanged(); }); float[] middle = dockPoint(2, .1f);
            rootLift(incoming); rootTouch(MotionEvent.ACTION_MOVE, middle); require(prefs.hubPins().equals(List.of(apps.get(0), apps.get(1))), "insertion preview remains a draft"); require(((ViewGroup) hub.findViewWithTag("hub-dock")).getChildCount() == 4, "Dock opens a visible third position before release"); frame("dock-insertion");
            rootTouch(MotionEvent.ACTION_UP, middle); require(prefs.hubPins().equals(List.of(apps.get(0), incoming, apps.get(1))), "release inserts between fixed references in pointer order"); assertOwnership(); require(grid.getCount() == apps.size() - 3, "pinned apps disappear from the normal application list"); require(!grid.dragging() && root.findViewWithTag("workspace-actions") == null, "release neither opens a menu nor retains drag state");
            main(() -> hub.dockPreferencesChanged()); require(root.getChildAt(0) == hub && prefs.hubPins().get(1).equals(incoming), "pin preference refresh preserves the mounted application center");
            mount(); require(prefs.hubPins().equals(List.of(apps.get(0), incoming, apps.get(1))), "inserted order survives complete hub reconstruction");
            float[] fourthPosition = dockPoint(3, .9f); rootLift(apps.get(5)); rootTouch(MotionEvent.ACTION_MOVE, fourthPosition); require(prefs.hubPins().size() == 3, "fourth pin remains a preview until release"); rootTouch(MotionEvent.ACTION_UP, fourthPosition); require(prefs.hubPins().equals(List.of(apps.get(0), incoming, apps.get(1), apps.get(5))), "fourth application can be pinned after the existing three"); assertOwnership(); require(grid.getCount() == apps.size() - 4, "all four pinned applications disappear from the normal list");
            rootLift(apps.get(6)); float[] full = dockPoint(2, .5f); rootTouch(MotionEvent.ACTION_MOVE, full); rootTouch(MotionEvent.ACTION_UP, full); require(prefs.hubPins().equals(List.of(apps.get(0), incoming, apps.get(1), apps.get(5))), "fifth application is rejected without silent replacement");
            float[] last = dockPoint(4, .5f), first = dockPoint(0, .6f); rootTouch(MotionEvent.ACTION_DOWN, last); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); rootTouch(MotionEvent.ACTION_MOVE, first); require(grid.dragging(), "long-press fixed icon can enter root-owned drag"); main(grid::refresh); require(grid.dragging(), "a content refresh retains a valid hidden Dock source"); rootTouch(MotionEvent.ACTION_UP, first); require(prefs.hubPins().equals(List.of(apps.get(5), apps.get(0), incoming, apps.get(1))), "fourth fixed icon reorders to first position");
            first = dockPoint(1, .5f); float[] outside = point(grid, grid.getWidth() / 2f, grid.gridHeight() / 2f); rootTouch(MotionEvent.ACTION_DOWN, first); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); rootTouch(MotionEvent.ACTION_MOVE, outside); rootTouch(MotionEvent.ACTION_CANCEL, outside); require(prefs.hubPins().size() == 4 && !grid.dragging(), "canceled Dock removal restores four references");
            first = dockPoint(1, .5f); int restoredSlot = grid.dropSlot(grid.getWidth() / 2f, grid.gridHeight() / 2f); rootTouch(MotionEvent.ACTION_DOWN, first); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); rootTouch(MotionEvent.ACTION_MOVE, outside); rootTouch(MotionEvent.ACTION_UP, outside); require(prefs.hubPins().equals(List.of(apps.get(0), incoming, apps.get(1))), "dragging fixed icon out removes its Dock reference"); assertOwnership(); require(grid.projected().slot(apps.get(5)) == restoredSlot, "drag-out restores app at the chosen desktop cell");
            main(() -> { prefs.saveHubPins(List.of()); hub.workspaceOptionsChanged(); }); float[] empty = dockPoint(0, .5f); rootLift(incoming); rootTouch(MotionEvent.ACTION_MOVE, empty); rootTouch(MotionEvent.ACTION_UP, empty); require(prefs.hubPins().equals(List.of(incoming)), "empty Dock accepts its first app");
            rootLift(apps.get(6)); float[] cancelPoint = dockPoint(1, .9f); rootTouch(MotionEvent.ACTION_MOVE, cancelPoint); rootTouch(MotionEvent.ACTION_POINTER_DOWN, cancelPoint); rootTouch(MotionEvent.ACTION_UP, cancelPoint); require(prefs.hubPins().equals(List.of(incoming)) && !grid.dragging(), "a second pointer cancels insertion without a stale release");
            main(() -> hub.recentResult(List.of(new RecentTasks.Task(901, 2, 0, apps.get(8).substring(4), ActionCatalog.component(apps.get(8)).getPackageName(), false)), null)); ViewGroup recentDock = hub.findViewWithTag("hub-dock"); View recent = null; for (int i = 0; i < recentDock.getChildCount(); i++) if (recentDock.getChildAt(i).getTag(R.id.dock_recent_task) != null) recent = recentDock.getChildAt(i); require(recent != null, "recent task area exists beside fixed references"); View separator = hub.findViewWithTag("dock-separator"); require(separator != null, "fixed and running groups have a visible separator"); android.widget.LinearLayout.LayoutParams divider = (android.widget.LinearLayout.LayoutParams) separator.getLayoutParams(); require(divider.width <= Ui.dp(activity, 1) && divider.leftMargin + divider.rightMargin <= Ui.dp(activity, 2), "separator uses a thin line with only 1dp space on each side"); frame("dock-separator"); float[] recentPoint = point(recent, recent.getWidth() / 2f, recent.getHeight() / 2f); rootLift(apps.get(6)); rootTouch(MotionEvent.ACTION_MOVE, recentPoint); rootTouch(MotionEvent.ACTION_UP, recentPoint); require(prefs.hubPins().equals(List.of(incoming)), "dropping over a recent task never pins or replaces it"); main(() -> hub.recentResult(List.of(), null));
            main(() -> { prefs.data.edit().putString("hub_sort", "name").apply(); hub.workspaceOptionsChanged(); }); String automatic = ((AppCatalogCache.Entry) grid.getAdapter().getItem(0)).id(); float[] autoStart = point(grid, x(0), y(0)), append = dockPoint(1, .9f); rootTouch(MotionEvent.ACTION_DOWN, autoStart); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); rootTouch(MotionEvent.ACTION_MOVE, append); rootTouch(MotionEvent.ACTION_UP, append); require(prefs.hubPins().equals(List.of(incoming, automatic)), "name-sorted view also supports drag to Dock; actual=" + prefs.hubPins() + "; expected=" + List.of(incoming, automatic)); assertOwnership();
            main(() -> ((EditText) hub.findViewWithTag("hub-search")).setText(hub.label(incoming))); boolean foundPin = false; for (int i = 0; i < grid.getCount(); i++) foundPin |= ((AppCatalogCache.Entry) grid.getAdapter().getItem(i)).id().equals(incoming); require(foundPin, "explicit search still finds fixed applications"); main(() -> ((EditText) hub.findViewWithTag("hub-search")).setText("")); require(grid.getCount() == apps.size() - 2, "clearing search returns to list without fixed duplicates");
            main(() -> { hub.manualMode(); prefs.saveHubPins(List.of()); hub.workspaceOptionsChanged(); }); rootLift(incoming); empty = dockPoint(0, .5f); rootTouch(MotionEvent.ACTION_MOVE, empty); main(() -> hub.setExpanded(false)); rootTouch(MotionEvent.ACTION_UP, empty); require(prefs.hubPins().isEmpty(), "collapse cancels pending Dock insertion"); main(() -> hub.setExpanded(true)); SystemClock.sleep(260);
            main(() -> { prefs.data.edit().putBoolean("hub_workspace_locked", true).apply(); hub.workspaceOptionsChanged(); }); int lockedSlot = grid.projected().slot(incoming); main(() -> grid.settlePage(lockedSlot / grid.capacity(), false)); float[] locked = point(grid, x(lockedSlot), y(lockedSlot)); rootTouch(MotionEvent.ACTION_DOWN, locked); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); rootTouch(MotionEvent.ACTION_MOVE, dockPoint(0, .5f)); rootTouch(MotionEvent.ACTION_UP, dockPoint(0, .5f)); require(prefs.hubPins().isEmpty() && !grid.dragging(), "layout lock prevents Dock edits");
            main(() -> { prefs.data.edit().putBoolean("hub_workspace_locked", false).apply(); grid.change(grid.projected().create(apps.subList(0, 9), "文件夹", 0)); }); String folder = prefs.workspace().parent(apps.get(0)); main(() -> grid.launch(folder)); SystemClock.sleep(280);
            View sheet = root.findViewWithTag("folder-sheet"), card = sheet.findViewWithTag("detail-card"); require(card.getWidth() <= Ui.dp(activity, 240), "folder card respects compact width cap"); require(hub.getWidth() - card.getWidth() >= Ui.dp(activity, 40), "folder leaves generous blank dismissal area"); AppFolderGrid members = root.findViewWithTag("folder-grid"); require(members.getChildCount() == 9 && members.getChildAt(0).getWidth() >= Ui.dp(activity, 48), "compact folder retains all nine accessible three-column targets"); frame("folder-compact");
            TextView title = ((DetailSheet) sheet).title; ViewGroup memberCell = (ViewGroup) members.getChildAt(0); require(title.getTextSize() < memberCell.getChildAt(0).getHeight() * .65f, "folder heading remains visually subordinate to application icons"); require(((TextView) memberCell.getChildAt(1)).getTextSize() < title.getTextSize(), "application labels and folder heading have a clear size hierarchy"); View close = sheet.findViewWithTag("detail-close"); require(close.getHeight() >= Ui.dp(activity, 48) && close.getWidth() - close.getPaddingLeft() - close.getPaddingRight() <= Ui.dp(activity, 16), "small close glyph retains full 48dp touch target");
            float[] blank = point(sheet, Ui.dp(activity, 3), sheet.getHeight() / 2f); rootTouch(MotionEvent.ACTION_DOWN, blank); rootTouch(MotionEvent.ACTION_UP, blank); SystemClock.sleep(220); require(root.findViewWithTag("folder-sheet") == null && hub.expanded(), "tapping newly exposed blank area closes only folder"); require(launches == 0, "organization gestures never launch an application");
            int[] observed = {0}; android.content.SharedPreferences.OnSharedPreferenceChangeListener ownership = (store, key) -> { if ("hub_pins".equals(key) || "hub_workspace".equals(key)) { observed[0]++; require(java.util.Collections.disjoint(prefs.workspace().apps(), prefs.hubPins()), "every preference observer sees atomic Dock/desktop ownership"); } };
            main(() -> { prefs.data.registerOnSharedPreferenceChangeListener(ownership); prefs.saveHubPins(List.of(apps.get(0))); prefs.data.unregisterOnSharedPreferenceChangeListener(ownership); hub.workspaceOptionsChanged(); }); require(observed[0] >= 2 && prefs.workspace().folder(folder).members().size() == 8, "pinning a folder member removes it and updates folder capacity atomically"); assertOwnership();
            java.util.Map<String, ?> unchanged = prefs.data.getAll(); boolean invalid = false; try { prefs.saveDockPlacement(List.of(apps.get(1)), prefs.workspace()); } catch (IllegalArgumentException expected) { invalid = true; } require(invalid && unchanged.equals(prefs.data.getAll()), "invalid duplicate ownership rejects the whole save");
            main(() -> { prefs.saveHubPins(List.of()); hub.workspaceOptionsChanged(); }); require(prefs.workspace().parent(apps.get(0)) == null && prefs.workspace().slot(apps.get(0)) >= 0, "unpin returns former folder member to desktop"); assertOwnership();
            mount(); require(prefs.hubPins().isEmpty() && prefs.workspace().folder(folder) != null, "remount preserves committed pin state and folders");
            return "PASS: workspace-dock-menu; " + assertions + " assertions; density=" + activity.getResources().getConfiguration().densityDpi + "; rotation=" + activity.getDisplay().getRotation();
        } finally { main(() -> { hub.dispose(); activity.finish(); }); }
    }
    String run() throws Exception {
        fullHost = true;
        activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); prefs = new Prefs(activity);
        require(rotation < 0 || activity.getDisplay().getRotation() == rotation, "actual display rotation matches request");
        List<AppCatalogCache.Entry> entries = CoverApp.catalog(activity).entriesBlocking(); apps = new ArrayList<>(); for (AppCatalogCache.Entry app : entries) apps.add(app.id()); require(apps.size() >= 10, "at least ten real launchable apps for capacity checks");
        prefs.data.edit().clear().putString("hub_sort", "manual").commit(); mount();
        try {
            String a = apps.get(0), b = apps.get(1); require(grid.capacity() / grid.getNumColumns() >= 2, "two rows fit at current density; size=" + grid.getWidth() + "x" + grid.getHeight() + ", columns=" + grid.getNumColumns() + ", font=" + activity.getResources().getConfiguration().fontScale + ", density=" + activity.getResources().getConfiguration().densityDpi); AppWorkspaceLayout before = grid.layoutSnapshot();
            lift(a); hover(b); require(b.equals(grid.mergeTarget()), "center captures B before any insertion can move it");
            int target = grid.projected().slot(b); SystemClock.sleep(200); require(grid.mergeElapsed() < 1000 && !grid.mergeReady() && before.equals(prefs.workspace()), "early sub-second hover never creates or persists a folder; elapsed=" + grid.mergeElapsed() + ", ready=" + grid.mergeReady() + ", unchanged=" + before.equals(prefs.workspace()));
            require(b.equals(grid.hit(x(target), y(target))), "target remains at its original center while counting");
            touch(grid, MotionEvent.ACTION_CANCEL, x(target), y(target)); SystemClock.sleep(450); require(before.equals(prefs.workspace()), "cancellation stops merge timer without a late write");
            lift(a); touch(grid, MotionEvent.ACTION_MOVE, x(target) - .4f * (grid.getWidth() / grid.getNumColumns()), y(target)); SystemClock.sleep(300); hover(b); require(b.equals(grid.mergeTarget()), "slow edge-to-center approach captures original target after an insertion preview"); long started = SystemClock.uptimeMillis(); SystemClock.sleep(500); touch(grid, MotionEvent.ACTION_MOVE, x(target) + Ui.dp(activity, 2), y(target)); SystemClock.sleep(600); test.waitForIdleSync(); require(grid.mergeReady() && SystemClock.uptimeMillis() - started >= 1000, "one-second dwell with small jitter produces merge preview"); require(before.equals(prefs.workspace()), "ready folder is still only a preview"); frame("merge-ready");
            release(b); String folder = prefs.workspace().parent(a); require(folder != null && prefs.workspace().folder(folder).members().equals(List.of(b, a)), "release creates folder with target then dragged member"); require(launches == 0, "drop never launches");
            require(grid.projected().span(folder) == 2 && grid.projected().apps().equals(before.apps()), "folder occupies 2x2 and retains each app once"); frame("folder-closed");
            int slot = grid.projected().slot(folder); touch(grid, MotionEvent.ACTION_DOWN, x(slot), y(slot)); touch(grid, MotionEvent.ACTION_UP, x(slot), y(slot));
            AppFolderGrid members = root.findViewWithTag("folder-grid"); require(members != null && members.getChildCount() == 2 && launches == 0, "whole folder opens a member panel without launching its small preview"); frame("folder-open");
            touch(members, MotionEvent.ACTION_DOWN, members.getChildAt(0).getWidth() / 2f, members.getChildAt(0).getHeight() / 2f); touch(members, MotionEvent.ACTION_UP, members.getChildAt(0).getWidth() / 2f, members.getChildAt(0).getHeight() / 2f); require(launches == 1 && root.findViewWithTag("folder-sheet") != null, "member tap requests launch once; a non-accepted launch leaves folder visible");
            touch(members, MotionEvent.ACTION_DOWN, members.getWidth() / 6f, members.getChildAt(0).getHeight() / 2f); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 60); touch(members, MotionEvent.ACTION_MOVE, members.getWidth() / 2f, members.getChildAt(0).getHeight() / 2f); require(prefs.workspace().folder(folder).members().equals(List.of(b, a)), "member sorting preview does not write"); touch(members, MotionEvent.ACTION_UP, members.getWidth() / 2f, members.getChildAt(0).getHeight() / 2f); require(prefs.workspace().folder(folder).members().equals(List.of(a, b)), "member release commits new order");
            main(hub::back); SystemClock.sleep(200); require(root.findViewWithTag("folder-sheet") == null && hub.expanded(), "Back closes folder before collapsing launcher");
            main(grid::undo); require(prefs.workspace().folder(folder).members().equals(List.of(b, a)), "single undo restores member order");
            main(() -> grid.change(grid.projected().rename(folder, "微信工具").color(folder, 3))); require(prefs.workspace().folder(folder).color() == 3, "name and accent persist");
            main(() -> { prefs.workspaceAlias(a, "微信"); hub.workspaceOptionsChanged(); ((EditText) hub.findViewWithTag("hub-search")).setText("weixin"); }); require(grid.getCount() >= 1 && !grid.editable(), "pinyin finds aliased folder member in read-only search");
            main(() -> ((EditText) hub.findViewWithTag("hub-search")).setText("wx")); require(grid.getCount() >= 1, "pinyin initials find member"); main(hub::manualMode);
            for (int i = 2; i < 9; i++) { String app = apps.get(i); main(() -> grid.change(grid.projected().merge(app, folder))); }
            require(prefs.workspace().folder(folder).members().size() == 9, "8 to 9 accepts final member"); AppWorkspaceLayout full = prefs.workspace();
            boolean rejected = false; try { grid.projected().merge(apps.get(9), folder); } catch (IllegalArgumentException expected) { rejected = true; } require(rejected && full.equals(prefs.workspace()), "tenth member rejects without modifying either source or target");
            main(() -> grid.launch(folder)); frame("folder-nine"); main(hub::back); SystemClock.sleep(200);
            main(() -> grid.change(grid.projected().dissolve(folder))); require(prefs.workspace().folders().isEmpty() && prefs.workspace().apps().equals(before.apps()), "dissolve nine restores every app"); main(grid::undo); require(prefs.workspace().folder(folder).members().size() == 9, "undo dissolve restores full folder");
            JSONObject config = activity.exportConfigurationData(); require(config.getInt("version") == 13 && config.getJSONObject("layout").getInt("version") == 9, "folder schema exported with metadata");
            main(() -> prefs.saveWorkspace(new AppWorkspaceLayout(), false)); activity.applyConfigurationData(config); main(grid::preferencesChanged); require(full.rename(folder, "微信工具").equals(prefs.workspace()), "folder export-import retains identity, members, color and cells"); require(prefs.workspaceAlias(a).equals("微信"), "alias is exported with layout");
            for (String defect : List.of("duplicate", "overflow", "nested", "overlap", "name", "fraction", "density")) {
                JSONObject bad = new JSONObject(config.toString()), layout = bad.getJSONObject("layout"); JSONArray nodes = layout.getJSONArray("workspace"); JSONObject group = null; for (int i = 0; i < nodes.length(); i++) if (nodes.getJSONObject(i).has("members")) group = nodes.getJSONObject(i);
                switch (defect) { case "duplicate" -> group.getJSONArray("members").put(1, group.getJSONArray("members").get(0)); case "overflow" -> group.getJSONArray("members").put(apps.get(9)); case "nested" -> group.getJSONArray("members").put(0, group.getString("id")); case "overlap" -> nodes.put(new JSONObject().put("id", apps.get(0)).put("slot", group.getInt("slot") + 1)); case "name" -> group.put("name", " "); case "fraction" -> layout.put("workspaceColumns", 3.5); case "density" -> layout.put("workspaceDensity", "unknown"); }
                java.util.Map<String, ?> original = prefs.data.getAll(); boolean failed = false; try { activity.applyConfigurationData(bad); } catch (IllegalArgumentException | org.json.JSONException expected) { failed = true; } require(failed && original.equals(prefs.data.getAll()), "bad " + defect + " rejects entire configuration atomically");
            }
            main(() -> { prefs.data.edit().putBoolean("hub_workspace_locked", true).apply(); hub.workspaceOptionsChanged(); }); require(!grid.editable() && !grid.canUndo(), "lock prevents dragging and undo"); AppWorkspaceLayout locked = prefs.workspace(); main(() -> grid.moveTo(folder, 0)); require(locked.equals(prefs.workspace()), "locked accessible movement cannot mutate");
            main(() -> grid.launch(folder)); AppFolderGrid lockedMembers = root.findViewWithTag("folder-grid"); int requests = launches; touch(lockedMembers, MotionEvent.ACTION_DOWN, lockedMembers.getWidth() / 6f, lockedMembers.getChildAt(0).getHeight() / 2f); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 60); touch(lockedMembers, MotionEvent.ACTION_UP, lockedMembers.getWidth() / 6f, lockedMembers.getChildAt(0).getHeight() / 2f); require(launches == requests && locked.equals(prefs.workspace()), "locked member long press opens a menu rather than launching or moving");
            boolean focusedMenu = false; for (int i = 0; i < 12; i++) { SystemClock.sleep(100); android.view.accessibility.AccessibilityNodeInfo window = test.getUiAutomation().getRootInActiveWindow(); if (window != null && !window.findAccessibilityNodeInfosByText("移出到桌面").isEmpty()) { focusedMenu = true; break; } } require(focusedMenu, "locked member menu has input focus before sending Back"); test.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); SystemClock.sleep(180); require(!activity.isFinishing(), "Back dismisses menu without finishing host"); main(hub::back); SystemClock.sleep(200);
            main(() -> { prefs.data.edit().putBoolean("hub_workspace_locked", false).apply(); hub.workspaceOptionsChanged(); });
            main(() -> grid.change(grid.projected().extract(a, 0))); require(prefs.workspace().parent(a) == null && prefs.workspace().folder(folder).members().size() == 8, "extract preserves source folder when more than one remains");
            for (String member : new ArrayList<>(prefs.workspace().folder(folder).members()).subList(0, 7)) main(() -> grid.change(grid.projected().extract(member, grid.capacity())));
            require(prefs.workspace().folder(folder) == null && prefs.workspace().apps().equals(before.apps()), "2 to 1 automatically dissolves container");
            toolsChecks();
            reset(); lift(apps.get(0)); hover(apps.get(1)); SystemClock.sleep(1100); main(hub::back); touch(grid, MotionEvent.ACTION_UP, x(1), y(1)); require(prefs.workspace().folders().isEmpty(), "Back cancels ready merge and consumes stale up");
            lift(apps.get(0)); hover(apps.get(1)); SystemClock.sleep(1100); main(() -> { hub.dispose(); root.removeView(hub); }); SystemClock.sleep(200); require(prefs.workspace().folders().isEmpty(), "unmount cannot commit ready merge");
            return "PASS: app-folders; " + assertions + " assertions; rotation=" + activity.getDisplay().getRotation() + "; real emulator gestures and config, no Samsung hardware proof";
        } finally { main(() -> { if (hub != null) hub.dispose(); activity.finish(); }); }
    }
}
