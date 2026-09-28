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
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); target = getIntent().getIntExtra("display", -1); widget = getIntent().getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1); item = getIntent().getStringExtra("item");
        if (saved != null || getDisplay() == null || getDisplay().getDisplayId() != target || !CoverApp.launcherWidgets(this).owns(widget)) { finish(); return; }
        setShowWhenLocked(true); // The system must dismiss its keyguard before any app launch.
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
        CoverApp.launcher(this).launch(this, new Prefs(this), item, target,
                () -> !isFinishing() && !isDestroyed() && getDisplay() != null && getDisplay().getDisplayId() == target && CoverApp.launcherWidgets(this).owns(widget), this::toast,
                accepted -> { if (accepted && CoverService.instance != null) CoverService.instance.launcherAccepted(); finish(); });
    }
    private void toast(String text) { if (!isFinishing()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
