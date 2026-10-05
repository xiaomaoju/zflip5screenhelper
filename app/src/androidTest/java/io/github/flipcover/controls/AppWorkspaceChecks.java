package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real native views and touch sequences on the runner's disposable emulator only. */
final class AppWorkspaceChecks {
    private final Instrumentation test;
    private final int expectedRotation;
    private Activity activity;
    private AppHubView hub;
    private AppWorkspaceView grid;
    private Prefs prefs;
    private int assertions, launches;
    private long down;
    AppWorkspaceChecks(Instrumentation test, int expectedRotation) { this.test = test; this.expectedRotation = expectedRotation; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void main(Runnable action) { Throwable[] failure = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } }); if (failure[0] != null) throw new AssertionError(failure[0]); test.waitForIdleSync(); }
    private void mount(AppHubView.WorkspaceState state) {
        main(() -> {
            if (hub != null) hub.dispose();
            hub = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { launches++; } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(Ui.BACKGROUND);
            FrameLayout.LayoutParams parameters = new FrameLayout.LayoutParams(-1, -1); parameters.setMargins(Ui.dp(activity, 12), Ui.dp(activity, 28), Ui.dp(activity, 12), Ui.dp(activity, 56)); root.addView(hub, parameters); activity.setContentView(root);
            grid = hub.findViewWithTag("hub-grid"); hub.restoreWorkspaceState(state);
        });
        SystemClock.sleep(100); test.waitForIdleSync();
    }
    private void touch(int action, float x, float y) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis();
        main(() -> { MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); grid.dispatchTouchEvent(event); event.recycle(); });
    }
    private float x(int slot) { return ((slot % grid.capacity()) % grid.getNumColumns() + .5f) * (grid.getWidth() / grid.getNumColumns()); }
    private float y(int slot) { return ((slot % grid.capacity()) / grid.getNumColumns() + .5f) * (grid.gridHeight() / (grid.capacity() / grid.getNumColumns())); }
    private void lift(String id) {
        int slot = grid.layoutSnapshot().slot(id); main(() -> grid.settlePage(slot / grid.capacity(), false));
        require(grid.editable() && id.equals(grid.hit(x(slot), y(slot))), "drag starts on the expected application in manual mode");
        touch(MotionEvent.ACTION_DOWN, x(slot), y(slot)); SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 100); test.waitForIdleSync();
        require(grid.dragging(), "long press lifts application; " + diagnostics());
    }
    private String diagnostics() {
        try { java.lang.reflect.Field field = AppWorkspaceView.class.getDeclaredField("gestures"); field.setAccessible(true); Object controller = field.get(grid); StringBuilder result = new StringBuilder("attached=" + grid.isAttachedToWindow() + " size=" + grid.getWidth() + "x" + grid.getHeight()); for (String name : new String[]{"held", "paging", "blocked", "pressed"}) { field = controller.getClass().getDeclaredField(name); field.setAccessible(true); result.append(" ").append(name).append("=").append(field.get(controller)); } return result.toString(); } catch (Exception error) { return error.toString(); }
    }
    private void frame(String name) throws Exception {
        SystemClock.sleep(260); test.waitForIdleSync();
        Bitmap image = test.getUiAutomation().takeScreenshot(); File directory = new File(activity.getFilesDir(), "ui-smoke"); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, output); } finally { image.recycle(); }
    }
    private void compactCoverRows() {
        main(() -> {
            android.content.res.Configuration configuration = new android.content.res.Configuration(activity.getResources().getConfiguration());
            configuration.fontScale = 1; configuration.densityDpi = 340;
            android.content.Context context = activity.createConfigurationContext(configuration);
            AppHubView compact = new AppHubView(context, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
            int width = Ui.dp(context, 352), height = Ui.dp(context, 268);
            for (int i = 0; i < 2; i++) { compact.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); compact.layout(0, 0, width, height); }
            AppWorkspaceView workspace = compact.findViewWithTag("hub-grid");
            require(workspace.getNumColumns() == 5 && workspace.capacity() == 15, "reference cover viewport fits five columns and three complete rows");
            require(workspace.gridHeight() / 3 >= ((AppWorkspaceView.CellAdapter) workspace.getAdapter()).minimumHeight(workspace.getWidth() / 5), "three rows preserve full icon and measured label height");
            android.graphics.Rect dock = new android.graphics.Rect(); compact.findViewWithTag("hub-dock").getDrawingRect(dock); compact.offsetDescendantRectToMyCoords(compact.findViewWithTag("hub-dock"), dock);
            require(dock.bottom == height - Ui.dp(context, AppLauncherStyle.SURFACE_INSET), "Dock background preserves the reserved safe bottom gutter");
            compact.dispose();
        });
    }
    String runPageMemory(String phase) throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        prefs = new Prefs(activity);
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); require(apps.size() >= 3, "catalog provides page fixtures");
        try {
            if (!phase.isEmpty()) {
                int expected = phase.equals("reboot") ? 0 : 1;
                require(prefs.launcherPage() == expected, "page survives process restart and expires after emulator reboot"); mount(null);
                require(grid.page() == expected, "new process restores the page for the current system boot");
            } else {
                prefs.data.edit().clear().putString("hub_sort", "manual").commit();
                java.util.Map<String, Integer> slots = new java.util.LinkedHashMap<>(); for (int i = 0; i < 3; i++) slots.put(apps.get(i).id(), i * AppLauncherStyle.GRID_COLUMNS * AppLauncherStyle.GRID_ROWS);
                prefs.saveWorkspace(new AppWorkspaceLayout(slots), false); mount(null);
                require(grid.page() == 0 && grid.pageCount() >= 3, "first open starts on the first page");
                main(() -> grid.settlePage(1, false)); require(new Prefs(activity).launcherPage() == 1, "completed paging saves shared navigation");
                mount(null); require(grid.page() == 1, "closing and reopening restores the page without an explicit view snapshot");
                main(() -> { hub.setExpanded(false); hub.setExpanded(true); }); require(grid.page() == 1, "Dock collapse and expansion preserve the page");
                main(() -> ((EditText) hub.findViewWithTag("hub-search")).setText("__missing_page_fixture__"));
                require(grid.getCount() == 0 && prefs.launcherPage() == 1, "empty search does not overwrite workspace navigation");
                main(() -> ((EditText) hub.findViewWithTag("hub-search")).setText("")); require(grid.page() == 1, "clearing search restores the browsing page");
                prefs.data.edit().putString("hub_sort", "name").commit(); mount(null); require(grid.page() == Math.min(1, grid.pageCount() - 1), "automatic sorting restores a valid remembered page");
                prefs.data.edit().putString("hub_sort", "manual").commit(); prefs.launcherPage(9999); mount(null);
                require(grid.page() == grid.pageCount() - 1 && prefs.launcherPage() == grid.page(), "out-of-range page clamps to the current last page");
                int boot = android.provider.Settings.Global.getInt(activity.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1); require(boot >= 0, "system supplies a reboot identity");
                prefs.data.edit().putInt("launcher_page_boot", boot + 1).commit(); require(new Prefs(activity).launcherPage() == 0, "a changed boot identity invalidates the previous page");
                mount(null); require(grid.page() == 0, "first open after a reboot identity change starts on page one");
                main(() -> grid.settlePage(1, false)); prefs.data.edit().putInt("launcher_page", 1).commit();
            }
            return "PASS: launcher-page-memory" + (phase.isEmpty() ? "" : "-" + phase) + "; " + assertions + " assertions; disposable emulator, not Samsung hardware validation";
        } finally { main(() -> { if (hub != null) hub.dispose(); activity.finish(); }); }
    }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        require(expectedRotation < 0 || activity.getDisplay().getRotation() == expectedRotation, "workspace uses the requested physical display rotation");
        prefs = new Prefs(activity); prefs.data.edit().clear().putString("hub_sort", "manual").commit();
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); require(apps.size() >= 5, "installed catalog provides real application fixtures");
        try {
            compactCoverRows();
            mount(null); String first = apps.get(0).id(), second = apps.get(1).id();
            require(grid.getChildCount() > 0 && grid.getNumColumns() >= 3, "paged grid mounts native cells at cover width");
            require(grid.getChildCount() <= grid.capacity() * 3, "only three pages can be mounted"); frame("workspace-initial");
            int start = grid.layoutSnapshot().slot(first); touch(MotionEvent.ACTION_DOWN, x(start), y(start)); touch(MotionEvent.ACTION_UP, x(start), y(start)); require(launches == 1, "tap launches once");
            AppWorkspaceLayout before = prefs.workspace(); lift(first);
            touch(MotionEvent.ACTION_MOVE, grid.getWidth() - 2, y(start));
            long deadline = SystemClock.elapsedRealtime() + 1000; while (grid.page() == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(20); test.waitForIdleSync();
            require(grid.page() == 1, "holding at the right edge turns to the next page; page=" + grid.page() + " " + diagnostics());
            int destination = grid.capacity() + Math.min(2, grid.capacity() - 1); float insertionX = x(destination) + .4f * (grid.getWidth() / grid.getNumColumns()); touch(MotionEvent.ACTION_MOVE, insertionX, y(destination)); frame("workspace-drag-preview");
            require(before.equals(prefs.workspace()), "drag preview does not persist intermediate positions");
            touch(MotionEvent.ACTION_UP, insertionX, y(destination)); require(prefs.workspace().slot(first) == destination, "cross-page edge release commits the insertion preview cell without requesting a merge");
            require(launches == 1 && grid.page() == 1 && !grid.dragging(), "drop neither launches nor returns to page zero"); frame("workspace-after-drop");
            before = prefs.workspace(); lift(first); touch(MotionEvent.ACTION_MOVE, x(destination + 1), y(destination + 1)); touch(MotionEvent.ACTION_CANCEL, x(destination + 1), y(destination + 1));
            require(before.equals(prefs.workspace()) && !grid.dragging(), "cancel restores the committed layout");
            lift(first); int bindings = grid.bindingGeneration(); main(() -> grid.getAdapter().notifyDataSetChanged());
            require(grid.bindingGeneration() == bindings, "catalog binding is deferred during a held drag");
            touch(MotionEvent.ACTION_CANCEL, x(destination), y(destination)); require(grid.bindingGeneration() > bindings, "cancel consumes deferred catalog updates without another touch");
            lift(first); touch(MotionEvent.ACTION_MOVE, -15, y(destination)); touch(MotionEvent.ACTION_UP, -15, y(destination)); require(before.equals(prefs.workspace()), "outside drop cannot commit a slot");
            lift(first); main(hub::back); touch(MotionEvent.ACTION_MOVE, x(destination) + grid.getWidth() / 2f, y(destination)); touch(MotionEvent.ACTION_UP, x(destination), y(destination)); require(hub.expanded() && before.equals(prefs.workspace()), "Back cancels drag and consumes the old finger sequence before collapsing the launcher");
            main(() -> { EditText search = hub.findViewWithTag("hub-search"); search.setText("__missing_workspace_app__"); });
            require(grid.getCount() == 0 && !grid.editable(), "search is a separate read-only result projection");
            mount(hub.workspaceState()); require(((EditText) hub.findViewWithTag("hub-search")).getText().toString().equals("__missing_workspace_app__"), "window reconstruction restores the search query");
            main(() -> ((EditText) hub.findViewWithTag("hub-search")).setText("")); require(grid.page() == 1 && before.equals(prefs.workspace()), "clearing search restores manual page and cells");
            AppHubView.WorkspaceState state = hub.workspaceState(); mount(state); require(grid.page() == 1 && before.equals(prefs.workspace()), "reconstructed workspace restores its logical page anchor");
            lift(first); main(() -> { FrameLayout.LayoutParams parameters = (FrameLayout.LayoutParams) hub.getLayoutParams(); parameters.width = Ui.dp(activity, 210); hub.setLayoutParams(parameters); });
            touch(MotionEvent.ACTION_MOVE, grid.getWidth() - 2, y(destination)); touch(MotionEvent.ACTION_UP, grid.getWidth() - 2, y(destination));
            require(!grid.dragging() && before.equals(prefs.workspace()), "real size changes cancel the old sequence without rewriting saved cells"); mount(state);
            lift(first); main(() -> { hub.setExpanded(false); }); touch(MotionEvent.ACTION_UP, x(destination), y(destination)); require(before.equals(prefs.workspace()) && !grid.dragging(), "collapse cancels drag without delayed commit"); main(() -> hub.setExpanded(true));
            main(() -> grid.compact(true)); require(prefs.workspaceCompact() && prefs.workspace().equals(prefs.workspace().compact()), "automatic packing removes gaps but retains order");
            main(() -> grid.moveTo(second, 0)); require(prefs.workspace().slot(second) == 0 && prefs.workspace().size() == apps.size(), "accessible movement inserts without losing an app");
            main(() -> grid.compact(false)); JSONObject exported = ((MainActivity) activity).exportConfigurationData();
            require(exported.getInt("version") == 15 && exported.getJSONObject("layout").getInt("version") == 11, "current configuration envelope contains the workspace layout");
            AppWorkspaceLayout exportedLayout = prefs.workspace(); main(() -> prefs.saveWorkspace(new AppWorkspaceLayout(), false));
            main(() -> { try { ((MainActivity) activity).applyConfigurationData(exported); } catch (Exception error) { throw new AssertionError(error); } }); require(exportedLayout.equals(prefs.workspace()), "configuration round-trip preserves every placement");
            for (Object invalid : new Object[]{-1, 4096, 1.5, "1"}) {
                JSONObject bad = new JSONObject(exported.toString()); bad.getJSONObject("layout").getJSONArray("workspace").getJSONObject(0).put("slot", invalid);
                java.util.Map<String, ?> original = prefs.data.getAll(); boolean rejected = false;
                try { ((MainActivity) activity).applyConfigurationData(bad); } catch (IllegalArgumentException | org.json.JSONException expected) { rejected = true; }
                require(rejected && original.equals(prefs.data.getAll()), "invalid cell rejects the entire configuration atomically: " + invalid);
            }
            JSONObject duplicate = new JSONObject(exported.toString()); JSONArray entries = duplicate.getJSONObject("layout").getJSONArray("workspace"); entries.getJSONObject(1).put("slot", entries.getJSONObject(0).getInt("slot"));
            boolean rejected = false; try { ((MainActivity) activity).applyConfigurationData(duplicate); } catch (IllegalArgumentException expected) { rejected = true; } require(rejected, "duplicate occupied cells are rejected");
            JSONObject legacy = new JSONObject(exported.toString()); legacy.put("version", 10); legacy.getJSONObject("layout").put("version", 7).remove("workspace");
            ((MainActivity) activity).applyConfigurationData(legacy); require(prefs.workspace().size() == 0 && !prefs.workspaceCompact(), "legacy import initializes workspace without retaining unrelated positions");
            mount(null); int observers = CoverApp.catalog(activity).observerCount(); lift(apps.get(0).id()); main(() -> { hub.dispose(); activity.setContentView(new FrameLayout(activity)); });
            SystemClock.sleep(800); require(!grid.dragging() && CoverApp.catalog(activity).observerCount() < observers, "unmount releases drag timers and cache subscription");
            return "PASS: app-workspace; " + assertions + " assertions; rotation=" + activity.getDisplay().getRotation() + "; native emulator touch and persistence, not Samsung hardware validation";
        } finally { main(() -> { if (hub != null) hub.dispose(); activity.finish(); }); }
    }
}
