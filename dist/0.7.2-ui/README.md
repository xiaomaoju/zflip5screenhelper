# 通知中心 · 0.7.2

- `notifications-grouped-framed.png`：分组、数量角标、时间和底部清理按钮。
- `notifications-actions-framed.png`：左滑露出应用通知设置与整组清理。
- `notifications-group-expanded-framed.png`：展开分组后逐条查看和操作。
- 同名 `.svg` 是自包含的合成源，可在普通浏览器直接打开，不需要本地服务或外部资源。

图片来自 720×748、340dpi Android 36 模拟器，用本地通知样例驱动真实的 `CoverService.buildPanelContent()` / `NotificationCenterView`；不是三星真机截图。原图位于 `Cache/tests/notification-center/screens-340/`。

原图等比放入标准屏幕开孔，按圆角和异形边界裁剪；最上层仅叠加一次 `dist/device-frames/zflip5-cover-overlay.svg`。外框、双摄和闪光灯均保留，未镜像或拉伸轮廓。PNG 为 720×748。

可见内容、字体和交互另在 440dpi、440dpi/1.3 倍字体下检查。测试范围见 `../VALIDATION-0.7.2.md`。
