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

/** Three complete peer cards and one compact toolbar. Task locks and previews stay in memory. */
final class RecentTasksView extends LinearLayout implements ComponentCallbacks2 {
    interface Listener {
        void open(RecentTasks.Task task); void clear(List<RecentTasks.Task> tasks); void refresh();
        void apps(); void close(); void reopen(RecentTasks.Task task);
        void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback);
    }
    private static final int LOCK_ACTION = R.id.action_task_lock;
    private final Listener listener;
    private final Prefs prefs;
    private final AppCatalogCache cache;
    private final RecentTasks.Locks locks;
    private final Carousel carousel;
    private final TextView title, status;
    private final View clear, reopen, apps, refresh;
    private final Map<String, ShizukuBridge.Snapshot> previews = new HashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private List<RecentTasks.Task> tasks = List.of(), deferred;
    private boolean known, busy, canOpen, canClear, canSnapshot, snapshotPending, memoryTrimmed;
    private int selected, firstVisible, generation;
    private RecentTasks.Task lostTask;
    private float panelProgress = 1;
    private String state = "正在读取外屏任务…";

    RecentTasksView(Context context, Prefs prefs, int edge, Listener listener) {
        super(context); this.listener = listener; this.prefs = prefs; cache = CoverApp.catalog(context); locks = CoverApp.taskLocks(context);
        setOrientation(VERTICAL); setTag("recent-tasks"); setBackground(Ui.background(context, Ui.SURFACE, 20));
        PanelSurface surface = new PanelSurface(context, edge, dp(240), prefs.haptics(), new PanelHeaderView.Listener() {
            public void begin() { animate().cancel(); }
            public float currentProgress() { return panelProgress; }
            public void progress(float value) {
                panelProgress = value; float distance = (1 - value) * dp(240);
                setTranslationX(edge == DockGeometry.LEFT ? -distance : edge == DockGeometry.RIGHT ? distance : 0);
                setTranslationY(edge == DockGeometry.TOP ? -distance : edge == DockGeometry.BOTTOM ? distance : 0);
            }
            public void finish(boolean close) { if (close) listener.close(); else { panelProgress = 1; animate().translationX(0).translationY(0).setDuration(duration()).start(); } }
        });
        addView(surface, new LayoutParams(-1, -1));
        LinearLayout header = Ui.row(context); header.setTag("tasks-toolbar");
        clear = smallIcon(R.drawable.ic_ms_close, "一键清理未锁定的后台任务", () -> dismiss(clearTargets())); clear.setTag("tasks-clear");
        LinearLayout words = Ui.column(context); words.setGravity(Gravity.CENTER_VERTICAL); words.setPadding(dp(12), 0, 0, 0); words.setTag("tasks-refresh"); words.setFocusable(true);
        words.setOnClickListener(v -> { if (!busy) { lostTask = null; releasePreviews(); listener.refresh(); } }); refresh = words;
        title = Ui.heading(context, "外屏任务", 12); title.setSingleLine(); title.setEllipsize(TextUtils.TruncateAt.END); title.setAccessibilityHeading(true); words.addView(title, new LayoutParams(-1, -2));
        status = Ui.text(context, state, 9, Ui.MUTED); status.setTag("tasks-status"); status.setSingleLine(); status.setEllipsize(TextUtils.TruncateAt.END); status.setPadding(0, dp(3), 0, 0); words.addView(status, new LayoutParams(-1, -2)); header.addView(words, new LayoutParams(0, -1, 1));
        FrameLayout entry = new FrameLayout(context);
        apps = smallIcon(R.drawable.ic_ms_apps, "返回全部应用", listener::apps); apps.setTag("tasks-apps"); entry.addView(apps, new FrameLayout.LayoutParams(-1, -1));
        reopen = smallIcon(R.drawable.ic_ms_refresh, "重新打开已结束窗口的应用", () -> { if (lostTask != null && !busy) listener.reopen(lostTask); }); reopen.setTag("tasks-reopen"); entry.addView(reopen, new FrameLayout.LayoutParams(-1, -1)); reopen.setVisibility(GONE); header.addView(entry, new LayoutParams(dp(48), dp(48)));
        View close = smallIcon(R.drawable.ic_ms_arrow_back, "返回，关闭任务页", listener::close); close.setTag("tasks-close"); header.addView(close, new LayoutParams(dp(48), dp(48))); surface.addView(header, new LayoutParams(-1, dp(48)));
        FrameLayout stage = new FrameLayout(context); stage.setTag("tasks-stage"); surface.addView(stage, new LayoutParams(-1, 0, 1));
        carousel = new Carousel(context); carousel.setTag("task-carousel"); stage.addView(carousel, new FrameLayout.LayoutParams(-1, -1));
        FrameLayout.LayoutParams clearBounds = new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.BOTTOM | Gravity.LEFT); clearBounds.leftMargin = dp(4); clearBounds.bottomMargin = dp(4); stage.addView(clear, clearBounds);
        render();
    }
    private View smallIcon(int icon, String label, Runnable action) { View view = Ui.iconButton(getContext(), icon, label, action); view.setPadding(dp(14), dp(14), dp(14), dp(14)); view.setTooltipText(label); return view; }
    private int dp(float value) { return Ui.dp(getContext(), value); }
    private long duration() { return android.animation.ValueAnimator.areAnimatorsEnabled() ? 160 : 0; }
    private Set<String> pinnedPackages() { Set<String> result = new HashSet<>(); for (String id : prefs.hubPins()) { android.content.ComponentName name = ActionCatalog.component(id); if (name != null) result.add(name.getPackageName()); } return result; }
    private List<RecentTasks.Task> clearTargets() { return locks.unlocked(RecentTasks.backgroundTargets(deferred == null ? tasks : deferred, pinnedPackages())); }
    private boolean clearable(RecentTasks.Task task) { return known && canClear && !busy && clearTargets().stream().anyMatch(current -> RecentTasks.sameTask(task, current)); }
    private int lastVisible() { return Math.min(tasks.size(), firstVisible + 3); }
    private void keepSelectedVisible() { firstVisible = Math.max(0, Math.min(firstVisible, tasks.size() - 3)); if (selected < firstVisible || selected >= lastVisible()) firstVisible = Math.max(0, Math.min(selected - 1, tasks.size() - 3)); }
    RecentTasks.Task selectedTask() { return tasks.isEmpty() ? null : tasks.get(selected); }
    void select(RecentTasks.Task task) {
        if (task != null) for (int i = 0; i < tasks.size(); i++) if (RecentTasks.sameTask(task, tasks.get(i))) { selected = i; break; }
        keepSelectedVisible(); render();
    }
    void data(List<RecentTasks.Task> next, boolean known, boolean busy, boolean canOpen, boolean canClear, boolean canSnapshot, String message) {
        this.known = known; this.busy = busy; this.canOpen = canOpen; this.canClear = canClear; this.canSnapshot = canSnapshot;
        state = message;
        if (!known) releasePreviews();
        if (carousel.tracking || carousel.pageMotion != null) { deferred = List.copyOf(next); updateControls(); return; }
        replace(next);
    }
    private void replace(List<RecentTasks.Task> next) {
        RecentTasks.Task current = selectedTask(); boolean changed = !tasks.equals(next); tasks = List.copyOf(next);
        selected = Math.max(0, Math.min(selected, tasks.size() - 1));
        if (current != null) for (int i = 0; i < tasks.size(); i++) if (RecentTasks.sameTask(current, tasks.get(i))) { selected = i; break; }
        keepSelectedVisible();
        if (changed || tasks.isEmpty()) render(); else { updateControls(); requestPreviews(); }
    }
    private void render() { carousel.render(); updateControls(); requestPreviews(); }
    private void updateControls() {
        String position = tasks.isEmpty() ? "外屏任务" : (firstVisible + 1) + "–" + lastVisible() + " / " + tasks.size();
        if (!position.contentEquals(title.getText())) title.setText(position);
        String description = busy ? "正在处理…" : state;
        if (!busy && known && (state.startsWith("图标模式") || state.startsWith("左右切换"))) description = "↑关闭  ↓锁定";
        if (lostTask != null && !busy) description = "窗口已结束 · 右侧重开";
        if (!description.contentEquals(status.getText())) status.setText(description);
        refresh.setContentDescription(description + "，点击刷新外屏任务");
        int count = clearTargets().size(); clear.setContentDescription("关闭 " + count + " 个后台任务，保留锁定、固定和可见任务"); clear.setTooltipText(clear.getContentDescription());
        clear.setEnabled(known && canClear && !busy && count > 0); clear.setAlpha(clear.isEnabled() ? 1 : .3f); refresh.setEnabled(!busy); reopen.setEnabled(!busy);
        apps.setVisibility(lostTask == null ? VISIBLE : GONE); reopen.setVisibility(lostTask == null ? GONE : VISIBLE);
        carousel.bindCards();
    }
    void openFailure(RecentTasks.Task task, boolean gone, String message) { busy = false; state = message; lostTask = gone ? task : null; updateControls(); }
    private void open(RecentTasks.Task task) { if (known && canOpen && !busy) { lostTask = null; listener.open(task); } }
    private void dismiss(List<RecentTasks.Task> targets) {
        if (!known || !canClear || busy || targets.isEmpty()) return;
        List<RecentTasks.Task> allowed = clearTargets();
        List<RecentTasks.Task> checked = new ArrayList<>();
        for (RecentTasks.Task expected : targets) for (RecentTasks.Task current : allowed) if (RecentTasks.sameTask(expected, current)) checked.add(current);
        if (!checked.isEmpty()) { lostTask = null; listener.clear(List.copyOf(checked)); }
    }
    private void toggleLock(RecentTasks.Task task) {
        if (!known || busy || (deferred == null ? tasks : deferred).stream().noneMatch(current -> RecentTasks.sameTask(task, current))) return;
        boolean changed = locks.toggle(task);
        String message = changed ? locks.contains(task) ? "已锁定，本工具清理会跳过此窗口" : "已解锁" : "最多锁定32个窗口";
        updateControls(); status.setText(!changed ? "锁定已达上限" : locks.contains(task) ? "已锁定 · ↓解锁" : "已解锁"); announceForAccessibility(message);
    }
    void catalogChanged() { carousel.bindCards(); }
    private String action(RecentTasks.Task task) { String launcher = cache.launcher(task.packageName()); return launcher == null ? "app:" + task.component() : launcher; }
    private String label(RecentTasks.Task task) { String label = cache.label(action(task)); return label == null ? task.packageName() : label; }
    private boolean visible(RecentTasks.Task task) { for (int i = firstVisible; i < lastVisible(); i++) if (RecentTasks.sameTask(task, tasks.get(i))) return true; return false; }
    private void requestPreviews() {
        Set<String> nearby = new HashSet<>(); for (int i = firstVisible; i < lastVisible(); i++) nearby.add(RecentTasks.key(tasks.get(i)));
        for (String key : previews.keySet()) if (!nearby.contains(key) && previews.get(key).bitmap() != null) attempted.remove(key);
        previews.keySet().retainAll(nearby);
        if (!known || busy || !canSnapshot || memoryTrimmed || snapshotPending || carousel.tracking || carousel.pageMotion != null || !isAttachedToWindow() || !isShown()) return;
        // Only the three fully visible cards request a preview; failed reads do not poll.
        for (int index = firstVisible; index < lastVisible(); index++) {
            RecentTasks.Task task = tasks.get(index); String key = RecentTasks.key(task);
            if (!attempted.add(key)) continue;
            int token = generation; snapshotPending = true;
            listener.snapshot(task, result -> {
                if (token != generation || !isAttachedToWindow() || !isShown()) { if (result.bitmap() != null) result.bitmap().recycle(); return; }
                snapshotPending = false;
                if (visible(task) && known && !memoryTrimmed) previews.put(key, result); else if (result.bitmap() != null) result.bitmap().recycle();
                carousel.bindCards(); post(this::requestPreviews);
            });
            return;
        }
    }
    private void releasePreviews() { generation++; snapshotPending = false; previews.clear(); attempted.clear(); if (carousel != null) carousel.bindCards(); }
    void dispose() { animate().cancel(); carousel.cancel(); releasePreviews(); deferred = null; tasks = List.of(); carousel.removeAllViews(); }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); getContext().getApplicationContext().registerComponentCallbacks(this); carousel.bindCards(); post(this::requestPreviews); }
    @Override protected void onDetachedFromWindow() { getContext().getApplicationContext().unregisterComponentCallbacks(this); dispose(); super.onDetachedFromWindow(); }
    @Override public void onTrimMemory(int level) { if (level >= TRIM_MEMORY_RUNNING_LOW && level != TRIM_MEMORY_UI_HIDDEN) { memoryTrimmed = true; releasePreviews(); } }
    @Override public void onLowMemory() { memoryTrimmed = true; releasePreviews(); }
    @Override public void onConfigurationChanged(Configuration configuration) { releasePreviews(); }

    private final class Card extends LinearLayout {
        final RecentTasks.Task task;
        final TextView name, caption;
        final ImageView picture, lockBadge;
        final int position;
        Card(Context context, RecentTasks.Task task, int position) {
            super(context); this.task = task; this.position = position; setOrientation(VERTICAL); setTag("task-card:" + task.id()); setPadding(dp(5), dp(8), dp(5), dp(8));
            setClipToOutline(true); setFocusable(true);
            name = Ui.heading(context, "", 10); name.setGravity(Gravity.CENTER); name.setMaxLines(2); name.setEllipsize(TextUtils.TruncateAt.END); name.setMinHeight(dp(30)); addView(name, new LayoutParams(-1, -2));
            FrameLayout preview = new FrameLayout(context);
            picture = new ImageView(context); picture.setTag("task-preview:" + task.id()); picture.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); picture.setPadding(dp(3), dp(6), dp(3), dp(6)); preview.addView(picture, new FrameLayout.LayoutParams(-1, -1));
            lockBadge = new ImageView(context); lockBadge.setImageDrawable(Ui.icon(context, R.drawable.ic_ms_lock, 0xFFFFCE75)); lockBadge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); lockBadge.setTag("task-lock:" + task.id()); preview.addView(lockBadge, new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP | Gravity.END)); addView(preview, new LayoutParams(-1, 0, 1));
            caption = Ui.text(context, "", 9, Ui.MUTED); caption.setGravity(Gravity.CENTER); caption.setSingleLine(); caption.setEllipsize(TextUtils.TruncateAt.END); caption.setPadding(0, dp(4), 0, 0); addView(caption, new LayoutParams(-1, -2));
            setOnClickListener(v -> { selected = position; open(task); });
            setOnLongClickListener(v -> {
                if (!known || busy) return true;
                PopupMenu menu = new PopupMenu(context, this);
                menu.getMenu().add(locks.contains(task) ? "解锁此窗口" : "锁定此窗口").setOnMenuItemClickListener(item -> { toggleLock(task); return true; });
                menu.getMenu().add("关闭此窗口").setEnabled(clearable(task)).setOnMenuItemClickListener(item -> { dismiss(List.of(task)); return true; }); menu.show(); return true;
            }); bind();
        }
        void bind() {
            String text = label(task); long count = tasks.stream().filter(item -> item.packageName().equals(task.packageName())).count();
            if (count > 1) { int ordinal = 0; for (int i = 0; i <= position; i++) if (tasks.get(i).packageName().equals(task.packageName())) ordinal++; text += " · " + ordinal; }
            if (!text.contentEquals(name.getText())) name.setText(text);
            ShizukuBridge.Snapshot preview = previews.get(RecentTasks.key(task)); Bitmap bitmap = preview == null ? null : preview.bitmap();
            if (bitmap != null) { picture.setScaleType(ImageView.ScaleType.FIT_CENTER); picture.setImageBitmap(bitmap); }
            else {
                picture.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
                android.graphics.drawable.Drawable icon = cache.cachedIcon(getContext(), action(task));
                picture.setImageDrawable(icon == null ? Ui.icon(getContext(), R.drawable.ic_ms_apps, Ui.ACCENT) : icon);
                if (icon == null && isAttachedToWindow()) cache.requestIcon(action(task));
            }
            boolean locked = locks.contains(task);
            String detail = locked ? "已锁定" : task.visible() ? "使用中" : pinnedPackages().contains(task.packageName()) ? "固定" : bitmap != null ? "上次画面" : "无画面预览";
            if (!detail.contentEquals(caption.getText())) caption.setText(detail);
            lockBadge.setVisibility(locked ? VISIBLE : GONE); setSelected(locked);
            android.graphics.drawable.GradientDrawable shape = Ui.background(getContext(), 0xFF292F39, 13); if (locked) shape.setStroke(dp(1), 0xFFFFCE75); setBackground(shape);
            setEnabled(known && !busy); setContentDescription(text + "，" + detail + "，点击返回原窗口，上滑关闭，下滑" + (locked ? "解锁" : "锁定") + "，长按更多操作");
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(info); if (known && !busy) info.addAction(new AccessibilityNodeInfo.AccessibilityAction(LOCK_ACTION, locks.contains(task) ? "解锁此窗口" : "锁定此窗口")); if (clearable(task)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS); }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) { if (action == LOCK_ACTION && known && !busy) { toggleLock(task); return true; } if (action == AccessibilityNodeInfo.ACTION_DISMISS && clearable(task)) { dismiss(List.of(task)); return true; } return super.performAccessibilityAction(action, args); }
    }
    private final class Carousel extends FrameLayout {
        private float startX, startY;
        private int axis;
        private RecentTasks.Task pressed;
        private boolean tracking;
        private float startOffset, startVertical;
        private boolean armed;
        private ValueAnimator pageMotion;
        private final int slop;
        Carousel(Context context) { super(context); slop = ViewConfiguration.get(context).getScaledTouchSlop(); setClipChildren(true); setClipToPadding(true); }
        void render() {
            removeAllViews();
            if (tasks.isEmpty()) { TextView empty = Ui.text(getContext(), known ? "暂无外屏任务\n从全部应用打开后会出现在这里" : "等待任务连接\n可返回全部应用继续使用", 13, Ui.MUTED); empty.setGravity(Gravity.CENTER); empty.setPadding(dp(20), 0, dp(20), 0); addView(empty, new FrameLayout.LayoutParams(-1, -1)); return; }
            for (int i = firstVisible; i < lastVisible(); i++) addView(new Card(getContext(), tasks.get(i), i));
        }
        void bindCards() { for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) card.bind(); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec); setMeasuredDimension(width, height);
            int cardWidth = Math.max(1, (width - dp(12) - dp(5) * 2) / 3);
            // Keep compact proportions; the minimum protects labels and the preview on narrow screens.
            int availableHeight = Math.max(0, height - dp(8)), cardHeight = Math.min(Math.max(0, height - dp(56)), Math.max(dp(112), Math.round(cardWidth * 1.5f)));
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(tasks.isEmpty() ? width : cardWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(tasks.isEmpty() ? availableHeight : cardHeight, MeasureSpec.EXACTLY));
        }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int count = getChildCount(), width = count == 0 ? 0 : getChildAt(0).getMeasuredWidth(), start = (getWidth() - count * width - Math.max(0, count - 1) * dp(5)) / 2;
            for (int i = 0; i < count; i++) { View view = getChildAt(i); int x = start + i * (width + dp(5)), y = (getHeight() - view.getMeasuredHeight()) / 2; if (view instanceof Card) y = Math.max(0, Math.min(y, getHeight() - view.getMeasuredHeight() - dp(52))); view.layout(x, y, x + width, y + view.getMeasuredHeight()); }
        }
        private float verticalThreshold() { return Math.max(dp(36), Math.min(dp(64), getHeight() * .22f)); }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                stopPageMotion(); tracking = true; startX = event.getX(); startY = event.getY(); axis = 0; pressed = null; armed = false; startVertical = 0;
                startOffset = getChildCount() == 0 ? 0 : getChildAt(0).getTranslationX();
                for (int i = 0; i < getChildCount(); i++) { View view = getChildAt(i); view.animate().cancel(); view.animate().alpha(1).setDuration(duration()).start(); if (view instanceof Card card && startX >= view.getX() && startX < view.getX() + view.getWidth() && startY >= view.getY() && startY < view.getY() + view.getHeight()) { pressed = card.task; selected = card.position; startVertical = view.getTranslationY(); } }
            }
            if (action == MotionEvent.ACTION_POINTER_DOWN) axis = 3;
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { tracking = false; if (pageMotion == null) resetCards(); finishDeferred(); }
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
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (event.getActionMasked() == MotionEvent.ACTION_MOVE) for (int i = 0; i < getChildCount(); i++) if (getChildAt(i) instanceof Card card) {
                if (axis == 1) { float limit = Math.max(dp(42), Math.abs(startOffset)); card.setTranslationX(Math.max(-limit, Math.min(limit, startOffset + dx * .4f))); }
                if (axis == 2 && pressed != null && RecentTasks.sameTask(pressed, card.task) && known && !busy) {
                    boolean allowed = dy > 0 || clearable(card.task);
                    float limit = Math.max(dp(70), Math.abs(startVertical)); card.setTranslationY(allowed ? Math.max(-limit, Math.min(limit, startVertical + dy * .65f)) : startVertical);
                    card.setAlpha(dy < 0 && allowed ? Math.max(.5f, 1 + dy / Math.max(1, getHeight())) : 1);
                    String release = Math.abs(dy) > verticalThreshold() ? "松手" : "继续滑动";
                    boolean nextArmed = allowed && Math.abs(dy) > verticalThreshold();
                    if (nextArmed && !armed && prefs.haptics()) performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); armed = nextArmed;
                    status.setText(dy >= 0 ? release + (locks.contains(card.task) ? "解锁" : "锁定") : locks.contains(card.task) ? "已锁定，先下滑解锁" : clearable(card.task) ? release + "关闭此窗口" : "此任务受保护，保留");
                }
            }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                updateControls();
                if (axis == 1 && Math.abs(dx) > Math.max(dp(32), getWidth() * .18f) && Math.abs(dx) > Math.abs(dy) * 1.4f) {
                    int next = Math.max(0, Math.min(Math.max(0, tasks.size() - 3), firstVisible + (dx < 0 ? 3 : -3)));
                    if (next != firstVisible) { changeGroup(next, dx < 0 ? -1 : 1); performClick(); return true; }
                } else if (axis == 2 && pressed != null && Math.abs(dy) > verticalThreshold() && Math.abs(dy) > Math.abs(dx) * 1.4f) { if (dy > 0) toggleLock(pressed); else dismiss(List.of(pressed)); }
                resetCards(); performClick();
            } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL || event.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN) { axis = 3; resetCards(); updateControls(); }
            return true;
        }
        private void resetCards() { for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().translationX(0).translationY(0).alpha(1).setDuration(duration()).start(); }
        private void changeGroup(int next, int direction) {
            if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) { firstVisible = next; selected = next; lostTask = null; RecentTasksView.this.render(); return; }
            animateGroup(direction * dp(56), 0, 80, () -> {
                firstVisible = Math.min(next, Math.max(0, tasks.size() - 3)); selected = firstVisible; lostTask = null; RecentTasksView.this.render();
                for (int i = 0; i < getChildCount(); i++) { getChildAt(i).setTranslationX(-direction * dp(42)); getChildAt(i).setAlpha(0); }
                animateGroup(0, 1, 100, this::finishDeferred);
            });
        }
        private void animateGroup(float targetX, float targetAlpha, long millis, Runnable complete) {
            stopPageMotion(); float fromX = getChildCount() == 0 ? 0 : getChildAt(0).getTranslationX(), fromAlpha = getChildCount() == 0 ? 1 : getChildAt(0).getAlpha();
            for (int i = 0; i < getChildCount(); i++) getChildAt(i).animate().cancel();
            pageMotion = ValueAnimator.ofFloat(0, 1); pageMotion.setDuration(millis); pageMotion.setInterpolator(new android.view.animation.DecelerateInterpolator());
            pageMotion.addUpdateListener(animation -> { float p = (float) animation.getAnimatedValue(); for (int i = 0; i < getChildCount(); i++) { View child = getChildAt(i); child.setTranslationX(fromX + (targetX - fromX) * p); child.setAlpha(fromAlpha + (targetAlpha - fromAlpha) * p); } });
            pageMotion.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { if (pageMotion != animation) return; pageMotion = null; complete.run(); } }); pageMotion.start();
        }
        private void finishDeferred() { if (tracking || pageMotion != null) return; if (deferred != null) { List<RecentTasks.Task> next = deferred; deferred = null; replace(next); } requestPreviews(); }
        private void stopPageMotion() { if (pageMotion != null) { pageMotion.removeAllListeners(); pageMotion.cancel(); pageMotion = null; } }
        void cancel() { stopPageMotion(); tracking = false; for (int i = 0; i < getChildCount(); i++) { View child = getChildAt(i); child.animate().cancel(); child.setTranslationX(0); child.setTranslationY(0); child.setAlpha(1); } }
        @Override public boolean performClick() { return super.performClick(); }
    }
}
