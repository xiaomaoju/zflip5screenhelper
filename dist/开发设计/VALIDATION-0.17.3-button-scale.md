# 完整按钮缩放与启动器布局修正（2026-10-01）

上一版只缩放玻璃轮廓，图标仍是完整尺寸，未实现用户视频中的整个圆钮从小到大。本版将圆面和图标绑定同一个20%到100%的插值。

- 侧栏设置按钮按同一比例绘制圆面和图标，断开后继续长大，约252ms完成缩放，560ms完成主体回弹；实际输入视图保持32dp。
- 通知左滑的设置、清除按钮直接缩放装饰性View，连同其图标一起放大，玻璃读取真实变换；父级操作槽保持原尺寸。后一按钮等待前一按钮断开，回滑反向缩回，合并清除保持完整尺寸。
- 搜索胶囊改为居中的短框，目标宽度为行宽62%，窄宽度为右侧独立排序按钮留出空间。外层应用区圆角改为16dp，与32dp高的搜索框一致。
- 侧栏在所选外屏安全边界与应用区之间水平居中，均分两侧留白。原生和浮窗共用布局计算；保留原有5×3网格、文件夹材质、胶囊遮罩和设置入口。

## 交付及安装

[APK](flipcover-controls-0.17.3-button-scale-debug.apk)，SHA-256：`c45301286eb34ef0cded62b47b93ed8fecf2cc8d51901b57b65dcdb265558c94`。

已通过ADB覆盖安装到连接的三星Galaxy Z Flip5（SM-F731U1），安装返回Success。未更改权限或目标显示器选择。

以下是实际模拟器渲染的中间帧和完成帧，最上层叠加一次标准设备贴图：

- [侧栏按钮缩放中](liquid-button-scale/sidebar-half-framed.png)
- [侧栏按钮完整尺寸及新布局](liquid-button-scale/sidebar-full-framed.png)
- [通知按钮缩放中](liquid-button-scale/notification-scale-half-framed.png)
- [通知按钮完整尺寸](liquid-button-scale/notification-full-framed.png)

## 本地验证

assembleDebug、assembleDebugAndroidTest、148项JVM单元测试通过；Lint为0错误、175条警告。

| 检查 | 通过断言 |
| --- | ---: |
| liquid-tension-motion：真实View缩放、顺序、惯性、停稳及释放 | 55 |
| interface-card：实际按钮绘制、紧凑搜索、居中侧栏、遮罩及文件夹玻璃 | 1130 |
| launcher-widget：原生卡片、两个宿主的短搜索和左右手居中几何 | 827 |
| notification-threshold-delete：连续左滑清除、淡出及失败恢复 | 41 |
| liquid-tension：原有分裂、回融和材质回归 | 117 |

原始证据位于`Cache/tests/liquid-button-scale/`。两个宿主的测量宽度已有最多2px的RemoteViews dp换算差；搜索检查分别按各自实际标题行宽度验证同一个共享几何，同时检查居中和排序不重叠。测试没有放宽分裂、惯性、清除或材质门限。

手机安装成功不等同于物理真机动效验收；三星最终观感由用户观察确认。原生RemoteViews同步静止布局，不能执行浮窗自定义液态分裂动画。
