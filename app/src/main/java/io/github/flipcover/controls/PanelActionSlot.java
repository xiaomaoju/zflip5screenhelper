package io.github.flipcover.controls;

import android.graphics.Rect;
import android.view.TouchDelegate;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

/** Smaller header artwork in its original layout and touch slot. */
final class PanelActionSlot extends FrameLayout {
    static void wrap(View action) {
        if (action.getParent() instanceof android.widget.LinearLayout parent) { int index=parent.indexOfChild(action); android.view.ViewGroup.LayoutParams size=action.getLayoutParams(); parent.removeViewAt(index); parent.addView(new PanelActionSlot(action),index,size); }
    }
    private final View action;
    private final Rect target=new Rect();
    private final android.view.ViewTreeObserver.OnPreDrawListener visibility=() -> { syncVisibility(); return true; };
    private boolean delegated;
    PanelActionSlot(View action) {
        super(action.getContext()); this.action=action; setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        int width=action.getLayoutParams()!=null && action.getLayoutParams().width==LayoutParams.WRAP_CONTENT ? LayoutParams.WRAP_CONTENT : LayoutParams.MATCH_PARENT;
        action.setScaleX(PanelUi.ACTION_SCALE); action.setScaleY(PanelUi.ACTION_SCALE); addView(action,new LayoutParams(width,-1,android.view.Gravity.CENTER)); syncVisibility();
        int slop=ViewConfiguration.get(getContext()).getScaledTouchSlop();
        setTouchDelegate(new TouchDelegate(target,action) {
            @Override public boolean onTouchEvent(MotionEvent event) {
                int kind=event.getActionMasked();
                if (action.getVisibility()!=VISIBLE || !action.isEnabled()) { delegated=false; return false; }
                if (kind==MotionEvent.ACTION_DOWN) delegated=target.contains((int)event.getX(),(int)event.getY());
                if (!delegated) return false;
                boolean hit=event.getX()>=-slop && event.getX()<=getWidth()+slop && event.getY()>=-slop && event.getY()<=getHeight()+slop;
                MotionEvent forwarded=MotionEvent.obtain(event); forwarded.setLocation(hit ? action.getWidth()/2f : -2*slop,hit ? action.getHeight()/2f : -2*slop);
                boolean handled=action.dispatchTouchEvent(forwarded); forwarded.recycle();
                if (kind==MotionEvent.ACTION_UP || kind==MotionEvent.ACTION_CANCEL) delegated=false;
                return handled;
            }
        });
    }
    void syncVisibility() { if (getVisibility()!=action.getVisibility()) setVisibility(action.getVisibility()); }
    @Override public boolean isLayoutRequested() { return action!=null && getVisibility()!=action.getVisibility() ? false : super.isLayoutRequested(); }
    @Override public void requestLayout() { if (action!=null) syncVisibility(); super.requestLayout(); }
    @Override public void onDescendantInvalidated(View child,View target) { syncVisibility(); super.onDescendantInvalidated(child,target); }
    @Override protected void onMeasure(int width,int height) { syncVisibility(); if (action.getVisibility()==GONE) setMeasuredDimension(0,0); else super.onMeasure(width,height); }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        super.onLayout(changed,l,t,r,b);
        target.set(0,0,getWidth(),getHeight());
        if (action instanceof android.widget.ImageButton && Math.abs(action.getWidth()-action.getHeight())<2) {
            float diameter=Ui.dp(getContext(),PanelUi.SLOT)*PanelUi.ACTION_SCALE; action.setScaleX(Math.min(PanelUi.ACTION_SCALE,diameter/Math.max(1,action.getWidth()))); action.setScaleY(Math.min(PanelUi.ACTION_SCALE,diameter/Math.max(1,action.getHeight())));
            int pad=Math.round(action.getWidth()*(PanelUi.SLOT-PanelUi.ACTION_ICON)*.5f/PanelUi.SLOT); if (action.getPaddingLeft()!=pad) action.setPadding(pad,pad,pad,pad);
        }
    }
    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); getViewTreeObserver().addOnPreDrawListener(visibility); }
    @Override protected void onDetachedFromWindow() { delegated=false; getViewTreeObserver().removeOnPreDrawListener(visibility); super.onDetachedFromWindow(); }
}
