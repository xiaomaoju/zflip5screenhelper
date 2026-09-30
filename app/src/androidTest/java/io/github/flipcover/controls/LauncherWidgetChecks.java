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
        ViewGroup panel = card.findViewById(R.id.launcher_panel);
        AppHubView floating = new AppHubView(activity, prefs, new AppHubView.Listener() {
            public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { }
        });
        try {
            floating.measure(View.MeasureSpec.makeMeasureSpec(panel.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(panel.getHeight(), View.MeasureSpec.EXACTLY)); floating.layout(0, 0, panel.getWidth(), panel.getHeight());
            ViewGroup tools = floating.findViewWithTag("hub-tools"), nativeTools = panel.findViewById(R.id.launcher_rail_tools);
            require(tools.getChildCount() == 1 && nativeTools.getChildCount() == 1, "both sidebar footers contain only the edit button");
            require(floating.findViewWithTag("hub-close") != null && described(card, "收起应用区，保留 Dock") == null && described(card, "关闭应用中心") == null, "only the floating launcher has a close button");
            View rail = floating.findViewWithTag("hub-rail"), nativeRail = panel.findViewById(R.id.launcher_rail_panel);
            require(Math.abs(rail.getLeft() - nativeRail.getLeft()) <= 1 && Math.abs(rail.getWidth() - nativeRail.getWidth()) <= 1 && Math.abs(rail.getHeight() - nativeRail.getHeight()) <= 1, "both hosts consume the same full-height sidebar geometry");
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
            for (int id : new int[]{R.id.launcher_search, R.id.launcher_sort, R.id.launcher_tasks}) {
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
            int[] top = {0}; main(() -> { top[0] = nativeGridTop(); described(card, "同步改名，文件夹").performClick(); });
            until(() -> card.findViewById(R.id.launcher_back).getVisibility() == View.VISIBLE, "later-page folder shows its return bar");
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
                require(Math.abs(first.getLeft() + last.getRight() - grid.getWidth()) <= 2 && Math.abs(first.getTop() + last.getBottom() - grid.getHeight()) <= 2, "two-member folder is centered in both axes");
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
            main(this::filledBackground);
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
            main(this::filledBackground);
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
            require(before.equals(prefs.data.getAll()), "native pagination cannot rearrange saved workspace");
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
