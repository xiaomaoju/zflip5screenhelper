# 侧栏分裂停留100毫秒、0.85倍速（2026-10-01）

停留改为100ms，动作时长改为659ms（560ms ÷ 0.85，取最近整数毫秒）。沿用900ms固定入场估计，因此从开启算起1000ms启动分裂。完整齿轮向下拉出、末段液颈断开和最终按钮位置沿用现有逻辑。

assembleDebug、assembleDebugAndroidTest及159项单元测试通过；Lint：0错误、176条警告；git diff --check通过。独立模拟器interface-card的1162项断言通过，包含等待取消、100ms停留后的启动、659ms动作完成与释放，以及实际齿轮像素、玻璃圆角和24条卡片切换。证据位于`Cache/tests/sidebar-wait-100-speed-085/`。

[APK](flipcover-controls-0.17.3-sidebar-wait-100-speed-085-debug.apk)，SHA-256：`3c95ae7120fe01526774e1385091aa26f7b31aa656b3a290052c75cf890b59f3`。已通过ADB覆盖安装到连接的三星Galaxy Z Flip5，返回Success。

使用固定入场估计，未监听实际入场完成。以上为本地检查和手机安装结果，物理三星最终观感及帧率仍待实际观察。
