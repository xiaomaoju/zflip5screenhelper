# 启动器侧栏分裂与文件夹玻璃验证（2026-10-01）

## 已完成

- Dock整体液态玻璃保留，仅清理扫把取消独立玻璃按钮；九宫格延续上次修复的透明反馈。
- 侧栏初始轮廓包住下方设置，页面就位后460ms内收缩、拉出连接颈部、断开为圆形。共用LiquidTensionRenderer和当前卡片强模糊纹理，不重新取样、不移动图标及触摸区域；页面未就位或背景准备中不会提前消耗动画，关闭、取消及尺寸变化停止回调。
- 侧栏36dp、设置圆形直径36dp、4dp间隔、图标20dp，与侧栏一致。
- 搜索及排序共用40dp高的玻璃表面，圆角共用应用区20dp资源。
- 文件夹为共享玻璃，颜色轻微染入材质。成员或名称刷新不再覆盖GlassSurface；降级恢复最新颜色，2×2占位、方形表面及3×3预览保持。
- 打开文件夹的居中坐标按最近像素取整，避免奇数空间向上偏移；RemoteViews继续使用dp适配宿主密度。
- 上次去掉的右上角两个按钮及手动拖动整张InterfaceCard的修复保持，并由本次容器检查回归覆盖。

## 本地检查

- assembleDebug、assembleDebugAndroidTest、144项JVM单元测试成功；lint：0错误、170警告。
- interface-card：1068项通过，包含实际窗口中的三阶段轮廓、动画启动、布局及触摸尺寸稳定、文件夹刷新保持玻璃、可见扫把不绑定玻璃、纹理内存不增长、关闭释放，以及手动收起背板与内容同步。
- launcher-widget：813项通过，真实Android AppWidgetHost、PendingIntent、旋转/尺寸、5×3格位、文件夹/侧栏/Dock及配置同步。
- liquid-tension：93项通过，横向通知顺序分裂与回融不受纵向API影响。
- liquid-tension-component：12项通过；liquid-tension-curvature：10项通过。
- hub-motion：43项通过；launcher-swipe：52项通过；workspace-dock-menu：69项通过。
- app-folders：88项通过。旧用例仍断言配置14/布局10，本次仅同步为当前代码已有的15/11，未改变配置格式或放宽导入校验。

两个用例第一次被其他安装中断；重新使用固定APK安装后已完成上述检查。缓存原始窗口截图的颈部中心像素（38,486）：包裹56/91/122、颈部55/99/137、分离36/72/104；分离阶段恢复背景，证明实际绘制连接已断开。

## 效果图及产物

三张图均从模拟器硬件窗口截图生成，在最上层叠加标准Z Flip5贴图一次；蓝色固定背景用于观察折射，不是手机实拍。

- [包裹](launcher-sidebar-tension/sidebar-wrapped-framed.png)
- [连接颈部](launcher-sidebar-tension/sidebar-neck-framed.png)
- [独立圆形](launcher-sidebar-tension/sidebar-separated-framed.png)
- [验证APK](flipcover-controls-0.17.3-launcher-sidebar-tension-debug.apk)

SHA-256：`49f9148b76fd35fb991cf7dae0027f07bef0607f98b1df2a31ea8dacef51fa3c`

## 验证边界

本次APK已装入模拟器，物理三星手机当前未连接，未安装本次构建；三星窗口路由、实际手势与动画观感仍需真机验证。原生RemoteViews仅同步静止尺寸、文件夹颜色及公共数据，不支持浮窗自定义液态着色器及分裂动画，也不取样宿主背景。

上次报告的旧任务页滚动/取消用例未在此项材质工作中修复，不将本次专项通过表述为全应用验证完成。原始日志位于Cache/tests/launcher-sidebar-tension/。
