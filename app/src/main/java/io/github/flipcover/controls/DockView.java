package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

public final class DockView extends InputSurface {
    public interface Listener {
        void action(String id); void configure();
        default void beginPull(String page, float distance, float originY) { }
        default void pull(String page, float distance) { }
        default void release(String page, float distance, float velocity, boolean canceled) { }
        default void toggleVisibility() { }
    }
    private final DockGeometry.Placement placement;
    private final DockGeometry.Chrome chrome;
    private final Listener listener;
    private final LinearLayout track;
    private final View background;
    private final FrameLayout viewport;
    private final List<LinearLayout> pages = new ArrayList<>();
    private final List<ImageButton> launcherButtons = new ArrayList<>();
    private final java.util.Map<ImageButton, String> actionButtons = new java.util.LinkedHashMap<>();
    private boolean launcherDisabled;
    private final SwipeGesture gesture;
    private final View dots;
    private final int extent;
    private final int pagerStart;
    private final Prefs prefs;
    private final int touchSlop;
    private final ImageButton fixed;
    private boolean compact;
    private int page;
    private float startX, startY, startTranslation;
    private enum TouchMode { PENDING, PAGING, CONSUMED }
    private TouchMode touchMode = TouchMode.CONSUMED;
    private boolean previouslyArmed, touchMoved;
    private View pressedButton;
    private final Runnable hold;
    public DockView(Context context, Prefs prefs, DockGeometry.Placement placement, int initialPage, Listener listener) {
        this(context, prefs, placement, initialPage, listener, false);
    }
    public DockView(Context context, Prefs prefs, DockGeometry.Placement placement, int initialPage, Listener listener, boolean initiallyCompact) {
        super(context); this.placement = placement; this.listener = listener; chrome = DockGeometry.chrome(placement, context.getResources().getDisplayMetrics().density);
        this.compact = initiallyCompact; this.prefs = prefs;
        setHapticFeedbackEnabled(prefs.haptics());
        setClipChildren(true); setClipToPadding(true);
        float density = context.getResources().getDisplayMetrics().density;
        int threshold = Math.round((new int[]{10, 16, 24})[prefs.damping()] * density);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        gesture = new SwipeGesture(touchSlop, threshold);
        hold = () -> {
            if (touchMode != TouchMode.PENDING) return;
            endTouch();
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            listener.configure();
        };
        DockGeometry.Box touch = placement.touch(), visual = placement.visual();
        int visualX = visual.x() - touch.x(), visualY = visual.y() - touch.y();
        background = new View(context); background.setBackgroundColor(prefs.chromeStyle().equals("black") ? Ui.BACKGROUND : android.graphics.Color.TRANSPARENT);
        FrameLayout.LayoutParams bg = new FrameLayout.LayoutParams(visual.width(), visual.height());
        bg.leftMargin = visualX; bg.topMargin = visualY; addView(background, bg);
        if (compact) background.setVisibility(GONE);
        DockGeometry.Slots slots = DockGeometry.slots(placement, prefs.perPage(), prefs.leftHand() && !placement.vertical());
        pagerStart = placement.vertical() ? slots.pager().y() : slots.pager().x();
        extent = slots.extent(); int count = slots.pageSize();
        List<String> actions = prefs.scrollingActions();
        int total = Math.max(1, (actions.size() + count - 1) / count);
        viewport = new FrameLayout(context); viewport.setClipChildren(true);
        FrameLayout.LayoutParams viewportParams = new FrameLayout.LayoutParams(slots.pager().width(), slots.pager().height()); viewportParams.leftMargin = slots.pager().x(); viewportParams.topMargin = slots.pager().y();
        viewport.setTag("paging-area"); addView(viewport, viewportParams);
        if (compact) viewport.setVisibility(GONE);
        track = new LinearLayout(context); track.setOrientation(placement.vertical() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        viewport.addView(track, new FrameLayout.LayoutParams(placement.vertical() ? touch.width() : extent * total, placement.vertical() ? extent * total : touch.height()));
        for (int p = 0; p < total; p++) {
            LinearLayout group = new LinearLayout(context); group.setOrientation(placement.vertical() ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            track.addView(group, new LinearLayout.LayoutParams(slots.pager().width(), slots.pager().height())); pages.add(group);
            for (int i = 0; i < count; i++) {
                int index = p * count + i;
                View button = index < actions.size() ? actionButton(context, actions.get(index), extent / count) : new View(context);
                group.addView(button, placement.vertical() ? new LinearLayout.LayoutParams(-1, 0, 1) : new LinearLayout.LayoutParams(0, -1, 1));
            }
        }
        fixed = actionButton(context, prefs.pinnedAction(), placement.vertical() ? slots.fixed().height() : slots.fixed().width());
        fixed.setTag("fixed-action");
        FrameLayout.LayoutParams fixedParams = new FrameLayout.LayoutParams(slots.fixed().width(), slots.fixed().height());
        fixedParams.leftMargin = slots.fixed().x(); fixedParams.topMargin = slots.fixed().y(); addView(fixed, fixedParams);
        if (compact) fixed.setVisibility(GONE);
        fixed.setContentDescription("固定按钮：" + ActionCatalog.label(context, prefs.pinnedAction()));
        fixed.setOnLongClickListener(v -> { listener.configure(); return true; });
        dots = new View(context) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setColor(Ui.chromeColor(prefs)); paint.setAlpha(230); ChromeShadowDrawable.applyShadow(paint, prefs.chromeStyle().equals("contrast") ? density * 1.25f : 0);
                if (compact || pages.size() < 2) return;
                int visible = Math.min(3, pages.size()), first = Math.max(0, Math.min(page - 1, pages.size() - visible));
                float gap = Ui.dp(context, 4), radius = Math.max(1, density * .65f);
                for (int i = 0; i < visible; i++) {
                    paint.setColor(Ui.chromeColor(prefs)); paint.setAlpha(i + first == page ? 220 : 95);
                    float offset = (i - (visible - 1) / 2f) * gap;
                    DockGeometry.Box bar = chrome.firstHandle();
                    float x = placement.vertical() ? bar.x() + bar.width() / 2f : visualX + visual.width() / 2f + offset;
                    float y = placement.vertical() ? visualY + visual.height() / 2f + offset : bar.y() + bar.height() / 2f;
                    canvas.drawCircle(x, y, radius, paint);
                }
            }
        };
        dots.setLayerType(LAYER_TYPE_SOFTWARE, null); dots.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); addView(dots, new FrameLayout.LayoutParams(-1, -1));
        page = Math.max(0, Math.min(total - 1, initialPage)); snap(false);
    }
    private ImageButton actionButton(Context context, String id, int length) {
        ImageButton button = new RuntimeVisuals.Button(context); RuntimeVisuals.surface(button, android.graphics.Color.TRANSPARENT, 12);
        RuntimeVisuals.control(button);
        button.setImageDrawable(Ui.chromeIcon(context, ActionCatalog.icon(context, id), prefs, !id.startsWith("app:") && !id.startsWith("tile:"))); button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        DockGeometry.Box touch = placement.touch(), icons = chrome.icons();
        int thickness = placement.vertical() ? icons.width() : icons.height();
        int size = Math.max(1, Math.min(Ui.dp(context, 23), Math.min(thickness, length - Ui.dp(context, 8))));
        int along = Math.max(0, (length - size) / 2), across = Math.max(0, (thickness - size) / 2);
        if (placement.vertical()) button.setPadding(icons.x() + across, along, touch.width() - icons.x() - across - size, along);
        else button.setPadding(along, icons.y() + across, along, touch.height() - icons.y() - across - size);
        button.setContentDescription(ActionCatalog.label(context, id));
        actionButtons.put(button, id);
        boolean launcher = id.equals("app_hub") || id.equals("app_dock");
        if (launcher) launcherButtons.add(button);
        button.setOnClickListener(v -> { if (!launcher || !launcherDisabled) listener.action(id); });
        return button;
    }
    void launcherDisabled(boolean disabled) {
        if (launcherDisabled == disabled) return;
        launcherDisabled = disabled;
        if (pressedButton != null && launcherButtons.contains(pressedButton)) endTouch();
        for (ImageButton button : launcherButtons) {
            button.setEnabled(!disabled);
            if (disabled) RuntimeVisuals.control(button).cancel();
            button.setStateDescription(disabled ? "应用中心已显示，此入口暂不可用" : null);
            android.graphics.drawable.Drawable icon = ActionCatalog.icon(getContext(), actionButtons.get(button));
            if (disabled) {
                icon = icon.mutate(); icon.setTint(Ui.TEXT); icon.setAlpha(Math.round(255 * .3f));
                android.graphics.drawable.Drawable mark = Ui.icon(getContext(), R.drawable.ic_ms_block, Ui.TEXT);
                mark.setAlpha(Math.round(255 * AppLauncherStyle.DOCK_DISABLED_ALPHA));
                android.graphics.drawable.LayerDrawable marked = new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{icon, mark});
                marked.setLayerSize(1, Math.max(1, Math.round(icon.getIntrinsicWidth() * AppLauncherStyle.DOCK_DISABLED_SCALE)), Math.max(1, Math.round(icon.getIntrinsicHeight() * AppLauncherStyle.DOCK_DISABLED_SCALE)));
                marked.setLayerGravity(1, android.view.Gravity.CENTER); button.setImageDrawable(marked);
            } else button.setImageDrawable(Ui.chromeIcon(getContext(), icon, prefs, true));
        }
    }
    String launcherEntryDiagnostics() { return "disabled=" + launcherDisabled; }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        setSystemGestureExclusionRects(java.util.List.of(new android.graphics.Rect(0, 0, w, h)));
    }
    public int page() { return page; }
    public boolean compact() { return compact; }
    /** Clock-page toggles keep this full-sized window below the moving arrow. */
    void compact(boolean value) {
        if (compact == value) return;
        endTouch(); track.animate().cancel(); compact = value; snap(false);
        background.setVisibility(value ? GONE : VISIBLE); viewport.setVisibility(value ? GONE : VISIBLE); fixed.setVisibility(value ? GONE : VISIBLE);
        dots.invalidate(); setVisibility(value ? GONE : VISIBLE);
    }
    private void begin(MotionEvent event) {
        endTouch(); track.animate().cancel(); gesture.reset();
        startTranslation = placement.vertical() ? track.getTranslationY() : track.getTranslationX();
        startX = event.getX(); startY = event.getY(); previouslyArmed = false; touchMoved = false; touchMode = TouchMode.PENDING;
        pressedButton = compact ? null : buttonAt(placement.vertical() ? startY : startX);
        if (pressedButton != null) pressedButton.setPressed(true);
        if (pressedButton != null) postDelayed(hold, ViewConfiguration.getLongPressTimeout());
    }
    private View buttonAt(float position) {
        DockGeometry.Box visual = placement.visual(), touch = placement.touch();
        int length = placement.vertical() ? touch.height() : touch.width();
        if (position < 0 || position >= length) return null;
        int rowStart = placement.vertical() ? visual.y() - touch.y() : visual.x() - touch.x();
        int rowEnd = rowStart + (placement.vertical() ? visual.height() : visual.width());
        if (position < rowStart || position >= rowEnd) return fixed.isEnabled() ? fixed : null;
        int first = placement.vertical() ? fixed.getTop() : fixed.getLeft(), last = placement.vertical() ? fixed.getBottom() : fixed.getRight();
        if (position >= first && position < last) return fixed.isEnabled() ? fixed : null;
        if (position < pagerStart || position >= pagerStart + extent) return null;
        float trackPosition = position - pagerStart - startTranslation;
        for (LinearLayout group : pages) {
            float local = trackPosition - (placement.vertical() ? group.getTop() : group.getLeft());
            for (int i = 0; i < group.getChildCount(); i++) {
                View button = group.getChildAt(i); int begin = placement.vertical() ? button.getTop() : button.getLeft(), end = placement.vertical() ? button.getBottom() : button.getRight();
                if (local >= begin && local < end && button instanceof ImageButton) return button.isEnabled() ? button : null;
            }
        }
        return null;
    }
    private void move(MotionEvent event) {
        float dx = event.getX() - startX, dy = event.getY() - startY;
        float along = placement.vertical() ? dy : dx, across = placement.vertical() ? dx : dy;
        if (touchMode == TouchMode.PENDING) {
            if (Math.hypot(dx, dy) <= touchSlop) return;
            touchMoved = true; removeCallbacks(hold); clearPressedButton();
            touchMode = TouchMode.PAGING;
        }
        if (touchMode != TouchMode.PAGING || compact) return;
        float offset = gesture.move(along, across);
        if (gesture.armed() && !previouslyArmed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        previouslyArmed = gesture.armed();
        if ((startTranslation >= 0 && offset > 0) || (startTranslation <= -(pages.size() - 1) * extent && offset < 0)) offset *= .3f;
        float translation = startTranslation + Math.max(-extent * .9f, Math.min(extent * .9f, offset));
        translation = Math.max(-(pages.size() - 1) * extent - extent * .27f, Math.min(extent * .27f, translation));
        if (placement.vertical()) track.setTranslationY(translation); else track.setTranslationX(translation);
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        // One owner resolves the sequence before delivering a button click.
        return true;
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (compact) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> begin(event);
            case MotionEvent.ACTION_MOVE -> move(event);
            case MotionEvent.ACTION_UP -> {
                move(event); View clicked = touchMode == TouchMode.PENDING && !touchMoved ? pressedButton : null;
                if (touchMode == TouchMode.PAGING) {
                    switch (gesture.finish()) {
                        case NEXT -> page = Math.min(pages.size() - 1, page + 1);
                        case PREVIOUS -> page = Math.max(0, page - 1);
                        default -> { }
                    }
                }
                endTouch(); snap(true);
                if (clicked != null) { performClick(); clicked.performClick(); }
            }
            case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { endTouch(); snap(true); }
        }
        return true;
    }
    private void endTouch() {
        removeCallbacks(hold); clearPressedButton(); pressedButton = null;
        touchMode = TouchMode.CONSUMED;
    }
    private void clearPressedButton() {
        if (pressedButton == null) return;
        pressedButton.setPressed(false);
        ControlFeedback feedback = RuntimeVisuals.control(pressedButton); if (feedback != null) feedback.cancel();
    }
    @Override public boolean performClick() { return super.performClick(); }
    private void snap(boolean animated) {
        track.animate().cancel();
        if (animated && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            if (placement.vertical()) track.animate().translationY(-page * extent).setDuration(160).start();
            else track.animate().translationX(-page * extent).setDuration(160).start();
        } else if (placement.vertical()) track.setTranslationY(-page * extent); else track.setTranslationX(-page * extent);
        for (int i = 0; i < pages.size(); i++) pages.get(i).setImportantForAccessibility(i == page ? IMPORTANT_FOR_ACCESSIBILITY_AUTO : IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        dots.invalidate(); setContentDescription("快捷栏，第 " + (page + 1) + " 页，共 " + pages.size() + " 页；点击按钮，滑动翻页，长按图标配置；底部横向双白条上滑打开面板，白条可见时长按切换按钮显示");
    }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info); info.setScrollable(true);
        if (page > 0) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        if (page + 1 < pages.size()) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.open_notifications, "打开跟手通知面板"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.open_controls, "打开控制中心"));
        info.addAction(new AccessibilityNodeInfo.AccessibilityAction(R.id.toggle_dock, "显示或隐藏快捷按钮"));
    }
    @Override public boolean performAccessibilityAction(int action, Bundle args) {
        if (action == R.id.toggle_dock) { listener.toggleVisibility(); return true; }
        if (action == R.id.open_notifications) { listener.action("notification_list"); return true; }
        if (action == R.id.open_controls) { listener.action("controls"); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) { page = Math.min(pages.size() - 1, page + 1); snap(true); return true; }
        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) { page = Math.max(0, page - 1); snap(true); return true; }
        return super.performAccessibilityAction(action, args);
    }
    @Override protected void onDetachedFromWindow() { endTouch(); track.animate().cancel(); super.onDetachedFromWindow(); }
}
