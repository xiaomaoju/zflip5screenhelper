package io.github.flipcover.controls;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.List;

/** Bounded swipe physics and real touch sequences on disposable emulators only. */
final class NotificationSwipeChecks {
    private final Instrumentation test;
    private Activity activity;
    private FrameLayout root;
    private NotificationSwipeRow row;
    private LinearLayout rail;
    private int assertions, opens, clears;
    private NotificationCenterView center;
    private List<StatusBarNotification> requested = List.of();
    private StatusBarNotification openedNotice;
    NotificationSwipeChecks(Instrumentation test) { this.test = test; }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(test.getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(this::mount); test.waitForIdleSync();
            checkEdgeFade();
            if (ValueAnimator.areAnimatorsEnabled()) { checkDrag(); checkFling(); checkCatch(); checkCancel(); checkButtons(); }
            else checkDisabled();
            checkDetach();
            checkRemoval();
            return "PASS: " + assertions + " notification swipe assertions; emulator motion, no live notifications or Samsung routing";
        } finally { main(() -> activity.finish()); }
    }
    private void main(Runnable action) { test.runOnMainSync(action); }
    private void checkEdgeFade() {
        main(() -> {
            row.surface.setTranslationX(-dp(96));
            Bitmap frame = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888); frame.eraseColor(Color.BLACK); row.draw(new Canvas(frame));
            int y = row.surface.getHeight() / 2, edge = Color.green(frame.getPixel(0, y)), middle = Color.green(frame.getPixel(dp(6), y)), interior = Color.green(frame.getPixel(dp(18), y));
            require(edge < middle && middle < interior && interior == 160, "departing card fades gradually at the viewport edge without dimming its interior");
            frame.recycle(); row.surface.setTranslationX(0);
            frame = Bitmap.createBitmap(row.getWidth(), row.getHeight(), Bitmap.Config.ARGB_8888); frame.eraseColor(Color.BLACK); row.draw(new Canvas(frame));
            require(Color.green(frame.getPixel(dp(2), y)) == 160, "resting card keeps its original edge opacity"); frame.recycle();
        });
    }
    private void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); assertions++; }
    private int dp(float value) { return Ui.dp(activity, value); }
    private void mount() {
        root = new FrameLayout(activity); NotificationCardSurface card = new NotificationCardSurface(activity); card.setMinimumHeight(dp(64)); card.setBackground(Ui.background(activity, 0xFF00A050, 14)); card.setPadding(dp(8), dp(8), dp(8), dp(8)); card.setOnClickListener(v -> opens++);
        TextView label = Ui.text(activity, "卡片卡片卡片", 12, Color.WHITE); label.setGravity(android.view.Gravity.CENTER); card.addView(label, new LinearLayout.LayoutParams(-1, -2));
        View icon = new View(activity); icon.setBackgroundColor(Color.BLUE); LinearLayout.LayoutParams iconSize = new LinearLayout.LayoutParams(dp(24), dp(24)); iconSize.gravity = android.view.Gravity.CENTER_HORIZONTAL; card.addView(icon, iconSize);
        rail = Ui.row(activity); View settings = Ui.button(activity, "设置", () -> opens++), clear = Ui.button(activity, "清除", () -> clears++);
        rail.addView(settings, new LinearLayout.LayoutParams(dp(48), -1)); rail.addView(clear, new LinearLayout.LayoutParams(dp(48), -1));
        row = new NotificationSwipeRow(activity, card, rail, () -> { }); row.setActionsWidth(96); row.setLayers(3); root.addView(row, new FrameLayout.LayoutParams(-1, -2)); activity.setContentView(root);
    }
    private Object field(String name) {
        try { java.lang.reflect.Field field = NotificationSwipeRow.class.getDeclaredField(name); field.setAccessible(true); return field.get(row); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private void send(long start, int elapsed, int action, float distance) {
        MotionEvent event = MotionEvent.obtain(start, start + elapsed, action, row.getWidth() * .35f + distance, row.getHeight() / 2f, 0); row.dispatchTouchEvent(event); event.recycle();
    }
    private void rest(boolean open) {
        long deadline = SystemClock.uptimeMillis() + 1200; boolean[] done = {false};
        do { SystemClock.sleep(20); main(() -> done[0] = field("spring") == null); } while (!done[0] && SystemClock.uptimeMillis() < deadline);
        main(() -> {
            require(done[0], "spring stops instead of leaving a permanent frame loop");
            require(Math.abs(row.surface.getTranslationX() - (open ? -rail.getLayoutParams().width : 0)) < .1f, "spring finishes exactly at chosen endpoint");
            require(rail.getVisibility() == (open ? View.VISIBLE : View.INVISIBLE), "rail visibility matches resting state");
            require(rail.getImportantForAccessibility() == (open ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS), "only exposed resting actions are accessible");
            require((float) field("pull") == 0 && row.surface.getScaleX() == 1 && row.surface.getScaleY() == 1, "rest releases material deformation without changing view geometry");
        });
    }
    private void checkDrag() {
        long start = SystemClock.uptimeMillis();
        main(() -> {
            int width = row.getWidth(), height = row.getHeight(); int[] before = materialPixels(); send(start, 0, MotionEvent.ACTION_DOWN, 0); send(start, 100, MotionEvent.ACTION_MOVE, -dp(40));
            require(Math.abs(row.surface.getTranslationX() + dp(40)) < .1f, "inside action range drag is one to one");
            send(start, 200, MotionEvent.ACTION_MOVE, -dp(136)); float first = row.surface.getTranslationX();
            float originalLimit = Math.max(dp(64), row.getWidth() * .35f);
            float originalTravel = originalLimit * (1 - (float) Math.exp(-dp(40) / originalLimit));
            require(Math.abs((-first - dp(96)) / originalTravel - .35f) < .002f, "same finger pull has exactly thirty-five percent of original elastic travel");
            send(start, 300, MotionEvent.ACTION_MOVE, -dp(176)); float second = row.surface.getTranslationX();
            require(first - second > dp(5) && first - second < dp(14), "reduced overdrag still follows the finger with gradual resistance");
            int[] after = materialPixels();
            require(after[0] > before[0] && after[1] < before[1], "rendered card material visibly compresses vertically during horizontal stretch");
            require(after[2] > before[2] && after[3] <= before[3], "rendered text widens with the card; subpixel compression never increases glyph height");
            require(after[4] > before[4] && after[5] <= before[5], "rendered icon widens with the card; subpixel compression never increases icon height");
            require(row.getWidth() == width && row.getHeight() == height && row.surface.getScaleX() == 1 && row.surface.getScaleY() == 1, "drawing deformation preserves resting layout and hit geometry");
            require(rail.getAlpha() == 1 && rail.getTranslationX() == 0, "fully revealed actions stay opaque and stationary");
            send(start, 800, MotionEvent.ACTION_UP, -dp(176));
        }); rest(true);
        main(() -> {
            long at = SystemClock.uptimeMillis(); send(at, 0, MotionEvent.ACTION_DOWN, 0); send(at, 120, MotionEvent.ACTION_MOVE, dp(140));
            require(row.surface.getTranslationX() > dp(10) && row.surface.getTranslationX() < row.getWidth() * .1225f, "closed endpoint retains continuous rubber band at reduced strength");
            send(at, 700, MotionEvent.ACTION_UP, dp(140));
        }); rest(false);
        main(() -> {
            long at = SystemClock.uptimeMillis(); send(at, 0, MotionEvent.ACTION_DOWN, 0); send(at, 100, MotionEvent.ACTION_MOVE, -dp(176));
            float before = row.surface.getTranslationX(); row.showActions(false);
            require(Math.abs(row.surface.getTranslationX() - before) < .1f, "closing from left overpull retains exact initial pose after strength reduction");
            send(at, 200, MotionEvent.ACTION_CANCEL, -dp(176));
            send(at + 250, 0, MotionEvent.ACTION_DOWN, 0); send(at + 250, 100, MotionEvent.ACTION_MOVE, dp(70));
            before = row.surface.getTranslationX(); row.showActions(true);
            require(Math.abs(row.surface.getTranslationX() - before) < .1f, "opening from right overpull retains exact initial pose after strength reduction");
            send(at + 250, 200, MotionEvent.ACTION_CANCEL, dp(70));
        }); rest(false);
    }
    private void checkFling() {
        main(() -> {
            long start = SystemClock.uptimeMillis(); send(start, 0, MotionEvent.ACTION_DOWN, 0);
            for (int i = 1; i <= 4; i++) send(start, i * 15, MotionEvent.ACTION_MOVE, -dp(24 * i));
            send(start, 62, MotionEvent.ACTION_UP, -dp(96));
            require((float) field("springVelocity") < -dp(450), "release velocity continues into spring instead of stopping at boundary");
            require(opens == 0 && clears == 0, "fast swipe never invokes notification or clear action");
        });
        float[] minimum = {0}; boolean[] shape = {false, false};
        for (int i = 0; i < 40; i++) {
            SystemClock.sleep(16); main(() -> {
                float value = row.surface.getTranslationX(); minimum[0] = Math.min(minimum[0], value);
                float pull = (float) field("pull"); shape[0] |= pull > .05f; shape[1] |= pull < -.02f;
                require(value >= -dp(96) - row.getWidth() * .35f && value <= row.getWidth() * .35f, "every sampled inertia frame stays inside task-sized boundary");
            });
        }
        require(minimum[0] < -dp(99), "reduced fast impact still produces visible extra travel"); require(shape[0] && shape[1], "shared task spring alternates stretch and compression during rebound"); rest(true);
        main(() -> row.showActions(false)); rest(false);
    }
    private void checkCatch() {
        main(() -> row.showActions(true)); SystemClock.sleep(35);
        main(() -> {
            float before = row.surface.getTranslationX(); long start = SystemClock.uptimeMillis(); send(start, 0, MotionEvent.ACTION_DOWN, 0);
            require(row.surface.getTranslationX() == before && field("spring") == null, "press catches animation at exact current position");
            send(start, 50, MotionEvent.ACTION_MOVE, dp(210));
            require(row.surface.getTranslationX() > before && row.surface.getTranslationX() - before <= dp(210), "reverse drag continues from caught position");
            send(start, 500, MotionEvent.ACTION_UP, dp(210));
        }); rest(false);
        main(() -> row.showActions(true)); rest(true);
        main(() -> row.showActions(false)); SystemClock.sleep(35);
        main(() -> {
            long start = SystemClock.uptimeMillis(); send(start, 0, MotionEvent.ACTION_DOWN, 0); send(start, 80, MotionEvent.ACTION_UP, 0);
            require(opens == 0 && clears == 0, "catch tap consumes the gesture without clicking content");
        }); rest(false);
    }
    private void checkCancel() {
        main(() -> {
            long start = SystemClock.uptimeMillis(); send(start, 0, MotionEvent.ACTION_DOWN, 0); send(start, 80, MotionEvent.ACTION_MOVE, -dp(140)); send(start, 90, MotionEvent.ACTION_CANCEL, -dp(140));
            require(row.surface.getTranslationX() == 0 && field("spring") == null && field("velocity") == null, "cancel releases tracking and restores original closed state");
            start = SystemClock.uptimeMillis(); send(start, 0, MotionEvent.ACTION_DOWN, 0); send(start, 80, MotionEvent.ACTION_MOVE, -dp(140));
            MotionEvent.PointerProperties[] properties = {new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties()};
            MotionEvent.PointerCoords[] coordinates = {new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords()};
            for (int i = 0; i < 2; i++) { properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER; coordinates[i].x = dp(90 + i * 30); coordinates[i].y = row.getHeight() / 2f; coordinates[i].pressure = coordinates[i].size = 1; }
            MotionEvent multi = MotionEvent.obtain(start, start + 90, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coordinates, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
            row.dispatchTouchEvent(multi); multi.recycle(); send(start, 100, MotionEvent.ACTION_UP, -dp(140));
            require(row.surface.getTranslationX() == 0 && field("spring") == null && field("velocity") == null, "second pointer cancels full sequence and consumes trailing up");
            start = SystemClock.uptimeMillis(); send(start, 0, MotionEvent.ACTION_DOWN, 0); send(start, 80, MotionEvent.ACTION_MOVE, -dp(140)); row.setActionsWidth(48); send(start, 100, MotionEvent.ACTION_UP, -dp(140));
            require(row.surface.getTranslationX() == 0 && field("spring") == null, "changing action geometry terminates old touch sequence");
            row.showActions(true);
        }); rest(true);
        main(() -> {
            long start = SystemClock.uptimeMillis(); send(start, 0, MotionEvent.ACTION_DOWN, 0); send(start, 80, MotionEvent.ACTION_MOVE, -dp(80));
            require(row.surface.getTranslationX() < -dp(64) && row.surface.getTranslationX() > -dp(48) - row.getWidth() * .1225f, "settings-only row also has a visible reduced elastic pull");
            row.layout(0, 0, row.getWidth() - dp(10), row.getHeight()); send(start, 100, MotionEvent.ACTION_UP, -dp(80));
            require(row.surface.getTranslationX() == -dp(48) && field("spring") == null && field("velocity") == null, "real row size change cancels drag to original open endpoint");
            row.setActionsWidth(96); row.showActions(false);
        }); rest(false);
    }
    private void checkButtons() {
        main(() -> row.showActions(true)); rest(true);
        main(() -> {
            long start = SystemClock.uptimeMillis(); float x = row.getWidth() - dp(24), y = row.getHeight() / 2f;
            MotionEvent down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0), up = MotionEvent.obtain(start, start + 80, MotionEvent.ACTION_UP, x, y, 0);
            row.dispatchTouchEvent(down); row.dispatchTouchEvent(up); down.recycle(); up.recycle();
        }); test.waitForIdleSync();
        main(() -> {
            require(clears == 1 && opens == 0, "stationary exposed clear button receives explicit click");
            require(rail.getLayoutParams().width == dp(96), "actions retain original touch geometry");
        });
    }
    private void checkDisabled() {
        main(() -> {
            row.showActions(true); require(row.surface.getTranslationX() == -dp(96) && field("spring") == null, "disabled animations open directly at endpoint");
            row.showActions(false); require(row.surface.getTranslationX() == 0 && rail.getVisibility() == View.INVISIBLE, "disabled animations close without callbacks");
            long start = SystemClock.uptimeMillis(); send(start, 0, MotionEvent.ACTION_DOWN, 0); send(start, 100, MotionEvent.ACTION_MOVE, -dp(140)); send(start, 700, MotionEvent.ACTION_UP, -dp(140));
            require(row.surface.getTranslationX() == -dp(96) && field("spring") == null && clears == 0 && opens == 0, "disabled release lands directly and never invokes action");
        });
    }
    private void checkDetach() {
        main(() -> { row.showActions(false); root.removeView(row); }); SystemClock.sleep(500);
        main(() -> require(row.surface.getTranslationX() == 0 && field("spring") == null && field("velocity") == null && rail.getVisibility() == View.INVISIBLE, "detach releases motion and no late frame restores removed row"));
    }
    private int[] materialPixels() {
        Bitmap bitmap = Bitmap.createBitmap(row.surface.getWidth(), row.surface.getHeight(), Bitmap.Config.ARGB_8888); row.surface.draw(new Canvas(bitmap));
        int top = bitmap.getHeight(), bottom = -1, leftText = bitmap.getWidth(), rightText = -1, topText = bitmap.getHeight(), bottomText = -1;
        int leftIcon = bitmap.getWidth(), rightIcon = -1, topIcon = bitmap.getHeight(), bottomIcon = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            if (bitmap.getPixel(bitmap.getWidth() * 4 / 5, y) == 0xFF00A050) { top = Math.min(top, y); bottom = y; }
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == Color.WHITE) { leftText = Math.min(leftText, x); rightText = Math.max(rightText, x); topText = Math.min(topText, y); bottomText = Math.max(bottomText, y); }
                if (bitmap.getPixel(x, y) == Color.BLUE) { leftIcon = Math.min(leftIcon, x); rightIcon = Math.max(rightIcon, x); topIcon = Math.min(topIcon, y); bottomIcon = Math.max(bottomIcon, y); }
            }
        }
        bitmap.recycle(); require(bottom >= top && rightText >= leftText, "native rendering contains both material and readable glyph pixels");
        return new int[]{top, bottom, rightText - leftText, bottomText - topText, rightIcon - leftIcon, bottomIcon - topIcon};
    }
    private StatusBarNotification notice(int id, String group, long time, boolean ongoing) {
        Notification value = new Notification.Builder(test.getTargetContext(), "swipe-fixture").setSmallIcon(R.drawable.ic_ms_notifications).setGroup(group).setContentTitle("弹性通知 " + id + " / " + time).setContentText("本地动画验证，不发布到系统。").setWhen(time).build();
        if (ongoing) value.flags |= Notification.FLAG_ONGOING_EVENT;
        String pkg = test.getTargetContext().getPackageName(); return new StatusBarNotification(pkg, pkg, id, "swipe", android.os.Process.myUid(), 0, 0, value, android.os.Process.myUserHandle(), time);
    }
    private ViewGroup list() { return center.findViewWithTag("notification-list"); }
    private NotificationSwipeRow header(int index) { return (NotificationSwipeRow) ((ViewGroup) list().getChildAt(index)).getChildAt(0); }
    private void mountCenter(List<StatusBarNotification> items) {
        main(() -> {
            requested = List.of(); openedNotice = null; center = new NotificationCenterView(activity, new NotificationCenterView.Actions() {
                public void open(StatusBarNotification item) { opens++; openedNotice = item; }
                public void settings(StatusBarNotification item) { opens++; }
                public void permission() { }
                public void clear(List<StatusBarNotification> selected) { requested = List.copyOf(selected); }
            });
            ScrollView scroll = new ScrollView(activity); scroll.addView(center); activity.setContentView(scroll); center.update(true, items);
        }); test.waitForIdleSync(); SystemClock.sleep(70); test.waitForIdleSync();
    }
    private void checkRemoval() {
        StatusBarNotification one = notice(1, "one", 100, false), two = notice(2, "two", 50, false), ongoing = notice(3, "keep", 20, true);
        mountCenter(List.of(one, two, ongoing)); NotificationSwipeRow[] retiring = {null}; View[] survivor = {null};
        main(() -> { retiring[0] = header(0); survivor[0] = list().getChildAt(1); retiring[0].showActions(true); }); SystemClock.sleep(650); test.waitForIdleSync();
        float[] previousX = {0};
        main(() -> {
            previousX[0] = retiring[0].surface.getTranslationX(); retiring[0].findViewWithTag("notification-clear").performClick();
            require(retiring[0].surface.getTranslationX() == previousX[0], "clear starts at current exposed pose without first closing actions");
            require(requested.size() == 1 && requested.get(0).getKey().equals(one.getKey()), "clear click submits only its original notification");
            require(list().getChildCount() == 3 && !retiring[0].dismissing(), "request alone does not pretend system removal succeeded");
        }); SystemClock.sleep(90);
        main(() -> {
            require(retiring[0].getAlpha() == 1, "unconfirmed or failed clear keeps original card visible");
            require(retiring[0].surface.getTranslationX() <= previousX[0], "waiting for system confirmation never moves card to the right");
            center.update(true, List.of(two, ongoing));
            if (ValueAnimator.areAnimatorsEnabled()) {
                require(list().getChildCount() == 3 && retiring[0].dismissing(), "confirmed removal retains row for elastic exit");
                for (int i = 0; i < 20; i++) center.update(true, List.of(two, ongoing));
                require(retiring[0].dismissing(), "repeated notification refreshes do not restart or cancel exit");
            } else require(list().getChildCount() == 2 && !retiring[0].isAttachedToWindow(), "disabled animations remove confirmed card directly");
        });
        if (ValueAnimator.areAnimatorsEnabled()) {
            for (int frame = 0; frame < 6; frame++) {
                SystemClock.sleep(16); main(() -> { float current = retiring[0].surface.getTranslationX(); require(current <= previousX[0], "every confirmed deletion frame moves directly left from current pose"); previousX[0] = current; });
            }
            main(() -> require(retiring[0].getAlpha() > 0 && retiring[0].getAlpha() < 1 && retiring[0].surface.getTranslationX() < -dp(20), "confirmed card stretches, moves out and fades during exit"));
        }
        SystemClock.sleep(900);
        main(() -> require(list().getChildCount() == 2 && list().getChildAt(0) == survivor[0] && !retiring[0].isAttachedToWindow(), "exit removes just departed row and preserves remaining view identity"));
        mountCenter(List.of(one, two, ongoing));
        main(() -> { retiring[0] = header(0); retiring[0].findViewWithTag("notification-clear").performClick(); center.update(true, List.of(one, two, ongoing)); }); SystemClock.sleep(650);
        main(() -> require(header(0) == retiring[0] && retiring[0].getAlpha() == 1 && !retiring[0].dismissing() && list().getChildCount() == 3, "rejected clear springs back and retains all real notifications"));
        if (ValueAnimator.areAnimatorsEnabled()) {
            main(() -> { retiring[0].findViewWithTag("notification-clear").performClick(); center.update(true, List.of(two, ongoing)); }); SystemClock.sleep(90);
            StatusBarNotification replacement = notice(1, "one", 200, false);
            main(() -> center.update(true, List.of(replacement, two, ongoing))); SystemClock.sleep(650);
            main(() -> require(header(0) == retiring[0] && retiring[0].getAlpha() == 1 && !retiring[0].dismissing() && list().getChildCount() == 3, "reposted key cancels old exit without removing replacement or rebuilding row"));
        }
        StatusBarNotification childA = notice(11, "thread", 400, false), childB = notice(12, "thread", 300, false);
        mountCenter(List.of(childA, childB, ongoing)); NotificationSwipeRow[] child = {null}; ViewGroup[] children = {null};
        main(() -> header(0).surface.performClick()); test.waitForIdleSync(); SystemClock.sleep(220); test.waitForIdleSync();
        main(() -> {
            children[0] = (ViewGroup) ((ViewGroup) list().getChildAt(0)).getChildAt(1); child[0] = (NotificationSwipeRow) children[0].getChildAt(0);
            child[0].findViewWithTag("notification-clear").performClick(); require(requested.size() == 1 && requested.get(0).getKey().equals(childA.getKey()), "expanded child clear never clears its sibling");
            center.update(true, List.of(childB, ongoing));
            if (ValueAnimator.areAnimatorsEnabled()) require(child[0].dismissing() && children[0].getVisibility() == View.VISIBLE, "last group child exits before automatic group collapse");
            StatusBarNotification changed = notice(12, "thread", 600, false); center.update(true, List.of(changed, ongoing));
            if (ValueAnimator.areAnimatorsEnabled()) {
                NotificationSwipeRow survivorChild = (NotificationSwipeRow) children[0].getChildAt(1); survivorChild.surface.performClick();
                require(openedNotice != null && openedNotice.getPostTime() == 600, "neighbor content and launch identity update while another child exits");
            }
        }); SystemClock.sleep(900);
        main(() -> require(list().getChildCount() == 2 && !child[0].isAttachedToWindow() && children[0].getVisibility() == View.GONE, "child exit finishes then collapses group with one surviving notification"));
        mountCenter(List.of(one, two, ongoing));
        main(() -> {
            center.clearAll.performClick(); require(requested.size() == 2 && requested.stream().noneMatch(item -> item.getKey().equals(ongoing.getKey())), "clear-all request protects ongoing notification");
            center.update(true, List.of(ongoing));
        }); SystemClock.sleep(900);
        main(() -> require(list().getChildCount() == 1 && header(0).findViewWithTag("notification-clear").getVisibility() == View.GONE, "clear-all elastic exits retain ongoing card and its settings-only actions"));
        if (ValueAnimator.areAnimatorsEnabled()) {
            List<StatusBarNotification> many = new java.util.ArrayList<>(); for (int i = 0; i < 6; i++) many.add(notice(20 + i, "scrolling-thread", 500 - i, false)); many.add(ongoing);
            mountCenter(many); main(() -> header(0).surface.performClick()); test.waitForIdleSync(); SystemClock.sleep(220); test.waitForIdleSync();
            main(() -> ((ScrollView) center.getParent()).scrollTo(0, header(0).getHeight() + dp(20))); test.waitForIdleSync(); SystemClock.sleep(50);
            main(() -> {
                NotificationSwipeRow groupHeader = header(0); ViewGroup members = (ViewGroup) ((ViewGroup) list().getChildAt(0)).getChildAt(1); child[0] = (NotificationSwipeRow) members.getChildAt(0);
                require(!groupHeader.getGlobalVisibleRect(new android.graphics.Rect()) && child[0].getGlobalVisibleRect(new android.graphics.Rect()), "scrolled fixture shows child while group header is outside viewport");
                center.clearAll.performClick(); center.update(true, List.of(ongoing)); require(child[0].dismissing(), "visible children receive clear-all exit even when header is offscreen");
            }); SystemClock.sleep(900);
            main(() -> require(list().getChildCount() == 1 && !child[0].isAttachedToWindow(), "visible child completion removes offscreen-header group without orphan rows"));
        }
        mountCenter(List.of(one, two));
        main(() -> { retiring[0] = header(0); retiring[0].findViewWithTag("notification-clear").performClick(); center.update(true, List.of(two)); center.update(false, List.of()); }); SystemClock.sleep(650);
        main(() -> require(list().getChildCount() == 0 && !retiring[0].isAttachedToWindow() && !retiring[0].dismissing(), "disconnect releases in-flight exits and rejects late completion"));
    }
}
