# 快捷栏收窄后的九宫格触摸扩展 · 2026-10-01

验证包：[flipcover-controls-0.17.3-dock-touch-debug.apk](flipcover-controls-0.17.3-dock-touch-debug.apk)，版本 0.17.3（53），包名 `io.github.flipcover.controls.debug`。校验见同名 `.apk.sha256` 文件。

按用户补充说明：图标整体保持收窄，但九宫格与相机之间的原快捷栏空白区域仍须可点。`DockGeometry.resolve` 保留自动定位时的原触摸长度，`edgeTouch` 扩展到物理边缘时保留该长度，`slots` 使用收窄后的视觉长度布局五个按钮；`DockView.buttonAt` 将相机侧新增空白映射到固定按钮。保留原端点、高度、图标大小、分页、左右手布局和手动长度设置；九宫格隐藏期间，扩展区域同样不能激活它。不扩大到相机缺口或整个显示区域，项目结构及职责边界不变。

旧版本的专项回归首先失败于“compact artwork retains the original full tail touch window”，确认原触摸窗口随视觉长度一起收窄。修复后的 Debug、AndroidTest 构建及 JVM 单元测试通过；Lint 为 0 错误、176 警告，修改文件的 `git diff --check` 通过。

可丢弃 Android 模拟器 `dock-input` 通过 829 项断言，新增检查覆盖四边、两种左右手布局、每隔3px点击一次扩展区域、图标中心保持在收窄范围内，以及隐藏状态不响应扩展点击。日志位于 `Cache/tests/dock-hit-bounds/`，构建报告位于 `Cache/build-output.nosync/app/`。模拟器结果与物理三星外屏验证分别记录。

已通过 ADB 覆盖安装到 SM-F731U1，保留配置。物理外屏 Display 1 的正常快捷栏窗口恢复为 `[6,660][373,720]`，图标区仍止于 x=327。向空白扩展区注入 `(350,681)` 点击后，本应用服务诊断从 `hub=false` 变为 `hub=true`，确认该位置打开九宫格对应的应用中心；随后用下滑返回。此项是物理设备上的 ADB 输入验证，手指及投屏鼠标的完整红框范围仍需用户确认。
