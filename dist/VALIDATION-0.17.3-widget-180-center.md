# 原生卡片居中与 QQ 音乐尺寸修复

2026-10-01 · 修正上一轮 `widget-180` 验证版。

安装包：`flipcover-controls-0.17.3-widget-180-center-debug.apk`；已安装到连接的 Flip5。

## 修改

上一轮把初始 XML 的自然尺寸当作固定下限。QQ 音乐初始封面为 500dp，但实际更新会动态调整尺寸，因此被错误整体缩小。这是卡片内容变换，并非修改系统 DPI。

`WidgetContentLayout.minimum` 现在只采用明确的最小尺寸；`NativeWidgetBridge.RelayView.minimumSize` 加上固定根宽高。不会把初始封面的固有尺寸、内部固定图片高度当作整个组件的下限。深蓝的 322dp 最小高度仍可触发等比适配，QQ 音乐恢复原生文字和控件尺寸。XML检查缓存只按布局和显示配置保存，不执行更新动作或创建集合订阅。

格位内容根改用居中的原生 LinearLayout，较短的 wrap-content 正文在可用空间内上下、左右居中。原生卡片顶部原有额外 4dp 间隔去掉，内容进一步上移；仍按状态栏及白条绘制边界预留。面板触摸避让、启动器边界、格位及绑定身份保持原规则。

## 验证

- 调试 APK、设备测试 APK、单元测试、Lint 构建成功。
- 146 项单元测试通过；Lint 0 错误、172 条现有警告。
- `native-widgets` 144 项断言通过：322dp 组件底部及变换后的 PendingIntent 点击、500dp 初始封面被更新动作改为 120dp 时保持原尺寸、短正文居中、正常组件恢复等。
- `launcher-widget` 813 项断言通过；本轮修改的已有文件 `git diff --check` 通过。
- 物理 SM-F731U1 的副屏 1、180° 实测：QQ 音乐文字与播放按钮恢复正常尺寸，深蓝四个底部按钮及文字完整可见。`wm density -d 1` 仍为物理 340dpi；组合卡片的实例与布局保持原值。未点击车辆控制按钮，实际车控业务不属于本轮验证。

## 实测效果图

- `widget-180-center/qq-framed.png`
- `widget-180-center/car-framed.png`
- `widget-180-center/result-framed.png`：并排展示上述两个截图。

每个屏幕均在 UI 上方叠加唯一标准设备贴图一次，并同步旋转 180°；组合图仅等比缩小。原始素材位于 `Cache/debug/widget-180-center/`。

APK SHA-256：`dd483afe8122e02c08e4b469f834808818a5dd1f490476bbb095e6a4a2599007`。
