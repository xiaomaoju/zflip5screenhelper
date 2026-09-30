package io.github.flipcover.controls;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import org.json.JSONObject;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import rikka.shizuku.Shizuku;

public final class ShizukuBridge {
    record Snapshot(android.graphics.Bitmap bitmap, String message) { }
    public interface Callback { void accept(Result result); }
    public static final class Result {
        public final boolean ok;
        public final String message;
        public final String output;
        public final boolean retryable;
        public Result(boolean ok, String message, String output) { this(ok, message, output, false); }
        Result(boolean ok, String message, String output, boolean retryable) { this.ok = ok; this.message = message; this.output = output; this.retryable = retryable; }
        static Result parse(String json) throws Exception {
            JSONObject object = new JSONObject(json);
            return new Result(object.optBoolean("ok"), object.optString("message"), object.optString("output"));
        }
    }
    private final Context application;
    private final CopyOnWriteArrayList<Runnable> connectivityObservers = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Runnable> hotspotObservers = new CopyOnWriteArrayList<>();
    private IShellService watchedService;
    private boolean nfcRegistered;
    private final android.content.BroadcastReceiver nfcEvents = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context context, android.content.Intent intent) { if (android.nfc.NfcAdapter.ACTION_ADAPTER_STATE_CHANGED.equals(intent.getAction())) notifyConnectivity(); }
    };
    private final IConnectivityListener connectivityEvents = new IConnectivityListener.Stub() {
        @Override public void onChanged() { main.post(ShizukuBridge.this::notifyConnectivity); }
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean snapshotBusy = new AtomicBoolean();
    private final Shizuku.UserServiceArgs args;
    private final CopyOnWriteArrayList<Runnable> observers = new CopyOnWriteArrayList<>();
    private volatile IShellService remote;
    private boolean binding;
    private String connectionError = "";
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            remote = IShellService.Stub.asInterface(binder); binding = false; connectionError = ""; changed();
        }
        @Override public void onServiceDisconnected(ComponentName name) { remote = null; binding = false; changed(); main.post(ShizukuBridge.this::connect); }
        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }
    };
    public ShizukuBridge(Context context) {
        application = context.getApplicationContext();
        args = new Shizuku.UserServiceArgs(new ComponentName(context, ShellService.class))
            .daemon(false).processNameSuffix("cover_shell").version(BuildConfig.VERSION_CODE);
        Shizuku.addBinderReceivedListenerSticky(() -> main.post(this::connect));
        Shizuku.addBinderDeadListener(() -> main.post(() -> { remote = null; binding = false; changed(); }));
        Shizuku.addRequestPermissionResultListener((request, grant) -> main.post(() -> {
            if (grant == PackageManager.PERMISSION_GRANTED) connect(); else changed();
        }));
    }
    public boolean granted() {
        try { return Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED; }
        catch (RuntimeException e) { return false; }
    }
    public boolean connected() { return remote != null; }
    public String status() {
        if (!Shizuku.pingBinder()) return "Shizuku 未运行";
        if (!granted()) return "Shizuku 未授权";
        if (remote != null) return "Shizuku 已连接";
        return binding ? "Shizuku 正在连接" : "连接未就绪" + (connectionError.isEmpty() ? "" : "：" + connectionError);
    }
    public void requestPermission() {
        if (!Shizuku.pingBinder()) { changed(); return; }
        if (granted()) connect(); else Shizuku.requestPermission(100);
    }
    public void connect() {
        if (!granted() || remote != null || binding) return;
        try {
            binding = true;
            Shizuku.bindUserService(args, connection);
            main.postDelayed(() -> { if (remote == null && binding) { binding = false; connectionError = "连接超时，可重试授权按钮"; changed(); } }, 6000);
        } catch (RuntimeException e) { binding = false; connectionError = e.getClass().getSimpleName(); }
        changed();
    }
    public void addObserver(Runnable observer) { observers.addIfAbsent(observer); }
    public void removeObserver(Runnable observer) { observers.remove(observer); }
    private void changed() { for (Runnable observer : observers) main.post(observer); updateConnectivityWatch(); main.post(this::notifyConnectivity); }
    void addConnectivityObserver(Runnable observer, boolean hotspot) {
        connectivityObservers.addIfAbsent(observer);
        if (hotspot) hotspotObservers.addIfAbsent(observer);
        if (!nfcRegistered) try {
            android.content.IntentFilter filter = new android.content.IntentFilter(android.nfc.NfcAdapter.ACTION_ADAPTER_STATE_CHANGED);
            if (android.os.Build.VERSION.SDK_INT >= 33) application.registerReceiver(nfcEvents, filter, Context.RECEIVER_EXPORTED); else application.registerReceiver(nfcEvents, filter);
            nfcRegistered = true;
        } catch (RuntimeException ignored) { }
        updateConnectivityWatch();
    }
    void removeConnectivityObserver(Runnable observer) {
        connectivityObservers.remove(observer);
        hotspotObservers.remove(observer);
        if (connectivityObservers.isEmpty() && nfcRegistered) { try { application.unregisterReceiver(nfcEvents); } catch (RuntimeException ignored) { } nfcRegistered = false; }
        updateConnectivityWatch();
    }
    private void notifyConnectivity() { for (Runnable observer : connectivityObservers) observer.run(); }
    private void updateConnectivityWatch() {
        executor.execute(() -> {
            IShellService wanted = hotspotObservers.isEmpty() ? null : remote;
            if (watchedService == wanted) return;
            if (watchedService != null) try { watchedService.watchConnectivity(null); } catch (Exception ignored) { }
            watchedService = null;
            if (wanted != null) try { wanted.watchConnectivity(connectivityEvents); watchedService = wanted; } catch (Exception ignored) { }
        });
    }
    public void run(String operation, int display, int value, String component, Callback callback) {
        run(operation, display, value, component, () -> true, callback);
    }
    void run(String operation, int display, int value, String component, java.util.function.BooleanSupplier active, Callback callback) {
        IShellService service = remote;
        if (service == null) { connect(); callback.accept(new Result(false, status() + "，请稍后重试", "")); return; }
        if (!busy.compareAndSet(false, true)) { callback.accept(new Result(false, "上一项仍在执行，请稍后重试", "", true)); return; }
        executor.execute(() -> {
            Result result;
            try { result = active.getAsBoolean() ? Result.parse(service.execute(operation, display, value, component == null ? "" : component)) : new Result(false, "操作已取消，外屏状态已改变", ""); }
            catch (Exception e) { remote = null; changed(); result = new Result(false, "Shizuku 调用失败：" + e.getClass().getSimpleName(), ""); }
            Result delivered = result;
            busy.set(false);
            main.post(() -> callback.accept(delivered));
        });
    }
    boolean busy() { return busy.get() || snapshotBusy.get(); }
    void snapshot(int display, RecentTasks.Task task, java.util.function.Consumer<Snapshot> callback) {
        IShellService service = remote;
        if (service == null || busy.get() || !snapshotBusy.compareAndSet(false, true)) { callback.accept(new Snapshot(null, "预览暂不可用")); return; }
        executor.execute(() -> {
            Snapshot result;
            try {
                android.os.Bundle bundle = service.taskSnapshot(display, SystemRecentTasks.json(task).toString());
                android.graphics.Bitmap bitmap = bundle == null ? null : bundle.getParcelable("bitmap");
                if (bitmap != null && (bitmap.getWidth() > 256 || bitmap.getHeight() > 256)) { bitmap.recycle(); bitmap = null; }
                result = new Snapshot(bitmap, bundle == null ? "预览不可用" : bundle.getString("message", "预览不可用"));
            } catch (Exception error) { result = new Snapshot(null, "预览不可用"); }
            Snapshot delivered = result; snapshotBusy.set(false); main.post(() -> callback.accept(delivered));
        });
    }
}
