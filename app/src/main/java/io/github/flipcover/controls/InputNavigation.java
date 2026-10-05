package io.github.flipcover.controls;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

/** Logical targets belong to the existing page; no business action is duplicated here. */
final class InputNavigation {
    enum Region { LIST, HEADER, APPS, SIDEBAR, PAGE, DOCK, CONTROL, TOOLS, NOTICE, TASK, CLEAR, WIDGET }
    record Binding(String key, Region region, Runnable primary, Runnable more) { }
    record Position(String key, int index, boolean more) { }
    static final class Memory {
        final LinkedHashMap<String, Position> positions = new LinkedHashMap<>();
        int shortcut;
        boolean touch;
        android.os.Bundle state() {
            android.os.Bundle out = new android.os.Bundle(); out.putInt("shortcut", shortcut); out.putBoolean("touch", touch);
            android.os.Bundle entries = new android.os.Bundle(); positions.forEach((key, value) -> { android.os.Bundle item = new android.os.Bundle(); item.putString("key", value.key()); item.putInt("index", value.index()); item.putBoolean("more", value.more()); entries.putBundle(key, item); }); out.putBundle("positions", entries); return out;
        }
        void restore(android.os.Bundle state) {
            if (state == null) return; shortcut = state.getInt("shortcut"); touch = state.getBoolean("touch"); android.os.Bundle entries = state.getBundle("positions");
            if (entries != null) for (String key : entries.keySet()) { android.os.Bundle item = entries.getBundle(key); if (item != null) save(key, new Position(item.getString("key", ""), Math.max(0, item.getInt("index")), item.getBoolean("more"))); }
        }
        void save(String scope, Position position) { positions.remove(scope); positions.put(scope, position); while (positions.size() > 32) positions.remove(positions.keySet().iterator().next()); }
    }
    static final class Target {
        final View view; final String key; final Region region; final Runnable primary, more;
        Rect local;
        Target(View view, String key, Region region, Runnable primary, Runnable more) { this.view = view; this.key = key; this.region = region; this.primary = primary; this.more = more; }
        Rect bounds(View root) { if (parent(view, SettingsUi.Viewport.class) != null) return SettingsUi.Viewport.bounds(view, local, root); int[] p = new int[2], o = new int[2]; view.getLocationOnScreen(p); root.getLocationOnScreen(o); Rect r = local == null ? new Rect(0, 0, view.getWidth(), view.getHeight()) : new Rect(local); r.offset(p[0] - o[0], p[1] - o[1]); return r; }
    }
    static void bind(View view, String key, Region region, Runnable primary, Runnable more) { view.setTag(R.id.input_target, new Binding(key, region, primary, more)); }
    static void exclude(View view) { view.setTag(R.id.input_excluded, true); }
    static boolean excluded(View view) { return Boolean.TRUE.equals(view.getTag(R.id.input_excluded)); }
    static <T> T parent(View view, Class<T> type) { for (View v = view; v != null; v = v.getParent() instanceof View p ? p : null) if (type.isInstance(v)) return type.cast(v); return null; }
    static List<Target> targets(View scope) { List<Target> result = new ArrayList<>(); collect(scope, result, "root"); return result; }
    private static void collect(View view, List<Target> out, String path) {
        if (!view.isShown() || !view.isEnabled() || excluded(view) || view.getWidth() <= 0 || view.getHeight() <= 0) return;
        if (view instanceof ControlEditorView || view instanceof WidgetGridEditor) return;
        if (view instanceof AppWorkspaceView workspace) { if (!workspace.editing()) out.addAll(workspace.inputTargets()); return; }
        Binding binding = view.getTag(R.id.input_target) instanceof Binding value ? value : null;
        if (binding != null) { if (visible(view)) out.add(new Target(view, binding.key, binding.region, binding.primary, binding.more)); return; }
        int before = out.size();
        if (view instanceof ViewGroup group && !(view instanceof EditText) && !(view instanceof OriginalLiquidTabs)) for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out, path + "/" + i);
        if (view.getId() == R.id.launcher_edit || "control-edit".equals(view.getTag())) return;
        boolean interactive = view.isClickable() || view instanceof EditText || view instanceof android.widget.AbsSeekBar || view instanceof OriginalLiquidTabs || view.getParent() instanceof android.widget.AdapterView;
        if (view.getId() == R.id.launcher_grid || view.getId() == R.id.launcher_pager || "hub-dock-area".equals(view.getTag())) interactive = false;
        // Clickable rows own nested switches and labels; never offer the same action twice.
        if (out.size() == before && interactive && !(view instanceof DetailSheet) && !(view instanceof InputSurface) && !(view instanceof android.widget.AdapterView) && !String.valueOf(view.getTag()).endsWith("backdrop") && !"detail-card".equals(view.getTag())) {
            if (visible(view)) out.add(new Target(view, key(view, path), region(view), null, null)); return;
        }
    }
    static String key(View view, String path) {
        if (view.getTag() != null) return view.getTag().toString();
        if (view.getContentDescription() != null) return "label:" + view.getContentDescription() + ":" + path;
        if (view.getId() != View.NO_ID) return "id:" + view.getId() + ":" + path;
        if (view instanceof android.widget.TextView text && text.length() > 0) return "text:" + text.getText() + ":" + path;
        return path;
    }
    private static Region region(View view) {
        if ("control-refresh".equals(view.getTag()) || "settings-back".equals(view.getTag())) return Region.HEADER;
        for (View v = view; v != null; v = v.getParent() instanceof View p ? p : null) {
            if ("detail-header".equals(v.getTag())) return Region.HEADER;
            if (v.getId() == R.id.launcher_grid) return Region.APPS;
            if (v.getId() == R.id.launcher_rail) return Region.SIDEBAR;
            if (v.getId() == R.id.launcher_dock_row) return Region.DOCK;
            if (v.getId() == R.id.launcher_pager) return Region.PAGE;
            if (v.getId() == R.id.launcher_header) return Region.HEADER;
        }
        if (parent(view, AppDockView.class) != null) return Region.DOCK;
        if (parent(view, LauncherSidebarView.class) != null) return Region.SIDEBAR;
        if (parent(view, AppFolderGrid.class) != null) return Region.APPS;
        if (parent(view, AppHubView.class) != null) return Region.HEADER;
        if (parent(view, NotificationCenterView.class) != null) return Region.HEADER;
        for (View v = view; v != null; v = v.getParent() instanceof View p ? p : null) if ("control-dashboard".equals(v.getTag())) return Region.TOOLS;
        return Region.LIST;
    }
    private static boolean visible(View view) {
        if (view.getGlobalVisibleRect(new Rect())) return true;
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) if (p instanceof ScrollView) return true;
        return false;
    }
    static Target first(List<Target> items) {
        for (Target item : items) if (item.region == Region.TASK) { RecentTasksView tasks = parent(item.view, RecentTasksView.class); if (tasks != null && tasks.selectedTask() != null && item.key.equals("task:" + RecentTasks.key(tasks.selectedTask()))) return item; }
        for (Region region : new Region[]{Region.APPS, Region.CONTROL, Region.NOTICE, Region.TASK, Region.WIDGET, Region.LIST, Region.DOCK}) for (Target item : items) if (item.region == region && !item.key.equals("settings-back")) return item;
        return items.isEmpty() ? null : items.get(0);
    }
    static Target find(List<Target> items, String key) { for (Target item : items) if (item.key.equals(key)) return item; return null; }
    static List<Target> contents(ViewGroup group) { List<Target> out = new ArrayList<>(); if (group.isClickable() && group.isEnabled()) out.add(new Target(group, "component:root-action", Region.LIST, group::performClick, null)); for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), out, "component/" + i); return out; }
    static Target closest(View root, List<Target> items, Target from, int direction, Region region) {
        Rect a = from.bounds(root); Target best = null; double score = Double.MAX_VALUE;
        for (Target item : items) {
            if (item.key.equals(from.key) || region != null && item.region != region) continue;
            Rect b = item.bounds(root); float dx = b.exactCenterX() - a.exactCenterX(), dy = b.exactCenterY() - a.exactCenterY();
            boolean horizontal = direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT;
            float forward = direction == View.FOCUS_LEFT ? -dx : direction == View.FOCUS_RIGHT ? dx : direction == View.FOCUS_UP ? -dy : dy;
            if (forward <= 1) continue;
            boolean beam = horizontal ? a.top < b.bottom && a.bottom > b.top : a.left < b.right && a.right > b.left;
            // Within named grids there is no diagonal jump at a row/column boundary.
            if (!beam && (region == Region.APPS || region == Region.CONTROL || region == Region.DOCK || region == Region.PAGE)) continue;
            double candidate = (beam ? 0 : 100000) + forward + (horizontal ? Math.abs(dy) : Math.abs(dx)) * 3;
            if (candidate < score) { score = candidate; best = item; }
        }
        return best;
    }
    private static Target region(List<Target> items, Region region, boolean last) { Target result = null; for (Target item : items) if (item.region == region) { result = item; if (!last) break; } return result; }
    static Target move(View root, List<Target> items, Target from, int direction) {
        if (from.region == Region.NOTICE) {
            if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) return from;
            List<Target> rows = items.stream().filter(t -> t.region == Region.NOTICE).sorted(Comparator.comparingInt(t -> t.bounds(root).top)).collect(java.util.stream.Collectors.toList());
            int next = rows.indexOf(from) + (direction == View.FOCUS_UP ? -1 : 1);
            return next >= 0 && next < rows.size() ? rows.get(next) : direction == View.FOCUS_UP ? region(items, Region.HEADER, true) : null;
        }
        if (from.region == Region.PAGE) {
            if (from.view instanceof AppWorkspaceView workspace && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) { workspace.inputPage(direction == View.FOCUS_LEFT ? -1 : 1); return from; }
            if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) { Target next = closest(root, items, from, direction, Region.PAGE); return next == null ? from : next; }
            return direction == View.FOCUS_UP ? region(items, Region.APPS, true) : direction == View.FOCUS_DOWN ? region(items, Region.DOCK, false) : from;
        }
        Target next = closest(root, items, from, direction, from.region);
        if (next != null) return next;
        if (from.region == Region.APPS) for (View v = from.view; v != null; v = v.getParent() instanceof View p ? p : null) if (v instanceof AppFolderGrid || "input-folder-grid".equals(v.getTag())) return from;
        if (from.region == Region.APPS) {
            if (direction == View.FOCUS_UP) return region(items, Region.HEADER, false);
            if (direction == View.FOCUS_DOWN) { Target page = region(items, Region.PAGE, false); return page != null ? page : region(items, Region.DOCK, false); }
            return closest(root, items, from, direction, Region.SIDEBAR);
        }
        if (from.region == Region.SIDEBAR && (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT)) return closest(root, items, from, direction, Region.APPS);
        if (from.region == Region.DOCK && direction == View.FOCUS_UP) { Target page = region(items, Region.PAGE, false); return page != null ? page : region(items, Region.APPS, true); }
        if (from.region == Region.HEADER && direction == View.FOCUS_DOWN) { Target main = first(items); return main == from ? null : main; }
        if (from.region == Region.CONTROL || from.region == Region.TOOLS) {
            next = closest(root, items, from, direction, from.region == Region.CONTROL ? Region.TOOLS : Region.CONTROL);
            return next != null ? next : direction == View.FOCUS_UP ? region(items, Region.HEADER, true) : null;
        }
        return closest(root, items, from, direction, null);
    }
}
