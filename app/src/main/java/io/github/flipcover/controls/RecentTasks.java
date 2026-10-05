package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** In-memory task identity and selection policy, shared by the UI and privileged boundary. */
final class RecentTasks {
    static final int DOCK_APP_LIMIT = 4;
    record Task(int id, int displayId, int userId, String component, String packageName, boolean visible) { }
    static boolean sameTask(Task expected, Task current) {
        return expected.id == current.id && expected.displayId > 0 && expected.displayId == current.displayId
            && expected.userId == current.userId && expected.component.equals(current.component) && expected.packageName.equals(current.packageName);
    }
    static boolean canClear(Task expected, Task current) { return sameTask(expected, current) && !current.visible; }
    static boolean canDismiss(Task expected, Task current, boolean explicit) { return sameTask(expected, current) && (explicit || !current.visible); }
    static String key(Task task) { return task.displayId + ":" + task.userId + ":" + task.id + ":" + task.component; }
    /** The task page represents windows, including multiple windows of one application. */
    static List<Task> backgroundTargets(List<Task> tasks, Set<String> pinnedPackages) {
        List<Task> result = new ArrayList<>();
        for (Task task : tasks) if (!task.visible && !pinnedPackages.contains(task.packageName)) result.add(task);
        return List.copyOf(result);
    }
    /** Process-only protection, shared by the task page, recent Dock and operation boundary. */
    static final class Locks {
        private final java.util.Map<String, Task> locked = new java.util.HashMap<>();
        boolean contains(Task task) { return locked.containsKey(key(task)); }
        boolean toggle(Task task) {
            String key = key(task);
            if (locked.remove(key) != null) return true;
            if (locked.size() >= 32) return false;
            locked.put(key, task); return true;
        }
        List<Task> unlocked(List<Task> tasks) {
            List<Task> result = new ArrayList<>(); for (Task task : tasks) if (!contains(task)) result.add(task); return List.copyOf(result);
        }
        void reconcile(int display, int user, List<Task> current, boolean complete) {
            locked.values().removeIf(old -> old.displayId == display && old.userId == user
                && current.stream().noneMatch(task -> sameTask(old, task))
                && (complete || current.stream().anyMatch(task -> task.id == old.id)));
        }
        void clear() { locked.clear(); }
    }
    static List<Task> apps(List<Task> tasks, Set<String> pinnedPackages) {
        List<Task> result = new ArrayList<>(); Set<String> seen = new HashSet<>(pinnedPackages);
        for (Task task : tasks) if (seen.add(task.packageName)) { result.add(task); if (result.size() == DOCK_APP_LIMIT) break; }
        return result;
    }
    /** Clear only the non-visible tasks represented by the displayed recent applications. */
    static List<Task> clearTargets(List<Task> tasks, Set<String> pinnedPackages) {
        Set<String> shown = new HashSet<>(); for (Task task : apps(tasks, pinnedPackages)) shown.add(task.packageName);
        List<Task> result = new ArrayList<>();
        for (Task task : tasks) if (!task.visible && shown.contains(task.packageName)) result.add(task);
        return result;
    }
    static int columns(float availableDp) { return Math.max(1, Math.min(5, (int) (availableDp / 38))); }
    static int dockCell(int availablePixels, int count, int preferredPixels, int chromePixels) {
        return count == 0 ? preferredPixels : Math.max(1, Math.min(preferredPixels, (availablePixels - chromePixels) / count));
    }
}
