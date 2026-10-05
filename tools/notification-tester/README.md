# 通知测试

独立 Android 测试 App，不修改外屏快捷栏。提供两种发送模式，使用相同十种内容：

- **发送10条独立通知**：每条使用不同会话 shortcut ID 和分组 ID，在本项目通知中心显示十张独立卡片。
- **发送10条非独立通知**：不设置会话标识，十条共用同一分组 ID，在本项目通知中心合并成一组，展开查看十条。

所有通知真实来源均为“通知测试”，不模拟其他软件身份；三星系统通知栏的最终分组仍由系统控制。

安装后打开“通知测试”，点击其中一个发送按钮，由用户允许通知权限。每次发送先清除本 App 上一组通知，再每 650ms 提交一条；发送期间两个按钮禁用。关闭测试 App 会取消尚未发送的条目。保留清除该 App 所有测试通知的按钮。没有网络、后台服务或通知读取权限。

在项目根目录使用 JDK 17 和已配置的 Android SDK 构建：

```sh
./gradlew -p tools/notification-tester --project-cache-dir "$PWD/Cache/notification-tester-gradle" assembleDebug lintDebug
```

Windows 使用 `gradlew.bat`。产物位于 `Cache/build-output.nosync/notification-tester/outputs/apk/debug/NotificationTester-debug.apk`；交付安装包为 `dist/notification-tester.apk`。

ADB 可启动发送入口（使用已选择的副屏显示 ID，不回退主屏）：

```sh
adb shell am start --display DISPLAY_ID -n io.github.flipcover.notificationtester/.MainActivity --ez send true
# 非独立模式
adb shell am start --display DISPLAY_ID -n io.github.flipcover.notificationtester/.MainActivity --ez send true --ez independent false
```

首次权限必须由用户在系统界面允许。旧 ADB Shell 测试通知可在外屏通知中心单独清除 Shell 组。
