package io.github.flipcover.controls;

import android.app.Notification;
import android.content.Intent;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Active notifications only: summaries are not extra messages, and profiles never mix. */
final class NotificationGroups {
    static final class Group {
        final String key;
        final List<StatusBarNotification> items = new ArrayList<>();
        Group(String key) { this.key = key; }
    }
    private static String appKey(StatusBarNotification item) { return item.getUserId() + "|" + item.getPackageName() + "|"; }
    private static String systemGroup(StatusBarNotification item) { return appKey(item) + item.getGroupKey(); }
    static boolean summary(StatusBarNotification item) { return (item.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0; }
    private static String groupKey(StatusBarNotification item) {
        Notification notification = item.getNotification();
        String shortcut = notification.getShortcutId();
        if (shortcut != null && !shortcut.isEmpty()) return appKey(item) + "conversation:" + shortcut;
        if (notification.getGroup() != null || item.getOverrideGroupKey() != null) return appKey(item) + "group:" + item.getGroupKey();
        if (!item.isClearable()) return appKey(item) + "ongoing:" + item.getKey();
        return appKey(item) + "app";
    }
    static List<Group> build(List<StatusBarNotification> source) {
        LinkedHashMap<String, StatusBarNotification> unique = new LinkedHashMap<>();
        for (StatusBarNotification item : source) unique.put(item.getKey(), item);
        List<StatusBarNotification> ordered = new ArrayList<>(unique.values());
        ordered.sort(Comparator.comparingLong(StatusBarNotification::getPostTime).reversed().thenComparing(StatusBarNotification::getKey));
        Set<String> children = new HashSet<>();
        for (StatusBarNotification item : ordered) if (!summary(item)) children.add(systemGroup(item));
        LinkedHashMap<String, Group> groups = new LinkedHashMap<>();
        for (StatusBarNotification item : ordered) {
            if (summary(item) && children.contains(systemGroup(item))) continue;
            groups.computeIfAbsent(groupKey(item), Group::new).items.add(item);
        }
        return new ArrayList<>(groups.values());
    }
    static List<StatusBarNotification> clearable(List<StatusBarNotification> source) {
        List<StatusBarNotification> result = new ArrayList<>();
        for (Group group : build(source)) for (StatusBarNotification item : group.items) if (item.isClearable()) result.add(item);
        return result;
    }
    static List<StatusBarNotification> clearRequest(List<StatusBarNotification> selected, List<StatusBarNotification> snapshot) {
        LinkedHashMap<String, StatusBarNotification> request = new LinkedHashMap<>();
        for (StatusBarNotification item : clearable(selected)) request.put(item.getKey(), item);
        for (StatusBarNotification item : snapshot) if (summary(item) && item.isClearable()) {
            boolean hasChild = false, complete = true;
            for (StatusBarNotification child : snapshot) if (!summary(child) && systemGroup(child).equals(systemGroup(item))) {
                hasChild = true; if (!request.containsKey(child.getKey())) { complete = false; break; }
            }
            if (hasChild && complete) request.put(item.getKey(), item);
        }
        return new ArrayList<>(request.values());
    }
    /** A changed/replaced key and a newly arrived child must survive an older clear request. */
    static List<String> clearKeys(List<StatusBarNotification> requested, List<StatusBarNotification> active) {
        LinkedHashMap<String, StatusBarNotification> current = new LinkedHashMap<>();
        for (StatusBarNotification item : active) if (item.isClearable()) current.put(item.getKey(), item);
        Set<String> keys = new java.util.LinkedHashSet<>();
        for (StatusBarNotification before : requested) {
            StatusBarNotification now = current.get(before.getKey());
            if (before.isClearable() && now != null && before.getUid() == now.getUid() && before.getPostTime() == now.getPostTime()) keys.add(now.getKey());
        }
        for (StatusBarNotification item : active) if (summary(item) && keys.contains(item.getKey())) {
            for (StatusBarNotification child : active) if (!summary(child) && systemGroup(child).equals(systemGroup(item)) && !keys.contains(child.getKey())) { keys.remove(item.getKey()); break; }
        }
        return new ArrayList<>(keys);
    }
    static Intent settingsIntent(StatusBarNotification item) {
        return new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, item.getPackageName());
    }
    static long timestamp(StatusBarNotification item, long now) {
        long when = item.getNotification().when;
        return when > 0 && when <= now ? when : item.getPostTime();
    }
}
