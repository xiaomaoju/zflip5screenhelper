# Dock 入场横向锚点修复 · 2026-10-01

验证包：[flipcover-controls-0.17.3-dock-anchor-debug.apk](flipcover-controls-0.17.3-dock-anchor-debug.apk)，版本0.17.3（53），包名 `io.github.flipcover.controls.debug`。校验见同名 `.apk.sha256` 文件。

## 原因与修复

`LauncherMotionLayout.visualX()` 原先按左右尚未展开的按钮宽度差平移整个 Dock。九宫格约297ms开始展开，清理按钮约350ms开始展开，错开的隐藏宽度使应用核心先横移再返回；无清理按钮时也会随九宫格展开发生整体横移。此路径是绘制坐标补偿，动画没有逐帧改变布局尺寸。

现在两端隐藏宽度仅用于玻璃边界和端部按钮自身滑出；Dock 横向位移只来自既有共享受力，入场缩放围绕最终 Dock 中心。保留660ms时钟、错开的按钮缩放与淡入、最终尺寸及触摸槽位、原生启动器静止布局。责任仍属于现有绘制容器，无新增动画模型或帧回调，项目协作结构不变。

## 本地验证

- 修复前运行新增回归检查：308ms首次失败，证据为 `Cache/tests/dock-entrance-anchor/baseline.log`。
- 修复后 `launcher-polish`：289项断言通过。对有／无清理按钮分别每11ms扫描660ms入场；检查布局坐标与共享横移，并用Android Canvas实际绘制应用像素，端部展开阶段横向中心偏差不超过1px；同时检查共享受力反馈及既有材质／弹窗路径。
- `launcher-force`：20项通过，含边界输入、稳定停帧、取消和卸载。
- `dock-pin-placement`：138项通过，含刷新、拖拽预览、取消及重新展开后的槽位。
- Debug及AndroidTest构建成功；158项JVM测试，失败0、错误0、跳过0；Lint为0错误、176警告；`git diff --check`通过。

Instrumentation仅在可丢弃Android 36模拟器（720×748、340dpi）运行。日志保存在 `Cache/tests/dock-entrance-anchor/`，构建报告位于 `Cache/build-output.nosync/app/`。

## 真机边界

本轮未将本地绘制检查视为三星物理外屏验收。需在真机分别有／无最近任务打开Dock，确认两端弹出期间应用核心不横向游移，并检查正常横向分页仍保留既有弹性反馈。

历史 `motion-continuity`、`standalone-dock` 旧场景的动画归属假设失败记录仍保留，本次未修改或重新标记为通过；当前布局和入场由上述专项覆盖。
