package io.github.flipcover.controls;

import android.content.ComponentName;
import android.content.Context;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;

/** Shared task request and response boundary. All operations retain the selected display identity. */
final class AppRecentTasks {
    record Snapshot(List<RecentTasks.Task> tasks, boolean canOpen, boolean canClear, boolean canSnapshot, boolean complete) { }
    record Response(Snapshot snapshot, String message, boolean unchanged) {
        Response(Snapshot snapshot, String message) { this(snapshot, message, false); }
        boolean ok() { return snapshot != null; }
    }
    static RecentTasks.Task task(JSONObject item, int display) throws Exception {
        ComponentName component = ComponentName.unflattenFromString(item.getString("component"));
        if (component == null || item.getInt("displayId") != display || display <= 0 || item.getInt("userId") != android.os.Process.myUid() / 100000 || item.getInt("id") < 0) throw new IllegalArgumentException("任务身份不匹配");
        return new RecentTasks.Task(item.getInt("id"), display, item.getInt("userId"), component.flattenToString(), component.getPackageName(), item.optBoolean("visible"));
    }
    static List<RecentTasks.Task> tasks(JSONArray source, int display) throws Exception {
        if (source.length() > 32) throw new IllegalArgumentException("任务数量过多"); List<RecentTasks.Task> tasks = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>(); for (int i = 0; i < source.length(); i++) { RecentTasks.Task task = task(source.getJSONObject(i), display); if (!seen.add(RecentTasks.key(task))) throw new IllegalArgumentException("重复任务"); tasks.add(task); } return List.copyOf(tasks);
    }
    static String encode(List<RecentTasks.Task> tasks) throws Exception { JSONArray rows = new JSONArray(); for (RecentTasks.Task task : tasks) rows.put(SystemRecentTasks.json(task)); return rows.toString(); }
    static Snapshot decode(Context context, int display, String output) throws Exception {
        JSONObject payload = new JSONObject(output); List<RecentTasks.Task> tasks = tasks(payload.getJSONArray("tasks"), display);
        boolean complete = payload.optBoolean("complete"); CoverApp.taskLocks(context).reconcile(display, android.os.Process.myUid() / 100000, tasks, complete);
        return new Snapshot(tasks, payload.optBoolean("canOpen"), payload.optBoolean("canClear"), payload.optBoolean("canSnapshot"), complete);
    }
    static void request(Context context, Prefs prefs, int display, List<RecentTasks.Task> clearing, BooleanSupplier active, Consumer<Response> callback) {
        request(context, prefs, display, clearing, false, active, callback);
    }
    static void dismiss(Context context, Prefs prefs, RecentTasks.Task task, BooleanSupplier active, Consumer<Response> callback) {
        request(context, prefs, task.displayId(), List.of(task), true, active, callback);
    }
    private static void request(Context context, Prefs prefs, int display, List<RecentTasks.Task> clearing, boolean explicitTask, BooleanSupplier active, Consumer<Response> callback) {
        if (!active.getAsBoolean() || !AppLauncher.ready(context, prefs, display)) { callback.accept(new Response(null, "所选外屏不可用，请解锁后重试")); return; }
        String request = "";
        if (clearing != null) {
            clearing = clearTargets(context, prefs, clearing, explicitTask);
            if (clearing.isEmpty()) { callback.accept(new Response(null, "没有可清理的后台任务", true)); return; }
            try { request = encode(clearing); } catch (Exception error) { callback.accept(new Response(null, "任务数据无效")); return; }
        }
        boolean clean = clearing != null;
        CoverApp.bridge(context).run(clean ? "recent_clear" : "recent_tasks", display, 0, request, result -> {
            if (!active.getAsBoolean() || !AppLauncher.ready(context, prefs, display)) { callback.accept(new Response(null, "外屏状态已改变")); return; }
            if (!result.ok) { callback.accept(new Response(null, result.message)); return; }
            Snapshot snapshot;
            try { snapshot = decode(context, display, result.output); }
            catch (Exception error) { callback.accept(new Response(null, "系统任务返回格式不兼容")); return; }
            callback.accept(new Response(snapshot, clean ? result.message : null));
        });
    }
    static List<RecentTasks.Task> clearTargets(Context context, Prefs prefs, List<RecentTasks.Task> tasks, boolean explicitTask) {
        return CoverApp.taskLocks(context).unlocked(RecentTasks.backgroundTargets(tasks, explicitTask ? java.util.Set.of() : AppDockLayout.packages(prefs.hubPins())));
    }
    static void open(Context context, Prefs prefs, RecentTasks.Task task, BooleanSupplier active, ShizukuBridge.Callback callback) {
        if (!active.getAsBoolean() || !AppLauncher.ready(context, prefs, task.displayId())) { callback.accept(new ShizukuBridge.Result(false, "所选外屏不可用，请解锁后重试", "")); return; }
        try { CoverApp.bridge(context).run("recent_open", task.displayId(), 0, SystemRecentTasks.json(task).toString(), result -> {
            if (!active.getAsBoolean() || !AppLauncher.ready(context, prefs, task.displayId())) { callback.accept(new ShizukuBridge.Result(false, "外屏状态已改变", "")); return; }
            callback.accept(result);
        }); }
        catch (Exception error) { callback.accept(new ShizukuBridge.Result(false, "任务数据无效", "")); }
    }
}
