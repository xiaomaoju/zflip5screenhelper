# 0.2.0 验证记录

日期：2026-09-27。产物：`FlipCover-0.2.0-debug.apk`。

## 本次变化

- 快捷栏改为纯黑、无彩色底板及边框；2–5 个总按钮，其中 1 个固定在末位，其余参与滑动。固定动作可选择，支持旧配置与版本 2 配置导入导出。
- 内置图标改用 39 个 Google Material Symbols Rounded 本地矢量资源，附带 Apache-2.0 许可；没有在线字体依赖。
- 设置页改为分组列表、图标入口、黑色底与柔和卡片；快捷栏编辑页直接预览分页。
- 控制中心采用紧凑图标、9sp 标签、明暗开关状态、小问号表示未知；亮度和音量改为竖向滑条，保留 TalkBack 范围操作和取消恢复。
- “通知”动作改为系统原生通知入口，旧列表保留为“应用内通知”。三星外屏路由未知，不能承诺系统一定在外屏展开。
- 接入 Android 12+ 窗口 blur-behind 与实时可用性监听；系统不支持或关闭模糊时用黑底，快捷栏窗口置于模糊面板上方。未截图或缓存其他应用画面以模拟毛玻璃。
- 保留 0.1.1 的常驻恢复、锁屏/息屏隐藏和外屏 ID 限制。

## 已执行检查

| 项目 | 结果 |
| --- | --- |
| Android 构建 | assembleDebug、assembleDebugAndroidTest 通过 |
| JVM 单元测试 | 9 项通过，0 失败、0 错误 |
| 单测覆盖 | 手势防误触与双向分页，四方向缺口避让，以及 2–5 键分页区/固定区不重叠并完整覆盖触控区域 |
| 原生组件检查 | Android 16 ARM64 一次性模拟器，720×748 / 340dpi，33 项断言通过 |
| 模拟器检查内容 | 2–5 键配置不被暗中减少、滑动翻页、固定键位置不变、固定区域手势不翻页、固定动作替换、滑条范围限制、不可用滑条禁止提交、取消手势恢复旧值 |
| 原生截图检查 | 已查看设置页、快捷栏、紧凑控制中心和四方向旋转组件；缩紧控制中心行距后默认 11 个按钮在测试视口完整显示 |
| Android Lint | 0 错误、12 警告；无 baseline、未关闭检查 |
| APK 签名 | apksigner verify 通过，v2；签名证书与 0.1.x 相同 |
| APK 标识 | io.github.flipcover.controls.debug；versionCode 3 / versionName 0.2.0 |
| SDK | 编译/目标 36，最低 30 |
| 权限 | Shizuku API、Wi-Fi 状态、音频设置、相机、振动；未新增权限，无 Internet |
| 本次三星真机检查 | **未执行，Flip5 未连接** |

12 项 Lint 警告为原有的内部亮度 API、固定依赖/构建版本、服务静态引用、程序创建的自定义 View 构造器、中文文本拼接、物理坐标对齐，以及新增 LevelSlider 的程序构造器提示。静态服务引用在销毁时清空；没有压制或隐藏这些检查。

## 截图的边界

`0.2.0-ui/` 下图片来自原生 Android 组件的模拟器截图，不是网页或 AI 绘图。720×748 与 340dpi 用作本次测试条件，**340dpi 不是已确认的手机参数**。控制中心由测试容器承载，显示的是未连接 Shizuku 时的未知状态和黑底效果；没有把样例状态伪装成真机状态。

测试没有启动真实外屏无障碍悬浮窗、Shizuku、三星系统通知面板或实际窗口模糊，不能将截图当作这些功能的验收。程序入口限定在一次性 Android 模拟器，检查会重置模拟器里本应用的配置，不应在日常手机上执行。

## 仍需手机确认

- 覆盖更新后切换应用和回桌面时是否常驻，以及锁屏/解锁、外屏旋转后的恢复。
- 真实异形区域能否容纳 5 键；横向触控宽度会随按钮数增加而减小，不能保证每键双向都有 44dp。
- “通知”是否在外屏展开三星原生面板；若无响应，可将“应用内通知”添加为备用入口。
- 毛玻璃是否在外屏可用、是否影响原侧边栏；不支持时应使用黑色面板，固定栏仍清晰。
- 按显示器亮度、全局音量、系统开关、原生通知路由、第三方磁贴的 One UI 兼容性。

SHA-256：

```text
aa50e3adbb36e4b903b2c6f409467945179f53547ae6c3afe2a363272ef16354
```

本地证据在 `Cache/tests/ui-0.2.0/build.log`、`instrumentation.txt`、`signature.txt`，Gradle 报告在 `app/build/reports/`。APK 不依赖这些文件。

官方参考：[Material Symbols](https://developers.google.com/fonts/docs/material_symbols)、[Android 设置页设计](https://developer.android.com/design/ui/mobile/guides/patterns/settings)、[跨窗口模糊](https://source.android.com/docs/core/display/window-blurs)、[系统通知动作](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#GLOBAL_ACTION_NOTIFICATIONS)。
