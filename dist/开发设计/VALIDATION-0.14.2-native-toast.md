# 0.14.2 Android 原生 Toast

安装包：[FlipCover-0.14.2-native-toast-debug.apk](FlipCover-0.14.2-native-toast-debug.apk)，versionCode 32，覆盖升级保留配置。

## 原因和改动

真机日志确认 `NotificationService` 因本应用通知权限关闭而拦截后台 Toast，应用的 `POST_NOTIFICATION` app-op 为 `ignore`。0.14.1 的 `Toast.makeText` 调用已存在，单纯增加相同调用不能解决这项系统限制。

现在系统面板的切换结果独立调用 Android 标准文本 Toast（`LENGTH_LONG`），提示“已开启内外屏控制中心”或“已关闭内外屏控制中心”。执行中仍通过图标反馈，不再排队显示过程 Toast；下一次结果替换上一条，窗口移除时取消 Toast。上下文沿用选定外屏，不回退到主屏。

Manifest 声明 `POST_NOTIFICATIONS`。权限中心增加“安卓提示（Toast）”行，系统面板长按说明在权限关闭时提供“允许安卓提示（Toast）”入口，均由用户进入系统通知设置开启。应用不自动授权，不发送通知，也不改变通知使用权。

权限关闭时，切换结果和开启提示权限的说明会出现在现有面板反馈位置，避免系统拦截后没有可见结果；这不是替代系统 Toast 的自绘气泡。

## 验证

- Debug APK及AndroidTest APK构建成功，56项单元测试通过；Lint 0错误。
- 一次性模拟器：权限关闭场景7项断言、权限允许场景6项断言通过，覆盖原生长文本Toast、替换旧提示、取消生命周期、未授权恢复入口。截图实际观察到系统图标与“已开启内外屏控制中心”原生Toast。
- 物理手机：已确认拦截原因。安装后仍需用户在本应用的系统通知页开启“允许通知”，再确认外屏的实际Toast效果；没有通过ADB或Shizuku替用户授权。
- 原始日志、截图位于 `Cache/tests/system-control-toast/`，截图为检查素材，不作为最终设备效果图。

本版本沿用0.14.1的内外屏共用开关和独立Binder限制生命周期，没有修改系统开关的作用范围。
