# 侧栏设置向下拉出分裂（2026-10-01）

设置图标起初完整显示在侧栏胶囊底部，与侧栏一体；随后同一个图标和圆面沿连续轨迹向下移动，玻璃连接逐渐变细，在动作末段断开，停在原有独立圆钮位置。图标不再隐藏后淡入，也不再从微小尺寸长大。最终32dp圆面、20dp图标、4dp间距、布局格位和触摸区域保持原样。

`LauncherSidebarView` 同时驱动几何、图标Canvas位移与列表底部渐隐，让列表暂时为内部齿轮让出空间，分离后恢复。区域受力及材质取样沿用已有`LauncherForce`和`PanelGlassSession`；没有新增截图或位图。下滑收起时，圆面与图标仍一起按剩余高度缩小，玻璃底部圆角保持。

当前源码使用固定入场调度：900ms入场估计加1000ms停留，然后800ms动作，对应原560ms动作的0.7倍速；总等待从开启算起为1900ms，计时不监听实际入场完成。重复开启不重新计时；取消、尺寸变化、隐藏或卸载停止等待及动画。

## 验证

- assembleDebug、assembleDebugAndroidTest及158项JVM单元测试通过；Lint：0错误、176条警告；git diff --check通过。
- interface-card：1162断言通过。实际硬件截图分别验证融合、移动和分离时齿轮均可见；轨迹连续、最终位置不变、液颈与共享材质释放正常，并保留24条卡片切换和下滑玻璃圆角回归。
- launcher-force：20断言通过，真实侧栏图标受力位移13px，取消与卸载后停止回调。
- hub-motion：43断言通过，覆盖跟手、回弹、两段退出及释放。

共享模拟器的两次检查被其他instrumentation启动中断，未计为通过；随后在本任务独立模拟器上安装冻结的APK完成以上检查。证据位于`Cache/tests/sidebar-pullout/`。

## 效果与安装

以下均为实际模拟器窗口截图，蓝色固定背景用于观察材质；UI等比居中到校准开孔，最上层叠加一次标准设备贴图，保留外框、双摄与闪光灯，没有拉伸或镜像贴图。它们不是三星真机照片。

- [侧栏内部的完整设置图标](sidebar-pullout/initial-framed.png)
- [图标向下移动、仍与侧栏相连](sidebar-pullout/moving-framed.png)
- [连接末段](sidebar-pullout/neck-framed.png)
- [原有最终位置](sidebar-pullout/final-framed.png)

[APK](flipcover-controls-0.17.3-sidebar-pullout-debug.apk)，SHA-256：`605a3044055adb272ba0b86d5e5f0d6eee1ebcc1c8631456db5c81daa757addc`。已通过ADB覆盖安装到连接的三星Galaxy Z Flip5，返回Success；未调整权限或显示器选择。

本次只改浮窗绘制及其时序；原生RemoteViews保留最终静止几何，不运行自定义分裂动画。物理三星最终观感与帧率仍需在手机上观察。
