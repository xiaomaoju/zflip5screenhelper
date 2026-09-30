# 多任务厚透镜光感与强折射

2026-09-30 · 0.17.3 / versionCode 53 · 原生 Android

[安装包](FlipCover-0.17.3-recents-thick-lens-debug.apk)。SHA-256：`1327e8e4ab00a58de60534a8a5ee29f625d391fc85ed0390dec8ba59ee4742a5`。

## 效果

按照用户补充的厚玻璃透镜参考，增强实际边缘采样位移及曲面反光。边缘带从约6dp扩大至约16dp，受实际圆角半径约束；最大采样位移约为带宽的55%，常规卡片约9dp。上下反射弧比侧边更宽、更亮，外沿具有适量青蓝色散与窄接触阴影，中心保持清晰且不叠白色光晕。原有物理圆角、尺寸、中心1.15倍凸透镜、四视图复用与页面快照缓存均保留。

`TaskPreviewView.EDGE_LIGHTING` 由任务快照和图标占位背景共用，其他控制中心角色使用原有绘制路径。折射映射保持单调，避免把采样倒像或接缝当作厚度。没有新增截图、轮询或静止帧刷新。

## 验证

- 构建及105项 JVM 测试通过；Lint 0错误、135警告。
- `recent-tasks-glass` 四方向各121项断言通过：新增实际边缘位移、连续采样方向和亮弧强度检查；中心及远离边缘的纯色保持原色。保留裁切、完整矩形按压、缓存复用、低内存、迟到结果及入场检查。
- `panel-glass-capture` 22项断言通过，验证共享玻璃程序的取样映射与资源生命周期。
- 已通过ADB覆盖安装至 Samsung SM-F731U1，保留数据。模拟器像素验证不能代替三星屏幕上的最终光感验收，未操作真实任务删除。

原始证据位于 `Cache/tests/recent-tasks-thick-lens/`，隔离构建输出位于 `Cache/build-output.nosync/recent-tasks-thick-lens/`。

## 原生效果图

生产组件绘制，任务快照为模拟素材；设备外框按项目规则叠加一次。

![加强的折射与上下亮弧](recent-tasks-thick-lens/tasks-glass-framed.png)

[纯色厚透镜验证图](recent-tasks-thick-lens/clear-lens-rim-framed.png)。
