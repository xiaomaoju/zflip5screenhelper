package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs under the emulator-only runner; all privileged replies are fake. */
final class ConnectivityChecks {
    private final Instrumentation test;
    private CoverService owner;
    private Activity activity;
    private FakeService fake;
    private int assertions;
    ConnectivityChecks(Instrumentation test) { this.test = test; }
    String run() throws Exception {
        Context context = test.getTargetContext(); Prefs prefs = new Prefs(context); Map<String, ?> previous = prefs.data.getAll(); CoverService prior = CoverService.instance;
        ShizukuBridge bridge = CoverApp.bridge(context); Object remote = field(ShizukuBridge.class, "remote").get(bridge);
        SurfaceTexture texture = new SurfaceTexture(false); texture.setDefaultBufferSize(400, 400); Surface surface = new Surface(texture);
        VirtualDisplay display = context.getSystemService(DisplayManager.class).createVirtualDisplay("Connectivity checks", 400, 400, 160, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
        if (display == null) throw new AssertionError("owned external display unavailable");
        try {
            fake = new FakeService(); field(ShizukuBridge.class, "remote").set(bridge, fake);
            prefs.data.edit().putInt("display", display.getDisplay().getDisplayId()).putBoolean("enabled", true).putBoolean("panel_media", false).putBoolean("panel_brightness", false).putBoolean("panel_volume", false).commit();
            prefs.saveActions("panel", List.of("wifi", "bluetooth", "nfc", "hotspot"));
            activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            main(() -> {
                owner = new CoverService(); Method attach = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); attach.setAccessible(true); attach.invoke(owner, context);
                owner.prefs = prefs; owner.screenContext = activity; owner.display = display.getDisplay(); CoverService.instance = owner;
                DockGeometry.Box box = new DockGeometry.Box(0, 0, 620, 610); owner.placement = new DockGeometry.Placement(box, box, box, DockGeometry.BOTTOM, false);
                FrameLayout root = new FrameLayout(activity); root.addView(owner.buildPanelContent("controls", box, 0), new FrameLayout.LayoutParams(620, 610)); activity.setContentView(root);
            }); idle();
            checkDelayedDetailSizes();
            require(ActionCatalog.valid("nfc") && ActionCatalog.valid("hotspot"), "both built-in IDs accepted");
            main(() -> { require(tag("control-nfc") != null && tag("control-hotspot") != null, "existing dashboard renders both controls"); tag("control-hotspot").performLongClick(); });
            waitFor(() -> findText("Native Demo") != null); require(fake.listener != null, "visible panel subscribes to connectivity events");
            main(() -> tag("hotspot-config").performClick());
            main(() -> ((EditText) tag("hotspot-name")).setText("Cancelled Demo"));
            main(() -> findText("取消").performClick());
            require(fake.writes == 0, "cancel never reaches privileged configuration writer");
            main(() -> { tag("hotspot-config").performClick(); ((EditText) tag("hotspot-password")).setText("123"); findText("保存").performClick(); });
            require(fake.writes == 0, "invalid password never reaches backend");
            require(findText("密码需为 8–63 位英文字母、数字或符号") != null, "inline validation feedback is visible");
            main(() -> { ((EditText) tag("hotspot-name")).setText("Changed Demo"); ((EditText) tag("hotspot-password")).setText("valid-demo-2026"); findText("保存").performClick(); });
            require(findText("应用并重启") != null && fake.writes == 0, "live hotspot changes await explicit restart confirmation");
            main(() -> findText("返回修改").performClick());
            main(() -> require(((EditText) tag("hotspot-name")).getText().toString().equals("Changed Demo"), "restart back preserves draft"));
            main(() -> findText("保存").performClick());
            fake.hold = new CountDownLatch(1); main(() -> findText("应用并重启").performClick()); waitFor(() -> fake.writes == 1);
            main(() -> require(!findText("应用并重启").isEnabled(), "pending operation disables duplicate submit"));
            fake.hold.countDown(); waitFor(() -> findText("Changed Demo") != null);
            require(fake.lastPatch.getBoolean("restart"), "restart approval is sent explicitly");
            require(fake.lastPatch.getString("revision").equals("a".repeat(64)), "editing retains original configuration identity");
            main(() -> tag("hotspot-share").performClick()); require(tag("hotspot-qr") != null, "native QR generated in current sheet");
            main(() -> ((DetailSheet) tag("detail-sheet")).back());
            main(() -> { tag("hotspot-band").performClick(); clickRow("5 GHz"); findText("应用并重启").performClick(); }); waitFor(() -> fake.band == 2); waitFor(() -> tag("hotspot-band") != null);
            require(fake.lastPatch.getInt("band") == 2 && !fake.lastPatch.has("password"), "band patch leaves password untouched");
            main(() -> { tag("hotspot-timeout").performClick(); clickRow("系统默认"); findText("应用并重启").performClick(); }); waitFor(() -> fake.timeout == 0); waitFor(() -> tag("hotspot-timeout") != null);
            require(fake.lastPatch.getLong("timeout") == 0, "system default stays distinct from disabled shutdown");
            fake.clientsKnown = false; fake.invalidate(); waitFor(() -> findText("连接设备 —") != null);
            main(() -> tag("hotspot-clients").performClick()); require(findText("设备列表暂不可用，请刷新或在系统设置中查看。") != null, "missing client capability stays unknown");
            main(() -> { owner.dismissDetails(); owner.showDetails("nfc", null); }); waitFor(() -> findText("仅解锁时使用") != null);
            SystemClock.sleep(500); idle(); View card=tag("detail-card"),power=tag("connectivity-power"); GlassToggle toggle=(GlassToggle)tag("connectivity-toggle"); int width=card.getWidth(),height=card.getHeight();
            require(toggle.glassProgress()==0,"NFC idle thumb is opaque rather than a permanent lens");
            boolean[] glassPhase={false}; android.view.ViewTreeObserver.OnDrawListener phase=() -> glassPhase[0]|=toggle.glassProgress()>0;
            fake.hold=new CountDownLatch(1); main(() -> { toggle.getViewTreeObserver().addOnDrawListener(phase); power.performClick(); }); SystemClock.sleep(120); idle();
            main(() -> toggle.getViewTreeObserver().removeOnDrawListener(phase));
            require(card.getWidth()==width && card.getHeight()==height,"NFC pending feedback keeps card dimensions fixed");
            require(glassPhase[0],"NFC transition renders an animated glass phase");
            fake.readHold=new CountDownLatch(1); fake.hold.countDown(); waitFor(() -> fake.nfc == 0); SystemClock.sleep(150); idle();
            require(!toggle.isEnabled(),"NFC remains disabled between write acknowledgement and delayed readback"); int nfcWrites=fake.nfcWrites; main(power::performClick); require(fake.nfcWrites==nfcWrites,"old snapshot cannot resubmit while awaiting readback");
            fake.readHold.countDown(); waitFor(() -> findText("已关闭 · 整机开关") != null); SystemClock.sleep(600); idle();
            require(tag("connectivity-power")==power && tag("connectivity-toggle")==toggle,"NFC readback updates existing row and preserves transition ownership");
            require(card.getWidth()==width && card.getHeight()==height,"NFC confirmed readback keeps card dimensions fixed"); require(toggle.glassProgress()==0,"NFC transition returns to opaque resting thumb");
            main(power::performClick); waitFor(() -> fake.nfc==1); waitFor(() -> findText("已开启 · 整机开关")!=null); require(card.getWidth()==width && card.getHeight()==height,"second toggle uses fresh state and the same size");
            fake.unknownRead=true; main(power::performClick); waitFor(() -> findText("状态未知或正在切换 · 整机开关")!=null); require(toggle.isEnabled(),"unknown readback exits pending and keeps explicit chooser available"); main(power::performClick); require(findText("选择操作")!=null,"unknown result opens existing explicit operation chooser"); fake.unknownRead=false;
            require(fake.hotspot == 1, "NFC toggle does not change hotspot");
            main(() -> { owner.dismissDetails(); owner.refreshStates(false); }); waitFor(() -> owner.on("data")!=null);
            for (String id:List.of("bluetooth","data","dnd","airplane","wifi")) {
                main(() -> owner.showDetails(id,null)); waitFor(() -> tag("detail-switch-"+id)!=null); GlassToggle control=(GlassToggle)tag("detail-switch-"+id); View detail=tag("detail-card"); SystemClock.sleep(300); idle(); int w=detail.getWidth(),h=detail.getHeight(); boolean wanted=!Boolean.TRUE.equals(owner.on(id));
                main(control::performClick); require(!control.isEnabled(),"common detail keeps pending switch disabled: "+id); require(tag("detail-card")==detail,"common switch does not dismiss its animation: "+id);
                waitFor(() -> control.isEnabled() && java.util.Objects.equals(owner.on(id),wanted)); require(tag("detail-switch-"+id)==control && detail.getWidth()==w && detail.getHeight()==h,"common readback reuses switch and stable layout: "+id); main(owner::dismissDetails);
            }
            main(() -> { owner.dismissDetails(); owner.showDetails("hotspot", null); }); waitFor(() -> findText("Changed Demo") != null);
            main(() -> tag("hotspot-config").performClick()); main(() -> ((EditText) tag("hotspot-name")).setText("Discard Demo")); main(() -> ((DetailSheet) tag("detail-sheet")).close());
            require(findText("放弃未保存的修改？") != null, "closing dirty editor asks inside current sheet");
            main(() -> findText("继续编辑").performClick()); main(() -> require(((EditText) tag("hotspot-name")).getText().toString().equals("Discard Demo"), "discard back retains draft"));
            int writes = fake.writes; fake.hold = new CountDownLatch(1);
            main(() -> { findText("保存").performClick(); findText("应用并重启").performClick(); }); waitFor(() -> fake.writes == writes + 1);
            main(() -> { field(ShizukuBridge.class, "remote").set(bridge, null); Method changed = ShizukuBridge.class.getDeclaredMethod("changed"); changed.setAccessible(true); changed.invoke(bridge); });
            waitFor(() -> tag("hotspot-config") == null && findText("状态未知 · 整机开关") != null);
            require(tag("hotspot-name") == null && tag("hotspot-password") == null && tag("hotspot-qr") == null, "disconnect clears credential views during pending submit");
            Object details = field(ControlDetails.class, "connectivity").get(field(CoverService.class, "detailContent").get(owner));
            require(field(ConnectivityDetails.class, "snapshot").get(details) == null && field(ConnectivityDetails.class, "editBase").get(details) == null && field(ConnectivityDetails.class, "pending").get(details) == null, "disconnect clears credential snapshot and retained draft");
            fake.hold.countDown(); waitFor(() -> !bridge.busy()); idle();
            require(tag("hotspot-config") == null, "late completion cannot restore disconnected credentials");
            main(() -> { field(ShizukuBridge.class, "remote").set(bridge, fake); Method changed = ShizukuBridge.class.getDeclaredMethod("changed"); changed.setAccessible(true); changed.invoke(bridge); });
            waitFor(() -> findText("Discard Demo") != null); require(tag("hotspot-config") != null, "reconnect performs a fresh read");
            main(() -> tag("hotspot-share").performClick()); android.graphics.Bitmap qr = (android.graphics.Bitmap) field(ConnectivityDetails.class, "qr").get(details);
            require(qr != null && !qr.isRecycled(), "QR is present before disconnect");
            main(() -> { field(ShizukuBridge.class, "remote").set(bridge, null); Method changed = ShizukuBridge.class.getDeclaredMethod("changed"); changed.setAccessible(true); changed.invoke(bridge); });
            waitFor(qr::isRecycled); require(tag("hotspot-qr") == null && field(ConnectivityDetails.class, "qr").get(details) == null, "disconnect releases displayed QR and reference");
            main(() -> { owner.dismissDetails(); owner.closePanel(); activity.setContentView(new FrameLayout(activity)); });
            waitFor(() -> fake.listener == null); require(fake.watchStops > 0, "last surface detaches remote watch");
            require(!new ShellService().execute("hotspot", 1, 1, "").isEmpty(), "unowned service is never callable");
            throw new AssertionError("caller guard should have thrown");
        } catch (SecurityException expected) {
            require(expected.getMessage().contains("owning application"), "caller identity enforced before system access");
            return "PASS: connectivity; " + assertions + " assertions; fake privileged replies only";
        } finally {
            if (fake != null && fake.hold != null) fake.hold.countDown();
            if (fake != null && fake.readHold != null) fake.readHold.countDown();
            if (fake != null && fake.queryHold != null) fake.queryHold.countDown();
            if (activity != null) main(() -> { if (owner != null) owner.closePanel(); activity.finish(); });
            field(ShizukuBridge.class, "remote").set(bridge, remote); CoverService.instance = prior;
            android.content.SharedPreferences.Editor edit = prefs.data.edit().clear(); for (var entry : previous.entrySet()) { Object value = entry.getValue(); if (value instanceof String s) edit.putString(entry.getKey(), s); else if (value instanceof Boolean b) edit.putBoolean(entry.getKey(), b); else if (value instanceof Integer i) edit.putInt(entry.getKey(), i); else if (value instanceof Long l) edit.putLong(entry.getKey(), l); else if (value instanceof Float f) edit.putFloat(entry.getKey(), f); } edit.commit();
            display.release(); surface.release(); texture.release();
        }
    }
    private void checkDelayedDetailSizes() throws Exception {
        for (String id:List.of("wifi","nfc","hotspot")) {
            fake.heldQuery=id+"_details"; fake.queryStarted=new CountDownLatch(1); fake.queryHold=new CountDownLatch(1);
            main(() -> owner.showDetails(id,tag("control-"+id))); waitFor(() -> fake.queryStarted.getCount()==0); idle();
            View card=tag("detail-card"); android.graphics.Rect initial=bounds(card); require(initial.width()>0 && initial.height()>0,"loading viewport is laid out: "+id);
            java.util.ArrayList<android.graphics.Rect> frames=new java.util.ArrayList<>();
            android.view.ViewTreeObserver.OnPreDrawListener listener=() -> { frames.add(bounds(card)); return true; };
            main(() -> card.getViewTreeObserver().addOnPreDrawListener(listener));
            try {
                fake.queryHold.countDown();
                String loaded=id.equals("wifi") ? "detail-switch-wifi" : id.equals("hotspot") ? "hotspot-config" : "connectivity-power";
                waitFor(() -> tag(loaded)!=null); idle();
                require(bounds(card).equals(initial),"first delayed result keeps the loading card's width, height and center: "+id);
                if (id.equals("wifi")) {
                    android.widget.ScrollView scroll=(android.widget.ScrollView)tag("detail-scroll");
                    require(scroll.canScrollVertically(1),"long Wi-Fi results scroll inside the stable viewport");
                    main(() -> scroll.fullScroll(View.FOCUS_DOWN)); idle(); android.graphics.Rect visible=new android.graphics.Rect();
                    require(findText("系统 Wi-Fi 选择").getGlobalVisibleRect(visible),"last Wi-Fi action remains reachable");
                    fake.wifiFailure=true; fake.queryHold=new CountDownLatch(1); fake.queryStarted=new CountDownLatch(1);
                    main(() -> description(activity.findViewById(android.R.id.content),"刷新网络").performClick()); waitFor(() -> fake.queryStarted.getCount()==0); idle();
                    require(bounds(card).equals(initial),"manual refresh does not collapse the existing Wi-Fi card");
                    fake.queryHold.countDown(); waitFor(() -> findText("模拟网络读取失败，请稍后刷新")!=null); idle(); require(bounds(card).equals(initial),"Wi-Fi error keeps the same viewport"); fake.wifiFailure=false;
                }
                if (id.equals("hotspot")) {
                    main(() -> tag("hotspot-clients").performClick()); idle(); require(bounds(card).equals(initial),"hotspot client page keeps its parent viewport");
                    fake.clientCount=12; fake.invalidate(); waitFor(() -> findText("当前连接 · 12 台")!=null); idle();
                    require(bounds(card).equals(initial) && ((android.widget.ScrollView)tag("detail-scroll")).canScrollVertically(1),"async client growth scrolls without enlarging the sheet"); fake.clientCount=1;
                }
                main(() -> { for (android.graphics.Rect frame:frames) require(frame.equals(initial),"no intermediate size jump during async content replacement: "+id); });
            } finally { main(() -> { card.getViewTreeObserver().removeOnPreDrawListener(listener); owner.dismissDetails(); }); if (fake.queryHold!=null) fake.queryHold.countDown(); fake.heldQuery=""; }
        }
    }
    private static android.graphics.Rect bounds(View view) { return new android.graphics.Rect(view.getLeft(),view.getTop(),view.getRight(),view.getBottom()); }
    private static View description(View view,String text) { if (text.contentEquals(view.getContentDescription()==null ? "" : view.getContentDescription())) return view; if (view instanceof ViewGroup group) for (int i=0;i<group.getChildCount();i++) { View found=description(group.getChildAt(i),text); if (found!=null) return found; } return null; }
    private static Field field(Class<?> type, String name) throws Exception { Field result = type.getDeclaredField(name); result.setAccessible(true); return result; }
    private View tag(String name) { return activity.findViewById(android.R.id.content).findViewWithTag(name); }
    private TextView findText(String text) { return findText(activity.findViewById(android.R.id.content), text); }
    private void clickRow(String text) { View target = findText(text); while (target != null && !target.isClickable() && target.getParent() instanceof View parent) target = parent; if (target == null || !target.isClickable()) throw new AssertionError("Missing row: " + text); target.performClick(); }
    private static TextView findText(View view, String text) { if (view instanceof TextView label && label.getText().toString().equals(text)) return label; if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = findText(group.getChildAt(i), text); if (found != null) return found; } return null; }
    private interface Work { void run() throws Exception; }
    private void main(Work work) { Throwable[] error = {null}; test.runOnMainSync(() -> { try { work.run(); } catch (Throwable failure) { error[0] = failure; } }); if (error[0] != null) throw new AssertionError(error[0]); test.waitForIdleSync(); }
    private void idle() { test.waitForIdleSync(); SystemClock.sleep(150); }
    private interface Check { boolean get() throws Exception; }
    private void waitFor(Check check) throws Exception { long end = SystemClock.uptimeMillis() + 5000; do { test.waitForIdleSync(); if (check.get()) return; SystemClock.sleep(60); } while (SystemClock.uptimeMillis() < end); throw new AssertionError("Timed out awaiting connectivity state"); }
    private void require(boolean value, String reason) { if (!value) throw new AssertionError(reason); assertions++; }
    private static final class FakeService extends IShellService.Stub {
        volatile int nfc = 1, hotspot = 1, writes, nfcWrites, watchStops, band = 1, common, clientCount=1;
        volatile long timeout = 600000;
        volatile boolean clientsKnown = true, unknownRead;
        volatile String name = "Native Demo";
        volatile JSONObject lastPatch;
        volatile CountDownLatch hold, readHold;
        volatile CountDownLatch queryHold,queryStarted;
        volatile String heldQuery="";
        volatile boolean wifiFailure;
        volatile IConnectivityListener listener;
        @Override public void watchConnectivity(IConnectivityListener next) { listener = next; if (next == null) watchStops++; else invalidate(); }
        void invalidate() { IConnectivityListener current = listener; if (current != null) try { current.onChanged(); } catch (Exception ignored) { } }
        @Override public String execute(String operation, int display, int value, String input) {
            try {
                if (operation.equals(heldQuery) && queryHold!=null) { queryStarted.countDown(); queryHold.await(4,TimeUnit.SECONDS); }
                JSONObject data = new JSONObject();
                switch (operation) {
                    case "wifi_details" -> {
                        if (wifiFailure) return new JSONObject().put("ok",false).put("message","模拟网络读取失败，请稍后刷新").put("output","").toString();
                        StringBuilder saved=new StringBuilder("Network Id  SSID\n"); for (int i=0;i<13;i++) saved.append(String.format(java.util.Locale.ROOT,"%12d %-32s \n",i,"Network "+i));
                        data.put("status","Wifi is connected to \"Network 0\"").put("saved",saved.toString()).put("nearby","");
                    }
                    case "states", "connectivity_states" -> data.put("nfc", nfc).put("hotspot", hotspot).put("wifi",common).put("bluetooth",common).put("data",common).put("dnd",common).put("airplane",common);
                    case "wifi", "bluetooth", "data", "dnd", "airplane" -> { common=value; invalidate(); }
                    case "nfc_details" -> { if (readHold!=null) readHold.await(4,TimeUnit.SECONDS); data.put("state", unknownRead ? -1 : nfc).put("supported", true).put("secureSupported", true).put("secure", 1).put("payment", "测试钱包"); }
                    case "hotspot_details" -> { JSONArray clients=new JSONArray(); for (int i=0;i<clientCount;i++) clients.put("测试设备 "+i); data.put("state", hotspot).put("configAvailable", true).put("name", name).put("password", "valid-demo-2026").put("security", 1).put("revision", "a".repeat(64)).put("band", band).put("bands", new JSONArray(List.of(1, 2))).put("timeoutAvailable", true).put("timeout", timeout).put("clientsAvailable", clientsKnown).put("clients", clients); }
                    case "nfc" -> { nfcWrites++; if (hold!=null) hold.await(4,TimeUnit.SECONDS); nfc = value == -1 ? 1 - nfc : value; invalidate(); }
                    case "hotspot" -> { hotspot = value == -1 ? 1 - hotspot : value; invalidate(); }
                    case "hotspot_config" -> { lastPatch = new JSONObject(input); writes++; if (hold != null) hold.await(4, TimeUnit.SECONDS); if (lastPatch.has("name")) name = lastPatch.getString("name"); if (lastPatch.has("band")) band = lastPatch.getInt("band"); if (lastPatch.has("timeout")) timeout = lastPatch.getLong("timeout"); invalidate(); }
                    default -> { }
                }
                return new JSONObject().put("ok", true).put("message", "模拟系统已确认").put("output", data.toString()).toString();
            } catch (Exception error) { return "{\"ok\":false,\"message\":\"fake failure\",\"output\":\"\"}"; }
        }
        @Override public Bundle taskSnapshot(int display, String task) { return new Bundle(); }
        @Override public void destroy() { }
    }
}
