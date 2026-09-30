package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded three-page workspace; the drag visual never leaves the existing overlay window. */
final class AppWorkspaceView extends ViewGroup {
    interface Listener { void launch(String id); void menu(View anchor, String id); default void changed() { } default boolean dockDrag(String id, float x, float y, boolean commit) { return false; } default boolean outsideDragVisual() { return false; } default void dragVisualChanged() { } }
    abstract static class CellAdapter extends BaseAdapter {
        abstract View bind(AppCatalogCache.Entry entry, View reusable, ViewGroup parent);
        abstract int minimumHeight(int width);
        abstract View folder(AppWorkspaceLayout.Folder folder, View reusable, ViewGroup parent);
    }
    record State(int slot, int manualSlot) { }
    private final Prefs prefs;
    private final CellAdapter adapter;
    private final Listener listener;
    private final AppDragController gestures;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Map<String, View> cells = new LinkedHashMap<>();
    private final Map<String, Integer> indices = new HashMap<>();
    private final Map<String, AppCatalogCache.Entry> entries = new HashMap<>();
    private AppWorkspaceLayout saved, displayed = new AppWorkspaceLayout(), preview, undo, mergeBase;
    private String mergeTarget, mergeMessage = "";
    private final RectF mergeBounds = new RectF();
    private long mergeStarted;
    private boolean mergeReady, dockDrop, undoCompact, dockOnly, dockSource;
    private final Runnable mergeTick = this::advanceMerge;
    private int columns = AppLauncherStyle.GRID_COLUMNS, rows = AppLauncherStyle.GRID_ROWS, page, manualSlot, pendingSlot = -1, target = -1, bindingGeneration;
    private int measuredWidth, measuredGridHeight;
    private boolean manual, compact, deferred, disposed, dirty = true, editing;
    boolean editing() { return editing; }
    void editing(boolean value) { cancelInteraction(); editing = value; invalidate(); setStateDescription(value ? "正在整理，长按拖动；排序菜单可完成或撤销" : "浏览应用"); }
    private String dragged, settling;
    private View shadow;
    private float dragX, dragY, offset, shadowScale = 1.10f;
    private ValueAnimator pager, landing;
    private final Runnable refreshDeferred = this::refresh;
    private final DataSetObserver observer = new DataSetObserver() { @Override public void onChanged() { refresh(); } };
    AppWorkspaceView(Context context, Prefs prefs, CellAdapter adapter, Listener listener) {
        super(context); this.prefs = prefs; this.adapter = adapter; this.listener = listener; saved = prefs.workspace(); compact = prefs.workspaceCompact();
        setTag("hub-grid"); setClipChildren(true); setWillNotDraw(false); setFocusable(true); setHapticFeedbackEnabled(prefs.haptics());
        gestures = new AppDragController(this); adapter.registerDataSetObserver(observer);
    }
    int dp(float value) { return Ui.dp(getContext(), value); }
    BaseAdapter getAdapter() { return adapter; }
    int getCount() { return adapter.getCount(); }
    int getNumColumns() { return columns; }
    int capacity() { return columns * rows; }
    int page() { return page; }
    int pageCount() { return displayed.pages(capacity()); }
    int gridHeight() { return Math.max(1, getMeasuredHeight() - dp(AppLauncherStyle.WORKSPACE_PAGER_HEIGHT)); }
    int edgeWidth() { return Math.min(dp(24), getWidth() / 6); }
    boolean editable() { return manual && !prefs.workspaceLocked(); }
    boolean draggable() { return !prefs.workspaceLocked(); }
    boolean dragging() { return dragged != null; }
    boolean validDrop() { return dockDrop || target >= 0 && (mergeTarget == null || mergeReady); }
    int bindingGeneration() { return bindingGeneration; }
    AppWorkspaceLayout layoutSnapshot() { return saved; }
    AppWorkspaceLayout projected() { return saved.project(columns, rows); }
    boolean canUndo() { return undo != null && !prefs.workspaceLocked(); }
    boolean mergeReady() { return mergeReady; }
    String mergeTarget() { return mergeTarget; }
    long mergeElapsed() { return mergeTarget == null ? 0 : Math.max(0, SystemClock.uptimeMillis() - mergeStarted); }
    void undo() { if (!canUndo()) return; AppWorkspaceLayout previous = undo; boolean previousCompact = undoCompact; cancelInteraction(); saved = previous; compact = previousCompact; undo = null; prefs.saveWorkspace(saved, compact); refresh(); listener.changed(); }
    void change(AppWorkspaceLayout next) {
        if (prefs.workspaceLocked()) throw new IllegalArgumentException("桌面布局已锁定");
        cancelInteraction(); save(next); refresh(); listener.changed();
    }
    private void save(AppWorkspaceLayout next) { if (compact) next = next.compact(); if (!next.equals(saved)) { undo = saved; undoCompact = compact; saved = next; prefs.saveWorkspace(saved, compact); } }
    State state() { return new State(page * capacity(), manual ? page * capacity() : manualSlot); }
    void restore(State state) { if (state != null) { pendingSlot = state.slot(); manualSlot = state.manualSlot(); requestLayout(); } }
    void mode(boolean value) {
        if (manual == value) return; cancelInteraction();
        if (manual) manualSlot = page * capacity();
        manual = value; pendingSlot = value ? manualSlot : 0; dirty = true;
    }
    void refresh() {
        if (disposed) return;
        if (gestures.active()) {
            boolean exists = saved.folder(dragged) != null; for (int i = 0; i < adapter.getCount(); i++) if (entry(i).id().equals(dragged)) exists = true;
            if (dockSource) for (AppCatalogCache.Entry app : CoverApp.catalog(getContext()).snapshot()) if (app.id().equals(dragged)) exists = true;
            if (dragged == null || exists) { deferred = true; return; }
            cancelInteraction();
        }
        List<String> ids = new ArrayList<>(); indices.clear(); entries.clear();
        for (int i = 0; i < adapter.getCount(); i++) { AppCatalogCache.Entry entry = entry(i); String id = entry.id(); ids.add(id); indices.put(id, i); entries.put(id, entry); }
        AppCatalogCache catalog = CoverApp.catalog(getContext());
        if (catalog.ready() && !catalog.failed()) {
            AppWorkspaceLayout reconciled = AppLauncherModel.reconcile(saved, catalog.snapshot(), prefs.hubPins(), compact);
            if (!reconciled.equals(saved)) { undo = null; saved = reconciled; prefs.saveWorkspace(saved, compact); }
        }
        displayed = manual ? projected() : AppWorkspaceLayout.sequential(ids).project(columns, rows);
        page = Math.min(page, pageCount() - 1); dirty = true; requestLayout(); invalidate();
    }
    private AppCatalogCache.Entry entry(int index) { return (AppCatalogCache.Entry) adapter.getItem(index); }
    void preferencesChanged() {
        AppWorkspaceLayout next = prefs.workspace(); boolean nextCompact = prefs.workspaceCompact();
        if (next.equals(saved) && nextCompact == compact) return;
        cancelInteraction(); undo = null; saved = next; compact = nextCompact; refresh(); listener.changed();
    }
    void compact(boolean value) {
        if (prefs.workspaceLocked()) return; cancelInteraction(); AppWorkspaceLayout next = value ? projected().compact() : saved; if (value != compact || !next.equals(saved)) { undo = saved; undoCompact = compact; saved = next; compact = value; prefs.saveWorkspace(saved, compact); } refresh();
    }
    void moveTo(String id, int slot) {
        if (!editable() || saved.slot(id) < 0) return;
        change(projected().move(id, slot, compact)); page = projected().slot(id) / capacity(); dirty = true; requestLayout(); invalidate(); announceForAccessibility("已移动");
    }
    private AppWorkspaceLayout visibleLayout() { return preview == null ? displayed : preview; }
    private int columnWidth() { return AppLauncherStyle.cellWidth(getMeasuredWidth(), columns); }
    private int rowHeight() { return AppLauncherStyle.cellHeight(gridHeight(), rows); }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        // Nested weighted rows make several EXACTLY probes too. Only onLayout owns geometry.
        setMeasuredDimension(width, height);
    }
    private void layoutGeometry(int width, int height) {
        // Stable page/row/column identity takes priority over fitting extra rows into free space.
        int nextColumns = AppLauncherStyle.GRID_COLUMNS, nextRows = AppLauncherStyle.GRID_ROWS;
        if (width != measuredWidth || height - dp(AppLauncherStyle.WORKSPACE_PAGER_HEIGHT) != measuredGridHeight || nextColumns != columns || nextRows != rows) {
            cancelInteraction(); int anchor = page * capacity(); columns = nextColumns; rows = nextRows;
            measuredWidth = width; measuredGridHeight = height - dp(AppLauncherStyle.WORKSPACE_PAGER_HEIGHT); page = anchor / capacity(); dirty = true;
            if (manual) displayed = projected();
        }
        if (pendingSlot >= 0) { page = pendingSlot / capacity(); pendingSlot = -1; dirty = true; }
        page = Math.max(0, Math.min(page, (dragging() ? dragPageCount() : pageCount()) - 1));
        if (dirty) bindPages();
        for (Map.Entry<String, View> item : cells.entrySet()) { int span = visibleLayout().span(item.getKey()); item.getValue().measure(MeasureSpec.makeMeasureSpec(columnWidth() * span, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight() * span, MeasureSpec.EXACTLY)); }
        if (shadow != null) { int span = displayed.span(dragged == null ? settling : dragged); shadow.measure(MeasureSpec.makeMeasureSpec(columnWidth() * span, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rowHeight() * span, MeasureSpec.EXACTLY)); }
    }
    private void bindPages() {
        AppWorkspaceLayout layout = visibleLayout(); List<String> wanted = new ArrayList<>();
        for (String id : layout.ordered()) if ((indices.containsKey(id) || layout.folder(id) != null) && Math.abs(layout.slot(id) / capacity() - page) <= 1 && (dockSource || !id.equals(dragged)) && !id.equals(settling)) wanted.add(id);
        for (String id : new ArrayList<>(cells.keySet())) if (!wanted.contains(id)) { View removed = cells.remove(id); removed.animate().cancel(); removeViewInLayout(removed); }
        for (String id : wanted) {
            View cell = cells.get(id);
            if (cell == null) {
                cell = bind(id, null, layout); cells.put(id, cell); addViewInLayout(cell, -1, new LayoutParams(-2, -2), true);
                View anchor = cell; cell.setOnClickListener(v -> launch(id)); cell.setOnLongClickListener(v -> { listener.menu(anchor, id); return true; }); cell.setFocusable(true);
            } else bind(id, cell, layout);
        }
        bindingGeneration++; dirty = false;
    }
    private View bind(String id, View reusable, AppWorkspaceLayout layout) { return layout.folder(id) == null ? adapter.bind(entries.get(id), reusable, this) : adapter.folder(layout.folder(id), reusable, this); }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        layoutGeometry(right - left, bottom - top);
        AppWorkspaceLayout layout = visibleLayout();
        for (Map.Entry<String, View> item : cells.entrySet()) {
            int slot = layout.slot(item.getKey()); View cell = item.getValue();
            android.graphics.Rect box = AppLauncherStyle.gridCell(getWidth(), gridHeight(), columns, rows, slot, layout.span(item.getKey()), page); int x = box.left, y = box.top;
            float oldX = cell.getX(), oldY = cell.getY(); boolean animate = dragging() && mergeTarget == null && cell.isLaidOut() && Math.abs(x - cell.getLeft()) < getWidth();
            cell.animate().cancel(); cell.layout(box.left, box.top, box.right, box.bottom);
            if (animate && ValueAnimator.areAnimatorsEnabled()) { cell.setTranslationX(oldX - x); cell.setTranslationY(oldY - y); cell.animate().translationX(0).translationY(0).setDuration(150).start(); }
            else { cell.setTranslationX(0); cell.setTranslationY(0); }
        }
        if (shadow != null) shadow.layout(0, 0, shadow.getMeasuredWidth(), shadow.getMeasuredHeight());
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        int checkpoint = canvas.save(); canvas.clipRect(0, 0, getWidth(), gridHeight()); canvas.translate(offset, 0);
        if (dragging() && target >= 0) {
            int local = target % capacity(); float x = (target / capacity() - page) * getWidth() + local % columns * columnWidth(), y = local / columns * rowHeight();
            int span = visibleLayout().span(mergeReady ? visibleLayout().parent(dragged) : dragged); paint.setColor(0x406EA8CE); canvas.drawRoundRect(x + dp(2), y + dp(2), x + columnWidth() * span - dp(2), y + rowHeight() * span - dp(2), dp(10), dp(10), paint);
        }
        super.dispatchDraw(canvas); canvas.restoreToCount(checkpoint);
        if (editing && !dragging()) { paint.setColor(0x707E9AAE); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(1)); for (View cell : cells.values()) canvas.drawRoundRect(cell.getLeft() + dp(2), cell.getTop() + dp(2), cell.getRight() - dp(2), cell.getBottom() - dp(2), dp(10), dp(10), paint); paint.setStyle(Paint.Style.FILL); }
        if (mergeTarget != null) {
            paint.setColor(mergeReady ? 0xFF8BD5AB : 0xFFE4C783); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(dp(2)); canvas.drawRoundRect(mergeBounds, dp(10), dp(10), paint);
            float radius = dp(12), cx = mergeBounds.centerX(), cy = mergeBounds.centerY(); canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius, -90, Math.min(360, 360f * mergeElapsed() / 1000), false, paint); paint.setStyle(Paint.Style.FILL);
        }
        if (shadow != null && (!dragging() || !listener.outsideDragVisual())) {
            checkpoint = canvas.save(); canvas.clipRect(0, 0, getWidth(), getHeight()); canvas.translate(dragX - shadow.getWidth() / 2f, dragY - shadow.getHeight() / 2f); canvas.scale(shadowScale, shadowScale, shadow.getWidth() / 2f, shadow.getHeight() / 2f); shadow.draw(canvas); canvas.restoreToCount(checkpoint);
        }
        checkpoint = canvas.save(); canvas.translate(0, gridHeight());
        AppLauncherStyle.drawPageDots(canvas, getWidth(), dp(AppLauncherStyle.WORKSPACE_PAGER_HEIGHT), page, dragging() ? dragPageCount() : pageCount(), getResources().getDisplayMetrics().density, paint); canvas.restoreToCount(checkpoint);
        if (!mergeMessage.isEmpty()) { paint.setColor(Ui.TEXT); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(dp(10)); canvas.drawText(mergeMessage, getWidth() / 2f, gridHeight() - dp(3), paint); paint.setTextAlign(Paint.Align.LEFT); }
    }
    String hit(float x, float y) {
        if (x < 0 || x >= getWidth() || y < 0 || y >= gridHeight()) return null;
        int slot = page * capacity() + Math.min(columns - 1, (int) x / columnWidth()) + Math.min(rows - 1, (int) y / rowHeight()) * columns;
        return displayed.at(slot);
    }
    void launch(String id) { if (!dragging() && (indices.containsKey(id) || saved.folder(id) != null)) listener.launch(id); }
    void pressed(String id, boolean value) { View cell = cells.get(id); if (cell != null) cell.setPressed(value); }
    void menu(String id) { View anchor = cells.get(id); listener.menu(anchor == null ? this : anchor, id); }
    void drawDragVisual(Canvas canvas) {
        if (!dragging() || shadow == null) return;
        int checkpoint = canvas.save(); canvas.translate(dragX - shadow.getWidth() / 2f, dragY - shadow.getHeight() / 2f); canvas.scale(shadowScale, shadowScale, shadow.getWidth() / 2f, shadow.getHeight() / 2f); shadow.draw(canvas); canvas.restoreToCount(checkpoint);
    }
    void beginDrag(String id, float x, float y) {
        beginDrag(id, x, y, false);
    }
    void beginDockDrag(String id, float x, float y) { beginDrag(id, x, y, true); }
    private void beginDrag(String id, float x, float y, boolean fromDock) {
        if (!draggable()) return;
        if (fromDock) { for (AppCatalogCache.Entry entry : CoverApp.catalog(getContext()).snapshot()) if (entry.id().equals(id)) entries.put(id, entry); if (!entries.containsKey(id)) return; }
        else if (!indices.containsKey(id) && saved.folder(id) == null) return;
        finishLanding(); shadowScale = 1.10f;
        dockOnly = fromDock || !editable(); dockSource = fromDock;
        dragged = id; preview = displayed; target = dockOnly ? -1 : displayed.slot(id); shadow = bind(id, null, displayed); dragX = x; dragY = y; dirty = true; requestLayout(); invalidate(); listener.dragVisualChanged();
    }
    void dragTo(float x, float y) {
        if (!dragging()) return; dragX = x; dragY = y; listener.dragVisualChanged();
        dockDrop = displayed.folder(dragged) == null && listener.dockDrag(dragged, x, y, false);
        if (dockDrop) { clearMerge(); target = -1; preview = displayed; dirty = true; requestLayout(); invalidate(); return; }
        if (dockOnly) { target = -1; preview = displayed; invalidate(); return; }
        if (mergeAt(x, y)) { invalidate(); return; }
        int next = x < 0 || x >= getWidth() || y < 0 || y >= gridHeight() ? -1 : page * capacity() + Math.min(columns - 1, (int) x / columnWidth()) + Math.min(rows - 1, (int) y / rowHeight()) * columns;
        if (next >= AppWorkspaceLayout.MAX_SLOTS) next = -1;
        if (next != target) { target = next; preview = next < 0 ? displayed : displayed.parent(dragged) == null ? displayed.move(dragged, next, compact) : displayed.extract(dragged, next); dirty = true; requestLayout(); }
        invalidate();
    }
    void endDrag(boolean commit, int origin) {
        if (!dragging()) return;
        String id = dragged;
        boolean toDock = commit && dockDrop;
        if (toDock) { listener.dockDrag(id, dragX, dragY, true); commit = false; }
        if (commit && preview != null) { save(preview); displayed = projected(); String owner = saved.parent(id); page = displayed.slot(owner == null ? id : owner) / capacity(); announceForAccessibility(mergeReady ? "已合并到文件夹" : "已移动应用"); }
        else page = Math.min(origin, pageCount() - 1);
        clearMerge(); dockDrop = false; dragged = null; preview = null; target = -1; stopPaging(); offset = 0; listener.changed();
        if (commit && saved.slot(id) >= 0 && shadow != null && isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled()) {
            settling = id; int local = displayed.slot(id) % capacity(), span = displayed.span(id); float startX = dragX, startY = dragY, endX = (local % columns + span / 2f) * columnWidth(), endY = (local / columns + span / 2f) * rowHeight();
            landing = ValueAnimator.ofFloat(0, 1); landing.setDuration(150); landing.setInterpolator(new android.view.animation.DecelerateInterpolator());
            landing.addUpdateListener(value -> { float progress = (float) value.getAnimatedValue(); dragX = startX + (endX - startX) * progress; dragY = startY + (endY - startY) * progress; shadowScale = 1.10f - .10f * progress; invalidate(); });
            landing.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { finishLanding(); } }); landing.start();
        } else shadow = null;
        dockOnly = dockSource = false; dirty = true; requestLayout(); invalidate(); listener.dragVisualChanged();
    }
    private boolean mergeAt(float x, float y) {
        if (x < edgeWidth() || x > getWidth() - edgeWidth() || y < 0 || y >= gridHeight() || displayed.folder(dragged) != null || Math.abs(offset) > 1) { clearMerge(); return false; }
        if (mergeTarget != null && mergeBounds.contains(x, y)) return true;
        clearMerge();
        // Detect against committed anchors, not displaced insertion previews. A slow edge-to-center
        // approach must still be able to acquire the original app rather than chase its animation.
        for (String id : displayed.ordered()) {
            if (id.equals(dragged) || id.equals(displayed.parent(dragged)) || displayed.slot(id) / capacity() != page) continue;
            int local = displayed.slot(id) % capacity(), span = displayed.span(id); float left = local % columns * columnWidth(), top = local / columns * rowHeight(), width = columnWidth() * span, height = rowHeight() * span;
            float inset = displayed.folder(id) == null ? .24f : .15f;
            RectF center = new RectF(left + width * inset, top + height * inset, left + width * (1 - inset), top + height * (1 - inset));
            if (!center.contains(x, y)) continue;
            mergeBase = displayed; mergeTarget = id; mergeBounds.set(center); mergeBounds.inset(-dp(4), -dp(4)); mergeStarted = SystemClock.uptimeMillis(); mergeReady = false;
            preview = mergeBase; dirty = true; requestLayout(); target = -1; AppWorkspaceLayout.Folder folder = displayed.folder(id); mergeMessage = folder == null ? "停留 1 秒合并" : folder.members().size() == 9 ? "文件夹已满 9/9" : "加入 · " + folder.members().size() + "/9，停留 1 秒"; postDelayed(mergeTick, 16); return true;
        }
        return false;
    }
    private void advanceMerge() {
        if (!dragging() || mergeTarget == null || !isAttachedToWindow()) return;
        if (mergeElapsed() < 1000) { invalidate(); postDelayed(mergeTick, 16); return; }
        try { preview = mergeBase.merge(dragged, mergeTarget); mergeReady = true; String owner = preview.parent(dragged); target = preview.slot(owner); mergeMessage = "松手" + (displayed.folder(mergeTarget) == null ? "创建文件夹" : "加入文件夹"); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK); }
        catch (IllegalArgumentException error) { preview = mergeBase; mergeMessage = error.getMessage(); target = -1; }
        dirty = true; requestLayout(); invalidate(); announceForAccessibility(mergeMessage);
    }
    private void clearMerge() { removeCallbacks(mergeTick); if (mergeTarget != null) { preview = displayed; dirty = true; requestLayout(); target = -1; } mergeTarget = null; mergeBase = null; mergeReady = false; mergeMessage = ""; }
    void externalDrag(String id, float x, float y) { gestures.external(id, x, y); }
    void externalDockDrag(String id, float x, float y) { gestures.external(id, x, y, true); }
    int dropSlot(float x, float y) { return Math.min(AppWorkspaceLayout.MAX_SLOTS - 1, page * capacity() + Math.min(columns - 1, Math.max(0, (int) x / columnWidth())) + Math.min(rows - 1, Math.max(0, (int) y / rowHeight())) * columns); }
    private void finishLanding() {
        if (landing != null) { landing.removeAllListeners(); landing.removeAllUpdateListeners(); landing.cancel(); landing = null; }
        if (settling != null) { settling = null; shadow = null; dirty = true; requestLayout(); invalidate(); }
    }
    private int dragPageCount() { return Math.min((AppWorkspaceLayout.MAX_SLOTS + capacity() - 1) / capacity(), pageCount() + (compact ? 0 : 1)); }
    boolean canTurn(int direction, boolean drag) { return page + direction >= 0 && page + direction < (drag ? dragPageCount() : pageCount()); }
    void turnPage(int direction) { settlePage(page + direction, true); }
    void pageOffset(float origin, float distance) { pageOffset(page, origin, distance); }
    void pageOffset(int originPage, float origin, float distance) {
        int width = Math.max(1, getWidth());
        float minimum = -(pageCount() - 1 - originPage) * width, maximum = originPage * width;
        // Unwind existing edge resistance before adding a new finger delta.
        float raw = origin < minimum ? minimum + (origin - minimum) / .22f : origin > maximum ? maximum + (origin - maximum) / .22f : origin;
        raw += distance;
        offset = raw < minimum ? minimum + (raw - minimum) * .22f : raw > maximum ? maximum + (raw - maximum) * .22f : raw;
        offset += (page - originPage) * width;
        // Rebase only after crossing a whole page, retaining the same visible position
        // while keeping the existing current/previous/next three-page mount limit.
        int shift = offset > width ? (int) Math.floor(offset / width) : offset < -width ? (int) Math.ceil(offset / width) : 0;
        int next = Math.max(0, Math.min(pageCount() - 1, page - shift));
        if (next != page) { offset += (next - page) * width; page = next; dirty = true; requestLayout(); }
        invalidate();
    }
    int interruptedRelease(float speed, boolean fling) {
        float position = page - offset / Math.max(1, getWidth());
        return fling ? speed < 0 ? (int) Math.floor(position) + 1 : (int) Math.ceil(position) - 1 : Math.round(position);
    }
    float freezePaging() { if (pager != null) { pager.cancel(); pager = null; } return offset; }
    void stopPaging() { if (pager != null) { pager.cancel(); pager = null; } offset = 0; }
    void settlePage(int requested, boolean animate) {
        int next = Math.max(0, Math.min(requested, (dragging() ? dragPageCount() : pageCount()) - 1));
        float from = offset + (next - page) * getWidth(); stopPaging(); page = next; dirty = true; requestLayout();
        if (animate && ValueAnimator.areAnimatorsEnabled() && Math.abs(from) > 1) {
            offset = from; pager = ValueAnimator.ofFloat(from, 0); pager.setDuration(220); pager.setInterpolator(new android.view.animation.DecelerateInterpolator()); pager.addUpdateListener(value -> { offset = (float) value.getAnimatedValue(); invalidate(); }); pager.start();
        }
        invalidate(); setStateDescription("第 " + (page + 1) + " 页，共 " + (dragging() ? dragPageCount() : pageCount()) + " 页");
    }
    void indicatorTap(float x) { int direction = x < getWidth() / 2f ? -1 : 1; if (canTurn(direction, false)) turnPage(direction); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return !disposed && gestures.touch(event); }
    void interactionEnded() { if (deferred) { deferred = false; refresh(); } }
    void cancelInteraction() { gestures.cancel(); finishLanding(); stopPaging(); if (deferred) { deferred = false; post(refreshDeferred); } }
    @Override protected void onDetachedFromWindow() { cancelInteraction(); removeCallbacks(refreshDeferred); super.onDetachedFromWindow(); }
    void dispose() { if (disposed) return; cancelInteraction(); disposed = true; removeCallbacks(refreshDeferred); adapter.unregisterDataSetObserver(observer); for (View cell : cells.values()) cell.animate().cancel(); }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setScrollable(pageCount() > 1);
        if (canTurn(-1, false)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (canTurn(1, false)) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
    }
    @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && canTurn(1, false)) { turnPage(1); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && canTurn(-1, false)) { turnPage(-1); return true; }
        return super.performAccessibilityAction(action, arguments);
    }
}
