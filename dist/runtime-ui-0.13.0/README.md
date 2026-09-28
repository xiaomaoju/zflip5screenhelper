# 0.13.0 运行时界面效果图

以下为 Android 16 模拟器上的原生组件截图，按项目规则等比放入设备屏幕开孔，再在最上层叠加一次 `../device-frames/zflip5-cover-overlay@2x.png`。最终画布为 720×748，未拉伸或镜像外框。

- [控制中心 · 340dpi](control-340-framed.png)：默认4列、原有按钮顺序、双滑条与媒体卡。
- [控制中心 · 440dpi](control-440-framed.png)：同一紧凑布局在较大密度下的原生表现，原有省略号保留。
- [应用中心 · 440dpi](hub-440-framed.png)：搜索结果、侧栏与固定 Dock；图中使用模拟器自带应用。
- [多任务 · 440dpi](tasks-440-framed.png)：三个完整任务卡片和下方留白；任务来自本地测试数据，未提供系统快照时显示应用图标。

媒体信息来自测试创建的本地 MediaSession，未知开关保留问号，未获得外屏亮度时保留不可用状态。这些图展示视觉和布局，不证明三星系统状态、窗口路由、实际模糊或触觉效果。静态图也不替代动画手感验收。

原始截图、几何对照和一次性合成脚本保留在本地 `Cache/tests/runtime-polish/`，不作为最终效果图。完整交付与真机待验边界见 [验证记录](../VALIDATION-0.13.0-runtime.md)。

![控制中心 340dpi](control-340-framed.png)

![控制中心 440dpi](control-440-framed.png)

![应用中心](hub-440-framed.png)

![多任务](tasks-440-framed.png)
