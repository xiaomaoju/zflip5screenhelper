package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** View adaptation of Backdrop LiquidToggle (Apache-2.0); only confirmed state colors the track. */
final class GlassToggle extends FrameLayout {
    private Boolean checked;
    private final View thumb;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track=new RectF(), screenTrack=new RectF();
    private final Matrix matrix=new Matrix();
    private float value, downX, downY, start, travel, glassProgress;
    private boolean dragging, canceled, pending;
    private ValueAnimator animation, materialAnimation;
    GlassToggle(Context context,String label,Boolean checked,Runnable action) {
        super(context); this.checked=checked; value=checked==null ? .5f : checked ? 1 : 0;
        setWillNotDraw(false); setClipChildren(false); setClickable(true); setFocusable(true); setLayoutParams(new LinearLayout.LayoutParams(Ui.dp(context,52),Ui.dp(context,36)));
        setContentDescription(label); setStateDescription(checked==null ? "未知，选择开启或关闭" : checked ? "已开启" : "已关闭"); setOnClickListener(v -> action.run());
        thumb=new View(context) { @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); paint.setStyle(Paint.Style.FILL); paint.setColor(0xFFFFFFFF); paint.setAlpha(Math.round(255*(1-glassProgress))); canvas.drawRoundRect(0,0,getWidth(),getHeight(),getHeight()/2f,getHeight()/2f,paint); } }; thumb.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); thumb.setBackground(Ui.background(context,dark() ? 0xFFADB9C5 : 0xFFF7FAFD,1000)); addView(thumb);
    }
    void state(Boolean next) { boolean changed=!java.util.Objects.equals(checked,next); checked=next; pending=false; setStateDescription(next==null ? "未知，选择开启或关闭" : next ? "已开启" : "已关闭"); if (changed) material(1,80); animateTo(resting()); }
    void pending(boolean target) { pending=true; setStateDescription("正在切换"); material(1,80); animateTo(target ? 1 : 0); }
    float glassProgress() { return glassProgress; }
    View thumb() { return thumb; }
    boolean dark() { return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES; }
    int trackColor() { return Boolean.TRUE.equals(checked) ? dark() ? 0xFF30D158 : 0xFF34C759 : dark() ? 0x5C787880 : 0x33787878; }
    RectF screenTrack() { matrix.reset(); transformMatrixToGlobal(matrix); screenTrack.set(track); matrix.mapRect(screenTrack); return screenTrack; }
    private float resting() { return checked==null ? .5f : checked ? 1 : 0; }
    private void position() { thumb.setTranslationX(travel*value); invalidate(); }
    private void material(float target,long duration) {
        if (materialAnimation!=null) materialAnimation.cancel();
        if (!isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { glassProgress=target; thumb.setScaleX(1+target*.4f); thumb.setScaleY(1+target*.4f); thumb.invalidate(); return; }
        materialAnimation=ValueAnimator.ofFloat(glassProgress,target); materialAnimation.setDuration(duration); materialAnimation.addUpdateListener(a -> { glassProgress=(float)a.getAnimatedValue(); thumb.setScaleX(1+glassProgress*.4f); thumb.setScaleY(1+glassProgress*.4f); thumb.invalidate(); }); materialAnimation.start();
    }
    private void animateTo(float target) {
        if (animation!=null) animation.cancel();
        if (!isAttachedToWindow() || !ValueAnimator.areAnimatorsEnabled()) { value=target; position(); material(0,0); return; }
        animation=ValueAnimator.ofFloat(value,target); animation.setDuration(260); animation.setInterpolator(new android.view.animation.DecelerateInterpolator(1.8f)); animation.addUpdateListener(a -> { value=(float)a.getAnimatedValue(); position(); });
        animation.addListener(new android.animation.AnimatorListenerAdapter() { boolean interrupted; @Override public void onAnimationCancel(android.animation.Animator a) { interrupted=true; } @Override public void onAnimationEnd(android.animation.Animator a) { if (!interrupted) material(0,170); } }); animation.start();
    }
    private void settle() { animateTo(resting()); }
    @Override protected void onMeasure(int w,int h) { int width=resolveSize(Ui.dp(getContext(),52),w),height=resolveSize(Ui.dp(getContext(),36),h); thumb.measure(MeasureSpec.makeMeasureSpec(Ui.dp(getContext(),28),MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Ui.dp(getContext(),17),MeasureSpec.EXACTLY)); setMeasuredDimension(width,height); }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        float width=Math.min(Ui.dp(getContext(),46),getWidth()),height=Math.min(Ui.dp(getContext(),20),getHeight()); track.set((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);
        float padding=Ui.dp(getContext(),2); travel=Math.max(0,width-thumb.getMeasuredWidth()-padding*2); int x=Math.round(track.left+padding),y=(getHeight()-thumb.getMeasuredHeight())/2; thumb.layout(x,y,x+thumb.getMeasuredWidth(),y+thumb.getMeasuredHeight()); position();
    }
    @Override protected void onDraw(Canvas canvas) { super.onDraw(canvas); paint.setStyle(Paint.Style.FILL); paint.setColor(trackColor()); if (!isEnabled()) paint.setAlpha(paint.getAlpha()/2); canvas.drawRoundRect(track,track.height()/2,track.height()/2,paint); }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (checked==null) { paint.setColor(RuntimeVisuals.blend(0xFF344150,dark() ? 0xFFF4F7FA : 0xFF344150,glassProgress)); paint.setTextAlign(Paint.Align.CENTER); paint.setTextSize(Ui.dp(getContext(),12)); canvas.drawText("?",thumb.getX()+thumb.getWidth()/2f,getHeight()/2f+Ui.dp(getContext(),4),paint); }
    }
    @Override public void setEnabled(boolean enabled) { super.setEnabled(enabled); if (thumb!=null) thumb.setAlpha(enabled ? 1 : .5f); invalidate(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false; int kind=event.getActionMasked(),slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (kind==MotionEvent.ACTION_DOWN) { if (animation!=null) animation.cancel(); downX=event.getX(); downY=event.getY(); start=value; dragging=false; canceled=false; material(1,100); getParent().requestDisallowInterceptTouchEvent(true); return true; }
        if (event.getPointerCount()>1 || kind==MotionEvent.ACTION_POINTER_DOWN || kind==MotionEvent.ACTION_CANCEL) { canceled=true; settle(); getParent().requestDisallowInterceptTouchEvent(false); return true; }
        if (kind==MotionEvent.ACTION_MOVE && !canceled) { float dx=event.getX()-downX,dy=event.getY()-downY; if (Math.abs(dy)>slop && Math.abs(dy)>Math.abs(dx)) { canceled=true; settle(); getParent().requestDisallowInterceptTouchEvent(false); } else if (Math.abs(dx)>slop || dragging) { dragging=true; if (checked!=null) { value=Math.max(0,Math.min(1,start+dx/Math.max(1,travel))); position(); } } return true; }
        if (kind==MotionEvent.ACTION_UP) { boolean inside=event.getX()>=-slop && event.getX()<=getWidth()+slop && event.getY()>=-slop && event.getY()<=getHeight()+slop; if (!canceled && inside && (!dragging || checked==null || (value>=.5f)!=checked)) performClick(); if (!pending) settle(); getParent().requestDisallowInterceptTouchEvent(false); return true; }
        return true;
    }
    @Override public boolean performClick() { return super.performClick(); }
    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) { super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.Switch"); info.setCheckable(checked!=null); info.setChecked(Boolean.TRUE.equals(checked)); }
    private void reset() { canceled=true; pending=false; if (animation!=null) animation.cancel(); if (materialAnimation!=null) materialAnimation.cancel(); value=resting(); glassProgress=0; thumb.setScaleX(1); thumb.setScaleY(1); position(); }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { super.onSizeChanged(w,h,oldw,oldh); if (oldw>0 && (w!=oldw || h!=oldh)) reset(); }
    @Override protected void onDetachedFromWindow() { reset(); super.onDetachedFromWindow(); }
}
