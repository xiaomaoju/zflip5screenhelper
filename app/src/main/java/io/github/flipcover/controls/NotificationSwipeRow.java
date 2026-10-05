package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.VelocityTracker;
import android.widget.FrameLayout;
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry;
import com.kyant.backdrop.catalog.components.LiquidTensionMotion;

/** Reveal actions; a continued two-button pull merges them before release can request clearing. */
final class NotificationSwipeRow extends FrameLayout implements SharedGlassHost {
    final View surface;
    private final View rail;
    private final View settingsAction, clearAction, settingsFace, clearFace;
    private final View[] actionFaces;
    private final Runnable reveal;
    private final float slop;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final LinearGradient edgeFade = new LinearGradient(0, 0, 1, 0, 0x00FFFFFF, 0xFFFFFFFF, Shader.TileMode.CLAMP);
    private final Matrix edgeMatrix = new Matrix();
    private final Rect drawingClip = new Rect();
    private final LiquidTensionGeometry tension = new LiquidTensionGeometry();
    private final LiquidTensionMotion actionMotion = new LiquidTensionMotion();
    private boolean actionFrameQueued;
    private long actionFrameTime, actionInputTime;
    private float actionInputDistance, mergeTarget;
    private final Runnable actionFrame = this::advanceActions;
    private final RectF tensionBounds = new RectF();
    private final Matrix tensionMatrix = new Matrix(), tensionInverse = new Matrix();
    private PanelGlassSession glass;
    private boolean liquidDrawing;
    private float highlightProgress = 1;
    private ValueAnimator highlightAnimation;
    private float mergeProgress;
    private boolean clearArmed;
    private boolean thresholdSubmitted;
    private float thresholdOpacity = 1;
    private ValueAnimator thresholdFade;
    private final Runnable thresholdTimeout = () -> { if (thresholdSubmitted && !this.dismissing) cancelDismiss(); };
    private float startX, startY, startOffset;
    private boolean dragging, vertical, startedOpen, railTouch;
    private int actionWidth, layers;
    private VelocityTracker velocity;
    private boolean targetOpen, sequenceCanceled, touching, interrupted;
    private final int maximumVelocity;
    private float offset, springVelocity, pull, anchor = 1, springTime, springPosition;
    private ValueAnimator spring;
    private boolean dismissing;
    private Runnable dismissComplete;
    float forceViewportTop;
    final NotificationForce.Spring scrollForce = new NotificationForce.Spring();
    final NotificationForce.Spring sideForce = new NotificationForce.Spring();
    private float boundaryOffset, verticalStrain;
    private int verticalEdge = -1;
    private NotificationScrollView boundaryScroll;
    NotificationSwipeRow(Context context, View surface, View rail, Runnable reveal) {
        super(context); this.surface = surface; this.rail = rail; this.reveal = reveal;
        settingsAction = rail.findViewWithTag("notification-settings"); clearAction = rail.findViewWithTag("notification-clear");
        settingsFace = settingsAction == null ? null : settingsAction.findViewWithTag("notification-action-face"); clearFace = clearAction == null ? null : clearAction.findViewWithTag("notification-action-face");
        actionFaces = settingsFace == null ? new View[0] : clearFace == null ? new View[]{settingsFace} : new View[]{settingsFace, clearFace};
        ViewConfiguration config = ViewConfiguration.get(context); slop = config.getScaledTouchSlop(); maximumVelocity = config.getScaledMaximumFlingVelocity(); setWillNotDraw(false); setClipChildren(false); setChildrenDrawingOrderEnabled(true); setHapticFeedbackEnabled(new Prefs(context).haptics());
        edgePaint.setShader(edgeFade); edgePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        addView(rail, new LayoutParams(Ui.dp(context, PanelUi.SLOT) * 2, Ui.dp(context, PanelUi.SLOT), Gravity.RIGHT | Gravity.CENTER_VERTICAL));
        addView(surface, new LayoutParams(-1, -2)); setActionCount(2); setRailVisible(false);
    }
    void setActionsWidth(int dp) {
        setActionWidth(Ui.dp(getContext(), dp));
    }
    void setActionCount(int count) { setActionWidth(Ui.dp(getContext(), PanelUi.SLOT) * count); }
    private void setActionWidth(int width) {
        if (actionWidth == width) return;
        actionWidth = width; rail.getLayoutParams().width = width; rail.requestLayout();
        if (touching || spring != null || opened()) { cancelTouch(); finishDismiss(); }
    }
    boolean opened() { return targetOpen || touching && surface.getTranslationX() < -1; }
    @Override public void glass(PanelGlassSession session) { glass = session; if (session == null) { stopHighlight(); setLiquidDrawing(false); } else session.prepareTension(); invalidate(); }
    @Override public View glassBody() { return surface; }
    @Override public View[] glassActions() { return actionFaces; }
    @Override public float glassOffsetX() { return sideForce.position; }
    @Override public float glassOffsetY() { return boundaryVisualOffset(); }
    @Override public boolean drawsSharedGlass(View view) { return liquidDrawing && (view == surface || view == settingsFace || view == clearFace); }
    void boundaryForce(float offset, float strain, int edge) { boundaryOffset = offset; verticalStrain = Math.max(-.015f, Math.min(.07f, strain * .07f)); verticalEdge = edge; invalidateForce(); }
    void invalidateForce() { invalidate(); surface.invalidate(); for (View face : actionFaces) face.invalidate(); }
    float boundaryVisualOffset() { return boundaryOffset + scrollForce.position + (boundaryScroll == null ? 0 : boundaryScroll.visualTranslation()); }
    private void drawVerticalForce(Canvas canvas) {
        canvas.translate(sideForce.position, boundaryOffset + scrollForce.position);
        float inertia = Math.max(-.028f, Math.min(.028f, scrollForce.velocity * Math.signum(scrollForce.position) / Math.max(1, getHeight()) * .02f));
        float strain = verticalStrain + inertia;
        canvas.scale(1 - strain * .3f, 1 + strain, getWidth() * .5f, verticalStrain == 0 ? getHeight() * .5f : verticalEdge < 0 ? 0 : getHeight());
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        for (android.view.ViewParent parent = getParent(); parent != null; parent = parent.getParent()) if (parent instanceof NotificationScrollView scroll) { boundaryScroll = scroll; break; }
    }
    boolean liquidDrawing() { return liquidDrawing; }
    private void setLiquidDrawing(boolean active) {
        if (liquidDrawing == active) return;
        liquidDrawing = active;
        // Background suppression must survive hardware display-list reuse, including after rest.
        surface.invalidate(); if (settingsFace != null) settingsFace.invalidate(); if (clearFace != null) clearFace.invalidate();
    }
    void cancelMergedAction() { if (touching || actionFrameQueued || mergeProgress > 0) { if (touching) targetOpen = startedOpen; cancelTouch(); sequenceCanceled = true; } }
    void showActions(boolean show) {
        if (dismissing || thresholdSubmitted) return;
        if (targetOpen == show && spring != null) return;
        settle(show, spring != null ? springVelocity : 0);
    }
    private float edgeLimit() { return Math.max(Ui.dp(getContext(), 64), getWidth() * .35f); }
    private float edgeDelta(float value) { return value - Math.max(-actionWidth, Math.min(0, value)); }
    private float unscaledPosition() { float value = surface.getTranslationX(), extra = edgeDelta(value); return value - extra + extra / NotificationCardSurface.ELASTICITY; }
    private void render(float visual, float shape, float pivot) {
        float forceDelta = visual - surface.getTranslationX();
        float extra = edgeDelta(visual), limit = edgeLimit();
        offset = visual - extra + (float) Math.copySign(-limit * Math.log(Math.max(.0001f, 1 - Math.abs(extra) / (limit * NotificationCardSurface.ELASTICITY))), extra);
        pull = Math.max(-.4f, Math.min(1.35f, shape)); anchor = pivot;
        if (surface instanceof NotificationCardSurface card) card.horizontalPull(pull, anchor);
        surface.setTranslationX(visual); if (!thresholdSubmitted) rail.setAlpha(Math.max(0, Math.min(1, -visual / Math.max(1, actionWidth))));
        if (boundaryScroll != null && forceDelta != 0) boundaryScroll.sideMotion(this, forceDelta);
        driveActions(visual); invalidate();
    }
    private boolean mergeAvailable() { return settingsFace != null && clearFace != null && settingsAction.getVisibility() == VISIBLE && clearAction.getVisibility() == VISIBLE && clearAction.isEnabled(); }
    private void updateMerge(float visual, float seconds) {
        if (thresholdSubmitted) return;
        float maximum = edgeLimit() * NotificationCardSurface.ELASTICITY;
        float begin = Math.min(Ui.dp(getContext(), 10), maximum * .25f), end = Math.min(Ui.dp(getContext(), 24), maximum * .65f);
        float next = mergeAvailable() && actionMotion.ready() && !dismissing && (touching && dragging || mergeProgress > 0) ? Math.max(0, Math.min(1, (-visual - actionWidth - begin) / Math.max(1, end - begin))) : 0;
        if (!touching) next = Math.min(mergeProgress, next); // Rebound cannot arm or intensify a released pull.
        mergeTarget = next;
        float change = Math.max(0, seconds) * 7;
        mergeProgress += Math.max(-change, Math.min(change, next - mergeProgress));
        boolean armed = touching && dragging && next == 1 && mergeProgress == 1;
        if (armed != clearArmed) { clearArmed = armed; setStateDescription(armed ? "松手清除此通知" : null); if (armed) performHapticFeedback(HapticFeedbackConstants.CONFIRM); }
        float travel = Ui.dp(getContext(), PanelUi.SLOT) * .5f * mergeProgress;
        if (settingsFace != null) actionTransform(settingsFace, actionMotion.getCount() - 1, travel);
        if (clearFace != null) actionTransform(clearFace, actionMotion.getCount() < 2 ? -1 : 0, -travel);
        if (armed) submitThreshold();
    }
    private void actionTransform(View face, int node, float mergeTravel) {
        float scale = node < 0 || mergeProgress > 0 ? 1 : actionMotion.scale(node);
        float position = node < 0 ? 0 : actionMotion.position(node) * (1 - mergeProgress);
        face.setScaleX(PanelUi.ACTION_SCALE * scale); face.setScaleY(PanelUi.ACTION_SCALE * scale);
        // Keep the leading edge against the neck while scaling the circle and icon together.
        face.setTranslationX(position + mergeTravel - face.getWidth() * PanelUi.ACTION_SCALE * .5f * (1 - scale));
    }
    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) { super.onLayout(changed, left, top, right, bottom); updateMerge(surface.getTranslationX(), 0); }
    private void submitThreshold() {
        if (thresholdSubmitted) return;
        thresholdSubmitted = sequenceCanceled = true; dragging = touching = clearArmed = false; mergeProgress = mergeTarget = 1;
        stopSpring(); stopActionFrames(); releaseVelocity(); targetOpen = true;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        float travel = Ui.dp(getContext(), PanelUi.SLOT) * .5f; settingsFace.setTranslationX(travel); clearFace.setTranslationX(-travel);
        setStateDescription("正在清除此通知");
        thresholdFade = ValueAnimator.ofFloat(1, 0); thresholdFade.setDuration(180); thresholdFade.setInterpolator(new android.view.animation.LinearInterpolator());
        thresholdFade.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue(); thresholdOpacity = value;
            invalidate(); if (value == 0) rail.setVisibility(INVISIBLE);
        });
        if (ValueAnimator.areAnimatorsEnabled()) thresholdFade.start();
        else { thresholdOpacity = 0; rail.setVisibility(INVISIBLE); }
        // Keep this pose until the notification event confirms removal. Only failure/identity
        // changes restore it; UP or reverse motion cannot split the pair or resubmit.
        postDelayed(thresholdTimeout, 1200); clearAction.performClick();
    }
    private void resetThreshold() {
        removeCallbacks(thresholdTimeout); thresholdSubmitted = false; thresholdOpacity = 1;
        if (thresholdFade != null) { thresholdFade.removeAllUpdateListeners(); thresholdFade.cancel(); thresholdFade = null; }
    }
    private void configureActions() {
        int count = settingsFace == null ? 0 : clearFace != null && clearAction.getVisibility() == VISIBLE ? 2 : 1;
        float slot = Ui.dp(getContext(), PanelUi.SLOT), gap = slot * (1 - PanelUi.ACTION_SCALE) * .5f;
        actionMotion.configure(count, slot, slot * .278f, gap, gap * .8f, slot * .0694f);
    }
    private void driveActions(float visual) {
        if (thresholdSubmitted) return;
        configureActions();
        long now = android.os.SystemClock.uptimeMillis(); float distance = Math.max(0, -visual);
        float speed = now > actionInputTime && actionInputTime > 0 ? (distance - actionInputDistance) * 1000 / (now - actionInputTime) : 0;
        actionInputTime = now; actionInputDistance = distance;
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow() || dismissing) {
            stopActionFrames(); highlightProgress = 1; actionMotion.snap(distance); mergeProgress = mergeTarget = 0; updateMerge(visual, dismissing ? 0 : 1); return;
        }
        actionMotion.drive(distance, speed);
        if (!actionMotion.ready() || actionMotion.moving()) { stopHighlight(); highlightProgress = 0; }
        updateMerge(visual, 0); queueActionFrame();
    }
    private void queueActionFrame() {
        if (actionFrameQueued || !isAttachedToWindow() || getWindowVisibility() != VISIBLE || dismissing || thresholdSubmitted || !(actionMotion.moving() || mergeTarget != mergeProgress)) return;
        actionFrameQueued = true; if (actionFrameTime == 0) actionFrameTime = android.os.SystemClock.uptimeMillis(); postOnAnimation(actionFrame);
        rail.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    private void advanceActions() {
        actionFrameQueued = false;
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE || dismissing || thresholdSubmitted) { stopActionFrames(); return; }
        long now = android.os.SystemClock.uptimeMillis(); float seconds = Math.min(.064f, Math.max(0, (now - actionFrameTime) / 1000f)); actionFrameTime = now;
        actionMotion.advance(seconds); updateMerge(surface.getTranslationX(), seconds); invalidate();
        if (actionMotion.ready() && !actionMotion.moving() && mergeProgress == 0) startHighlight();
        queueActionFrame();
        if (!actionFrameQueued) { actionFrameTime = 0; setRailVisible(rail.getVisibility() == VISIBLE); }
    }
    private void stopActionFrames() { removeCallbacks(actionFrame); stopHighlight(); actionFrameQueued = false; actionFrameTime = actionInputTime = 0; }
    private void snapActions() { stopActionFrames(); stopHighlight(); highlightProgress = 1; configureActions(); actionMotion.snap(Math.max(0, -surface.getTranslationX())); mergeProgress = mergeTarget = 0; clearArmed = false; setStateDescription(null); updateMerge(surface.getTranslationX(), 0); }
    private void startHighlight() {
        if (highlightProgress == 1 || highlightAnimation != null) return;
        if (glass == null || !glass.ready() || !ValueAnimator.areAnimatorsEnabled()) { highlightProgress = 1; return; }
        highlightAnimation = ValueAnimator.ofFloat(0, 1); highlightAnimation.setDuration(LiquidTensionGeometry.HIGHLIGHT_DURATION_MS);
        highlightAnimation.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        highlightAnimation.addUpdateListener(frame -> { highlightProgress = (float) frame.getAnimatedValue(); invalidate(); });
        highlightAnimation.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator ended) { if (highlightAnimation == ended) { highlightAnimation = null; highlightProgress = 1; invalidate(); } } }); highlightAnimation.start();
    }
    private void stopHighlight() { if (highlightAnimation == null) return; ValueAnimator stopped = highlightAnimation; highlightAnimation = null; stopped.removeAllListeners(); stopped.removeAllUpdateListeners(); stopped.cancel(); }
    private void move(float value) {
        float extra = edgeDelta(value), limit = edgeLimit();
        float travel = (float) Math.copySign(limit * (1 - Math.exp(-Math.abs(extra) / limit)), extra);
        render(value - extra + travel * NotificationCardSurface.ELASTICITY, Math.min(1, Math.abs(travel) / (limit * .65f)), extra == 0 ? anchor : extra < 0 ? 1 : 0);
    }
    private void settle(boolean show, float speed) { settle(show, speed, 0); }
    private void settle(boolean show, float speed, float shapeVelocity) {
        stopSpring(); targetOpen = show; if (show) reveal.run();
        float target = show ? -actionWidth : 0;
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) { move(target); setRailVisible(show); return; }
        float from = unscaledPosition(), fromPull = pull, pivot = anchor, initialSpeed = Math.max(-Ui.dp(getContext(), 1800), Math.min(Ui.dp(getContext(), 1800), speed)), limit = edgeLimit();
        if (Math.abs(from - target) < .1f && Math.abs(initialSpeed) < 1 && fromPull == 0 && shapeVelocity == 0) { move(target); setRailVisible(show); return; }
        springTime = 0; springPosition = from;
        boolean[] reached = {show ? from <= target : from >= target};
        spring = ValueAnimator.ofFloat(0, 1); spring.setDuration(TaskSpring.DURATION); spring.setInterpolator(new android.view.animation.LinearInterpolator()); setRailVisible(true);
        spring.addUpdateListener(animation -> {
            float time = (float) animation.getAnimatedValue(), remaining = 1 - TaskSpring.progress(time), impulse = TaskSpring.velocityOffset(time), kick = initialSpeed * impulse;
            float visual = Math.max(-actionWidth - limit * .98f, Math.min(limit * .98f, target + (from - target) * remaining + kick));
            if (!reached[0] && (visual - target) * (from - target) <= 0) reached[0] = true;
            springVelocity = time > springTime ? (visual - springPosition) / ((time - springTime) * TaskSpring.DURATION / 1000f) : initialSpeed; springTime = time; springPosition = visual;
            float shape = fromPull * remaining + (show ? -kick : kick) / (limit * .65f) + shapeVelocity * impulse;
            if (fromPull == 0 && shapeVelocity == 0) shape += Math.abs(edgeDelta(visual)) / (limit * .65f);
            float extra = edgeDelta(visual);
            render(reached[0] ? target + (visual - target) * NotificationCardSurface.ELASTICITY : visual - extra + extra * NotificationCardSurface.ELASTICITY, Math.min(1, shape), pivot);
            if (time >= 1) {
                stopSpring(); move(target); setRailVisible(targetOpen);
            }
        }); spring.start();
    }
    // Keep the exact current pose until the system confirms removal; never close the rail first.
    void prepareDismiss() { if (!dismissing) { stopSpring(); stopActionFrames(); } }
    boolean dismissing() { return dismissing; }
    boolean dismiss(Runnable complete) {
        if (dismissing) { dismissComplete = complete; return true; }
        if (!ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow() || getWindowVisibility() != VISIBLE || !isShown() || getAlpha() == 0) return false;
        if (!getGlobalVisibleRect(new android.graphics.Rect())) return false;
        stopSpring(); stopActionFrames(); releaseVelocity(); removeCallbacks(thresholdTimeout); dragging = touching = false; sequenceCanceled = dismissing = true; dismissComplete = complete;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS); if (!thresholdSubmitted) setRailVisible(false);
        float from = surface.getTranslationX(), fromPull = pull, fromAlpha = getAlpha(), target = -getWidth() * 1.25f;
        spring = ValueAnimator.ofFloat(0, 1); spring.setDuration(360); spring.setInterpolator(new android.view.animation.PathInterpolator(.2f, .55f, .5f, 1));
        spring.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue(), shape = value < .4f ? fromPull + (1.35f - fromPull) * value / .4f : 1.35f * (1 - value) / .6f;
            render(from + (target - from) * value, shape, 1); setAlpha(fromAlpha * (1 - value));
        });
        spring.addListener(new AnimatorListenerAdapter() { @Override public void onAnimationEnd(Animator animation) { if (spring != animation) return; spring = null; finishDismiss(); } }); spring.start(); return true;
    }
    private void finishDismiss() { Runnable complete = dismissComplete; dismissComplete = null; dismissing = false; if (complete != null) complete.run(); }
    void cancelDismiss() { dismissComplete = null; dismissing = false; targetOpen = false; cancelGesture(); }
    private void stopSpring() {
        if (spring != null) { spring.removeAllUpdateListeners(); spring.removeAllListeners(); spring.cancel(); spring = null; }
        springVelocity = 0;
    }
    private void cancelGesture() {
        resetThreshold();
        stopSpring(); releaseVelocity(); dragging = touching = interrupted = false; vertical = sequenceCanceled = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        move(targetOpen ? -actionWidth : 0); snapActions(); setAlpha(1); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_AUTO); setRailVisible(targetOpen);
    }
    private void cancelTouch() {
        if (touching) {
            MotionEvent cancel = MotionEvent.obtain(android.os.SystemClock.uptimeMillis(), android.os.SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
            super.dispatchTouchEvent(cancel); cancel.recycle();
        }
        cancelGesture();
    }
    private void setRailVisible(boolean visible) {
        if (!visible) snapActions();
        rail.setVisibility(visible ? VISIBLE : INVISIBLE);
        rail.setImportantForAccessibility(visible && targetOpen && !dragging && spring == null && !actionFrameQueued ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }
    void setLayers(int count) {
        int next = Math.min(2, Math.max(0, count - 1)); if (next == layers) return; layers = next;
        LayoutParams params = (LayoutParams) surface.getLayoutParams(); params.bottomMargin = Ui.dp(getContext(), layers * 3); surface.setLayoutParams(params); invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        int saved = canvas.save(); drawVerticalForce(canvas); super.onDraw(canvas); float visual = surface.getTranslationX();
        canvas.translate((visual - Math.max(-actionWidth, Math.min(0, visual))) * .2f, 0);
        canvas.scale(1 + .18f * pull * NotificationCardSurface.ELASTICITY, 1 - .10f * pull * NotificationCardSurface.ELASTICITY, getWidth() * anchor, getHeight() * .5f);
        for (int i = layers; i > 0; i--) {
            float inset = Ui.dp(getContext(), i * 4), bottom = getHeight() - Ui.dp(getContext(), (layers - i) * 3);
            paint.setColor(i == 1 ? 0xFF23262C : 0xFF191C21);
            canvas.drawRoundRect(inset, Ui.dp(getContext(), 8), getWidth() - inset, bottom, Ui.dp(getContext(), 14), Ui.dp(getContext(), 14), paint);
        }
        canvas.restoreToCount(saved);
    }
    @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        float fade = Math.min(Ui.dp(getContext(), 12), -surface.getTranslationX());
        if (child != surface || fade <= 0) return super.drawChild(canvas, child, drawingTime);
        // Fade only the departing card at the list edge; exposed actions stay crisp.
        canvas.getClipBounds(drawingClip);
        int saved = canvas.saveLayer(drawingClip.left, drawingClip.top, drawingClip.right, drawingClip.bottom, null);
        boolean drawn = super.drawChild(canvas, child, drawingTime);
        edgeMatrix.setScale(fade, 1); edgeFade.setLocalMatrix(edgeMatrix);
        canvas.drawRect(0, drawingClip.top, fade, drawingClip.bottom, edgePaint);
        canvas.restoreToCount(saved); return drawn;
    }
    @Override protected int getChildDrawingOrder(int count, int position) { return liquidDrawing && count == 2 ? 1 - position : position; }
    private void revealActionIcons() {
        int active = 0;
        for (int i = 1; i < tension.getCount(); i++) if (tension.getJoins()[i] > 0) active = i;
        int shape = 0; float[] shapes = tension.getShapes();
        for (View face : actionFaces) {
            if (!face.isShown()) continue;
            if (!(face instanceof ViewGroup content)) continue;
            shape++; float alpha = face == settingsFace ? 1 - mergeProgress : 1;
            if (liquidDrawing && mergeProgress == 0 && active > 0) {
                if (shape < active) alpha = 0;
                else if (shape == active) {
                    int index = shape * 4;
                    float emergence = Math.max(0, Math.min(1, (shapes[index] + shapes[index + 2] - shapes[0] - shapes[2]) / Math.max(1, shapes[index + 2])));
                    alpha *= emergence * emergence * (3 - 2 * emergence);
                }
            }
            // Reveal the complete glyph above the card, never slice it at the moving edge.
            for (int j = 0; j < content.getChildCount(); j++) if (content.getChildAt(j).getAlpha() != alpha) content.getChildAt(j).setAlpha(alpha);
        }
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        // Glass STACK rows skip draw()/onDraw(); dispatchDraw is shared by both render paths.
        int checkpoint = canvas.save(); drawVerticalForce(canvas);
        boolean drawn = false;
        if (glass != null && (!dismissing || thresholdSubmitted) && (rail.getVisibility() == VISIBLE || thresholdSubmitted) && surface.getTranslationX() < -.5f) {
            tension.reset(); float density = getResources().getDisplayMetrics().density;
            tension.setConnectionRange(48 * density); tension.setBevel(22 * density); tension.setRefraction(18.7f * density);
            tension.setHighlight(highlightProgress);
            tension.setEdgeFade(Math.min(12 * density, -surface.getTranslationX()));
            tensionInverse.reset(); transformMatrixToGlobal(tensionInverse);
            if (tensionInverse.invert(tensionInverse)) {
                tensionBounds.set(0, 0, surface.getWidth(), surface.getHeight());
                if (surface instanceof NotificationCardSurface card) card.visualBounds(tensionBounds);
                addTensionShape(surface, 26 * density);
                for (View face : actionFaces) {
                    if (!face.isShown() && !(thresholdSubmitted && face.getVisibility() == VISIBLE)) continue;
                    tensionBounds.set(0, 0, face.getWidth(), face.getHeight()); addTensionShape(face, Float.MAX_VALUE);
                }
                float completionGap = Ui.dp(getContext(), PanelUi.SLOT) * (1 - PanelUi.ACTION_SCALE) * .5f;
                tension.splitHorizontallyInOrder(2 * density, completionGap);
                if (mergeProgress > 0 && tension.getCount() > 2) tension.mergePair(1, 2, mergeProgress, 16 * density);
                drawn = true;
            }
        }
        if (thresholdSubmitted && thresholdOpacity < 1) {
            int boundary = rail.getLeft(), saved = canvas.save(); canvas.clipRect(0, 0, boundary, getHeight());
            drawForceContents(canvas, drawn); canvas.restoreToCount(saved);
            if (thresholdOpacity > 0) {
                saved = canvas.saveLayerAlpha(boundary, 0, getWidth(), getHeight(), Math.round(thresholdOpacity * 255)); canvas.clipRect(boundary, 0, getWidth(), getHeight());
                drawForceContents(canvas, drawn); canvas.restoreToCount(saved);
            }
        } else drawForceContents(canvas, drawn);
        canvas.restoreToCount(checkpoint);
    }
    private void drawForceContents(Canvas canvas, boolean hasTension) {
        // The rail material and glyphs must share one opacity layer; the card remains
        // opaque on the other side of the already detached gap.
        setLiquidDrawing(hasTension && glass.drawTension(canvas, this, tension)); revealActionIcons(); super.dispatchDraw(canvas);
    }
    private void addTensionShape(View view, float radius) {
        tensionMatrix.reset(); view.transformMatrixToGlobal(tensionMatrix); tensionMatrix.postConcat(tensionInverse); tensionMatrix.mapRect(tensionBounds);
        tension.add(tensionBounds.left, tensionBounds.top, tensionBounds.right, tensionBounds.bottom, radius);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            interrupted = spring != null; stopSpring(); startX = event.getX(); startY = event.getY(); startOffset = offset;
            startedOpen = targetOpen; dragging = false; vertical = false; railTouch = !interrupted && !actionFrameQueued && opened() && startX >= getWidth() + surface.getTranslationX();
            return (opened() || interrupted) && !railTouch;
        }
        if (action == MotionEvent.ACTION_POINTER_DOWN) { targetOpen = startedOpen; cancelGesture(); return false; }
        if (action == MotionEvent.ACTION_MOVE && !vertical && !railTouch) {
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) vertical = true;
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f) { dragging = true; getParent().requestDisallowInterceptTouchEvent(true); reveal.run(); setRailVisible(true); move(startOffset + dx); return true; }
        }
        return dragging;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && !vertical) {
            float dx = event.getX() - startX, dy = event.getY() - startY;
            if (!dragging && Math.abs(dy) > slop && Math.abs(dy) >= Math.abs(dx)) { vertical = true; showActions(startedOpen); }
            if (!vertical && (dragging || Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.15f)) {
                dragging = true; getParent().requestDisallowInterceptTouchEvent(true); reveal.run(); setRailVisible(true);
                move(startOffset + dx);
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (action != MotionEvent.ACTION_UP) { targetOpen = startedOpen; cancelGesture(); return true; }
            float speed = 0; if (velocity != null) { velocity.computeCurrentVelocity(1000, maximumVelocity); speed = velocity.getXVelocity(); }
            float threshold = Ui.dp(getContext(), 450);
            boolean open = vertical || interrupted && !dragging ? startedOpen : dragging && (Math.abs(speed) > threshold ? speed < 0 : surface.getTranslationX() < -actionWidth * .4f);
            float releaseSpeed = dragging && !vertical ? speed * (float) Math.exp(-Math.abs(edgeDelta(offset)) / edgeLimit()) : 0;
            dragging = touching = false;
            settle(open, releaseSpeed);
            getParent().requestDisallowInterceptTouchEvent(false);
        }
        return true;
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (dismissing || thresholdSubmitted) return true;
        int action = event.getActionMasked(); if (action == MotionEvent.ACTION_DOWN) { sequenceCanceled = false; touching = true; releaseVelocity(); velocity = VelocityTracker.obtain(); if (boundaryScroll != null && opened() && event.getX() >= getWidth() + surface.getTranslationX()) boundaryScroll.cardFeedback(this); }
        if (!sequenceCanceled && (event.getPointerCount() > 1 || action == MotionEvent.ACTION_POINTER_DOWN)) {
            sequenceCanceled = true; MotionEvent cancel = MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle();
            targetOpen = startedOpen; cancelGesture();
        }
        if (sequenceCanceled) return true;
        if (velocity != null) velocity.addMovement(event); boolean result = super.dispatchTouchEvent(event);
        if (action == MotionEvent.ACTION_UP && !dragging && !vertical && boundaryScroll != null && Math.abs(event.getX() - startX) < slop && Math.abs(event.getY() - startY) < slop) boundaryScroll.cardFeedback(this);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) { touching = false; releaseVelocity(); } return result;
    }
    private void releaseVelocity() { if (velocity != null) velocity.recycle(); velocity = null; }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (w != oldw || h != oldh) { cancelTouch(); finishDismiss(); } }
    @Override protected void onWindowVisibilityChanged(int visibility) { super.onWindowVisibilityChanged(visibility); if (visibility != VISIBLE && surface != null) { cancelDismiss(); setRailVisible(false); } }
    @Override protected void onDetachedFromWindow() { boundaryScroll = null; boundaryOffset = verticalStrain = 0; scrollForce.reset(); sideForce.reset(); dismissComplete = null; dismissing = false; cancelTouch(); targetOpen = false; move(0); setRailVisible(false); super.onDetachedFromWindow(); }
}
