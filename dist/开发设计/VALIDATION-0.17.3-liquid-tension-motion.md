> 此独立阶段记录已由[通知联动联合版本](VALIDATION-0.17.3-notification-liquid-combined.md)接续，当前安装状态以联合版本为准。

# 液态按钮双向分裂与惯性弹出

日期：2026-10-01。版本0.17.3 / code53，调试包 `io.github.flipcover.controls.debug`。

安装包：[flipcover-controls-0.17.3-liquid-tension-motion-debug.apk](flipcover-controls-0.17.3-liquid-tension-motion-debug.apk)

SHA-256：`8c77954f2a4f8d1fbe47debd2ed7681f6de16e9df578f65c56168479c5de9dfa`。

## 行为与复用

- 卡片左移时，按钮先少量黏连跟随，再向右挣脱；断链后保留向右速度，越过停靠点最多约2.5dp再弹回。按钮、完整图标与液体轮廓共用实时坐标。
- 按最先露出的顺序，先清除、后设置；前一个断链才解锁下一个。单按钮执行一段。回滑连续回接，正常终点完全分离，静止尺寸与间距保持控制中心规格。
- 两按钮分离并接近停靠后才允许过拉合并；实际完整合并且仍按住才待清理，松手复用原清理动作。反拉立即解除待清理；取消、多指、身份/尺寸变化、隐藏和卸载终止旧序列，系统未确认移除时保留通知。
- 通用 `LiquidTensionMotion` 只负责节点位置、速度与顺序，不依赖Android、Compose或通知业务。宿主拥有帧时钟和动作。`LiquidTensionSurface`/Renderer保留现有液体轮廓与材质，SharedGlassHost统一共享取样偏移；复用方法见[组件文档](../../docs/liquid-tension.md)。

## 本地独立验证

- 固定源码快照完成应用、仪器测试构建与142项单元测试；Lint 0错误、171条警告。
- 独立验证者在可丢弃API36模拟器完成运动48、Compose12、张力102、侧滑152、通知玻璃15及静态合并3项断言；10组曲率采样最大相邻色阶差4/255，门限18/255。
- GPU图确认两个按钮各自右移、弹出、居中归位；前一按钮保持断开，图标与圆面同步；静止和卸载停止按钮帧回调。
- 平坦、高反差、相同明确静止合并状态与旧批准版本内容区最大像素差0。原先合并差异由按压波纹瞬态取样时序不同导致，旧版本自己在短延时和明确静止状态间同样出现差异；失败素材保留，未放宽门限。
- 证据：`Cache/tests/liquid-tension-motion/verify/REPORT-final.md`；完整构建：`integration-build.log`。首轮运动测试取样失败及同步快照整合失败也保留。

## 性能与设备范围

运动计算使用固定数组及有界积分；没有逐帧对象、位图或背景截图。每组只在运动/合并未稳定时运行帧回调。现有纹理复用与释放检查通过。

本轮多模拟器并行负载下帧耗时波动较大，没有证明提速或退化。旧版稳态测量仅作为材质开销参考，不能代替本轮真实手势、三星首帧、温度及功耗验证。显式降级检查也不能代替真实Android12设备验证。

已通过ADB覆盖安装到Samsung SM-F731U1，保留应用数据；实际安装目录的SHA-256与上述最终包一致。首次安装后被并行通知任务的另一包覆盖，记录了不匹配哈希；再次安装并核对成功的证据为本任务 `phone-install-recheck.log`。本地验证和安装成功不等同于三星外屏触摸手感验收。

随后交付复核发现，并行通知任务再次安装了哈希为 `f01bbb0ef5d6e80eea2676d6b8871620f96ddcf03ef54cbb6dcaebd926048d23` 的另一包。当前手机包不等于本次验证包，已停止互相覆盖安装，等待用户授权跨聊天协调最终合并；本页的成功安装记录仅表示当时的核对结果。

## 最终效果图

原生模拟器截图，每张最上层仅叠加一次项目标准外框；属于效果示意。

- [第一个按钮向右挣脱](liquid-tension-motion/first-connected-framed.png)
- [第一个按钮断链弹出](liquid-tension-motion/first-pop-framed.png)
- [第二个按钮继续分裂](liquid-tension-motion/second-connected-framed.png)
- [最终分离与停靠](liquid-tension-motion/rest-framed.png)
