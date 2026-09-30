package io.github.flipcover.controls;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Shared Dock capacity and clear selection; floating and native renderers only place the cells. */
final class AppDockLayout {
    record Geometry(int recentCount, int cell, int separator, int margin, int chrome) { }
    static Geometry fit(int width, int pins, int recent, float density) {
        int fixed = 2 * Math.round(AppLauncherStyle.DOCK_EDGE * density) + Math.round(AppLauncherStyle.DOCK_TOOL_WIDTH * density);
        int separator = Math.max(1, Math.round(AppLauncherStyle.DOCK_SEPARATOR_WIDTH * density)), margin = Math.round(AppLauncherStyle.DOCK_SEPARATOR_MARGIN * density);
        int recentChrome = recent == 0 ? 0 : Math.round(AppLauncherStyle.DOCK_TOOL_WIDTH * density) + (pins == 0 ? 0 : separator + 2 * margin);
        int capacity = Math.max(0, (width - fixed - recentChrome) / Math.max(1, Math.round(AppLauncherStyle.DOCK_MIN_CELL * density)) - pins);
        int shown = Math.min(Math.min(RecentTasks.DOCK_APP_LIMIT, recent), capacity);
        int chrome = fixed + (pins > 0 && shown > 0 ? separator + 2 * margin : 0) + (shown == 0 ? 0 : Math.round(AppLauncherStyle.DOCK_TOOL_WIDTH * density));
        return new Geometry(shown, RecentTasks.dockCell(width, pins + shown, Math.round(AppLauncherStyle.DOCK_ROW * density), chrome), separator, margin, chrome);
    }
    static Set<String> packages(List<String> pins) { Set<String> packages = new HashSet<>(); for (String id : pins) { android.content.ComponentName component = ActionCatalog.component(id); if (component != null) packages.add(component.getPackageName()); } return packages; }
    static List<RecentTasks.Task> clearTargets(List<RecentTasks.Task> tasks, List<String> pins, List<RecentTasks.Task> shown, RecentTasks.Locks locks) {
        Set<String> visibleApps = new HashSet<>(); for (RecentTasks.Task task : shown) visibleApps.add(task.packageName());
        List<RecentTasks.Task> result = new ArrayList<>();
        for (RecentTasks.Task task : RecentTasks.backgroundTargets(tasks, packages(pins))) if (visibleApps.contains(task.packageName())) result.add(task);
        return locks.unlocked(result);
    }
}
