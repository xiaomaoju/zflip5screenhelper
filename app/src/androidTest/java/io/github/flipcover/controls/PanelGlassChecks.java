package io.github.flipcover.controls;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.Executor;

/** Production materials and lifecycle on the disposable emulator, never live user data. */
final class PanelGlassChecks {
    private final Instrumentation test;
    private final int expectedRotation;
    private Activity activity;
    private PanelGlassSession session;
    private FrameLayout root;
    private LinearLayout panel;
    private CoverService owner;
    private Bitmap source;
    private int assertions;
    private String evidence="";
    PanelGlassChecks(Instrumentation test,int expectedRotation) { this.test=test; this.expectedRotation=expectedRotation; }
    String runContentChecks() throws Exception {
        activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        main(() -> { activity.getWindow().setDecorFitsSystemWindows(false); activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); }); idle();
        try {
            mount("controls",0,4);
            checkDetailHeaders();
            for (String id:List.of("wifi","volume","nfc","hotspot","media")) {
                main(() -> owner.showDetails(id,null)); idle();
                DetailSheet sheet=root.findViewWithTag("detail-sheet"); View card=sheet.findViewWithTag("detail-card"); android.graphics.Rect initial=layoutBounds(card);
                if (id.equals("media")) checkMediaContent(sheet,initial);
                main(() -> { for (int i=0;i<20;i++) sheet.content.addView(Ui.text(activity,"Async result "+i+" with a longer label",14,Ui.TEXT)); }); idle();
                android.widget.ScrollView scroll=sheet.findViewWithTag("detail-scroll");
                require(layoutBounds(card).equals(initial) && scroll.canScrollVertically(1),"growing data stays in a scrollable fixed viewport: "+id);
                main(() -> { sheet.content.removeAllViews(); sheet.content.addView(Ui.text(activity,"Loading…",10,Ui.MUTED)); }); idle();
                require(layoutBounds(card).equals(initial),"refresh cannot shrink the viewport: "+id);
                main(() -> { sheet.content.removeAllViews(); sheet.content.addView(Ui.text(activity,"A long asynchronous error message. ".repeat(12),14,Ui.MUTED)); sheet.footer("A longer footer label after an update",() -> { }); }); idle();
                require(layoutBounds(card).equals(initial),"errors and footer wrapping do not change outer geometry: "+id);
                main(() -> { FrameLayout.LayoutParams size=(FrameLayout.LayoutParams)sheet.getLayoutParams(); size.width=Ui.dp(activity,180); size.height=Ui.dp(activity,160); sheet.setLayoutParams(size); }); idle();
                require(card.getWidth()<=sheet.getWidth()-Ui.dp(activity,16) && card.getHeight()<=sheet.getHeight()-Ui.dp(activity,16),"fixed viewport is capped by the actual small safe area: "+id);
                require(scroll.canScrollVertically(1),"small safe areas preserve access through scrolling: "+id);
                main(owner::dismissDetails);
            }
            checkImmediateReopen();
            return "PASS: "+assertions+" dynamic detail viewport and immediate-input assertions; emulator only";
        } finally { main(() -> { if (owner!=null) owner.dismissDetails(); if (session!=null) session.close(); if (root!=null) root.setBackground(null); activity.setContentView(new FrameLayout(activity)); activity.finish(); }); if (source!=null && !source.isRecycled()) source.recycle(); }
    }
    private static android.graphics.Rect layoutBounds(View view) { return new android.graphics.Rect(view.getLeft(),view.getTop(),view.getRight(),view.getBottom()); }
    private void checkMediaContent(DetailSheet sheet,android.graphics.Rect initial) throws Exception {
        MediaSessions sessions=owner.mediaSessions(); android.media.session.MediaSession first=new android.media.session.MediaSession(activity,"detail-size-first"),second=new android.media.session.MediaSession(activity,"detail-size-second");
        java.lang.reflect.Method replace=MediaSessions.class.getDeclaredMethod("replace",List.class); replace.setAccessible(true);
        java.lang.reflect.Field permission=MediaSessions.class.getDeclaredField("permission"); permission.setAccessible(true);
        try {
            main(() -> {
                first.setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE,"Loaded media title").putLong(android.media.MediaMetadata.METADATA_KEY_DURATION,120000).build());
                try { permission.setBoolean(sessions,true); replace.invoke(sessions,List.of(first.getController(),second.getController())); } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            }); idle();
            View card=sheet.findViewWithTag("detail-card"); require(layoutBounds(card).equals(initial),"late media permission, duration and player toolbar keep geometry");
            main(() -> sheet.findViewWithTag("media-player-picker").performClick()); idle();
            require(layoutBounds(card).equals(initial),"expanded player choices remain inside the media viewport");
            java.lang.reflect.Field available=MediaDetailView.class.getDeclaredField("availableHeight"); available.setAccessible(true);
            android.widget.ScrollView scroll=sheet.findViewWithTag("detail-scroll");
            require(available.getInt(sheet.findViewWithTag("media-detail"))==scroll.getHeight()-sheet.content.getPaddingTop()-sheet.content.getPaddingBottom(),"media receives the actual reserved body height");
            main(() -> { try { permission.setBoolean(sessions,false); replace.invoke(sessions,List.of()); } catch (ReflectiveOperationException error) { throw new AssertionError(error); } }); idle();
            require(layoutBounds(card).equals(initial),"loss of media sessions cannot shrink the open card");
        } finally { main(() -> { first.release(); second.release(); sessions.refresh(); }); }
    }
    private void checkImmediateReopen() {
        View button=panel.findViewWithTag("control-rotation"); int[] clicks={0}; View[] closing={null}; boolean[] beforeRemoval={false};
        main(() -> { button.setOnClickListener(v -> { clicks[0]++; beforeRemoval[0]=closing[0]!=null && closing[0].isAttachedToWindow(); owner.showDetails("rotation",button); }); owner.showDetails("rotation",button); }); SystemClock.sleep(950); idle();
        DetailSheet previous=root.findViewWithTag("detail-sheet");
        main(() -> {
            closing[0]=previous; previous.close();
            int[] at=new int[2],base=new int[2]; button.getLocationOnScreen(at); root.getLocationOnScreen(base);
            float x=at[0]-base[0]+button.getWidth()/2f,y=at[1]-base[1]+button.getHeight()/2f;
            touch(root,android.view.MotionEvent.ACTION_DOWN,x,y); touch(root,android.view.MotionEvent.ACTION_UP,x,y);
        }); test.waitForIdleSync(); // View posts performClick after ACTION_UP.
        main(() -> {
            require(clicks[0]==1 && beforeRemoval[0],"the next real touch reaches the control before the old close animation ends: clicks="+clicks[0]+" attached="+beforeRemoval[0]);
            require(root.findViewWithTag("detail-sheet")!=previous,"the control can reopen a new sheet without waiting for the old spring");
        }); SystemClock.sleep(950); idle();
        require(root.findViewWithTag("detail-sheet")!=null && root.findViewWithTag("detail-sheet").getAlpha()==1,"old close cannot remove the immediately reopened sheet");
        main(() -> ((DetailSheet)root.findViewWithTag("detail-sheet")).close()); SystemClock.sleep(450); test.waitForIdleSync();
        require(root.findViewWithTag("detail-sheet")==null,"invisible scale tail no longer keeps a modal mounted");
    }
    String runMotionChecks() throws Exception {
        activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        main(() -> { activity.getWindow().setDecorFitsSystemWindows(false); activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); }); idle();
        try {
            mount("controls",0,4);
            checkEntrance("rotation",true); checkEntrance("bluetooth",true); checkEntrance("brightness",true);
            checkExit("rotation",0,true,false); checkExit("bluetooth",1,true,false); checkExit("brightness",2,true,false);
            checkExit("rotation",0,true,true); checkExit("bluetooth",1,false,false);
            View[] retired=new View[2]; float[] frozen=new float[2];
            main(() -> owner.showDetails("rotation",panel.findViewWithTag("control-rotation")));
            for (int i=0;i<80;i++) { boolean[] visible={false}; main(() -> visible[0]=root.findViewWithTag("detail-sheet").getAlpha()>0); if (visible[0]) break; SystemClock.sleep(5); }
            main(() -> { retired[0]=root.findViewWithTag("detail-sheet"); retired[1]=root.findViewWithTag("detail-card"); require(retired[0].getAlpha()>0,"replacement interrupts a visible spring, not material preparation"); ((DetailSheet)retired[0]).close(); owner.dismissDetails(); frozen[0]=retired[1].getScaleX(); frozen[1]=retired[1].getScaleY(); owner.showDetails("bluetooth",panel.findViewWithTag("control-bluetooth")); });
            SystemClock.sleep(950); idle();
            require(root.findViewWithTag("detail-sheet")!=retired[0] && root.findViewWithTag("detail-sheet").getAlpha()==1,"retired spring cannot dismiss a replacement sheet");
            require(retired[1].getScaleX()==frozen[0] && retired[1].getScaleY()==frozen[1],"detached spring stops changing the old card");
            int settledDraws=session.draws; SystemClock.sleep(400); test.waitForIdleSync(); require(session.draws-settledDraws<5,"settled spring has no idle draw loop"); main(owner::dismissDetails);
            main(() -> { owner.showDetails("rotation",panel.findViewWithTag("control-rotation")); owner.dismissDetails(); }); idle();
            require(root.findViewWithTag("detail-sheet")==null,"dismiss before first frame cannot reopen from a preparation callback");
            main(() -> { owner.showDetails("rotation",null); session.close(); owner.panelGlass=null; }); SystemClock.sleep(700); idle();
            require(root.findViewWithTag("detail-sheet").getAlpha()==1,"disabling glass while waiting cannot strand an invisible modal"); main(owner::dismissDetails);
            checkEntrance("rotation",true); checkEntrance("bluetooth",false);
            mount("controls",0,4);
            java.lang.reflect.Field work=PanelGlassSession.class.getDeclaredField("WORK"); work.setAccessible(true);
            java.util.concurrent.ThreadPoolExecutor executor=(java.util.concurrent.ThreadPoolExecutor)work.get(null);
            java.util.concurrent.CountDownLatch blocked=new java.util.concurrent.CountDownLatch(1), release=new java.util.concurrent.CountDownLatch(1);
            executor.execute(() -> { blocked.countDown(); try { release.await(3,java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); } });
            require(blocked.await(1,java.util.concurrent.TimeUnit.SECONDS),"controlled slow modal preparation started");
            long base=session.bytes(); android.graphics.RuntimeShader original=session.shader(panel);
            try {
                checkEntrance("rotation",true,false);
                require(session.bytes()==base,"slow preparation opens with the existing bounded texture");
                View card=root.findViewWithTag("detail-card"); require(session.shader(card)==original,"timeout chooses original material");
                release.countDown(); SystemClock.sleep(350); idle();
                require(session.shader(card)==original && session.bytes()==base,"late preparation cannot replace the visible material");
                main(owner::dismissDetails);
            } finally { release.countDown(); }
            checkBoundaryReversal();
            return "PASS: "+assertions+" detail spring assertions; button origin, elastic travel, bounded two-axis overshoot, boundary reversal, reverse exit, resize, interrupted opening, stable material/geometry, timeout and cancellation; emulator only; "+evidence;
        } finally { main(() -> { if (owner!=null) owner.dismissDetails(); if (session!=null) session.close(); if (root!=null) root.setBackground(null); activity.setContentView(new FrameLayout(activity)); activity.finish(); }); if (source!=null && !source.isRecycled()) source.recycle(); }
    }
    private void checkBoundaryReversal() {
        if (!android.animation.ValueAnimator.areAnimatorsEnabled()) return;
        FrameLayout host=new FrameLayout(activity); View card=new View(activity); DetailSheetMotion motion=new DetailSheetMotion(card,host); int[] completed={0};
        main(() -> { root.addView(host,new FrameLayout.LayoutParams(Ui.dp(activity,200),Ui.dp(activity,140),android.view.Gravity.CENTER)); host.addView(card,new FrameLayout.LayoutParams(Ui.dp(activity,180),Ui.dp(activity,120),android.view.Gravity.CENTER)); }); idle();
        main(() -> motion.animateTo(1.15f,Ui.dp(activity,50),0,true,null)); SystemClock.sleep(110);
        java.util.ArrayList<Float> positions=new java.util.ArrayList<>();
        android.view.ViewTreeObserver.OnPreDrawListener sample=() -> { positions.add(card.getTranslationX()); return true; };
        try {
            main(() -> {
                float right=card.getLeft()+card.getWidth()/2f+card.getTranslationX()+card.getWidth()*card.getScaleX()/2f;
                require(Math.abs(right-host.getWidth())<1,"fixture reaches the coupled scale/travel boundary");
                positions.add(card.getTranslationX()); root.getViewTreeObserver().addOnPreDrawListener(sample);
                motion.animateTo(.6f,-Ui.dp(activity,10),0,false,() -> completed[0]++);
            });
            SystemClock.sleep(900); test.waitForIdleSync();
            main(() -> {
                require(positions.size()>2,"boundary reversal has intermediate frames");
                for (int i=1;i<positions.size();i++) require(positions.get(i)<=positions.get(i-1)+.01f,"clipped outward velocity cannot leak into the return path");
                require(completed[0]==1 && card.getScaleX()==.6f && card.getScaleY()==.6f && card.getTranslationX()==-Ui.dp(activity,10),"bounded spring returns exactly and finishes once");
            });
        } finally { main(() -> { root.getViewTreeObserver().removeOnPreDrawListener(sample); motion.cancel(); root.removeView(host); }); }
    }
    private void checkExit(String id,int action,boolean anchored,boolean interrupt) {
        View[] views=new View[2]; float[] origin=new float[4];
        main(() -> {
            View button=panel.findViewWithTag("control-"+id), anchor=button instanceof Panels.Tile tile ? tile.face : button;
            int[] position=new int[2]; anchor.getLocationOnScreen(position); origin[0]=position[0]+anchor.getWidth()/2f; origin[1]=position[1]+anchor.getHeight()/2f; origin[2]=anchor.getWidth(); origin[3]=anchor.getHeight();
            owner.showDetails(id,anchored ? button : null); views[0]=root.findViewWithTag("detail-sheet"); views[1]=root.findViewWithTag("detail-card");
        });
        if (interrupt && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            for (int i=0;i<80;i++) { boolean[] visible={false}; main(() -> visible[0]=views[0].getAlpha()>0); if (visible[0]) break; SystemClock.sleep(5); }
        } else { SystemClock.sleep(800); idle(); }
        if (action==2) { main(() -> ((DetailSheet)views[0]).content.addView(Ui.text(activity,"Additional content after loading\nSecond line\nThird line",14,Ui.TEXT))); idle(); }
        java.util.ArrayList<float[]> frames=new java.util.ArrayList<>(); float[] target=new float[3];
        android.view.ViewTreeObserver.OnPreDrawListener listener=() -> { View card=views[1]; frames.add(new float[]{views[0].getAlpha(),card.getScaleX(),card.getTranslationX(),card.getTranslationY(),card.getScaleY()}); return true; };
        main(() -> {
            DetailSheet sheet=(DetailSheet)views[0]; View card=views[1]; int[] base=new int[2]; sheet.getLocationOnScreen(base);
            target[0]=anchored ? Math.min(1,Math.min(origin[2]/card.getWidth(),origin[3]/card.getHeight())) : .92f;
            target[1]=anchored ? origin[0]-base[0]-card.getLeft()-card.getWidth()/2f : 0;
            target[2]=anchored ? origin[1]-base[1]-card.getTop()-card.getHeight()/2f : Ui.dp(activity,10);
            float alpha=sheet.getAlpha(), scale=card.getScaleX(), x=card.getTranslationX(), y=card.getTranslationY();
            if (interrupt && android.animation.ValueAnimator.areAnimatorsEnabled()) require(alpha>0 && alpha<1,"close interrupts an actual opening frame");
            root.getViewTreeObserver().addOnPreDrawListener(listener);
            if (action==0) sheet.close(); else if (action==1) sheet.performClick(); else owner.act("back");
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                require(sheet.isAttachedToWindow(),"close keeps sheet mounted until the reverse animation completes");
                require(sheet.getAlpha()==alpha && card.getScaleX()==scale && card.getTranslationX()==x && card.getTranslationY()==y,"closing starts at the current pose without a jump");
                sheet.close(); require(sheet.isAttachedToWindow(),"duplicate close cannot remove an animating sheet");
            }
        });
        SystemClock.sleep(850); test.waitForIdleSync();
        main(() -> {
            root.getViewTreeObserver().removeOnPreDrawListener(listener);
            require(root.findViewWithTag("detail-sheet")==null,"reverse close finally releases the modal");
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                require(frames.size()>2,"reverse close spans multiple frames: "+id+" action="+action+" interrupt="+interrupt+" frames="+frames.size());
                View card=views[1]; require(Math.abs(card.getScaleX()-target[0])<.001f && Math.abs(card.getScaleY()-target[0])<.001f && Math.abs(card.getTranslationX()-target[1])<1 && Math.abs(card.getTranslationY()-target[2])<1,"exit ends at the original button even after content resize");
                for (int i=1;i<frames.size();i++) {
                    float[] a=frames.get(i-1),b=frames.get(i);
                    require(b[0]<=a[0],"exit opacity never rebounds with the elastic shape");
                    require(b[1]>0 && b[1]<=1.13f && b[4]>0 && b[4]<=1.075f,"exit spring stays positive and bounded");
                    if (!interrupt) require(Math.abs(b[2]-target[1])<=Math.abs(a[2]-target[1])+.01f && Math.abs(b[3]-target[2])<=Math.abs(a[3]-target[2])+.01f,"settled exit follows the path back toward the button");
                }
            }
        });
    }
    private void checkEntrance(String id,boolean anchored) { checkEntrance(id,anchored,true); }
    private void checkEntrance(String id,boolean anchored,boolean dismiss) {
        java.util.ArrayList<float[]> frames=new java.util.ArrayList<>(); java.util.ArrayList<android.graphics.RuntimeShader> shaders=new java.util.ArrayList<>();
        float[] origin=new float[4]; View[] views=new View[2]; android.view.ViewTreeObserver.OnDrawListener[] listener=new android.view.ViewTreeObserver.OnDrawListener[1];
        main(() -> {
            View button=panel.findViewWithTag("control-"+id), anchor=button instanceof Panels.Tile tile ? tile.face : button;
            if (anchored) { int[] position=new int[2]; anchor.getLocationOnScreen(position); origin[0]=position[0]+anchor.getWidth()/2f; origin[1]=position[1]+anchor.getHeight()/2f; origin[2]=anchor.getWidth(); origin[3]=anchor.getHeight(); }
            owner.showDetails(id,anchored ? button : null); DetailSheet sheet=root.findViewWithTag("detail-sheet"); View card=sheet.findViewWithTag("detail-card"); views[0]=sheet; views[1]=card;
            require(sheet.getAlpha()==0,"modal and scrim are hidden synchronously before first traversal: "+id);
            listener[0]=() -> {
                if (card.getWidth()==0 || card.getScaleX()==1 && sheet.getAlpha()==0) return;
                int[] location=new int[2]; card.getLocationOnScreen(location);
                float cx=card.getLeft()+card.getWidth()/2f+card.getTranslationX(),cy=card.getTop()+card.getHeight()/2f+card.getTranslationY();
                float limitX=Math.max(1,2*Math.min(cx,sheet.getWidth()-cx)/Math.max(1,card.getWidth())),limitY=Math.max(1,2*Math.min(cy,sheet.getHeight()-cy)/Math.max(1,card.getHeight()));
                frames.add(new float[]{sheet.getAlpha(),card.getScaleX(),location[0]+card.getWidth()*card.getScaleX()/2f,location[1]+card.getHeight()*card.getScaleY()/2f,card.getWidth(),card.getHeight(),card.getLeft(),card.getTop(),card.getScaleY(),limitX,limitY,card.getTranslationX(),card.getTranslationY(),sheet.getWidth(),sheet.getHeight()});
                shaders.add(owner.panelGlass==null ? null : owner.panelGlass.shader(card));
            };
            root.getViewTreeObserver().addOnDrawListener(listener[0]);
        });
        SystemClock.sleep(950); test.waitForIdleSync();
        main(() -> {
            root.getViewTreeObserver().removeOnDrawListener(listener[0]);
            require(views[0].getAlpha()==1 && views[1].getScaleX()==1 && views[1].getScaleY()==1 && views[1].getTranslationX()==0 && views[1].getTranslationY()==0,"entrance settles at the natural final geometry: "+id);
            require(!frames.isEmpty(),"entrance produced rendered frames: "+id);
            float[] first=frames.get(0);
            if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
                require(frames.size()>2 && first[0]==0,"first rendered frame cannot flash opaque: "+id);
                if (anchored) {
                    require(Math.abs(first[2]-origin[0])<=2 && Math.abs(first[3]-origin[1])<=2,"first card center equals the actual button center: "+id);
                    float expected=Math.min(1,Math.min(origin[2]/first[4],origin[3]/first[5])); require(Math.abs(first[1]-expected)<.001f,"card expands from button-sized uniform scale: "+id);
                }
            }
            float peakX=0,peakY=0,axisDifference=0,minTravel=0; boolean returned=false;
            float travel=first[11]*first[11]+first[12]*first[12];
            for (int i=1;i<frames.size();i++) {
                float[] previous=frames.get(i-1), frame=frames.get(i);
                require(frame[0]>=previous[0],"opacity remains monotonic while the shape rebounds: "+id);
                require(frame[1]>0 && frame[1]<=1.13f && frame[8]>0 && frame[8]<=1.075f,"stronger elastic overshoot stays bounded: "+id);
                require(frame[1]<=frame[9]+.001f && frame[8]<=frame[10]+.001f,"overshoot remains inside the moving card's safe-area host: "+id);
                float cx=frame[6]+frame[4]/2+frame[11],cy=frame[7]+frame[5]/2+frame[12];
                require(cx-frame[4]*frame[1]/2>=-.5f && cx+frame[4]*frame[1]/2<=frame[13]+.5f && cy-frame[5]*frame[8]/2>=-.5f && cy+frame[5]*frame[8]/2<=frame[14]+.5f,"travel and scale jointly stay within the host: "+id);
                if (travel>4) minTravel=Math.min(minTravel,(frame[11]*first[11]+frame[12]*first[12])/travel);
                peakX=Math.max(peakX,frame[1]); peakY=Math.max(peakY,frame[8]); axisDifference=Math.max(axisDifference,Math.abs(frame[1]-frame[8])); returned|=previous[1]>1 && frame[1]<previous[1];
                require(frame[4]==first[4] && frame[5]==first[5] && frame[6]==first[6] && frame[7]==first[7],"action decoration never moves or resizes the entering card: "+id);
                require(shaders.get(i)==shaders.get(0),"visible glass material never switches mid-entrance: "+id);
            }
            if (anchored && android.animation.ValueAnimator.areAnimatorsEnabled()) {
                require(peakX>1.008f && peakY>1.03f && axisDifference>.008f && returned,"stronger two-axis springs overshoot and settle: "+id+" peaks="+peakX+","+peakY+" frames="+frames.size());
                if (travel>4) require(minTravel<-.002f,"opening position crosses its resting center then springs back: "+id+" travel="+minTravel);
                evidence+="spring "+id+" scale="+peakX+","+peakY+" travel="+minTravel+"; ";
            }
            if (dismiss) owner.dismissDetails();
        });
    }
    String runCaptureChecks() {
        activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try { checkCardCaptureAreas(); checkRequests(); checkScaledBackdrop(); return "PASS: "+assertions+" glass capture/scale assertions; rotation-aware card coverage, hardware texture mapping and lifecycle on disposable emulator"; }
        finally { main(activity::finish); }
    }
    String runOriginalTabs() throws Exception {
        activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        SystemClock.sleep(700); test.waitForIdleSync();
        main(() -> { activity.getWindow().setDecorFitsSystemWindows(false); activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); }); idle();
        try { checkOriginalTabs(); checkRotationTabs(); return "PASS: "+assertions+" original LiquidBottomTabs source/rotation host and interaction assertions"; }
        finally { main(() -> { if (session!=null) session.close(); activity.setContentView(new FrameLayout(activity)); activity.finish(); }); if (source!=null && !source.isRecycled()) source.recycle(); }
    }
    String runNotifications() throws Exception {
        activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        SystemClock.sleep(700); test.waitForIdleSync();
        main(() -> { activity.getWindow().setDecorFitsSystemWindows(false); activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); }); idle();
        try {
            checkNotificationUpdates();
            return "PASS: " + assertions + " notification glass assertions; real rendering, plain expanded chevron, centered icons and icon-only actions";
        } finally { main(() -> { if (session != null) session.close(); if (root!=null) root.setBackground(null); activity.setContentView(new FrameLayout(activity)); activity.finish(); }); if (source != null && !source.isRecycled()) source.recycle(); }
    }
    String run() throws Exception {
        Instrumentation.ActivityMonitor monitor=test.addMonitor(MainActivity.class.getName(),null,false);
        activity=test.startActivitySync(new Intent(test.getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        if (expectedRotation>=0) require(test.getUiAutomation().setRotation(expectedRotation),"rotation requested after fixture launch");
        SystemClock.sleep(700); test.waitForIdleSync();
        if (monitor.getLastActivity()!=null) activity=monitor.getLastActivity(); test.removeMonitor(monitor);
        require(expectedRotation<0 || activity.getDisplay().getRotation()==expectedRotation,"actual glass rotation equals request: "+activity.getDisplay().getRotation()+" / "+expectedRotation);
        main(() -> { activity.getWindow().setDecorFitsSystemWindows(false); activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars()); }); idle();
        try {
            for (int pattern=0;pattern<5;pattern++) {
                mount("notifications",pattern,4); hideContents(); checkNotificationPixels();
                main(() -> panel.setTranslationY(38)); idle(); checkNotificationPixels(); main(() -> panel.setTranslationY(0)); idle();
                showNotifications(); capture("notification-"+pattern);
                mount("controls",pattern,4); hideContents(); checkControlPixels(); capture("control-floor-"+pattern);
            }
            for (int columns:new int[]{3,4,5}) {
                mount("controls",0,columns); require(panel.findViewWithTag("control-dashboard") != null,"real control dashboard retained");
                require(session.ready() && session.bytes() <= 12L*1024*1024,"bounded hardware textures"); capture("controls-"+columns);
                int before=session.draws; SystemClock.sleep(1200); test.waitForIdleSync(); require(session.draws-before < 5,"glass has no idle frame loop");
                main(() -> panel.setTranslationY(38)); idle(); require(session.draws>before,"moving panel refreshes registered backdrop coordinates"); main(() -> panel.setTranslationY(0)); idle();
                main(owner::editControls); idle(); require(panel.findViewWithTag("control-editor") != null,"editor stays inside same panel"); capture("editor-"+columns);
                main(() -> panel.findViewWithTag("control-editor-done").performClick()); idle(); require(panel.findViewWithTag("control-dashboard") != null,"completed editor returns to controls");
                main(() -> owner.showDetails("volume",panel.findViewWithTag("control-volume"))); idle(); SystemClock.sleep(300); idle();
                require(root.findViewWithTag("detail-card") != null,"real detail sheet receives optional glass"); require(session.bytes() <= 12L*1024*1024,"local modal stays within texture budget"); capture("details-"+columns);
                main(owner::dismissDetails); idle(); require(root.findViewWithTag("detail-card") == null,"detail closes without rebuilding panel");
                long base=session.bytes(); main(owner::editControls); idle();
                main(() -> panel.findViewWithTag("control-selected-wifi").performClick()); idle();
                main(() -> panel.findViewWithTag("order-menu-remove").performClick()); idle();
                main(() -> { owner.act("back"); owner.act("back"); }); idle();
                require(panel.findViewWithTag("control-editor-discard") != null,"discard confirmation uses the parent underlay");
                main(() -> panel.findViewWithTag("control-editor-discard").performClick()); idle(); SystemClock.sleep(250); idle();
                require(session.bytes()==base,"leaving editor discards its modal texture and rejects late preparation");
            }
            checkRefinement();
            mount("controls",0,4); main(() -> panel.setTag("test-panel-surface"));
            main(() -> owner.showDetails("rotation",null)); idle(); checkSelectedDetail(); capture("rotation-selected"); main(owner::dismissDetails); idle();
            assertions+=new ControlGridChecks(test).runOnPanel(activity,owner.prefs,owner,false);
            for (String page : List.of("media","rotation")) {
                mount(page,5,4); Bitmap rendered=test.getUiAutomation().takeScreenshot();
                try { require(checkLegacyTiles(panel,rendered)==(page.equals("media") ? 3 : 4),"legacy "+page+" entry decorates complete tiles"); } finally { rendered.recycle(); }
                capture("legacy-"+page);
            }
            checkPageSwitches();
            mount("controls",0,4); main(() -> owner.showDetails("volume",null)); idle();
            main(() -> owner.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW));
            require(session.bytes()==0 && owner.panelGlass==null,"low-memory event releases accepted background and modal textures");
            main(owner::dismissDetails); main(() -> owner.showDetails("volume",null)); idle();
            require(owner.blurDiagnostics().contains("详情本地模糊当前启用：false"),"low-memory fallback cannot restart legacy detail blur"); main(owner::dismissDetails);
            main(() -> owner.prefs.data.edit().putBoolean("panel_blur",false).commit()); require(!PanelGlassSession.allowed(activity,owner.prefs),"existing effects switch disables glass"); main(() -> owner.prefs.data.edit().putBoolean("panel_blur",true).commit());
            checkNotificationUpdates(); checkRequests();
            main(() -> { session.close(); session.close(); }); require(session.bytes()==0 && !session.ready(),"idempotent close releases textures");
            return "PASS: "+assertions+" production glass assertions; rotation="+activity.getDisplay().getRotation()+"; "+evidence;
        } finally { main(() -> { if (session != null) session.close(); if (root!=null) root.setBackground(null); activity.setContentView(new FrameLayout(activity)); activity.finish(); }); if (source != null && !source.isRecycled()) source.recycle(); }
    }
    private void main(Runnable action) { Throwable[] problem={null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable failure) { problem[0]=failure; } }); if (problem[0]!=null) throw new AssertionError(problem[0]); }
    private void idle() { test.waitForIdleSync(); SystemClock.sleep(250); test.waitForIdleSync(); }
    private void require(boolean condition,String message) { assertions++; if (!condition) throw new AssertionError(message); }
    private void mount(String page,int pattern,int columns) {
        main(() -> { if (session != null) session.close(); activity.setContentView(new FrameLayout(activity)); });
        if (source != null) source.recycle();
        int width=activity.getResources().getDisplayMetrics().widthPixels, height=activity.getResources().getDisplayMetrics().heightPixels;
        source=pattern(width,height,pattern);
        PanelGlassSession prepared=new PanelGlassSession(activity,activity.getDisplay()); prepared.fixture(source.copy(Bitmap.Config.ARGB_8888,false));
        main(() -> {
            session=prepared; owner=new CoverService(); owner.screenContext=activity; owner.display=activity.getDisplay(); owner.prefs=new Prefs(activity);
            owner.prefs.data.edit().putInt("panel_columns",columns).putBoolean("panel_blur",true).putBoolean("panel_brightness",true).putBoolean("panel_volume",true).putBoolean("panel_media",true).commit();
            java.util.ArrayList<String> actions=new java.util.ArrayList<>(List.of("wifi","bluetooth","data","torch","dnd","airplane","rotation","screenshot","lock","media","apps","configure"));
            if (columns==5) { for (ActionCatalog.Action action:ActionCatalog.BUILT_INS) if (!actions.contains(action.id())) actions.add(action.id()); while (actions.size()<30) actions.add("app:glass.fixture/Item"+actions.size()); }
            owner.prefs.saveActions("panel",actions);
            DockGeometry.Placement placement=DockGeometry.edgeTouch(DockGeometry.resolve(width,height,List.of(),activity.getResources().getDisplayMetrics().density,owner.prefs.corner(activity.getDisplay().getRotation()),.46f,.088f,false),width,height);
            DockGeometry.Box content=DockGeometry.panelContent(placement,width,height,List.of()); owner.placement=new DockGeometry.Placement(placement.visual(),placement.touch(),content,placement.edge(),placement.measured());
            root=new FrameLayout(activity); root.setBackground(new BitmapDrawable(activity.getResources(),source));
            panel=owner.buildPanelContent(page,new DockGeometry.Box(0,0,width,height),content.y()); root.addView(panel,new FrameLayout.LayoutParams(-1,-1)); owner.panelGlass=session;
            activity.setContentView(root); session.attach(root,page.equals("notifications"));
        }); idle();
    }
    private void hideContents() { main(() -> { for (int i=0;i<panel.getChildCount();i++) panel.getChildAt(i).setVisibility(View.INVISIBLE); }); idle(); }
    private void checkRefinement() throws Exception {
        for (String page:List.of("notifications","controls")) {
            mount(page,0,4); ViewGroup header=panel.findViewWithTag("panel-header");
            for (int i=1;i<header.getChildCount();i++) {
                ViewGroup slot=(ViewGroup)header.getChildAt(i); View action=slot.getChildAt(0);
                require(slot instanceof PanelActionSlot && action.getScaleX()==.8f && action.getScaleY()==.8f,"header visuals shrink exactly20 percent");
                require(slot.getWidth()==action.getWidth() && slot.getHeight()==action.getHeight(),"original header layout and touch slot retained");
            }
            ViewGroup slot=(ViewGroup)header.getChildAt(1); View action=slot.getChildAt(0); int[] clicks={0};
            main(() -> { action.setTooltipText(null); action.setOnClickListener(v -> clicks[0]++); touch(slot,0,1,slot.getHeight()/2f); touch(slot,1,1,slot.getHeight()/2f); }); idle();
            require(clicks[0]==1,"outside smaller artwork still reaches original action target");
            main(() -> { touch(slot,0,1,slot.getHeight()/2f); slot.requestLayout(); }); idle(); main(() -> touch(slot,1,1,slot.getHeight()/2f)); idle(); require(clicks[0]==2,"notification relayout during edge press retains the touch delegate sequence: "+page+" clicks="+clicks[0]+" visible="+slot.getVisibility()+" enabled="+action.isEnabled());
            main(() -> { action.setEnabled(false); touch(slot,0,1,slot.getHeight()/2f); touch(slot,1,1,slot.getHeight()/2f); }); idle(); require(clicks[0]==2,"disabled header action remains disabled");
            if (page.equals("notifications")) {
                android.widget.Button clear=panel.findViewWithTag("notification-clear-all"); ViewGroup clearSlot=(ViewGroup)clear.getParent(); int[] cleared={0};
                main(() -> { clear.setEnabled(true); clear.setText("9"); clear.setOnClickListener(v -> cleared[0]++); }); idle(); int oldWidth=clearSlot.getWidth();
                main(() -> { touch(clearSlot,0,1,clearSlot.getHeight()/2f); clear.setText("99999"); }); idle();
                require(clearSlot.getWidth()>oldWidth,"clear-count fixture changes the actual header width during a press");
                main(() -> touch(clearSlot,1,1,clearSlot.getHeight()/2f)); idle(); require(cleared[0]==1,"count-width change preserves the in-progress edge click");
                main(() -> clear.setText("9")); idle(); int[] position=new int[2]; clearSlot.getLocationOnScreen(position); float screenX=position[0]+clearSlot.getWidth()-1;
                main(() -> { touch(clearSlot,0,clearSlot.getWidth()-1,clearSlot.getHeight()/2f); clear.setText("99999"); }); idle(); clearSlot.getLocationOnScreen(position); float localX=screenX-position[0];
                main(() -> { touch(clearSlot,2,localX,clearSlot.getHeight()/2f); touch(clearSlot,1,localX,clearSlot.getHeight()/2f); }); idle(); require(cleared[0]==2,"fixed screen-coordinate press survives count-width growth and an intervening move");
            }
        }
        checkOriginalTabs();
        mount("controls",0,4);
        checkDetailHeaders();
        main(() -> owner.showDetails("media",null)); idle(); View picker=root.findViewWithTag("media-player-picker"); ViewGroup pickerSlot=(ViewGroup)picker.getParent(); int[] hiddenClicks={0};
        main(() -> { picker.setOnClickListener(v -> hiddenClicks[0]++); picker.setVisibility(View.GONE); }); idle(); require(pickerSlot.getVisibility()==View.GONE,"hidden media action removes its whole visual and layout slot");
        main(() -> { touch(pickerSlot,0,1,1); touch(pickerSlot,1,1,1); }); require(hiddenClicks[0]==0,"hidden slot cannot invoke action");
        main(() -> picker.setVisibility(View.VISIBLE)); idle(); require(pickerSlot.getVisibility()==View.VISIBLE && pickerSlot.getWidth()>0,"media action slot returns when players become available");
        for (int hidden:new int[]{View.GONE,View.INVISIBLE}) { main(() -> picker.setVisibility(hidden)); idle(); require(pickerSlot.getVisibility()==hidden,"slot tracks later hidden state"); main(() -> picker.setVisibility(View.VISIBLE)); idle(); require(pickerSlot.getVisibility()==View.VISIBLE && pickerSlot.getWidth()>0,"slot returns after later visibility change"); }
        main(owner::dismissDetails); idle();
        checkToggles();
        mount("controls",0,4);
        android.view.WindowInsets.Builder insets=new android.view.WindowInsets.Builder(); for (int i=0;i<4;i++) insets.setRoundedCorner(i,new android.view.RoundedCorner(i,49,49,49));
        android.view.WindowInsets corners=insets.build(); FrameLayout probe=new FrameLayout(activity) { @Override public android.view.WindowInsets getRootWindowInsets() { return corners; } };
        main(() -> { FrameLayout host=new FrameLayout(activity); host.setBackgroundColor(Color.BLACK); host.addView(probe,new FrameLayout.LayoutParams(300,220)); View fill=new View(activity); fill.setBackgroundColor(Color.MAGENTA); probe.addView(fill,new FrameLayout.LayoutParams(-1,-1)); session.bind(probe,GlassSurface.Role.CONTROL_PAGE); activity.setContentView(host); }); idle();
        android.graphics.Outline outline=new android.graphics.Outline(); probe.getBackground().getOutline(outline);
        require(probe.getClipToOutline() && Math.abs(outline.getRadius()-48)<.1f,"page uses physical radius with one-pixel seam coverage");
        Bitmap pixels=test.getUiAutomation().takeScreenshot(); require(pixels.getPixel(25,8)==Color.MAGENTA && pixels.getPixel(1,1)==Color.BLACK,"physical corner fills old gap and clips child overflow"); pixels.recycle();
        main(() -> probe.setTranslationY(25)); idle(); probe.getBackground().getOutline(outline); require(outline.getRadius()>48 && outline.getRadius()<Ui.dp(activity,32),"lifted page corner transitions continuously");
        main(session::close); require(!probe.getClipToOutline(),"glass cleanup restores original clipping");
    }
    private void checkOriginalTabs() throws Exception {
        mount("controls",0,4); main(owner::editControls); idle();
        ControlSourceTabs tabs=panel.findViewWithTag("control-source-tabs");
        View first=tabs.findViewWithTag("library-tab-builtin"),last=tabs.findViewWithTag("library-tab-apps");
        float from=first.getX()+first.getWidth()/2f,to=last.getX()+last.getWidth()/2f,y=tabs.getHeight()/2f;
        List<String> saved=owner.prefs.actions("panel");
        require(tabs.usesOriginal() && tabs.originalView().getClass().getSimpleName().equals("OriginalLiquidTabs"),"original Compose LiquidBottomTabs is the visible renderer");
        require(first.getVisibility()==View.INVISIBLE && last.getVisibility()==View.INVISIBLE,"native fallback does not draw over the upstream effect");
        capture("tabs-original-resting");
        main(() -> touch(tabs,0,from,y)); idle(); SystemClock.sleep(400); idle(); capture("tabs-original-pressed");
        main(() -> touch(tabs,2,from+(to-from)/4,y)); idle(); capture("tabs-original-between");
        require(tabs.selectedIndex()==0,"original drag does not commit a source before release");
        main(() -> touch(tabs,2,to,y)); idle(); main(() -> touch(tabs,1,to,y)); SystemClock.sleep(900); idle();
        require(tabs.selectedIndex()==2 && CoverApp.catalog(activity).observerCount()>0,"original release selects apps and starts its scoped catalog subscription");
        require(tabs==panel.findViewWithTag("control-source-tabs"),"source changes preserve the original Compose host"); capture("tabs-original-released");
        main(() -> touch(tabs,0,to,y)); idle(); main(() -> touch(tabs,2,from,y)); idle(); main(() -> touch(tabs,3,from,y)); SystemClock.sleep(400); idle();
        require(tabs.selectedIndex()==2,"canceled original drag cannot commit a late source change");
        main(() -> touch(tabs,0,to,y)); idle();
        main(() -> { android.view.MotionEvent.PointerProperties a=new android.view.MotionEvent.PointerProperties(),b=new android.view.MotionEvent.PointerProperties(); a.id=0; b.id=1; android.view.MotionEvent.PointerCoords ca=new android.view.MotionEvent.PointerCoords(),cb=new android.view.MotionEvent.PointerCoords(); ca.x=to; ca.y=y; cb.x=to+10; cb.y=y; long now=SystemClock.uptimeMillis(); android.view.MotionEvent multi=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_POINTER_DOWN | 1<<8,2,new android.view.MotionEvent.PointerProperties[]{a,b},new android.view.MotionEvent.PointerCoords[]{ca,cb},0,0,1,1,0,0,0,0); tabs.dispatchTouchEvent(multi); multi.recycle(); touch(tabs,1,from,y); }); idle();
        require(tabs.selectedIndex()==2,"multi-touch is canceled at the native boundary before original gesture handling");
        main(() -> touch(tabs,0,from,y)); main(() -> touch(tabs,1,from,y)); SystemClock.sleep(900); idle();
        require(tabs.selectedIndex()==0 && CoverApp.catalog(activity).observerCount()==0,"original tab click selects builtin and releases app observation");
        // Dispatch through the real parent hierarchy: captured drags must survive leaving all four edges.
        main(() -> touchThroughRoot(tabs,0,from,y)); idle();
        main(() -> touchThroughRoot(tabs,2,from,-tabs.getHeight())); idle();
        main(() -> touchThroughRoot(tabs,2,to+last.getWidth(),-tabs.getHeight())); idle();
        main(() -> touchThroughRoot(tabs,2,to+last.getWidth(),tabs.getHeight()*2f)); idle();
        require(tabs.selectedIndex()==0,"leaving top, right and bottom edges keeps the drag pending until release");
        require(panel.findViewWithTag("control-editor")!=null && panel.findViewWithTag("control-candidate-query")==null,"captured outside drag does not dismiss editor or activate adjacent search");
        capture("tabs-original-outside");
        main(() -> touchThroughRoot(tabs,1,to+last.getWidth(),tabs.getHeight()*2f)); SystemClock.sleep(900); idle();
        require(tabs.selectedIndex()==2 && CoverApp.catalog(activity).observerCount()>0,"release outside tab commits the retained drag to apps");
        main(() -> touchThroughRoot(tabs,0,to,y)); idle();
        main(() -> touchThroughRoot(tabs,2,to,tabs.getHeight()*2f)); idle();
        main(() -> touchThroughRoot(tabs,2,-8,tabs.getHeight()*2f)); idle();
        require(tabs.selectedIndex()==2,"drag leaving left edge remains pending");
        main(() -> touchThroughRoot(tabs,1,-8,tabs.getHeight()*2f)); SystemClock.sleep(900); idle();
        require(tabs.selectedIndex()==0 && CoverApp.catalog(activity).observerCount()==0,"release beyond left edge selects builtin and releases observation");
        require(saved.equals(owner.prefs.actions("panel")),"original source switching does not save the control draft");
        int draws=session.draws; SystemClock.sleep(1200); idle(); require(session.draws-draws<5,"settled original tabs have no idle backdrop draw loop");
        main(() -> { tabs.findViewWithTag("control-candidates-search").performClick(); }); idle(); require(panel.findViewWithTag("control-candidate-query")!=null,"search remains available from the unified strip");
        main(() -> tabs.findViewWithTag("control-candidates-search").performClick()); idle();
        android.widget.GridView candidates=panel.findViewWithTag("control-candidate-grid");
        for (int position:new int[]{candidates.getCount()-1,0,candidates.getCount()/2,0}) {
            main(() -> candidates.setSelection(position)); idle(); require(candidates.getChildCount()>0,"candidate scroll exposes recycled rows");
            for (int i=0;i<candidates.getChildCount();i++) { ViewGroup cell=(ViewGroup)candidates.getChildAt(i); android.graphics.drawable.Drawable background=cell.getChildAt(0).getBackground(); require(background instanceof GlassSurface || background instanceof android.graphics.drawable.RippleDrawable ripple && ripple.getDrawable(0) instanceof GlassSurface,"recycled candidate retains glass after scrolling"); }
        }
        ViewGroup editorHeader=panel.findViewWithTag("control-editor-header"); View back=((ViewGroup)editorHeader.getChildAt(0)).getChildAt(0),done=panel.findViewWithTag("control-editor-done");
        require(back.getScaleX()==.8f && done.getScaleX()==.8f && tabs.getHeight()==Ui.dp(activity,ControlSourceTabs.HEIGHT),"editor and source strip use compact runtime metrics");
        checkCandidateHeaderClip();
    }
    private void checkCandidateHeaderClip() {
        main(() -> {
            ControlCandidatesView picker=new ControlCandidatesView(activity,List.of(),new android.os.Bundle(),id -> { },(id,view) -> false,() -> { });
            int width=Ui.dp(activity,320),height=Ui.dp(activity,130);
            picker.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY)); picker.layout(0,0,width,height);
            // The real glass header has transparent space around its right-side actions.
            // Make the entire fixture header transparent so an opaque fallback cannot hide a leak.
            picker.getChildAt(0).setAlpha(0);
            android.widget.GridView grid=picker.grid(); grid.scrollListBy(Ui.dp(activity,20));
            require(grid.getChildCount()>0 && grid.getChildAt(0).getTop()<0,"candidate fixture contains a partially scrolled row");
            for (int i=0;i<grid.getChildCount();i++) grid.getChildAt(i).setForeground(new android.graphics.drawable.ColorDrawable(Color.MAGENTA));
            Bitmap pixels=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            try {
                picker.draw(new Canvas(pixels)); int headerLeaks=0,bodyMarkers=0;
                for (int y=1;y<height-1;y++) for (int x=Ui.dp(activity,20);x<width-Ui.dp(activity,20);x++) if (pixels.getPixel(x,y)==Color.MAGENTA) { if (y<grid.getTop()) headerLeaks++; else bodyMarkers++; }
                require(bodyMarkers>100,"candidate marker pixels are actually rendered");
                require(headerLeaks==0,"scrolled candidate pixels never enter tabs or right-side actions; leaked="+headerLeaks);
            } finally { pixels.recycle(); }
        });
    }
    private void touchThroughRoot(View target,int action,float x,float y) {
        int[] targetLocation=new int[2],rootLocation=new int[2]; target.getLocationOnScreen(targetLocation); root.getLocationOnScreen(rootLocation);
        touch(root,action,x+targetLocation[0]-rootLocation[0],y+targetLocation[1]-rootLocation[1]);
    }
    private void checkDetailHeaders() {
        for (String id:List.of("rotation","media","volume","brightness","wifi","bluetooth","data","dnd","airplane","system_controls","nfc","hotspot","torch","screenshot","lock","apps")) {
            main(() -> owner.showDetails(id,null)); idle();
            ViewGroup card=root.findViewWithTag("detail-card"); require(card.findViewWithTag("detail-close")==null,"control detail has no close button: "+id);
            require(card.getChildAt(0).getHeight()==Ui.dp(activity,PanelUi.SLOT),"detail header retains its original height: "+id);
            checkCompactActions(card); main(owner::dismissDetails); idle();
        }
        main(owner::editControls); idle(); main(() -> panel.findViewWithTag("control-selected-wifi").performClick()); idle();
        require(panel.findViewWithTag("detail-close")==null && panel.findViewWithTag("order-menu-remove")!=null,"control editor detail retains its actions without a close button");
        main(() -> owner.act("back")); SystemClock.sleep(850); idle(); main(() -> owner.act("back")); idle();
        mount("controls",0,4);
    }
    private int checkCompactActions(View view) {
        int count=0;
        if (view instanceof android.widget.ImageButton || view instanceof android.widget.Button) { require(view.getParent() instanceof PanelActionSlot,"detail action retains an original-sized touch slot"); require(view.getScaleX()<=.8f && view.getScaleY()<=.8f,"detail artwork is compact"); count++; }
        if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) count+=checkCompactActions(group.getChildAt(i)); return count;
    }
    private void checkToggles() throws Exception {
        for (int mode:new int[]{android.content.res.Configuration.UI_MODE_NIGHT_NO,android.content.res.Configuration.UI_MODE_NIGHT_YES}) {
            android.content.res.Configuration config=new android.content.res.Configuration(activity.getResources().getConfiguration()); config.uiMode=(config.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)|mode; Context themed=activity.createConfigurationContext(config);
            int[] clicks={0}; GlassToggle toggle=new GlassToggle(themed,"NFC",true,() -> clicks[0]++),unknown=new GlassToggle(themed,"未知测试",null,() -> clicks[0]++);
            main(() -> { LinearLayout column=Ui.column(activity); column.addView(toggle); column.addView(unknown); root.removeAllViews(); root.addView(column); session.attach(column,false); }); idle();
            require(toggle.trackColor()==(mode==android.content.res.Configuration.UI_MODE_NIGHT_YES ? 0xFF30D158 : 0xFF34C759),"toggle uses theme-specific confirmed green");
            android.graphics.drawable.Drawable thumbBackground=toggle.thumb().getBackground(); require(thumbBackground instanceof android.graphics.drawable.RippleDrawable ripple && ripple.getDrawable(0) instanceof GlassSurface,"toggle thumb uses shared refractive renderer");
            android.view.accessibility.AccessibilityNodeInfo info=toggle.createAccessibilityNodeInfo(); require("NFC".contentEquals(info.getContentDescription()) && info.isCheckable() && info.isChecked(),"toggle exposes label and confirmed switch state"); info.recycle();
            float x=toggle.getWidth()/2f,y=toggle.getHeight()/2f; main(() -> { touch(toggle,0,x,y); touch(toggle,1,x,y); }); idle(); require(clicks[0]==1,"toggle click invokes backend once");
            require("已开启".contentEquals(toggle.getStateDescription()),"toggle does not fabricate backend state before readback");
            main(() -> { touch(toggle,0,x,y); touch(toggle,2,0,y); touch(toggle,3,0,y); }); idle(); require(clicks[0]==1,"canceled switch drag does not invoke backend");
            main(() -> { toggle.setEnabled(false); touch(toggle,0,x,y); touch(toggle,1,x,y); }); require(clicks[0]==1,"disabled switch does not invoke backend");
            main(() -> { touch(unknown,0,x,y); touch(unknown,1,x,y); }); idle(); require(clicks[0]==2 && "未知，选择开启或关闭".contentEquals(unknown.getStateDescription()),"unknown switch opens chooser without asserting state"); SystemClock.sleep(500); idle(); require(unknown.glassProgress()==0,"toggle resting material returns to solid white"); capture(mode==android.content.res.Configuration.UI_MODE_NIGHT_YES ? "toggle-dark" : "toggle-light");
            int draws=session.draws; SystemClock.sleep(500); idle(); require(session.draws-draws<5,"settled toggle has no idle draw loop");
        }
        int[] clicks={0}; GlassToggle toggle=new GlassToggle(activity,"滚动测试",false,() -> clicks[0]++); android.widget.ScrollView scroll=new android.widget.ScrollView(activity);
        main(() -> { LinearLayout column=Ui.column(activity); column.setPadding(0,Ui.dp(activity,90),0,0); column.addView(toggle); View space=new View(activity); column.addView(space,new LinearLayout.LayoutParams(1,Ui.dp(activity,800))); scroll.addView(column); root.removeAllViews(); root.addView(scroll,new FrameLayout.LayoutParams(-1,Ui.dp(activity,220))); }); idle();
        float x=toggle.getWidth()/2f,y=toggle.getTop()+toggle.getHeight()/2f;
        main(() -> touch(scroll,0,x,y)); main(() -> touch(scroll,2,x,y-Ui.dp(activity,25))); main(() -> touch(scroll,2,x,y-Ui.dp(activity,65))); main(() -> touch(scroll,2,x,y-Ui.dp(activity,85))); main(() -> touch(scroll,1,x,y-Ui.dp(activity,85))); idle();
        require(scroll.getScrollY()>0 && clicks[0]==0,"vertical swipe starting on switch transfers to real ScrollView without toggling");
    }
    private void touch(View view,int action,float x,float y) { long now=SystemClock.uptimeMillis(); android.view.MotionEvent event=android.view.MotionEvent.obtain(now,now,action,x,y,0); view.dispatchTouchEvent(event); event.recycle(); }
    private int checkLegacyTiles(View view,Bitmap rendered) {
        int count=0;
        if (view instanceof Panels.Tile tile) {
            require(!tile.compact && tile.getBackground() instanceof android.graphics.drawable.RippleDrawable,"legacy tile keeps click ripple");
            android.graphics.drawable.RippleDrawable ripple=(android.graphics.drawable.RippleDrawable)tile.getBackground();
            require(ripple.getDrawable(0) instanceof GlassSurface,"legacy full tile has glass instead of the opaque root");
            require(tile.face.getBackground()==null,"legacy icon has no duplicate glass layer"); count++;
            int[] location=new int[2]; tile.getLocationOnScreen(location); int readable=0,total=0;
            for (int y=tile.getHeight()/3;y<tile.getHeight()*2/3;y+=2) for (int x=tile.getWidth()/5;x<tile.getWidth()*4/5;x+=2) {
                int color=rendered.getPixel(location[0]+x,location[1]+y), brightness=Color.red(color)+Color.green(color)+Color.blue(color);
                if (tile.isSelected() ? brightness>600 : brightness<450) readable++; total++;
            }
            require(total>0 && readable>total*.6,"legacy tile protects foreground contrast over a white background; selected="+tile.isSelected());
        }
        if (view instanceof android.widget.Button button && !button.isSelected()) {
            int[] location=new int[2]; button.getLocationOnScreen(location); int dark=0,total=0;
            for (int y=button.getHeight()/3;y<button.getHeight()*2/3;y+=2) for (int x=button.getWidth()/5;x<button.getWidth()*4/5;x+=2) {
                int color=rendered.getPixel(location[0]+x,location[1]+y);
                if (Color.red(color)+Color.green(color)+Color.blue(color)<450) dark++; total++;
            }
            require(total>0 && dark>total*.6,"legacy text action protects foreground contrast over a white background");
        }
        if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) count+=checkLegacyTiles(group.getChildAt(i),rendered);
        return count;
    }
    private void checkPageSwitches() {
        mount("media",0,4);
        VirtualDisplay virtual=activity.getSystemService(DisplayManager.class).createVirtualDisplay("glass-switch-check",320,240,160,null,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        require(virtual!=null,"isolated page-switch display");
        FrameLayout host=new FrameLayout(activity); Probe probe=new Probe(activity); PanelGlassSession[] opening={null};
        try {
            main(() -> {
                try { java.lang.reflect.Method attach=android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext",Context.class); attach.setAccessible(true); attach.invoke(owner,activity); }
                catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                session.close(); owner.display=virtual.getDisplay(); opening[0]=new PanelGlassSession(activity,owner.display); owner.panelGlass=opening[0];
                setOwnerField("panelHost",host);
                setOwnerField("dock",new DockView(activity,owner.prefs,owner.placement,0,new DockView.Listener() { public void action(String id) { } public void configure() { } }));
                setOwnerField("windows",java.lang.reflect.Proxy.newProxyInstance(android.view.WindowManager.class.getClassLoader(),new Class<?>[]{android.view.WindowManager.class},(proxy,method,args) -> {
                    if (method.getName().equals("updateViewLayout")) ((View)args[0]).setLayoutParams((ViewGroup.LayoutParams)args[1]);
                    return method.getReturnType()==boolean.class ? false : null;
                }));
                host.setLayoutParams(new android.view.WindowManager.LayoutParams());
                opening[0].capture(probe,() -> owner.panelGlass==opening[0],() -> host.setVisibility(View.VISIBLE));
                owner.showPanel("rotation");
                require(owner.panelGlass==opening[0] && probe.calls==1,"pending page switch retains the single capture");
                require(host.getVisibility()==View.INVISIBLE,"pending page stays outside screenshot composition");
            });
            SystemClock.sleep(250); idle();
            require(host.getVisibility()==View.VISIBLE && opening[0].state.equals("timeout"),"original timeout shows the switched page");
            main(() -> {
                owner.showPanel("media"); require(owner.panelGlass==opening[0] && probe.calls==1,"failed page switch does not retry capture");
                owner.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW); owner.showPanel("rotation");
                require(owner.panelGlass==null && (boolean)ownerField("panelMemoryFallback"),"page switch keeps low-memory fallback for this opening");
                owner.closePanel(); require(!(boolean)ownerField("panelMemoryFallback"),"explicit close resets low-memory fallback for the next opening");
            });
        } finally { main(() -> { owner.closePanel(); if (opening[0]!=null) opening[0].close(); }); virtual.release(); }
    }
    private Object ownerField(String name) { try { java.lang.reflect.Field field=CoverService.class.getDeclaredField(name); field.setAccessible(true); return field.get(owner); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); } }
    private void setOwnerField(String name,Object value) { try { java.lang.reflect.Field field=CoverService.class.getDeclaredField(name); field.setAccessible(true); field.set(owner,value); } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); } }
    private void checkRotationTabs() throws Exception {
        mount("controls",0,4); main(() -> owner.showDetails("rotation",null)); SystemClock.sleep(950); idle();
        ControlRotationTabs actual=root.findViewWithTag("detail-rotation-tabs");
        View backplate=root.findViewWithTag("detail-card");
        require(actual!=null && actual.getChildAt(0).getVisibility()==View.VISIBLE,"rotation sheet uses the original liquid renderer");
        require(backplate.getWidth()==Ui.dp(activity,240),"rotation backplate is compact with room for expanded end tabs");
        require(actual.getWidth()==Ui.dp(activity,ControlRotationTabs.WIDTH) && Math.abs(actual.getLeft()-(((View)actual.getParent()).getWidth()-actual.getWidth())/2)<=1,"four rotation labels use a compact centered strip");
        int right=0;
        for (int i=0;i<4;i++) { View angle=actual.findViewWithTag("detail-angle-"+i); require(angle.getVisibility()==View.INVISIBLE && angle.getTop()==Ui.dp(activity,2) && angle.getLeft()>=right && angle.getWidth()>0,"four angles share one row without drawing fallback buttons"); right=angle.getRight(); }
        require(root.findViewWithTag("detail-switch-rotation")!=null,"rotation tab keeps the automatic switch");
        capture("rotation-tabs");
        Bitmap resting=test.getUiAutomation().takeScreenshot(); int[] stripPosition=new int[2]; actual.getLocationOnScreen(stripPosition);
        float pressX=actual.getWidth()/8f,pressY=actual.getHeight()/2f;
        main(() -> touchThroughRoot(actual,0,pressX,pressY)); SystemClock.sleep(400); idle(); capture("rotation-tabs-pressed");
        Bitmap pressed=test.getUiAutomation().takeScreenshot(); int upper=0,lower=0,left=0;
        for (int dx=-Ui.dp(activity,12);dx<=Ui.dp(activity,12);dx++) for (int dy=2;dy<=Ui.dp(activity,4);dy++) {
            int x=stripPosition[0]+Math.round(pressX)+dx,top=stripPosition[1]-dy,bottom=stripPosition[1]+actual.getHeight()+dy;
            int a=resting.getPixel(x,top),b=pressed.getPixel(x,top); if (Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b))>24) upper++;
            a=resting.getPixel(x,bottom); b=pressed.getPixel(x,bottom); if (Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b))>24) lower++;
        }
        for (int dy=-Ui.dp(activity,6);dy<=Ui.dp(activity,6);dy++) for (int dx=2;dx<=Ui.dp(activity,4);dx++) {
            int x=stripPosition[0]-dx,y=stripPosition[1]+actual.getHeight()/2+dy,a=resting.getPixel(x,y),b=pressed.getPixel(x,y);
            if (Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b))>24) left++;
        }
        require(rotationCardEdgeChanges(resting,pressed,backplate,stripPosition[1],actual.getHeight())==0,"expanded first tab keeps clear space before the backplate edge"); pressed.recycle();
        main(() -> touchThroughRoot(actual,2,actual.getWidth()*7/8f,pressY)); SystemClock.sleep(400); idle(); capture("rotation-tabs-drag-right");
        Bitmap last=test.getUiAutomation().takeScreenshot(); require(rotationCardEdgeChanges(resting,last,backplate,stripPosition[1],actual.getHeight())==0,"expanded last tab keeps clear space before the backplate edge"); last.recycle();
        resting.recycle(); main(() -> touchThroughRoot(actual,3,pressX,pressY)); SystemClock.sleep(400); idle();
        require(upper>30 && lower>30 && left>30,"pressed rotation thumb draws beyond strip edges: upper="+upper+", lower="+lower+", left="+left);
        main(owner::dismissDetails); idle();
        int[] commits={0},chosen={-1}; ControlRotationTabs[] host={null};
        main(() -> { host[0]=new ControlRotationTabs(activity,0,angle -> { commits[0]++; chosen[0]=angle; }); root.removeAllViews(); root.addView(host[0],new FrameLayout.LayoutParams(Ui.dp(activity,260),Ui.dp(activity,PanelUi.SLOT))); session.decorate(); }); idle(); ControlRotationTabs tabs=host[0];
        main(() -> tabs.source(2)); SystemClock.sleep(400); idle(); require(commits[0]==0,"programmatic current-angle updates cannot execute rotation");
        float y=tabs.getHeight()/2f,from=tabs.getWidth()*5/8f,to=tabs.getWidth()/8f;
        main(() -> touch(tabs,0,from,y)); idle(); main(() -> touch(tabs,1,from,y)); SystemClock.sleep(400); idle();
        require(commits[0]==1 && chosen[0]==2,"clicking the current angle still explicitly locks it");
        main(() -> touch(tabs,0,from,y)); idle(); main(() -> touch(tabs,2,to,y)); idle(); require(commits[0]==1,"drag does not execute before release");
        main(() -> touch(tabs,3,to,y)); SystemClock.sleep(400); idle(); require(commits[0]==1,"canceled rotation drag cannot execute late");
        main(() -> touch(tabs,0,from,y)); idle(); main(() -> touch(tabs,2,to,y)); idle(); main(() -> touch(tabs,1,to,y)); SystemClock.sleep(600); idle();
        require(commits[0]==2 && chosen[0]==0,"released drag executes its selected angle once");
        main(() -> { session.close(); owner.panelGlass=null; tabs.setLayoutParams(new FrameLayout.LayoutParams(Ui.dp(activity,180),Ui.dp(activity,PanelUi.SLOT))); }); idle();
        require(tabs.getChildAt(0).getVisibility()==View.GONE,"missing backdrop restores native angle tabs");
        main(() -> tabs.findViewWithTag("detail-angle-3").performClick()); idle(); require(commits[0]==3 && chosen[0]==3,"native fallback angle remains usable at narrow width");
        main(() -> { touch(tabs,0,to,y); root.removeView(tabs); }); SystemClock.sleep(400); idle(); require(commits[0]==3,"unmount cancels pending rotation input");
    }
    private int rotationCardEdgeChanges(Bitmap before,Bitmap after,View card,int stripTop,int stripHeight) {
        int[] location=new int[2]; card.getLocationOnScreen(location); int changed=0,padding=Ui.dp(activity,4);
        for (int edge:new int[]{location[0],location[0]+card.getWidth()}) for (int x=edge-padding;x<edge+padding;x++) for (int y=stripTop-Ui.dp(activity,8);y<stripTop+stripHeight+Ui.dp(activity,8);y++) {
            int a=before.getPixel(x,y),b=after.getPixel(x,y);
            if (Math.abs(Color.red(a)-Color.red(b))+Math.abs(Color.green(a)-Color.green(b))+Math.abs(Color.blue(a)-Color.blue(b))>24) changed++;
        }
        return changed;
    }
    private void checkSelectedDetail() {
        android.widget.Button button=root.findViewWithTag("detail-angle-"+activity.getDisplay().getRotation());
        require(button!=null && button.isSelected() && button.getCurrentTextColor()==0xFF0091FF && button.getVisibility()==View.INVISIBLE,"current angle retains blue tab semantics under the liquid renderer");
        Bitmap rendered; int[] location=new int[2]; int bright,total;
        DetailSheet sheet=(DetailSheet)root.getChildAt(root.getChildCount()-1);
        android.widget.ImageButton picker=Ui.iconButton(activity,R.drawable.ic_ms_apps,"播放器选择",() -> { });
        main(() -> { picker.setSelected(true); sheet.content.addView(picker,0,new LinearLayout.LayoutParams(Ui.dp(activity,36),Ui.dp(activity,36))); }); idle();
        rendered=test.getUiAutomation().takeScreenshot(); picker.getLocationOnScreen(location); bright=total=0;
        for (int y=4;y<picker.getHeight()-4;y+=2) for (int x=4;x<picker.getWidth()-4;x+=2) { int color=rendered.getPixel(location[0]+x,location[1]+y); if (Color.red(color)+Color.green(color)+Color.blue(color)>600) bright++; total++; }
        rendered.recycle(); require(total>0 && bright>total*.01 && bright<total*.5,"selected detail icon remains white on a dark glass field"); main(() -> sheet.content.removeView(picker)); idle();
    }
    private void checkNotificationPixels() {
        Bitmap rendered=test.getUiAutomation().takeScreenshot(); require(rendered!=null,"hardware page screenshot");
        int[] where=new int[2]; panel.getLocationOnScreen(where);
        long error=0; int max=0,count=0;
        for (int y=100;y<Math.min(source.getHeight(),panel.getHeight())-100;y+=7) for (int x=100;x<Math.min(source.getWidth(),panel.getWidth())-100;x+=7) {
            int expected=source.getPixel(where[0]+x,where[1]+y), actual=rendered.getPixel(where[0]+x,where[1]+y);
            for (int shift:new int[]{16,8,0}) { int delta=Math.abs((actual>>shift&255)-Math.round((expected>>shift&255)*.76f)); error+=delta; max=Math.max(max,delta); count++; }
        }
        if (max>3) {
            try (FileOutputStream output=new FileOutputStream(new File(activity.getFilesDir(),"glass-pixel-failure.png"))) { rendered.compress(Bitmap.CompressFormat.PNG,100,output); }
            catch (java.io.IOException failure) { throw new AssertionError(failure); }
        }
        rendered.recycle(); require(count>0 && max<=3 && error/(double)count<1,"notification center only dims, no added blur: max="+max+" mean="+error/(double)Math.max(1,count)+" count="+count+" panel="+panel.getWidth()+"x"+panel.getHeight()+" source="+source.getWidth()+"x"+source.getHeight()+" attached="+panel.isAttachedToWindow());
        evidence="notification interior dim error max "+max+"/255; ";
    }
    private void checkControlPixels() {
        Bitmap rendered=test.getUiAutomation().takeScreenshot(); require(rendered!=null,"hardware blurred page screenshot");
        int[] where=new int[2]; panel.getLocationOnScreen(where);
        int y=panel.getHeight()/2, left=panel.getWidth()/4,right=panel.getWidth()*3/4;
        long before=0,after=0;
        for (int x=left+1;x<right;x++) { before+=Math.abs((source.getPixel(x,y)&255)-(source.getPixel(x-1,y)&255)); after+=Math.abs((rendered.getPixel(where[0]+x,where[1]+y)&255)-(rendered.getPixel(where[0]+x-1,where[1]+y)&255)); }
        rendered.recycle(); if (before > 500) require(after<=before*.30,"high-frequency blur exceeds the .76 dim-only gain: "+before+" -> "+after);
    }
    private void showNotifications() {
        main(() -> { for (int i=0;i<panel.getChildCount();i++) panel.getChildAt(i).setVisibility(View.VISIBLE); ((NotificationCenterView)panel.findViewWithTag("notification-center")).update(true,notices(18)); }); idle();
    }
    private List<android.service.notification.StatusBarNotification> notices(int count) {
        java.util.ArrayList<android.service.notification.StatusBarNotification> list=new java.util.ArrayList<>();
        for (int i=0;i<count;i++) {
            android.app.Notification notification=new android.app.Notification.Builder(activity,"glass-fixture").setSmallIcon(R.drawable.ic_ms_notifications).setContentTitle(i<3 ? "家庭群" : "玻璃通知测试 "+i).setContentText(i<3 ? "今晚一起吃饭，回来的路上记得带伞。" : "这是一条用于检查模糊、折射与文字可读性的本地长通知。背景仍能透出层次，正文和图标保持清晰。").setGroup(i<3 ? "family" : "item-"+i).build();
            list.add(new android.service.notification.StatusBarNotification(activity.getPackageName(),activity.getPackageName(),i,"glass",android.os.Process.myUid(),0,0,notification,android.os.Process.myUserHandle(),System.currentTimeMillis()-i*60000L));
        }
        return list;
    }
    private void checkNotificationUpdates() throws Exception {
        mount("controls",1,4);
        ViewGroup referenceHeader = panel.findViewWithTag("panel-header"), referenceSlot = (ViewGroup) referenceHeader.getChildAt(1);
        View referenceAction = referenceSlot.getChildAt(0);
        int referenceSize = referenceSlot.getWidth(), referenceGap = referenceHeader.getChildAt(2).getLeft() - referenceSlot.getRight();
        float referenceDiameter = referenceAction.getWidth() * referenceAction.getScaleX();
        mount("notifications",1,4); showNotifications();
        ViewGroup notificationHeader = panel.findViewWithTag("panel-header");
        for (int i = 1; i < notificationHeader.getChildCount(); i++) {
            ViewGroup slot = (ViewGroup) notificationHeader.getChildAt(i); View action = slot.getChildAt(0);
            require(slot.getHeight() == referenceSize && action.getScaleY() == referenceAction.getScaleY(), "notification header shares control header height and artwork scale");
            if (i + 1 < notificationHeader.getChildCount()) require(notificationHeader.getChildAt(i + 1).getLeft() - slot.getRight() == referenceGap, "notification header shares control header action spacing");
        }
        ViewGroup list=panel.findViewWithTag("notification-list"); View first=list.getChildAt(0);
        main(() -> { NotificationCenterView center=panel.findViewWithTag("notification-center"); List<android.service.notification.StatusBarNotification> items=notices(35); for (int i=0;i<60;i++) center.update(true,items); }); idle();
        require(list.getChildAt(0)==first && session.captures==0 && session.preparations==1,"notification bursts reuse rows and the same texture");
        NotificationSwipeRow row=(NotificationSwipeRow)((ViewGroup)first).getChildAt(0);
        main(() -> row.surface.performClick()); idle(); capture("notification-expanded");
        main(() -> {
            View expand = row.findViewWithTag("notification-expand");
            require(!expand.isSelected() && expand.getBackground() instanceof android.graphics.drawable.RippleDrawable && !(expand.getParent() instanceof PanelActionSlot), "expanded chevron retains transparent feedback without a white selected glass disc");
            require(expand.getContentDescription().toString().equals("收起通知组"), "plain chevron still announces its actual expanded state");
            View icon = row.findViewWithTag("notification-icon"); int[] image = new int[2], card = new int[2]; icon.getLocationInWindow(image); row.surface.getLocationInWindow(card);
            require(Math.abs(image[1] + icon.getHeight() / 2f - card[1] - row.surface.getHeight() / 2f) <= Ui.dp(activity, 1), "notification app icon is vertically centered inside its card");
            for (String tag : List.of("notification-settings", "notification-clear")) {
                ViewGroup action = row.findViewWithTag(tag); require(action.getChildCount() == 1 && action.getContentDescription().length() > 0 && action.getWidth() == Ui.dp(activity, PanelUi.SLOT), "icon-only action keeps its accessible label and matches control header slot: " + tag);
                View face = action.findViewWithTag("notification-action-face");
                require(action.getWidth() == referenceSize && Math.abs(face.getWidth() * face.getScaleX() - referenceDiameter) < .1f, "card action matches the actual control header button dimensions");
                if (tag.equals("notification-settings")) require(row.findViewWithTag("notification-clear").getLeft() - action.getRight() == referenceGap, "card buttons match actual control header spacing");
            }
        });
        main(() -> row.showActions(true)); idle(); capture("notification-actions");
        require(row.willNotDraw() && row.getBackground()==null,"stack's old opaque painter is disabled");
    }
    private void checkCardCaptureAreas() {
        DockGeometry.Box[] cutouts = {new DockGeometry.Box(379,654,369,66), new DockGeometry.Box(654,0,66,369), new DockGeometry.Box(0,0,369,66), new DockGeometry.Box(0,379,66,369)};
        Prefs prefs = new Prefs(activity);
        boolean effects = prefs.panelBlur(); prefs.data.edit().putBoolean("panel_blur",true).commit();
        try {
            for (int rotation=0;rotation<4;rotation++) {
                int angle=rotation;
                int width=rotation%2==0 ? 748 : 720, height=rotation%2==0 ? 720 : 748;
                DockGeometry.Placement dock=DockGeometry.resolve(width,height,List.of(cutouts[rotation]),1,(rotation+3)%4,.46f,.088f,true);
                DockGeometry.Box safe=DockGeometry.panelContent(dock,width,height,List.of(cutouts[rotation]));
                DockGeometry.Box content=new DockGeometry.Box(safe.x(),safe.y()+24,safe.width(),safe.height()-24);
                android.graphics.Rect appBounds=new android.graphics.Rect(safe.x(),safe.y(),safe.right(),safe.bottom());
                require(rotation!=0 || safe.y()==0 && safe.bottom()<height,"0-degree camera reserve is below the content");
                require(rotation!=2 || safe.y()>0 && safe.bottom()==height,"180-degree camera reserve is above the content");
                VirtualDisplay display=activity.getSystemService(DisplayManager.class).createVirtualDisplay("glass-card-coverage",width,height,160,null,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
                require(display!=null,"isolated rotated card display");
                try {
                    for (InterfaceCard.Definition definition:List.of(InterfaceCard.NOTIFICATIONS,InterfaceCard.CONTROLS,InterfaceCard.LAUNCHER,InterfaceCard.TASKS)) main(() -> {
                        InterfaceCard card=new InterfaceCard(activity,prefs,definition,new FrameLayout(activity),() -> { },() -> { });
                        try {
                            card.frameBounds(new DockGeometry.Box(0,0,width,height),safe); card.contentSafeBounds(content);
                            card.statusBounds(DockGeometry.statusBar(safe,width,20,1));
                            card.prepareGlass(new Probe(activity),display.getDisplay(),prefs,() -> true,() -> { },false);
                            PanelGlassSession glass=card.glass();
                            require(glass!=null && glass.canCaptureWindow(appBounds),"app excluding rotation-specific system strip covers "+definition.id()+" at rotation "+angle);
                            require(!glass.canCaptureWindow(new android.graphics.Rect(safe.x()+1,safe.y(),safe.right()-1,safe.bottom())),"cropped floating window remains rejected for "+definition.id()+" at rotation "+angle);
                            if (definition.showStatusBar()) require(!glass.canCaptureWindow(new android.graphics.Rect(content.x(),content.y(),content.right(),content.bottom())),"control capture also requires its status row");
                        } finally { card.release(); }
                    });
                } finally { display.release(); }
            }
        } finally { prefs.data.edit().putBoolean("panel_blur",effects).commit(); }
    }
    private void checkRequests() {
        VirtualDisplay display=activity.getSystemService(DisplayManager.class).createVirtualDisplay("glass-request-check",320,240,160,null,DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        require(display!=null,"isolated test display");
        try {
            Probe service=new Probe(activity); int[] finished={0}; PanelGlassSession[] request={null};
            main(() -> { request[0]=new PanelGlassSession(activity,display.getDisplay()); request[0].capture(service,() -> true,() -> finished[0]++); }); SystemClock.sleep(250); idle();
            require(service.calls==1 && finished[0]==1 && !request[0].ready() && request[0].state.equals("timeout"),"one request times out once: "+service.calls+"/"+finished[0]+"/"+request[0].state);
            main(() -> service.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW)); require(finished[0]==1,"late failure cannot complete twice"); main(request[0]::close);
            android.hardware.HardwareBuffer late=android.hardware.HardwareBuffer.create(320,240,android.hardware.HardwareBuffer.RGBA_8888,1,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            main(() -> request[0].receive(late,android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),320,240)); require(late.isClosed() && request[0].bytes()==0,"late successful callback closes native buffer without adoption");
            android.hardware.HardwareBuffer accepted=android.hardware.HardwareBuffer.create(320,240,android.hardware.HardwareBuffer.RGBA_8888,1,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            main(() -> { request[0]=new PanelGlassSession(activity,display.getDisplay()); request[0].capture(service,() -> true,() -> { }); request[0].capture(service,() -> true,() -> { throw new AssertionError("duplicate capture"); }); request[0].receive(accepted,android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),320,240); }); idle();
            require(accepted.isClosed() && request[0].captures==1 && request[0].ready(),"successful native frame is adopted once and releases incoming handle: "+request[0].state); main(request[0]::close);
            for (int[] size:new int[][]{{640,480},{160,120},{640,420}}) {
                android.hardware.HardwareBuffer scaled=android.hardware.HardwareBuffer.create(size[0],size[1],android.hardware.HardwareBuffer.RGBA_8888,1,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
                main(() -> {
                    request[0]=new PanelGlassSession(activity,display.getDisplay()); request[0].capture(service,() -> true,() -> { });
                    try { java.lang.reflect.Field kind=PanelGlassSession.class.getDeclaredField("sourceKind"); kind.setAccessible(true); kind.set(request[0],"window"); }
                    catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
                    request[0].receive(scaled,android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),320,240);
                }); idle();
                boolean uniform=size[0]*240==size[1]*320;
                require(scaled.isClosed() && request[0].ready()==uniform && request[0].preparations==(uniform ? 1 : 0),"window scale accepted but cropped frame rejected: "+size[0]+"x"+size[1]+" / "+request[0].state);
                require(request[0].diagnostics().contains(size[0]+"×"+size[1]+" → 显示区域 320×240"),"diagnostics distinguish captured pixels from display geometry"); main(request[0]::close);
            }
            main(() -> { request[0]=new PanelGlassSession(activity,display.getDisplay()); request[0].capture(service,() -> true,() -> finished[0]++); request[0].close(); service.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY); });
            require(finished[0]==1 && request[0].bytes()==0,"close cancels completion and retained data");
            main(() -> { request[0]=new PanelGlassSession(activity,display.getDisplay()); request[0].capture(service,() -> true,() -> finished[0]++); service.callback.onFailure(AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW); });
            require(request[0].state.startsWith("capture-") && !request[0].ready(),"protected content has explicit fallback"); main(request[0]::close);
            android.hardware.HardwareBuffer obsolete=android.hardware.HardwareBuffer.create(320,240,android.hardware.HardwareBuffer.RGBA_8888,1,android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | android.hardware.HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
            int[] sourceFinished={0};
            main(() -> {
                request[0]=new PanelGlassSession(activity,display.getDisplay()); request[0].capture(service,() -> true,() -> sourceFinished[0]++);
                request[0].sourceWindow(71,new android.graphics.Rect(0,0,320,240)); request[0].invalidateSource(); request[0].invalidateSource();
                request[0].receive(obsolete,android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB),320,240);
                request[0].capture(service,() -> true,() -> { throw new AssertionError("invalid source recaptured"); });
            }); idle();
            require(obsolete.isClosed() && sourceFinished[0]==1 && request[0].captures==1 && request[0].preparations==0 && request[0].bytes()==0 && request[0].state.equals("source-changed"),"source lost during preparation finishes once, releases late buffers and never retries"); main(request[0]::close);
            int calls=service.calls;
            main(() -> { request[0]=new PanelGlassSession(activity,activity.getDisplay()); request[0].capture(service,() -> true,() -> finished[0]++); });
            require(service.calls==calls && request[0].state.equals("unsupported"),"never capture default display"); main(request[0]::close);
        } finally { display.release(); }
    }
    private void checkScaledBackdrop() {
        for (int[] shape:new int[][]{{320,240},{240,320}}) {
            int width=shape[0], height=shape[1];
            Bitmap source=Bitmap.createBitmap(width*2,height*2,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(source); Paint paint=new Paint();
            canvas.drawColor(Color.RED); paint.setColor(Color.GREEN); canvas.drawRect(width,0,width*2,height,paint);
            paint.setColor(Color.BLUE); canvas.drawRect(0,height,width,height*2,paint); paint.setColor(Color.YELLOW); canvas.drawRect(width,height,width*2,height*2,paint);
            GlassBackdrop backdrop=GlassBackdrop.prepare(source,1,384,width,height);
            try {
                require(backdrop.width==width && backdrop.height==height && backdrop.sharp.getWidth()==width && backdrop.sharp.getHeight()==height,"scaled window textures use display-space dimensions in both orientations");
                Bitmap pixels=backdrop.sharp.copy(Bitmap.Config.ARGB_8888,false);
                try {
                    require(pixels.getPixel(width/4,height/4)==Color.RED && pixels.getPixel(width*3/4,height/4)==Color.GREEN && pixels.getPixel(width/4,height*3/4)==Color.BLUE && pixels.getPixel(width*3/4,height*3/4)==Color.YELLOW,"all four corners survive scaling without crop or mirror");
                } finally { pixels.recycle(); }
                require(source.isRecycled() && backdrop.bytes<=12L*1024*1024,"original scaled source released and retained texture budget bounded");
            } finally { backdrop.close(); }
            require(backdrop.sharp.isRecycled() && backdrop.soft.isRecycled() && backdrop.strong.isRecycled(),"scaled textures release on close");
        }
    }
    private static final class Probe extends AccessibilityService {
        int calls; TakeScreenshotCallback callback;
        Probe(Context context) { attachBaseContext(context); }
        @Override public Executor getMainExecutor() { return command -> new Handler(Looper.getMainLooper()).post(command); }
        @Override public void takeScreenshot(int display,Executor executor,TakeScreenshotCallback callback) { calls++; this.callback=callback; }
        @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
        @Override public void onInterrupt() { }
    }
    private static Bitmap pattern(int width,int height,int kind) {
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); Canvas canvas=new Canvas(bitmap); Paint paint=new Paint();
        canvas.drawColor(Color.rgb(40,60,90));
        if (kind==5) canvas.drawColor(Color.WHITE);
        else if (kind==1) { paint.setColor(Color.WHITE); for (int x=0;x<width;x+=36) canvas.drawRect(x,0,x+3,height,paint); for (int y=0;y<height;y+=36) canvas.drawRect(0,y,width,y+3,paint); }
        else if (kind==2) { paint.setColor(Color.WHITE); for (int x=0;x<width;x+=24) canvas.drawRect(x,0,x+12,height,paint); }
        else if (kind==3) { paint.setColor(0xFFF0F2F4); canvas.drawRect(0,0,width/2f,height,paint); }
        else if (kind==4) { paint.setShader(new android.graphics.LinearGradient(0,0,width,height,0xFF145E98,0xFF884475,android.graphics.Shader.TileMode.CLAMP)); canvas.drawRect(0,0,width,height,paint); }
        else { for (int y=30;y<height;y+=150) for (int x=20;x<width;x+=150) { paint.setColor(new int[]{0xFFFF741C,0xFF8853CE,0xFF13AD67,0xFF248CDA}[(x/150+y/150)%4]); canvas.drawRoundRect(x,y,x+100,y+100,22,22,paint); } }
        return bitmap;
    }
    private void capture(String name) throws Exception {
        Bitmap bitmap=test.getUiAutomation().takeScreenshot(); if (bitmap==null) return;
        File directory=new File(activity.getFilesDir(),"panel-glass-checks"); directory.mkdirs();
        try (FileOutputStream file=new FileOutputStream(new File(directory,name+".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG,100,file); } finally { bitmap.recycle(); }
    }
}
