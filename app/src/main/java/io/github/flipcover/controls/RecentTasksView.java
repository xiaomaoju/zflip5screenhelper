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
    private final Set<String> attempted = new HashSet<>();
    private List<RecentTasks.Task> tasks = List.of(), deferred;
    private boolean known, busy, canOpen, canClear, canSnapshot, snapshotPending, memoryTrimmed;
    private int selected, firstVisible, generation;
    private RecentTasks.Task lostTask;
    private RecentTasks.Task pendingClose;
    private float panelProgress = 1;
    private PanelGlassSession glass;
    private boolean glassReady;
    private boolean blankTap;
    private float blankX, blankY;
    private long blankDown;
    private final android.graphics.Rect hitBounds = new android.graphics.Rect();
    private String state = "正在读取外屏任务…";

    RecentTasksView(Context context, Prefs prefs, int edge, Listener listener) {
        super(context); this.listener = listener; this.prefs = prefs; cache = CoverApp.catalog(context); locks = CoverApp.taskLocks(context);
        setOrientation(VERTICAL); setTag("recent-tasks"); setBackgroundColor(0x3D000000); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        surface = new PanelSurface(context, DockGeometry.BOTTOM, dp(240), prefs.haptics(), new PanelHeaderView.Listener() {
            public void begin() { animate().cancel(); }
            public float currentProgress() { return panelProgress; }
            public void progress(float value) {
                panelProgress = value; float distance = (1 - value) * dp(240);
                setTranslationY(distance);
            }
            public void finish(boolean close) { if (close) listener.close(); else { panelProgress = 1; animate().translationX(0).translationY(0).setDuration(duration()).start(); } }
        });
        addView(surface, new LayoutParams(-1, -1));
        clear = smallIcon(R.drawable.ic_ms_close, "一键清理未锁定的后台任务", () -> dismiss(clearTargets())); clear.setTag("tasks-clear");
        clear.setBackground(Ui.ripple(context, 0xF21C2632, 1000));
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
    void glass(PanelGlassSession session) {
        if (glass == session) return;
        glass = session;
        if (session != null && session.active()) {
            session.bind(this, GlassSurface.Role.NOTIFICATION_PAGE);
            session.bind(clear, GlassSurface.Role.TASK_ACTION);
        }
        glassReady(session != null && session.ready()); carousel.bindCards();
    }
    void glassReady(boolean ready) {
        if (glassReady == ready) return; glassReady = ready;
        for (int i = 0; i < carousel.getChildCount(); i++) if (carousel.getChildAt(i) instanceof Card card) card.picture.glass(ready);
    }
    void safeArea(DockGeometry.Box area, DockGeometry.Box frame) { surface.setPadding(area.x(), area.y(), Math.max(0, frame.width() - area.right()), Math.max(0, frame.height() - area.bottom())); }
    void previewsVisible() { removeCallbacks(loadPreviews); post(loadPreviews); }
    private final Runnable loadPreviews = this::requestPreviews;
    private void refresh() { if (!working()) { lostTask = null; releasePreviews(); listener.refresh(); } }
    private void message(String text) { android.widget.Toast.makeText(getContext(), text, android.widget.Toast.LENGTH_SHORT).show(); announceForAccessibility(text); }
    private void pageActions(PopupMenu menu) {
        if (lostTask != null) menu.getMenu().add("重新打开：" + label(lostTask)).setEnabled(!working()).setOnMenuItemClickListener(item -> { if (lostTask != null) listener.reopen(lostTask); return true; });
        menu.getMenu().add("刷新外屏任务").setEnabled(!working()).setOnMenuItemClickListener(item -> { refresh(); return true; });
        menu.getMenu().add("返回全部应用").setOnMenuItemClickListener(item -> { listener.apps(); return true; });
        menu.getMenu().add("关闭任务页").setOnMenuItemClickListener(item -> { listener.close(); return true; });
    }
    private void showPageMenu(View anchor) { PopupMenu menu = new PopupMenu(getContext(), anchor); pageActions(menu); menu.show(); }
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
    private List<RecentTasks.Task> clearTargets() { return locks.unlocked(RecentTasks.backgroundTargets(deferred == null ? tasks : deferred, pinnedPackages())); }
    private boolean working() { return busy || pendingClose != null; }
    private boolean clearable(RecentTasks.Task task) { return known && canClear && !working() && !locks.contains(task) && (deferred == null ? tasks : deferred).stream().anyMatch(current -> RecentTasks.canClear(task, current)); }
    private int lastVisible() { return Math.min(tasks.size(), firstVisible + 3); }
    private void keepSelectedVisible() {
        carousel.position = Math.max(0, Math.min(carousel.position, Math.max(0, tasks.size() - 3)));
        firstVisible = Math.round(carousel.position);
        if (selected < firstVisible || selected >= lastVisible()) { firstVisible = Math.max(0, Math.min(selected - 1, tasks.size() - 3)); carousel.position = firstVisible; }
    }
    RecentTasks.Task selectedTask() { return tasks.isEmpty() ? null : tasks.get(selected); }
    void select(RecentTasks.Task task) {
        carousel.stopFling(); carousel.stopPageMotion(); carousel.edge(0);
        if (task != null) for (int i = 0; i < tasks.size(); i++) if (RecentTasks.sameTask(task, tasks.get(i))) { selected = i; break; }
        keepSelectedVisible(); render();
    }
    void data(List<RecentTasks.Task> next, boolean known, boolean busy, boolean canOpen, boolean canClear, boolean canSnapshot, String message) {
        this.known = known; this.busy = busy; this.canOpen = canOpen; this.canClear = canClear; this.canSnapshot = canSnapshot;
        state = message;
        if (!known) releasePreviews();
        if (carousel.tracking || carousel.flinging || carousel.pageMotion != null) { deferred = List.copyOf(next); updateControls(); return; }
        replace(next);
    }
    private void replace(List<RecentTasks.Task> next) {
        if (pendingClose != null && !busy) {
            RecentTasks.Task closing = pendingClose; pendingClose = null;
            if (!known || next.stream().anyMatch(task -> RecentTasks.sameTask(closing, task))) {
                carousel.resetCards();
                if (known) message("未能关闭此任务，请重试");
            }
        }
        RecentTasks.Task current = selectedTask(); boolean changed = !tasks.equals(next); tasks = List.copyOf(next);
        selected = Math.max(0, Math.min(selected, tasks.size() - 1));
        if (current != null) for (int i = 0; i < tasks.size(); i++) if (RecentTasks.sameTask(current, tasks.get(i))) { selected = i; break; }
        keepSelectedVisible();
        if (changed || tasks.isEmpty()) render(); else { updateControls(); requestPreviews(); }
    }
    private void render() { carousel.render(); updateControls(); requestPreviews(); }
    private void updateControls() {
        String position = tasks.isEmpty() ? "外屏任务" : (firstVisible + 1) + "–" + lastVisible() + " / " + tasks.size();
        String description = busy ? "正在处理…" : state;
        if (!busy && known && (state.startsWith("图标模式") || state.startsWith("左右切换"))) description = "↑关闭  ↓锁定";
        if (lostTask != null && !working()) description = "窗口已结束，长按可重新打开";
        setContentDescription(position + "，" + description + "，长按空白处刷新或返回全部应用");
        int count = clearTargets().size(); clear.setContentDescription("关闭 " + count + " 个后台任务，保留锁定、固定和可见任务"); clear.setTooltipText(clear.getContentDescription());
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
        if (!checked.isEmpty()) { lostTask = null; listener.clear(List.copyOf(checked)); }
    }
    private void dismissTask(RecentTasks.Task task) {
        if (!clearable(task)) {
            message(locks.contains(task) ? "已锁定，先下滑解锁" : task.visible() ? "此任务正在使用，不能关闭" : "当前连接无法关闭此任务"); return;
        }
        pendingClose = task; lostTask = null; carousel.liftForClose(task); updateControls(); listener.closeTask(task);
    }
    private void toggleLock(RecentTasks.Task task) {
        if (!known || working() || (deferred == null ? tasks : deferred).stream().noneMatch(current -> RecentTasks.sameTask(task, current))) return;
        boolean changed = locks.toggle(task);
        String message = changed ? locks.contains(task) ? "已锁定，本工具清理会跳过此窗口" : "已解锁" : "最多锁定32个窗口";
        updateControls(); message(message);
    }
    void catalogChanged() { carousel.bindCards(); }
    private String action(RecentTasks.Task task) { String launcher = cache.launcher(task.packageName()); return launcher == null ? "app:" + task.component() : launcher; }
    private String label(RecentTasks.Task task) { String label = cache.label(action(task)); return label == null ? task.packageName() : label; }
    private void requestPreviews() {
        Set<String> live = new HashSet<>();
        for (RecentTasks.Task task : deferred == null ? tasks : deferred) live.add(RecentTasks.key(task));
        attempted.retainAll(live);
        List<Bitmap> expired = new ArrayList<>();
        for (String key : new ArrayList<>(previews.keySet())) if (!live.contains(key)) { Bitmap bitmap = previews.remove(key).bitmap(); if (bitmap != null) expired.add(bitmap); }
        if (!expired.isEmpty()) { carousel.bindCards(); for (Bitmap bitmap : expired) bitmap.recycle(); }
        if (!known || working() || !canSnapshot || memoryTrimmed || snapshotPending || carousel.tracking || carousel.flinging || carousel.pageMotion != null || !isAttachedToWindow() || !isShown()) return;
        // Only the three nearest cards request previews after scrolling settles; failed reads do not poll.
        for (int index = firstVisible; index < lastVisible(); index++) {
            RecentTasks.Task task = tasks.get(index); String key = RecentTasks.key(task);
            if (!attempted.add(key)) continue;
            int token = generation; snapshotPending = true;
            listener.snapshot(task, result -> {
                if (token != generation || !isAttachedToWindow()) { if (result.bitmap() != null) result.bitmap().recycle(); return; }
                snapshotPending = false;
                boolean current = (deferred == null ? tasks : deferred).stream().anyMatch(item -> RecentTasks.sameTask(task, item));
                // Cache successful reads for this page, even if the user scrolled away meanwhile.
                if (current && known && !memoryTrimmed && previews.size() < 32) previews.put(key, result); else if (result.bitmap() != null) result.bitmap().recycle();
                carousel.bindCards(); previewsVisible();
            });
            return;
        }
    }
    private void releasePreviews() { generation++; snapshotPending = false; List<ShizukuBridge.Snapshot> old = new ArrayList<>(previews.values()); previews.clear(); attempted.clear(); if (carousel != null) carousel.bindCards(); for (ShizukuBridge.Snapshot snapshot : old) if (snapshot.bitmap() != null) snapshot.bitmap().recycle(); }
    void dispose() { blankTap = false; pendingClose = null; removeCallbacks(loadPreviews); animate().cancel(); carousel.cancel(); glass = null; glassReady(false); releasePreviews(); deferred = null; tasks = List.of(); carousel.clearCards(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); getContext().getApplicationContext().registerComponentCallbacks(this); carousel.bindCards(); previewsVisible(); }
    @Override protected void onDetachedFromWindow() { getContext().getApplicationContext().unregisterComponentCallbacks(this); dispose(); super.onDetachedFromWindow(); }
    @Override public void onTrimMemory(int level) { if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) { memoryTrimmed = true; releasePreviews(); } }
    @Override public void onLowMemory() { memoryTrimmed = true; releasePreviews(); }
    @Override public void onConfigurationChanged(Configuration configuration) { releasePreviews(); }

    private final class Card extends LinearLayout {
        RecentTasks.Task task;
        final TextView name, caption;
        final TaskPreviewView picture;
        final ImageView lockBadge;
        int position;
        Card(Context context) {
            super(context); setOrientation(VERTICAL); setFocusable(true);
            FrameLayout label = new FrameLayout(context);
            name = Ui.heading(context, "", PanelUi.SECONDARY); name.setGravity(Gravity.CENTER); name.setSingleLine(); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(dp(14), 0, dp(14), 0); label.addView(name, new FrameLayout.LayoutParams(-1, -1));
            lockBadge = new ImageView(context); lockBadge.setImageDrawable(Ui.icon(context, R.drawable.ic_ms_lock, 0xFFFFCE75)); lockBadge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); label.addView(lockBadge, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER_VERTICAL | Gravity.END)); addView(label, new LayoutParams(-1, dp(24)));
            picture = new TaskPreviewView(context); picture.setDuplicateParentStateEnabled(true); addView(picture, new LayoutParams(-1, 0, 1));
            caption = Ui.text(context, "", PanelUi.SECONDARY, 0xFFDCE3ED); caption.setGravity(Gravity.CENTER); caption.setSingleLine(); caption.setEllipsize(TextUtils.TruncateAt.END); caption.setPadding(0, dp(PanelUi.GAP), 0, 0); addView(caption, new LayoutParams(-1, dp(20)));
            name.setShadowLayer(dp(1.5f), 0, dp(.5f), 0xCC101820); caption.setShadowLayer(dp(1.5f), 0, dp(.5f), 0xCC101820);
            setOnClickListener(v -> { selected = position; open(task); });
            setOnLongClickListener(v -> {
                if (!known || working()) return true;
                RecentTasks.Task chosen = task; PopupMenu menu = new PopupMenu(context, this);
                menu.getMenu().add(locks.contains(chosen) ? "解锁此窗口" : "锁定此窗口").setOnMenuItemClickListener(item -> { toggleLock(chosen); return true; });
                menu.getMenu().add("关闭此窗口").setEnabled(clearable(chosen)).setOnMenuItemClickListener(item -> { dismissTask(chosen); return true; }); pageActions(menu); menu.show(); return true;
            });
        }
        void assign(RecentTasks.Task next, int index) {
            animate().cancel(); setPressed(false); setStateDescription(null); setTranslationX(0); setTranslationY(0); setAlpha(1); picture.resetPull();
            task = next; position = index; setTag("task-card:" + next.id()); picture.setTag("task-preview:" + next.id()); lockBadge.setTag("task-lock:" + next.id());
            bind(); jumpDrawablesToCurrentState(); picture.jumpDrawablesToCurrentState();
        }
        void recycle() { animate().cancel(); setPressed(false); picture.resetPull(); picture.content(null, null); task = null; setTag(null); }
        void bind() {
            if (task == null) return;
            String text = label(task); long count = tasks.stream().filter(item -> item.packageName().equals(task.packageName())).count();
            if (count > 1) { int ordinal = 0; for (int i = 0; i <= position; i++) if (tasks.get(i).packageName().equals(task.packageName())) ordinal++; text += " · " + ordinal; }
            if (!text.contentEquals(name.getText())) name.setText(text);
            ShizukuBridge.Snapshot preview = previews.get(RecentTasks.key(task)); Bitmap bitmap = preview == null ? null : preview.bitmap();
            android.graphics.drawable.Drawable icon = cache.cachedIcon(getContext(), action(task));
            picture.content(bitmap, icon == null ? Ui.icon(getContext(), R.drawable.ic_ms_apps, Ui.ACCENT) : icon);
            if (bitmap == null && icon == null && isAttachedToWindow()) cache.requestIcon(action(task));
            boolean locked = locks.contains(task);
            String detail = pendingClose != null && RecentTasks.sameTask(task, pendingClose) ? "正在关闭…" : locked ? "已锁定" : task.visible() ? "使用中" : pinnedPackages().contains(task.packageName()) ? "固定" : bitmap != null ? "上次画面" : "无画面预览";
            if (!detail.contentEquals(caption.getText())) caption.setText(detail);
            lockBadge.setVisibility(locked ? VISIBLE : GONE); setSelected(locked);
            if (glass != null && glass.active()) glass.bind(picture, GlassSurface.Role.TASK_PREVIEW);
            picture.glass(glassReady);
            picture.locked(locked);
            setEnabled(known && !working()); setContentDescription(text + "，" + detail + "，点击返回原窗口，上滑关闭，下滑" + (locked ? "解锁" : "锁定") + "，长按更多操作");
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(info); if (known && !working()) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(LOCK_ACTION, locks.contains(task) ? "解锁此窗口" : "锁定此窗口")); if (clearable(task)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS); }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) { if (action == LOCK_ACTION && known && !working()) { toggleLock(task); return true; } if (action == AccessibilityNodeInfo.ACTION_DISMISS && clearable(task)) { dismissTask(task); return true; } return super.performAccessibilityAction(action, args); }
    }
    private final class Carousel extends FrameLayout {
        private float startX, startY;
        private int axis;
        private RecentTasks.Task pressed;
        private boolean tracking;
        private float startOffset, startVertical, position, edgeOffset;
        private final android.widget.OverScroller scroller;
        private android.view.VelocityTracker velocity;
        private boolean flinging;
        private final int minFling, maxFling;
        private boolean armed;
        private ValueAnimator pageMotion;
        private final int slop;
        Carousel(Context context) { super(context); ViewConfiguration configuration = ViewConfiguration.get(context); slop = configuration.getScaledTouchSlop(); minFling = configuration.getScaledMinimumFlingVelocity(); maxFling = configuration.getScaledMaximumFlingVelocity(); scroller = new android.widget.OverScroller(context); setPadding(dp(6), 0, dp(6), 0); setClipChildren(true); setClipToPadding(true); setOnLongClickListener(v -> { blankTap = false; showPageMenu(clear); return true; }); }
        void render() {
            edgeOffset = 0; recycleCards();
            if (tasks.isEmpty()) { TextView empty = Ui.text(getContext(), known ? "暂无外屏任务\n长按空白处可返回全部应用" : "等待任务连接\n长按空白处可刷新或返回应用", 13, Ui.TEXT); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), 0, dp(20), 0); empty.setShadowLayer(dp(1.5f), 0, dp(.5f), 0xCC101820); addView(empty, new FrameLayout.LayoutParams(-1, -1)); return; }
            syncVisibleCards();
        }
        private final java.util.ArrayDeque<Card> recycled = new java.util.ArrayDeque<>();
        private void recycleCards() {
            for (int i = getChildCount() - 1; i >= 0; i--) { View child = getChildAt(i); removeViewAt(i); if (child instanceof Card card) { card.recycle(); if (recycled.size() < 4) recycled.add(card); } }
        }
        private boolean syncVisibleCards() {
            int from = Math.max(0, (int) Math.floor(position)), to = Math.min(tasks.size(), (int) Math.ceil(position + 3)); boolean changed = false;
            for (int i = getChildCount() - 1; i >= 0; i--) if (getChildAt(i) instanceof Card card && (card.position < from || card.position >= to)) {
                removeViewAt(i); card.recycle(); recycled.add(card); changed = true;
            }
            for (int index = from; index < to; index++) {
                int slot = index - from;
                if (slot < getChildCount() && getChildAt(slot) instanceof Card existing && existing.position == index) continue;
                Card card = recycled.poll(); if (card == null) card = new Card(getContext());
                card.assign(tasks.get(index), index); addView(card, slot); card.bind(); changed = true;
            }
            return changed;
        }
        void clearCards() { recycleCards(); recycled.clear(); }
        void bindCards() { for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) card.bind(); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec); setMeasuredDimension(width, height);
            measureCards(width, height);
        }
        private void measureCards(int width, int height) {
            int cardWidth = Math.max(1, (width - dp(12) - dp(5) * 2) / 3);
            // Keep compact proportions; the minimum protects labels and the preview on narrow screens.
            int availableHeight = Math.max(0, height - dp(8)), cardHeight = Math.min(Math.max(0, height - dp(56)), Math.max(dp(112), Math.round(cardWidth * 1.5f)));
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(tasks.isEmpty() ? width : cardWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tasks.isEmpty() ? availableHeight : cardHeight, MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { layoutCards(); }
        private void layoutCards() {
            int count = getChildCount(), width = count == 0 ? 0 : getChildAt(0).getMeasuredWidth(), shown = tasks.isEmpty() ? 1 : Math.min(3, tasks.size()), start = (getWidth() - shown * width - Math.max(0, shown - 1) * dp(5)) / 2;
            for (int i = 0; i < count; i++) { View view = getChildAt(i); int index = view instanceof Card card ? card.position : 0; int x = start + index * (width + dp(5)), y = Math.max(0, (getHeight() - dp(52) - view.getMeasuredHeight()) / 2); view.layout(x, y, x + width, y + view.getMeasuredHeight()); view.setTranslationX(edgeOffset); }
            scrollTo(Math.round(position * step()), 0);
        }
        private float verticalThreshold() { return Math.max(dp(36), Math.min(dp(64), getHeight() * .22f)); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (pendingClose != null) return true;
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                stopFling(); stopPageMotion(); recycleVelocity(); velocity = android.view.VelocityTracker.obtain(); tracking = true; startX = event.getX(); startY = event.getY(); axis = 0; pressed = null; armed = false; startVertical = 0;
                startOffset = position * step() - edgeDistance(edgeOffset);
                for (int i = 0; i < getChildCount(); i++) { View view = getChildAt(i); view.animate().cancel(); view.animate().alpha(1).setDuration(duration()).start(); if (view instanceof Card card && startX + getScrollX() >= view.getX() && startX + getScrollX() < view.getX() + view.getWidth() && startY >= view.getY() && startY < view.getY() + view.getHeight()) { pressed = card.task; selected = card.position; startVertical = view.getTranslationY(); } }
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) axis = 3;
            if (velocity != null) velocity.addMovement(event);
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { tracking = false; recycleVelocity(); if (pageMotion == null) resetCards(); finishDeferred(); }
            return handled;
        }
        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) return true;
            if (event.getActionMasked() != MotionEvent.ACTION_MOVE) return false;
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (axis == 0 && Math.hypot(dx, dy) > slop) axis = Math.abs(dx) > Math.abs(dy) * 1.4f ? 1 : Math.abs(dy) > Math.abs(dx) * 1.4f ? 2 : 3;
            return axis != 0;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (pressed == null) super.onTouchEvent(event);
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE && axis == 1) {
                if (!tasks.isEmpty()) scrollWithEdge(startOffset - dx);
            }
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) {
                if (axis == 2 && pressed != null && RecentTasks.sameTask(pressed, card.task) && known && !busy) {
                    boolean allowed = dy > 0 || clearable(card.task);
                    float limit = dp(allowed ? 110 : 12), travel = (float) Math.copySign(limit * (1 - Math.exp(-Math.abs(dy) / Math.max(1, limit))), dy);
                    card.setTranslationY(startVertical + travel);
                    float pull = Math.min(1, Math.abs(dy) / verticalThreshold()) * (allowed ? 1 : .25f);
                    card.picture.pull(pull);
                    card.setAlpha(dy < 0 && allowed ? Math.max(.78f, 1 + dy / Math.max(1, getHeight()) * .3f) : 1);
                    String release = Math.abs(dy) > verticalThreshold() ? "松手" : "继续滑动";
                    boolean nextArmed = allowed && Math.abs(dy) > verticalThreshold();
                    if (nextArmed && !armed && prefs.haptics()) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); armed = nextArmed;
                    card.setStateDescription(dy >= 0 ? release + (locks.contains(card.task) ? "解锁" : "锁定") : locks.contains(card.task) ? "已锁定，先下滑解锁" : clearable(card.task) ? release + "关闭此窗口" : "此任务受保护，保留");
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                updateControls();
                if (axis == 1) { if (edgeOffset != 0) springEdge(); else startFling(); performClick(); return true; } else if (axis == 2 && pressed != null && Math.abs(dy) > verticalThreshold() && Math.abs(dy) > Math.abs(dx) * 1.4f) { if (dy > 0) toggleLock(pressed); else { dismissTask(pressed); if (pendingClose != null) { performClick(); return true; } } }
                resetCards(); performClick();
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) { axis = 3; resetCards(); updateControls(); }
            return true;
        }
        private void resetCards() {
            if (edgeOffset != 0) springEdge();
            for (int i = 0; i < getChildCount(); i++) {
                View view = getChildAt(i); view.animate().translationY(0).alpha(1).setInterpolator(new android.view.animation.OvershootInterpolator(1.1f)).setDuration(duration() == 0 ? 0 : 300).start();
                if (view instanceof Card card) card.picture.spring();
            }
        }
        private Card card(RecentTasks.Task task) { for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card && RecentTasks.sameTask(card.task, task)) return card; return null; }
        void liftForClose(RecentTasks.Task task) {
            Card card = card(task); if (card == null) return;
            // Continue from the finger's current position; never reverse into a waiting ledge.
            card.picture.releaseUp(); animateCard(card, -getHeight(), 0, 360, this::finishDeferred);
        }
        private void animateCard(Card card, float targetY, float targetAlpha, long millis, Runnable complete) {
            stopPageMotion(); card.animate().cancel(); float fromY = card.getTranslationY(), fromAlpha = card.getAlpha();
            pageMotion = ValueAnimator.ofFloat(0, 1); pageMotion.setDuration(duration() == 0 ? 0 : millis); pageMotion.setInterpolator(new android.view.animation.PathInterpolator(.2f, .55f, .5f, 1));
            pageMotion.addUpdateListener(animation -> { float value = (float) animation.getAnimatedValue(); card.setTranslationY(fromY + (targetY - fromY) * value); card.setAlpha(fromAlpha + (targetAlpha - fromAlpha) * value); });
            pageMotion.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { if (pageMotion != animation) return; pageMotion = null; complete.run(); } }); pageMotion.start();
        }
        private float edgeLimit() { return Math.max(dp(64), getWidth() * .35f); }
        private float edgeDistance(float value) { return (float) Math.copySign(-edgeLimit() * Math.log(Math.max(.0001f, 1 - Math.abs(value) / edgeLimit())), value); }
        private void scrollWithEdge(float offset) {
            float bounded = Math.max(0, Math.min(offset, Math.max(0, tasks.size() - 3) * step())), excess = bounded - offset;
            moveTo(bounded / step());
            edge((float) Math.copySign(edgeLimit() * (1 - Math.exp(-Math.abs(excess) / edgeLimit())), excess));
        }
        private void edge(float value) { edgeOffset = value; for (int i = 0; i < getChildCount(); i++) getChildAt(i).setTranslationX(value); }
        private void springEdge() {
            stopPageMotion();
            if (edgeOffset == 0) return;
            if (!ValueAnimator.areAnimatorsEnabled()) { edge(0); return; }
            pageMotion = ValueAnimator.ofFloat(edgeOffset, 0); pageMotion.setDuration(380); pageMotion.setInterpolator(new android.view.animation.OvershootInterpolator(1.15f));
            pageMotion.addUpdateListener(animation -> edge((float) animation.getAnimatedValue()));
            pageMotion.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { if (pageMotion != animation) return; pageMotion = null; edge(0); finishDeferred(); } }); pageMotion.start();
        }
        private float step() { return Math.max(1, (getMeasuredWidth() - dp(12) - dp(5) * 2) / 3 + dp(5)); }
        private void moveTo(float next) {
            position = Math.max(0, Math.min(next, Math.max(0, tasks.size() - 3)));
            if (syncVisibleCards() && getWidth() > 0 && getHeight() > 0) { measureCards(getWidth(), getHeight()); layoutCards(); }
            scrollTo(Math.round(position * step()), 0);
            int nearest = Math.round(position);
            if (firstVisible != nearest) { firstVisible = nearest; selected = Math.max(firstVisible, Math.min(selected, lastVisible() - 1)); updateControls(); }
        }
        private void startFling() {
            if (velocity == null || tasks.size() <= 3) return;
            velocity.computeCurrentVelocity(1000, maxFling); int speed = Math.round(-velocity.getXVelocity());
            if (Math.abs(speed) < minFling) return;
            flinging = true; scroller.fling(getScrollX(), 0, speed, 0, 0, Math.round((tasks.size() - 3) * step()), 0, 0, dp(36), 0); postOnAnimation(advanceFling);
        }
        private final Runnable advanceFling = new Runnable() {
            @Override public void run() {
                if (!flinging) return;
                if (scroller.computeScrollOffset()) { scrollWithEdge(scroller.getCurrX()); postOnAnimation(this); }
                else { flinging = false; edge(0); finishDeferred(); }
            }
        };
        private void stopFling() { removeCallbacks(advanceFling); scroller.abortAnimation(); flinging = false; }
        private void recycleVelocity() { if (velocity != null) { velocity.recycle(); velocity = null; } }
        private void finishDeferred() { if (tracking || flinging || pageMotion != null) return; if (deferred != null) { List<RecentTasks.Task> next = deferred; deferred = null; replace(next); } requestPreviews(); }
        private void stopPageMotion() { if (pageMotion != null) { pageMotion.removeAllListeners(); pageMotion.cancel(); pageMotion = null; } }
        void cancel() { stopFling(); recycleVelocity(); stopPageMotion(); edgeOffset = 0; tracking = false; axis = 3; pressed = null; armed = false; for (int i = 0; i < getChildCount(); i++) { View child = getChildAt(i); child.animate().cancel(); child.setTranslationX(0); child.setTranslationY(0); child.setAlpha(1); if (child instanceof Card card) card.picture.resetPull(); } }
        @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (oldw > 0 && oldh > 0 && (w != oldw || h != oldh)) { cancel(); finishDeferred(); }
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setClassName(android.widget.HorizontalScrollView.class.getName()); info.setScrollable(tasks.size() > 3);
            if (position > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            if (position < tasks.size() - 3) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
            if (!working() && (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                stopFling(); stopPageMotion(); edge(0); moveTo(position + (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD ? 1 : -1)); finishDeferred(); return true;
            }
            return super.performAccessibilityAction(action, args);
        }
        @Override public boolean performClick() { return super.performClick(); }
    }
}
