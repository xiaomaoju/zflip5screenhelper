package io.github.flipcover.controls;

import android.accessibilityservice.AccessibilityService;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.service.notification.StatusBarNotification;
import android.view.Display;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.view.View;
import android.view.MotionEvent;
import android.animation.ValueAnimator;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

public final class CoverService extends AccessibilityService implements DisplayManager.DisplayListener {
    public static volatile CoverService instance;
    public static volatile String status = "无障碍服务未启用";
    private static final ArrayDeque<String> lifecycleHistory = new ArrayDeque<>();
    final Handler main = new Handler(Looper.getMainLooper());
    final Map<String, Integer> states = new HashMap<>();
    Prefs prefs;
    Context screenContext;
    Display display;
    DockGeometry.Placement placement;
    private WindowManager windows;
    private DockView dock;
    private PanelEntryView panelEntry;
    private DockGeometry.Placement panelEntryPlacement;
    private DockGeometry.Placement dockPlacement;
    private final DockVisibility dockVisibility = new DockVisibility();
    private Boolean keyboardVisible;
    private final Map<Integer, String> windowPackages = new java.util.LinkedHashMap<>();
    private LinearLayout panel;
    private FrameLayout panelHost;
    private DockGeometry.Box panelFrame;
    private StatusBarView statusBar;
    private DockGeometry.Box statusBox;
    private AppHubView hub;
    private FrameLayout hubHost;
    private DockGeometry.Box hubFrame;
    private boolean panelDragging;
    private float panelProgress = 1;
    private boolean panelPulling, panelTargetOpen = true, panelPullCancelOpen;
    private float panelPullStartProgress, panelPullStartDistance;
    private ValueAnimator panelAnimation;
    private ValueAnimator panelTintAnimation;
    private android.graphics.drawable.ColorDrawable panelBackdrop;
    private TextView feedback;
    private Panels panels;
    private ControlEditorView controlEditor;
    private MediaSessions mediaSessions;
    private Boolean mediaAccessReady;
    private DetailSheet details;
    private ControlDetails detailContent;
    private boolean detailBlurApplied;
    private CoverRotation automaticRotation;
    private Consumer<Boolean> blurListener;
    private Consumer<Boolean> hubBlurListener;
    private WindowManager hubBlurWindows;
    private String panelPage = "", signature = "";
    private record BrightnessRequest(Panels target, int displayId, int value, long deadline, long sequence) { }
    private BrightnessRequest pendingBrightness, activeBrightness;
    private long brightnessSequence;
    private final Runnable drainBrightness = this::drainBrightness;
    private int savedPage;
    private boolean screenshotInProgress;
    private boolean systemControlsBusy;
    private SystemControlGuard systemControlGuard;
    private Toast systemControlsToast;
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private CameraManager cameras;
    private String torchCamera;
    Boolean torchOn;
    private final CameraManager.TorchCallback torchCallback = new CameraManager.TorchCallback() {
        @Override public void onTorchModeChanged(String cameraId, boolean enabled) {
            if (cameraId.equals(torchCamera)) { torchOn = enabled; if (panels != null) panels.updateStates(); }
        }
        @Override public void onTorchModeUnavailable(String cameraId) { if (cameraId.equals(torchCamera)) { torchOn = null; if (panels != null) panels.updateStates(); } }
    };
    private int navigationInsetPixels;
    private final Runnable updateDisplay = () -> reconcile(false);
    private final Runnable settledDisplay = () -> reconcile(false);
    private final Runnable updateNotifications = () -> {
        boolean ready = CoverNotifications.ready();
        if (mediaAccessReady == null || mediaAccessReady != ready) { mediaAccessReady = ready; if (mediaSessions != null) mediaSessions.refresh(); }
        if (statusBar != null) statusBar.refreshNotifications();
        if (hub != null && hub.isAttachedToWindow() && hub.expanded()) hub.notificationsChanged();
        StatusBarView panelStatus = panel == null ? null : panel.findViewWithTag("panel-status");
        if (panelStatus != null) panelStatus.refreshNotifications();
        if (panelPage.equals("notifications") && !panelDragging && panels != null) panels.refreshNotifications();
    };
    private final Runnable bridgeChanged = () -> {
        ShizukuBridge bridge = CoverApp.bridge(this);
        handleBridgeChanged(bridge.granted(), bridge.connected());
    };
    private void handleBridgeChanged(boolean granted, boolean connected) {
        if (systemControlGuard != null) systemControlGuard.changed();
        boolean ready = granted && connected;
        if (hub != null) { if (!ready) hub.recentFailure("Shizuku 连接中断"); else requestHubTasks(null); }
        if (!ready) {
            pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
            stopAutomaticRotation();
            states.clear();
            if (panels != null) { panels.updateStates(); panels.brightness(new ShizukuBridge.Result(false, "连接中断", "")); }
        } else if (panelPage.equals("controls")) refreshStates(true);
    }
    private final Runnable updatePreferences = () -> reconcile(true);
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (preferences, key) -> {
        if ("system_controls_disabled".equals(key)) return;
        if ("panel".equals(key) && panel != null && panelPage.equals("controls")) { if (controlEditor == null) { dismissDetails(); populatePanel("controls"); refreshStates(true); } return; }
        if ("hub_workspace".equals(key) || "hub_workspace_compact".equals(key)) { if (hub != null) hub.workspacePreferencesChanged(); return; }
        if ("hub_pins".equals(key) || "hub_pinned".equals(key)) { if (hub != null) hub.dockPreferencesChanged(); return; }
        if (key != null && key.startsWith("hub_workspace_")) { if (hub != null) hub.workspaceOptionsChanged(); return; }
        if (key != null && (key.equals("layout_backup") || key.equals("layout_saved_at") || key.equals("layout_undo") || key.equals("panel_undo") || key.equals("hub_sort") || key.equals("hub_workspace") || key.equals("hub_workspace_compact"))) return;
        main.removeCallbacks(updatePreferences); main.post(updatePreferences);
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (systemControlGuard != null && (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || Intent.ACTION_USER_PRESENT.equals(intent.getAction()))) systemControlGuard.changed();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                stopAutomaticRotation();
                main.removeCallbacks(updateDisplay); main.removeCallbacks(settledDisplay);
                removeWindows(); signature = ""; setStatus("息屏期间已隐藏");
            } else if (android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) refreshBlurState();
            else scheduleDisplay();
        }
    };
    @Override protected void onServiceConnected() {
        instance = this; prefs = new Prefs(this); automaticRotation = new CoverRotation(this); prefs.data.registerOnSharedPreferenceChangeListener(preferenceListener);
        if (systemControlGuard != null) systemControlGuard.close();
        systemControlGuard = new SystemControlGuard(prefs, main, CoverApp.bridge(this), result -> {
            states.put("system_controls", result.ok ? 0 : -1);
            if (panels != null) panels.updateStates();
            if (!result.ok) android.util.Log.w("SystemControlGuard", result.message);
        });
        systemControlGuard.changed();
        getSystemService(DisplayManager.class).registerDisplayListener(this, main);
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT); filter.addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED); else registerReceiver(screenReceiver, filter);
        cameras = getSystemService(CameraManager.class);
        try {
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics info = cameras.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) && Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(info.get(CameraCharacteristics.LENS_FACING))) { torchCamera = id; break; }
            }
            cameras.registerTorchCallback(torchCallback, main);
        } catch (Exception ignored) { }
        CoverApp.bridge(this).addObserver(bridgeChanged); CoverApp.bridge(this).connect(); reconcile(true); scheduleDisplay();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Read window IDs/types and event package only; never request a node tree or its text.
        observeForeground(event);
        if (getSystemService(KeyguardManager.class).isKeyguardLocked() && dock != null) reconcile(false);
        scheduleDisplay();
    }
    private void observeForeground(AccessibilityEvent event) {
        if (prefs == null) return;
        Display target = Displays.selected(this, prefs);
        if (target == null || target.getDisplayId() == Display.DEFAULT_DISPLAY) return;
        try {
            android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            try {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> items = all.get(target.getDisplayId());
                if (items == null) return;
                String active = null, focused = null; boolean inputMethod = false;
                for (android.view.accessibility.AccessibilityWindowInfo window : items) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) inputMethod = true;
                    if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                    if (event != null && window.getId() == event.getWindowId() && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && event.getPackageName() != null) windowPackages.put(window.getId(), event.getPackageName().toString());
                    String name = windowPackages.get(window.getId());
                    if (name != null && window.isActive() && active == null) active = name;
                    if (name != null && window.isFocused() && focused == null) focused = name;
                }
                // Keep a small cache so a temporarily covered app can regain focus without
                // a new state event. IDs and package names are never persisted or exported.
                while (windowPackages.size() > 32) windowPackages.remove(windowPackages.keySet().iterator().next());
                dockVisibility.foreground(active != null ? active : focused);
                keyboardVisible = inputMethod;
                if (display != null && display.getDisplayId() == target.getDisplayId()) syncDockVisibility();
            } finally { for (int i = 0; i < all.size(); i++) for (android.view.accessibility.AccessibilityWindowInfo window : all.valueAt(i)) window.recycle(); }
        } catch (RuntimeException ignored) { /* Keep the last state; a long press always remains available. */ }
    }
    @Override public void onInterrupt() {
        // This interrupts spoken/haptic feedback, not the service or its persistent windows.
    }
    @Override public void onDisplayAdded(int id) { scheduleDisplay(); if (systemControlGuard != null) systemControlGuard.changed(); }
    @Override public void onDisplayRemoved(int id) { scheduleDisplay(); if (systemControlGuard != null) systemControlGuard.changed(); }
    @Override public void onDisplayChanged(int id) {
        scheduleDisplay();
        if (systemControlGuard != null && (id == Display.DEFAULT_DISPLAY || display != null && id == display.getDisplayId())) systemControlGuard.changed();
    }
    @Override public void onConfigurationChanged(Configuration configuration) { super.onConfigurationChanged(configuration); scheduleDisplay(); }
    private void scheduleDisplay() {
        if (instance != this) return;
        // Coalesce a burst without postponing the first check indefinitely. A single later
        // check handles display/keyguard state that settles after the original notification.
        if (!main.hasCallbacks(updateDisplay)) main.postDelayed(updateDisplay, 100);
        main.removeCallbacks(settledDisplay); main.postDelayed(settledDisplay, 1000);
    }
    private void reconcile(boolean force) {
        if (instance != this || prefs == null || screenshotInProgress) return;
        if (automaticRotation != null) automaticRotation.checkActive();
        try {
            Display selected = Displays.selected(this, prefs);
            boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
            if (!prefs.enabled() || selected == null || locked || selected.getState() != Display.STATE_ON) {
                removeWindows(); signature = "";
                setStatus(!prefs.enabled() ? "快捷栏已暂停" : selected == null ? "未检测到外屏，请手动选择" : locked ? "系统报告锁屏，快捷栏已隐藏" : "外屏未亮起，快捷栏已隐藏（状态 " + selected.getState() + "）");
                return;
            }
            Point size = Displays.size(selected);
            if (display == null || display.getDisplayId() != selected.getDisplayId()) { dockVisibility.reset(); windowPackages.clear(); keyboardVisible = null; }
            observeForeground(null);
            Context context = createDisplayContext(selected);
            float density = context.getResources().getDisplayMetrics().density;
            String nextSignature = selected.getDisplayId() + ":" + selected.getRotation() + ":" + size + ":" + density + ":" + selected.getCutout();
            if (!force && nextSignature.equals(signature) && dock != null && dock.isAttachedToWindow() && panelEntry != null && panelEntry.isAttachedToWindow()) { syncDockVisibility(); return; }
            String reopen = panelPage; boolean reopenHub = hub != null && !hub.closing(), expandedHub = reopenHub && hub.expanded(), reopenTasks = reopenHub && hub.showingTasks();
            android.os.Bundle editorState = controlEditor != null && display != null && display.getDisplayId() == selected.getDisplayId() ? controlEditor.snapshot() : null;
            RecentTasks.Task selectedTask = reopenHub ? hub.selectedTask() : null;
            AppHubView.WorkspaceState workspaceState = reopenHub ? hub.workspaceState() : null;
            removeWindows();
            display = selected;
            screenContext = context.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            windows = screenContext.getSystemService(WindowManager.class);
            ArrayList<DockGeometry.Box> cutouts = new ArrayList<>();
            if (selected.getCutout() != null) for (android.graphics.Rect r : selected.getCutout().getBoundingRects()) cutouts.add(new DockGeometry.Box(r.left, r.top, r.width(), r.height()));
            placement = DockGeometry.resolve(size.x, size.y, cutouts, density, prefs.corner(selected.getRotation()), prefs.widthRatio(), prefs.heightRatio(), prefs.data.getBoolean("auto_placement", true));
            DockGeometry.Placement entryAnchor = placement;
            int panelHomeInset = 0;
            android.graphics.Insets widgetSystemInsets = android.graphics.Insets.NONE;
            try {
                android.view.WindowInsets insets = windows.getMaximumWindowMetrics().getWindowInsets();
                panelHomeInset = Math.max(insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()).bottom, insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()).bottom);
                widgetSystemInsets = android.graphics.Insets.max(insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures() | android.view.WindowInsets.Type.displayCutout() | android.view.WindowInsets.Type.statusBars()), insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()));
            } catch (RuntimeException ignored) { }
            navigationInsetPixels = 0;
            if (prefs.avoidNavigation()) {
                try {
                    android.view.WindowInsets insets = windows.getMaximumWindowMetrics().getWindowInsets();
                    android.graphics.Insets safe = android.graphics.Insets.max(insets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures()), insets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars()));
                    navigationInsetPixels = switch (placement.edge()) { case DockGeometry.TOP -> safe.top; case DockGeometry.LEFT -> safe.left; case DockGeometry.RIGHT -> safe.right; default -> safe.bottom; };
                } catch (RuntimeException ignored) { }
                placement = DockGeometry.avoidEdge(placement, size.x, size.y, Math.max(navigationInsetPixels, Ui.dp(screenContext, 16)), Ui.dp(screenContext, prefs.navigationGap()));
            }
            if (!prefs.avoidNavigation()) placement = DockGeometry.edgeTouch(placement, size.x, size.y);
            DockGeometry.Box content = DockGeometry.panelContent(placement, size.x, size.y, cutouts);
            panelEntryPlacement = DockGeometry.panelEntry(entryAnchor, placement, size.x, size.y, cutouts, density, panelHomeInset);
            DockGeometry.Box entryContent = panelEntryPlacement.panel();
            int contentLeft = Math.max(content.x(), entryContent.x()), contentTop = Math.max(content.y(), entryContent.y());
            content = new DockGeometry.Box(contentLeft, contentTop, Math.max(1, Math.min(content.right(), entryContent.right()) - contentLeft), Math.max(1, Math.min(content.bottom(), entryContent.bottom()) - contentTop));
            placement = new DockGeometry.Placement(placement.visual(), placement.touch(), content, placement.edge(), placement.measured());
            panelFrame = new DockGeometry.Box(0, 0, size.x, size.y);
            if (prefs.statusEnabled()) {
                DockGeometry.Box area = placement.panel(); int height = Math.round(Ui.dp(screenContext, 20) * prefs.statusScale() / 100f);
                int margin = Math.max(4, Math.round(3 * density));
                int left = area.x() <= margin ? 0 : area.x(), top = area.y() <= margin ? 0 : area.y(), right = area.right() >= size.x - margin ? size.x : area.right();
                statusBox = new DockGeometry.Box(left, top, right - left, height + area.y() - top);
                placement = new DockGeometry.Placement(placement.visual(), placement.touch(), new DockGeometry.Box(area.x(), area.y() + height + Ui.dp(screenContext, 4), area.width(), Math.max(1, area.height() - height - Ui.dp(screenContext, 4))), placement.edge(), placement.measured());
            }
            hubFrame = DockGeometry.hubContent(placement, size.x, size.y, cutouts, panelHomeInset);
            ensurePanelHost(); addDock();
            if (prefs.statusEnabled()) { statusBar = new StatusBarView(screenContext, prefs); windows.addView(statusBar, statusParameters()); }
            panelEntry = new PanelEntryView(screenContext, prefs, panelEntryPlacement, chromeListener());
            windows.addView(panelEntry, panelEntryParameters());
            DockGeometry.Box widgetArea = placement.panel();
            int widgetLeft = Math.max(widgetArea.x(), widgetSystemInsets.left), widgetTop = Math.max(widgetArea.y(), widgetSystemInsets.top);
            int widgetRight = Math.min(widgetArea.right(), size.x - widgetSystemInsets.right), widgetBottom = Math.min(widgetArea.bottom(), size.y - widgetSystemInsets.bottom);
            CoverApp.widgets(this).safeArea(selected, size.x, size.y, new DockGeometry.Box(widgetLeft, widgetTop, Math.max(0, widgetRight - widgetLeft), Math.max(0, widgetBottom - widgetTop)));
            signature = nextSignature;
            setStatus("快捷栏运行中 · 屏幕 " + selected.getDisplayId() + (placement.measured() ? " · 按缺口定位" : " · 位置需校准"));
            if (!reopen.isEmpty()) { showPanel(reopen); if (editorState != null) editControls(editorState); } else if (reopenHub) { showHub(expandedHub); if (hub != null) { hub.restoreWorkspaceState(workspaceState); if (reopenTasks) { hub.showTasks(true); hub.selectTask(selectedTask); } } }
        } catch (RuntimeException e) { removeWindows(); signature = ""; setStatus("外屏挂载失败：" + e.getClass().getSimpleName()); }
    }
    private void addDock() {
        boolean compact = dockCompact();
        dockPlacement = compact ? DockGeometry.handlesOnly(placement, screenContext.getResources().getDisplayMetrics().density) : placement;
        dock = new DockView(screenContext, prefs, dockPlacement, savedPage, chromeListener(), compact);
        if (compact) dock.setVisibility(View.GONE);
        windows.addView(dock, dockParameters());
    }
    private DockView.Listener chromeListener() {
        return new DockView.Listener() {
                @Override public void action(String id) { act(id); }
                @Override public void configure() { openSettings("dock"); }
                @Override public void beginPull(String page, float distance) { beginPanelPull(page, distance); }
                @Override public void pull(String page, float distance) { pullPanel(page, distance); }
                @Override public void release(String page, float distance, float velocity, boolean canceled) { releasePanel(distance, velocity, canceled); }
                @Override public void toggleVisibility() { dockVisibility.toggle(prefs.autoHideDock(), prefs.compactApps()); syncDockVisibility(); }
            };
    }
    private WindowManager.LayoutParams dockParameters() {
        WindowManager.LayoutParams layout = parameters(dockPlacement.touch());
        if (dock.compact()) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return layout;
    }
    private WindowManager.LayoutParams panelEntryParameters() {
        WindowManager.LayoutParams layout = parameters(panelEntryPlacement.touch()); layout.setTitle("外屏双白条入口");
        if (controlEditor != null || hub != null) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return layout;
    }
    private void syncHubEntry() {
        if (panelEntry == null || windows == null) return;
        panelEntry.suspended(hub != null);
        try { windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { }
    }
    private void syncDockVisibility() {
        if (dock == null || windows == null || panelDragging || dock.compact() == dockCompact()) return;
        try { savedPage = dock.page(); windows.removeViewImmediate(dock); dock = null; addDock(); }
        catch (RuntimeException e) { removeWindows(); signature = ""; scheduleDisplay(); }
    }
    private boolean dockCompact() { dockVisibility.keyboard(prefs.avoidKeyboard() && Boolean.TRUE.equals(keyboardVisible)); return dockVisibility.compact(prefs.autoHideDock(), prefs.compactApps()); }
    private WindowManager.LayoutParams parameters(DockGeometry.Box box) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(box.width(), box.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
        // These are physical display coordinates; locale direction must not mirror the camera cutout.
        params.gravity = Gravity.TOP | Gravity.LEFT; params.x = box.x(); params.y = box.y();
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0); params.setTitle("外屏快捷栏"); return params;
    }
    void showPanel(String page) { showPanel(page, false); }
    private void showPanel(String page, boolean dragging) {
        if (windows == null || dock == null) return;
        dismissHub(); closePanel(); panelPage = page; panelDragging = dragging; panelTargetOpen = !dragging; panelProgress = dragging ? 0 : 1;
        buildPanelContent(page, panelFrame, page.equals("controls") && statusBox != null ? statusBox.y() : placement.panel().y());
        if (page.equals("controls") && statusBar != null) statusBar.setVisibility(View.INVISIBLE);
        try {
            WindowManager.LayoutParams layout = parameters(panelFrame);
            ensurePanelHost(); panelHost.addView(panel, new FrameLayout.LayoutParams(-1, -1)); panelHost.setVisibility(View.VISIBLE);
            if (dragging) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            applyPanelBlur(layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
            windows.updateViewLayout(panelHost, layout); setPanelProgress(panelProgress);
            if (panelEntry != null) panelEntry.panelVisible(true);
            // The persistent panel host was added below the chrome. Keep the entry's
            // existing surface attached so showing/settling a panel cannot blink it.
            if (Build.VERSION.SDK_INT >= 31 && prefs.panelBlur()) {
                LinearLayout observed = panel; WindowManager observedWindows = windows;
                blurListener = enabled -> {
                    if (panel != observed || windows != observedWindows || panelHost == null || !(panelHost.getLayoutParams() instanceof WindowManager.LayoutParams current)) return;
                    applyPanelBlur(current, enabled);
                    try { windows.updateViewLayout(panelHost, current); } catch (RuntimeException ignored) { }
                };
                windows.addCrossWindowBlurEnabledListener(getMainExecutor(), blurListener);
            }
        }
        catch (RuntimeException e) { closePanel(); message("面板挂载失败：" + e.getClass().getSimpleName()); return; }
        if (page.equals("controls")) refreshStates(true);
    }
    /** The same content surface is used by the overlay and by display-sized component checks. */
    LinearLayout buildPanelContent(String page, DockGeometry.Box frame, int contentTop) {
        panelFrame = frame;
        panelPage = page;
        panel = new PanelSurface(screenContext, panelEdge(), panelExtent(), prefs.haptics(), new PanelHeaderView.Listener() {
            @Override public void begin() { cancelPanelAnimation(); panelPulling = false; panelDragging = true; }
            @Override public float currentProgress() { return panelProgress; }
            @Override public boolean cancelCloses() { return !panelTargetOpen; }
            @Override public void progress(float value) { setPanelProgress(value); }
            @Override public void finish(boolean close) { settlePanel(!close); }
        }); int padding = Ui.dp(screenContext, 6); DockGeometry.Box content = placement.panel();
        panel.setPadding(content.x() + padding, contentTop + padding, panelFrame.width() - content.right() + padding, panelFrame.height() - content.bottom());
        panel.setBackgroundColor(Ui.BACKGROUND);
        populatePanel(page); return panel;
    }
    private void populatePanel(String page) {
        panel.removeAllViews();
        if (page.equals("controls")) { StatusBarView batteryRow = new StatusBarView(screenContext, prefs, true); batteryRow.setTag("panel-status"); panel.addView(batteryRow, new LinearLayout.LayoutParams(-1, batteryRow.heightPixels())); }
        panels = new Panels(this); View contents = panels.build(page);
        LinearLayout header = Ui.row(screenContext); header.setTag("panel-header");
        String title = switch (page) { case "rotation" -> "旋转方向"; case "notifications" -> "通知中心"; case "media" -> "媒体控制"; default -> "控制中心"; };
        TextView heading = Ui.heading(screenContext, title, 17); heading.setMinimumHeight(Ui.dp(screenContext, 36)); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setAccessibilityHeading(true);
        if (page.equals("notifications")) {
            heading.setSingleLine(); heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout titleArea = new FrameLayout(screenContext); titleArea.addView(heading, new FrameLayout.LayoutParams(-1, -1));
            TextView notice = (TextView) panels.notificationNotice(); notice.setSingleLine(); notice.setEllipsize(android.text.TextUtils.TruncateAt.END); titleArea.addView(notice, new FrameLayout.LayoutParams(-1, -1));
            panels.bindNotificationTitle(heading);
            header.addView(titleArea, new LinearLayout.LayoutParams(0, Ui.dp(screenContext, 36), 1));
            View settings = Ui.iconButton(screenContext, R.drawable.ic_ms_settings, "通知中心设置", this::openNotificationCenterSettings); settings.setTag("notification-center-settings"); settings.setBackground(Ui.ripple(screenContext, Ui.SURFACE, 18)); settings.setTooltipText("系统通知设置");
            LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(Ui.dp(screenContext, 36), Ui.dp(screenContext, 36)); settingsSize.setMarginEnd(Ui.dp(screenContext, 6)); header.addView(settings, settingsSize);
            LinearLayout.LayoutParams clearSize = new LinearLayout.LayoutParams(-2, Ui.dp(screenContext, 36)); clearSize.setMarginEnd(Ui.dp(screenContext, 6)); header.addView(panels.notificationClearAction(), clearSize);
        } else header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (page.equals("controls")) { View edit = RuntimeVisuals.button(screenContext, R.drawable.ic_ms_edit, "编辑控制中心", this::editControls); edit.setTag("control-edit"); header.addView(edit); }
        if (page.equals("controls")) { View add = RuntimeVisuals.button(screenContext, R.drawable.ic_ms_add, "添加控制按钮", () -> { editControls(); if (controlEditor != null) controlEditor.showPicker(); }); add.setTag("control-add"); header.addView(add); }
        if (page.equals("controls")) {
            android.widget.ImageButton refresh = RuntimeVisuals.button(screenContext, R.drawable.ic_ms_refresh, "刷新状态", () -> { }); refresh.setTag("control-refresh");
            refresh.setOnClickListener(v -> { refresh.setEnabled(false); refresh.setActivated(true); refresh.setStateDescription("正在刷新"); refreshStates(true, () -> { refresh.setEnabled(true); refresh.setActivated(false); refresh.setStateDescription("刷新状态"); }); }); header.addView(refresh);
        }
        header.addView(RuntimeVisuals.button(screenContext, R.drawable.ic_ms_close, "关闭面板", this::closePanel)); LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2); headerParams.bottomMargin = Ui.dp(screenContext, 3); panel.addView(header, headerParams);
        ScrollView scroll = new ScrollView(screenContext); scroll.setFillViewport(page.equals("controls")); scroll.setVerticalScrollBarEnabled(!page.equals("notifications")); scroll.addView(contents);
        panel.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        feedback = Ui.text(screenContext, "", 11, Ui.MUTED); feedback.setVisibility(View.GONE); feedback.setMaxLines(2); panel.addView(feedback);
    }
    void editControls() { editControls(null); }
    private void editControls(android.os.Bundle restored) {
        if (panel == null || !panelPage.equals("controls") || controlEditor != null) return;
        dismissDetails(); pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
        panel.removeAllViews(); panels = null; feedback = null;
        controlEditor = new ControlEditorView(screenContext, prefs, restored, id -> CoverApp.bridge(this).run("tile_add", -1, 0, ActionCatalog.component(id).flattenToString(), result -> message(result.message)), () -> {
            if (panel == null || controlEditor == null) return;
            screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(controlEditor.getWindowToken(), 0);
            controlEditor = null; populatePanel("controls"); panelEditorFocus(false); if (instance == this) refreshStates(true);
        });
        panel.addView(controlEditor, new LinearLayout.LayoutParams(-1, 0, 1)); panelEditorFocus(true); controlEditor.requestFocus();
    }
    private void panelEditorFocus(boolean editing) {
        if (windows == null || panelHost == null || !(panelHost.getLayoutParams() instanceof WindowManager.LayoutParams layout)) return;
        if (editing) layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; else layout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layout.softInputMode = (editing ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        try { windows.updateViewLayout(panelHost, layout); if (panelEntry != null) windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { closePanel(); }
    }
    private void ensurePanelHost() {
        if (panelHost != null) return;
        panelHost = new FrameLayout(screenContext); panelHost.setClipChildren(true); panelHost.setVisibility(View.INVISIBLE);
        WindowManager.LayoutParams layout = parameters(panelFrame); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        windows.addView(panelHost, layout);
    }
    void closePanel() {
        if (controlEditor != null) { screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(controlEditor.getWindowToken(), 0); controlEditor.cancelDrag(); controlEditor = null; }
        if (panelTintAnimation != null) panelTintAnimation.cancel(); panelTintAnimation = null; panelBackdrop = null;
        pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
        dismissDetails();
        cancelPanelAnimation(); panelDragging = false; panelPulling = false; panelTargetOpen = false;
        if (statusBar != null) statusBar.setVisibility(View.VISIBLE);
        if (Build.VERSION.SDK_INT >= 31 && blurListener != null && windows != null) windows.removeCrossWindowBlurEnabledListener(blurListener);
        blurListener = null;
        if (panelHost != null && windows != null) { panelHost.setVisibility(View.INVISIBLE); panelHost.removeAllViews(); try { WindowManager.LayoutParams layout = (WindowManager.LayoutParams) panelHost.getLayoutParams(); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0; if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0); windows.updateViewLayout(panelHost, layout); } catch (RuntimeException ignored) { } }
        panel = null; feedback = null; panels = null; panelPage = ""; panelProgress = 1;
        if (panelEntry != null) panelEntry.panelVisible(false);
        if (panelEntry != null && windows != null) { try { windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { } }
    }
    private void applyPanelBlur(WindowManager.LayoutParams layout, boolean supported) {
        boolean enabled = visualEffectsAllowed() && supported && !panelDragging;
        int tint = enabled ? 0xC2000000 : Ui.BACKGROUND;
        if (panelBackdrop == null) { panelBackdrop = new android.graphics.drawable.ColorDrawable(tint); panel.setBackground(panelBackdrop); }
        else if (panelBackdrop.getColor() != tint) {
            if (panelTintAnimation != null) panelTintAnimation.cancel();
            // Only local paint is interpolated; no per-frame window blur reconfiguration.
            if (!enabled || !ValueAnimator.areAnimatorsEnabled()) panelBackdrop.setColor(tint);
            else { int start = panelBackdrop.getColor(); android.graphics.drawable.ColorDrawable target = panelBackdrop; panelTintAnimation = ValueAnimator.ofFloat(0, 1); panelTintAnimation.setDuration(180); panelTintAnimation.addUpdateListener(frame -> target.setColor(RuntimeVisuals.blend(start, tint, (float) frame.getAnimatedValue()))); panelTintAnimation.start(); }
        }
        if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(enabled ? Math.min(100, Ui.dp(screenContext, 32)) : 0);
        if (enabled) { layout.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = .18f; }
        else { layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0; }
        if (details != null) setDetailBlur(true);
    }
    private boolean visualEffectsAllowed() { return Build.VERSION.SDK_INT >= 31 && prefs.panelBlur() && !screenContext.getSystemService(android.os.PowerManager.class).isPowerSaveMode(); }
    private void refreshBlurState() {
        if (screenContext == null || windows == null) return;
        boolean supported = Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled();
        if (panel != null && panelHost != null && panelHost.getLayoutParams() instanceof WindowManager.LayoutParams layout) {
            applyPanelBlur(layout, supported);
            try { windows.updateViewLayout(panelHost, layout); } catch (RuntimeException ignored) { }
        }
        if (hub != null && hubHost != null && hubHost.getLayoutParams() instanceof WindowManager.LayoutParams layout) {
            applyHubBlur(hub, layout, supported);
            try { windows.updateViewLayout(hubHost, layout); } catch (RuntimeException ignored) { }
        }
    }
    String blurDiagnostics() {
        String accelerated = panelHost == null || !panelHost.isAttachedToWindow() ? "未挂载" : String.valueOf(panelHost.isHardwareAccelerated());
        return "外屏面板宿主硬件加速：" + accelerated + "\n详情本地模糊当前启用：" + detailBlurApplied + "\n";
    }
    void showDetails(String id, View source) {
        if (panel == null || !(panel.getParent() instanceof FrameLayout host)) return;
        dismissDetails();
        DetailSheet sheet = new DetailSheet(screenContext, ActionCatalog.label(screenContext, id), this::dismissDetails); details = sheet;
        DockGeometry.Box safe = placement.panel(); FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(safe.width(), safe.height()); position.leftMargin = safe.x(); position.topMargin = safe.y();
        host.addView(sheet, position); panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setDetailBlur(true);
        if (windows != null && panelHost != null) { WindowManager.LayoutParams params = (WindowManager.LayoutParams) panelHost.getLayoutParams(); params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(panelHost, params); }
        detailContent = new ControlDetails(this, sheet); detailContent.build(id); sheet.enter(source);
        if (prefs.haptics() && source != null) source.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }
    private void setDetailBlur(boolean enabled) {
        boolean active = enabled && panel != null && panel.isHardwareAccelerated() && visualEffectsAllowed();
        if (detailBlurApplied == active) return;
        detailBlurApplied = active;
        if (panel != null && Build.VERSION.SDK_INT >= 31) panel.setRenderEffect(active ? android.graphics.RenderEffect.createBlurEffect(Ui.dp(screenContext, 5), Ui.dp(screenContext, 5), android.graphics.Shader.TileMode.CLAMP) : null);
    }
    void dismissDetails() {
        if (detailContent != null) { detailContent.close(); detailContent = null; }
        if (details == null) return;
        if (details.getParent() instanceof android.view.ViewGroup host) host.removeView(details); details = null;
        setDetailBlur(false); if (panel != null) panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (windows != null && panelHost != null) try { WindowManager.LayoutParams params = (WindowManager.LayoutParams) panelHost.getLayoutParams(); params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(panelHost, params); } catch (RuntimeException ignored) { }
    }
    boolean rotationAutomatic() { return automaticRotation != null && automaticRotation.enabled(); }
    void stopAutomaticRotation() { if (automaticRotation != null) automaticRotation.stop(); if (panels != null) panels.updateStates(); }
    void lockRotation(int angle) {
        int target = display == null ? -1 : display.getDisplayId(); stopAutomaticRotation();
        Runnable lock = () -> { Display selected = Displays.selected(this, prefs); if (selected == null || selected.getDisplayId() != target || selected.getState() != Display.STATE_ON || getSystemService(KeyguardManager.class).isKeyguardLocked()) return; shell("rotation", angle, "", null); };
        if (automaticRotation != null) automaticRotation.afterCurrent(lock); else lock.run();
    }
    void toggleRotation() {
        if (rotationAutomatic()) lockRotation(display.getRotation());
        else if (automaticRotation == null || !automaticRotation.start()) message("自动旋转需要外屏、方向传感器与 Shizuku");
        else message("自动旋转已开启，以当前握持方向为基准");
        if (panels != null) panels.updateStates();
    }
    private WindowManager.LayoutParams statusParameters() {
        WindowManager.LayoutParams layout = parameters(statusBox); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; layout.setTitle("外屏状态栏"); return layout;
    }
    private void raiseChrome() {
        if (dock != null) { windows.removeViewImmediate(dock); windows.addView(dock, dockParameters()); }
        if (statusBar != null) { windows.removeViewImmediate(statusBar); windows.addView(statusBar, statusParameters()); }
        if (panelEntry != null) { windows.removeViewImmediate(panelEntry); windows.addView(panelEntry, panelEntryParameters()); }
    }
    private int panelEdge() { return DockGeometry.BOTTOM; }
    private float panelExtent() { return Math.max(1, panelFrame.height()); }
    private void setPanelProgress(float value) {
        panelProgress = Math.max(0, Math.min(1, value)); if (panel == null) return;
        float offset = (1 - panelProgress) * panelExtent();
        panel.setTranslationX(0); panel.setTranslationY(offset);
    }
    private void beginPanelPull(String page, float distance) {
        if (controlEditor != null) return;
        // An already open page is idempotent: no rebuild, loss of scroll, or jump to zero.
        if (panel != null && page.equals(panelPage) && !panelDragging && panelProgress >= 1) return;
        boolean continuing = panel != null && page.equals(panelPage);
        if (!continuing) showPanel(page, true);
        if (panel == null || !page.equals(panelPage)) return;
        panelPullCancelOpen = panelTargetOpen; cancelPanelAnimation(); panelDragging = true; panelPulling = true;
        panelPullStartProgress = panelProgress; panelPullStartDistance = continuing ? distance : 0;
    }
    private void pullPanel(String page, float distance) {
        if (!panelPulling || !page.equals(panelPage)) beginPanelPull(page, distance);
        if (panelPulling && page.equals(panelPage)) setPanelProgress(PanelDrag.progress(panelPullStartProgress, distance - panelPullStartDistance, panelExtent()));
    }
    private void releasePanel(float distance, float velocity, boolean canceled) {
        if (!panelPulling || panel == null) return;
        panelPulling = false;
        if (!canceled) setPanelProgress(PanelDrag.progress(panelPullStartProgress, distance - panelPullStartDistance, panelExtent()));
        settlePanel(canceled ? panelPullCancelOpen : PanelDrag.shouldOpen(panelProgress * panelExtent(), panelExtent(), velocity, screenContext.getResources().getDisplayMetrics().density));
    }
    private void cancelPanelAnimation() { if (panelAnimation != null) { panelAnimation.removeAllListeners(); panelAnimation.cancel(); panelAnimation = null; } }
    private void settlePanel(boolean open) {
        cancelPanelAnimation(); panelTargetOpen = open;
        Runnable settled = () -> {
            panelAnimation = null; panelDragging = false;
            if (!open) { closePanel(); return; }
            if (panelHost == null) return;
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) panelHost.getLayoutParams(); layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            applyPanelBlur(layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
            try { windows.updateViewLayout(panelHost, layout); if (panelPage.equals("notifications")) main.post(updateNotifications); } catch (RuntimeException ignored) { closePanel(); }
        };
        if (!ValueAnimator.areAnimatorsEnabled()) { setPanelProgress(open ? 1 : 0); settled.run(); return; }
        panelAnimation = ValueAnimator.ofFloat(panelProgress, open ? 1 : 0); panelAnimation.setDuration(180);
        panelAnimation.setInterpolator(new android.view.animation.DecelerateInterpolator());
        panelAnimation.addUpdateListener(animation -> setPanelProgress((float) animation.getAnimatedValue()));
        panelAnimation.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animator) { settled.run(); } }); panelAnimation.start();
    }
    private void showHub() {
        showHub(true);
    }
    private WindowManager.LayoutParams hubParameters(boolean expanded) {
        // Keep one safe-area window: the transparent region dismisses a temporary Dock,
        // and expanding the catalog does not move or replace its application row.
        WindowManager.LayoutParams layout = parameters(hubFrame);
        layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layout.softInputMode = (expanded ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        applyHubBlur(hub, layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
        return layout;
    }
    void applyHubBlur(AppHubView target, WindowManager.LayoutParams layout, boolean supported) {
        boolean expanded = target.expanded(), enabled = expanded && visualEffectsAllowed() && supported;
        target.setBackdropBlur(enabled);
        if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(enabled ? Math.min(100, Ui.dp(screenContext, 32)) : 0);
        if (enabled) layout.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND; else layout.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (expanded) { layout.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = enabled ? .18f : .32f; }
        else { layout.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = 0; }
    }
    private void showHub(boolean expandedInitially) {
        if (windows == null || dock == null) return;
        closePanel(); removeHubImmediately();
        hub = new AppHubView(screenContext, prefs, new AppHubView.Listener() {
            @Override public void action(String id) { if (!id.startsWith("app:") && !java.util.Set.of("app_dock", "app_hub", "recents", "back").contains(id)) dismissHub(); act(id); }
            @Override public void editFavorites() { openSettings("favorites"); }
            @Override public void editPinned() { openSettings("hub_pin"); }
            @Override public void settings() { openSettings("main"); }
            @Override public void applicationSettings(String id, boolean uninstall) {
                android.content.ComponentName component = ActionCatalog.component(id); if (component == null) return;
                Intent intent = new Intent(uninstall ? Intent.ACTION_DELETE : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", component.getPackageName(), null)); launch(intent);
            }
            @Override public void close() { dismissHub(); }
            @Override public void refreshRecents() { requestHubTasks(null); }
            @Override public void clearRecents(java.util.List<RecentTasks.Task> tasks) { requestHubTasks(tasks); }
            @Override public void openTask(RecentTasks.Task task) { openHubTask(task); }
            @Override public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { requestTaskSnapshot(task, callback); }
            @Override public void expand(boolean expanded) {
                if (hub == null || hubHost == null || !hubHost.isAttachedToWindow()) return;
                WindowManager.LayoutParams layout = hubParameters(expanded);
                if (!expanded) screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(hub.getWindowToken(), 0);
                try { windows.updateViewLayout(hubHost, layout); } catch (RuntimeException e) { removeHubImmediately(); }
            }
        });
        // Let the fixed dock key receive its complete click before toggling the hub.
        // ACTION_OUTSIDE on DOWN would close it early, then UP would reopen it.
        hub.dockEdge(placement.edge()); hub.setExpanded(expandedInitially); hub.prepareEntrance();
        attachHubContent(hub);
        try {
            windows.addView(hubHost, hubParameters(expandedInitially)); syncHubEntry(); raiseChrome(); hub.enter();
            if (Build.VERSION.SDK_INT >= 31 && prefs.panelBlur()) {
                AppHubView observed = hub; FrameLayout observedHost = hubHost; WindowManager observedWindows = windows; hubBlurWindows = observedWindows;
                hubBlurListener = enabled -> {
                    if (hub != observed || hubHost != observedHost || windows != observedWindows || !(observedHost.getLayoutParams() instanceof WindowManager.LayoutParams layout)) return;
                    applyHubBlur(observed, layout, enabled);
                    try { observedWindows.updateViewLayout(observedHost, layout); } catch (RuntimeException ignored) { }
                };
                observedWindows.addCrossWindowBlurEnabledListener(getMainExecutor(), hubBlurListener);
            }
        } catch (RuntimeException e) { removeHubImmediately(); message("应用中心暂不可用"); }
    }
    FrameLayout attachHubContent(AppHubView content) {
        // Match the panel host structure: move/fade a child while the window's
        // physical bounds and input coordinate space stay fixed until completion.
        hubHost = new FrameLayout(screenContext); hubHost.addView(content, new FrameLayout.LayoutParams(-1, -1)); return hubHost;
    }
    private void dismissHub() {
        if (hub == null) return; AppHubView target = hub;
        target.dismiss(() -> { if (hub == target) removeHubImmediately(); });
    }
    private void removeHubImmediately() {
        if (Build.VERSION.SDK_INT >= 31 && hubBlurListener != null && hubBlurWindows != null) try { hubBlurWindows.removeCrossWindowBlurEnabledListener(hubBlurListener); } catch (RuntimeException ignored) { }
        hubBlurListener = null; hubBlurWindows = null;
        AppHubView removed = hub; FrameLayout host = hubHost; hub = null; hubHost = null;
        if (removed != null) { screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(removed.getWindowToken(), 0); removed.dispose(); }
        if (host != null) { try { windows.removeViewImmediate(host); } catch (RuntimeException ignored) { } host.removeAllViews(); }
        syncHubEntry();
    }
    private void requestHubTasks(java.util.List<RecentTasks.Task> clearing) {
        AppHubView target = hub; Display selected = Displays.selected(this, prefs);
        if (target == null || target.closing() || !target.isAttachedToWindow() || target.recentBusy()) return;
        if (clearing == null && CoverApp.bridge(this).busy()) return;
        if (display == null || selected == null || selected.getDisplayId() != display.getDisplayId() || selected.getDisplayId() <= 0 || selected.getState() != Display.STATE_ON || getSystemService(KeyguardManager.class).isKeyguardLocked()) { target.recentResult(java.util.List.of(), null); target.recentFailure("目标外屏不可用"); return; }
        int displayId = selected.getDisplayId(); String request = "";
        if (clearing != null) {
            clearing = CoverApp.taskLocks(this).unlocked(clearing);
            if (clearing.isEmpty()) { message("没有可清理的后台任务"); return; }
            try { org.json.JSONArray items = new org.json.JSONArray(); for (RecentTasks.Task task : clearing) items.put(SystemRecentTasks.json(task)); request = items.toString(); }
            catch (Exception e) { target.recentFailure("任务数据无效"); return; }
        }
        boolean clearingTasks = clearing != null;
        target.recentBusy(true);
        CoverApp.bridge(this).run(clearingTasks ? "recent_clear" : "recent_tasks", displayId, 0, request, result -> {
            if (hub != target) return;
            Display current = Displays.selected(this, prefs);
            if (current == null || current.getDisplayId() != displayId || current.getState() != Display.STATE_ON || getSystemService(KeyguardManager.class).isKeyguardLocked()) { target.recentResult(java.util.List.of(), null); target.recentFailure("外屏状态已改变"); return; }
            if (!result.ok) { target.recentFailure(result.message); if (clearingTasks) message(result.message); return; }
            try {
                JSONObject payload = new JSONObject(result.output); org.json.JSONArray items = payload.getJSONArray("tasks"); if (items.length() > 32) throw new IllegalArgumentException("Too many tasks");
                java.util.List<RecentTasks.Task> tasks = new ArrayList<>();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i); android.content.ComponentName component = android.content.ComponentName.unflattenFromString(item.getString("component"));
                    if (component == null || item.getInt("displayId") != displayId || item.getInt("userId") != android.os.Process.myUid() / 100000 || item.getInt("id") < 0) throw new IllegalArgumentException("Task identity mismatch");
                    tasks.add(new RecentTasks.Task(item.getInt("id"), displayId, item.getInt("userId"), component.flattenToString(), component.getPackageName(), item.getBoolean("visible")));
                }
                target.recentCapabilities(payload.optBoolean("canOpen"), payload.optBoolean("canClear"), payload.optBoolean("canSnapshot"));
                CoverApp.taskLocks(this).reconcile(displayId, android.os.Process.myUid() / 100000, tasks, payload.optBoolean("complete"));
                target.recentResult(tasks, clearingTasks ? result.message : null);
            } catch (Exception e) { target.recentFailure("系统任务返回格式不兼容"); }
        });
    }
    private boolean currentTaskDisplay(int id) {
        Display selected = Displays.selected(this, prefs);
        return instance == this && prefs.enabled() && id > 0 && display != null && display.getDisplayId() == id && selected != null && selected.getDisplayId() == id && selected.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private void openHubTask(RecentTasks.Task task) {
        AppHubView target = hub;
        if (target == null || target.closing() || !target.isAttachedToWindow() || target.recentBusy()) return;
        if (!currentTaskDisplay(task.displayId())) { target.recentFailure("目标外屏已改变"); return; }
        String request;
        try { request = SystemRecentTasks.json(task).toString(); } catch (Exception error) { target.recentFailure("任务数据无效"); return; }
        target.recentBusy(true);
        CoverApp.bridge(this).run("recent_open", task.displayId(), 0, request, result -> finishHubTask(target, task, result));
    }
    void finishHubTask(AppHubView target, RecentTasks.Task task, ShizukuBridge.Result result) {
        if (!currentTaskDisplay(task.displayId())) { if (hub == target) target.recentFailure("外屏状态已改变"); return; }
        String state = "";
        if (result.ok) try { state = new JSONObject(result.output).getString("state"); } catch (Exception ignored) { }
        if (state.equals("opened")) { dismissHub(); closePanel(); }
        else if (hub == target) {
            String failure = state.equals("gone") ? "原窗口已结束，可重新打开应用" : result.ok ? "已请求恢复，但未确认任务在外屏可见" : result.message;
            if (target.closing()) target.recentFailure(failure); else target.taskOpenFailure(task, state.equals("gone"), failure);
        }
    }
    private void requestTaskSnapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) {
        AppHubView target = hub;
        if (target == null || !target.showingTasks() || !currentTaskDisplay(task.displayId())) { callback.accept(new ShizukuBridge.Snapshot(null, "外屏状态已改变")); return; }
        CoverApp.bridge(this).snapshot(task.displayId(), task, result -> {
            if (hub != target || !target.showingTasks() || !currentTaskDisplay(task.displayId())) {
                if (result.bitmap() != null) result.bitmap().recycle(); callback.accept(new ShizukuBridge.Snapshot(null, "外屏状态已改变")); return;
            }
            callback.accept(result);
        });
    }
    private void removeWindows() {
        CoverApp.widgets(this).safeArea(null, 0, 0, null);
        if (systemControlsToast != null) { systemControlsToast.cancel(); systemControlsToast = null; }
        removeHubImmediately(); closePanel();
        if (panelHost != null) { try { windows.removeViewImmediate(panelHost); } catch (RuntimeException ignored) { } panelHost = null; }
        if (statusBar != null) { try { windows.removeViewImmediate(statusBar); } catch (RuntimeException ignored) { } statusBar = null; }
        if (panelEntry != null) { PanelEntryView removed = panelEntry; panelEntry = null; try { windows.removeViewImmediate(removed); } catch (RuntimeException ignored) { } }
        statusBox = null;
        DockView removed = dock; dock = null;
        if (removed != null) { savedPage = removed.page(); try { windows.removeViewImmediate(removed); } catch (RuntimeException ignored) { } }
        panelEntryPlacement = null; hubFrame = null;
    }
    String windowDiagnostics() {
        String entry = panelEntry != null && panelEntry.isAttachedToWindow() && panelEntryPlacement != null
            ? (panelEntryPlacement.vertical() ? "上条通知／下条控制中心" : "左条通知／右条控制中心") + " · " + (panelEntryPlacement.measured() ? "系统缺口" : "校准估计，需本机确认") + " · " + panelEntryPlacement.touch() : "未挂载";
        return "系统导航边缘：" + navigationInsetPixels + "px\n导航避让：" + prefs.avoidNavigation() + "，额外内移 " + prefs.navigationGap() + "dp\n" + "快捷栏窗口：" + (dock == null ? "未创建" : dock.isAttachedToWindow() ? "已挂载" : "已脱离，等待恢复") + "\n双白条入口：" + entry + "\n显示模式：" + (dock != null && dock.compact() ? "快捷按钮已隐藏" : "快捷按钮") + "\n外屏键盘：" + (keyboardVisible == null ? "未知" : keyboardVisible ? "已检测到" : "未检测到") + "\n";
    }
    private static void setStatus(String value) {
        if (value.equals(status)) return;
        status = value;
        if (lifecycleHistory.size() == 10) lifecycleHistory.removeFirst();
        lifecycleHistory.addLast(android.os.SystemClock.elapsedRealtime() / 1000 + "s · " + value);
    }
    static String lifecycleDiagnostics() { return "最近显示状态（本次进程，开机后秒数）：\n" + String.join("\n", lifecycleHistory) + "\n"; }
    void message(String text) {
        if (feedback != null && panelPage.equals("controls")) { feedback.announceForAccessibility(text); Toast.makeText(screenContext, text, Toast.LENGTH_SHORT).show(); }
        else if (feedback != null) { feedback.setVisibility(View.VISIBLE); feedback.setText(text); feedback.announceForAccessibility(text); }
        else Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
    void shell(String operation, int value, String component, ShizukuBridge.Callback after) {
        String action = operation.equals("tile_click") ? "tile:" + component : operation; Panels target = panels;
        if (target != null && target.working(action)) return;
        if (target != null) target.working(action, true);
        message("正在执行…");
        CoverApp.bridge(this).run(operation, display == null ? -1 : display.getDisplayId(), value, component, result -> {
            if (instance != this) return;
            if (target != null) target.working(action, false);
            message(result.message); if (after != null) after.accept(result);
        });
    }
    void refreshStates(boolean readBrightness) { refreshStates(readBrightness, () -> { }); }
    private void refreshStates(boolean readBrightness, Runnable finished) {
        CoverApp.bridge(this).run("states", -1, 0, "", result -> {
            if (instance != this) return;
            if (result.ok) try {
                JSONObject values = new JSONObject(result.output);
                for (String key : new String[]{"wifi", "bluetooth", "data", "dnd", "airplane", "system_controls"}) states.put(key, values.optInt(key, -1));
            } catch (Exception ignored) { states.clear(); }
            if (!result.ok) states.clear();
            if (panels != null) panels.updateStates();
            finished.run();
            if (readBrightness && panelPage.equals("controls") && panels != null && panels.hasBrightness() && display != null) {
                requestBrightness(panels, -1);
            }
        });
    }
    void setBrightness(Panels target, int value) {
        if (value < 5 || value > 100) return;
        requestBrightness(target, value);
    }
    private boolean brightnessCurrent(BrightnessRequest request) {
        return panels == request.target() && panelPage.equals("controls") && request.target().hasBrightness() && currentTaskDisplay(request.displayId());
    }
    private void requestBrightness(Panels target, int value) {
        if (display == null) return;
        // Refreshes must never replace a user value waiting for the shared Shizuku service.
        if (value < 0 && (pendingBrightness != null || (activeBrightness != null && activeBrightness.target() == target && activeBrightness.sequence() == brightnessSequence))) return;
        BrightnessRequest request = new BrightnessRequest(target, display.getDisplayId(), value, android.os.SystemClock.uptimeMillis() + 2500, brightnessSequence + 1);
        if (!brightnessCurrent(request)) return;
        brightnessSequence = request.sequence();
        pendingBrightness = request;
        if (value >= 0) { target.brightnessWorking(true); message("正在调节外屏亮度…"); }
        main.removeCallbacks(drainBrightness); drainBrightness();
    }
    private void drainBrightness() {
        BrightnessRequest request = pendingBrightness;
        if (request == null) return;
        if (!brightnessCurrent(request)) { pendingBrightness = null; return; }
        ShizukuBridge bridge = CoverApp.bridge(this);
        if (activeBrightness != null || bridge.busy()) {
            if (android.os.SystemClock.uptimeMillis() < request.deadline()) { main.postDelayed(drainBrightness, 80); return; }
            pendingBrightness = null;
            ShizukuBridge.Result timeout = new ShizukuBridge.Result(false, "亮度操作等待超时，请重试", "", true);
            if (request.value() >= 0) { request.target().brightnessWorking(false); request.target().brightnessWritten(timeout); }
            message(timeout.message); return;
        }
        pendingBrightness = null; activeBrightness = request;
        bridge.run(request.value() < 0 ? "brightness_read" : "brightness", request.displayId(), Math.max(0, request.value()), "", result -> {
            if (activeBrightness == request) activeBrightness = null;
            if (brightnessCurrent(request) && request.sequence() == brightnessSequence) {
                if (request.value() < 0) request.target().brightness(result); else { request.target().brightnessWorking(false); request.target().brightnessWritten(result); }
                if (request.value() >= 0 || !result.ok) message(result.message);
            }
            main.removeCallbacks(drainBrightness); drainBrightness();
        });
    }
    Boolean on(String id) {
        if (id.equals("torch")) return torchOn;
        int value = states.getOrDefault(id, -1);
        if (value < 0) return null;
        if (id.equals("dnd")) return value > 0;
        return value <= 1 ? value == 1 : null;
    }
    void act(String id) {
        switch (id) {
            case "app_dock" -> { if (hub == null) showHub(false); else if (hub.closing()) { closePanel(); hub.reopen(); hub.setExpanded(false); } else if (hub.expanded()) hub.setExpanded(false); else dismissHub(); }
            case "app_hub" -> { if (hub == null) showHub(); else if (hub.closing()) { closePanel(); hub.reopen(); hub.showTasks(false); hub.setExpanded(true); } else if (hub.showingTasks()) hub.showTasks(false); else if (!hub.expanded()) hub.setExpanded(true); else dismissHub(); }
            case "controls", "rotation", "media" -> { if (panelPage.equals(id)) closePanel(); else showPanel(id); }
            case "notifications" -> global(GLOBAL_ACTION_NOTIFICATIONS);
            case "notification_list" -> { if (panelPage.equals("notifications")) closePanel(); else showPanel("notifications"); }
            case "configure" -> openSettings("dock");
            case "apps" -> openSettings("apps");
            case "home" -> global(GLOBAL_ACTION_HOME);
            case "back" -> { if (controlEditor != null) controlEditor.back(); else if (details != null) details.close(); else if (hub != null && !hub.closing()) hub.back(); else if (panel != null) closePanel(); else if (hub == null) global(GLOBAL_ACTION_BACK); }
            case "recents" -> { if (hub != null && hub.showingTasks() && !hub.closing()) dismissHub(); else { if (hub == null) showHub(); if (hub != null) { if (hub.closing()) { closePanel(); hub.reopen(); } hub.showTasks(true); } } }
            case "system_recents" -> global(GLOBAL_ACTION_RECENTS);
            case "lock" -> global(GLOBAL_ACTION_LOCK_SCREEN);
            case "screenshot" -> screenshot();
            case "torch" -> toggleTorch();
            case "system_controls" -> setSystemControls(-1);
            case "wifi", "bluetooth", "data", "dnd", "airplane" -> {
                Boolean value = on(id);
                if (value == null) { showPanel("controls"); panels.chooseState(id); }
                else setSwitch(id, !value);
            }
            default -> {
                if (id.startsWith("tile:")) shell("tile_click", 0, ActionCatalog.component(id).flattenToString(), null);
                else if (id.startsWith("app:")) launchApp(id);
            }
        }
    }
    void setSwitch(String id, boolean enabled) {
        Panels target = panels;
        shell(id, enabled ? 1 : 0, "", result -> {
            if (target != null && panels == target) target.working(id, result.ok);
            main.postDelayed(() -> refreshStates(false, () -> { if (target != null && panels == target) target.working(id, false); }), 700);
        });
    }
    void setSystemControls(int value) {
        if (systemControlsBusy) return;
        SystemControlGuard guard = systemControlGuard;
        int requested = guard == null ? value : guard.begin(value);
        systemControlsBusy = true; Panels target = panels;
        if (target != null) target.working("system_controls", true);
        if (feedback != null) { feedback.setVisibility(View.GONE); feedback.announceForAccessibility("正在切换内外屏控制中心"); }
        CoverApp.bridge(this).run("system_controls", -1, requested, "", result -> {
            systemControlsBusy = false;
            if (guard != null) guard.finish(result);
            if (instance != this) return;
            if (!result.retryable) {
                int state = -1;
                if (result.output.equals("0") || result.output.equals("1")) state = Integer.parseInt(result.output);
                states.put("system_controls", state);
            }
            if (target != null) target.working("system_controls", false);
            if (panels != null) panels.updateStates();
            systemControlsTip(result.message);
        });
    }
    void systemControlsTip(String text) {
        if (systemControlsToast != null) { systemControlsToast.cancel(); systemControlsToast = null; }
        if (screenContext == null) return;
        if (!screenContext.getSystemService(android.app.NotificationManager.class).areNotificationsEnabled()) {
            if (feedback != null) { feedback.setText(text + "；安卓提示被禁用，长按图标开启提示权限"); feedback.setVisibility(View.VISIBLE); feedback.announceForAccessibility(feedback.getText()); }
            return;
        }
        // Use the system's text Toast on the existing cover context; never fall back to display 0.
        systemControlsToast = Toast.makeText(screenContext, text, Toast.LENGTH_LONG);
        systemControlsToast.show();
    }
    private void global(int action) { if (action == GLOBAL_ACTION_LOCK_SCREEN) removeHubImmediately(); else dismissHub(); closePanel(); main.postDelayed(() -> { if (!performGlobalAction(action)) message("系统未接受此操作"); }, 80); }
    void openSettings(String section) { launch(new Intent(this, MainActivity.class).putExtra("section", section)); }
    void launchApp(String id) {
        int target = display == null ? -1 : display.getDisplayId();
        CoverApp.launcher(this).launch(this, prefs, id, target, () -> instance == this && prefs.enabled() && display != null && display.getDisplayId() == target, this::message, accepted -> { if (accepted) launcherAccepted(); });
    }
    void launcherAccepted() { dismissHub(); closePanel(); }
    void launch(Intent intent) {
        if (!notificationDisplayReady()) { message("所选外屏不可用，请解锁后重试"); return; }
        try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle()); dismissHub(); closePanel(); }
        catch (RuntimeException e) { message("无法在外屏打开：" + e.getClass().getSimpleName()); }
    }
    void openNotification(StatusBarNotification item) {
        if (!notificationDisplayReady()) { message("所选外屏不可用，请解锁并重新打开通知中心"); return; }
        if (item.getNotification().contentIntent == null) { message("这条通知没有打开入口"); return; }
        try {
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId());
            if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            item.getNotification().contentIntent.send(this, 0, null, null, null, null, options.toBundle());
            closePanel();
        } catch (Exception e) { message("通知入口已失效或被系统限制"); }
    }
    private boolean notificationDisplayReady() {
        Display selected = Displays.selected(this, prefs);
        return display != null && display.getDisplayId() != Display.DEFAULT_DISPLAY && selected != null && selected.getDisplayId() == display.getDisplayId() && selected.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    void openNotificationSettings(StatusBarNotification item) {
        if (!notificationDisplayReady()) { message("所选外屏不可用，请解锁后重试"); return; }
        if (!item.getUser().equals(android.os.Process.myUserHandle())) { message("请在通知所属的用户或工作资料中管理通知设置"); return; }
        try {
            startActivity(NotificationGroups.settingsIntent(item).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK), ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle());
            dismissHub(); closePanel();
        } catch (RuntimeException failure) { message("系统未能在所选外屏打开此应用的通知设置"); }
    }
    void openNotificationCenterSettings() {
        if (!notificationDisplayReady()) { message("所选外屏不可用，请解锁后重试"); return; }
        if (Build.VERSION.SDK_INT < 33) { message("当前系统未提供通知管理入口"); return; }
        try {
            Intent intent = new Intent(android.provider.Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle()); dismissHub(); closePanel();
        } catch (RuntimeException failure) { message("系统未能在所选外屏打开通知设置"); }
    }
    public void notificationsChanged() { if (!main.hasCallbacks(updateNotifications)) main.postDelayed(updateNotifications, 200); }
    public void permissionsChanged() { main.removeCallbacks(updatePreferences); main.post(updatePreferences); }
    private void toggleTorch() {
        if (torchCamera == null) { message("未找到可用闪光灯"); return; }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) { openSettings("main"); message("请在设置页授权手电筒所需的相机权限"); return; }
        try { cameras.setTorchMode(torchCamera, !Boolean.TRUE.equals(torchOn)); message("手电筒请求已提交"); }
        catch (Exception e) { message("手电筒不可用：" + e.getClass().getSimpleName()); }
    }
    MediaSessions mediaSessions() { if (mediaSessions == null) mediaSessions = new MediaSessions(screenContext); return mediaSessions; }
    void openMediaPlayer() {
        android.media.session.MediaController controller = mediaSessions().controller();
        if (controller == null) { message("请先打开播放器"); return; }
        if (display == null || display.getDisplayId() == 0) { message("没有可用外屏"); return; }
        android.app.PendingIntent entry = controller.getSessionActivity();
        if (entry == null) { Intent intent = screenContext.getPackageManager().getLaunchIntentForPackage(controller.getPackageName()); if (intent != null) launch(intent); else message("播放器未提供打开入口"); return; }
        try {
            ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId());
            if (Build.VERSION.SDK_INT >= 34) options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
            entry.send(this, 0, null, null, null, null, options.toBundle()); closePanel();
        } catch (Exception e) { message("播放器入口已失效或被系统限制"); }
    }
    void media(int code) {
        AudioManager audio = getSystemService(AudioManager.class);
        audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, code)); audio.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, code)); message("媒体按键已发送");
    }
    private void screenshot() {
        if (display == null || screenshotInProgress) return;
        int displayId = display.getDisplayId(); screenshotInProgress = true; removeWindows();
        main.postDelayed(() -> {
            try { takeScreenshot(displayId, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                Bitmap copy = null, source = null;
                try { source = Bitmap.wrapHardwareBuffer(result.getHardwareBuffer(), result.getColorSpace()); if (source != null) copy = source.copy(Bitmap.Config.ARGB_8888, false); }
                catch (RuntimeException ignored) { }
                finally { if (source != null) source.recycle(); result.getHardwareBuffer().close(); }
                screenshotInProgress = false;
                if (instance != CoverService.this) { if (copy != null) copy.recycle(); return; }
                reconcile(true);
                if (copy == null) { message("无法读取外屏截图"); return; }
                Bitmap saved = copy; files.execute(() -> saveScreenshot(saved));
            }
            @Override public void onFailure(int errorCode) { screenshotInProgress = false; if (instance == CoverService.this) { reconcile(true); message("截图被系统拒绝，错误码 " + errorCode); } }
            }); } catch (RuntimeException e) { screenshotInProgress = false; reconcile(true); message("截图不可用：" + e.getClass().getSimpleName()); }
        }, 160);
    }
    private void saveScreenshot(Bitmap bitmap) {
        Uri uri = null;
        try {
            ContentValues values = new ContentValues(); values.put(MediaStore.Images.Media.DISPLAY_NAME, "FlipCover-" + System.currentTimeMillis() + ".png");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png"); values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/FlipCover"); values.put(MediaStore.Images.Media.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("MediaStore insert failed");
            try (OutputStream output = getContentResolver().openOutputStream(uri)) { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) throw new IllegalStateException("PNG failed"); }
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0); getContentResolver().update(uri, values, null, null);
            main.post(() -> message("外屏截图已保存到 Pictures/FlipCover"));
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            main.post(() -> message("截图保存失败"));
        } finally { bitmap.recycle(); }
    }
    @Override public void onDestroy() {
        stopAutomaticRotation();
        if (systemControlGuard != null) systemControlGuard.close();
        instance = null; setStatus("无障碍服务已断开"); main.removeCallbacksAndMessages(null); removeWindows();
        windowPackages.clear(); dockVisibility.reset();
        CoverApp.bridge(this).removeObserver(bridgeChanged);
        if (prefs != null) prefs.data.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        getSystemService(DisplayManager.class).unregisterDisplayListener(this);
        try { unregisterReceiver(screenReceiver); } catch (RuntimeException ignored) { }
        if (cameras != null) cameras.unregisterTorchCallback(torchCallback);
        files.shutdown(); super.onDestroy();
    }
}
