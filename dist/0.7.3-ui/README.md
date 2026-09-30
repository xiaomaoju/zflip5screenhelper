# 通知中心标题栏 · 0.7.3

- `notifications-header-framed.png`：标题栏从左到右为标题、系统通知设置、垃圾桶＋可清除数量、关闭。底部清理区域已移除。
- `notifications-empty-framed.png`：没有可清除通知时保留灰色垃圾桶＋0，设置入口仍可用。
- 同名 SVG 为自包含合成源，可直接在普通浏览器打开。

图片来自 720×748、340dpi Android 36 模拟器，使用真实 `CoverService.buildPanelContent()` 与本地通知样例，不代表三星真机验证。原图在 `Cache/tests/notification-header/screens-340/`。

原图等比置于标准屏幕开孔，按圆角和异形区域裁剪；最上层仅叠加一次 `dist/device-frames/zflip5-cover-overlay.svg`。外框、双摄和闪光灯完整保留，没有镜像或拉伸。PNG 为 836×992；此次外框更新不改变历史原生验证结论。
