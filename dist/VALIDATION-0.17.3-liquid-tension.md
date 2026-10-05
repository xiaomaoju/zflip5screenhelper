# Liquid Glass 通知曲率与明暗修正版

日期：2026-10-01。安装包：`flipcover-controls-0.17.3-liquid-tension-debug.apk`，版本0.17.3 / code53。

SHA-256：`28854eaca00d2b0965bba6cae75e3e7db87b68c7d76bc1162568faaa47eff85d`。

## 本轮行为

- 按最先露出的顺序，先清除、后设置；第一按钮完全分离后才处理第二按钮。单按钮只走第一段。正常展开停点全部脱离。
- 本轮针对仍存在的轮廓和明暗折痕：轮廓改为三次平滑并集，重叠期扩大过渡范围后逐渐收细；明暗保留原始融合坡度，折射使用宽域连续坡度，消除细颈取样方向翻转产生的接缝。卡片和按钮继续共用材质，独立背景缓存保持停绘。活动图标完整绘在卡片之上，整枚淡入，不再沿卡片边缘裁切；下一图标仍按顺序隐藏。
- 通知顶栏和卡片动作共用控制中心的36dp槽、28.8dp圆面、16dp可见图标及7.2dp圆面间距。计数胶囊保持内容宽度与相同高度。
- 两按钮完全分离后继续左拉，越过弹性阈值才向共同中心靠拢，以液颈融合为一个清理图标。合并完成后松手才请求清理；反拉、取消、多指、尺寸变化和身份变化取消待清理。单按钮持续通知不触发该动作；系统未确认移除时保留通知。
- 延续Backdrop 2.0.1公开扩展接口，Compose及原生Canvas复用 `LiquidTensionSurface`、`LiquidTensionGeometry` 和 `LiquidTensionRenderer`。详见[组件文档](../docs/liquid-tension.md)。

## 本地验证

- Gradle应用与Android测试构建成功；129项单元测试通过；Lint零错误、170条警告。
- 本轮原生模拟器：93项张力断言、15项通知玻璃断言及10组曲率／明暗像素采样通过。前轮152项左滑与278项通知中心检查保留，本轮未重复不涉及的完整通知回归。
- 新场景先对已交付旧版复现失败，再验证修正版：五个连接距离、两种背景下，旧版液颈相邻像素最大单通道差为202/255，修正版最大为4/255；深色背景由12/255降至2/255。门限保持18/255。原始证据为 `before.log`、`final-curvature.log` 及对应GPU截图。
- 张力检查包含顺序分裂及反向连通区域、正常停点一／两按钮脱离、完整活动图标、子视图缓存刷新后像素保持一致、合并／反拉／取消／多指／身份取消、持续通知保护、合并松手仅一次清理请求、未确认请求保留原行，以及纹理复用与资源释放。
- 使用模拟通知与回调记录，不清理真实用户通知。本轮证据位于 `Cache/tests/liquid-tension-curvature/`，前轮证据保留在 `Cache/tests/liquid-tension-repair/`。

## 性能

Android ARM64模拟器720×748、340dpi、60Hz；预热后交替四组，共724帧。基线两组CPU绘制中位数平均0.100ms，张力平均0.121ms，增加约0.021ms/帧。GPU中位数差约0.068ms，小于模拟器帧时波动；没有新增截图、背景位图或静止帧循环。完整数据见组件文档及 `tension.log`。

当前两至三个表面的场景开销较小，适合组件化复用；大面积、多组并行和三星物理设备功耗需要单独评估。

## 物理设备

已通过ADB覆盖安装到连接的Samsung SM-F731U1（Z Flip5），版本0.17.3 / code53；从设备安装目录读取的SHA-256与上述交付APK完全一致。证据为 `phone-install.log`。

本轮确认的是构建、模拟器效果与真机安装成功，尚未完成物理外屏触摸、首帧、温度和功耗验收。

## 效果图

以下均为原生模拟器截图，最上层仅叠加一次项目标准机身贴图；属于模拟器效果示意。

- [旧版与修正版轮廓／明暗对比](liquid-tension/curvature-comparison-framed.png)
- [顺序分裂三阶段](liquid-tension/sequence-framed.png)
- [设置按钮连接与完整图标](liquid-tension/two-buttons-framed.png)
- [单按钮连接](liquid-tension/one-button-framed.png)
- [正常停点完全分离](liquid-tension/detached-framed.png)
- [继续左拉、双按钮融合](liquid-tension/merging-framed.png)
- [合并为清理图标](liquid-tension/merged-framed.png)
