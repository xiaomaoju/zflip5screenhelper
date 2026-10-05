package io.github.flipcover.controls;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

/** Settings-only update controls; no overlay, background polling or auto-install. */
final class UpdateSettings implements AutoCloseable {
    final LinearLayout view;
    private final Activity activity;
    private final SettingsUi.ValueRow status;
    private final TextView details;
    private final ProgressBar progress;
    private enum Action { CHECK, DOWNLOAD, INSTALL, CANCEL, BACK }
    private final Button primary, secondary;
    private final Runnable back;
    private Action primaryAction = Action.CHECK, secondaryAction = Action.BACK;
    private AppUpdater updater;
    private UpdateCatalog catalog;
    private File apk;
    private boolean busy, preparingInstall, handedToInstaller;
    UpdateSettings(Activity activity, Runnable back) {
        this.activity = activity; this.back = back; view = SettingsUi.group(activity); view.setTag("about-update");
        status = SettingsUi.valueRow(activity, "在线更新", "手动检查更新，确认后下载和安装", null); status.setTag("update-status"); status.value.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); view.addView(status);
        progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setVisibility(View.INVISIBLE); progress.setTag("update-progress"); progress.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams track = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 4)); track.topMargin = Ui.dp(activity, 8); ((LinearLayout) status.value.getParent()).addView(progress, track);
        details = SettingsUi.text(activity, "仅在你操作时连接更新服务器，安装由系统确认。", 14, SettingsUi.MUTED); details.setPadding(Ui.dp(activity, 14), Ui.dp(activity, 8), Ui.dp(activity, 14), Ui.dp(activity, 8)); details.setTag("update-details"); view.addView(details);
        LinearLayout actions = SettingsUi.row(activity); actions.setTag("update-actions"); actions.setPadding(Ui.dp(activity, 8), 0, Ui.dp(activity, 8), Ui.dp(activity, 4)); view.addView(actions);
        primary = action(actions, "update-primary", () -> perform(primaryAction));
        secondary = action(actions, "update-secondary", () -> perform(secondaryAction));
        try { updater = new AppUpdater(activity, AppConfig.load(activity).update); }
        catch (Exception error) { status.value("更新配置不可用：" + error.getMessage()); }
        refresh();
        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { close(); }
        });
    }
    private Button action(LinearLayout actions, String tag, Runnable action) {
        Button button = SettingsUi.button(activity, "", action); button.setTag(tag); button.setBackground(Ui.ripple(activity, 0, 12)); actions.addView(button, new LinearLayout.LayoutParams(0, -1, 1)); return button;
    }
    private void perform(Action action) { switch (action) { case CHECK -> check(); case DOWNLOAD -> download(); case INSTALL -> install(); case CANCEL -> pause(); case BACK -> back.run(); } }
    void refresh() {
        boolean available = updater != null && !handedToInstaller && catalog != null && catalog.versionCode() > BuildConfig.VERSION_CODE && catalog.minSdk() <= Build.VERSION.SDK_INT;
        int primaryLabel;
        if (busy) primaryLabel = preparingInstall ? R.string.update_verifying : catalog == null ? R.string.update_checking : R.string.update_downloading;
        else if (handedToInstaller) primaryLabel = R.string.update_installer_opened;
        else if (available && apk != null) { primaryAction = Action.INSTALL; primaryLabel = activity.getPackageManager().canRequestPackageInstalls() ? R.string.update_install : R.string.update_allow_install; }
        else if (available) { primaryAction = Action.DOWNLOAD; primaryLabel = R.string.update_download; }
        else { primaryAction = Action.CHECK; primaryLabel = R.string.update_check; }
        primary.setText(primaryLabel); primary.setEnabled(updater != null && !busy && !handedToInstaller);
        secondaryAction = busy ? Action.CANCEL : available ? Action.CHECK : Action.BACK;
        secondary.setText(busy ? R.string.update_cancel : available ? R.string.update_recheck : R.string.update_back);
        secondary.setTextColor(busy || available ? SettingsUi.ACCENT : SettingsUi.MUTED);
    }
    void check() {
        if (updater == null || busy || handedToInstaller) return;
        busy = true; preparingInstall = false; catalog = null; apk = null; status.value("正在检查更新…"); details.setText("正在连接更新服务器"); progress.setVisibility(View.INVISIBLE); refresh(); updater.check(listener);
    }
    private void download() {
        if (updater == null || busy || handedToInstaller || catalog == null) return;
        busy = true; preparingInstall = false; apk = null; status.value("正在下载更新…"); progress.setProgress(0); progress.setVisibility(View.VISIBLE); refresh(); updater.download(catalog, listener);
    }
    private void install() {
        if (updater == null || busy || handedToInstaller || apk == null || catalog == null) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            try {
                status.value("请在系统设置允许安装，返回后再次点击“安装更新”");
                launch(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + activity.getPackageName())));
            } catch (RuntimeException error) { status.value("无法打开安装授权页面，请在系统设置中操作"); }
            return;
        }
        busy = preparingInstall = true; status.value("正在核验安装包…"); refresh(); updater.prepareInstall(apk, catalog, listener);
    }
    private void launch(Intent intent) {
        if (activity.getDisplay() == null) throw new IllegalStateException("当前显示器不可用");
        activity.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(activity.getDisplay().getDisplayId()).toBundle());
    }
    private final AppUpdater.Listener listener = new AppUpdater.Listener() {
        @Override public void catalog(UpdateCatalog value) {
            catalog = value; busy = false;
            String size = android.text.format.Formatter.formatShortFileSize(activity, value.apkSize());
            details.setText("版本 " + value.versionName() + "（构建 " + value.versionCode() + "） · " + size + "\n\n" + value.changelog());
            if (value.versionCode() <= BuildConfig.VERSION_CODE) status.value("当前已是相同或更高版本");
            else if (value.minSdk() > Build.VERSION.SDK_INT) status.value("发现新版，但需要 Android API " + value.minSdk() + " 或更高版本");
            else status.value("发现新版 " + value.versionName());
            refresh();
        }
        @Override public void progress(long downloaded, long total) {
            int percent = (int) (downloaded * 100 / total); progress.setProgress(percent);
            status.value("下载中 " + percent + "% · " + android.text.format.Formatter.formatShortFileSize(activity, downloaded) + " / " + android.text.format.Formatter.formatShortFileSize(activity, total));
        }
        @Override public void verifying() { status.value("下载完成，正在校验包名、版本和签名…"); primary.setText(R.string.update_verifying); }
        @Override public void ready(File file) {
            apk = file; busy = false; progress.setVisibility(View.INVISIBLE);
            if (!preparingInstall) { status.value("安装包已校验，点击安装更新"); refresh(); return; }
            preparingInstall = false;
            try {
                Uri uri = androidx.core.content.FileProvider.getUriForFile(activity, activity.getPackageName() + ".updates", file);
                Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("应用更新", uri)); launch(intent);
                handedToInstaller = true; updater.installerStarted(); status.value("已交给系统安装；若取消安装，请重新进入本页检查");
            } catch (RuntimeException error) { status.value("无法打开系统安装器，请检查安装权限后重试"); Toast.makeText(activity, "无法打开系统安装器", Toast.LENGTH_LONG).show(); }
            refresh();
        }
        @Override public void failed(String message) { busy = preparingInstall = false; apk = null; progress.setVisibility(View.INVISIBLE); status.value(message); refresh(); }
    };
    void pause() { if (busy && updater != null) { updater.cancel(); busy = preparingInstall = false; progress.setVisibility(View.INVISIBLE); status.value("已取消，可重新检查或下载"); refresh(); } }
    @Override public void close() { if (updater != null) updater.close(); updater = null; busy = false; }
}
