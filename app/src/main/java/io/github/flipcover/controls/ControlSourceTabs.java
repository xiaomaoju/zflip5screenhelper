package io.github.flipcover.controls;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import java.util.function.Consumer;

/** Native host boundary only. Optics and animation belong to upstream LiquidBottomTabs. */
final class ControlSourceTabs extends FrameLayout {
    static final int HEIGHT=28;
    private static final String[] IDS={"builtin","tiles","apps"}, LABELS={"内置功能","应用磁贴","应用"};
    private final Button[] tabs=new Button[3];
    private final ImageButton search,collapse;
    private final OriginalLiquidTabs original;
    private final Consumer<String> choose;
    private int selected,inset,tabsRight;
    private boolean tracking,blocked;

    ControlSourceTabs(Context context,String source,Consumer<String> choose,Runnable find,Runnable hide) {
        super(context); this.choose=choose;
        selected=index(source); setTag("control-source-tabs"); setClipChildren(false); setClipToPadding(false);
        original=new OriginalLiquidTabs(context,this::commit); addView(original);
        int[] icons={R.drawable.ic_ms_tune,R.drawable.ic_ms_view_carousel,R.drawable.ic_ms_apps};
        for (int i=0;i<tabs.length;i++) {
            int item=i; Button tab=new Button(context); tabs[i]=tab; tab.setTag("library-tab-"+IDS[i]); tab.setText(LABELS[i]); tab.setContentDescription(LABELS[i]);
            tab.setTextSize(8); tab.setAllCaps(false); tab.setSingleLine(); tab.setIncludeFontPadding(false); tab.setGravity(Gravity.CENTER);
            tab.setMinWidth(0); tab.setMinimumWidth(0); tab.setMinHeight(0); tab.setMinimumHeight(0); tab.setPadding(0,0,0,0); tab.setBackgroundColor(Color.TRANSPARENT); tab.setStateListAnimator(null);
            android.graphics.drawable.Drawable icon=Ui.icon(context,icons[i],Ui.TEXT); icon.setBounds(0,0,Ui.dp(context,11),Ui.dp(context,11)); tab.setCompoundDrawables(null,icon,null,null); tab.setCompoundDrawablePadding(0);
            tab.setOnClickListener(v -> commit(item)); addView(tab);
        }
        search=Ui.iconButton(context,R.drawable.ic_ms_search,"搜索候选按钮",find); search.setTag("control-candidates-search"); search.setBackgroundColor(Color.TRANSPARENT); addView(search);
        collapse=Ui.iconButton(context,R.drawable.ic_ms_keyboard_arrow_down,"收起候选区",hide); collapse.setTag("control-candidates-collapse"); collapse.setBackgroundColor(Color.TRANSPARENT); addView(collapse);
        for (ImageButton action:new ImageButton[]{search,collapse}) action.setPadding(Ui.dp(context,6),Ui.dp(context,6),Ui.dp(context,6),Ui.dp(context,6));
        colors(); original.source(selected); glass(null);
    }
    private int index(String source) { for (int i=0;i<IDS.length;i++) if (IDS[i].equals(source)) return i; return 0; }
    int selectedIndex() { return selected; }
    View originalView() { return original; }
    boolean usesOriginal() { return original.getVisibility()==VISIBLE; }
    void source(String source) { int next=index(source); if (selected==next) return; selected=next; colors(); original.source(next); }
    private void colors() { for (int i=0;i<tabs.length;i++) { boolean active=i==selected; tabs[i].setSelected(active); tabs[i].setStateDescription(active ? "已选择" : "未选择"); int color=active ? 0xFF0091FF : Ui.TEXT; tabs[i].setTextColor(color); tabs[i].getCompoundDrawables()[1].setTint(color); } }
    private void commit(int next) { if (next==selected || next<0 || next>=IDS.length) return; selected=next; colors(); original.source(next); choose.accept(IDS[next]); }
    void glass(PanelGlassSession session) {
        boolean available=session!=null && session.ready(); original.glass(available ? session : null); original.setVisibility(available ? VISIBLE : GONE);
        for (Button tab:tabs) tab.setVisibility(available ? INVISIBLE : VISIBLE);
        setBackground(available ? null : Ui.background(getContext(),0xB21A222C,1000));
    }
    void backdropMoved() { original.backdropMoved(); }
    private void cancelInteraction() { tracking=false; blocked=true; original.cancelGesture(); if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false); }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        int action=event.getActionMasked();
        if (action==MotionEvent.ACTION_DOWN) {
            blocked=false; tracking=event.getX()>=0 && event.getX()<tabsRight;
            // The original capsule owns this pointer until release, including outside its bounds.
            if (tracking && getParent()!=null) getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (event.getPointerCount()>1 || action==MotionEvent.ACTION_CANCEL) {
            cancelInteraction(); MotionEvent cancel=MotionEvent.obtain(event); cancel.setAction(MotionEvent.ACTION_CANCEL); super.dispatchTouchEvent(cancel); cancel.recycle(); return true;
        }
        if (blocked) return true;
        boolean handled=super.dispatchTouchEvent(event);
        if (action==MotionEvent.ACTION_UP) { tracking=false; if (getParent()!=null) getParent().requestDisallowInterceptTouchEvent(false); }
        return handled;
    }
    @Override protected void onMeasure(int widthSpec,int heightSpec) {
        int width=MeasureSpec.getSize(widthSpec),height=resolveSize(Ui.dp(getContext(),HEIGHT),heightSpec); inset=Ui.dp(getContext(),2);
        int action=Math.min(Ui.dp(getContext(),28),Math.max(1,width/6)); tabsRight=width-inset-2*action; int tabWidth=Math.max(1,(tabsRight-inset)/3);
        if (original.getVisibility()!=GONE) original.measure(MeasureSpec.makeMeasureSpec(tabsRight,MeasureSpec.EXACTLY),MeasureSpec.makeMeasureSpec(height,MeasureSpec.EXACTLY));
        int exactHeight=MeasureSpec.makeMeasureSpec(Math.max(1,height-2*inset),MeasureSpec.EXACTLY);
        for (int i=0;i<tabs.length;i++) { tabs[i].setText(tabWidth<Ui.dp(getContext(),62) ? new String[]{"内置","磁贴","应用"}[i] : LABELS[i]); tabs[i].measure(MeasureSpec.makeMeasureSpec(tabWidth,MeasureSpec.EXACTLY),exactHeight); }
        search.measure(MeasureSpec.makeMeasureSpec(action,MeasureSpec.EXACTLY),exactHeight); collapse.measure(MeasureSpec.makeMeasureSpec(action,MeasureSpec.EXACTLY),exactHeight); setMeasuredDimension(width,height);
    }
    @Override protected void onLayout(boolean changed,int l,int t,int r,int b) {
        if (original.getVisibility()!=GONE) original.layout(0,0,tabsRight,getHeight());
        for (int i=0;i<tabs.length;i++) { int x=inset+i*tabs[i].getMeasuredWidth(); tabs[i].layout(x,inset,x+tabs[i].getMeasuredWidth(),getHeight()-inset); }
        search.layout(tabsRight,inset,tabsRight+search.getMeasuredWidth(),getHeight()-inset); collapse.layout(tabsRight+search.getMeasuredWidth(),inset,getWidth()-inset,getHeight()-inset);
    }
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh) { super.onSizeChanged(w,h,oldw,oldh); if (oldw>0 && (w!=oldw || h!=oldh)) cancelInteraction(); }
    @Override protected void onDetachedFromWindow() { cancelInteraction(); super.onDetachedFromWindow(); }
}
