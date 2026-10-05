package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** At most nine members. Draft order and external drags commit only on a valid release. */
final class AppFolderGrid extends ViewGroup {
    interface Listener {
        void launch(String id); void menu(View anchor, String id); void reorder(List<String> members);
        boolean outside(float rawX, float rawY); boolean external(String id, float rawX, float rawY); void forward(MotionEvent event); void endExternal();
    }
    private final List<String> members;
    private List<String> order;
    private final Listener listener;
    private final boolean editable;
    private final int slop;
    private String pressed;
    private boolean held, moved, blocked, external;
    private float downX, downY, x, y, rawX, rawY;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Runnable lift, escape;
    private LauncherForce launcherForce; private Runnable forceWake;
    void launcherForce(LauncherForce force, Runnable wake) { launcherForce = force; forceWake = wake; }
    @Override public void draw(Canvas canvas) {
        if (launcherForce == null) { super.draw(canvas); return; }
        int saved = canvas.save(); float density = getResources().getDisplayMetrics().density; canvas.translate(launcherForce.x[LauncherForce.FOLDER] * density, launcherForce.y[LauncherForce.FOLDER] * density); super.draw(canvas); canvas.restoreToCount(saved);
    }
    AppFolderGrid(Context context, List<String> members, boolean editable, Function<String, View> bind, Listener listener) {
        super(context); setWillNotDraw(false); this.members = List.copyOf(members); this.order = new ArrayList<>(members); this.listener = listener; this.editable = editable; slop = ViewConfiguration.get(context).getScaledTouchSlop(); setTag("folder-grid"); setClipChildren(false);
        lift = () -> { if (pressed != null && !blocked) { held = true; getParent().requestDisallowInterceptTouchEvent(true); performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); invalidate(); } };
        escape = () -> { if (held && moved && listener.outside(rawX, rawY)) { external = listener.external(pressed, rawX, rawY); if (external) removeCallbacks(lift); } };
        for (String id : members) { View view = bind.apply(id); view.setTag("folder-member:" + id); view.setOnClickListener(v -> listener.launch(id)); view.setOnLongClickListener(v -> { listener.menu(v, id); return true; }); addView(view); }
    }
    @Override protected void onMeasure(int w, int h) {
        int width = Math.max(1, MeasureSpec.getSize(w)), row = Ui.dp(getContext(), AppLauncherStyle.FOLDER_MEMBER_HEIGHT), cell = AppLauncherStyle.cellWidth(width, AppLauncherStyle.FOLDER_COLUMNS);
        for (int i = 0; i < getChildCount(); i++) { View view = getChildAt(i); view.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)); row = Math.max(row, view.getMeasuredHeight()); }
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(row, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, row * ((members.size() + AppLauncherStyle.FOLDER_COLUMNS - 1) / AppLauncherStyle.FOLDER_COLUMNS));
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { for (int i = 0; i < members.size(); i++) { View view = getChildAt(i); int index = order.indexOf(members.get(i)), x = index % AppLauncherStyle.FOLDER_COLUMNS * AppLauncherStyle.cellWidth(getWidth(), AppLauncherStyle.FOLDER_COLUMNS), y = index / AppLauncherStyle.FOLDER_COLUMNS * view.getMeasuredHeight(); view.layout(x, y, x + view.getMeasuredWidth(), y + view.getMeasuredHeight()); } }
    private int hit(float x, float y) { if (x < 0 || y < 0 || x >= getWidth() || y >= getHeight()) return -1; int index = (int) y / Math.max(1, getChildAt(0).getHeight()) * AppLauncherStyle.FOLDER_COLUMNS + Math.min(AppLauncherStyle.FOLDER_COLUMNS - 1, (int) (x * AppLauncherStyle.FOLDER_COLUMNS / getWidth())); return index < order.size() ? index : -1; }
    @Override protected void dispatchDraw(Canvas canvas) { super.dispatchDraw(canvas); if (held && !external && pressed != null) { View cell = getChildAt(members.indexOf(pressed)); paint.setColor(0xCC9BD5F5); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(), 2)); canvas.drawRoundRect(cell.getLeft() + 2, cell.getTop() + 2, cell.getRight() - 2, cell.getBottom() - 2, Ui.dp(getContext(), 10), Ui.dp(getContext(), 10), paint); } }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked(); x = event.getX(); y = event.getY(); rawX = event.getRawX(); rawY = event.getRawY();
        if (launcherForce != null && android.animation.ValueAnimator.areAnimatorsEnabled() && action == MotionEvent.ACTION_DOWN) { launcherForce.impulse(LauncherForce.FOLDER, 0, 90); forceWake.run(); }
        if (action == MotionEvent.ACTION_DOWN) { cancel(); blocked = false; downX = x; downY = y; int index = hit(x, y); pressed = index < 0 ? null : order.get(index); if (pressed != null) postDelayed(lift, ViewConfiguration.getLongPressTimeout()); }
        if (external) { listener.forward(event); if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) { external = false; cancel(); listener.endExternal(); } return true; }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) { cancel(); return true; }
        if (blocked) return true;
        if (action == MotionEvent.ACTION_MOVE && Math.hypot(x - downX, y - downY) > slop) {
            moved = true;
            if (!held || !editable) { removeCallbacks(lift); blocked = true; return true; }
            if (listener.outside(rawX, rawY)) { if (getHandler() != null && !getHandler().hasCallbacks(escape)) postDelayed(escape, 350); }
            else { removeCallbacks(escape); int index = hit(x, y); if (index >= 0) { List<String> next = new ArrayList<>(members); next.remove(pressed); next.add(Math.min(index, next.size()), pressed); order = next; requestLayout(); invalidate(); } }
        }
        if (action == MotionEvent.ACTION_UP) {
            String id = pressed; boolean menu = held && !moved, launch = !held && !moved && id != null && hit(x, y) >= 0;
            List<String> next = List.copyOf(order); boolean commit = held && moved && hit(x, y) >= 0 && !next.equals(members); cancel();
            if (commit) listener.reorder(next); else if (menu && id != null) listener.menu(getChildAt(members.indexOf(id)), id); else if (launch) listener.launch(id);
        }
        return true;
    }
    void cancel() { removeCallbacks(lift); removeCallbacks(escape); held = moved = false; blocked = true; pressed = null; order = new ArrayList<>(members); requestLayout(); invalidate(); }
    @Override protected void onDetachedFromWindow() { cancel(); if (external) { MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0); listener.forward(event); event.recycle(); external = false; } super.onDetachedFromWindow(); }
}
