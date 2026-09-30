# 应用磁贴图标对比度

2026-09-30：应用磁贴列表直接使用`ServiceInfo.loadIcon`的原始颜色，黑色或深色图案在深色玻璃背景上不可辨认。`ActionCatalog.loadIcon`现在对磁贴专用图标使用浅色着色，并先调用`mutate`隔离Drawable状态。未声明专用图标、回退到应用图标时保留应用原色；普通`app:`图标路径不变。

修复位于共享图标加载入口，覆盖候选区、已选区以及复用该目录的入口，缓存栅格化使用相同加载路径。只改变图标绘制，不读取或推测第三方磁贴状态，不更改授权、注册或执行行为。既有AGENTS职责划分不变。

依据：[Android Tile.setIcon文档](https://developer.android.com/reference/android/service/quicksettings/Tile#setIcon(android.graphics.drawable.Icon))说明图标由系统按主题着色。

`assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug`及`git diff --check`通过。日志保存在`Cache/tests/tile-icon-contrast-*`。本地检查不替代三星第三方磁贴逐项视觉验收。

模拟器编辑布局45项断言通过；ADB已覆盖安装到三星SM-F731U1，保留现有数据。磁贴逐项真机视觉结果待用户重新打开候选区确认。

[安装包](FlipCover-0.17.1-tile-icon-contrast-debug.apk)
