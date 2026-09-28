package io.github.flipcover.controls;

import android.Manifest;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telephony.SignalStrength;
import android.telephony.SubscriptionInfo;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyDisplayInfo;
import android.telephony.TelephonyManager;
import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** Read-only status overlay. Subscriptions and timers live only while this view is attached. */
final class StatusBarView extends View {
    private final Prefs prefs;
    private final boolean panelMode;
    private int previewScale;
    private int previewSafeLeft = -1, previewSafeRight = -1;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Drawable> notificationIcons = new ArrayList<>();
    private final List<Cell> cells = new ArrayList<>();
    private final Drawable wifiIcon, alarmIcon, boltIcon;
    private ConnectivityManager connectivity;
    private SubscriptionManager subscriptions;
    private boolean running, networkRegistered, receiverRegistered, subscriptionsRegistered, wifi, alarm, charging;
    private int battery = -1;
    private String speed = "";
    private String clock = "";
    private long lastBytes = -1, lastTime;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                battery = level < 0 || scale <= 0 ? -1 : Math.max(0, Math.min(100, Math.round(100f * level / scale)));
                charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            }
            alarm = context.getSystemService(AlarmManager.class).getNextAlarmClock() != null; updateClock(); invalidate();
        }
    };
    private final ConnectivityManager.NetworkCallback networks = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) { refreshNetwork(); }
        @Override public void onLost(Network network) { refreshNetwork(); }
    };
    private final SubscriptionManager.OnSubscriptionsChangedListener subscriptionListener = new SubscriptionManager.OnSubscriptionsChangedListener() {
        @Override public void onSubscriptionsChanged() { refreshCells(); }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            updateClock();
            if (prefs.statusItem("speed")) {
                long rx = TrafficStats.getTotalRxBytes(), tx = TrafficStats.getTotalTxBytes(), now = SystemClock.elapsedRealtime();
                long total = rx < 0 || tx < 0 ? -1 : rx + tx;
                speed = lastBytes < 0 || total < lastBytes || now <= lastTime ? "" : formatRate((total - lastBytes) * 1000f / (now - lastTime));
                lastBytes = total; lastTime = now;
            }
            invalidate(); main.postDelayed(this, prefs.statusItem("speed") ? 2000 : 60000 - System.currentTimeMillis() % 60000);
        }
    };
    StatusBarView(Context context, Prefs prefs) {
        this(context, prefs, false);
    }
    StatusBarView(Context context, Prefs prefs, boolean panelMode) {
        super(context); this.prefs = prefs; this.panelMode = panelMode; applyAppearance();
        wifiIcon = Ui.icon(context, R.drawable.ic_ms_wifi, Ui.TEXT); alarmIcon = Ui.icon(context, R.drawable.ic_ms_alarm, Ui.TEXT); boltIcon = Ui.icon(context, R.drawable.ic_ms_bolt, Ui.TEXT);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    int heightPixels() { return Math.max(1, Math.round(Ui.dp(getContext(), 20) * scaleFactor())); }
    private float scaleFactor() { return (previewScale > 0 ? previewScale : prefs.statusScale()) / 100f; }
    void previewScale(int percent) { previewScale = Math.max(50, Math.min(150, percent)); invalidate(); }
    void previewSafeArea(int left, int right) { previewSafeLeft = Math.max(0, Math.min(48, left)); previewSafeRight = Math.max(0, Math.min(48, right)); invalidate(); }
    boolean showsPercentage() { return panelMode || prefs.batteryPercent(); }
    private int foreground() { return panelMode ? Ui.TEXT : Ui.chromeColor(prefs); }
    private void applyAppearance() { setBackgroundColor(!panelMode && prefs.chromeStyle().equals("black") ? Ui.BACKGROUND : android.graphics.Color.TRANSPARENT); setLayerType(LAYER_TYPE_SOFTWARE, null); }
    static String formatRate(float bytes) { return bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.0fK/s", bytes / 1024) : String.format(Locale.ROOT, "%.1fM/s", bytes / (1024 * 1024)); }
    private void updateClock() { clock = android.text.format.DateFormat.getTimeFormat(getContext()).format(new java.util.Date()); }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow(); running = true;
        IntentFilter filter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED); filter.addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED); filter.addAction(Intent.ACTION_TIME_CHANGED); filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) getContext().registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED); else getContext().registerReceiver(receiver, filter);
        receiverRegistered = true; connectivity = getContext().getSystemService(ConnectivityManager.class);
        try { connectivity.registerDefaultNetworkCallback(networks, main); networkRegistered = true; } catch (RuntimeException ignored) { }
        refreshNetwork(); refreshNotifications(); refreshCells();
        if (Build.VERSION.SDK_INT >= 31 && prefs.statusItem("cellular") && getContext().checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            subscriptions = getContext().getSystemService(SubscriptionManager.class);
            try { subscriptions.addOnSubscriptionsChangedListener(getContext().getMainExecutor(), subscriptionListener); subscriptionsRegistered = true; } catch (RuntimeException ignored) { }
        }
        alarm = getContext().getSystemService(AlarmManager.class).getNextAlarmClock() != null; main.post(tick);
    }
    private void refreshNetwork() {
        if (!running) return;
        try { NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork()); wifi = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI); }
        catch (RuntimeException ignored) { wifi = false; }
        invalidate();
    }
    void refreshNotifications() {
        notificationIcons.clear();
        if (!prefs.statusItem("notifications")) { invalidate(); return; }
        HashSet<String> packages = new HashSet<>();
        for (android.service.notification.StatusBarNotification item : CoverNotifications.snapshot()) {
            if (!packages.add(item.getPackageName()) || notificationIcons.size() >= 4) continue;
            try {
                android.graphics.drawable.Icon small = item.getNotification().getSmallIcon();
                Drawable icon = small == null ? getContext().getPackageManager().getApplicationIcon(item.getPackageName()) : small.loadDrawable(getContext());
                if (icon != null) { icon = icon.mutate(); icon.setTint(foreground()); notificationIcons.add(!panelMode && prefs.chromeStyle().equals("contrast") ? new ContrastDrawable(icon, Ui.dp(getContext(), .7f)) : icon); }
            } catch (Exception ignored) { }
        }
        invalidate();
    }
    void updateOptions() { applyAppearance(); invalidate(); if (running) { refreshNotifications(); refreshCells(); main.removeCallbacks(tick); main.post(tick); } }
    private void refreshCells() {
        if (!running || Build.VERSION.SDK_INT < 31) return;
        for (Cell cell : cells) cell.stop(); cells.clear();
        if (!prefs.statusItem("cellular") || getContext().checkSelfPermission(Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) { invalidate(); return; }
        try {
            List<SubscriptionInfo> active = getContext().getSystemService(SubscriptionManager.class).getActiveSubscriptionInfoList();
            if (active != null) for (SubscriptionInfo subscription : active) {
                if (cells.size() >= 2) break;
                Cell cell = new Cell(getContext().getSystemService(TelephonyManager.class).createForSubscriptionId(subscription.getSubscriptionId())); cells.add(cell); cell.start();
            }
        } catch (RuntimeException ignored) { }
        invalidate();
    }
    @androidx.annotation.RequiresApi(31)
    private final class Cell extends TelephonyCallback implements TelephonyCallback.SignalStrengthsListener, TelephonyCallback.DisplayInfoListener {
        final TelephonyManager phone; int level = -1; String type = ""; boolean registered;
        Cell(TelephonyManager phone) { this.phone = phone; }
        void start() { try { phone.registerTelephonyCallback(getContext().getMainExecutor(), this); registered = true; } catch (RuntimeException ignored) { } }
        void stop() { if (registered) try { phone.unregisterTelephonyCallback(this); } catch (RuntimeException ignored) { } }
        @Override public void onSignalStrengthsChanged(SignalStrength signal) { if (running) { level = signal.getLevel(); invalidate(); } }
        @Override public void onDisplayInfoChanged(TelephonyDisplayInfo information) {
            if (!running) return;
            int override = information.getOverrideNetworkType(), network = information.getNetworkType();
            if (override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA || override == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED || network == TelephonyManager.NETWORK_TYPE_NR) type = "5G";
            else if (network == TelephonyManager.NETWORK_TYPE_LTE) type = "4G";
            else if (network == TelephonyManager.NETWORK_TYPE_UMTS || network == TelephonyManager.NETWORK_TYPE_HSDPA || network == TelephonyManager.NETWORK_TYPE_HSPA || network == TelephonyManager.NETWORK_TYPE_HSPAP) type = "3G";
            else if (network == TelephonyManager.NETWORK_TYPE_GPRS || network == TelephonyManager.NETWORK_TYPE_EDGE || network == TelephonyManager.NETWORK_TYPE_GSM) type = "2G";
            else type = "";
            invalidate();
        }
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int leftInset = Ui.dp(getContext(), previewSafeLeft >= 0 ? previewSafeLeft : prefs.statusSafeLeft()), rightInset = Ui.dp(getContext(), previewSafeRight >= 0 ? previewSafeRight : prefs.statusSafeRight());
        if (getWidth() <= leftInset + rightInset) return;
        int save = canvas.save(); canvas.clipRect(leftInset, 0, getWidth() - rightInset, getHeight()); canvas.translate(leftInset, 0);
        float scale = scaleFactor(); canvas.scale(scale, scale);
        float width = (getWidth() - leftInset - rightInset) / scale, height = getHeight() / scale;
        paint.setStyle(Paint.Style.FILL); paint.setColor(foreground()); paint.setTextSize(Ui.dp(getContext(), 10));
        paint.clearShadowLayer();
        float baseline = height / 2f - (paint.ascent() + paint.descent()) / 2, right = width - Ui.dp(getContext(), 6), x = Ui.dp(getContext(), 6);
        int icon = Ui.dp(getContext(), 13), gap = Ui.dp(getContext(), 5), middle = Math.round(height / 2);
        if (panelMode || prefs.statusItem("battery")) { right -= batteryWidth(); drawBattery(canvas, right, baseline, middle, showsPercentage()); right -= gap; }
        if (wifi && prefs.statusItem("wifi") && right - icon >= x) { right -= icon; drawIcon(canvas, wifiIcon, right, middle, icon); right -= gap; }
        if (prefs.statusItem("cellular") && Build.VERSION.SDK_INT >= 31) for (Cell cell : cells) {
            if (cell.level < 0 || right - Ui.dp(getContext(), 25) < x) continue;
            right -= Ui.dp(getContext(), 20); float unit = Ui.dp(getContext(), 3);
            for (int i = 0; i < 4; i++) { paint.setColor(i < cell.level ? foreground() : Ui.MUTED); canvas.drawRect(right + i * unit, middle + Ui.dp(getContext(), 6) - (i + 1) * unit, right + i * unit + unit * .65f, middle + Ui.dp(getContext(), 6), paint); }
            paint.setColor(foreground()); paint.setTextSize(Ui.dp(getContext(), 6)); text(canvas, cell.type, right, middle - Ui.dp(getContext(), 6)); paint.setTextSize(Ui.dp(getContext(), 10)); right -= gap;
        }
        if (alarm && prefs.statusItem("alarm") && right - icon > x) { right -= icon; drawIcon(canvas, alarmIcon, right, middle, icon); right -= gap; }
        if (prefs.statusItem("speed") && !speed.isEmpty()) { paint.setTextSize(Ui.dp(getContext(), 8)); float length = paint.measureText(speed); if (right - length > x) { right -= length; text(canvas, speed, right, baseline); right -= gap; } paint.setTextSize(Ui.dp(getContext(), 10)); }
        if (prefs.statusItem("time") && x + paint.measureText(clock) <= right) { text(canvas, clock, x, baseline); x += paint.measureText(clock) + gap * 2; }
        for (Drawable drawable : notificationIcons) { if (x + icon > right) break; drawIcon(canvas, drawable, x, middle, icon); x += icon + gap; }
        canvas.restoreToCount(save);
    }
    private void text(Canvas canvas, String value, float x, float y) {
        if (!panelMode && prefs.chromeStyle().equals("contrast")) { paint.setColor(android.graphics.Color.BLACK); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(), 1)); canvas.drawText(value, x, y, paint); }
        paint.setColor(foreground()); paint.setStyle(Paint.Style.FILL); canvas.drawText(value, x, y, paint);
    }
    private String percent() { return battery < 0 ? "—%" : battery + "%"; }
    private float batteryWidth() { return Ui.dp(getContext(), 18) + (showsPercentage() ? Ui.dp(getContext(), 5) + paint.measureText(percent()) : 0) + (charging ? Ui.dp(getContext(), 18) : 0); }
    private float drawBattery(Canvas canvas, float x, float baseline, int middle, boolean percent) {
        int width = Ui.dp(getContext(), 16), height = Ui.dp(getContext(), 8); float line = Math.max(1, Ui.dp(getContext(), .7f));
        if (!panelMode && prefs.chromeStyle().equals("contrast")) { paint.setStyle(Paint.Style.FILL); paint.setColor(0xDD000000); canvas.drawRoundRect(x - line, middle - height / 2f - line, x + width, middle + height / 2f + line, line * 2, line * 2, paint); paint.setColor(foreground()); }
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(line); canvas.drawRoundRect(x, middle - height / 2f, x + width - line * 2, middle + height / 2f, line, line, paint);
        paint.setStyle(Paint.Style.FILL); canvas.drawRect(x + width - line, middle - height / 4f, x + width + line, middle + height / 4f, paint);
        if (battery >= 0) canvas.drawRect(x + line * 2, middle - height / 2f + line * 2, x + line * 2 + (width - line * 6) * battery / 100f, middle + height / 2f - line * 2, paint);
        x += width + Ui.dp(getContext(), 2); if (percent) { x += Ui.dp(getContext(), 5); text(canvas, percent(), x, baseline); x += paint.measureText(percent()); }
        if (charging) { x += Ui.dp(getContext(), 5); drawIcon(canvas, boltIcon, x, middle, Ui.dp(getContext(), 13)); x += Ui.dp(getContext(), 13); } return x;
    }
    private void drawIcon(Canvas canvas, Drawable drawable, float x, int middle, int size) {
        drawable.setBounds(Math.round(x), middle - size / 2, Math.round(x) + size, middle + size / 2);
        if (drawable instanceof ContrastDrawable) { drawable.draw(canvas); return; }
        if (!panelMode && prefs.chromeStyle().equals("contrast")) { drawable.setTint(android.graphics.Color.BLACK); float r = Ui.dp(getContext(), .7f); for (int a = -1; a <= 1; a++) for (int b = -1; b <= 1; b++) { if (a == 0 && b == 0) continue; int save = canvas.save(); canvas.translate(a * r, b * r); drawable.draw(canvas); canvas.restoreToCount(save); } }
        drawable.setTint(foreground()); drawable.draw(canvas);
    }
    @Override protected void onDetachedFromWindow() {
        running = false; main.removeCallbacksAndMessages(null);
        if (receiverRegistered) { getContext().unregisterReceiver(receiver); receiverRegistered = false; }
        if (networkRegistered) { connectivity.unregisterNetworkCallback(networks); networkRegistered = false; }
        if (Build.VERSION.SDK_INT >= 31) { for (Cell cell : cells) cell.stop(); cells.clear(); }
        if (subscriptionsRegistered) { subscriptions.removeOnSubscriptionsChangedListener(subscriptionListener); subscriptionsRegistered = false; }
        notificationIcons.clear(); super.onDetachedFromWindow();
    }
}
