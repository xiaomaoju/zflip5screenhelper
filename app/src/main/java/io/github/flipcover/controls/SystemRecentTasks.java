package io.github.flipcover.controls;

import android.app.KeyguardManager;
import android.app.ActivityOptions;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.hardware.HardwareBuffer;
import android.graphics.Bitmap;
import android.graphics.ColorSpace;
import android.os.Bundle;
import android.os.Process;
import android.view.Display;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Android 16 task API adapter. Executed only behind ShellService's owning-UID check. */
final class SystemRecentTasks {
    private final Context context;
    private final int userId;
    private final Object service;
    private final Class<?> contract;
    private boolean complete;
    SystemRecentTasks(Context context, int ownerUid) throws Exception {
        if (context == null || ownerUid < 0) throw new SecurityException("Missing application owner");
        this.context = context; userId = ownerUid / 100000;
        requirePermission("android.permission.REAL_GET_TASKS");
        service = Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
        contract = Class.forName("android.app.IActivityTaskManager");
    }
    private void requirePermission(String permission) {
        if (context.checkPermission(permission, Process.myPid(), Process.myUid()) != PackageManager.PERMISSION_GRANTED) throw new SecurityException("Shizuku lacks " + permission);
    }
    private boolean available(String permission, String method, Class<?>... parameters) {
        try { requirePermission(permission); contract.getMethod(method, parameters); return true; }
        catch (Exception error) { return false; }
    }
    private RecentTasks.Task requested(int display, JSONObject item) throws Exception {
        ComponentName component = ComponentName.unflattenFromString(item.getString("component"));
        int id = item.getInt("id");
        if (component == null || id < 0 || item.getInt("userId") != userId || item.getInt("displayId") != display) throw new IllegalArgumentException("任务身份无效");
        return new RecentTasks.Task(id, display, userId, component.flattenToString(), component.getPackageName(), false);
    }
    private RecentTasks.Task requested(int display, String request) throws Exception {
        if (request == null || request.length() > 2048) throw new IllegalArgumentException("任务请求无效");
        return requested(display, new JSONObject(request));
    }
    private void checkDisplay(int id) {
        if (id <= 0) throw new IllegalArgumentException("A secondary display is required");
        Display display = context.getSystemService(DisplayManager.class).getDisplay(id);
        if (display == null || !display.isValid() || display.getState() != Display.STATE_ON || context.getSystemService(KeyguardManager.class).isKeyguardLocked()) throw new IllegalStateException("目标外屏已改变、息屏或锁定");
    }
    List<RecentTasks.Task> read(int display) throws Exception {
        checkDisplay(display);
        Object slice = contract.getMethod("getRecentTasks", int.class, int.class, int.class).invoke(service, 100, 0, userId);
        List<?> source = (List<?>) Class.forName("android.content.pm.ParceledListSlice").getMethod("getList").invoke(slice);
        complete = source.size() < 100;
        List<RecentTasks.Task> result = new ArrayList<>();
        for (Object object : source) {
            TaskInfo task = (TaskInfo) object;
            int taskDisplay = TaskInfo.class.getField("displayId").getInt(task), taskUser = TaskInfo.class.getField("userId").getInt(task);
            if (taskDisplay != display || taskUser != userId || task.taskId < 0) continue;
            int type = (int) TaskInfo.class.getMethod("getActivityType").invoke(task);
            if (type != 0 && type != 1) continue; // Never expose launcher, recents or assistant tasks.
            ComponentName component = task.baseIntent == null ? null : task.baseIntent.getComponent();
            if (component == null || component.getPackageName().equals(context.getPackageName()) || component.getPackageName().equals("com.android.systemui")) continue;
            boolean visible = TaskInfo.class.getField("isVisible").getBoolean(task);
            result.add(new RecentTasks.Task(task.taskId, taskDisplay, taskUser, component.flattenToString(), component.getPackageName(), visible));
            if (result.size() == 32) { complete = false; break; }
        }
        return result;
    }
    JSONObject execute(int display, String request, boolean clear) throws Exception {
        return execute(display, request, clear, false);
    }
    JSONObject execute(int display, String request, boolean clear, boolean explicit) throws Exception {
        List<RecentTasks.Task> before = read(display), targets = new ArrayList<>(), accepted = new ArrayList<>();
        int requestedCount = 0;
        if (clear) {
            requirePermission("android.permission.REMOVE_TASKS");
            if (request == null || request.length() > 16384) throw new IllegalArgumentException("清理请求过大");
            JSONArray items = new JSONArray(request);
            if (items.length() < 1 || items.length() > 32) throw new IllegalArgumentException("清理数量无效");
            requestedCount = items.length();
            Set<Integer> ids = new HashSet<>();
            for (int i = 0; i < items.length(); i++) {
                RecentTasks.Task expected = requested(display, items.getJSONObject(i));
                if (!ids.add(expected.id())) throw new IllegalArgumentException("重复任务");
                if (before.stream().anyMatch(task -> RecentTasks.canDismiss(expected, task, explicit))) targets.add(expected);
            }
            Method remove = contract.getMethod("removeTask", int.class);
            for (RecentTasks.Task expected : targets) {
                // Re-read just before every mutation: task IDs can be reused or moved to another display.
                List<RecentTasks.Task> current = read(display);
                if (current.stream().anyMatch(task -> RecentTasks.canDismiss(expected, task, explicit)) && Boolean.TRUE.equals(remove.invoke(service, expected.id()))) accepted.add(expected);
            }
        }
        List<RecentTasks.Task> after = clear ? read(display) : before;
        int removed = 0;
        for (RecentTasks.Task expected : accepted) if (after.stream().noneMatch(task -> RecentTasks.sameTask(expected, task))) removed++;
        JSONArray rows = new JSONArray(); for (RecentTasks.Task task : after) rows.put(json(task));
        return new JSONObject().put("tasks", rows).put("complete", complete).put("removed", removed).put("retained", requestedCount - removed)
            .put("canOpen", available("android.permission.START_TASKS_FROM_RECENTS", "startActivityFromRecents", int.class, Bundle.class))
            .put("canClear", available("android.permission.REMOVE_TASKS", "removeTask", int.class))
            .put("canSnapshot", available("android.permission.READ_FRAME_BUFFER", "getTaskSnapshot", int.class, boolean.class) || available("android.permission.READ_FRAME_BUFFER", "getTaskSnapshot", int.class, boolean.class, boolean.class));
    }
    JSONObject open(int display, String request) throws Exception {
        RecentTasks.Task expected = requested(display, request);
        requirePermission("android.permission.START_TASKS_FROM_RECENTS");
        Method start = contract.getMethod("startActivityFromRecents", int.class, Bundle.class);
        if (read(display).stream().noneMatch(task -> RecentTasks.sameTask(expected, task))) return new JSONObject().put("state", "gone");
        // Restore this exact task. Never launch its MAIN activity or migrate a task from another display.
        int result = (int) start.invoke(service, expected.id(), ActivityOptions.makeBasic().setLaunchDisplayId(display).toBundle());
        if (result < 0) throw new IllegalStateException("系统拒绝恢复任务：" + result);
        for (int attempt = 0; attempt < 6; attempt++) {
            if (read(display).stream().anyMatch(task -> RecentTasks.sameTask(expected, task) && task.visible())) return new JSONObject().put("state", "opened");
            if (attempt < 5) Thread.sleep(100); // Bounded verification on the Shizuku worker, never the UI thread.
        }
        return new JSONObject().put("state", "unconfirmed");
    }
    Bundle snapshot(int display, String request) throws Exception {
        RecentTasks.Task expected = requested(display, request);
        requirePermission("android.permission.READ_FRAME_BUFFER");
        RecentTasks.Task current = read(display).stream().filter(task -> RecentTasks.sameTask(expected, task)).findFirst().orElseThrow(() -> new IllegalStateException("任务已改变"));
        Object snapshot = null;
        // One on-demand foreground snapshot. updateCache=true preserves the system's app-theme/secure rules.
        if (current.visible() && available("android.permission.READ_FRAME_BUFFER", "takeTaskSnapshot", int.class, boolean.class)) snapshot = contract.getMethod("takeTaskSnapshot", int.class, boolean.class).invoke(service, expected.id(), true);
        if (snapshot == null) snapshot = cachedSnapshot(expected.id(), true);
        if (snapshot == null) snapshot = cachedSnapshot(expected.id(), false);
        Bundle result = new Bundle(); result.putString("message", "暂无可用预览"); result.putBoolean("retryable", snapshot == null);
        if (snapshot == null) return result;
        Class<?> type = Class.forName("android.window.TaskSnapshot");
        HardwareBuffer buffer = (HardwareBuffer) type.getMethod("getHardwareBuffer").invoke(snapshot);
        Bitmap wrapped = null, scaled = null, copy = null;
        try {
            ComponentName top = (ComponentName) type.getMethod("getTopActivityComponent").invoke(snapshot);
            if (!Boolean.TRUE.equals(type.getMethod("isRealSnapshot").invoke(snapshot)) || top == null || !expected.packageName().equals(top.getPackageName()) || buffer == null) { result.putString("message", "系统未提供可用画面"); return result; }
            if (buffer.getWidth() < 1 || buffer.getHeight() < 1 || (long) buffer.getWidth() * buffer.getHeight() > 8_000_000) return result;
            wrapped = Bitmap.wrapHardwareBuffer(buffer, (ColorSpace) type.getMethod("getColorSpace").invoke(snapshot));
            if (wrapped == null) return result;
            float scale = Math.min(1f, 256f / Math.max(wrapped.getWidth(), wrapped.getHeight()));
            scaled = Bitmap.createScaledBitmap(wrapped, Math.max(1, Math.round(wrapped.getWidth() * scale)), Math.max(1, Math.round(wrapped.getHeight() * scale)), true);
            copy = scaled.copy(Bitmap.Config.ARGB_8888, false);
            // Revalidate after the potentially slow snapshot read. Secure/system theme snapshots stay placeholders.
            if (read(display).stream().noneMatch(task -> RecentTasks.sameTask(expected, task))) return result;
            result.putParcelable("bitmap", copy); copy = null; result.putString("message", "上次画面");
            return result;
        } finally {
            if (copy != null) copy.recycle();
            if (scaled != null && scaled != wrapped) scaled.recycle();
            if (wrapped != null) wrapped.recycle();
            if (buffer != null) buffer.close();
        }
    }
    private Object cachedSnapshot(int taskId, boolean lowResolution) throws Exception {
        Method method;
        try { method = contract.getMethod("getTaskSnapshot", int.class, boolean.class); }
        catch (NoSuchMethodException oldContract) { return contract.getMethod("getTaskSnapshot", int.class, boolean.class, boolean.class).invoke(service, taskId, lowResolution, false); }
        return method.invoke(service, taskId, lowResolution);
    }
    static JSONObject json(RecentTasks.Task task) throws org.json.JSONException {
        return new JSONObject().put("id", task.id()).put("displayId", task.displayId()).put("userId", task.userId()).put("component", task.component()).put("visible", task.visible());
    }
}
