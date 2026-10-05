package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.FrameMetrics;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.FrameLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.kyant.backdrop.catalog.components.LiquidTensionGeometry;
import com.kyant.backdrop.catalog.components.LiquidTensionMotion;

/** Actual shared material pixels and alternating frame costs on a disposable emulator. */
final class LiquidTensionChecks {
    private final Instrumentation test;
    private Activity activity;
    private NotificationCenterView center;
    private PanelGlassSession session;
    private int assertions;
    private final ArrayList<List<StatusBarNotification>> clearRequests = new ArrayList<>();
    private final StringBuilder report = new StringBuilder();
    LiquidTensionChecks(Instrumentation test) { this.test = test; }
    private void main(Runnable action) { test.runOnMainSync(action); }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); assertions++; }
    private int dp(float value) { return Ui.dp(activity, value); }
    private void idle() { test.waitForIdleSync(); SystemClock.sleep(120); test.waitForIdleSync(); }

    String runHighlight() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(this::mount); idle(); NotificationSwipeRow row = row(1); ValueAnimator[] clock = {null};
            main(() -> row.showActions(true));
            long deadline = SystemClock.uptimeMillis() + 2400;
            do { SystemClock.sleep(16); main(() -> { clock[0] = (ValueAnimator) field(row, "highlightAnimation"); if (clock[0] != null) clock[0].pause(); }); } while (clock[0] == null && SystemClock.uptimeMillis() < deadline);
            require(clock[0] != null && clock[0].getDuration() == 300, "notification swipe starts a bounded 300ms highlight after separation");
            for (int elapsed : new int[]{0, 150, 299, 300}) {
                main(() -> clock[0].setCurrentPlayTime(elapsed)); idle();
                LiquidTensionGeometry geometry = (LiquidTensionGeometry) field(row, "tension");
                require(Math.abs(geometry.getHighlight() - (elapsed == 0 ? 0 : elapsed == 150 ? .5f : 1)) < .001f, "shared shader receives gradual highlight at " + elapsed + "ms");
                screenshot("swipe-highlight-" + elapsed).recycle();
            }
            main(() -> row.showActions(false)); idle();
            main(() -> { require(field(row, "highlightAnimation") == null, "reverse swipe cancels the old highlight animator"); session.close(); });
            return "PASS: " + assertions + " notification highlight assertions; actual settled motion and shader fade, emulator only";
        } finally { main(() -> { if (session != null) session.close(); activity.finish(); }); }
    }
    String runMotion() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(this::mount); idle();
            NotificationSwipeRow two = row(1), one = row(0);
            View settings = (View) field(two, "settingsFace"), clear = (View) field(two, "clearFace");
            LiquidTensionMotion motion = (LiquidTensionMotion) field(two, "actionMotion");
            LiquidTensionGeometry geometry = (LiquidTensionGeometry) field(two, "tension");
            int width = two.getWidth(), height = two.getHeight(); long at = SystemClock.uptimeMillis();
            float initial = clear.getTranslationX();
            main(() -> { send(two, at, 0, MotionEvent.ACTION_DOWN, 0); send(two, at, 100, MotionEvent.ACTION_MOVE, -dp(9)); }); SystemClock.sleep(100);
            require(clear.getTranslationX() < initial, "initial adhesion pulls the first button slightly left");
            require(geometry.getRadii()[2] < clear.getWidth() * .3f, "first exposed lobe starts as a small liquid bud");
            float budRadius = geometry.getRadii()[2]; screenshot("motion-first-bud").recycle();
            float smallScale = clear.getScaleX(); require(smallScale < PanelUi.ACTION_SCALE * .3f && clear.getScaleX() == clear.getScaleY(), "actual circular face and its child icon start at a small uniform scale");
            main(() -> send(two, at, 200, MotionEvent.ACTION_MOVE, -dp(24))); SystemClock.sleep(140); idle();
            require(clear.getTranslationX() > initial + dp(4), "first button visibly moves right while the card goes left");
            require(!motion.detached(0) && Math.abs(settings.getTranslationX() - initial) < .1f, "second button waits during the first connected stretch");
            require(geometry.getRadii()[2] > budRadius && geometry.getRadii()[1] < settings.getWidth() * .3f, "first bud grows while the second stays tucked into the card");
            require(clear.getScaleX() > smallScale && clear.getScaleX() < PanelUi.ACTION_SCALE && settings.getScaleX() == smallScale, "first actual button lerps larger while the second remains small");
            screenshot("motion-first-connected").recycle();
            main(() -> send(two, at, 300, MotionEvent.ACTION_MOVE, -dp(36)));
            float maximum = 0;
            for (int frame = 0; frame < 24; frame++) {
                SystemClock.sleep(16); maximum = Math.max(maximum, clear.getTranslationX());
                require(Math.abs(settings.getTranslationX() - initial) < .1f, "first release cannot start the second button at this distance");
                if (frame == 5) screenshot("motion-first-pop").recycle();
            }
            require(motion.detached(0) && maximum > dp(.5f), "severed first button overshoots its dock with inertia");
            require(maximum <= dp(3.6f), "first outward bounce stays inside the reserved edge margin");
            main(() -> send(two, at, 500, MotionEvent.ACTION_MOVE, -dp(60))); SystemClock.sleep(180); idle();
            require(settings.getTranslationX() > initial + dp(4) && geometry.getJoins()[2] == 0, "second button moves right only after the first neck is completely gone; position=" + settings.getTranslationX() + ", initial=" + initial + ", firstJoin=" + geometry.getJoins()[2]);
            screenshot("motion-second-connected").recycle();
            main(() -> send(two, at, 600, MotionEvent.ACTION_MOVE, -dp(72))); maximum = 0;
            for (int frame = 0; frame < 24; frame++) { SystemClock.sleep(16); maximum = Math.max(maximum, settings.getTranslationX()); }
            require(maximum > dp(.5f), "second button also has an outward release bounce");
            awaitActions(two); idle();
            require(clear.getTranslationX() == 0 && settings.getTranslationX() == 0 && motion.ready(), "both buttons settle exactly at existing docks");
            require(clear.getScaleX() == PanelUi.ACTION_SCALE && settings.getScaleX() == PanelUi.ACTION_SCALE, "actual button faces and icons finish at their original resting scale");
            float restingRadius = dp(PanelUi.SLOT) * PanelUi.ACTION_SCALE / 2f;
            require(Math.abs(geometry.getRadii()[1] - restingRadius) < .1f && Math.abs(geometry.getRadii()[2] - restingRadius) < .1f, "settled material returns to the existing full-size circles");
            require(two.getWidth() == width && two.getHeight() == height, "physics preserves resting layout geometry");
            screenshot("motion-rest").recycle();
            main(() -> send(two, at, 1000, MotionEvent.ACTION_UP, -dp(72))); SystemClock.sleep(800); awaitActions(two);
            require(clearRequests.isEmpty(), "ordinary two-stage release never clears");
            long reverse = SystemClock.uptimeMillis(); float before = settings.getTranslationX();
            main(() -> { send(two, reverse, 0, MotionEvent.ACTION_DOWN, 0); send(two, reverse, 100, MotionEvent.ACTION_MOVE, dp(24)); });
            require(settings.getTranslationX() == before, "reverse input retains the exact current button pose before the next physics frame");
            SystemClock.sleep(180); require(settings.getTranslationX() < before - dp(3), "reversal brings the second button back toward the card");
            main(() -> send(two, reverse, 200, MotionEvent.ACTION_CANCEL, dp(24))); idle();
            require(!(boolean) field(two, "actionFrameQueued") && settings.getTranslationX() == 0, "cancellation drops the clock and restores the original open pose");
            main(() -> two.showActions(false)); SystemClock.sleep(800); awaitActions(two);
            long single = SystemClock.uptimeMillis(); View face = (View) field(one, "settingsFace");
            main(() -> { send(one, single, 0, MotionEvent.ACTION_DOWN, 0); send(one, single, 100, MotionEvent.ACTION_MOVE, -dp(36)); }); maximum = 0;
            for (int frame = 0; frame < 24; frame++) { SystemClock.sleep(16); maximum = Math.max(maximum, face.getTranslationX()); }
            require(maximum > dp(.5f) && (float) field(one, "mergeProgress") == 0, "single-button ongoing row bounces without gaining a clear gesture");
            main(() -> send(one, single, 600, MotionEvent.ACTION_UP, -dp(36))); SystemClock.sleep(800); awaitActions(one);
            long bytes = session.bytes(); require(session.captures == 0 && session.preparations == 1, "motion reuses the existing background without new captures");
            main(() -> { two.showActions(true); }); SystemClock.sleep(40);
            main(() -> session.close()); idle();
            main(() -> { ((ViewGroup) two.getParent()).removeView(two); }); idle();
            require(!(boolean) field(two, "actionFrameQueued") && session.bytes() == 0 && bytes > 0, "detach cancels motion frames and session releases its texture");
            return "PASS: " + assertions + " moving tension assertions; actual View positions and GPU material, no physical Samsung feel acceptance";
        } finally { main(() -> { if (session != null) session.close(); activity.finish(); }); }
    }
    private void awaitActions(NotificationSwipeRow row) {
        long deadline = SystemClock.uptimeMillis() + 1600; boolean[] done = {false};
        do { SystemClock.sleep(16); main(() -> done[0] = !(boolean) field(row, "actionFrameQueued")); } while (!done[0] && SystemClock.uptimeMillis() < deadline);
        require(done[0], "button motion and merge stop their frame clock");
    }

    /** Same fixture can run against the approved old APK: no motion-only API or varying wait. */
    String runStaticMerge() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(this::mount); idle(); NotificationSwipeRow two = row(1); int width = (int) field(two, "actionWidth"); long at = SystemClock.uptimeMillis();
            main(() -> { send(two, at, 0, MotionEvent.ACTION_DOWN, 0); send(two, at, 100, MotionEvent.ACTION_MOVE, -width - dp(60)); });
            SystemClock.sleep(900); main(() -> two.surface.jumpDrawablesToCurrentState()); idle();
            require((float) field(two, "mergeProgress") > 0 && (float) field(two, "mergeProgress") < 1, "same held partial merge after all transient ripple and release motion");
            require(!two.surface.isPressed(), "departing card has no pressed overlay");
            screenshot("merging-static").recycle();
            return "PASS: " + assertions + " static merge assertions; cardX=" + two.surface.getTranslationX() + ", merge=" + field(two, "mergeProgress");
        } finally { main(() -> { if (session != null) session.close(); activity.finish(); }); }
    }

    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(this::mount); idle();
            NotificationSwipeRow one = row(0), two = row(1);
            checkSequence(two);
            checkCompleteIcon(two);
            checkPixels(one, 2, "one-button"); checkPixels(two, 3, "two-buttons");
            checkMerge(one, two);
            NotificationSwipeRow measured = row(1);
            main(() -> measured.showActions(true)); SystemClock.sleep(800); idle();
            long bytes = session.bytes();
            measure(measured, true, "warmup", 1200);
            measure(measured, false, "baseline-A", 3000); measure(measured, true, "tension-A", 3000);
            measure(measured, true, "tension-B", 3000); measure(measured, false, "baseline-B", 3000);
            main(() -> { measured.glass(session); measured.surface.setTranslationX(-dp(PanelUi.SLOT * 2)); measured.invalidate(); }); idle();
            require(session.bytes() == bytes && session.captures == 0 && session.preparations == 1, "dragging borrows the same texture without capturing or preparing more bitmaps");
            main(() -> { session.close(); require(!session.active(), "closing releases the material session"); });
            require(field(session, "tensionRenderer") == null && field(session, "tensionSource") == null && field(measured, "glass") == null, "closed session releases cached shader, texture reference and row link");
            write("performance.txt", report.toString());
            return "PASS: " + assertions + " tension assertions; " + report + "; emulator results, no Samsung hardware acceptance";
        } finally { main(() -> { if (session != null) session.close(); activity.finish(); }); }
    }
    String runDelete() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try { main(this::mount); idle(); checkMerge(row(0), row(1)); return "PASS: " + assertions + " immediate threshold delete assertions; one request before UP, frozen merged pair, lerp fade, confirmed exit and rejection recovery"; }
        finally { main(() -> { if (session != null) session.close(); activity.finish(); }); }
    }
    private Object field(Object owner, String name) {
        try { java.lang.reflect.Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private NotificationSwipeRow row(int index) {
        ViewGroup list = center.findViewWithTag("notification-list"); return (NotificationSwipeRow) ((ViewGroup) list.getChildAt(index)).getChildAt(0);
    }
    private void mount() {
        FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(0xFF406A98);
        center = new NotificationCenterView(activity, new NotificationCenterView.Actions() {
            public void open(StatusBarNotification item) { }
            public void settings(StatusBarNotification item) { }
            public void permission() { }
            public void clear(List<StatusBarNotification> items) { clearRequests.add(List.copyOf(items)); }
        });
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(-1, -2); params.setMargins(dp(12), dp(72), dp(12), 0); root.addView(center, params); activity.setContentView(root);
        center.update(true, List.of(notice(1, true), notice(2, false)));
        Bitmap backdrop = Bitmap.createBitmap(activity.getResources().getDisplayMetrics().widthPixels, activity.getResources().getDisplayMetrics().heightPixels, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(backdrop); canvas.drawColor(0xFF406A98); Paint paint = new Paint();
        paint.setShader(new android.graphics.LinearGradient(0, 0, backdrop.getWidth(), backdrop.getHeight(), 0xFF567FAC, 0xFF7E466D, android.graphics.Shader.TileMode.CLAMP)); canvas.drawRect(0, 0, backdrop.getWidth(), backdrop.getHeight(), paint);
        session = new PanelGlassSession(activity, activity.getDisplay()); session.fixture(backdrop); session.attach(center, true);
    }
    private StatusBarNotification notice(int id, boolean ongoing) {
        Notification value = new Notification.Builder(test.getTargetContext(), "tension-fixture").setSmallIcon(R.drawable.ic_ms_notifications).setContentTitle(ongoing ? "USB 用于手机充电" : "通知张力测试").setContentText(ongoing ? "一个按钮：卡片与设置相连" : "两个按钮：卡片、设置、清除相连").setWhen(100 - id).build();
        if (ongoing) value.flags |= Notification.FLAG_ONGOING_EVENT;
        String pkg = test.getTargetContext().getPackageName(); return new StatusBarNotification(pkg, pkg, id, "tension", android.os.Process.myUid(), 0, 0, value, android.os.Process.myUserHandle(), 100 - id);
    }
    private void checkPixels(NotificationSwipeRow row, int expected, String name) throws Exception {
        main(() -> row.showActions(true)); SystemClock.sleep(800); idle();
        LiquidTensionGeometry geometry = (LiquidTensionGeometry) field(row, "tension"); require(geometry.getCount() == expected, "only visible card and action faces enter the group: " + name);
        int[] rowPosition = new int[2]; main(() -> row.getLocationOnScreen(rowPosition));
        float[] shapes = geometry.getShapes();
        int x = Math.round(rowPosition[0] + gapCenter(shapes, 0, 1)), y = Math.round(rowPosition[1] + shapes[5]);
        Bitmap resting = screenshot(name + "-resting"); int restPixel = resting.getPixel(x, y); resting.recycle();
        for (int i = 1; i < geometry.getCount(); i++) require(geometry.getJoins()[i] == 0, "every button finishes splitting at the settled endpoint: " + name);
        main(() -> row.glass(null)); idle(); Bitmap separated = screenshot(name + "-baseline"); int basePixel = separated.getPixel(x, y); separated.recycle();
        require(colorDifference(restPixel, basePixel) < 4, "settled card-to-button gap is fully clear: " + name);
        main(() -> row.glass(session)); idle();
        float endpoint = row.surface.getTranslationX();
        main(() -> { row.surface.setTranslationX(endpoint + dp(3)); row.invalidate(); }); idle();
        int movingGap = Math.round(rowPosition[0] + gapCenter(shapes, 0, 1));
        Bitmap joined = screenshot(name + "-connected"); int joinedPixel = joined.getPixel(movingGap, y); joined.recycle();
        main(() -> row.glass(null)); idle(); Bitmap separateMoving = screenshot(name + "-moving-baseline"); int separatePixel = separateMoving.getPixel(movingGap, y); separateMoving.recycle();
        require(colorDifference(joinedPixel, separatePixel) > 12, "neck remains visible during the final split: " + name);
        main(() -> { row.glass(session); row.surface.setTranslationX(endpoint); row.invalidate(); }); idle();
        if (expected == 3) {
            int bx = Math.round(rowPosition[0] + gapCenter(shapes, 1, 2));
            Bitmap a = screenshot(name + "-chain"); int connected = a.getPixel(bx, y); a.recycle();
            main(() -> row.glass(null)); idle(); Bitmap b = screenshot(name + "-chain-baseline"); int original = b.getPixel(bx, y); b.recycle();
            require(colorDifference(connected, original) < 4, "first exposed button stays fully detached while the second splits"); main(() -> row.glass(session));
        }
        main(() -> { row.surface.setTranslationX(row.surface.getTranslationX() - dp(60)); row.invalidate(); }); idle();
        Bitmap pulled = screenshot(name + "-detached");
        int gap = Math.round(rowPosition[0] + gapCenter(shapes, 0, 1));
        require(colorDifference(pulled.getPixel(gap, y), 0xFF406A98) < 4, "long card-to-button separation breaks the neck: " + name); pulled.recycle();
        main(() -> { row.surface.setTranslationX(0); row.showActions(false); }); SystemClock.sleep(800); idle();
    }
    private static float gapCenter(float[] shapes, int left, int right) { return (shapes[left * 4] + shapes[left * 4 + 2] + shapes[right * 4] - shapes[right * 4 + 2]) * .5f; }
    private void checkSequence(NotificationSwipeRow row) throws Exception {
        main(() -> row.showActions(true)); SystemClock.sleep(800); idle();
        int firstPartial = PanelUi.SLOT - 3, secondPartial = PanelUi.SLOT * 2 - 3, endpoint = PanelUi.SLOT * 2;
        int[] distances = {firstPartial, PanelUi.SLOT + 8, secondPartial, endpoint, endpoint + 16, endpoint, secondPartial, PanelUi.SLOT + 8, firstPartial};
        int[] components = {1, 2, 2, 3, 3, 3, 2, 2, 1};
        for (int step = 0; step < distances.length; step++) {
            int distance = distances[step]; main(() -> { row.surface.setTranslationX(-dp(distance)); row.invalidate(); }); idle();
            LiquidTensionGeometry geometry = (LiquidTensionGeometry) field(row, "tension"); float[] joins = geometry.getJoins();
            int active = 0; for (int i = 1; i < geometry.getCount(); i++) if (joins[i] > 0) active++;
            require(active <= 1, "at most one split connection at " + distance + "dp");
            if (distance == firstPartial) require(joins[2] > 0 && joins[1] == 0, "rightmost first exposed button splits before settings");
            if (distance == PanelUi.SLOT + 8 || distance == secondPartial) require(joins[2] == 0 && joins[1] > 0, "second split starts only after first is fully disconnected");
            if (distance >= endpoint) require(active == 0, "settled and overpulled poses contain no remaining neck");
            int[] position = new int[2]; main(() -> row.getLocationOnScreen(position));
            Bitmap bitmap = screenshot("sequence-" + step + "-" + distance);
            float[] shapes = geometry.getShapes(); int y = Math.round(position[1] + shapes[5]); int groups = 0; boolean inside = false;
            for (int x = position[0] + dp(18); x < position[0] + row.getWidth(); x++) {
                boolean material = colorDifference(bitmap.getPixel(x, y), 0xFF406A98) > 8;
                if (material && !inside) groups++; inside = material;
            }
            require(groups == components[step], "rendered material has " + components[step] + " components at " + distance + "dp, got " + groups);
            if (step == 0) {
                ViewGroup face = row.findViewWithTag("notification-settings").findViewWithTag("notification-action-face"); View icon = face.getChildAt(0);
                main(() -> icon.setVisibility(View.INVISIBLE)); idle(); Bitmap hidden = screenshot("sequence-first-icon-occlusion");
                int[] at = new int[2]; main(() -> face.getLocationOnScreen(at)); int difference = 0;
                for (int py = at[1]; py < at[1] + face.getHeight(); py++) for (int px = at[0]; px < at[0] + face.getWidth(); px++) difference += colorDifference(bitmap.getPixel(px, py), hidden.getPixel(px, py));
                require(difference == 0, "second button icon remains covered during the first split"); hidden.recycle(); main(() -> icon.setVisibility(View.VISIBLE));
            }
            bitmap.recycle();
        }
        main(() -> { row.surface.setTranslationX(0); row.showActions(false); }); SystemClock.sleep(800); idle();
    }
    private static int colorDifference(int a, int b) { return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b)); }
    private void checkCompleteIcon(NotificationSwipeRow row) throws Exception {
        main(() -> row.showActions(true)); SystemClock.sleep(800); idle();
        ViewGroup face = row.findViewWithTag("notification-settings").findViewWithTag("notification-action-face"); View icon = face.getChildAt(0);
        main(() -> { row.surface.setTranslationX(-dp(PanelUi.SLOT * 2 - 12)); row.invalidate(); }); idle();
        Bitmap visible = screenshot("complete-active-icon");
        main(() -> icon.setVisibility(View.INVISIBLE)); idle(); Bitmap hidden = screenshot("active-icon-hidden");
        LiquidTensionGeometry geometry = (LiquidTensionGeometry) field(row, "tension"); float[] shapes = geometry.getShapes(); int[] at = new int[2]; main(() -> row.getLocationOnScreen(at));
        int left = visible.getWidth(), right = 0, pixelsBeforeCardEdge = 0;
        int cx = Math.round(at[0] + shapes[4]), cy = Math.round(at[1] + shapes[5]), bodyRight = Math.round(at[0] + shapes[0] + shapes[2]);
        for (int y = cy - dp(12); y <= cy + dp(12); y++) for (int x = cx - dp(12); x <= cx + dp(12); x++) if (colorDifference(visible.getPixel(x,y), hidden.getPixel(x,y)) > 30) { left = Math.min(left,x); right = Math.max(right,x); if (x < bodyRight) pixelsBeforeCardEdge++; }
        require(right - left >= dp(12), "active settings glyph retains its full width");
        require(pixelsBeforeCardEdge > 4, "complete active glyph draws above the moving card edge");
        main(() -> { icon.setVisibility(View.VISIBLE); row.surface.invalidate(); face.invalidate(); }); idle();
        Bitmap refreshed = screenshot("shared-material-refreshed"); int difference = 0;
        for (int y = at[1]; y < at[1] + row.getHeight(); y++) for (int x = at[0]; x < at[0] + row.getWidth(); x++) difference += colorDifference(visible.getPixel(x,y), refreshed.getPixel(x,y));
        require(difference == 0, "child display-list refresh cannot restore a separate card or button backplate");
        visible.recycle(); hidden.recycle(); refreshed.recycle(); main(() -> row.showActions(false)); SystemClock.sleep(800); idle();
    }
    private void send(NotificationSwipeRow row, long start, int elapsed, int action, float distance) {
        MotionEvent event = MotionEvent.obtain(start, start + elapsed, action, row.getWidth() * .5f + distance, row.getHeight() * .5f, 0); row.dispatchTouchEvent(event); event.recycle();
    }
    private long pull(NotificationSwipeRow row, int extra) {
        long start = SystemClock.uptimeMillis();
        main(() -> { send(row, start, 0, MotionEvent.ACTION_DOWN, 0); send(row, start, 100, MotionEvent.ACTION_MOVE, -(int) field(row,"actionWidth") - dp(extra)); }); awaitActions(row); return start;
    }
    private void checkMerge(NotificationSwipeRow one, NotificationSwipeRow two) throws Exception {
        int width = (int) field(two,"actionWidth");
        long start = pull(two, 0); idle();
        require((float) field(two,"mergeProgress") == 0 && !(boolean) field(two,"clearArmed"), "normal endpoint remains two detached actions");
        final long first = start;
        main(() -> send(two, first, 200, MotionEvent.ACTION_MOVE, -width - dp(60))); awaitActions(two); idle();
        float partial = (float) field(two,"mergeProgress"); require(partial > 0 && partial < 1 && clearRequests.isEmpty(), "overpull moves the pair together without early clear");
        screenshot("merging-pair").recycle();
        main(() -> send(two, first, 900, MotionEvent.ACTION_MOVE, -width)); awaitActions(two); idle();
        require(!(boolean) field(two,"clearArmed") && (float) field(two,"mergeProgress") == 0, "reversing below threshold cancels clear and separates buttons");
        main(() -> send(two, first, 1000, MotionEvent.ACTION_UP, -width)); SystemClock.sleep(800); idle(); require(clearRequests.isEmpty(), "reversed release never clears");
        main(() -> two.showActions(false)); SystemClock.sleep(800); idle();
        for (int cancel : new int[]{MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN}) {
            long at = pull(two,60); idle();
            main(() -> { send(two,at,200,cancel,-width-dp(60)); send(two,at,300,MotionEvent.ACTION_UP,-width-dp(60)); }); idle();
            require(clearRequests.isEmpty() && !(boolean) field(two,"clearArmed"), "cancel or extra pointer followed by up never clears");
        }
        long identity = pull(two,60); idle();
        main(() -> { two.cancelMergedAction(); send(two,identity,300,MotionEvent.ACTION_UP,-width-dp(60)); }); idle();
        require(clearRequests.isEmpty() && !(boolean) field(two,"clearArmed"), "changed notification identity invalidates the armed gesture");
        long ongoing = pull(one,160); idle();
        require((float) field(one,"mergeProgress") == 0 && !(boolean) field(one,"clearArmed"), "ongoing one-button notification never arms clear");
        main(() -> send(one,ongoing,900,MotionEvent.ACTION_UP,-(int)field(one,"actionWidth")-dp(160))); SystemClock.sleep(800); idle();
        require(clearRequests.isEmpty(), "one-button release never clears");
        long submit = SystemClock.uptimeMillis(); float[] pose = new float[3];
        View settings = (View) field(two,"settingsFace"), clear = (View) field(two,"clearFace");
        main(() -> {
            send(two,submit,0,MotionEvent.ACTION_DOWN,0); send(two,submit,100,MotionEvent.ACTION_MOVE,-width-dp(160));
            require(clearRequests.isEmpty(), "fast overpull preserves ordered button release before the actual merged threshold");
        }); awaitActions(two);
        main(() -> {
            require(clearRequests.size() == 1 && clearRequests.get(0).size() == 1 && clearRequests.get(0).get(0).getId() == 2, "maximum threshold submits exactly this notification before UP");
            require((boolean)field(two,"thresholdSubmitted") && (float)field(two,"mergeProgress") == 1 && ((LiquidTensionMotion)field(two,"actionMotion")).ready(), "actual full merge locks delete after ordered button release, without waiting for UP");
            pose[0] = two.surface.getTranslationX(); pose[1] = settings.getTranslationX(); pose[2] = clear.getTranslationX();
        });
        main(() -> require(((ValueAnimator)field(two,"thresholdFade")).getDuration() == 180 && clear.getScaleX() == PanelUi.ACTION_SCALE, "delete buttons lerp their whole composite opacity at the original 28.8dp size"));
        checkDeletePixels(two, clear);
        main(() -> {
            send(two,submit,200,MotionEvent.ACTION_UP,-width-dp(160)); send(two,submit,210,MotionEvent.ACTION_MOVE,-width);
            require(clearRequests.size() == 1 && two.surface.getTranslationX() == pose[0], "UP/reverse cannot resubmit or return the card to its action endpoint");
            require(settings.getTranslationX() == pose[1] && clear.getTranslationX() == pose[2], "pending delete never splits the merged buttons");
            center.update(true,List.of(notice(1,true))); require(two.dismissing(), "confirmed removal continues outward from the submitted pose");
        }); SystemClock.sleep(90);
        main(() -> require(two.surface.getTranslationX() < pose[0] && settings.getTranslationX() == pose[1] && clear.getTranslationX() == pose[2], "confirmed exit moves only outward while merged buttons fade"));
        SystemClock.sleep(500); idle();
        main(() -> { require(!two.isAttachedToWindow() && row(0) == one, "confirmed delete removes only its original row"); center.update(true,List.of(notice(1,true),notice(2,false))); }); idle();
        NotificationSwipeRow rejected = row(1); int rejectionCount = clearRequests.size(); long rejectedAt = SystemClock.uptimeMillis();
        main(() -> { send(rejected,rejectedAt,0,MotionEvent.ACTION_DOWN,0); send(rejected,rejectedAt,100,MotionEvent.ACTION_MOVE,-(int)field(rejected,"actionWidth")-dp(160)); });
        awaitActions(rejected);
        SystemClock.sleep(1500); idle();
        main(() -> require(clearRequests.size() == rejectionCount + 1 && rejected.isAttachedToWindow() && rejected.getAlpha() == 1 && !(boolean)field(rejected,"thresholdSubmitted"), "only absent system confirmation restores a rejected deletion; no duplicate requests"));
    }
    private void checkDeletePixels(NotificationSwipeRow row, View clear) throws Exception {
        ValueAnimator fade = (ValueAnimator) field(row,"thresholdFade"); android.graphics.Rect area = new android.graphics.Rect();
        main(() -> { row.removeCallbacks((Runnable)field(row,"thresholdTimeout")); fade.cancel(); row.surface.setPressed(false); row.surface.jumpDrawablesToCurrentState(); ((View)field(row,"rail")).setVisibility(View.VISIBLE); clear.getGlobalVisibleRect(area); });
        commitDeleteFrame(row, fade, 0); Bitmap full = screenshot("delete-full");
        commitDeleteFrame(row, fade, .5f); Bitmap half = screenshot("delete-half");
        commitDeleteFrame(row, fade, 1); Bitmap zero = screenshot("delete-zero");
        try {
            int cardX = area.left - dp(80), cardY = area.centerY();
            require(colorDifference(full.getPixel(cardX,cardY),half.getPixel(cardX,cardY)) <= 2 && colorDifference(full.getPixel(cardX,cardY),zero.getPixel(cardX,cardY)) <= 2, "button opacity leaves the detached card material unchanged");
            int material = 0, glyph = 0, materialPass = 0, glyphPass = 0; float radius = area.width() * .5f;
            for (int y = area.top; y < area.bottom; y++) for (int x = area.left; x < area.right; x++) {
                float dx = x + .5f - area.exactCenterX(), dy = y + .5f - area.exactCenterY(), distance = (float)Math.sqrt(dx * dx + dy * dy);
                int a = full.getPixel(x,y), b = zero.getPixel(x,y), m = half.getPixel(x,y); if (colorDifference(a,b) <= 12) continue;
                int expected = 0xFF000000; for (int shift : new int[]{16,8,0}) expected |= ((((a >> shift) & 255) + ((b >> shift) & 255)) / 2) << shift;
                boolean matches = colorDifference(m,expected) <= 6;
                if (distance > radius * .60f && distance < radius * .85f) { material++; if (matches) materialPass++; }
                if (distance < radius * .55f && colorDifference(a, 0xFFFFB4AB) <= 15) { glyph++; if (matches) glyphPass++; }
            }
            require(material >= 20 && materialPass >= material * .95f, "GPU glass circle pixels lerp at half opacity while card material stays outside the faded rail: " + materialPass + "/" + material);
            require(glyph >= 8 && glyphPass >= glyph * .95f, "GPU clear glyph fades in the same composite opacity layer: " + glyphPass + "/" + glyph);
        } finally { full.recycle(); half.recycle(); zero.recycle(); }
    }
    private void commitDeleteFrame(NotificationSwipeRow row, ValueAnimator fade, float fraction) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        main(() -> {
            row.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
            fade.setCurrentFraction(fraction); row.invalidate();
            require((float)field(row,"thresholdOpacity") == 1 - fraction, "opacity probe uses the actual fade listener");
        });
        require(committed.await(5, TimeUnit.SECONDS), "GPU commits the requested opacity before screenshot capture");
        // Frame commit submits the buffer; screen capture observes SurfaceFlinger presentation.
        // The clock is frozen, so allow the committed buffer to reach the compositor.
        idle();
    }
    private Bitmap screenshot(String name) throws Exception {
        Bitmap bitmap = test.getUiAutomation().takeScreenshot(); require(bitmap != null, "hardware screenshot available");
        File directory = new File(activity.getFilesDir(), "liquid-tension"); directory.mkdirs();
        try (FileOutputStream file = new FileOutputStream(new File(directory, name + ".png"))) { bitmap.compress(Bitmap.CompressFormat.PNG, 100, file); }
        return bitmap;
    }
    private void write(String name, String value) throws Exception {
        File directory = new File(activity.getFilesDir(), "liquid-tension"); directory.mkdirs(); try (FileOutputStream file = new FileOutputStream(new File(directory, name))) { file.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    }
    private void measure(NotificationSwipeRow row, boolean enabled, String label, int duration) {
        ArrayList<long[]> frames = new ArrayList<>(); Window.OnFrameMetricsAvailableListener listener = (window, frame, dropped) -> frames.add(new long[]{frame.getMetric(FrameMetrics.TOTAL_DURATION), frame.getMetric(FrameMetrics.DRAW_DURATION), frame.getMetric(FrameMetrics.GPU_DURATION)});
        ValueAnimator[] animation = {null};
        main(() -> {
            row.glass(enabled ? session : null); activity.getWindow().addOnFrameMetricsAvailableListener(listener, new Handler(Looper.getMainLooper()));
            animation[0] = ValueAnimator.ofFloat(0, 1); animation[0].setDuration(duration); animation[0].setInterpolator(new android.view.animation.LinearInterpolator());
            int actionWidth = (int) field(row, "actionWidth");
            animation[0].addUpdateListener(value -> { row.surface.setTranslationX(-actionWidth - dp(30) * (float) Math.sin((float) value.getAnimatedValue() * Math.PI * 8)); row.invalidate(); }); animation[0].start();
        });
        SystemClock.sleep(duration + 200);
        main(() -> { activity.getWindow().removeOnFrameMetricsAvailableListener(listener); animation[0].removeAllUpdateListeners(); animation[0].cancel(); });
        require(frames.size() > 30, "frame metrics collected for " + label);
        if (label.equals("warmup")) return;
        report.append(label).append(" frames=").append(frames.size());
        for (int metric = 0; metric < 3; metric++) {
            long[] values = new long[frames.size()]; for (int i = 0; i < frames.size(); i++) values[i] = frames.get(i)[metric]; Arrays.sort(values);
            report.append(new String[]{" total", " draw", " gpu"}[metric]).append(" p50/p90=").append(String.format(java.util.Locale.ROOT, "%.3f/%.3fms", values[values.length / 2] / 1e6, values[(int) (values.length * .9)] / 1e6));
        }
        report.append('\n');
    }
}
