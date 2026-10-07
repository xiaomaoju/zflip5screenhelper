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
    private void filledBackground() {
        View background = card.findViewById(R.id.launcher_background), panel = card.findViewById(R.id.launcher_panel);
        require(background.getLeft() == 0 && background.getTop() == 0 && background.getWidth() == card.getWidth() && background.getHeight() == card.getHeight(), "background fills the native host independently of content insets");
        require(panel.getBackground() == null, "safe content has no second inset rounded background");
        android.graphics.Bitmap pixels = android.graphics.Bitmap.createBitmap(background.getWidth(), background.getHeight(), android.graphics.Bitmap.Config.ARGB_8888);
        try {
            background.getBackground().draw(new android.graphics.Canvas(pixels)); boolean filled = true; int color = card.findViewById(R.id.launcher_catalog) == null ? Ui.SURFACE : Ui.BACKGROUND;
            for (int x = 0; x < pixels.getWidth(); x++) filled &= pixels.getPixel(x, 0) == color && pixels.getPixel(x, pixels.getHeight() - 1) == color;
            for (int y = 0; y < pixels.getHeight(); y++) filled &= pixels.getPixel(0, y) == color && pixels.getPixel(pixels.getWidth() - 1, y) == color;
            require(filled, "all four rendered edges and corners keep the panel color instead of exposing the host");
        } finally { pixels.recycle(); }
    }
    private void sharedHubLayout(Prefs prefs) {
        sharedHubLayout(prefs, 0);
    }
    private void sharedHubLayout(Prefs prefs, int topSpace) {
        ViewGroup panel = card.findViewById(R.id.launcher_panel);
        AppHubView floating = new AppHubView(activity, prefs, new AppHubView.Listener() {
            public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { }
        });
        try {
            floating.centeringSpace(topSpace);
            floating.measure(View.MeasureSpec.makeMeasureSpec(panel.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(panel.getHeight(), View.MeasureSpec.EXACTLY)); floating.layout(0, 0, panel.getWidth(), panel.getHeight());
            ViewGroup tools = floating.findViewWithTag("hub-tools"), nativeTools = panel.findViewById(R.id.launcher_rail_tools);
            require(tools.getChildCount() == 1 && nativeTools.getChildCount() == 1, "both sidebar footers contain only the edit button");
            require(floating.findViewWithTag("hub-close") == null && floating.findViewWithTag("hub-tasks") == null && described(card, "关闭应用中心") == null, "upper-right launcher actions are absent in both hosts");
            View rail = floating.findViewWithTag("hub-sidebar"), nativeRail = panel.findViewById(R.id.launcher_rail_panel);
            android.graphics.Rect railBounds = new android.graphics.Rect(0, 0, rail.getWidth(), rail.getHeight()), nativeRailBounds = new android.graphics.Rect(0, 0, nativeRail.getWidth(), nativeRail.getHeight()); floating.offsetDescendantRectToMyCoords(rail, railBounds); panel.offsetDescendantRectToMyCoords(nativeRail, nativeRailBounds);
            require(Math.abs(railBounds.left - nativeRailBounds.left) <= 1 && Math.abs(railBounds.top - nativeRailBounds.top) <= 1 && Math.abs(rail.getWidth() - nativeRail.getWidth()) <= 1 && Math.abs(rail.getHeight() - nativeRail.getHeight()) <= 1, "both hosts consume the same inset full-height sidebar geometry");
            View search = floating.findViewWithTag("hub-search-field"), nativeSearch = panel.findViewById(R.id.launcher_search_field);
            float density = activity.getResources().getDisplayMetrics().density;
            android.graphics.Rect expectedSearch = AppLauncherStyle.searchBounds(((View) search.getParent()).getWidth(), density), expectedNativeSearch = AppLauncherStyle.searchBounds(((View) nativeSearch.getParent()).getWidth(), density);
            // RemoteViews dp conversion already allows a two-pixel host width difference above.
            // Check each measured header against the same geometry instead of assuming equal widths.
            require(search.getLeft() == expectedSearch.left && search.getWidth() == expectedSearch.width() && Math.abs(nativeSearch.getLeft() - expectedNativeSearch.left) <= 1 && Math.abs(nativeSearch.getWidth() - expectedNativeSearch.width()) <= 1, "both hosts center the shorter search by the shared geometry of their measured headers");
            require(search.getRight() < floating.findViewWithTag("hub-sort").getLeft() && nativeSearch.getRight() < panel.findViewById(R.id.launcher_sort).getLeft(), "both hosts keep sorting separate from the compact search");
            AppWorkspaceView grid = floating.findViewWithTag("hub-grid"); View nativeGrid = panel.findViewById(R.id.launcher_grid);
            android.graphics.Rect a = new android.graphics.Rect(0, 0, grid.getWidth(), grid.getHeight()), b = new android.graphics.Rect(0, 0, nativeGrid.getWidth(), nativeGrid.getHeight());
            floating.offsetDescendantRectToMyCoords(grid, a); panel.offsetDescendantRectToMyCoords(nativeGrid, b);
            require(Math.abs(a.left - b.left) <= 2 && Math.abs(a.top - b.top) <= 2 && Math.abs(a.width() - b.width()) <= 2, "native grid starts on the same horizontal and vertical alignment as the floating workspace");
            int pagingDifference = Ui.dp(activity, AppLauncherStyle.BUTTON_PAGER_HEIGHT) - Ui.dp(activity, AppLauncherStyle.WORKSPACE_PAGER_HEIGHT);
            require(Math.abs(grid.gridHeight() - nativeGrid.getHeight() - pagingDifference) <= 2, "the only grid-height difference is native button paging instead of swiping");
            require(panel.findViewById(R.id.launcher_rail).getHeight() > nativeGrid.getHeight(), "sidebar uses the whole body instead of stopping at the app grid");
        } finally { floating.dispose(); }
    }
    private void surfaceBroadcasts(LauncherWidgetBridge bridge, int widget, int target) {
        require(CoverService.instance == null, "emulator fixture exercises missing-service fallback without opening real overlays");
        Instrumentation.ActivityMonitor monitor = test.addMonitor(LauncherWidgetActivity.class.getName(), null, false);
        try {
            for (int id : new int[]{R.id.launcher_search, R.id.launcher_sort}) {
                android.widget.Toast previous = nativeToast(bridge);
                main(() -> card.findViewById(id).performClick());
                until(() -> nativeToast(bridge) != previous, "native surface click reaches the existing bridge through its broadcast PendingIntent");
            }
            require(monitor.getHits() == 0, "search, sort and tasks do not create a transient Activity or steal its window focus");
            android.widget.Toast previous = nativeToast(bridge);
            main(() -> { bridge.action(widget, 0, "surface", "tasks"); bridge.action(-1, target, "surface", "tasks"); bridge.action(widget, target, "surface", "arbitrary"); bridge.action(widget, target, "surface", null); });
            require(nativeToast(bridge) == previous, "wrong display, unknown card and unsupported surface operations are rejected before service dispatch");
        } finally { test.removeMonitor(monitor); }
    }
    private int nativeGridTop() {
        View grid = card.findViewById(R.id.launcher_grid); android.graphics.Rect box = new android.graphics.Rect(0, 0, grid.getWidth(), grid.getHeight()); card.offsetDescendantRectToMyCoords(grid, box); return box.top;
    }
    private void folderReturn(Prefs prefs, LauncherWidgetBridge bridge, int widget, int target, String folder) throws Exception {
        AppWorkspaceLayout original = prefs.workspace();
        try {
            main(() -> prefs.saveWorkspace(original.move(folder, 45, false), false));
            until(() -> card.findViewById(R.id.launcher_page).getContentDescription().toString().contains("共4页"), "folder fixture is on a later application page");
            for (int page = 2; page <= 4; page++) { int expected = page; main(() -> bridge.action(widget, target, "next", "")); until(() -> card.findViewById(R.id.launcher_page).getContentDescription().toString().startsWith("第" + expected + "页"), "native page advances to the folder origin"); }
            require(prefs.launcherPage() == 3, "native paging persists the shared workspace page");
            main(() -> { bridge.onDisplayRemoved(target); bridge.refresh(); });
            until(() -> card.findViewById(R.id.launcher_page).getContentDescription().toString().startsWith("第4页"), "native lifecycle reset restores its page from shared preferences");
            int[] top = {0}; main(() -> { top[0] = nativeGridTop(); described(card, "同步改名，文件夹").performClick(); });
            until(() -> card.findViewById(R.id.launcher_back).getVisibility() == View.VISIBLE, "later-page folder shows its return bar");
            require(prefs.launcherPage() == 3, "folder page zero does not overwrite the remembered workspace page");
            main(() -> {
                View back = card.findViewById(R.id.launcher_back), header = card.findViewById(R.id.launcher_header);
                require(back.getWidth() == header.getWidth() && back.getHeight() == Ui.dp(activity, AppLauncherStyle.FOLDER_RETURN_HEIGHT), "whole 44dp title row is the folder return target");
                require(card.findViewById(R.id.launcher_summary).getVisibility() == View.GONE && card.findViewById(R.id.launcher_search_field).getVisibility() == View.GONE, "return bar borrows existing search and summary height");
                require(Math.abs(nativeGridTop() - top[0]) <= 1, "return bar does not push down the member grid");
                View apps = described(card, "全部应用（固定显示）"); require(!apps.isEnabled(), "Dock grid icon remains disabled inside folders"); apps.performClick(); bridge.action(widget, target, "apps", "");
                require(back.getVisibility() == View.VISIBLE && card.findViewById(R.id.launcher_body).getVisibility() == View.VISIBLE, "disabled or stale Dock click neither exits the folder nor hides the native body");
            });
            cardFrame("folder-return-180");
            main(() -> {
                android.view.ViewGroup grid = card.findViewById(R.id.launcher_grid); View surface = grid.findViewById(R.id.launcher_folder_backplate), first = grid.getChildAt(1), last = grid.getChildAt(grid.getChildCount() - 1);
                require(surface == grid.getChildAt(0) && !surface.isClickable() && !surface.isFocusable(), "folder background is behind members and does not consume blank touches");
                require(surface.getWidth() == surface.getHeight() && Math.abs(surface.getWidth() - (3 * first.getWidth() + 2 * Ui.dp(activity, AppLauncherStyle.FOLDER_PADDING))) <= 2, "two-member folder retains a full three-by-three square background");
                require(surface.getLeft() < first.getLeft() && surface.getTop() < first.getTop() && surface.getRight() > last.getRight() && surface.getBottom() > last.getBottom(), "rounded folder background encloses the compact member block with padding");
                require(first.getWidth() <= Ui.dp(activity, AppLauncherStyle.FOLDER_MEMBER_HEIGHT) && first.getHeight() == first.getWidth(), "folder members use compact square hit targets");
                require(Math.abs(first.getLeft() + last.getRight() - grid.getWidth()) <= 2 && Math.abs(first.getTop() + last.getBottom() - grid.getHeight()) <= 2, "two-member folder is centered in both axes: dx=" + (first.getLeft() + last.getRight() - grid.getWidth()) + ", dy=" + (first.getTop() + last.getBottom() - grid.getHeight()));
                require(card.findViewById(R.id.launcher_previous).getVisibility() == View.INVISIBLE && card.findViewById(R.id.launcher_next).getVisibility() == View.INVISIBLE, "single-page folder has no redundant paging buttons");
            });
            for (int blank : new int[]{0, 1, 2}) {
                main(() -> {
                    View grid = card.findViewById(R.id.launcher_grid); long down = SystemClock.uptimeMillis();
                    View surface = grid.findViewById(R.id.launcher_folder_backplate); float x = blank == 2 ? surface.getLeft() + 1 : blank == 1 ? grid.getWidth() - 2 : 2, y = blank == 2 ? surface.getTop() + surface.getHeight() / 2f : blank == 1 ? grid.getHeight() - 2 : 2;
                    for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) { android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + (action == android.view.MotionEvent.ACTION_UP ? 50 : 0), action, x, y, 0); grid.dispatchTouchEvent(event); event.recycle(); }
                });
                until(() -> card.findViewById(R.id.launcher_back).getVisibility() == View.GONE && card.findViewById(R.id.launcher_page).getContentDescription().toString().startsWith("第4页"), "blank corner closes the folder and restores its originating page");
                main(() -> { require(!card.findViewById(R.id.launcher_grid).hasOnClickListeners(), "workspace blank area does not keep a folder-dismiss listener"); described(card, "同步改名，文件夹").performClick(); });
                until(() -> card.findViewById(R.id.launcher_back).getVisibility() == View.VISIBLE, "folder can reopen after blank-area dismissal");
            }
            main(() -> {
                View back = card.findViewById(R.id.launcher_back); long down = SystemClock.uptimeMillis();
                for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) { android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + (action == android.view.MotionEvent.ACTION_UP ? 50 : 0), action, back.getWidth() - Ui.dp(activity, 8), back.getHeight() / 2f, 0); back.dispatchTouchEvent(event); event.recycle(); }
            });
            until(() -> card.findViewById(R.id.launcher_back).getVisibility() == View.GONE && card.findViewById(R.id.launcher_page).getContentDescription().toString().startsWith("第4页"), "tapping the far end of the return bar restores the original fourth page");
            main(() -> bridge.action(widget, target, "back", ""));
            require(card.findViewById(R.id.launcher_page).getContentDescription().toString().startsWith("第4页"), "repeated stale back does not reset the workspace to page one");
        } finally {
            main(() -> { bridge.action(widget, target, "back", ""); prefs.saveWorkspace(original, false); for (int i = 0; i < 4; i++) bridge.action(widget, target, "previous", ""); });
        }
        until(() -> text(card, "同步改名") != null, "folder fixture restores the original workspace");
    }
    private void rotateDisplay(int target, int rotation) throws Exception {
        try (java.io.InputStream output = new android.os.ParcelFileDescriptor.AutoCloseInputStream(test.getUiAutomation().executeShellCommand("wm user-rotation -d " + target + " lock " + rotation))) {
            byte[] buffer = new byte[1024]; while (output.read(buffer) != -1) { }
        }
        until(() -> test.getTargetContext().getSystemService(DisplayManager.class).getDisplay(target).getRotation() == rotation, "owned emulator display applies the requested rotation");
    }
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
                require(surface.getLeft() >= 0 && surface.getTop() >= 0 && surface.getRight() <= cell.getWidth() && surface.getBottom() <= cell.getHeight(), "square folder stays within its saved occupied area: surface=" + surface.getLeft() + "," + surface.getTop() + "," + surface.getRight() + "," + surface.getBottom() + "; cell=" + cell.getWidth() + "x" + cell.getHeight());
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
    private void sharedServiceLaunch(Context context, Prefs prefs, int widget, int target, String app, java.util.concurrent.atomic.AtomicInteger launchedDisplay) throws Exception {
        CoverService previous = CoverService.instance; CoverService[] service = {null};
        main(() -> { service[0] = new CoverService(); service[0].prefs = prefs; service[0].screenContext = context; });
        java.lang.reflect.Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(service[0], context);
        try {
            // Deliberately keep the transport on primary and its display extra stale.
            // Only the final app must be launched on the freshly selected secondary.
            main(() -> {
                CoverService.instance = service[0]; launchedDisplay.set(-1);
                Intent click = new Intent(context, LauncherWidgetActivity.class).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widget).putExtra("display", 0).putExtra("operation", "launch").putExtra("item", app).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { android.app.PendingIntent.getActivity(context, 75, click, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE, android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle()).send(); }
                catch (android.app.PendingIntent.CanceledException error) { throw new AssertionError(error); }
            });
            until(() -> launchedDisplay.get() == target, "native click uses the real floating service launch method and selected secondary despite a primary transport and stale display extra");
            require(CoverApp.launcher(context).diagnostics.report().contains("dispatch via=floating_service"), "native click delegates to the same service entry as the floating launcher");
            LauncherWidgetBridge widgets = CoverApp.launcherWidgets(context);
            main(widgets::refresh); SystemClock.sleep(250); test.waitForIdleSync();
            Instrumentation.ActivityMonitor monitor = test.addMonitor(LauncherWidgetActivity.class.getName(), null, false);
            try {
                launchedDisplay.set(-1); clickRail(card.findViewById(R.id.launcher_rail), 0);
                until(() -> launchedDisplay.get() == target, "service-backed sidebar Broadcast PendingIntent opens the app on the selected display");
                launchedDisplay.set(-1);
                main(() -> { View member = described(card.findViewById(R.id.launcher_grid), "修改后别名"); require(member != null && member.performClick(), "folder member dispatches its RemoteViews click"); });
                until(() -> launchedDisplay.get() == target, "service-backed folder Broadcast PendingIntent opens the app on the selected display");
                require(monitor.getHits() == 0, "ordinary native app clicks never create a Samsung Activity trampoline when the service exists");
                require(CoverApp.launcher(context).diagnostics.report().contains("transport=broadcast"), "diagnostics distinguish the direct broadcast path");
            } finally { test.removeMonitor(monitor); }
            for (String close : List.of("dismissHub", "dismissForHome", "native-hidden")) cancelledFloatingLaunch(context, prefs, service[0], target, app, launchedDisplay, close, widget);
        } finally {
            main(() -> { CoverService.instance = previous; service[0].main.removeCallbacksAndMessages(null); CoverApp.launcherWidgets(context).refresh(); });
            java.lang.reflect.Field files = CoverService.class.getDeclaredField("files"); files.setAccessible(true); ((java.util.concurrent.ExecutorService) files.get(service[0])).shutdownNow();
        }
    }
    private void nativeHostContinuity(Context context, Prefs prefs, int widget, android.view.Display target) throws Exception {
        CoverService previous = CoverService.instance; CoverService[] owner = {null}; NativeWidgetBridge widgets = CoverApp.widgets(context);
        AppWidgetManager manager = AppWidgetManager.getInstance(context); Bundle original = manager.getAppWidgetOptions(widget);
        int oldWidth = card.getLayoutParams().width, oldHeight = card.getLayoutParams().height;
        android.graphics.Point pixels = Displays.size(target); float density = context.createDisplayContext(target).getResources().getDisplayMetrics().density;
        DockGeometry.Box hostArea = new DockGeometry.Box(0, 66, pixels.x, pixels.y - 66), safe = new DockGeometry.Box(0, 151, pixels.x, pixels.y - 151);
        java.lang.reflect.Method attach = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true);
        java.lang.reflect.Method observed = CoverService.class.getDeclaredMethod("nativeHostArea", android.view.Display.class, DockGeometry.Box.class); observed.setAccessible(true);
        java.lang.reflect.Field visible = CoverService.class.getDeclaredField("nativeHostVisible"); visible.setAccessible(true);
        java.lang.reflect.Field deferred = CoverService.class.getDeclaredField("nativeGeometryDeferred"); deferred.setAccessible(true);
        java.lang.reflect.Method resume = CoverService.class.getDeclaredMethod("resumeNativeCardUpdates"); resume.setAccessible(true);
        java.lang.reflect.Field update = CoverService.class.getDeclaredField("updateDisplay"); update.setAccessible(true);
        java.lang.reflect.Field settled = CoverService.class.getDeclaredField("settledDisplay"); settled.setAccessible(true);
        java.util.function.Consumer<Boolean> foreground = shown -> { try { visible.setBoolean(owner[0], shown); } catch (Exception error) { throw new AssertionError(error); } };
        java.util.function.Consumer<DockGeometry.Box> observe = bounds -> { try { observed.invoke(owner[0], target, bounds); } catch (Exception error) { throw new AssertionError(error); } };
        List<DockGeometry.Box> layouts = new ArrayList<>();
        android.view.ViewTreeObserver.OnGlobalLayoutListener changed = () -> { View panel = card.findViewById(R.id.launcher_panel); if (panel != null) layouts.add(new DockGeometry.Box(panel.getLeft(), panel.getTop(), panel.getWidth(), panel.getHeight())); };
        main(() -> { owner[0] = new CoverService(); owner[0].prefs = prefs; owner[0].screenContext = activity; owner[0].display = target; });
        attach.invoke(owner[0], context);
        try {
            main(() -> {
                CoverService.instance = owner[0];
                foreground.accept(true);
                ViewGroup.LayoutParams params = card.getLayoutParams(); params.width = Ui.dp(activity, pixels.x / density); params.height = Ui.dp(activity, hostArea.height() / density); card.setLayoutParams(params);
                widgets.safeArea(target, pixels.x, pixels.y, safe); observe.accept(hostArea);
                Bundle options = new Bundle(original); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, Math.round(pixels.x / density)); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, Math.round(pixels.x / density));
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, Math.round(pixels.y / density)); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, Math.round(pixels.y / density));
                manager.updateAppWidgetOptions(widget, options); CoverApp.launcherWidgets(context).refresh();
            });
            until(() -> Math.abs(card.findViewById(R.id.launcher_panel).getTop() - Ui.dp(activity, 85 / density)) <= 1, "measured native host consumes the notch inset once before transition checks");
            WidgetSafeArea.Frame[] expected = {null}; DockGeometry.Box[] initial = {null};
            main(() -> {
                expected[0] = widgets.launcherFrame(widget); View panel = card.findViewById(R.id.launcher_panel); initial[0] = new DockGeometry.Box(panel.getLeft(), panel.getTop(), panel.getWidth(), panel.getHeight());
                card.getViewTreeObserver().addOnGlobalLayoutListener(changed);
            });
            for (int step = 0; step < 6; step++) {
                String[] previousText = {null}; boolean[] nextFailure = {false};
                main(() -> {
                    foreground.accept(false);
                    observe.accept(null);
                    require(hostArea.equals(owner[0].nativeHostBounds(target)), "temporary foreground loss retains the confirmed native host geometry");
                    require(expected[0].equals(widgets.launcherFrame(widget)), "a hidden or returning card never publishes the full-display fallback frame");
                    require(!owner[0].nativeInputEligible(), "retained geometry cannot make a hidden native card eligible for input");
                    previousText[0] = ((TextView) card.findViewById(R.id.launcher_refresh)).getText().toString(); nextFailure[0] = !previousText[0].contains("刷新失败");
                    if (nextFailure[0]) CoverApp.launcherWidgets(context).recentFailure("deferred fixture"); else CoverApp.launcherWidgets(context).recent(List.of(), true, true);
                    widgets.safeArea(target, pixels.x, pixels.y, new DockGeometry.Box(0, 87, pixels.x, safe.height()));
                    CoverApp.launcherWidgets(context).refresh();
                    try { require(deferred.getBoolean(owner[0]), "an app's navigation geometry defers native publication instead of reflowing the hidden card"); } catch (Exception error) { throw new AssertionError(error); }
                });
                SystemClock.sleep(100); test.waitForIdleSync();
                main(() -> {
                    require(previousText[0].contentEquals(((TextView) card.findViewById(R.id.launcher_refresh)).getText()), "content changes do not republish the hidden card with application insets");
                    widgets.safeArea(target, pixels.x, pixels.y, safe); foreground.accept(true); observe.accept(hostArea);
                    try {
                        Runnable pending = (Runnable) update.get(owner[0]); owner[0].main.postDelayed(pending, 30000);
                        resume.invoke(owner[0]); require(deferred.getBoolean(owner[0]), "visible host waits while display reconciliation is queued");
                        owner[0].main.removeCallbacks(pending); Runnable stable = (Runnable) settled.get(owner[0]); owner[0].main.postDelayed(stable, 30000);
                        resume.invoke(owner[0]); require(deferred.getBoolean(owner[0]), "first layout pass must not publish the outgoing application's unsettled navigation insets");
                        owner[0].main.removeCallbacks(stable); resume.invoke(owner[0]);
                        require(!deferred.getBoolean(owner[0]), "reconciled host resumes pending native content updates");
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                until(() -> ((TextView) card.findViewById(R.id.launcher_refresh)).getText().toString().contains("刷新失败") == nextFailure[0], "resumption schedules the latest RemoteViews without a direct refresh from the test");
            }
            main(() -> {
                require(!layouts.isEmpty() && layouts.stream().allMatch(initial[0]::equals), "every real RemoteViews layout during leave/return keeps the same origin and size");
                card.getViewTreeObserver().removeOnGlobalLayoutListener(changed);
            });
            centeredNativeLayout(context, prefs, widget, target, owner[0]);
            main(() -> {
                try { java.lang.reflect.Method rebuild = CoverService.class.getDeclaredMethod("removeWindows", boolean.class); rebuild.setAccessible(true); rebuild.invoke(owner[0], true); } catch (Exception error) { throw new AssertionError(error); }
                require(hostArea.equals(owner[0].nativeHostBounds(target)), "same-session overlay rebuild does not erase native host geometry");
                widgets.safeArea(target, pixels.x, pixels.y, safe);
                foreground.accept(true);
                observe.accept(new DockGeometry.Box(0, 0, pixels.x, pixels.y));
                require(widgets.launcherFrame(widget).equals(WidgetSafeArea.fit(pixels.x / density, pixels.y / density, pixels.x, pixels.y, density, safe)), "a measured full host immediately restores the original full-mode calculation");
                observe.accept(hostArea); owner[0].onDisplayRemoved(target.getDisplayId());
                require(owner[0].nativeHostBounds(target) == null, "display removal immediately invalidates retained geometry");
                observe.accept(hostArea);
                try { java.lang.reflect.Method teardown = CoverService.class.getDeclaredMethod("removeWindows"); teardown.setAccessible(true); teardown.invoke(owner[0]); } catch (Exception error) { throw new AssertionError(error); }
                require(owner[0].nativeHostBounds(target) == null, "screen-off and service teardown release native host geometry");
            });
        } finally {
            main(() -> {
                card.getViewTreeObserver().removeOnGlobalLayoutListener(changed); CoverService.instance = previous; owner[0].main.removeCallbacksAndMessages(null);
                widgets.safeArea(null, 0, 0, null); ViewGroup.LayoutParams params = card.getLayoutParams(); params.width = oldWidth; params.height = oldHeight; card.setLayoutParams(params);
                manager.updateAppWidgetOptions(widget, original); CoverApp.launcherWidgets(context).refresh();
            });
            java.lang.reflect.Field files = CoverService.class.getDeclaredField("files"); files.setAccessible(true); ((java.util.concurrent.ExecutorService) files.get(owner[0])).shutdownNow();
        }
    }
    private android.graphics.Rect nativeBounds(int id) {
        View view = card.findViewById(id); android.graphics.Rect bounds = new android.graphics.Rect(0, 0, view.getWidth(), view.getHeight()); card.offsetDescendantRectToMyCoords(view, bounds); return bounds;
    }
    private void centeredNativeLayout(Context context, Prefs prefs, int widget, android.view.Display target, CoverService owner) throws Exception {
        require(target.getRotation() == Surface.ROTATION_180, "centering fixture uses the actual upside-down display");
        android.graphics.Point pixels = Displays.size(target); float density = context.createDisplayContext(target).getResources().getDisplayMetrics().density;
        java.lang.reflect.Field area = CoverService.class.getDeclaredField("hubAvailableFrame"); area.setAccessible(true);
        DockGeometry.Box[] centered = {null}; LauncherWidgetBridge bridge = CoverApp.launcherWidgets(context); NativeWidgetBridge widgets = CoverApp.widgets(context);
        main(() -> {
            List<DockGeometry.Box> cuts = List.of(new DockGeometry.Box(0, 0, pixels.x / 2, 66));
            DockGeometry.Placement anchor = DockGeometry.resolve(pixels.x, pixels.y, cuts, density, 1, .46f, .088f, true);
            owner.placement = DockGeometry.edgeTouch(anchor, pixels.x, pixels.y);
            owner.resolveContentGeometry(pixels, target.getRotation(), cuts, anchor, android.graphics.Insets.of(0, 66, 0, 0), 0);
            try { centered[0] = (DockGeometry.Box) area.get(owner); area.set(owner, owner.launcherContentBounds(target.getDisplayId())); } catch (Exception error) { throw new AssertionError(error); }
            bridge.refresh();
        });
        until(() -> Math.abs(card.findViewById(R.id.launcher_panel).getTop() - Ui.dp(activity, widgets.launcherFrame(widget).top())) <= 1, "baseline native geometry is rendered");
        android.graphics.Rect[] body = {null}, rail = {null}, dock = {null};
        main(() -> { body[0] = nativeBounds(R.id.launcher_catalog); rail[0] = nativeBounds(R.id.launcher_rail_panel); dock[0] = nativeBounds(R.id.launcher_pins); });
        cardFrame("hub-180-center-before");
        main(() -> { try { area.set(owner, centered[0]); } catch (Exception error) { throw new AssertionError(error); } bridge.refresh(); });
        until(() -> nativeBounds(R.id.launcher_catalog).top < body[0].top, "native body moves upward after reclaiming the entry tail");
        main(() -> {
            android.graphics.Rect moved = nativeBounds(R.id.launcher_catalog), movedRail = nativeBounds(R.id.launcher_rail_panel);
            require(moved.width() == body[0].width() && moved.height() == body[0].height(), "native body size stays unchanged after centering");
            require(movedRail.height() == rail[0].height() && rail[0].top - movedRail.top == body[0].top - moved.top, "native sidebar and settings move by the same amount as the catalog");
            require(dock[0].equals(nativeBounds(R.id.launcher_pins)), "native Dock remains exactly fixed in host coordinates");
            int space = Math.max(0, Ui.dp(activity, widgets.launcherFrame(widget).top()) - Ui.dp(activity, widgets.launcherFrame(widget, true).top()));
            sharedHubLayout(prefs, space); fixedGrid(prefs);
            int top = nativeBounds(R.id.launcher_panel).top + Ui.dp(activity, AppLauncherStyle.SURFACE_INSET);
            require(Math.abs((moved.top - top) - (dock[0].top - moved.bottom)) <= 2, "native body shares the reclaimed space above and below");
            described(card, "编辑说明").performClick();
        });
        until(() -> nativeToast(bridge) != null, "moved settings button retains its native PendingIntent");
        cardFrame("hub-180-center-after");
    }
    private void cancelledFloatingLaunch(Context context, Prefs prefs, CoverService owner, int target, String app, java.util.concurrent.atomic.AtomicInteger launchedDisplay, String close, int widget) throws Exception {
        ShizukuBridge bridge = CoverApp.bridge(context); java.lang.reflect.Field remote = ShizukuBridge.class.getDeclaredField("remote"); remote.setAccessible(true); Object previous = remote.get(bridge);
        String packageName = ActionCatalog.component(app).getPackageName(); int rotation = prefs.appRotation(packageName);
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1), release = new java.util.concurrent.CountDownLatch(1);
        IShellService controlled = new IShellService.Stub() {
            @Override public void watchConnectivity(IConnectivityListener listener) { }
            public String execute(String operation, int display, int value, String item) { entered.countDown(); try { release.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException error) { Thread.currentThread().interrupt(); } return "{\"ok\":true}"; }
            public Bundle taskSnapshot(int display, String item) { return null; }
            public void destroy() { }
        };
        try {
            prefs.saveAppRotation(packageName, 0); remote.set(bridge, controlled); launchedDisplay.set(-1);
            boolean nativeHidden = close.equals("native-hidden"); LauncherWidgetBridge widgets = CoverApp.launcherWidgets(context); Bundle hidden = new Bundle(); hidden.putBoolean("visible", false);
            if (nativeHidden) main(() -> widgets.optionsChanged(widget, hidden));
            main(() -> { owner.display = context.getSystemService(DisplayManager.class).getDisplay(target); if (nativeHidden) widgets.action(widget, target, "launch", app); else owner.launchApp(app); });
            require(entered.await(3, java.util.concurrent.TimeUnit.SECONDS), "floating launch waits for its controlled direction rule");
            if (nativeHidden) main(() -> widgets.optionsChanged(widget, hidden));
            else {
                java.lang.reflect.Method dismiss = CoverService.class.getDeclaredMethod(close); dismiss.setAccessible(true);
                main(() -> { try { dismiss.invoke(owner); } catch (Exception error) { throw new AssertionError(error); } });
            }
            release.countDown();
            until(() -> { String log = CoverApp.launcher(context).diagnostics.report(); return log.lastIndexOf("cancelled reason=owner_inactive") > log.lastIndexOf("rotation requested="); }, close + " cancels its late direction callback");
            require(launchedDisplay.get() == -1, "cancelled direction callback never starts the app");
        } finally { release.countDown(); remote.set(bridge, previous); prefs.saveAppRotation(packageName, rotation); }
    }
    private void frame(String name) throws Exception {
        SystemClock.sleep(160); android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot(); java.io.File dir = new java.io.File(activity.getFilesDir(), "launcher-widget-raw"); dir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { image.recycle(); }
    }
    private void cardFrame(String name) throws Exception {
        android.graphics.Bitmap[] image = {null};
        main(() -> { image[0] = android.graphics.Bitmap.createBitmap(card.getWidth(), card.getHeight(), android.graphics.Bitmap.Config.ARGB_8888); card.draw(new android.graphics.Canvas(image[0])); });
        java.io.File dir = new java.io.File(activity.getFilesDir(), "launcher-widget-raw"); dir.mkdirs();
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name + ".png"))) { image[0].compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } finally { image[0].recycle(); }
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
            main(() -> {
                Bundle shown = new Bundle(), hidden = new Bundle(); shown.putBoolean("visible", true); hidden.putBoolean("visible", false);
                bridge.optionsChanged(-1, shown); require(!bridge.visible(target), "unknown widget cannot hide launcher entry");
                bridge.optionsChanged(ids[0], shown); require(bridge.visible(target) && !bridge.visible(0) && !bridge.visible(target + 1), "visibility belongs only to the selected secondary display");
                bridge.optionsChanged(ids[0], new Bundle()); require(bridge.visible(target), "size-only options preserve live visibility");
                bridge.optionsChanged(ids[1], shown); bridge.optionsChanged(ids[0], hidden); require(bridge.visible(target), "one hidden instance does not erase another visible card");
                bridge.optionsChanged(ids[1], hidden); require(!bridge.visible(target), "last hidden card restores launcher entry");
                bridge.optionsChanged(ids[0], shown); bridge.remove(ids[0]); require(!bridge.visible(target), "removal releases visibility immediately");
                bridge.onDisplayRemoved(target); bridge.optionsChanged(ids[0], shown);
                require(bridge.visible(target), "first live visible callback restores the display session before a queued refresh"); bridge.optionsChanged(ids[0], hidden);
            });
            main(this::filledBackground);
            require(!java.util.Arrays.stream(CoverApp.widgets(context).cards()).anyMatch(id -> id == ids[0]), "launcher does not consume a combination slot");
            // Visibility/removal checks above invalidate the display session and queue RemoteViews.
            // Restore the actually displayed card and let its new PendingIntents reach the host.
            main(() -> { Bundle visible = new Bundle(); visible.putBoolean("visible", true); bridge.optionsChanged(ids[0], visible); bridge.refresh(); });
            SystemClock.sleep(160); test.waitForIdleSync();
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
            main(this::filledBackground);
            main(() -> { ViewGroup.LayoutParams params = card.getLayoutParams(); params.height = Ui.dp(activity, 280); card.setLayoutParams(params); manager.updateAppWidgetOptions(ids[0], originalOptions); bridge.refresh(); });
            until(() -> card.findViewById(R.id.launcher_grid).getHeight() == originalGridHeight, "native host restores original height"); main(() -> fixedGrid(prefs));
            require(describedCount(card.findViewById(R.id.launcher_pins), "常用：") == prefs.hubPins().size(), "native fixed row uses shared Dock membership");
            require(described(card, "侧栏：旋转方向") != null, "native sidebar reads floating favorites");
            android.widget.ListView rail = card.findViewById(R.id.launcher_rail);
            main(() -> {
                int full = 0; for (int i = 0; i < rail.getChildCount(); i++) {
                    View row = rail.getChildAt(i); if (row.getTop() >= rail.getPaddingTop() && row.getBottom() <= rail.getHeight() - rail.getPaddingBottom()) full++;
                    TextView label = row.findViewById(R.id.launcher_label); View icon = row.findViewById(R.id.launcher_icon);
                    require(label.getTop() >= icon.getBottom() && label.getBottom() <= row.getHeight() && label.getLayout().getHeight() <= label.getHeight(), "compact sidebar icon and label stay separate without clipping");
                    require(Math.abs(label.getTextSize() / activity.getResources().getDisplayMetrics().density - AppLauncherStyle.RAIL_LABEL_SP) < .1f, "native sidebar font stays fixed when host font scale changes");
                }
                int rowHeight = rail.getChildAt(0).getHeight(), available = rail.getHeight() - rail.getPaddingTop() - rail.getPaddingBottom();
                require(full == Math.min(rail.getAdapter().getCount(), available / rowHeight), "sidebar fills the padded viewport with complete compact items and retains overflow for scrolling");
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
            main(() -> {
                View appsButton = described(card, "全部应用（固定显示）"), icon = appsButton.findViewById(R.id.launcher_icon), mark = appsButton.findViewById(R.id.launcher_disabled_mark);
                require(!appsButton.isEnabled(), "native Dock grid icon is not an action");
                require(mark.getVisibility() == View.VISIBLE && Math.abs(mark.getWidth() * 2 - icon.getWidth()) <= 1 && mark.getHeight() == mark.getWidth() && Math.abs(mark.getLeft() * 2 + mark.getWidth() - icon.getLeft() * 2 - icon.getWidth()) <= 1 && Math.abs(mark.getTop() * 2 + mark.getHeight() - icon.getTop() * 2 - icon.getHeight()) <= 1 && Math.abs(mark.getAlpha() - .7f) < .01f, "Material block mark is centered at half size and 70 percent opacity");
                appsButton.performClick(); bridge.action(ids[0], target, "apps", "");
            });
            require(card.findViewById(R.id.launcher_grid).getVisibility() == View.VISIBLE && card.findViewById(R.id.launcher_rail).getVisibility() == View.VISIBLE, "disabled grid icon and stale toggle intents cannot collapse the native card");
            main(this::filledBackground);
            List<RecentTasks.Task> tasks = new ArrayList<>();
            for (int i = 0; i < 6; i++) { String pkg = "fixture.recent" + i; tasks.add(new RecentTasks.Task(100 + i, target, android.os.Process.myUid() / 100000, new ComponentName(pkg, pkg + ".Main").flattenToString(), pkg, i == 0)); }
            require(AppRecentTasks.tasks(new org.json.JSONArray(AppRecentTasks.encode(tasks)), target).equals(tasks), "native pending task payload preserves canonical system identity");
            main(() -> bridge.recent(tasks, true, true));
            until(() -> describedCount(card, "最近任务：") == 4, "native Dock renders at most four recent app groups");
            main(() -> {
                View dock = card.findViewById(R.id.launcher_dock_row), appsButton = described(dock, "全部应用（固定显示）"), clearButton = described(dock, "清理可见最近应用");
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
            java.util.Map<String, Object> afterPaging = new java.util.HashMap<>(prefs.data.getAll()); java.util.Map<String, Object> beforePaging = new java.util.HashMap<>(before);
            for (String key : new String[]{"launcher_page", "launcher_page_boot"}) { afterPaging.remove(key); beforePaging.remove(key); }
            require(beforePaging.equals(afterPaging) && prefs.launcherPage() == 1, "native pagination saves only navigation without rearranging the workspace or changing settings");
            main(() -> card.findViewById(R.id.launcher_previous).performClick());
            until(() -> text(card, "共享文件夹") != null, "previous-page button restores folder page");
            main(() -> described(card, "共享文件夹，文件夹").performClick());
            until(() -> text(card.findViewById(R.id.launcher_grid), "共享别名") != null, "folder opens inside the card with shared alias");
            main(() -> { View back = card.findViewById(R.id.launcher_back); require(back.getWidth() == card.findViewById(R.id.launcher_header).getWidth() && back.getHeight() == Ui.dp(activity, AppLauncherStyle.FOLDER_RETURN_HEIGHT), "legacy native folder also has a full-width 44dp return bar"); });
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
                main(() -> {
                    android.view.ViewGroup grid = card.findViewById(R.id.launcher_grid); View member = described(grid, "修改后别名"); android.graphics.Rect hit = new android.graphics.Rect(); member.getDrawingRect(hit); grid.offsetDescendantRectToMyCoords(member, hit); long down = SystemClock.uptimeMillis();
                    for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) { android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + (action == android.view.MotionEvent.ACTION_UP ? 50 : 0), action, hit.centerX(), hit.centerY(), 0); grid.dispatchTouchEvent(event); event.recycle(); }
                }); until(() -> launchedDisplay.get() == target, "member touch through the folder grid launches the app instead of dismissing the folder");
                String launchReport = CoverApp.launcher(context).diagnostics.report();
                require(launchReport.contains("source=native_card") && launchReport.contains("preflight") && launchReport.contains("start_activity returned visibility=unconfirmed"), "native PendingIntent diagnostics cover lifecycle, checks and accepted launch");
                require(!launchReport.contains(all.get(0)), "native launch diagnostics exclude application identity");
                sharedServiceLaunch(context, prefs, ids[0], target, all.get(0), launchedDisplay);
            }
            finally { context.unregisterReceiver(launched); }
            main(() -> prefs.saveActions("favorites", List.of("app_dock", all.get(0))));
            until(() -> described(card, "侧栏：应用 Dock") != null, "collection updates configured system shortcuts");
            android.widget.Toast beforeDockShortcut = nativeToast(bridge); clickRail(card.findViewById(R.id.launcher_rail), 0);
            until(() -> nativeToast(bridge) != beforeDockShortcut, "sidebar Dock shortcut delegates to the service instead of collapsing the native card");
            require(card.findViewById(R.id.launcher_grid).getVisibility() == View.VISIBLE, "native folder remains visible when the floating service is unavailable");
            main(() -> card.findViewById(R.id.launcher_back).performClick());
            until(() -> text(card, "共享文件夹") != null, "back returns to workspace");
            String folder = prefs.workspace().parent(all.get(0));
            main(() -> prefs.saveWorkspace(prefs.workspace().rename(folder, "同步改名"), false));
            until(() -> text(card, "同步改名") != null, "folder rename from shared model refreshes card");
            // Insets arrive in current display coordinates. Exercise each camera-side edge.
            for (DockGeometry.Box safe : new DockGeometry.Box[]{new DockGeometry.Box(0, 0, 720, 648), new DockGeometry.Box(0, 0, 620, 748), new DockGeometry.Box(0, 100, 720, 648), new DockGeometry.Box(100, 0, 620, 748)}) {
                main(() -> CoverApp.widgets(context).safeArea(display.getDisplay(), 720, 748, safe));
                until(() -> {
                    View panel = card.findViewById(R.id.launcher_panel);
                    return Math.abs(panel.getLeft() - Ui.dp(activity, safe.x() / 2f)) <= 1 && Math.abs(panel.getTop() - Ui.dp(activity, safe.y() / 2f)) <= 1 && Math.abs(panel.getWidth() - Ui.dp(activity, 310 - (720 - safe.width()) / 2f)) <= 1 && Math.abs(panel.getHeight() - Ui.dp(activity, 280 - (748 - safe.height()) / 2f)) <= 1;
                }, "content follows the reported safe edge without moving the full background");
                main(() -> { filledBackground(); fixedGrid(prefs); });
            }
            main(() -> { CoverApp.widgets(context).safeArea(display.getDisplay(), 720, 748, new DockGeometry.Box(160, 60, 560, 608)); bridge.action(ids[0], target, "edit", ""); });
            until(() -> Math.abs(card.findViewById(R.id.launcher_panel).getLeft() - Ui.dp(activity, 80)) <= 1, "native card follows rotated-edge safe area");
            main(() -> {
                View panel = card.findViewById(R.id.launcher_panel);
                require(panel.getVisibility() == View.VISIBLE && Math.abs(panel.getTop() - Ui.dp(activity, 30)) <= 1, "Toast does not cover or replace the native panel");
                require(panel.getRight() <= card.getWidth() && panel.getBottom() <= card.getHeight(), "panel stays inside native host bounds");
                filledBackground();
                CoverApp.widgets(context).safeArea(null, 0, 0, null);
            });
            until(() -> card.findViewById(R.id.launcher_panel).getLeft() == 0, "restored content geometry reaches the host before idle-cache check");
            until(() -> card.findViewById(R.id.launcher_panel).getHeight() == Ui.dp(activity, 280), "normal orientation restores full content height");
            rotateDisplay(target, android.view.Surface.ROTATION_180);
            until(() -> card.findViewById(R.id.launcher_catalog) != null, "180-degree native card uses the shared hub regions");
            main(() -> {
                View panel = card.findViewById(R.id.launcher_panel), dock = card.findViewById(R.id.launcher_pins);
                require(panel.getTop() == 0 && panel.getLeft() == 0 && panel.getHeight() == Ui.dp(activity, 280), "shared layout uses available safe space without the old extra 20dp strip");
                require(dock.getBottom() == panel.getHeight(), "independent Dock reaches the same safe bottom as the floating launcher");
                filledBackground(); fixedGrid(prefs); sharedHubLayout(prefs);
            });
            surfaceBroadcasts(bridge, ids[0], target);
            cardFrame("hub-180-card");
            main(() -> { described(card, "全部应用（固定显示）").performClick(); bridge.action(ids[0], target, "apps", ""); filledBackground(); require(card.findViewById(R.id.launcher_body).getVisibility() == View.VISIBLE, "180-degree native body cannot collapse"); });
            folderReturn(prefs, bridge, ids[0], target, folder);
            nativeHostContinuity(context, prefs, ids[0], display.getDisplay());
            // Keep the fixture host on the primary display while rotating its target;
            // otherwise MainActivity recreation leaves the assertions on a detached card.
            Activity rotatedHost = activity;
            Activity stableHost = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(0).toBundle());
            main(() -> {
                activity = stableHost; card = host.createView(activity, ids[0], manager.getAppWidgetInfo(ids[0])); card.setPadding(0, 0, 0, 0);
                FrameLayout root = new FrameLayout(activity); root.addView(card, new FrameLayout.LayoutParams(Ui.dp(activity, 310), Ui.dp(activity, 280))); activity.setContentView(root); rotatedHost.finish();
            });
            for (int rotation : new int[]{Surface.ROTATION_90, Surface.ROTATION_270}) {
                rotateDisplay(target, rotation);
                for (String hand : new String[]{"left", "right"}) {
                    main(() -> prefs.data.edit().putString("hand_side", hand).commit());
                    String[] bounds = {"pending"};
                    try { until(() -> {
                        View panel = card.findViewById(R.id.launcher_panel), sidebar = card.findViewById(R.id.launcher_rail_panel);
                        bounds[0] = "rotation=" + rotation + ", hand=" + hand + ", panel=" + panel.getWidth() + "x" + panel.getHeight() + ", sidebar=" + (sidebar == null ? "missing" : sidebar.getLeft() + ".." + sidebar.getRight());
                        int offset = AppLauncherStyle.railOffset(activity.getResources().getDisplayMetrics().density, hand.equals("right"));
                        int inset = Ui.dp(activity, AppLauncherStyle.SURFACE_INSET);
                        return sidebar != null && sidebar.getWidth() > 0 && Math.abs((hand.equals("left") ? sidebar.getLeft() - inset : sidebar.getRight() - panel.getWidth() + inset) - offset) <= 1;
                    }, "landscape card centers the sidebar in the reserved gap on the selected side"); }
                    catch (AssertionError failure) { throw new AssertionError(bounds[0], failure); }
                    main(() -> {
                        View panel = card.findViewById(R.id.launcher_panel), dock = card.findViewById(R.id.launcher_pins);
                        WidgetSafeArea.Frame safe = CoverApp.widgets(context).launcherFrame(ids[0]);
                        require(Math.abs(panel.getLeft() - Ui.dp(activity, safe.left())) <= 1 && Math.abs(panel.getTop() - Ui.dp(activity, safe.top())) <= 1 && Math.abs(panel.getHeight() - Ui.dp(activity, safe.height())) <= 1, "landscape content uses the launcher safe frame");
                        require(dock.getBottom() == panel.getHeight(), "landscape Dock reaches the shared safe bottom");
                        filledBackground(); fixedGrid(prefs); sharedHubLayout(prefs);
                    });
                    cardFrame("hub-" + (rotation == Surface.ROTATION_90 ? "90" : "270") + "-" + hand);
                }
                folderReturn(prefs, bridge, ids[0], target, folder);
                // Reproduce Samsung retaining the natural full-display dimensions
                // while the actual native host follows the rotated display.
                main(() -> {
                    android.graphics.Point pixels = Displays.size(display.getDisplay());
                    float density = context.createDisplayContext(display.getDisplay()).getResources().getDisplayMetrics().density;
                    Bundle stale = new Bundle();
                    stale.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 360); stale.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 360);
                    stale.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 374); stale.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 374);
                    manager.updateAppWidgetOptions(ids[0], stale);
                    card.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(activity, pixels.x / density), Ui.dp(activity, pixels.y / density))); bridge.refresh();
                });
                until(() -> card.findViewById(R.id.launcher_panel).getHeight() == card.getHeight(), "stale natural-orientation options do not shorten the rotated full-screen launcher");
                main(() -> { sharedHubLayout(prefs); fixedGrid(prefs); });
                main(() -> {
                    manager.updateAppWidgetOptions(ids[0], CoverApp.widgets(context).options(new android.util.SizeF(310, 280)));
                    card.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(activity, 310), Ui.dp(activity, 280))); bridge.refresh();
                });
                until(() -> card.findViewById(R.id.launcher_panel).getHeight() == Ui.dp(activity, 280), "resized cards retain their own bounds after full-screen rotation");
            }
            rotateDisplay(target, android.view.Surface.ROTATION_0);
            until(() -> card.findViewById(R.id.launcher_catalog) == null, "returning to zero degrees restores the existing card presentation");
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
            main(this::filledBackground);
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
