package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Disposable emulator only: real AppWidget host and PendingIntent navigation, shared live data. */
final class LauncherWidgetChecks {
    private final Instrumentation test;
    private int assertions;
    private Activity activity;
    private AppWidgetHost host;
    private AppWidgetHostView card, second;
    LauncherWidgetChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
    private void main(Runnable action) { Throwable[] error = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } }); if (error[0] != null) throw new AssertionError(error[0]); test.waitForIdleSync(); }
    private void until(BooleanSupplier value, String why) { long deadline = SystemClock.uptimeMillis() + 8000; boolean[] found = {false}; do { main(() -> found[0] = value.getAsBoolean()); if (!found[0]) SystemClock.sleep(80); } while (!found[0] && SystemClock.uptimeMillis() < deadline); require(found[0], why); }
    private TextView text(View view, String value) { if (view instanceof TextView label && value.contentEquals(label.getText())) return label; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = text(group.getChildAt(i), value); if (found != null) return found; } return null; }
    private View described(View view, String value) { if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith(value)) return view; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = described(group.getChildAt(i), value); if (found != null) return found; } return null; }
    private int describedCount(View view, String value) { int count = view.getContentDescription() != null && view.getContentDescription().toString().startsWith(value) ? 1 : 0; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) count += describedCount(group.getChildAt(i), value); return count; }
    private void fixedGrid(Prefs prefs) {
        ViewGroup grid = card.findViewById(R.id.launcher_grid); AppWorkspaceLayout layout = prefs.workspace().project(5, 3); int index = 0;
        for (String id : layout.ordered()) {
            int slot = layout.slot(id); if (slot >= 15) continue;
            View cell = grid.getChildAt(index++); require(cell != null, "each first-page item remains in the fixed 15-slot page");
            require(Math.abs(cell.getLeft() - slot % 5 * grid.getWidth() / 5f) <= Ui.dp(activity, 2) && Math.abs(cell.getTop() - slot / 5 * grid.getHeight() / 3f) <= Ui.dp(activity, 2), "native app or folder keeps its saved column and row in a 5 by 3 grid");
            View icon = cell.findViewById(R.id.launcher_icon); require(icon.getWidth() == icon.getHeight(), "native application and preview images keep a square aspect ratio");
            if (layout.folder(id) != null) {
                android.graphics.Bitmap preview = ((android.graphics.drawable.BitmapDrawable) ((android.widget.ImageView) icon).getDrawable()).getBitmap();
                float targetDensity = activity.createDisplayContext(Displays.selected(activity, prefs)).getResources().getDisplayMetrics().density;
                int expectedPixels = Math.round(icon.getWidth() * targetDensity / activity.getResources().getDisplayMetrics().density);
                require(Math.abs(preview.getWidth() - expectedPixels) <= 2 && preview.getHeight() == preview.getWidth(), "native folder preview retains target-display pixels without thumbnail upscaling");
                View surface = cell.findViewById(R.id.launcher_folder_surface); require(surface != null && surface.getWidth() == surface.getHeight(), "native folder background stays square inside its two-by-two occupied area");
                require(surface.getLeft() >= 0 && surface.getTop() >= 0 && surface.getRight() <= cell.getWidth() && surface.getBottom() <= cell.getHeight(), "square folder stays within its saved occupied area");
                TextView label = cell.findViewById(R.id.launcher_label); require(label.getTop() >= icon.getBottom() && label.getBottom() <= surface.getHeight(), "folder label stays below the preview inside the square");
            }
        }
        require(grid.getChildCount() == index, "native page contains exactly the saved 5 by 3 projection");
    }
    private void scrollRail(android.widget.ListView list) {
        long down = SystemClock.uptimeMillis(); float x = list.getWidth() / 2f, start = list.getHeight() - 8, finish = 8;
        main(() -> { for (int i = 0; i <= 8; i++) { android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + i * 30, i == 0 ? android.view.MotionEvent.ACTION_DOWN : i == 8 ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE, x, start + (finish - start) * i / 8, 0); list.dispatchTouchEvent(event); event.recycle(); } });
    }
    private void clickRail(android.widget.ListView list, int child) {
        View row = list.getChildAt(child); long down = SystemClock.uptimeMillis(); float x = row.getWidth() / 2f, y = row.getTop() + row.getHeight() / 2f;
        main(() -> { for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) { android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + (action == android.view.MotionEvent.ACTION_UP ? 50 : 0), action, x, y, 0); list.dispatchTouchEvent(event); event.recycle(); } });
    }
    private android.widget.Toast nativeToast(LauncherWidgetBridge bridge) { try { java.lang.reflect.Field field = LauncherWidgetBridge.class.getDeclaredField("editToast"); field.setAccessible(true); return (android.widget.Toast) field.get(bridge); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }
    private void cancelledTaskRequest(Context context, Prefs prefs, RecentTasks.Task task) throws Exception {
        ShizukuBridge bridge = CoverApp.bridge(context); java.lang.reflect.Field field = ShizukuBridge.class.getDeclaredField("remote"); field.setAccessible(true); Object original = field.get(bridge);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean active = new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicReference<ShizukuBridge.Result> received = new java.util.concurrent.atomic.AtomicReference<>();
        IShellService service = new IShellService.Stub() {
            @Override public void watchConnectivity(IConnectivityListener listener) { }
            public String execute(String operation, int display, int value, String item) { entered.countDown(); try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } return "{\"ok\":true,\"output\":\"{\\\"state\\\":\\\"opened\\\"}\"}"; }
            public Bundle taskSnapshot(int display, String item) { return null; }
            public void destroy() { }
        };
        try {
            field.set(bridge, service); main(() -> AppRecentTasks.open(context, prefs, task, active::get, received::set));
            require(entered.await(3, java.util.concurrent.TimeUnit.SECONDS), "recent open reached controlled asynchronous service"); active.set(false); release.countDown();
            until(() -> received.get() != null, "cancelled task request completes"); require(!received.get().ok, "late opened result cannot be accepted after owner cancellation");
        } finally { release.countDown(); field.set(bridge, original); }
    }
    private void frame(String name) throws Exception {
        SystemClock.sleep(160); android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot(); java.io.File dir = new java.io.File(activity.getFilesDir(), "launcher-widget-raw"); dir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { image.recycle(); }
    }
    String run() throws Exception {
        require(android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish"), "disposable emulator guard");
        Context context = test.getTargetContext(); Prefs prefs = new Prefs(context); AppCatalogCache cache = CoverApp.catalog(context);
        List<AppCatalogCache.Entry> apps = cache.entriesBlocking(); require(apps.size() >= 5, "real launchable catalog available");
        test.getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET", "android.permission.ADD_TRUSTED_DISPLAY");
        SurfaceTexture texture = new SurfaceTexture(false); texture.setDefaultBufferSize(720, 748); Surface surface = new Surface(texture);
        VirtualDisplay display = context.getSystemService(DisplayManager.class).createVirtualDisplay("Launcher card checks", 720, 748, 320, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 10));
        require(display != null, "secondary display created"); int target = display.getDisplay().getDisplayId(); int[] ids = new int[2]; LauncherWidgetBridge bridge = CoverApp.launcherWidgets(context);
        try {
            prefs.data.edit().clear().putInt("display", target).putString("hub_sort", "manual").commit();
            List<String> all = new ArrayList<>(); for (AppCatalogCache.Entry app : apps) all.add(app.id());
            String fixture = "app:" + new ComponentName(test.getContext(), LauncherLaunchFixtureActivity.class).flattenToString(); require(all.remove(fixture), "launch target discovered through shared app catalog"); all.add(0, fixture);
            AppWorkspaceLayout layout = AppWorkspaceLayout.sequential(all).create(List.of(all.get(0), all.get(1)), "共享文件夹", 0).move(all.get(4), 35, false);
            prefs.saveWorkspace(layout, false); prefs.saveHubPins(List.of(all.get(2))); prefs.workspaceAlias(all.get(0), "共享别名");
            prefs.saveActions("favorites", List.of("rotation", "screenshot", "notification_list", "media", "torch", "controls", "wifi", "bluetooth", all.get(0)));
            activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            main(() -> {
                host = new AppWidgetHost(context, 0x4c41554e); host.deleteHost(); Bundle options = new Bundle();
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 310); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 310);
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 280); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 280);
                for (int i = 0; i < 2; i++) { ids[i] = host.allocateAppWidgetId(); require(manager.bindAppWidgetIdIfAllowed(ids[i], new ComponentName(context, LauncherWidgetProvider.class), options), "standalone launcher provider binds"); }
                card = host.createView(activity, ids[0], manager.getAppWidgetInfo(ids[0])); second = host.createView(activity, ids[1], manager.getAppWidgetInfo(ids[1])); card.setPadding(0, 0, 0, 0); second.setPadding(0, 0, 0, 0);
                FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(Ui.BACKGROUND); root.addView(card, new FrameLayout.LayoutParams(Ui.dp(activity, 310), Ui.dp(activity, 280), android.view.Gravity.CENTER)); activity.setContentView(root); activity.getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars()); host.startListening(); bridge.refresh();
            });
            until(() -> text(card, "共享文件夹") != null, "card displays the floating workspace folder");
            require(!java.util.Arrays.stream(CoverApp.widgets(context).cards()).anyMatch(id -> id == ids[0]), "launcher does not consume a combination slot");
            Map<String, ?> before = prefs.data.getAll();
            main(() -> card.findViewById(R.id.launcher_edit).performClick());
            until(() -> nativeToast(bridge) != null, "real edit PendingIntent creates an Android Toast");
            require(nativeToast(bridge).getView() == null && nativeToast(bridge).getDuration() == android.widget.Toast.LENGTH_SHORT, "guidance uses system text Toast, with no custom popup view");
            require(card.findViewById(R.id.launcher_panel).getVisibility() == View.VISIBLE && text(card, "知道了") == null, "edit guidance leaves the card visible without a confirmation button");
            require(before.equals(prefs.data.getAll()), "edit Toast never changes shared content"); frame("edit-toast");
            frame("apps");
            main(() -> {
                View header = card.findViewById(R.id.launcher_header), title = card.findViewById(R.id.launcher_title);
                require(Math.abs(title.getLeft() + title.getWidth() / 2f - header.getWidth() / 2f) <= 1, "title stays centered in the whole card header");
                require(Math.abs(((TextView) title).getTextSize() - Ui.dp(activity, 12)) <= 1, "native title ignores host system font scaling");
                require(header.getHeight() == Ui.dp(activity, 24) && card.findViewById(R.id.launcher_edit).getWidth() == Ui.dp(activity, 24), "compact header and secondary actions have stable dimensions");
                require(card.findViewById(R.id.launcher_pager).getHeight() == Ui.dp(activity, 18) && card.findViewById(R.id.launcher_pins).getHeight() == Ui.dp(activity, 36), "page and Dock strip remove spare vertical padding");
                View pager = card.findViewById(R.id.launcher_pager), page = card.findViewById(R.id.launcher_page), previous = card.findViewById(R.id.launcher_previous), next = card.findViewById(R.id.launcher_next);
                require(previous instanceof android.widget.ImageButton && next instanceof android.widget.ImageButton, "native paging arrows use bold vector buttons instead of small text glyphs");
                require(Math.abs(page.getLeft() + page.getWidth() / 2f - pager.getWidth() / 2f) <= 1, "page indicator stays at the center of the unchanged pager strip");
                require(previous.getRight() == page.getLeft() && next.getLeft() == page.getRight() && previous.getWidth() == next.getWidth() && previous.getWidth() == Ui.dp(activity, 44), "paging buttons sit directly beside the indicator with their original touch width");
                View body = (View) card.findViewById(R.id.launcher_grid).getParent();
                require(body.getBottom() == pager.getTop() && pager.getBottom() == card.findViewById(R.id.launcher_pins).getTop(), "pager stays between the unchanged app body and Dock without overlap");
                require(!previous.isEnabled() && next.isEnabled() && previous.getAlpha() < next.getAlpha(), "first page distinguishes unavailable previous action from bright next action");
                require(card.findViewById(R.id.launcher_grid).getHeight() > Ui.dp(activity, 185), "compacted chrome returns vertical space without adding rows"); fixedGrid(prefs);
            });
            int originalGridHeight = card.findViewById(R.id.launcher_grid).getHeight();
            Bundle originalOptions = manager.getAppWidgetOptions(ids[0]), smallerOptions = new Bundle(originalOptions);
            smallerOptions.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 260); smallerOptions.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 260);
            main(() -> { ViewGroup.LayoutParams params = card.getLayoutParams(); params.height = Ui.dp(activity, 260); card.setLayoutParams(params); manager.updateAppWidgetOptions(ids[0], smallerOptions); bridge.refresh(); });
            until(() -> card.findViewById(R.id.launcher_grid).getHeight() < originalGridHeight, "native host applies a smaller available height"); main(() -> fixedGrid(prefs));
            main(() -> { ViewGroup.LayoutParams params = card.getLayoutParams(); params.height = Ui.dp(activity, 280); card.setLayoutParams(params); manager.updateAppWidgetOptions(ids[0], originalOptions); bridge.refresh(); });
            until(() -> card.findViewById(R.id.launcher_grid).getHeight() == originalGridHeight, "native host restores original height"); main(() -> fixedGrid(prefs));
            require(describedCount(card.findViewById(R.id.launcher_pins), "常用：") == prefs.hubPins().size(), "native fixed row uses shared Dock membership");
            require(described(card, "侧栏：旋转方向") != null, "native sidebar reads floating favorites");
            android.widget.ListView rail = card.findViewById(R.id.launcher_rail);
            main(() -> {
                int full = 0; for (int i = 0; i < rail.getChildCount(); i++) {
                    View row = rail.getChildAt(i); if (row.getTop() >= 0 && row.getBottom() <= rail.getHeight()) full++;
                    TextView label = row.findViewById(R.id.launcher_label); View icon = row.findViewById(R.id.launcher_icon);
                    require(label.getTop() >= icon.getBottom() && label.getBottom() <= row.getHeight() && label.getLayout().getHeight() <= label.getHeight(), "compact sidebar icon and label stay separate without clipping");
                    require(Math.abs(label.getTextSize() / activity.getResources().getDisplayMetrics().density - AppLauncherStyle.RAIL_LABEL_SP) < .1f, "native sidebar font stays fixed when host font scale changes");
                }
                require(full >= 6, "compact sidebar shows at least six complete items");
                require(rail.getAdapter().getCount() == prefs.actions("favorites").size(), "scrolling sidebar retains every configured item");
                require(described(card, "上一页侧栏") == null && described(card, "下一页侧栏") == null, "sidebar arrow row is removed");
            });
            scrollRail(rail);
            until(() -> rail.getFirstVisiblePosition() > 0, "native sidebar accepts vertical drag to overflow items");
            SystemClock.sleep(300); int scrolled = rail.getFirstVisiblePosition(); main(bridge::refresh); SystemClock.sleep(150);
            require(rail.getFirstVisiblePosition() == scrolled, "card content refresh retains sidebar scroll position");
            main(() -> rail.setSelection(0));
            until(() -> rail.getFirstVisiblePosition() == 0, "sidebar can return to the first item");
            require(before.equals(prefs.data.getAll()), "sidebar navigation keeps shared data read only");
            main(() -> described(card, "收起全部应用，保留 Dock").performClick());
            until(() -> card.findViewById(R.id.launcher_grid).getVisibility() == View.INVISIBLE, "native Dock can collapse workspace");
            require(card.findViewById(R.id.launcher_rail).getVisibility() == View.INVISIBLE && card.findViewById(R.id.launcher_pins).getVisibility() == View.VISIBLE, "collapsed state preserves only Dock content");
            main(() -> described(card, "展开全部应用").performClick());
            until(() -> card.findViewById(R.id.launcher_grid).getVisibility() == View.VISIBLE, "apps entry expands native workspace");
            List<RecentTasks.Task> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) { String pkg = "fixture.recent" + i; tasks.add(new RecentTasks.Task(100 + i, target, android.os.Process.myUid() / 100000, new ComponentName(pkg, pkg + ".Main").flattenToString(), pkg, i == 0)); }
            require(AppRecentTasks.tasks(new org.json.JSONArray(AppRecentTasks.encode(tasks)), target).equals(tasks), "native pending task payload preserves canonical system identity");
            main(() -> bridge.recent(tasks, true, true));
            until(() -> describedCount(card, "最近任务：") == 4, "native Dock renders at most four recent app groups");
            main(() -> {
                View dock = card.findViewById(R.id.launcher_dock_row), appsButton = described(dock, "收起全部应用，保留 Dock"), clearButton = described(dock, "清理可见最近应用");
                require(appsButton.getWidth() == clearButton.getWidth() && appsButton.getWidth() == Ui.dp(activity, 24), "native Dock side actions share a compact 24dp width");
                require(Math.abs(appsButton.getLeft() + appsButton.getWidth() / 2f - (dock.getWidth() - clearButton.getLeft() - clearButton.getWidth() / 2f)) <= 1, "native Dock action centers mirror around the row center");
                require(Math.abs(appsButton.getLeft() - (dock.getWidth() - clearButton.getRight())) <= 1, "native Dock has matching left and right outer spacing");
                View icon = appsButton.findViewById(R.id.launcher_icon); require(icon.getLeft() >= 0 && icon.getRight() <= appsButton.getWidth() && icon.getWidth() == Ui.dp(activity, 24), "narrow apps action preserves the full icon without clipping");
            }); frame("dock-symmetric");
            require(described(card, "清理可见最近应用").isEnabled(), "native Dock exposes usable clear action for eligible targets");
            RecentTasks.Locks locks = CoverApp.taskLocks(context); locks.toggle(tasks.get(1));
            require(AppDockLayout.clearTargets(tasks, prefs.hubPins(), tasks.subList(0, 4), locks).equals(tasks.subList(2, 4)), "shared clear policy excludes visible, locked and offscreen recent apps"); locks.clear();
            main(() -> { for (RecentTasks.Task task : tasks.subList(1, 4)) locks.toggle(task); described(card, "清理可见最近应用").performClick(); });
            until(() -> !described(card, "清理可见最近应用").isEnabled(), "late locks turn stale clear click into a harmless no-op");
            require(described(card, "最近任务：").isEnabled(), "no-op clear retains a usable recent snapshot"); locks.clear();
            AppDockLayout.Geometry narrow = AppDockLayout.fit(200, 4, 4, 1); require(narrow.recentCount() < 4 && narrow.chrome() + (4 + narrow.recentCount()) * narrow.cell() <= 200, "shared Dock reduces recent slots at narrow widths");
            main(() -> bridge.recentFailure("fixture disconnected"));
            until(() -> !described(card, "最近任务：").isEnabled(), "failed synchronization disables stale recent task launch");
            require(!described(card, "清理可见最近应用").isEnabled(), "failed synchronization disables stale clear");
            main(() -> bridge.recent(List.of(), false, false));
            until(() -> described(card, "最近任务：") == null, "empty result clears old native recent icons");
            main(() -> card.findViewById(R.id.launcher_next).performClick());
            until(() -> ((TextView) card.findViewById(R.id.launcher_page)).getText().toString().startsWith("2 / "), "native next-page button browses projected saved layout");
            require(card.findViewById(R.id.launcher_previous).isEnabled() && card.findViewById(R.id.launcher_previous).getAlpha() == 1f, "previous arrow becomes bright and enabled after moving forward");
            require(before.equals(prefs.data.getAll()), "native pagination cannot rearrange saved workspace");
            main(() -> card.findViewById(R.id.launcher_previous).performClick());
            until(() -> text(card, "共享文件夹") != null, "previous-page button restores folder page");
            main(() -> described(card, "共享文件夹，文件夹").performClick());
            until(() -> text(card.findViewById(R.id.launcher_grid), "共享别名") != null, "folder opens inside the card with shared alias");
            require(text(second, "共享文件夹") != null && text(second.findViewById(R.id.launcher_grid), "共享别名") == null, "separate card instances keep independent navigation");
            main(() -> prefs.workspaceAlias(all.get(0), "修改后别名"));
            until(() -> text(card, "修改后别名") != null, "shared alias edit updates open card folder");
            main(() -> {
                AppHubView floating = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { } });
                require(floating.label(all.get(0)).equals("修改后别名"), "floating renderer reads the same alias");
                require(floating.getResources().getConfiguration().fontScale == 1f, "floating launcher uses its own fixed-font configuration");
                require(floating.getContext().getTheme().getResources().getConfiguration().fontScale == 1f, "launcher menus and themed text share fixed-font resources");
                require(floating.getResources().getConfiguration().densityDpi == activity.getResources().getConfiguration().densityDpi, "fixed launcher font preserves actual display density");
                TextView search = floating.findViewWithTag("hub-search"); require(Math.abs(search.getTextSize() / floating.getResources().getDisplayMetrics().density - 11) < .1f, "floating search field ignores system font scaling");
                ViewGroup floatingRail = floating.findViewWithTag("hub-rail"); android.widget.ScrollView favorites = (android.widget.ScrollView) floatingRail.getChildAt(0); ViewGroup items = (ViewGroup) favorites.getChildAt(0);
                require(items.getChildAt(0).getMinimumHeight() == Ui.dp(activity, AppLauncherStyle.RAIL_CELL), "floating sidebar shares compact item height");
                AppWorkspaceView workspace = floating.findViewWithTag("hub-grid");
                for (int[] size : new int[][]{{310, 280}, {270, 240}, {310, 340}}) {
                    floating.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(activity, size[0]), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(Ui.dp(activity, size[1]), View.MeasureSpec.EXACTLY)); floating.layout(0, 0, floating.getMeasuredWidth(), floating.getMeasuredHeight());
                    require(workspace.getNumColumns() == 5 && workspace.capacity() == 15 && workspace.projected().rows() == 3, "floating app positions always use five columns and three rows at every size");
                    for (int i = 0; i < workspace.getChildCount(); i++) if (workspace.getChildAt(i) instanceof AppFolderTile tile) {
                        View folderSurface = tile.findViewWithTag("folder-surface"); require(folderSurface.getWidth() == folderSurface.getHeight() && folderSurface.getRight() <= tile.getWidth() && folderSurface.getBottom() <= tile.getHeight(), "floating folder uses the same bounded square surface at every viewport size");
                    }
                }
                floating.dispose();
            }); frame("folder");
            main(() -> { prefs.saveActions("favorites", List.of(all.get(0), "rotation")); prefs.data.edit().putString("hand_side", "right").commit(); });
            until(() -> described(card, "侧栏：修改后别名") != null && card.findViewById(R.id.launcher_rail).getLeft() > card.findViewById(R.id.launcher_grid).getLeft(), "favorite edits and right-side preference update the native adapter");
            main(() -> {
                try { AppRecentTasks.tasks(new org.json.JSONArray().put(SystemRecentTasks.json(tasks.get(0))).put(SystemRecentTasks.json(tasks.get(0))), target); require(false, "duplicate task must be rejected"); } catch (Exception expected) { require(true, "shared task boundary rejects duplicate identities"); }
                try { AppRecentTasks.task(SystemRecentTasks.json(tasks.get(0)), 0); require(false, "primary task must be rejected"); } catch (Exception expected) { require(true, "shared task boundary rejects wrong display"); }
            });
            cancelledTaskRequest(context, prefs, tasks.get(0));
            java.util.concurrent.atomic.AtomicInteger launchedDisplay = new java.util.concurrent.atomic.AtomicInteger(-1);
            Activity primaryHost = activity;
            Activity secondaryHost = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle());
            main(() -> {
                activity = secondaryHost; card = host.createView(activity, ids[0], manager.getAppWidgetInfo(ids[0])); card.setPadding(0, 0, 0, 0);
                FrameLayout root = new FrameLayout(activity); root.addView(card, new FrameLayout.LayoutParams(Ui.dp(activity, 310), Ui.dp(activity, 280))); activity.setContentView(root); primaryHost.finish();
            });
            until(() -> text(card, "修改后别名") != null, "card is hosted on the actual selected secondary display for launch verification");
            android.content.BroadcastReceiver launched = new android.content.BroadcastReceiver() { public void onReceive(Context c, Intent intent) { launchedDisplay.set(intent.getIntExtra("display", -1)); } };
            context.registerReceiver(launched, new android.content.IntentFilter("fixture.LAUNCHER_RESULT"), Context.RECEIVER_EXPORTED);
            try {
                clickRail(card.findViewById(R.id.launcher_rail), 0); until(() -> launchedDisplay.get() == target, "collection fill-in click launches favorite app on selected secondary display"); launchedDisplay.set(-1);
                main(() -> described(card.findViewById(R.id.launcher_grid), "修改后别名").performClick()); until(() -> launchedDisplay.get() == target, "real card PendingIntent launches a different-package activity on selected secondary display");
            }
            finally { context.unregisterReceiver(launched); }
            main(() -> prefs.saveActions("favorites", List.of("app_dock", all.get(0))));
            until(() -> described(card, "侧栏：应用 Dock") != null, "collection updates configured system shortcuts");
            clickRail(card.findViewById(R.id.launcher_rail), 0);
            until(() -> card.findViewById(R.id.launcher_grid).getVisibility() == View.INVISIBLE, "collection system shortcut uses the native card action path");
            main(() -> described(card, "展开全部应用").performClick());
            until(() -> card.findViewById(R.id.launcher_grid).getVisibility() == View.VISIBLE, "collection Dock shortcut leaves workspace recoverable");
            until(() -> text(card, "共享文件夹") != null, "back returns to workspace");
            String folder = prefs.workspace().parent(all.get(0));
            main(() -> prefs.saveWorkspace(prefs.workspace().rename(folder, "同步改名"), false));
            until(() -> text(card, "同步改名") != null, "folder rename from shared model refreshes card");
            main(() -> { CoverApp.widgets(context).safeArea(display.getDisplay(), 720, 748, new DockGeometry.Box(160, 60, 560, 608)); bridge.action(ids[0], target, "edit", ""); });
            until(() -> Math.abs(card.findViewById(R.id.launcher_panel).getLeft() - Ui.dp(activity, 80)) <= 1, "native card follows rotated-edge safe area");
            main(() -> {
                View panel = card.findViewById(R.id.launcher_panel);
                require(panel.getVisibility() == View.VISIBLE && Math.abs(panel.getTop() - Ui.dp(activity, 30)) <= 1, "Toast does not cover or replace the native panel");
                require(panel.getRight() <= card.getWidth() && panel.getBottom() <= card.getHeight(), "panel stays inside native host bounds");
                CoverApp.widgets(context).safeArea(null, 0, 0, null);
            });
            until(() -> card.findViewById(R.id.launcher_panel).getLeft() == 0, "restored content geometry reaches the host before idle-cache check");
            until(() -> cache.pendingIconCount() == 0, "icon queue drains"); int decodes = cache.decodeCount(); SystemClock.sleep(400); require(cache.decodeCount() == decodes, "idle card updates do not restart icon decoding");
            main(() -> { cache.trimMemory(); bridge.refresh(); }); SystemClock.sleep(200);
            require(cache.decodeCount() == decodes, "shared-cache eviction preserves current card bitmaps without decode loops");
            before = prefs.data.getAll();
            android.widget.Toast previousToast = nativeToast(bridge);
            main(() -> bridge.action(ids[0], 0, "edit", "")); SystemClock.sleep(100);
            require(nativeToast(bridge) == previousToast, "wrong-display stale edit action cannot replace the Toast"); require(before.equals(prefs.data.getAll()), "navigation never writes launcher configuration");
            main(() -> {
                boolean[] accepted = {true}; CoverApp.launcher(context).launch(context, prefs, all.get(0), 0, () -> true, ignored -> { }, value -> accepted[0] = value);
                require(!accepted[0], "shared launch policy rejects primary display");
                CoverApp.launcher(context).launch(context, prefs, all.get(0), target, () -> false, ignored -> { }, value -> accepted[0] = value);
                require(!accepted[0], "shared launch policy rejects detached owner");
                prefs.data.edit().putInt("display", 99999).commit(); bridge.refresh();
            });
            until(() -> text(card, "请先选择目标外屏") != null, "missing external display cannot fall back to primary");
            main(() -> { prefs.data.edit().putInt("display", target).commit(); bridge.refresh(); });
            until(() -> text(card, "同步改名") != null, "display recovery reads saved shared workspace");
            main(() -> { host.deleteHost(); bridge.refresh(); }); require(bridge.cards().length == 0, "last card deletion removes provider instances");
            return "PASS: " + assertions + " launcher widget checks; Android host verified, Samsung firmware pending";
        } finally {
            main(() -> { if (host != null) { host.stopListening(); host.deleteHost(); } bridge.refresh(); if (activity != null) activity.finish(); });
            prefs.data.edit().clear().commit(); test.getUiAutomation().dropShellPermissionIdentity(); display.release(); surface.release(); texture.release();
        }
    }
}
