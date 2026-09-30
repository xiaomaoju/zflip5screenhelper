# 二级弹窗液态弹性

2026-09-30 · 0.17.1 / versionCode 51 · `io.github.flipcover.controls.debug`

[安装包](FlipCover-0.17.1-detail-spring-debug.apk)

## 原版依据与实现

参考固定提交 `65ab177e90e5c1d8c62e70cf7755841982da65f6` 的 [DampedDragAnimation](https://github.com/Kyant0/AndroidLiquidGlass/blob/65ab177e90e5c1d8c62e70cf7755841982da65f6/app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/DampedDragAnimation.kt)。项目内该原文件 SHA-256 与接入记录一致：`8310075ee00a9b5021935f6da1f016cb38eac61a527d27f5318c34250402c44e`。原版横纵缩放分别使用阻尼比 0.6 / 0.7、刚度 250 的 Compose 弹簧，位置与按压进度使用临界阻尼。

`DetailSheetMotion` 直接使用现有 Compose `FloatSpringSpec` 求值，不增加依赖或 Composition。针对整张弹窗，将横纵阻尼调整为 0.68 / 0.78、刚度 400；位移和透明度采用临界阻尼、刚度 1000。横纵不同步产生轻微拉伸、有限过冲与回弹，最终精确落回原尺寸。`ValueAnimator` 仅提供受系统动画倍率控制的时钟，属性值来自弹簧求解器，不是线性插值。

展开、关闭仍共用按钮原点；中途关闭继承当前形状和位移速度，透明度独立收敛，避免变亮。额外过冲根据卡片当前中心和宿主安全边界限幅。关闭、替换、卸载会取消旧时钟和回调；停止后无逐帧循环。

仅 `panelStyle` 二级弹窗使用弹性，设置和其他普通弹窗保留原动画。没有更改最终布局、字号、触摸规格、偏好、权限或系统密度。

## 验证

- 完整构建、测试 APK、105 项单元测试、Lint 通过；Lint 0 错误、136 条警告。
- 模拟器开启动画 1314 项、禁用动画 148 项断言通过，均检查首帧、按钮原点、反向收回、内容尺寸变化、快速关闭及材质超时。
- 弹性专项检查横纵差异、超过目标后回弹、缩放及实际宿主边界限幅、透明度单调、结束时精确归位。
- 退出中替换旧弹窗检查旧卡片停止变化、旧完成回调不能关闭新弹窗；静止后没有持续重绘。
- 独立静态审查未发现阻断问题；仅确认本地渲染和生命周期，真机观感仍需实际体验。
- APK v2 签名验证通过，已 ADB 覆盖安装到 Z Flip5；设备安装包哈希与测试包一致。

构建与模拟器记录：`Cache/tests/detail-spring/`。APK SHA-256：`510a6444eabce2d76e97f344c73d34559698cdee5e3e4c103eeaabec11aacf3e`。
