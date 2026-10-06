package io.github.flipcover.controls;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.SystemClock;
import android.view.Surface;
import java.util.concurrent.atomic.AtomicReference;

/** Real task reuse on a disposable secondary display, including a retained detail page. */
final class AppLaunchChecks {
    private final Instrumentation test;
    private int assertions;
    AppLaunchChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
    private Intent result(AtomicReference<Intent> received) {
        long deadline = SystemClock.uptimeMillis() + 6000;
        while (received.get() == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        require(received.get() != null, "fixture resumes on the requested display"); return received.get();
    }
    String run() {
        Context context = test.getTargetContext(); Prefs prefs = new Prefs(context);
        boolean hadDisplay = prefs.data.contains("display"); int previousDisplay = prefs.displayId();
        test.getUiAutomation().adoptShellPermissionIdentity("android.permission.ADD_TRUSTED_DISPLAY");
        SurfaceTexture texture = new SurfaceTexture(false); texture.setDefaultBufferSize(720, 748); Surface surface = new Surface(texture);
        VirtualDisplay display = context.getSystemService(DisplayManager.class).createVirtualDisplay("Application reuse checks", 720, 748, 320, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 10));
        AtomicReference<Intent> received = new AtomicReference<>();
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() { public void onReceive(Context c, Intent intent) { received.set(intent); } };
        context.registerReceiver(receiver, new android.content.IntentFilter("fixture.APP_REUSE_RESULT"), Context.RECEIVER_EXPORTED);
        Activity host = null;
        try {
            require(display != null && display.getDisplay().getDisplayId() > 0, "secondary display exists");
            int target = display.getDisplay().getDisplayId(); prefs.data.edit().putInt("display", target).commit();
            ComponentName component = new ComponentName(test.getContext(), AppLaunchFixtureActivity.class);
            Intent intent = AppLauncher.applicationIntent(component);
            require(intent.getComponent().equals(component) && intent.hasCategory(Intent.CATEGORY_LAUNCHER), "standard explicit launcher entry");
            require((intent.getFlags() & (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)) == (Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), "launcher requests task reuse from Activity and non-Activity contexts");
            require((intent.getFlags() & (Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)) == 0, "launch neither duplicates nor clears tasks");
            host = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle());
            AppLauncher launcher = new AppLauncher();
            test.runOnMainSync(() -> launcher.launch(context, prefs, "app:" + component.flattenToString(), target, () -> true, message -> { throw new AssertionError(message); }, accepted -> require(accepted, "launch accepted")));
            Intent root = result(received); require(root.getIntExtra("display", -1) == target && !root.getBooleanExtra("detail", false), "cold launch reaches the secondary root");
            int task = root.getIntExtra("task", -1); String instance = root.getStringExtra("instance");
            for (int i = 0; i < 3; i++) {
                test.runOnMainSync(() -> context.startActivity(AppLauncher.applicationIntent(new ComponentName(context, MainActivity.class)), ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()));
                SystemClock.sleep(250); received.set(null);
                test.runOnMainSync(() -> launcher.launch(context, prefs, "app:" + component.flattenToString(), target, () -> true, message -> { throw new AssertionError(message); }, accepted -> require(accepted, "repeat launch accepted")));
                Intent resumed = result(received); require(resumed.getIntExtra("task", -1) == task && instance.equals(resumed.getStringExtra("instance")), "repeat launch preserves task and activity instance");
            }
            received.set(null);
            test.runOnMainSync(() -> context.startActivity(new Intent().setComponent(new ComponentName(test.getContext(), AppLaunchFixtureActivity.Detail.class)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()));
            Intent detail = result(received); require(detail.getBooleanExtra("detail", false) && detail.getIntExtra("task", -1) == task, "detail page belongs to the original task");
            test.runOnMainSync(() -> context.startActivity(AppLauncher.applicationIntent(new ComponentName(context, MainActivity.class)), ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()));
            SystemClock.sleep(250); received.set(null);
            test.runOnMainSync(() -> launcher.launch(context, prefs, "app:" + component.flattenToString(), target, () -> true, message -> { throw new AssertionError(message); }, accepted -> require(accepted, "detail task launch accepted")));
            Intent resumed = result(received); require(resumed.getBooleanExtra("detail", false) && resumed.getIntExtra("task", -1) == task && detail.getStringExtra("instance").equals(resumed.getStringExtra("instance")), "launcher resumes the intact detail page");
            test.runOnMainSync(() -> launcher.launch(context, prefs, "app:" + component.flattenToString(), 0, () -> true, message -> { }, accepted -> require(!accepted, "primary display is rejected")));
            test.runOnMainSync(() -> {
                launcher.launch(context, prefs, null, target, () -> true, message -> require(message.contains("应用入口"), "missing click payload is reported as an invalid app instead of crashing or blaming unlock"), accepted -> require(!accepted, "missing component rejected"));
                launcher.launch(context, prefs, "app:" + component.flattenToString(), target, () -> false, message -> { throw new AssertionError("cancelled request should not show a late Toast"); }, accepted -> require(!accepted, "cancelled owner rejected"));
                String report = launcher.diagnostics.report();
                require(report.contains("result=invalid_target") && report.contains("result=invalid_component") && report.contains("result=owner_inactive"), "diagnostics distinguish failure causes");
                require(report.contains("start_activity accepted") && !report.contains(component.flattenToString()) && !report.contains(component.getPackageName()), "accepted system calls are logged without app identity");
            });
            Activity diagnosticPage = test.startActivitySync(new Intent(context, MainActivity.class).putExtra("section", "diagnostics").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle());
            try {
                test.runOnMainSync(() -> {
                    AppLaunchDiagnostics log = CoverApp.launcher(context).diagnostics;
                    long request = log.begin("diagnostic_check"); log.event(request, "after_page_open");
                    android.view.View copy = find(diagnosticPage.getWindow().getDecorView(), "复制检测结果");
                    require(copy != null, "existing copy diagnostics action is present");
                    while (!copy.isClickable() && copy.getParent() instanceof android.view.View parent) copy = parent;
                    require(copy.performClick(), "copy diagnostics action executes");
                    android.content.ClipData clip = diagnosticPage.getSystemService(android.content.ClipboardManager.class).getPrimaryClip();
                    String text = clip == null ? "" : clip.getItemAt(0).getText().toString();
                    require(text.contains("after_page_open") && text.contains(BuildConfig.VERSION_NAME) && text.contains("系统版本："), "copy includes fresh in-memory events and build context without a Shizuku probe");
                });
            } finally { test.runOnMainSync(diagnosticPage::finish); }
            return "app-launch-reuse: " + assertions + " assertions; cold launch, repeated task/instance reuse, detail-page preservation and primary-display rejection PASS";
        } finally {
            if (host != null) { Activity opened = host; test.runOnMainSync(opened::finish); }
            context.unregisterReceiver(receiver);
            if (hadDisplay) prefs.data.edit().putInt("display", previousDisplay).commit(); else prefs.data.edit().remove("display").commit();
            if (display != null) display.release(); surface.release(); texture.release(); test.getUiAutomation().dropShellPermissionIdentity();
        }
    }
    private android.view.View find(android.view.View view, String text) {
        if (view instanceof android.widget.TextView label && text.contentEquals(label.getText())) return view;
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { android.view.View found = find(group.getChildAt(i), text); if (found != null) return found; }
        return null;
    }
}
