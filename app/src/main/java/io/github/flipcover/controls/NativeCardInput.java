package io.github.flipcover.controls;

import android.graphics.PixelFormat;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.view.WindowManager;
import android.widget.RemoteViews;

/** A visible-instance-only input host. It uses the card's exact RemoteViews and existing PendingIntents. */
final class NativeCardInput {
    private final CoverService service;
    private InputSurface surface;
    private AppWidgetHostView content;
    private int card = -1;
    private boolean launcher;
    private RemoteViews rendered;
    private WindowManager windows;
    private DockGeometry.Box hostBounds;
    private int rejectedCard = -1;
    private int dismissedCard = -1;
    private long dismissedEpoch = -1;
    private boolean dismissedLauncher;
    private final Runnable captureCheck = this::checkCapture;
    private void checkCapture() {
        if (surface != null && CoverApp.inputs(service).needsCapture() && !surface.hasPointerCapture()) {
            rejectedCard = card; close(); CoverApp.inputs(service).status("系统未授予原生卡片指针捕获；可返回助手重试");
        }
    }
    NativeCardInput(CoverService service) { this.service = service; }
    void update(int id, boolean launcher, RemoteViews views) {
        long epoch = launcher ? CoverApp.launcherWidgets(service).inputEpoch() : CoverApp.widgets(service).inputEpoch();
        if (id == dismissedCard && launcher == dismissedLauncher && epoch == dismissedEpoch) { close(); return; }
        if (!service.nativeInputEligible() || id < 0 || views == null || !(CoverApp.inputs(service).needsCapture() || CoverApp.inputs(service).keyboardAvailable())) { rejectedCard = -1; close(); return; }
        if (rejectedCard == id) return;
        android.graphics.Point size = Displays.size(service.display);
        DockGeometry.Box bounds = service.nativeHostBounds(service.display);
        if (bounds == null) bounds = new DockGeometry.Box(0, 0, size.x, size.y);
        if (card != id || this.launcher != launcher || !bounds.equals(hostBounds)) close();
        if (rendered == views && surface != null) return;
        try {
            if (surface == null) {
                card = id; this.launcher = launcher; surface = new InputSurface(service.screenContext);
                surface.memory(service.inputMemory, "native:" + id);
                surface.nativeCard(() -> service.nativeInputEligible() && visible());
                surface.collectionScroll((list, position) -> { if (!this.launcher || list.getId() != R.id.launcher_rail || !service.nativeInputEligible() || !visible()) return; RemoteViews update = new RemoteViews(service.getPackageName(), R.layout.launcher_widget_hub); update.setScrollPosition(R.id.launcher_rail, position); AppWidgetManager.getInstance(service).partiallyUpdateAppWidget(card, update); });
                surface.navigation(() -> { if (this.launcher && CoverApp.launcherWidgets(service).inputBack(card, service.display.getDisplayId())) return; dismissedCard = card; dismissedLauncher = this.launcher; dismissedEpoch = this.launcher ? CoverApp.launcherWidgets(service).inputEpoch() : CoverApp.widgets(service).inputEpoch(); close(); }, () -> { });
                hostBounds = bounds;
                WindowManager.LayoutParams params = new WindowManager.LayoutParams(bounds.width(), bounds.height(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, PixelFormat.TRANSLUCENT);
                params.x = bounds.x(); params.y = bounds.y(); params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT; params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
                params.setFitInsetsTypes(0); params.setTitle("外屏卡片输入"); windows = service.screenContext.getSystemService(WindowManager.class); windows.addView(surface, params);
            }
            if (content == null) {
                content = new AppWidgetHostView(service.screenContext); content.setAppWidget(id, AppWidgetManager.getInstance(service).getAppWidgetInfo(id));
                content.setPadding(0, 0, 0, 0); content.setAlpha(0); surface.addView(content, new android.widget.FrameLayout.LayoutParams(-1, -1));
            }
            content.updateAppWidget(views);
            surface.page("native:" + id + ":" + (launcher ? CoverApp.launcherWidgets(service).inputFolder(id) : "widgets"));
            if (launcher) content.post(() -> { if (content != null && card == id) { android.view.View grid = content.findViewById(R.id.launcher_grid); if (grid != null) grid.setTag("desktop".equals(CoverApp.launcherWidgets(service).inputFolder(id)) ? null : "input-folder-grid"); } });
            if (!launcher) content.post(() -> { if (content != null && surface != null && card == id) bindComponents(content, new int[]{0}); });
            rendered = views; surface.requestFocus();
            if (CoverApp.inputs(service).needsCapture() && surface.hasWindowFocus() && !surface.hasPointerCapture()) surface.requestPointerCapture();
            surface.removeCallbacks(captureCheck); if (CoverApp.inputs(service).needsCapture()) surface.postDelayed(captureCheck, 1000);
        } catch (RuntimeException failure) { close(); CoverApp.inputs(service).status("原生卡片输入暂不可用：" + failure.getClass().getSimpleName()); }
    }
    private boolean visible() { return launcher ? CoverApp.launcherWidgets(service).inputCard() == card : CoverApp.widgets(service).inputCard() == card; }
    private void bindComponents(android.view.View view, int[] index) {
        if (view.getId() == R.id.widget_cell) { InputNavigation.bind(view, "widget:" + card + ":" + index[0]++, InputNavigation.Region.WIDGET, null, null); return; }
        if (view instanceof android.view.ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) bindComponents(group.getChildAt(i), index);
        if (view == content && index[0] == 0 && content.getChildCount() > 0) InputNavigation.bind(content.getChildAt(0), "widget:" + card + ":single", InputNavigation.Region.WIDGET, null, null);
    }
    String diagnostics() { android.view.View rail = content == null ? null : content.findViewById(R.id.launcher_rail); return "card=" + card + ", capture=" + (surface != null && surface.hasPointerCapture()) + ", rail=" + (rail instanceof android.view.ViewGroup group ? group.getChildCount() : -1); }
    void close() { InputSurface old = surface; surface = null; content = null; rendered = null; hostBounds = null; card = -1; if (old != null) { old.removeCallbacks(captureCheck); old.release(); old.removeAllViews(); try { windows.removeViewImmediate(old); } catch (RuntimeException ignored) { } } }
}
