package io.github.flipcover.controls;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** User-click PendingIntent entry, with the same launch policy as the overlay. */
public final class LauncherWidgetActivity extends Activity {
    private boolean requested;
    private int target, widget;
    private String item;
    private boolean resumed, observing, launching, application;
    private long request;
    private AppLaunchDiagnostics diagnostics;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable pendingLaunch = this::launch;
    private final android.content.BroadcastReceiver screenOff = new android.content.BroadcastReceiver() { public void onReceive(android.content.Context context, android.content.Intent intent) { event("screen_off"); finish(); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); target = getIntent().getIntExtra("display", -1); widget = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1); item = getIntent().getStringExtra("item");
        diagnostics = CoverApp.launcher(this).diagnostics; request = diagnostics.begin("native_card"); event("create restored=" + (saved != null));
        application = "launch".equals(getIntent().getStringExtra("operation")) || item != null && item.startsWith("app:") && "side".equals(getIntent().getStringExtra("operation"));
        if (saved != null) { reject("restored_entry"); return; }
        if (!CoverApp.launcherWidgets(this).owns(widget)) { reject("widget_removed"); return; }
        registerReceiver(screenOff, new android.content.IntentFilter(android.content.Intent.ACTION_SCREEN_OFF)); observing = true;
        // A widget click already authorizes the action. The running floating service
        // owns the external launch; this transport Activity's display/focus is irrelevant.
        if (application) { requested = true; launch(); }
    }
    @Override protected void onResume() { super.onResume(); resumed = true; event("resume"); requestLaunch(); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused); event("focus=" + focused);
        requestLaunch();
    }
    private void requestLaunch() {
        if (!resumed || !hasWindowFocus() || requested || isFinishing() || isDestroyed()) return;
        requested = true; main.post(pendingLaunch);
    }
    private boolean ownerReady() {
        return !isFinishing() && !isDestroyed() && CoverApp.launcherWidgets(this).owns(widget);
    }
    private void event(String stage) {
        if (diagnostics == null) return;
        diagnostics.event(request, stage + " activityDisplay=" + (getDisplay() == null ? -1 : getDisplay().getDisplayId()) + " target=" + target + " resumed=" + resumed + " finishing=" + isFinishing());
    }
    private void reject(String reason) {
        diagnostics.event(request, "rejected reason=" + reason);
        toast(reason.equals("widget_removed") ? "这张应用卡片已失效" : AppLauncher.failureMessage(reason)); finish();
    }
    private void launch() {
        if (isFinishing() || isDestroyed()) return;
        launching = true; Prefs prefs = new Prefs(this);
        if (!ownerReady()) { reject("widget_removed"); return; }
        if ("side".equals(getIntent().getStringExtra("operation"))) {
            if (!ActionCatalog.valid(item) || !prefs.actions("favorites").contains(item)) { diagnostics.event(request, "rejected reason=sidebar_changed"); toast("侧栏项目已改变，请重新选择"); finish(); return; }
        }
        CoverService service = CoverService.instance;
        if (application) {
            if (service != null) {
                diagnostics.event(request, "dispatch via=floating_service");
                service.launchApp(item, request, this::ownerReady, accepted -> finish());
            } else {
                diagnostics.event(request, "dispatch via=standalone");
                android.view.Display selected = Displays.selected(getApplicationContext(), prefs); target = selected == null ? -1 : selected.getDisplayId();
                CoverApp.launcher(this).launch(this, prefs, item, target, request, this::ownerReady, this::toast, accepted -> finish());
            }
            return;
        }
        AppLauncher.Check check = AppLauncher.check(this, prefs, target);
        diagnostics.event(request, "entry_check " + check.details() + " deviceLocked=" + getSystemService(android.app.KeyguardManager.class).isDeviceLocked());
        if (!check.ready()) { reject(check.reason()); return; }
        if ("side".equals(getIntent().getStringExtra("operation")) && !item.startsWith("app:")) { diagnostics.event(request, "sidebar delegated"); CoverApp.launcherWidgets(this).action(widget, target, "side", item); finish(); return; }
        if ("task".equals(getIntent().getStringExtra("operation"))) {
            try {
                RecentTasks.Task task = AppRecentTasks.task(new org.json.JSONObject(item), target);
                diagnostics.event(request, "task requested");
                AppRecentTasks.open(this, prefs, task, this::ownerReady, result -> {
                    diagnostics.event(request, "task completed ok=" + result.ok);
                    if (ownerReady() && AppLauncher.ready(this, prefs, target)) {
                        String state = ""; if (result.ok) try { state = new org.json.JSONObject(result.output).optString("state"); } catch (Exception ignored) { }
                        if (state.equals("opened")) { if (CoverService.instance != null) CoverService.instance.launcherAccepted(); }
                        else toast(state.equals("gone") ? "原窗口已结束，请刷新最近应用" : result.ok ? "未确认原窗口已恢复，请刷新后重试" : result.message);
                    }
                    finish();
                });
            } catch (Exception error) { diagnostics.event(request, "task exception=" + error.getClass().getSimpleName()); toast("任务数据已改变，请刷新最近应用"); finish(); }
            return;
        }
        diagnostics.event(request, "dispatch via=standalone");
        CoverApp.launcher(this).launch(this, prefs, item, target, request, this::ownerReady, this::toast,
                accepted -> { if (accepted && CoverService.instance != null) CoverService.instance.launcherAccepted(); finish(); });
    }
    @Override public void finish() { main.removeCallbacks(pendingLaunch); super.finish(); }
    @Override protected void onPause() { resumed = false; event("pause"); if (requested && application) finish(); super.onPause(); }
    @Override protected void onStop() { super.onStop(); event("stop"); if (launching) finish(); }
    @Override protected void onDestroy() { main.removeCallbacks(pendingLaunch); if (observing) unregisterReceiver(screenOff); event("destroy"); super.onDestroy(); }
    private void toast(String text) { if (!isFinishing()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
