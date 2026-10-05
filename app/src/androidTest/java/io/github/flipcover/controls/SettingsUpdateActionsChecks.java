package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import org.json.JSONObject;

/** Mounted settings layout and controlled updater callbacks; no automatic download/install. */
final class SettingsUpdateActionsChecks {
    private final Instrumentation test;
    private MainActivity activity;
    private int assertions;
    SettingsUpdateActionsChecks(Instrumentation test) { this.test = test; }
    private void require(boolean okay, String message) { assertions++; if (!okay) throw new AssertionError(message); }
    private interface Work { void run() throws Exception; }
    private void main(Work work) throws Exception { Throwable[] failure = {null}; test.runOnMainSync(() -> { try { work.run(); } catch (Throwable error) { failure[0] = error; } }); test.waitForIdleSync(); if (failure[0] != null) throw new AssertionError("UI check", failure[0]); }
    private View root() { return activity.findViewById(android.R.id.content); }
    private View tag(String name) { return root().findViewWithTag(name); }
    private String label(String name) { return ((TextView) tag(name)).getText().toString(); }
    private Object field(Object owner, String name) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
    private void set(Object owner, String name, Object value) throws Exception { Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value); }
    private void open(String page) { if (activity != null) test.runOnMainSync(activity::finish); activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", page)); test.waitForIdleSync(); SystemClock.sleep(180); }
    private void capture(String name) throws Exception { SystemClock.sleep(180); Bitmap image = test.getUiAutomation().takeScreenshot(); require(image != null, "capture " + name); File dir = new File(test.getTargetContext().getFilesDir(), "settings-update-raw"); if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("capture folder"); try (FileOutputStream stream = new FileOutputStream(new File(dir, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, stream); } finally { image.recycle(); } }
    private void buttons(String primary, String secondary) {
        require(((ViewGroup) tag("update-actions")).getChildCount() == 2, "two action slots"); require(label("update-primary").equals(primary), "primary " + primary); require(label("update-secondary").equals(secondary), "secondary " + secondary);
    }
    private void textFits(View view) { if (view.getVisibility() != View.VISIBLE) return; if (view instanceof TextView text && text.getLayout() != null && text.getWidth() > 0) { require(text.getLayout().getLineCount() == 0 || text.getLayout().getEllipsisCount(text.getLayout().getLineCount() - 1) == 0, "untruncated " + text.getText()); require(text.getLayout().getHeight() <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom() + 2, "text height " + text.getText()); } if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) textFits(group.getChildAt(i)); }
    String run() throws Exception {
        Prefs prefs = new Prefs(test.getTargetContext()); boolean hadScale = prefs.data.contains("settings_scale"); int scale = prefs.settingsScale();
        try {
            main(() -> prefs.settingsScale(100)); open("main");
            main(() -> { View mouse = tag("settings-link-input_devices"), backup = tag("settings-link-group_backup"); require(mouse.getParent() == backup.getParent() && ((ViewGroup) mouse.getParent()).indexOfChild(mouse) == 5 && ((ViewGroup) backup.getParent()).indexOfChild(backup) == 6, "mouse and backup swapped exactly"); require(label("settings-support").equals("免费软件,为爱发电,有问题和建议请联系QQ:769954413"), "exact support line"); TextView support = (TextView) tag("settings-support"); require(support.getCurrentTextColor() == SettingsUi.MUTED && support.getTextSize() < ((TextView) tag("settings-check-update")).getTextSize(), "small gray footer"); require(((ViewGroup) tag("settings-footer").getParent()).indexOfChild(tag("settings-footer")) == ((ViewGroup) tag("settings-footer").getParent()).getChildCount() - 1, "footer last"); ((ScrollView) tag("settings-scroll")).fullScroll(View.FOCUS_DOWN); }); capture("home-footer"); main(() -> textFits(root()));
            main(() -> { EditText query = (EditText) tag("settings-query"); query.setVisibility(View.VISIBLE); query.setText("电量"); require(tag("settings-footer").getVisibility() == View.GONE && tag("search-status") != null, "search keeps result and hides footer"); query.setText(""); require(tag("settings-footer").getVisibility() == View.VISIBLE, "clearing search restores footer"); });
            main(() -> { tag("settings-check-update").performClick(); require(tag("about-update") != null, "shortcut opens update route"); buttons("检查中…", "取消"); require(!tag("update-primary").isEnabled(), "shortcut starts one check"); tag("update-secondary").performClick(); buttons("检查更新", "返回"); }); capture("update-idle");
            Object owner = field(activity, "updateSettings"); AppUpdater.Listener listener = (AppUpdater.Listener) field(owner, "listener"); AppConfig.Update config = AppConfig.load(activity).update;
            JSONObject json = new JSONObject().put("schemaVersion", 1).put("packageName", activity.getPackageName()).put("versionCode", BuildConfig.VERSION_CODE + 1).put("versionName", "fixture-next").put("minSdk", 30).put("apkPath", "releases/" + (BuildConfig.VERSION_CODE + 1) + "/fixture.apk").put("apkSize", 100).put("sha256", "0".repeat(64)).put("changelog", "测试更新说明");
            UpdateCatalog next = UpdateCatalog.parse(json.toString(), config, activity.getPackageName());
            main(() -> { listener.catalog(next); buttons("下载更新", "重新检查"); require(tag("update-primary").isEnabled(), "new compatible version downloadable"); }); capture("update-available");
            main(() -> { tag("update-primary").performClick(); buttons("下载中…", "取消"); require(!tag("update-primary").isEnabled(), "duplicate download disabled"); listener.progress(25, 100); require(((android.widget.ProgressBar) tag("update-progress")).getProgress() == 25, "visible download progress"); listener.verifying(); buttons("校验中…", "取消"); tag("update-secondary").performClick(); buttons("下载更新", "重新检查"); require(tag("update-progress").getVisibility() == View.INVISIBLE, "cancel resets progress"); });
            main(() -> { listener.ready(new File(activity.getApplicationInfo().sourceDir)); buttons(activity.getPackageManager().canRequestPackageInstalls() ? "安装更新" : "允许安装更新", "重新检查"); require(tag("update-primary").isEnabled(), "verified callback offers installation"); }); capture("update-ready");
            main(() -> { set(owner, "busy", true); set(owner, "preparingInstall", true); ((UpdateSettings) owner).refresh(); buttons("校验中…", "取消"); listener.failed("测试失败，可重试"); buttons("下载更新", "重新检查"); require(tag("update-primary").isEnabled(), "failure allows download retry"); });
            UpdateCatalog same = UpdateCatalog.parse(new JSONObject(json.toString()).put("versionCode", BuildConfig.VERSION_CODE).put("versionName", BuildConfig.VERSION_NAME).toString(), config, activity.getPackageName());
            UpdateCatalog highSdk = UpdateCatalog.parse(new JSONObject(json.toString()).put("minSdk", android.os.Build.VERSION.SDK_INT + 1).toString(), config, activity.getPackageName());
            main(() -> { listener.catalog(same); buttons("检查更新", "返回"); listener.catalog(highSdk); buttons("检查更新", "返回"); require(((SettingsUi.ValueRow) tag("update-status")).value.getText().toString().contains("需要 Android"), "incompatible SDK truthful"); listener.failed("更新服务器不可用"); buttons("检查更新", "返回"); tag("update-primary").performClick(); buttons("检查中…", "取消"); ((UpdateSettings) owner).pause(); buttons("检查更新", "返回"); });
            main(() -> { tag("update-secondary").performClick(); require(tag("settings-check-update") != null && tag("about-update") == null, "secondary returns to source home"); require(field(owner, "updater") == null, "leaving releases update session"); });
            for (int percent : new int[]{70, 130}) { main(() -> prefs.settingsScale(percent)); open("main"); main(() -> { ((ScrollView) tag("settings-scroll")).fullScroll(View.FOCUS_DOWN); textFits(root()); }); open("about"); main(() -> { textFits(tag("about-update")); Button primary = (Button) tag("update-primary"), secondary = (Button) tag("update-secondary"); require(primary.getHeight() == secondary.getHeight(), "two buttons aligned at " + percent); }); }
            return "PASS settings-update-actions assertions=" + assertions;
        } finally { if (activity != null) main(activity::finish); main(() -> { if (hadScale) prefs.settingsScale(scale); else prefs.data.edit().remove("settings_scale").commit(); }); }
    }
}
