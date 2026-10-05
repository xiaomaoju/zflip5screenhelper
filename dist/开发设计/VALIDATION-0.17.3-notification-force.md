# 通知中心橡皮筋联动验证版

2026-10-01。安装包：[flipcover-controls-0.17.3-notification-force-debug.apk](flipcover-controls-0.17.3-notification-force-debug.apk)。

到达通知列表顶部后继续下拉，整组卡片向下跟手移动，后面的卡片下移更多，间距被拉开；到底后继续上拉采用相反方向的同一规则。拉力采用逐渐增加阻力的曲线，松手后衰减回弹。整体位移占响应的72%，可见卡片之间的差异位移占28%。拉伸根据视口内实际位置计算，展开分组的子通知同样响应。

`NotificationScrollView` 保留手势、边界距离、惯性与面板交接的所有权；`NotificationForce` 计算阻力、整体位移和差异位移，`NotificationSwipeRow` 在绘制时施加偏移。只发现并更新视口及边缘余量内的通知行；静止后没有新增动画循环。正文不随纵向拉伸缩放，不改变静止尺寸、字号、布局或触摸区域。布局变化刷新可见行，取消、多指、尺寸变化、隐藏与卸载清除弹性状态。

继续收起仍须先滑完列表，在对应边界继续拉动，超过原96dp阈值后才交给 `PanelSurface`。交接带走当前整组位移，保留卡片之间的拉伸，避免整组位移被重复计算。横向通知操作沿用现有独占手势和液态按钮分裂、融合规则。

玻璃采样坐标跟随实际绘制偏移，复用现有 `PanelGlassSession` 和张力纹理，不新增截图或材质位图。另将任务页一个 `Stream.toList()` 调用换为现有低版本可用的收集接口，修复本次 Lint 发现的 Android API 34 兼容错误。

## 本地验证

- Gradle `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功；140项单元测试，0失败；Lint 0错误、170项现有警告。
- Android API 36 ARM64可丢弃模拟器，720×748、340dpi、系统动画开启。通知中心302项、惯性20项、专项联动10项、侧滑152项、既有通知与面板回归27项、通知玻璃15项、张力93项，共619项原生断言通过。
- 专项检查直接读取实际渲染像素，确认整组移动、相邻卡片间距扩大、展开分组不裁掉子卡片，以及多指取消恢复原始像素。小数像素平移允许1像素栅格差异，卡片布局尺寸和宽度保持不变。
- 记录包含实际页面构建器的软件绘制和硬件截图。最初共享测试进程被新的仪器测试启动中断，之后使用独立模拟器与隔离测试包完成长列表、惯性和玻璃检查；隔离包仅用于本地验证，不交付。
- 日志、原始截图、APK校验和及测试辅助配置位于 `Cache/tests/notification-force-native/`。

## 最终效果图

[静止状态](notification-force/rest-framed.png) · [顶部下拉](notification-force/top-pull-framed.png) · [底部上拉](notification-force/bottom-pull-framed.png)

效果图使用示例通知，由原生页面绘制，并在最上层叠加一次项目标准 Z Flip5 透明外框；不是新的UI布局设计稿。原始硬件截图只用于验证。

尚未安装到已连接的物理真机。以上结果不能代替三星外屏窗口路由、系统手势和主观触感验收。

APK SHA-256：`4156600122e2ca01e5fab7cd43ecfafe7e9c71074b814e8c141fccd6b125c63e`。
