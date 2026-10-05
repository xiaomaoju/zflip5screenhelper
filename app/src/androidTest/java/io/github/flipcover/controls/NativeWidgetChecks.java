package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;

/** Disposable emulator; real AppWidget service, separate provider UID, no Samsung claims. */
final class NativeWidgetChecks {
    private final Instrumentation test;
    private Activity activity;
    private AppWidgetHost outerHost;
    private AppWidgetHostView rendered;
    private NativeWidgetBridge bridge;
    private int outer, inner, assertions;
    private final int screenWidth, screenHeight, screenDensity;
    NativeWidgetChecks(Instrumentation test) { this(test, 720, 748); }
    NativeWidgetChecks(Instrumentation test, int width, int height) { this(test, width, height, 340); }
    NativeWidgetChecks(Instrumentation test, int width, int height, int density) { this.test = test; screenWidth = width; screenHeight = height; screenDensity = density; }
    private void require(boolean value, String why) { assertions++; if (!value) throw new AssertionError(why); }
    private void main(Runnable action) {
        Throwable[] error = {null}; test.runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error[0] = e; } });
        if (error[0] != null) throw new AssertionError(error[0]); test.waitForIdleSync();
    }
    private void until(BooleanSupplier condition, String why) {
        long until = SystemClock.uptimeMillis() + 7000;
        while (!condition.getAsBoolean() && SystemClock.uptimeMillis() < until) { test.waitForIdleSync(); SystemClock.sleep(80); }
        require(condition.getAsBoolean(), why);
    }
    private TextView text(View v, String expected) {
        if (v instanceof TextView t && expected.contentEquals(t.getText())) return t;
        if (v instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = text(group.getChildAt(i), expected); if (found != null) return found; }
        return null;
    }
    private android.graphics.RectF bounds(View child) {
        android.graphics.RectF result = new android.graphics.RectF(0, 0, child.getWidth(), child.getHeight());
        while (child != rendered) {
            child.getMatrix().mapRect(result); View parent = (View) child.getParent();
            result.offset(child.getLeft() - parent.getScrollX(), child.getTop() - parent.getScrollY()); child = parent;
        }
        return result;
    }
    String run() throws Exception {
        Context context = test.getTargetContext(); Prefs prefs = new Prefs(context); int oldDisplay = prefs.displayId();
        test.getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET", "android.permission.ADD_TRUSTED_DISPLAY");
        SurfaceTexture texture = new SurfaceTexture(false); texture.setDefaultBufferSize(screenWidth, screenHeight); Surface surface = new Surface(texture);
        // AOSP Android 16's system-only TRUSTED flag (1 << 10), exclusively in this emulator fixture.
        VirtualDisplay display = context.getSystemService(DisplayManager.class).createVirtualDisplay("Widget relay checks", screenWidth, screenHeight, screenDensity, surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 10));
        require(display != null, "owned secondary display exists");
        try {
            prefs.data.edit().putInt("display", display.getDisplay().getDisplayId()).commit();
            activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            AppWidgetManager manager = AppWidgetManager.getInstance(context);
            main(() -> {
                bridge = CoverApp.widgets(context); outerHost = new AppWidgetHost(context, 0x54455354); outerHost.deleteHost();
                outer = outerHost.allocateAppWidgetId(); Bundle size = new Bundle();
                size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300); size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 300);
                size.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 300); size.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 300);
                require(manager.bindAppWidgetIdIfAllowed(outer, new ComponentName(context, NativeWidgetProvider.class), size), "outer card binds through AppWidget service");
                rendered = outerHost.createView(activity, outer, manager.getAppWidgetInfo(outer)); FrameLayout root = new FrameLayout(activity);
                int cardPixels = Math.round(300 * activity.getResources().getDisplayMetrics().density);
                root.addView(rendered, new FrameLayout.LayoutParams(cardPixels, cardPixels)); activity.setContentView(root); outerHost.startListening();
                inner = bridge.allocate(outer);
                require(manager.bindAppWidgetIdIfAllowed(inner, new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName()), bridge.options(bridge.size(outer))), "different-UID provider binds after explicit test grant");
                require(bridge.save(outer, inner), "inner binding saved");
                var legacy = context.getSharedPreferences("native_widgets", Context.MODE_PRIVATE);
                legacy.edit().remove("layout_" + outer).putInt("inner_" + outer, inner).commit();
                require(bridge.items(outer).equals(java.util.List.of(new WidgetGrid.Item(inner, 0, 0, 4, 4))), "legacy binding loads as full card without reallocating");
                require(bridge.commitLayout(outer, bridge.items(outer)), "legacy layout commits into grid format");
                require(!legacy.contains("inner_" + outer) && bridge.inner(outer) == inner && bridge.info(inner) != null, "migration keeps authorized widget identity");
            });
            until(() -> text(rendered, "跨应用更新 1") != null, "original cross-package resources render inside outer card");
            until(() -> text(rendered, "LARGE") != null, "responsive widget retains large layout at full card size");
            main(() -> {
                ListView list = rendered.findViewById(io.github.flipcover.controls.test.R.id.fixture_list);
                require(list != null && list.getAdapter() != null && list.getAdapter().getCount() == 1, "inline collection survives nested RemoteViews");
                require(manager.getAppWidgetOptions(inner).getInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY) == android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD, "inner provider informed of lockscreen surface");
                require(manager.getAppWidgetInfo(outer).targetCellWidth == 4 && manager.getAppWidgetInfo(outer).targetCellHeight == 4, "native card declares four by four cells");
                android.appwidget.AppWidgetProviderInfo wide = bridge.info(inner).clone(); wide.targetCellWidth = 5; wide.resizeMode = android.appwidget.AppWidgetProviderInfo.RESIZE_NONE;
                require(!bridge.fits(wide, bridge.size(outer)), "non-resizable widgets above four columns rejected");
                wide.resizeMode = android.appwidget.AppWidgetProviderInfo.RESIZE_HORIZONTAL;
                require(bridge.fits(wide, bridge.size(outer)), "larger defaults allowed only when provider supports resizing into card");
                require(!bridge.fits(bridge.info(inner), new android.util.SizeF(20, 20)), "reject below provider minimum");
                require(manager.getAppWidgetOptions(inner).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) == (int) Math.min(300, screenWidth * 160f / screenDensity), "actual card size forwarded in dp");
            });
            context.sendBroadcast(new Intent("fixture.UPDATE").setComponent(new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName())).putExtra("value", 3));
            until(() -> text(rendered, "跨应用更新 3") != null, "provider updates propagate through both widget hosts");
            main(() -> rendered.findViewById(io.github.flipcover.controls.test.R.id.fixture_button).performClick());
            until(() -> text(rendered, "跨应用更新 7") != null, "original PendingIntent executes in provider and returns update");
            main(() -> {
                int unused = bridge.allocate(outer); require(bridge.pending(outer) == unused, "pending allocation recorded"); bridge.cancel(outer);
                require(bridge.inner(outer) == inner, "cancel preserves previous widget");
                require(java.util.Arrays.stream(bridge.host().getAppWidgetIds()).noneMatch(id -> id == unused), "cancel frees pending system widget ID");
                int abandoned = bridge.allocate(outer);
                context.getSharedPreferences("native_widgets", Context.MODE_PRIVATE).edit().putLong("pending_at_" + outer, System.currentTimeMillis() - 25 * 60 * 60 * 1000L).commit(); bridge.refresh();
                require(bridge.pending(outer) <= 0 && java.util.Arrays.stream(bridge.host().getAppWidgetIds()).noneMatch(id -> id == abandoned), "abandoned configuration expires without removing current widget");
                prefs.data.edit().putInt("display", 9999).commit(); bridge.refresh();
            });
            until(() -> text(rendered, "请先选择目标外屏") != null, "missing target never falls back to display zero");
            main(() -> { prefs.data.edit().putInt("display", display.getDisplay().getDisplayId()).commit(); bridge.refresh(); });
            until(() -> text(rendered, "跨应用更新 7") != null, "reattachment restores last provider contents without polling");
            checkFullEditor(context, display.getDisplay().getDisplayId());
            checkSafeArea(display.getDisplay(), manager);
            checkMultipleCards(context, manager);
            checkConfigureResult(context, display.getDisplay().getDisplayId());
            checkDraftEditing(context, display.getDisplay().getDisplayId());
            checkTemplates(context, display.getDisplay().getDisplayId());
            inner = bridge.inner(outer);
            main(() -> {
                bridge.trim();
                outerHost.deleteAppWidgetId(outer);
            });
            until(() -> bridge.inner(outer) <= 0, "native card deletion clears persisted binding");
            require(java.util.Arrays.stream(bridge.host().getAppWidgetIds()).noneMatch(id -> id == inner), "native card deletion releases inner system widget ID");
            return "PASS: " + assertions + " native widget assertions; real Android relay, not Samsung host verification";
        } finally {
            main(() -> { if (outerHost != null) { outerHost.stopListening(); outerHost.deleteHost(); } if (bridge != null) { if (outer > 0) bridge.remove(outer); bridge.refresh(); } if (activity != null) activity.finish(); });
            prefs.data.edit().putInt("display", oldDisplay).commit(); test.getUiAutomation().dropShellPermissionIdentity(); display.release(); surface.release(); texture.release();
        }
    }
    private void checkSafeArea(android.view.Display display, AppWidgetManager manager) {
        var saved = bridge.items(outer);
        float targetDensity = screenDensity / 160f, canvasHeight = Math.min(300, screenHeight / targetDensity), top = 40 / targetDensity, usableHeight = canvasHeight - 120 / targetDensity;
        main(() -> bridge.safeArea(display, screenWidth, screenHeight, new DockGeometry.Box(0, 40, screenWidth, screenHeight - 120)));
        until(() -> rendered.findViewById(R.id.widget_grid_root) != null && ((ViewGroup) rendered.findViewById(R.id.widget_grid_root)).getChildCount() == 1, "full-card widgets use a safe-area container");
        main(() -> {
            var frame = bridge.frame(outer); require(Math.abs(frame.top() - top) < .01f && Math.abs(frame.height() - usableHeight) < .01f, "status and dock edge depths reserved once: " + frame + " expected top=" + top + " height=" + usableHeight + " density=" + test.getTargetContext().createDisplayContext(display).getResources().getDisplayMetrics().density);
            require(manager.getAppWidgetOptions(inner).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT) == (int) usableHeight, "provider receives usable height after safety reserve");
            ViewGroup grid = rendered.findViewById(R.id.widget_grid_root); View content = grid.getChildAt(0); float density = activity.getResources().getDisplayMetrics().density;
            require(Math.abs(content.getTop() / density - top) <= 1, "actual RemoteViews content starts below status area");
            require(content.getBottom() / density <= top + usableHeight + 1, "actual RemoteViews content ends above dock area");
            require(bridge.items(outer).equals(saved), "safe-area update preserves widget identity and logical grid");
            bridge.safeArea(display, screenWidth + 1, screenHeight, new DockGeometry.Box(0, 40, screenWidth, screenHeight - 120));
            require(Math.abs(bridge.size(outer).getHeight() - canvasHeight) < .01f, "stale physical geometry is ignored");
            bridge.safeArea(null, 0, 0, null);
        });
        until(() -> text(rendered, "LARGE") != null && rendered.findViewById(R.id.widget_grid_root) == null, "removing safety reserve restores original responsive forwarding");
        main(() -> {
            bridge.safeArea(display, screenWidth, screenHeight, new DockGeometry.Box(0, 0, Math.max(0, screenWidth - Math.round(260 * targetDensity)), screenHeight));
            require(!bridge.fits(bridge.info(inner), bridge.size(outer)), "narrow safe frame is below fixture minimum for a new addition");
            require(bridge.commitLayout(outer, saved), "existing instance can save unchanged layout after safe area narrows");
        });
        until(() -> rendered.findViewById(R.id.widget_grid_root) != null && rendered.findViewById(io.github.flipcover.controls.test.R.id.fixture_text) != null, "existing content remains rendered instead of being replaced by a size placeholder");
        main(() -> { require(bridge.items(outer).equals(saved), "narrowing does not replace bindings"); bridge.safeArea(null, 0, 0, null); });
        until(() -> text(rendered, "LARGE") != null && rendered.findViewById(R.id.widget_grid_root) == null, "restoring frame restores full-card content after narrowing");
        Context context = test.getTargetContext();
        main(() -> bridge.safeArea(display, screenWidth, screenHeight, new DockGeometry.Box(0, 40, screenWidth, screenHeight - 120)));
        context.sendBroadcast(new Intent("fixture.UPDATE").setComponent(new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName())).putExtra("value", 11).putExtra("only_id", inner));
        until(() -> text(rendered, "跨应用更新 11") != null, "fixed-height provider update reaches the native grid");
        main(() -> {
            View content = rendered.findViewById(R.id.widget_content), button = rendered.findViewById(io.github.flipcover.controls.test.R.id.fixture_button);
            require(content.getScaleX() < 1 && content.getScaleX() == content.getScaleY(), "oversized native XML scales uniformly inside its allocated cell");
            android.graphics.RectF hit = bounds(button), cell = bounds((View) content.getParent());
            require(hit.bottom <= cell.bottom + 1 && hit.top >= cell.top, "fixed-height bottom button is visible within the native cell: " + hit + " / " + cell);
            long down = SystemClock.uptimeMillis();
            android.view.MotionEvent press = android.view.MotionEvent.obtain(down, down, android.view.MotionEvent.ACTION_DOWN, hit.centerX(), hit.centerY(), 0), release = android.view.MotionEvent.obtain(down, down + 30, android.view.MotionEvent.ACTION_UP, hit.centerX(), hit.centerY(), 0);
            rendered.dispatchTouchEvent(press); rendered.dispatchTouchEvent(release); press.recycle(); release.recycle();
        });
        until(() -> text(rendered, "跨应用更新 7") != null, "scaled bottom-button touch still executes the provider PendingIntent");
        context.sendBroadcast(new Intent("fixture.UPDATE").setComponent(new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName())).putExtra("value", 12).putExtra("only_id", inner));
        until(() -> text(rendered, "跨应用更新 12") != null, "provider replaces a large initial artwork with its actual layout");
        main(() -> {
            ViewGroup content = rendered.findViewById(R.id.widget_content); View body = content.getChildAt(0);
            require(content.getScaleX() == 1 && content.getScaleY() == 1, "initial 500dp artwork does not shrink the provider's dynamic controls");
            require(rendered.findViewById(io.github.flipcover.controls.test.R.id.fixture_artwork).getHeight() == Math.round(120 * activity.getResources().getDisplayMetrics().density), "dynamic artwork keeps its native size");
            require(Math.abs(body.getTop() + body.getHeight() / 2f - content.getHeight() / 2f) <= 1, "shorter native content is centered vertically in its cell");
            require(body.getWidth() == content.getWidth(), "flexible content keeps the full available width");
        });
        context.sendBroadcast(new Intent("fixture.UPDATE").setComponent(new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName())).putExtra("value", 7).putExtra("only_id", inner));
        main(() -> bridge.safeArea(null, 0, 0, null));
        until(() -> text(rendered, "LARGE") != null && rendered.findViewById(R.id.widget_grid_root) == null, "flexible content restores unscaled forwarding after a fixed-layout update");
    }
    private void checkMultipleCards(Context context, AppWidgetManager manager) {
        main(() -> {
            require(NativeWidgetProvider.TYPES.length == 6, "six distinct native card entries");
            for (Class<?> type : NativeWidgetProvider.TYPES) { try { var receiver = context.getPackageManager().getReceiverInfo(new ComponentName(context, type), android.content.pm.PackageManager.GET_META_DATA); require(receiver.metaData.containsKey("com.samsung.android.appwidget.provider"), "native slot registered " + type.getSimpleName()); } catch (android.content.pm.PackageManager.NameNotFoundException failure) { throw new AssertionError(failure); } }
            int second = outerHost.allocateAppWidgetId(); require(manager.bindAppWidgetIdIfAllowed(second, new ComponentName(context, NativeWidgetProvider.Slot2.class), manager.getAppWidgetOptions(outer)), "second native component binds");
            require(bridge.slot(second) == 2 && bridge.owns(second), "second card has separate identity");
            int added = bridge.allocate(outer); require(manager.bindAppWidgetIdIfAllowed(added, new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName()), bridge.options(bridge.size(outer, 2, 2))), "second instance authorized"); require(bridge.stage(outer, added), "second instance staged");
            var layout = java.util.List.of(new WidgetGrid.Item(inner, 0, 0, 2, 2), new WidgetGrid.Item(added, 2, 2, 2, 2));
            require(bridge.commitLayout(outer, layout), "multiple instances saved"); require(bridge.items(second).isEmpty(), "other card stays unchanged");
            require(!bridge.commitLayout(outer, java.util.List.of(layout.get(0), layout.get(1).at(0, 0))), "overlap rejected transactionally"); require(bridge.items(outer).equals(layout), "failed save retains positions");
            int abandoned = bridge.allocate(outer); require(manager.bindAppWidgetIdIfAllowed(abandoned, new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName())), "draft instance bound"); require(bridge.stage(outer, abandoned), "draft staged"); bridge.discardDraft(outer);
            require(bridge.info(abandoned) == null && bridge.items(outer).equals(layout), "discard frees new ID and preserves all saved items");
            String a = bridge.startEditing(outer), b = bridge.startEditing(outer);
            int protectedDraft = bridge.allocate(outer); require(manager.bindAppWidgetIdIfAllowed(protectedDraft, new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName())), "new editing session binds"); require(bridge.stage(outer, protectedDraft), "new editing session stages");
            bridge.endEditing(outer, a); require(bridge.editing(outer, b) && bridge.info(protectedDraft) != null, "old editor cannot clean new editor draft"); bridge.endEditing(outer, b); require(bridge.info(protectedDraft) == null, "own session cleanup releases draft");
            outerHost.deleteAppWidgetId(second);
        });
        until(() -> rendered.findViewById(R.id.widget_grid_root) != null && ((ViewGroup) rendered.findViewById(R.id.widget_grid_root)).getChildCount() == 2 && text(rendered, "SMALL") != null, "multiple nested original RemoteViews render");
        main(() -> {
            ViewGroup grid = rendered.findViewById(R.id.widget_grid_root); View a = grid.getChildAt(0), b = grid.getChildAt(1);
            require(a.getWidth() > 0 && b.getLeft() >= a.getWidth() && b.getTop() >= a.getHeight(), "native RemoteViews grid applies real cell geometry: a=" + a.getLeft() + "," + a.getTop() + "," + a.getWidth() + "," + a.getHeight() + " b=" + b.getLeft() + "," + b.getTop() + "," + b.getWidth() + "," + b.getHeight());
            require(bridge.items(outer).size() == 2, "deleting second card preserves first card");

        });
        main(() -> { rendered.setLayoutDirection(View.LAYOUT_DIRECTION_RTL); bridge.trim(); });
        context.sendBroadcast(new Intent("fixture.UPDATE").setComponent(new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName())).putExtra("value", 11).putExtra("only_id", inner));
        until(() -> text(rendered, "跨应用更新 11") != null && text(rendered, "跨应用更新 1") != null, "trim then single-provider update preserves other static widget");
        main(() -> { ViewGroup grid = rendered.findViewById(R.id.widget_grid_root); require(grid.getChildAt(1).getLeft() >= grid.getChildAt(0).getRight(), "RTL preserves physical grid columns"); rendered.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); require(bridge.commitLayout(outer, java.util.List.of()), "clear selected card before configure test"); });
    }
    private void capture(Activity screen, String name) {
        main(() -> {
            View root = screen.getWindow().getDecorView(); android.graphics.Bitmap image = android.graphics.Bitmap.createBitmap(root.getWidth(), root.getHeight(), android.graphics.Bitmap.Config.ARGB_8888); root.draw(new android.graphics.Canvas(image));
            java.io.File directory = new java.io.File(test.getTargetContext().getExternalFilesDir(null), "widget-checks"); directory.mkdirs();
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output); } catch (java.io.IOException failure) { throw new AssertionError(failure); } finally { image.recycle(); }
        });
    }
    private void checkTemplates(Context context, int displayId) throws Exception {
        Prefs prefs = new Prefs(context); String previous = prefs.data.getString("widget_templates", null);
        var saved = bridge.items(outer); int[] before = bridge.host().getAppWidgetIds();
        Activity editor = null;
        try {
            prefs.data.edit().remove("widget_templates").commit();
            var exported = bridge.exportTemplates(); var cards = WidgetTemplates.read(exported);
            require(cards.size() == 1 && cards.get(0).items().size() == saved.size(), "export captures actual current widget provider and geometry");
            require(exported.getJSONObject(0).length() == 2 && exported.getJSONObject(0).getJSONArray("items").getJSONObject(0).length() == 5, "template output contains no instance or host identity");
            var missing = new WidgetTemplates.Card(2, java.util.List.of(new WidgetTemplates.Entry("com.example.missing/.Widget", 0, 0, 2, 2)));
            prefs.data.edit().putString("widget_templates", WidgetTemplates.json(java.util.List.of(cards.get(0), missing)).toString()).commit();
            editor = test.startActivitySync(new Intent(context, NativeWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outer), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
            Activity first = editor;
            main(() -> { first.getWindow().getDecorView().findViewWithTag("widget-open-templates").performClick(); first.getWindow().getDecorView().findViewWithTag("widget-template-1").performClick(); first.getWindow().getDecorView().findViewWithTag("widget-template-start").performClick(); });
            require(!editor.getWindow().getDecorView().findViewWithTag("widget-template-bind").isEnabled(), "missing provider disables authorization without deleting template");
            require(bridge.items(outer).equals(saved) && bridge.host().getAppWidgetIds().length == before.length, "template selection never changes existing bindings");
            capture(editor, "template-missing");
            main(() -> { first.getWindow().getDecorView().findViewWithTag("widget-template-skip").performClick(); first.onBackPressed(); text(first.getWindow().getDecorView(), "放弃修改").performClick(); });
            require(bridge.items(outer).equals(saved), "skipping then discarding keeps original card");
            editor = test.startActivitySync(new Intent(context, NativeWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outer), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
            Activity second = editor;
            main(() -> { second.getWindow().getDecorView().findViewWithTag("widget-open-templates").performClick(); second.getWindow().getDecorView().findViewWithTag("widget-template-0").performClick(); second.getWindow().getDecorView().findViewWithTag("widget-template-start").performClick(); });
            capture(editor, "template-restore");
            Instrumentation.ActivityMonitor denied = test.addMonitor(new android.content.IntentFilter(AppWidgetManager.ACTION_APPWIDGET_BIND), new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
            test.getUiAutomation().dropShellPermissionIdentity();
            try {
                main(() -> second.getWindow().getDecorView().findViewWithTag("widget-template-bind").performClick());
                until(() -> text(second.getWindow().getDecorView(), "已取消本次授权，原布局保持不变") != null, "template denial returns to same step");
                require(bridge.items(outer).equals(saved) && bridge.host().getAppWidgetIds().length == before.length, "template denial frees only the new pending instance");
            } finally { test.removeMonitor(denied); test.getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET", "android.permission.ADD_TRUSTED_DISPLAY"); }
            main(() -> second.getWindow().getDecorView().findViewWithTag("widget-template-bind").performClick());
            require(bridge.items(outer).equals(saved) && bridge.host().getAppWidgetIds().length == before.length + 1, "restored item stays uncommitted with fresh identity");
            Instrumentation.ActivityMonitor recreation = test.addMonitor(NativeWidgetActivity.class.getName(), null, false);
            try { main(second::recreate); editor = recreation.waitForActivityWithTimeout(5000); require(editor != null && editor != second, "template workflow survives recreation"); test.waitForIdleSync(); }
            finally { test.removeMonitor(recreation); }
            Activity resumed = editor;
            require(text(editor.getWindow().getDecorView(), "待恢复 2/2") != null, "recreation keeps template step instead of rebinding first item");
            main(() -> resumed.getWindow().getDecorView().findViewWithTag("widget-template-bind").performClick());
            require(bridge.items(outer).equals(saved), "complete template remains a draft until Done");
            main(() -> resumed.getWindow().getDecorView().findViewWithTag("widget-done").performClick());
            var restored = bridge.items(outer);
            require(restored.size() == saved.size() && restored.get(0).id() != saved.get(0).id(), "Done replaces layout using newly authorized identities");
            for (int i = 0; i < restored.size(); i++) require(restored.get(i).x() == saved.get(i).x() && restored.get(i).y() == saved.get(i).y() && restored.get(i).width() == saved.get(i).width() && restored.get(i).height() == saved.get(i).height(), "template retains item geometry " + i);
            for (var old : saved) require(bridge.info(old.id()) == null, "Done releases replaced identity");
        } finally {
            if (editor != null) { Activity last = editor; main(last::finish); }
            prefs.data.edit().putString("widget_templates", previous).commit();
        }
    }
    private void checkDraftEditing(Context context, int displayId) {
        var saved = bridge.items(outer); int[] before = bridge.host().getAppWidgetIds();
        Activity editor = test.startActivitySync(new Intent(context, NativeWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outer), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        capture(editor, "editor");
        main(() -> {
            View add = editor.getWindow().getDecorView().findViewWithTag("widget-open-picker"), done = editor.getWindow().getDecorView().findViewWithTag("widget-done");
            require(add.getParent() == done.getParent() && add.getRight() <= done.getLeft(), "Add is in the header immediately left of Done");
            View grid = editor.getWindow().getDecorView().findViewWithTag("widget-grid"); float x = grid.getWidth() / 8f, fromY = grid.getHeight() / 8f, toY = grid.getHeight() * 5 / 8f; long now = SystemClock.uptimeMillis();
            for (int action : new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP}) { var e = android.view.MotionEvent.obtain(now, now + action + 10, action, x, action == 0 ? fromY : toY, 0); grid.dispatchTouchEvent(e); e.recycle(); }
            require(((WidgetGrid.Item) ((ViewGroup) grid).getChildAt(0).getTag()).equals(saved.get(0)), "ordinary preview swipe does not move or select the widget");
            android.widget.ScrollView scroll = editor.getWindow().getDecorView().findViewWithTag("widget-scroll"); scroll.scrollTo(0, 0);
            int[] viewport = new int[2], preview = new int[2]; scroll.getLocationInWindow(viewport); grid.getLocationInWindow(preview); float start = preview[1] - viewport[1] + fromY;
            for (int i = 0; i < 4; i++) { int action = i == 0 ? android.view.MotionEvent.ACTION_DOWN : i == 3 ? android.view.MotionEvent.ACTION_UP : android.view.MotionEvent.ACTION_MOVE; var e = android.view.MotionEvent.obtain(now, now + i * 32, action, preview[0] - viewport[0] + x, start - i * Ui.dp(editor, 24), 0); scroll.dispatchTouchEvent(e); e.recycle(); }
            require(scroll.getScrollY() > 0, "swiping from the widget preview scrolls the page"); require(bridge.items(outer).equals(saved), "page scrolling preserves saved widget identities and layout"); scroll.scrollTo(0, 0);
            for (int action : new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_CANCEL}) { var e = android.view.MotionEvent.obtain(now, now + action + 30, action, x, action == 0 ? fromY : toY, 0); grid.dispatchTouchEvent(e); e.recycle(); if (action == android.view.MotionEvent.ACTION_DOWN) require(grid.performLongClick(), "long press starts draft drag"); }
            require(((WidgetGrid.Item) ((ViewGroup) grid).getChildAt(0).getTag()).equals(saved.get(0)), "cancelled grid drag restores original position");
            for (int action : new int[] {android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_MOVE, android.view.MotionEvent.ACTION_UP}) { var e = android.view.MotionEvent.obtain(now, now + action + 80, action, x, action == 0 ? fromY : toY, 0); grid.dispatchTouchEvent(e); e.recycle(); if (action == android.view.MotionEvent.ACTION_DOWN) grid.performLongClick(); }
            ViewGroup changed = editor.getWindow().getDecorView().findViewWithTag("widget-grid"); require(((WidgetGrid.Item) changed.getChildAt(0).getTag()).y() == 2, "valid drag moves local draft"); require(bridge.items(outer).equals(saved), "drag does not persist before Done");
        });
        Instrumentation.ActivityMonitor cancelled = test.addMonitor(new android.content.IntentFilter(AppWidgetManager.ACTION_APPWIDGET_BIND), new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null), true);
        test.getUiAutomation().dropShellPermissionIdentity();
        try { addInEditor(editor, true); require(bridge.host().getAppWidgetIds().length == before.length && bridge.items(outer).equals(saved), "cancelled authorization frees pending ID and retains saved layout"); }
        finally { test.removeMonitor(cancelled); test.getUiAutomation().adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET", "android.permission.ADD_TRUSTED_DISPLAY"); }
        main(() -> { editor.onBackPressed(); editor.onBackPressed(); });
        addInEditor(editor);
        require(bridge.host().getAppWidgetIds().length == before.length + 1, "uncommitted addition owns exactly one new ID");
        main(() -> { editor.onBackPressed(); TextView discard = text(editor.getWindow().getDecorView(), "放弃修改"); require(discard != null, "dirty back offers discard"); discard.performClick(); });
        until(() -> bridge.host().getAppWidgetIds().length == before.length, "discard deletes only uncommitted new binding"); require(bridge.items(outer).equals(saved), "discard restores all saved positions and identities");
    }
    private void checkFullEditor(Context context, int displayId) {
        var saved = bridge.items(outer); int[] identities = bridge.host().getAppWidgetIds();
        Activity editor = test.startActivitySync(new Intent(context, NativeWidgetActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, outer), android.app.ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
        try {
            main(() -> {
                var info = bridge.info(inner).clone(); info.getActivityInfo().labelRes = 0; info.getActivityInfo().nonLocalizedLabel = " \u200b\u00a0\u2060\ufe0f"; require(bridge.providerLabel(info).equals(context.getPackageManager().getApplicationLabel(test.getContext().getApplicationInfo()).toString()), "invisible provider name falls back to the application label");
                View root = editor.getWindow().getDecorView(), grid = root.findViewWithTag("widget-grid"), add = root.findViewWithTag("widget-open-picker");
                require(grid.getWidth() < root.getWidth() * .65f, "full-card editor preview stays a centered thumbnail");
                require(add != null && add.isEnabled(), "full sixteen-cell card still allows browsing via Add");
                require(text(root, bridge.widgetLabel(inner)) != null, "editor list displays the widget name");
            });
            capture(editor, "full-editor");
            main(() -> { editor.getWindow().getDecorView().findViewWithTag("widget-open-picker").performClick(); require(editor.getWindow().getDecorView().findViewWithTag("widget-search") != null, "full-card Add opens the picker"); editor.onBackPressed(); editor.getWindow().getDecorView().findViewWithTag("widget-edit-" + inner).performClick(); });
            capture(editor, "full-placement");
            checkPlacementLayout(editor);
            main(() -> require(editor.getWindow().getDecorView().findViewWithTag("widget-remove") != null, "existing widget has an accessible remove action beside Apply"));
            require(bridge.items(outer).equals(saved) && java.util.Arrays.equals(bridge.host().getAppWidgetIds(), identities), "browsing full card and placement neither reallocates nor commits");
        } finally { main(editor::finish); }
    }
    private void checkPlacementLayout(Activity editor) {
        main(() -> {
            View root = editor.getWindow().getDecorView(), confirm = root.findViewWithTag("widget-place-confirm"), preview = root.findViewWithTag("widget-placement-preview"), controls = root.findViewWithTag("widget-placement-controls");
            require(confirm.getWidth() < root.getWidth() / 2, "placement confirmation remains compact");
            View left = root.findViewWithTag("widget-nudge-0"), right = root.findViewWithTag("widget-nudge-1"), up = root.findViewWithTag("widget-nudge-2"), down = root.findViewWithTag("widget-nudge-3");
            require(confirm.getParent() == up.getParent() && up.getBottom() <= confirm.getTop() && down.getTop() >= confirm.getBottom() && left.getRight() <= confirm.getLeft() && right.getLeft() >= confirm.getRight(), "movement buttons form a cross with Apply or Add in the center");
            if (editor.getResources().getConfiguration().fontScale <= 1) {
                require(controls.getLeft() >= preview.getRight(), "placement controls sit to the right of preview");
                android.widget.ScrollView scroll = root.findViewWithTag("widget-scroll"); require(scroll.getChildAt(0).getHeight() <= scroll.getHeight(), "normal-font placement fits one page without scrolling");
            }
        });
    }
    private void addInEditor(Activity editor) { addInEditor(editor, false); }
    private void addInEditor(Activity editor, boolean cancelled) {
        main(() -> { View add = editor.getWindow().getDecorView().findViewWithTag("widget-open-picker"); require(add != null && add.isEnabled(), "more than one item can be added"); add.performClick(); });
        main(() -> { android.widget.EditText search = editor.getWindow().getDecorView().findViewWithTag("widget-search"); require(search != null, "search available"); search.setText(test.getContext().getPackageName()); });
        String component = new ComponentName(test.getContext().getPackageName(), WidgetFixtureProvider.class.getName()).flattenToString();
        until(() -> editor.getWindow().getDecorView().findViewWithTag("widget-add-" + component) != null, "grouped picker asynchronously loads fixture");
        capture(editor, "picker");
        main(() -> {
            View root = editor.getWindow().getDecorView(); require(root.findViewWithTag("widget-group-" + test.getContext().getPackageName()) != null, "picker groups providers by app"); require(text(root, "2×2 · 占4格") != null, "picker labels dimensions and occupied cells"); require(text(root, "应用提供的预览") != null || text(root, "仍可查看尺寸并尝试添加") != null, "picker offers provider preview status");
            root.findViewWithTag("widget-add-" + component).performClick(); View confirm = editor.getWindow().getDecorView().findViewWithTag("widget-place-confirm"); require(confirm != null && confirm.isEnabled(), "placement finds free cells");
        });
        capture(editor, "placement");
        checkPlacementLayout(editor);
        main(() -> {
            View root = editor.getWindow().getDecorView(), confirm = root.findViewWithTag("widget-place-confirm");
            confirm.performClick();
        });
        if (cancelled) until(() -> text(editor.getWindow().getDecorView(), "已取消本次授权，原布局保持不变") != null, "cancelled system bind returns safely");
        else until(() -> editor.getWindow().getDecorView().findViewWithTag("widget-open-picker") != null, "binding returns to editable grid");
    }
    private void checkConfigureResult(Context context, int targetDisplay) {
        AtomicInteger result = new AtomicInteger(99), returnedId = new AtomicInteger(-1);
        android.content.BroadcastReceiver receiver = new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent intent) { result.set(intent.getIntExtra("result", 99)); returnedId.set(intent.getIntExtra("outer", -1)); }
        };
        context.registerReceiver(receiver, new android.content.IntentFilter("fixture.CONFIG_RESULT"), Context.RECEIVER_EXPORTED);
        Instrumentation.ActivityMonitor monitor = test.addMonitor(NativeWidgetActivity.class.getName(), null, false);
        try {
            context.startActivity(new Intent().setComponent(new ComponentName(test.getContext().getPackageName(), WidgetResultActivity.class.getName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("target_package", context.getPackageName()).putExtra("outer", outer));
            Activity first = monitor.waitForActivityWithTimeout(5000); require(first != null, "native host configure caller opens activity");
            main(() -> { TextView button = text(first.getWindow().getDecorView(), "在所选外屏继续"); require(button != null, "main-screen configure offers selected-cover continuation"); button.performClick(); });
            Activity cover = monitor.waitForActivityWithTimeout(5000); require(cover != null && cover != first, "configuration moved to a new external activity");
            test.waitForIdleSync(); require(cover.getDisplay().getDisplayId() == targetDisplay, "configuration uses selected secondary display");
            require(result.get() == 99, "move does not report cancellation to native caller");
            addInEditor(cover);
            main(cover::recreate);
            final Activity editor = monitor.waitForActivityWithTimeout(5000); require(editor != null && editor != cover, "editor recreation retains draft and owner");
            test.waitForIdleSync();
            require(result.get() == 99 && bridge.items(outer).isEmpty(), "adding stays in draft and does not finish native configure");
            addInEditor(editor);
            main(() -> {
                View done = editor.getWindow().getDecorView().findViewWithTag("widget-done"); require(done != null, "Done remains reachable"); done.performClick();
            });
            until(() -> result.get() != 99, "configure result returns through forwarded chain");
            require(result.get() == Activity.RESULT_OK && returnedId.get() == outer, "native caller receives success and original outer ID");
            require(bridge.items(outer).size() == 2 && WidgetGrid.valid(bridge.items(outer)), "editor saves two independent widgets with nonoverlapping positions");
        } finally { test.removeMonitor(monitor); context.unregisterReceiver(receiver); }
    }
}
