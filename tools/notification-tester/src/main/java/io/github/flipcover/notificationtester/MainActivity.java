package io.github.flipcover.notificationtester;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Independent or grouped test notifications; owns only its own ten notification IDs. */
public final class MainActivity extends Activity {
    private static final String CHANNEL = "independent_samples";
    private static final String[] TITLES = {"聊天消息", "快递到站", "日程提醒", "邮件收件箱", "天气预报", "付款结果", "下载完成", "新闻速报", "出行提醒", "长文本测试"};
    private static final String[] TEXTS = {
        "小明：今晚七点一起吃饭吗？", "你的测试包裹已到取件柜，取件码 123456。",
        "测试会议将在 15 分钟后开始，请准备好笔记。", "测试邮件：本周计划已发送，请查看附件说明。",
        "下午有阵雨，气温 26～30°C，出门记得带伞。", "模拟付款成功：¥18.80。此消息仅为通知测试。",
        "测试文件 report.pdf 已下载，大小 8.6 MB。", "测试资讯：这条消息用于观察短内容卡片的排列。",
        "测试行程：距离发车还有 20 分钟，请前往检票口。",
        "这是第十条测试通知，用于测试长内容、多行文字和展开后的高度。\n第二行：中文、English、数字 123456。\n第三行：向下滚动再向上滚动，检查卡片位置。\n第四行：尝试滑动清除这一条，确认其他九条仍然保留。"
    };
    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView status;
    private Button send, sendGrouped;
    private boolean sending;
    private boolean independent = true;
    private int next;
    private final Runnable postNext = new Runnable() {
        @Override public void run() {
            try {
                postSample(next++);
                if (next < TITLES.length) {
                    status.setText("正在发送：" + next + " / 10");
                    handler.postDelayed(this, 650);
                } else {
                    stopSending();
                    status.setText(independent ? "已提交10条独立通知，请打开外屏通知中心查看。" : "已提交10条非独立通知，请在外屏通知中心展开合并组查看。");
                }
            } catch (RuntimeException failure) {
                stopSending(); status.setText("发送失败：" + failure.getClass().getSimpleName());
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        independent = state != null ? state.getBoolean("independent", true) : getIntent().getBooleanExtra("independent", true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        int inset = dp(16); content.setPadding(inset, inset, inset, inset); scroll.addView(content);
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(inset + bars.left, inset + bars.top, inset + bars.right, inset + bars.bottom); return insets;
        });
        TextView title = new TextView(this); title.setText("通知测试"); title.setTextSize(24); content.addView(title);
        TextView description = new TextView(this); description.setText("独立：10张卡片。非独立：10条合并成一组。切换模式会先清除上一组测试通知。均为静音测试。");
        description.setTextSize(15); description.setPadding(0, dp(12), 0, dp(12)); content.addView(description);
        send = new Button(this); send.setText("发送10条独立通知"); send.setMinHeight(dp(48)); content.addView(send);
        send.setOnClickListener(view -> sendSamples(true));
        sendGrouped = new Button(this); sendGrouped.setText("发送10条非独立通知"); sendGrouped.setMinHeight(dp(48)); content.addView(sendGrouped);
        sendGrouped.setOnClickListener(view -> sendSamples(false));
        Button clear = new Button(this); clear.setText("清除本 App 测试通知"); clear.setMinHeight(dp(48)); content.addView(clear);
        clear.setOnClickListener(view -> { stopSending(); getSystemService(NotificationManager.class).cancelAll(); status.setText("本 App 测试通知已清除。"); });
        status = new TextView(this); status.setTextSize(14); status.setMinHeight(dp(72)); status.setPadding(0, dp(12), 0, 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); status.setText("点击发送；首次使用需要允许通知权限。"); content.addView(status);
        setContentView(scroll); content.requestApplyInsets();
        if (state == null && getIntent().getBooleanExtra("send", false)) sendSamples(independent);
    }

    private void sendSamples(boolean independentMode) {
        if (sending) return;
        independent = independentMode;
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            status.setText("请在系统弹窗中允许通知，授权后会发送十条。");
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1); return;
        }
        if (!getSystemService(NotificationManager.class).areNotificationsEnabled()) {
            status.setText("系统已关闭本 App 通知，请在应用通知设置中开启。");
            startActivity(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName())); return;
        }
        NotificationChannel channel = new NotificationChannel(CHANNEL, "独立测试通知", NotificationManager.IMPORTANCE_LOW);
        channel.setSound(null, null); channel.enableVibration(false);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        getSystemService(NotificationManager.class).cancelAll();
        sending = true; next = 0; send.setEnabled(false); sendGrouped.setEnabled(false); status.setText("正在发送：0 / 10"); handler.post(postNext);
    }

    private void postSample(int index) {
        String shortcutId = "sample_" + index;
        Intent open = new Intent(this, MainActivity.class).setAction("sample_" + index);
        PendingIntent click = PendingIntent.getActivity(this, index, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(TITLES[index]).setContentText(TEXTS[index]).setContentIntent(click).setAutoCancel(true).setOnlyAlertOnce(true);
        if (independent) {
            Person sender = new Person.Builder().setName(TITLES[index]).setKey(shortcutId).setIcon(Icon.createWithResource(this, R.drawable.ic_notification)).build();
            getSystemService(ShortcutManager.class).pushDynamicShortcut(new ShortcutInfo.Builder(this, shortcutId)
                .setShortLabel(TITLES[index]).setIntent(open).setLongLived(true).setPerson(sender)
                .setIcon(Icon.createWithResource(this, R.drawable.ic_notification)).build());
            notification.setShortcutId(shortcutId).setGroup(shortcutId)
                .setStyle(new Notification.MessagingStyle(new Person.Builder().setName("我").build()).addMessage(TEXTS[index], System.currentTimeMillis(), sender));
        } else {
            notification.setGroup("grouped_samples").setStyle(new Notification.BigTextStyle().bigText(TEXTS[index]));
        }
        getSystemService(NotificationManager.class).notify(index + 1, notification.build());
    }

    @Override public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (request == 1 && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) sendSamples(independent);
        else status.setText("未获得通知权限，未发送。可以再次点击发送或到系统设置中开启。");
    }
    @Override public void onSaveInstanceState(Bundle state) { state.putBoolean("independent", independent); super.onSaveInstanceState(state); }
    private void stopSending() { handler.removeCallbacks(postNext); sending = false; send.setEnabled(true); sendGrouped.setEnabled(true); }
    @Override public void onDestroy() { stopSending(); super.onDestroy(); }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
