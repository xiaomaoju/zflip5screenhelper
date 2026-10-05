package io.github.flipcover.controls;

import android.app.Instrumentation;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.List;

/** Emulator-only native pixels and cache checks; no physical overlay routing claim. */
final class ChromeShadowChecks {
    private int assertions;
    private void require(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
    static String run(Instrumentation test) throws Exception {
        ChromeShadowChecks checks = new ChromeShadowChecks(); Throwable[] failure = {null};
        test.runOnMainSync(() -> { try { checks.check(test.getTargetContext()); } catch (Throwable error) { failure[0] = error; } });
        if (failure[0] != null) throw new AssertionError("Chrome shadow check failed", failure[0]);
        checks.checkSettings(test);
        return "PASS: chrome shadow; " + checks.assertions + " assertions; native raw renders in files/chrome-shadow-raw; no Samsung physical overlay validation";
    }
    private static final class Square extends Drawable {
        final Paint paint = new Paint(); int draws;
        Square() { paint.setColor(Color.WHITE); }
        @Override public void draw(Canvas canvas) { draws++; Rect bounds = getBounds(); canvas.drawRect(bounds.left + 4, bounds.top + 4, bounds.right - 4, bounds.bottom - 4, paint); }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public int getAlpha() { return paint.getAlpha(); }
        @Override public void setTint(int color) { paint.setColor(color); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
    private void check(Context target) throws Exception {
        Square square = new Square(); ChromeShadowDrawable icon = new ChromeShadowDrawable(square, 2);
        icon.setBounds(20, 20, 44, 44); Bitmap pixels = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888); Canvas canvas = new Canvas(pixels); icon.draw(canvas);
        int near = Color.alpha(pixels.getPixel(23, 32)), far = Color.alpha(pixels.getPixel(21, 32));
        require(near > far && far > 0 && near < 166, "shadow fades gradually outside the original silhouette");
        require(pixels.getPixel(32, 32) == Color.WHITE && icon.getBounds().equals(new Rect(20, 20, 44, 44)), "foreground and bounds stay intact without ConstantState");
        int draws = square.draws; pixels.eraseColor(Color.TRANSPARENT); icon.draw(canvas);
        require(square.draws == draws + 1, "unchanged-size redraw reuses its alpha mask");
        icon.setAlpha(128); pixels.eraseColor(Color.TRANSPARENT); icon.draw(canvas);
        require(Math.abs(Color.alpha(pixels.getPixel(23, 32)) - near / 2f) <= 2, "fading scales the shadow once, rather than squaring opacity");
        draws = square.draws; icon.setAlpha(255); icon.setBounds(60, 60, 84, 84); pixels.eraseColor(Color.TRANSPARENT); icon.draw(canvas);
        require(square.draws == draws + 1 && pixels.getPixel(72, 72) == Color.WHITE && pixels.getPixel(32, 32) == Color.TRANSPARENT, "moving same-size bounds reuses and translates the mask");
        draws = square.draws; icon.setBounds(60, 60, 92, 92); icon.draw(canvas);
        require(square.draws == draws + 2, "a real size change regenerates the mask");
        icon.setTint(Color.BLACK); pixels.eraseColor(Color.TRANSPARENT); icon.draw(canvas);
        require(pixels.getPixel(76, 76) == Color.BLACK, "foreground tint reaches the source drawable"); pixels.recycle();
        Context context = new ContextWrapper(target) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) { return super.getSharedPreferences("chrome-shadow-checks", mode); }
        };
        Prefs prefs = new Prefs(context); File directory = new File(target.getFilesDir(), "chrome-shadow-raw");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("Cannot create chrome-shadow-raw");
        try {
            prefs.data.edit().putBoolean("battery_percent", true).putBoolean("status_time", true).putBoolean("status_battery", true).putBoolean("status_wifi", true).putBoolean("status_speed", false).putBoolean("status_alarm", false).putBoolean("status_cellular", false).putInt("per_page", 5).putString("pinned_action", "app_hub").commit();
            prefs.saveActions("dock", List.of("back", "home", "recents", "controls"));
            Bitmap panelReference = null;
            for (String style : new String[]{"contrast", "light", "dark", "black"}) {
                prefs.data.edit().putString("chrome_style", style).commit();
                require(Prefs.validChrome(style) && prefs.chromeStyle().equals(style), "legacy appearance remains valid: " + style);
                StatusBarView panelStatus = status(context, prefs, true); Bitmap panel = render(panelStatus, 748, panelStatus.heightPixels());
                if (panelReference == null) panelReference = panel; else { require(panel.sameAs(panelReference), "control-panel status appearance stays independent: " + style); panel.recycle(); }
                for (int background : new int[]{Color.WHITE, 0xFF808080, 0xFF15171C}) {
                    FrameLayout screen = new FrameLayout(context); screen.setBackgroundColor(background);
                    StatusBarView floatingStatus = status(context, prefs, false); screen.addView(floatingStatus, new FrameLayout.LayoutParams(-1, floatingStatus.heightPixels()));
                    float density = context.getResources().getDisplayMetrics().density;
                    DockGeometry.Placement placement = DockGeometry.resolve(748, 720, List.of(new DockGeometry.Box(379, 654, 369, 66)), density, 3, .46f, .088f, true);
                    DockView dock = new DockView(context, prefs, placement, 0, new DockView.Listener() { public void action(String id) { } public void configure() { } public void toggleVisibility() { } });
                    FrameLayout.LayoutParams dockParams = new FrameLayout.LayoutParams(placement.touch().width(), placement.touch().height()); dockParams.leftMargin = placement.touch().x(); dockParams.topMargin = placement.touch().y(); screen.addView(dock, dockParams);
                    Bitmap bitmap = render(screen, 748, 720);
                    try (FileOutputStream output = new FileOutputStream(new File(directory, style + "-" + Integer.toHexString(background) + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, output); } finally { bitmap.recycle(); }
                }
            }
            panelReference.recycle();
            StatusBarView changing = status(context, prefs, false); prefs.data.edit().putString("chrome_style", "contrast").commit(); changing.updateOptions(); Bitmap shadowStatus = render(changing, 748, changing.heightPixels());
            prefs.data.edit().putString("chrome_style", "dark").commit(); changing.updateOptions(); Bitmap darkStatus = render(changing, 748, changing.heightPixels());
            require(!shadowStatus.sameAs(darkStatus), "live appearance update refreshes status icons and text"); shadowStatus.recycle(); darkStatus.recycle();
            prefs.data.edit().putString("chrome_style", "contrast").commit();
            for (int scale : new int[]{50, 100, 150}) {
                StatusBarView safe = status(context, prefs, false); safe.previewScale(scale); safe.previewSafeArea(8, 32);
                Bitmap bitmap = render(safe, 748, Math.round(Ui.dp(context, 20) * scale / 100f)); boolean clear = true;
                for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < 748; x++) if ((x < Ui.dp(context, 8) || x >= 748 - Ui.dp(context, 32)) && Color.alpha(bitmap.getPixel(x, y)) != 0) clear = false;
                require(clear, "soft shadows respect physical safe-area clipping at " + scale + "%"); bitmap.recycle();
            }
            checkBatteryAndClock(context, prefs);
            checkBatteryTones(context, prefs);
            checkSharedStatusShadow(context, prefs);
        } finally { target.deleteSharedPreferences("chrome-shadow-checks"); }
    }
    private void checkBatteryAndClock(Context context, Prefs prefs) throws Exception {
        prefs.data.edit().putString("chrome_style", "dark").putBoolean("battery_percent", false).putBoolean("status_time", false).putBoolean("status_wifi", false).commit();
        int foreground = Ui.chromeColor(prefs), previous = -1; Rect footprint = null; Bitmap empty = null;
        for (int level : new int[]{-1, 0, 25, 50, 75, 100}) {
            StatusBarView view = status(context, prefs, false);
            Field battery = StatusBarView.class.getDeclaredField("battery"), charging = StatusBarView.class.getDeclaredField("charging"); battery.setAccessible(true); charging.setAccessible(true); battery.set(view, level); charging.set(view, false);
            Bitmap bitmap = render(view, 748, view.heightPixels()); int filled = 0, track = 0; Rect bounds = new Rect(748, bitmap.getHeight(), 0, 0);
            for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < 748; x++) {
                int pixel = bitmap.getPixel(x, y); if (pixel == Color.WHITE) filled++; if (pixel == 0xFFC3C3C7) track++;
                if (Color.alpha(pixel) > 0) { bounds.left = Math.min(bounds.left, x); bounds.top = Math.min(bounds.top, y); bounds.right = Math.max(bounds.right, x + 1); bounds.bottom = Math.max(bounds.bottom, y + 1); }
            }
            if (level < 0) { require(filled == 0 && track > 0, "unknown battery does not invent charge"); empty = bitmap; footprint = bounds; }
            else {
                require(bounds.equals(footprint), "battery silhouette is stable at " + level + "%");
                if (level == 0) require(bitmap.sameAs(empty), "empty battery keeps the full gray silhouette");
                else require(filled > previous, "filled battery area increases at " + level + "%");
                if (level == 100) require(track == 0, "full charge fills the complete battery and contact");
                else require(track > 0, "partial charge retains its gray remaining segment");
                previous = filled; bitmap.recycle();
            }
        }
        empty.recycle(); prefs.data.edit().putBoolean("status_battery", false).putBoolean("status_time", true).commit();
        for (int scale : new int[]{70, 100}) {
            StatusBarView view = status(context, prefs, false); view.previewScale(scale); Bitmap bold = render(view, 748, view.heightPixels()), normal = Bitmap.createBitmap(748, view.heightPixels(), Bitmap.Config.ARGB_8888);
            Paint normalPaint = new Paint(Paint.ANTI_ALIAS_FLAG); normalPaint.setColor(foreground); normalPaint.setTextSize(context.getResources().getDisplayMetrics().density * StatusBarView.CLOCK_SIZE_DP);
            Canvas normalCanvas = new Canvas(normal); float factor = scale / 100f; normalCanvas.translate(Ui.dp(context, prefs.statusSafeLeft()), 0); normalCanvas.scale(factor, factor);
            normalCanvas.drawText("16:12", Ui.dp(context, 6), view.heightPixels() / (2f * factor) - (normalPaint.ascent() + normalPaint.descent()) / 2, normalPaint);
            long boldInk = 0, normalInk = 0;
            for (int y = 0; y < bold.getHeight(); y++) for (int x = 0; x < 748; x++) { boldInk += Color.alpha(bold.getPixel(x, y)); normalInk += Color.alpha(normal.getPixel(x, y)); }
            require(boldInk > normalInk * 1.12, "time glyphs carry more visible ink at " + scale + "% without changing size or baseline: " + boldInk + "/" + normalInk); bold.recycle(); normal.recycle();
        }
    }
    private void checkBatteryTones(Context context, Prefs prefs) throws Exception {
        prefs.data.edit().putBoolean("status_time", false).putBoolean("status_wifi", false).putBoolean("status_battery", true).putBoolean("battery_percent", false).commit();
        for (String style : new String[]{"contrast", "light", "dark", "black"}) {
            prefs.data.edit().putString("chrome_style", style).commit(); int previous = 0;
            for (int level : new int[]{25, 75}) {
                StatusBarView view = status(context, prefs, false); Field charge = StatusBarView.class.getDeclaredField("battery"), charging = StatusBarView.class.getDeclaredField("charging"); charge.setAccessible(true); charging.setAccessible(true); charge.set(view, level); charging.set(view, false);
                Bitmap bitmap = render(view, 748, view.heightPixels()); int filled = 0, track = 0, firstFilled = 748, lastFilled = 0, lastTrack = 0;
                for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < 748; x++) { int pixel = bitmap.getPixel(x, y); if (pixel == Color.WHITE) { filled++; firstFilled = Math.min(firstFilled, x); lastFilled = Math.max(lastFilled, x); } if (pixel == 0xFFC3C3C7) { track++; lastTrack = Math.max(lastTrack, x); } }
                require(filled > previous && track > 0 && firstFilled < lastFilled && lastFilled < lastTrack, "battery has proportional white charge and light-gray empty track at " + level + "% in " + style); previous = filled; bitmap.recycle();
            }
        }
    }
    private void checkSharedStatusShadow(Context context, Prefs prefs) throws Exception {
        prefs.data.edit().putString("chrome_style", "contrast").putBoolean("status_time", true).putBoolean("status_battery", true).putBoolean("status_wifi", true).commit();
        StatusBarView view = status(context, prefs, false); render(view, 748, view.heightPixels()).recycle();
        Field radius = ChromeShadowDrawable.class.getDeclaredField("radius"), color = ChromeShadowDrawable.class.getDeclaredField("shadowPaint"), mask = ChromeShadowDrawable.class.getDeclaredField("shadow");
        radius.setAccessible(true); color.setAccessible(true); mask.setAccessible(true);
        Drawable[] sources = new Drawable[3]; String[] names = {"clockDrawable", "batteryDrawable", "wifiIcon"};
        for (int i = 0; i < names.length; i++) { Field field = StatusBarView.class.getDeclaredField(names[i]); field.setAccessible(true); sources[i] = (Drawable) field.get(view); require(sources[i] instanceof ChromeShadowDrawable, names[i] + " uses the common icon renderer"); }
        for (int i = 0; i < 2; i++) require(radius.getFloat(sources[i]) == radius.getFloat(sources[2]) && ((Paint) color.get(sources[i])).getColor() == ((Paint) color.get(sources[2])).getColor(), names[i] + " matches Wi-Fi blur and opacity");
        Object clockMask = mask.get(sources[0]), batteryMask = mask.get(sources[1]); render(view, 748, view.heightPixels()).recycle();
        require(clockMask != null && batteryMask != null && clockMask == mask.get(sources[0]) && batteryMask == mask.get(sources[1]), "unchanged clock and charge reuse cached masks");
        Field battery = StatusBarView.class.getDeclaredField("battery"), clock = StatusBarView.class.getDeclaredField("clock"); battery.setAccessible(true); clock.setAccessible(true);
        battery.set(view, 25); render(view, 748, view.heightPixels()).recycle();
        require(batteryMask != mask.get(sources[1]) && clockMask == mask.get(sources[0]), "charge changes refresh only the battery mask"); batteryMask = mask.get(sources[1]);
        clock.set(view, "16:13"); render(view, 748, view.heightPixels()).recycle();
        require(clockMask != mask.get(sources[0]) && batteryMask == mask.get(sources[1]), "time changes refresh only the clock mask");
        Field batteryForeground = StatusBarView.class.getDeclaredField("batteryForeground"); batteryForeground.setAccessible(true); Drawable body = (Drawable) batteryForeground.get(view);
        Bitmap fade = Bitmap.createBitmap(80, 40, Bitmap.Config.ARGB_8888); body.setAlpha(128); body.draw(new Canvas(fade));
        require(Color.alpha(fade.getPixel(3, body.getBounds().height() / 2)) == 128, "battery foreground fades once across overlapping charge/track shapes"); body.setAlpha(255); fade.recycle();
        prefs.data.edit().putString("chrome_style", "light").commit(); view.updateOptions();
        for (String name : names) { Field field = StatusBarView.class.getDeclaredField(name); field.setAccessible(true); require(!(field.get(view) instanceof ChromeShadowDrawable), "manual light mode has no shadow wrapper: " + name); }
        Bitmap actual = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888), legacy = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888); Paint a = new Paint(Paint.ANTI_ALIAS_FLAG), b = new Paint(Paint.ANTI_ALIAS_FLAG);
        a.setColor(Color.WHITE); b.setColor(Color.WHITE); ChromeShadowDrawable.applyShadow(a, 2.5f); b.setShadowLayer(2.5f, 0, 2.5f / 4, 0xA6000000);
        new Canvas(actual).drawCircle(20, 20, 4, a); new Canvas(legacy).drawCircle(20, 20, 4, b);
        require(actual.sameAs(legacy), "separate pagination/entry Paint shadow recipe remains unchanged"); actual.recycle(); legacy.recycle();
    }
    private void checkSettings(Instrumentation test) {
        Prefs prefs = new Prefs(test.getTargetContext()); String original = prefs.data.getString("chrome_style", null);
        MainActivity activity = (MainActivity) test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("section", "appearance"));
        try {
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                View root = activity.findViewById(android.R.id.content);
                View shadow = root.findViewWithTag("chrome-contrast");
                require(shadow != null && shadow.getContentDescription().toString().equals("透明 · 柔和阴影"), "settings describes the actual default treatment");
                for (String style : new String[]{"dark", "light", "black", "contrast"}) {
                    View choice = root.findViewWithTag("chrome-" + style); choice.performClick();
                    require(choice.isSelected() && prefs.chromeStyle().equals(style), "appearance choice saves and selects once: " + style);
                }
            });
        } finally {
            test.runOnMainSync(activity::finish); SharedPreferences.Editor restore = prefs.data.edit();
            if (original == null) restore.remove("chrome_style"); else restore.putString("chrome_style", original); restore.commit();
        }
    }
    private static StatusBarView status(Context context, Prefs prefs, boolean panel) throws Exception {
        StatusBarView view = new StatusBarView(context, prefs, panel);
        for (String key : new String[]{"clock", "battery", "wifi", "charging"}) { Field field = StatusBarView.class.getDeclaredField(key); field.setAccessible(true); field.set(view, key.equals("clock") ? "16:12" : key.equals("battery") ? 73 : true); }
        return view;
    }
    private static Bitmap render(View view, int width, int height) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY)); view.layout(0, 0, width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); view.draw(new Canvas(bitmap)); return bitmap;
    }
}
