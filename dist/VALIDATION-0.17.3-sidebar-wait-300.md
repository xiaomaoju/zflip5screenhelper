# 侧栏分裂停留300毫秒（2026-10-01）

`LauncherForce.SPLIT_WAIT_MS`由1000改为300。沿用固定900ms入场估计，因此从开启算起1200ms启动分裂；向下拉出轨迹、完整齿轮、最终位置及800ms动作时长保持原逻辑。

assembleDebug、assembleDebugAndroidTest及158项单元测试通过；Lint为0错误、176条警告；git diff --check通过。独立模拟器interface-card的1162项断言通过，包含等待取消、较短等待后开始800ms动作、各阶段真实齿轮像素、液态玻璃边缘及卡片切换。证据位于`Cache/tests/sidebar-wait-300/`。

[APK](flipcover-controls-0.17.3-sidebar-wait-300-debug.apk)，SHA-256：`bdb7a5860d51322361f8786c58e98f7d931c26a86ac81cc0a267cce5514bd8bd`。已通过ADB覆盖安装到连接的三星Galaxy Z Flip5，返回Success。

本地时序与画面验证不等同于物理三星最终观感验收。仍使用固定入场估计，不监听实际入场完成。
