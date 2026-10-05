package io.github.flipcover.controls;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Native components and real MotionEvents, using in-memory fixtures on a disposable emulator. */
final class NotificationCenterChecks {
    private final Instrumentation test;
    private final Context context;
    private Activity activity;
    private NotificationCenterView center;
    private ScrollView scroll;
    private PanelSurface surface;
    private int assertions, panelDrags, opens, clears;
    private float panelProgress = 1;
    private boolean panelFinished, panelClosed;
    private StatusBarNotification settingsTarget;
    private List<StatusBarNotification> clearRequest = List.of();
    private final long now = System.currentTimeMillis();
    NotificationCenterChecks(Instrumentation test) { this.test = test; context = test.getTargetContext(); }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(this::checkModel); mount(DockGeometry.BOTTOM); checkUi(); checkSwipes(); checkElasticity(1); checkElasticity(-1); checkLongListScrolling(DockGeometry.BOTTOM); checkBoundaryHandoff(DockGeometry.BOTTOM);
            for (int edge : new int[]{DockGeometry.LEFT, DockGeometry.RIGHT, DockGeometry.TOP}) { mount(edge); checkSwipeAxis(); checkElasticity(1); checkElasticity(-1); if (edge == DockGeometry.TOP) { checkLongListScrolling(edge); checkBoundaryHandoff(edge); } }
            productPreview(); idle(); checkHeader(); screenshot("notifications-grouped");
            main(() -> firstRow().surface.performClick()); idle(); screenshot("notifications-group-expanded");
            main(() -> firstRow().surface.performClick()); idle(); main(() -> firstRow().showActions(true)); idle(); screenshot("notifications-actions");
            return "PASS: " + assertions + " notification assertions; native emulator components, no Samsung routing or live user notifications";
        } finally { main(() -> activity.finish()); }
    }
    private void main(Runnable action) { test.runOnMainSync(action); }
    String runInertia() throws Exception {
        activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            mount(DockGeometry.BOTTOM);
            for (int direction : new int[]{1, -1}) for (int preparation = 0; preparation < 3; preparation++) checkInwardFling(direction, preparation);
            return "PASS: " + assertions + " edge-fling assertions; top/bottom, resting/rebound/reversal";
        } finally { main(() -> activity.finish()); }
    }
    String runForce() throws Exception {
        activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            mount(DockGeometry.BOTTOM); main(() -> firstRow().surface.performClick()); idle();
            ViewGroup members = (ViewGroup) ((ViewGroup) list().getChildAt(0)).getChildAt(1);
            NotificationSwipeRow first = (NotificationSwipeRow) members.getChildAt(0), second = (NotificationSwipeRow) members.getChildAt(1);
            android.graphics.Rect[] baseline = new android.graphics.Rect[2];
            float x = surface.getWidth() * .6f, y = top(scroll) - top(surface) + scroll.getHeight() * .4f; long at = SystemClock.uptimeMillis();
            main(() -> {
                first.surface.setBackgroundColor(android.graphics.Color.MAGENTA); second.surface.setBackgroundColor(android.graphics.Color.CYAN);
                first.setWillNotDraw(true); second.setWillNotDraw(true);
                baseline[0] = coloredBounds(android.graphics.Color.MAGENTA); baseline[1] = coloredBounds(android.graphics.Color.CYAN);
                send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 60, MotionEvent.ACTION_MOVE, x, y + Ui.dp(activity, 60));
                android.graphics.Rect a = coloredBounds(android.graphics.Color.MAGENTA), b = coloredBounds(android.graphics.Color.CYAN);
                require(a.top > baseline[0].top && b.top - baseline[1].top > a.top - baseline[0].top, "expanded group members also translate and separate rather than being clipped by their group");
                require(a.height() > baseline[0].height() && b.height() > baseline[1].height() && first.surface.getScaleY() == 1 && second.surface.getScaleY() == 1, "skip-draw glass path visibly deforms expanded cards without changing View scale or layout");
                secondPointer(at, at + 70, x, y + Ui.dp(activity, 60));
                require(deformation() == 0 && first.boundaryVisualOffset() == 0 && second.boundaryVisualOffset() == 0, "second pointer resets both whole-list and child forces");
                require(coloredBounds(android.graphics.Color.MAGENTA).equals(baseline[0]), "multi-touch restores the actual original pixels");
                send(at, at + 80, MotionEvent.ACTION_UP, x, y);
            });
            productPreview(); idle();
            main(() -> { for (android.view.ViewParent parent = center.getParent(); parent != null; parent = parent.getParent()) if (parent instanceof NotificationScrollView found) { scroll = found; break; } });
            screenshot("notification-force-rest");
            for (int direction : new int[]{-1, 1}) {
                main(() -> scroll.fullScroll(direction < 0 ? View.FOCUS_UP : View.FOCUS_DOWN)); idle();
                float px = surface.getWidth() * .6f, py = top(scroll) - top(surface) + scroll.getHeight() * .4f; long start = SystemClock.uptimeMillis();
                main(() -> { send(start, start, MotionEvent.ACTION_DOWN, px, py); send(start, start + 60, MotionEvent.ACTION_MOVE, px, py - direction * Ui.dp(activity, 60)); require(((NotificationScrollView) scroll).ownsPull() && deformation() * direction > 0, "product builder mounts the native force scroll for both edges"); });
                screenshot(direction < 0 ? "notification-force-product-top" : "notification-force-product-bottom");
                test.waitForIdleSync(); Bitmap frame = test.getUiAutomation().takeScreenshot(); require(frame != null, "hardware notification screenshot available");
                File file = new File(context.getFilesDir(), "ui-smoke/notification-force-hardware-" + (direction < 0 ? "top" : "bottom") + ".png");
                try (FileOutputStream output = new FileOutputStream(file)) { frame.compress(Bitmap.CompressFormat.PNG, 100, output); } finally { frame.recycle(); }
                main(() -> { send(start, start + 80, MotionEvent.ACTION_CANCEL, px, py); require(deformation() == 0, "product force cancels without leaving offsets"); });
            }
            return "PASS: " + assertions + " notification force assertions; expanded groups, multi-touch, real product builder and hardware screenshots";
        } finally { main(() -> activity.finish()); }
    }
    String runReadingForce() throws Exception {
        activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            mount(DockGeometry.BOTTOM);
            for (int direction : new int[]{-1, 1}) {
                List<StatusBarNotification> content = new ArrayList<>(); for (int i = 0; i < 24; i++) content.add(basic(2400 + i, "reading-force-" + i, now - i, "滚动联动 " + i));
                main(() -> { center.update(false, List.of()); center.update(true, content); }); idle();
                main(() -> scroll.scrollTo(0, Ui.dp(activity, 320))); idle();
                NotificationSwipeRow[] marked = new NotificationSwipeRow[2]; android.graphics.Rect[] baseline = new android.graphics.Rect[2]; int[] before = {0}, layoutHeights = new int[2];
                main(() -> {
                    int count = 0;
                    for (int i = 0; i < list().getChildCount() && count < 2; i++) {
                        NotificationSwipeRow row = (NotificationSwipeRow) ((ViewGroup) list().getChildAt(i)).getChildAt(0); int local = top(row) - top(scroll);
                        if (local >= Ui.dp(activity, direction < 0 ? 64 : 8) && local + row.getHeight() < scroll.getHeight() - Ui.dp(activity, direction > 0 ? 64 : 8)) marked[count++] = row;
                    }
                    require(count == 2, "ordinary scroll fixture has two fully visible middle cards");
                    marked[0].surface.setBackgroundColor(android.graphics.Color.MAGENTA); marked[1].surface.setBackgroundColor(android.graphics.Color.CYAN);
                    marked[0].setWillNotDraw(true); marked[1].setWillNotDraw(true);
                    baseline[0] = coloredBounds(android.graphics.Color.MAGENTA); baseline[1] = coloredBounds(android.graphics.Color.CYAN); before[0] = scroll.getScrollY();
                    layoutHeights[0] = marked[0].getHeight(); layoutHeights[1] = marked[1].getHeight();
                    require(marked[0].scrollForce.position == 0 && marked[1].scrollForce.position == 0, "programmatic scroll does not inject a reading force");
                });
                float x = surface.getWidth() * .6f, y = top(scroll) - top(surface) + scroll.getHeight() * .5f; long at = SystemClock.uptimeMillis();
                main(() -> send(at, at, MotionEvent.ACTION_DOWN, x, y));
                for (int step = 1; step <= 12; step++) { final int n = step; SystemClock.sleep(18); main(() -> send(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, n * 3))); }
                main(() -> {
                    require(scroll.canScrollVertically(-1) && scroll.canScrollVertically(1) && deformation() == 0 && !((NotificationScrollView) scroll).ownsPull(), "reading force is visible in the middle, independently of boundary pull");
                    int nativeTravel = before[0] - scroll.getScrollY(); android.graphics.Rect a = coloredBounds(android.graphics.Color.MAGENTA), b = coloredBounds(android.graphics.Color.CYAN);
                    int reactionA = a.top - baseline[0].top - nativeTravel, reactionB = b.top - baseline[1].top - nativeTravel;
                    require(reactionA * direction > Ui.dp(activity, 3) && reactionB * direction > Ui.dp(activity, 3), "skip-draw glass card pixels have visible force displacement beyond normal scrolling: " + reactionA + "/" + reactionB);
                    require(Math.abs(reactionA - reactionB) >= Ui.dp(activity, 2), "adjacent glass cards have visibly different displacement: " + reactionA + "/" + reactionB);
                    require(marked[0].getHeight() == layoutHeights[0] && marked[1].getHeight() == layoutHeights[1] && marked[0].surface.getScaleY() == 1, "reading jelly deforms only drawing, preserving layout and hit geometry");
                    require(panelDrags == 0 && opens == 0 && clears == 0, "reading force does not launch, clear or dismiss");
                });
                screenshot(direction < 0 ? "notification-reading-up" : "notification-reading-down");
                main(() -> send(at, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y + direction * Ui.dp(activity, 36))); SystemClock.sleep(1000); idle();
                main(() -> require(marked[0].scrollForce.position == 0 && marked[1].scrollForce.position == 0, "ordinary scroll and fling return to exact resting positions"));
                long next = SystemClock.uptimeMillis(); main(() -> send(next, next, MotionEvent.ACTION_DOWN, x, y));
                for (int step = 1; step <= 4; step++) { final int n = step; SystemClock.sleep(18); main(() -> send(next, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, n * 6))); }
                main(() -> { secondPointer(next, SystemClock.uptimeMillis(), x, y - direction * Ui.dp(activity, 24)); require(marked[0].scrollForce.position == 0 && marked[1].scrollForce.position == 0, "multi-pointer cancellation clears ordinary scroll forces"); send(next, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y); });
            }
            checkSceneForce();
            checkLinkedForce();
            return "PASS: " + assertions + " notification force-network assertions; skip-draw glass, hardware entry, ordinary scroll, header press, side swipe and cancellation";
        } finally { main(() -> activity.finish()); }
    }
    private void checkSceneForce() throws Exception {
        mount(DockGeometry.BOTTOM);
        InterfaceCard[] card = new InterfaceCard[1]; NotificationSwipeRow[] marked = new NotificationSwipeRow[2];
        main(() -> {
            center.update(false, List.of()); List<StatusBarNotification> content = new ArrayList<>(); for (int i = 0; i < 24; i++) content.add(basic(2800 + i, "scene-force-" + i, now - i, "入场联动 " + i)); center.update(true, content);
            ((ViewGroup) surface.getParent()).removeView(surface); card[0] = new InterfaceCard(activity, new Prefs(activity), InterfaceCard.NOTIFICATIONS, surface, () -> { }, () -> { });
            activity.setContentView(card[0]);
        }); idle(); main(() -> scroll.scrollTo(0, Ui.dp(activity, 320))); idle();
        android.graphics.Rect[] baseline = new android.graphics.Rect[2]; int[] location = new int[2];
        main(() -> {
            int count = 0; for (int i = 0; i < list().getChildCount() && count < 2; i++) { NotificationSwipeRow row = (NotificationSwipeRow) ((ViewGroup) list().getChildAt(i)).getChildAt(0); int local = top(row) - top(scroll); if (local >= Ui.dp(activity, 24) && local + row.getHeight() < scroll.getHeight() - Ui.dp(activity, 50)) marked[count++] = row; }
            require(count == 2, "scene has two fully visible cards");
            marked[0].setWillNotDraw(true); marked[1].setWillNotDraw(true); marked[0].surface.setBackgroundColor(android.graphics.Color.MAGENTA); marked[1].surface.setBackgroundColor(android.graphics.Color.CYAN);
            baseline[0] = coloredBounds(android.graphics.Color.MAGENTA); baseline[1] = coloredBounds(android.graphics.Color.CYAN);
            card[0].enter(.65f, DockGeometry.BOTTOM, surface.getHeight());
        });
        for (int frame = 1; frame <= 18; frame++) { final int n = frame; SystemClock.sleep(18); main(() -> card[0].enter(.65f + .35f * n / 18, DockGeometry.BOTTOM, surface.getHeight())); }
        main(() -> { require(marked[0].scrollForce.position > Ui.dp(activity, 4) && marked[1].scrollForce.position > marked[0].scrollForce.position + Ui.dp(activity, 2), "InterfaceCard entry drives staggered card inertia: " + marked[0].scrollForce.position + "/" + marked[1].scrollForce.position); surface.getLocationOnScreen(location); });
        Bitmap hardware = test.getUiAutomation().takeScreenshot(); require(hardware != null, "hardware entry screenshot available");
        try {
            android.graphics.Rect a = coloredBounds(hardware, android.graphics.Color.MAGENTA), b = coloredBounds(hardware, android.graphics.Color.CYAN);
            require(a.top - location[1] - baseline[0].top > Ui.dp(activity, 3) && b.top - location[1] - baseline[1].top > Ui.dp(activity, 3), "hardware display list renders entry spring in skip-draw glass mode");
        } finally { hardware.recycle(); }
        SystemClock.sleep(1300); idle(); main(() -> require(marked[0].scrollForce.position == 0 && marked[1].scrollForce.position == 0 && card[0].getTranslationY() == 0, "entry restores exact resting card geometry"));
    }
    private void checkLinkedForce() throws Exception {
        NotificationForceHeader header = (NotificationForceHeader) surface.getChildAt(0);
        NotificationSwipeRow[] rows = new NotificationSwipeRow[2]; android.graphics.Rect[] baseline = new android.graphics.Rect[2];
        main(() -> {
            int count = 0; for (int i = 0; i < list().getChildCount() && count < 2; i++) { NotificationSwipeRow row = (NotificationSwipeRow) ((ViewGroup) list().getChildAt(i)).getChildAt(0); int local = top(row) - top(scroll); if (local >= Ui.dp(activity, 24) && local + row.getHeight() < scroll.getHeight() - Ui.dp(activity, 50)) rows[count++] = row; }
            require(count == 2, "linked force fixture has two visible cards");
            header.getChildAt(0).setBackgroundColor(android.graphics.Color.YELLOW); rows[0].surface.setBackgroundColor(android.graphics.Color.MAGENTA); rows[1].surface.setBackgroundColor(android.graphics.Color.CYAN); rows[0].setWillNotDraw(true); rows[1].setWillNotDraw(true);
            baseline[0] = coloredBounds(android.graphics.Color.YELLOW); baseline[1] = coloredBounds(android.graphics.Color.CYAN);
            long at = SystemClock.uptimeMillis(); MotionEvent down = MotionEvent.obtain(at, at, MotionEvent.ACTION_DOWN, header.getChildAt(0).getWidth() * .5f, header.getHeight() * .5f, 0); header.dispatchTouchEvent(down); down.recycle();
        }); SystemClock.sleep(90);
        main(() -> {
            require(header.moving() && coloredBounds(android.graphics.Color.YELLOW).top > baseline[0].top + Ui.dp(activity, 3), "header press moves actual title pixels through the shared force clock");
            require(coloredBounds(android.graphics.Color.CYAN).top > baseline[1].top + Ui.dp(activity, 2), "header press propagates to nearby notification cards");
            long at = SystemClock.uptimeMillis(); MotionEvent cancel = MotionEvent.obtain(at, at, MotionEvent.ACTION_CANCEL, 0, 0, 0); header.dispatchTouchEvent(cancel); cancel.recycle();
            require(!header.moving() && rows[0].scrollForce.position == 0 && rows[1].scrollForce.position == 0, "canceled header touch clears the whole force network");
            rows[0].showActions(true);
        }); SystemClock.sleep(170);
        main(() -> {
            require(rows[1].sideForce.position < -Ui.dp(activity, 1), "side reveal transmits horizontal tension to another notification");
            require(header.moving() && coloredBounds(android.graphics.Color.YELLOW).right < baseline[0].right, "side reveal also moves title/actions instead of remaining isolated in one card");
            ((NotificationScrollView) scroll).cancelLinkedForce(); rows[0].showActions(false);
        }); SystemClock.sleep(1300); idle();
        main(() -> require(!header.moving() && rows[0].sideForce.position == 0 && rows[1].sideForce.position == 0, "side reveal and rejoin leave no residual network motion"));
    }
    private void checkInwardFling(int direction, int preparation) {
        List<StatusBarNotification> content = new ArrayList<>(); for (int i = 0; i < 30; i++) content.add(basic(1800 + i, "fling-" + i, now - i, "惯性检查 " + i));
        main(() -> { panelDrags = 0; center.update(false, List.of()); center.update(true, content); }); idle();
        main(() -> scroll.fullScroll(direction > 0 ? View.FOCUS_DOWN : View.FOCUS_UP)); idle();
        float x = surface.getWidth() * .6f, y = top(scroll) - top(surface) + scroll.getHeight() * .5f;
        if (preparation == 1 && android.animation.ValueAnimator.areAnimatorsEnabled()) {
            long at = SystemClock.uptimeMillis();
            main(() -> { send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 20, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 40)); send(at, at + 30, MotionEvent.ACTION_UP, x, y - direction * Ui.dp(activity, 50)); });
            SystemClock.sleep(40); main(() -> require(deformation() != 0, "rebound fixture still has elastic displacement"));
        }
        int[] released = {0}; long at = SystemClock.uptimeMillis();
        main(() -> {
            send(at, at, MotionEvent.ACTION_DOWN, x, y);
            if (preparation == 2) send(at, at + 10, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 24));
            for (int i = 1; i <= 6; i++) send(at, at + 10 + i * 10, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, i * 22));
            send(at, at + 80, MotionEvent.ACTION_UP, x, y + direction * Ui.dp(activity, 145)); released[0] = scroll.getScrollY();
            require(!((NotificationScrollView) scroll).ownsPull(), "inward scroll releases boundary ownership: " + direction + "/" + preparation);
        });
        SystemClock.sleep(160); test.waitForIdleSync();
        main(() -> {
            int travel = (released[0] - scroll.getScrollY()) * direction;
            require(travel > Ui.dp(activity, 60), "inward fling continues after release: " + direction + "/" + preparation + " travel=" + travel);
            require(panelDrags == 0 && !panelFinished, "inward fling never hands off to panel dismissal");
            long cancel = SystemClock.uptimeMillis(); send(cancel, cancel, MotionEvent.ACTION_DOWN, x, y); send(cancel, cancel + 1, MotionEvent.ACTION_CANCEL, x, y);
        });
    }
    private void idle() { test.waitForIdleSync(); SystemClock.sleep(TaskSpring.DURATION + 400); }
    private void require(boolean value, String message) { if (!value) throw new AssertionError(message); assertions++; }
    private StatusBarNotification item(int id, String pkg, String group, String shortcut, int user, long time, int flags, String title, String body) {
        Notification.Builder builder = new Notification.Builder(context, "notification-fixture").setSmallIcon(R.drawable.ic_ms_notifications).setContentTitle(title).setContentText(body).setWhen(time);
        if (group != null) builder.setGroup(group); if (shortcut != null) builder.setShortcutId(shortcut);
        Notification notification = builder.build(); notification.flags |= flags;
        return new StatusBarNotification(pkg, pkg, id, "fixture", user * 100000 + 10001, 0, 0, notification, UserHandle.getUserHandleForUid(user * 100000 + 10001), time);
    }
    private StatusBarNotification basic(int id, String group, long time, String title) { return item(id, context.getPackageName(), group, null, 0, time, 0, title, "本地通知示例，检查分组、时间和滑动操作。"); }
    private void checkModel() {
        StatusBarNotification a = basic(1, "chat", now - 600000, "家庭群"), b = basic(2, "chat", now - 300000, "家庭群");
        StatusBarNotification summary = item(3, context.getPackageName(), "chat", null, 0, now, Notification.FLAG_GROUP_SUMMARY, "摘要", "两条通知");
        List<NotificationGroups.Group> groups = NotificationGroups.build(List.of(a, b, summary));
        require(groups.size() == 1 && groups.get(0).items.size() == 2, "summary does not inflate count");
        require(groups.get(0).items.get(0) == b, "newest child is collapsed preview");
        require(NotificationGroups.build(List.of(a, a, b)).get(0).items.size() == 2, "duplicate keys are not duplicate messages");
        require(NotificationGroups.build(List.of(summary)).get(0).items.size() == 1, "summary without children remains visible");
        StatusBarNotification profile = item(4, context.getPackageName(), "chat", null, 10, now, 0, "工作资料", "正文");
        require(NotificationGroups.build(List.of(a, profile)).size() == 2, "different users never merge");
        StatusBarNotification foreign = item(5, "other.app", "chat", null, 0, now, 0, "其他应用", "正文");
        require(NotificationGroups.build(List.of(a, foreign)).size() == 2, "same group string from different apps never merges");
        StatusBarNotification one = item(6, context.getPackageName(), "chat", "person-one", 0, now, 0, "聊天一", "正文"), two = item(7, context.getPackageName(), "chat", "person-two", 0, now, 0, "聊天二", "正文");
        require(NotificationGroups.build(List.of(one, two, summary)).size() == 2, "conversation IDs preserve different conversations");
        require(NotificationGroups.build(List.of(basic(8, null, now, "甲"), basic(9, null, now, "乙"))).size() == 1, "ungrouped app notifications stack");
        StatusBarNotification ongoing = item(10, context.getPackageName(), null, null, 0, now, Notification.FLAG_ONGOING_EVENT, "正在运行", "常驻");
        require(NotificationGroups.clearable(List.of(a, ongoing)).equals(List.of(a)), "ongoing notification is excluded from clear");
        StatusBarNotification changed = basic(1, "chat", now + 1, "更新后的内容");
        require(NotificationGroups.clearKeys(List.of(a, b), List.of(changed, b, ongoing, foreign)).equals(List.of(b.getKey())), "old clear request excludes changed keys, new arrivals and ongoing");
        require(NotificationGroups.clearKeys(List.of(summary), List.of(summary, a)).isEmpty(), "old summary clear cannot remove a new child");
        List<StatusBarNotification> complete = NotificationGroups.clearRequest(List.of(a, b), List.of(summary, a, b));
        require(complete.size() == 3 && NotificationGroups.clearKeys(complete, List.of(summary, a, b)).size() == 3, "complete group cleanup includes its app-owned summary");
        StatusBarNotification fresh = basic(12, "chat", now + 2, "刚收到的消息");
        List<String> withNewChild = NotificationGroups.clearKeys(complete, List.of(summary, a, b, fresh));
        require(withNewChild.size() == 2 && !withNewChild.contains(summary.getKey()) && !withNewChild.contains(fresh.getKey()), "fresh child protects itself and its summary from an older group request");
        StatusBarNotification permanentChild = item(13, context.getPackageName(), "chat", null, 0, now, Notification.FLAG_NO_CLEAR, "常驻子项", "正文");
        require(!NotificationGroups.clearKeys(complete, List.of(summary, a, b, permanentChild)).contains(summary.getKey()), "unclearable child prevents summary cascade");
        require(NotificationGroups.clearRequest(List.of(a), List.of(summary, a, b)).size() == 1, "single-child clear preserves sibling and summary");
        require(NotificationGroups.clearKeys(List.of(a), List.of()).isEmpty(), "removed key is not cancelled again");
        StatusBarNotification qq = item(11, "com.tencent.mobileqq", null, null, 0, now, 0, "QQ", "正文");
        Intent intent = NotificationGroups.settingsIntent(qq);
        require(intent.getAction().equals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS) && "com.tencent.mobileqq".equals(intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE)), "settings points to owning app notification page");
        List<StatusBarNotification> many = new ArrayList<>(); for (int i = 0; i < 40; i++) many.add(basic(100 + i, "big", now - i * 1000, "消息 " + i));
        require(NotificationGroups.build(many).get(0).items.size() == 40, "group count is computed before display pagination");
        a.getNotification().when = 0; require(NotificationGroups.timestamp(a, now) == a.getPostTime(), "missing event time falls back to post time");
        a.getNotification().when = now + 60000; require(NotificationGroups.timestamp(a, now) == a.getPostTime(), "future event time falls back to post time");
    }
    private List<StatusBarNotification> demo() {
        List<StatusBarNotification> items = new ArrayList<>();
        items.add(basic(1, "family", now - 300000, "家庭群 · 今晚一起吃饭")); items.add(basic(2, "family", now - 600000, "家庭群 · 已经出发了")); items.add(basic(3, "family", now - 900000, "家庭群 · 记得带伞"));
        items.add(basic(4, "parcel", now - 3900000, "取件提醒 · 您的包裹已到站")); items.add(basic(5, "parcel", now - 7200000, "还有一件包裹待领取"));
        items.add(basic(6, "calendar", now - 9000000, "明天下午的项目讨论需要提前准备，并检查外屏排版"));
        items.add(item(7, context.getPackageName(), null, null, 0, now - 86400000, Notification.FLAG_ONGOING_EVENT, "正在运行", "常驻通知会保留"));
        return items;
    }
    private void mount(int edge) {
        main(() -> {
            panelDrags = 0; panelProgress = 1; panelFinished = panelClosed = false;
            // PanelSurface accepts a dock edge; its dismiss direction is the opposite edge.
            surface = new PanelSurface(activity, edge, 700, false, new PanelHeaderView.Listener() { public void begin() { panelDrags++; } public void progress(float value) { panelProgress = value; } public void finish(boolean close) { panelFinished = true; panelClosed = close; } });
            surface.setTag("panel-surface"); surface.setBackgroundColor(Ui.BACKGROUND); surface.setPadding(Ui.dp(activity, 9), Ui.dp(activity, 12), Ui.dp(activity, 9), Ui.dp(activity, 28));
            surface.setClipChildren(false); surface.setClipToPadding(false);
            center = new NotificationCenterView(activity, new NotificationCenterView.Actions() {
                public void open(StatusBarNotification item) { opens++; }
                public void settings(StatusBarNotification item) { settingsTarget = item; }
                public void permission() { }
                public void clear(List<StatusBarNotification> items) { clears++; clearRequest = new ArrayList<>(items); }
            });
            LinearLayout header = new NotificationForceHeader(activity); header.addView(Ui.heading(activity, "通知中心", 17), new LinearLayout.LayoutParams(0, Ui.dp(activity, 40), 1)); header.addView(center.newNotice); header.addView(center.clearAll); surface.addView(header);
            scroll = new NotificationScrollView(activity); scroll.addView(center); surface.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
            activity.setContentView(surface); center.update(true, demo());
        }); idle();
    }
    private void productPreview() {
        main(() -> {
            CoverService owner = new CoverService(); owner.screenContext = activity; owner.prefs = new Prefs(activity); owner.display = activity.getDisplay();
            int width = activity.getResources().getDisplayMetrics().widthPixels, height = activity.getResources().getDisplayMetrics().heightPixels;
            DockGeometry.Placement placement = DockGeometry.edgeTouch(DockGeometry.resolve(width, height, List.of(), activity.getResources().getDisplayMetrics().density, 3, .46f, .088f, false), width, height);
            DockGeometry.Box area = DockGeometry.panelContent(placement, width, height, List.of()); int top = new StatusBarView(activity, owner.prefs).heightPixels() + Ui.dp(activity, 4);
            owner.placement = new DockGeometry.Placement(placement.visual(), placement.touch(), new DockGeometry.Box(area.x(), top, area.width(), area.bottom() - top), placement.edge(), placement.measured());
            surface = (PanelSurface) owner.buildPanelContent("notifications", new DockGeometry.Box(0, 0, width, height), top); surface.setTag("panel-surface");
            FrameLayout root = new FrameLayout(activity); root.setBackgroundColor(Ui.BACKGROUND); root.addView(surface, new FrameLayout.LayoutParams(-1, -1)); root.addView(new StatusBarView(activity, owner.prefs), new FrameLayout.LayoutParams(width, top));
            activity.setContentView(root); center = surface.findViewWithTag("notification-center"); center.update(true, demo());
        });
    }
    private LinearLayout list() { return center.findViewWithTag("notification-list"); }
    private void checkHeader() throws Exception {
        main(() -> {
            LinearLayout header = surface.findViewWithTag("panel-header"); View settings = header.findViewWithTag("notification-center-settings");
            require(settings != null && settings.hasOnClickListeners() && settings.isEnabled(), "header has an enabled notification-center settings action");
            View clearTarget=center.clearAll.getParent() instanceof PanelActionSlot slot ? slot : center.clearAll,settingsTarget=settings.getParent() instanceof PanelActionSlot slot ? slot : settings;
            require(clearTarget.getParent() == header && center.clearAll.getText().toString().equals("6"), "clear action belongs to header and displays count only");
            require(center.clearAll.getCompoundDrawables()[0] != null && center.clearAll.getContentDescription().toString().contains("6条"), "clear icon and accessible full description remain available");
            require(settingsTarget.getRight() <= clearTarget.getLeft() && clearTarget.getRight() <= header.getChildAt(header.getChildCount() - 1).getLeft(), "settings, clear and close targets do not overlap");
            View titleArea = header.getChildAt(0); require(titleArea.getWidth() >= Ui.dp(activity, 74) && header.getHeight() <= Ui.dp(activity, 40), "header reserves readable title width without a second row");
            View content = surface.getChildAt(surface.getChildCount() - 2); require(content instanceof ScrollView && surface.indexOfChild(header) + 1 == surface.indexOfChild(content), "notification list fills remaining surface with no clear footer");
            center.update(true, List.of()); require(center.clearAll.getText().toString().equals("0") && !center.clearAll.isEnabled() && center.clearAll.getVisibility() == View.VISIBLE, "empty header keeps a disabled zero count instead of shifting controls");
        }); idle(); screenshot("notifications-empty");
        List<StatusBarNotification> many = new ArrayList<>(); for (int i = 0; i < 120; i++) many.add(basic(500 + i, "count", now - i, "数量检查"));
        main(() -> center.update(true, many)); idle();
        main(() -> {
            require(center.clearAll.getText().toString().equals("120"), "header displays full three-digit clear count");
            LinearLayout header = surface.findViewWithTag("panel-header"); require(header.getChildAt(0).getWidth() >= Ui.dp(activity, 64), "three-digit count leaves title space at large font size");
            require(center.clearAll.getLayout().getEllipsisCount(0) == 0, "clear count is not truncated");
            center.update(true, demo());
        }); idle();
        List<StatusBarNotification> reading = new ArrayList<>(); for (int i = 0; i < 12; i++) reading.add(basic(700 + i, "header-read-" + i, now - i, "阅读测试"));
        main(() -> center.update(true, reading)); idle();
        main(() -> {
            ScrollView currentScroll = (ScrollView) center.getParent().getParent(); currentScroll.scrollTo(0, Ui.dp(activity, 150)); reading.add(0, basic(800, "header-new", now + 1, "新通知")); center.update(true, reading);
            LinearLayout header = surface.findViewWithTag("panel-header"); View title = ((ViewGroup) header.getChildAt(0)).getChildAt(0);
            require(center.newNotice.getVisibility() == View.VISIBLE && title.getVisibility() == View.INVISIBLE, "new-message prompt occupies the title slot without overlapping title text");
        }); idle(); screenshot("notifications-new");
        main(() -> {
            center.newNotice.performClick(); LinearLayout header = surface.findViewWithTag("panel-header"); View title = ((ViewGroup) header.getChildAt(0)).getChildAt(0);
            require(center.newNotice.getVisibility() == View.GONE && title.getVisibility() == View.VISIBLE, "returning to new notifications restores the header title");
            require(header.getHeight() <= Ui.dp(activity, 40), "pending notice never adds a header row"); center.update(true, demo());
        }); idle();
    }
    private NotificationSwipeRow firstRow() { return (NotificationSwipeRow) ((ViewGroup) list().getChildAt(0)).getChildAt(0); }
    private void checkUi() throws Exception {
        main(() -> {
            require(list().getChildCount() == 4, "seven notices render as four groups");
            TextView badge = firstRow().findViewWithTag("notification-count"); require(badge.getText().toString().equals("3"), "badge shows actual child count");
            TextView time = firstRow().findViewWithTag("notification-time"); require(time.length() > 0 && time.getContentDescription().toString().contains(":"), "relative and accessible full time exist");
            View group = list().getChildAt(0); firstRow().surface.performClick(); require(((ViewGroup) group).getChildAt(1).getVisibility() == View.VISIBLE, "group tap expands children");
            require(((ViewGroup) ((ViewGroup) group).getChildAt(1)).getChildCount() == 3 && opens == 0, "group expansion does not launch latest message");
        }); idle();
        View child = ((ViewGroup) ((ViewGroup) list().getChildAt(0)).getChildAt(1)).getChildAt(0);
        main(() -> {
            ((NotificationSwipeRow) child).surface.performClick(); require(opens == 1, "individual child opens original notification");
            View group = list().getChildAt(0); center.update(true, demo()); require(list().getChildAt(0) == group && ((ViewGroup) group).getChildAt(1).getVisibility() == View.VISIBLE, "refresh preserves expanded group and container");
            require(((ViewGroup) ((ViewGroup) group).getChildAt(1)).getChildAt(0) == child, "refresh preserves child identity");
            firstRow().surface.performClick(); center.clearAll.performClick(); require(clearRequest.size() == 6, "clear all includes clearable members only");
            require(list().getChildCount() == 4, "request does not pretend system cancellation succeeded");
        });
        List<StatusBarNotification> reading = new ArrayList<>(); for (int i = 0; i < 12; i++) reading.add(basic(30 + i, "row-" + i, now - i * 60000, "阅读中的通知 " + i));
        // The preceding clear-request fixture may retain its cards for an exit animation.
        main(() -> { center.update(false, List.of()); center.update(true, reading); }); idle();
        final int[] offset = {0}, changes = {0}; final View[] first = {null};
        main(() -> {
            first[0] = list().getChildAt(0); TextView title = findText(first[0], "阅读中的通知 0");
            title.addTextChangedListener(new android.text.TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) { } public void onTextChanged(CharSequence s, int start, int before, int count) { changes[0]++; } public void afterTextChanged(android.text.Editable s) { } });
            scroll.scrollTo(0, Ui.dp(activity, 160)); offset[0] = scroll.getScrollY(); require(offset[0] > 0, "fixture scrolls on cover viewport");
            for (int i = 0; i < 60; i++) center.update(true, reading);
            require(changes[0] == 0 && scroll.getScrollY() == offset[0] && list().getChildAt(0) == first[0], "sixty identical refreshes preserve text, scroll and row identity");
            reading.add(0, basic(90, "new", now + 1, "刚到的新通知")); center.update(true, reading);
            require(list().getChildCount() == 12 && center.newNotice.getVisibility() == View.VISIBLE, "new arrival waits behind header notice while reading");
        }); idle();
        main(() -> { require(scroll.getScrollY() == offset[0], "deferred arrival does not move reading position after layout"); center.newNotice.performClick(); }); idle();
        main(() -> {
            require(scroll.getScrollY() == 0 && list().getChildCount() == 13 && list().getChildAt(1) == first[0], "new notice reveals arrival without rebuilding existing rows");
            center.update(false, List.of()); require(list().getChildCount() == 0 && !center.clearAll.isEnabled(), "disconnect removes stale content and disables clear action");
            require(findText(center, "通知使用权未连接") != null, "connection loss has explicit state");
            center.update(true, List.of()); require(findText(center, "暂无通知") != null, "connected empty state is distinct");
            StatusBarNotification ongoing = demo().get(6); center.update(true, List.of(ongoing)); require(!center.clearAll.isEnabled() && findText(center, "仅剩常驻通知") != null, "ongoing-only state disables clear all");
            firstRow().showActions(true); require(firstRow().findViewWithTag("notification-clear").getVisibility() == View.GONE, "ongoing card exposes settings only");
        }); idle();
        List<StatusBarNotification> pages = new ArrayList<>(); for (int i = 0; i < 35; i++) pages.add(basic(200 + i, "page-" + i, now - i * 60000, "分页通知 " + i));
        main(() -> { scroll.scrollTo(0, 0); center.update(true, List.of()); center.update(true, pages); }); idle();
        main(() -> {
            require(list().getChildCount() == 30, "first page mounts thirty groups"); View firstPage = list().getChildAt(0);
            findText(center, "显示更多").performClick(); require(list().getChildCount() == 35 && list().getChildAt(0) == firstPage, "older groups append without moving recent rows");
            require(findText(list().getChildAt(34), "分页通知 34") != null, "pagination retains chronological insertion order");
            pages.add(0, basic(299, "page-new", now + 1, "分页后的新通知")); center.update(true, pages);
            require(list().getChildCount() == 36 && list().getChildAt(1) == firstPage, "new group stays visible after pagination");
        }); idle();
    }
    private void checkSwipes() throws Exception {
        main(() -> { scroll.scrollTo(0, 0); center.update(true, demo()); }); idle();
        checkSwipeAxis();
        main(() -> { firstRow().findViewWithTag("notification-settings").performClick(); require(settingsTarget != null && settingsTarget.getKey().equals(demo().get(0).getKey()), "revealed settings retains actual app target"); firstRow().showActions(true); }); idle();
        main(() -> { firstRow().findViewWithTag("notification-clear").performClick(); require(clearRequest.size() == 3, "collapsed group action clears only its three members"); }); idle();
        main(() -> firstRow().surface.performLongClick()); idle(); require(firstRow().opened(), "long press exposes same actions");
        main(() -> { NotificationSwipeRow second = (NotificationSwipeRow) ((ViewGroup) list().getChildAt(1)).getChildAt(0); second.surface.performLongClick(); }); idle(); require(!firstRow().opened(), "only one action rail remains open");
        main(() -> center.closeActions()); idle();
        int[] xy = new int[2]; firstRow().getLocationInWindow(xy); float x = surface.getWidth() * .65f, y = xy[1] - top(surface) + firstRow().getHeight() / 2f;
        gesture(x, y, x - Ui.dp(activity, 70), y, true); require(!firstRow().opened(), "cancelled horizontal gesture returns closed");
    }
    private void checkSwipeAxis() {
        main(() -> { scroll.scrollTo(0, 0); center.closeActions(); }); idle();
        int before = clears, oldOpens = opens; float x = surface.getWidth() * .72f, y = top(firstRow()) - top(surface) + firstRow().getHeight() / 2f;
        gesture(x, y, x - Ui.dp(activity, 130), y + 1, false);
        require(firstRow().opened(), "horizontal swipe exposes rail"); require(panelDrags == 0, "horizontal notification gesture never drags rotated panel"); require(clears == before && opens == oldOpens, "long swipe neither clears nor launches notification");
    }
    private float deformation() {
        try { java.lang.reflect.Field field = NotificationScrollView.class.getDeclaredField("deformation"); field.setAccessible(true); return field.getFloat(scroll); }
        catch (ReflectiveOperationException error) { throw new AssertionError(error); }
    }
    private android.graphics.Rect coloredBounds(int color) {
        Bitmap frame = Bitmap.createBitmap(surface.getWidth(), surface.getHeight(), Bitmap.Config.ARGB_8888); surface.draw(new Canvas(frame));
        try { return coloredBounds(frame, color); } finally { frame.recycle(); }
    }
    private android.graphics.Rect coloredBounds(Bitmap frame, int color) {
        int width = frame.getWidth(), height = frame.getHeight(); int[] pixels = new int[width * height]; frame.getPixels(pixels, 0, width, 0, 0, width, height);
        android.graphics.Rect bounds = new android.graphics.Rect(width, height, 0, 0);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) if (pixels[y * width + x] == color) { bounds.left = Math.min(bounds.left, x); bounds.top = Math.min(bounds.top, y); bounds.right = Math.max(bounds.right, x + 1); bounds.bottom = Math.max(bounds.bottom, y + 1); }
        return bounds;
    }
    private void checkElasticity(int direction) throws Exception {
        List<StatusBarNotification> content = new ArrayList<>(); for (int i = 0; i < 12; i++) content.add(basic(1000 + i, "elastic-" + i, now - i, "弹性通知 " + i));
        main(() -> { center.closeActions(); center.update(false, List.of()); center.update(true, content); }); idle();
        final int[] bottom = {0}, height = {0}; final View[] card = {null}; final android.graphics.Rect[] original = new android.graphics.Rect[2];
        main(() -> {
            scroll.fullScroll(direction > 0 ? View.FOCUS_DOWN : View.FOCUS_UP); card[0] = ((ViewGroup) list().getChildAt(direction > 0 ? list().getChildCount() - 1 : 0)).getChildAt(0); height[0] = card[0].getHeight();
            ((NotificationSwipeRow) card[0]).surface.setBackgroundColor(android.graphics.Color.MAGENTA);
            ((NotificationSwipeRow) card[0]).setWillNotDraw(true);
            NotificationSwipeRow neighbor = (NotificationSwipeRow) ((ViewGroup) list().getChildAt(direction > 0 ? list().getChildCount() - 2 : 1)).getChildAt(0); neighbor.surface.setBackgroundColor(android.graphics.Color.CYAN);
        }); idle();
        float x = surface.getWidth() * .6f, y = top(scroll) - top(surface) + scroll.getHeight() * .5f;
        long start = SystemClock.uptimeMillis(); int oldOpens = opens, oldClears = clears;
        main(() -> {
            bottom[0] = scroll.getScrollY(); original[0] = coloredBounds(android.graphics.Color.MAGENTA); original[1] = coloredBounds(android.graphics.Color.CYAN);
            send(start, start, MotionEvent.ACTION_DOWN, x, y);
            send(start, start + 20, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 35));
            send(start, start + 40, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 60));
            require(deformation() * direction > 0 && ((NotificationScrollView) scroll).ownsPull(), "both boundaries have immediate elastic feedback");
            android.graphics.Rect shifted = coloredBounds(android.graphics.Color.MAGENTA), neighbor = coloredBounds(android.graphics.Color.CYAN);
            require(!original[0].isEmpty() && !original[1].isEmpty() && !shifted.isEmpty() && !neighbor.isEmpty(), "both edge cards are actually drawn");
            int travel = shifted.top - original[0].top, neighborTravel = neighbor.top - original[1].top;
            require(travel * direction < -Ui.dp(activity, 8), "whole card follows the continued edge pull: direction=" + direction + ", travel=" + travel);
            require((neighborTravel - travel) * direction < 0, "edge pull opens card gaps rather than compressing them: edge=" + direction + ", travel=" + travel + ", neighbor=" + neighborTravel);
            require(shifted.height() > original[0].height() && shifted.width() < original[0].width(), "continued boundary pull visibly stretches glass cards vertically and narrows them");
            require(card[0].getHeight() == height[0] && scroll.getScrollY() == bottom[0], "stretch preserves layout height and reading position");
            require(panelDrags == 0 && opens == oldOpens && clears == oldClears, "below-threshold elasticity neither dismisses panel nor activates notification");
        }); screenshot(direction > 0 ? "notification-force-bottom" : "notification-force-top"); main(() -> {
            send(start, start + 50, MotionEvent.ACTION_UP, x, y - direction * Ui.dp(activity, 75));
            require(deformation() * direction > 0, "release retains displacement for inertial return");
        }); SystemClock.sleep(750); test.waitForIdleSync();
        main(() -> require(deformation() == 0 && scroll.getScrollY() == bottom[0], "spring settles exactly at original bottom"));
        final long canceledStart = SystemClock.uptimeMillis();
        main(() -> {
            send(canceledStart, canceledStart, MotionEvent.ACTION_DOWN, x, y); send(canceledStart, canceledStart + 30, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 80));
            float stretched = deformation() * direction; send(canceledStart, canceledStart + 35, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 50));
            require(deformation() * direction > 0 && deformation() * direction < stretched, "reversing finger motion continuously reduces stretch");
            send(canceledStart, canceledStart + 40, MotionEvent.ACTION_CANCEL, x, y - direction * Ui.dp(activity, 80));
            require(deformation() == 0 && !((NotificationScrollView) scroll).ownsPull(), "cancellation clears deformation and pull ownership");
            scroll.fling(direction * Ui.dp(activity, 2600));
        });
        final boolean[] impact = {false}; long deadline = SystemClock.uptimeMillis() + 350;
        while (!impact[0] && SystemClock.uptimeMillis() < deadline) { main(() -> impact[0] = deformation() * direction > 0); if (!impact[0]) SystemClock.sleep(16); }
        main(() -> require(impact[0] && panelDrags == 0, "fling hitting either edge stretches without dismissing panel: direction=" + direction + ", deformation=" + deformation() + ", drags=" + panelDrags + ", scroll=" + scroll.getScrollY()));
        SystemClock.sleep(750); test.waitForIdleSync();
        main(() -> {
            require(deformation() == 0, "bottom fling spring releases all deformation");
            long at = SystemClock.uptimeMillis();
            send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 20, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 60)); send(at, at + 25, MotionEvent.ACTION_UP, x, y - direction * Ui.dp(activity, 70));
            float before = deformation(); send(at + 30, at + 30, MotionEvent.ACTION_DOWN, x, y);
            require(before * direction > 0 && deformation() == before, "new touch takes over rebound without a visual jump");
            scroll.layout(scroll.getLeft(), scroll.getTop(), scroll.getRight(), scroll.getBottom() + 1);
            require(deformation() == 0 && !((NotificationScrollView) scroll).ownsPull(), "size change cancels deformation and touch ownership");
            send(at + 30, at + 40, MotionEvent.ACTION_UP, x, y); scroll.requestLayout();
        }); idle();
        main(() -> {
            long at = SystemClock.uptimeMillis();
            send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 20, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 70)); send(at, at + 30, MotionEvent.ACTION_UP, x, y - direction * Ui.dp(activity, 80));
            require(deformation() * direction > 0, "unmount fixture has active rebound");
            ViewGroup.LayoutParams params = scroll.getLayoutParams(); int index = surface.indexOfChild(scroll); surface.removeView(scroll);
            require(deformation() == 0 && !((NotificationScrollView) scroll).ownsPull(), "unmount releases rebound immediately");
            surface.addView(scroll, index, params); center.update(true, demo());
        }); idle();
    }
    private void checkBoundaryHandoff(int edge) {
        int direction = edge == DockGeometry.TOP ? -1 : 1;
        List<StatusBarNotification> content = new ArrayList<>(); for (int i = 0; i < 12; i++) content.add(basic(1200 + i, "handoff-" + i, now - i, "任意位置收起 " + i));
        for (int region = 0; region < 4; region++) {
            final int target = region;
            main(() -> { panelDrags = 0; panelProgress = 1; panelFinished = panelClosed = false; center.update(true, List.of()); if (target != 3) center.update(true, content); }); idle();
            main(() -> scroll.fullScroll(direction < 0 ? View.FOCUS_DOWN : View.FOCUS_UP)); idle();
            float x = target == 2 ? Ui.dp(activity, 20) : surface.getWidth() * .6f;
            float y = target == 2 ? Ui.dp(activity, 24) : target == 1 ? surface.getHeight() - Ui.dp(activity, 10) : top(scroll) - top(surface) + scroll.getHeight() * .5f;
            long at = SystemClock.uptimeMillis(); int oldOpens = opens, oldClears = clears;
            float[] heldStretch = {0};
            main(() -> {
                send(at, at, MotionEvent.ACTION_DOWN, x, y);
                send(at, at + 80, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 64));
                require(panelDrags == 0 && panelProgress == 1, "all notification regions wait for dismissal threshold");
                require(!scroll.canScrollVertically(-direction), "all handoff regions require the completed list boundary");
                heldStretch[0] = deformation();
                send(at, at + 160, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 120));
                require(panelDrags == 1 && panelProgress < 1 && panelProgress > .8f, "continued boundary pull transfers immediately after elastic threshold: edge=" + edge + ", region=" + target + ", drags=" + panelDrags + ", progress=" + panelProgress + ", scroll=" + scroll.getScrollY());
                require(deformation() == heldStretch[0], "panel handoff preserves pre-threshold card stretch");
            }); SystemClock.sleep(180);
            main(() -> {
                require(deformation() == heldStretch[0], "holding the finger past threshold does not start card rebound");
                float transferred = panelProgress;
                send(at, at + 240, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 150));
                require(panelDrags == 1 && panelProgress < transferred, "after threshold the entire notification panel follows the finger");
                send(at, at + 260, MotionEvent.ACTION_UP, x, y + direction * Ui.dp(activity, 150));
                require(panelDrags == 1 && panelFinished && panelClosed && opens == oldOpens && clears == oldClears, "release completes boundary dismissal without activating notification");
            }); SystemClock.sleep(750); test.waitForIdleSync();
            main(() -> require(deformation() == 0, "handoff releases card deformation"));
        }
        main(() -> { panelDrags = 0; panelFinished = panelClosed = false; center.update(true, List.of()); center.update(true, content); }); idle();
        main(() -> scroll.fullScroll(direction < 0 ? View.FOCUS_UP : View.FOCUS_DOWN)); idle();
        float x = surface.getWidth() * .6f, y = top(scroll) - top(surface) + scroll.getHeight() * .5f;
        main(() -> {
            long at = SystemClock.uptimeMillis();
            send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 80, MotionEvent.ACTION_MOVE, x, y - direction * Ui.dp(activity, 140));
            require(panelDrags == 0 && ((NotificationScrollView) scroll).ownsPull(), "large pull opposite to closing direction stays elastic");
            send(at, at + 100, MotionEvent.ACTION_CANCEL, x, y - direction * Ui.dp(activity, 140));
            require(deformation() == 0 && !panelFinished, "canceling opposite pull does not dismiss panel");
            scroll.fullScroll(direction < 0 ? View.FOCUS_DOWN : View.FOCUS_UP);
        }); idle();
        main(() -> {
            long at = SystemClock.uptimeMillis();
            send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 100, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 120));
            require(panelDrags == 1 && !panelFinished, "continued boundary pull immediately starts a panel drag");
            secondPointer(at, at + 110, x, y + direction * Ui.dp(activity, 120));
            require(panelFinished && !panelClosed, "second finger cancels transferred panel drag without closing");
            send(at, at + 130, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 160));
            require(panelDrags == 1 && !panelClosed, "canceled multi-pointer tail cannot restart dismissal");
            send(at, at + 140, MotionEvent.ACTION_UP, x, y + direction * Ui.dp(activity, 160));
            require(panelFinished && !panelClosed && panelDrags == 1, "release after multi-pointer cancellation never dismisses panel");
            panelDrags = 0; panelFinished = panelClosed = false;
            scroll.fullScroll(direction < 0 ? View.FOCUS_DOWN : View.FOCUS_UP);
        }); idle();
        main(() -> {
            long at = SystemClock.uptimeMillis();
            send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 80, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 60)); send(at, at + 100, MotionEvent.ACTION_UP, x, y + direction * Ui.dp(activity, 60));
            at += 150;
            send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 80, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 40));
            require(panelDrags == 0 && !panelFinished, "previous rebound displacement is not counted toward a new boundary threshold");
            send(at, at + 100, MotionEvent.ACTION_CANCEL, x, y + direction * Ui.dp(activity, 40));
            scroll.scrollBy(0, direction * Ui.dp(activity, 60));
            at += 300;
            send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 60, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 75)); send(at, at + 100, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 150));
            require(panelDrags == 0 && !scroll.canScrollVertically(-direction), "large reading move reaches boundary without using its distance as threshold");
            send(at, at + 140, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 185));
            send(at, at + 180, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 225));
            require(panelDrags == 0 && ((NotificationScrollView) scroll).ownsPull(), "only post-boundary elastic movement accumulates toward dismissal");
            send(at, at + 220, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 265));
            require(panelDrags == 1 && panelProgress < 1, "same reading gesture transfers only after enough extra movement beyond boundary");
            send(at, at + 240, MotionEvent.ACTION_CANCEL, x, y + direction * Ui.dp(activity, 265));
            require(panelFinished && !panelClosed, "canceled boundary transfer returns panel rather than closing");
            panelDrags = 0; panelFinished = panelClosed = false; center.update(true, demo());
        }); idle();
    }
    private void checkLongListScrolling(int edge) {
        List<StatusBarNotification> content = new ArrayList<>(); for (int i = 0; i < 24; i++) content.add(basic(1500 + i, "reading-" + i, now - i, "尚未读完的通知 " + i));
        main(() -> { panelDrags = 0; panelProgress = 1; panelFinished = panelClosed = false; center.update(true, List.of()); center.update(true, content); }); idle();
        int direction = edge == DockGeometry.TOP ? -1 : 1;
        main(() -> scroll.scrollTo(0, Ui.dp(activity, 600))); idle();
        float x = surface.getWidth() * .6f, y = top(scroll) - top(surface) + scroll.getHeight() * .5f; long at = SystemClock.uptimeMillis();
        main(() -> {
            send(at, at, MotionEvent.ACTION_DOWN, x, y); send(at, at + 100, MotionEvent.ACTION_MOVE, x, y + direction * Ui.dp(activity, 150));
            require(scroll.canScrollVertically(-direction), "long notification list still has unread scroll range after a large drag");
            require(panelDrags == 0 && panelProgress == 1 && !panelFinished, "normal list scrolling never accumulates dismissal distance");
            send(at, at + 180, MotionEvent.ACTION_UP, x, y + direction * Ui.dp(activity, 150));
            require(panelDrags == 0 && !panelFinished, "releasing a large reading scroll cannot close a list that has not reached its boundary");
        }); idle();
    }
    private void secondPointer(long start, long at, float x, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2]; MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) { properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER; coords[i] = new MotionEvent.PointerCoords(); coords[i].x = x + i * Ui.dp(activity, 8); coords[i].y = y; coords[i].pressure = 1; coords[i].size = 1; }
        MotionEvent event = MotionEvent.obtain(start, at, MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2, properties, coords, 0, 0, 1, 1, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0); surface.dispatchTouchEvent(event); event.recycle();
    }
    private int top(View view) { int[] position = new int[2]; view.getLocationInWindow(position); return position[1]; }
    private void gesture(float x, float y, float endX, float endY, boolean cancel) {
        long start = SystemClock.uptimeMillis();
        main(() -> {
            send(start, start, MotionEvent.ACTION_DOWN, x, y);
            for (int i = 1; i <= 8; i++) send(start, start + i * 20, MotionEvent.ACTION_MOVE, x + (endX - x) * i / 8, y + (endY - y) * i / 8);
            send(start, start + 180, cancel ? MotionEvent.ACTION_CANCEL : MotionEvent.ACTION_UP, endX, endY);
        }); idle();
    }
    private void send(long start, long at, int action, float x, float y) { MotionEvent event = MotionEvent.obtain(start, at, action, x, y, 0); surface.dispatchTouchEvent(event); event.recycle(); }
    private TextView findText(View root, String prefix) {
        if (root instanceof TextView text && text.getText().toString().startsWith(prefix)) return text;
        if (root instanceof ViewGroup group) for (int i = 0; i < group.getChildCount(); i++) { TextView found = findText(group.getChildAt(i), prefix); if (found != null) return found; }
        return null;
    }
    private void screenshot(String name) throws Exception {
        Bitmap[] frame = {null}; main(() -> { View root = activity.findViewById(android.R.id.content); frame[0] = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888); root.draw(new Canvas(frame[0])); });
        File directory = new File(context.getFilesDir(), "ui-smoke"); directory.mkdirs(); try (FileOutputStream output = new FileOutputStream(new File(directory, name + ".png"))) { frame[0].compress(Bitmap.CompressFormat.PNG, 100, output); } finally { frame[0].recycle(); }
    }
}
