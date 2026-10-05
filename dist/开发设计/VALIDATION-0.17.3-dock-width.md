# 快捷栏整组收窄 · 2026-10-01

验证包：[flipcover-controls-0.17.3-dock-width-debug.apk](flipcover-controls-0.17.3-dock-width-debug.apk)，版本 0.17.3（53）。校验见同名 `.apk.sha256` 文件。

自动定位的快捷栏沿相机旁可用区域收窄到原长度的 87.5%，保留远离相机的端点、高度和图标大小；五个按钮、固定九宫格及分页提示共用新的宽度。四种旋转同步收窄，触摸窗口与按钮区域一起更新。手动定位仍按既有长度设置，独立双白条保持既有计算。

Debug 与 AndroidTest 构建成功，JVM 单元测试全部通过；新增几何检查覆盖四种旋转、三种密度、相机侧留白、固定端点及五按钮分区。Lint 为 0 错误、176 警告，修改文件的 `git diff --check` 通过。可丢弃 Android 模拟器上的 `dock-input` 通过 677 项断言，覆盖点击、翻页、长按、两种左右手布局、四边方向及取消。

日志位于 `Cache/tests/dock-width/`，构建报告位于 `Cache/build-output.nosync/app/`。本轮未安装到物理手机，尚未验证三星原生宿主下的实际视觉宽度及手势。项目结构及现有职责划分不变。
