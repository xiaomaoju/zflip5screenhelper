# 2026-09-28 整体本地回归

本轮已完成当前 Android 应用的整体本地回归，并修复应用桌面在大字号下裁切名称的问题。最终整合包的 25 组基础场景全部通过；连同密度、方向、字号、权限、网络和动画补充检查，共保留 48 次通过的 instrumentation 结果。49 项 JVM 单元测试通过，构建成功，Lint 为 0 错误、50 警告。

这是 Android 模拟器上的本地验证结果。未连接物理 Flip5，不能据此认定三星外屏窗口路由、私有接口、锁屏及原生卡片宿主已经通过真机验收。

## 安装包与源码范围

- [下载 0.12.0 整合验证包](FlipCover-0.12.0-integrated-tested.apk)，versionCode 28，包名 `io.github.flipcover.controls.debug`，沿用项目调试签名。
- [SHA-256 校验文件](FlipCover-0.12.0-integrated-tested.apk.sha256)。APK 摘要：`61c753cc8d6bc4b2915a9f18002322f66e48435ed844d6d05f204df21831489a`。
- 包含应用桌面分页、拖拽、手动格位、补位与配置恢复，以及当前 One UI 设置重构和已有 Dock、任务页、通知、媒体、控制中心及原生小组件功能。
- 九宫格文件夹仍为[设计方案](../../docs/app-workspace-folder-plan.md)，未实现，因此不列为本轮已通过的功能。
- 使用隔离源码副本构建，避免另一设置任务的构建输出相互覆盖。最终基础回归重新安装并运行本报告对应的 0.12.0 APK。补充矩阵中部分检查早于仅版本号更新；它们验证的是本次整合实现，不将其伪称为每次都运行相同 APK 字节。
- 收尾比较时，工作区 `app/src/main` 与测试副本完全一致。后来设置测试增加了截图等待与截图滚动位置调整，未改变生产源码或断言；本报告保留实际执行的测试源码快照，不追溯替换证据。

## 环境和判定方式

- Android 16 / API 36，arm64 可丢弃模拟器 `emulator-5556`，720×748 像素，340 / 440dpi；横向实际尺寸随方向变为 748×720。
- 使用独立只读 AVD 实例；没有操作另一任务的 `emulator-5554`，没有对物理手机改设置。
- instrumentation 必须同时返回 `result=PASS` 与 `INSTRUMENTATION_CODE: -1` 才计为通过；不以 `adb shell` 的退出码单独判断。
- 真实旋转由显示器状态和测试内断言共同确认。首次 AOSP 放大镜窗口阻止旋转的运行保留为环境失败，修正模拟器旋转策略后重新执行，不将“请求旋转成功”当作“已经旋转”。
- 字号覆盖 100% / 130% / 150% / 200%。可读性组合测试使用对应资源配置；设置页另在实际系统字号 130% / 200% 下运行。
- 原始截图仅作为 `Cache/` 内的验证材料；不是合成后的最终设备效果图。

## 本轮修复与重新验证

1. **修复应用名称的大字号裁切。** 340dpi 大字号下，真实文字行高超过原估算可用高度。桌面行高改为测量与正式应用名称一致的 `TextView`，包含系统文字缩放、字体内边距和标签间距，不再仅按 `Paint` 行距估算。两种密度的可读性测试和最终整合回归均通过。
2. **修正设置测试与现有界面的接口。** 单选项已改为弹窗，恢复操作有确认，应用列表包含滚动表头；测试改为走实际当前入口，布局版本断言更新到 8。保留取消不写入、草稿提交、返回恢复和无障碍滑条检查。
3. **查清旧窗口像素测试的误报。** 前一次故意启动失败留下的系统 Toast 会污染截图；440dpi 下窗口边界还包含 3 像素系统导航区域，不能把该区域与固定蓝色直接比较。测试等待旧 Toast 消失，并在添加窗口前保存相同位置的真实背景作为参照。340 / 440dpi 的真实 WindowManager 窗口隐藏与退出像素检查均通过，没有为此修改生产窗口行为。
4. **增强失败证据。** 主线程断言捕获后在 instrumentation 线程报告，避免只得到进程崩溃而缺少有效断言信息。Wi-Fi 检查等待系统默认网络完成交接，最长 10 秒，仍要求真实 Wi-Fi transport。
5. **保持现有未知状态角标约定。** 检查主图标居中、问号位于右上角及边界内；移除与项目既定叠加设计矛盾的“两个外接矩形完全分离”旧断言，并实际查看组件截图。

## 最终基础回归：25 组全部通过

以下均为重新安装最终 0.12.0 整合包后，在 340dpi、方向 0、系统字号 100%、动画开启下的结果。断言数量是该场景内部检查数，不代表同等数量的独立用户流程。

| 场景 | 断言数 | 主要覆盖 |
| --- | ---: | --- |
| suite | 203 | 设置、组件、导入导出等基础冒烟 |
| oneui-settings | 382 | 真实 Activity、导航、取消、草稿、预览、滑条与文字布局 |
| app-workspace | 47 | 分页、拖拽、跨页、取消、延迟刷新、搜索、保存与重建 |
| native-widgets | 32 | Android 原生组件跨 UID 转发、实例和生命周期 |
| compact-restore | 150 | 紧凑布局与恢复 |
| dock-input | 527 | 四边、左右手、五按钮、紧凑把手及取消 |
| standalone-dock | 64 | 独立 Dock、切换、关闭与启动边界 |
| hub-motion | 22 | 动画帧及服务切换边界 |
| hub-window | 13 | 实际窗口表面、隐藏、退出及背景像素 |
| recent-tasks | 84 | 任务卡片、手势、锁定和无权限路径 |
| readability | 9,490 | 字号、列数、左右手及可读性组合 |
| motion-continuity | 84 | 手势接管与动画连续性 |
| blur-policy | 11 | 模糊策略与组件硬件渲染 |
| brightness-flow | 46 | 请求合并、忙碌恢复、失败处理，使用模拟回复 |
| status-safe-area | 418 | 状态栏安全区，126 次组件渲染 |
| panel-settings | 316 | 控制中心配置与恢复 |
| panel-layout | 77,377 | 288 种控制中心布局组合 |
| panel-features | 58 | 控制中心功能界面与配置 |
| control-settings | 345 | 手动列数、工具区、名称及控件 |
| hub-performance | 93 | 应用目录、图标缓存及有界资源行为 |
| notification-center | 74 | 通知列表、更新及操作 |
| notification-regression | 27 | 通知与既有面板手势 |
| media-card | 221 | 媒体卡、详情与手势 |
| details | 196 | 二级详情及控件 |
| hub-dock | 46 | 应用中心与 Dock 联动 |

## 补充矩阵

| 检查 | 结果 |
| --- | --- |
| 440dpi：基础冒烟、设置、桌面、可读性、窗口、控制中心布局、原生组件 | 7 次通过；布局 288 种组合，可读性 9,488 项断言 |
| 440dpi：实际 90° / 180° / 270° 下的桌面、控制中心、任务页 | 9 次通过；每个方向读取实际显示器旋转状态 |
| 440dpi：系统字号 130% / 200% 的设置页 | 2 次通过，每次 382 项断言 |
| READ_PHONE_STATE 授权 / 拒绝 | 2 次通过，每次 3 项断言 |
| Wi-Fi 开启 / 关闭 | 2 次通过，每次 4 项断言；开启检查在最终基础回归末尾再次完成 |
| 系统动画关闭 | 1 次通过，7 项断言 |

人工查看了大字号应用桌面、控制中心角标、隐藏窗口和大字号设置页的原始图像。应用名称无垂直裁切；长名称按既有单行策略省略。设置页在 200% 字号下通过滚动显示完整内容，不把“一屏放不下”伪装成缺陷修复。

## 构建、静态检查和签名

| 检查 | 结果 |
| --- | --- |
| `assembleDebug` / `assembleDebugAndroidTest` | 成功 |
| `testDebugUnitTest` | 49 项，0 失败、0 错误、0 跳过 |
| `lintDebug` | 0 错误、50 警告 |
| `apksigner verify` | 成功，沿用项目 Android Debug 证书 |

50 条 Lint 警告没有被隐藏或算作“全部清零”。包括私有接口反射、View 构造方法、RTL 硬编码、触摸无障碍提示、静态服务引用和绘制分配等类别；完整位置在 XML / HTML 报告中。`CoverService` 会在销毁时清空静态实例，设置滑条已通过无障碍调节断言，但本轮不据此宣称所有警告均为误报，也未扩展为无关架构重构。

## 仍未获得的设备证据

- 三星物理外屏的无障碍悬浮窗路由、开孔安全边界、边缘系统手势及手指操作容错。
- 三星原生卡片的收录、授权和锁屏行为；模拟器原生 AppWidget 转发成功不等于三星宿主兼容成功。
- Shizuku 私有任务接口在目标固件上的任务恢复、清理和系统快照；本地任务测试包含组件、身份保护和拒绝路径。
- 真实外屏发光亮度、传感器旋转及刷新率/帧率；亮度请求测试使用模拟回复。
- 长期待机、耗电、真实媒体应用及真实用户通知的兼容性。

这些属于未覆盖范围，不记为已经发生的故障，也不计入本轮通过项。无需用户此时配合操作。

## 证据与复现

本地证据根目录：`Cache/tests/full-regression-20260928/`。

- `build.log`：完整构建结果。
- `source/Cache/build-output.nosync/app/test-results/testDebugUnitTest/`：JVM XML 结果。
- `source/Cache/build-output.nosync/app/reports/lint-results-debug.xml` 及 HTML：Lint 明细。
- 根目录 `*-340.txt`：最终 25 组基础回归及 Wi-Fi 开启结果。
- `*-440.txt`、`*-440-rotation*.txt`、`settings-440-font*.txt`、`phone-*.txt`、`wifi-off.txt`、`animations-off.txt`：补充矩阵。
- `display-rotation*.txt`：实际方向证据；`signature.txt`、`digests.txt`：签名与摘要。
- `device-artifacts.tar`：从本轮模拟器导出的原始截图和组件证据。
- `tested-source.tar`：实际构建与执行的源码及测试快照，SHA-256 为 `68b41104146a59a3050a2cd3326ebfb6b4eb39c176e610a6468df91d89dd970f`。
- `initial-run/` 与 `rotation-setup-failure/`：最初失败及环境问题，保留历史，没有改写为成功。

从项目根目录复现构建时，先按 README 设置本地 JDK / SDK，再执行：

```sh
./gradlew -p Cache/tests/full-regression-20260928/source :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug --console=plain
```

在专用可丢弃模拟器上安装该副本的应用与测试 APK 后，可逐场景执行，例如：

```sh
adb -s emulator-5556 shell am instrument -w -e scenario app-workspace -e rotation 0 io.github.flipcover.controls.debug.test/io.github.flipcover.controls.UiSmokeInstrumentation
```

测试会临时修改模拟器的偏好、权限或系统设置；复现应使用专用模拟器。`Cache/` 为本地可丢弃证据，安装包运行不依赖这些目录。
