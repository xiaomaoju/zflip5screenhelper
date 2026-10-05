# 顶栏到位立即分裂与高光过渡（2026-10-01）

通知与控制中心的顶栏分裂改为由实际 `InterfaceCard.enter` 进度驱动，面板滑入到位的当帧立即开始，不再使用900ms入场估计或100ms停留。重复到位更新不重启；未到位时即使等待超过旧延时也不提前开始。

每个从右向左的分裂段由659ms缩短至507ms（659 ÷ 1.3），速度提高30%。通知按钮圆面与完整图标由62%平滑放大到100%，图标整枚淡入。两页移动结束后，在同一有界Animator内用140ms平滑过渡到独立按钮材质及高光。高光透明度直接作用于填充和描边，避免最后一帧切换离屏层引起抗锯齿跳变；最终尺寸、位置、文字和输入槽保持原样。启动器侧栏节奏不变。

本地与模拟器验证：

- `assembleDebug`、`assembleDebugAndroidTest`、159项单元测试通过；Lint零错误、177条警告；`git diff --check`通过。
- `panel-header-split` 169项断言通过，覆盖未到位等待、同步触发、507ms速度、140ms高光插值、缩放、硬件绘制、原输入槽和取消释放。
- 最终APK回归：`panel-actions` 42项、`control-motion` 34项、`liquid-tension` 117项通过。
- 通知按钮区域高光139ms与140ms截图最大通道差为0；0ms与70ms最大通道差为21，表明材质随插值变化且结束没有额外跳变。
- 日志与未合成的原始截图在 `Cache/tests/header-split-immediate/`；这些仅为验证素材，不是最终效果图。

[调试APK](flipcover-controls-0.17.3-header-split-immediate-debug.apk)，SHA-256：`12a39b27cc87b3d359028e47aeb5bc3094d440078c9c8f7b7edfbcf47991bea0`。

已通过ADB覆盖安装到三星Z Flip5；安装成功不等同于物理外屏观感、触感、帧率和功耗验收。以上动效与像素检查来自可丢弃模拟器。
