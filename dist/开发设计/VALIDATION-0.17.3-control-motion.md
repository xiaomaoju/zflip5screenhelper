# 0.17.3 控制中心动效开发验收

日期：2026-10-01。交付：[调试 APK](FlipCover-0.17.3-control-motion-debug.apk)。该包由当前项目工作区构建，包含工作区已有修改；本次实现范围为正常控制中心一级动效，不变更设置主题。

## 已实现

- 网格保留原生滚动、既有3/4/5列及四行可视规格；到边界后共用一个一维弹簧。网格拉到22dp上限后， 超出的拉力 交给整页最多5.5%拉伸。
- 松手时网格与整页使用相同恢复进度，从同一帧开始回弹；重新按住立即冻结，反向拉回零交接原生滚动，避免误点按钮。
- 磁贴及小按钮按压/弹起；亮度、音量和音乐表面180ms非线性缩小、480ms单次恢复，没有过冲。滑条拖动随方向微移，松手有限回摆。
- 顶栏、编辑、刷新、亮度、音量和音乐使用六个固定局部余弹曲线，没有随机或逐模块物理积分。顶栏保留编辑、刷新，添加在原位编辑器内操作。
- 开启动效与背板/正文一起绘制，沿用现有 InterfaceCard 入口、切换和收起手势。未新增原型中的一级正文横滑路由。
- 静止尺寸、间距、字号及触摸范围保持原值，变形只在绘制。共享玻璃跟随绘制矩阵，复用原有背景纹理，不增加截图、实时背景取样、订阅或轮询。取消、多指、尺寸变化、卸载及关闭系统动画时释放旧动效；稳定与持指静止不运行网格帧循环。

所有权与固定参数见 [设计契约](../../docs/control-force-design.md)。主要新增源码：ControlElastic、ControlScrollView、ControlFeedback、ControlHeaderView。

## 本地构建及检查

从项目根目录运行，使用现有本地 JDK17 / Android SDK / Gradle9.3.1：

```sh
JAVA_HOME="$PWD/Cache/build-tools/jdk17/Contents/Home" ANDROID_HOME="$PWD/Cache/sdk" GRADLE_USER_HOME="$PWD/Cache/gradle-home" ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

结果：BUILD SUCCESSFUL；158项单元测试，0失败/错误；其中新增4项 ControlElastic 测试覆盖两段拉伸、同时恢复、重新按住与反转、极端速度和帧间隔。Lint：0错误、176项现有警告。证据位于 `Cache/tests/control-motion/build.log` 和 `Cache/build-output.nosync/app/`。

隔离 Android 36 ARM64 模拟器：720×748，density340，hardware rendering / SwiftShader。仅安装在本次隔离模拟器，不安装或改动物理手机。

| 场景 | 结果 | 覆盖 |
| --- | --- | --- |
| control-motion | PASS，34项断言 | 同帧恢复、重新按住、反向拖动不误点、滑条取消、三块单调恢复、两个小按钮余弹、非零滚动材质坐标、卸载停帧 |
| control-motion-reduced | PASS，3项断言 | 系统动画比例临时设0时按压/方向位移/恢复均无残留，finally恢复原值 |
| control-dashboard | PASS，180场景 | 3/4/5列、稀疏/完整/超量、横竖布局、两种字体比例及工具区布局 |
| control-rotation | PASS，4方向 | 实际原生边界及网格格位 |
| control-editor | PASS，148项断言 | 原位添加、移除、排序、取消、撤销、搜索、保存/放弃、订阅生命周期 |

Tile 图形使用 PixelCopy 的实际硬件窗口像素检查，按压面积减少且恢复到原面积；原始素材保存在 `Cache/tests/control-motion/`，不是最终 UI 效果图。独立只读审核已通过上述源码及日志证据，四个发现的问题均已修复并复查：反向交接误点、减少动效残留、父级绘制缓存刷新、滚动后材质锚点。

扩展 `panel-glass` 回归未全部通过：首轮被新模拟器首次全屏提示遮挡像素采样；确认提示后重跑通过前段五种纹理/控制中心模糊、三种列数、静止停帧、原位编辑与详情、纹理预算等断言，随后在复用 ControlGridChecks 拖到顶部工具栏时，Android 36 系统拒绝原生输入注入（`native input accepted`）。日志显示 `WindowManager ... move channel`，尚不能判为产品行为缺陷，亦不计为完整玻璃验收通过。独立 `control-editor` 的148项原生输入检查已通过。原始扩展证据：`Cache/tests/control-motion/native-glass.log`、`glass-logcat.log`；此项保留为模拟器测试环境限制。

## 验证边界

此处为构建、单元测试、隔离模拟器输入/布局/像素证据。尚未验证三星 Z Flip5 物理外屏手感、帧率、GPU驱动及耗电；不宣称性能提升比例。玻璃绘制、裁切和整页合成仍有实际GPU成本。

真机体验重点：打开/收起；正常滚动到上下边界后继续拉；网格与整页同帧恢复；回弹中再次按住、反向拖回；亮度/音量/音乐的单次恢复；顶栏两个按钮错相余弹；在3/4/5列及四方向检查既有触摸范围；开启编辑后确认添加和取消；息屏/关闭后确认无旧动效。

APK 签名由 SDK apksigner verify 检查通过。

APK SHA-256：`e698e93329d278d3f2068d7d5eba0368196db27fb8a7eba54a20b31c7959c2a4`。
