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
    private void until(BooleanSupplier value, String why) { long deadline = SystemClock.uptimeMillis() + 8000; while (!value.getAsBoolean() && SystemClock.uptimeMillis() < deadline) { test.waitForIdleSync(); SystemClock.sleep(80); } require(value.getAsBoolean(), why); }
    private TextView text(View view, String value) { if (view instanceof TextView label && value.contentEquals(label.getText())) return label; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = text(group.getChildAt(i), value); if (found != null) return found; } return null; }
    private View described(View view, String value) { if (view.getContentDescription() != null && view.getContentDescription().toString().startsWith(value)) return view; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { View found = described(group.getChildAt(i), value); if (found != null) return found; } return null; }
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
            AppWorkspaceLayout layout = AppWorkspaceLayout.sequential(all).create(List.of(all.get(0), all.get(1)), "共享文件夹", 0);
            prefs.saveWorkspace(layout, false); prefs.saveHubPins(List.of(all.get(2))); prefs.workspaceAlias(all.get(0), "共享别名");
            activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            main(() -> {
                host = new AppWidgetHost(context, 0x4c41554e); host.deleteHost(); Bundle options = new Bundle();
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 310); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 310);
                options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 280); options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 280);
                for (int i = 0; i < 2; i++) { ids[i] = host.allocateAppWidgetId(); require(manager.bindAppWidgetIdIfAllowed(ids[i], new ComponentName(context, LauncherWidgetProvider.class), options), "standalone launcher provider binds"); }
                card = host.createView(activity, ids[0], manager.getAppWidgetInfo(ids[0])); second = host.createView(activity, ids[1], manager.getAppWidgetInfo(ids[1]));
                FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(Ui.BACKGROUND); root.addView(card, new FrameLayout.LayoutParams(Ui.dp(activity, 310), Ui.dp(activity, 280))); activity.setContentView(root); host.startListening(); bridge.refresh();
            });
            until(() -> text(card, "共享文件夹") != null, "card displays the floating workspace folder");
            require(!java.util.Arrays.stream(CoverApp.widgets(context).cards()).anyMatch(id -> id == ids[0]), "launcher does not consume a combination slot");
            Map<String, ?> before = prefs.data.getAll();
            main(() -> card.findViewById(R.id.launcher_edit).performClick());
            until(() -> card.findViewById(R.id.launcher_hint).getVisibility() == View.VISIBLE, "edit click shows in-card prompt");
            require(text(card, AppLauncherModel.EDIT_HINT) != null, "requested edit guidance shown verbatim");
            require(card.findViewById(R.id.launcher_panel).getVisibility() == View.INVISIBLE, "prompt blocks underlying launcher controls");
            require(before.equals(prefs.data.getAll()), "edit prompt never changes shared content"); frame("edit-hint");
            main(() -> card.findViewById(R.id.launcher_hint_close).performClick());
            until(() -> card.findViewById(R.id.launcher_panel).getVisibility() == View.VISIBLE, "dismiss returns to same native card");
            frame("apps");
            main(() -> described(card, "共享文件夹，文件夹").performClick());
            until(() -> text(card, "共享别名") != null, "folder opens inside the card with shared alias");
            require(text(second, "共享文件夹") != null && text(second, "共享别名") == null, "separate card instances keep independent navigation");
            main(() -> prefs.workspaceAlias(all.get(0), "修改后别名"));
            until(() -> text(card, "修改后别名") != null, "shared alias edit updates open card folder");
            main(() -> {
                AppHubView floating = new AppHubView(activity, prefs, new AppHubView.Listener() { public void action(String id) { } public void editFavorites() { } public void editPinned() { } public void expand(boolean expanded) { } public void close() { } });
                require(floating.label(all.get(0)).equals("修改后别名"), "floating renderer reads the same alias"); floating.dispose();
            }); frame("folder");
            main(() -> card.findViewById(R.id.launcher_back).performClick());
            until(() -> text(card, "共享文件夹") != null, "back returns to workspace");
            String folder = prefs.workspace().parent(all.get(0));
            main(() -> prefs.saveWorkspace(prefs.workspace().rename(folder, "同步改名"), false));
            until(() -> text(card, "同步改名") != null, "folder rename from shared model refreshes card");
            before = prefs.data.getAll();
            main(() -> bridge.action(ids[0], 0, "edit", "")); SystemClock.sleep(100);
            require(card.findViewById(R.id.launcher_hint).getVisibility() == View.GONE, "wrong-display stale action is rejected"); require(before.equals(prefs.data.getAll()), "navigation never writes launcher configuration");
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
