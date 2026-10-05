package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.view.View;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;

/** Protocol, real signed archive and settings lifecycle checks on the guarded emulator. */
final class AppUpdateChecks {
    private int assertions;
    private void require(boolean value, String label) { assertions++; if (!value) throw new AssertionError(label); }
    private interface Invalid { void run() throws Exception; }
    private void rejects(Invalid action, String label) throws Exception { try { action.run(); } catch (IllegalArgumentException | java.io.IOException expected) { assertions++; return; } throw new AssertionError(label); }
    String run(Instrumentation test) throws Exception {
        Context context = test.getTargetContext(); AppConfig.Update config = AppConfig.load(context).update;
        require(config.catalogUri().getPath().endsWith("/catalog.json"), "packaged catalog address");
        PackageInfo installed = context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        File source = new File(context.getApplicationInfo().sourceDir); MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream stream = new FileInputStream(source)) { byte[] buffer = new byte[65536]; int count; while ((count = stream.read(buffer)) != -1) digest.update(buffer, 0, count); }
        StringBuilder hash = new StringBuilder(); for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        JSONObject valid = new JSONObject().put("schemaVersion", 1).put("packageName", context.getPackageName()).put("versionCode", installed.getLongVersionCode()).put("versionName", installed.versionName).put("minSdk", installed.applicationInfo.minSdkVersion).put("changelog", "协议验证\n第二行").put("apkPath", "releases/" + installed.getLongVersionCode() + "/fixture.apk").put("apkSize", source.length()).put("sha256", hash.toString());
        UpdateCatalog catalog = UpdateCatalog.parse(valid.toString(), config, context.getPackageName());
        require(catalog.versionCode() == installed.getLongVersionCode(), "numeric version"); require(catalog.changelog().contains("\n"), "multiline changelog");
        require(catalog.apkUri().getPath().startsWith(config.catalogUri().resolve(".").getPath()), "relative project APK");
        AppUpdater.verifyArchive(context, source, catalog); assertions++;
        File staged = new File(context.getCacheDir(), "update-fixture.part");
        try { java.nio.file.Files.copy(source.toPath(), staged.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); AppUpdater.verifyArchive(context, staged, catalog); assertions++; }
        finally { staged.delete(); }
        rejects(() -> androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".updates", source), "provider cannot share unrelated files");
        File shared = new File(new File(context.getFilesDir(), "updates"), "provider-fixture.apk");
        require(androidx.core.content.FileProvider.getUriForFile(context, context.getPackageName() + ".updates", shared).getScheme().equals("content"), "private update URI");
        for (String path : new String[]{"../escape.apk", "/escape.apk", "https://example.invalid/evil.apk", "//example.invalid/evil.apk", "releases/%2e%2e/evil.apk", "releases/%252e%252e/evil.apk", "releases/../evil.apk", "fixture.apk?token=1", "fixture.apk#fragment", "releases/..%2fevil.apk"}) {
            JSONObject bad = new JSONObject(valid.toString()).put("apkPath", path); rejects(() -> UpdateCatalog.parse(bad.toString(), config, context.getPackageName()), "reject path " + path);
        }
        for (Object[] change : new Object[][]{{"schemaVersion", 2}, {"packageName", "other.application"}, {"versionCode", 0}, {"versionCode", "69"}, {"versionCode", 69.5}, {"apkSize", 0}, {"apkSize", config.maxApkBytes() + 1}, {"minSdk", 0}, {"sha256", "INVALID"}}) {
            JSONObject bad = new JSONObject(valid.toString()).put((String) change[0], change[1]); rejects(() -> UpdateCatalog.parse(bad.toString(), config, context.getPackageName()), "reject field " + change[0]);
        }
        JSONObject mismatch = new JSONObject(valid.toString()).put("versionCode", installed.getLongVersionCode() + 1);
        rejects(() -> AppUpdater.verifyArchive(context, source, UpdateCatalog.parse(mismatch.toString(), config, context.getPackageName())), "archive version must match catalog");
        Signature first = new Signature(new byte[]{1, 2}), second = new Signature(new byte[]{3, 4});
        require(AppUpdater.sameSigners(new Signature[]{first, second}, new Signature[]{second, first}), "signer ordering");
        require(!AppUpdater.sameSigners(new Signature[]{first}, new Signature[]{second}), "different signer rejected");
        require(!AppUpdater.sameSigners(new Signature[0], new Signature[0]), "missing signer rejected");
        MainActivity activity = (MainActivity) test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "group_backup"));
        try {
            test.waitForIdleSync(); test.runOnMainSync(() -> { activity.findViewById(android.R.id.content).findViewWithTag("settings-link-about").performClick(); }); test.waitForIdleSync();
            test.runOnMainSync(() -> {
                View root = activity.findViewById(android.R.id.content);
                View primary = root.findViewWithTag("update-primary"), secondary = root.findViewWithTag("update-secondary");
                require(primary.isEnabled() && ((android.view.ViewGroup) root.findViewWithTag("update-actions")).getChildCount() == 2, "exactly two dynamic update buttons");
                primary.performClick(); require(!primary.isEnabled(), "duplicate check disabled");
                require(((android.widget.Button) secondary).getText().toString().equals("取消"), "busy secondary cancels"); secondary.performClick(); require(primary.isEnabled(), "cancel allows retry");
                primary.performClick(); activity.onBackPressed(); require(activity.findViewById(android.R.id.content).findViewWithTag("about-update") == null, "leaving page releases updater");
            }); test.waitForIdleSync();
        } finally { test.runOnMainSync(activity::finish); }
        return "PASS app-update assertions=" + assertions;
    }
}
