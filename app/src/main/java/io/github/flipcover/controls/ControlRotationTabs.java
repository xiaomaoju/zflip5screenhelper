package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.FrameLayout;
import java.util.function.IntConsumer;

/** Four angles in one liquid capsule; native buttons remain available without a backdrop. */
final class ControlRotationTabs extends FrameLayout {
    static final int WIDTH=192;
    private static final String[] LABELS={"0°","90°","180°","270°"};
    private final OriginalLiquidTabs original;
    private final Button[] tabs=new Button[LABELS.length];
    private final IntConsumer choose;
    private int selected;
    private float downX,downY;
    private boolean blocked,reselect,moved;

    ControlRotationTabs(Context context,int angle,IntConsumer choose) {
        super(context); this.choose=choose; setTag("detail-rotation-tabs"); setClipChildren(false); setClipToPadding(false);
        original=new OriginalLiquidTabs(context,this::commit,LABELS,new int[0],PanelUi.SLOT); addView(original,new LayoutParams(-1,-1));
        for (int i=0;i<tabs.length;i++) {
            int item=i; Button tab=new Button(context); tabs[i]=tab; tab.setTag("detail-angle-"+i); tab.setText(LABELS[i]); tab.setContentDescription("外屏旋转至"+LABELS[i]);
            tab.setTextSize(PanelUi.ACTION); tab.setAllCaps(false); tab.setSingleLine(); tab.setIncludeFontPadding(false); tab.setGravity(Gravity.CENTER);
            tab.setMinWidth(0); tab.setMinimumWidth(0); tab.setMinHeight(0); tab.setMinimumHeight(0); tab.setPadding(0,0,0,0); tab.setStateListAnimator(null);
            tab.setOnClickListener(v -> commit(item)); addView(tab);
        }
        source(angle); glass(null);
    }
    void source(int angle) {
        selected=angle; original.source(angle);
        for (int i=0;i<tabs.length;i++) { boolean active=i==angle; tabs[i].setSelected(active); tabs[i].setStateDescription(active ? "当前方向" : "切换方向"); tabs[i].setTextColor(active ? 0xFF0091FF : Ui.TEXT); tabs[i].setBackground(active ? Ui.background(getContext(),0x334F82AD,1000) : Ui.ripple(getContext(),Color.TRANSPARENT,1000)); }
    }
    private void commit(int angle) { if (angle<0 || angle>=tabs.length) return; source(angle); choose.accept(angle); }
    void glass(PanelGlassSession session) {
        boolean ready=session!=null && session.ready(); original.glass(ready ? session : null); original.setVisibility(ready ? VISIBLE : GONE);
        for (Button tab:tabs) tab.setVisibility(ready ? INVISIBLE : VISIBLE);
        setBackground(ready ? null : Ui.background(getContext(),0xB21A222C,1000));
    }
    void backdropMoved() { original.backdropMoved(); }
    private void cancelInteraction() { blocked=true; original.cancelGesture(); if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if (action==MotionEvent.ACTION_DOWN) { blocked=false; moved=false; downX=event.getX(); downY=event.getY(); reselect=original.getVisibility()==VISIBLE && downX>=0 && downX<getWidth() && (int)(downX*tabs.length/getWidth())==selected; if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true); }
        if (event.getPointerCount()>1 || action==MotionEvent.ACTION_CANCEL) { cancelInteraction(); MotionEvent cancel=MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); return true; }
        if (blocked) return true;
        int slop=android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
        if (Math.abs(event.getX()-downX)>slop || Math.abs(event.getY()-downY)>slop) moved=true;
        boolean handled=super.dispatchTouchEvent(event);
        // Upstream's draggable thumb consumes taps on the current tab. Here a tap still locks that angle.
        if (action==MotionEvent.ACTION_UP && reselect && !moved && event.getX()>=0 && event.getX()<getWidth() && event.getY()>=0 && event.getY()<getHeight()) commit(selected);
        if (action==MotionEvent.ACTION_UP && getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false);
        return handled;
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=resolveSize(Ui.dp(getContext(),WIDTH),widthSpec),height=resolveSize(Ui.dp(getContext(),PanelUi.SLOT),heightSpec),inset=Ui.dp(getContext(),2);
        original.measure(MeasureSpec.makeMeasureSpec(width,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
        for (int i=0;i<tabs.length;i++) tabs[i].measure(MeasureSpec.makeMeasureSpec((width-2*inset)*(i+1)/tabs.length-(width-2*inset)*i/tabs.length,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(Math.max(1,height-2*inset),MeasureSpec.EXACTLY));
        setMeasuredDimension(width,height);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        original.layout(0,0,getWidth(),getHeight()); int inset=Ui.dp(getContext(),2),x=inset;
        for (Button tab:tabs) { tab.layout(x,inset,x+tab.getMeasuredWidth(),getHeight()-inset); x+=tab.getMeasuredWidth(); }
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { super.onSizeChanged(w,h,oldw,oldh); if (oldw>0 && (w!=oldw || h!=oldh)) cancelInteraction(); }
    @Override protected void onDetachedFromWindow() { cancelInteraction(); super.onDetachedFromWindow(); }
}
