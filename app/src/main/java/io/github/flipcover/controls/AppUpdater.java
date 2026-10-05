package io.github.flipcover.controls;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** One mounted settings page owns one cancellable update session. */
final class AppUpdater implements AutoCloseable {
    interface Listener {
        void catalog(UpdateCatalog value);
        void progress(long downloaded, long total);
        void verifying();
        void ready(File file);
        void failed(String message);
    }
    final AppConfig.Update config;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final File directory;
    private volatile int request;
    private volatile boolean closed;
    private volatile HttpURLConnection connection;
    private Listener listener;
    private Future<?> task;
    private boolean retainForInstaller;
    AppUpdater(Context context, AppConfig.Update config) {
        this.context = context.getApplicationContext(); this.config = config;
        directory = new File(new File(this.context.getFilesDir(), "updates"), UUID.randomUUID().toString());
    }
    private int begin(Listener next) { cancel(); listener = next; return request; }
    void check(Listener next) {
        int ticket = begin(next);
        task = worker.submit(() -> {
            try {
                cleanExpired(); HttpURLConnection opened = open(config.catalogUri(), ticket);
                String type = opened.getContentType(); if (type != null && type.toLowerCase(java.util.Locale.ROOT).contains("text/html")) throw new IOException("服务器返回了网页，尚未提供更新目录，请确认 APK 更新服务已部署");
                if (opened.getContentLengthLong() > config.maxCatalogBytes()) throw new IOException("更新目录过大");
                String encoded; try (InputStream stream = opened.getInputStream()) { encoded = new String(UpdateTransfer.read(stream, config.maxCatalogBytes()), StandardCharsets.UTF_8); }
                UpdateCatalog catalog = UpdateCatalog.parse(encoded, config, context.getPackageName());
                post(ticket, current -> current.catalog(catalog));
            } catch (Exception error) { failure(ticket, error); }
            finally { disconnect(); }
        });
    }
    void download(UpdateCatalog catalog, Listener next) {
        int ticket = begin(next);
        task = worker.submit(() -> {
            File partial = new File(directory, "download.part"), apk = new File(directory, "update.apk"); boolean complete = false;
            try {
                if (catalog.versionCode() <= installed().getLongVersionCode() || catalog.minSdk() > Build.VERSION.SDK_INT) throw new IOException("这个版本无法更新当前应用");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建更新缓存");
                HttpURLConnection opened = open(catalog.apkUri(), ticket);
                long length = opened.getContentLengthLong(); if (length >= 0 && length != catalog.apkSize()) throw new IOException("服务器文件大小与更新目录不一致");
                try (InputStream input = opened.getInputStream(); FileOutputStream output = new FileOutputStream(partial)) {
                    UpdateTransfer.copy(input, output, catalog.apkSize(), catalog.sha256(), () -> !active(ticket), size -> post(ticket, current -> current.progress(size, catalog.apkSize())));
                    output.getFD().sync();
                }
                if (!active(ticket)) return;
                post(ticket, Listener::verifying); verifyArchive(context, partial, catalog);
                if (!active(ticket)) return;
                if (apk.exists() && !apk.delete() || !partial.renameTo(apk)) throw new IOException("无法保存已校验的更新包");
                complete = true; post(ticket, current -> current.ready(apk));
            } catch (Exception error) { failure(ticket, error); }
            finally { disconnect(); partial.delete(); if (!complete) apk.delete(); }
        });
    }
    void prepareInstall(File apk, UpdateCatalog catalog, Listener next) {
        int ticket = begin(next);
        task = worker.submit(() -> {
            try {
                if (catalog.versionCode() <= installed().getLongVersionCode()) throw new IOException("当前应用已是相同或更高版本，请重新检查更新");
                verifyArchive(context, apk, catalog); if (active(ticket)) post(ticket, current -> current.ready(apk));
            } catch (Exception error) { failure(ticket, error); }
        });
    }
    void installerStarted() { retainForInstaller = true; close(); }
    private PackageInfo installed() throws Exception { return context.getPackageManager().getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES); }
    static void verifyArchive(Context context, File apk, UpdateCatalog catalog) throws Exception {
        if (!apk.isFile() || apk.length() != catalog.apkSize()) throw new IOException("更新文件已失效，请重新下载");
        PackageManager manager = context.getPackageManager(); PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null || archive.applicationInfo == null || !context.getPackageName().equals(archive.packageName) || !catalog.packageName().equals(archive.packageName) || archive.getLongVersionCode() != catalog.versionCode() || !catalog.versionName().equals(archive.versionName) || archive.applicationInfo.minSdkVersion != catalog.minSdk() || archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException("APK 包名、版本或系统要求与更新目录不一致");
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive.signingInfo == null || installed.signingInfo == null || !sameSigners(archive.signingInfo.getApkContentsSigners(), installed.signingInfo.getApkContentsSigners())) throw new IOException("APK 签名与已安装应用不一致");
    }
    static boolean sameSigners(Signature[] first, Signature[] second) {
        if (first == null || second == null || first.length == 0 || first.length != second.length) return false;
        return new java.util.HashSet<>(java.util.Arrays.asList(first)).equals(new java.util.HashSet<>(java.util.Arrays.asList(second)));
    }
    private HttpURLConnection open(URI address, int ticket) throws Exception {
        for (int redirects = 0; ; redirects++) {
            if (!active(ticket)) throw new java.io.InterruptedIOException("已取消");
            UpdateCatalog.inProject(config.catalogUri(), address);
            HttpURLConnection opened = (HttpURLConnection) address.toURL().openConnection(); connection = opened;
            opened.setInstanceFollowRedirects(false); opened.setUseCaches(false); opened.setConnectTimeout(config.connectTimeoutMs()); opened.setReadTimeout(config.readTimeoutMs());
            opened.setRequestProperty("Accept-Encoding", "identity"); opened.setRequestProperty("Cache-Control", "no-cache"); opened.setRequestProperty("User-Agent", "FlipCover-Update");
            int status = opened.getResponseCode();
            if (status == 200) return opened;
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = opened.getHeaderField("Location"); opened.disconnect(); connection = null;
                if (redirects >= config.maxRedirects() || location == null) throw new IOException("更新地址重定向失败");
                address = address.resolve(location); continue;
            }
            if (status == 404) throw new IOException("服务器暂未提供该更新文件（404）");
            if (status == 403) throw new IOException("服务器已关闭下载或拒绝访问（403）");
            throw new IOException("更新服务器返回 HTTP " + status);
        }
    }
    private boolean active(int ticket) { return !closed && request == ticket && !Thread.currentThread().isInterrupted(); }
    private void post(int ticket, java.util.function.Consumer<Listener> action) {
        if (!active(ticket)) return;
        main.postAtTime(() -> { if (!closed && request == ticket && listener != null) action.accept(listener); }, this, SystemClock.uptimeMillis());
    }
    private void failure(int ticket, Exception error) {
        String message = error instanceof javax.net.ssl.SSLException ? "服务器安全连接失败，请检查证书或网络" : error instanceof java.net.SocketTimeoutException ? "连接或下载超时，请重试" : error instanceof java.net.UnknownHostException ? "无法连接更新服务器，请检查网络" : error.getMessage();
        post(ticket, current -> current.failed(message == null ? "更新失败，请重试" : message));
    }
    void cancel() { request++; listener = null; main.removeCallbacksAndMessages(this); if (task != null) task.cancel(true); task = null; disconnect(); }
    private void disconnect() { HttpURLConnection opened = connection; connection = null; if (opened != null) opened.disconnect(); }
    private void cleanExpired() {
        File[] sessions = directory.getParentFile().listFiles(); if (sessions == null) return;
        long cutoff = System.currentTimeMillis() - config.cacheRetentionHours() * 3600000L;
        for (File session : sessions) if (session.isDirectory() && session.lastModified() < cutoff) remove(session);
    }
    private static void remove(File directory) { File[] files = directory.listFiles(); if (files != null) for (File file : files) if (file.isFile()) file.delete(); directory.delete(); }
    @Override public void close() { if (closed) return; closed = true; cancel(); if (!retainForInstaller) worker.execute(() -> remove(directory)); worker.shutdown(); }
}
