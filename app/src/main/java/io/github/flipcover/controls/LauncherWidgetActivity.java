package io.github.flipcover.controls;

import android.app.Activity;
import android.app.KeyguardManager;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.widget.Toast;

/** User-click PendingIntent entry, with the same launch policy as the overlay. */
public final class LauncherWidgetActivity extends Activity {
    private boolean requested;
    private int target, widget;
    private String item;
    private boolean launching, observing;
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() { public void onReceive(android.content.Context context, android.content.Intent intent) { finish(); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); target = getIntent().getIntExtra("display", -1); widget = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1); item = getIntent().getStringExtra("item");
        if (saved != null || getDisplay() == null || getDisplay().getDisplayId() != target || !CoverApp.launcherWidgets(this).owns(widget)) { finish(); return; }
        setShowWhenLocked(true); // The system must dismiss its keyguard before any app launch.
        registerReceiver(screenOff, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)); observing = true;
    }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); if (!focused || requested || isFinishing()) return; requested = true;
        KeyguardManager guard = getSystemService(KeyguardManager.class);
        if (guard.isKeyguardLocked()) guard.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
            @Override public void onDismissSucceeded() { launch(); }
            @Override public void onDismissCancelled() { finish(); }
            @Override public void onDismissError() { toast("请解锁后重新点击应用"); finish(); }
        }); else launch();
    }
    private void launch() {
        if (isFinishing() || isDestroyed()) return;
        launching = true;
        if ("side".equals(getIntent().getStringExtra("operation"))) {
            Prefs prefs = new Prefs(this);
            if (!ActionCatalog.valid(item) || !prefs.actions("favorites").contains(item)) { toast("侧栏项目已改变，请重新选择"); finish(); return; }
            if (!AppLauncher.ready(this, prefs, target)) { finish(); return; }
            if (!item.startsWith("app:")) { CoverApp.launcherWidgets(this).action(widget, target, "side", item); finish(); return; }
        }
        if ("task".equals(getIntent().getStringExtra("operation"))) {
            try {
                RecentTasks.Task task = AppRecentTasks.task(new org.json.JSONObject(item), target);
                AppRecentTasks.open(this, new Prefs(this), task, () -> !isFinishing() && !isDestroyed() && CoverApp.launcherWidgets(this).owns(widget), result -> {
                    if (!isFinishing() && !isDestroyed() && AppLauncher.ready(this, new Prefs(this), target) && CoverApp.launcherWidgets(this).owns(widget)) {
                        String state = ""; if (result.ok) try { state = new org.json.JSONObject(result.output).optString("state"); } catch (Exception ignored) { }
                        if (state.equals("opened")) { if (CoverService.instance != null) CoverService.instance.launcherAccepted(); }
                        else toast(state.equals("gone") ? "原窗口已结束，请刷新最近应用" : result.ok ? "未确认原窗口已恢复，请刷新后重试" : result.message);
                    }
                    finish();
                });
            } catch (Exception error) { toast("任务数据已改变，请刷新最近应用"); finish(); }
            return;
        }
        CoverApp.launcher(this).launch(this, new Prefs(this), item, target,
                () -> !isFinishing() && !isDestroyed() && getDisplay() != null && getDisplay().getDisplayId() == target && CoverApp.launcherWidgets(this).owns(widget), this::toast,
                accepted -> { if (accepted && CoverService.instance != null) CoverService.instance.launcherAccepted(); finish(); });
    }
    @Override protected void onStop() { super.onStop(); if (launching) finish(); }
    @Override protected void onDestroy() { if (observing) unregisterReceiver(screenOff); super.onDestroy(); }
    private void toast(String text) { if (!isFinishing()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
