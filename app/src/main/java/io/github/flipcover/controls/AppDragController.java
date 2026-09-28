package io.github.flipcover.controls;

import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.ViewConfiguration;

/** Owns a single touch sequence, including delayed lift and edge paging. */
final class AppDragController {
    private final AppWorkspaceView host;
    private final int slop, minimumVelocity;
    private float downX, downY, x, y, startOffset;
    private int originPage, edge;
    private String pressed;
    private boolean held, moved, paging, blocked;
    private VelocityTracker velocity;
    private final Runnable lift = this::lift;
    private void lift() {
        if (blocked || paging || !host.isAttachedToWindow()) return;
        if (pressed == null) { blocked = true; host.menu(""); return; }
        held = true; host.pressed(pressed, false); host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (host.draggable()) host.beginDrag(pressed, x, y);
    }
    private final Runnable turn = new Runnable() {
        @Override public void run() {
            if (!held || !moved || edge == 0 || !host.dragging() || !host.canTurn(edge, true)) return;
            host.turnPage(edge); host.dragTo(x, y);
            host.postDelayed(this, 700);
        }
    };
    AppDragController(AppWorkspaceView host) {
        this.host = host; ViewConfiguration configuration = ViewConfiguration.get(host.getContext());
        slop = configuration.getScaledTouchSlop(); minimumVelocity = configuration.getScaledMinimumFlingVelocity();
    }
    boolean active() { return held || paging; }
    void external(String id, float x, float y) { external(id, x, y, false); }
    void external(String id, float x, float y, boolean fromDock) { cancel(); this.x = downX = x; this.y = downY = y; originPage = host.page(); pressed = id; blocked = false; held = moved = true; if (fromDock) host.beginDockDrag(id, x, y); else host.beginDrag(id, x, y); host.dragTo(x, y); }
    boolean touch(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel(); startOffset = host.freezePaging(); originPage = host.page(); downX = x = event.getX(); downY = y = event.getY();
            blocked = false; pressed = Math.abs(startOffset) > 1 ? null : host.hit(x, y); host.pressed(pressed, true); velocity = VelocityTracker.obtain();
            host.getParent().requestDisallowInterceptTouchEvent(true);
            host.postDelayed(lift, ViewConfiguration.getLongPressTimeout());
        }
        if (velocity != null) velocity.addMovement(event);
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_CANCEL) { cancel(); host.interactionEnded(); return true; }
        if (blocked) return true;
        x = event.getX(); y = event.getY(); float dx = x - downX, dy = y - downY;
        if (action == MotionEvent.ACTION_MOVE) {
            if (held) {
                if (Math.hypot(dx, dy) > slop) moved = true;
                if (host.dragging() && moved) {
                    host.dragTo(x, y);
                    int next = y >= 0 && y < host.gridHeight() ? x < host.edgeWidth() ? -1 : x > host.getWidth() - host.edgeWidth() ? 1 : 0 : 0;
                    if (next != edge) { host.removeCallbacks(turn); edge = next; if (edge != 0) host.postDelayed(turn, 450); }
                }
            } else if (paging) host.pageOffset(originPage, startOffset, dx);
            else if (Math.hypot(dx, dy) > slop) {
                host.removeCallbacks(lift); host.pressed(pressed, false);
                if (Math.abs(dx) > Math.abs(dy) * 1.15f) { paging = true; host.pageOffset(originPage, startOffset, dx); }
                else { blocked = true; if (Math.abs(startOffset) > 1) host.settlePage(originPage, true); startOffset = 0; recycle(); host.interactionEnded(); }
            }
        } else if (action == MotionEvent.ACTION_UP) {
            host.pressed(pressed, false);
            host.removeCallbacks(lift); host.removeCallbacks(turn); edge = 0;
            if (paging) {
                velocity.computeCurrentVelocity(1000); float speed = velocity.getXVelocity();
                boolean fling = Math.abs(dx) > slop && Math.abs(speed) > Math.max(minimumVelocity, host.dp(350));
                int direction = Math.abs(dx) > host.getWidth() * .22f || fling ? dx < 0 ? 1 : -1 : 0;
                host.settlePage(Math.abs(startOffset) > 1 ? host.interruptedRelease(speed, fling) : originPage + direction, true);
            } else if (held) {
                String id = pressed;
                if (host.dragging() && moved) host.dragTo(x, y);
                if (host.dragging()) host.endDrag(moved && host.validDrop(), originPage);
                if (!moved && id != null) host.menu(id);
            } else if (Math.abs(startOffset) > 1) host.settlePage(originPage, true);
            else if (pressed != null && pressed.equals(host.hit(x, y))) host.launch(pressed);
            else if (y >= host.gridHeight()) host.indicatorTap(x);
            held = moved = paging = false; pressed = null; startOffset = 0; recycle(); host.interactionEnded();
        }
        return true;
    }
    void cancel() {
        host.pressed(pressed, false);
        host.removeCallbacks(lift); host.removeCallbacks(turn); edge = 0;
        if (host.dragging()) host.endDrag(false, originPage);
        else if (paging || Math.abs(startOffset) > 1) host.settlePage(originPage, false);
        startOffset = 0;
        held = moved = paging = false; blocked = true; pressed = null; recycle();
    }
    private void recycle() { if (velocity != null) { velocity.recycle(); velocity = null; } }
}
