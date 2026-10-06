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
    volatile Context screenContext;
    Display display;
    DockGeometry.Placement placement;
    private WindowManager windows;
    private DockView dock;
    private PanelEntryView panelEntry;
    private DockGeometry.Placement panelEntryPlacement;
    private DockGeometry.Placement dockPlacement;
    private final DockVisibility dockVisibility = new DockVisibility();
    private final StatusAppVisibility statusVisibility = new StatusAppVisibility();
    private java.util.Set<String> compactApplications = java.util.Set.of();
    private boolean dockAppRulesEnabled;
    private boolean avoidKeyboard;
    private final Runnable settleStatusApplication = () -> { statusVisibility.settle(android.os.SystemClock.uptimeMillis()); syncCardStatus(); };
    private boolean launcherHostFocused;
    private boolean launcherEntryPending;
    private final Runnable settleLauncherEntry = () -> { launcherEntryPending = false; launcherVisibilityChanged(); };
    private Boolean keyboardVisible;
    private final Map<Integer, String> windowPackages = new java.util.LinkedHashMap<>(32, .75f, true);
    private LinearLayout panel;
    private FrameLayout panelHost;
    private InterfaceCard panelCard;
    PanelGlassSession panelGlass;
    private boolean panelMemoryFallback;
    private String lastGlassDiagnostics = "玻璃背景：未打开\n";
    private DockGeometry.Box panelFrame;
    private DockGeometry.Box cardSafeFrame;
    private StatusBarView statusBar;
    private DockGeometry.Box statusBox;
    private DockGeometry.Box controlStatusBox;
    private final android.graphics.Rect panelContentPadding = new android.graphics.Rect();
    private AppHubView hub;
    private AppHubView openingTaskHub;
    private PanelGlassSession taskGlass;
    private RecentTasksView taskSurface;
    private String lastTaskGlassDiagnostics = "多任务玻璃：未打开\n";
    private FrameLayout hubHost;
    private final FrameLayout[] hubHosts = new FrameLayout[2];
    private InterfaceCard hubCard;
    private DockGeometry.Box hubFrame;
    private int launcherFrameRotation = android.view.Surface.ROTATION_0;
    private record HubPush(AppHubView view, FrameLayout host, InterfaceCard card, PanelGlassSession glass, RecentTasksView tasks, Consumer<Boolean> blurListener, WindowManager blurWindows, float startY, float exitDistance, float progress, int entryEdge, boolean sceneMotion) { }
    private HubPush hubPush;
    private record PanelPush(LinearLayout view, InterfaceCard card, Panels contents, TextView feedback, String page, PanelGlassSession glass, boolean fallback, Consumer<Boolean> blurListener, android.graphics.Rect padding, float startY, float exitDistance, int edge, float progress, ControlEditorView editor) { }
    private PanelPush panelPush;
    private float hubSceneProgress = 1;
    private int hubSceneEdge;
    private boolean panelDragging;
    private float panelProgress = 1;
    private boolean panelPulling, panelTargetOpen = true, panelPullCancelOpen, panelPullFresh;
    private float panelPullStartProgress, panelPullStartDistance, panelPullSourceStartProgress;
    private ValueAnimator panelAnimation;
    private boolean homeClosing;
    private long appLaunchGeneration;
    private ValueAnimator sceneBootstrap;
    private InterfaceCard bootstrapCard;
    private float bootstrapProgress, panelBridgeOffset, panelUserProgress, panelSourceUserProgress, panelSourceProgress = 1;
    private boolean hubSceneMotion = true;
    private boolean bootstrapMoves;
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
    private boolean taskInsetsDeferred;
    private record BrightnessRequest(Panels target, int displayId, int value, long deadline, long sequence, Runnable finished) { }
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
            if (cameraId.equals(torchCamera)) { torchOn = enabled; if (panels != null) panels.updateStates(); if (detailContent!=null) detailContent.torchChanged(); }
        }
        @Override public void onTorchModeUnavailable(String cameraId) { if (cameraId.equals(torchCamera)) { torchOn = null; if (panels != null) panels.updateStates(); if (detailContent!=null) detailContent.torchChanged(); } }
    };
    private int navigationInsetPixels;
    private final Runnable updateDisplay = () -> reconcile(false);
    private final Runnable settledDisplay = () -> reconcile(false);
    private String lastScreenEvent = "尚未收到";
    private String lastLauncherRecovery = "尚未收到";
    private int pendingLauncherDisplay = -1;
    private final Runnable restoreFromLauncherCard = () -> {
        int target = pendingLauncherDisplay; pendingLauncherDisplay = -1;
        if (instance != this || prefs == null || !prefs.enabled() || target <= 0) return;
        Display selected = Displays.selected(this, prefs);
        if (selected == null || selected.getDisplayId() != target || selected.getState() != Display.STATE_ON || getSystemService(KeyguardManager.class).isKeyguardLocked() || !CoverApp.launcherWidgets(this).visible(target)) return;
        lastLauncherRecovery = android.os.SystemClock.elapsedRealtime() / 1000 + "s · 应用中心卡片可见 · 屏幕 " + target;
        reconcile(false);
    };
    private final Runnable updateNotifications = () -> {
        boolean ready = CoverNotifications.ready();
        if (mediaAccessReady == null || mediaAccessReady != ready) { mediaAccessReady = ready; if (mediaSessions != null) mediaSessions.refresh(); }
        if (statusBar != null) statusBar.refreshNotifications();
        if (hub != null && hub.isAttachedToWindow() && hub.expanded()) hub.notificationsChanged();
        StatusBarView panelStatus = panelCard == null ? null : panelCard.statusBar();
        if (panelStatus != null && panelStatus.isAttachedToWindow()) panelStatus.refreshNotifications();
        if (hubCard != null && hubCard.statusBar() != null && hubCard.statusBar().isAttachedToWindow()) hubCard.statusBar().refreshNotifications();
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
            closeTaskGlass(); refreshBlurState();
            pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
            stopAutomaticRotation();
            states.clear();
            if (detailContent!=null) detailContent.statesInvalidated();
            if (panels != null) { panels.updateStates(); panels.brightness(new ShizukuBridge.Result(false, "连接中断", "")); }
        } else if (panelPage.equals("controls")) refreshStates(true);
    }
    private final Runnable updatePreferences = () -> reconcile(true);
    private final SharedPreferences.OnSharedPreferenceChangeListener preferenceListener = (preferences, key) -> {
        if (key == null || key.equals("enabled") || key.equals("display")) appLaunchGeneration++;
        if ("system_controls_disabled".equals(key) || "launcher_page".equals(key) || "launcher_page_boot".equals(key)) return;
        if ("status_hidden_apps".equals(key) || "dock_compact_apps".equals(key) || "dock_auto_hide".equals(key) || "avoid_keyboard".equals(key)) { main.post(this::updateApplicationRules); return; }
        if ("status_enabled".equals(key) || key == null) main.post(this::updateApplicationRules);
        if ("panel".equals(key) && panel != null && panelPage.equals("controls")) { if (controlEditor == null) { dismissDetails(); populatePanel("controls"); refreshStates(true); } return; }
        if ("hub_workspace".equals(key) || "hub_workspace_compact".equals(key)) { if (hub != null) hub.workspacePreferencesChanged(); return; }
        if ("hub_pins".equals(key) || "hub_pinned".equals(key)) { if (hub != null) hub.dockPreferencesChanged(); return; }
        if (key != null && key.startsWith("hub_workspace_")) { if (hub != null) hub.workspaceOptionsChanged(); return; }
        if (key != null && (key.equals("layout_backup") || key.equals("layout_saved_at") || key.equals("layout_undo") || key.equals("panel_undo") || key.equals("hub_sort") || key.equals("hub_workspace") || key.equals("hub_workspace_compact"))) return;
        main.removeCallbacks(updatePreferences); main.post(updatePreferences);
    };
    private final BroadcastReceiver screenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (!Intent.ACTION_SCREEN_OFF.equals(action) && !Intent.ACTION_SCREEN_ON.equals(action) && !Intent.ACTION_USER_PRESENT.equals(action) && !android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(action)) return;
            lastScreenEvent = android.os.SystemClock.elapsedRealtime() / 1000 + "s · " + action;
            if (systemControlGuard != null && (Intent.ACTION_SCREEN_ON.equals(intent.getAction()) || Intent.ACTION_USER_PRESENT.equals(intent.getAction()))) systemControlGuard.changed();
            if (Intent.ACTION_SCREEN_OFF.equals(intent.getAction())) {
                appLaunchGeneration++;
                resetStatusApplication();
                stopAutomaticRotation();
                main.removeCallbacks(restoreFromLauncherCard); pendingLauncherDisplay = -1;
                main.removeCallbacks(updateDisplay); main.removeCallbacks(settledDisplay);
                removeWindows(); signature = ""; setStatus("息屏期间已隐藏");
            } else if (android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED.equals(intent.getAction())) refreshBlurState();
            else scheduleDisplay();
        }
    };
    private final BroadcastReceiver systemDialogsReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { systemDialogsClosed(intent); }
    };
    private void registerScreenEvents() {
        IntentFilter filter = new IntentFilter(); filter.addAction(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT); filter.addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        // These are protected system actions. Vendor unlock senders may use a separate UID.
        registerReceiver(screenReceiver, filter, null, main, Context.RECEIVER_EXPORTED);
        // Android 11 predates the dedicated Home permission; use its existing system permission.
        registerReceiver(systemDialogsReceiver, new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), systemDialogsPermission(Build.VERSION.SDK_INT), main, Context.RECEIVER_EXPORTED);
    }
    static String systemDialogsPermission(int sdk) { return sdk >= 31 ? "android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS" : "android.permission.STATUS_BAR"; }
    void launcherCardVisible(int target) {
        if (instance != this || target <= 0) return;
        pendingLauncherDisplay = target;
        if (!main.hasCallbacks(restoreFromLauncherCard)) main.post(restoreFromLauncherCard);
    }
    @Override protected void onServiceConnected() {
        instance = this; prefs = new Prefs(this); automaticRotation = new CoverRotation(this); prefs.data.registerOnSharedPreferenceChangeListener(preferenceListener);
        updateApplicationRules();
        if (systemControlGuard != null) systemControlGuard.close();
        systemControlGuard = new SystemControlGuard(prefs, main, CoverApp.bridge(this), result -> {
            states.put("system_controls", result.ok ? 0 : -1);
            if (panels != null) panels.updateStates();
            if (detailContent!=null) detailContent.stateChanged("system_controls");
            if (!result.ok) android.util.Log.w("SystemControlGuard", result.message);
        });
        systemControlGuard.changed();
        getSystemService(DisplayManager.class).registerDisplayListener(this, main);
        registerScreenEvents();
        cameras = getSystemService(CameraManager.class);
        try {
            for (String id : cameras.getCameraIdList()) {
                CameraCharacteristics info = cameras.getCameraCharacteristics(id);
                if (Boolean.TRUE.equals(info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE)) && Integer.valueOf(CameraCharacteristics.LENS_FACING_BACK).equals(info.get(CameraCharacteristics.LENS_FACING))) { torchCamera = id; break; }
            }
            cameras.registerTorchCallback(torchCallback, main);
        } catch (Exception ignored) { }
        CoverApp.inputs(this).observe(updateNativeInput); CoverApp.bridge(this).addObserver(bridgeChanged); CoverApp.bridge(this).connect(); reconcile(true); scheduleDisplay(); CoverApp.launcherWidgets(this).refresh();
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Read window IDs/types and event package only; never request a node tree or its text.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Content events supply only a missing window identity. Known identities cost
            // no window enumeration, display reconciliation, node reads or text processing.
            if ((!statusVisibility.enabled() && !dockAppRulesEnabled) || event.getPackageName() == null || event.getPackageName().toString().equals(windowPackages.get(event.getWindowId()))) return;
            Display target = Displays.selected(this, prefs);
            if (target == null || Build.VERSION.SDK_INT >= 33 && event.getDisplayId() != target.getDisplayId()) return;
            observeForeground(event); return;
        }
        observeForeground(event);
        if (getSystemService(KeyguardManager.class).isKeyguardLocked() && dock != null) reconcile(false);
        scheduleDisplay();
    }
    private void updateApplicationRules() {
        if (prefs == null) return;
        statusVisibility.applications(prefs.statusEnabled() ? prefs.statusHiddenApps() : java.util.Set.of());
        java.util.Set<String> nextCompact = prefs.compactApps();
        if (!compactApplications.equals(nextCompact)) { compactApplications = java.util.Set.copyOf(nextCompact); dockVisibility.rulesChanged(); }
        dockAppRulesEnabled = prefs.autoHideDock() && !compactApplications.isEmpty();
        avoidKeyboard = prefs.avoidKeyboard();
        if (!statusVisibility.enabled() && !dockAppRulesEnabled && !dockVisibility.needsForeground()) windowPackages.clear();
        main.removeCallbacks(settleStatusApplication);
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            int types = info.eventTypes;
            if (statusVisibility.enabled() || dockAppRulesEnabled) info.eventTypes |= AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            else info.eventTypes &= ~AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
            if (types != info.eventTypes) setServiceInfo(info);
        }
        if (statusVisibility.enabled() || dockAppRulesEnabled) observeForeground(null);
        syncCardStatus(); syncDockVisibility();
    }
    private void resetStatusApplication() { main.removeCallbacks(settleStatusApplication); statusVisibility.reset(); }
    private void statusApplication(String packageName) {
        if (!statusVisibility.enabled()) return;
        long now = android.os.SystemClock.uptimeMillis();
        statusVisibility.observed(packageName, now);
        long remaining = statusVisibility.remaining(now);
        if (remaining == 0) main.removeCallbacks(settleStatusApplication);
        else if (!main.hasCallbacks(settleStatusApplication)) main.postDelayed(settleStatusApplication, remaining);
        syncCardStatus();
    }
    private void systemDialogsClosed(Intent intent) {
        // Home also fires when Samsung changes the clock/card inside the same host window.
        // Other reasons (recents, assistant, screenshot, gestureNav) are not proof of Home.
        if (!Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction()) || !"homekey".equals(intent.getStringExtra("reason")) || prefs == null || display == null) return;
        Display selected = Displays.selected(this, prefs);
        if (selected == null || selected.getDisplayId() == Display.DEFAULT_DISPLAY || selected.getDisplayId() != display.getDisplayId() || selected.getState() != Display.STATE_ON) return;
        if (intent.hasExtra("displayId") && intent.getIntExtra("displayId", -1) != selected.getDisplayId()) return;
        dismissForHome();
    }
    private void dismissForHome() {
        appLaunchGeneration++;
        if (homeClosing || panel == null && hub == null && panelPush == null && hubPush == null) return;
        homeClosing = true;
        cancelPanelAnimation(); cancelSceneBootstrap();
        finishPanelPush(false); finishHubPush(false); dismissDetails();
        pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
        if (controlEditor != null) controlEditor.cancelDrag();
        panelPulling = panelDragging = false; panelTargetOpen = false;
        if (panelTintAnimation != null) panelTintAnimation.cancel(); panelTintAnimation = null;
        InterfaceCard card = panelCard != null ? panelCard : hubCard;
        int edge = hub != null && !hub.expanded() && !hub.showingTasks() ? DockGeometry.BOTTOM : DockGeometry.TOP;
        float extent = panelCard != null ? panelExtent() : hubSceneExtent();
        Runnable finished = () -> { closePanel(); removeHubImmediately(); homeClosing = false; syncHubEntry(); if (BuildConfig.DEBUG) android.util.Log.i("CoverHome", "temporary interfaces released"); };
        if (BuildConfig.DEBUG) android.util.Log.i("CoverHome", "Home exit started; display=" + (display == null ? -1 : display.getDisplayId()) + "; edge=" + edge);
        if (card == null || !card.ready()) { finished.run(); return; }
        card.beginExit(edge, extent);
        // Retire the old app image before sliding over Samsung's new Home scene.
        card.dropGlass(); panelGlass = null; taskGlass = null;
        FrameLayout host = panelCard != null ? panelHost : hubHost;
        if (host != null && windows != null) try {
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) host.getLayoutParams();
            layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            card.windowMaterial(layout, false, false); windows.updateViewLayout(host, layout);
        } catch (RuntimeException ignored) { finished.run(); return; }
        syncHubEntry();
        float start = InterfaceCard.position(card, edge), end = InterfaceCard.sign(edge) * extent;
        if (!ValueAnimator.areAnimatorsEnabled()) { InterfaceCard.translate(card, edge, extent); finished.run(); return; }
        panelAnimation = InterfaceCard.motion(start, end);
        panelAnimation.addUpdateListener(animation -> { float position = (float) animation.getAnimatedValue(); InterfaceCard.translate(card, edge, InterfaceCard.sign(edge) * position); if (hub != null) hub.sceneMotion(card.getTranslationX(), card.getTranslationY()); });
        panelAnimation.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animation) { panelAnimation = null; finished.run(); } });
        panelAnimation.start();
    }
    private void observeForeground(AccessibilityEvent event) {
        if (prefs == null) return;
        launcherHostFocused = false; nativeHostVisible = false;
        Display target = Displays.selected(this, prefs);
        if (target == null || target.getDisplayId() == Display.DEFAULT_DISPLAY) { resetStatusApplication(); syncCardStatus(); launcherVisibilityChanged(); return; }
        try {
            android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all = getWindowsOnAllDisplays();
            try {
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> items = all.get(target.getDisplayId());
                if (items == null) { statusApplication(null); if (taskGlass != null && taskGlass.displayId == target.getDisplayId()) taskGlass.sourceMissing(); launcherVisibilityChanged(); return; }
                boolean packageNeeded = statusVisibility.enabled() || dockAppRulesEnabled || dockVisibility.needsForeground();
                if (packageNeeded && event != null && event.getPackageName() != null && event.getWindowId() >= 0
                    && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
                    && Build.VERSION.SDK_INT >= 33 && event.getDisplayId() == target.getDisplayId()) {
                    // The state event may precede the window-list update. Retain its verified
                    // display/window identity so the next snapshot can resolve that window.
                    windowPackages.put(event.getWindowId(), event.getPackageName().toString());
                }
                String active = null, focused = null; boolean inputMethod = false;
                int backgroundWindow = -1, backgroundLayer = Integer.MIN_VALUE;
                CharSequence backgroundTitle = null; boolean externalSceneChanged = false;
                android.graphics.Rect backgroundBounds = new android.graphics.Rect();
                for (android.view.accessibility.AccessibilityWindowInfo window : items) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getLayer() > backgroundLayer) {
                        backgroundLayer = window.getLayer(); backgroundWindow = window.getId(); backgroundTitle = window.getTitle(); window.getBoundsInScreen(backgroundBounds);
                    }
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) inputMethod = true;
                    // Samsung's host window survives card and orientation changes without
                    // necessarily emitting another package-bearing WINDOW_STATE_CHANGED.
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.isFocused() && "SubLauncherWindow".contentEquals(window.getTitle() == null ? "" : window.getTitle())) launcherHostFocused = true;
                    if (window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM) continue;
                    // Samsung can switch the cover host's page without replacing its window ID.
                    if (event != null && event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && window.getId() == event.getWindowId()
                        && (window.isFocused() || window.isActive() || taskGlass != null && taskGlass.sourceStateChanged(window.getId()))) externalSceneChanged = true;
                    if (packageNeeded && event != null && window.getId() == event.getWindowId() && (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) && event.getPackageName() != null) windowPackages.put(window.getId(), event.getPackageName().toString());
                    String name = packageNeeded ? windowPackages.get(window.getId()) : null;
                    if (name != null && window.isActive() && active == null) active = name;
                    if (name != null && window.isFocused() && focused == null) focused = name;
                }
                nativeHostVisible = "SubLauncherWindow".contentEquals(backgroundTitle == null ? "" : backgroundTitle);
                if (hub != null && hub.showingTasks() && taskGlass != null && taskGlass.displayId == target.getDisplayId() && (externalSceneChanged || taskGlass.observedSourceChanged(backgroundWindow, backgroundBounds, backgroundTitle))) {
                    PanelGlassSession session = taskGlass; AppHubView owner = hub; RecentTasksView page = owner.taskPage(); session.invalidateSource();
                    session.refreshSource(this, target, backgroundWindow, backgroundBounds, backgroundTitle,
                        () -> hub == owner && taskGlass == session && !owner.closing() && owner.isAttachedToWindow() && currentTaskDisplay(target.getDisplayId()) && session.matches(target) && PanelGlassSession.allowed(screenContext, prefs), page::backgroundStable);
                }
                // Keep a small cache so a temporarily covered app can regain focus without
                // a new state event. IDs and package names are never persisted or exported.
                if (packageNeeded) while (windowPackages.size() > 32) windowPackages.remove(windowPackages.keySet().iterator().next());
                if (packageNeeded) dockVisibility.foreground(active != null ? active : focused);
                // The highest application window belongs to this display. System/IME/own
                // overlays cannot borrow another display's package. Briefly unresolved
                // window replacements wait for metadata, then default to visible.
                if (statusVisibility.enabled()) statusApplication(windowPackages.get(backgroundWindow));
                keyboardVisible = inputMethod;
                if (display != null && display.getDisplayId() == target.getDisplayId()) { syncDockVisibility(); launcherVisibilityChanged(); }
            } finally { for (int i = 0; i < all.size(); i++) for (android.view.accessibility.AccessibilityWindowInfo window : all.valueAt(i)) window.recycle(); }
        } catch (RuntimeException ignored) { statusApplication(null); launcherHostFocused = false; launcherVisibilityChanged(); }
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
                appLaunchGeneration++;
                resetStatusApplication();
                removeWindows(); signature = "";
                setStatus(!prefs.enabled() ? "快捷栏已暂停" : selected == null ? "未检测到外屏，请手动选择" : locked ? "系统报告锁屏，快捷栏已隐藏" : "外屏未亮起，快捷栏已隐藏（状态 " + selected.getState() + "）");
                return;
            }
            Point size = Displays.size(selected);
            int rotation = selected.getRotation();
            if (display == null || display.getDisplayId() != selected.getDisplayId()) { resetStatusApplication(); dockVisibility.reset(); windowPackages.clear(); launcherHostFocused = false; keyboardVisible = null; }
            observeForeground(null);
            Context context = createDisplayContext(selected);
            float density = context.getResources().getDisplayMetrics().density;
            android.view.WindowInsets displayInsets = null;
            try { WindowManager metrics = windows != null && display != null && display.getDisplayId() == selected.getDisplayId() ? windows : context.getSystemService(WindowManager.class); displayInsets = metrics.getMaximumWindowMetrics().getWindowInsets(); } catch (RuntimeException ignored) { }
            android.graphics.Insets mandatory = displayInsets == null ? android.graphics.Insets.NONE : displayInsets.getInsets(android.view.WindowInsets.Type.mandatorySystemGestures());
            android.graphics.Insets navigation = displayInsets == null ? android.graphics.Insets.NONE : displayInsets.getInsets(android.view.WindowInsets.Type.navigationBars());
            android.graphics.Insets navigationBounds = displayInsets == null ? android.graphics.Insets.NONE : displayInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars());
            android.graphics.Insets systemInsets = contentInsets(displayInsets, selected.getCutout());
            String nextSignature = selected.getDisplayId() + ":" + rotation + ":" + size + ":" + density + ":" + selected.getCutout() + ":" + mandatory + ":" + navigation + ":" + navigationBounds + ":" + systemInsets;
            if (!force && nextSignature.equals(signature) && dock != null && dock.isAttachedToWindow() && panelEntry != null && panelEntry.isAttachedToWindow()) { syncDockVisibility(); return; }
            // App removal/restoration changes navigation insets without changing the display.
            // Keep the live carousel, close queue and pointer owner until this task session ends.
            if (!force && retainTaskSession(selected, size, rotation, density)) { taskInsetsDeferred = true; return; }
            taskInsetsDeferred = false;
            String reopen = homeClosing ? "" : panelPage; boolean reopenHub = !homeClosing && hub != null && !hub.closing() && hub != openingTaskHub, expandedHub = reopenHub && hub.expanded(), reopenTasks = reopenHub && hub.showingTasks();
            android.os.Bundle editorState = !homeClosing && controlEditor != null && display != null && display.getDisplayId() == selected.getDisplayId() ? controlEditor.snapshot() : null;
            RecentTasks.Task selectedTask = reopenHub ? hub.selectedTask() : null;
            AppHubView.WorkspaceState workspaceState = reopenHub ? hub.workspaceState() : null;
            boolean firstDockPresentation = dock == null || dock.compact() || launcherEntryPending;
            removeWindows();
            display = selected;
            screenContext = context.createWindowContext(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
            windows = screenContext.getSystemService(WindowManager.class);
            ArrayList<DockGeometry.Box> cutouts = new ArrayList<>();
            if (selected.getCutout() != null) for (android.graphics.Rect r : selected.getCutout().getBoundingRects()) cutouts.add(new DockGeometry.Box(r.left, r.top, r.width(), r.height()));
            placement = DockGeometry.resolve(size.x, size.y, cutouts, density, prefs.corner(rotation), prefs.widthRatio(), prefs.heightRatio(), prefs.data.getBoolean("auto_placement", true));
            DockGeometry.Placement entryAnchor = placement;
            int panelHomeInset = Math.max(mandatory.bottom, navigation.bottom);
            navigationInsetPixels = 0;
            if (prefs.avoidNavigation()) {
                android.graphics.Insets safe = android.graphics.Insets.max(mandatory, navigationBounds);
                navigationInsetPixels = switch (placement.edge()) { case DockGeometry.TOP -> safe.top; case DockGeometry.LEFT -> safe.left; case DockGeometry.RIGHT -> safe.right; default -> safe.bottom; };
                placement = DockGeometry.avoidEdge(placement, size.x, size.y, Math.max(navigationInsetPixels, Ui.dp(screenContext, 16)), Ui.dp(screenContext, prefs.navigationGap()));
            }
            if (!prefs.avoidNavigation()) placement = DockGeometry.edgeTouch(placement, size.x, size.y);
            DockGeometry.Box widgetArea = resolveContentGeometry(size, rotation, cutouts, entryAnchor, systemInsets, panelHomeInset);
            ensurePanelHost(); ensureHubHosts(); addDock(firstDockPresentation);
            if (prefs.statusEnabled()) { statusBar = new StatusBarView(screenContext, prefs); syncCardStatus(); windows.addView(statusBar, statusParameters()); }
            panelEntry = new PanelEntryView(screenContext, prefs, panelEntryPlacement, chromeListener());
            windows.addView(panelEntry, panelEntryParameters());
            CoverApp.widgets(this).safeArea(selected, size.x, size.y, widgetArea);
            signature = nextSignature;
            setStatus("快捷栏运行中 · 屏幕 " + selected.getDisplayId() + (placement.measured() ? " · 按缺口定位" : " · 位置需校准"));
            if (!reopen.isEmpty()) { showPanel(reopen); if (editorState != null) editControls(editorState); } else if (reopenHub) { showHub(expandedHub, reopenTasks); if (hub != null) { hub.restoreWorkspaceState(workspaceState); if (reopenTasks) { hub.showTasks(true); hub.selectTask(selectedTask); } } }
        } catch (RuntimeException e) { removeWindows(); signature = ""; setStatus("外屏挂载失败：" + e.getClass().getSimpleName()); }
    }
    /** Visibility-aware system bars; physical cutout safety also works when window metrics are unavailable. */
    static android.graphics.Insets contentInsets(android.view.WindowInsets insets, android.view.DisplayCutout cutout) {
        android.graphics.Insets safe = insets == null ? android.graphics.Insets.NONE : insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.mandatorySystemGestures() | android.view.WindowInsets.Type.displayCutout());
        if (cutout != null) safe = android.graphics.Insets.max(safe, android.graphics.Insets.of(cutout.getSafeInsetLeft(), cutout.getSafeInsetTop(), cutout.getSafeInsetRight(), cutout.getSafeInsetBottom()));
        return safe;
    }
    /** One display-coordinate content frame for runtime pages and both native card hosts. Entry touch keeps its own edge policy. */
    DockGeometry.Box resolveContentGeometry(Point size, int rotation, java.util.List<DockGeometry.Box> cutouts, DockGeometry.Placement entryAnchor, android.graphics.Insets insets, int homeInset) {
        float density = screenContext.getResources().getDisplayMetrics().density;
        DockGeometry.Box systemSafe = new DockGeometry.Box(insets.left, insets.top, Math.max(0, size.x - insets.left - insets.right), Math.max(0, size.y - insets.top - insets.bottom));
        DockGeometry.Box physical = DockGeometry.panelContent(placement, size.x, size.y, cutouts);
        cardSafeFrame = physical.intersect(systemSafe);
        int statusHeight = Math.round(Ui.dp(screenContext, 20) * prefs.statusScale() / 100f);
        controlStatusBox = DockGeometry.statusBar(cardSafeFrame, size.x, statusHeight, density).intersect(systemSafe);
        String entryPosition = prefs.entryPosition(rotation);
        int entryTopInset = DockGeometry.panelEntryTopInset(entryPosition, prefs.statusScale(), density);
        if (!entryPosition.equals("bottom_right")) entryTopInset += Math.max(0, cardSafeFrame.y() - physical.y());
        panelEntryPlacement = DockGeometry.panelEntry(entryAnchor, placement, size.x, size.y, cutouts, density, homeInset, entryPosition, entryTopInset);
        DockGeometry.Box content = physical.intersect(panelEntryPlacement.panel());
        panelFrame = new DockGeometry.Box(0, 0, size.x, size.y);
        statusBox = prefs.statusEnabled() ? controlStatusBox : null;
        if (statusBox != null) {
            int top = Math.max(content.y(), statusBox.bottom() + Ui.dp(screenContext, 4));
            content = new DockGeometry.Box(content.x(), top, content.width(), Math.max(0, content.bottom() - top));
        }
        // Preserve the pre-system bottom so launcher movement is not applied twice after clipping panel content.
        DockGeometry.Placement chrome = new DockGeometry.Placement(placement.visual(), placement.touch(), content, placement.edge(), placement.measured());
        hubFrame = DockGeometry.hubContent(chrome, size.x, size.y, cutouts, systemSafe);
        placement = new DockGeometry.Placement(chrome.visual(), chrome.touch(), content.intersect(systemSafe), chrome.edge(), chrome.measured());
        launcherFrameRotation = rotation;
        return DockGeometry.widgetContent(placement, panelEntryPlacement, statusBox, size.x, size.y, cutouts, density).intersect(systemSafe);
    }
    private boolean retainTaskSession(Display selected, Point size, int rotation, float density) {
        return hub != null && (hub.showingTasks() || hub == openingTaskHub) && hub.isAttachedToWindow()
            && display != null && display.getDisplayId() == selected.getDisplayId()
            && panelFrame != null && panelFrame.width() == size.x && panelFrame.height() == size.y
            && launcherFrameRotation == rotation && screenContext != null
            && Float.compare(screenContext.getResources().getDisplayMetrics().density, density) == 0
            && java.util.Objects.equals(display.getCutout(), selected.getCutout());
    }
    private void resumeTaskInsets() {
        if (!taskInsetsDeferred) return;
        taskInsetsDeferred = false; scheduleDisplay();
    }
    private void addDock(boolean firstPresentation) {
        main.removeCallbacks(settleLauncherEntry); launcherEntryPending = false;
        boolean compact = dockCompact();
        dockPlacement = compact ? DockGeometry.handlesOnly(placement, screenContext.getResources().getDisplayMetrics().density) : placement;
        dock = new DockView(screenContext, prefs, dockPlacement, savedPage, chromeListener(), compact);
        dock.passive(); configureInput(dock, dock::release);
        // The host window can appear before its per-card visible callback. Keep
        // these two entries out of the first frame, without delaying other keys.
        launcherEntryPending = firstPresentation && !compact && launcherHostFocused && CoverApp.launcherWidgets(this).cards().length > 0;
        launcherVisibilityChanged();
        if (launcherEntryPending) main.postDelayed(settleLauncherEntry, 600);
        if (compact) dock.setVisibility(View.GONE);
        windows.addView(dock, dockParameters());
    }
    private DockView.Listener chromeListener() {
        return new DockView.Listener() {
                @Override public void action(String id) { act(id); }
                @Override public void configure() { openSettings("dock"); }
                @Override public void beginPull(String page, float distance, float originY) { beginPanelPull(page, distance, originY); }
                @Override public void pull(String page, float distance) { pullPanel(page, distance); }
                @Override public void release(String page, float distance, float velocity, boolean canceled) { releasePanel(distance, velocity, canceled); }
                @Override public void toggleVisibility() { dockVisibility.toggle(dockAppRulesEnabled, compactApplications); syncDockVisibility(); }
            };
    }
    private WindowManager.LayoutParams dockParameters() {
        WindowManager.LayoutParams layout = parameters(dockPlacement.touch());
        if (dock.compact()) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return layout;
    }
    private WindowManager.LayoutParams panelEntryParameters() {
        WindowManager.LayoutParams layout = parameters(panelEntryPlacement.touch()); layout.setTitle("外屏双白条入口");
        if (controlEditor != null || homeClosing) layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        return layout;
    }
    private void syncHubEntry() {
        launcherVisibilityChanged();
        if (panelEntry == null || windows == null) return;
        try { windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { }
    }
    void launcherVisibilityChanged() {
        nativeInputChanged();
        if (dock == null || display == null) return;
        boolean eligible = hub == null && launcherHostFocused && display.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked();
        boolean visible = CoverApp.launcherWidgets(this).visible(display.getDisplayId());
        if (!eligible || visible) { launcherEntryPending = false; main.removeCallbacks(settleLauncherEntry); }
        dock.launcherHidden(eligible && (visible || launcherEntryPending));
    }
    private void syncDockVisibility() {
        if (dock == null || windows == null || panelDragging || dock.compact() == dockCompact()) return;
        try { boolean firstPresentation = dock.compact(); savedPage = dock.page(); windows.removeViewImmediate(dock); dock = null; addDock(firstPresentation); }
        catch (RuntimeException e) { removeWindows(); signature = ""; scheduleDisplay(); }
    }
    private boolean dockCompact() { dockVisibility.keyboard(avoidKeyboard && Boolean.TRUE.equals(keyboardVisible)); return dockVisibility.compact(dockAppRulesEnabled, compactApplications); }
    private NativeCardInput nativeInput;
    final InputNavigation.Memory inputMemory = new InputNavigation.Memory();
    boolean inputShortcut(String id, int target) {
        if (display == null || target == 0 || display.getDisplayId() != target || display.getState() != Display.STATE_ON || !ActionCatalog.valid(id) || getSystemService(KeyguardManager.class).isKeyguardLocked()) return false;
        if (controlEditor != null) { controlEditor.back(); return true; }
        if (id.equals("configure")) openSettings("main"); else act(id); return true;
    }
    private boolean nativeHostVisible;
    private final Runnable updateNativeInput = () -> {
        if (nativeInput == null) nativeInput = new NativeCardInput(this);
        LauncherWidgetBridge launcher = CoverApp.launcherWidgets(this); NativeWidgetBridge widgets = CoverApp.widgets(this);
        boolean ownLauncher = launcher.inputCard() >= 0;
        if (ownLauncher && widgets.inputCard() >= 0) { nativeInput.close(); return; }
        nativeInput.update(ownLauncher ? launcher.inputCard() : widgets.inputCard(), ownLauncher, ownLauncher ? launcher.inputViews() : widgets.inputViews());
    };
    void nativeInputChanged() { if (!main.hasCallbacks(updateNativeInput)) main.post(updateNativeInput); }
    boolean nativeInputEligible() { return windows != null && screenContext != null && display != null && display.getDisplayId() != 0 && display.getState() == Display.STATE_ON && nativeHostVisible && hub == null && panel == null && !Boolean.TRUE.equals(keyboardVisible) && !getSystemService(KeyguardManager.class).isKeyguardLocked(); }
    private InputSurface inputHost() {
        InputSurface host = new InputSurface(screenContext);
        configureInput(host, () -> act("back")); return host;
    }
    private void configureInput(InputSurface host, Runnable back) {
        host.memory(inputMemory, "runtime");
        boolean[] changedFocus = {false};
        host.navigation(back, () -> {
            if (windows == null || !host.isAttachedToWindow()) return;
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) host.getLayoutParams();
            if ((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0) return;
            if ((params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) { changedFocus[0] = true; params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(host, params); }
        });
        host.released(() -> { if (!changedFocus[0] || windows == null || !host.isAttachedToWindow()) return; changedFocus[0] = false; WindowManager.LayoutParams params = (WindowManager.LayoutParams) host.getLayoutParams(); if (!CoverApp.inputs(this).keyboardAvailable()) params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(host, params); });
    }
    private WindowManager.LayoutParams parameters(DockGeometry.Box box) {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(box.width(), box.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
        // These are physical display coordinates; locale direction must not mirror the camera cutout.
        params.gravity = Gravity.TOP | Gravity.LEFT; params.x = box.x(); params.y = box.y();
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setFitInsetsTypes(0); params.setTitle(getString(R.string.app_name)); return params;
    }
    private boolean glassTargetSelected(PanelGlassSession opening) { Display selected=Displays.selected(this,prefs); return selected != null && selected.getDisplayId() == opening.displayId; }
    private static boolean glassPage(String page) { return page.equals("controls") || page.equals("notifications") || page.equals("media") || page.equals("rotation"); }
    void showPanel(String page) { showPanel(page, false); }
    private void showPanel(String page, boolean dragging) {
        if (windows == null || dock == null || homeClosing) return;
        boolean retainedFallback = panel != null && panelCard == null && panelMemoryFallback && glassPage(page);
        // A failed or pending capture belongs to this opening too; switching pages must not retry it.
        PanelGlassSession retained = panelCard == null && panelGlass != null && panelGlass.matches(display) && glassPage(page) ? panelGlass : null;
        if (panel != null) beginPanelPush(panelEdge()); else closePanel();
        beginHubPush();
        if (retained != null) retained.clearViews();
        panelMemoryFallback = retainedFallback; panelGlass = retained; panelPage = page; panelDragging = true; panelTargetOpen = !dragging; panelProgress = panelSourceProgress = panelUserProgress = panelSourceUserProgress = panelBridgeOffset = 0;
        buildPanelCard(page, panelFrame, page.equals("controls") && controlStatusBox != null ? Math.max(placement.panel().y(), controlStatusBox.bottom() + Ui.dp(screenContext, 4)) : placement.panel().y());
        LinearLayout openingPanel = panel;
        InterfaceCard openingCard = panelCard;
        openingCard.prepareEntry();
        Runnable enter = () -> { if (panelCard != openingCard) return; startSceneBootstrap(openingCard); if (!dragging && panel == openingPanel && !panelPulling && panelProgress < 1 && panelAnimation == null) settlePanel(true); };
        try {
            WindowManager.LayoutParams layout = parameters(panelFrame);
            ensurePanelHost(); panelHost.addView(openingCard);
            if (retained != null) openingCard.glass(retained);
            syncCardStatus();
            panelHost.setVisibility(View.VISIBLE);
            layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            applyPanelBlur(layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
            windows.updateViewLayout(panelHost, layout); setPanelProgress(panelProgress);
            if (retained == null && !panelMemoryFallback) openingCard.prepareGlass(this, display, prefs, () -> panelCard == openingCard && display != null && openingCard.glass() != null && openingCard.glass().matches(display) && glassTargetSelected(openingCard.glass()) && display.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked(), () -> {
                if (panelCard != openingCard) return; panelGlass = openingCard.glass();
                setPanelProgress(panelProgress); if (panelGlass != null) lastGlassDiagnostics = panelGlass.diagnostics(); enter.run();
            }, hubPush != null || panelPush != null);
            panelGlass = openingCard.glass();
            if (panelEntry != null) panelEntry.panelVisible(true);
            // The persistent panel host was added below the chrome. Keep the entry's
            // existing surface attached so showing/settling a panel cannot blink it.
            if (panelGlass == null && Build.VERSION.SDK_INT >= 31 && prefs.panelBlur()) {
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
        if (openingCard.ready()) enter.run();
    }
    InterfaceCard buildPanelCard(String page, DockGeometry.Box frame, int contentTop) {
        InterfaceCard.Definition definition = InterfaceCard.panel(page);
        DockGeometry.Box content = definition.useSafeArea() ? placement.panel() : definition.useCardSafeArea() ? cardSafeBounds() : frame;
        int top = definition.useSafeArea() ? contentTop : content.y();
        if (definition.showStatusBar() && controlStatusBox != null) top = Math.max(top, controlStatusBox.bottom() + Ui.dp(screenContext, 4));
        LinearLayout body = buildPanelContent(page, frame, top, content);
        panelCard = new InterfaceCard(screenContext, prefs, definition, body, () -> { if (body instanceof PanelSurface surface) surface.prepareScenePush(); }, body::removeAllViews);
        panelCard.frameBounds(frame, cardSafeBounds()); panelCard.contentSafeBounds(placement.panel()); panelCard.statusBounds(controlStatusBox);
        panelContentPadding.set(body.getPaddingLeft(), body.getPaddingTop(), body.getPaddingRight(), body.getPaddingBottom()); return panelCard;
    }
    /** Business content; runtime and complete visual probes wrap it in buildPanelCard. */
    private DockGeometry.Box cardSafeBounds() {
        if (cardSafeFrame != null) return cardSafeFrame;
        DockGeometry.Box safe = placement.panel();
        if (controlStatusBox == null) return safe;
        int left = Math.min(safe.x(), controlStatusBox.x()), top = Math.min(safe.y(), controlStatusBox.y());
        return new DockGeometry.Box(left, top, Math.max(safe.right(), controlStatusBox.right()) - left, Math.max(safe.bottom(), controlStatusBox.bottom()) - top);
    }
    LinearLayout buildPanelContent(String page, DockGeometry.Box frame, int contentTop) {
        return buildPanelContent(page, frame, contentTop, placement.panel());
    }
    private LinearLayout buildPanelContent(String page, DockGeometry.Box frame, int contentTop, DockGeometry.Box content) {
        panelFrame = frame;
        panelPage = page;
        if (panelHost instanceof InputSurface input) input.page("panel:" + page);
        panel = new PanelSurface(screenContext, panelEdge(), panelExtent(), prefs.haptics(), new PanelHeaderView.Listener() {
            @Override public void begin() { cancelPanelAnimation(); panelPulling = false; panelDragging = true; }
            @Override public float currentProgress() { return panelProgress; }
            @Override public boolean cancelCloses() { return !panelTargetOpen; }
            @Override public void progress(float value) { setPanelProgress(value); }
            @Override public void finish(boolean close) { settlePanel(!close); }
        }) {
            @Override protected boolean blankTapDismissalEnabled() { return (page.equals("controls") || page.equals("notifications")) && controlEditor == null; }
        }; int padding = Ui.dp(screenContext, 6);
        panelContentPadding.set(content.x() + padding, contentTop + padding, panelFrame.width() - content.right() + padding, panelFrame.height() - content.bottom());
        panel.setBackgroundColor(Ui.BACKGROUND);
        populatePanel(page); return panel;
    }
    private void populatePanel(String page) {
        panel.removeAllViews();
        panel.setPadding(panelContentPadding.left, panelContentPadding.top, panelContentPadding.right, panelContentPadding.bottom);
        LinearLayout body = panel;
        body.setClipChildren(!page.equals("notifications")); body.setClipToPadding(!page.equals("notifications"));
        panels = new Panels(this); View contents = panels.build(page);
        LinearLayout header = page.equals("notifications") ? new NotificationForceHeader(screenContext) : page.equals("controls") ? new ControlHeaderView(screenContext) : Ui.row(screenContext); header.setTag("panel-header");
        String title = switch (page) { case "rotation" -> "旋转方向"; case "notifications" -> "通知中心"; case "media" -> "媒体控制"; default -> "控制中心"; };
        TextView heading = Ui.heading(screenContext, title, 17); heading.setMinimumHeight(Ui.dp(screenContext, 36)); heading.setGravity(Gravity.CENTER_VERTICAL); heading.setAccessibilityHeading(true);
        if (page.equals("notifications")) {
            heading.setSingleLine(); heading.setEllipsize(android.text.TextUtils.TruncateAt.END);
            FrameLayout titleArea = new FrameLayout(screenContext); titleArea.addView(heading, new FrameLayout.LayoutParams(-1, -1));
            TextView notice = (TextView) panels.notificationNotice(); notice.setSingleLine(); notice.setEllipsize(android.text.TextUtils.TruncateAt.END); titleArea.addView(notice, new FrameLayout.LayoutParams(-1, -1));
            panels.bindNotificationTitle(heading);
            header.addView(titleArea, new LinearLayout.LayoutParams(0, Ui.dp(screenContext, 36), 1));
            View settings = Ui.iconButton(screenContext, R.drawable.ic_ms_settings, "通知中心设置", this::openNotificationCenterSettings); settings.setTag("notification-center-settings"); settings.setBackground(Ui.ripple(screenContext, Ui.SURFACE, 18)); settings.setTooltipText("系统通知设置");
            LinearLayout.LayoutParams settingsSize = new LinearLayout.LayoutParams(Ui.dp(screenContext, PanelUi.SLOT), Ui.dp(screenContext, PanelUi.SLOT)); header.addView(settings, settingsSize);
            LinearLayout.LayoutParams clearSize = new LinearLayout.LayoutParams(-2, Ui.dp(screenContext, PanelUi.SLOT)); header.addView(panels.notificationClearAction(), clearSize);
        } else header.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        if (page.equals("controls")) { View edit = RuntimeVisuals.button(screenContext, R.drawable.ic_ms_edit, "编辑控制中心", this::editControls); edit.setTag("control-edit"); header.addView(edit); }
        // Adding is available inside the single editor entry.
        if (page.equals("controls")) {
            android.widget.ImageButton refresh = RuntimeVisuals.button(screenContext, R.drawable.ic_ms_refresh, "刷新状态", () -> { }); refresh.setTag("control-refresh");
            refresh.setOnClickListener(v -> { refresh.setEnabled(false); refresh.setActivated(true); refresh.setStateDescription("正在刷新"); refreshStates(true, () -> { refresh.setEnabled(true); refresh.setActivated(false); refresh.setStateDescription("刷新状态"); }); }); header.addView(refresh);
        }
        if (!page.equals("controls")) header.addView(RuntimeVisuals.button(screenContext, R.drawable.ic_ms_close, "关闭面板", this::closePanel)); LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2); headerParams.bottomMargin = Ui.dp(screenContext, 3); body.addView(header, headerParams);
        if (page.equals("controls") || page.equals("notifications")) for (int i=1;i<header.getChildCount();i++) {
            View action=header.getChildAt(i); android.view.ViewGroup.LayoutParams size=action.getLayoutParams();
            header.removeViewAt(i); header.addView(new PanelActionSlot(action),i,size);
        }
        if (header instanceof PanelActionHeader splitting) splitting.actions();
        if (page.equals("controls")) panels.bindControlHeader(header);
        Panels contentPanels = panels;
        ScrollView scroll = page.equals("notifications") ? new NotificationScrollView(screenContext) : new ScrollView(screenContext) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (page.equals("controls") && MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) contentPanels.controlViewportHeight(MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
                super.onMeasure(widthSpec, heightSpec);
            }
        }; scroll.setFillViewport(page.equals("controls")); scroll.setVerticalScrollBarEnabled(!page.equals("notifications")); scroll.addView(contents);
        body.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        feedback = Ui.text(screenContext, "", 11, Ui.MUTED); feedback.setVisibility(View.GONE); feedback.setMaxLines(2); body.addView(feedback);
        if (panelCard != null && page.equals("controls")) panelCard.refreshControlMotion();
    }
    void editControls() { editControls(null); }
    private void editControls(android.os.Bundle restored) {
        if (panel == null || !panelPage.equals("controls") || controlEditor != null) return;
        dismissDetails(); pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
        panel.removeAllViews(); panels = null; feedback = null;
        if (panelCard != null) panelCard.statusVisible(false);
        panel.setPadding(panelContentPadding.left, panelContentPadding.top, panelContentPadding.right, panelContentPadding.bottom);
        controlEditor = new ControlEditorView(screenContext, prefs, restored, id -> CoverApp.bridge(this).run("tile_add", -1, 0, ActionCatalog.component(id).flattenToString(), result -> message(result.message)), () -> {
            if (panel == null || controlEditor == null) return;
            screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(controlEditor.getWindowToken(), 0);
            controlEditor = null; populatePanel("controls"); if (panelCard != null) panelCard.statusVisible(true); panelEditorFocus(false); if (instance == this) refreshStates(true);
        });
        controlEditor.glass(panelGlass); panel.addView(controlEditor, new LinearLayout.LayoutParams(-1, 0, 1)); panelEditorFocus(true); controlEditor.requestFocus();
    }
    private void panelEditorFocus(boolean editing) {
        if (windows == null || panelHost == null || !(panelHost.getLayoutParams() instanceof WindowManager.LayoutParams layout)) return;
        if (editing || CoverApp.inputs(this).keyboardAvailable() || panelHost.hasPointerCapture()) layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; else layout.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layout.softInputMode = (editing ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        try { windows.updateViewLayout(panelHost, layout); if (panelEntry != null) windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { closePanel(); }
    }
    private void ensurePanelHost() {
        if (panelHost != null) return;
        panelHost = inputHost(); panelHost.setClipChildren(true); panelHost.setVisibility(View.INVISIBLE);
        WindowManager.LayoutParams layout = parameters(panelFrame); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        windows.addView(panelHost, layout);
    }
    void closePanel() { closePanel(false); }
    private void closePanel(boolean preserveOutgoing) {
        if (!preserveOutgoing) { finishHubPush(false); finishPanelPush(false); }
        panelMemoryFallback = false;
        if (panelGlass != null) { lastGlassDiagnostics = panelGlass.diagnostics(); if (panelCard == null) panelGlass.close(); panelGlass = null; }
        if (controlEditor != null) { screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(controlEditor.getWindowToken(), 0); controlEditor.cancelDrag(); controlEditor = null; }
        if (panelTintAnimation != null) panelTintAnimation.cancel(); panelTintAnimation = null; panelBackdrop = null;
        pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
        dismissDetails();
        if (panel != null || hub == null) { cancelPanelAnimation(); cancelSceneBootstrap(); } panelDragging = false; panelPulling = false; panelTargetOpen = false;
        if (Build.VERSION.SDK_INT >= 31 && blurListener != null && windows != null) windows.removeCrossWindowBlurEnabledListener(blurListener);
        blurListener = null;
        if (panelCard != null) { panelCard.release(); panelCard = null; }
        if (panelHost != null && windows != null) { if (!preserveOutgoing) panelHost.removeAllViews(); panelHost.setVisibility(preserveOutgoing && panelHost.getChildCount() > 0 ? View.VISIBLE : View.INVISIBLE); try { WindowManager.LayoutParams layout = (WindowManager.LayoutParams) panelHost.getLayoutParams(); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE; layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0; if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0); windows.updateViewLayout(panelHost, layout); } catch (RuntimeException ignored) { } }
        panel = null; feedback = null; panels = null; panelPage = ""; panelProgress = 1;
        syncCardStatus();
        if (panelEntry != null) panelEntry.panelVisible(false);
        if (panelEntry != null && windows != null) { try { windows.updateViewLayout(panelEntry, panelEntryParameters()); } catch (RuntimeException ignored) { } }
    }
    private void applyPanelBlur(WindowManager.LayoutParams layout, boolean supported) {
        if (panelCard != null) { panelCard.windowMaterial(layout, visualEffectsAllowed() && !panelDragging, supported); return; }
        if (panelGlass != null) {
            layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0;
            if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0);
            return;
        }
        boolean enabled = visualEffectsAllowed() && supported && !panelDragging && !panelPage.equals("notifications");
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
    private boolean visualEffectsAllowed() { return !panelMemoryFallback && Build.VERSION.SDK_INT >= 31 && prefs.panelBlur() && !screenContext.getSystemService(android.os.PowerManager.class).isPowerSaveMode(); }
    private void refreshBlurState() {
        if (screenContext == null || windows == null) return;
        if (!PanelGlassSession.allowed(screenContext, prefs)) {
            if (hubCard != null) { hubCard.dropGlass(); taskGlass = null; if (taskSurface != null) taskSurface.previewsVisible(); } else closeTaskGlass();
            if (panelCard != null) { panelCard.dropGlass(); panelGlass = null; }
            else if (panelGlass != null) { lastGlassDiagnostics = panelGlass.diagnostics(); panelGlass.close(); panelGlass = null; if (panelHost != null) panelHost.setVisibility(View.VISIBLE); }
        }
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
        return "外屏面板宿主硬件加速：" + accelerated + "\n详情本地模糊当前启用：" + detailBlurApplied + "\n" + (panelGlass == null ? lastGlassDiagnostics + "当前会话已释放 · 纹理 0B\n" : panelGlass.diagnostics()) + "多任务：\n" + (taskGlass == null ? lastTaskGlassDiagnostics + "当前会话已释放 · 纹理 0B\n" : taskGlass.diagnostics());
    }
    void showDetails(String id, View source) {
        if (panel == null || !(panel.getParent() instanceof FrameLayout host)) return;
        dismissDetails();
        if (panelGlass != null) panelGlass.beginModal(panelCard == null ? panel : panelCard);
        DetailSheet sheet = new DetailSheet(screenContext, ActionCatalog.label(screenContext, id), this::dismissDetails); details = sheet;
        DockGeometry.Box safe = panelCard == null ? placement.panel() : panelCard.localArea(placement.panel()); FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(safe.width(), safe.height()); position.leftMargin = safe.x(); position.topMargin = safe.y();
        host.addView(sheet, position); panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        setDetailBlur(true);
        if (windows != null && panelHost != null) { WindowManager.LayoutParams params = (WindowManager.LayoutParams) panelHost.getLayoutParams(); params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(panelHost, params); }
        detailContent = new ControlDetails(this, sheet); detailContent.build(id); sheet.enter(source instanceof Panels.Tile tile ? tile.face : source, panelGlass);
        if (prefs.haptics() && source != null) source.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
    }
    private void setDetailBlur(boolean enabled) {
        boolean active = enabled && panelGlass == null && panel != null && panel.isHardwareAccelerated() && visualEffectsAllowed();
        if (detailBlurApplied == active) return;
        detailBlurApplied = active;
        if (panel != null && Build.VERSION.SDK_INT >= 31) panel.setRenderEffect(active ? android.graphics.RenderEffect.createBlurEffect(Ui.dp(screenContext, 5), Ui.dp(screenContext, 5), android.graphics.Shader.TileMode.CLAMP) : null);
    }
    void dismissDetails() {
        if (detailContent != null) { detailContent.close(); detailContent = null; }
        if (details == null) return;
        if (panelGlass != null) panelGlass.endModal();
        if (details.getParent() instanceof android.view.ViewGroup host) host.removeView(details); details = null;
        setDetailBlur(false); if (panel != null) panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        if (windows != null && panelHost != null) try { WindowManager.LayoutParams params = (WindowManager.LayoutParams) panelHost.getLayoutParams(); if (!CoverApp.inputs(this).keyboardAvailable() && !panelHost.hasPointerCapture()) params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE; windows.updateViewLayout(panelHost, params); } catch (RuntimeException ignored) { }
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
    private int panelEdge() { return panelEntryPlacement == null ? DockGeometry.BOTTOM : panelEntryPlacement.edge(); }
    private float panelExtent() { return Math.max(1, panelFrame.height()); }
    private DockGeometry.Box hubWindowFrame() { return hubCard != null || hub != null && hub.showingTasks() ? panelFrame : hubFrame; }
    private float hubSceneExtent() {
        DockGeometry.Box frame = hubWindowFrame();
        return Math.max(1, switch (hubSceneEdge) { case DockGeometry.TOP -> frame.bottom() - panelFrame.y(); case DockGeometry.LEFT -> frame.right() - panelFrame.x(); case DockGeometry.RIGHT -> panelFrame.right() - frame.x(); default -> panelFrame.bottom() - frame.y(); });
    }
    private void setPanelProgress(float value) { setPanelMotion(value, value); }
    private void setPanelMotion(float value, float sourceProgress) {
        panelProgress = Math.max(0, Math.min(1, value)); panelSourceProgress = Math.max(0, Math.min(1, sourceProgress)); if (panel == null) return;
        float offset = (1 - panelProgress) * panelExtent();
        float bridge = motionFactor(panelCard);
        if (panelCard != null) panelCard.enter(panelProgress * bridge, panelEdge(), panelExtent()); else { panel.setTranslationX(0); panel.setTranslationY(panelEdge() == DockGeometry.TOP ? -offset : offset); }
        boolean ready = panelCard == null || panelCard.ready();
        if (hubPush != null && ready && panelHost != null && panelHost.getVisibility() == View.VISIBLE) {
            float travel = panelSourceProgress * bridge * panelExtent();
            if (hubPush.card() != null) { if (hubPush.card().push(travel, panelEdge(), panelCard)) finishHubPush(false); }
            else { hubPush.view().setTranslationY(hubPush.startY() + (panelEdge() == DockGeometry.TOP ? travel : -travel)); if (travel >= hubPush.exitDistance()) finishHubPush(false); }
        }
        if (ready && panelHost != null && panelHost.getVisibility() == View.VISIBLE) pushPanel(panelSourceProgress * bridge * panelExtent());
    }
    private void beginPanelPull(String page, float distance, float originY) {
        if (controlEditor != null || homeClosing) return;
        // An already open page is idempotent: no rebuild, loss of scroll, or jump to zero.
        if (panel != null && page.equals(panelPage) && !panelDragging && panelProgress >= 1) return;
        boolean continuing = panel != null && page.equals(panelPage);
        if (!continuing) showPanel(page, true);
        if (panel == null || !page.equals(panelPage)) return;
        panelPullCancelOpen = panelTargetOpen; cancelPanelAnimation(); panelDragging = true; panelPulling = true;
        panelPullFresh = !continuing;
        panelPullStartProgress = continuing ? panelProgress : 0; panelPullStartDistance = continuing ? distance : 0;
        panelPullSourceStartProgress = continuing ? panelSourceProgress : 0;
        panelBridgeOffset = continuing ? 0 : PanelDrag.entryStart(panelEdge(), originY, panelFrame.y(), panelExtent()); panelUserProgress = panelPullStartProgress; panelSourceUserProgress = panelPullSourceStartProgress;
        if (panelCard != null && panelCard.ready()) startSceneBootstrap(panelCard, !continuing);
    }
    private void pullPanel(String page, float distance) {
        if (panelPulling && page.equals(panelPage)) {
            panelUserProgress = PanelDrag.progress(panelPullStartProgress, distance - panelPullStartDistance, panelExtent());
            panelSourceUserProgress = PanelDrag.progress(panelPullSourceStartProgress, distance - panelPullStartDistance, panelExtent());
            setPanelMotion(panelUserProgress + panelBridgeOffset, panelSourceUserProgress);
        }
    }
    private void releasePanel(float distance, float velocity, boolean canceled) {
        if (!panelPulling || panel == null) return;
        if (!canceled) pullPanel(panelPage, distance);
        panelPulling = false;
        // Camera/Home clearance is a position offset, never user-travel toward a commit.
        float traveled = panelPullFresh ? distance : panelProgress * panelExtent();
        settlePanel(canceled ? panelPullCancelOpen : PanelDrag.shouldOpen(traveled, panelExtent(), velocity, screenContext.getResources().getDisplayMetrics().density));
    }
    private void cancelPanelAnimation() { if (panelAnimation != null) { panelAnimation.removeAllListeners(); panelAnimation.cancel(); panelAnimation = null; } }
    private void cancelSceneBootstrap() { if (sceneBootstrap != null) { sceneBootstrap.removeAllListeners(); sceneBootstrap.cancel(); } sceneBootstrap = null; bootstrapCard = null; bootstrapProgress = 0; }
    private float motionFactor(InterfaceCard card) { return card == null ? 1 : bootstrapCard == card && bootstrapMoves ? bootstrapProgress : card.ready() ? 1 : 0; }
    private void cropOutgoing(float value) {
        if (panelPush != null && panelPush.card() != null) panelPush.card().crop(value);
        if (hubPush != null && hubPush.card() != null) hubPush.card().crop(value);
    }
    private void prepareOutgoingCrop(boolean restoring) {
        InterfaceCard oldPanel = panelPush == null ? null : panelPush.card(), oldHub = hubPush == null ? null : hubPush.card();
        if (oldPanel != null) { if (restoring) oldPanel.beginRestoreCrop(); else oldPanel.beginExitCrop(); }
        if (oldHub != null) { if (restoring) oldHub.beginRestoreCrop(); else oldHub.beginExitCrop(); }
    }
    private void startSceneBootstrap(InterfaceCard incoming) { startSceneBootstrap(incoming, true); }
    private void startSceneBootstrap(InterfaceCard incoming, boolean bridgeMotion) {
        if (incoming == null || bootstrapCard == incoming) return;
        if (incoming == panelCard && !panelPulling && !panelTargetOpen) return;
        cancelSceneBootstrap(); bootstrapCard = incoming; bootstrapMoves = bridgeMotion; incoming.beginEntryCrop(); prepareOutgoingCrop(false);
        java.util.function.Consumer<Float> step = value -> {
            if (incoming != panelCard && incoming != hubCard) return;
            bootstrapProgress = value; incoming.crop(value);
            cropOutgoing(value);
            if (incoming == panelCard) setPanelMotion(panelProgress, panelSourceProgress);
            else if (hubSceneMotion) setHubSceneProgress(hubSceneProgress);
        };
        if (!ValueAnimator.areAnimatorsEnabled()) { step.accept(1f); return; }
        sceneBootstrap = InterfaceCard.initialMotion(); ValueAnimator opening = sceneBootstrap;
        opening.addUpdateListener(animation -> step.accept((float) animation.getAnimatedValue()));
        opening.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animation) { if (sceneBootstrap == opening) sceneBootstrap = null; } }); opening.start();
    }
    private void settlePanel(boolean open) {
        cancelPanelAnimation(); panelTargetOpen = open;
        boolean restoreCrop = !open && (panelPush != null || hubPush != null);
        float start = panelProgress * (open ? 1 : motionFactor(panelCard)), sourceStart = panelSourceProgress * (open ? 1 : motionFactor(panelCard)), target = open ? 1 : 0;
        if (!open) { cancelSceneBootstrap(); panelProgress = start; panelSourceProgress = sourceStart; }
        if (restoreCrop) prepareOutgoingCrop(true);
        Runnable settled = () -> {
            panelAnimation = null; panelDragging = false;
            if (!open) { cancelScenePush(); return; }
            finishHubPush(false); finishPanelPush(false);
            if (panelCard != null) panelCard.interactive(true);
            if (panelHost == null) return;
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) panelHost.getLayoutParams(); layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            if (CoverApp.inputs(this).keyboardAvailable()) layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            applyPanelBlur(layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
            try { windows.updateViewLayout(panelHost, layout); if (panelPage.equals("notifications")) main.post(updateNotifications); } catch (RuntimeException ignored) { closePanel(); }
        };
        if (!ValueAnimator.areAnimatorsEnabled()) { if (restoreCrop) cropOutgoing(1); setPanelProgress(open ? 1 : 0); settled.run(); return; }
        panelAnimation = InterfaceCard.motion(0, 1);
        panelAnimation.addUpdateListener(animation -> { float fraction = (float) animation.getAnimatedValue(); if (restoreCrop) cropOutgoing(fraction); setPanelMotion(start + (target - start) * fraction, sourceStart + (target - sourceStart) * fraction); });
        panelAnimation.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animator) { settled.run(); } }); panelAnimation.start();
    }
    private void showHub() {
        showHub(true);
    }
    private WindowManager.LayoutParams hubParameters(boolean expanded) {
        // Keep one safe-area window: the transparent region dismisses a temporary Dock,
        // and expanding the catalog does not move or replace its application row.
        WindowManager.LayoutParams layout = parameters(hubWindowFrame());
        layout.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layout.softInputMode = (expanded ? WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE : WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING) | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        applyHubBlur(hub, layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled());
        return layout;
    }
    void applyHubBlur(AppHubView target, WindowManager.LayoutParams layout, boolean supported) {
        if (hubCard != null) { hubCard.windowMaterial(layout, visualEffectsAllowed(), supported); target.setBackdropBlur(false); return; }
        if (target.showingTasks()) {
            target.setBackdropBlur(false);
            layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0;
            if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0);
            return;
        }
        boolean expanded = target.expanded(), enabled = expanded && visualEffectsAllowed() && supported;
        target.setBackdropBlur(enabled);
        if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(enabled ? Math.min(100, Ui.dp(screenContext, 32)) : 0);
        if (enabled) layout.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND; else layout.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        if (expanded) { layout.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = enabled ? .18f : .32f; }
        else { layout.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND; layout.dimAmount = 0; }
    }
    private void taskPageChanged(RecentTasksView page) {
        if (hubCard != null) {
            taskSurface = page; if (page != null) { hubCard.contentSafeBounds(hubFrame); page.safeArea(hubCard.localArea(hubCard.contentArea(hubFrame)), hubCard.localFrame()); }
            taskGlass = page == null ? null : hubCard.glass();
            if (page != null && hubCard.ready()) page.previewsVisible();
            return;
        }
        closeTaskGlass(); taskSurface = page;
        if (hub == null || hubHost == null || windows == null) return;
        if (page != null) { hub.prepareTaskEntrance(); page.safeArea(hubFrame, panelFrame); }
        try { windows.updateViewLayout(hubHost, hubParameters(hub.expanded())); } catch (RuntimeException failure) { removeHubImmediately(); return; }
        if (page == null) return;
        Display selected = Displays.selected(this, prefs);
        if (selected != null && display != null && selected.getDisplayId() == display.getDisplayId() && display.getDisplayId() != Display.DEFAULT_DISPLAY && display.getState() == Display.STATE_ON && PanelGlassSession.allowed(screenContext, prefs)) {
            PanelGlassSession opening = new PanelGlassSession(screenContext, display); opening.captureArea(new android.graphics.Rect(hubFrame.x(), hubFrame.y(), hubFrame.right(), hubFrame.bottom())); taskGlass = opening;
            AppHubView owner = hub; FrameLayout host = hubHost;
            host.setVisibility(View.INVISIBLE); refreshBlurState();
            opening.attach(page, true);
            opening.capture(this, () -> taskGlass == opening && taskSurface == page && hub == owner && hubHost == host && owner.isAttachedToWindow() && !owner.closing() && display != null && opening.matches(display) && glassTargetSelected(opening) && display.getState() == Display.STATE_ON && PanelGlassSession.allowed(screenContext, prefs), () -> {
                host.setVisibility(View.VISIBLE); owner.revealTasks(page); lastTaskGlassDiagnostics = opening.diagnostics();
            });
        } else { refreshBlurState(); hub.revealTasks(page); }
    }
    private void closeTaskGlass() {
        if (taskGlass != null) { lastTaskGlassDiagnostics = taskGlass.diagnostics(); if (hubCard != null && hubCard.glass() == taskGlass) hubCard.dropGlass(); else taskGlass.close(); taskGlass = null; }
        if (taskSurface != null) { taskSurface.glass(null, hubCard == null); taskSurface.previewsVisible(); }
        if (hubHost != null) hubHost.setVisibility(View.VISIBLE);
    }
    private void showHub(boolean expandedInitially) { showHub(expandedInitially, false); }
    private void showHub(boolean expandedInitially, boolean tasksInitially) {
        if (windows == null || dock == null || homeClosing) return;
        int sceneEdge = tasksInitially ? DockGeometry.BOTTOM : expandedInitially ? panelEdge() : DockGeometry.BOTTOM;
        if (panel != null) beginPanelPush(sceneEdge); else closePanel();
        beginHubPush(sceneEdge);
        hubSceneMotion = true;
        hubSceneEdge = sceneEdge; hubSceneProgress = 0;
        AppHubView[] owner = {null};
        hub = new AppHubView(screenContext, prefs, new AppHubView.Listener() {
            @Override public void action(String id) { if (hub != owner[0]) return; if (!id.startsWith("app:") && !java.util.Set.of("app_dock", "app_hub", "recents", "back").contains(id)) dismissHub(); act(id); }
            @Override public void editFavorites() { openSettings("favorites"); }
            @Override public void editPinned() { openSettings("hub_pin"); }
            @Override public void settings() { openSettings("main"); }
            @Override public void applicationSettings(String id, boolean uninstall) {
                android.content.ComponentName component = ActionCatalog.component(id); if (component == null) return;
                Intent intent = new Intent(uninstall ? Intent.ACTION_DELETE : android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", component.getPackageName(), null)); launch(intent);
            }
            @Override public void close() { if (hub == owner[0]) dismissHub(); }
            @Override public void beginDismissal() { if (hub == owner[0]) { cancelPanelAnimation(); cancelSceneBootstrap(); hubSceneMotion = false; } }
            @Override public void refreshRecents() { if (hub == owner[0]) requestHubTasks(null); }
            @Override public void clearRecents(java.util.List<RecentTasks.Task> tasks) { if (hub == owner[0]) requestHubTasks(tasks); }
            @Override public void clearTaskPage(java.util.List<RecentTasks.Task> tasks) { if (hub == owner[0]) clearHubTaskPage(tasks); }
            @Override public void emptyTaskPage() {
                if (hub != owner[0]) return;
                AppHubView target = hub;
                if (target != null && target.showingTasks()) target.dismissForTaskClear(() -> { if (hub == target) removeHubImmediately(); });
            }
            @Override public void closeTask(RecentTasks.Task task) { if (hub == owner[0]) requestHubTasks(java.util.List.of(task), true); }
            @Override public void openTask(RecentTasks.Task task) { if (hub == owner[0]) openHubTask(task); }
            @Override public boolean managesTaskEntrance() { return true; }
            @Override public void taskPageChanged(RecentTasksView page) { if (hub == owner[0]) CoverService.this.taskPageChanged(page); }
            @Override public boolean navigateTaskPage(boolean tasks) {
                if (hub != owner[0] || hubHost == null || !hubHost.isAttachedToWindow() || hub.closing()) return false;
                showHub(true, tasks); return true;
            }
            @Override public void snapshot(RecentTasks.Task task, java.util.function.Consumer<ShizukuBridge.Snapshot> callback) { if (hub == owner[0]) requestTaskSnapshot(task, callback); else callback.accept(new ShizukuBridge.Snapshot(null, "页面已关闭")); }
            @Override public void expand(boolean expanded) {
                if (hub != owner[0] || hub == null || hubHost == null || !hubHost.isAttachedToWindow()) return;
                if (hubCard != null) { hubCard.plateVisible(expanded || hub.showingTasks()); prepareHubCard(hubCard, hub); }
                WindowManager.LayoutParams layout = hubParameters(expanded);
                if (!expanded) screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(hub.getWindowToken(), 0);
                try { windows.updateViewLayout(hubHost, layout); } catch (RuntimeException e) { removeHubImmediately(); }
            }
        });
        owner[0] = hub;
        // Let the fixed dock key receive its complete click before toggling the hub.
        // ACTION_OUTSIDE on DOWN would close it early, then UP would reopen it.
        hub.setExpanded(expandedInitially); if (tasksInitially) hub.showTasks(true);
        attachHubContent(hub, hubHosts[0].getChildCount() == 0 ? hubHosts[0] : hubHosts[1]);
        hubCard.prepareEntry();
        setHubSceneProgress(0);
        try {
            hubHost.setVisibility(View.VISIBLE);
            windows.updateViewLayout(hubHost, hubParameters(expandedInitially)); syncHubEntry();
            if (tasksInitially) taskPageChanged(hub.taskPage());
            prepareHubCard(hubCard, hub);
            if (hubCard.ready() && panelAnimation == null) settleHubScene(true);
            if (Build.VERSION.SDK_INT >= 31 && prefs.panelBlur()) {
                AppHubView observed = hub; FrameLayout observedHost = hubHost; WindowManager observedWindows = windows; hubBlurWindows = observedWindows;
                hubBlurListener = enabled -> {
                    if (hub != observed || hubHost != observedHost || windows != observedWindows || !(observedHost.getLayoutParams() instanceof WindowManager.LayoutParams layout)) return;
                    applyHubBlur(observed, layout, enabled);
                    try { observedWindows.updateViewLayout(observedHost, layout); } catch (RuntimeException ignored) { }
                };
                observedWindows.addCrossWindowBlurEnabledListener(getMainExecutor(), hubBlurListener);
            }
        } catch (RuntimeException e) { cancelScenePush(); message("应用中心暂不可用"); }
    }
    FrameLayout attachHubContent(AppHubView content) {
        return attachHubContent(content, inputHost());
    }
    private FrameLayout attachHubContent(AppHubView content, FrameLayout host) {
        // Match the panel host structure: move/fade a child while the window's
        // physical bounds and input coordinate space stay fixed until completion.
        content.cardHosted();
        hubCard = new InterfaceCard(screenContext, prefs, content.showingTasks() ? InterfaceCard.TASKS : InterfaceCard.LAUNCHER, content, content::preparePanelPush, content::dispose);
        hubCard.plateVisible(content.expanded() || content.showingTasks());
        if (content.showingTasks()) hubCard.contentOwnsInsets();
        if (hubFrame != null && panelFrame != null) {
            hubCard.frameBounds(panelFrame, cardSafeBounds());
            if (!content.showingTasks()) hubCard.contentBounds(hubFrame, panelFrame);
        }
        hubCard.statusBounds(controlStatusBox);
        syncCardStatus();
        hubHost = host; hubHost.setClipChildren(true); hubHost.addView(hubCard); return hubHost;
    }
    private void ensureHubHosts() {
        // Mount below the chrome once. Two slots retain only the current and
        // departing cards, without recreating the status/shortcut surfaces.
        for (int i = 0; i < hubHosts.length; i++) {
            if (hubHosts[i] != null) continue;
            FrameLayout host = inputHost(); hubHosts[i] = host;
            host.setClipChildren(true); host.setVisibility(View.INVISIBLE);
            WindowManager.LayoutParams layout = parameters(panelFrame); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
            layout.setTitle("外屏启动器宿主 " + i); windows.addView(host, layout);
        }
    }
    private void clearHubHost(FrameLayout host) {
        host.removeAllViews();
        if (host != hubHosts[0] && host != hubHosts[1]) { try { windows.removeViewImmediate(host); } catch (RuntimeException ignored) { } return; }
        host.setVisibility(View.INVISIBLE);
        WindowManager.LayoutParams layout = parameters(panelFrame); layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try { windows.updateViewLayout(host, layout); } catch (RuntimeException ignored) { }
    }
    private void prepareHubCard(InterfaceCard card, AppHubView content) {
        if (card == null || hub != content) return;
        card.prepareGlass(this, display, prefs, () -> hub == content && !content.closing() && hubCard == card && display != null && card.glass() != null && card.glass().matches(display) && glassTargetSelected(card.glass()) && display.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked(), () -> {
            if (hub != content || content.closing() || hubCard != card) return;
            startSceneBootstrap(card);
            taskGlass = content.showingTasks() ? card.glass() : null;
            if (content.taskPage() != null) content.taskPage().previewsVisible();
            content.revealSidebar();
            if (hubSceneMotion) { setHubSceneProgress(hubSceneProgress); if (hubSceneProgress < 1 && panelAnimation == null) settleHubScene(true); }
        }, hubPush != null || panelPush != null);
        if (hub == content && content.showingTasks()) taskGlass = card.glass();
        if (card.ready()) { startSceneBootstrap(card); content.revealSidebar(); }
    }
    private void dismissHub() {
        appLaunchGeneration++;
        if (homeClosing) return;
        if (hub == null) return; AppHubView target = hub;
        if (hubCard != null && target.expanded()) {
            boolean following = target.followingDismissal();
            finishPanelPush(false); finishHubPush(false); target.preparePanelPush(); hubCard.interactive(false); hubSceneMotion = true;
            if (target.showingTasks() || following) hubSceneEdge = DockGeometry.BOTTOM;
            hubSceneProgress = Math.max(0, Math.min(1, 1 - InterfaceCard.sign(hubSceneEdge) * InterfaceCard.position(hubCard, hubSceneEdge) / hubSceneExtent()));
            settleHubScene(false); return;
        }
        target.dismiss(() -> { if (hub == target) removeHubImmediately(); });
    }
    private void beginPanelPush(int edge) {
        if (panel == null) return;
        finishHubPush(false); finishPanelPush(false); cancelPanelAnimation(); cancelSceneBootstrap(); dismissDetails();
        ControlEditorView outgoingEditor = controlEditor;
        if (controlEditor != null) { controlEditor.cancelDrag(); controlEditor = null; }
        if (panelTintAnimation != null) panelTintAnimation.cancel(); panelTintAnimation = null;
        pendingBrightness = null; brightnessSequence++; main.removeCallbacks(drainBrightness);
        if (Build.VERSION.SDK_INT >= 31 && blurListener != null && windows != null) windows.removeCrossWindowBlurEnabledListener(blurListener);
        float startY = panelCard == null ? panel.getTranslationY() : panelCard.getTranslationY();
        float visibleProgress = panelCard == null ? panelProgress : 1 - Math.min(1, Math.abs(startY) / panelExtent());
        panelPush = new PanelPush(panel, panelCard, panels, feedback, panelPage, panelGlass, panelMemoryFallback, blurListener, new android.graphics.Rect(panelContentPadding), startY, Math.max(1, panelExtent() + (edge == DockGeometry.TOP ? -startY : startY)), edge, visibleProgress, outgoingEditor);
        if (panelCard != null) panelCard.beginExit(edge, panelExtent()); else if (panel instanceof PanelSurface surface) surface.prepareScenePush();
        panel = null; panelCard = null; panels = null; feedback = null; panelGlass = null; panelBackdrop = null; blurListener = null; panelPage = "";
        panelPulling = panelDragging = false; panelMemoryFallback = false;
        if (panelHost != null && windows != null) try {
            WindowManager.LayoutParams layout = (WindowManager.LayoutParams) panelHost.getLayoutParams();
            layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0;
            if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0); windows.updateViewLayout(panelHost, layout);
        } catch (RuntimeException ignored) { }
    }
    private void pushPanel(float distance) {
        PanelPush outgoing = panelPush; if (outgoing == null) return;
        if (outgoing.card() != null) { if (outgoing.card().push(distance, outgoing.edge(), panelCard != null ? panelCard : hubCard)) finishPanelPush(false); }
        else { outgoing.view().setTranslationY(outgoing.startY() + (outgoing.edge() == DockGeometry.TOP ? distance : -distance)); if (distance >= outgoing.exitDistance()) finishPanelPush(false); }
    }
    private void finishPanelPush(boolean restore) {
        PanelPush outgoing = panelPush; if (outgoing == null) return; panelPush = null;
        if (restore) {
            panel = outgoing.view(); panelCard = outgoing.card(); panels = outgoing.contents(); feedback = outgoing.feedback(); panelPage = outgoing.page();
            controlEditor = outgoing.editor();
            panelGlass = outgoing.glass(); panelMemoryFallback = outgoing.fallback(); panelContentPadding.set(outgoing.padding());
            panelBackdrop = panel.getBackground() instanceof android.graphics.drawable.ColorDrawable color ? color : null;
            blurListener = outgoing.blurListener(); panelProgress = panelSourceProgress = outgoing.progress(); panelTargetOpen = true; panelPulling = panelDragging = false;
            if (panelCard != null) panelCard.restore(); else panel.setTranslationY(outgoing.startY());
            panel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            if (Build.VERSION.SDK_INT >= 31 && blurListener != null) windows.addCrossWindowBlurEnabledListener(getMainExecutor(), blurListener);
            if (panelHost != null) { panelHost.setVisibility(View.VISIBLE); WindowManager.LayoutParams layout = parameters(panelFrame); applyPanelBlur(layout, Build.VERSION.SDK_INT >= 31 && windows.isCrossWindowBlurEnabled()); try { windows.updateViewLayout(panelHost, layout); } catch (RuntimeException ignored) { closePanel(); } }
            if (controlEditor != null) panelEditorFocus(true);
            syncCardStatus();
            if (panelEntry != null) panelEntry.panelVisible(true);
            return;
        }
        if (outgoing.card() != null) outgoing.card().release();
        else { if (outgoing.glass() != null && outgoing.glass() != panelGlass) outgoing.glass().close(); if (outgoing.view().getParent() instanceof android.view.ViewGroup parent) parent.removeView(outgoing.view()); outgoing.view().removeAllViews(); }
        if (panel == null && panelHost != null && panelHost.getChildCount() == 0) { panelHost.setVisibility(View.INVISIBLE); if (panelEntry != null) panelEntry.panelVisible(false); }
        syncCardStatus();
    }
    private void cancelScenePush() {
        // An already removed outgoing card is never reconstructed during rollback.
        cancelPanelAnimation(); cancelSceneBootstrap();
        if (panelPush != null && panelPush.card() == null && panelGlass == panelPush.glass()) panelGlass = null;
        closePanel(true); removeHubImmediately();
        finishHubPush(true); finishPanelPush(true); syncHubEntry();
        if (hub != null && hubCard != null && hubSceneProgress < 1) { startSceneBootstrap(hubCard, false); settleHubScene(true); }
        else if (panel != null && panelCard != null && panelProgress < 1) { startSceneBootstrap(panelCard, false); settlePanel(true); }
    }
    private void setHubSceneProgress(float value) {
        hubSceneProgress = Math.max(0, Math.min(1, value)); if (hub == null) return;
        float bridge = motionFactor(hubCard);
        if (hubCard != null) { hubCard.enter(hubSceneProgress * bridge, hubSceneEdge, hubSceneExtent()); if (!hubCard.ready()) return; if (hub.taskPage() != null) hub.taskPage().sceneMotion(hubCard.getTranslationX(), hubCard.getTranslationY()); }
        else hub.followScenePush(hubSceneProgress, hubSceneEdge, hubSceneExtent());
        float travel = hubSceneProgress * bridge * hubSceneExtent(); pushPanel(travel);
        if (hubPush != null) {
            if (hubPush.card() != null) { if (hubPush.card().push(travel, hubSceneEdge, hubCard)) finishHubPush(false); }
            else { hubPush.view().setTranslationY(hubPush.startY() + (hubSceneEdge == DockGeometry.TOP ? travel : -travel)); if (travel >= hubPush.exitDistance()) finishHubPush(false); }
        }
    }
    private void settleHubScene(boolean open) {
        cancelPanelAnimation(); AppHubView incoming = hub; if (incoming == null) return;
        boolean restoreCrop = !open && (panelPush != null || hubPush != null);
        if (!open) { hubSceneProgress *= motionFactor(hubCard); cancelSceneBootstrap(); }
        if (restoreCrop) prepareOutgoingCrop(true);
        Runnable settled = () -> {
            panelAnimation = null; if (hub != incoming) return;
            if (!open) { cancelScenePush(); return; }
            finishPanelPush(false); finishHubPush(false);
            if (hubCard != null) hubCard.interactive(true);
            incoming.revealSidebar();
            if (hubHost != null) try { windows.updateViewLayout(hubHost, hubParameters(hub.expanded())); } catch (RuntimeException failure) { removeHubImmediately(); }
            syncHubEntry();
        };
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) { if (restoreCrop) cropOutgoing(1); setHubSceneProgress(open ? 1 : 0); settled.run(); return; }
        panelAnimation = InterfaceCard.motion(hubSceneProgress, open ? 1 : 0);
        float start = hubSceneProgress;
        panelAnimation.addUpdateListener(animation -> { if (hub == incoming) { float progress = (float) animation.getAnimatedValue(); if (restoreCrop) cropOutgoing(start == 0 ? 1 : 1 - progress / start); setHubSceneProgress(progress); } });
        panelAnimation.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animation) { settled.run(); } }); panelAnimation.start();
    }
    private void beginHubPush() { beginHubPush(panelEdge()); }
    private void beginHubPush(int edge) {
        if (hub == null || hubHost == null) return;
        if (openingTaskHub == hub) openingTaskHub = null;
        cancelPanelAnimation(); cancelSceneBootstrap();
        WindowManager.LayoutParams layout = hubParameters(hub.expanded());
        float startY = hubCard == null ? hub.getTranslationY() : hubCard.getTranslationY();
        float height = Math.max(1, hubHost.getHeight() > 0 ? hubHost.getHeight() : layout.height);
        float visibleProgress = hubCard == null ? hubSceneProgress : 1 - Math.min(1, Math.abs(InterfaceCard.position(hubCard, hubSceneEdge)) / hubSceneExtent());
        hubPush = new HubPush(hub, hubHost, hubCard, hubCard == null ? taskGlass : hubCard.glass(), taskSurface, hubBlurListener, hubBlurWindows, startY, edge == DockGeometry.TOP ? height - startY : height + startY, visibleProgress, hubSceneEdge, hubSceneMotion);
        if (hubCard != null) hubCard.beginExit(edge, height);
        if (Build.VERSION.SDK_INT >= 31 && hubBlurListener != null && hubBlurWindows != null) try { hubBlurWindows.removeCrossWindowBlurEnabledListener(hubBlurListener); } catch (RuntimeException ignored) { }
        hubBlurListener = null; hubBlurWindows = null; hub = null; hubHost = null; hubCard = null; taskGlass = null; taskSurface = null;
        if (hubPush.card() == null) hubPush.view().preparePanelPush();
        layout.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
        layout.flags &= ~(WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND); layout.dimAmount = 0;
        if (Build.VERSION.SDK_INT >= 31) layout.setBlurBehindRadius(0);
        try { windows.updateViewLayout(hubPush.host(), layout); } catch (RuntimeException failure) { finishHubPush(false); }
        syncHubEntry();
    }
    private void finishHubPush(boolean restore) {
        HubPush outgoing = hubPush; if (outgoing == null) return; hubPush = null;
        if (restore) {
            hub = outgoing.view(); hubHost = outgoing.host(); hubCard = outgoing.card(); taskGlass = outgoing.tasks() == null ? null : outgoing.glass(); taskSurface = outgoing.tasks();
            hubSceneProgress = outgoing.progress(); hubSceneEdge = outgoing.entryEdge(); hubSceneMotion = outgoing.sceneMotion();
            hubBlurListener = outgoing.blurListener(); hubBlurWindows = outgoing.blurWindows();
            if (Build.VERSION.SDK_INT >= 31 && hubBlurListener != null && hubBlurWindows != null) try { hubBlurWindows.addCrossWindowBlurEnabledListener(getMainExecutor(), hubBlurListener); } catch (RuntimeException ignored) { }
            if (hubCard != null) hubCard.restore(); else hub.setTranslationY(outgoing.startY()); hub.reopen();
            try { windows.updateViewLayout(hubHost, hubParameters(hub.expanded())); } catch (RuntimeException failure) { removeHubImmediately(); }
            syncHubEntry();
            syncCardStatus();
            return;
        }
        if (outgoing.card() != null) outgoing.card().release();
        else { if (outgoing.glass() != null) { lastTaskGlassDiagnostics = outgoing.glass().diagnostics(); outgoing.glass().close(); } outgoing.view().dispose(); }
        clearHubHost(outgoing.host());
        if (outgoing.tasks() != null) resumeTaskInsets();
        syncCardStatus();
    }
    private void removeHubImmediately() {
        if (hub != null) { cancelPanelAnimation(); cancelSceneBootstrap(); }
        if (hubCard == null) closeTaskGlass(); else taskGlass = null; taskSurface = null;
        if (Build.VERSION.SDK_INT >= 31 && hubBlurListener != null && hubBlurWindows != null) try { hubBlurWindows.removeCrossWindowBlurEnabledListener(hubBlurListener); } catch (RuntimeException ignored) { }
        hubBlurListener = null; hubBlurWindows = null;
        boolean removedTasks = hub != null && hub.showingTasks();
        AppHubView removed = hub; FrameLayout host = hubHost; InterfaceCard card = hubCard; hub = null; hubHost = null; hubCard = null;
        if (openingTaskHub == removed) openingTaskHub = null;
        if (removed != null) { screenContext.getSystemService(android.view.inputmethod.InputMethodManager.class).hideSoftInputFromWindow(removed.getWindowToken(), 0); if (card != null) card.release(); else removed.dispose(); }
        if (host != null) clearHubHost(host);
        if (removedTasks) resumeTaskInsets();
        syncHubEntry();
        syncCardStatus();
    }
    private void syncCardStatus() {
        if (statusBar == null) return;
        boolean own = panelCard != null && panelCard.definition().showStatusBar() || hubCard != null && hubCard.definition().showStatusBar()
            || panelPush != null && panelPush.card() != null && panelPush.card().definition().showStatusBar()
            || hubPush != null && hubPush.card() != null && hubPush.card().definition().showStatusBar();
        boolean appHidden = statusVisibility.hidden();
        int visibility = own || appHidden ? View.INVISIBLE : View.VISIBLE;
        statusBar.setAlpha(1); if (statusBar.getVisibility() != visibility) statusBar.setVisibility(visibility);
    }
    private void requestHubTasks(java.util.List<RecentTasks.Task> clearing) {
        requestHubTasks(clearing, false);
    }
    private void clearHubTaskPage(java.util.List<RecentTasks.Task> tasks) {
        AppHubView target = hub; Display selected = Displays.selected(this, prefs);
        if (target == null || target.closing() || target.recentBusy() || !target.showingTasks() || !target.isAttachedToWindow() || selected == null) return;
        int displayId = selected.getDisplayId();
        if (!currentTaskDisplay(displayId)) { target.recentFailure("目标外屏不可用"); return; }
        java.util.List<RecentTasks.Task> requested = AppRecentTasks.clearTargets(this, prefs, tasks, true).stream().filter(task -> task.displayId() == displayId).collect(java.util.stream.Collectors.toList());
        if (requested.isEmpty()) { message("没有可清理的未锁定任务"); return; }
        target.recentBusy(true);
        invalidateClosingTaskBackground(requested);
        if (prefs.haptics()) target.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM);
        AppRecentTasks.clearPage(this, prefs, displayId, requested, () -> hub == target && !target.closing() && target.isAttachedToWindow() && currentTaskDisplay(displayId), result -> {
            if (hub != target || target.closing()) return;
            if (result.unchanged()) { target.recentBusy(false); message(result.message()); return; }
            if (!result.ok()) { target.recentFailure(result.message()); message(result.message()); return; }
            AppRecentTasks.Snapshot snapshot = result.snapshot(); target.recentCapabilities(snapshot.canOpen(), snapshot.canClear(), snapshot.canSnapshot()); target.recentResult(snapshot.tasks(), result.message(), snapshot.complete());
            invalidateRemovedTaskBackground(requested, snapshot);
            target.dismissForTaskClear(() -> { if (hub == target) removeHubImmediately(); });
            message(result.message());
        });
    }
    private void requestHubTasks(java.util.List<RecentTasks.Task> clearing, boolean explicitTask) {
        AppHubView target = hub; Display selected = Displays.selected(this, prefs);
        if (target == null || target.closing() || !target.isAttachedToWindow() || target.recentBusy()) return;
        if (display == null || selected == null || selected.getDisplayId() != display.getDisplayId() || selected.getDisplayId() <= 0 || selected.getState() != Display.STATE_ON || getSystemService(KeyguardManager.class).isKeyguardLocked()) { target.recentResult(java.util.List.of(), null); target.recentFailure("目标外屏不可用"); return; }
        int displayId = selected.getDisplayId();
        boolean clearingTasks = clearing != null;
        target.recentBusy(true);
        java.util.function.BooleanSupplier active = () -> hub == target && !target.closing() && target.isAttachedToWindow();
        java.util.function.Consumer<AppRecentTasks.Response> complete = result -> {
            if (hub != target) return;
            if (result.unchanged()) { target.recentBusy(false); message(result.message()); return; }
            if (!result.ok()) { target.recentFailure(result.message()); if (clearingTasks) message(result.message()); return; }
            AppRecentTasks.Snapshot snapshot = result.snapshot(); target.recentCapabilities(snapshot.canOpen(), snapshot.canClear(), snapshot.canSnapshot()); target.recentResult(snapshot.tasks(), result.message(), snapshot.complete());
            if (explicitTask) invalidateRemovedTaskBackground(clearing, snapshot);
        };
        if (explicitTask) { invalidateClosingTaskBackground(clearing); AppRecentTasks.dismiss(this, prefs, clearing.get(0), active, complete); }
        else AppRecentTasks.request(this, prefs, displayId, clearing, active, complete);
    }
    private void invalidateClosingTaskBackground(java.util.List<RecentTasks.Task> requested) {
        if (taskGlass == null || hub == null || !hub.showingTasks()) return;
        // Retire the source before the system exposes its replacement, including slow replies.
        for (RecentTasks.Task task : requested) if (task.visible() && task.displayId() == taskGlass.displayId) { taskGlass.invalidateSource(); return; }
    }
    private void invalidateRemovedTaskBackground(java.util.List<RecentTasks.Task> requested, AppRecentTasks.Snapshot snapshot) {
        if (taskGlass == null || hub == null || !hub.showingTasks() || !snapshot.complete()) return;
        for (RecentTasks.Task removed : requested) if (removed.visible() && snapshot.tasks().stream().noneMatch(task -> RecentTasks.sameTask(removed, task))) { taskGlass.invalidateSource(); return; }
    }
    private boolean currentTaskDisplay(int id) {
        Display selected = Displays.selected(this, prefs);
        return instance == this && prefs.enabled() && id > 0 && display != null && display.getDisplayId() == id && selected != null && selected.getDisplayId() == id && selected.getState() == Display.STATE_ON && !getSystemService(KeyguardManager.class).isKeyguardLocked();
    }
    private void openHubTask(RecentTasks.Task task) {
        AppHubView target = hub;
        if (target == null || target.closing() || !target.isAttachedToWindow() || target.recentBusy()) return;
        if (!currentTaskDisplay(task.displayId())) { target.recentFailure("目标外屏已改变"); return; }
        openingTaskHub = target;
        target.recentBusy(true);
        AppRecentTasks.open(this, prefs, task, () -> hub == target && openingTaskHub == target && !target.closing() && target.isAttachedToWindow(), result -> finishHubTask(target, task, result));
    }
    void finishHubTask(AppHubView target, RecentTasks.Task task, ShizukuBridge.Result result) {
        if (openingTaskHub == target) openingTaskHub = null;
        if (hub != target) return;
        scheduleDisplay();
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
        main.removeCallbacks(updateNativeInput); if (nativeInput != null) nativeInput.close(); nativeHostVisible = false;
        homeClosing = false;
        main.removeCallbacks(settleLauncherEntry); launcherEntryPending = false;
        CoverApp.widgets(this).safeArea(null, 0, 0, null);
        if (systemControlsToast != null) { systemControlsToast.cancel(); systemControlsToast = null; }
        removeHubImmediately(); closePanel();
        for (int i = 0; i < hubHosts.length; i++) {
            if (hubHosts[i] != null) { try { windows.removeViewImmediate(hubHosts[i]); } catch (RuntimeException ignored) { } hubHosts[i] = null; }
        }
        if (panelHost != null) { try { windows.removeViewImmediate(panelHost); } catch (RuntimeException ignored) { } panelHost = null; }
        if (statusBar != null) { try { windows.removeViewImmediate(statusBar); } catch (RuntimeException ignored) { } statusBar = null; }
        if (panelEntry != null) { PanelEntryView removed = panelEntry; panelEntry = null; try { windows.removeViewImmediate(removed); } catch (RuntimeException ignored) { } }
        statusBox = null; controlStatusBox = null; cardSafeFrame = null;
        DockView removed = dock; dock = null;
        if (removed != null) { savedPage = removed.page(); try { windows.removeViewImmediate(removed); } catch (RuntimeException ignored) { } }
        panelEntryPlacement = null; hubFrame = null;
    }
    String windowDiagnostics() {
        String entry = panelEntry != null && panelEntry.isAttachedToWindow() && panelEntryPlacement != null
            ? (panelEntryPlacement.vertical() ? "上条通知／下条控制中心" : "左条通知／右条控制中心") + " · " + (panelEntryPlacement.measured() ? "系统缺口" : "校准估计，需本机确认") + " · " + panelEntryPlacement.touch() : "未挂载";
        return "系统导航边缘：" + navigationInsetPixels + "px\n导航避让：" + prefs.avoidNavigation() + "，额外内移 " + prefs.navigationGap() + "dp\n" + "快捷栏窗口：" + (dock == null ? "未创建" : dock.isAttachedToWindow() ? "已挂载" : "已脱离，等待恢复") + "\n双白条入口：" + entry + "\n显示模式：" + (dock != null && dock.compact() ? "快捷按钮已隐藏" : "快捷按钮") + "\n外屏键盘：" + (keyboardVisible == null ? "未知" : keyboardVisible ? "已检测到" : "未检测到") + "\n最近亮屏／解锁事件：" + lastScreenEvent + "\n最近应用中心恢复检查：" + lastLauncherRecovery + "\n";
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
        if (readBrightness && panelPage.equals("controls") && panels != null && panels.hasBrightness() && display != null && !CoverApp.bridge(this).busy() && pendingBrightness == null && activeBrightness == null) {
            Panels target = panels; int selected = display.getDisplayId();
            if (requestBrightness(target, -1, () -> {
                if (instance == this && panels == target && currentTaskDisplay(selected) && !CoverApp.bridge(this).busy()) refreshStates(false, finished);
                else finished.run();
            })) return;
        }
        CoverApp.bridge(this).run("states", -1, 0, "", result -> {
            if (instance != this) return;
            if (result.ok) try {
                JSONObject values = new JSONObject(result.output);
                for (String key : new String[]{"wifi", "bluetooth", "data", "dnd", "airplane", "system_controls", "nfc", "hotspot"}) states.put(key, values.optInt(key, -1));
            } catch (Exception ignored) { states.clear(); }
            if (!result.ok) states.clear();
            if (detailContent!=null) { if (result.ok) detailContent.statesChanged(); else detailContent.statesInvalidated(); }
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
        requestBrightness(target, value, null);
    }
    private boolean requestBrightness(Panels target, int value, Runnable finished) {
        if (display == null) return false;
        // Refreshes must never replace a user value waiting for the shared Shizuku service.
        if (value < 0 && (pendingBrightness != null || (activeBrightness != null && activeBrightness.target() == target && activeBrightness.sequence() == brightnessSequence))) return false;
        BrightnessRequest request = new BrightnessRequest(target, display.getDisplayId(), value, android.os.SystemClock.uptimeMillis() + 2500, brightnessSequence + 1, finished);
        if (!brightnessCurrent(request)) return false;
        brightnessSequence = request.sequence();
        pendingBrightness = request;
        if (value >= 0) { target.brightnessWorking(true); message("正在调节外屏亮度…"); }
        main.removeCallbacks(drainBrightness); drainBrightness(); return true;
    }
    private void drainBrightness() {
        BrightnessRequest request = pendingBrightness;
        if (request == null) return;
        if (!brightnessCurrent(request)) { pendingBrightness = null; if (request.finished() != null) request.finished().run(); return; }
        ShizukuBridge bridge = CoverApp.bridge(this);
        if (activeBrightness != null || bridge.busy()) {
            if (android.os.SystemClock.uptimeMillis() < request.deadline()) { main.postDelayed(drainBrightness, 80); return; }
            pendingBrightness = null;
            ShizukuBridge.Result timeout = new ShizukuBridge.Result(false, "亮度操作等待超时，请重试", "", true);
            if (request.value() >= 0) { request.target().brightnessWorking(false); request.target().brightnessWritten(timeout); }
            message(timeout.message); if (request.finished() != null) request.finished().run(); return;
        }
        pendingBrightness = null; activeBrightness = request;
        bridge.run(request.value() < 0 ? "brightness_read" : "brightness", request.displayId(), Math.max(0, request.value()), "", result -> {
            if (activeBrightness == request) activeBrightness = null;
            if (brightnessCurrent(request) && request.sequence() == brightnessSequence) {
                if (request.value() < 0) request.target().brightness(result); else { request.target().brightnessWorking(false); request.target().brightnessWritten(result); }
                if (request.value() >= 0 || !result.ok) message(result.message);
            }
            main.removeCallbacks(drainBrightness); drainBrightness();
            if (request.finished() != null) request.finished().run();
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
        if (homeClosing) return;
        switch (id) {
            case "app_dock" -> { if (hub == null) showHub(false); else if (hub.closing()) { closePanel(); hub.reopen(); hub.setExpanded(false); } else if (hub.expanded()) hub.setExpanded(false); else dismissHub(); }
            case "app_hub" -> { if (hub == null) showHub(); else if (hub.closing()) { closePanel(); hub.reopen(); hub.showTasks(false); hub.setExpanded(true); } else if (hub.showingTasks()) hub.showTasks(false); else if (!hub.expanded()) hub.setExpanded(true); else dismissHub(); }
            case "controls", "rotation", "media" -> { if (panelPage.equals(id)) closePanel(); else showPanel(id); }
            case "notifications", "notification_list" -> { if (panelPage.equals("notifications")) closePanel(); else showPanel("notifications"); }
            case "configure" -> openSettings("dock");
            case "apps" -> openSettings("apps");
            case "external_devices" -> { if (panel == null || !panelPage.equals("controls")) showPanel("controls"); showDetails("external_devices", null); }
            case "home" -> returnHome();
            case "back" -> { if (controlEditor != null) controlEditor.back(); else if (details != null) details.back(); else if (hub != null && !hub.closing()) hub.back(); else if (panel != null) closePanel(); else if (hub == null) global(GLOBAL_ACTION_BACK); }
            case "recents" -> { if (hub != null && hub.showingTasks() && !hub.closing()) dismissHub(); else { if (hub == null) showHub(true, true); if (hub != null) { if (hub.closing()) { closePanel(); hub.reopen(); } hub.showTasks(true); } } }
            case "system_recents" -> global(GLOBAL_ACTION_RECENTS);
            case "lock" -> global(GLOBAL_ACTION_LOCK_SCREEN);
            case "screenshot" -> screenshot();
            case "torch" -> toggleTorch();
            case "system_controls" -> setSystemControls(-1);
            case "nfc", "hotspot" -> {
                if (!CoverApp.bridge(this).connected()) { if (panel == null || !panelPage.equals("controls")) showPanel("controls"); showDetails(id, null); }
                else connectivityAction(id, -1, "", null);
            }
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
    void connectivityStateChanged(String id, int value) { states.put(id, value); if (panels != null) panels.updateStates(); }
    void refreshConnectivityStates() {
        if (instance != this) return;
        Panels target = panels; int screen = display == null ? -1 : display.getDisplayId();
        if (target == null || !panelPage.equals("controls") || !currentTaskDisplay(screen)) return;
        CoverApp.bridge(this).run("connectivity_states", screen, 0, "", result -> {
            if (instance != this || panels != target || !currentTaskDisplay(screen) || result.retryable) return;
            try { JSONObject values = result.ok ? new JSONObject(result.output) : new JSONObject(); states.put("nfc", values.optInt("nfc", -1)); states.put("hotspot", values.optInt("hotspot", -1)); }
            catch (Exception error) { states.put("nfc", -1); states.put("hotspot", -1); }
            target.updateStates();
        });
    }
    boolean connectivityDisplay(int screen) { return instance == this && currentTaskDisplay(screen); }
    void connectivityAction(String operation, int value, String input, ShizukuBridge.Callback after) {
        int screen = display == null ? -1 : display.getDisplayId();
        if (!currentTaskDisplay(screen)) { message("所选外屏不可用，请解锁后重试"); if (after != null) after.accept(new ShizukuBridge.Result(false, "所选外屏不可用", "")); return; }
        Panels target = panels; String id = operation.startsWith("nfc") ? "nfc" : "hotspot";
        if (target != null) { if (target.working(id)) { if (after != null) after.accept(new ShizukuBridge.Result(false, "上一项仍在执行，请稍后重试", "", true)); return; } target.working(id, true); }
        CoverApp.bridge(this).run(operation, screen, value, input, result -> {
            if (target != null) target.working(id, false);
            if (instance != this || !currentTaskDisplay(screen)) return;
            message(result.message); if (after != null) after.accept(result); refreshConnectivityStates();
        });
    }
    void setSwitch(String id, boolean enabled) {
        setSwitch(id,enabled,() -> { });
    }
    void setSwitch(String id, boolean enabled,Runnable finished) {
        Panels target = panels;
        shell(id, enabled ? 1 : 0, "", result -> {
            if (target != null && panels == target) target.working(id, result.ok);
            main.postDelayed(() -> refreshStates(false, () -> { if (target != null && panels == target) target.working(id, false); finished.run(); }), 700);
        });
    }
    void setSystemControls(int value) {
        setSystemControls(value,() -> { });
    }
    void setSystemControls(int value,Runnable finished) {
        if (systemControlsBusy) { finished.run(); return; }
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
            finished.run();
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
    private void returnHome() {
        int target = display == null ? -1 : display.getDisplayId();
        if (!currentTaskDisplay(target)) { message("所选外屏不可用，请解锁后重试"); return; }
        Context owner = screenContext;
        if (prefs.homeAction().equals("clock")) {
            dismissHub(); closePanel();
            main.postDelayed(() -> {
                if (owner != screenContext || !currentTaskDisplay(target) || !prefs.homeAction().equals("clock")) return;
                if (!performGlobalAction(GLOBAL_ACTION_HOME)) message("系统未接受返回锁屏页面");
            }, 80);
            return;
        }
        // Samsung rejects ordinary app launches of its cover home from another app.
        // The authorized, fixed Shizuku operation resumes the native task without a HOME event.
        message("正在返回三星卡片…");
        CoverApp.bridge(this).run("native_home", target, 0, "", () -> owner == screenContext && currentTaskDisplay(target) && prefs.homeAction().equals("cards"), result -> {
            if (owner != screenContext || !currentTaskDisplay(target) || !prefs.homeAction().equals("cards")) return;
            if (result.ok) launcherAccepted(); else message(result.message);
        });
    }
    private void global(int action) { if (action == GLOBAL_ACTION_LOCK_SCREEN) removeHubImmediately(); else dismissHub(); closePanel(); main.postDelayed(() -> { if (!performGlobalAction(action)) message("系统未接受此操作"); }, 80); }
    void openSettings(String section) { launch(new Intent(this, MainActivity.class).putExtra("section", section)); }
    void launchApp(String id) {
        long generation = appLaunchGeneration;
        launchApp(id, CoverApp.launcher(this).diagnostics.begin("floating"), () -> generation == appLaunchGeneration, accepted -> { });
    }
    void launchApp(String id, long request, java.util.function.BooleanSupplier sourceReady, java.util.function.Consumer<Boolean> completed) {
        Display selected = Displays.selected(this, prefs); int target = selected == null ? -1 : selected.getDisplayId();
        CoverApp.launcher(this).launch(this, prefs, id, target, request, () -> instance == this && sourceReady.getAsBoolean(), this::message, accepted -> { if (accepted) launcherAccepted(); completed.accept(accepted); });
    }
    void launcherAccepted() { if (homeClosing) return; dismissHub(); closePanel(); }
    boolean launcherAction(String id, int target) {
        if (!currentTaskDisplay(target) || !prefs.actions("favorites").contains(id) || !ActionCatalog.valid(id)) return false;
        act(id); return true;
    }
    DockGeometry.Box launcherContentBounds(int target) {
        if (display == null || display.getDisplayId() != target || launcherFrameRotation != display.getRotation() || panelFrame == null) return null;
        Point size = Displays.size(display);
        return panelFrame.width() == size.x && panelFrame.height() == size.y ? hubFrame : null;
    }
    boolean launcherSurface(String operation, int target) {
        if (operation == null || !currentTaskDisplay(target) || !java.util.Set.of("search", "sort", "tasks").contains(operation)) return false;
        showHub(true, operation.equals("tasks"));
        if (hub == null) return false;
        AppHubView opened = hub;
        if (!operation.equals("tasks")) opened.post(() -> { if (hub == opened && opened.isAttachedToWindow()) opened.widgetEntry(operation); });
        return true;
    }
    void launch(Intent intent) {
        launch(intent, false);
    }
    private void launch(Intent intent, boolean reuseTask) {
        if (!notificationDisplayReady()) { message("所选外屏不可用，请解锁后重试"); return; }
        // Players may finish a duplicate launcher task immediately. Bring their existing task forward.
        if (reuseTask) intent.setFlags((intent.getFlags() & ~Intent.FLAG_ACTIVITY_MULTIPLE_TASK) | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        else intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        try { startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display.getDisplayId()).toBundle()); dismissHub(); closePanel(); }
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
        setTorch(!Boolean.TRUE.equals(torchOn));
    }
    void setTorch(boolean enabled) {
        if (torchCamera == null) { message("未找到可用闪光灯"); return; }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) { openSettings("main"); message("请在设置页授权手电筒所需的相机权限"); return; }
        try { cameras.setTorchMode(torchCamera, enabled); message("手电筒请求已提交"); }
        catch (Exception e) { message("手电筒不可用：" + e.getClass().getSimpleName()); }
    }
    MediaSessions mediaSessions() { if (mediaSessions == null) mediaSessions = new MediaSessions(screenContext); return mediaSessions; }
    void openMediaPlayer() {
        android.media.session.MediaController controller = mediaSessions().controller();
        if (controller == null) { message("请先打开播放器"); return; }
        if (!notificationDisplayReady()) { message("所选外屏不可用，请解锁后重试"); return; }
        try {
            android.app.PendingIntent entry = controller.getSessionActivity();
            if (entry == null) { Intent intent = screenContext.getPackageManager().getLaunchIntentForPackage(controller.getPackageName()); if (intent != null) launch(intent, true); else message("播放器未提供打开入口"); return; }
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
    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level != TRIM_MEMORY_UI_HIDDEN && level >= TRIM_MEMORY_RUNNING_LOW) releaseCardTextures();
    }
    @Override public void onLowMemory() { super.onLowMemory(); releaseCardTextures(); }
    private void releaseCardTextures() {
        finishHubPush(false); finishPanelPush(false);
        if (hubCard != null) hubCard.dropGlass(); closeTaskGlass();
        if (panelCard != null || panelGlass != null) panelMemoryFallback = true;
        if (panelCard != null) panelCard.dropGlass(); else if (panelGlass != null) panelGlass.close();
        panelGlass = null; refreshBlurState();
    }
    @Override protected void dump(java.io.FileDescriptor descriptor, java.io.PrintWriter writer, String[] arguments) {
        super.dump(descriptor, writer, arguments); writer.println(blurDiagnostics());
        if (prefs != null) { writer.println(windowDiagnostics()); writer.println(lifecycleDiagnostics()); }
        writer.println("Status overlay: mounted=" + (statusBar != null) + ", visible=" + (statusBar != null && statusBar.getVisibility() == View.VISIBLE) + ", appRule=" + statusVisibility.hidden() + ", enabled=" + statusVisibility.enabled() + ", settling=" + main.hasCallbacks(settleStatusApplication));
        writer.println("Launcher entry: hostFocused=" + launcherHostFocused + ", cardVisible=" + (display != null && CoverApp.launcherWidgets(this).visible(display.getDisplayId())) + ", pending=" + launcherEntryPending + ", hub=" + (hub != null) + ", " + (dock == null ? "dock=absent" : dock.launcherEntryDiagnostics()));
        writer.println("Launcher geometry: rotation=" + (display == null ? -1 : display.getRotation()) + ", frame=" + hubFrame);
        writer.println("External input: " + CoverApp.inputs(this).status + ", keyboard=" + CoverApp.inputs(this).keyboardAvailable() + ", devices=" + CoverApp.inputs(this).connected().size() + ", nativeHost=" + nativeHostVisible + ", nativeCard=" + CoverApp.launcherWidgets(this).inputCard());
        if (nativeInput != null) writer.println("Native input: " + nativeInput.diagnostics());
        writer.println(CoverApp.launcher(this).diagnostics.report());
    }
    @Override public void onDestroy() {
        CoverApp.inputs(this).unobserve(updateNativeInput);
        stopAutomaticRotation();
        main.removeCallbacks(restoreFromLauncherCard); pendingLauncherDisplay = -1;
        if (systemControlGuard != null) systemControlGuard.close();
        instance = null; setStatus("无障碍服务已断开"); main.removeCallbacksAndMessages(null); removeWindows();
        resetStatusApplication(); windowPackages.clear(); dockVisibility.reset();
        CoverApp.bridge(this).removeObserver(bridgeChanged);
        if (prefs != null) prefs.data.unregisterOnSharedPreferenceChangeListener(preferenceListener);
        getSystemService(DisplayManager.class).unregisterDisplayListener(this);
        try { unregisterReceiver(screenReceiver); } catch (RuntimeException ignored) { }
        try { unregisterReceiver(systemDialogsReceiver); } catch (RuntimeException ignored) { }
        if (cameras != null) cameras.unregisterTorchCallback(torchCallback);
        files.shutdown(); CoverApp.launcherWidgets(this).refresh(); super.onDestroy();
    }
}
