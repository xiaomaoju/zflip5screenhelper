package io.github.flipcover.controls;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;

/** Shared runtime page shell. Content owners keep their own layout and business state. */
@android.annotation.SuppressLint("ViewConstructor")
final class InterfaceCard extends FrameLayout {
    enum Material { TRANSPARENT, FROSTED }
    record Definition(String id, Material material, boolean showStatusBar, boolean useSafeArea, boolean useCardSafeArea) {
        Definition(String id, Material material, boolean showStatusBar) { this(id, material, showStatusBar, true, false); }
    }
    static final Definition CONTROLS = new Definition("controls", Material.FROSTED, true, true, false);
    static final Definition NOTIFICATIONS = new Definition("notifications", Material.TRANSPARENT, false, true, false);
    static final Definition TASKS = new Definition("tasks", Material.TRANSPARENT, false, true, false);
    static final Definition LAUNCHER = new Definition("launcher", Material.TRANSPARENT, false, true, false);
    static Definition panel(String page) { return page.equals("controls") ? CONTROLS : page.equals("notifications") ? NOTIFICATIONS : new Definition(page, Material.FROSTED, false); }
    static android.animation.ValueAnimator motion(float from, float to) {
        android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(from, to);
        animation.setDuration(BuildConfig.MOTION_CARD_DURATION_MS); animation.setInterpolator(new android.view.animation.PathInterpolator(.2f, 0, .2f, 1)); return animation;
    }
    static android.animation.ValueAnimator initialMotion() {
        android.animation.ValueAnimator animation = android.animation.ValueAnimator.ofFloat(0, 1);
        animation.setDuration(BuildConfig.MOTION_CARD_INITIAL_DURATION_MS); animation.setInterpolator(new android.view.animation.DecelerateInterpolator()); return animation;
    }

    private final Definition definition;
    private final Runnable pauseContent, releaseContent;
    private Material material;
    private PanelGlassSession glass;
    private boolean interactive = true, closed, prepared, plateVisible = true, memoryFallback;
    private float exitStart, exitExtent;
    private float restingX, restingY;
    private Rect restingClip;
    private final RectF visual = new RectF(), restingVisual = new RectF(), cropStart = new RectF(), cropTarget = new RectF();
    private final Rect outlineBox = new Rect(), pushClip = new Rect();
    private final RectF pushBounds = new RectF(), incomingBounds = new RectF();
    private final View content;
    private final StatusBarView status;
    private final int contentTop;
    private DockGeometry.Box viewport, cardArea, safeCardArea, localSafeArea;
    private final android.graphics.Paint contentMask = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private int maskBottom = -1;
    private DockGeometry.Box statusArea;
    private boolean contentOwnsInsets;
    private final NotificationScrollView notificationMotion;
    private ControlScrollView controlMotion;
    private PanelActionHeader actionHeader;
    private float entranceProgress;
    private float controlEntryX, controlEntryY;
    void refreshControlMotion() { View grid = definition.id().equals("controls") ? content.findViewWithTag("control-grid-scroll") : null; controlMotion = grid instanceof ControlScrollView scroll ? scroll : null; if (controlMotion != null) controlMotion.card(this); View header = content.findViewWithTag("panel-header"); actionHeader = header instanceof PanelActionHeader splitting ? splitting : null; if (actionHeader != null) actionHeader.entryProgress(entranceProgress); }
    boolean controlMatrix(android.graphics.Matrix matrix) { if (controlMotion == null || !controlMotion.isAttachedToWindow()) return false; float whole = controlMotion.elastic.whole(); matrix.setScale(1 + controlEntryX - whole * .38f, 1 + controlEntryY + whole, getWidth() / 2f, controlMotion.elastic.edge() < 0 ? getHeight() : 0); return true; }
    private final android.graphics.Matrix controlPaint = new android.graphics.Matrix();
    @Override public void draw(android.graphics.Canvas canvas) { int checkpoint = canvas.save(); if (controlMatrix(controlPaint)) canvas.concat(controlPaint); super.draw(canvas); canvas.restoreToCount(checkpoint); }

    InterfaceCard(Context context, Prefs prefs, Definition definition, View content, Runnable pauseContent, Runnable releaseContent) {
        super(context); this.definition = definition; material = definition.material(); this.pauseContent = pauseContent; this.releaseContent = releaseContent;
        this.content = content;
        View center = definition.id().equals("notifications") ? content.findViewWithTag("notification-center") : null;
        NotificationScrollView motion = null;
        if (center != null) for (android.view.ViewParent parent = center.getParent(); parent instanceof View && parent != content.getParent(); parent = parent.getParent()) if (parent instanceof NotificationScrollView scroll) { motion = scroll; break; }
        notificationMotion = motion;
        contentTop = content.getPaddingTop(); refreshControlMotion();
        setTag("interface-card:" + definition.id()); setMotionEventSplittingEnabled(false); setClipChildren(true);
        content.setBackgroundColor(Color.TRANSPARENT); addView(content, new LayoutParams(-1, -1)); fallback();
        status = definition.showStatusBar() ? new StatusBarView(context, prefs, true) : null;
        if (status != null) { status.setTag("panel-status"); addView(status, new LayoutParams(-1, status.heightPixels())); statusBounds(new DockGeometry.Box(0, 0, context.getResources().getDisplayMetrics().widthPixels, status.heightPixels())); }
    }
    Definition definition() { return definition; }
    StatusBarView statusBar() { return status; }
    void statusVisible(boolean visible) {
        if (closed || status == null) return;
        status.setVisibility(visible ? VISIBLE : GONE);
        if (!visible) removeView(status);
        else { if (status.getParent() == null) addView(status); if (statusArea != null) statusBounds(statusArea); }
    }
    void frameBounds(DockGeometry.Box full, DockGeometry.Box safe) {
        viewport = full; safeCardArea = safe; cardArea = definition.useCardSafeArea() ? safe : full; localSafeArea = localArea(safe);
        setLayoutParams(new LayoutParams(full.width(), full.height()));
        visual.set(cardArea.x() - full.x(), cardArea.y() - full.y(), cardArea.right() - full.x(), cardArea.bottom() - full.y());
        invalidateOutline(); invalidate();
        if (statusArea != null) statusBounds(statusArea);
    }
    DockGeometry.Box localFrame() { return new DockGeometry.Box(0, 0, viewport.width(), viewport.height()); }
    void visualBounds(RectF result) { if (visual.isEmpty()) result.set(0, 0, getWidth(), getHeight()); else result.set(visual); }
    float insetProgress() {
        if (viewport == null || safeCardArea == null) return 0;
        float margin = safeCardArea.x() - viewport.x() + safeCardArea.y() - viewport.y() + viewport.right() - safeCardArea.right() + viewport.bottom() - safeCardArea.bottom();
        return margin <= 0 ? 0 : Math.max(0, Math.min(1, (visual.left + visual.top + viewport.width() - visual.right + viewport.height() - visual.bottom) / margin));
    }
    void prepareEntry() { if (viewport == null) return; visual.set(0, 0, viewport.width(), viewport.height()); beginEntryCrop(); invalidateOutline(); invalidate(); }
    void beginEntryCrop() { cropStart.set(visual); if (viewport != null) cropTarget.set(cardArea.x() - viewport.x(), cardArea.y() - viewport.y(), cardArea.right() - viewport.x(), cardArea.bottom() - viewport.y()); }
    void beginExitCrop() { cropStart.set(visual); if (viewport != null) cropTarget.set(safeCardArea.x() - viewport.x(), safeCardArea.y() - viewport.y(), safeCardArea.right() - viewport.x(), safeCardArea.bottom() - viewport.y()); }
    void beginRestoreCrop() { cropStart.set(visual); cropTarget.set(restingVisual); }
    void crop(float progress) {
        if (viewport == null) return;
        float p = Math.max(0, Math.min(1, progress));
        visual.set(cropStart.left + (cropTarget.left - cropStart.left) * p, cropStart.top + (cropTarget.top - cropStart.top) * p, cropStart.right + (cropTarget.right - cropStart.right) * p, cropStart.bottom + (cropTarget.bottom - cropStart.bottom) * p);
        invalidateOutline(); invalidate();
    }
    DockGeometry.Box safeArea() { return localSafeArea; }
    void contentSafeBounds(DockGeometry.Box safe) { if (cardArea != null) localSafeArea = localArea(safe); }
    DockGeometry.Box localArea(DockGeometry.Box bounds) {
        int left = Math.max(bounds.x(), cardArea.x()), top = Math.max(bounds.y(), cardArea.y());
        return new DockGeometry.Box(left - viewport.x(), top - viewport.y(), Math.max(1, Math.min(bounds.right(), cardArea.right()) - left), Math.max(1, Math.min(bounds.bottom(), cardArea.bottom()) - top));
    }
    void statusBounds(DockGeometry.Box bounds) {
        if (status == null || bounds == null) return;
        statusArea = bounds;
        int left = viewport == null ? 0 : viewport.x(), originTop = viewport == null ? 0 : viewport.y();
        LayoutParams layout = new LayoutParams(bounds.width(), bounds.height()); layout.leftMargin = bounds.x() - left; layout.topMargin = bounds.y() - originTop; status.setLayoutParams(layout);
        if (!contentOwnsInsets) { int top = content.getLayoutParams() instanceof LayoutParams position ? position.topMargin : 0; content.setPadding(content.getPaddingLeft(), Math.max(Math.max(0, contentTop - originTop), bounds.bottom() + Ui.dp(getContext(), 10) - originTop - top), content.getPaddingRight(), content.getPaddingBottom()); }
    }
    void contentBounds(DockGeometry.Box bounds, DockGeometry.Box frame) {
        contentSafeBounds(bounds);
        if (!definition.useSafeArea()) bounds = frame;
        DockGeometry.Box local = cardArea == null ? new DockGeometry.Box(bounds.x() - frame.x(), bounds.y() - frame.y(), bounds.width(), bounds.height()) : localArea(bounds);
        LayoutParams layout = new LayoutParams(local.width(), local.height()); layout.leftMargin = local.x(); layout.topMargin = local.y(); content.setLayoutParams(layout);
        if (statusArea != null) statusBounds(statusArea);
    }
    void contentOwnsInsets() { contentOwnsInsets = true; content.setPadding(content.getPaddingLeft(), contentTop, content.getPaddingRight(), content.getPaddingBottom()); }
    DockGeometry.Box contentArea(DockGeometry.Box area) {
        if (!definition.useSafeArea() && viewport != null) area = viewport;
        if (status == null || statusArea == null) return area;
        int top = Math.max(area.y(), statusArea.bottom() + Ui.dp(getContext(), 4)); return new DockGeometry.Box(area.x(), top, area.width(), Math.max(1, area.bottom() - top));
    }
    @Override protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
        if (child != content || localSafeArea == null || !definition.useSafeArea()) return super.drawChild(canvas, child, drawingTime);
        DockGeometry.Box safe = safeArea();
        if (safe.bottom() >= getHeight()) return super.drawChild(canvas, child, drawingTime);
        int band = Ui.dp(getContext(), 16); int checkpoint = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        boolean drawn = super.drawChild(canvas, child, drawingTime);
        if (maskBottom != safe.bottom()) { maskBottom = safe.bottom(); contentMask.setShader(new android.graphics.LinearGradient(0, maskBottom - band, 0, maskBottom, Color.BLACK, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)); contentMask.setBlendMode(android.graphics.BlendMode.DST_IN); }
        canvas.drawRect(0, 0, getWidth(), getHeight(), contentMask); canvas.restoreToCount(checkpoint); return drawn;
    }
    Material material() { return material; }
    PanelGlassSession glass() { return glass; }
    boolean ready() { return !closed && (glass == null || !glass.preparing()); }
    boolean plateVisible() { return plateVisible; }
    void plateVisible(boolean visible) { if (plateVisible == visible) return; plateVisible = visible; rebind(); }
    void material(Material value) { if (material == value) return; material = value; rebind(); }
    private void fallback() {
        int color = !plateVisible ? Color.TRANSPARENT : material == Material.FROSTED ? 0x88161C24 : 0x3D000000;
        setBackground(new android.graphics.drawable.Drawable() {
            private final android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            private final RectF bounds = new RectF();
            @Override public void draw(android.graphics.Canvas canvas) { visualBounds(bounds); paint.setColor(color); float radius = Math.min(Ui.dp(getContext(), 32), Math.min(bounds.width(), bounds.height()) / 2); canvas.drawRoundRect(bounds, radius, radius, paint); }
            @Override public void getOutline(android.graphics.Outline outline) { visualBounds(bounds); bounds.round(outlineBox); outline.setRoundRect(outlineBox, Ui.dp(getContext(), 32)); outline.setAlpha(1); }
            @Override public void setAlpha(int alpha) { }
            @Override public void setColorFilter(android.graphics.ColorFilter filter) { }
            @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        }); setClipToOutline(plateVisible);
    }
    private void rebind() { if (glass != null) glass.clearViews(); fallback(); if (glass != null) glass.attach(this, false); }
    void glass(PanelGlassSession value) {
        if (glass == value) return;
        if (glass != null) glass.close(); glass = value; fallback();
        if (glass != null) glass.attach(this, false);
        else setVisibility(VISIBLE);
    }
    void dropGlass() { memoryFallback = true; glass(null); setVisibility(VISIBLE); }
    void prepareGlass(AccessibilityService service, Display display, Prefs prefs, BooleanSupplier current, Runnable ready, boolean windowOnly) {
        if (closed || prepared) return;
        prepared = true;
        if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY || !PanelGlassSession.allowed(getContext(), prefs)) { ready.run(); return; }
        glass = new PanelGlassSession(getContext(), display);
        if (viewport != null && localSafeArea != null) {
            Rect required = new Rect(viewport.x() + localSafeArea.x(), viewport.y() + localSafeArea.y(), viewport.x() + localSafeArea.right(), viewport.y() + localSafeArea.bottom());
            if (status != null && statusArea != null) required.union(statusArea.x(), statusArea.y(), statusArea.right(), statusArea.bottom());
            glass.captureArea(required);
        }
        glass.attach(this, false); setVisibility(INVISIBLE);
        glass.capture(service, () -> !closed && current.getAsBoolean(), () -> { if (!closed) { setVisibility(VISIBLE); ready.run(); } }, windowOnly);
    }
    void windowMaterial(WindowManager.LayoutParams layout, boolean effects, boolean supported) {
        boolean blur = !memoryFallback && plateVisible && material == Material.FROSTED && effects && supported && (glass == null || !glass.ready());
        layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0;
        if (blur) layout.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(blur ? Math.min(100, Ui.dp(getContext(), 32)) : 0);
    }
    void interactive(boolean enabled) { interactive = enabled; setImportantForAccessibility(enabled ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) { return !interactive || super.onInterceptTouchEvent(event); }
    @Override public boolean onTouchEvent(MotionEvent event) { return !interactive || super.onTouchEvent(event); }
    static boolean horizontal(int edge) { return edge == DockGeometry.LEFT || edge == DockGeometry.RIGHT; }
    static int sign(int edge) { return edge == DockGeometry.TOP || edge == DockGeometry.LEFT ? -1 : 1; }
    static float position(View view, int edge) { return horizontal(edge) ? view.getTranslationX() : view.getTranslationY(); }
    static void translate(View view, int edge, float distance) { view.setTranslationX(horizontal(edge) ? sign(edge) * distance : 0); view.setTranslationY(horizontal(edge) ? 0 : sign(edge) * distance); }
    void enter(float progress, int edge, float extent) { entranceProgress = progress; translate(this, edge, (1 - progress) * extent); setAlpha(1); if (actionHeader != null) actionHeader.entryProgress(progress); if (controlMotion != null) { float wave = android.animation.ValueAnimator.areAnimatorsEnabled() ? (float) Math.sin(progress * Math.PI * 2) * (1 - progress) * .045f : 0; controlEntryX = horizontal(edge) ? wave : -wave * .35f; controlEntryY = horizontal(edge) ? -wave * .35f : wave; invalidate(); } if (notificationMotion != null) notificationMotion.sceneMotion(getTranslationY()); if (content instanceof AppHubView hub) { hub.sceneMotion(getTranslationX(), getTranslationY()); hub.revealSidebar(); } }
    void beginExit(int edge, float extent) {
        if (controlMotion != null) controlMotion.reset(); controlEntryX = controlEntryY = 0; pauseContent.run(); interactive(false); restingX = getTranslationX(); restingY = getTranslationY(); exitStart = position(this, edge); restingClip = getClipBounds();
        restingVisual.set(visual); beginExitCrop(); exitExtent = viewport == null ? extent : horizontal(edge) ? viewport.width() : viewport.height();
        float height = getHeight() > 0 ? getHeight() : getResources().getDisplayMetrics().heightPixels;
        int width = getWidth() > 0 ? getWidth() : getResources().getDisplayMetrics().widthPixels;
        Rect slice = new Rect(Math.max(0, Math.round(-restingX)), Math.max(0, Math.round(-restingY)), Math.max(0, Math.round(Math.min(width, (viewport == null ? width : viewport.width()) - restingX))), Math.max(0, Math.round(Math.min(height, (viewport == null ? height : viewport.height()) - restingY))));
        if (!visual.isEmpty()) { visual.round(outlineBox); slice.intersect(outlineBox); }
        if (restingClip != null) slice.intersect(restingClip); setClipBounds(slice);
    }
    void visibleBounds(RectF result) {
        visualBounds(result);
        if (getClipBounds(pushClip)) { result.left = Math.max(result.left, pushClip.left); result.top = Math.max(result.top, pushClip.top); result.right = Math.min(result.right, pushClip.right); result.bottom = Math.min(result.bottom, pushClip.bottom); }
    }
    boolean push(float distance, int edge) { return push(distance, edge, null); }
    boolean push(float distance, int edge, InterfaceCard incoming) {
        boolean horizontal = horizontal(edge), leading = sign(edge) < 0;
        float position = exitStart - sign(edge) * distance; visibleBounds(pushBounds);
        float start = horizontal ? pushBounds.left : pushBounds.top, end = horizontal ? pushBounds.right : pushBounds.bottom;
        if (incoming != null) {
            incoming.visibleBounds(incomingBounds);
            float incomingPosition = position(incoming, edge) + (incoming.viewport == null ? 0 : horizontal ? incoming.viewport.x() : incoming.viewport.y()) - (viewport == null ? 0 : horizontal ? viewport.x() : viewport.y());
            float boundary = incomingPosition + (horizontal ? leading ? incomingBounds.right : incomingBounds.left : leading ? incomingBounds.bottom : incomingBounds.top);
            // Grow the gap only as the incoming plate enters; cancellation closes it continuously.
            float gap = Math.min(Ui.dp(getContext(), 6), Math.max(0, leading ? boundary : exitExtent - boundary));
            position = leading ? Math.max(position, boundary + gap - start) : Math.min(position, boundary - gap - end);
        }
        if (horizontal) setTranslationX(position); else setTranslationY(position);
        return getAlpha() == 0 || (leading ? position + start >= exitExtent : position + end <= 0);
    }
    void restore() { visual.set(restingVisual); setClipBounds(restingClip); setTranslationX(restingX); setTranslationY(restingY); setAlpha(1); invalidateOutline(); invalidate(); if (ready()) setVisibility(VISIBLE); interactive(true); }
    void release() {
        if (closed) return; closed = true; if (controlMotion != null) controlMotion.reset(); controlMotion = null; interactive(false);
        if (glass != null) { glass.close(); glass = null; }
        if (getParent() instanceof ViewGroup parent) parent.removeView(this);
        releaseContent.run(); removeAllViews();
    }
}
