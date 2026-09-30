package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Bitmap;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Disposable emulator only: real components and a captured activity-start boundary. */
final class AppDockChecks {
    private final Instrumentation instrumentation;
    private Activity activity;
    private AppHubView hub;
    private Prefs prefs;
    private int assertions, refreshes, launches, closes, opened;
    private List<RecentTasks.Task> clearing = List.of();
    AppDockChecks(Instrumentation instrumentation) { this.instrumentation = instrumentation; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void main(Runnable action) { Throwable[] error = {null}; instrumentation.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { error[0] = failure; } }); if (error[0] != null) throw new AssertionError(error[0]); instrumentation.waitForIdleSync(); }
    private <T extends View> T find(String tag) { return hub.findViewWithTag(tag); }
    private List<View> recentItems() { List<View> result = new ArrayList<>(); ViewGroup row = find("hub-dock"); for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).getTag(R.id.dock_recent_task) != null) result.add(row.getChildAt(i)); return result; }
    String run() throws Exception {
        activity = instrumentation.startActivitySync(new Intent(instrumentation.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        prefs = new Prefs(activity); prefs.data.edit().clear().putString("pinned_action", "home").putBoolean("hub_default_migrated", true).putString("dock", "[\"back\",\"rotation\"]").commit();
        prefs = new Prefs(activity);
        require(prefs.actions("dock").equals(List.of("app_dock", "back", "rotation")) && prefs.pinnedAction().equals("home"), "upgrade inserts Dock while retaining saved order and fixed key");
        prefs.saveActions("dock", List.of("back", "rotation")); prefs = new Prefs(activity); require(!prefs.actions("dock").contains("app_dock"), "user removal is not undone on next preferences read");
        prefs.saveActions("dock", List.of("app_dock", "back")); org.json.JSONObject layout = prefs.layoutSnapshot(); prefs.saveActions("dock", List.of("back")); prefs.prepareLayout(layout, prefs.data.edit()).commit(); require(prefs.actions("dock").contains("app_dock"), "Dock action survives layout backup/restore");
        List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking(); require(apps.size() >= 8, "installed application fixtures");
        List<RecentTasks.Task> tasks = new ArrayList<>();
        for (int i = 3; i < 8; i++) tasks.add(new RecentTasks.Task(100 + i, 2, 0, apps.get(i).id().substring(4), apps.get(i).packageName(), i == 3));
        tasks.add(new RecentTasks.Task(200, 2, 0, apps.get(4).id().substring(4), apps.get(4).packageName(), false));
        prefs.saveHubPins(List.of(apps.get(0).id(), apps.get(1).id(), apps.get(2).id(), apps.get(3).id())); CoverApp.taskLocks(activity).clear(); CoverApp.taskLocks(activity).toggle(tasks.get(1));
        main(() -> {
            hub = new AppHubView(activity, prefs, new AppHubView.Listener() {
                public void action(String id) { launches++; } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { closes++; }
                public void refreshRecents() { refreshes++; } public void openTask(RecentTasks.Task task) { opened++; }
                public void clearRecents(List<RecentTasks.Task> tasks) { clearing = tasks; hub.recentBusy(true); }
            });
            hub.setExpanded(false); hub.recentResult(tasks, null);
            activity.getWindow().getInsetsController().hide(android.view.WindowInsets.Type.systemBars());
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(0xFF172234);
            FrameLayout.LayoutParams bounds = new FrameLayout.LayoutParams(-1, -1); bounds.setMargins(Ui.dp(activity, 4), Ui.dp(activity, 28), Ui.dp(activity, 4), Ui.dp(activity, 32)); root.addView(hub, bounds); activity.setContentView(root);
        });
        main(() -> {
            require(!hub.expanded() && !find("hub-grid").isShown() && find("hub-dock").isShown(), "Dock opens without catalog or sidebar");
            require(find("dock-dismiss-backdrop").isShown() && !find("hub-search").hasFocus(), "temporary blank region dismisses and does not summon keyboard");
            require(refreshes > 0, "standalone Dock refreshes on mount");
            require(recentItems().size() >= 2 && recentItems().size() <= 4, "recent capacity adapts to cover density with a four-app limit");
            ViewGroup row = find("hub-dock"); require(row.getWidth() <= hub.getWidth(), "Dock fits available width");
            View appsButton = row.getChildAt(0), clearButton = find("hub-clear");
            require(appsButton.getWidth() == clearButton.getWidth() && appsButton.getWidth() == Ui.dp(activity, 24), "floating Dock side actions share the compact width");
            require(appsButton.getLeft() == row.getWidth() - clearButton.getRight(), "floating Dock action centers and outer margins are symmetric");
            require(find("hub-apps").getWidth() - find("hub-apps").getPaddingLeft() - find("hub-apps").getPaddingRight() == Ui.dp(activity, 24), "compact side action preserves the apps icon size");
            for (View item : recentItems()) require(item.getWidth() >= Ui.dp(activity, 28), "recent targets retain at least 28dp width");
            row.getChildAt(1).performClick(); require(launches == 1, "standalone fixed app routes to launch"); recentItems().get(0).performClick(); require(opened == 1 && launches == 1, "recent item restores exact task instead of launching app");
        });
        screenshot("standalone-dock");
        int[] before = new int[2]; View row = find("hub-dock"), pin = ((ViewGroup) row).getChildAt(1); row.getLocationOnScreen(before);
        main(() -> find("hub-apps").performClick()); SystemClock.sleep(200); instrumentation.waitForIdleSync();
        main(() -> {
            int[] after = new int[2]; row.getLocationOnScreen(after);
            require(hub.expanded() && find("hub-grid").isShown() && !find("dock-dismiss-backdrop").isShown(), "Dock opens full catalog in place");
            require(row == find("hub-dock") && pin == ((ViewGroup) row).getChildAt(1) && before[0] == after[0] && before[1] == after[1], "same Dock and app cells remain at identical screen coordinates");
            EditText search = find("hub-search"); search.setText(apps.get(0).label());
        });
        screenshot("dock-expanded");
        main(() -> find("hub-apps").performClick()); main(() -> find("hub-apps").performClick());
        main(() -> {
            require(((EditText) find("hub-search")).getText().toString().equals(apps.get(0).label()), "search survives collapse and re-expansion");
            AppWorkspaceView grid = find("hub-grid"); grid.getChildAt(0).performClick(); require(launches == 2, "Dock-expanded catalog routes selected app to same launcher");
            hub.setExpanded(false); hub.recentFailure("test disconnect");
            require(find("dock-warning").getVisibility() == View.VISIBLE && !find("hub-clear").isEnabled(), "compact Dock exposes disconnected state and disables clear");
            for (View item : recentItems()) require(!item.isEnabled(), "stale recent task cannot be activated");
            ((ViewGroup) row).getChildAt(1).performClick(); require(launches == 3, "fixed app remains usable when Shizuku disconnects");
            hub.recentResult(tasks, null); find("hub-clear").performClick();
            require(!clearing.isEmpty() && clearing.stream().noneMatch(task -> task.visible() || CoverApp.taskLocks(activity).contains(task)), "clear preserves visible and locked tasks");
            java.util.Set<String> shown = new java.util.HashSet<>(); for (View item : recentItems()) shown.add(((RecentTasks.Task) item.getTag(R.id.dock_recent_task)).packageName());
            require(clearing.stream().allMatch(task -> shown.contains(task.packageName())), "clear is limited to displayed recent applications");
            require(clearing.stream().anyMatch(task -> task.id() == 200), "unlocked sibling task remains independently clearable"); hub.recentBusy(false);
            find("dock-dismiss-backdrop").performClick(); require(closes == 1, "blank region closes temporary interface");
            hub.setExpanded(true); hub.back(); require(!hub.expanded() && closes == 1, "back collapses to Dock"); hub.back(); require(closes == 2, "second back closes Dock");
        });
        int count = refreshes; SystemClock.sleep(3200); instrumentation.waitForIdleSync(); require(refreshes > count, "standalone Dock keeps bounded refresh active");
        main(() -> { hub.dispose(); activity.setContentView(new FrameLayout(activity)); require(CoverApp.catalog(activity).observerCount() == 0, "closing releases catalog subscription"); });
        count = refreshes; SystemClock.sleep(3200); require(refreshes == count, "closed Dock no longer refreshes tasks");
        checkLaunchDismissal(apps.get(0).id());
        main(() -> { CoverApp.taskLocks(activity).clear(); activity.finish(); });
        return "PASS: standalone-dock; " + assertions + " assertions; component and captured launch boundary, not Samsung overlay/privileged task validation";
    }
    private void checkLaunchDismissal(String app) throws Exception {
        ImageReader image = ImageReader.newInstance(720, 748, android.graphics.PixelFormat.RGBA_8888, 2);
        VirtualDisplay virtual = activity.getSystemService(DisplayManager.class).createVirtualDisplay("Dock launch check", 720, 748, 340, image.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION);
        require(virtual != null && virtual.getDisplay().getDisplayId() > 0, "launch check has an explicit secondary display");
        Field hubField = CoverService.class.getDeclaredField("hub"); hubField.setAccessible(true);
        Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true);
        boolean[] reject = {false}; int[] starts = {0}; int[] target = {-1};
        ContextWrapper captured = new ContextWrapper(activity) {
            @Override public void startActivity(Intent intent, Bundle options) { starts[0]++; target[0] = options.getInt("android.activity.launchDisplayId", -1); if (reject[0]) throw new android.content.ActivityNotFoundException("test rejection"); }
        };
        try {
            prefs.data.edit().putInt("display", virtual.getDisplay().getDisplayId()).putBoolean("enabled", true).commit();
            for (boolean expanded : new boolean[]{false, true}) {
                CoverService[] owner = {null}; AppHubView[] launched = {null}, replacement = {null};
                main(() -> {
                    try {
                        owner[0] = new CoverService(); attach.invoke(owner[0], captured); owner[0].prefs = prefs; owner[0].screenContext = activity; owner[0].display = virtual.getDisplay();
                        launched[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { owner[0].launchApp(id); } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
                        launched[0].setExpanded(expanded); activity.setContentView(owner[0].attachHubContent(launched[0])); hubField.set(owner[0], launched[0]);
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                main(() -> {
                    try {
                        require(launched[0].isAttachedToWindow(), "launch regression starts with an attached visible surface");
                        reject[0] = true; owner[0].launchApp(app); require(hubField.get(owner[0]) == launched[0] && !launched[0].closing(), "rejected app start retains temporary UI in mode " + expanded);
                        reject[0] = false; owner[0].launchApp(app); finishSharedExit(owner[0], launched[0], hubField, "accepted app start in mode " + expanded);
                        require(target[0] == virtual.getDisplay().getDisplayId(), "launch never falls back to primary display");
                        replacement[0] = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean value) { } public void close() { } });
                        replacement[0].setExpanded(false); activity.setContentView(owner[0].attachHubContent(replacement[0])); hubField.set(owner[0], replacement[0]);
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
                main(() -> {
                    try {
                        CoverService previous = CoverService.instance; CoverService.instance = owner[0];
                        try {
                            RecentTasks.Task task = new RecentTasks.Task(1, virtual.getDisplay().getDisplayId(), 0, app.substring(4), ActionCatalog.component(app).getPackageName(), false);
                            replacement[0].recentBusy(true); replacement[0].dismiss(() -> { });
                            owner[0].finishHubTask(replacement[0], task, new ShizukuBridge.Result(false, "closing failure", ""));
                            require(!replacement[0].expanded() && !replacement[0].showingTasks() && !replacement[0].recentBusy(), "task failure during exit neither switches page nor leaves refresh busy"); replacement[0].reopen();
                            java.lang.reflect.Field animation = AppHubView.class.getDeclaredField("revealAnimation"); animation.setAccessible(true); android.animation.ValueAnimator reopening = (android.animation.ValueAnimator) animation.get(replacement[0]); if (reopening != null) reopening.end();
                            owner[0].finishHubTask(launched[0], task, new ShizukuBridge.Result(false, "stale failure", "")); require(hubField.get(owner[0]) == replacement[0], "old task failure cannot alter rebuilt UI");
                            owner[0].finishHubTask(launched[0], task, new ShizukuBridge.Result(true, "", "{\"state\":\"opened\"}")); finishSharedExit(owner[0], replacement[0], hubField, "confirmed task recovery");
                        } finally { CoverService.instance = previous; }
                    } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                });
            }
            require(starts[0] == 4, "both entry modes reached accepted and rejected activity boundaries");
        } finally { prefs.data.edit().remove("display").putBoolean("enabled", false).commit(); virtual.release(); image.close(); }
    }
    private void finishSharedExit(CoverService owner, AppHubView surface, Field hubField, String trigger) throws ReflectiveOperationException {
        Field field = AppHubView.class.getDeclaredField("revealAnimation"); field.setAccessible(true); android.animation.ValueAnimator animation = (android.animation.ValueAnimator) field.get(surface);
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            require(hubField.get(owner) == surface && surface.closing() && surface.isAttachedToWindow() && animation != null, trigger + " keeps the window for the shared exit");
            require(animation.getDuration() == 180, trigger + " uses the same exit duration as manual dismissal"); animation.pause(); animation.setCurrentPlayTime(90);
            require(surface.getTranslationY() > 0 && surface.getAlpha() > 0 && surface.getAlpha() < 1 && surface.isAttachedToWindow(), trigger + " shows a sliding intermediate frame"); animation.end();
        }
        require(hubField.get(owner) == null && !surface.isAttachedToWindow(), trigger + " removes content only at completion");
    }
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync(); SystemClock.sleep(400); Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); File directory = new File(instrumentation.getTargetContext().getFilesDir(), "ui-smoke"); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, output); } finally { image.recycle(); }
    }
}
