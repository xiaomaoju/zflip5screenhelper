# 原版 LiquidBottomTabs 验证

2026-09-30：将控制中心编辑器的来源 Tab 接入作者原版 `LiquidBottomTabs.kt`，移除此前自行实现的 Tab AGSL 折射与动画。五个上游文件逐字节校验一致；版本、来源与文件哈希见 [接入说明](../docs/liquid-tabs-upstream.md)。应用接入层只提供内容、回调、尺寸适配、背景与窗口生命周期。

- 构建：`assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 均成功。
- 模拟器：原版 Tab 专项在浅色、深色模式各通过48项断言；完整面板玻璃检查通过518项断言。覆盖点击/拖动切换、取消、多指、草稿不变、订阅及静止时无背景绘制循环。
- 真机：ADB 安装到 Samsung SM-F731U1 成功。已在所选外屏（display 1）执行拖动并截图，确认胶囊放大、覆盖两项图文及边缘色散。没有修改权限、系统密度或保存编辑草稿。完整自动化断言在模拟器执行，不代表三星设备全部生命周期场景已经验收。
- 独立只读源码复核：宿主 Recomposer/Composition 生命周期与隐藏态释放问题已修复，五个作者文件保持不变。

安装包：[FlipCover-0.17.1-original-liquid-tabs-debug.apk](FlipCover-0.17.1-original-liquid-tabs-debug.apk)

APK SHA-256：`c0439b353bcf8d52bd4d26777a73121102e98a759533448d7cc9226ded85fda1`，与真机已安装的 `base.apk` 一致。

真机默认与拖动态：[默认](original-liquid-tabs/device-resting.png) · [拖动](original-liquid-tabs/device-dragging.png)。最终图片通过 `tools/device-frames/compose.mjs` 叠加标准机型框一次，原始截图及测试日志保存在可丢弃的 `Cache/tests/original-liquid-tabs/`。

## 越界拖动修复

同日真机反馈指出：原生宿主在手指超出 Tab 或纵向偏移时错误地取消了拖动。已移除这两个取消条件，按下时禁止父级拦截，连续传递手指坐标直至松手。系统取消、多指、尺寸变化和卸载仍终止旧序列；五个作者文件没有修改。

最终修复版的构建、单元测试与 Lint 均成功；原版 Tab 专项通过53项断言，新增检查通过真实父视图层级派发事件，覆盖上下左右越界、外部松手、取消、多指、误触搜索及订阅释放。[越界仍保持拖动态](original-liquid-tabs/outside-drag.png) 为模拟器验证图。

最终安装包：[FlipCover-0.17.1-original-liquid-tabs-drag-debug.apk](FlipCover-0.17.1-original-liquid-tabs-drag-debug.apk)，SHA-256：`c387eeb2a23631e738291d6fea221b7bd2103cf0e35b737a80a8c4d198c64bab`。ADB 已再次安装到 SM-F731U1；此次越界行为自动化验证在模拟器完成，真机手感仍待实际使用确认。上述518项完整检查和真机拖动截图属于前一集成版。
