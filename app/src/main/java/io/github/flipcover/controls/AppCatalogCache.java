package io.github.flipcover.controls;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-wide installed-app metadata and bounded icons. Never stores usage or task history. */
final class AppCatalogCache {
    record Entry(String id, String label, String packageName, String searchKey) { }
    private record Snapshot(int generation, List<Entry> entries, Map<String, Entry> byId, Map<String, String> launchers) { }
    interface Listener { void catalogChanged(); void iconsChanged(Set<String> ids); }
    static final int ICON_BYTES = 4 * 1024 * 1024, ICON_SIZE = 96;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object scanLock = new Object(), stateLock = new Object();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 15, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), task -> new Thread(() -> { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); task.run(); }, "cover-app-catalog"));
    private final AtomicBoolean warming = new AtomicBoolean();
    private final Set<Listener> listeners = new HashSet<>();
    private final Set<String> pendingIcons = new HashSet<>(), changedIcons = new HashSet<>();
    private final LruCache<String, String> extraLabels = new LruCache<>(256);
    private final LruCache<String, Boolean> failedIcons = new LruCache<>(96);
    private final LruCache<String, Bitmap> icons = new LruCache<>(ICON_BYTES) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private volatile Snapshot snapshot;
    private volatile int generation, iconGeneration;
    private volatile boolean failed;
    private volatile int scans, decodes, labelReads;
    private final Runnable deliverIcons = () -> {
        Set<String> changed;
        synchronized (stateLock) { changed = Set.copyOf(changedIcons); changedIcons.clear(); }
        for (Listener listener : List.copyOf(listeners)) listener.iconsChanged(changed);
    };
    AppCatalogCache(Context context) {
        this.context = context.getApplicationContext();
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ignored, Intent intent) {
                if (Intent.ACTION_PACKAGE_REMOVED.equals(intent.getAction()) && intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return;
                invalidate();
            }
        };
        IntentFilter packages = new IntentFilter(); packages.addAction(Intent.ACTION_PACKAGE_ADDED); packages.addAction(Intent.ACTION_PACKAGE_REMOVED); packages.addAction(Intent.ACTION_PACKAGE_CHANGED); packages.addAction(Intent.ACTION_PACKAGE_REPLACED); packages.addDataScheme("package");
        IntentFilter external = new IntentFilter(); external.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE); external.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
        if (Build.VERSION.SDK_INT >= 33) { this.context.registerReceiver(receiver, packages, Context.RECEIVER_NOT_EXPORTED); this.context.registerReceiver(receiver, external, Context.RECEIVER_NOT_EXPORTED); }
        else { this.context.registerReceiver(receiver, packages); this.context.registerReceiver(receiver, external); }
    }
    List<Entry> snapshot() { Snapshot value = snapshot; return value == null ? List.of() : value.entries; }
    boolean ready() { Snapshot value = snapshot; return value != null && value.generation == generation; }
    boolean failed() { return failed; }
    String label(String id) { Snapshot value = snapshot; Entry entry = value == null || value.generation != generation ? null : value.byId.get(id); return entry == null ? extraLabels.get(id) : entry.label; }
    String launcher(String packageName) { Snapshot value = snapshot; return value == null ? null : value.launchers.get(packageName); }
    void observe(Listener listener) { listeners.add(listener); warm(); }
    void unobserve(Listener listener) { listeners.remove(listener); }
    void warm() {
        if (ready() || !warming.compareAndSet(false, true)) return;
        int requested = generation;
        worker.execute(() -> {
            try { entriesBlocking(); }
            catch (RuntimeException e) { failed = true; main.post(this::notifyCatalog); }
            finally { warming.set(false); if (requested != generation) warm(); }
        });
    }
    /** Only for background catalog consumers; UI reads the immutable snapshot without waiting. */
    List<Entry> entriesBlocking() {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IllegalStateException("Read the UI snapshot instead of blocking");
        synchronized (scanLock) {
            for (int attempt = 0; attempt < 3; attempt++) {
                if (ready()) return snapshot.entries;
                int requested = generation; scans++;
                Map<String, Entry> found = new LinkedHashMap<>();
                for (ResolveInfo info : context.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                    if (info.activityInfo == null || !info.activityInfo.exported || !info.activityInfo.enabled || !info.activityInfo.applicationInfo.enabled) continue;
                    String id = "app:" + new ComponentName(info.activityInfo.packageName, info.activityInfo.name).flattenToString();
                    if (found.containsKey(id)) continue;
                    String label; labelReads++;
                    try { label = info.loadLabel(context.getPackageManager()).toString(); } catch (RuntimeException e) { label = info.activityInfo.packageName; }
                    found.put(id, new Entry(id, label, info.activityInfo.packageName, label.toLowerCase(Locale.ROOT)));
                    if (found.size() == 1000) break;
                }
                List<Entry> entries = new ArrayList<>(found.values()); Collator order = Collator.getInstance(context.getResources().getConfiguration().getLocales().get(0));
                entries.sort((a, b) -> { int compared = order.compare(a.label, b.label); return compared != 0 ? compared : a.id.compareTo(b.id); });
                Map<String, String> launchers = new HashMap<>(); for (Entry entry : entries) launchers.putIfAbsent(entry.packageName, entry.id);
                synchronized (stateLock) {
                    if (requested != generation) continue;
                    snapshot = new Snapshot(requested, List.copyOf(entries), Map.copyOf(found), Map.copyOf(launchers)); failed = false;
                }
                main.post(this::notifyCatalog); return snapshot.entries;
            }
        }
        throw new IllegalStateException("Application catalog changed during loading");
    }
    private void notifyCatalog() { for (Listener listener : List.copyOf(listeners)) listener.catalogChanged(); }
    void invalidate() {
        synchronized (stateLock) { generation++; iconGeneration++; failed = false; icons.evictAll(); extraLabels.evictAll(); failedIcons.evictAll(); }
        // Coalesce package-replace broadcasts and rebuild lazily when the UI is closed.
        main.removeCallbacks(refreshCatalog); if (!listeners.isEmpty()) main.postDelayed(refreshCatalog, 200);
    }
    private final Runnable refreshCatalog = this::warm;
    Drawable cachedIcon(Context target, String id) { Bitmap bitmap = icons.get(id); return bitmap == null ? null : new BitmapDrawable(target.getResources(), bitmap); }
    boolean requestIcon(String id) {
        int requested; String pendingKey;
        synchronized (stateLock) {
            requested = iconGeneration;
            pendingKey = requested + ":" + id;
            if (icons.get(id) != null || failedIcons.get(id) != null || pendingIcons.contains(pendingKey)) return true;
            if (pendingIcons.size() >= 96) return false;
            pendingIcons.add(pendingKey);
        }
        worker.execute(() -> {
            Bitmap bitmap = null; String name = null;
            try {
                name = ActionCatalog.label(context, id);
                Drawable drawable = ActionCatalog.loadIcon(context, id);
                bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888); bitmap.setDensity(Bitmap.DENSITY_NONE);
                int width = Math.max(1, drawable.getIntrinsicWidth()), height = Math.max(1, drawable.getIntrinsicHeight()); float scale = Math.min((float) ICON_SIZE / width, (float) ICON_SIZE / height);
                int fittedWidth = Math.max(1, Math.round(width * scale)), fittedHeight = Math.max(1, Math.round(height * scale)); int left = (ICON_SIZE - fittedWidth) / 2, top = (ICON_SIZE - fittedHeight) / 2;
                drawable.setBounds(left, top, left + fittedWidth, top + fittedHeight); drawable.draw(new Canvas(bitmap)); decodes++;
            } catch (RuntimeException ignored) { }
            synchronized (stateLock) {
                pendingIcons.remove(pendingKey);
                if (requested != iconGeneration) { bitmap = null; }
                if (bitmap != null) { icons.put(id, bitmap); if (name != null) extraLabels.put(id, name); }
                else if (requested == iconGeneration) failedIcons.put(id, true);
                changedIcons.add(id);
                if (!main.hasCallbacks(deliverIcons)) main.postDelayed(deliverIcons, 16);
            }
        });
        return true;
    }
    void trimMemory() { synchronized (stateLock) { iconGeneration++; icons.evictAll(); } }
    int scanCount() { return scans; }
    int contentGeneration() { return generation; }
    int decodeCount() { return decodes; }
    int labelReadCount() { return labelReads; }
    int pendingIconCount() { synchronized (stateLock) { return pendingIcons.size(); } }
    int iconBytes() { return icons.size(); }
    int observerCount() { return listeners.size(); }
}
