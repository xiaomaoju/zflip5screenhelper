package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Disposable-emulator UI check; deliberately never operates Shizuku or a real overlay. */
public final class UiSmokeInstrumentation extends Instrumentation {
    private Activity activity;
    private int assertions;
    private String scenario = "suite";
    private int expectedRotation = -1;
    private final org.json.JSONArray measurements = new org.json.JSONArray();
    @Override public void runOnMainSync(Runnable action) {
        Throwable[] failure = {null}; super.runOnMainSync(() -> { try { action.run(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] instanceof Error error) throw error;
        if (failure[0] instanceof RuntimeException error) throw error;
        if (failure[0] != null) throw new AssertionError("Main-thread test action failed", failure[0]);
    }
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); if (arguments != null) { scenario = arguments.getString("scenario", "suite"); expectedRotation = Integer.parseInt(arguments.getString("rotation", "-1")); } start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            if (!android.os.Build.HARDWARE.equals("ranchu") && !android.os.Build.HARDWARE.equals("goldfish")) throw new IllegalStateException("Run only on a disposable Android emulator");
            if (scenario.equals("configuration")) { result.putString("result", new ConfigurationChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("runtime-geometry")) { result.putString("result", new RuntimeGeometryChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("runtime-feedback")) { result.putString("result", new RuntimeFeedbackChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("oneui-settings")) { result.putString("result", new SettingsUiChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("shortcut-editor")) { result.putString("result", new SettingsOrderChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("control-grid") || scenario.equals("control-grid-layout")) { if (expectedRotation >= 0) require(getUiAutomation().setRotation(expectedRotation), "grid rotation accepted"); result.putString("result", new ControlGridChecks(this).run(scenario.equals("control-grid-layout"))); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("shortcut-editor-layout")) { if (expectedRotation >= 0) require(getUiAutomation().setRotation(expectedRotation), "editor layout rotation accepted"); result.putString("result", new SettingsOrderChecks(this).runLayout()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("app-workspace")) { result.putString("result", new AppWorkspaceChecks(this, expectedRotation).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("app-folders")) { result.putString("result", new AppFolderChecks(this, expectedRotation).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("folder-layout")) { result.putString("result", new AppFolderChecks(this, expectedRotation).runLayout()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("folder-tools")) { result.putString("result", new AppFolderChecks(this, expectedRotation).runTools()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("workspace-dock-menu")) { result.putString("result", new AppFolderChecks(this, expectedRotation).runDockMenu()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("native-widgets-narrow")) { result.putString("result", new NativeWidgetChecks(this, 640, 748).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("native-widgets-wide")) { result.putString("result", new NativeWidgetChecks(this, 800, 748).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("native-widgets-landscape")) { result.putString("result", new NativeWidgetChecks(this, 748, 640).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("native-widgets")) { result.putString("result", new NativeWidgetChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("launcher-widget")) { result.putString("result", new LauncherWidgetChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("compact-restore")) { checkCompactRestore(); result.putString("result", "PASS: compact restoration; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("panel-entry")) { result.putString("result", new PanelEntryChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("dock-input")) { result.putString("result", new DockInputChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("standalone-dock")) { result.putString("result", new AppDockChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("hub-motion")) { result.putString("result", new HubMotionChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("hub-window")) { result.putString("result", new HubWindowChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("recent-tasks")) { if (expectedRotation >= 0) require(getUiAutomation().setRotation(expectedRotation), "task page rotation request accepted"); result.putString("result", new RecentTasksChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("readability")) { if (expectedRotation >= 0) require(getUiAutomation().setRotation(expectedRotation), "readability rotation request accepted"); result.putString("result", new ReadabilityChecks(this, expectedRotation).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("motion-continuity")) { result.putString("result", new MotionContinuityChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("blur-policy")) { checkBlurPolicy(); result.putString("result", "PASS: blur policy; " + assertions + " assertions; component hardware rendering, not Samsung overlay validation"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("brightness-flow")) { result.putString("result", BrightnessChecks.run(this)); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("status-safe-area")) { result.putString("result", StatusSafeAreaChecks.run(this)); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("panel-settings")) { result.putString("result", "PASS: panel settings; " + PanelSettingsChecks.run(getTargetContext()) + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("panel-layout")) { result.putString("result", PanelLayoutChecks.run(this)); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("panel-features")) { checkPanelFeatures(); result.putString("result", "PASS: panel feature UI and configuration; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("system-controls")) { checkSystemControls(); result.putString("result", "PASS: system control center UI and migration; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("system-controls-toast")) { checkSystemControlToast(); result.putString("result", "PASS: native system control Toast; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("settings-personalization")) { Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().commit(); open("main"); checkPersonalization(prefs); result.putString("result", "PASS: settings personalization and configuration; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("control-settings")) { checkControlSettings(); result.putString("result", "PASS: control settings; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("hub-performance")) { result.putString("result", new HubPerformanceChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("notification-center")) { result.putString("result", new NotificationCenterChecks(this).run()); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("notification-regression")) { checkNotificationUpdates(new Prefs(getTargetContext())); checkPanelSurface(); result.putString("result", "PASS: " + assertions + " existing notification and panel gesture assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("control-reference")) { checkMediaCard(); result.putString("result", "PASS: control reference layout; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("media-card")) { checkMediaCard(); checkPanelSurface(); result.putString("result", "PASS: media and gestures; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (scenario.equals("details")) { checkDetails(); result.putString("result", "PASS: details; " + assertions + " assertions"); finish(Activity.RESULT_OK, result); return; }
            if (!scenario.equals("suite")) { if (scenario.equals("hub-dock")) { Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().commit(); open("main"); checkHub(prefs); checkHubDock(prefs); } else if (scenario.equals("density-audit")) checkDensityAudit(); else checkAvailabilityScenario(); result.putString("result", "PASS: " + scenario + "; " + assertions + " assertions on disposable emulator"); finish(Activity.RESULT_OK, result); return; }
            Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().putInt("per_page", 4).commit();
            open("main"); screenshot("settings"); checkSettings();
            for (int count = 2; count <= 5; count++) {
                prefs.data.edit().putInt("per_page", count).commit(); open("dock");
                DockView dock = findDock(activity.getWindow().getDecorView()); require(dock != null, "dock preview exists");
                View fixed = dock.findViewWithTag("fixed-action"); require(fixed != null, "fixed button exists");
                require(prefs.perPage() == count, "configured count is not silently reduced");
                require(Math.abs(fixed.getWidth() - dock.getWidth() / count) <= 1, "fixed key occupies one of total slots");
                float fixedX = fixed.getX(), fixedY = fixed.getY();
                swipe(dock, false); waitForIdleSync(); SystemClock.sleep(220);
                require(dock.page() == 1, "paging advances for count " + count);
                require(fixed.getX() == fixedX && fixed.getY() == fixedY, "fixed key does not move");
                swipe(dock, true); waitForIdleSync();
                require(dock.page() == 2, "swipe on fixed key pages while the fixed key stays in place");
                screenshot("dock-" + count);
            }
            prefs.pin("home"); require(prefs.pinnedAction().equals("home"), "fixed action can be changed");
            require(!prefs.scrollingActions().contains("home") && prefs.scrollingActions().contains("app_hub"), "old fixed action moves into pager"); prefs.pin("app_hub");
            prefs.data.edit().putInt("per_page", 4).commit();
            open("main");
            runOnMainSync(this::checkSlider);
            checkPullGestures(prefs);
            checkPhysicalEdgePull(prefs);
            checkHandleHold(prefs);
            runOnMainSync(this::checkPanelHeader);
            checkLayoutRestore(prefs);
            checkPersonalization(prefs);
            checkCoverPolish(prefs);
            checkNotificationUpdates(prefs);
            for (String page : new String[]{"controls", "rotation", "notifications", "media"}) { renderPanel(page, prefs); screenshot(page); }
            checkHub(prefs);
            open("status"); screenshot("status-settings");
            open("visibility"); screenshot("visibility-settings");
            open("gestures"); screenshot("gesture-settings");
            open("layout_backup"); screenshot("layout-settings");
            open("about"); require(findText(activity.getWindow().getDecorView(), "版本 " + BuildConfig.VERSION_NAME) != null, "about shows current build version"); screenshot("about");
            result.putString("result", "PASS: " + assertions + " assertions; screenshots are component previews on Android emulator, not Samsung cover validation");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable failure) { result.putString("failure", android.util.Log.getStackTraceString(failure)); finish(Activity.RESULT_CANCELED, result); }
    }
    private void open(String section) {
        if (activity != null) runOnMainSync(() -> activity.finish());
        Intent intent = new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", section);
        activity = startActivitySync(intent); waitForIdleSync(); SystemClock.sleep(250);
    }
    private CoverService previewOwner;
    private void clickPanelSetting(String tag) {
        Throwable[] failure = {null};
        runOnMainSync(() -> { try { View view = activity.findViewById(android.R.id.content).findViewWithTag(tag); require(view != null && view.isEnabled(), "setting is available: " + tag); view.performClick(); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError("Setting click failed: " + tag, failure[0]);
        waitForIdleSync();
    }
    private void chooseSetting(String tag, String value) { clickPanelSetting(tag); clickPanelSetting("settings-choice-" + value); }
    private void checkPanelFeatures() throws Exception {
        Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().commit(); open("panel");
        require(activity.findViewById(android.R.id.content).findViewWithTag("panel-settings-preview") == null, "preview initially collapsed");
        require(!activity.findViewById(android.R.id.content).findViewWithTag("panel-undo").isEnabled(), "fresh preferences have no undo");
        chooseSetting("panel-preset", "standard"); require(!prefs.hasPanelUndo(), "no-op preset does not create undo");
        chooseSetting("panel-preset", "compact"); require(prefs.panelColumns() == 5 && prefs.panelDensity() == 0 && prefs.panelLabelSize() == 0, "compact preset applies through UI");
        chooseSetting("panel-option-columns", "3"); require(prefs.panelColumns() == 3 && prefs.panelDensity() == 0, "manual columns retain density");
        clickPanelSetting("panel-option-labels"); require(!prefs.panelLabels() && !activity.findViewById(android.R.id.content).findViewWithTag("panel-option-labelSize").isShown(), "hidden names hide only dependent setting");
        clickPanelSetting("panel-option-media"); require(!prefs.panelMedia() && !activity.findViewById(android.R.id.content).findViewWithTag("panel-option-mediaIdle").isShown(), "hidden media hides only dependent idle setting");
        clickPanelSetting("panel-option-brightness"); clickPanelSetting("panel-option-volume");
        clickPanelSetting("panel-preview-toggle");
        View preview = activity.findViewById(android.R.id.content).findViewWithTag("panel-settings-preview");
        require(preview != null && preview.getMeasuredHeight() > 0, "shared dashboard preview has actual measured content");
        require(preview.findViewWithTag("control-brightness") == null && preview.findViewWithTag("control-volume") == null && preview.findViewWithTag("media-card") == null, "preview reflects all modules off");
        screenshot("panel-preview-no-tools");
        org.json.JSONObject custom = prefs.panelSnapshot();
        clickPanelSetting("panel-reset"); clickPanelSetting("settings-confirm"); require(prefs.panelPreset().equals("standard") && prefs.panelMedia() && prefs.panelBrightness() && prefs.panelVolume(), "confirmed reset UI restores local default");
        clickPanelSetting("panel-undo"); require(prefs.panelSnapshot().toString().equals(custom.toString()), "undo UI precisely restores custom layout");
        require(!prefs.hasPanelUndo(), "undo is consumed");
        chooseSetting("panel-preset", "easy"); chooseSetting("panel-option-toolsPosition", "side"); clickPanelSetting("panel-option-media"); clickPanelSetting("panel-option-mediaIdle");
        require(prefs.panelMediaIdle() && prefs.panelToolsPosition().equals("side"), "tool position and idle option saved from UI");
        org.json.JSONObject config = ((MainActivity) activity).exportConfigurationData(), expected = prefs.panelSnapshot();
        require(config.getInt("version") == 13 && config.getJSONObject("layout").getInt("version") == 9, "configuration and layout schemas are current");
        prefs.resetPanelSettings(); ((MainActivity) activity).applyConfigurationData(config);
        require(prefs.panelSnapshot().toString().equals(expected.toString()) && !prefs.hasPanelUndo(), "configuration restores every option and invalidates local undo");
        for (int version = 1; version <= 8; version++) {
            org.json.JSONObject legacy = new org.json.JSONObject(config.toString()).put("version", version);
            if (version < 4) legacy.remove("layout");
            else { org.json.JSONObject layout = legacy.getJSONObject("layout"); layout.put("version", version == 8 ? 5 : 4); layout.remove("panelOptions"); if (version < 8) layout.remove("panelColumns"); }
            prefs.applyPanelSettings(expected); ((MainActivity) activity).applyConfigurationData(legacy);
            require(prefs.panelColumns() == (version == 8 ? expected.getInt("columns") : 4), "legacy columns preserved according to schema v" + version);
            require(prefs.panelDensity() == 1 && prefs.panelLabels() && prefs.panelLabelSize() == 1 && prefs.panelToolsPosition().equals("auto") && prefs.panelBrightness() && prefs.panelVolume() && prefs.panelMedia() && !prefs.panelMediaIdle(), "legacy new options use stable defaults v" + version);
        }
        for (Object invalid : new Object[]{"false", 0, org.json.JSONObject.NULL}) {
            org.json.JSONObject bad = new org.json.JSONObject(config.toString()); bad.getJSONObject("layout").getJSONObject("panelOptions").put("media", invalid);
            java.util.Map<String, ?> before = prefs.data.getAll(); boolean rejected = false;
            try { ((MainActivity) activity).applyConfigurationData(bad); } catch (IllegalArgumentException | org.json.JSONException error) { rejected = true; }
            require(rejected && before.equals(prefs.data.getAll()), "invalid nested options reject full configuration atomically");
        }
        prefs.resetPanelSettings(); open("panel"); clickPanelSetting("panel-preview-toggle"); screenshot("panel-settings-preview-default");
        View previewDefault = activity.findViewById(android.R.id.content).findViewWithTag("panel-settings-preview");
        require(previewDefault.findViewWithTag("media-card") != null && !previewDefault.findViewWithTag("control-volume").isEnabled(), "default preview displays media and disables volume");
        clickPanelSetting("panel-preview-toggle"); require(activity.findViewById(android.R.id.content).findViewWithTag("panel-settings-preview") == null, "preview collapses and removes content"); screenshot("panel-settings-default");
    }
    private void checkControlSettings() throws Exception {
        if (expectedRotation >= 0) require(getUiAutomation().setRotation(expectedRotation), "emulator rotation request accepted");
        Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().commit(); open("panel");
        if (expectedRotation >= 0) {
            // Android may finish the launcher rotation transition after startActivitySync returns.
            require(getUiAutomation().setRotation(expectedRotation), "rotation requested after settings is visible");
            for (int i = 0; i < 50 && activity.getDisplay().getRotation() != expectedRotation; i++) SystemClock.sleep(100);
            waitForIdleSync(); if (activity.isDestroyed()) open("panel");
        }
        require(prefs.panelColumns() == 4, "default is four columns independent of rotation");
        require(expectedRotation < 0 || activity.getDisplay().getRotation() == expectedRotation, "native display uses requested test rotation " + expectedRotation + ", actual " + activity.getDisplay().getRotation());
        chooseSetting("panel-option-columns", "5");
        require(prefs.panelColumns() == 5 && findText(activity.findViewById(android.R.id.content).findViewWithTag("panel-option-columns"), "5 列") != null, "setting saves five columns and shows selection"); screenshot("control-settings");
        org.json.JSONObject config = ((MainActivity) activity).exportConfigurationData();
        require(config.getInt("version") == 13 && config.getJSONObject("layout").getInt("panelColumns") == 5, "export includes column count");
        prefs.data.edit().putInt("panel_columns", 3).commit(); ((MainActivity) activity).applyConfigurationData(config); require(prefs.panelColumns() == 5, "full configuration restores columns");
        for (Object invalid : new Object[]{2, 6, 3.5, "4"}) {
            java.util.Map<String, ?> before = prefs.data.getAll(); org.json.JSONObject bad = prefs.layoutSnapshot().put("panelColumns", invalid); boolean rejected = false;
            try { prefs.prepareLayout(bad, prefs.data.edit()).commit(); } catch (IllegalArgumentException expected) { rejected = true; }
            require(rejected && before.equals(prefs.data.getAll()), "invalid column count rejected atomically: " + invalid);
        }
        org.json.JSONObject legacy = prefs.layoutSnapshot().put("version", 4); legacy.remove("panelColumns"); prefs.prepareLayout(legacy, prefs.data.edit()).commit(); require(prefs.panelColumns() == 4, "legacy layout uses unified four-column default");
        for (int version = 1; version <= 7; version++) {
            org.json.JSONObject oldConfig = new org.json.JSONObject(config.toString()).put("version", version);
            if (version < 4) oldConfig.remove("layout");
            else { oldConfig.getJSONObject("layout").put("version", 4).remove("panelColumns"); }
            prefs.data.edit().putInt("panel_columns", 5).commit(); ((MainActivity) activity).applyConfigurationData(oldConfig);
            require(prefs.panelColumns() == 4, "legacy configuration " + version + " defaults to four columns");
        }
        prefs.data.edit().putInt("panel_columns", 5).commit(); prefs.saveLayout(); prefs.data.edit().putInt("panel_columns", 3).commit(); prefs.restoreLayout(false); require(prefs.panelColumns() == 5, "backup includes column count"); prefs.restoreLayout(true); require(prefs.panelColumns() == 3, "column restore can be undone");
        for (String hand : new String[]{"left", "right"}) for (int columns : new int[]{3, 4, 5}) {
            prefs.data.edit().putString("hand_side", hand).putInt("panel_columns", columns).commit(); renderPanel("controls", prefs);
            require(expectedRotation < 0 || activity.getDisplay().getRotation() == expectedRotation, "display retains requested rotation throughout layout checks");
            runOnMainSync(() -> {
                ViewGroup dashboard = activity.findViewById(android.R.id.content).findViewWithTag("control-dashboard"); int firstRow = 0, count = prefs.actions("panel").size();
                for (int i = 0; i < count; i++) {
                    ViewGroup tile = (ViewGroup) dashboard.getChildAt(i); if (tile.getTop() == 0) firstRow++;
                    require(tile.getLeft() >= 0 && tile.getRight() <= dashboard.getWidth() && tile.getBottom() <= dashboard.getHeight(), "tile stays inside layout");
                    ViewGroup face = (ViewGroup) tile.getChildAt(0); View symbol = face.getChildAt(0);
                    require(symbol.getLeft() >= 0 && symbol.getTop() >= 0 && symbol.getRight() <= face.getWidth() && symbol.getBottom() <= face.getHeight(), "adaptive icon is not clipped");
                    View marker = face.getChildAt(1);
                    if (marker.getVisibility() == View.VISIBLE) {
                        android.graphics.Rect mark = new android.graphics.Rect(), glyph = new android.graphics.Rect(); marker.getHitRect(mark); symbol.getHitRect(glyph);
                        require(Math.abs(glyph.left + glyph.right - face.getWidth()) <= 1 && Math.abs(glyph.top + glyph.bottom - face.getHeight()) <= 1, "unknown-state marker never shifts the centered icon");
                        require(mark.left >= 0 && mark.top >= 0 && mark.right <= face.getWidth() && mark.bottom <= face.getHeight() && mark.centerX() > face.getWidth() / 2f && mark.centerY() < face.getHeight() / 2f, "unknown-state marker remains contained in the upper-right corner");
                    }
                    require(tile.getChildAt(1).getBottom() <= tile.getHeight(), "label fits allocated row");
                }
                require(firstRow == columns, "requested columns kept on rotation " + activity.getDisplay().getRotation() + ": " + columns);
                View media = dashboard.findViewWithTag("media-card"), sliders = dashboard.findViewWithTag("control-sliders"); android.graphics.Rect a = new android.graphics.Rect(), b = new android.graphics.Rect(); media.getHitRect(a); sliders.getHitRect(b);
                require(!android.graphics.Rect.intersects(a, b), "media and sliders do not overlap"); if (((MediaCardView) media).condensed()) require(media.getHeight() == ((MediaCardView) media).minimumCardHeight(media.getWidth()), "permission entry uses natural compact height"); else require(media.getBottom() == dashboard.getHeight(), "no empty reserved footer");
                if (((MediaCardView) media).condensed()) require(media.findViewWithTag("media-transport").getVisibility() == View.GONE && media.findViewWithTag("media-artist").getBottom() <= media.getHeight() - media.getPaddingBottom(), "permission entry fits without hidden playback area"); else require(media.findViewWithTag("media-transport").getBottom() <= media.getHeight() - media.getPaddingBottom(), "playback keys fit media card");
            }); screenshot("controls-" + hand + "-" + columns);
        }
        runOnMainSync(() -> {
            try {
                prefs.data.edit().putString("chrome_style", "light").putBoolean("battery_percent", true).commit();
                for (int scale : new int[]{50, 70, 150}) {
                    prefs.data.edit().putInt("status_scale", scale).commit(); StatusBarView normal = new StatusBarView(activity, prefs), panel = new StatusBarView(activity, prefs, true);
                    for (StatusBarView view : new StatusBarView[]{normal, panel}) {
                        for (String key : new String[]{"clock", "battery", "wifi", "charging"}) { java.lang.reflect.Field field = StatusBarView.class.getDeclaredField(key); field.setAccessible(true); field.set(view, key.equals("clock") ? "09:41" : key.equals("battery") ? 86 : true); }
                        view.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(view.heightPixels(), View.MeasureSpec.EXACTLY)); view.layout(0, 0, 720, view.heightPixels());
                    }
                    Bitmap a = Bitmap.createBitmap(720, normal.getHeight(), Bitmap.Config.ARGB_8888), b = Bitmap.createBitmap(720, panel.getHeight(), Bitmap.Config.ARGB_8888); normal.draw(new android.graphics.Canvas(a)); panel.draw(new android.graphics.Canvas(b));
                    require(a.sameAs(b), "panel and normal status use identical layout at scale " + scale); a.recycle(); b.recycle();
                    prefs.data.edit().putBoolean("battery_percent", false).commit(); require(!normal.showsPercentage() && panel.showsPercentage(), "control panel preserves percentage on right independently"); prefs.data.edit().putBoolean("battery_percent", true).commit();
                }
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
        prefs.data.edit().remove("hand_side").putInt("status_scale", 70).putBoolean("battery_percent", false).putString("chrome_style", "contrast").commit(); prefs.resetLayout(); require(prefs.panelColumns() == 4, "reset restores four columns");
        renderPanel("controls", prefs); screenshot("controls-status-fixed");
        StatusBarView panelStatus = activity.findViewById(android.R.id.content).findViewWithTag("panel-status");
        java.lang.reflect.Field iconsField = StatusBarView.class.getDeclaredField("notificationIcons"); iconsField.setAccessible(true);
        android.service.notification.StatusBarNotification item = sampleNotification(900, "状态栏更新样例", 1000);
        CoverNotifications listener = new CoverNotifications();
        try {
            runOnMainSync(() -> { listener.onNotificationPosted(item); previewOwner.notificationsChanged(); }); SystemClock.sleep(300); waitForIdleSync();
            require(((List<?>) iconsField.get(panelStatus)).size() == 1, "open control panel receives new notification icon");
            require(activity.findViewById(android.R.id.content).findViewWithTag("panel-status") == panelStatus, "notification update keeps existing panel status view");
        } finally { runOnMainSync(() -> { listener.onNotificationRemoved(item); previewOwner.notificationsChanged(); }); }
        SystemClock.sleep(300); waitForIdleSync(); require(((List<?>) iconsField.get(panelStatus)).isEmpty(), "notification removal updates control panel icon in place");
    }

    private void checkMediaCard() throws Exception {
        Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().commit(); open("main");
        getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
        android.media.session.MediaSession[] fixture = {null}, second = {null}; int[] pauses = {0}, next = {0}; long[] seek = {-1};
        long actions = android.media.session.PlaybackState.ACTION_PLAY | android.media.session.PlaybackState.ACTION_PAUSE | android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT | android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS | android.media.session.PlaybackState.ACTION_SEEK_TO;
        try {
            runOnMainSync(() -> {
                fixture[0] = new android.media.session.MediaSession(activity, "media-card-fixture");
                fixture[0].setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
                Bitmap art = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888); android.graphics.Canvas canvas = new android.graphics.Canvas(art); canvas.drawColor(0xFF254D54); android.graphics.Paint paint = new android.graphics.Paint(); paint.setColor(0xFF9EC7CE); canvas.drawCircle(32, 32, 19, paint);
                fixture[0].setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "日落以后 · 本地测试").putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "演示播放器").putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 210000).putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, art).build());
                fixture[0].setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(actions).setState(android.media.session.PlaybackState.STATE_PLAYING, 32000, 1).build());
                fixture[0].setCallback(new android.media.session.MediaSession.Callback() {
                    @Override public void onPause() { pauses[0]++; fixture[0].setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(actions).setState(android.media.session.PlaybackState.STATE_PAUSED, 34000, 0).build()); }
                    @Override public void onSkipToNext() { next[0]++; }
                    @Override public void onSeekTo(long position) { seek[0] = position; }
                }); fixture[0].setActive(true);
            });
            for (int columns : new int[]{3, 4, 5}) {
                prefs.data.edit().putInt("panel_columns", columns).commit(); renderPanel("controls", prefs); waitForIdleSync();
                runOnMainSync(() -> {
                    ViewGroup dashboard = activity.findViewById(android.R.id.content).findViewWithTag("control-dashboard");
                    View sliders = dashboard.findViewWithTag("control-sliders"), media = dashboard.findViewWithTag("media-card");
                    require(sliders.getTop() == 0 && media.getTop() > sliders.getBottom(), "reference layout keeps twin sliders above the media card");
                    require(sliders.getLeft() == media.getLeft() && sliders.getRight() == media.getRight(), "sliders and media card share column edges");
                    int firstRow = 0;
                    for (String id : prefs.actions("panel")) {
                        ViewGroup tile = dashboard.findViewWithTag("control-" + id); if (tile.getTop() == 0) firstRow++; require(tile.getRight() <= sliders.getLeft(), "icons fit next to the tool column");
                        ViewGroup face = (ViewGroup) tile.getChildAt(0); View icon = face.getChildAt(0);
                        require(Math.abs(icon.getLeft() + icon.getRight() - face.getWidth()) <= 1 && Math.abs(icon.getTop() + icon.getBottom() - face.getHeight()) <= 1, "main icon is centered with or without a state badge");
                    }
                    require(firstRow == columns && prefs.panelColumns() == columns, "available space scales icons without changing selected columns");
                }); screenshot("control-reference-" + columns);
            }
            if (scenario.equals("control-reference")) {
                runOnMainSync(() -> { activity.setContentView(new FrameLayout(activity)); require(previewOwner.mediaSessions().observerCount() == 0, "reference capture releases its media subscription"); }); return;
            }
            prefs.data.edit().putInt("panel_columns", 4).commit();
            renderPanel("controls", prefs); checkControlSpace(prefs); waitForIdleSync(); SystemClock.sleep(300);
            runOnMainSync(() -> {
                View card = activity.findViewById(android.R.id.content).findViewWithTag("media-card");
                require(((TextView) card.findViewWithTag("media-title")).getText().toString().contains("日落以后"), "media title comes from native session");
                require(card.findViewWithTag("media-play").getContentDescription().toString().equals("暂停"), "playing session exposes pause");
                require(previewOwner.mediaSessions().observerCount() == 1, "only visible compact card subscribes");
                card.findViewWithTag("media-play").performClick();
            }); waitForIdleSync(); SystemClock.sleep(160);
            require(pauses[0] == 1, "pause targets selected real session once");
            runOnMainSync(() -> {
                View card = activity.findViewById(android.R.id.content).findViewWithTag("media-card"); require(card.findViewWithTag("media-play").getContentDescription().toString().equals("播放"), "pause callback updates compact card");
                card.findViewWithTag("media-next").performClick(); card.performLongClick();
            }); waitForIdleSync(); SystemClock.sleep(300); require(next[0] == 1 && pauses[0] == 1, "long press opens detail without playback command"); screenshot("detail-media-card");
            runOnMainSync(() -> {
                View root = activity.findViewById(android.R.id.content); require(root.findViewWithTag("media-detail") != null, "long press creates expanded detail");
                require(root.findViewWithTag("media-progress").isEnabled(), "seek enabled only for advertised capability");
                View expanded = root.findViewWithTag("media-expanded"), timeline = expanded.findViewWithTag("media-timeline"), transport = expanded.findViewWithTag("media-transport");
                require(timeline.getBottom() <= transport.getTop(), "timeline precedes transport without overlapping");
                ScrollView detailScroll = root.findViewWithTag("detail-scroll");
                if (activity.getResources().getConfiguration().fontScale <= 1.3f) require(detailScroll.getChildAt(0).getHeight() <= detailScroll.getHeight(), "single-player detail fits viewport by shrinking artwork first");
                else { detailScroll.scrollTo(0, detailScroll.getChildAt(0).getHeight()); android.graphics.Rect visible = new android.graphics.Rect(); require(transport.getGlobalVisibleRect(visible) && visible.height() == transport.getHeight() && visible.width() == transport.getWidth(), "large-font playback controls remain fully reachable by scrolling"); detailScroll.scrollTo(0, 0); }
                require(((TextView) expanded.findViewWithTag("media-title")).getMaxLines() == 1, "long song title remains a single ellipsized line");
                require(findText(root.findViewWithTag("detail-sheet"), "打开播放器") == null, "open-player action no longer occupies text footer");
                require(previewOwner.mediaSessions().observerCount() == 3, "detail and artwork views share session subscription owner");
                previewOwner.mediaSessions().seek(500000);
            }); waitForIdleSync(); SystemClock.sleep(120); require(seek[0] == 210000, "seek clamps to real duration");
            runOnMainSync(() -> {
                second[0] = new android.media.session.MediaSession(activity, "second-fixture"); second[0].setMetadata(new android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "直播测试").build());
                second[0].setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(0).setState(android.media.session.PlaybackState.STATE_PAUSED, 0, 0).build()); second[0].setActive(true);
            }); waitForIdleSync(); SystemClock.sleep(180);
            runOnMainSync(() -> {
                previewOwner.mediaSessions().refresh(); require(previewOwner.mediaSessions().players().size() == 2, "two actual sessions are discoverable"); previewOwner.mediaSessions().select(second[0].getSessionToken());
                View root = activity.findViewById(android.R.id.content); require(root.findViewWithTag("media-progress").getVisibility() == View.GONE, "unknown live duration has no fabricated progress");
                require(!root.findViewWithTag("media-play").isEnabled(), "unsupported playback action disabled");
                require(((LinearLayout) root.findViewWithTag("media-players")).getChildCount() >= 2, "multiple players have selection rows");
                require(root.findViewWithTag("media-players").getVisibility() == View.GONE, "player list does not consume space until requested");
                root.findViewWithTag("media-player-picker").performClick(); require(root.findViewWithTag("media-players").getVisibility() == View.VISIBLE, "toolbar reveals player selection");
                previewOwner.dismissDetails(); require(previewOwner.mediaSessions().observerCount() == 1, "closing detail releases observers");
                second[0].release(); second[0] = null; fixture[0].release(); fixture[0] = null;
            }); waitForIdleSync(); SystemClock.sleep(180);
            runOnMainSync(() -> { previewOwner.mediaSessions().refresh(); require(!previewOwner.mediaSessions().info().available(), "session removal leaves empty honest state"); activity.setContentView(new FrameLayout(activity)); require(previewOwner.mediaSessions().observerCount() == 0, "last surface detach releases all media callbacks"); });
        } finally { runOnMainSync(() -> { if (fixture[0] != null) fixture[0].release(); if (second[0] != null) second[0].release(); }); getUiAutomation().dropShellPermissionIdentity(); }
        renderPanel("controls", prefs);
        runOnMainSync(() -> require(!previewOwner.mediaSessions().info().permission(), "missing permission is represented explicitly")); screenshot("detail-media-empty");
        // Pulling the same already-open page cannot reconstruct or reset its state.
        runOnMainSync(() -> {
            try { View before = activity.findViewById(android.R.id.content).findViewWithTag("panel-surface"); java.lang.reflect.Method pull = CoverService.class.getDeclaredMethod("pullPanel", String.class, float.class); pull.setAccessible(true); pull.invoke(previewOwner, "controls", 20f); require(before.getTranslationY() == 0 && before.findViewWithTag("media-card") != null, "repeated pull of open panel remains stable"); } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        });
    }
    private void checkPanelSurface() {
        for (int edge = 0; edge < 4; edge++) {
            final int currentEdge = edge; int[] begun = {0}, closed = {0}; float[] progress = {1}; PanelSurface[] surface = {null};
            runOnMainSync(() -> {
                surface[0] = new PanelSurface(activity, currentEdge, 400, false, new PanelHeaderView.Listener() { public void begin() { begun[0]++; } public void progress(float value) { progress[0] = value; } public void finish(boolean close) { if (close) closed[0]++; } });
                surface[0].setBackgroundColor(Ui.BACKGROUND); activity.setContentView(surface[0]);
            }); waitForIdleSync();
            runOnMainSync(() -> {
                float x = surface[0].getWidth() / 2f, y = surface[0].getHeight() / 2f; float dx = currentEdge == DockGeometry.LEFT ? -150 : currentEdge == DockGeometry.RIGHT ? 150 : 0, dy = currentEdge == DockGeometry.TOP ? -150 : currentEdge == DockGeometry.BOTTOM ? 150 : 0;
                surfaceSwipe(surface[0], x, y, dx, dy, MotionEvent.ACTION_UP);
                require(begun[0] == 1 && closed[0] == 1 && progress[0] < 1, "blank-space pull follows finger and dismisses at edge " + currentEdge);
                surfaceSwipe(surface[0], x, y, dx, dy, MotionEvent.ACTION_CANCEL); require(closed[0] == 1, "canceled panel pull reopens at edge " + currentEdge);
            });
        }
        int[] begun = {0}, changes = {0}; PanelSurface[] surface = {null}; ScrollView[] scroll = {null};
        runOnMainSync(() -> {
            surface[0] = new PanelSurface(activity, DockGeometry.BOTTOM, 400, false, new PanelHeaderView.Listener() { public void begin() { begun[0]++; } public void progress(float value) { } public void finish(boolean close) { } });
            LevelSlider slider = new LevelSlider(activity, "测试音量", R.drawable.ic_ms_volume_up, 0, 100, 50, value -> changes[0]++); surface[0].addView(slider, new LinearLayout.LayoutParams(120, 200));
            scroll[0] = new ScrollView(activity); LinearLayout content = Ui.column(activity); for (int i = 0; i < 20; i++) { TextView row = Ui.text(activity, "通知 " + i, 16, Ui.TEXT); content.addView(row, new LinearLayout.LayoutParams(-1, 100)); } scroll[0].addView(content); surface[0].addView(scroll[0], new LinearLayout.LayoutParams(-1, 0, 1)); activity.setContentView(surface[0]);
        }); waitForIdleSync();
        runOnMainSync(() -> { surfaceSwipe(surface[0], 60, 20, 0, 100, MotionEvent.ACTION_UP); require(begun[0] == 0 && changes[0] == 1, "volume drag never dismisses panel"); scroll[0].scrollTo(0, 600); }); waitForIdleSync();
        runOnMainSync(() -> { surfaceSwipe(surface[0], 160, 280, 0, 100, MotionEvent.ACTION_UP); require(begun[0] == 0, "scrolled list keeps normal scrolling"); scroll[0].scrollTo(0, 0); }); waitForIdleSync();
        runOnMainSync(() -> { surfaceSwipe(surface[0], 160, 280, 0, 100, MotionEvent.ACTION_UP); require(begun[0] == 1, "list at top hands outward drag to panel"); });
    }
    private void surfaceSwipe(View view, float x, float y, float dx, float dy, int end) {
        long down = SystemClock.uptimeMillis();
        for (int i = 0; i <= 5; i++) { MotionEvent event = MotionEvent.obtain(down, down + i * 45, i == 0 ? MotionEvent.ACTION_DOWN : i == 5 ? end : MotionEvent.ACTION_MOVE, x + dx * i / 5, y + dy * i / 5, 0); view.dispatchTouchEvent(event); event.recycle(); }
    }

    private void checkBlurPolicy() throws Exception {
        Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().putBoolean("panel_blur", true).commit(); open("main"); renderPanel("controls", prefs);
        runOnMainSync(() -> {
            try {
                java.lang.reflect.Method parameters = CoverService.class.getDeclaredMethod("parameters", DockGeometry.Box.class); parameters.setAccessible(true);
                android.view.WindowManager.LayoutParams layout = (android.view.WindowManager.LayoutParams) parameters.invoke(previewOwner, new DockGeometry.Box(0, 0, 720, 748));
                require((layout.flags & android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED) != 0, "overlay requests hardware rendering before mounting");
                java.lang.reflect.Method apply = CoverService.class.getDeclaredMethod("applyPanelBlur", android.view.WindowManager.LayoutParams.class, boolean.class); apply.setAccessible(true);
                java.lang.reflect.Field applied = CoverService.class.getDeclaredField("detailBlurApplied"), panelField = CoverService.class.getDeclaredField("panel"); applied.setAccessible(true); panelField.setAccessible(true);
                View panel = (View) panelField.get(previewOwner); require(panel.isHardwareAccelerated(), "component is mounted in a hardware window");
                boolean allowed = android.os.Build.VERSION.SDK_INT >= 31 && !activity.getSystemService(android.os.PowerManager.class).isPowerSaveMode();
                previewOwner.showDetails("configure", null); require(applied.getBoolean(previewOwner) == allowed, "local detail blur respects power state");
                apply.invoke(previewOwner, layout, false);
                require((layout.flags & android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0, "unsupported cross-window blur is disabled");
                require(applied.getBoolean(previewOwner) == allowed, "cross-window fallback preserves independently supported local blur");
                prefs.data.edit().putBoolean("panel_blur", false).commit(); apply.invoke(previewOwner, layout, true);
                require(!applied.getBoolean(previewOwner) && (layout.flags & android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0, "user effect switch disables both blur layers");
                prefs.data.edit().putBoolean("panel_blur", true).commit(); apply.invoke(previewOwner, layout, true);
                require(((layout.flags & android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0) == allowed, "cross-window blur obeys same power policy");
                require(applied.getBoolean(previewOwner) == allowed, "local blur restores when permitted");
                previewOwner.dismissDetails(); require(!applied.getBoolean(previewOwner), "detail dismissal clears local render effect");
            } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        });
        screenshot("blur-policy-after-dismiss");
    }
    private void checkSystemControlToast() throws Exception {
        Prefs prefs = new Prefs(getTargetContext()); open("permissions");
        require(findText(activity.findViewById(android.R.id.content), "安卓提示（Toast）") != null, "notification permission entry is present");
        boolean allowed = getTargetContext().getSystemService(android.app.NotificationManager.class).areNotificationsEnabled();
        renderPanel("controls", prefs);
        java.lang.reflect.Field toastField = CoverService.class.getDeclaredField("systemControlsToast"); toastField.setAccessible(true);
        runOnMainSync(() -> {
            previewOwner.systemControlsTip("已关闭内外屏控制中心");
            try {
                android.widget.Toast first = (android.widget.Toast) toastField.get(previewOwner);
                if (allowed) {
                    require(first != null && first.getDuration() == android.widget.Toast.LENGTH_LONG && first.getView() == null, "standard long text Toast is used without custom view");
                    previewOwner.systemControlsTip("已开启内外屏控制中心");
                    require(toastField.get(previewOwner) != first, "new result replaces prior Toast");
                } else {
                    require(first == null, "blocked notification permission never masquerades as a shown Toast");
                    require(findText(activity.findViewById(android.R.id.content), "安卓提示被禁用") != null, "blocked Toast has a visible recovery explanation");
                    previewOwner.showDetails("system_controls", null);
                    require(findText(activity.findViewById(android.R.id.content), "允许安卓提示（Toast）") != null, "long press exposes user-controlled notification settings");
                }
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
        waitForIdleSync(); SystemClock.sleep(400); screenshot(allowed ? "detail-system-toast-native" : "detail-system-toast-blocked");
        runOnMainSync(() -> {
            try { java.lang.reflect.Method remove = CoverService.class.getDeclaredMethod("removeWindows"); remove.setAccessible(true); remove.invoke(previewOwner); require(toastField.get(previewOwner) == null, "window teardown cancels pending Toast"); }
            catch (ReflectiveOperationException error) { throw new AssertionError(error); }
        });
    }
    private void checkSystemControls() throws Exception {
        Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().putString("panel", "[\"wifi\",\"bluetooth\"]").commit();
        prefs = new Prefs(getTargetContext());
        require(prefs.actions("panel").equals(List.of("wifi", "bluetooth", "system_controls")), "upgrade appends switch without reordering controls");
        require(new Prefs(getTargetContext()).actions("panel").size() == 3, "upgrade adds switch only once");
        prefs.saveActions("panel", List.of("wifi")); require(new Prefs(getTargetContext()).actions("panel").equals(List.of("wifi")), "user can remove switch after upgrade");
        prefs.data.edit().remove("panel").commit(); require(prefs.actions("panel").contains("system_controls"), "fresh default contains switch");
        open("main"); renderPanel("controls", prefs);
        runOnMainSync(() -> {
            View tile = activity.findViewById(android.R.id.content).findViewWithTag("control-system_controls"); require(tile != null && tile.isLongClickable(), "switch and long press are available");
            try {
                java.lang.reflect.Field field = CoverService.class.getDeclaredField("panels"); field.setAccessible(true); Panels panels = (Panels) field.get(previewOwner);
                previewOwner.states.put("system_controls", 1); panels.updateStates(); require(tile.isSelected() && tile.getStateDescription().equals("已开启"), "readback enabled state is active");
                previewOwner.states.put("system_controls", 0); panels.updateStates(); require(!tile.isSelected() && tile.getStateDescription().equals("已关闭"), "readback disabled state is inactive");
                previewOwner.states.remove("system_controls"); panels.updateStates(); require(tile.getStateDescription().equals("状态未知"), "unknown state never appears enabled");
                panels.working("system_controls", true); require(!tile.isEnabled() && tile.getStateDescription().equals("正在执行"), "in-flight switch blocks duplicate taps"); panels.working("system_controls", false);
            } catch (ReflectiveOperationException error) { throw new AssertionError(error); }
            require(tile.performLongClick(), "long press consumed without toggling");
        });
        waitForIdleSync(); SystemClock.sleep(300);
        runOnMainSync(() -> {
            DetailSheet sheet = activity.findViewById(android.R.id.content).findViewWithTag("detail-sheet");
            require(sheet != null && findText(sheet, "开启/关闭内外屏控制中心") != null, "requested scope tip appears in detail");
            require(findText(sheet, "开启内外屏控制中心") != null && findText(sheet, "关闭内外屏控制中心") != null, "unknown state offers explicit enable and disable");
            require(findText(sheet, "Shizuku") != null, "permission and lifetime explanation present");
            View card = sheet.findViewWithTag("detail-card"), scroll = sheet.findViewWithTag("detail-scroll");
            require(card.getLeft() >= 0 && card.getTop() >= 0 && card.getRight() <= sheet.getWidth() && card.getBottom() <= sheet.getHeight() && scroll.getHeight() > 0, "detail fits external-screen bounds and scrolls");
        });
        screenshot("system-controls-detail"); runOnMainSync(previewOwner::dismissDetails); screenshot("system-controls-grid");
    }
    private void checkDetails() throws Exception {
        Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().commit(); open("main"); renderPanel("controls", prefs);
        checkControlSpace(prefs);
        for (String id : new String[]{"rotation", "volume", "bluetooth", "data", "dnd", "airplane", "media", "torch", "screenshot", "lock", "apps", "configure", "brightness"}) {
            runOnMainSync(() -> previewOwner.showDetails(id, activity.findViewById(android.R.id.content).findViewWithTag("control-" + id))); waitForIdleSync(); SystemClock.sleep(300);
            runOnMainSync(() -> {
                DetailSheet sheet = activity.findViewById(android.R.id.content).findViewWithTag("detail-sheet"); View card = sheet.findViewWithTag("detail-card"), scroll = sheet.findViewWithTag("detail-scroll");
                require(card.getWidth() <= sheet.getWidth() - Ui.dp(activity, 15), id + " card width respects safe edge");
                require(card.getHeight() <= sheet.getHeight() - Ui.dp(activity, 15), id + " card height respects safe edge");
                require(card.getX() >= 0 && card.getY() >= 0 && card.getRight() <= sheet.getWidth() && card.getBottom() <= sheet.getHeight(), id + " card stays inside safe frame");
                require(scroll.getHeight() > 0, id + " content remains usable");
                Bitmap corners = Bitmap.createBitmap(card.getWidth(), card.getHeight(), Bitmap.Config.ARGB_8888); card.draw(new android.graphics.Canvas(corners));
                require(android.graphics.Color.alpha(corners.getPixel(0, card.getHeight() - 1)) == 0 && android.graphics.Color.alpha(corners.getPixel(card.getWidth() - 1, card.getHeight() - 1)) == 0, id + " lower corners stay rounded in software capture too"); corners.recycle();
                if ((id.equals("torch") || id.equals("configure")) && activity.getResources().getConfiguration().fontScale <= 1.3f) require(card.getHeight() < sheet.getHeight() * .8f, id + " short content does not fill height at ordinary font scales");
            }); screenshot("detail-" + id); runOnMainSync(previewOwner::dismissDetails);
        }
        // Local data feeds the same row/card builders; never starts a Wi-Fi scan or Shizuku.
        DetailSheet[] wifi = {null}; ControlDetails[] builder = {null};
        runOnMainSync(() -> {
            previewOwner.showDetails("configure", null); wifi[0] = activity.findViewById(android.R.id.content).findViewWithTag("detail-sheet"); wifi[0].title.setText("Wi-Fi · 本地样例"); wifi[0].content.removeAllViews(); builder[0] = new ControlDetails(previewOwner, wifi[0]);
            for (String name : new String[]{"家庭网络", "工作室", "咖啡店", "访客网络", "书房网络", "办公室"}) { LinearLayout row = (LinearLayout) builder[0].row(name, "", R.drawable.ic_ms_wifi, () -> { }); row.setMinimumHeight(Ui.dp(activity, 32)); row.setPadding(0, Ui.dp(activity, 4), 0, Ui.dp(activity, 4)); row.addView(Ui.text(activity, "已保存", 10, Ui.MUTED)); }
            wifi[0].footer("更多 WLAN 设置", () -> { });
        }); waitForIdleSync(); screenshot("detail-wifi-fixture"); int[] shortWidth = {0};
        runOnMainSync(() -> { View card = wifi[0].findViewWithTag("detail-card"); shortWidth[0] = card.getWidth(); require(card.getLeft() >= 0 && card.getTop() >= 0 && card.getRight() <= wifi[0].getWidth() && card.getBottom() <= wifi[0].getHeight(), "short network fixture stays inside safe bounds"); if (activity.getResources().getConfiguration().fontScale <= 1.3f) require(shortWidth[0] < wifi[0].getWidth() * .85f, "short network names use content width at ordinary font scales"); wifi[0].content.removeAllViews(); for (int i = 0; i < 12; i++) builder[0].row("会议室访客专用无线网络-LongNetworkName-0123456789-" + i, "由系统确认连接", R.drawable.ic_ms_wifi, () -> { }); });
        waitForIdleSync(); screenshot("detail-wifi-long");
        runOnMainSync(() -> {
            ScrollView scroll = wifi[0].findViewWithTag("detail-scroll"); require(scroll.getChildAt(0).getHeight() > scroll.getHeight(), "long network list scrolls inside bounded card");
            View card = wifi[0].findViewWithTag("detail-card"); require(card.getWidth() >= shortWidth[0] && card.getLeft() >= 0 && card.getTop() >= 0 && card.getRight() <= wifi[0].getWidth() && card.getBottom() <= wifi[0].getHeight(), "long names expand within the safe cap");
            TextView label = findText(wifi[0].content, "会议室访客"); require(label.getLineCount() > 1 && label.getLayout().getHeight() <= label.getHeight() - label.getCompoundPaddingTop() - label.getCompoundPaddingBottom(), "long network wraps without vertical clipping");
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight()); TextView last = findText(wifi[0].content.getChildAt(wifi[0].content.getChildCount() - 1), "由系统确认连接"); android.graphics.Rect visible = new android.graphics.Rect(); require(last != null && last.getGlobalVisibleRect(visible) && visible.width() == last.getWidth() && visible.height() == last.getHeight(), "last network row end remains fully reachable");
            previewOwner.dismissDetails(); builder[0].close();
        });
        renderPanel("controls", prefs);
        LevelSlider[] volume = {null}; String[] old = {null}; long down = SystemClock.uptimeMillis();
        runOnMainSync(() -> { volume[0] = activity.findViewById(android.R.id.content).findViewWithTag("control-volume"); old[0] = volume[0].getContentDescription().toString(); MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, 10, 5, 0); volume[0].dispatchTouchEvent(event); event.recycle(); require(volume[0].getContentDescription().toString().equals(old[0]), "stationary down leaves volume unchanged"); });
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 100); waitForIdleSync();
        runOnMainSync(() -> { MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, 10, 5, 0); volume[0].dispatchTouchEvent(event); event.recycle(); require(activity.findViewById(android.R.id.content).findViewWithTag("detail-sheet") != null, "holding slider opens output"); require(volume[0].getContentDescription().toString().equals(old[0]), "release after hold never changes volume"); previewOwner.dismissDetails(); });
        checkNotificationUpdates(prefs);
    }
    private void checkControlSpace(Prefs prefs) throws Exception {
        List<String> defaults = prefs.actions("panel");
        for (int count : new int[]{1, 3, 6, 11, 18}) {
            List<String> ids = count <= defaults.size() ? defaults.subList(0, count) : ActionCatalog.BUILT_INS.stream().map(ActionCatalog.Action::id).limit(count).toList(); prefs.saveActions("panel", ids); renderPanel("controls", prefs);
            runOnMainSync(() -> {
                View root = activity.findViewById(android.R.id.content); ViewGroup dashboard = root.findViewWithTag("control-dashboard"); ScrollView scroll = find(root.findViewWithTag("panel-surface"), ScrollView.class);
                require(dashboard.getBottom() == ((View) dashboard.getParent()).getHeight(), count + " controls leave no reserved footer band");
                require(((View) dashboard.getParent()).getHeight() >= scroll.getHeight(), count + " control surface uses viewport height");
                if (count <= 11 && activity.getResources().getConfiguration().fontScale <= 1.05f && ((View) root.findViewWithTag("control-sliders")).getTop() == 0) require(dashboard.getHeight() <= scroll.getHeight(), count + " default controls fit without cutting off last row: dashboard=" + dashboard.getHeight() + " viewport=" + scroll.getHeight());
                View sliders = root.findViewWithTag("control-sliders"); View media = root.findViewWithTag("media-card"); if (((MediaCardView) media).condensed()) require(media.getHeight() == ((MediaCardView) media).minimumCardHeight(media.getWidth()), count + " condensed media uses natural height"); else require(media.getBottom() == dashboard.getHeight(), count + " media reaches content boundary"); android.graphics.Rect a = new android.graphics.Rect(), b = new android.graphics.Rect(); sliders.getHitRect(a); media.getHitRect(b); require(!android.graphics.Rect.intersects(a, b) && sliders.getHeight() <= Ui.dp(activity, 136), count + " short sliders leave media space without overlap"); View last = media.findViewWithTag(((MediaCardView) media).condensed() ? "media-artist" : "media-transport"); require(last.getBottom() <= media.getHeight() - media.getPaddingBottom(), count + " visible media content fits card");
                for (int i = 0; i < dashboard.getChildCount(); i++) { View cell = dashboard.getChildAt(i); require(cell.getLeft() >= 0 && cell.getRight() <= dashboard.getWidth() && cell.getTop() >= 0 && cell.getBottom() <= dashboard.getHeight(), count + " cell inside allocated control area"); }
                require(findText(root, "开关：亮色开启") == null, "static legend no longer occupies a separate row");
            });
            if (count == 11) screenshot("controls-adaptive");
        }
        prefs.saveActions("panel", defaults); renderPanel("controls", prefs);
    }
    private void renderPanel(String page, Prefs prefs) {
        runOnMainSync(() -> {
            CoverService owner = new CoverService(); previewOwner = owner; owner.screenContext = activity; owner.prefs = prefs; owner.display = activity.getDisplay();
            int width = activity.getResources().getDisplayMetrics().widthPixels, height = activity.getResources().getDisplayMetrics().heightPixels;
            DockGeometry.Placement p = DockGeometry.edgeTouch(DockGeometry.resolve(width, height, List.of(), activity.getResources().getDisplayMetrics().density, prefs.corner(activity.getDisplay().getRotation()), .46f, .088f, false), width, height);
            DockGeometry.Box area = DockGeometry.panelContent(p, width, height, List.of()); int statusHeight = new StatusBarView(activity, prefs).heightPixels();
            int top = area.y() + (prefs.statusEnabled() ? statusHeight + Ui.dp(activity, 4) : 0);
            owner.placement = new DockGeometry.Placement(p.visual(), p.touch(), new DockGeometry.Box(area.x(), top, area.width(), area.bottom() - top), p.edge(), p.measured());
            LinearLayout surface = owner.buildPanelContent(page, new DockGeometry.Box(0, 0, width, height), page.equals("controls") ? area.y() : top); surface.setTag("panel-surface");
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(Ui.BACKGROUND); root.addView(surface, new FrameLayout.LayoutParams(-1, -1));
            if (!page.equals("controls") && prefs.statusEnabled()) root.addView(new StatusBarView(activity, prefs), new FrameLayout.LayoutParams(width, statusHeight));
            FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(p.touch().width(), p.touch().height()); dockParams.leftMargin = p.touch().x(); dockParams.topMargin = p.touch().y();
            root.addView(new DockView(activity, prefs, p, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }), dockParams);
            activity.setContentView(root);
        }); waitForIdleSync(); SystemClock.sleep(200);
        runOnMainSync(() -> {
            View surface = activity.findViewById(android.R.id.content).findViewWithTag("panel-surface"); ScrollView list = find(surface, ScrollView.class);
            require(surface.getHeight() == activity.getResources().getDisplayMetrics().heightPixels, "panel background covers full display height");
            require(list.getBottom() + surface.getPaddingBottom() == surface.getHeight(), "panel content has no separate empty footer");
        });
    }
    private DockView findDock(View view) {
        if (view instanceof DockView) return (DockView) view;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { DockView dock = findDock(group.getChildAt(i)); if (dock != null) return dock; }
        return null;
    }
    private <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { T found = find(group.getChildAt(i), type); if (found != null) return found; }
        return null;
    }
    private TextView findText(View view, String text) {
        if (view instanceof TextView label && label.getText().toString().contains(text)) return label;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = findText(group.getChildAt(i), text); if (found != null) return found; }
        return null;
    }
    private void checkCompactRestore() throws Exception {
        Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().commit(); open("main");
        for (String hand : new String[]{"left", "right"}) for (int columns : new int[]{4, 5}) {
            prefs.data.edit().putString("hand_side", hand).putInt("panel_columns", columns).commit(); renderPanel("controls", prefs); waitForIdleSync();
            runOnMainSync(() -> {
                ViewGroup dashboard = activity.findViewById(android.R.id.content).findViewWithTag("control-dashboard"); View sliders = dashboard.findViewWithTag("control-sliders"), media = dashboard.findViewWithTag("media-card");
                require(sliders.getTop() == 0 && media.getTop() > sliders.getTop(), "ordinary text retains sliders above media in the side column");
                int firstRow = 0;
                for (String id : prefs.actions("panel")) {
                    ViewGroup tile = dashboard.findViewWithTag("control-" + id); if (tile.getTop() == 0) firstRow++;
                    TextView label = (TextView) tile.getChildAt(1); require(label.getMaxLines() == 1 && label.getLineCount() <= 1, "ordinary control name uses the compact single line");
                    require(hand.equals("left") ? tile.getLeft() >= sliders.getRight() : tile.getRight() <= sliders.getLeft(), "tools remain beside the grid without covering a tile");
                }
                require(firstRow == columns, "restoration preserves the selected column count");
            }); screenshot("compact-controls-" + hand + "-" + columns);
        }
        checkHub(prefs); checkHubDock(prefs);
        // These fixture checks do not reset or migrate a real user's settings.
    }
    private void checkSettings() throws Exception {
        EditText search = find(activity.getWindow().getDecorView(), EditText.class);
        require(search != null, "settings search exists");
        runOnMainSync(() -> search.setText("电量")); waitForIdleSync();
        require(findText(activity.getWindow().getDecorView(), "状态栏") != null, "settings search finds battery through description");
        require(findText(activity.getWindow().getDecorView(), "权限中心") == null, "settings search excludes unrelated rows"); screenshot("settings-search");
        runOnMainSync(() -> search.setText("不存在的设置")); waitForIdleSync();
        require(findText(activity.getWindow().getDecorView(), "没有找到设置") != null, "settings search has empty state");
    }
    private void checkPullGestures(Prefs prefs) {
        runOnMainSync(() -> {
            int width = Ui.dp(activity, 200), height = Ui.dp(activity, 24);
            String[] page = {""}; float[] distance = {0}; boolean[] canceled = {false}; int[] releases = {0}, actions = {0};
            PanelEntryView entry = new PanelEntryView(activity, prefs, new DockGeometry.Placement(new DockGeometry.Box(0, 0, width, height), new DockGeometry.Box(0, 0, width, height), new DockGeometry.Box(0, 0, width, height), DockGeometry.BOTTOM, true), new DockView.Listener() {
                public void action(String id) { actions[0]++; } public void configure() { }
                public void pull(String name, float value) { page[0] = name; distance[0] = value; }
                public void release(String name, float value, float speed, boolean cancel) { releases[0]++; canceled[0] = cancel; }
            });
            entry.layout(0, 0, width, height);
            for (int zone = 0; zone < 2; zone++) {
                float x = width * (zone == 0 ? .25f : .75f); long time = SystemClock.uptimeMillis();
                int[] events = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, zone == 0 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_CANCEL};
                for (int i = 0; i < events.length; i++) {
                    float travel = Ui.dp(activity, Math.min(i, 2) * 30);
                    MotionEvent event = MotionEvent.obtain(time, time + i * 80, events[i], x, height - 2 - travel, 0); entry.dispatchTouchEvent(event); event.recycle();
                    if (i == 1 || i == 2) require(Math.abs(distance[0] - travel) <= 1, "upward pull follows finger");
                }
                require(page[0].equals(zone == 0 ? "notifications" : "controls"), "left and right handles select correct page");
                require(releases[0] == zone + 1 && canceled[0] == (zone == 1), "release and cancellation routed once");
            }
            require(actions[0] == 0, "upward pull does not also click");
        });
    }
    private void checkHub(Prefs prefs) throws Exception {
        List<String> oldFavorites = prefs.actions("favorites"); prefs.saveActions("favorites", prefs.actions("dock"));
        AppHubView[] hub = {null};
        runOnMainSync(() -> {
            hub[0] = new AppHubView(activity, prefs, new AppHubView.Listener() {
                @Override public void action(String id) { }
                @Override public void editFavorites() { }
                @Override public void editPinned() { }
                @Override public void expand(boolean expanded) { }
                @Override public void close() { }
            });
            int width = activity.getResources().getDisplayMetrics().widthPixels, height = activity.getResources().getDisplayMetrics().heightPixels;
            DockGeometry.Placement placement = DockGeometry.edgeTouch(DockGeometry.resolve(width, height, List.of(), activity.getResources().getDisplayMetrics().density, 3, .46f, .088f, false), width, height);
            DockGeometry.Box content = DockGeometry.panelContent(placement, width, height, List.of()); int top = new StatusBarView(activity, prefs).heightPixels() + Ui.dp(activity, 4);
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(Ui.BACKGROUND); FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(content.width(), content.height() - top); layout.topMargin = content.y() + top; layout.leftMargin = content.x(); root.addView(hub[0], layout); activity.setContentView(root);
        }); waitForIdleSync();
        require(hub[0].expanded(), "hub opens the complete application center by default"); require(!find(hub[0], EditText.class).hasFocus(), "default opening does not focus the keyboard");
        runOnMainSync(hub[0]::toggle); waitForIdleSync(); require(!hub[0].expanded() && hub[0].findViewWithTag("hub-dock").isShown(), "hub collapses to its shared Dock");
        require(hub[0].getBottom() <= activity.getResources().getDisplayMetrics().heightPixels, "hub fits the actual display instead of a fixed dp height"); screenshot("hub-collapsed");
        runOnMainSync(() -> {
            hub[0].toggle(); View pinned = hub[0].findViewWithTag("hub-dock"); float before = pinned.getY();
            ScrollView favorites = find(hub[0], ScrollView.class); favorites.scrollTo(0, Ui.dp(activity, 200));
            require(favorites.getScrollY() > 0, "favorites list scrolls"); require(pinned.getY() == before, "Dock stays outside sidebar scroll");
        }); waitForIdleSync(); SystemClock.sleep(350);
        require(hub[0].expanded(), "hub can expand app grid");
        View rail = hub[0].findViewWithTag("hub-rail"); require(prefs.handSide().equals("right") ? rail.getRight() == hub[0].getWidth() : rail.getLeft() == 0, "app rail respects chosen side"); screenshot(prefs.handSide().equals("right") ? "hub-right-expanded" : "hub-expanded");
        runOnMainSync(() -> {
            AppWorkspaceView grid = find(hub[0], AppWorkspaceView.class); EditText search = find(hub[0], EditText.class);
            int original = grid.getCount(); require(original > 0, "installed apps are loaded"); require(grid.getNumColumns() >= 3, "grid adapts to cover width with at least three columns");
            View edit = hub[0].findViewWithTag("hub-edit"), expand = hub[0].findViewWithTag("hub-expand");
            require(edit.getWidth() >= Ui.dp(activity, 20) && edit.getHeight() == Ui.dp(activity, 28) && expand.getHeight() == Ui.dp(activity, 28) && expand.getLeft() >= edit.getRight() && expand.getTop() == edit.getTop(), "sidebar tools share one compact row without overlap");
            search.setText("__no_matching_application__"); require(grid.getCount() == 0, "app search filters grid");
            search.setText(""); require(grid.getCount() == original, "clearing search restores apps");
            hub[0].toggle(); require(!hub[0].expanded(), "hub collapses again"); hub[0].dispose();
        }); prefs.saveActions("favorites", oldFavorites);
    }
    private void checkHubDock(Prefs prefs) throws Exception {
        java.util.List<String> apps = new java.util.ArrayList<>(); java.util.Set<String> packages = new java.util.HashSet<>();
        for (String id : ActionCatalog.applications(activity)) if (packages.add(ActionCatalog.component(id).getPackageName())) apps.add(id);
        require(apps.size() >= 10, "disposable emulator has enough distinct applications for four plus four and overflow");
        prefs.saveHubPins(apps.subList(0, 3)); org.json.JSONObject legacy = prefs.layoutSnapshot();
        prefs.saveHubPins(apps.subList(0, 4)); org.json.JSONObject saved = prefs.layoutSnapshot();
        prefs.saveHubPins(java.util.List.of()); prefs.prepareLayout(legacy, prefs.data.edit()).commit(); require(prefs.hubPins().equals(apps.subList(0, 3)), "existing three-pin backups remain supported");
        prefs.prepareLayout(saved, prefs.data.edit()).commit(); require(prefs.hubPins().equals(apps.subList(0, 4)), "four pins survive layout restore");
        java.util.Map<String, ?> before = prefs.data.getAll(); boolean rejected = false;
        try { saved.put("hubPins", new org.json.JSONArray(apps.subList(0, 5))); prefs.prepareLayout(saved, prefs.data.edit()).apply(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected && before.equals(prefs.data.getAll()), "invalid pin count cannot partially restore preferences");
        final java.util.List<RecentTasks.Task> recent = new java.util.ArrayList<>();
        for (int i = 4; i < 10; i++) { android.content.ComponentName name = ActionCatalog.component(apps.get(i)); recent.add(new RecentTasks.Task(100 + i, 2, 0, name.flattenToString(), name.getPackageName(), false)); }
        AppHubView[] view = {null}; int[] clearRequests = {0}, closes = {0}, launches = {0}, refreshes = {0};
        runOnMainSync(() -> {
            view[0] = new AppHubView(activity, prefs, new AppHubView.Listener() {
                @Override public void action(String id) { launches[0]++; }
                @Override public void editFavorites() { }
                @Override public void editPinned() { }
                @Override public void expand(boolean expanded) { }
                @Override public void close() { closes[0]++; }
                @Override public void refreshRecents() { refreshes[0]++; }
                @Override public void clearRecents(java.util.List<RecentTasks.Task> tasks) { clearRequests[0]++; view[0].recentBusy(true); }
            });
            FrameLayout root = new FrameLayout(activity); root.setTag("panel-surface"); root.setBackgroundColor(Ui.BACKGROUND); StatusBarView status = new StatusBarView(activity, prefs); root.addView(status, new FrameLayout.LayoutParams(-1, status.heightPixels())); FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(-1, -1); layout.setMargins(Ui.dp(activity, 4), status.heightPixels() + Ui.dp(activity, 4), Ui.dp(activity, 4), Ui.dp(activity, 16)); root.addView(view[0], layout); activity.setContentView(root); view[0].recentResult(recent, "本地界面检查 · 示例任务");
        }); waitForIdleSync(); SystemClock.sleep(450);
        LinearLayout row = view[0].findViewWithTag("hub-dock"); int fullWidth = row.getWidth();
        require(find(view[0], AppWorkspaceView.class).getNumColumns() == 5, "720x748 at the checked density uses five columns");
        require(row.getChildCount() >= 9 && row.getChildCount() <= 11 && row.getWidth() <= view[0].getWidth(), "launcher, four pins and width-adapted recents fit in one Dock row");
        int visibleRecent = 0; for (int i = 0; i < row.getChildCount(); i++) if (row.getChildAt(i).getTag(R.id.dock_recent_task) instanceof RecentTasks.Task task) { visibleRecent++; require(task.id() < 108, "fifth and later recent applications stay off the Dock"); }
        require(visibleRecent <= 4 && (activity.getResources().getConfiguration().densityDpi != 340 || visibleRecent == 4), "normal cover density shows exactly four fixed and four recent applications");
        if (activity.getResources().getConfiguration().densityDpi == 340) for (int i = 1; i <= 4; i++) { require(row.getChildAt(i).getWidth() >= Ui.dp(activity, 33), "four plus four keeps existing compact touch widths"); ViewGroup cell = (ViewGroup) row.getChildAt(i); require(cell.getChildAt(0).getWidth() == Ui.dp(activity, 28), "four plus four keeps 28dp application icons"); }
        View fullClear = view[0].findViewWithTag("hub-clear"), summary = view[0].findViewWithTag("hub-summary");
        require(row.getChildAt(row.getChildCount() - 1) == fullClear && fullClear.getRight() <= row.getWidth() - row.getPaddingRight() && fullClear.getWidth() < row.getChildAt(0).getWidth(), "compact clear remains narrower than an application and completely visible at the far right");
        View dockArea = view[0].findViewWithTag("hub-dock-area");
        require(dockArea.getHeight() == Ui.dp(activity, 2) + Ui.dp(activity, 34), "Dock uses one 34dp row plus 2dp gap without a footer");
        require(summary.getBottom() <= find(view[0], AppWorkspaceView.class).getTop() && view[0].findViewWithTag("hub-recent-status").getParent() == summary, "recent status shares the catalog count row above applications");
        require(Math.abs(row.getLeft() + row.getWidth() / 2f - view[0].getWidth() / 2f) <= 1, "full Dock is centered"); screenshot("hub-dock-full");
        runOnMainSync(() -> { prefs.saveHubPins(apps.subList(0, 2)); view[0].recentResult(recent.subList(0, 2), "本地界面检查 · 示例任务"); }); waitForIdleSync();
        require(row.getWidth() < fullWidth && Math.abs(row.getLeft() + row.getWidth() / 2f - view[0].getWidth() / 2f) <= 1, "small Dock shrinks and remains centered"); screenshot("hub-dock-few");
        View left = view[0].findViewWithTag("hub-dismiss-left"), right = view[0].findViewWithTag("hub-dismiss-right");
        require(left.getWidth() == row.getLeft() && right.getLeft() == row.getRight() && left.getHeight() >= row.getHeight(), "dismiss targets cover the two blank sides without covering Dock");
        require(left.getHeight() <= Ui.dp(activity, 60) && find(view[0], AppWorkspaceView.class).getHeight() > Ui.dp(activity, 50), "blank hit targets never expand the footer or squeeze away the application grid");
        tapHub(view[0], left, 0, false); require(closes[0] == 1 && launches[0] == 0 && clearRequests[0] == 0, "left blank tap closes only the hub");
        tapHub(view[0], right, 0, false); require(closes[0] == 2, "right blank tap closes the hub");
        tapHub(view[0], left, Ui.dp(activity, 20), false); tapHub(view[0], right, 0, true); require(closes[0] == 2, "drag and cancellation on blank space do not dismiss");
        tapHub(view[0], row.getChildAt(1), 0, false); require(launches[0] == 1 && closes[0] == 2, "Dock application tap is not intercepted by blank dismissal");
        int previousRefreshes = refreshes[0]; tapHub(view[0], view[0].findViewWithTag("hub-recent-status"), 0, false);
        require(refreshes[0] == previousRefreshes + 1 && closes[0] == 2, "recent task status keeps its refresh action: " + previousRefreshes + " -> " + refreshes[0] + ", closes=" + closes[0]);
        checkHubBlur(view[0], prefs);
        runOnMainSync(() -> { view[0].recentFailure("Shizuku unavailable"); require(row.getChildCount() == 7 && !view[0].findViewWithTag("hub-clear").isEnabled(), "failed sync retains last rows but disables cleaning"); view[0].recentResult(recent.subList(0, 2), null); });
        View clear = view[0].findViewWithTag("hub-clear"); require(clear.getWidth() == Ui.dp(activity, 22) && clear.getHeight() == Ui.dp(activity, 34) && clear.getPaddingLeft() == 0 && clear.getPaddingRight() == 0 && clear.getParent() == row, "clear uses a compact 22x34dp target without horizontal padding inside Dock");
        tapHub(view[0], clear, 0, false); require(clearRequests[0] == 1 && row.getChildCount() == 7 && !clear.isEnabled() && closes[0] == 2, "clear waits for system acknowledgement and is not intercepted by dismissal");
        runOnMainSync(() -> view[0].recentResult(java.util.List.of(), "本地界面检查 · 已确认移除")); waitForIdleSync();
        require(row.getChildCount() == 3 && Math.abs(row.getLeft() + row.getWidth() / 2f - view[0].getWidth() / 2f) <= 1, "clear preserves launcher and pins; empty recent group is centered"); screenshot("hub-dock-cleared");
        runOnMainSync(() -> { prefs.saveHubPins(java.util.List.of()); view[0].recentResult(java.util.List.of(), null); }); waitForIdleSync();
        require(row.getChildCount() == 1 && view[0].findViewWithTag("hub-apps").isShown() && Math.abs(row.getLeft() + row.getWidth() / 2f - view[0].getWidth() / 2f) <= 1, "empty Dock keeps centered launcher with long-press editing"); screenshot("hub-dock-empty");
        require(dockArea.getHeight() == Ui.dp(activity, 2) + Ui.dp(activity, 34), "clearing tasks does not move the grid or resize the Dock vertically");
        require(left.getWidth() == row.getLeft() && right.getLeft() == row.getRight(), "blank dismissal follows empty Dock width");
        runOnMainSync(() -> { view[0].recentFailure("Shizuku disconnected"); require(view[0].findViewWithTag("hub-clear") == null, "unknown system state does not expose a working clear action"); view[0].dispose(); });
        prefs.saveHubPins(apps.subList(0, 3)); open("hub_pin"); require(activity.findViewById(android.R.id.content).findViewWithTag("order-add").isEnabled(), "settings allow adding the fourth fixed application");
        prefs.saveHubPins(apps.subList(0, 4)); open("hub_pin"); View settingsRoot = activity.findViewById(android.R.id.content); require(!settingsRoot.findViewWithTag("order-add").isEnabled(), "settings stop additions at four fixed applications"); require(((TextView) settingsRoot.findViewWithTag("order-count")).getText().toString().startsWith("4 / 4"), "settings display the same four-item limit");
    }
    private void tapHub(AppHubView hub, View target, float move, boolean cancel) {
        runOnMainSync(() -> {
            int[] origin = new int[2], position = new int[2]; hub.getLocationOnScreen(origin); target.getLocationOnScreen(position);
            float x = position[0] - origin[0] + target.getWidth() / 2f, y = position[1] - origin[1] + target.getHeight() / 2f; long time = SystemClock.uptimeMillis();
            int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP};
            for (int i = 0; i < actions.length; i++) {
                MotionEvent event = MotionEvent.obtain(time, time + 20L * i, actions[i], i == 0 ? x : x + move, y, 0); hub.dispatchTouchEvent(event); event.recycle();
            }
        }); waitForIdleSync();
    }
    private void checkHubBlur(AppHubView hub, Prefs prefs) {
        boolean previous = prefs.panelBlur();
        runOnMainSync(() -> {
            CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs; prefs.data.edit().putBoolean("panel_blur", true).commit();
            android.view.WindowManager.LayoutParams params = new android.view.WindowManager.LayoutParams(); owner.applyHubBlur(hub, params, true);
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                require((params.flags & android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0 && params.getBlurBehindRadius() > 0 && params.getBlurBehindRadius() <= 100, "expanded hub requests bounded system blur");
                android.graphics.drawable.GradientDrawable background = (android.graphics.drawable.GradientDrawable) hub.findViewWithTag("hub-rail").getBackground();
                require(android.graphics.Color.alpha(background.getColor().getDefaultColor()) < 255, "blurred hub surface lets the system backdrop show through");
            }
            owner.applyHubBlur(hub, params, false);
            require((params.flags & android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0 && params.dimAmount == .32f, "unsupported or disabled system blur uses dim fallback");
            if (android.os.Build.VERSION.SDK_INT >= 31) require(params.getBlurBehindRadius() == 0, "fallback removes any stale blur radius");
            android.graphics.drawable.GradientDrawable background = (android.graphics.drawable.GradientDrawable) hub.findViewWithTag("hub-rail").getBackground();
            require(android.graphics.Color.alpha(background.getColor().getDefaultColor()) == 255, "fallback restores opaque readable surfaces");
            prefs.data.edit().putBoolean("panel_blur", false).commit(); owner.applyHubBlur(hub, params, true); require((params.flags & android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0, "existing user blur preference is respected");
            prefs.data.edit().putBoolean("panel_blur", true).commit(); hub.setExpanded(false); owner.applyHubBlur(hub, params, true); require((params.flags & (android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND | android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)) == 0 && params.dimAmount == 0, "collapsed rail releases background effects");
            hub.setExpanded(true); owner.applyHubBlur(hub, params, false); prefs.data.edit().putBoolean("panel_blur", previous).commit();
        }); waitForIdleSync();
    }
    private void checkHandleHold(Prefs prefs) throws Exception {
        PanelEntryView[] dock = {null}; int[] toggles = {0}, actions = {0}, pulls = {0}, releases = {0}; boolean[] canceled = {false};
        prefs.data.edit().putBoolean("haptics", false).commit();
        runOnMainSync(() -> {
            int width = activity.getResources().getDisplayMetrics().widthPixels;
            DockGeometry.Box area = new DockGeometry.Box(0, 0, width / 2, Ui.dp(activity, 16));
            dock[0] = new PanelEntryView(activity, prefs, new DockGeometry.Placement(area, area, area, DockGeometry.BOTTOM, true), new DockView.Listener() {
                @Override public void action(String id) { actions[0]++; }
                @Override public void configure() { }
                @Override public void pull(String page, float distance) { pulls[0]++; }
                @Override public void toggleVisibility() { toggles[0]++; }
                @Override public void release(String page, float distance, float velocity, boolean cancel) { releases[0]++; canceled[0] = cancel; }
            });
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(Ui.BACKGROUND); TextView label = Ui.text(activity, "摄像头内侧双白条 · 原生组件预览", 14, Ui.MUTED); FrameLayout.LayoutParams text = new FrameLayout.LayoutParams(-2, -2); text.topMargin = Ui.dp(activity, 70); text.leftMargin = Ui.dp(activity, 16); root.addView(label, text);
            FrameLayout.LayoutParams position = new FrameLayout.LayoutParams(area.width(), area.height()); position.topMargin = area.y(); position.leftMargin = area.x(); root.addView(dock[0], position); activity.setContentView(root);
        }); waitForIdleSync();
        require(dock[0].getBackground() == null, "handle strip has a transparent background"); screenshot("panel-entry");
        require(!dock[0].isHapticFeedbackEnabled(), "dock respects disabled haptic setting");
        runOnMainSync(() -> dock[0].panelVisible(true)); SystemClock.sleep(180); waitForIdleSync();
        for (int half = 0; half < 2; half++) {
            float x = dock[0].getWidth() * (half == 0 ? .25f : .75f), y = dock[0].getHeight() / 2f; long time = SystemClock.uptimeMillis();
            touch(dock[0], time, MotionEvent.ACTION_DOWN, x, y);
            SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 100); waitForIdleSync();
            touch(dock[0], time, MotionEvent.ACTION_UP, x, y);
            require(toggles[0] == half + 1, "either handle long press toggles exactly once");
        }
        long time = SystemClock.uptimeMillis(); touch(dock[0], time, MotionEvent.ACTION_DOWN, 10, 2); touch(dock[0], time, MotionEvent.ACTION_CANCEL, 10, 2);
        SystemClock.sleep(android.view.ViewConfiguration.getLongPressTimeout() + 100); waitForIdleSync();
        require(toggles[0] == 2 && pulls[0] == 0 && actions[0] == 0, "hold and cancel do not also open panels or click icons");
        time = SystemClock.uptimeMillis(); touch(dock[0], time, MotionEvent.ACTION_DOWN, 20, .5f); touch(dock[0], time, MotionEvent.ACTION_MOVE, 20, -100);
        touch(dock[0], time, MotionEvent.ACTION_POINTER_DOWN, 20, -100); touch(dock[0], time, MotionEvent.ACTION_UP, 20, -100);
        require(releases[0] == 1 && canceled[0] && actions[0] == 0 && toggles[0] == 2, "second pointer cancels pull once without clicking or toggling");
        for (int half = 0; half < 2; half++) { float x = dock[0].getWidth() * (half == 0 ? .25f : .75f); time = SystemClock.uptimeMillis(); touch(dock[0], time, MotionEvent.ACTION_DOWN, x, 2); touch(dock[0], time, MotionEvent.ACTION_UP, x, 2); }
        require(actions[0] == 0 && toggles[0] == 2, "top entries ignore taps without toggling icons");
        prefs.data.edit().putBoolean("tap_handles", false).commit(); time = SystemClock.uptimeMillis(); touch(dock[0], time, MotionEvent.ACTION_DOWN, 10, 2); touch(dock[0], time, MotionEvent.ACTION_UP, 10, 2); require(actions[0] == 0, "top taps stay inactive");
        prefs.data.edit().putBoolean("tap_handles", true).putBoolean("haptics", true).commit();
    }
    private void checkPanelHeader() {
        for (int edge = 0; edge < 4; edge++) {
            float[] progress = {1}; int[] finishes = {0}; boolean[] closed = {false};
            PanelHeaderView header = new PanelHeaderView(activity, "测试面板", edge, 400, false, new PanelHeaderView.Listener() {
                @Override public void begin() { }
                @Override public void progress(float value) { progress[0] = value; }
                @Override public void finish(boolean close) { finishes[0]++; closed[0] = close; }
            });
            require(!header.isHapticFeedbackEnabled(), "header respects haptic preference");
            float dx = edge == DockGeometry.LEFT ? -1 : edge == DockGeometry.RIGHT ? 1 : 0, dy = edge == DockGeometry.TOP ? -1 : edge == DockGeometry.BOTTOM ? 1 : 0;
            long time = SystemClock.uptimeMillis();
            headerEvent(header, time, 0, MotionEvent.ACTION_DOWN, 300, 300);
            headerEvent(header, time, 500, MotionEvent.ACTION_MOVE, 300 + dx * 160, 300 + dy * 160);
            require(Math.abs(progress[0] - .6f) < .001f, "header follows outward distance on edge " + edge);
            headerEvent(header, time, 1000, MotionEvent.ACTION_UP, 300 + dx * 160, 300 + dy * 160); require(closed[0], "long outward drag closes panel");
            headerEvent(header, time, 1200, MotionEvent.ACTION_DOWN, 300, 300);
            headerEvent(header, time, 1700, MotionEvent.ACTION_MOVE, 300 + dx * 160, 300 + dy * 160);
            headerEvent(header, time, 1800, MotionEvent.ACTION_POINTER_DOWN, 300 + dx * 160, 300 + dy * 160);
            headerEvent(header, time, 1900, MotionEvent.ACTION_UP, 300 + dx * 160, 300 + dy * 160);
            require(finishes[0] == 2 && !closed[0], "multi-pointer dismissal cancels once");
            headerEvent(header, time, 2000, MotionEvent.ACTION_DOWN, 300, 300);
            headerEvent(header, time, 2500, MotionEvent.ACTION_MOVE, 300 + dx * 5, 300 + dy * 5);
            headerEvent(header, time, 3000, MotionEvent.ACTION_UP, 300 + dx * 5, 300 + dy * 5); require(!closed[0], "short header drag stays open");
            headerEvent(header, time, 3100, MotionEvent.ACTION_DOWN, 300, 300);
            headerEvent(header, time, 3600, MotionEvent.ACTION_MOVE, 300 + dy * 160, 300 + dx * 160);
            headerEvent(header, time, 4100, MotionEvent.ACTION_UP, 300 + dy * 160, 300 + dx * 160); require(!closed[0], "wrong-axis header drag stays open");
        }
    }
    private void headerEvent(PanelHeaderView view, long time, int offset, int action, float x, float y) {
        MotionEvent event = testEvent(time, time + offset, action, x, y); view.onTouchEvent(event); event.recycle();
    }
    private void checkLayoutRestore(Prefs prefs) throws Exception {
        org.json.JSONObject original = prefs.layoutSnapshot();
        prefs.data.edit().putInt("per_page", 5).putFloat("dock_width", .52f).putBoolean("auto_placement", false).putInt("corner_2", 3).putBoolean("status_wifi", false).commit();
        prefs.saveLayout(); prefs.data.edit().putInt("per_page", 2).putFloat("dock_width", .33f).putInt("display", 92).putBoolean("avoid_keyboard", false).commit();
        prefs.restoreLayout(false);
        require(prefs.perPage() == 5 && Math.abs(prefs.widthRatio() - .52f) < .001f && prefs.corner(2) == 3 && !prefs.statusItem("wifi"), "layout restores count, proportions, rotation and status choices");
        require(prefs.displayId() == 92 && !prefs.avoidKeyboard(), "layout restore leaves device selection and avoidance rules unchanged");
        prefs.restoreLayout(true); require(prefs.perPage() == 2 && !prefs.data.contains("layout_undo"), "restore can be undone once");
        prefs.resetLayout(); require(prefs.perPage() == 4 && Math.abs(prefs.widthRatio() - .46f) < .001f, "default layout resets local geometry");
        prefs.restoreLayout(true); require(prefs.perPage() == 2, "default reset also supports undo");
        java.util.Map<String, ?> before = prefs.data.getAll(); org.json.JSONObject malformed = prefs.layoutSnapshot(); malformed.put("corners", new org.json.JSONArray("[0,1,2,9]"));
        boolean rejected = false; try { prefs.prepareLayout(malformed, prefs.data.edit()).apply(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected && before.equals(prefs.data.getAll()), "late validation failure does not partially apply layout");
        malformed = prefs.layoutSnapshot(); malformed.put("width", 4);
        rejected = false; try { prefs.prepareLayout(malformed, prefs.data.edit()).apply(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected && before.equals(prefs.data.getAll()), "out-of-range geometry is rejected atomically");
        prefs.prepareLayout(original, prefs.data.edit()).remove("display").remove("avoid_keyboard").apply(); prefs.saveLayout();
    }
    private void touch(View view, long downTime, int action, float x, float y) {
        runOnMainSync(() -> { MotionEvent event = testEvent(downTime, SystemClock.uptimeMillis(), action, x, y); view.dispatchTouchEvent(event); event.recycle(); });
    }
    private MotionEvent testEvent(long down, long time, int action, float x, float y) {
        if (action != MotionEvent.ACTION_POINTER_DOWN) return MotionEvent.obtain(down, time, action, x, y, 0);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2]; MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords(); coordinates[i].x = x + i * 5; coordinates[i].y = y + i * 5; coordinates[i].pressure = 1; coordinates[i].size = 1;
        }
        return MotionEvent.obtain(down, time, action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
    }
    private void checkSlider() {
        int[] commits = {0}, selected = {0};
        LevelSlider slider = new LevelSlider(activity, "测试亮度", R.drawable.ic_ms_brightness_6, 5, 100, 25, value -> { commits[0]++; selected[0] = value; });
        FrameLayout parent = new FrameLayout(activity); parent.addView(slider); slider.layout(0, 0, Ui.dp(activity, 44), Ui.dp(activity, 180));
        Bundle arguments = new Bundle(); arguments.putFloat(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 500);
        slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments);
        require(selected[0] == 100, "accessible slider clamps values");
        slider.setEnabled(false); slider.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId(), arguments);
        require(commits[0] == 1, "unavailable brightness cannot commit");
        slider.setEnabled(true); slider.setValue(25); long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 10, 10, 0); slider.onTouchEvent(down); down.recycle();
        MotionEvent cancel = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_CANCEL, 10, 10, 0); slider.onTouchEvent(cancel); cancel.recycle();
        require(slider.getContentDescription().toString().contains("25%") && commits[0] == 1, "canceled gesture restores actual level without commit");
    }
    private void swipe(DockView dock, boolean fixedArea) {
        runOnMainSync(() -> {
            float width = dock.getWidth(), y = dock.getHeight() / 2f;
            View pager = dock.findViewWithTag("paging-area"), fixed = dock.findViewWithTag("fixed-action");
            float start = fixedArea ? fixed.getX() + fixed.getWidth() * .8f : pager.getX() + pager.getWidth() * .85f, end = start - pager.getWidth() * .8f;
            long time = SystemClock.uptimeMillis();
            int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
            for (int i = 0; i < actions.length; i++) {
                float x = start + (end - start) * i / (actions.length - 1);
                MotionEvent event = MotionEvent.obtain(time, time + i * 50, actions[i], x, y, 0); dock.dispatchTouchEvent(event); event.recycle();
            }
        });
    }
    private void checkPersonalization(Prefs prefs) throws Exception {
        open("hand"); runOnMainSync(() -> activity.findViewById(android.R.id.content).findViewWithTag("hand-left").performClick()); waitForIdleSync(); SystemClock.sleep(150);
        DockView dock = findDock(activity.getWindow().getDecorView()); require(dock != null && dock.findViewWithTag("fixed-action").getX() == 0, "left hand pins the first horizontal slot");
        swipe(dock, false); waitForIdleSync(); require(dock.page() == 1, "left-hand pager still advances");
        swipe(dock, true); waitForIdleSync(); require(dock.page() == 2, "left fixed area also supports paging"); screenshot("hand-left");
        renderPanel("controls", prefs); require(activity.findViewById(android.R.id.content).findViewWithTag("control-sliders").getLeft() == 0, "left-hand controls put sliders on the left"); screenshot("controls-left");
        prefs.data.edit().putString("hand_side", "right").commit(); checkHub(prefs); screenshot("hub-right-collapsed");
        open("hand"); screenshot("hand-right");
        open("status"); chooseSetting("status-preset", "minimal");
        require(prefs.statusPreset().equals("minimal") && !prefs.statusItem("notifications") && !prefs.statusItem("cellular"), "minimal preset trims optional indicators"); screenshot("status-minimal");
        prefs.data.edit().putBoolean("status_alarm", true).commit(); require(prefs.statusPreset().equals("custom"), "manual switches change preset state to custom"); prefs.statusPreset("daily");
        CoverApp.catalog(getTargetContext()).entriesBlocking(); open("orientations");
        runOnMainSync(() -> ((EditText) activity.findViewById(android.R.id.content).findViewWithTag("settings-query")).setText(getTargetContext().getPackageName())); waitForIdleSync();
        android.widget.ListView applications = activity.findViewById(android.R.id.content).findViewWithTag("settings-app-list");
        require(applications.getAdapter().getCount() == 2 && applications.getChildCount() >= 1, "rotation settings find the app through package search"); runOnMainSync(() -> applications.setSelection(1)); waitForIdleSync(); runOnMainSync(() -> applications.findViewWithTag(((AppCatalogCache.Entry) applications.getAdapter().getItem(1)).id()).performClick()); waitForIdleSync();
        runOnMainSync(() -> activity.findViewById(android.R.id.content).findViewWithTag("orientation-choice-1").performClick()); waitForIdleSync();
        require(prefs.appRotation(getTargetContext().getPackageName()) == 1, "rotation choice saves without applying system operation"); SystemClock.sleep(4000); screenshot("app-orientation");
        prefs.data.edit().putString("hand_side", "left").putBoolean("haptics", false).putInt("display", 0).commit();
        require(Displays.selected(getTargetContext(), prefs) == null, "primary display cannot be selected as a cover");
        org.json.JSONObject config = ((MainActivity) activity).exportConfigurationData();
        require(config.getInt("version") == 13 && !config.has("display") && !config.has("enabled"), "export v13 excludes device and runtime activation");
        prefs.data.edit().putString("hand_side", "auto").putString("app_rotations", "{}").putBoolean("haptics", true).commit(); ((MainActivity) activity).applyConfigurationData(config);
        require(prefs.leftHand() && !prefs.haptics() && prefs.appRotation(getTargetContext().getPackageName()) == 1 && prefs.displayId() == 0, "configuration roundtrip preserves new choices and device selection");
        java.util.Map<String, ?> before = prefs.data.getAll(); org.json.JSONObject invalid = new org.json.JSONObject(config.toString()); invalid.getJSONObject("rotations").put("invalid.package", 2.5);
        boolean rejected = false; try { ((MainActivity) activity).applyConfigurationData(invalid); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected && before.equals(prefs.data.getAll()), "invalid rotation rejects complete import atomically");
        for (int version = 1; version <= 6; version++) {
            org.json.JSONObject legacy = new org.json.JSONObject(config.toString()).put("version", version); if (version < 5) legacy.remove("rotations");
            if (version < 4) legacy.remove("layout"); else if (version == 4) { legacy.getJSONObject("layout").put("version", 1).remove("hand"); } else legacy.getJSONObject("layout").put("version", version == 6 ? 3 : 2);
            ((MainActivity) activity).applyConfigurationData(legacy); require(prefs.appRotation(getTargetContext().getPackageName()) == 1, "legacy v" + version + " preserves app rotation rules");
        }
        require(prefs.handSide().equals("left"), "v5 layout preserves its saved handedness");
        prefs.saveAppRotation(getTargetContext().getPackageName(), -1); require(prefs.appRotation(getTargetContext().getPackageName()) == -1, "current-direction choice removes rule");
        prefs.data.edit().remove("display").putBoolean("haptics", true).commit();
    }
    private android.service.notification.StatusBarNotification sampleNotification(int id, String title, long time) {
        android.app.Notification notification = new android.app.Notification.Builder(getTargetContext(), "ui-fixture").setSmallIcon(R.drawable.ic_ms_notifications).setGroup("fixture-" + id).setContentTitle(title).setContentText("本地检查示例，不会发布到系统").build();
        return new android.service.notification.StatusBarNotification(getTargetContext().getPackageName(), getTargetContext().getPackageName(), id, "fixture", android.os.Process.myUid(), android.os.Process.myPid(), 0, notification, android.os.Process.myUserHandle(), time);
    }
    private void checkPhysicalEdgePull(Prefs prefs) throws Exception {
        for (int cornerIndex = 0; cornerIndex < 4; cornerIndex++) {
            final int corner = cornerIndex; DockView[] dock = {null}; int[] pulls = {0}, clicks = {0}; float[] distance = {0};
            runOnMainSync(() -> {
                DockGeometry.Placement p = DockGeometry.edgeTouch(DockGeometry.resolve(720, 748, List.of(), 2, corner, .46f, .088f, false), 720, 748);
                dock[0] = new DockView(activity, prefs, p, 0, new DockView.Listener() { public void action(String id) { clicks[0]++; } public void configure() { } public void pull(String page, float value) { pulls[0]++; distance[0] = value; } });
                dock[0].measure(View.MeasureSpec.makeMeasureSpec(p.touch().width(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(p.touch().height(), View.MeasureSpec.EXACTLY)); dock[0].layout(0, 0, p.touch().width(), p.touch().height());
            });
            int edge = (corner + 1) % 4; float x = dock[0].getWidth() / 4f, y = dock[0].getHeight() / 4f;
            if (edge == DockGeometry.BOTTOM) y = dock[0].getHeight() - .5f; else if (edge == DockGeometry.TOP) y = .5f; else if (edge == DockGeometry.LEFT) x = .5f; else x = dock[0].getWidth() - .5f;
            float dx = edge == DockGeometry.LEFT ? 90 : edge == DockGeometry.RIGHT ? -90 : 18, dy = edge == DockGeometry.BOTTOM ? -90 : edge == DockGeometry.TOP ? 90 : 18;
            long time = SystemClock.uptimeMillis(); touch(dock[0], time, MotionEvent.ACTION_DOWN, x, y); touch(dock[0], time, MotionEvent.ACTION_MOVE, x + dx, y + dy); touch(dock[0], time, MotionEvent.ACTION_UP, x + dx, y + dy);
            require(pulls[0] == 0 && clicks[0] == 0, "old dock edge never opens a panel on edge " + edge);
        }
    }
    private void checkNotificationUpdates(Prefs prefs) throws Exception {
        open("main"); Panels[] renderer = {null}; ScrollView[] scroll = {null}; LinearLayout[] body = {null};
        renderPanel("notifications", prefs);
        List<android.service.notification.StatusBarNotification> items = new java.util.ArrayList<>(); for (int i = 0; i < 8; i++) items.add(sampleNotification(i, "通知 " + i, 1000 - i));
        runOnMainSync(() -> {
            try { java.lang.reflect.Field field = CoverService.class.getDeclaredField("panels"); field.setAccessible(true); renderer[0] = (Panels) field.get(previewOwner); } catch (ReflectiveOperationException failure) { throw new RuntimeException(failure); }
            scroll[0] = find(activity.findViewById(android.R.id.content), ScrollView.class); body[0] = (LinearLayout) scroll[0].getChildAt(0);
            renderer[0].updateNotifications(true, items);
        }); waitForIdleSync();
        runOnMainSync(() -> {
            LinearLayout list = body[0].findViewWithTag("notification-list"); View first = list.getChildAt(0); TextView title = findText(first, "通知 0"); int[] changes = {0};
            title.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { changes[0]++; } public void afterTextChanged(android.text.Editable s) { } });
            scroll[0].scrollTo(0, Ui.dp(activity, 150)); int offset = scroll[0].getScrollY(); require(offset > 0, "notification fixture scrolls");
            for (int update = 0; update < 60; update++) { items.set(0, sampleNotification(0, "通知 0", 2000 + update)); renderer[0].updateNotifications(true, items); }
            require(scroll[0].getChildAt(0) == body[0] && list.getChildAt(0) == first && first.isAttachedToWindow(), "notification updates preserve attached container and row");
            require(changes[0] == 0 && scroll[0].getScrollY() == offset, "identical visible content does not reset text or scroll");
            items.set(0, sampleNotification(0, "标题已更新", 3000)); renderer[0].updateNotifications(true, items); require(changes[0] == 1 && list.getChildAt(0) == first, "changed title updates existing card once");
            items.add(0, sampleNotification(99, "新消息", 4000)); renderer[0].updateNotifications(true, items); require(list.getChildCount() == 8 && list.getChildAt(0) == first, "new notification waits while reading");
            scroll[0].scrollTo(0, 0); renderer[0].updateNotifications(true, items); require(list.getChildCount() == 9 && list.getChildAt(1) == first, "returning to top inserts without replacing old cards");
            items.remove(1); renderer[0].updateNotifications(true, items); require(list.getChildCount() == 8 && first.getParent() == null, "removed notification removes its card only");
            scroll[0].scrollTo(0, 0);
        }); waitForIdleSync(); screenshot("notifications-stable"); measurePage("notifications_fixture");
        runOnMainSync(() -> {
            items.set(0, sampleNotification(99, "较长通知标题：明天下午的项目讨论需要提前准备，并检查外屏排版", 4001)); renderer[0].updateNotifications(true, items);
        }); waitForIdleSync();
        runOnMainSync(() -> {
            LinearLayout list = body[0].findViewWithTag("notification-list"); TextView title = findText(list.getChildAt(0), "较长通知标题");
            require(title.getLineCount() == 1 && title.getWidth() > 0, "long notification title folds to one line");
            if (activity.getResources().getConfiguration().fontScale <= 1.3f) require(list.getChildAt(0).getHeight() < scroll[0].getHeight() / 2, "long notification still leaves room for a second card at ordinary font scales");
            else { View second = list.getChildAt(1); TextView secondTitle = findText(second, "通知 1"); scroll[0].scrollTo(0, second.getTop()); android.graphics.Rect visible = new android.graphics.Rect(); require(secondTitle != null && secondTitle.getGlobalVisibleRect(visible) && visible.height() == secondTitle.getHeight() && visible.width() == secondTitle.getWidth(), "large-font second notification title remains fully reachable by scrolling"); scroll[0].scrollTo(0, 0); }
            require(list.getChildAt(0).findViewWithTag("notification-icon") != null, "notification carries its small icon");
            View expand = list.getChildAt(0).findViewWithTag("notification-expand"); require(expand.getVisibility() == View.VISIBLE, "actual text overflow shows expand"); expand.performClick();
        }); screenshot("notifications-long-title");
        waitForIdleSync(); SystemClock.sleep(220);
        runOnMainSync(() -> { LinearLayout list = body[0].findViewWithTag("notification-list"); TextView title = findText(list.getChildAt(0), "较长通知标题"); require(title.getLineCount() > 1, "expanding reveals full title"); renderer[0].updateNotifications(true, items); require(title.getMaxLines() > 1, "notification refresh preserves expanded state"); });
        screenshot("notifications-expanded");
        runOnMainSync(() -> { renderer[0].updateNotifications(false, java.util.List.of()); require(((LinearLayout) body[0].findViewWithTag("notification-list")).getChildCount() == 0 && scroll[0].getChildAt(0) == body[0], "permission loss clears content without recreating panel"); });
    }
    private void checkCoverPolish(Prefs prefs) throws Exception {
        require(prefs.statusScale() == 70 && !prefs.batteryPercent() && prefs.chromeStyle().equals("contrast"), "new chrome defaults are compact and transparent");
        require(!prefs.avoidNavigation() && !prefs.tapHandles(), "edge drag is default and handle taps stay disabled");
        open("status"); require(find(activity.getWindow().getDecorView(), android.widget.SeekBar.class).getMax() == 150, "status size slider reaches 150 percent"); screenshot("status-size");
        runOnMainSync(() -> {
            StatusBarView overlay = new StatusBarView(activity, prefs), controls = new StatusBarView(activity, prefs, true);
            require(!overlay.showsPercentage() && controls.showsPercentage(), "control center forces percent independently");
            int base = Ui.dp(activity, 20); overlay.previewScale(50); require(overlay.heightPixels() == Math.round(base * .5f), "50 percent scale changes height"); overlay.previewScale(150); require(overlay.heightPixels() == Math.round(base * 1.5f), "150 percent scale changes height");
            prefs.data.edit().putBoolean("status_battery", false).commit(); require(controls.showsPercentage(), "panel percent survives hidden overlay battery"); prefs.data.edit().putBoolean("status_battery", true).commit();
        });
        for (int scale : new int[]{50, 70, 150}) {
            prefs.data.edit().putInt("status_scale", scale).commit();
            runOnMainSync(() -> {
                LinearLayout root = Ui.column(activity); root.setPadding(0, Ui.dp(activity, 50), 0, 0); root.setBackgroundColor(Ui.BACKGROUND); activity.setContentView(root);
                root.addView(Ui.text(activity, "透明描边 · " + scale + "% · 原生组件", 14, Ui.TEXT));
                for (int color : new int[]{android.graphics.Color.WHITE, Ui.BACKGROUND}) {
                    LinearLayout sample = Ui.column(activity); sample.setBackgroundColor(color); sample.setPadding(0, Ui.dp(activity, 12), 0, Ui.dp(activity, 12)); StatusBarView status = new StatusBarView(activity, prefs); sample.addView(status, new LinearLayout.LayoutParams(-1, status.heightPixels()));
                    DockGeometry.Placement placement = new DockGeometry.Placement(new DockGeometry.Box(0, 0, Ui.dp(activity, 155), Ui.dp(activity, 30)), new DockGeometry.Box(0, 0, Ui.dp(activity, 155), Ui.dp(activity, 44)), new DockGeometry.Box(0, 0, 720, 500), DockGeometry.BOTTOM, false);
                    sample.addView(new DockView(activity, prefs, placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } }), new LinearLayout.LayoutParams(Ui.dp(activity, 155), Ui.dp(activity, 44))); root.addView(sample);
                }
                StatusBarView panel = new StatusBarView(activity, prefs, true); root.addView(Ui.text(activity, "控制中心 · 右侧保留百分比", 13, Ui.TEXT)); root.addView(panel, new LinearLayout.LayoutParams(-1, panel.heightPixels()));
            }); waitForIdleSync(); screenshot("chrome-" + scale);
        }
        prefs.data.edit().putInt("status_scale", 70).putInt("navigation_gap", 17).putString("chrome_style", "dark").putBoolean("battery_percent", true).commit();
        org.json.JSONObject snapshot = prefs.layoutSnapshot(); require(snapshot.getInt("version") == 9, "layout schema carries hub and chrome preferences"); prefs.data.edit().putInt("status_scale", 150).commit(); prefs.prepareLayout(snapshot, prefs.data.edit()).commit();
        require(prefs.statusScale() == 70 && prefs.batteryPercent() && prefs.navigationGap() == 17 && prefs.chromeStyle().equals("dark"), "new chrome configuration roundtrip");
        java.util.Map<String, ?> before = prefs.data.getAll(); snapshot.put("statusScale", 151); boolean rejected = false; try { prefs.prepareLayout(snapshot, prefs.data.edit()).commit(); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected && before.equals(prefs.data.getAll()), "invalid scale atomically rejects layout");
        prefs.data.edit().putInt("navigation_gap", 8).putString("chrome_style", "contrast").putBoolean("battery_percent", false).commit();
        open("gestures"); screenshot("navigation-avoidance"); open("appearance"); screenshot("appearance");
    }
    private void measurePage(String name) throws Exception {
        runOnMainSync(() -> {
            try {
                View root = activity.findViewById(android.R.id.content); ScrollView viewport = find(root, ScrollView.class); org.json.JSONObject row = new org.json.JSONObject().put("page", name).put("screenWidthPx", activity.getResources().getDisplayMetrics().widthPixels).put("screenHeightPx", activity.getResources().getDisplayMetrics().heightPixels).put("density", activity.getResources().getDisplayMetrics().density);
                if (viewport != null && viewport.getChildCount() > 0) { View content = viewport.getChildAt(0); row.put("viewportHeightPx", viewport.getHeight()).put("contentHeightPx", content.getHeight()).put("contentPaddingPx", content.getPaddingTop() + content.getPaddingBottom()).put("unusedTailPx", Math.max(0, viewport.getHeight() - content.getHeight())); }
                View outer = ((ViewGroup) root).getChildAt(0); if (outer instanceof LinearLayout stack && stack.getChildCount() > 0) row.put("firstRowHeightPx", stack.getChildAt(0).getHeight());
                LinearLayout list = root.findViewWithTag("notification-list"); if (list != null && list.getChildCount() > 0) { View card = list.getChildAt(0); row.put("notificationCardHeightPx", card.getHeight()).put("notificationCardPaddingPx", card.getPaddingTop() + card.getPaddingBottom()).put("notificationHeaderHeightPx", ((ViewGroup) card).getChildAt(0).getHeight()); }
                AppWorkspaceView grid = find(root, AppWorkspaceView.class); if (grid != null) row.put("appColumns", grid.getNumColumns()); measurements.put(row);
            } catch (org.json.JSONException e) { throw new RuntimeException(e); }
        });
    }
    private void checkDensityAudit() throws Exception {
        Prefs prefs = new Prefs(getTargetContext()); prefs.data.edit().clear().commit();
        for (String page : new String[]{"main", "dock", "panel", "favorites", "hub", "status", "gestures", "appearance", "visibility", "layout_backup", "hand", "orientations", "calibrate", "permissions", "diagnostics", "about", "apps", "hub_pin"}) { open(page); measurePage(page); screenshot("audit-" + page); }
        open("dock"); runOnMainSync(() -> findText(activity.findViewById(android.R.id.content), "添加按钮").performClick()); waitForIdleSync(); SystemClock.sleep(300); measurePage("library_builtin"); screenshot("audit-library-builtin");
        runOnMainSync(() -> findText(activity.findViewById(android.R.id.content), "应用磁贴").performClick()); waitForIdleSync(); SystemClock.sleep(300); measurePage("library_tiles"); screenshot("audit-library-tiles");
        runOnMainSync(() -> ((ViewGroup) findText(activity.findViewById(android.R.id.content), "应用磁贴").getParent()).getChildAt(2).performClick()); waitForIdleSync(); SystemClock.sleep(300); measurePage("library_apps"); screenshot("audit-library-apps");
        open("calibrate"); runOnMainSync(() -> findText(activity.findViewById(android.R.id.content), "先选择外屏").performClick()); waitForIdleSync(); measurePage("display_picker"); screenshot("audit-display-picker");
        open("orientations"); SystemClock.sleep(350); runOnMainSync(() -> ((android.widget.EditText) activity.findViewById(android.R.id.content).findViewWithTag("settings-query")).setText(getTargetContext().getPackageName())); waitForIdleSync(); android.widget.ListView rules = activity.findViewById(android.R.id.content).findViewWithTag("settings-app-list"); runOnMainSync(() -> rules.setSelection(1)); waitForIdleSync(); runOnMainSync(() -> rules.findViewWithTag(((AppCatalogCache.Entry) rules.getAdapter().getItem(1)).id()).performClick()); waitForIdleSync(); measurePage("app_orientation"); screenshot("audit-app-orientation");
        for (String page : new String[]{"controls", "rotation", "media"}) { renderPanel(page, prefs); measurePage("overlay_" + page); screenshot("audit-overlay-" + page); }
        checkNotificationUpdates(prefs); checkHub(prefs); measurePage("hub_component");
        java.io.File folder = new java.io.File(getTargetContext().getFilesDir(), "ui-smoke"); folder.mkdirs(); try (FileOutputStream out = new FileOutputStream(new java.io.File(folder, "density-audit.json"))) { out.write(measurements.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        require(measurements.length() == 28, "all 28 interface families measured");
    }
    private void checkAvailabilityScenario() throws Exception {
        boolean wifiTest = scenario.startsWith("wifi-");
        if (!wifiTest && !scenario.equals("phone-granted") && !scenario.equals("phone-denied")) throw new IllegalArgumentException("Unknown scenario");
        Prefs prefs = new Prefs(getTargetContext()); android.content.SharedPreferences.Editor edit = prefs.data.edit();
        for (String item : Prefs.STATUS_ITEMS) edit.putBoolean("status_" + item, item.equals(wifiTest ? "wifi" : "cellular")); edit.commit();
        if (!wifiTest) require((getTargetContext().checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) == scenario.equals("phone-granted"), "phone permission matches external emulator setup");
        open("main"); StatusBarView[] status = {null};
        runOnMainSync(() -> {
            LinearLayout root = Ui.column(activity); root.setBackgroundColor(Ui.BACKGROUND); root.setPadding(Ui.dp(activity, 16), Ui.dp(activity, 65), Ui.dp(activity, 16), 0);
            root.addView(Ui.heading(activity, "状态条组件检查 · " + scenario, 14)); status[0] = new StatusBarView(activity, prefs); root.addView(status[0], new LinearLayout.LayoutParams(-1, Ui.dp(activity, 32))); activity.setContentView(root);
        }); waitForIdleSync(); SystemClock.sleep(1200);
        require(status[0].isAttachedToWindow(), "status component attaches with current permissions");
        if (wifiTest) {
            android.net.ConnectivityManager network = getTargetContext().getSystemService(android.net.ConnectivityManager.class); boolean connected = false; long deadline = SystemClock.uptimeMillis() + 10000;
            do { android.net.NetworkCapabilities caps = network.getNetworkCapabilities(network.getActiveNetwork()); connected = caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI); if (connected == scenario.equals("wifi-on")) break; SystemClock.sleep(100); } while (SystemClock.uptimeMillis() < deadline);
            require(connected == scenario.equals("wifi-on"), "active Wi-Fi matches emulator scenario");
            int[] pixels = {0}; runOnMainSync(() -> {
                Bitmap bitmap = Bitmap.createBitmap(status[0].getWidth(), status[0].getHeight(), Bitmap.Config.ARGB_8888); status[0].draw(new android.graphics.Canvas(bitmap));
                int[] colors = new int[bitmap.getWidth() * bitmap.getHeight()]; bitmap.getPixels(colors, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight()); for (int color : colors) if ((color & 0xFFFFFF) != 0) pixels[0]++; bitmap.recycle();
            }); require((pixels[0] > 0) == connected, "Wi-Fi glyph appears only when connected");
        }
        require(!CoverApp.bridge(getTargetContext()).granted(), "missing Shizuku remains unavailable"); screenshot(scenario); prefs.statusPreset("daily");
    }
    private void screenshot(String name) throws Exception {
        waitForIdleSync(); SystemClock.sleep(180); Bitmap bitmap;
        if (!name.startsWith("detail-") && activity.findViewById(android.R.id.content).findViewWithTag("panel-surface") != null) {
            // Capture the actual native view tree. Emulator SystemUI sits above this Activity
            // fixture, whereas the product draws it on the separately selected cover display.
            Bitmap[] frame = {null}; runOnMainSync(() -> { View root = activity.findViewById(android.R.id.content); frame[0] = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new android.graphics.Canvas(frame[0])); }); bitmap = frame[0];
        } else bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new IllegalStateException("Screenshot unavailable");
        File directory = new File(getTargetContext().getFilesDir(), "ui-smoke"); directory.mkdirs();
        try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); }
        finally { bitmap.recycle(); }
    }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
}
