# 原版 LiquidBottomTabs 接入

运行路径为 `ControlCandidatesView → ControlSourceTabs → OriginalLiquidTabs → com.kyant.backdrop.catalog.components.LiquidBottomTabs`。玻璃渲染直接使用 Maven 的 `io.github.kyant0:backdrop:2.0.1`。

作者仓库：[Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass/tree/65ab177e90e5c1d8c62e70cf7755841982da65f6)。固定提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6`；以下文件来自其 `app/src/commonMain/kotlin/com/kyant/backdrop/catalog/`，保存于本项目 `app/src/main/java/com/kyant/backdrop/catalog/`，参与编译的内容与原文件逐字节一致。

| 文件 | SHA-256 |
| --- | --- |
| components/LiquidBottomTabs.kt | 4dfbafaf008b058bf71ceebbc568c900f7a74acaf94f2fe774c6155367ff0155 |
| components/LiquidBottomTab.kt | 40178a5ab429022e4e5523a39b9feb40c8c04370671361b24ad4ee992eaaeeb0 |
| utils/DampedDragAnimation.kt | 8310075ee00a9b5021935f6da1f016cb38eac61a527d27f5318c34250402c44e |
| utils/DragGestureInspector.kt | 01548ab5604d89bd9a8ee66a69d716036e94559c842461fbee4afc97915770c1 |
| utils/InteractiveHighlight.kt | d0af46b531eba19bd0fed886892f02193f05d5a61db0925da016ff6059984f83 |

`AndroidFrameClock.kt` 是 Android 单平台桥，对应上游 Android `Coroutines.kt` 的 `kotlinx.coroutines.android.awaitFrame()`；不使用多平台 `expect/actual` 声明。

应用自有接入代码位于 `OriginalLiquidTabs.kt` 与 `ControlSourceTabs.java`：提供三项文字/图标、来源回调、无背景时的普通按钮、取消/多指/尺寸变化边界、Compose 窗口生命周期，以及既有玻璃会话中的背景纹理。作者的动画、位移曲线、色散和图文叠层没有改写。整体64dp组件通过局部密度适配40dp槽，未修改系统密度、共享Ui或原文件内部比例。

手指从 Tab 区域按下后，宿主持有该触摸序列并禁止父容器中途拦截；移出胶囊或 Tab 的上下左右边界均继续传递坐标，松手后由原版组件结算选择。只有系统取消、多指、真实尺寸变化或卸载才中止序列。

原版库要求 compileSdk 37 与 AGP 9.1 以上，因此构建使用 SDK 37.0、Build Tools 36.0.0、AGP 9.1.1、Gradle 9.3.1 和 Kotlin 2.4.10。minSdk 30、targetSdk 36 保持原值。依赖只在构建时解析，应用没有新增联网权限。

背景仍由 `PanelGlassSession` 一次性获取，Compose 的隐藏 LayerBackdrop 只读取已有 sharp 位图。关闭或降级销毁 Composition，释放其图层；没有轮询截图。外屏GPU、窗口合成和三星宿主行为仍需要物理设备验收。
