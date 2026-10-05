# 通知液态张力与联动联合版本 · 0.17.3

2026-10-01：完整源码冻结、构建及独立本地验证通过；已通过ADB覆盖安装至SM-F731U1（R5CW61RE8XB），保留应用数据。安装返回Success，随后读取手机`base.apk`的SHA256，与交付APK一致。此联合结果接续两个聊天的独立阶段，不再使用旧通知联动包覆盖按钮惯性版本。

- [可安装联合APK](flipcover-controls-0.17.3-notification-liquid-combined-debug.apk)
- 包名：`io.github.flipcover.controls.debug`
- SHA256：`05a1e21418e19ccc7be009f9c1580751e225ba492b50c0d2e49e87d072842420`
- [组件接口与设计](../../docs/liquid-tension.md)

## 合并后的行为

按钮与卡片共同参与拉伸。圆面起初少量向左黏连，然后逐渐向右挣脱；液颈断开时保留惯性，越过停靠点约2.5dp再回弹。按最先露出的按钮顺序分裂，前一按钮完全断开才释放后一按钮；一个按钮只走一段。正常停点恢复既有尺寸、位置、间距和触摸范围，图标随实际圆面移动，轮廓、阴影和材质保留此前已确认版本。

保留通知列表边界弹性、相邻卡片受力和顶栏联动。两按钮完全分离后继续左拉，到最大删除目标且实际合并完成时立即提交一次，不等待松手。提交锁住姿态，圆面和清理图标共用180ms线性透明度淡出，不再次分开；系统确认后卡片从当前位置继续向外退出。未确认请求在1200ms后恢复，持续通知的单按钮仍不能清理。

`LiquidTensionMotion`复用固定数组，不拥有材质、时钟或通知业务；`LiquidTensionSurface`与原生Renderer仍使用现有Backdrop扩展和纹理。宿主只在运动未收稳时安排下一帧，取消、尺寸变化、隐藏及卸载停止回调；共同位移由`SharedGlassHost`提供，不在共享绘制层判断通知类型。

## 验证证据

冻结快照位于`Cache/tests/notification-liquid-combined/source/`。`:app:assembleDebug`、`:app:assembleDebugAndroidTest`、`:app:testDebugUnitTest`、`:app:lintDebug`均成功：25个测试套件、146项单元测试通过，Lint零错误、171条警告。

独立验证者未修改源码或测试，只使用可丢弃模拟器emulator-5574；检查实际PASS结果，未以命令退出码代替场景结果，也没有重试失败项。

| 场景 | 本次结果 |
| --- | --- |
| 分裂惯性 `liquid-tension-motion` | 48项通过 |
| 即时清理 `notification-threshold-delete` | 41项通过 |
| 共享张力 `liquid-tension` | 117项通过 |
| Compose组件 | 12项通过 |
| 曲率连续性 | 10个样本通过，最大相邻通道差4/255 |
| 通知受力网络 | 30项通过 |
| 通知中心 | 302项通过 |
| 横向侧滑 | 152项通过 |
| 通知玻璃 | 15项通过 |
| 同状态静态合并 | 3项通过 |
| 边界惯性 | 20项通过 |
| 通知受力回归 | 10项通过 |
| 通知更新回归 | 27项通过 |

真实GPU帧检查覆盖完整圆面及图标透明度1、0.5、0，卡片材质像素不变；覆盖持拉不松手的一次提交、提交后反拉/UP不再次清理、确认外退和未确认恢复。默认平坦、高反差、明确停稳合并三个材质内容区与已批准版本的最大像素差均为0，原有最多1通道级差的门限保持不变。未增加手势背景截图或位图预处理。

独立报告：`Cache/tests/notification-liquid-combined/verify/REPORT.md`；原始日志、GPU图、静态对照及单元/Lint汇总位于同目录。构建日志为`Cache/tests/notification-liquid-combined/build.log`。手机安装及哈希读回为`phone-install.log`、`phone-installed-sha256.txt`。此前静态差异、取样修正、整合失败及手机包覆盖证据保留在各自Cache记录，联合版本没有覆写旧报告。

## 效果图

以下为受控模拟器组件场景，已在最上层叠加一次标准机身贴图；用于显示本次分裂及合并淡出的形态，不代表手机当前通知内容或物理手感。

- [第一按钮黏连右移](notification-liquid-combined/motion-first-connected-framed.png)
- [第一按钮断链弹出](notification-liquid-combined/motion-first-pop-framed.png)
- [第二按钮拉伸](notification-liquid-combined/motion-second-connected-framed.png)
- [两按钮停稳](notification-liquid-combined/motion-rest-framed.png)
- [提交时完整圆面与图标](notification-liquid-combined/delete-full-framed.png)
- [圆面与图标同时半透明](notification-liquid-combined/delete-half-framed.png)
- [圆面与图标同时消失](notification-liquid-combined/delete-zero-framed.png)

## 性能及真机验证范围

运动积分使用有界小步及固定数组，通知当前仅一至两个运动节点；静止后无额外动画循环，纹理复用。合并后的180ms渐隐仅在断开动作区域使用短时透明离屏层，其额外GPU成本未单独测量。此轮FrameMetrics不能支持优化提速或功耗结论；原有稳态基准见组件文档。

ADB安装与手机包身份已经核验。本地模拟器验证不等同三星物理外屏的触感、首帧、锁屏路由、功耗或真实通知服务的删除验收；受控删除检查使用内存fixture及模拟确认，没有清理手机用户通知。显式低版本降级分支通过，不代表已在实际API30–32设备验证；超过当前按钮数量的扩展需另行验证。
