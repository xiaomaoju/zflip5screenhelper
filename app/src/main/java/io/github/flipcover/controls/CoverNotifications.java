package io.github.flipcover.controls;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class CoverNotifications extends NotificationListenerService {
    private static final ConcurrentHashMap<String, StatusBarNotification> ITEMS = new ConcurrentHashMap<>();
    private static volatile CoverNotifications connected;
    public static boolean ready() { return connected != null; }
    public static List<StatusBarNotification> snapshot() {
        List<StatusBarNotification> items = new ArrayList<>(ITEMS.values());
        items.sort(Comparator.comparingLong(StatusBarNotification::getPostTime).reversed());
        return items;
    }
    @Override public void onListenerConnected() {
        connected = this; ITEMS.clear();
        try { for (StatusBarNotification item : getActiveNotifications()) ITEMS.put(item.getKey(), item); }
        catch (RuntimeException e) { connected = null; }
        changed();
    }
    @Override public void onNotificationPosted(StatusBarNotification item) {
        ITEMS.put(item.getKey(), item);
        changed();
    }
    @Override public void onNotificationRemoved(StatusBarNotification item) { ITEMS.remove(item.getKey()); changed(); }
    @Override public void onListenerDisconnected() { connected = null; ITEMS.clear(); changed(); }
    @Override public void onDestroy() { connected = null; ITEMS.clear(); changed(); super.onDestroy(); }
    /** Returns submitted requests, not confirmed removals. The listener callback owns removal. */
    static int dismiss(List<StatusBarNotification> requested) {
        CoverNotifications service = connected; if (service == null) return -1;
        try {
            StatusBarNotification[] active = service.getActiveNotifications();
            if (active == null) return -1;
            List<String> keys = NotificationGroups.clearKeys(requested, java.util.Arrays.asList(active));
            if (!keys.isEmpty()) service.cancelNotifications(keys.toArray(new String[0]));
            int count = 0; for (StatusBarNotification item : NotificationGroups.clearable(java.util.Arrays.asList(active))) if (keys.contains(item.getKey())) count++;
            return count;
        } catch (RuntimeException failure) { return -1; }
    }
    private void changed() { CoverService service = CoverService.instance; if (service != null) service.notificationsChanged(); CoverApp.launcherWidgets(this).schedule(); }
}
