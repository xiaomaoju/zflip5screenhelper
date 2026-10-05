# 液态分裂参考与启动器调整（2026-10-01）

参考用户视频第3秒后的效果：主体收缩、小液滴成形、颈部收细、顺序断开和轻微回弹。

- 侧栏以560ms完成收缩、液滴成形、断开和回弹，绘制轮廓不移动图标或点击区域。
- 通知左滑的设置和清除共用液滴成形计算；先露出的按钮先分裂，后一按钮等待前一按钮断开。保留既有反向回融、惯性和合并清除。
- 侧栏和设置圆钮同步收窄为32dp，保留4dp间距、4dp内部留白和20dp图标。搜索栏改为32dp高，应用区圆角统一为外框的32dp规格，小表面按宽高限制半径。两宿主读取同一共享参数。
- 浮窗侧栏采用同心内缩胶囊裁切和上下8dp渐隐；滚动时遮罩留在视口。原生列表使用圆角容器裁切和系统渐隐。
- 文件夹拖动复用已挂载的原视图，保留玻璃及颜色，更新取样变换；取消、落位和卸载释放临时材质。固定5×3格位、2×2文件夹占位及布局提交规则保持。

## 产物

[验证APK](flipcover-controls-0.17.3-liquid-reference-debug.apk)，SHA-256：`49a258a9712e9095c6a14b93e10205ba400a2353dab916c8be00d0cb676fbe2c`。

模拟器固定背景效果图均在最上层叠加一次标准设备贴图，并同步旋转贴图以对应720×748素材：

- [侧栏连接颈部](liquid-reference/sidebar-neck-framed.png)
- [侧栏分离与紧凑布局](liquid-reference/sidebar-separated-framed.png)
- [文件夹拖动保留玻璃](liquid-reference/folder-drag-glass-framed.png)
- [通知小液滴](liquid-reference/motion-first-bud-framed.png)
- [第一个按钮断开](liquid-reference/motion-first-pop-framed.png)
- [通知两个按钮停稳](liquid-reference/motion-rest-framed.png)

## 本地验证

成功构建assembleDebug、assembleDebugAndroidTest；147项JVM单元测试通过，Lint为0错误、173条警告。验证APK已安装到可丢弃ARM64模拟器。

| 检查 | 通过断言 |
| --- | ---: |
| interface-card，含胶囊裁切、实际滚动渐隐、拖动材质及释放 | 1113 |
| liquid-tension-motion，含小液滴逐渐成形及停稳尺寸 | 52 |
| notification-threshold-delete | 41 |
| app-workspace | 50 |
| app-folders | 88 |
| launcher-widget，真实Android AppWidgetHost | 813 |
| liquid-tension，横向顺序分裂及回融回归 | 117 |

原始日志与图片位于`Cache/tests/liquid-reference/`。首次遮罩探针使用普通View作为白色内容，ScrollView将其自然高度测为0；补足最小内容高度后，实际滚动及固定像素边界检查通过。圆钮图标对称内边距存在1px取整差，尺寸检查按实际显示密度保留该像素取整范围。桌面测试的配置/布局版本断言从旧13/9同步为现有15/11，没有修改配置格式或导入逻辑。高频背景检查一次未通过，原门限保持，最终完整检查通过；不把模拟器时序波动解释为性能结论。

## 验证边界

未安装到物理三星手机；三星窗口路由、锁屏宿主兼容性、真实手势和最终观感尚待真机验证。RemoteViews同步静止尺寸及共享数据，不能执行浮窗自定义液态着色器与分裂动画。效果图使用模拟器固定背景，不是手机实拍，也不证明GPU功耗或真机帧率。
