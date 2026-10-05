package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.Matrix;
import android.graphics.Bitmap;
import android.view.PixelCopy;
import android.os.Handler;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;

/** Production paint and input on an isolated emulator; no system toggle or media command. */
final class ControlMotionChecks {
    private final Instrumentation test;
    private Activity activity;
    private InterfaceCard card;
    private ControlScrollView scroll;
    private int assertions;
    private final float[] values = new float[9];
    private final Matrix matrix = new Matrix();
    ControlMotionChecks(Instrumentation test) { this.test = test; }
    private void require(boolean value, String message) { assertions++; if (!value) throw new AssertionError(message); }
    private void main(Runnable work) { test.runOnMainSync(work); }
    private void send(View target, long down, int action, float x, float y) { MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0); target.dispatchTouchEvent(event); event.recycle(); }
    private int redPixels(String name) throws Exception {
        Rect area = new Rect(); main(() -> { View tile = ((android.view.ViewGroup) scroll.findViewWithTag("control-tile-grid")).getChildAt(0); int[] location = new int[2]; tile.getLocationInWindow(location); area.set(location[0], location[1], location[0] + tile.getWidth(), location[1] + tile.getHeight()); });
        Bitmap image = Bitmap.createBitmap(area.width(), area.height(), Bitmap.Config.ARGB_8888); CountDownLatch ready = new CountDownLatch(1); int[] result = {-1};
        main(() -> PixelCopy.request(activity.getWindow(), area, image, code -> { result[0] = code; ready.countDown(); }, new Handler(Looper.getMainLooper())));
        require(ready.await(3, TimeUnit.SECONDS) && result[0] == PixelCopy.SUCCESS, "hardware paint captured: " + name);
        java.io.File directory = new java.io.File(activity.getFilesDir(), "control-motion"); if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException("fixture image directory");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(directory, name + ".png"))) { image.compress(Bitmap.CompressFormat.PNG, 100, output); }
        int red = 0; for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) { int color = image.getPixel(x, y); if (android.graphics.Color.red(color) > 200 && android.graphics.Color.green(color) < 80 && android.graphics.Color.blue(color) < 80) red++; } image.recycle(); return red;
    }
    private boolean filled(LevelSlider slider, float height) {
        Bitmap image = Bitmap.createBitmap(slider.getWidth(), slider.getHeight(), Bitmap.Config.ARGB_8888);
        slider.draw(new android.graphics.Canvas(image)); int color = image.getPixel(slider.getWidth() / 2, Math.round((slider.getHeight() - Ui.dp(activity, 18)) * height)); image.recycle();
        return android.graphics.Color.red(color) > 220 && android.graphics.Color.green(color) > 220;
    }
    String runLevels() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        LevelSlider[] slider = {null}; int[] commits = {0}; java.lang.reflect.Field animation = LevelSlider.class.getDeclaredField("valueAnimation"); animation.setAccessible(true);
        try {
            main(() -> { LinearLayout root = new LinearLayout(activity); slider[0] = new LevelSlider(activity, "外屏亮度", R.drawable.ic_ms_brightness_6, 5, 100, 5, v -> commits[0]++); slider[0].setEnabled(false); root.addView(slider[0], new LinearLayout.LayoutParams(Ui.dp(activity, 44), Ui.dp(activity, 160))); activity.setContentView(root); }); test.waitForIdleSync();
            main(() -> {
                require(!filled(slider[0], .5f) && !slider[0].getContentDescription().toString().contains("0%"), "unavailable level does not claim a measured zero");
                slider[0].setEnabled(true); slider[0].setValueAnimated(80);
                try {
                    android.animation.ValueAnimator clock = (android.animation.ValueAnimator) animation.get(slider[0]); require(clock != null && clock.getDuration() == 300, "visible async read starts a finite 300ms transition"); clock.pause(); clock.setCurrentPlayTime(150);
                    require(!filled(slider[0], .5f) && filled(slider[0], .7f), "midpoint renders fill rising from the bottom instead of jumping to 80 percent");
                    slider[0].setValueAnimated(80); require(animation.get(slider[0]) == clock, "repeated read does not restart the transition");
                    slider[0].setValueAnimated(60); require(animation.get(slider[0]) != clock && !filled(slider[0], .5f), "changed target starts at the current rendered fill");
                    slider[0].setValue(90); require(animation.get(slider[0]) == null && filled(slider[0], .2f), "direct user input takes over immediately");
                    slider[0].setValueAnimated(20); activity.setContentView(new android.widget.FrameLayout(activity)); require(animation.get(slider[0]) == null, "unmount cancels the value animator");
                } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
                require(commits[0] == 0, "display updates never write system brightness or volume");
            }); return "PASS: level loading; " + assertions + " assertions; rendered bottom-up fill, retarget, input takeover and release";
        } finally { main(activity::finish); }
    }
    String runReduced() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try { main(() -> { require(!android.animation.ValueAnimator.areAnimatorsEnabled(), "isolated emulator animation duration is zero"); LevelSlider slider = new LevelSlider(activity, "fixture", R.drawable.ic_ms_volume_up, 0, 100, 55, value -> { }); activity.setContentView(slider); }); test.waitForIdleSync(); main(() -> { LevelSlider slider = (LevelSlider) ((android.view.ViewGroup) activity.findViewById(android.R.id.content)).getChildAt(0); ControlFeedback feedback = slider.controlFeedback(); feedback.pressed(true); feedback.drag(1000); feedback.matrix(matrix); require(matrix.isIdentity(), "disabled motion has no press/drag transform"); feedback.pressed(false); feedback.releaseDrag(); feedback.matrix(matrix); require(matrix.isIdentity(), "disabled drag release remains at rest"); }); return "PASS: reduced control feedback; " + assertions + " assertions"; } finally { main(activity::finish); }
    }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        try {
            main(() -> {
                Prefs prefs = new Prefs(activity); prefs.data.edit().clear().putBoolean("panel_media", true).putInt("panel_columns", 4).commit();
                List<String> actions = new ArrayList<>(); for (ActionCatalog.Action action : ActionCatalog.BUILT_INS) actions.add(action.id()); prefs.saveActions("panel", actions);
                CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = prefs;
                int width = activity.getResources().getDisplayMetrics().widthPixels, height = activity.getResources().getDisplayMetrics().heightPixels;
                owner.placement = new DockGeometry.Placement(new DockGeometry.Box(0, 0, 0, 0), new DockGeometry.Box(0, 0, 0, 0), new DockGeometry.Box(0, 38, width, height - 104), DockGeometry.TOP, true);
                card = owner.buildPanelCard("controls", new DockGeometry.Box(0, 0, width, height), 38); activity.setContentView(card);
                scroll = card.findViewWithTag("control-grid-scroll");
                require(card.findViewWithTag("control-add") == null, "single editor entry replaces plus"); require(card.findViewWithTag("control-edit") != null && card.findViewWithTag("control-refresh") != null, "two top actions retained");
                require(card.findViewWithTag("control-brightness").getTag(R.id.control_feedback) instanceof ControlFeedback, "brightness opts into feedback"); require(card.findViewWithTag("control-volume").getTag(R.id.control_feedback) instanceof ControlFeedback, "volume opts into feedback"); require(card.findViewWithTag("media-card").getTag(R.id.control_feedback) instanceof ControlFeedback, "music opts into feedback");
            });
            test.waitForIdleSync();
            Rect bounds = new Rect(); main(() -> { scroll.getDrawingRect(bounds); card.offsetDescendantRectToMyCoords(scroll, bounds); });
            float x = bounds.exactCenterX(), y = bounds.top + bounds.height() * .2f, distance = ControlElastic.MAXIMUM * activity.getResources().getDisplayMetrics().density * .9f;
            long down = SystemClock.uptimeMillis();
            main(() -> { send(card, down, MotionEvent.ACTION_DOWN, x, y); send(card, down, MotionEvent.ACTION_MOVE, x, y + distance); require(Math.abs(scroll.elastic.grid() - ControlElastic.LIMIT) < .001f, "grid reaches fixed local limit"); require(scroll.elastic.whole() > 0, "whole page takes excess pull"); require(!scroll.framePending(), "held grid has no own animation frame"); });
            float[] initial = new float[2]; main(() -> { initial[0] = scroll.elastic.grid(); initial[1] = scroll.elastic.whole(); send(card, down, MotionEvent.ACTION_UP, x, y + distance); });
            SystemClock.sleep(32);
            main(() -> { require(scroll.elastic.grid() < initial[0] && scroll.elastic.whole() > 0, "grid returns while whole page is still restoring"); require(Math.abs(scroll.elastic.grid() / initial[0] - scroll.elastic.whole() / initial[1]) < .001f, "both share return progress"); });
            long grabbedAt = SystemClock.uptimeMillis(); float[] grabbed = new float[1]; main(() -> { send(card, grabbedAt, MotionEvent.ACTION_DOWN, x, y); grabbed[0] = scroll.elastic.load; }); SystemClock.sleep(100);
            main(() -> { require(scroll.elastic.load == grabbed[0] && !scroll.framePending(), "regrab freezes return before direction choice"); send(card, grabbedAt, MotionEvent.ACTION_CANCEL, x, y); require(!scroll.elastic.moving() && !scroll.framePending(), "cancel clears spring and callback"); });
            int[] clicks = {0}; main(() -> { ((android.view.ViewGroup) scroll.findViewWithTag("control-tile-grid")).getChildAt(0).setOnClickListener(v -> clicks[0]++); scroll.scrollTo(0, 0); });
            long reverseAt = SystemClock.uptimeMillis(); main(() -> { float firstX = bounds.left + bounds.width() / 8f, firstY = bounds.top + 24; send(card, reverseAt, MotionEvent.ACTION_DOWN, firstX, firstY); send(card, reverseAt, MotionEvent.ACTION_MOVE, firstX, firstY + distance); send(card, reverseAt, MotionEvent.ACTION_MOVE, firstX, firstY - 4); send(card, reverseAt, MotionEvent.ACTION_UP, firstX, firstY - 4); require(clicks[0] == 0, "reversing rubber band into short native scroll cannot click a tile"); scroll.reset(); });
            LevelSlider slider = card.findViewWithTag("control-brightness"); int[] geometry = new int[2]; String[] value = new String[1];
            main(() -> { slider.setEnabled(true); slider.setValue(55); value[0] = slider.getContentDescription().toString(); geometry[0] = slider.getWidth(); geometry[1] = slider.getHeight(); long at = SystemClock.uptimeMillis(); send(slider, at, MotionEvent.ACTION_DOWN, slider.getWidth() / 2f, slider.getHeight() * .4f); send(slider, at, MotionEvent.ACTION_MOVE, slider.getWidth() / 2f, slider.getHeight() * .2f); send(slider, at, MotionEvent.ACTION_CANCEL, slider.getWidth() / 2f, slider.getHeight() * .2f); require(slider.getContentDescription().toString().equals(value[0]), "slider cancellation preserves value"); require(slider.getWidth() == geometry[0] && slider.getHeight() == geometry[1], "paint feedback never changes slider layout"); });
            for (String tag : new String[]{"control-brightness", "control-volume", "media-card"}) {
                ControlFeedback[] feedback = new ControlFeedback[1]; float[] pressed = new float[1];
                main(() -> { feedback[0] = (ControlFeedback) card.findViewWithTag(tag).getTag(R.id.control_feedback); feedback[0].pressed(true); }); SystemClock.sleep(200);
                main(() -> { feedback[0].matrix(matrix); matrix.getValues(values); pressed[0] = values[0]; require(pressed[0] < 1, "module shrinks on down: " + tag); feedback[0].pressed(false); }); SystemClock.sleep(180);
                main(() -> { feedback[0].matrix(matrix); matrix.getValues(values); require(values[0] > pressed[0] && values[0] <= 1.0001f, "module grows monotonically without overshoot: " + tag); }); SystemClock.sleep(360);
                main(() -> { feedback[0].matrix(matrix); require(matrix.isIdentity(), "module settles: " + tag); });
            }
            main(() -> { scroll.reset(); android.view.ViewGroup grid = scroll.findViewWithTag("control-tile-grid"); scroll.scrollTo(0, grid.getChildAt(0).getHeight()); require(scroll.getScrollY() > 0, "material sample uses nonzero scroll"); scroll.elastic.drag(-40); View face = ((android.view.ViewGroup) grid.getChildAt(8)).getChildAt(0); Matrix actual = new Matrix(); face.transformMatrixToGlobal(actual); float[] center = {face.getWidth() / 2f, face.getHeight() / 2f}; actual.mapPoints(center); Matrix viewport = new Matrix(); scroll.transformMatrixToGlobal(viewport); float[] origin = {0, 0}; viewport.mapPoints(origin); float strain = Math.abs(scroll.elastic.grid()) / ControlElastic.LIMIT, anchor = scroll.getHeight(); float expectedY = origin[1] + anchor + (1 + strain * .025f) * (center[1] - origin[1] - anchor) + scroll.elastic.grid() * activity.getResources().getDisplayMetrics().density; actual.reset(); face.transformMatrixToGlobal(actual); ControlFeedback.materialPosition(face, actual, new Matrix(), new Matrix(), new Matrix(), new Matrix()); float[] painted = {face.getWidth() / 2f, face.getHeight() / 2f}; actual.mapPoints(painted); require(Math.abs(painted[1] - expectedY) < .05f, "glass sampling follows viewport-space paint after scrolling: " + painted[1] + " vs " + expectedY); scroll.reset(); });
            RuntimeVisuals.Cell[] tileSurface = new RuntimeVisuals.Cell[1]; main(() -> { scroll.reset(); scroll.scrollTo(0, 0); tileSurface[0] = (RuntimeVisuals.Cell) ((android.view.ViewGroup) scroll.findViewWithTag("control-tile-grid")).getChildAt(0); ((android.view.ViewGroup) tileSurface[0]).getChildAt(0).setBackgroundColor(android.graphics.Color.RED); }); SystemClock.sleep(100);
            int restingPixels = redPixels("tile-resting"); main(() -> tileSurface[0].setPressed(true)); SystemClock.sleep(130); int pressedPixels = redPixels("tile-pressed");
            require(restingPixels > 0 && pressedPixels < restingPixels * .9f, "hardware display list records tile press: " + restingPixels + " -> " + pressedPixels);
            main(() -> tileSurface[0].setPressed(false)); SystemClock.sleep(400); int returnedPixels = redPixels("tile-returned"); require(Math.abs(returnedPixels - restingPixels) < restingPixels * .05f, "hardware tile returns to original geometry");
            main(() -> { long at = SystemClock.uptimeMillis(); send(card, at, MotionEvent.ACTION_DOWN, x, y); send(card, at, MotionEvent.ACTION_MOVE, x, y + distance); send(card, at, MotionEvent.ACTION_UP, x, y + distance); }); SystemClock.sleep(160);
            main(() -> { for (String tag : new String[]{"control-edit", "control-refresh"}) { ControlFeedback feedback = (ControlFeedback) card.findViewWithTag(tag).getTag(R.id.control_feedback); feedback.matrix(matrix); require(!matrix.isIdentity(), "top action has independent recoil: " + tag); } activity.setContentView(new android.widget.FrameLayout(activity)); require(!scroll.framePending() && !scroll.elastic.moving(), "unmount releases grid return"); });
            return "PASS: control motion; " + assertions + " assertions; same-frame restoration, regrab, slider cancel, three smooth modules, two action recoils, fixed hit bounds and unmount";
        } finally { main(() -> { if (card != null) card.release(); activity.finish(); }); }
    }
}
