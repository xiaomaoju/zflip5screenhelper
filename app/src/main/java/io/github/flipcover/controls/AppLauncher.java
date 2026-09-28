package io.github.flipcover.controls;

import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.view.Display;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** One explicit-app launch policy for the floating launcher and native card. */
final class AppLauncher {
    private boolean busy;
    static boolean ready(Context context, Prefs prefs, int target) {
        Display selected = Displays.selected(context, prefs);
        return target > 0 && selected != null && selected.getDisplayId() == target && selected.getState() == Display.STATE_ON && !context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    void launch(Context context, Prefs prefs, String id, int target, BooleanSupplier ownerReady, Consumer<String> message, Consumer<Boolean> completed) {
        ComponentName component = ActionCatalog.component(id);
        if (component == null || !ready(context, prefs, target) || !ownerReady.getAsBoolean()) { message.accept("所选外屏不可用，请解锁后重试"); completed.accept(false); return; }
        if (busy) { message.accept("正在打开应用，请稍候"); completed.accept(false); return; }
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        Runnable start = () -> {
            if (!ready(context, prefs, target) || !ownerReady.getAsBoolean()) { message.accept("外屏状态已改变，请重新打开应用"); completed.accept(false); return; }
            try { context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()); completed.accept(true); }
            catch (RuntimeException error) { message.accept("无法在外屏打开：" + error.getClass().getSimpleName()); completed.accept(false); }
        };
        int rotation = prefs.appRotation(component.getPackageName());
        if (rotation < 0) { start.run(); return; }
        CoverService service = CoverService.instance; if (service != null) service.stopAutomaticRotation();
        busy = true; message.accept("正在应用 " + Prefs.rotationLabel(rotation));
        CoverApp.bridge(context).run(rotation == 4 ? "rotation_auto" : "rotation", target, rotation == 4 ? 0 : rotation, "", result -> {
            busy = false;
            if (!result.ok && ownerReady.getAsBoolean()) message.accept("方向未确认，将沿用当前方向打开：" + result.message);
            start.run();
        });
    }
}
