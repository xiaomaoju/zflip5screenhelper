# 控制中心二级弹窗入场修复

日期：2026-09-30。基于 0.17.1 / versionCode 51 的调试构建。

[安装包](FlipCover-0.17.1-detail-motion-debug.apk)，包名 `io.github.flipcover.controls.debug`。本轮未安装到物理手机。APK v2 签名验证成功，SHA-256 为 `482f89a0506d58ee112918f9076c20704b17aa8e51cc8d3f62c3d401a1d508bc`，与执行下述模拟器检查的已安装 APK 完全相同。

## 修改

- 长按控制按钮后，从该按钮图形的中心和尺寸开始，统一等比放大并移动到原有弹窗位置；滑条使用自身中心。没有来源按钮的入口保留轻微上浮。
- 首次绘制前同步隐藏弹窗和遮罩，完成按钮包装及布局后才开始 240ms 入场动画，遮罩同步淡入。
- 玻璃模态纹理就绪后统一入场；准备超过 120ms 或失败时，本次打开沿用已有纹理，迟到结果释放，不再替换可见材质。
- 关闭、卸载和关闭玻璃效果会清理等待与动画，避免迟到回调重新打开弹窗或留下透明弹窗。
- 保留最终尺寸、位置、字号、触摸范围和设置主题，未修改全局 `Ui`、权限、系统选择器或偏好格式。

## 已完成检查

- 修复构建（本轮后续并行依赖变更前）的 `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功；105 项单元测试通过，Lint 0 错误、129 条警告。
- Android 模拟器 `detail-motion`：481 项断言通过，逐绘制帧检查按钮原点、首帧透明度、缩放/透明度单调递增、卡片布局及材质稳定、慢准备超时、同一次打开的迟到结果、立即关闭和中途关闭玻璃。
- 系统动画关闭：81 项断言通过；测试后恢复模拟器原动画设置。
- 共用弹窗入口回归：`folder-tools` 71 项、`oneui-settings` 386 项断言通过。
- 独立静态审查未发现本轮改动中的阻断问题；`git diff --check` 通过。

## 尚未通过或未验证

- 最后一次重构建期间，工作区被并行加入 Kotlin/Compose 与 Backdrop 2.0.1 依赖；`checkDebugAarMetadata` 因依赖需要 compileSdk 37 / AGP 9.1，而项目仍为 compileSdk 36 / AGP 8.13.2 失败。本轮未修改或撤销这些依赖配置。上方安装包是依赖变更前成功构建并完成检查的弹窗修复版本，不代表当前并行修改后的整个工作区已通过构建；失败日志为 `Cache/tests/detail-motion/build-final.log`。
- 完整 `panel-glass` 回归两次停在 `checkReferenceLensProjection` 的 Tab 按压折射断言：标记从 `Rect(80, 9 - 114, 43)` 变为 `Rect(111, 9 - 145, 43)`，未满足预期的向上拉伸。该测试在新建 session、尚未打开二级弹窗时执行，未走本轮模态入场代码。保留失败记录；未修改 Tab 实现或放宽断言，也未将其宣称为已证实的历史失败。
- 本轮未进行三星 Z Flip5 外屏逐帧录像、实际悬浮窗口焦点切换或物理四方向验收，模拟器通过不等于真机无闪烁证明。

原始日志：`Cache/tests/detail-motion/`；构建与 Lint 报告：`Cache/build-output.nosync/app/`。
