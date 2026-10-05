package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;

/** Notification title/actions share the list's force clock; drawing never changes hit regions. */
final class NotificationForceHeader extends PanelActionHeader {
    private static final class Node { final NotificationForce.Spring x = new NotificationForce.Spring(), y = new NotificationForce.Spring(); }
    private final ArrayList<Node> nodes = new ArrayList<>();
    private NotificationScrollView forceOwner;
    NotificationForceHeader(Context context) { super(context); setGravity(Gravity.CENTER_VERTICAL); setClipChildren(false); setTag("panel-header"); }
    void forceOwner(NotificationScrollView owner) { forceOwner = owner; }
    @Override protected boolean growsActions() { return true; }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (nodes.size() != getChildCount()) { nodes.clear(); for (int i = 0; i < getChildCount(); i++) nodes.add(new Node()); }
    }
    void load(float x, float y) {
        for (int i = 0; i < nodes.size(); i++) { float weight = .35f + .65f * i / Math.max(1, nodes.size() - 1); nodes.get(i).x.load(x * weight * .35f); nodes.get(i).y.load(y * weight * .18f); }
        for (int i = 1; i < nodes.size(); i++) { NotificationForce.connect(nodes.get(i - 1).x, nodes.get(i).x); NotificationForce.connect(nodes.get(i - 1).y, nodes.get(i).y); }
    }
    void connect(NotificationSwipeRow row) { if (!nodes.isEmpty()) { Node last = nodes.get(nodes.size() - 1); NotificationForce.connect(last.x, row.sideForce); NotificationForce.connect(last.y, row.scrollForce); } }
    void advance(float seconds) { for (int i = 0; i < nodes.size(); i++) { nodes.get(i).x.advance(seconds); nodes.get(i).y.advance(seconds); } }
    boolean moving() { for (int i = 0; i < nodes.size(); i++) if (nodes.get(i).x.moving() || nodes.get(i).y.moving()) return true; return false; }
    float visualX(View child) { int index = indexOfChild(child); return index < 0 || index >= nodes.size() ? 0 : nodes.get(index).x.position; }
    float visualY(View child) { int index = indexOfChild(child); return index < 0 || index >= nodes.size() ? 0 : nodes.get(index).y.position; }
    @Override protected float actionForceX(View slot) { return visualX(slot); }
    @Override protected float actionForceY(View slot) { return visualY(slot); }
    void invalidateForce() { invalidateContent(this); }
    private void invalidateContent(View view) { view.invalidate(); if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) invalidateContent(group.getChildAt(i)); }
    void pulse(float x) {
        float speed = Ui.dp(getContext(), 220);
        for (int i = 0; i < nodes.size(); i++) { View child = getChildAt(i); float distance = Math.abs(x - child.getLeft() - child.getWidth() * .5f) / Math.max(1, getWidth()); float weight = Math.max(.1f, 1 - distance * 2); nodes.get(i).y.velocity += speed * weight; nodes.get(i).x.velocity += Math.copySign(speed * .45f * weight, getWidth() * .5f - x); }
    }
    void resetForce() { for (int i = 0; i < nodes.size(); i++) { nodes.get(i).x.reset(); nodes.get(i).y.reset(); } invalidate(); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (forceOwner != null && event.getActionMasked() == MotionEvent.ACTION_DOWN) forceOwner.pressFeedback(event.getX());
        if (forceOwner != null && (event.getActionMasked() == MotionEvent.ACTION_CANCEL || event.getPointerCount() > 1)) forceOwner.cancelLinkedForce();
        return super.dispatchTouchEvent(event);
    }
    @Override protected boolean drawChild(Canvas canvas, View child, long time) {
        int index = indexOfChild(child); if (index < 0 || index >= nodes.size()) return super.drawChild(canvas, child, time);
        Node node = nodes.get(index); int saved = canvas.save(); canvas.translate(node.x.position, node.y.position);
        float stretch = Math.max(-.025f, Math.min(.025f, node.y.velocity * Math.signum(node.y.position) / Math.max(1, getHeight()) * .012f));
        canvas.scale(1 - stretch * .3f, 1 + stretch, child.getLeft() + child.getWidth() * .5f, getHeight() * .5f);
        boolean drawn = super.drawChild(canvas, child, time); canvas.restoreToCount(saved); return drawn;
    }
    @Override protected void onDetachedFromWindow() { forceOwner = null; resetForce(); super.onDetachedFromWindow(); }
}
