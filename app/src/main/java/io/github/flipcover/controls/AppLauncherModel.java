package io.github.flipcover.controls;

import android.content.ComponentName;
import android.service.notification.StatusBarNotification;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Shared launcher rules; neither renderer owns a second app/folder configuration. */
final class AppLauncherModel {
    static final String EDIT_HINT = "如需编辑，请到浮窗启动器内操作。";
    static long itemId(String id) { long hash = 0xcbf29ce484222325L; for (int i = 0; i < id.length(); i++) { hash ^= id.charAt(i); hash *= 0x100000001b3L; } return hash; }
    static List<AppCatalogCache.Entry> select(List<AppCatalogCache.Entry> catalog, List<String> pins, JSONObject aliases, String query, String order, List<RecentTasks.Task> tasks, AppSearchIndex index) {
        List<AppCatalogCache.Entry> result = new ArrayList<>();
        for (AppCatalogCache.Entry app : catalog) if ((!query.isEmpty() || !pins.contains(app.id())) && index.matches(app, aliases.optString(app.id(), ""), query)) result.add(app);
        if (order.equals("reverse")) Collections.reverse(result);
        else if (order.equals("recent")) { Map<String, Integer> ranks = new HashMap<>(); for (RecentTasks.Task task : tasks) ranks.putIfAbsent(task.packageName(), ranks.size()); result.sort((a, b) -> Integer.compare(ranks.getOrDefault(a.packageName(), Integer.MAX_VALUE), ranks.getOrDefault(b.packageName(), Integer.MAX_VALUE))); }
        return List.copyOf(result);
    }
    static AppWorkspaceLayout reconcile(AppWorkspaceLayout saved, List<AppCatalogCache.Entry> catalog, List<String> pins, boolean compact) {
        List<String> available = new ArrayList<>(); for (AppCatalogCache.Entry app : catalog) if (!pins.contains(app.id())) available.add(app.id());
        return saved.reconcile(available, compact);
    }
    static String label(String id, JSONObject aliases, AppCatalogCache cache) {
        String alias = aliases.optString(id, ""); if (!alias.isEmpty()) return alias;
        String cached = cache.label(id); if (cached != null) return cached;
        for (ActionCatalog.Action action : ActionCatalog.BUILT_INS) if (action.id().equals(id)) return action.label();
        ComponentName name = ActionCatalog.component(id); return name == null ? "应用" : name.getPackageName();
    }
    static Map<String, Integer> badges(boolean enabled, boolean ready, List<StatusBarNotification> notifications) {
        Map<String, Integer> result = new HashMap<>();
        if (enabled && ready) for (StatusBarNotification item : notifications) if (item.getUser().equals(android.os.Process.myUserHandle()) && !item.isOngoing() && (item.getNotification().flags & android.app.Notification.FLAG_GROUP_SUMMARY) == 0) result.merge(item.getPackageName(), 1, Integer::sum);
        return result;
    }
    static int badge(String id, Map<String, Integer> badges) { ComponentName name = ActionCatalog.component(id); return name == null ? 0 : badges.getOrDefault(name.getPackageName(), 0); }
}
