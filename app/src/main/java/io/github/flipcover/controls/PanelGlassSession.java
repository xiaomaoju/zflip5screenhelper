package io.github.flipcover.controls;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.View;
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry;
import com.kyant.backdrop.catalog.components.LiquidTensionRenderer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** One selected-display opening; no polling, persistence or permission changes. */
@android.annotation.TargetApi(33)
final class PanelGlassSession implements AutoCloseable {
    private static final ThreadPoolExecutor WORK = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), task -> { Thread thread = new Thread(task, "panel-glass"); thread.setDaemon(true); return thread; }, new ThreadPoolExecutor.AbortPolicy());
    static { WORK.allowCoreThreadTimeOut(true); }
    private static final int BLUR_LIMIT = 384;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Context context;
    final int displayId, width, height, rotation;
    private final float density;
    private final Map<View, GlassSurface> surfaces = new IdentityHashMap<>();
    private final ArrayList<GlassSurface.Scope> scopes = new ArrayList<>();
    private GlassBackdrop backdrop;
    private final android.graphics.Paint localBackdropPaint=new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.RectF localBackdropBounds=new android.graphics.RectF();
    private final int[] localBackdropPosition=new int[2];
    private RuntimeShader shader, modalShader;
    private float taskReactionX, taskReactionY;
    void taskReaction(float x, float y) {
        if (closed || taskReactionX == x && taskReactionY == y) return;
        taskReactionX = x; taskReactionY = y;
        for (View view : surfaces.keySet()) view.invalidate();
    }
    float taskReactionX() { return taskReactionX; }
    float taskReactionY() { return taskReactionY; }
    private LiquidTensionRenderer tensionRenderer;
    private boolean tensionPreparationQueued;
    private final android.os.MessageQueue.IdleHandler prepareTensionWhenIdle = () -> {
        tensionPreparationQueued = false;
        if (!this.closed) { if (tensionRenderer == null) tensionRenderer = new LiquidTensionRenderer(); tensionRenderer.prepare(); }
        return false;
    };
    private BitmapShader tensionSource, tensionStrongSource;
    private final Matrix tensionMatrix = new Matrix();
    private final Matrix tensionBase = new Matrix(), tensionInverse = new Matrix(), tensionLocal = new Matrix(), tensionCombined = new Matrix();
    private final float[] tensionCoordinates = new float[9];
    private GlassBackdrop modalBackdrop;
    private int modalX,modalY;
    private int modalGeneration;
    private boolean modalPreparing;
    private Runnable modalReady;
    private final Runnable modalTimeout = () -> completeModal(null, null);
    private Runnable sourceRefresh;
    private PanelGlassSession replacement;
    private BooleanSupplier refreshCurrent, refreshIdle;
    private boolean closed, settled;
    private boolean sourceInvalidated, hadBackdrop;
    private int sourceWindowId = -1;
    private String sourceTitle;
    private record SourceIdentity(int id, android.graphics.Rect bounds, String title) { }
    private SourceIdentity observedSource;
    private static SourceIdentity identity(int id, android.graphics.Rect bounds, CharSequence title) { return new SourceIdentity(id, new android.graphics.Rect(bounds), title == null ? "" : title.toString()); }
    boolean observedSourceChanged(int id, android.graphics.Rect bounds, CharSequence title) { return !closed && observedSource != null && !observedSource.equals(identity(id, bounds, title)); }
    private final android.graphics.Rect captureArea = new android.graphics.Rect();
    void captureArea(android.graphics.Rect area) { captureArea.set(area); }
    boolean canCaptureWindow(android.graphics.Rect bounds) { return !captureArea.isEmpty() && bounds.contains(captureArea); }
    private final android.graphics.Rect sourceWindowBounds = new android.graphics.Rect();
    private float backdropAlpha = 1;
    private android.animation.ValueAnimator sourceFade;
    private Runnable finish;
    private BooleanSupplier current;
    private long started;
    private int sourceX, sourceY;
    private int capturedWidth, capturedHeight, mappedWidth, mappedHeight;
    private String sourceKind = "display";
    int captures, preparations, draws;
    long readyMillis;
    String state = "pending";
    private final Runnable timeout = () -> complete(null,null,"timeout");
    PanelGlassSession(Context context, Display display) {
        this.context = context; displayId = display.getDisplayId(); rotation = display.getRotation();
        Point size = new Point(); display.getRealSize(size); width = size.x; height = size.y; captureArea.set(0, 0, width, height); density = context.getResources().getDisplayMetrics().density;
    }
    static boolean allowed(Context context, Prefs prefs) { return Build.VERSION.SDK_INT >= 33 && prefs.panelBlur() && !context.getSystemService(PowerManager.class).isPowerSaveMode() && !context.getSystemService(KeyguardManager.class).isKeyguardLocked(); }
    boolean matches(Display display) { Point size = new Point(); display.getRealSize(size); return !closed && displayId == display.getDisplayId() && rotation == display.getRotation() && width == size.x && height == size.y; }
    boolean ready() { return !closed && shader != null; }
    boolean taskOptics() { return ready() || !closed && sourceInvalidated && hadBackdrop; }
    boolean closingBackdropValid() { return !sourceInvalidated && ready(); }
    float backdropAlpha() { return backdropAlpha; }
    void sourceWindow(int id, android.graphics.Rect bounds) { sourceWindowId = id; sourceWindowBounds.set(bounds); }
    void sourceWindow(int id, android.graphics.Rect bounds, CharSequence title) { sourceWindow(id, bounds); sourceTitle = title == null ? "" : title.toString(); observedSource = identity(id, bounds, title); }
    boolean sourceChanged(int id, android.graphics.Rect bounds, CharSequence title) { return sourceChanged(id, bounds) || !closed && !sourceInvalidated && sourceTitle != null && !sourceTitle.contentEquals(title == null ? "" : title); }
    boolean sourceStateChanged(int windowId) { return !closed && !sourceInvalidated && sourceWindowId >= 0 && sourceWindowId == windowId; }
    boolean sourceChanged(int id, android.graphics.Rect bounds) { return !closed && !sourceInvalidated && sourceWindowId >= 0 && (id != sourceWindowId || !sourceWindowBounds.equals(bounds)); }
    /** Retire only the background. Task identities, preview lenses and touch state stay mounted. */
    void invalidateSource() {
        if (closed || sourceInvalidated) return;
        sourceInvalidated = true;
        if (sourceFade != null) { sourceFade.removeAllListeners(); sourceFade.removeAllUpdateListeners(); sourceFade.cancel(); sourceFade = null; }
        if (!settled) { complete(null, null, "source-changed"); return; }
        state = "source-changed";
        if (!ready() || !android.animation.ValueAnimator.areAnimatorsEnabled()) { releaseBackdrop(); return; }
        sourceFade = android.animation.ValueAnimator.ofFloat(backdropAlpha, 0); sourceFade.setDuration(100);
        sourceFade.addUpdateListener(animation -> { backdropAlpha = (float) animation.getAnimatedValue(); for (View view : surfaces.keySet()) view.invalidate(); });
        sourceFade.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) { sourceFade = null; releaseBackdrop(); }
        }); sourceFade.start();
    }
    private void releaseBackdrop() {
        backdropAlpha = 0; shader = null; tensionSource = tensionStrongSource = null; endModal();
        main.getLooper().getQueue().removeIdleHandler(prepareTensionWhenIdle); tensionPreparationQueued = false;
        if (tensionRenderer != null) tensionRenderer.clear();
        if (backdrop != null) { backdrop.close(); backdrop = null; }
        for (View view : surfaces.keySet()) view.invalidate();
    }
    void sourceMissing() { invalidateSource(); observedSource = identity(-1, new android.graphics.Rect(), ""); cancelSourceRefresh(); }
    /** Event-driven window-only refresh. Existing views, task optics and gesture owners stay bound. */
    void refreshSource(AccessibilityService service, Display display, int windowId, android.graphics.Rect bounds, CharSequence title, BooleanSupplier current, BooleanSupplier idle) {
        observedSource = identity(windowId, bounds, title);
        cancelSourceRefresh();
        if (closed || Build.VERSION.SDK_INT < 34 || windowId < 0 || !canCaptureWindow(bounds)) return;
        android.graphics.Rect expectedBounds = new android.graphics.Rect(bounds); String expectedTitle = title == null ? "" : title.toString();
        refreshCurrent = current; refreshIdle = idle;
        sourceRefresh = () -> {
            if (closed || !refreshCurrent.getAsBoolean()) { cancelSourceRefresh(); return; }
            if (!refreshIdle.getAsBoolean()) return;
            if (replacement != null) {
                if (replacement.ready()) { PanelGlassSession next = replacement; replacement = null; sourceRefresh = null; refreshCurrent = refreshIdle = null; replaceSource(next); }
                return;
            }
            PanelGlassSession next = new PanelGlassSession(context, display); next.captureArea(captureArea); replacement = next;
            next.capture(service, () -> !closed && replacement == next && refreshCurrent != null && refreshCurrent.getAsBoolean(), () -> {
                if (replacement != next) { next.close(); return; }
                if (!next.ready() || next.sourceChanged(windowId, expectedBounds, expectedTitle)) { cancelSourceRefresh(); return; }
                resumeSourceRefresh();
            }, true);
        };
        main.postDelayed(sourceRefresh, 300);
    }
    void resumeSourceRefresh() { if (sourceRefresh != null && !main.hasCallbacks(sourceRefresh)) main.post(sourceRefresh); }
    void cancelSourceRefresh() {
        if (sourceRefresh != null) main.removeCallbacks(sourceRefresh); sourceRefresh = null; refreshCurrent = refreshIdle = null;
        PanelGlassSession pending = replacement; replacement = null; if (pending != null) pending.close();
    }
    /** Transfer only prepared texture ownership, without unbinding or rebuilding the scene. */
    void replaceSource(PanelGlassSession next) {
        if (closed || !next.ready() || next.displayId != displayId || next.rotation != rotation || next.width != width || next.height != height) { next.close(); return; }
        if (sourceFade != null) { sourceFade.removeAllListeners(); sourceFade.removeAllUpdateListeners(); sourceFade.cancel(); sourceFade = null; }
        releaseBackdrop();
        backdrop = next.backdrop; shader = next.shader; next.backdrop = null; next.shader = null;
        sourceWindow(next.sourceWindowId, next.sourceWindowBounds, next.sourceTitle); sourceX = next.sourceX; sourceY = next.sourceY; sourceKind = next.sourceKind;
        capturedWidth = next.capturedWidth; capturedHeight = next.capturedHeight; mappedWidth = next.mappedWidth; mappedHeight = next.mappedHeight;
        captures += next.captures; preparations += next.preparations; readyMillis = next.readyMillis;
        sourceInvalidated = false; hadBackdrop = true; backdropAlpha = 1; settled = true; state = "source-updated"; next.close();
        for (GlassSurface surface : surfaces.values()) surface.refresh();
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            backdropAlpha = 0; sourceFade = android.animation.ValueAnimator.ofFloat(0, 1); sourceFade.setDuration(140);
            sourceFade.addUpdateListener(animation -> { backdropAlpha = (float) animation.getAnimatedValue(); for (View view : surfaces.keySet()) view.invalidate(); });
            sourceFade.addListener(new android.animation.AnimatorListenerAdapter() { @Override public void onAnimationEnd(android.animation.Animator animation) { sourceFade = null; } }); sourceFade.start();
        }
    }
    /** Draw the existing capture into Compose's original LayerBackdrop; never captures or owns another bitmap. */
    void drawLocalBackdrop(android.graphics.Canvas canvas,View host,float offsetX,float offsetY) {
        canvas.drawColor(0xFF111820);
        if (!ready() || backdrop==null || !canvas.isHardwareAccelerated()) return;
        GlassBackdrop pixels=backdrop; int originX=sourceX,originY=sourceY;
        for (android.view.ViewParent parent=host.getParent(); parent instanceof View ancestor; parent=ancestor.getParent()) if (ancestor instanceof DetailSheet) { if (modalBackdrop!=null) { pixels=modalBackdrop; originX=modalX; originY=modalY; } break; }
        host.getLocationOnScreen(localBackdropPosition);
        float x=originX-localBackdropPosition[0]-offsetX,y=originY-localBackdropPosition[1]-offsetY;
        localBackdropBounds.set(x,y,x+pixels.width,y+pixels.height);
        canvas.drawBitmap(pixels.sharp,null,localBackdropBounds,localBackdropPaint); draws++;
    }
    /** Reuses this opening's prepared Gaussian texture for the task page's final dissolve. */
    void drawClosingBackdrop(android.graphics.Canvas canvas, View host, float blur, float alpha) {
        if (sourceInvalidated || !ready() || backdrop == null || !canvas.isHardwareAccelerated() || alpha <= 0) return;
        host.getLocationOnScreen(localBackdropPosition);
        float x = sourceX - localBackdropPosition[0] + taskReactionX, y = sourceY - localBackdropPosition[1] + taskReactionY;
        localBackdropBounds.set(x, y, x + backdrop.width, y + backdrop.height);
        int saved = canvas.saveLayerAlpha(0, 0, host.getWidth(), host.getHeight(), Math.round(255 * alpha));
        localBackdropPaint.setAlpha(255); canvas.drawBitmap(backdrop.sharp, null, localBackdropBounds, localBackdropPaint);
        localBackdropPaint.setAlpha(Math.round(255 * blur)); canvas.drawBitmap(backdrop.strong, null, localBackdropBounds, localBackdropPaint);
        canvas.drawColor(0x3D000000); // Match the task floor's 0.76 gain before the dissolve.
        localBackdropPaint.setAlpha(255); canvas.restoreToCount(saved); draws++;
    }
    boolean active() { return !closed; }
    boolean preparing() { return !closed && !settled; }
    long bytes() { return (backdrop == null ? 0 : backdrop.bytes) + (modalBackdrop == null ? 0 : modalBackdrop.bytes) + (replacement == null ? 0 : replacement.bytes()); }
    RuntimeShader shader(View view) { for (android.view.ViewParent parent=view.getParent(); parent instanceof View ancestor; parent=ancestor.getParent()) if (ancestor instanceof DetailSheet) return modalShader == null ? shader : modalShader; return shader; }
    /** Reuse the notification's existing Gaussian texture; one cached library renderer per opening. */
    void prepareTension() {
        if (closed || sourceInvalidated || Build.VERSION.SDK_INT < 33 || tensionRenderer != null || tensionPreparationQueued) return;
        tensionPreparationQueued = true; main.getLooper().getQueue().addIdleHandler(prepareTensionWhenIdle);
    }
    boolean drawTension(android.graphics.Canvas canvas, View owner, LiquidTensionGeometry geometry) {
        return drawTension(canvas, owner, geometry, false);
    }
    boolean drawTension(android.graphics.Canvas canvas, View owner, LiquidTensionGeometry geometry, boolean strong) {
        return drawTension(canvas, owner, geometry, strong, com.kyant.backdrop.catalog.components.LiquidTensionStyle.Default);
    }
    boolean drawTension(android.graphics.Canvas canvas, View owner, LiquidTensionGeometry geometry, boolean strong, com.kyant.backdrop.catalog.components.LiquidTensionStyle style) {
        if (closed || sourceInvalidated || Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated() || geometry.getCount() < 1) return false;
        if (tensionRenderer == null) tensionRenderer = new LiquidTensionRenderer();
        if (strong) { if (tensionStrongSource == null && backdrop != null) tensionStrongSource = source(backdrop.strong, backdrop.width, backdrop.height, sourceX, sourceY); }
        else if (tensionSource == null && backdrop != null) tensionSource = source(backdrop.soft, backdrop.width, backdrop.height, sourceX, sourceY);
        tensionMatrix.reset(); owner.transformMatrixToGlobal(tensionMatrix);
        if (ControlFeedback.hasPaint(owner)) ControlFeedback.materialPosition(owner, tensionMatrix, tensionBase, tensionInverse, tensionLocal, tensionCombined);
        if (owner instanceof SharedGlassHost host) tensionMatrix.postTranslate(host.glassOffsetX(), host.glassOffsetY()); tensionMatrix.getValues(tensionCoordinates);
        boolean drawn = tensionRenderer.draw(canvas, owner.getWidth(), owner.getHeight(), geometry, strong ? tensionStrongSource : tensionSource, tensionCoordinates, style);
        if (drawn) draws++; return drawn;
    }
    void capture(AccessibilityService service, BooleanSupplier current, Runnable finish) {
        capture(service, current, finish, false);
    }
    void capture(AccessibilityService service, BooleanSupplier current, Runnable finish, boolean windowOnly) {
        if (closed || sourceInvalidated || settled || captures > 0) return;
        this.current = current; this.finish = finish; started = SystemClock.uptimeMillis();
        if (displayId == Display.DEFAULT_DISPLAY || Build.VERSION.SDK_INT < 33) { complete(null,null,"unsupported"); return; }
        main.postDelayed(timeout, 150); captures++;
        try {
            android.graphics.Rect sourceBounds = new android.graphics.Rect(0,0,width,height);
            int windowId = -1;
            {
                android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all=service.getWindowsOnAllDisplays();
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = all.get(displayId);
                int layer = Integer.MIN_VALUE;
                try { if (windows != null) for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getLayer() > layer) {
                        android.graphics.Rect bounds = new android.graphics.Rect(); window.getBoundsInScreen(bounds);
                        layer=window.getLayer();
                        sourceWindow(window.getId(), bounds, window.getTitle());
                        // A floating top app must not be replaced by a full-screen app beneath it.
                        windowId=Build.VERSION.SDK_INT >= 34 && canCaptureWindow(bounds) ? window.getId() : -1;
                        sourceBounds.set(windowId >= 0 ? bounds : new android.graphics.Rect(0,0,width,height));
                    }
                } } finally { for (int i=0;i<all.size();i++) for (android.view.accessibility.AccessibilityWindowInfo window:all.valueAt(i)) window.recycle(); }
            }
            sourceX=sourceBounds.left; sourceY=sourceBounds.top; sourceKind=windowId >= 0 ? "window" : "display";
            // A departing launcher is still on screen during a push. Never bake it
            // into the new panel's background when window capture is unavailable.
            if (windowOnly && windowId < 0) { complete(null,null,"transition-window-unavailable"); return; }
            AccessibilityService.TakeScreenshotCallback callback = new AccessibilityService.TakeScreenshotCallback() {
                @Override public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    receive(result.getHardwareBuffer(),result.getColorSpace(),sourceBounds.width(),sourceBounds.height());
                }
                @Override public void onFailure(int error) { complete(null,null,"capture-" + error); }
            };
            if (windowId >= 0 && Build.VERSION.SDK_INT >= 34) service.takeScreenshotOfWindow(windowId,service.getMainExecutor(),callback);
            else service.takeScreenshot(displayId,service.getMainExecutor(),callback);
        } catch (RuntimeException | OutOfMemoryError failure) { complete(null,null,failure.getClass().getSimpleName()); }
    }
    /** Consumes the callback's native handle, including stale and rejected frames. */
    void receive(android.hardware.HardwareBuffer buffer, android.graphics.ColorSpace color, int expectedWidth, int expectedHeight) {
        Bitmap bitmap;
        try {
            if (!accepting()) return;
            bitmap=Bitmap.wrapHardwareBuffer(buffer,color);
        } catch (RuntimeException | OutOfMemoryError failure) { complete(null,null,"buffer"); return; }
        finally { buffer.close(); }
        if (bitmap == null) { complete(null,null,"empty"); return; }
        capturedWidth=bitmap.getWidth(); capturedHeight=bitmap.getHeight(); mappedWidth=expectedWidth; mappedHeight=expectedHeight;
        if (!captureSizeMatches(capturedWidth,capturedHeight,expectedWidth,expectedHeight,sourceKind.equals("window"))) { bitmap.recycle(); complete(null,null,"geometry"); return; }
        prepare(bitmap,expectedWidth,expectedHeight);
    }
    /** Compat-scaled app surfaces can use more pixels than their on-display window bounds. */
    static boolean captureSizeMatches(int actualWidth,int actualHeight,int expectedWidth,int expectedHeight,boolean window) {
        if (actualWidth<=0 || actualHeight<=0 || expectedWidth<=0 || expectedHeight<=0) return false;
        if (actualWidth==expectedWidth && actualHeight==expectedHeight) return true;
        // Only window capture may be uniformly scaled; allow one pixel of rounding, not cropping.
        return window && Math.abs((long)actualWidth*expectedHeight-(long)actualHeight*expectedWidth)<=Math.max(expectedWidth,expectedHeight);
    }
    private boolean accepting() { return !closed && !sourceInvalidated && !settled && SystemClock.uptimeMillis() - started <= 150 && current.getAsBoolean(); }
    private void prepare(Bitmap bitmap,int width,int height) {
        try {
            WORK.execute(() -> {
                GlassBackdrop prepared = null; RuntimeShader program = null; String outcome = "ready";
                try { prepared = GlassBackdrop.prepare(bitmap,density,BLUR_LIMIT,width,height); program=shader(prepared,sourceX,sourceY); }
                catch (RuntimeException | OutOfMemoryError failure) { if (prepared != null) prepared.close(); prepared=null; outcome=failure.getClass().getSimpleName(); }
                GlassBackdrop result=prepared; RuntimeShader material=program; String status=outcome;
                main.post(() -> complete(result,material,status));
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { bitmap.recycle(); complete(null,null,"busy"); }
    }
    private void complete(GlassBackdrop prepared, RuntimeShader program, String outcome) {
        if (closed || settled) { if (prepared != null) prepared.close(); return; }
        if (prepared != null && !accepting()) { prepared.close(); prepared = null; outcome = "expired"; }
        main.removeCallbacks(timeout); settled=true;
        if (prepared != null) { shader=program; backdrop=prepared; hadBackdrop=true; preparations++; }
        state=outcome; for (GlassSurface surface:surfaces.values()) surface.refresh();
        readyMillis=SystemClock.uptimeMillis()-started;
        Runnable callback = finish; finish = null;
        if (callback != null && current.getAsBoolean()) callback.run();
        current = null;
    }
    private static RuntimeShader shader(GlassBackdrop backdrop, int x, int y) {
        RuntimeShader shader = new RuntimeShader(GlassSurface.PROGRAM);
        shader.setFloatUniform("frame",x,y,x+backdrop.width,y+backdrop.height);
        shader.setInputShader("sharp", source(backdrop.sharp, backdrop.width, backdrop.height,x,y));
        shader.setInputShader("soft", source(backdrop.soft, backdrop.width, backdrop.height,x,y));
        shader.setInputShader("strong", source(backdrop.strong, backdrop.width, backdrop.height,x,y));
        return shader;
    }
    private static BitmapShader source(Bitmap bitmap, int width, int height, int x, int y) {
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shader.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
        Matrix matrix = new Matrix(); matrix.setScale(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight()); matrix.postTranslate(x,y); shader.setLocalMatrix(matrix); return shader;
    }
    void beginModal(View parent) {
        endModal(); if (!ready()) return;
        int generation=modalGeneration; int[] position=new int[2]; parent.getLocationOnScreen(position);
        modalX=position[0]; modalY=position[1];
        android.graphics.RenderNode record;
        try { record=GlassBackdrop.recordLocal(parent); } catch (RuntimeException | OutOfMemoryError failure) { return; }
        modalPreparing=true; main.postDelayed(modalTimeout,120);
        try {
            WORK.execute(() -> {
                GlassBackdrop prepared=null; RuntimeShader program=null;
                try { prepared=GlassBackdrop.prepare(GlassBackdrop.renderRecorded(record),density,BLUR_LIMIT); program=shader(prepared,position[0],position[1]); }
                catch (RuntimeException | OutOfMemoryError failure) { if (prepared != null) prepared.close(); prepared=null; record.discardDisplayList(); }
                GlassBackdrop result=prepared; RuntimeShader material=program;
                main.post(() -> {
                    if (closed || modalGeneration != generation || !modalPreparing) { if (result != null) result.close(); return; }
                    if (result != null && (backdrop == null || backdrop.bytes+result.bytes > 12L*1024*1024)) { result.close(); completeModal(null,null); }
                    else completeModal(result,material);
                });
            });
        } catch (java.util.concurrent.RejectedExecutionException busy) { record.discardDisplayList(); completeModal(null,null); }
    }
    /** First visible frame uses the final material. A slow preparation keeps the original texture for this opening. */
    void whenModalReady(Runnable ready) { if (modalPreparing) modalReady=ready; else ready.run(); }
    private void completeModal(GlassBackdrop prepared, RuntimeShader material) {
        main.removeCallbacks(modalTimeout); modalPreparing=false; modalBackdrop=prepared; modalShader=material;
        for (GlassSurface surface:surfaces.values()) surface.refresh();
        Runnable ready=modalReady; modalReady=null; if (ready != null) ready.run();
    }
    void endModal() { modalGeneration++; main.removeCallbacks(modalTimeout); modalPreparing=false; Runnable ready=modalReady; modalReady=null; modalShader=null; if (modalBackdrop != null) { modalBackdrop.close(); modalBackdrop=null; } if (ready != null) ready.run(); }
    GlassSurface bind(View view, GlassSurface.Role role) {
        GlassSurface old = surfaces.get(view);
        if (old != null && old.role == role) return old;
        if (old != null) old.close();
        GlassSurface surface = new GlassSurface(this, view, role); surfaces.put(view, surface); surface.install(); return surface;
    }
    void positions() { for (GlassSurface surface:surfaces.values()) surface.position(); }
    void forget(View view, GlassSurface surface) { if (surfaces.get(view) == surface) surfaces.remove(view); }
    void unbind(View view) { GlassSurface surface = surfaces.get(view); if (surface != null) surface.close(); }
    void attach(View root, boolean notifications) { if (!closed) scopes.add(new GlassSurface.Scope(this, root, notifications)); }
    void decorate() { if (!closed) for (GlassSurface.Scope scope:scopes) scope.decorate(); }
    void clearViews() { main.getLooper().getQueue().removeIdleHandler(prepareTensionWhenIdle); tensionPreparationQueued = false; endModal(); for (GlassSurface.Scope scope : scopes) scope.close(); scopes.clear(); for (GlassSurface surface : new ArrayList<>(surfaces.values())) surface.close(); surfaces.clear(); }
    String diagnostics() { return "玻璃背景：" + state + " / " + sourceKind + " · " + readyMillis + "ms\n取样/预处理：" + captures + "/" + preparations + " · 纹理 " + bytes() + "B · 表面 " + surfaces.size() + "\n取样尺寸：" + capturedWidth + "×" + capturedHeight + " → 显示区域 " + mappedWidth + "×" + mappedHeight + "\n"; }
    /** Deterministic emulator fixtures use the same production texture pipeline. */
    void fixture(Bitmap bitmap) { started = SystemClock.uptimeMillis(); current = () -> true; GlassBackdrop prepared = GlassBackdrop.prepare(bitmap, density, BLUR_LIMIT); shader = shader(prepared,0,0); backdrop = prepared; hadBackdrop = true; settled = true; preparations++; state = "fixture"; }
    @Override public void close() {
        main.getLooper().getQueue().removeIdleHandler(prepareTensionWhenIdle); tensionPreparationQueued = false;
        if (closed) return; closed = true; cancelSourceRefresh();
        if (sourceFade != null) { sourceFade.removeAllListeners(); sourceFade.removeAllUpdateListeners(); sourceFade.cancel(); sourceFade = null; }
        main.removeCallbacks(timeout); finish = null; current = null; clearViews(); shader = null;
        if (tensionRenderer != null) tensionRenderer.clear(); tensionRenderer = null; tensionSource = tensionStrongSource = null;
        if (backdrop != null) { backdrop.close(); backdrop = null; } state = "closed";
    }
}
