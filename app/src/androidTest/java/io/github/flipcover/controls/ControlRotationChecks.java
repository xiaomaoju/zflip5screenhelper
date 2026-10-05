package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** Native measurement regression for the separate landscape status row. Emulator only. */
final class ControlRotationChecks {
    static String run(Instrumentation test) throws Exception {
        Activity activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        File directory = new File(test.getTargetContext().getFilesDir(), "control-rotation"); directory.mkdirs();
        StringBuilder samples = new StringBuilder();
        Throwable[] failure = {null};
        try {
            test.getUiAutomation().adoptShellPermissionIdentity("android.permission.MEDIA_CONTENT_CONTROL");
            test.runOnMainSync(() -> {
                try {
                    Configuration configuration = new Configuration(activity.getResources().getConfiguration()); configuration.densityDpi = 340; configuration.fontScale = 1.15f;
                    Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.AppTheme);
                    // This scenario is admitted only by the disposable-emulator guard.
                    Prefs prefs = new Prefs(context);
                    prefs.data.edit().clear().putInt("panel_columns", 4).putInt("panel_label_size", 0).putBoolean("panel_media_idle", false).commit();
                    prefs.saveActions("panel", List.of("wifi", "bluetooth", "data", "torch", "dnd", "airplane", "rotation", "screenshot", "lock", "media", "apps", "configure", "system_controls", "recents", "notifications", "nfc"));
                    for (int rotation : new int[]{0, 1, 2, 3}) {
                        boolean landscape = rotation == 1 || rotation == 3;
                        int width = landscape ? 720 : 748, height = landscape ? 748 : 720;
                        CoverService owner = new CoverService(); owner.screenContext = context; owner.prefs = prefs;
                        Runnable mediaObserver = () -> { }; owner.mediaSessions().observe(mediaObserver);
                        try {
                            int left = rotation == 3 ? 66 : 0, right = rotation == 1 ? 66 : 0, top = landscape ? 102 : 38, bottom = landscape ? 0 : 66;
                            DockGeometry.Box safe = new DockGeometry.Box(left, top, width - left - right, height - top - bottom);
                            owner.placement = new DockGeometry.Placement(new DockGeometry.Box(0, 0, 0, 0), new DockGeometry.Box(0, 0, 0, 0), safe, DockGeometry.TOP, true);
                            if (landscape) { Field status = CoverService.class.getDeclaredField("controlStatusBox"); status.setAccessible(true); status.set(owner, new DockGeometry.Box(left, 0, safe.width(), 30)); }
                            View panel = owner.buildPanelContent("controls", new DockGeometry.Box(0, 0, width, height), top);
                            panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); panel.layout(0, 0, width, height);
                            ScrollView scroll = findScroll(panel); ViewGroup dashboard = panel.findViewWithTag("control-dashboard");
                            View tile = dashboard.findViewWithTag("control-wifi");
                            ViewGroup face = (ViewGroup) ((ViewGroup) tile).getChildAt(0);
                            if (face.getWidth() < Ui.dp(context, 36) || face.getChildAt(0).getWidth() != Ui.dp(context, 23)) throw new AssertionError("Default four-row controls must retain the normal button face and full-size glyph");
                            samples.append("rotation=").append(rotation).append(" panel=").append(panel.getHeight()).append(" viewport=").append(scroll.getHeight()).append(" content=").append(scroll.getChildAt(0).getHeight()).append(" dashboard=").append(dashboard.getHeight()).append(" row=").append(tile.getHeight()).append('\n');
                            Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); panel.draw(new Canvas(image));
                            try (FileOutputStream output = new FileOutputStream(new File(directory, "rotation-" + rotation + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, output); } finally { image.recycle(); }
                            if (scroll.getHeight() > height - top) throw new AssertionError("Scroll viewport extends below the landscape content frame: " + samples);
                            if (scroll.getChildAt(0).getHeight() > scroll.getHeight()) throw new AssertionError("Sixteen default-size controls should fit the safe viewport: " + samples);
                            MediaCardView card = dashboard.findViewWithTag("media-card");
                            if (card.condensed() || card.findViewWithTag("media-transport").getVisibility() != View.VISIBLE || card.getBottom() > dashboard.getHeight()) throw new AssertionError("Full media card must fit with all transport actions");
                            for (String id : prefs.actions("panel")) {
                                ViewGroup action = dashboard.findViewWithTag("control-" + id); TextView label = (TextView) action.getChildAt(1);
                                if (action.getBottom() > dashboard.getHeight() || action.getHeight() < Ui.dp(context, 48) || label.getBottom() > action.getHeight() || label.getLayout().getHeight() > label.getHeight()) throw new AssertionError("Control row or label is clipped: " + id);
                            }
                        } finally { owner.mediaSessions().remove(mediaObserver); }
                    }
                } catch (Throwable error) { failure[0] = error; }
            });
            Files.write(new File(directory, "measurements.txt").toPath(), samples.toString().getBytes(StandardCharsets.UTF_8));
            if (failure[0] != null) throw new AssertionError(failure[0]);
            return "PASS: control-rotation native bounds; " + samples;
        } finally { test.runOnMainSync(activity::finish); test.getUiAutomation().dropShellPermissionIdentity(); }
    }
    private static ScrollView findScroll(View view) {
        if (view instanceof ScrollView scroll) return scroll;
        if (view instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { ScrollView scroll = findScroll(group.getChildAt(i)); if (scroll != null) return scroll; }
        return null;
    }
}
