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
    private GlassBackdrop modalBackdrop;
    private int modalGeneration;
    private boolean modalPreparing;
    private Runnable modalReady;
    private final Runnable modalTimeout = () -> completeModal(null, null);
    private boolean closed, settled;
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
        Point size = new Point(); display.getRealSize(size); width = size.x; height = size.y; density = context.getResources().getDisplayMetrics().density;
    }
    static boolean allowed(Context context, Prefs prefs) { return Build.VERSION.SDK_INT >= 33 && prefs.panelBlur() && !context.getSystemService(PowerManager.class).isPowerSaveMode() && !context.getSystemService(KeyguardManager.class).isKeyguardLocked(); }
    boolean matches(Display display) { Point size = new Point(); display.getRealSize(size); return !closed && displayId == display.getDisplayId() && rotation == display.getRotation() && width == size.x && height == size.y; }
    boolean ready() { return !closed && shader != null; }
    /** Draw the existing capture into Compose's original LayerBackdrop; never captures or owns another bitmap. */
    void drawLocalBackdrop(android.graphics.Canvas canvas,View host,float offsetX,float offsetY) {
        canvas.drawColor(0xFF111820);
        if (!ready() || backdrop==null || !canvas.isHardwareAccelerated()) return;
        host.getLocationOnScreen(localBackdropPosition);
        float x=sourceX-localBackdropPosition[0]-offsetX,y=sourceY-localBackdropPosition[1]-offsetY;
        localBackdropBounds.set(x,y,x+backdrop.width,y+backdrop.height);
        canvas.drawBitmap(backdrop.sharp,null,localBackdropBounds,localBackdropPaint); draws++;
    }
    boolean active() { return !closed; }
    boolean preparing() { return !closed && !settled; }
    long bytes() { return (backdrop == null ? 0 : backdrop.bytes) + (modalBackdrop == null ? 0 : modalBackdrop.bytes); }
    RuntimeShader shader(View view) { for (android.view.ViewParent parent=view.getParent(); parent instanceof View ancestor; parent=ancestor.getParent()) if (ancestor instanceof DetailSheet) return modalShader == null ? shader : modalShader; return shader; }
    void capture(AccessibilityService service, BooleanSupplier current, Runnable finish) {
        if (closed || settled || captures > 0) return;
        this.current = current; this.finish = finish; started = SystemClock.uptimeMillis();
        if (displayId == Display.DEFAULT_DISPLAY || Build.VERSION.SDK_INT < 33) { complete(null,null,"unsupported"); return; }
        main.postDelayed(timeout, 150); captures++;
        try {
            android.graphics.Rect sourceBounds = new android.graphics.Rect(0,0,width,height);
            int windowId = -1;
            if (Build.VERSION.SDK_INT >= 34) {
                android.util.SparseArray<java.util.List<android.view.accessibility.AccessibilityWindowInfo>> all=service.getWindowsOnAllDisplays();
                java.util.List<android.view.accessibility.AccessibilityWindowInfo> windows = all.get(displayId);
                int layer = Integer.MIN_VALUE;
                try { if (windows != null) for (android.view.accessibility.AccessibilityWindowInfo window : windows) {
                    if (window.getType() == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && window.getLayer() > layer) {
                        android.graphics.Rect bounds = new android.graphics.Rect(); window.getBoundsInScreen(bounds);
                        layer=window.getLayer();
                        // A floating top app must not be replaced by a full-screen app beneath it.
                        windowId=bounds.contains(0,0,width,height) ? window.getId() : -1;
                        sourceBounds.set(windowId >= 0 ? bounds : new android.graphics.Rect(0,0,width,height));
                    }
                } } finally { for (int i=0;i<all.size();i++) for (android.view.accessibility.AccessibilityWindowInfo window:all.valueAt(i)) window.recycle(); }
            }
            sourceX=sourceBounds.left; sourceY=sourceBounds.top; sourceKind=windowId >= 0 ? "window" : "display";
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
    private boolean accepting() { return !closed && !settled && SystemClock.uptimeMillis() - started <= 150 && current.getAsBoolean(); }
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
        if (prepared != null) { shader=program; backdrop=prepared; preparations++; }
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
    void attach(View root, boolean notifications) { if (!closed) scopes.add(new GlassSurface.Scope(this, root, notifications)); }
    void decorate() { if (!closed) for (GlassSurface.Scope scope:scopes) scope.decorate(); }
    void clearViews() { endModal(); for (GlassSurface.Scope scope : scopes) scope.close(); scopes.clear(); for (GlassSurface surface : new ArrayList<>(surfaces.values())) surface.close(); surfaces.clear(); }
    String diagnostics() { return "玻璃背景：" + state + " / " + sourceKind + " · " + readyMillis + "ms\n取样/预处理：" + captures + "/" + preparations + " · 纹理 " + bytes() + "B · 表面 " + surfaces.size() + "\n取样尺寸：" + capturedWidth + "×" + capturedHeight + " → 显示区域 " + mappedWidth + "×" + mappedHeight + "\n"; }
    /** Deterministic emulator fixtures use the same production texture pipeline. */
    void fixture(Bitmap bitmap) { started = SystemClock.uptimeMillis(); current = () -> true; GlassBackdrop prepared = GlassBackdrop.prepare(bitmap, density, BLUR_LIMIT); shader = shader(prepared,0,0); backdrop = prepared; settled = true; preparations++; state = "fixture"; }
    @Override public void close() {
        if (closed) return; closed = true; main.removeCallbacks(timeout); finish = null; current = null; clearViews(); shader = null;
        if (backdrop != null) { backdrop.close(); backdrop = null; } state = "closed";
    }
}
