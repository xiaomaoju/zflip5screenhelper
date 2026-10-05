# 侧栏停稳后延迟分裂与0.7倍速（2026-10-01）

侧栏和应用中心完全滑入并停稳后，先保持包住设置按钮的整体玻璃轮廓1秒，再分裂出圆形设置按钮。分裂、按钮缩放及主体回弹沿用相同进度曲线，完整动作从560ms改为800ms，相当于0.7倍速；按钮缩放在动作开始约360ms后完成。

`AppHubView` 在卡片与内部正文动画结束后允许分裂，`LauncherSidebarView` 使用同一Animator的1000ms起始延迟。重复就绪回调不重新计时；拖动、收起、尺寸变化或卸载取消等待和动画，回弹停稳后再等待。等待阶段保持融合轮廓并隐藏齿轮，不额外创建绘制循环。静止几何、触摸区、通知动作时序及原生卡片保持原有行为。

## 构建与检查

- assembleDebug、assembleDebugAndroidTest、148项JVM单元测试通过。
- Lint：0错误、175条警告；git diff --check通过。
- interface-card：1156断言通过，覆盖滑入时不排队、真实等待700ms仍融合、取消等待后无迟到分裂、等待后开始800ms动作、完成释放及既有玻璃像素、手势、卡片切换检查。
- hub-motion：43断言通过，覆盖取消、回弹、快捷入口反向及两段退出。

本次变更前后只移除了`preparePanelPush`中重复的停止调用，停止由它调用的`prepareDismissal`统一负责；最终构建与hub-motion检查针对最终产物。日志位于`Cache/tests/sidebar-delayed-split/`。

## 交付

[修正版 APK](flipcover-controls-0.17.3-sidebar-delayed-split-debug.apk)，SHA-256：`b59274cfe52f180ad1d2038ba9c36bc68ba63e330a2ad0eddc5466bb67b08b03`。

已通过ADB覆盖安装到连接的三星Galaxy Z Flip5，返回Success。本地验证证明时序与生命周期，物理三星最终动画观感仍需在手机上观察。
