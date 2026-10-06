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
    final AppLaunchDiagnostics diagnostics = new AppLaunchDiagnostics();
    record Check(int target, int selected, int state, boolean keyguard) {
        String launchReason() {
            if (target <= 0) return "invalid_target";
            if (selected < 0) return "display_missing";
            if (selected != target) return "display_changed";
            return "ready";
        }
        boolean launchable() { return launchReason().equals("ready"); }
        String reason() {
            if (!launchable()) return launchReason();
            if (state != Display.STATE_ON) return "display_not_on";
            return keyguard ? "keyguard_showing" : "ready";
        }
        boolean ready() { return reason().equals("ready"); }
        String details() { return "target=" + target + " selected=" + selected + " state=" + state + " keyguard=" + keyguard + " reason=" + reason(); }
    }
    static Intent applicationIntent(ComponentName component) { return Intent.makeMainActivity(component).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED); }
    static Check check(Context context, Prefs prefs, int target) {
        Display selected = Displays.selected(context, prefs);
        return new Check(target, selected == null ? -1 : selected.getDisplayId(), selected == null ? Display.STATE_UNKNOWN : selected.getState(), context.getSystemService(KeyguardManager.class).isKeyguardLocked());
    }
    static boolean ready(Context context, Prefs prefs, int target) { return check(context, prefs, target).ready(); }
    static String failureMessage(String reason) {
        return switch (reason) {
            case "invalid_component" -> "应用入口已失效，请刷新应用列表";
            case "invalid_target", "display_missing" -> "未找到可用外屏";
            case "display_changed" -> "外屏设置已改变，请重新打开应用中心";
            case "display_not_on" -> "外屏已关闭";
            case "keyguard_showing" -> "系统锁屏状态暂未解除";
            default -> "启动入口已失效，请重新打开启动器";
        };
    }
    void launch(Context context, Prefs prefs, String id, int target, BooleanSupplier ownerReady, Consumer<String> message, Consumer<Boolean> completed) {
        long request = diagnostics.begin(context instanceof CoverService ? "floating" : "direct");
        launch(context, prefs, id, target, request, ownerReady, message, completed);
    }
    void launch(Context context, Prefs prefs, String id, int target, long request, BooleanSupplier ownerReady, Consumer<String> message, Consumer<Boolean> completed) {
        ComponentName component = id != null && id.startsWith("app:") ? ActionCatalog.component(id) : null;
        Check initial = check(context, prefs, target); boolean owner = ownerReady.getAsBoolean();
        String reason = component == null ? "invalid_component" : !initial.launchable() ? initial.launchReason() : !owner ? "owner_inactive" : "ready";
        diagnostics.event(request, "preflight " + initial.details() + " deviceLocked=" + context.getSystemService(KeyguardManager.class).isDeviceLocked() + " owner=" + owner + " result=" + reason);
        if (!reason.equals("ready")) { if (!reason.equals("owner_inactive")) message.accept(failureMessage(reason)); completed.accept(false); return; }
        if (busy) { diagnostics.event(request, "rejected busy"); message.accept("正在打开应用，请稍候"); completed.accept(false); return; }
        Intent intent = applicationIntent(component);
        Runnable start = () -> {
            Check current = check(context, prefs, target); boolean active = ownerReady.getAsBoolean();
            diagnostics.event(request, "start_check " + current.details() + " owner=" + active);
            // Ordinary explicit app starts use Android's lockscreen policy. Keyguard
            // visibility and display power are diagnostic observations, not extra vetoes.
            if (!current.launchable() || !active) { diagnostics.event(request, "cancelled reason=" + (!current.launchable() ? current.launchReason() : "owner_inactive")); completed.accept(false); return; }
            try { context.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(target).toBundle()); diagnostics.event(request, "start_activity accepted"); completed.accept(true); }
            catch (RuntimeException error) {
                diagnostics.event(request, "start_activity exception=" + error.getClass().getSimpleName());
                message.accept(error instanceof android.content.ActivityNotFoundException ? "应用已卸载或暂不可用" : error instanceof SecurityException ? "系统未允许在外屏打开此应用" : "暂时无法打开此应用"); completed.accept(false);
            }
        };
        int rotation = prefs.appRotation(component.getPackageName());
        if (rotation < 0) { start.run(); return; }
        CoverService service = CoverService.instance; if (service != null) service.stopAutomaticRotation();
        diagnostics.event(request, "rotation requested=" + rotation);
        busy = true; message.accept("正在应用 " + Prefs.rotationLabel(rotation));
        CoverApp.bridge(context).run(rotation == 4 ? "rotation_auto" : "rotation", target, rotation == 4 ? 0 : rotation, "", result -> {
            busy = false;
            diagnostics.event(request, "rotation completed ok=" + result.ok);
            if (!result.ok && ownerReady.getAsBoolean()) message.accept("方向未确认，将沿用当前方向打开：" + result.message);
            start.run();
        });
    }
}
