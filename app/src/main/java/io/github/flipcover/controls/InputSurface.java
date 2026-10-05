package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import java.util.ArrayList;
import java.util.List;

/** Input is confined to this application's current window. Local replay never enters InputManager. */
class InputSurface extends FrameLayout {
    private final InputDevices devices;
    private final InputSteps steps = new InputSteps();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect rect = new Rect();
    private final Runnable changed = this::configurationChanged;
    private final Runnable captureTimeout = () -> { if (!hasPointerCapture()) devicesStatus("系统未授予指针捕获，请点击窗口后重试"); };
    private View selected, pressed;
    private boolean editing, replaying, direction, observed, pointerDown, suppressUp;
    private int deviceId = -1, keyDeviceId = -1, buttons;
    private float pointerX, pointerY;
    private float absoluteX, absoluteY, padTravel;
    private boolean absoluteReady, padTap;
    private long padDown;
    private long downTime;
    private boolean nativeCard;
    private boolean keyboardNavigation;
    private boolean moreSelected, cancelPressed, cancelLong, confirmPressed, shortcutPressed;
    private String pressedKey;
    private String confirmTarget, scopeKey = "", pageKey = "page";
    private String lastApp = "";
    private InputNavigation.Target target;
    private InputNavigation.Memory memory = new InputNavigation.Memory();
    private ViewGroup componentScope;
    private DetailSheet shortcuts;
    private boolean shortcutEditing;
    private View shortcutTextFocus;
    private java.util.function.Consumer<String> shortcutAction = this::defaultShortcut;
    private final Runnable longCancel = () -> { if (cancelPressed) { cancelLong = true; toggleShortcuts(); } };
    private final android.view.ViewTreeObserver.OnGlobalLayoutListener layoutChanged = this::initializeNavigation;
    private boolean passive;
    private java.util.function.BooleanSupplier valid = () -> true;
    private Runnable back = () -> { }, focusWindow = () -> { };
    private Runnable released = () -> { };
    private java.util.function.BiConsumer<AdapterView<?>, Integer> collectionScroll = (view, position) -> { };
    void collectionScroll(java.util.function.BiConsumer<AdapterView<?>, Integer> action) { collectionScroll = action; }
    InputSurface(Context context) { super(context); devices = CoverApp.inputs(context); setFocusableInTouchMode(true); setClipChildren(true); }
    void navigation(Runnable back, Runnable focusWindow) { this.back = back; this.focusWindow = focusWindow; }
    void released(Runnable callback) { released = callback; }
    void memory(InputNavigation.Memory value, String page) { memory = value; pageKey = page; }
    void page(String value) { if (!pageKey.equals(value)) { remember(); pageKey = value; target = null; selected = null; componentScope = null; scopeKey = ""; editing = moreSelected = false; } }
    void shortcuts(java.util.function.Consumer<String> callback) { shortcutAction = callback; }
    void passive() { passive = true; }
    void keyboardFocus() { if (!passive && !nativeCard && devices.keyboardAvailable() && isShown() && getChildCount() > 0 && !hasWindowFocus()) { focusWindow.run(); requestFocus(); } }
    void nativeCard(java.util.function.BooleanSupplier valid) { nativeCard = true; this.valid = valid; }
    private void devicesStatus(String value) { devices.status(value); }
    private void configurationChanged() {
        if (keyDeviceId >= 0 && InputDevice.getDevice(keyDeviceId) == null) { cancelInput(); keyDeviceId = -1; }
        if (deviceId >= 0 && devices.device(deviceId) == null) { cancelInput(); deviceId = -1; }
        if (pointerDown || buttons != 0) return; // Commit mode changes only after the triggering button sequence.
        if (!devices.needsCapture() && hasPointerCapture()) release();
        keyboardFocus(); initializeNavigation();
    }
    void release() { remember(); cancelInput(); if (shortcuts != null) closeShortcuts(); editing = false; if (hasPointerCapture()) releasePointerCapture(); devices.capture(this, false); selected = null; target = null; direction = false; keyboardNavigation = false; invalidate(); }
    private void cancelInput() {
        removeCallbacks(captureTimeout); removeCallbacks(longCancel); cancelPressed = cancelLong = confirmPressed = shortcutPressed = false;
        if (pointerDown) localTouch(MotionEvent.ACTION_CANCEL);
        pointerDown = false; buttons = 0; pressed = null; editing = false; absoluteReady = false; padTap = false; steps.reset();
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); getViewTreeObserver().addOnGlobalLayoutListener(layoutChanged); if (!observed) { observed = true; devices.observe(changed); } post(this::keyboardFocus); }
    @Override protected void onDetachedFromWindow() { getViewTreeObserver().removeOnGlobalLayoutListener(layoutChanged); release(); if (observed) { observed = false; devices.unobserve(changed); } super.onDetachedFromWindow(); }
    @Override protected void onVisibilityChanged(View view, int visibility) { super.onVisibilityChanged(view, visibility); if (devices == null) return; if (!isShown()) release(); else if (view == this) post(this::keyboardFocus); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (!focus) release(); else { initializeNavigation(); if (nativeCard && valid.getAsBoolean() && devices.needsCapture()) requestPointerCapture(); } }
    @Override public void onPointerCaptureChange(boolean capture) {
        super.onPointerCaptureChange(capture); removeCallbacks(captureTimeout);
        if (!capture) { cancelInput(); released.run(); } else { direction = devices.needsCapture(); pointerX = getWidth() / 2f; pointerY = getHeight() / 2f; }
        devices.capture(this, capture); if (capture) beginNavigation(); invalidate();
    }
    private boolean start(MotionEvent event) {
        if (modal(this) instanceof DetailSheet sheet && sheet.inputClosing()) { cancelInput(); return true; }
        if (devices.observeEvent(event)) { suppressUp = true; return true; }
        if (suppressUp) { if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) suppressUp = false; return true; }
        if (!devices.direction(event.getDeviceId()) || !isShown() || !InputDevices.pointer(event.getDevice())) return false;
        deviceId = event.getDeviceId(); direction = true; focusWindow.run(); requestFocus();
        if (hasWindowFocus()) { requestPointerCapture(); removeCallbacks(captureTimeout); postDelayed(captureTimeout, 800); }
        else devicesStatus("请点击当前界面以启用方向导航");
        return true;
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        if (!replaying && InputDevices.pointer(event.getDevice()) && start(event)) return true;
        return super.dispatchGenericMotionEvent(event);
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!replaying && InputDevices.pointer(event.getDevice()) && start(event)) return true;
        if (!replaying && event.getActionMasked() == MotionEvent.ACTION_DOWN) { remember(); cancelInput(); memory.touch = true; selected = null; target = null; editing = moreSelected = keyboardNavigation = false; invalidate(); }
        return super.dispatchTouchEvent(event);
    }
    @Override public boolean dispatchCapturedPointerEvent(MotionEvent event) {
        if (!hasPointerCapture() || !isShown() || !valid.getAsBoolean()) { release(); return false; }
        if (modal(this) instanceof DetailSheet sheet && sheet.inputClosing()) { cancelInput(); return true; }
        if (devices.observeEvent(event)) { suppressUp = true; return true; }
        if (suppressUp) { if (event.getButtonState() == 0) suppressUp = false; return true; }
        if (deviceId != event.getDeviceId()) { if (buttons != 0) return true; steps.reset(); absoluteReady = false; padTap = false; deviceId = event.getDeviceId(); }
        direction = devices.direction(deviceId);
        boolean rawPad = event.isFromSource(InputDevice.SOURCE_TOUCHPAD) && !event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE);
        if (rawPad && event.getActionMasked() == MotionEvent.ACTION_DOWN) { absoluteX = event.getX(); absoluteY = event.getY(); absoluteReady = true; padTravel = 0; padDown = event.getEventTime(); padTap = true; }
        if (event.getPointerCount() > 1 || event.getActionMasked() == MotionEvent.ACTION_CANCEL) { cancelInput(); return true; }
        InputDevices.Device device = devices.device(deviceId); int sensitivity = device == null ? 1 : devices.sensitivity(device);
        if (event.getActionMasked() == MotionEvent.ACTION_MOVE || event.getActionMasked() == MotionEvent.ACTION_HOVER_MOVE) {
            float dx = event.getX(), dy = event.getY();
            // Captured mouse coordinates are relative. Captured touchpad coordinates can be absolute.
            if (rawPad) {
                dx = absoluteReady ? event.getX() - absoluteX : 0; dy = absoluteReady ? event.getY() - absoluteY : 0;
                absoluteX = event.getX(); absoluteY = event.getY(); absoluteReady = true; padTravel += Math.abs(dx) + Math.abs(dy);
            }
            if (direction) { int next = steps.move(dx, dy, new float[]{48, 28, 16}[sensitivity]); if (next != 0 && buttons == 0) { beginNavigation(); move(next); } }
            else { float scale = new float[]{.6f, 1f, 1.6f}[sensitivity]; pointerX = Math.max(1, Math.min(getWidth() - 2, pointerX + dx * scale)); pointerY = Math.max(1, Math.min(getHeight() - 2, pointerY + dy * scale)); if (pointerDown) localTouch(MotionEvent.ACTION_MOVE); }
        }
        if (event.getActionMasked() == MotionEvent.ACTION_SCROLL) scroll(event.getAxisValue(MotionEvent.AXIS_VSCROLL) < 0 ? FOCUS_DOWN : FOCUS_UP);
        int nextButtons = event.getButtonState();
        if (event.getActionMasked() == MotionEvent.ACTION_UP && !rawPad) nextButtons = 0;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && nextButtons == 0 && !rawPad) nextButtons = MotionEvent.BUTTON_PRIMARY;
        if ((nextButtons & MotionEvent.BUTTON_PRIMARY) != 0 && (buttons & MotionEvent.BUTTON_PRIMARY) == 0) {
            padTap = false;
            downTime = SystemClock.uptimeMillis();
            if (direction) { beginNavigation(); pressed = selected; pressedKey = target == null ? null : target.key; }
            else { pointerDown = true; localTouch(MotionEvent.ACTION_DOWN); }
        }
        if ((nextButtons & MotionEvent.BUTTON_PRIMARY) == 0 && (buttons & MotionEvent.BUTTON_PRIMARY) != 0) {
            View target = pressed; pressed = null;
            if (direction && target != null && target == selected && target.isAttachedToWindow()) { ensureSelected(); if (this.target != null && this.target.key.equals(pressedKey)) activate(target); }
            else if (pointerDown) { localTouch(MotionEvent.ACTION_UP); pointerDown = false; }
        }
        if ((nextButtons & MotionEvent.BUTTON_SECONDARY) != 0 && (buttons & MotionEvent.BUTTON_SECONDARY) == 0) cancelDown();
        if ((nextButtons & MotionEvent.BUTTON_SECONDARY) == 0 && (buttons & MotionEvent.BUTTON_SECONDARY) != 0) cancelUp();
        if (rawPad && event.getActionMasked() == MotionEvent.ACTION_UP) {
            if (padTap && padTravel < android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop() && event.getEventTime() - padDown < android.view.ViewConfiguration.getLongPressTimeout()) { if (direction) { beginNavigation(); if (selected != null) activate(selected); } else { downTime = SystemClock.uptimeMillis(); localTouch(MotionEvent.ACTION_DOWN); localTouch(MotionEvent.ACTION_UP); } }
            absoluteReady = false; padTap = false;
        }
        buttons = nextButtons; configurationChanged(); invalidate(); return true;
    }
    private void initializeNavigation() {
        if (passive || memory.touch || !hasWindowFocus() || !isShown() || !valid.getAsBoolean()) return;
        if (devices.keyboardAvailable() || devices.needsCapture() || keyboardNavigation) { keyboardNavigation = true; direction = true; ensureSelected(); invalidate(); }
    }
    private void beginNavigation() { memory.touch = false; keyboardNavigation = true; direction = true; ensureSelected(); }
    private void cancelDown() { if (cancelPressed) return; cancelPressed = true; cancelLong = false; postDelayed(longCancel, 500); }
    private void cancelUp() { if (!cancelPressed) return; removeCallbacks(longCancel); boolean ordinary = !cancelLong; cancelPressed = cancelLong = false; if (ordinary) goBack(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { super.onSizeChanged(w, h, oldw, oldh); if (oldw != 0 || oldh != 0) { cancelInput(); if (shortcuts != null) closeShortcuts(); } }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (!valid.getAsBoolean()) { cancelInput(); return super.dispatchKeyEvent(event); }
        if (modal(this) instanceof DetailSheet sheet && sheet.inputClosing()) { cancelInput(); return true; }
        int key = event.getKeyCode(); boolean down = event.getAction() == KeyEvent.ACTION_DOWN; if (down) keyDeviceId = event.getDeviceId();
        if (key == KeyEvent.KEYCODE_ESCAPE || key == KeyEvent.KEYCODE_BACK) {
            if (down) cancelDown(); else { if (event.isCanceled()) { removeCallbacks(longCancel); cancelPressed = false; } else cancelUp(); } return true;
        }
        if (key == KeyEvent.KEYCODE_F6) { if (down && event.getRepeatCount() == 0) shortcutPressed = true; else if (!down && shortcutPressed) { shortcutPressed = false; if (!event.isCanceled()) toggleShortcuts(); } return true; }
        View focused = findFocus();
        if (shortcuts == null && focused instanceof EditText && inside(focused, scope()) && !event.isCtrlPressed() && !event.isAltPressed()) { editing = true; return super.dispatchKeyEvent(event); }
        int navigation = switch (key) { case KeyEvent.KEYCODE_DPAD_LEFT -> FOCUS_LEFT; case KeyEvent.KEYCODE_DPAD_RIGHT -> FOCUS_RIGHT; case KeyEvent.KEYCODE_DPAD_UP -> FOCUS_UP; case KeyEvent.KEYCODE_DPAD_DOWN -> FOCUS_DOWN; default -> 0; };
        if (navigation != 0 && !event.isCtrlPressed() && !event.isAltPressed() && !event.isMetaPressed()) { beginNavigation(); if (down) move(navigation); return true; }
        if (key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER || key == KeyEvent.KEYCODE_DPAD_CENTER) {
            beginNavigation();
            if (down && event.getRepeatCount() == 0) { confirmPressed = true; confirmTarget = scopeKey + ":" + (target == null ? "" : target.key) + ":" + moreSelected; }
            else if (!down && confirmPressed) { confirmPressed = false; if (!event.isCanceled() && target != null && confirmTarget.equals(scopeKey + ":" + target.key + ":" + moreSelected)) activate(selected); }
            return true;
        }
        if (key == KeyEvent.KEYCODE_MENU || key == KeyEvent.KEYCODE_F10 && event.isShiftPressed()) { beginNavigation(); if (!down && target != null && target.more != null) target.more.run(); return true; }
        return super.dispatchKeyEvent(event);
    }
    void cancelCommand() { if (!(modal(this) instanceof DetailSheet sheet && sheet.inputClosing())) goBack(); }
    private void goBack() {
        if (shortcuts != null) { closeShortcuts(); return; }
        if (editing || findFocus() instanceof EditText && inside(findFocus(), scope())) { editing = false; requestFocus(); getContext().getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(getWindowToken(), 0); invalidate(); return; }
        if (moreSelected) { moreSelected = false; remember(); invalidate(); return; }
        if (componentScope != null) { componentScope = null; scopeKey = ""; ensureSelected(); invalidate(); return; }
        remember(); View modal = modal(this); if (modal instanceof DetailSheet sheet) sheet.back(); else back.run();
        post(this::initializeNavigation);
    }
    private boolean inside(View view, View owner) { for (View cursor = view; cursor != null; cursor = cursor.getParent() instanceof View parent ? parent : null) if (cursor == owner) return true; return false; }
    private View modal(View root) { if (root instanceof ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--) { View child = group.getChildAt(i); if (!child.isShown()) continue; if (child instanceof DetailSheet) return child; View nested = modal(child); if (nested != child) return nested; } return root; }
    private View scope() { View modal = modal(this); if (modal != this) return modal; if (componentScope != null && componentScope.isAttachedToWindow()) return componentScope; componentScope = null; return this; }
    private List<InputNavigation.Target> targets() { View root = scope(); return root == componentScope ? InputNavigation.contents(componentScope) : InputNavigation.targets(root); }
    private List<InputNavigation.Target> currentTargets = List.of();
    private void remember() {
        if (target == null || scopeKey.isEmpty()) return;
        int index = 0; for (int i = 0; i < currentTargets.size(); i++) if (currentTargets.get(i).key.equals(target.key)) { index = i; break; }
        memory.save(scopeKey, new InputNavigation.Position(target.key, index, moreSelected));
    }
    private void ensureSelected() {
        View root = scope(); View taskPage = findViewWithTag("recent-tasks"), workspace = findViewWithTag("hub-grid");
        String section = taskPage != null && taskPage.isShown() ? "/tasks" : workspace != null ? workspace.isShown() ? "/launcher" : "/dock" : "";
        String nextScope = pageKey + section + (root == this ? "" : ":" + System.identityHashCode(root));
        boolean changed = !nextScope.equals(scopeKey); if (changed) { remember(); scopeKey = nextScope; editing = false; target = null; moreSelected = false; }
        List<InputNavigation.Target> list = targets(); InputNavigation.Position saved = memory.positions.get(scopeKey);
        InputNavigation.Target next = InputNavigation.find(list, target != null ? target.key : saved == null ? "" : saved.key());
        if (next == null) { editing = false; moreSelected = false; next = saved != null && !list.isEmpty() ? list.get(Math.min(saved.index(), list.size() - 1)) : InputNavigation.first(list); }
        else if (target == null && saved != null) moreSelected = saved.more() && next.more != null;
        boolean relocated = target == null || next == null || !target.key.equals(next.key);
        target = next; selected = next == null ? null : next.view; currentTargets = list;
        if ((changed || relocated) && selected != null) selected.requestRectangleOnScreen(next.local == null ? new Rect(0, 0, selected.getWidth(), selected.getHeight()) : new Rect(next.local), true);
        if (target != null) remember();
    }
    private void choose(InputNavigation.Target next) {
        if (next == null) return; target = next; selected = next.view; if (next.region == InputNavigation.Region.APPS) lastApp = next.key; moreSelected = false; editing = false; remember();
        Rect box = next.local == null ? new Rect(0, 0, selected.getWidth(), selected.getHeight()) : new Rect(next.local); selected.requestRectangleOnScreen(box, false); invalidate();
    }
    void move(int direction) {
        ensureSelected(); if (target == null) return;
        if (shortcuts != null) { moveShortcut(direction); return; }
        if (editing) {
            if (selected instanceof OriginalLiquidTabs tabs) { tabs.inputStep(direction == FOCUS_RIGHT || direction == FOCUS_DOWN); return; }
            int key = direction == FOCUS_LEFT || direction == FOCUS_DOWN ? KeyEvent.KEYCODE_DPAD_LEFT : KeyEvent.KEYCODE_DPAD_RIGHT;
            if (selected instanceof EditText) key = direction == FOCUS_LEFT ? KeyEvent.KEYCODE_DPAD_LEFT : direction == FOCUS_RIGHT ? KeyEvent.KEYCODE_DPAD_RIGHT : direction == FOCUS_UP ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
            if (selected instanceof LevelSlider) key = direction == FOCUS_LEFT || direction == FOCUS_DOWN ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP;
            selected.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, key)); selected.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, key)); return;
        }
        if (moreSelected) { if (direction == FOCUS_LEFT || direction == FOCUS_DOWN || direction == FOCUS_UP) { moreSelected = false; remember(); invalidate(); return; } }
        else if (target.more != null && (direction == FOCUS_RIGHT && target.region != InputNavigation.Region.TASK || direction == FOCUS_UP && target.region == InputNavigation.Region.TASK)) { moreSelected = true; remember(); invalidate(); return; }
        RecentTasksView tasks = InputNavigation.parent(selected, RecentTasksView.class);
        if (tasks != null && (target.region == InputNavigation.Region.TASK || target.region == InputNavigation.Region.CLEAR)) {
            String next = tasks.inputNavigate(target.key, direction); if (next != null) { currentTargets = targets(); choose(InputNavigation.find(currentTargets, next)); } return;
        }
        AdapterView<?> collection = InputNavigation.parent(selected, AdapterView.class);
        if (collection instanceof android.widget.ListView list && (direction == FOCUS_UP || direction == FOCUS_DOWN)) {
            int position = collection.getPositionForView(selected), next = position + (direction == FOCUS_UP ? -1 : 1);
            if (next >= 0 && next < collection.getCount()) {
                list.setSelection(next); collectionScroll.accept(collection, next);
                list.post(() -> { if (!list.isAttachedToWindow()) return; currentTargets = targets(); for (InputNavigation.Target item : currentTargets) if (InputNavigation.parent(item.view, AdapterView.class) == list && list.getPositionForView(item.view) == next) { choose(item); break; } }); return;
            }
        }
        InputNavigation.Target next = InputNavigation.move(this, currentTargets, target, direction);
        if (next != null && next.region == InputNavigation.Region.APPS && (target.region == InputNavigation.Region.SIDEBAR || target.region == InputNavigation.Region.PAGE)) { InputNavigation.Target previous = InputNavigation.find(currentTargets, lastApp); if (previous != null) next = previous; }
        if (next != null) { boolean previousMore = target.region == InputNavigation.Region.CONTROL && next.region == InputNavigation.Region.CONTROL && direction == FOCUS_LEFT && !next.key.equals(target.key); choose(next); if (previousMore && target.more != null) { moreSelected = true; remember(); } }
        else if (direction == FOCUS_DOWN && scope() == this) toggleShortcuts();
        invalidate();
    }
    private Rect bounds(View view) { if (InputNavigation.parent(view, SettingsUi.Viewport.class) != null) return SettingsUi.Viewport.bounds(view, null, this); int[] p = new int[2], o = new int[2]; view.getLocationOnScreen(p); getLocationOnScreen(o); return new Rect(p[0] - o[0], p[1] - o[1], p[0] - o[0] + view.getWidth(), p[1] - o[1] + view.getHeight()); }
    private void activate(View view) {
        ensureSelected(); if (target == null || target.view != view) return; remember();
        if (moreSelected) { moreSelected = false; remember(); if (target.more != null) target.more.run(); post(this::initializeNavigation); return; }
        if (target.region == InputNavigation.Region.WIDGET && view instanceof ViewGroup group) { componentScope = group; scopeKey = ""; ensureSelected(); invalidate(); return; }
        if (view instanceof android.widget.AbsSeekBar || view instanceof LevelSlider || view instanceof EditText || view instanceof OriginalLiquidTabs) { editing = !editing; if (editing) { view.requestFocus(); if (view instanceof EditText) view.performClick(); } else requestFocus(); invalidate(); return; }
        if (target.primary != null) target.primary.run();
        else if (!view.performClick()) { Rect box = target.bounds(this); float x = pointerX, y = pointerY; pointerX = box.exactCenterX(); pointerY = box.exactCenterY(); downTime = SystemClock.uptimeMillis(); localTouch(MotionEvent.ACTION_DOWN); localTouch(MotionEvent.ACTION_UP); pointerX = x; pointerY = y; }
        post(this::initializeNavigation);
    }
    private void scroll(int direction) { move(direction); }
    private final List<String> shortcutIds = new ArrayList<>();
    private void defaultShortcut(String id) {
        if ("back".equals(id)) { goBack(); return; }
        CoverService service = CoverService.instance;
        if (service == null || getDisplay() == null || !service.inputShortcut(id, getDisplay().getDisplayId())) android.widget.Toast.makeText(getContext(), "请在已选择的外屏开启助手后使用快捷栏", android.widget.Toast.LENGTH_SHORT).show();
    }
    private void toggleShortcuts() {
        if (shortcuts != null) { closeShortcuts(); return; }
        beginNavigation(); remember(); shortcutTextFocus = findFocus(); shortcutEditing = editing || shortcutTextFocus instanceof EditText && inside(shortcutTextFocus, scope());
        Prefs prefs = devices.prefs; shortcutIds.clear(); shortcutIds.addAll(prefs.scrollingActions()); String pinned = prefs.pinnedAction(); if (ActionCatalog.valid(pinned) && !shortcutIds.contains(pinned)) shortcutIds.add(pinned);
        shortcutIds.removeIf(id -> !ActionCatalog.valid(id)); if (shortcutIds.isEmpty()) return;
        memory.shortcut = Math.max(0, Math.min(memory.shortcut, shortcutIds.size() - 1));
        shortcuts = new DetailSheet(getContext(), "快捷栏", this::closeShortcuts); shortcuts.setTag("input-shortcuts"); shortcuts.compactWidth(320, 1f); shortcuts.glassControls();
        addView(shortcuts, new LayoutParams(-1, -1)); renderShortcuts(); shortcuts.enter(null, glass(this)); editing = false; ensureSelected(); selectShortcut(); invalidate();
    }
    private PanelGlassSession glass(View root) { if (root instanceof InterfaceCard card) return card.glass(); if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { PanelGlassSession found = glass(group.getChildAt(i)); if (found != null) return found; } return null; }
    private void renderShortcuts() {
        if (shortcuts == null) return; shortcuts.content.removeAllViews(); int count = Math.max(1, Math.min(5, devices.prefs.perPage())); int page = memory.shortcut / count, start = page * count;
        android.widget.LinearLayout row = Ui.row(getContext());
        for (int i = start; i < Math.min(start + count, shortcutIds.size()); i++) {
            int index = i; String id = shortcutIds.get(i); android.widget.LinearLayout cell = Ui.column(getContext()); cell.setGravity(android.view.Gravity.CENTER); cell.setPadding(Ui.dp(getContext(), 2), Ui.dp(getContext(), 8), Ui.dp(getContext(), 2), Ui.dp(getContext(), 8));
            android.widget.ImageView icon = new android.widget.ImageView(getContext()); icon.setImageDrawable(ActionCatalog.icon(getContext(), id)); cell.addView(icon, new android.widget.LinearLayout.LayoutParams(Ui.dp(getContext(), 24), Ui.dp(getContext(), 24)));
            android.widget.TextView label = Ui.text(getContext(), ActionCatalog.label(getContext(), id), 11, Ui.TEXT); label.setMaxLines(2); label.setGravity(android.view.Gravity.CENTER); cell.addView(label);
            cell.setContentDescription(ActionCatalog.label(getContext(), id)); cell.setOnClickListener(v -> { memory.shortcut = index; closeShortcuts(); shortcutAction.accept(id); }); cell.setTag("shortcut:" + index);
            InputNavigation.bind(cell, "shortcut:" + index, InputNavigation.Region.DOCK, cell::performClick, null); row.addView(cell, new android.widget.LinearLayout.LayoutParams(0, -2, 1));
        }
        shortcuts.content.addView(row); android.widget.TextView hint = Ui.text(getContext(), "← → 选择 · 确认执行 · 取消返回  " + (page + 1) + "/" + ((shortcutIds.size() + count - 1) / count), 10, Ui.MUTED); hint.setGravity(android.view.Gravity.CENTER); shortcuts.content.addView(hint);
        row.post(() -> { if (shortcuts != null) { selectShortcut(); invalidate(); } });
    }
    private void selectShortcut() { ensureSelected(); choose(InputNavigation.find(currentTargets, "shortcut:" + memory.shortcut)); }
    private void moveShortcut(int direction) {
        if (direction == FOCUS_UP) { closeShortcuts(); return; } if (direction != FOCUS_LEFT && direction != FOCUS_RIGHT) return;
        int next = Math.max(0, Math.min(shortcutIds.size() - 1, memory.shortcut + (direction == FOCUS_LEFT ? -1 : 1))); if (next == memory.shortcut) return;
        int count = Math.max(1, Math.min(5, devices.prefs.perPage())); boolean page = next / count != memory.shortcut / count; memory.shortcut = next; if (page) renderShortcuts(); else selectShortcut();
    }
    private void closeShortcuts() {
        if (shortcuts == null) return; DetailSheet old = shortcuts; shortcuts = null; removeView(old); target = null; selected = null; scopeKey = ""; ensureSelected(); editing = shortcutEditing;
        if (editing && shortcutTextFocus != null && shortcutTextFocus.isAttachedToWindow()) shortcutTextFocus.requestFocus(); shortcutTextFocus = null; invalidate();
    }
    void menu(InputPopupMenu menu, View anchor) {
        menu(menu.getMenu(), anchor);
    }
    boolean navigating() { return hasPointerCapture() || keyboardNavigation; }
    private void menu(android.view.Menu menu, View anchor) {
        final DetailSheet[] owner = new DetailSheet[1]; DetailSheet sheet = new DetailSheet(getContext(), "操作", () -> { removeView(owner[0]); selected = anchor.isAttachedToWindow() ? anchor : null; }); owner[0] = sheet;
        for (int i = 0; i < menu.size(); i++) { android.view.MenuItem item = menu.getItem(i); if (!item.isVisible()) continue; View row = Ui.button(getContext(), (item.isChecked() ? "✓ " : "") + item.getTitle(), () -> { removeView(sheet); selected = anchor; if (!anchor.isAttachedToWindow()) return; if (item.hasSubMenu()) menu(item.getSubMenu(), anchor); else menu.performIdentifierAction(item.getItemId(), 0); }); row.setEnabled(item.isEnabled()); sheet.content.addView(row); }
        addView(sheet, new LayoutParams(-1, -1)); selected = null; sheet.enter(null);
    }
    private void localTouch(int action) { MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, pointerX, pointerY, 0); event.setSource(InputDevice.SOURCE_TOUCHSCREEN); replaying = true; try { super.dispatchTouchEvent(event); } finally { replaying = false; event.recycle(); } }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas); if (!hasPointerCapture() && !keyboardNavigation) return;
        paint.setStrokeWidth(Ui.dp(getContext(), 2));
        if (direction) { if (selected == null || !selected.isAttachedToWindow()) return; Rect box = target == null ? bounds(selected) : target.bounds(this); Rect visible = new Rect(); if (!selected.getGlobalVisibleRect(visible)) return; int[] origin = new int[2]; getLocationOnScreen(origin); visible.offset(-origin[0], -origin[1]); if (!box.intersect(visible)) return; paint.setStyle(Paint.Style.STROKE); paint.setColor(editing ? 0xFFFFD77A : 0xFF80AAFF); if (!moreSelected) canvas.drawRoundRect(box.left + 2, box.top + 2, box.right - 2, box.bottom - 2, Ui.dp(getContext(), 8), Ui.dp(getContext(), 8), paint);
            if (target != null && target.more != null) { float size = Ui.dp(getContext(), 24), right = Math.min(getWidth() - 3, box.right - 2), top = Math.max(2, box.top + 2); paint.setStyle(Paint.Style.FILL); paint.setColor(moreSelected ? 0xFF80AAFF : 0xE6222933); canvas.drawRoundRect(right - size, top, right, top + size, size / 2, size / 2, paint); paint.setColor(moreSelected ? 0xFF102030 : 0xFFFFFFFF); paint.setTextSize(Ui.dp(getContext(), 18)); paint.setTextAlign(Paint.Align.CENTER); canvas.drawText("⋯", right - size / 2, top + size * .72f, paint); }
        }
        else { float size = Ui.dp(getContext(), 16); Path path = new Path(); path.moveTo(pointerX, pointerY); path.lineTo(pointerX + size * .25f, pointerY + size); path.lineTo(pointerX + size * .55f, pointerY + size * .6f); path.lineTo(pointerX + size, pointerY + size * .5f); path.close(); paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFFFFFFF); canvas.drawPath(path, paint); paint.setStyle(Paint.Style.STROKE); paint.setColor(0xFF152333); canvas.drawPath(path, paint); }
    }
}
