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
    private StatusBarNotification settingsTarget;
    private List<StatusBarNotification> clearRequest = List.of();
    private final long now = System.currentTimeMillis();
    NotificationCenterChecks(Instrumentation test) { this.test = test; context = test.getTargetContext(); }
    String run() throws Exception {
        activity = test.startActivitySync(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        try {
            main(this::checkModel); mount(DockGeometry.BOTTOM); checkUi(); checkSwipes();
            for (int edge : new int[]{DockGeometry.LEFT, DockGeometry.RIGHT, DockGeometry.TOP}) { mount(edge); checkSwipeAxis(); }
            productPreview(); idle(); checkHeader(); screenshot("notifications-grouped");
            main(() -> firstRow().surface.performClick()); idle(); screenshot("notifications-group-expanded");
            main(() -> firstRow().surface.performClick()); idle(); main(() -> firstRow().showActions(true)); idle(); screenshot("notifications-actions");
            return "PASS: " + assertions + " notification assertions; native emulator components, no Samsung routing or live user notifications";
        } finally { main(() -> activity.finish()); }
    }
    private void main(Runnable action) { test.runOnMainSync(action); }
    private void idle() { test.waitForIdleSync(); SystemClock.sleep(220); }
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
            panelDrags = 0;
            // PanelSurface accepts a dock edge; its dismiss direction is the opposite edge.
            surface = new PanelSurface(activity, edge, 700, false, new PanelHeaderView.Listener() { public void begin() { panelDrags++; } public void progress(float value) { } public void finish(boolean close) { } });
            surface.setTag("panel-surface"); surface.setBackgroundColor(Ui.BACKGROUND); surface.setPadding(Ui.dp(activity, 9), Ui.dp(activity, 12), Ui.dp(activity, 9), Ui.dp(activity, 28));
            center = new NotificationCenterView(activity, new NotificationCenterView.Actions() {
                public void open(StatusBarNotification item) { opens++; }
                public void settings(StatusBarNotification item) { settingsTarget = item; }
                public void permission() { }
                public void clear(List<StatusBarNotification> items) { clears++; clearRequest = new ArrayList<>(items); }
            });
            LinearLayout header = Ui.row(activity); header.addView(Ui.heading(activity, "通知中心", 17), new LinearLayout.LayoutParams(0, Ui.dp(activity, 40), 1)); header.addView(center.newNotice); header.addView(center.clearAll); surface.addView(header);
            scroll = new ScrollView(activity); scroll.addView(center); surface.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
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
        main(() -> center.update(true, reading)); idle();
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
