package io.github.flipcover.controls;

import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import java.util.ArrayList;

/** Opt-in material only. Keeps geometry, listeners, range semantics and content untouched. */
@android.annotation.TargetApi(33)
final class GlassSurface extends Drawable implements View.OnAttachStateChangeListener {
    enum Role {
        NOTIFICATION_PAGE(32,0,.76f,0), CONTROL_PAGE(32,1,.76f,0), LAUNCHER_PANEL(24,2,.72f,.025f), LAUNCHER_DOCK(24,2,.72f,.025f), LAUNCHER_SEARCH(20,2,.55f,.055f), FOLDER(14,2,.72f,.025f), CARD(26,1,.74f,.08f), BUTTON(1000,1,.65f,.055f), TILE(20,2,.65f,.09f), PICKER(1000,1,.65f,.055f), TOGGLE_LIGHT(1000,1,.92f,.08f), TOGGLE_DARK(1000,1,.72f,.08f), TEXT(16,2,.65f,.09f), EDITOR(20,2,.65f,.09f), INPUT(12,2,.55f,.055f), SLIDER(1000,1,.65f,.055f), CLEAR(0,0,1,0), STACK(0,0,1,0), TASK_PREVIEW(1000,0,.90f,0), TASK_ACTION(1000,1,.42f,.03f);
        final float radius, gain, tint; final int source;
        Role(float radius,int source,float gain,float tint) { this.radius=radius; this.source=source; this.gain=gain; this.tint=tint; }
    }
    static final String PROGRAM = TaskPreviewView.EDGE_LIGHTING + """
        uniform shader sharp;
        uniform shader soft;
        uniform shader strong;
        uniform float2 size;
        uniform float2 layoutOffset;
        uniform float2 origin;
        uniform float4 frame;
        uniform float4 transform;
        uniform float radius;
        uniform float edgeWidth;
        uniform float bend;
        uniform float source;
        uniform float gain;
        uniform float tint;
        uniform float selected;
        uniform float3 selectionColor;
        uniform float pressed;
        uniform float toggle;
        uniform float4 toggleTrack;
        uniform float4 toggleColor;
        uniform float readable;
        uniform float taskLens;
        uniform float4 safeArea;
        uniform float unsafeBand;
        half4 main(float2 p) {
            p -= layoutOffset;
            float2 c = p - size * .5;
            float2 q = abs(c) - (size * .5 - radius);
            float2 outside = max(q,0.0);
            float distance = length(outside) + min(max(q.x,q.y),0.0) - radius;
            float2 direction = length(outside) > .001 ? sign(c) * normalize(outside) : sign(c) * (q.x > q.y ? float2(1,0) : float2(0,1));
            float edge = clamp(1.0 + distance / max(edgeWidth,1.0),0.0,1.0);
            float curve = edge * edge * (3.0 - 2.0 * edge);
            float2 local = p + direction * curve * bend;
            float2 samplePoint = origin + float2(dot(transform.xy,local),dot(transform.zw,local));
            half3 color;
            if (source < .5) color = sharp.eval(samplePoint).rgb;
            else if (source < 1.5) color = soft.eval(samplePoint).rgb;
            else color = strong.eval(samplePoint).rgb;
            float overflow = max(max(safeArea.x - p.x, p.x - safeArea.z), max(safeArea.y - p.y, p.y - safeArea.w));
            float unsafeMix = unsafeBand > 0.0 ? smoothstep(-unsafeBand, unsafeBand, overflow) : 0.0;
            color = mix(color, strong.eval(samplePoint).rgb, unsafeMix);
            if (edge <= 0.0 && source < .5) return half4(color * gain,1);
            half3 crisp = color;
            if (edge > 0.0) crisp = source > .5 && readable < .5 ? soft.eval(samplePoint).rgb : sharp.eval(samplePoint).rgb;
            // Outside the captured frame there is no real detail to refract. Use the
            // cached edge average instead of stretching one bright boundary pixel.
            if (source < .5 && edge > 0.0) {
                float2 overflow=max(frame.xy-samplePoint,samplePoint-frame.zw);
                float fade=smoothstep(0.0,max(edgeWidth*.45,1.0),max(overflow.x,overflow.y));
                if (fade > 0.0) { half3 average=soft.eval(samplePoint).rgb; color=mix(color,average,fade); crisp=mix(crisp,average,fade); }
            }
            if (toggle > .5) {
                float tr=toggleTrack.w*.5;
                float2 tq=abs(samplePoint-toggleTrack.xy)-(toggleTrack.zw*.5-tr);
                float td=length(max(tq,0.0))+min(max(tq.x,tq.y),0.0)-tr;
                float coverage=(1.0-smoothstep(-1.0,1.0,td))*toggleColor.a;
                color=mix(color,half3(toggleColor.rgb),coverage); crisp=mix(crisp,half3(toggleColor.rgb),coverage);
            }
            if (taskLens > 0.0) return half4(taskEdgeLight(color * gain, 1.0, max(-distance,0.0), direction, c / (size * .5), taskLens, pressed), 1);
            float clarity = readable > .5 ? smoothstep(.65,.98,edge)*.50 : smoothstep(.12,.85,edge)*.65;
            color = mix(color,crisp,clarity);
            color = color * mix(gain,.90,edge) + half3(mix(tint,.045,edge));
            // Preserve legibility over light apps without changing notification text semantics.
            float luminance=dot(color,half3(.2126,.7152,.0722));
            color *= mix(1.0,min(1.0,.38/max(luminance,.001)),readable*(1.0-edge));
            color = mix(color,half3(selectionColor),selected*.96);
            color += half3(pressed*.05);
            float light = dot(direction,float2(-.60,-.80));
            float highlight = smoothstep(.78,1.0,edge) * (.035+.22*pow(abs(light),4.0));
            color += (half3(.25)+crisp*.75)*highlight;
            color *= 1.0-.18*edge*(1.0-edge);
            color -= edge*edge*.035*max(-light,0.0);
            return half4(clamp(color,0.0,1.0),1);
        }
        """;
    final Role role;
    private final PanelGlassSession session;
    private final View owner;
    private final SharedGlassHost sharedHost;
    private final NotificationForceHeader notificationHeader;
    private final View headerNode;
    private Drawable original;
    private final boolean originalSkip;
    private Drawable installed;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Matrix matrix = new Matrix();
    private final Matrix controlBase = new Matrix(), controlInverse = new Matrix(), controlLocal = new Matrix(), controlCombined = new Matrix();
    private final boolean controlPaint;
    private final float[] coordinates = new float[9], previous = new float[9];
    private final Matrix launcherMatrix = new Matrix();
    private final float[] launcherCoordinates = new float[9];
    private final Rect visible = new Rect();
    private final float density;
    private final ArrayList<Letters> letters = new ArrayList<>();
    private final boolean originalClip;
    private final AppHubView launcher;
    private boolean closed, selected, pressed, working;
    private int opacity = 255;
    private Shader rim;
    private int rimWidth, rimHeight;
    GlassSurface(PanelGlassSession session, View owner, Role role) {
        this.session=session; this.owner=owner; this.role=role; controlPaint=ControlFeedback.hasPaint(owner); original=owner.getBackground(); originalSkip=owner.willNotDraw(); originalClip=owner.getClipToOutline(); density=owner.getResources().getDisplayMetrics().density;
        SharedGlassHost host = null;
        for (android.view.ViewParent parent = owner.getParent(); parent instanceof View ancestor; parent = ancestor.getParent()) if (ancestor instanceof SharedGlassHost group) { host = group; break; }
        sharedHost = host;
        AppHubView hub = null;
        if (role == Role.LAUNCHER_PANEL || role == Role.LAUNCHER_DOCK || role == Role.LAUNCHER_SEARCH || role == Role.FOLDER) for (android.view.ViewParent parent = owner.getParent(); parent instanceof View ancestor; parent = parent.getParent()) if (ancestor instanceof AppHubView found) { hub = found; break; }
        launcher = hub;
        NotificationForceHeader force = null; View node = owner;
        for (android.view.ViewParent parent = owner.getParent(); parent instanceof View ancestor; parent = parent.getParent()) { if (ancestor instanceof NotificationForceHeader header) { force = header; break; } node = ancestor; }
        notificationHeader = force; headerNode = node;
        java.util.Arrays.fill(previous,Float.NaN);
        if (role == Role.CARD || role == Role.TEXT) collectLetters(owner);
    }
    void install() {
        if (owner instanceof SharedGlassHost host) host.glass(session);
        if (original instanceof RuntimeVisuals.Surface surface) { surface.onViewDetachedFromWindow(owner); owner.removeOnAttachStateChangeListener(surface); }
        if (owner instanceof LevelSlider slider) slider.glass(this);
        else if (role == Role.STACK) { installed=null; owner.setBackground(null); }
        else {
            installed = role == Role.FOLDER || role == Role.TASK_PREVIEW || role == Role.CLEAR || role == Role.STACK || role == Role.NOTIFICATION_PAGE || role == Role.CONTROL_PAGE ? this : new RippleDrawable(ColorStateList.valueOf(0x24FFFFFF),this,Ui.background(owner.getContext(),Color.WHITE,role.radius));
            owner.setBackground(installed);
        }
        if (role == Role.STACK) owner.setWillNotDraw(true);
        if (role == Role.LAUNCHER_DOCK) owner.setClipToOutline(false); // Dock clips its moving capsule in paint space.
        else if (page() || launcherPlate()) owner.setClipToOutline(true);
        owner.addOnAttachStateChangeListener(this); refresh();
    }
    private void collectLetters(View view) { if (view instanceof TextView text) letters.add(new Letters(text)); if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) collectLetters(group.getChildAt(i)); }
    void refresh() { for (Letters text : letters) text.apply(session.ready()); if (owner instanceof ControlSourceTabs tabs) tabs.glass(session); if (owner instanceof ControlRotationTabs tabs) tabs.glass(session); if (owner instanceof RecentTasksView tasks) tasks.glassReady(session.taskOptics()); owner.invalidate(); }
    void position() {
        if (closed || !owner.isShown() || !owner.getGlobalVisibleRect(visible)) return;
        matrix.reset(); owner.transformMatrixToGlobal(matrix); if (controlPaint) ControlFeedback.materialPosition(owner,matrix,controlBase,controlInverse,controlLocal,controlCombined); matrix.getValues(coordinates);
        if (launcher != null) LauncherMotionLayout.materialPosition(owner, coordinates, launcherMatrix, launcherCoordinates);
        if (!java.util.Arrays.equals(coordinates,previous)) { System.arraycopy(coordinates,0,previous,0,9); owner.invalidate(); if (owner instanceof ControlSourceTabs tabs) tabs.backdropMoved(); if (owner instanceof ControlRotationTabs tabs) tabs.backdropMoved(); if (page()) owner.invalidateOutline(); }
    }
    @Override public boolean isStateful() { return true; }
    @Override protected boolean onStateChange(int[] states) {
        selected=pressed=working=false;
        for (int state:states) { selected |= state == android.R.attr.state_selected; pressed |= state == android.R.attr.state_pressed; working |= state == android.R.attr.state_activated; }
        invalidateSelf(); return true;
    }
    private boolean page() { return role==Role.NOTIFICATION_PAGE || role==Role.CONTROL_PAGE; }
    private boolean launcherPlate() { return role == Role.LAUNCHER_PANEL || role == Role.LAUNCHER_DOCK; }
    void fallbackBackground(Drawable value) { original = value; }
    private float radius(float width,float height) {
        if (role == Role.TASK_PREVIEW) return TaskPreviewView.cornerRadius(owner,width,height);
        float requested = role == Role.FOLDER ? AppLauncherStyle.folderRadius(owner.getContext()) : role == Role.LAUNCHER_SEARCH || role == Role.LAUNCHER_PANEL ? AppLauncherStyle.panelRadius(owner.getContext()) : role == Role.LAUNCHER_DOCK ? AppLauncherStyle.dockRadius(owner.getContext()) : role.radius;
        float radius=Math.min(requested*density,Math.min(width,height)/2f);
        if (!page() || android.os.Build.VERSION.SDK_INT<31 || owner.getRootWindowInsets()==null) return radius;
        float physical=Float.MAX_VALUE;
        for (int i=0;i<4;i++) { android.view.RoundedCorner corner=owner.getRootWindowInsets().getRoundedCorner(i); if (corner!=null) physical=Math.min(physical,corner.getRadius()); }
        if (physical==Float.MAX_VALUE) physical=0;
        // Extend one pixel beneath the physical mask to avoid an antialiasing seam.
        physical=Math.max(0,physical-1); float lifted=Math.min(1,Math.abs(owner.getTranslationY())/Math.max(1,radius*2));
        if (owner instanceof InterfaceCard card) lifted = Math.max(lifted, card.insetProgress());
        return physical+(radius-physical)*lifted;
    }
    private void visualBounds(RectF bounds) { if (owner instanceof LauncherMotionLayout group) group.materialBounds(bounds); if (owner instanceof InterfaceCard card) card.visualBounds(bounds); else if (launcher != null) launcher.glassBounds(owner, bounds); }
    @Override public void getOutline(Outline outline) { box.set(getBounds()); visualBounds(box); if (box.isEmpty()) { outline.setEmpty(); return; } box.round(visible); outline.setRoundRect(visible,radius(box.width(),box.height())); outline.setAlpha(1); }
    @Override public void draw(Canvas canvas) { box.set(getBounds()); draw(canvas,box); }
    void draw(Canvas canvas, RectF bounds) {
        if (closed || role == Role.CLEAR || role == Role.STACK) return;
        float alpha = sharedHost == null ? 1 : sharedHost.independentGlassAlpha(owner);
        if (alpha <= 0) return;
        drawMaterial(canvas, bounds, Math.round(opacity * alpha));
    }
    private void drawMaterial(Canvas canvas, RectF bounds, int materialOpacity) {
        box.set(bounds); visualBounds(box); if (box.isEmpty()) return; float radius=radius(box.width(),box.height());
        paint.setStyle(Paint.Style.FILL); paint.setColor(Color.WHITE); paint.setAlpha(materialOpacity);
        RuntimeShader shader=session.shader(owner);
        if (shader != null && canvas.isHardwareAccelerated()) {
            if (session.backdropAlpha() < 1) {
                paint.setColor(fallbackColor()); paint.setAlpha(Color.alpha(paint.getColor()) * materialOpacity / 255); canvas.drawRoundRect(box, radius, radius, paint);
                paint.setColor(Color.WHITE); paint.setAlpha(Math.round(materialOpacity * session.backdropAlpha()));
            }
            matrix.reset(); owner.transformMatrixToGlobal(matrix); if (controlPaint) ControlFeedback.materialPosition(owner,matrix,controlBase,controlInverse,controlLocal,controlCombined); matrix.getValues(coordinates);
            if (launcher != null) LauncherMotionLayout.materialPosition(owner, coordinates, launcherMatrix, launcherCoordinates);
            float groupOffsetY = sharedHost == null ? 0 : sharedHost.glassOffsetY();
            float groupOffsetX = sharedHost == null ? 0 : sharedHost.glassOffsetX();
            if (notificationHeader != null) { groupOffsetX += notificationHeader.visualX(headerNode); groupOffsetY += notificationHeader.visualY(headerNode); }
            shader.setFloatUniform("size",box.width(),box.height()); shader.setFloatUniform("layoutOffset", box.left, box.top);
            shader.setFloatUniform("origin",coordinates[2] + coordinates[0] * box.left + coordinates[1] * box.top - session.taskReactionX() + groupOffsetX,coordinates[5] + coordinates[3] * box.left + coordinates[4] * box.top - session.taskReactionY() + groupOffsetY);
            shader.setFloatUniform("transform",coordinates[0],coordinates[1],coordinates[3],coordinates[4]);
            // Large page edges should not pull distant content back into the panel while dragging.
            float bevel=Math.min(radius,Math.min(box.height()*.38f,(page() ? 12 : role == Role.TASK_PREVIEW ? 16 : 22)*density));
            shader.setFloatUniform("radius",radius); shader.setFloatUniform("edgeWidth",bevel); shader.setFloatUniform("bend",role == Role.TASK_PREVIEW ? bevel*.55f : bevel*(page() ? .80f : .85f));
            shader.setFloatUniform("source",role.source); shader.setFloatUniform("gain",role.gain); shader.setFloatUniform("tint",role.tint);
            if (role == Role.FOLDER && owner.getParent() instanceof AppFolderTile folder) { int tint = folder.color(); shader.setFloatUniform("selected", .12f); shader.setFloatUniform("selectionColor", Color.red(tint) / 255f, Color.green(tint) / 255f, Color.blue(tint) / 255f); }
            else { shader.setFloatUniform("selected",selected && (role == Role.BUTTON || role == Role.TILE || role == Role.PICKER) ? 1 : 0); shader.setFloatUniform("selectionColor",role == Role.PICKER ? .14f : .94f,role == Role.PICKER ? .23f : .95f,role == Role.PICKER ? .38f : .98f); }
            shader.setFloatUniform("pressed",pressed ? 1 : 0);
            shader.setFloatUniform("taskLens",role == Role.TASK_PREVIEW ? density : 0);
            if (owner instanceof InterfaceCard card && card.safeArea() != null) {
                DockGeometry.Box safe = card.safeArea(); shader.setFloatUniform("safeArea", safe.x() - box.left, safe.y() - box.top, safe.right() - box.left, safe.bottom() - box.top); shader.setFloatUniform("unsafeBand", 20 * density);
            } else { shader.setFloatUniform("safeArea", 0, 0, box.width(), box.height()); shader.setFloatUniform("unsafeBand", 0); }
            shader.setFloatUniform("readable",launcherPlate() || role == Role.FOLDER || role == Role.LAUNCHER_SEARCH || role == Role.TASK_ACTION || role == Role.CARD || role == Role.TEXT || role == Role.TILE || role == Role.EDITOR || role == Role.INPUT ? 1 : 0);
            boolean toggle=owner.getParent() instanceof GlassToggle; shader.setFloatUniform("toggle",toggle ? 1 : 0);
            if (toggle) { GlassToggle control=(GlassToggle)owner.getParent(); RectF track=control.screenTrack(); int color=control.trackColor(); shader.setFloatUniform("toggleTrack",track.centerX(),track.centerY(),track.width(),track.height()); shader.setFloatUniform("toggleColor",Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,Color.alpha(color)/255f); }
            paint.setShader(shader); session.draws++;
        } else {
            paint.setShader(null); paint.setColor(fallbackColor());
            paint.setAlpha(Color.alpha(paint.getColor())*materialOpacity/255);
        }
        canvas.drawRoundRect(box,radius,radius,paint); paint.setShader(null);
        float line=Math.max(1,density*(role == Role.BUTTON ? .65f : .45f)); box.inset(line/2,line/2); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(line); paint.setAlpha(materialOpacity);
        if (rim == null || rimWidth != getBounds().width() || rimHeight != getBounds().height()) {
            rimWidth=getBounds().width(); rimHeight=getBounds().height(); rim=new LinearGradient(0,0,Math.max(1,box.width()),Math.max(1,box.height()),new int[]{0x60FFFFFF,0x08FFFFFF,0x38FFFFFF},new float[]{0,.48f,1},Shader.TileMode.CLAMP);
        }
        boolean busyRim=working && role!=Role.SLIDER;
        paint.setColor(busyRim ? 0xFFAFD3FF : Color.WHITE); paint.setAlpha(materialOpacity); if (!busyRim) paint.setShader(rim); canvas.drawRoundRect(box,radius,radius,paint); paint.setShader(null);
    }
    @Override public void setAlpha(int alpha) { opacity=alpha; invalidateSelf(); }
    private int fallbackColor() {
        if (role == Role.FOLDER && owner.getParent() instanceof AppFolderTile folder) return folder.color();
        return selected && role == Role.PICKER ? SettingsUi.ACTIVE : selected && (role == Role.BUTTON || role == Role.TILE) ? Ui.ACTIVE : role == Role.NOTIFICATION_PAGE ? 0x3D000000 : role == Role.CONTROL_PAGE ? 0xEB111820 : launcherPlate() ? 0xEB22272F : 0xEE293340;
    }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { close(); }
    void close() {
        if (closed) return; closed=true; paint.setShader(null); owner.removeOnAttachStateChangeListener(this);
        if (owner instanceof SharedGlassHost host) host.glass(null);
        if (owner instanceof ControlSourceTabs tabs) tabs.glass(null);
        if (owner instanceof ControlRotationTabs tabs) tabs.glass(null);
        if (owner instanceof RecentTasksView tasks) tasks.glassReady(false);
        if (owner instanceof LevelSlider slider) slider.glass(null); else if (owner.getBackground() == installed) owner.setBackground(original);
        if (role == Role.STACK) owner.setWillNotDraw(originalSkip);
        if (page() || launcherPlate()) owner.setClipToOutline(originalClip);
        if (original instanceof RuntimeVisuals.Surface surface) owner.addOnAttachStateChangeListener(surface);
        for (Letters text : letters) text.apply(false); letters.clear(); session.forget(owner,this);
    }
    private static final class Letters {
        final TextView view; final float radius,x,y; final int color; final ColorStateList textColors;
        Letters(TextView view) { this.view=view; radius=view.getShadowRadius(); x=view.getShadowDx(); y=view.getShadowDy(); color=view.getShadowColor(); textColors=view.getTextColors(); }
        void apply(boolean glass) { view.setShadowLayer(glass ? Ui.dp(view.getContext(),1.5f) : radius,glass ? 0 : x,glass ? Ui.dp(view.getContext(),.5f) : y,glass ? 0xCC101820 : color); if (textColors.getDefaultColor()==Ui.MUTED) view.setTextColor(glass ? ColorStateList.valueOf(0xFFDCE3ED) : textColors); }
    }
    /** Discovery runs on layout, never on each frame. Drawing updates only registered positions. */
    static final class Scope implements AutoCloseable {
        private final PanelGlassSession session; private final View root; private final boolean notifications;
        private final ViewTreeObserver.OnGlobalLayoutListener layout=this::decorate;
        private final ViewTreeObserver.OnPreDrawListener positions=this::updatePositions;
        private boolean updatePositions() { session.positions(); return true; }
        Scope(PanelGlassSession session,View root,boolean notifications) { this.session=session; this.root=root; this.notifications=notifications; root.getViewTreeObserver().addOnGlobalLayoutListener(layout); root.getViewTreeObserver().addOnPreDrawListener(positions); decorate(); }
        private void bind(View view,Role role) { session.bind(view,role); }
        void decorate() { visit(root,false,false,false); }
        private void visit(View view,boolean editor,boolean detail,boolean inCard) {
            if (view instanceof InterfaceCard card) { inCard = true; bind(view,!card.plateVisible() ? Role.CLEAR : card.material() == InterfaceCard.Material.FROSTED ? Role.CONTROL_PAGE : Role.NOTIFICATION_PAGE); }
            if (view instanceof RecentTasksView tasks) { tasks.glass(session, !inCard); return; }
            Object tag=view.getTag(); boolean inEditor=editor || view instanceof ControlEditorView, inDetail=detail || view instanceof DetailSheet;
            if (view instanceof PanelActionSlot slot) slot.syncVisibility();
            if (view instanceof PanelSurface) bind(view,inCard ? Role.CLEAR : notifications ? Role.NOTIFICATION_PAGE : Role.CONTROL_PAGE);
            else if (view instanceof DetailSheet sheet) sheet.glassControls();
            else if (view instanceof GlassToggle toggle) { bind(toggle.thumb(),toggle.dark() ? Role.TOGGLE_DARK : Role.TOGGLE_LIGHT); return; }
            else if (view instanceof ControlSourceTabs || view instanceof ControlRotationTabs) { bind(view,Role.CLEAR); return; }
            else if (view instanceof ControlEditorView) bind(view,Role.CLEAR);
            else if (view instanceof SharedGlassHost host) { bind(view,Role.STACK); bind(host.glassBody(),host.glassBodyRole()); for (View face : host.glassActions()) bind(face,Role.BUTTON); }
            else if (view instanceof LevelSlider) bind(view,Role.SLIDER);
            else if (inCard && ("hub-rail".equals(tag) || "hub-catalog".equals(tag))) bind(view, Role.LAUNCHER_PANEL);
            else if (inCard && "hub-dock".equals(tag)) bind(view, Role.LAUNCHER_DOCK);
            else if (inCard && "hub-search-field".equals(tag)) bind(view, Role.LAUNCHER_SEARCH);
            else if (inCard && "folder-surface".equals(tag)) bind(view, Role.FOLDER);
            else if ("hub-apps".equals(tag) || "hub-clear".equals(tag)) return; // These Dock actions retain their original transparent feedback.
            else if ("notification-expand".equals(tag)) return; // Keep its transparent ripple; no selected glass disc.
            else if (view instanceof Panels.Tile tile) bind(tile.compact ? tile.face : tile,tile.compact ? Role.BUTTON : Role.TILE);
            else if (view instanceof ControlEditGrid.Cell cell) cell.glass(session);
            else if ("media-card".equals(tag) || "detail-card".equals(tag)) bind(view,Role.TEXT);
            else if (view instanceof ControlCandidatesView candidates) { candidates.glass(session); bind(view,Role.EDITOR); }
            else if (view instanceof EditText && (inEditor || inDetail)) bind(view,Role.INPUT);
            else if (view instanceof ImageButton) { if (inDetail || inEditor) PanelActionSlot.wrap(view); bind(view,inDetail ? Role.PICKER : Role.BUTTON); }
            else if (view instanceof Button button) { if ((inDetail || inEditor) && !(view.getParent() instanceof PanelActionSlot)) { if (!"control-editor-undo".equals(tag) && !"control-editor-done".equals(tag)) PanelUi.action(button); PanelActionSlot.wrap(view); } bind(view,inEditor ? Role.PICKER : inDetail || "notification-clear-all".equals(tag) || "notification-new".equals(tag) ? Role.BUTTON : Role.TILE); }
            if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) visit(group.getChildAt(i),inEditor,inDetail,inCard);
        }
        @Override public void close() { root.getViewTreeObserver().removeOnGlobalLayoutListener(layout); root.getViewTreeObserver().removeOnPreDrawListener(positions); }
    }
}
