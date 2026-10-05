package io.github.flipcover.controls;

import android.content.ComponentCallbacks2;
import android.content.Context;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A continuous strip of task previews, sized three across, with a centered clear action. */
final class RecentTasksView extends LinearLayout implements ComponentCallbacks2 {
    interface Listener {
        void open(RecentTasks.Task task); void clear(List<RecentTasks.Task> tasks); void refresh();
        default void closeTask(RecentTasks.Task task) { clear(List.of(task)); }
        void apps(); void close(); void reopen(RecentTasks.Task task);
        default void empty() { close(); }
        default void beginMotion() { }
        default float currentMotion() { return Float.NaN; }
        default boolean motion(float progress) { return false; }
        default boolean restoreMotion() { return false; }
        void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback);
    }
    private static final int LOCK_ACTION = R.id.action_task_lock;
    private final Listener listener;
    private final Prefs prefs;
    private final AppCatalogCache cache;
    private final RecentTasks.Locks locks;
    private final Carousel carousel;
    private final View clear;
    private final PanelSurface surface;
    private final Map<String, ShizukuBridge.Snapshot> previews = new HashMap<>();
    private final Map<String, ShizukuBridge.Snapshot> pendingPreviews = new HashMap<>();
    private final Map<String, Integer> attempted = new HashMap<>();
    private final Set<String> retryPreviews = new HashSet<>();
    private List<RecentTasks.Task> tasks = List.of(), deferred;
    private boolean known, complete, busy, canOpen, canClear, canSnapshot, snapshotPending, memoryTrimmed;
    private boolean closingPreviewsPaused;
    private int selected, firstVisible, generation;
    private RecentTasks.Task lostTask;
    private final Map<String, CloseOperation> closings = new java.util.LinkedHashMap<>();
    private CloseOperation submittedClose;
    private final Runnable submitClose = this::submitNextClose;
    private static final class CloseOperation {
        final RecentTasks.Task task;
        boolean sent, accepted, failed;
        final float[] visual = new float[6];
        float layout, layoutVelocity;
        CloseOperation(RecentTasks.Task task) { this.task = task; }
    }
    private float panelProgress = 1;
    private PanelGlassSession glass;
    private boolean glassReady;
    private boolean blankTap;
    private boolean emptyClosing;
    private boolean emptyAfterDismissal;
    private boolean exiting;
    private float exitProgress;
    private ValueAnimator exitAnimation;
    private final Runnable closeEmpty = this::closeIfEmpty;
    private void closeIfEmpty() {
        if (emptyAfterDismissal && !emptyClosing && known && complete && !working() && tasks.isEmpty() && deferred == null && isAttachedToWindow()) { emptyClosing = true; listener.empty(); }
    }
    private float blankX, blankY;
    private long blankDown;
    private final android.graphics.Rect hitBounds = new android.graphics.Rect();
    private String state = "正在读取外屏任务…";

    RecentTasksView(Context context, Prefs prefs, int edge, Listener listener) {
        super(context); this.listener = listener; this.prefs = prefs; cache = CoverApp.catalog(context); locks = CoverApp.taskLocks(context);
        setOrientation(VERTICAL); setTag("recent-tasks"); setBackgroundColor(0x3D000000); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        surface = new PanelSurface(context, edge, dp(240), prefs.haptics(), new PanelHeaderView.Listener() {
            public void begin() { animate().cancel(); listener.beginMotion(); }
            public float currentProgress() { float current = listener.currentMotion(); return Float.isNaN(current) ? panelProgress : current; }
            public void progress(float value) {
                panelProgress = value;
                if (!listener.motion(value)) InterfaceCard.translate(RecentTasksView.this, edge, (1 - value) * surface.dismissalExtent());
            }
            public void finish(boolean close) { if (close) listener.close(); else { panelProgress = 1; if (!listener.restoreMotion()) animate().translationX(0).translationY(0).setDuration(duration()).start(); } }
        }) { @Override protected float dismissalExtent() { return Math.max(1, InterfaceCard.horizontal(edge) ? RecentTasksView.this.getWidth() : RecentTasksView.this.getHeight()); } };
        addView(surface, new LayoutParams(-1, -1));
        clear = smallIcon(R.drawable.ic_ms_close, "关闭全部未锁定外屏任务并退出", () -> dismiss(clearTargets())); clear.setTag("tasks-clear");
        clear.setBackground(Ui.ripple(context, 0xF21C2632, 1000));
        InputNavigation.bind(clear, "tasks-clear", InputNavigation.Region.CLEAR, clear::performClick, () -> showPageMenu(clear));
        FrameLayout stage = new FrameLayout(context); stage.setTag("tasks-stage"); surface.addView(stage, new LayoutParams(-1, 0, 1));
        carousel = new Carousel(context); carousel.setTag("task-carousel"); stage.addView(carousel, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams clearBounds = new FrameLayout.LayoutParams(dp(PanelUi.SLOT), dp(PanelUi.SLOT), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL); clearBounds.bottomMargin = dp(PanelUi.INSET); stage.addView(new PanelActionSlot(clear), clearBounds);
        render();
    }
    private View smallIcon(int icon, String label, Runnable action) { View view = PanelUi.icon(getContext(), icon, label, action); view.setPadding(dp(8), dp(8), dp(8), dp(8)); view.setTooltipText(label); return view; }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            blankX = event.getRawX(); blankY = event.getRawY(); blankDown = event.getEventTime(); blankTap = true;
            View clearSlot = (View) clear.getParent(); if (hit(clearSlot, blankX, blankY)) blankTap = false;
            for (int i = 0; i < carousel.getChildCount(); i++) if (carousel.getChildAt(i) instanceof Card card && hit(card, blankX, blankY)) blankTap = false;
        }
        if (event.getPointerCount() > 1 || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN || Math.hypot(event.getRawX() - blankX, event.getRawY() - blankY) > ViewConfiguration.get(getContext()).getScaledTouchSlop()) blankTap = false;
        boolean dismiss = action == MotionEvent.ACTION_UP && blankTap && event.getEventTime() - blankDown < ViewConfiguration.getLongPressTimeout();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) blankTap = false;
        boolean handled = super.dispatchTouchEvent(event);
        if (dismiss) listener.close();
        return handled || dismiss;
    }
    private boolean hit(View view, float x, float y) { return view.getGlobalVisibleRect(hitBounds) && hitBounds.contains(Math.round(x), Math.round(y)); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { blankTap = false; super.onSizeChanged(w, h, oldw, oldh); }
    private boolean ownBackplate = true;
    void glass(PanelGlassSession session) {
        glass(session, true);
    }
    void glass(PanelGlassSession session, boolean backplate) {
        ownBackplate = backplate;
        boolean changed = glass != session;
        glass = session;
        if (session == null) setBackgroundColor(backplate ? 0x3D000000 : android.graphics.Color.TRANSPARENT);
        if (session != null && session.active()) {
            session.bind(this, backplate ? GlassSurface.Role.NOTIFICATION_PAGE : GlassSurface.Role.CLEAR);
            session.bind(clear, GlassSurface.Role.TASK_ACTION);
        }
        glassReady(session != null ? session.taskOptics() : !backplate && PanelGlassSession.allowed(getContext(), prefs)); if (changed) carousel.bindCards();
    }
    void glassReady(boolean ready) {
        if (glassReady == ready) return; glassReady = ready;
        for (int i = 0; i < carousel.getChildCount(); i++) if (carousel.getChildAt(i) instanceof Card card) card.picture.glass(ready);
    }
    void safeArea(DockGeometry.Box area, DockGeometry.Box frame) { surface.setPadding(area.x(), area.y(), Math.max(0, frame.width() - area.right()), Math.max(0, frame.height() - area.bottom())); }
    boolean backgroundStable() { return !exiting && !memoryTrimmed && !working() && !carousel.tracking && !carousel.blocked && !carousel.moving() && closings.isEmpty(); }
    void previewsVisible() { removeCallbacks(loadPreviews); post(loadPreviews); }
    private long sceneTime;
    private float sceneX, sceneY, sceneVelocityX, sceneVelocityY;
    void sceneMotion(float x, float y) {
        long now = System.nanoTime();
        if (sceneTime != 0 && now - sceneTime < 1_000_000) return;
        if (sceneTime != 0 && now - sceneTime >= 1_000_000 && now - sceneTime < 120_000_000) {
            float dt = (now - sceneTime) / 1_000_000_000f, vx = (x - sceneX) / dt, vy = (y - sceneY) / dt;
            carousel.force.sceneImpulse(vx - sceneVelocityX, vy - sceneVelocityY); sceneVelocityX = vx; sceneVelocityY = vy; carousel.wake();
        } else { sceneVelocityX = sceneVelocityY = 0; }
        sceneX = x; sceneY = y; sceneTime = now;
    }
    private final Runnable loadPreviews = this::requestPreviews;
    private void refresh() { if (!working()) { lostTask = null; releasePreviews(); listener.refresh(); } }
    private void message(String text) { android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT).show(); announceForAccessibility(text); }
    private void pageActions(InputPopupMenu menu) {
        if (lostTask != null) menu.add("重新打开：" + label(lostTask)).setEnabled(!working()).setOnMenuItemClickListener(item -> { if (lostTask != null) listener.reopen(lostTask); return true; });
        menu.add("刷新外屏任务").setEnabled(!working()).setOnMenuItemClickListener(item -> { refresh(); return true; });
        menu.add("返回全部应用").setOnMenuItemClickListener(item -> { listener.apps(); return true; });
        menu.add("关闭任务页").setOnMenuItemClickListener(item -> { listener.close(); return true; });
    }
    private void showPageMenu(View anchor) { InputPopupMenu menu = new InputPopupMenu(getContext(), anchor); pageActions(menu); menu.show(); }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_task_apps, "返回全部应用"));
        if (!working()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_task_refresh, "刷新外屏任务"));
        if (lostTask != null && !working()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.action_task_reopen, "重新打开已结束窗口的应用"));
    }
    @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
        if (action == R.id.action_task_apps) { listener.apps(); return true; }
        if (action == R.id.action_task_refresh && !working()) { refresh(); return true; }
        if (action == R.id.action_task_reopen && lostTask != null && !working()) { listener.reopen(lostTask); return true; }
        return super.performAccessibilityAction(action, args);
    }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    private long duration() { return android.animation.ValueAnimator.areAnimatorsEnabled() ? 160 : 0; }
    private Set<String> pinnedPackages() { Set<String> result = new HashSet<>(); for (String id : prefs.hubPins()) { android.content.ComponentName name = ActionCatalog.component(id); if (name != null) result.add(name.getPackageName()); } return result; }
    private List<RecentTasks.Task> clearTargets() { return locks.unlocked(deferred == null ? tasks : deferred); }
    private boolean working() { return busy || !closings.isEmpty() || exiting; }
    private boolean closing(RecentTasks.Task task) { CloseOperation operation = task == null ? null : closings.get(RecentTasks.key(task)); return operation != null && !operation.failed; }
    private boolean clearable(RecentTasks.Task task) { return known && canClear && !exiting && !closing(task) && !locks.contains(task) && (deferred == null ? tasks : deferred).stream().anyMatch(current -> RecentTasks.sameTask(task, current)); }
    private int lastVisible() { return Math.min(tasks.size(), firstVisible + 3); }
    private void keepSelectedVisible() {
        carousel.position = Math.max(0, Math.min(carousel.position, Math.max(0, tasks.size() - 1)));
        firstVisible = Math.max(0, Math.min(Math.round(carousel.position) - 1, Math.max(0, tasks.size() - 3)));
    }
    RecentTasks.Task selectedTask() { return tasks.isEmpty() ? null : tasks.get(selected); }
    boolean inputStep(int delta) { if (tasks.isEmpty()) return false; int next = Math.max(0, Math.min(tasks.size() - 1, Math.round(carousel.position) + delta)); if (next == Math.round(carousel.position)) return false; select(tasks.get(next)); return true; }
    String inputNavigate(String key, int direction) {
        if (direction == View.FOCUS_DOWN) return "tasks-clear";
        if (tasks.isEmpty()) return "tasks-clear";
        int index = Math.max(0, Math.min(tasks.size() - 1, Math.round(carousel.position)));
        for (int i = 0; i < tasks.size(); i++) if (("task:" + RecentTasks.key(tasks.get(i))).equals(key)) index = i;
        if (direction == View.FOCUS_LEFT || direction == View.FOCUS_RIGHT) index = Math.max(0, Math.min(tasks.size() - 1, index + (direction == View.FOCUS_LEFT ? 1 : -1)));
        select(tasks.get(index)); return "task:" + RecentTasks.key(tasks.get(index));
    }
    void select(RecentTasks.Task task) {
        carousel.stopFling(); carousel.stopPageMotion(); carousel.edge(0);
        if (task != null) for (int i = 0; i < tasks.size(); i++) if (RecentTasks.sameTask(task, tasks.get(i))) { selected = i; break; }
        carousel.position = selected; keepSelectedVisible(); carousel.force.offset = selected * carousel.step(); render();
    }
    void data(List<RecentTasks.Task> next, boolean known, boolean complete, boolean busy, boolean canOpen, boolean canClear, boolean canSnapshot, String message) {
        removeCallbacks(closeEmpty);
        this.known = known; this.complete = complete; this.busy = busy; this.canOpen = canOpen; this.canClear = canClear; this.canSnapshot = canSnapshot;
        state = message;
        if (!known) { failClosings(); releasePreviews(); updateControls(); carousel.wake(); return; }
        if (submittedClose != null && !busy) {
            CloseOperation operation = submittedClose; submittedClose = null;
            if (complete && next.stream().noneMatch(task -> RecentTasks.sameTask(operation.task, task))) operation.accepted = true;
            else { operation.failed = true; carousel.rebound(operation.task); message("未能关闭此任务，请重试"); }
        }
        // The first result must join the visible entrance; background reaction alone must not delay card creation.
        if (tasks.isEmpty() && closings.isEmpty() && !carousel.tracking) { deferred = null; replace(next); queueClose(); return; }
        if (!closings.isEmpty() || carousel.tracking || carousel.moving()) { deferred = List.copyOf(next); updateControls(); carousel.wake(); queueClose(); return; }
        replace(next);
        queueClose();
    }
    private void replace(List<RecentTasks.Task> next) {
        boolean changed = !tasks.equals(next); if (changed) carousel.rememberPositions();
        RecentTasks.Task current = selectedTask(); tasks = List.copyOf(next);
        selected = Math.max(0, Math.min(selected, tasks.size() - 1));
        if (current != null) for (int i = 0; i < tasks.size(); i++) if (RecentTasks.sameTask(current, tasks.get(i))) { selected = i; break; }
        if (changed) carousel.preservePosition(); keepSelectedVisible();
        if (changed || tasks.isEmpty()) render(); else { updateControls(); requestPreviews(); }
        if (!tasks.isEmpty()) { emptyClosing = false; emptyAfterDismissal = false; }
        if (emptyAfterDismissal && known && complete && !working() && tasks.isEmpty()) post(closeEmpty);
    }
    private void render() { carousel.render(); updateControls(); requestPreviews(); }
    private void updateControls() {
        String position = tasks.isEmpty() ? "外屏任务" : (firstVisible + 1) + "–" + lastVisible() + " / " + tasks.size();
        String description = busy ? "正在处理…" : state;
        if (!busy && known && (state.startsWith("图标模式") || state.startsWith("左右切换"))) description = "↑关闭  ↓锁定";
        if (lostTask != null && !working()) description = "窗口已结束，长按可重新打开";
        setContentDescription(position + "，" + description + "，长按空白处刷新或返回全部应用");
        int count = clearTargets().size(); clear.setContentDescription("关闭 " + count + " 个外屏任务并退出，包含使用中和固定应用，保留锁定任务"); clear.setTooltipText(clear.getContentDescription());
        ((View) clear.getParent()).setVisibility(known && complete && tasks.isEmpty() ? GONE : VISIBLE);
        clear.setEnabled(known && canClear && !working() && count > 0); clear.setAlpha(1);
        ((ImageView) clear).setImageTintList(android.content.res.ColorStateList.valueOf(clear.isEnabled() ? Ui.TEXT : 0xFFB8C3CF));
        carousel.bindCards();
    }
    void openFailure(RecentTasks.Task task, boolean gone, String message) { busy = false; state = message; lostTask = gone ? task : null; updateControls(); message(gone ? message + "，长按可重新打开" : message); }
    private void open(RecentTasks.Task task) { if (known && canOpen && !working()) { lostTask = null; listener.open(task); } }
    private void dismiss(List<RecentTasks.Task> targets) {
        if (!known || !canClear || working() || targets.isEmpty()) return;
        List<RecentTasks.Task> allowed = clearTargets();
        List<RecentTasks.Task> checked = new ArrayList<>();
        for (RecentTasks.Task expected : targets) for (RecentTasks.Task current : allowed) if (RecentTasks.sameTask(expected, current)) checked.add(current);
        if (!checked.isEmpty()) { closingPreviewsPaused = true; lostTask = null; listener.clear(List.copyOf(checked)); }
    }
    private void dismissTask(RecentTasks.Task task) {
        if (!clearable(task)) {
            message(locks.contains(task) ? "已锁定，先下滑解锁" : "当前连接无法关闭此任务"); return;
        }
        closingPreviewsPaused = true; closings.put(RecentTasks.key(task), new CloseOperation(task)); lostTask = null; carousel.liftForClose(task); updateControls(); queueClose();
    }
    private void queueClose() { removeCallbacks(submitClose); if (busy || exiting || !known || submittedClose != null) return; for (CloseOperation operation : closings.values()) if (!operation.sent && !operation.accepted && !operation.failed) { post(submitClose); return; } }
    private void submitNextClose() {
        if (busy || exiting || !known || submittedClose != null || !isAttachedToWindow()) return;
        for (CloseOperation operation : closings.values()) if (!operation.sent && !operation.accepted && !operation.failed) {
            if (locks.contains(operation.task)) { operation.failed = true; carousel.rebound(operation.task); queueClose(); return; }
            operation.sent = true; submittedClose = operation; listener.closeTask(operation.task); return;
        }
    }
    private void failClosings() { for (CloseOperation operation : closings.values()) if (!operation.accepted) operation.failed = true; submittedClose = null; carousel.targets(); carousel.wake(); removeCallbacks(submitClose); }
    private void toggleLock(RecentTasks.Task task) {
        if (!known || exiting || closing(task) || (deferred == null ? tasks : deferred).stream().noneMatch(current -> RecentTasks.sameTask(task, current))) return;
        boolean changed = locks.toggle(task);
        String message = changed ? locks.contains(task) ? "已锁定，本工具清理会跳过此窗口" : "已解锁" : "最多锁定32个窗口";
        updateControls(); message(message);
    }
    void catalogChanged() { carousel.bindCards(); }
    void exit(Runnable finished) {
        if (exiting) return; exiting = true; blankTap = false; removeCallbacks(closeEmpty); removeCallbacks(loadPreviews); carousel.cancel();
        if (glass != null) glass.unbind(this);
        setBackgroundColor(android.graphics.Color.TRANSPARENT); setWillNotDraw(false);
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) { exitProgress(1); finished.run(); return; }
        exitAnimation = ValueAnimator.ofFloat(0, 1); exitAnimation.setDuration(200); exitAnimation.setInterpolator(new android.view.animation.LinearInterpolator());
        exitAnimation.addUpdateListener(animation -> exitProgress((float) animation.getAnimatedValue()));
        exitAnimation.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { if (exitAnimation != animation) return; exitAnimation = null; exitProgress(1); finished.run(); }
        }); exitAnimation.start();
    }
    private void exitProgress(float value) {
        exitProgress = value;
        carousel.setAlpha(Math.max(0, 1 - value / .3f));
        View slot = (View) clear.getParent();
        float pop = 1 + .20f * (float) Math.sin(Math.min(1, value / .4f) * Math.PI), shrink = 1 - Math.max(0, (value - .4f) / .6f);
        slot.setScaleX(pop * shrink); slot.setScaleY(pop * shrink); slot.setAlpha(Math.max(0, 1 - Math.max(0, value - .15f) / .6f));
        if (!ownBackplate && getParent() instanceof AppHubView hub && hub.getParent() instanceof InterfaceCard card) card.setAlpha(1 - value);
        invalidate();
    }
    void cancelExit() {
        stopExit(); exiting = false; emptyClosing = false; exitProgress = 0; carousel.setAlpha(1);
        View slot = (View) clear.getParent(); slot.setScaleX(1); slot.setScaleY(1); slot.setAlpha(1);
        if (!ownBackplate && getParent() instanceof AppHubView hub && hub.getParent() instanceof InterfaceCard card) card.setAlpha(1);
        setBackgroundColor(ownBackplate ? 0x3D000000 : android.graphics.Color.TRANSPARENT); if (glass != null && glass.active()) glass.bind(this, ownBackplate ? GlassSurface.Role.NOTIFICATION_PAGE : GlassSurface.Role.CLEAR);
        carousel.wake();
    }
    private void stopExit() { if (exitAnimation != null) { exitAnimation.removeAllListeners(); exitAnimation.removeAllUpdateListeners(); exitAnimation.cancel(); exitAnimation = null; } }
    @Override protected void onDraw(android.graphics.Canvas canvas) {
        super.onDraw(canvas);
        if (!exiting || !ownBackplate) return;
        if (glass != null && glass.closingBackdropValid()) glass.drawClosingBackdrop(canvas, this, Math.min(1, exitProgress / .35f), 1 - exitProgress);
        else canvas.drawColor(Math.round(61 * (1 - exitProgress)) << 24);
    }
    private String action(RecentTasks.Task task) { String launcher = cache.launcher(task.packageName()); return launcher == null ? "app:" + task.component() : launcher; }
    private String label(RecentTasks.Task task) { String label = cache.label(action(task)); return label == null ? task.packageName() : label; }
    private void requestPreviews() {
        Set<String> live = new HashSet<>();
        for (RecentTasks.Task task : deferred == null ? tasks : deferred) live.add(RecentTasks.key(task));
        // A confirmed removal still borrows its preview until its visible retreat has completed.
        for (RecentTasks.Task task : tasks) live.add(RecentTasks.key(task));
        attempted.keySet().retainAll(live); retryPreviews.retainAll(live);
        List<Bitmap> expired = new ArrayList<>();
        for (Map<String, ShizukuBridge.Snapshot> cache : List.of(previews, pendingPreviews)) for (String key : new ArrayList<>(cache.keySet())) if (!live.contains(key)) { Bitmap bitmap = cache.remove(key).bitmap(); if (bitmap != null) expired.add(bitmap); }
        if (!expired.isEmpty()) { carousel.bindCards(); for (Bitmap bitmap : expired) bitmap.recycle(); }
        if (!known || working() || closingPreviewsPaused || getParent() instanceof AppHubView owner && owner.closing() || !canSnapshot || memoryTrimmed || snapshotPending || carousel.tracking || Math.abs(carousel.force.velocity) > carousel.step() * 2 || !isAttachedToWindow() || !isShown()) return;
        // Late images wait for rest before creating optics or reveal animations.
        if (!pendingPreviews.isEmpty()) {
            previews.putAll(pendingPreviews); pendingPreviews.clear(); carousel.bindCards();
        }
        // Center first, then the two neighbors. At most one bounded retry, never background polling.
        for (int rank = 0; rank < lastVisible() - firstVisible; rank++) {
            int index = rank == 0 ? selected : firstVisible + rank - 1; if (rank > 0 && index >= selected) index++;
            RecentTasks.Task task = tasks.get(index); String key = RecentTasks.key(task);
            int attempts = attempted.getOrDefault(key, 0); if (attempts > 0 && !retryPreviews.remove(key) || attempts >= 2 || previews.containsKey(key)) continue;
            attempted.put(key, attempts + 1);
            int token = generation; snapshotPending = true;
            listener.snapshot(task, result -> {
                if (token != generation || !isAttachedToWindow()) { if (result.bitmap() != null) result.bitmap().recycle(); return; }
                snapshotPending = false;
                boolean current = (deferred == null ? tasks : deferred).stream().anyMatch(item -> RecentTasks.sameTask(task, item));
                // Cache successful reads for this page, even if the user scrolled away meanwhile.
                boolean retry = current && known && !memoryTrimmed && result.bitmap() == null && result.retryable() && attempted.getOrDefault(key, 0) < 2;
                if (current && known && !memoryTrimmed && result.bitmap() != null && previews.size() + pendingPreviews.size() < 32) pendingPreviews.put(key, result); else if (result.bitmap() != null) result.bitmap().recycle();
                if (retry) retryPreviews.add(key);
                removeCallbacks(loadPreviews); postDelayed(loadPreviews, retry ? 180 : 0);
            });
            return;
        }
    }
    private void releasePreviews() { generation++; snapshotPending = false; List<ShizukuBridge.Snapshot> old = new ArrayList<>(previews.values()); old.addAll(pendingPreviews.values()); previews.clear(); pendingPreviews.clear(); attempted.clear(); retryPreviews.clear(); if (carousel != null) carousel.bindCards(); for (ShizukuBridge.Snapshot snapshot : old) if (snapshot.bitmap() != null) snapshot.bitmap().recycle(); }
    void dispose() { blankTap = false; closings.clear(); submittedClose = null; removeCallbacks(submitClose); stopExit(); removeCallbacks(closeEmpty); removeCallbacks(loadPreviews); animate().cancel(); carousel.cancel(); if (glass != null) glass.taskReaction(0, 0); glass = null; glassReady(false); releasePreviews(); deferred = null; tasks = List.of(); carousel.clearCards(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); getContext().getApplicationContext().registerComponentCallbacks(this); carousel.bindCards(); previewsVisible(); }
    @Override protected void onDetachedFromWindow() { getContext().getApplicationContext().unregisterComponentCallbacks(this); dispose(); super.onDetachedFromWindow(); }
    @Override public void onTrimMemory(int level) { if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) { memoryTrimmed = true; if (glass != null) glass.cancelSourceRefresh(); releasePreviews(); } }
    @Override public void onLowMemory() { memoryTrimmed = true; if (glass != null) glass.cancelSourceRefresh(); releasePreviews(); }
    @Override public void onConfigurationChanged(Configuration configuration) { releasePreviews(); }

    private final class Card extends LinearLayout {
        int node;
        String key;
        float layoutOffset, layoutVelocity;
        boolean assigning;
        RecentTasks.Task task;
        final TextView name, caption;
        final TaskPreviewView picture;
        final ImageView lockBadge;
        int position;
        Card(Context context) {
            super(context); setOrientation(VERTICAL); setFocusable(true); setClipChildren(false); setClipToPadding(false);
            FrameLayout label = new FrameLayout(context);
            name = Ui.heading(context, "", PanelUi.SECONDARY); name.setGravity(Gravity.CENTER); name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(dp(14), 0, dp(14), 0); label.addView(name, new FrameLayout.LayoutParams(-1, -1));
            lockBadge = new ImageView(context); lockBadge.setImageDrawable(Ui.icon(context, R.drawable.ic_ms_lock, 0xFFFFCE75)); lockBadge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); label.addView(lockBadge, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL | Gravity.END)); addView(label, new LayoutParams(-1, dp(24)));
            picture = new TaskPreviewView(context); picture.setDuplicateParentStateEnabled(true); addView(picture, new LayoutParams(-1, 0, 1));
            caption = Ui.text(context, "", PanelUi.SECONDARY, 0xFFDCE3ED); caption.setGravity(Gravity.CENTER); caption.setSingleLine(); caption.setEllipsize(TextUtils.TruncateAt.END); caption.setPadding(0, dp(PanelUi.GAP), 0, 0); addView(caption, new LayoutParams(-1, dp(20)));
            name.setShadowLayer(dp(1.5f), 0, dp(.5f), 0xCC101820); caption.setShadowLayer(dp(1.5f), 0, dp(.5f), 0xCC101820);
            setOnClickListener(v -> { selected = position; open(task); });
            setOnLongClickListener(v -> {
                if (!known || working()) return true;
                RecentTasks.Task chosen = task; InputPopupMenu menu = new InputPopupMenu(context, this);
                menu.add(locks.contains(chosen) ? "解锁此窗口" : "锁定此窗口").setOnMenuItemClickListener(item -> { toggleLock(chosen); return true; });
                menu.add("关闭此窗口").setEnabled(clearable(chosen)).setOnMenuItemClickListener(item -> { dismissTask(chosen); return true; }); pageActions(menu); menu.show(); return true;
            });
        }
        void assign(RecentTasks.Task next, int index) {
            animate().cancel(); setPressed(false); setStateDescription(null); setTranslationX(0); setTranslationY(0); setAlpha(1); picture.resetPull();
            task = next; key = RecentTasks.key(next); position = index; setTag("task-card:" + next.id()); picture.setTag("task-preview:" + next.id()); lockBadge.setTag("task-lock:" + next.id());
            InputNavigation.bind(this, "task:" + key, InputNavigation.Region.TASK, this::performClick, this::performLongClick);
            assigning = true; bind(); assigning = false; jumpDrawablesToCurrentState(); picture.jumpDrawablesToCurrentState();
        }
        void recycle() { animate().cancel(); setPressed(false); picture.resetPull(); picture.content(null, null); task = null; setTag(null); }
        void bind() {
            if (task == null) return;
            String text = label(task); long count = tasks.stream().filter(item -> item.packageName().equals(task.packageName())).count();
            if (count > 1) { int ordinal = 0; for (int i = 0; i <= position; i++) if (tasks.get(i).packageName().equals(task.packageName())) ordinal++; text += " · " + ordinal; }
            if (!text.contentEquals(name.getText())) name.setText(text);
            ShizukuBridge.Snapshot preview = previews.get(RecentTasks.key(task)); Bitmap bitmap = preview == null ? null : preview.bitmap();
            android.graphics.drawable.Drawable icon = cache.cachedIcon(getContext(), action(task));
            picture.content(bitmap, icon == null ? Ui.icon(getContext(), R.drawable.ic_ms_apps, Ui.ACCENT) : icon, !assigning);
            if (bitmap == null && icon == null && isAttachedToWindow()) cache.requestIcon(action(task));
            boolean locked = locks.contains(task);
            String detail = closing(task) ? "正在关闭…" : locked ? "已锁定" : task.visible() ? "使用中" : pinnedPackages().contains(task.packageName()) ? "固定" : bitmap != null ? "上次画面" : "无画面预览";
            if (!detail.contentEquals(caption.getText())) caption.setText(detail);
            lockBadge.setVisibility(locked ? VISIBLE : GONE); setSelected(locked);
            if (glass != null && glass.active()) glass.bind(picture, GlassSurface.Role.TASK_PREVIEW);
            picture.glass(glassReady);
            picture.locked(locked);
            setEnabled(known && !exiting && !closing(task)); setContentDescription(text + "，" + detail + "，点击返回原窗口，上滑关闭，下滑" + (locked ? "解锁" : "锁定") + "，长按更多操作");
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(info); if (known && !working()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(LOCK_ACTION, locks.contains(task) ? "解锁此窗口" : "锁定此窗口")); if (clearable(task)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS); }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) { if (action == LOCK_ACTION && known && !working()) { toggleLock(task); return true; } if (action == AccessibilityNodeInfo.ACTION_DISMISS && clearable(task)) { dismissTask(task); return true; } return super.performAccessibilityAction(action, args); }
    }
    /** Owns gesture/data continuity and four reusable cards; TaskForce owns the six-node solver. */
    private final class Carousel extends FrameLayout {
        private float startX, startY, startOffset, startVertical, position, edgeOffset;
        private int axis, pointerId = -1, created;
        private RecentTasks.Task pressed;
        private boolean tracking, blocked, armed, scheduled;
        private android.view.VelocityTracker velocity;
        private final int slop, maxFling;
        private long lastFrame, lastMove;
        private final TaskForce force = new TaskForce();
        private final java.util.ArrayDeque<Card> recycled = new java.util.ArrayDeque<>();
        private final Map<String, Float> previous = new HashMap<>();
        private RecentTasks.Task anchorTask;
        private int anchorIndex;
        private float oldOffset;
        private String[] keys = new String[0];
        private int[] closingBefore = new int[1];
        private final int[] desired = new int[4];
        private int desiredCount;
        Carousel(Context context) {
            super(context); ViewConfiguration configuration = ViewConfiguration.get(context); slop = configuration.getScaledTouchSlop(); maxFling = configuration.getScaledMaximumFlingVelocity();
            setPadding(dp(6), 0, dp(6), 0); setClipChildren(true); setClipToPadding(true); setOnLongClickListener(v -> { blankTap = false; showPageMenu(clear); return true; });
        }
        void rememberPositions() {
            previous.clear(); for (int i = 0; i < tasks.size(); i++) previous.put(RecentTasks.key(tasks.get(i)), base(i));
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) previous.put(card.key, base(card.position) + card.layoutOffset);
            anchorIndex = Math.max(0, Math.min(Math.round(position), tasks.size() - 1)); anchorTask = tasks.isEmpty() ? null : tasks.get(anchorIndex); oldOffset = force.offset;
        }
        void preservePosition() {
            if (anchorTask != null) for (int i = 0; i < tasks.size(); i++) if (RecentTasks.sameTask(anchorTask, tasks.get(i))) { position += i - anchorIndex; break; }
            position = Math.max(0, Math.min(position, Math.max(0, tasks.size() - 1))); force.bounds(Math.max(0, tasks.size() - 1) * step(), step()); force.offset = position * step();
            if (tracking && axis == 1) startOffset += force.offset - oldOffset;
            force.snapping = false; force.pending = !tracking;
            indexKeys();
        }
        private void indexKeys() { keys = new String[tasks.size()]; closingBefore = new int[tasks.size() + 1]; for (int i = 0; i < tasks.size(); i++) keys[i] = RecentTasks.key(tasks.get(i)); }
        private boolean closingIndex(int index) { CloseOperation operation = closings.get(keys[index]); return operation != null && !operation.failed; }
        private void indexClosings() { closingBefore[0] = 0; for (int i = 0; i < tasks.size(); i++) closingBefore[i + 1] = closingBefore[i] + (closingIndex(i) ? 1 : 0); }
        void render() {
            force.bounds(Math.max(0, tasks.size() - 1) * step(), step()); force.offset = position * step();
            if (tasks.isEmpty()) {
                recycleCards(); TextView empty = Ui.text(getContext(), known ? "当前没有后台任务" : state, 13, Ui.TEXT); empty.setTag("tasks-empty"); InputNavigation.bind(empty, "tasks-empty", InputNavigation.Region.CLEAR, () -> showPageMenu(empty), () -> showPageMenu(empty)); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), 0, dp(20), 0); empty.setShadowLayer(dp(1.5f), 0, dp(.5f), 0xCC101820); addView(empty, new FrameLayout.LayoutParams(-1, -1));
            } else {
                syncVisibleCards(); for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) { Float before = previous.get(card.key); if (before != null) card.layoutOffset = before - base(card.position); }
            }
            previous.clear(); requestLayout(); wake();
        }
        private void save(Card card) {
            CloseOperation operation = closings.get(card.key);
            if (operation != null) { int n = card.node; operation.visual[0] = force.x[n]; operation.visual[1] = force.y[n]; operation.visual[2] = force.vx[n]; operation.visual[3] = force.vy[n]; operation.visual[4] = force.tx[n]; operation.visual[5] = force.ty[n]; operation.layout = card.layoutOffset; operation.layoutVelocity = card.layoutVelocity; }
        }
        private void recycleCards() {
            for (int i = getChildCount() - 1; i >= 0; i--) { View child = getChildAt(i); removeViewAt(i); if (child instanceof Card card) { save(card); force.resetNode(card.node); card.recycle(); if (recycled.size() < 4) recycled.add(card); } }
        }
        private boolean intersects(float left) { return left + cardWidth() > getPaddingLeft() && left < getWidth() - getPaddingRight(); }
        private boolean desired(int index) { for (int i = 0; i < desiredCount; i++) if (desired[i] == index) return true; return false; }
        private void include(int index) { if (desiredCount < 4 && !desired(index)) desired[desiredCount++] = index; }
        private float projected(int index) {
            int center = Math.max(0, Math.min(tasks.size(), Math.round(position))), total = closingBefore[tasks.size()];
            float next = Math.max(0, Math.min(position - closingBefore[center], Math.max(0, tasks.size() - total - 1)));
            return (getWidth() - cardWidth()) / 2f + (next - index + closingBefore[index]) * step();
        }
        private boolean syncVisibleCards() {
            if (tasks.isEmpty()) { force.count = 0; return false; }
            if (keys.length != tasks.size()) indexKeys(); indexClosings(); desiredCount = 0;
            // Keep every still-visible outgoing surface; once faded, its operation retains numeric state only.
            for (int index = 0; index < tasks.size(); index++) { Card card = card(tasks.get(index)); if (card != null && closingIndex(index) && force.y[card.node] > -card.getHeight() * .65f && intersects(base(index) + card.layoutOffset + force.x[card.node])) include(index); }
            for (int index = 0; index < tasks.size(); index++) { Card card = card(tasks.get(index)); if (card != null && !closingIndex(index) && intersects(base(index) + card.layoutOffset + force.x[card.node])) include(index); }
            for (int index = 0; index < tasks.size(); index++) if (!closingIndex(index) && intersects(projected(index))) include(index);
            boolean changed = false;
            for (int i = getChildCount() - 1; i >= 0; i--) {
                View child = getChildAt(i); int index = -1;
                if (child instanceof Card card) for (int j = 0; j < desiredCount; j++) { int candidate = desired[j]; if (RecentTasks.sameTask(card.task, tasks.get(candidate))) { index = candidate; break; } }
                if (index < 0) { removeViewAt(i); if (child instanceof Card card) { save(card); force.resetNode(card.node); card.recycle(); recycled.add(card); } changed = true; }
                else { Card card = (Card) child; card.position = index; card.task = tasks.get(index); }
            }
            for (int i = 0; i < desiredCount; i++) {
                int index = desired[i]; if (card(tasks.get(index)) != null) continue;
                Card card = recycled.poll(); if (card == null) { card = new Card(getContext()); card.node = created++; }
                force.resetNode(card.node); card.layoutOffset = card.layoutVelocity = 0; card.assign(tasks.get(index), index); addView(card); changed = true;
                CloseOperation operation = closings.get(card.key);
                if (operation != null) { int n = card.node; force.x[n] = operation.visual[0]; force.y[n] = operation.visual[1]; force.vx[n] = operation.visual[2]; force.vy[n] = operation.visual[3]; force.tx[n] = operation.visual[4]; force.ty[n] = operation.failed ? 0 : -getHeight(); card.layoutOffset = operation.layout; card.layoutVelocity = operation.layoutVelocity; }
            }
            force.count = 0;
            for (int index = 0; index < tasks.size(); index++) { Card card = card(tasks.get(index)); if (card != null && !closingIndex(index)) force.order[force.count++] = card.node; }
            return changed;
        }
        void clearCards() { recycleCards(); recycled.clear(); previous.clear(); keys = new String[0]; closingBefore = new int[1]; force.count = 0; created = 0; anchorTask = null; }
        void bindCards() { for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) card.bind(); }
        private int cardWidth() { return Math.max(1, (getMeasuredWidth() - dp(12) - dp(5) * 2) / 3); }
        private float step() { return cardWidth() + dp(5); }
        private float base(int index) { return (getWidth() - cardWidth()) / 2f + (position - index) * step(); }
        private void measureCards(int width, int height) {
            int available = height, cardHeight = Math.min(Math.max(0, height - dp(56)), Math.max(dp(112), Math.round(cardWidth() * 1.5f)));
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(tasks.isEmpty() ? width : cardWidth(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tasks.isEmpty() ? available : cardHeight, MeasureSpec.EXACTLY));
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) { int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec); setMeasuredDimension(width, height); measureCards(width, height); }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            force.bounds(Math.max(0, tasks.size() - 1) * step(), step()); force.offset = position * step();
            if (syncVisibleCards()) measureCards(getWidth(), getHeight());
            for (int i = 0; i < getChildCount(); i++) { View view = getChildAt(i); int y = Math.max(0, (getHeight() - dp(52) - view.getMeasuredHeight()) / 2); view.layout(0, y, view.getMeasuredWidth(), y + view.getMeasuredHeight()); }
            drawMotion();
        }
        private Card card(RecentTasks.Task task) { if (task == null) return null; for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card && RecentTasks.sameTask(card.task, task)) return card; return null; }
        private void targets() {
            for (int i = 0; i < 6; i++) { force.tx[i] = 0; force.ty[i] = 0; }
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card && closingIndex(card.position)) force.ty[card.node] = -getHeight();
        }
        private float layoutTarget(Card card) { return closingIndex(card.position) ? 0 : projected(card.position) - base(card.position); }
        private boolean advanceLayout(float dt) {
            boolean moving = false;
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) {
                if (tracking && axis == 2 && RecentTasks.sameTask(pressed, card.task)) { card.layoutVelocity = 0; continue; }
                float target = layoutTarget(card);
                for (int sub = 0; sub < 4; sub++) { float h = dt / 4; card.layoutVelocity += ((target - card.layoutOffset) * 420 - card.layoutVelocity * 42) * h; card.layoutOffset += card.layoutVelocity * h; }
                if (Math.abs(target - card.layoutOffset) + Math.abs(card.layoutVelocity) > .09f) moving = true; else { card.layoutOffset = target; card.layoutVelocity = 0; }
            }
            return moving;
        }
        private void drawMotion() {
            float unit = step() / 245;
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) {
                int n = card.node; card.setTranslationX(base(card.position) + card.layoutOffset + force.x[n]); card.setTranslationY(force.y[n]);
                card.setTranslationZ(closingIndex(card.position) ? dp(1) : 0);
                card.setAlpha(closingIndex(card.position) ? Math.max(0, 1 - Math.abs(force.y[n]) / Math.max(1, card.getHeight() * .65f)) : 1);
                boolean fastCapture = force.fastCapture();
                float stretch = Math.min(fastCapture ? .022f : .055f, (Math.abs(force.vx[n]) + Math.abs(force.vy[n])) / (6500 * unit) + Math.abs(force.y[n]) / (2100 * unit) + Math.abs(force.x[n]) / (700 * unit) + Math.abs(force.velocity) / (48000 * unit) + Math.min(.009f, Math.abs(card.layoutVelocity) / (90000 * unit)));
                float tilt = fastCapture ? .6f : 1.2f;
                card.picture.force(stretch, axis == 1 && tracking || force.snapping || Math.abs(force.x[n]) > Math.abs(force.y[n]), Math.max(-tilt, Math.min(tilt, (force.vx[n] + force.velocity * .025f) / (280 * unit))));
            }
            clear.setTranslationX(force.x[4]); clear.setTranslationY(force.y[4]);
            if (glass != null && glass.active()) glass.taskReaction(force.x[5], force.y[5]);
        }
        boolean moving() {
            if (force.snapping || Math.abs(force.velocity) > 1 || force.pending && force.offset != force.nearest()) return true;
            for (int i = 0; i < 6; i++) if (Math.abs(force.vx[i]) + Math.abs(force.vy[i]) + Math.abs(force.x[i] - force.tx[i]) + Math.abs(force.y[i] - force.ty[i]) > .09f) return true;
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card && Math.abs(card.layoutOffset - layoutTarget(card)) + Math.abs(card.layoutVelocity) > .09f) return true;
            return false;
        }
        void wake() { if (!scheduled && !exiting && isAttachedToWindow() && isShown()) { scheduled = true; lastFrame = 0; postOnAnimation(frame); } }
        private final Runnable frame = this::advanceFrame;
        private void advanceFrame() {
            scheduled = false; if (exiting || !isAttachedToWindow() || !isShown()) return;
            long now = System.nanoTime(); float dt = lastFrame == 0 ? 1f / 60 : Math.min(.032f, (now - lastFrame) / 1_000_000_000f); lastFrame = now;
            force.bounds(Math.max(0, tasks.size() - 1) * step(), step());
            Card anchor = tasks.isEmpty() ? null : card(tasks.get(Math.max(0, Math.min(tasks.size() - 1, force.destination()))));
            boolean active;
            if (ValueAnimator.areAnimatorsEnabled()) active = force.advance(dt, tracking || blocked, closings.isEmpty(), anchor == null ? -1 : anchor.node);
            else { force.reduced(tracking || blocked, closings.isEmpty()); active = false; }
            position = force.offset / step();
            if (syncVisibleCards()) { measureCards(getWidth(), getHeight()); for (int i = 0; i < getChildCount(); i++) { View view = getChildAt(i); int y = Math.max(0, (getHeight() - dp(52) - view.getMeasuredHeight()) / 2); view.layout(0, y, view.getMeasuredWidth(), y + view.getMeasuredHeight()); } }
            active |= advanceLayout(dt); if (!ValueAnimator.areAnimatorsEnabled()) { for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) { card.layoutOffset = layoutTarget(card); card.layoutVelocity = 0; } active = false; }
            drawMotion(); commitCloses();
            int nearest = Math.max(0, Math.min(tasks.size() - 1, Math.round(position))), first = Math.max(0, Math.min(nearest - 1, Math.max(0, tasks.size() - 3)));
            if (firstVisible != first || selected != nearest && (!tracking || axis == 1)) { firstVisible = first; if (!tracking || axis == 1) selected = nearest; updateControls(); }
            if (active) { if (!scheduled) { scheduled = true; postOnAnimation(frame); } } else { lastFrame = 0; finishDeferred(); }
        }
        private void commitCloses() {
            boolean changed = false; java.util.Iterator<CloseOperation> iterator = closings.values().iterator();
            while (iterator.hasNext()) { CloseOperation operation = iterator.next(); Card card = card(operation.task);
                if (operation.failed && (card == null && !intersects(base(tasks.indexOf(operation.task))) || card != null && Math.abs(force.y[card.node]) + Math.abs(force.vy[card.node]) < .09f)) { iterator.remove(); continue; }
                if (operation.accepted && (card == null || force.y[card.node] < -card.getHeight() * .72f || !ValueAnimator.areAnimatorsEnabled())) {
                if (!changed) rememberPositions(); tasks = tasks.stream().filter(task -> !RecentTasks.sameTask(operation.task, task)).collect(java.util.stream.Collectors.toList());
                if (deferred != null) deferred = deferred.stream().filter(task -> !RecentTasks.sameTask(operation.task, task)).collect(java.util.stream.Collectors.toList());
                iterator.remove(); changed = true;
            } }
            if (!changed) { queueClose(); return; }
            emptyAfterDismissal = tasks.isEmpty();
            if (emptyAfterDismissal && deferred != null && deferred.isEmpty()) deferred = null;
            selected = Math.max(0, Math.min(selected, tasks.size() - 1)); preservePosition(); keepSelectedVisible(); render(); updateControls();
            if (tasks.isEmpty() && known && complete && !working()) post(closeEmpty);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (exiting) return true;
            if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) { blocked = true; cancelSequence(); blankTap = false; return true; }
            if (blocked) { if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { blocked = false; targets(); force.release(0); wake(); } return true; }
            if (action == MotionEvent.ACTION_DOWN) {
                force.interrupt(); recycleVelocity(); velocity = android.view.VelocityTracker.obtain(); tracking = true; pointerId = event.getPointerId(0); startX = event.getX(); startY = event.getY(); lastMove = event.getEventTime(); axis = 0; pressed = null; armed = false; startVertical = 0; startOffset = force.offset;
                for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card && !closing(card.task) && startX >= card.getX() && startX < card.getX() + card.getWidth() && startY >= card.getY() && startY < card.getY() + card.getHeight()) { pressed = card.task; selected = card.position; startVertical = force.y[card.node]; }
            }
            if (velocity != null) velocity.addMovement(event);
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                tracking = false; pointerId = -1; recycleVelocity(); if (axis == 0 || axis == 3 || action == MotionEvent.ACTION_CANCEL) { targets(); force.release(0); }
                wake(); finishDeferred();
            }
            return handled;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (axis == 0 && Math.hypot(dx, dy) > slop) { axis = Math.abs(dx) > Math.abs(dy) * 1.4f ? 1 : Math.abs(dy) > Math.abs(dx) * 1.4f ? 2 : 3; getParent().requestDisallowInterceptTouchEvent(true); }
            return axis != 0;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            float dx = event.getX() - startX, dy = event.getY() - startY; int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_MOVE && event.findPointerIndex(pointerId) >= 0) {
                lastMove = event.getEventTime();
                if (axis == 1) { force.drag(startOffset + dx); position = force.offset / step(); if (syncVisibleCards()) requestLayout(); drawMotion(); wake(); }
                else if (axis == 2 && pressed != null) {
                    Card card = card(pressed); if (card != null && known && !closing(card.task)) {
                        targets(); boolean allowed = dy > 0 || clearable(card.task); float unit = step() / 245, limit = (allowed ? 105 : 15) * unit;
                        force.ty[card.node] = startVertical + Math.signum(dy) * limit * (1 - (float) Math.exp(-Math.abs(dy) / (130 * unit)));
                        force.ty[4] = force.ty[card.node] * .06f; force.ty[5] = -force.ty[card.node] * .04f;
                        boolean next = allowed && (dy > 0 ? dy > verticalThreshold() : -dy > card.picture.getHeight() * .30f);
                        if (next && !armed && prefs.haptics()) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); armed = next;
                        card.setStateDescription(dy >= 0 ? (next ? "松手" : "继续滑动") + (locks.contains(card.task) ? "解锁" : "锁定") : locks.contains(card.task) ? "已锁定，先下滑解锁" : "上滑关闭此窗口"); wake();
                    }
                }
            }
            if (action == MotionEvent.ACTION_UP) {
                float speedX = 0, speedY = 0; if (velocity != null && event.getEventTime() - lastMove <= 100) { velocity.computeCurrentVelocity(1000, maxFling); speedX = velocity.getXVelocity(pointerId); speedY = velocity.getYVelocity(pointerId); }
                targets();
                if (axis == 1) force.release(speedX);
                else if (axis == 2 && pressed != null && Math.abs(dy) > Math.abs(dx) * 1.4f) {
                    Card card = card(pressed);
                    if (dy > verticalThreshold()) toggleLock(pressed);
                    else if (card != null && TaskForce.dismiss(dy, card.picture.getHeight(), speedY, event.getEventTime() - lastMove <= 100)) dismissTask(pressed);
                    force.release(0);
                } else force.release(0);
                updateControls(); wake(); performClick();
            } else if (action == MotionEvent.ACTION_CANCEL) { if (tracking) cancelSequence(); force.release(0); wake(); }
            return true;
        }
        private float verticalThreshold() { return Math.max(dp(36), Math.min(dp(64), getHeight() * .22f)); }
        void liftForClose(RecentTasks.Task task) { Card card = card(task); if (card != null) { force.ty[card.node] = -getHeight(); force.vy[card.node] = Math.min(force.vy[card.node], -360 * step() / 245); force.vy[4] = Math.max(-150, force.vy[4] - 25); } syncVisibleCards(); wake(); }
        void rebound(RecentTasks.Task task) { Card card = card(task); if (card != null) force.ty[card.node] = 0; syncVisibleCards(); wake(); }
        private void resetCards() { targets(); force.release(0); wake(); }
        private void edge(float value) { edgeOffset = value; if (value == 0) for (int i = 0; i < 6; i++) force.tx[i] = 0; }
        private void stopFling() { force.velocity = 0; }
        private void stopPageMotion() { force.interrupt(); }
        private void recycleVelocity() { if (velocity != null) { velocity.recycle(); velocity = null; } }
        private void finishDeferred() { if (tracking || blocked || moving() || !closings.isEmpty()) return; if (!working()) closingPreviewsPaused = false; if (deferred != null) { List<RecentTasks.Task> next = deferred; deferred = null; replace(next); } else requestPreviews(); queueClose(); if (glass != null) glass.resumeSourceRefresh(); }
        private void cancelSequence() {
            tracking = false; pointerId = -1; axis = 3; pressed = null; armed = false; recycleVelocity(); force.interrupt(); targets();
            long now = android.os.SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0); super.dispatchTouchEvent(cancel); cancel.recycle(); wake();
        }
        void cancel() { cancel(false); }
        private void cancel(boolean center) {
            removeCallbacks(frame); scheduled = false; lastFrame = 0; tracking = blocked = false; pointerId = -1; axis = 3; pressed = null; recycleVelocity();
            if (exiting) { force.interrupt(); return; }
            force.reset(); if (center) position = Math.round(position); force.bounds(Math.max(0, tasks.size() - 1) * step(), step()); force.offset = position * step();
            for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) { card.layoutOffset = card.layoutVelocity = 0; card.picture.resetPull(); } drawMotion();
        }
        @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); wake(); }
        @Override protected void onDetachedFromWindow() { cancel(); super.onDetachedFromWindow(); }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) { cancel(true); finishDeferred(); } }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setClassName(android.widget.HorizontalScrollView.class.getName()); info.setScrollable(tasks.size() > 1);
            if (position > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (position < tasks.size() - 1) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
            if (!working() && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                force.interrupt(); force.offset = Math.max(0, Math.min(force.maximum, (Math.round(position) + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1)) * step())); position = force.offset / step(); force.release(0); render(); return true;
            }
            return super.performAccessibilityAction(action, args);
        }
        @Override public boolean performClick() { return super.performClick(); }
    }
}
