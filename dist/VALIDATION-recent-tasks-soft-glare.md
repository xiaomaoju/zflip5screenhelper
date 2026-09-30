# 多任务上下白边柔化

2026-09-30 · 0.17.3 / versionCode 53

任务卡片上下反射弧的白光强度降低40%，向侧边平滑过渡。沿用原有弧宽、16dp折射带、55%边缘位移与侧面色散；快照和图标占位共用调整后的绘制逻辑。

- 构建、105项JVM测试通过；Lint 0错误、135警告。
- 独立模拟器0°方向 `recent-tasks-glass` 121项断言通过，覆盖柔化亮弧像素、边缘位移、无倒像采样、缓存与释放；本次单参数调整未重跑其余方向。
- 检查原生渲染截图，按规则叠加一次设备外框。
- ADB覆盖安装Samsung SM-F731U1成功，保留数据；设备更新时间14:35:33。三星实体屏幕上的主观光感尚待验收。

[安装包](FlipCover-0.17.3-recents-soft-glare-debug.apk) · SHA-256：`ae10eac06f3928b20d6f7da34757df9f74df98675acd82ea0681146893ffdc0c`。

![柔化上下亮弧](recent-tasks-soft-glare/tasks-glass-framed.png)

[纯色亮弧验证图](recent-tasks-soft-glare/clear-lens-rim-framed.png)。任务画面为模拟素材，使用生产绘制组件。原始证据：`Cache/tests/recent-tasks-soft-glare/`。
