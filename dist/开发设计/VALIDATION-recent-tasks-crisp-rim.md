# 多任务清晰边缘反光

2026-09-30 · 0.17.3 / versionCode 53

上一版只降低白光强度，宽而平坦的反射分布仍覆盖边缘画面，真机反馈呈磨砂感。本次替换为半高全宽约0.8–1dp的窄高斯反光，强度随曲面朝向和肩部位置变化；接触阴影同步收窄。保留约16dp折射带、55%边缘位移和原色散。快照及图标占位共用修改，不改变布局、手势、缓存或刷新频率。

- 构建、105项JVM测试通过；Lint 0错误、135警告。
- 独立模拟器0° `recent-tasks-glass` 124项断言通过：上下细反光可见，深入3dp处纯色像素保持原色；强位移、单调采样、缓存与释放检查通过。本次未重跑其余方向。
- 已查看生产组件渲染截图，设备外框按规则叠加一次。画面为模拟素材，不能代替三星实际屏幕观感验收。
- ADB覆盖安装至Samsung SM-F731U1成功，保留数据。未操作真实任务删除。

[安装包](FlipCover-0.17.3-recents-crisp-rim-debug.apk) · SHA-256：`d424055a92e3c6207baea66d6acf8c565f3447118e222162b60a5c99317c2583`。

![清晰反光与强折射](recent-tasks-crisp-rim/tasks-glass-framed.png)

[纯色反光验证图](recent-tasks-crisp-rim/clear-lens-rim-framed.png)。原始证据位于 `Cache/tests/recent-tasks-crisp-rim/`。
