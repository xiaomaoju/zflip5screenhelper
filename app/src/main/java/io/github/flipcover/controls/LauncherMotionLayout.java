package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

/** Paint-only region translation. Measurement, text proportions and input coordinates stay fixed. */
final class LauncherMotionLayout extends LinearLayout {
    private LauncherForce force;
    private final int node;
    private float dockProgress = 1, leftToolWidth, rightToolWidth, leftRadius, rightRadius, leftDistance, rightDistance;
    void dockTools(float left, float right) { leftToolWidth = left; rightToolWidth = right; invalidate(); invalidateOutline(); }
    void dockMargins(float left, float right) {
        float density = getResources().getDisplayMetrics().density;
        leftRadius = Math.min(AppLauncherStyle.DOCK_FUSION_FACE_MAX * density / 2, (AppLauncherStyle.DOCK_FUSION_FACE_MIN * density + Math.max(0, left)) / 2);
        rightRadius = Math.min(AppLauncherStyle.DOCK_FUSION_FACE_MAX * density / 2, (AppLauncherStyle.DOCK_FUSION_FACE_MIN * density + Math.max(0, right)) / 2);
        float leftGap = Math.min(AppLauncherStyle.DOCK_FUSION_GAP * density, leftToolWidth + Math.max(0, left) - 2 * leftRadius);
        float rightGap = Math.min(AppLauncherStyle.DOCK_FUSION_GAP * density, rightToolWidth + Math.max(0, right) - 2 * rightRadius);
        leftDistance = leftRadius + leftGap + getPaddingLeft() - leftToolWidth / 2;
        rightDistance = rightRadius + rightGap + getPaddingRight() - rightToolWidth / 2;
    }
    void dockEntrance(float value) { dockProgress = Math.max(0, Math.min(1, value)); invalidate(); invalidateOutline(); if (getParent() != null && getParent().getParent() instanceof View dock) dock.invalidate(); }
    float fusionProgress() { return getChildCount() <= 1 ? 1 : dockProgress; }
    private float easedFusion() { float p = fusionProgress(); return p * p * p * (p * (p * 6 - 15) + 10); }
    void materialBounds(RectF bounds) { if (node == LauncherForce.DOCK) { float remaining = 1 - easedFusion(); bounds.left += leftToolWidth * remaining; bounds.right -= rightToolWidth * remaining; } }
    float toolSlideX(boolean right) { return (right ? rightDistance : -leftDistance) * (1 - easedFusion()); }
    float toolRadius(boolean right) { return right ? rightRadius : leftRadius; }
    float visualScaleX() { return 1; }
    float visualScaleY() { return 1; }
    LauncherMotionLayout(Context context, LauncherForce force, int node) { super(context); this.force = force; this.node = node; setOrientation(VERTICAL); setWillNotDraw(false); if (node == LauncherForce.DOCK) { setClipChildren(false); setClipToPadding(false); } }
    void force(LauncherForce value) { force = value; }
    float visualX() { return force == null ? 0 : force.x[node] * getResources().getDisplayMetrics().density; }
    float visualY() { return force == null ? 0 : force.y[node] * getResources().getDisplayMetrics().density; }
    @Override public void draw(Canvas canvas) {
        int saved = canvas.save(); canvas.translate(visualX(), visualY()); super.draw(canvas); canvas.restoreToCount(saved);
    }
    @Override protected boolean drawChild(Canvas canvas, View child, long time) {
        boolean right = "hub-clear".equals(child.getTag());
        if (node != LauncherForce.DOCK || fusionProgress() == 1 || !right && !"dock-apps-slot".equals(child.getTag())) return super.drawChild(canvas, child, time);
        int saved = canvas.save(); canvas.translate(toolSlideX(right), 0);
        boolean drawn = super.drawChild(canvas, child, time); canvas.restoreToCount(saved); return drawn;
    }
    static void invalidateForce(View view) {
        if (view.getVisibility() != VISIBLE) return;
        if (view instanceof ViewGroup group) { view.invalidate(); for (int i = 0; i < group.getChildCount(); i++) invalidateForce(group.getChildAt(i)); }
        else if (view instanceof RuntimeVisuals.Button || "folder-surface".equals(view.getTag())) view.invalidate();
    }
    /** Add paint-only movement to the existing View matrix; texture sampling follows the drawn surface. */
    static void materialPosition(View owner, float[] values, android.graphics.Matrix scratch, float[] parent) {
        if (owner instanceof LauncherMotionLayout group) scaleMaterial(values, owner.getWidth() / 2f, owner.getHeight() / 2f, group.visualScaleX(), group.visualScaleY());
        if (owner.getParent() instanceof AppFolderTile folder) {
            float sx = folder.visualScaleX(), sy = folder.visualScaleY(), px = folder.getWidth() / 2f - owner.getLeft(), py = folder.getHeight() / 2f - owner.getTop();
            scaleMaterial(values, px, py, sx, sy);
        }
        for (View view = owner; view != null; view = view.getParent() instanceof View ancestor ? ancestor : null) {
            float x = 0, y = 0;
            if (view instanceof LauncherMotionLayout group) { x = group.visualX(); y = group.visualY(); }
            else if (view instanceof AppWorkspaceView workspace) { x = workspace.visualX(); y = workspace.visualY(); }
            else if (view instanceof AppFolderTile folder) { x = folder.visualX(); y = folder.visualY(); }
            if (x == 0 && y == 0) continue;
            scratch.reset(); view.transformMatrixToGlobal(scratch); scratch.getValues(parent);
            values[2] += parent[0] * x + parent[1] * y; values[5] += parent[3] * x + parent[4] * y;
        }
    }
    private static void scaleMaterial(float[] values, float px, float py, float sx, float sy) { values[2] += values[0] * px * (1 - sx) + values[1] * py * (1 - sy); values[5] += values[3] * px * (1 - sx) + values[4] * py * (1 - sy); values[0] *= sx; values[3] *= sx; values[1] *= sy; values[4] *= sy; }
}
