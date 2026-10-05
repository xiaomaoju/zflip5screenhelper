package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

/** Exercises the production coordinator with actual application-panel windows on an emulator. */
final class InterfaceCardChecks {
    private final Instrumentation test;
    private Activity activity;
    private Prefs prefs;
    private CoverService owner;
    private WindowManager real;
    private final Set<View> roots = new HashSet<>();
    private int assertions;
    private boolean failNextHubUpdate;
    private int windowAdds, windowRemovals;
    private DockGeometry.Box frame;
    InterfaceCardChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void main(Runnable action) { test.runOnMainSync(action); test.waitForIdleSync(); }
    private Object field(String name) { try { Field f = CoverService.class.getDeclaredField(name); f.setAccessible(true); return f.get(owner); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
    private void field(String name, Object value) { try { Field f = CoverService.class.getDeclaredField(name); f.setAccessible(true); f.set(owner, value); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
    private void call(String name, Class<?> type, Object value) { try { Method m = CoverService.class.getDeclaredMethod(name, type); m.setAccessible(true); m.invoke(owner, value); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
    private void call(String name) { try { Method m = CoverService.class.getDeclaredMethod(name); m.setAccessible(true); m.invoke(owner); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
    private void call(String name, Class<?>[] types, Object... values) { try { Method m = CoverService.class.getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(owner, values); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
    private InterfaceCard active() { return (InterfaceCard) (field("panelCard") != null ? field("panelCard") : field("hubCard")); }
    private void freeze() { if (field("sceneBootstrap") instanceof ValueAnimator animation) animation.end(); if (field("panelAnimation") instanceof ValueAnimator animation) animation.pause(); }
    private void finish() { if (field("sceneBootstrap") instanceof ValueAnimator animation) animation.end(); if (field("panelAnimation") instanceof ValueAnimator animation) animation.end(); }
    private void progress(float p) { call(field("panelCard") != null ? "setPanelProgress" : "setHubSceneProgress", float.class, p); }
    private void separation(InterfaceCard source, InterfaceCard incoming, int edge) {
        if (source.getParent() == null) { require(source.getChildCount() == 0, "departed source releases its content"); return; }
        android.graphics.RectF oldBounds = new android.graphics.RectF(), nextBounds = new android.graphics.RectF(); source.visibleBounds(oldBounds); incoming.visibleBounds(nextBounds);
        int sourceY = ((WindowManager.LayoutParams) ((View) source.getParent()).getLayoutParams()).y, incomingY = ((WindowManager.LayoutParams) ((View) incoming.getParent()).getLayoutParams()).y;
        float boundary = incomingY - sourceY + incoming.getTranslationY() + (edge == DockGeometry.TOP ? nextBounds.bottom : nextBounds.top);
        float gap = edge == DockGeometry.TOP ? source.getTranslationY() + oldBounds.top - boundary : boundary - source.getTranslationY() - oldBounds.bottom;
        float required = Math.min(Ui.dp(activity, 6), Math.max(0, edge == DockGeometry.TOP ? boundary : frame.height() - boundary));
        require(gap >= required - .1f, "visible plate edges never overlap and retain the shared gap, edge=" + edge + ", gap=" + gap);
    }
    private void cancel() { call("cancelScenePush"); }
    private void clear() { if (owner != null) { owner.closePanel(); call("removeHubImmediately"); } for (View v : Set.copyOf(roots)) { real.removeViewImmediate(v); roots.remove(v); } }
    private WindowManager.LayoutParams adapted(Object params) {
        WindowManager.LayoutParams result = new WindowManager.LayoutParams(); result.copyFrom((WindowManager.LayoutParams) params);
        result.type = WindowManager.LayoutParams.TYPE_APPLICATION_PANEL; result.token = activity.getWindow().getDecorView().getWindowToken(); result.windowAnimations = 0; return result;
    }
    private void setup(int edge) {
        clear(); owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs;
        try { Method m = android.content.ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class); m.setAccessible(true); m.invoke(owner, activity); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        WindowManager proxy = (WindowManager) java.lang.reflect.Proxy.newProxyInstance(WindowManager.class.getClassLoader(), new Class<?>[]{WindowManager.class}, (p, m, args) -> {
            switch (m.getName()) {
                case "addView" -> { windowAdds++; View view = (View) args[0]; if (roots.add(view)) real.addView(view, adapted(args[1])); return null; }
                case "updateViewLayout" -> { if (failNextHubUpdate && args[0] == field("hubHost")) { failNextHubUpdate = false; throw new IllegalStateException("injected launcher window update failure"); } if (roots.contains(args[0])) real.updateViewLayout((View) args[0], adapted(args[1])); return null; }
                case "removeView", "removeViewImmediate" -> { windowRemovals++; View view = (View) args[0]; if (roots.remove(view)) real.removeViewImmediate(view); return null; }
                case "isCrossWindowBlurEnabled" -> { return false; }
                case "getDefaultDisplay" -> { return activity.getDisplay(); }
                default -> { return null; }
            }
        });
        int width = activity.getResources().getDisplayMetrics().widthPixels, height = activity.getResources().getDisplayMetrics().heightPixels;
        frame = new DockGeometry.Box(0, 0, width, height);
        DockGeometry.Box safe = new DockGeometry.Box(0, Ui.dp(activity, 40), width, height - Ui.dp(activity, 76));
        DockGeometry.Box dockBox = new DockGeometry.Box(0, height - Ui.dp(activity, 36), width, Ui.dp(activity, 36));
        owner.placement = new DockGeometry.Placement(dockBox, dockBox, safe, edge, true);
        field("windows", proxy); field("panelFrame", frame); field("hubFrame", safe); field("dockPlacement", owner.placement);
        field("panelEntryPlacement", new DockGeometry.Placement(dockBox, dockBox, safe, edge, true));
        field("controlStatusBox", new DockGeometry.Box(0, 0, width, Ui.dp(activity, 28)));
        field("dock", new DockView(activity, prefs, owner.placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }, false));
        call("ensurePanelHost"); call("ensureHubHosts");
    }
    private void checkDockWindows() {
        for (int edge : new int[]{DockGeometry.TOP, DockGeometry.BOTTOM}) {
            View[] chrome = new View[3]; int[] detachments = {0}; Set<View> mounted = new HashSet<>(); int[] counts = new int[2];
            main(() -> {
                setup(edge);
                DockView dock = (DockView) field("dock"); StatusBarView status = new StatusBarView(activity, prefs);
                PanelEntryView entry = new PanelEntryView(activity, prefs, (DockGeometry.Placement) field("panelEntryPlacement"), new DockView.Listener() { public void action(String id) { } public void configure() { } });
                field("statusBar", status); field("statusBox", field("controlStatusBox")); field("panelEntry", entry);
                WindowManager windows = (WindowManager) field("windows");
                windows.addView(dock, new WindowManager.LayoutParams(frame.width(), Ui.dp(activity, 36)));
                windows.addView(status, new WindowManager.LayoutParams(frame.width(), status.heightPixels()));
                windows.addView(entry, new WindowManager.LayoutParams(frame.width(), Ui.dp(activity, 36)));
                chrome[0] = dock; chrome[1] = status; chrome[2] = entry;
                for (View view : chrome) view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() { public void onViewAttachedToWindow(View v) { } public void onViewDetachedFromWindow(View v) { detachments[0]++; } });
                mounted.addAll(roots); counts[0] = windowAdds; counts[1] = windowRemovals;
            });
            for (int repetition = 0; repetition < 3; repetition++) {
                main(() -> { owner.act("app_dock"); freeze(); progress(.5f); require((int) field("hubSceneEdge") == DockGeometry.BOTTOM && active().getTranslationY() > 0, "Dock always enters upward from below, independently of the white-bar edge"); finish(); });
                main(() -> {
                    require(field("hub") != null && !((AppHubView) field("hub")).expanded(), "Dock opens inside an existing host");
                    for (View view : chrome) require(view.isAttachedToWindow(), "chrome stays mounted above Dock");
                    require(detachments[0] == 0 && roots.equals(mounted) && windowAdds == counts[0] && windowRemovals == counts[1], "Dock open never recreates a window surface");
                    owner.act("app_dock"); finish();
                });
                SystemClock.sleep(420); test.waitForIdleSync();
                main(() -> {
                    require(field("hub") == null && CoverApp.catalog(activity).observerCount() == 0, "Dock close releases content and subscriptions");
                    for (FrameLayout host : (FrameLayout[]) field("hubHosts")) require(host.isAttachedToWindow() && host.getChildCount() == 0 && host.getVisibility() == View.INVISIBLE && (((WindowManager.LayoutParams) host.getLayoutParams()).flags & (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)) == (WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE), "empty host stays hidden, unfocused and untouchable");
                    require(detachments[0] == 0 && roots.equals(mounted) && windowAdds == counts[0] && windowRemovals == counts[1], "Dock close preserves chrome and host surfaces");
                });
            }
            main(() -> { call("removeWindows"); require(roots.isEmpty() && detachments[0] == 3, "display teardown releases all persistent hosts and chrome"); });
        }
    }
    private void checkDefinitions() {
        require(InterfaceCard.CONTROLS.material() == InterfaceCard.Material.FROSTED && InterfaceCard.CONTROLS.showStatusBar(), "controls default to frosted with top status");
        for (InterfaceCard.Definition definition : new InterfaceCard.Definition[]{InterfaceCard.NOTIFICATIONS, InterfaceCard.TASKS, InterfaceCard.LAUNCHER}) require(definition.material() == InterfaceCard.Material.TRANSPARENT && !definition.showStatusBar(), "other three cards default transparent without status");
        for (InterfaceCard.Definition definition : new InterfaceCard.Definition[]{InterfaceCard.CONTROLS, InterfaceCard.NOTIFICATIONS, InterfaceCard.TASKS, InterfaceCard.LAUNCHER}) require(definition.useSafeArea() && !definition.useCardSafeArea(), "all existing pages retain large plates with safe content");
        FrameLayout body = new FrameLayout(activity); int[] cleanup = {0}, pause = {0};
        InterfaceCard card = new InterfaceCard(activity, prefs, new InterfaceCard.Definition("future-page", InterfaceCard.Material.TRANSPARENT, true), body, () -> pause[0]++, () -> cleanup[0]++);
        require(card.statusBar() != null && card.statusBar().showsPercentage(), "any registered card gets the shared forced-percentage row");
        card.statusBounds(new DockGeometry.Box(7, 12, 180, 28)); require(body.getPaddingTop() == 40 + Ui.dp(activity, 10), "container reserves status space once");
        View original = card.getChildAt(0); card.material(InterfaceCard.Material.FROSTED); require(card.getChildAt(0) == original && card.statusBar() != null, "one material parameter preserves content and status identity");
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(); card.windowMaterial(params, true, true); require((params.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0, "frosted parameter selects window fallback");
        card.material(InterfaceCard.Material.TRANSPARENT); card.windowMaterial(params, true, true); require((params.flags & (WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND)) == 0, "transparent parameter has no extra window blur or dim");
        card.layout(0, 0, 200, 300); card.enter(.5f, DockGeometry.BOTTOM, 300); card.beginExit(DockGeometry.TOP, 300);
        require(pause[0] == 1 && card.getClipBounds().bottom == 150, "interrupted entry freezes only its visible slice");
        require(!card.push(149, DockGeometry.TOP) && card.push(150, DockGeometry.TOP), "partially entered card releases at the actual offscreen boundary");
        card.restore(); require(card.getClipBounds() == null && card.getTranslationY() == 150, "unfinished replacement restores original pose and clipping");
        card.dropGlass(); require(card.getVisibility() == View.VISIBLE, "memory fallback cannot leave hidden card");
        card.material(InterfaceCard.Material.FROSTED); card.windowMaterial(params, true, true); require((params.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0, "memory fallback cannot restart expensive blur");
        card.release(); card.release(); require(cleanup[0] == 1 && card.getChildCount() == 0, "card releases once");
        DockGeometry.Box full = new DockGeometry.Box(0, 0, 200, 300), safe = new DockGeometry.Box(10, 20, 180, 240);
        InterfaceCard inset = new InterfaceCard(activity, prefs, new InterfaceCard.Definition("inset", InterfaceCard.Material.TRANSPARENT, false, true, true), new View(activity), () -> { }, () -> { }); inset.frameBounds(full, safe);
        FrameLayout.LayoutParams insetLayout = (FrameLayout.LayoutParams) inset.getLayoutParams();
        android.graphics.RectF plateBounds = new android.graphics.RectF(); inset.visualBounds(plateBounds);
        require(inset.definition().useSafeArea() && plateBounds.equals(new android.graphics.RectF(10, 20, 190, 260)), "card safe area true limits the visible plate bounds");
        require(insetLayout.width == 200 && insetLayout.height == 300 && insetLayout.topMargin == 0, "viewport and input coordinates remain fixed while cropping");
        inset.beginExit(DockGeometry.TOP, 300); require(!inset.push(279, DockGeometry.TOP) && inset.push(280, DockGeometry.TOP), "inset card exits at viewport boundary, not its own height");
        InterfaceCard fullscreen = new InterfaceCard(activity, prefs, new InterfaceCard.Definition("full", InterfaceCard.Material.TRANSPARENT, false, false, false), new View(activity), () -> { }, () -> { }); fullscreen.frameBounds(full, safe);
        FrameLayout.LayoutParams fullLayout = (FrameLayout.LayoutParams) fullscreen.getLayoutParams();
        require(fullLayout.width == 200 && fullLayout.height == 300 && fullLayout.leftMargin == 0 && fullLayout.topMargin == 0, "safe area false uses the large viewport bounds without changing status setting"); inset.release(); fullscreen.release();
        for (boolean contentSafe : new boolean[]{false, true}) for (boolean cardSafe : new boolean[]{false, true}) {
            View content = new View(activity); InterfaceCard example = new InterfaceCard(activity, prefs, new InterfaceCard.Definition("matrix", InterfaceCard.Material.TRANSPARENT, false, contentSafe, cardSafe), content, () -> { }, () -> { });
            example.frameBounds(full, safe); example.contentBounds(safe, full);
            FrameLayout.LayoutParams text = (FrameLayout.LayoutParams) content.getLayoutParams(); example.visualBounds(plateBounds);
            require(plateBounds.width() == (cardSafe ? 180 : 200) && plateBounds.height() == (cardSafe ? 240 : 300), "only card-safe flag decides visible outer bounds");
            require(text.width == (contentSafe || cardSafe ? 180 : 200) && text.height == (contentSafe || cardSafe ? 240 : 300), "content-safe is independent, constrained only by selected card bounds");
            require(text.topMargin == (cardSafe || contentSafe ? 20 : 0), "content reservation is not doubled"); example.release();
        }
    }
    @SuppressWarnings("unchecked")
    private java.util.Map<View, GlassSurface> surfaces(PanelGlassSession session) { try { Field f = PanelGlassSession.class.getDeclaredField("surfaces"); f.setAccessible(true); return (java.util.Map<View, GlassSurface>) f.get(session); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
    private void checkLauncherGlass() {
        PanelGlassSession[] session = {null}; InterfaceCard[] card = {null};
        main(() -> { setup(DockGeometry.BOTTOM); owner.act("app_hub"); finish(); card[0] = active(); });
        main(() -> {
            android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(frame.width(), frame.height(), android.graphics.Bitmap.Config.ARGB_8888);
            for (int y = 0; y < sample.getHeight(); y++) for (int x = 0; x < sample.getWidth(); x++) sample.setPixel(x, y, ((x / 6 + y / 6) % 2 == 0) ? 0xFF4A92CE : 0xFF14243A);
            session[0] = new PanelGlassSession(activity, activity.getDisplay()); session[0].fixture(sample); card[0].glass(session[0]); sample.recycle();
            java.util.Map<View, GlassSurface> surfaces = surfaces(session[0]);
            require(surfaces.get(card[0]).role == GlassSurface.Role.NOTIFICATION_PAGE, "outer launcher is its own transparent plate");
            for (String tag : new String[]{"hub-rail", "hub-catalog", "hub-dock"}) {
                View pane = card[0].findViewWithTag(tag); GlassSurface bound = surfaces.get(pane);
                require(pane != null && pane.isAttachedToWindow() && bound != null && bound.role.source == 2, tag + " suppresses captured app text using the existing strong texture");
                require(bound.role == (tag.equals("hub-dock") ? GlassSurface.Role.LAUNCHER_DOCK : GlassSurface.Role.LAUNCHER_PANEL), tag + " retains own rounded contour and refraction");
            }
            AppHubView hub = (AppHubView) field("hub"); View rail = card[0].findViewWithTag("hub-rail"); android.graphics.drawable.Drawable before = rail.getBackground();
            require(card[0].findViewWithTag("hub-tasks") == null && card[0].findViewWithTag("hub-close") == null, "both upper-right launcher buttons are removed");
            View edit = card[0].findViewWithTag("hub-edit"), tools = card[0].findViewWithTag("hub-tools"), sidebar = card[0].findViewWithTag("hub-sidebar");
            require(edit.getWidth() == rail.getWidth() && edit.getWidth() == edit.getHeight() && edit.getScaleX() == 1, "independent round setting button diameter matches the sidebar width");
            require(edit.getParent() == tools && tools.getParent() == rail.getParent() && tools.getTop() - rail.getBottom() == Ui.dp(activity, AppLauncherStyle.RAIL_GAP), "settings sit below the glass sidebar with one shared gap");
            require(sidebar.getWidth() == rail.getWidth() && tools.getLeft() == rail.getLeft(), "settings and sidebar share their left and right edges");
            require(rail.getPaddingTop() == Ui.dp(activity, AppLauncherStyle.RAIL_FRAME_PADDING) && rail.getPaddingTop() > 0, "sidebar leaves shared space inside its rounded frame");
            require(surfaces.get(card[0].findViewWithTag("hub-search-field")).role == GlassSurface.Role.LAUNCHER_SEARCH, "search and sorting share the launcher corner radius and input glass");
            require(!surfaces.containsKey(card[0].findViewWithTag("hub-apps")), "expanded Dock entrance keeps its transparent feedback instead of a white selected glass disc");
            DockGeometry.Box safeContent = (DockGeometry.Box) field("hubFrame"); require(hub.getTop() + card[0].getTop() == safeContent.y() && hub.getHeight() == safeContent.height(), "full plate preserves launcher content origin and height");
            owner.applyHubBlur(hub, new WindowManager.LayoutParams(), false); require(rail.getBackground() == before, "window state update cannot overwrite nested glass backgrounds");
            card[0].material(InterfaceCard.Material.FROSTED); require(surfaces(session[0]).get(card[0]).role == GlassSurface.Role.CONTROL_PAGE && surfaces(session[0]).get(rail).role == GlassSurface.Role.LAUNCHER_PANEL, "changing outer material keeps inner containers transparent");
            card[0].material(InterfaceCard.Material.TRANSPARENT); require(card[0].glass() == session[0], "all surfaces keep the same captured background");
        });
        SystemClock.sleep(160); test.waitForIdleSync(); android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
        require(image != null, "native launcher glass renders into an actual window screenshot");
        try { java.io.File dir = new java.io.File(test.getTargetContext().getFilesDir(), "ui-smoke"); dir.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "interface-card-launcher.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } } catch (java.io.IOException e) { throw new AssertionError(e); } finally { if (image != null) image.recycle(); }
        int[] sampleArea = new int[4];
        main(() -> {
            android.view.ViewGroup catalog = card[0].findViewWithTag("hub-catalog"); View rail = card[0].findViewWithTag("hub-rail");
            for (int i = 0; i < catalog.getChildCount(); i++) catalog.getChildAt(i).setVisibility(View.INVISIBLE);
            int[] at = new int[2], railAt = new int[2]; catalog.getLocationOnScreen(at); rail.getLocationOnScreen(railAt);
            sampleArea[0] = at[0] + catalog.getWidth() / 2; sampleArea[1] = railAt[0] + rail.getWidth() + (at[0] - railAt[0] - rail.getWidth()) / 2;
            sampleArea[2] = at[1] + Ui.dp(activity, 60); sampleArea[3] = at[1] + catalog.getHeight() - Ui.dp(activity, 30);
        });
        SystemClock.sleep(160); test.waitForIdleSync(); image = test.getUiAutomation().takeScreenshot();
        try {
            long inner = 0, outer = 0;
            for (int y = sampleArea[2]; y < sampleArea[3]; y++) {
                inner += Math.abs(android.graphics.Color.blue(image.getPixel(sampleArea[0], y)) - android.graphics.Color.blue(image.getPixel(sampleArea[0], y + 1)));
                outer += Math.abs(android.graphics.Color.blue(image.getPixel(sampleArea[1], y)) - android.graphics.Color.blue(image.getPixel(sampleArea[1], y + 1)));
            }
            require(outer > 1000 && inner < outer * .15f, "rendered inner glass suppresses high-frequency app detail while the outer transparent plate retains it: inner=" + inner + ", outer=" + outer + ", sample=" + java.util.Arrays.toString(sampleArea));
        } finally { image.recycle(); }
        main(() -> { clear(); require(!session[0].active(), "common release closes shared background and all subplates"); });
    }
    private void checkSidebarSplit() {
        java.util.List<String> apps = CoverApp.catalog(activity).entriesBlocking().stream().map(AppCatalogCache.Entry::id).limit(8).toList();
        require(apps.size() >= 8, "folder fixture has eight installed apps");
        AppWorkspaceLayout.Folder folder = new AppWorkspaceLayout.Folder("三星", apps.subList(0, 4), 1);
        PanelGlassSession[] session = {null}; LauncherSidebarView[] sidebar = {null}; int[] bounds = new int[4]; long[] bytes = {0};
        main(() -> {
            clear(); prefs.data.edit().putString("hub_sort", "manual").commit(); prefs.saveHubPins(java.util.List.of());
            prefs.saveWorkspace(new AppWorkspaceLayout(java.util.Map.of("folder:10000000-0000-0000-0000-000000000001", 0, "folder:10000000-0000-0000-0000-000000000002", 2), java.util.Map.of("folder:10000000-0000-0000-0000-000000000001", folder, "folder:10000000-0000-0000-0000-000000000002", new AppWorkspaceLayout.Folder("三星", apps.subList(4, 8), 2)), 5, 3), false);
            setup(DockGeometry.BOTTOM); owner.act("app_hub");
            require(((LauncherSidebarView) active().findViewWithTag("hub-sidebar")).separation() == 0, "sidebar stays fused during card entry before its fixed delay"); finish();
            android.content.ComponentName recent = ActionCatalog.component(apps.get(7));
            ((AppHubView) field("hub")).recentResult(java.util.List.of(new RecentTasks.Task(991, 1, 0, recent.flattenToString(), recent.getPackageName(), false)), null);
        });
        main(() -> {
            InterfaceCard card = active();
            android.content.ComponentName recent = ActionCatalog.component(apps.get(7));
            View dockRow = card.findViewWithTag("hub-dock");
            ((AppDockView) ((View) dockRow.getParent()).getParent()).data(java.util.List.of(new RecentTasks.Task(991, 1, 0, recent.flattenToString(), recent.getPackageName(), false)), true, false, true, true, false);
            android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(frame.width(), frame.height(), android.graphics.Bitmap.Config.ARGB_8888); sample.eraseColor(0xFF305F89);
            session[0] = new PanelGlassSession(activity, activity.getDisplay()); session[0].fixture(sample); card.glass(session[0]); sample.recycle();
            sidebar[0] = card.findViewWithTag("hub-sidebar"); sidebar[0].stopEntrance(); sidebar[0].separation(0); sidebar[0].reveal();
            require(sidebar[0].animating(), "ready launcher plays the sidebar split"); sidebar[0].stopEntrance();
            View rail = card.findViewWithTag("hub-rail"), setting = card.findViewWithTag("hub-edit"), search = card.findViewWithTag("hub-search-field");
            require(rail.getWidth() == Ui.dp(activity, 32) && Math.abs(setting.getWidth() - setting.getPaddingLeft() - setting.getPaddingRight() - Ui.dp(activity, 20)) <= 1, "narrow sidebar and matching settings glyph with symmetric pixel-rounded padding");
            require(search.getHeight() == Ui.dp(activity, 32) && AppLauncherStyle.panelRadius(activity) == 16, "compact search and matching smaller panel corner radius");
            View header = (View) search.getParent(), catalog = card.findViewWithTag("hub-catalog"), sort = card.findViewWithTag("hub-sort");
            require(Math.abs(search.getLeft() + search.getWidth() / 2f - header.getWidth() / 2f) <= 1 && search.getWidth() <= header.getWidth() * .63f && search.getRight() < sort.getLeft(), "shorter search is centered and sorting remains separate on the right");
            View side = card.findViewWithTag("hub-sidebar");
            require(Math.abs(side.getLeft() - (catalog.getLeft() - side.getRight())) <= 1, "left sidebar is centered between the safe screen edge and catalog");
            bounds[0] = rail.getWidth(); bounds[1] = rail.getHeight(); bounds[2] = setting.getTop(); bounds[3] = setting.getHeight();
            require(surfaces(session[0]).get(card.findViewWithTag("hub-dock")).role == GlassSurface.Role.LAUNCHER_DOCK, "Dock still has its glass container");
            require(card.findViewWithTag("hub-clear") != null && !surfaces(session[0]).containsKey(card.findViewWithTag("hub-clear")), "visible broom keeps transparent feedback without a separate glass button: view=" + card.findViewWithTag("hub-clear") + ", bound=" + surfaces(session[0]).get(card.findViewWithTag("hub-clear")));
            java.util.List<View> folderSurfaces = surfaces(session[0]).keySet().stream().filter(v -> "folder-surface".equals(v.getTag())).toList();
            require(folderSurfaces.size() == 2, "both real folder tiles bind to shared glass");
            for (View surface : folderSurfaces) {
                GlassSurface glass = surfaces(session[0]).get(surface); require(glass.role == GlassSurface.Role.FOLDER && surface.getWidth() == surface.getHeight(), "folder uses square liquid glass");
                AppFolderTile tile = (AppFolderTile) surface.getParent(); tile.bind(folder, ((AppHubView) field("hub"))::bindIcon, 0);
                require(surface.getBackground() == glass && tile.color() == AppFolderTile.COLORS[1], "folder refresh retains glass and updates its color");
            }
            bytes[0] = session[0].bytes();
        });
        checkSidebarDelay(sidebar[0]);
        for (float value : new float[]{0, .1f, .22f, .35f, .45f, .9f, 1}) {
            main(() -> {
                LauncherSidebarView side = sidebar[0]; side.stopEntrance(); side.separation(value);
                com.kyant.backdrop.catalog.components.LiquidTensionGeometry geometry = new com.kyant.backdrop.catalog.components.LiquidTensionGeometry(); side.geometry(geometry);
                require(geometry.getCount() == (value == 0 ? 1 : 2), "wrapped sidebar becomes two material lobes");
                if (value == 0) require(Math.abs(geometry.getShapes()[1] + geometry.getShapes()[3] - side.glassBody().getBottom()) < 1 && side.settingTranslationY() < 0, "initial settings glyph sits inside the sidebar's lower capsule");
                if (value == .22f) {
                    require(geometry.getJoins()[1] > 0, "growing settings bud retains a liquid neck");
                    require(geometry.getRadii()[1] == side.glassActions()[0].getWidth() / 2f, "settings keeps its full size as its joined circle moves downward");
                }
                if (value == .35f) require(geometry.getJoins()[1] > 0 && side.settingTranslationY() > -Ui.dp(activity, AppLauncherStyle.RAIL_WIDTH + AppLauncherStyle.RAIL_GAP), "moving glyph keeps a liquid neck until it leaves the sidebar");
                if (value == 1) require(geometry.getJoins()[1] == 0, "resting circle is fully disconnected");
                View rail = side.glassBody(), setting = side.glassActions()[0];
                require(setting.getScaleX() == 1 && side.settingScale() == 1, "pullout preserves glyph size and leaves its input view untransformed");
                float expected = -Ui.dp(activity, AppLauncherStyle.RAIL_WIDTH + AppLauncherStyle.RAIL_GAP) * (1 - value * value * (3 - 2 * value));
                require(Math.abs(side.settingTranslationY() - expected) <= 1, "settings follows a continuous downward trajectory to its original final position");
                require(rail.getWidth() == bounds[0] && rail.getHeight() == bounds[1] && setting.getTop() == bounds[2] && setting.getHeight() == bounds[3], "material animation preserves layout and hit targets");
                require(session[0].bytes() == bytes[0], "split does not allocate another background texture");
            });
            SystemClock.sleep(120); test.waitForIdleSync(); android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
            require(image != null, "sidebar split renders in a real hardware window");
            if (value == 0 || value == .22f || value == 1) checkSettingGlyphPixels(sidebar[0], value, image);
            try { java.io.File dir = new java.io.File(test.getTargetContext().getFilesDir(), "ui-smoke"); dir.mkdirs(); String name = value == 0 ? "sidebar-wrapped.png" : value == 1 ? "sidebar-separated.png" : value == .35f ? "sidebar-recoil.png" : value == .1f ? "sidebar-scale-small.png" : value == .45f ? "sidebar-scale-full.png" : "sidebar-neck.png"; try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, name))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } } catch (java.io.IOException e) { throw new AssertionError(e); } finally { image.recycle(); }
        }
        AppFolderTile[] held = {null}; View[] heldSurface = {null}; android.graphics.drawable.Drawable[] material = {null};
        AppWorkspaceLayout beforeDrag = prefs.workspace();
        main(() -> {
            AppWorkspaceView grid = active().findViewWithTag("hub-grid");
            grid.beginDrag("folder:10000000-0000-0000-0000-000000000001", grid.getWidth() / 5f, grid.gridHeight() / 3f);
            try { Field f = AppWorkspaceView.class.getDeclaredField("shadow"); f.setAccessible(true); held[0] = (AppFolderTile) f.get(grid); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            heldSurface[0] = held[0].findViewWithTag("folder-surface"); material[0] = heldSurface[0].getBackground();
            require(held[0].getParent() == grid && held[0].isAttachedToWindow() && material[0] instanceof GlassSurface, "lifted folder retains its attached glass surface");
            grid.dragTo(grid.getWidth() * .65f, grid.gridHeight() * .55f);
        });
        SystemClock.sleep(180); test.waitForIdleSync();
        main(() -> {
            require(heldSurface[0].getBackground() == material[0] && surfaces(session[0]).containsKey(heldSurface[0]), "moving folder keeps the same session material");
            require(held[0].getTranslationX() > 0 && held[0].getScaleX() == 1.1f && session[0].bytes() == bytes[0] && session[0].captures == 0, "drag updates the material transform without another texture or capture");
        });
        android.graphics.Bitmap dragging = test.getUiAutomation().takeScreenshot();
        try { java.io.File dir = new java.io.File(test.getTargetContext().getFilesDir(), "ui-smoke"); dir.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "folder-drag-glass.png"))) { dragging.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } } catch (java.io.IOException e) { throw new AssertionError(e); } finally { dragging.recycle(); }
        main(() -> {
            AppWorkspaceView grid = active().findViewWithTag("hub-grid"); grid.cancelInteraction();
            require(held[0].getParent() == null && !surfaces(session[0]).containsKey(heldSurface[0]) && prefs.workspace().equals(beforeDrag), "cancel releases the drag material and preserves the saved folder layout");
        });
        main(() -> { clear(); require(!sidebar[0].animating() && !session[0].active() && session[0].bytes() == 0, "closing releases split animation and shared texture"); });
    }
    private void checkSidebarDelay(LauncherSidebarView side) {
        java.util.concurrent.CountDownLatch fused = new java.util.concurrent.CountDownLatch(1); boolean[] before = {false};
        main(() -> { side.stopEntrance(); side.separation(0); side.reveal(); require(side.animating() && side.separation() == 0, "entry reserves a delayed split without waiting for animator completion"); side.reveal(); side.postDelayed(() -> { before[0] = side.separation() == 0 && side.settingTranslationY() < 0; side.stopEntrance(); fused.countDown(); }, 700); });
        try { require(fused.await(5, java.util.concurrent.TimeUnit.SECONDS) && before[0], "visible settings remains integrated during the fixed entry wait"); } catch (InterruptedException error) { throw new AssertionError(error); }
        SystemClock.sleep(400);
        java.util.concurrent.CountDownLatch split = new java.util.concurrent.CountDownLatch(1); boolean[] began = {false};
        main(() -> { require(!side.animating() && side.separation() == 0, "canceling fixed delay prevents a late split"); side.reveal(); side.postDelayed(() -> { try { Field field = LauncherSidebarView.class.getDeclaredField("animation"); field.setAccessible(true); ValueAnimator animation = (ValueAnimator) field.get(side); if (animation != null) { animation.pause(); animation.setCurrentPlayTime(150); began[0] = side.separation() > 0 && side.separation() < 1 && animation.getDuration() == 959; animation.setCurrentPlayTime(659); require(side.separation() == 1 && side.highlight() == 0, "sidebar highlight starts after pullout"); animation.setCurrentPlayTime(809); require(Math.abs(side.highlight() - .5f) < .001f, "sidebar highlight is halfway after 150ms"); animation.end(); require(side.highlight() == 1, "sidebar highlight completes after 300ms"); } } catch (ReflectiveOperationException error) { began[0] = false; } finally { split.countDown(); } }, 1150); });
        try { require(split.await(5, java.util.concurrent.TimeUnit.SECONDS) && began[0], "split begins after 900ms entry estimate plus 100ms wait"); } catch (InterruptedException error) { throw new AssertionError(error); }
        main(() -> require(!side.animating() && side.separation() == 1, "pullout and 300ms highlight complete and release the animator"));
    }
    private void checkSettingGlyphPixels(LauncherSidebarView side, float progress, android.graphics.Bitmap baseline) {
        android.widget.ImageButton setting = (android.widget.ImageButton) side.glassActions()[0];
        android.graphics.drawable.Drawable[] glyph = {null}; int[] at = new int[2];
        main(() -> { setting.getLocationOnScreen(at); at[1] += Math.round(side.settingTranslationY()); glyph[0] = setting.getDrawable(); setting.setImageDrawable(null); });
        SystemClock.sleep(100); test.waitForIdleSync(); android.graphics.Bitmap hidden = test.getUiAutomation().takeScreenshot();
        try {
            int changed = 0;
            for (int y = at[1]; y < at[1] + setting.getHeight(); y++) for (int x = at[0]; x < at[0] + setting.getWidth(); x++) if (baseline.getPixel(x, y) != hidden.getPixel(x, y)) changed++;
            require(changed > 20, "actual settings glyph pixels remain visible inside and outside the sidebar: progress=" + progress + ", changed=" + changed);
        } finally { hidden.recycle(); main(() -> setting.setImageDrawable(glyph[0])); }
    }
    private void touchHub(long down, int time, int action, float x, float y) {
        View host = (View) active().getParent(); int[] origin = new int[2]; host.getLocationOnScreen(origin);
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + time, action, x - origin[0], y - origin[1], 0);
        host.dispatchTouchEvent(event); event.recycle();
    }
    private void checkSidebarMask() {
        FrameLayout[] probe = {null}; LauncherSidebarView.Content[] scroll = {null}; int[] at = new int[2];
        main(() -> {
            probe[0] = new FrameLayout(activity); probe[0].setBackgroundColor(0xFF112233); probe[0].setForceDarkAllowed(false);
            scroll[0] = new LauncherSidebarView.Content(activity);
            View white = new View(activity); white.setBackgroundColor(0xFFFFFFFF); white.setMinimumHeight(Ui.dp(activity, 288)); scroll[0].addView(white, new android.widget.ScrollView.LayoutParams(-1, Ui.dp(activity, 288)));
            FrameLayout.LayoutParams size = new FrameLayout.LayoutParams(Ui.dp(activity, AppLauncherStyle.RAIL_WIDTH - 2 * AppLauncherStyle.RAIL_FRAME_PADDING), Ui.dp(activity, 96)); size.leftMargin = Ui.dp(activity, 20); size.topMargin = Ui.dp(activity, 40); probe[0].addView(scroll[0], size);
            ((FrameLayout) activity.findViewById(android.R.id.content)).addView(probe[0], new FrameLayout.LayoutParams(-1, -1));
        });
        try {
            SystemClock.sleep(150); test.waitForIdleSync(); main(() -> scroll[0].getLocationOnScreen(at));
            android.graphics.Bitmap initial = test.getUiAutomation().takeScreenshot();
            int width = scroll[0].getWidth(), height = scroll[0].getHeight();
            try {
                require(scroll[0].getChildAt(0).getHeight() > height, "white mask fixture supplies actual overflowing content");
                require(initial.getPixel(at[0] + 1, at[1] + 1) == 0xFF112233 && initial.getPixel(at[0] + 1, at[1] + height - 2) == 0xFF112233, "capsule mask clips both corners along the inset sidebar curve");
                int center = android.graphics.Color.red(initial.getPixel(at[0] + width / 2, at[1] + height / 2)), edge = android.graphics.Color.red(initial.getPixel(at[0] + width / 2, at[1] + 1));
                require(center == 255 && edge < center * .3f, "sidebar edge mask fades the contents instead of cutting a hard white boundary: center=" + center + ", edge=" + edge);
                main(() -> scroll[0].scrollTo(0, Ui.dp(activity, 48))); SystemClock.sleep(150); test.waitForIdleSync();
                android.graphics.Bitmap moved = test.getUiAutomation().takeScreenshot();
                try {
                    require(scroll[0].getScrollY() > 0, "mask fixture actually scrolls");
                    for (int y : new int[]{1, height / 2, height - 2}) require(initial.getPixel(at[0] + width / 2, at[1] + y) == moved.getPixel(at[0] + width / 2, at[1] + y), "capsule and fading edges remain fixed while the list moves");
                } finally { moved.recycle(); }
            } finally { initial.recycle(); }
        } finally { main(() -> ((FrameLayout) probe[0].getParent()).removeView(probe[0])); }
    }
    private void finishHubRebound(AppHubView hub) {
        try { Field f = AppHubView.class.getDeclaredField("revealAnimation"); f.setAccessible(true); if (f.get(hub) instanceof ValueAnimator animation) animation.end(); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    private void checkManualDismissal() {
        for (int edge : new int[]{DockGeometry.TOP, DockGeometry.BOTTOM}) {
            main(() -> { setup(edge); owner.act("app_hub"); finish(); });
            main(() -> {
                InterfaceCard card = active(); AppHubView hub = (AppHubView) field("hub"); View summary = hub.findViewWithTag("hub-summary"), dock = hub.findViewWithTag("hub-dock");
                int[] point = new int[2], dockPoint = new int[2]; summary.getLocationOnScreen(point); dock.getLocationOnScreen(dockPoint);
                float x = point[0] + summary.getWidth() / 2f, y = point[1] + summary.getHeight() / 2f, small = Ui.dp(activity, 20), large = Ui.dp(activity, 64);
                long down = SystemClock.uptimeMillis();
                touchHub(down, 0, android.view.MotionEvent.ACTION_DOWN, x, y); touchHub(down, 200, android.view.MotionEvent.ACTION_MOVE, x, y + small);
                require(Math.abs(card.getTranslationY() - small) < 1 && hub.getTranslationY() == 0, "manual pull moves the plate on the first MOVE without double-moving its content");
                int[] movedDock = new int[2]; dock.getLocationOnScreen(movedDock); require(Math.abs(movedDock[1] - dockPoint[1]) <= 1, "shared card motion preserves the independent first Dock stage");
                touchHub(down, 400, android.view.MotionEvent.ACTION_MOVE, x, y + small / 2);
                require(Math.abs(card.getTranslationY() - small / 2) < 1, "the plate reverses immediately with the finger");
                touchHub(down, 800, android.view.MotionEvent.ACTION_UP, x, y + small / 2); finishHubRebound(hub);
                require(active() == card && card.getTranslationY() == 0 && hub.getTranslationY() == 0, "short pull rebounds the same card and content together");
                touchHub(down, 1000, android.view.MotionEvent.ACTION_DOWN, x, y); touchHub(down, 1200, android.view.MotionEvent.ACTION_MOVE, x, y + large);
                touchHub(down, 1400, android.view.MotionEvent.ACTION_CANCEL, x, y + large); finishHubRebound(hub);
                require(active() == card && card.getTranslationY() == 0 && !hub.closing(), "system cancellation restores the whole card beyond the closing threshold");
                touchHub(down, 1600, android.view.MotionEvent.ACTION_DOWN, x, y); touchHub(down, 1800, android.view.MotionEvent.ACTION_MOVE, x, y + large);
                float released = card.getTranslationY(); touchHub(down, 2200, android.view.MotionEvent.ACTION_UP, x, y + large);
                ValueAnimator exit = (ValueAnimator) field("panelAnimation"); require(exit != null, "committed pull continues through the service card animator"); exit.pause(); exit.setCurrentPlayTime(0);
                require(Math.abs(card.getTranslationY() - released) < 1 && hub.getTranslationY() == 0 && (int) field("hubSceneEdge") == DockGeometry.BOTTOM, "release keeps the rendered pose and downward direction even after top entry");
                exit.setCurrentPlayTime(100); require(card.getTranslationY() > released, "settle continues outward from the finger pose"); exit.end();
                require(active() == null && !card.isAttachedToWindow(), "whole-card exit releases its content and window"); clear();
            });
        }
    }
    private void checkLauncherDismissalEdge() {
        AppHubView[] hub = {null}; PanelGlassSession[] session = {null};
        View[] catalog = {null}; LauncherSidebarView[] side = {null};
        float[] touch = new float[2]; int[] at = new int[2]; int[] height = new int[2];
        long down = SystemClock.uptimeMillis();
        main(() -> { setup(DockGeometry.BOTTOM); owner.act("app_hub"); finish(); });
        main(() -> {
            hub[0] = (AppHubView) field("hub"); catalog[0] = hub[0].findViewWithTag("hub-catalog"); side[0] = hub[0].findViewWithTag("hub-sidebar");
            android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(frame.width(), frame.height(), android.graphics.Bitmap.Config.ARGB_8888); sample.eraseColor(0xFF305F89);
            session[0] = new PanelGlassSession(activity, activity.getDisplay()); session[0].fixture(sample); active().glass(session[0]); sample.recycle();
            side[0].reveal(); side[0].stopEntrance(); side[0].separation(1);
            View summary = hub[0].findViewWithTag("hub-summary"); summary.getLocationOnScreen(at); touch[0] = at[0] + summary.getWidth() / 2f; touch[1] = at[1] + summary.getHeight() / 2f;
            height[0] = catalog[0].getHeight(); height[1] = side[0].getHeight();
            touchHub(down, 0, android.view.MotionEvent.ACTION_DOWN, touch[0], touch[1]); touchHub(down, 200, android.view.MotionEvent.ACTION_MOVE, touch[0], touch[1] + Ui.dp(activity, 20));
            require(side[0].settingScale() > 0 && side[0].settingScale() < 1, "partly occluded settings button shrinks as a whole circle");
            com.kyant.backdrop.catalog.components.LiquidTensionGeometry geometry = new com.kyant.backdrop.catalog.components.LiquidTensionGeometry(); side[0].geometry(geometry);
            require(geometry.getCount() == 2 && geometry.getShapes()[6] == geometry.getShapes()[7], "partly visible settings material remains circular");
        });
        for (int distance : new int[]{20, 64}) {
            main(() -> {
                touchHub(down, distance == 20 ? 400 : 600, android.view.MotionEvent.ACTION_MOVE, touch[0], touch[1] + Ui.dp(activity, distance));
                android.graphics.Outline outline = new android.graphics.Outline(); surfaces(session[0]).get(catalog[0]).getOutline(outline); android.graphics.Rect visible = new android.graphics.Rect(); require(outline.getRect(visible), "dragged material retains a rounded outline");
                require(Math.abs(visible.bottom - (height[0] - Ui.dp(activity, distance))) <= 1 && outline.getRadius() > 0, "glass bottom follows the visible viewport with its original corner radius");
                require(catalog[0].getHeight() == height[0] && side[0].getHeight() == height[1], "drag does not remeasure launcher content");
                if (distance == 64) require(side[0].settingScale() == 0, "settings artwork disappears when its circle is outside the viewport");
                catalog[0].getLocationOnScreen(at); at[1] += visible.bottom;
            });
            SystemClock.sleep(150); test.waitForIdleSync(); android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
            try {
                int corner = image.getPixel(at[0] + 1, at[1] - 2), outside = image.getPixel(at[0] - 3, at[1] - 2), inside = image.getPixel(at[0] + Ui.dp(activity, 20), at[1] - 5);
                require(Math.abs(android.graphics.Color.blue(corner) - android.graphics.Color.blue(outside)) < 8 && Math.abs(android.graphics.Color.blue(inside) - android.graphics.Color.blue(outside)) > 8, "hardware pixels retain the lower rounded glass corner during drag: " + corner + "," + outside + "," + inside);
                java.io.File dir = new java.io.File(test.getTargetContext().getFilesDir(), "ui-smoke"); dir.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "launcher-drag-edge-" + distance + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
            } catch (java.io.IOException error) { throw new AssertionError(error); } finally { image.recycle(); }
        }
        main(() -> {
            touchHub(down, 1000, android.view.MotionEvent.ACTION_CANCEL, touch[0], touch[1]); finishHubRebound(hub[0]);
            android.graphics.Outline outline = new android.graphics.Outline(); surfaces(session[0]).get(catalog[0]).getOutline(outline); android.graphics.Rect visible = new android.graphics.Rect(); outline.getRect(visible);
            require(visible.bottom == height[0] && side[0].settingScale() == 1 && active().getTranslationY() == 0, "cancel restores the complete material and settings circle: bottom=" + visible.bottom + ", height=" + height[0] + ", scale=" + side[0].settingScale() + ", translation=" + active().getTranslationY()); clear();
        });
    }
    private void checkUnsafeGradient() {
        InterfaceCard[] card = {null}; View[] red = {null}; PanelGlassSession[] session = {null};
        DockGeometry.Box[] safe = {null};
        main(() -> {
            setup(DockGeometry.BOTTOM); int margin = Ui.dp(activity, 40);
            safe[0] = new DockGeometry.Box(margin, margin, frame.width() - 2 * margin, frame.height() - 3 * margin);
            FrameLayout body = new FrameLayout(activity); red[0] = new View(activity); red[0].setBackgroundColor(0xFFFF0000); body.addView(red[0], new FrameLayout.LayoutParams(-1, -1));
            card[0] = new InterfaceCard(activity, prefs, new InterfaceCard.Definition("gradient", InterfaceCard.Material.TRANSPARENT, false, true, false), body, () -> { }, () -> { });
            card[0].frameBounds(frame, safe[0]); card[0].contentBounds(safe[0], frame);
            FrameLayout host = new FrameLayout(activity); host.addView(card[0]);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(frame.width(), frame.height(), WindowManager.LayoutParams.TYPE_APPLICATION_PANEL, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED, android.graphics.PixelFormat.TRANSLUCENT);
            params.token = activity.getWindow().getDecorView().getWindowToken(); params.gravity = android.view.Gravity.TOP | android.view.Gravity.LEFT; params.setFitInsetsTypes(0); real.addView(host, params); roots.add(host);
            android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(frame.width(), frame.height(), android.graphics.Bitmap.Config.ARGB_8888);
            for (int y = 0; y < sample.getHeight(); y++) for (int x = 0; x < sample.getWidth(); x++) sample.setPixel(x, y, (x / 6 % 2 == 0) ? 0xFF3050A0 : 0xFF0A1520);
            session[0] = new PanelGlassSession(activity, activity.getDisplay()); session[0].fixture(sample); card[0].glass(session[0]); sample.recycle();
        });
        SystemClock.sleep(180); test.waitForIdleSync(); android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
        try { int band = Ui.dp(activity, 16), x = frame.width() / 2; int solid = android.graphics.Color.red(image.getPixel(x, safe[0].bottom() - band - 4)), fade = android.graphics.Color.red(image.getPixel(x, safe[0].bottom() - 3)); require(solid > 220 && fade < solid * .6f, "content fades before its hard scroll clipping edge"); } finally { image.recycle(); }
        main(() -> red[0].setVisibility(View.GONE)); SystemClock.sleep(180); test.waitForIdleSync(); image = test.getUiAutomation().takeScreenshot();
        try {
            int top = safe[0].y() + Ui.dp(activity, 30), bottom = safe[0].bottom() + Ui.dp(activity, 30); long clear = 0, blurred = 0;
            for (int x = safe[0].x() + 30; x < safe[0].right() - 30; x++) { clear += Math.abs(android.graphics.Color.blue(image.getPixel(x, top)) - android.graphics.Color.blue(image.getPixel(x + 1, top))); blurred += Math.abs(android.graphics.Color.blue(image.getPixel(x, bottom)) - android.graphics.Color.blue(image.getPixel(x + 1, bottom))); }
            require(clear > 1000 && blurred < clear * .35f, "unsafe area gradually selects precomputed strong blur while safe center stays clear");
            java.io.File dir = new java.io.File(test.getTargetContext().getFilesDir(), "ui-smoke"); dir.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "interface-card-unsafe.png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); }
        } catch (java.io.IOException e) { throw new AssertionError(e); } finally { image.recycle(); }
        main(() -> { card[0].release(); clear(); require(!session[0].active(), "unsafe gradient borrows only one releasable background"); });
    }
    private void checkInitialBridge() {
        for (int edge : new int[]{DockGeometry.TOP, DockGeometry.BOTTOM}) for (String page : new String[]{"controls", "notifications"}) for (String from : new String[]{"app_hub", "recents", "controls", "notification_list"}) {
            if (from.equals(page) || from.equals("notification_list") && page.equals("notifications")) continue;
            InterfaceCard[] source = {null}; int[] contentHeight = {0};
            main(() -> { setup(edge); owner.act(from); finish(); });
            main(() -> {
                source[0] = active(); contentHeight[0] = source[0].getChildAt(0).getHeight();
                float origin = edge == DockGeometry.TOP ? 110 : frame.height() - 110;
                call("beginPanelPull", new Class<?>[]{String.class, float.class, float.class}, page, 0f, origin);
                call("pullPanel", new Class<?>[]{String.class, float.class}, page, 0f);
                require(source[0].getTranslationY() == 0 && Math.abs(active().getTranslationY()) == frame.height(), "fresh gesture starts beyond the viewport without applying entry inset as source travel");
                ValueAnimator initial = (ValueAnimator) field("sceneBootstrap"); require(initial != null && initial.getDuration() == 90, "shared short initial bridge is distinct from follow and settle"); initial.pause();
                for (int time = 0; time <= 45; time += 5) { initial.setCurrentPlayTime(time); separation(source[0], active(), edge); }
                android.graphics.RectF crop = new android.graphics.RectF(); source[0].visualBounds(crop);
                require(Math.abs(source[0].getTranslationY()) < 110 + Ui.dp(activity, 6) && crop.bottom > ((DockGeometry.Box) field("hubFrame")).bottom() && crop.bottom < frame.height(), "source crops and separates progressively from large to safe without teleporting");
                require(Math.abs(active().getTranslationY()) < frame.height() && Math.abs(active().getTranslationY()) > frame.height() - 110, "incoming crosses unsafe gap continuously during initial bridge");
                require(source[0].getChildAt(0).getHeight() == contentHeight[0], "initial crop never remeasures source content");
                for (int time = 50; time <= 90; time += 5) { initial.setCurrentPlayTime(time); separation(source[0], active(), edge); } initial.end();
                float resting = active().getTranslationY(), sourceResting = source[0].getTranslationY(); call("pullPanel", new Class<?>[]{String.class, float.class}, page, 60f);
                int direction = edge == DockGeometry.TOP ? 1 : -1;
                require(Math.abs(source[0].getTranslationY() - sourceResting - direction * 60) < 1 && Math.abs(active().getTranslationY() - resting - direction * 60) < 1, "after initial bridge both cards follow actual finger travel one to one"); separation(source[0], active(), edge);
                call("releasePanel", new Class<?>[]{float.class, float.class, boolean.class}, 60f, 0f, true);
                ValueAnimator reverse = (ValueAnimator) field("panelAnimation"); reverse.pause(); for (int time = 0; time < 340; time += 20) { reverse.setCurrentPlayTime(time); separation(source[0], active(), edge); } finish();
                source[0].visualBounds(crop); require(active() == source[0] && source[0].getTranslationY() == 0 && crop.bottom == frame.height(), "cancellation restores the same large source plate and pose"); clear();
            });
        }
    }
    private void checkDelayedReady() {
        InterfaceCard[] source = {null}, incoming = {null}; PanelGlassSession[] waiting = {null};
        main(() -> { setup(DockGeometry.BOTTOM); owner.act("app_hub"); finish(); });
        main(() -> {
            source[0] = active(); call("beginPanelPull", new Class<?>[]{String.class, float.class, float.class}, "notifications", 0f, (float) frame.height() - 110);
            call("cancelSceneBootstrap"); incoming[0] = active(); waiting[0] = new PanelGlassSession(activity, activity.getDisplay()); incoming[0].glass(waiting[0]); incoming[0].setVisibility(View.INVISIBLE);
            call("pullPanel", new Class<?>[]{String.class, float.class}, "notifications", 240f);
            require(source[0].getTranslationY() == 0 && Math.abs(incoming[0].getTranslationY()) == frame.height(), "pending material retains source pose while accumulating finger travel");
            android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(frame.width(), frame.height(), android.graphics.Bitmap.Config.ARGB_8888); sample.eraseColor(0xFF143050); waiting[0].fixture(sample); sample.recycle(); incoming[0].setVisibility(View.VISIBLE);
            call("startSceneBootstrap", InterfaceCard.class, incoming[0]); ValueAnimator initial = (ValueAnimator) field("sceneBootstrap"); initial.pause();
            require(source[0].getTranslationY() == 0 && Math.abs(incoming[0].getTranslationY()) == frame.height(), "late readiness starts from the rendered origin instead of jumping to queued progress");
            initial.setCurrentPlayTime(45); require(source[0].getTranslationY() < 0 && source[0].getTranslationY() > -240, "late source position catches up continuously during short interpolation"); initial.end();
            separation(source[0], incoming[0], DockGeometry.BOTTOM); require(source[0].getTranslationY() <= -240, "bridge reaches accumulated finger position and separates plate edges");
            float incomingY = incoming[0].getTranslationY(), sourceY = source[0].getTranslationY();
            call("beginPanelPull", new Class<?>[]{String.class, float.class, float.class}, "notifications", 240f, (float) frame.height() - 110);
            call("pullPanel", new Class<?>[]{String.class, float.class}, "notifications", 260f);
            require(Math.abs(source[0].getTranslationY() - sourceY + 20) < 1 && Math.abs(incoming[0].getTranslationY() - incomingY + 20) < 1, "continuing the same page preserves entry offset and both source and incoming poses"); separation(source[0], incoming[0], DockGeometry.BOTTOM);
            call("releasePanel", new Class<?>[]{float.class, float.class, boolean.class}, 260f, 0f, true); finish(); require(active() == source[0] && waiting[0].bytes() == 0, "cancel closes delayed background and restores original source"); clear();
        });
    }
    private void checkTaskDirections() {
        for (int edge : new int[]{DockGeometry.BOTTOM, DockGeometry.LEFT, DockGeometry.TOP, DockGeometry.RIGHT}) {
            main(() -> { setup(edge); owner.act("controls"); finish(); });
            InterfaceCard[] old = {null};
            main(() -> { old[0] = active(); owner.act("recents"); freeze(); progress(0); InterfaceCard card = active(); require(Math.abs(card.getTranslationY() - sceneExtent()) < 1, "tasks always enter from bottom for shortcut edge " + edge); progress(.4f); require(old[0].getTranslationY() < 0, "outgoing plate moves in common incoming direction " + edge); finish(); });
            AppHubView hub = (AppHubView) field("hub"); RecentTasksView page = hub.taskPage();
            long now = SystemClock.uptimeMillis(); float x = frame.width() / 2f, y = Ui.dp(activity, 50), travel = Ui.dp(activity, 42);
            main(() -> { android.view.MotionEvent e = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, x, y, 0); page.dispatchTouchEvent(e); e.recycle(); });
            main(() -> { android.view.MotionEvent e = android.view.MotionEvent.obtain(now, now + 40, android.view.MotionEvent.ACTION_MOVE, x, y + travel, 0); page.dispatchTouchEvent(e); e.recycle(); require(Math.abs(active().getTranslationY() - travel) < 1 && active().getTranslationX() == 0, "blank drag moves full plate along shortcut edge " + edge); require(page.getTranslationX() == 0 && page.getTranslationY() == 0 && hub.getTranslationX() == 0 && hub.getTranslationY() == 0, "task content never separates from backplate " + edge); });
            main(() -> { android.view.MotionEvent e = android.view.MotionEvent.obtain(now, now + 60, android.view.MotionEvent.ACTION_CANCEL, x, y + travel, 0); page.dispatchTouchEvent(e); e.recycle(); });
            SystemClock.sleep(600); test.waitForIdleSync();
            main(() -> { require(active() != null && active().getTranslationX() == 0 && active().getTranslationY() == 0, "cancelled task dismissal returns whole plate " + edge); owner.act("recents"); if (field("panelAnimation") instanceof ValueAnimator animation) { animation.pause(); animation.setCurrentPlayTime(170); } require(active() != null && active().getTranslationY() > 0 && active().getTranslationX() == 0, "task exit returns toward shortcut edge " + edge); finish(); require(active() == null, "directional task exit releases host " + edge); clear(); });
        }
    }
    private float sceneExtent() { try { Method m = CoverService.class.getDeclaredMethod("hubSceneExtent"); m.setAccessible(true); return (float) m.invoke(owner); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
    String runFullscreenTasks() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync(); real = activity.getWindowManager();
        PanelGlassSession[] session = {null}; int[] outsideClicks = {0};
        try {
            main(() -> {
                prefs = new Prefs(activity); prefs.data.edit().clear().putBoolean("haptics", false).putBoolean("panel_blur", true).putBoolean("panel_media", false).commit();
                FrameLayout background = new FrameLayout(activity); background.setBackgroundColor(0xFF254861); background.setOnClickListener(v -> outsideClicks[0]++); activity.setContentView(background);
                setup(DockGeometry.BOTTOM); owner.act("recents"); finish();
                android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(frame.width(), frame.height(), android.graphics.Bitmap.Config.ARGB_8888); sample.eraseColor(0xFF315F76);
                session[0] = new PanelGlassSession(activity, activity.getDisplay()); session[0].fixture(sample); active().glass(session[0]); sample.recycle();
                field("taskGlass", session[0]);
            });
            java.util.List<AppCatalogCache.Entry> apps = CoverApp.catalog(activity).entriesBlocking();
            main(() -> {
                AppHubView hub = (AppHubView) field("hub"); java.util.List<RecentTasks.Task> tasks = new java.util.ArrayList<>();
                for (int i = 0; i < 6; i++) { AppCatalogCache.Entry app = apps.get(i % apps.size()); tasks.add(new RecentTasks.Task(200 + i, 2, 0, app.id().substring(4), app.packageName(), false)); }
                hub.recentCapabilities(true, true, false); hub.recentResult(tasks, null);
                try {
                    Field f = RecentTasksView.class.getDeclaredField("previews"); f.setAccessible(true);
                    @SuppressWarnings("unchecked") java.util.Map<String, ShizukuBridge.Snapshot> images = (java.util.Map<String, ShizukuBridge.Snapshot>) f.get(hub.taskPage());
                    for (int i = 0; i < tasks.size(); i++) {
                        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(180, 280, android.graphics.Bitmap.Config.ARGB_8888); android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap); canvas.drawColor(new int[]{0xFF315F76, 0xFF51684B, 0xFF655079}[i % 3]);
                        android.graphics.Paint paint = new android.graphics.Paint(3); paint.setColor(0xFFF3F7FA); paint.setTextSize(18); canvas.drawText("任务 " + (i + 1), 16, 38, paint); paint.setColor(0xFFB8CDD8); paint.setTextSize(12); canvas.drawText("模拟任务预览", 16, 62, paint);
                        paint.setColor(0x556ED5DD); for (int row = 0; row < 4; row++) canvas.drawRoundRect(16, 85 + row * 38, 164, 112 + row * 38, 8, 8, paint);
                        images.put(RecentTasks.key(tasks.get(i)), new ShizukuBridge.Snapshot(bitmap, "fixture"));
                    }
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                hub.taskPage().catalogChanged(); hub.taskPage().select(tasks.get(2));
            });

            SystemClock.sleep(700); test.waitForIdleSync();
            main(() -> {
                View host = (View) field("hubHost"); WindowManager.LayoutParams params = (WindowManager.LayoutParams) host.getLayoutParams();
                require(params.height == frame.height() && params.width == frame.width() && params.y == 0, "restored task window covers the whole display");
                require((params.flags & (WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_BLUR_BEHIND)) == 0, "shared card owns material without duplicate window dim/blur");
                android.view.ViewGroup carousel = active().findViewWithTag("task-carousel"); require(carousel.getChildCount() <= 4 && carousel.getChildCount() >= 2, "full-screen layout retains bounded task pool");
                TaskPreviewView picture = (TaskPreviewView) ((android.view.ViewGroup) carousel.getChildAt(0)).getChildAt(1);
                require(picture.refracting(), "task snapshot liquid lens is active");
                View clear = active().findViewWithTag("tasks-clear"); int[] at = new int[2]; ((View) clear.getParent()).getLocationOnScreen(at);
                require(Math.abs(at[0] + ((View) clear.getParent()).getWidth() / 2f - frame.width() / 2f) < 2, "clear slot is centered below cards: " + at[0]);
                require(clear.getBackground() instanceof android.graphics.drawable.RippleDrawable, "clear action retains bound glass");
                require(active().glass() == session[0] && session[0].ready(), "full-screen plate uses the original shared glass session");
            });
            android.graphics.Bitmap shot = test.getUiAutomation().takeScreenshot();
            try { java.io.File dir = new java.io.File(test.getTargetContext().getFilesDir(), "ui-smoke"); dir.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(dir, "task-fullscreen.png"))) { shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } } finally { shot.recycle(); }
            main(() -> {
                RecentTasks.Task base = ((AppHubView) field("hub")).selectedTask();
                RecentTasks.Task background = new RecentTasks.Task(base.id(), session[0].displayId, base.userId(), base.component(), base.packageName(), false);
                call("invalidateClosingTaskBackground", new Class<?>[]{java.util.List.class}, java.util.List.of(background)); require(session[0].closingBackdropValid(), "closing background task retains valid foreground source");
                RecentTasks.Task foreground = new RecentTasks.Task(base.id(), session[0].displayId, base.userId(), base.component(), base.packageName(), true);
                call("invalidateClosingTaskBackground", new Class<?>[]{java.util.List.class}, java.util.List.of(foreground)); require(!session[0].closingBackdropValid(), "closing foreground retires screenshot before backend confirmation");
            }); SystemClock.sleep(180); test.waitForIdleSync();
            main(() -> { require(session[0].bytes() == 0 && session[0].taskOptics(), "source loss retires background without disabling task lens"); require(active().isAttachedToWindow(), "source loss does not replace the page"); RecentTasksView page = ((AppHubView) field("hub")).taskPage(); ((GlassSurface) page.getBackground()).refresh(); android.view.ViewGroup strip = page.findViewWithTag("task-carousel"); require(((TaskPreviewView) ((android.view.ViewGroup) strip.getChildAt(0)).getChildAt(1)).refracting(), "material refresh after source loss preserves snapshot lens"); });
            main(() -> {
                RecentTasksView page = ((AppHubView) field("hub")).taskPage(); View carousel = page.findViewWithTag("task-carousel"); float x = carousel.getTranslationX();
                android.graphics.Rect source = new android.graphics.Rect(0, 0, frame.width(), frame.height()); session[0].sourceWindow(71, source, "previous app");
                require(session[0].observedSourceChanged(72, source, "SubLauncherWindow"), "source discovery still works after proactive foreground retirement");
                android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(frame.width(), frame.height(), android.graphics.Bitmap.Config.ARGB_8888); sample.eraseColor(0xFF654278);
                PanelGlassSession next = new PanelGlassSession(activity, activity.getDisplay()); next.fixture(sample); next.sourceWindow(72, source, "SubLauncherWindow"); sample.recycle();
                android.graphics.Rect appArea = new android.graphics.Rect(0, Ui.dp(activity, 30), frame.width(), frame.height());
                require(!next.canCaptureWindow(appArea), "default capture still requires complete page coverage");
                next.captureArea(new android.graphics.Rect(0, Ui.dp(activity, 40), frame.width(), frame.height() - Ui.dp(activity, 36)));
                require(next.canCaptureWindow(appArea), "task background accepts app window excluding the system status strip when it covers all task content");
                require(!next.canCaptureWindow(new android.graphics.Rect(20, Ui.dp(activity, 30), frame.width() - 20, frame.height())), "floating window cannot substitute a cropped source for task content");
                session[0].replaceSource(next);
                require(session[0].ready() && session[0].bytes() > 0 && !next.active() && next.bytes() == 0, "new background texture transfers ownership and releases donor session");
                require(active().glass() == session[0] && page.findViewWithTag("task-carousel") == carousel && carousel.getTranslationX() == x, "whole-page glass update retains session, carousel, geometry and position");
                require(!session[0].observedSourceChanged(72, source, "SubLauncherWindow") && session[0].closingBackdropValid(), "unchanged replacement is not scheduled again and is valid for display");
                require(active().getBackground() instanceof GlassSurface && page.backgroundStable(), "full-page liquid surface is rebound to replacement texture only at rest");
            });
            SystemClock.sleep(100); test.waitForIdleSync();
            android.graphics.Bitmap refreshed = test.getUiAutomation().takeScreenshot();
            try { int pixel = refreshed.getPixel(frame.width() / 2, Ui.dp(activity, 100)); require(android.graphics.Color.red(pixel) > android.graphics.Color.green(pixel) && android.graphics.Color.blue(pixel) > android.graphics.Color.green(pixel), "hardware full-page pixels show purple replacement instead of former blue backdrop"); } finally { refreshed.recycle(); }
            main(() -> {
                RecentTasksView page = ((AppHubView) field("hub")).taskPage(); page.exit(() -> { });
                try { Field f = RecentTasksView.class.getDeclaredField("exitAnimation"); f.setAccessible(true); ValueAnimator animation = (ValueAnimator) f.get(page); animation.pause(); animation.setCurrentPlayTime(100); } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
                require(Math.abs(active().getAlpha() - .5f) < .01f, "retired-source exit fades actual shared backplate instead of drawing a second screenshot"); page.cancelExit(); require(active().getAlpha() == 1, "cancel restores shared plate alpha");
            });
            long now = SystemClock.uptimeMillis();
            for (int action : new int[]{android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP}) { android.view.MotionEvent event = android.view.MotionEvent.obtain(now, SystemClock.uptimeMillis(), action, frame.width() / 2f, Ui.dp(activity, 50), 0); test.getUiAutomation().injectInputEvent(event, true); event.recycle(); }
            test.waitForIdleSync();
            main(() -> { require(outsideClicks[0] == 0, "blank tap is consumed rather than delivered to underlying application"); require(field("panelAnimation") != null, "blank tap starts nonlinear page dismissal"); finish(); require(active() == null && session[0].bytes() == 0, "dismissal releases task host and background"); });
            checkTaskDirections();
            return "PASS: " + assertions + " restored-fullscreen assertions; full window, three-across pool, liquid snapshot optics, shared plate, centered X, source loss, blank dismissal and directional gestures; emulator only";
        } finally { main(() -> { clear(); activity.finish(); }); }
    }
    private void touch(PanelSurface surface, long down, long elapsed, int action, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(down, down + elapsed, action, x, y, 0); surface.dispatchTouchEvent(event); event.recycle();
    }
    private android.graphics.Rect bounds(PanelSurface surface, View view) {
        android.graphics.Rect result = new android.graphics.Rect(); view.getDrawingRect(result); surface.offsetDescendantRectToMyCoords(view, result); return result;
    }
    String runHeaderSplit() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync(); real = activity.getWindowManager();
        try {
            main(() -> { prefs = new Prefs(activity); prefs.data.edit().clear().putBoolean("haptics", false).putBoolean("panel_blur", false).putBoolean("panel_media", false).commit(); activity.setContentView(new FrameLayout(activity)); });
            for (String page : new String[]{"controls", "notifications"}) {
                PanelActionHeader[] header = {null}; PanelGlassSession[] session = {null}; android.graphics.Rect[] original = {null}; ValueAnimator[] entrance = {null};
                main(() -> { setup(DockGeometry.TOP); owner.act(page); freeze(); progress(.75f); });
                main(() -> {
                    header[0] = active().findViewWithTag("panel-header");
                    View last = header[0].getChildAt(header[0].getChildCount() - 1); original[0] = new android.graphics.Rect(last.getLeft(), last.getTop(), last.getRight(), last.getBottom());
                    android.graphics.Bitmap sample = android.graphics.Bitmap.createBitmap(frame.width(), frame.height(), android.graphics.Bitmap.Config.ARGB_8888); sample.eraseColor(0xFF305F89);
                    session[0] = new PanelGlassSession(activity, activity.getDisplay()); session[0].fixture(sample); active().glass(session[0]); sample.recycle();
                });
                main(() -> {
                    View last = header[0].getChildAt(header[0].getChildCount() - 1); original[0] = new android.graphics.Rect(last.getLeft(), last.getTop(), last.getRight(), last.getBottom());
                    require(header[0].animating() && header[0].separation() == 0, page + " holds merged buttons until the card reaches the end; progress=" + header[0].separation());
                    require(header[0].actionTranslationX(1) > 0, page + " starts the left action inside its right neighbor");
                });
                SystemClock.sleep(1100); test.waitForIdleSync();
                main(() -> {
                    try {
                        Field f = PanelActionHeader.class.getDeclaredField("animation"); f.setAccessible(true);
                        require(header[0].separation() == 0 && f.get(header[0]) == null, page + " no timer starts the split while the card is still entering");
                        progress(.99f); require(f.get(header[0]) == null, page + " split does not start before the actual endpoint");
                        progress(1); ValueAnimator clock = (ValueAnimator) f.get(header[0]); require(clock != null, page + " endpoint starts the split synchronously without a delayed callback"); clock.pause();
                        require(clock.getDuration() == 507L * (header[0].getChildCount() - 2) + 300, page + " each split is 507ms, followed by a 300ms material lerp");
                        progress(1); require(f.get(header[0]) == clock, page + " repeated endpoint updates do not restart the animation");
                        entrance[0] = clock;
                    } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                });
                for (int elapsed : new int[]{0, 150, 299, 300}) {
                    main(() -> {
                        entrance[0].setCurrentPlayTime(507L * (header[0].getChildCount() - 2) + elapsed);
                        require(header[0].separation() == 1, page + " material fade keeps already settled action geometry");
                        require(Math.abs(header[0].appearance() - (elapsed == 0 ? 0 : elapsed == 150 ? .5f : elapsed == 300 ? 1 : .99996674f)) < .001f, page + " highlight lerps continuously after separation, t=" + elapsed);
                    });
                    SystemClock.sleep(70); test.waitForIdleSync();
                    android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot(); require(image != null, page + " renders material fade into a hardware window");
                    try { java.io.File directory = new java.io.File(activity.getExternalFilesDir(null), "header-split"); directory.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, page + "-highlight-" + elapsed + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } } catch (java.io.IOException e) { throw new AssertionError(e); } finally { image.recycle(); }
                }
                main(() -> header[0].stopSplit());
                float previous = Float.MAX_VALUE;
                for (float position : new float[]{0, .15f, .33f, .5f, .65f, .83f, 1}) {
                    float[] translation = {0};
                    main(() -> {
                        header[0].separation(position); translation[0] = header[0].actionTranslationX(1);
                        com.kyant.backdrop.catalog.components.LiquidTensionGeometry geometry = new com.kyant.backdrop.catalog.components.LiquidTensionGeometry(); header[0].geometry(geometry);
                        require(geometry.getCount() >= 1 && geometry.getCount() <= header[0].getChildCount() - 1, page + " keeps one bounded material group");
                        require(position > 0 || geometry.getCount() == 1, page + " starts with only the rightmost body");
                        require(position < 1 || geometry.getCount() == header[0].getChildCount() - 1, page + " restores all independent surfaces");
                        for (int i = 1; i < geometry.getCount(); i++) require(geometry.getShapes()[i * 4] <= geometry.getShapes()[(i - 1) * 4], page + " surfaces run from right to left");
                        View last = header[0].getChildAt(header[0].getChildCount() - 1); require(original[0].equals(new android.graphics.Rect(last.getLeft(), last.getTop(), last.getRight(), last.getBottom())), page + " never moves or resizes input slots");
                        if (page.equals("notifications") && position <= .5f) require(header[0].actionTranslationX(2) > 0, "second split waits for the first to finish");
                        require(page.equals("notifications") ? Math.abs(header[0].actionScale(1) - (.62f + .38f * (Math.min(1, position * 2) * Math.min(1, position * 2) * (3 - 2 * Math.min(1, position * 2))))) < .001f : header[0].actionScale(1) == 1, page + " notification circles grow while control geometry retains its scale");
                    });
                    require(translation[0] <= previous, page + " action artwork travels continuously left"); previous = translation[0];
                    SystemClock.sleep(70); test.waitForIdleSync();
                    android.graphics.Bitmap image = test.getUiAutomation().takeScreenshot();
                    require(image != null, page + " renders split into a hardware window");
                    try { java.io.File directory = new java.io.File(activity.getExternalFilesDir(null), "header-split"); directory.mkdirs(); try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(directory, page + "-" + Math.round(position * 100) + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out); } } catch (java.io.IOException e) { throw new AssertionError(e); } finally { image.recycle(); }
                }
                main(() -> {
                    require(header[0].actionTranslationX(1) == 0 && !header[0].animating(), page + " ends with no clock and the original action position");
                    View face = header[0].glassBody(); require(!header[0].drawsSharedGlass(face), page + " restores existing per-button material and feedback at rest");
                    header[0].separation(.25f); long now = SystemClock.uptimeMillis(); android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN, 2, 2, 0); header[0].dispatchTouchEvent(event); event.recycle();
                    event = android.view.MotionEvent.obtain(now, now + 1, android.view.MotionEvent.ACTION_CANCEL, 2, 2, 0); header[0].dispatchTouchEvent(event); event.recycle();
                    require(header[0].separation() == 1 && !header[0].animating(), page + " interaction settles entrance before original input handling");
                    header[0].separation(.25f); header[0].layout(0, 0, header[0].getWidth() - 1, header[0].getHeight()); require(header[0].separation() == 1 && !header[0].animating(), page + " size change cancels split");
                    header[0].separation(.25f); header[0].setVisibility(View.GONE); require(header[0].separation() == 1 && !header[0].animating(), page + " hiding cancels split");
                    header[0].setVisibility(View.VISIBLE);
                });
                main(() -> {
                    require(header[0].animating(), page + " showing starts a fresh bounded entrance after layout");
                    active().release(); require(!header[0].animating() && !header[0].isAttachedToWindow(), page + " release cancels the entrance animator"); clear();
                });
            }
            return "PASS: " + assertions + " header split assertions; right-to-left sequential liquid split, hardware images, original slots, input, resize, hide and release; emulator only";
        } finally { main(() -> { clear(); activity.finish(); }); }
    }
    String runPanelActions() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync(); real = activity.getWindowManager();
        try {
            main(() -> { prefs = new Prefs(activity); prefs.data.edit().clear().putBoolean("haptics", false).putBoolean("panel_blur", false).putBoolean("panel_media", false).commit(); activity.setContentView(new FrameLayout(activity)); });
            for (int edge : new int[]{DockGeometry.TOP, DockGeometry.BOTTOM}) {
                for (String action : new String[]{"notifications", "notification_list", "controls"}) {
                    String page = action.equals("controls") ? "controls" : "notifications";
                    main(() -> { setup(edge); owner.act(action); finish(); });
                    main(() -> {
                        InterfaceCard card = active();
                        require(card != null && card.isAttachedToWindow() && card.definition().id().equals(page), action + " opens the app-owned center");
                        require(page.equals("controls") ? card.findViewWithTag("control-tile-grid") != null : card.findViewWithTag("notification-center") instanceof NotificationCenterView, "production center content is mounted");
                        owner.act(action); finish(); require(active() == null && !card.isAttachedToWindow(), "same shortcut closes the center");
                    });
                }
                main(() -> {
                    setup(edge); owner.act("notifications"); finish(); owner.act("notification_list"); finish(); require(active() == null, "legacy notification shortcut toggles the same center");
                    owner.act("notifications"); finish(); InterfaceCard old = active(); owner.act("controls"); finish();
                    require(active().definition() == InterfaceCard.CONTROLS && !old.isAttachedToWindow(), "control shortcut replaces the notification center");
                    owner.act("notifications"); finish(); require(active().definition() == InterfaceCard.NOTIFICATIONS, "notification shortcut replaces the control center");
                    DockView.Listener listener = new DockView.Listener() { public void action(String id) { owner.act(id); } public void configure() { } };
                    DockView dock = new DockView(activity, prefs, owner.placement, 0, listener, false);
                    PanelEntryView entry = new PanelEntryView(activity, prefs, owner.placement, listener);
                    for (View source : new View[]{dock, entry}) {
                        owner.closePanel(); require(source.performAccessibilityAction(R.id.open_notifications, null), "notification accessibility action is accepted"); finish();
                        require(active() != null && active().definition() == InterfaceCard.NOTIFICATIONS, "notification accessibility opens the app-owned center");
                        require(source.performAccessibilityAction(R.id.open_controls, null), "control accessibility action is accepted"); finish();
                        require(active() != null && active().definition() == InterfaceCard.CONTROLS, "control accessibility opens the app-owned center");
                    }
                    clear(); require(CoverApp.catalog(activity).observerCount() == 0, "center close releases catalog subscriptions");
                });
            }
            return "PASS: " + assertions + " panel action assertions; notification shortcuts, legacy alias, control shortcut, switching, repeated taps and accessibility; emulator only";
        } finally { main(() -> { clear(); activity.finish(); }); }
    }
    String runBlankTaps() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); test.waitForIdleSync(); real = activity.getWindowManager();
        try {
            main(() -> { prefs = new Prefs(activity); prefs.data.edit().clear().putBoolean("haptics", false).putBoolean("panel_blur", false).putBoolean("panel_media", false).commit(); prefs.saveActions("panel", java.util.List.of("wifi")); activity.setContentView(new FrameLayout(activity)); });
            for (int edge : new int[]{DockGeometry.TOP, DockGeometry.BOTTOM}) for (String page : new String[]{"controls", "notification_list"}) {
                for (boolean inScroll : new boolean[]{false, true}) {
                    main(() -> { setup(edge); owner.act(page); finish(); });
                    main(() -> {
                        InterfaceCard card = active(); PanelSurface surface = (PanelSurface) card.getChildAt(0);
                        View area = inScroll ? page.equals("controls") ? surface.findViewWithTag("control-tile-grid") : surface.getChildAt(1) : ((android.view.ViewGroup) surface.findViewWithTag("panel-header")).getChildAt(0);
                        android.graphics.Rect box = bounds(surface, area); float x = box.centerX(), y = inScroll ? box.bottom - 8 : box.centerY(); long at = SystemClock.uptimeMillis();
                        touch(surface, at, 0, android.view.MotionEvent.ACTION_DOWN, x, y); touch(surface, at, 60, android.view.MotionEvent.ACTION_UP, x, y);
                        require(field("panelAnimation") != null && active() == card, "blank tap starts whole-card exit: " + page + ", scroll=" + inScroll);
                        ValueAnimator animation = (ValueAnimator) field("panelAnimation"); animation.pause(); animation.setCurrentPlayTime(animation.getDuration() / 2);
                        require(card.getTranslationY() * (edge == DockGeometry.TOP ? -1 : 1) > 0, "blank tap exits toward the originating edge"); finish();
                        require(active() == null && !card.isAttachedToWindow() && card.getChildCount() == 0, "blank tap releases content after animation");
                        View host = (View) field("panelHost"); require(host.getVisibility() == View.INVISIBLE && (((WindowManager.LayoutParams) host.getLayoutParams()).flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0, "closed host is hidden and untouchable");
                    });
                }
                main(() -> { setup(edge); owner.act(page); finish(); });
                main(() -> {
                    PanelSurface surface = (PanelSurface) active().getChildAt(0); android.graphics.Rect box = bounds(surface, ((android.view.ViewGroup) surface.findViewWithTag("panel-header")).getChildAt(0)); float x = box.centerX(), y = box.centerY();
                    for (int mode = 0; mode < 4; mode++) {
                        long at = SystemClock.uptimeMillis(); touch(surface, at, 0, android.view.MotionEvent.ACTION_DOWN, x, y);
                        if (mode == 0) { touch(surface, at, 20, android.view.MotionEvent.ACTION_MOVE, x + Ui.dp(activity, 40), y); touch(surface, at, 40, android.view.MotionEvent.ACTION_MOVE, x, y); }
                        if (mode == 1) touch(surface, at, 20, android.view.MotionEvent.ACTION_CANCEL, x, y);
                        if (mode == 2) touch(surface, at, 20, android.view.MotionEvent.ACTION_POINTER_DOWN, x, y);
                        touch(surface, at, mode == 3 ? android.view.ViewConfiguration.getLongPressTimeout() + 10 : 60, android.view.MotionEvent.ACTION_UP, x, y);
                        require(active() != null && field("panelAnimation") == null, "movement, cancel, multi-touch and hold do not blank-dismiss, mode=" + mode);
                    }
                    long at = SystemClock.uptimeMillis(); touch(surface, at, 0, android.view.MotionEvent.ACTION_DOWN, x, y); surface.layout(0, 0, surface.getWidth() - 1, surface.getHeight()); touch(surface, at, 60, android.view.MotionEvent.ACTION_UP, x, y);
                    require(field("panelAnimation") == null, "resize cancels a pending blank tap");
                });
                int[] clicks = {0}; View[] clicked = {null};
                main(() -> {
                    PanelSurface surface = (PanelSurface) active().getChildAt(0); View target;
                    if (page.equals("controls")) target = surface.findViewWithTag("control-wifi");
                    else {
                        NotificationCenterView center = surface.findViewWithTag("notification-center"); android.app.Notification notification = new android.app.Notification.Builder(activity, "fixture").setSmallIcon(R.drawable.ic_ms_notifications).setContentTitle("Fixture").build();
                        center.update(true, java.util.List.of(new android.service.notification.StatusBarNotification(activity.getPackageName(), activity.getPackageName(), 7, "blank-tap", android.os.Process.myUid(), 0, 0, notification, android.os.Process.myUserHandle(), System.currentTimeMillis())));
                        target = center.findViewWithTag("notification-list");
                        surface.measure(View.MeasureSpec.makeMeasureSpec(frame.width(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(frame.height(), View.MeasureSpec.EXACTLY)); surface.layout(0, 0, frame.width(), frame.height());
                        target = ((NotificationSwipeRow) ((android.view.ViewGroup) ((android.view.ViewGroup) target).getChildAt(0)).getChildAt(0)).surface;
                    }
                    clicked[0] = target; target.setOnClickListener(v -> clicks[0]++); android.graphics.Rect box = bounds(surface, target); long at = SystemClock.uptimeMillis(); touch(surface, at, 0, android.view.MotionEvent.ACTION_DOWN, box.centerX(), box.centerY()); touch(surface, at, 60, android.view.MotionEvent.ACTION_UP, box.centerX(), box.centerY());
                });
                main(() -> {
                    PanelSurface surface = (PanelSurface) active().getChildAt(0); View target = clicked[0]; android.graphics.Rect box = bounds(surface, target); long at;
                    require(clicks[0] == 1 && field("panelAnimation") == null, "button or notification click keeps its own action: " + page + ", clicks=" + clicks[0]);
                    target.setEnabled(false); at = SystemClock.uptimeMillis(); touch(surface, at, 0, android.view.MotionEvent.ACTION_DOWN, box.centerX(), box.centerY()); touch(surface, at, 60, android.view.MotionEvent.ACTION_UP, box.centerX(), box.centerY()); require(field("panelAnimation") == null, "disabled action is not blank space");
                    if (page.equals("controls")) {
                        owner.editControls(); at = SystemClock.uptimeMillis(); touch(surface, at, 0, android.view.MotionEvent.ACTION_DOWN, 2, 2); touch(surface, at, 60, android.view.MotionEvent.ACTION_UP, 2, 2); require(field("controlEditor") != null && field("panelAnimation") == null, "editor padding tap preserves the draft");
                    }
                    clear();
                });
            }
            return "PASS: " + assertions + " blank-tap assertions; controls and notifications, both entry edges, scroll whitespace, animation, release and gesture exclusions; emulator only";
        } finally { main(() -> { clear(); activity.finish(); }); }
    }
    String runHome() {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        prefs = new Prefs(activity); real = activity.getSystemService(WindowManager.class);
        main(() -> { prefs.data.edit().clear().putBoolean("panel_blur", false).putBoolean("haptics", false).commit(); activity.setContentView(new FrameLayout(activity)); });
        try {
            for (int edge : new int[]{DockGeometry.TOP, DockGeometry.BOTTOM}) {
                for (String page : new String[]{"controls", "notifications", "media", "rotation", "editor", "details", "app_hub", "app_dock", "recents"}) {
                    main(() -> {
                        setup(edge); owner.display = activity.getDisplay(); owner.act(page.equals("editor") || page.equals("details") ? "controls" : page); finish();
                        if (page.equals("editor")) owner.editControls();
                        if (page.equals("details")) owner.showDetails("volume", active());
                        InterfaceCard card = active(); View body = card.getChildAt(card.getChildCount() - 1);
                        String saved = prefs.data.getString("panel", "");
                        call("systemDialogsClosed", Intent.class, new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "recentapps"));
                        require(active() == card && !(boolean) field("homeClosing"), "Recents does not close " + page);
                        call("systemDialogsClosed", Intent.class, new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "gestureNav"));
                        require(active() == card && !(boolean) field("homeClosing"), "generic navigation is not treated as Home");
                        call("dismissForHome");
                        require((boolean) field("homeClosing") && field("panelAnimation") != null, "Home immediately starts exit for " + page);
                        require(field("details") == null && field("panelPush") == null && field("hubPush") == null, "Home drops nested detail and outgoing history");
                        ValueAnimator exit = (ValueAnimator) field("panelAnimation");
                        call("dismissForHome"); owner.act("app_hub"); owner.launcherAccepted();
                        require(field("panelAnimation") == exit && active() == card, "repeated Home, late launch completion and taps cannot restart exit");
                        exit.setCurrentPlayTime(170);
                        int expected = page.equals("app_dock") ? DockGeometry.BOTTOM : DockGeometry.TOP;
                        require(card.getTranslationY() * InterfaceCard.sign(expected) > 0, "Home exits upward except for Dock alone: " + page);
                        WindowManager.LayoutParams params = (WindowManager.LayoutParams) ((View) card.getParent()).getLayoutParams();
                        require((params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0 && (params.flags & (WindowManager.LayoutParams.FLAG_BLUR_BEHIND | WindowManager.LayoutParams.FLAG_DIM_BEHIND)) == 0, "exiting card cannot intercept Home or obscure new scene");
                        exit.end();
                        require(active() == null && field("hub") == null && field("controlEditor") == null && !(boolean) field("homeClosing"), "Home releases all interfaces for " + page);
                        require(card.getParent() == null && card.getChildCount() == 0 && !body.isAttachedToWindow(), "Home disposes card and subscriptions");
                        require(saved.equals(prefs.data.getString("panel", "")), "Home never commits editor draft");
                        for (FrameLayout host : (FrameLayout[]) field("hubHosts")) require(host.getChildCount() == 0 && host.getVisibility() == View.INVISIBLE, "launcher hosts stay empty and hidden");
                        require(((FrameLayout) field("panelHost")).getChildCount() == 0 && ((View) field("panelHost")).getVisibility() == View.INVISIBLE, "panel host stays empty and hidden");
                    });
                }
                main(() -> {
                    setup(edge); owner.display = activity.getDisplay(); owner.act("controls"); finish(); InterfaceCard old = active();
                    owner.act("app_hub"); freeze(); progress(.35f); InterfaceCard incoming = active(); float start = incoming.getTranslationY();
                    call("dismissForHome"); require(Math.abs(incoming.getTranslationY() - start) < .1f, "Home reverses a partial entrance without jumping");
                    require(old.getParent() == null && old.getChildCount() == 0, "Home immediately retires the outgoing page");
                    finish(); require(active() == null && field("panelPush") == null && field("hubPush") == null, "Home during transition cannot restore outgoing page");
                    owner.act("controls"); finish(); call("dismissForHome"); call("removeWindows");
                    require(field("panelAnimation") == null && !(boolean) field("homeClosing") && roots.isEmpty(), "display teardown cancels Home animation without late resurrection");
                });
            }
            return "PASS: cover-home; " + assertions + " assertions; 9 surfaces, both entry edges, repeated Home, interrupted transitions and teardown; system broadcast and Samsung navigation require device verification";
        } finally { main(() -> { clear(); activity.finish(); }); }
    }
    String run() {
        Instrumentation.ActivityMonitor monitor = test.addMonitor(MainActivity.class.getName(), null, false);
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); SystemClock.sleep(700); test.waitForIdleSync();
        if (monitor.getLastActivity() != null) activity = monitor.getLastActivity(); test.removeMonitor(monitor);
        real = activity.getWindowManager();
        try {
            main(() -> { prefs = new Prefs(activity); prefs.data.edit().clear().putBoolean("haptics", false).putBoolean("panel_blur", false).commit(); activity.setContentView(new FrameLayout(activity)); checkDefinitions(); });
            String[] actions = {"controls", "notification_list", "app_hub", "recents"};
            for (int edge : new int[]{DockGeometry.TOP, DockGeometry.BOTTOM}) for (String from : actions) for (String to : actions) if (!from.equals(to)) {
                int transitionEdge = to.equals("recents") ? DockGeometry.BOTTOM : edge; String label = from + " -> " + to + ", edge=" + edge; InterfaceCard[] old = {null}, next = {null};
                main(() -> { setup(edge); owner.act(from); finish(); });
                main(() -> { old[0] = active(); require(old[0] != null && old[0].isAttachedToWindow(), "source attached: " + label); owner.act(to); freeze(); next[0] = active(); require(next[0] != null && next[0] != old[0], "separate incoming card: " + label); progress(0); require(Math.abs(next[0].getTranslationY()) == (to.equals("recents") ? sceneExtent() : frame.height()), "shortcut starts outside screen: " + label); });
                main(() -> {
                    progress(.1f); int direction = transitionEdge == DockGeometry.TOP ? 1 : -1;
                    require(direction * old[0].getTranslationY() >= .1f * (to.equals("recents") ? sceneExtent() : frame.height()) - 1, "same-direction common travel with edge separation: " + label); separation(old[0], next[0], transitionEdge);
                    require(old[0].isAttachedToWindow() && old[0].getImportantForAccessibility() == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS, "source retained with input disabled: " + label);
                    cancel(); require(active() == old[0] && !next[0].isAttachedToWindow() && old[0].getTranslationY() == 0, "cancellation reuses still-visible source: " + label);
                    owner.act(to); freeze();
                });
                main(() -> { for (float p : new float[]{.1f, .25f, .5f, .7f}) { progress(p); separation(old[0], active(), transitionEdge); } progress(1); require(!old[0].isAttachedToWindow() && old[0].getChildCount() == 0, "offscreen source is fully released: " + label); finish(); });
                main(() -> {
                    InterfaceCard current = active(); require(current != null && current.getTranslationY() == 0 && current.isAttachedToWindow(), "incoming settles: " + label);
                    clear(); require(active() == null && !old[0].isAttachedToWindow(), "closing cannot revive old card: " + label);
                    require(CoverApp.catalog(activity).observerCount() == 0, "catalog subscriptions released: " + label);
                });
            }
            main(() -> { setup(DockGeometry.BOTTOM); owner.act("controls"); finish(); });
            main(() -> { InterfaceCard card = active(); require(card.statusBar().isAttachedToWindow(), "status owned by mounted card"); owner.editControls(); require(card.statusBar().getVisibility() == View.GONE, "editor hides shared status"); card.findViewWithTag("control-editor-done").performClick(); require(card.statusBar().getVisibility() == View.VISIBLE, "editor completion restores same shared status"); clear(); require(!card.statusBar().isAttachedToWindow(), "status subscriptions detach with card"); });
            checkLauncherGlass();
            checkSidebarSplit();
            checkSidebarMask();
            checkDockWindows();
            checkManualDismissal();
            checkLauncherDismissalEdge();
            checkUnsafeGradient();
            checkInitialBridge();
            checkDelayedReady();
            checkTaskDirections();
            main(() -> { setup(DockGeometry.TOP); owner.act("app_hub"); finish(); });
            main(() -> { owner.launcherAccepted(); require(field("hub") != null && field("panelAnimation") != null, "accepted application keeps whole-card exit running when panel cleanup runs"); finish(); require(active() == null, "accepted launch releases launcher at exit completion"); });
            InterfaceCard[] interrupted = {null};
            main(() -> { setup(DockGeometry.BOTTOM); owner.act("controls"); freeze(); progress(.3f); interrupted[0] = active(); });
            main(() -> { owner.act("app_hub"); freeze(); });
            main(() -> { progress(.1f); cancel(); require(active() == interrupted[0] && Math.abs(interrupted[0].getTranslationY() - .7f * frame.height()) < 1, "canceling an interrupted entrance restores its precise pose"); require(field("panelAnimation") != null, "restored partial source resumes its pending entrance"); finish(); require(active().getTranslationY() == 0, "resumed source reaches its resting pose"); clear(); });
            main(() -> { setup(DockGeometry.BOTTOM); owner.act("controls"); finish(); });
            main(() -> {
                InterfaceCard original = active(); owner.editControls(); Object editor = field("controlEditor"); failNextHubUpdate = true; owner.act("app_hub");
                require(active() == original && field("controlEditor") == editor && original.isAttachedToWindow(), "mount failure restores the exact editor owner and source card");
                WindowManager.LayoutParams restored = (WindowManager.LayoutParams) ((View) original.getParent()).getLayoutParams();
                require(original.statusBar().getParent() == null && (restored.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0, "editor keeps shared status detached and restores focusable window");
                original.findViewWithTag("control-editor-done").performClick(); require(field("controlEditor") == null && original.statusBar().getParent() == original, "restored editor completion remains functional"); clear();
            });
            return "PASS: interface-card; " + assertions + " assertions; all 24 directed top/bottom transitions, cancellation, disposal, materials and status; application-panel windows, Samsung overlay routing unverified";
        } finally { main(() -> { clear(); activity.finish(); }); }
    }
}
