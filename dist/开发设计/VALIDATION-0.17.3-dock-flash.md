# Dock 打开时顶层控件闪黑修复

验证包：[flipcover-controls-0.17.3-dock-flash-debug.apk](flipcover-controls-0.17.3-dock-flash-debug.apk)，versionName 0.17.3，versionCode 53，沿用现有调试包名和签名。日期：2026-10-01。

SHA-256：`74f794478e07b7f75b31b29b02a0790024d01d58ab6cdead525b853ae934e4f3`。

## 原因与修复

`showHub` 打开临时 Dock、启动器或任务页时调用 `raiseChrome`，移除再添加快捷栏、状态栏和双白条窗口；共用快捷栏窗口的底边上滑完成时也会调用。顶层窗口表面因此短暂消失。

现在由 `CoverService` 在顶层控件之前预挂载两个启动器宿主。打开只更新宿主内容、可见性及窗口属性，两槽支持当前卡片与退场卡片的同时过渡；取消恢复原卡片，滑出释放旧卡片。关闭释放卡片内容、玻璃与订阅，空宿主保持不可见、不可触摸、不可聚焦；显示器生命周期结束时移除宿主。移除 `raiseChrome` 及两个调用点。控件静止几何、点击、横向翻页、白条显隐和系统手势规则保持原有路径。

## 验证结果

- Debug APK 与设备测试 APK 构建成功；140 项单元测试通过，Lint 0 错误、171 警告；`git diff --check` 通过。
- 独立模拟器 `emulator-5574`，720×748 / 340dpi：`interface-card` 1034 项断言通过，包含新增的上下入口反复开关 Dock、真实窗口调用计数、状态栏/快捷栏/白条零卸载、空宿主触摸与焦点属性、目录订阅释放和显示器卸载检查；既有 24 种定向卡片切换、取消和注入窗口更新失败后的编辑恢复通过。
- 同一独立模拟器：`panel-entry` 657 项、`launcher-swipe` 52 项断言通过。
- 首轮 `emulator-5580` 的后续检查被并发通知测试启动中断，日志保留但不计通过；改在空闲模拟器完成上述检查。
- 补充旧 `hub-window` 检查未通过：第一条断言仍要求 `hub.getParent() == host`，而修复前的 `attachHubContent` 已将 `AppHubView` 放在 `InterfaceCard` 内。该检查沿用旧直接宿主假设，未进入像素验收；此次不修改其余旧动画夹具，也不将它计为通过。

日志与构建记录：`Cache/tests/dock-chrome-flash/`。这些检查证明应用侧窗口与生命周期行为；本轮未覆盖安装到物理手机，也未进行三星 Z Flip5 外屏的逐帧录像、真实系统 Home 接管或肉眼闪黑验收。
