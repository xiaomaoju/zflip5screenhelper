# 0.3.0 验证记录

日期：2026-09-27。产物：`FlipCover-0.3.0-debug.apk`。上一版记录保留在 `VALIDATION-0.2.0.md`。

## 本次变化

- 常驻状态条：时间、Wi-Fi、电量/充电、闹钟、通知图标，可选整机网速及最多双卡信号/网络类型。未知信息不伪造；订阅和定时器随窗口挂载/卸载。
- 双横条按手指距离展开本工具通知或控制中心；短划回弹、快速滑动展开，标题可拖回收起。保留独立三星原生通知按钮。
- 桌面/系统面板默认收起快捷按钮，应用内恢复；长按任意横条临时切换，换应用后恢复规则。收起后只剩约 10dp 透明细条窗口，原按钮区域不拦截触摸。
- 按应用收起规则可编辑。识别只使用所选非零显示器的窗口类型、编号和事件包名；不请求页面节点树或文字，最多 32 条映射仅留在内存。没有可确认的前台应用时保留状态，初始状态仅显示横条。
- 固定键默认打开应用中心：收藏栏独立滚动、展开/收起应用网格、搜索、编辑、底部固定应用。
- 设置中心按任务分组并可搜索；权限用途与状态、预览、版本/构建号、更新日志。配置版本 3 支持新状态项目、应用中心和收起规则，并兼容版本 1/2。
- 新增 4 个官方 Material Symbols Rounded 矢量图标，现共 43 个；许可随 APK 打包。

## 检查结果

| 项目 | 结果 |
| --- | --- |
| 构建 | assembleDebug、assembleDebugAndroidTest 通过 |
| JVM 测试 | 16 项通过，0 失败、0 错误 |
| JVM 覆盖 | 分页防误触、四方向几何/缺口、2–5 键槽位、收起触摸范围、方向锁定、展开阈值、桌面/应用规则和临时选择生命周期 |
| 原生组件检查 | Android 16 ARM64、720×748 / 340dpi，86 项断言通过 |
| 原生覆盖 | 2–5 键、固定键位置/替换、四边双入口距离回调、取消不误点、两条横条长按、搜索空状态、收藏滚动与底部固定、网格搜索、滑条范围/取消、关于页版本 |
| 视觉检查 | 查看最终首页、规则页、状态页、搜索、应用中心、控制面板、仅横条和关于页截图；设置首页减少状态说明高度 |
| Lint | 0 错误、14 警告；无 baseline、未关闭检查 |
| APK 签名 | apksigner verify 通过，v2，证书与 0.1.x / 0.2.0 相同 |
| APK 标识 | io.github.flipcover.controls.debug；versionCode 4 / versionName 0.3.0 |
| SDK | compile/target 36、min 30 |
| 新权限 | ACCESS_NETWORK_STATE；可选运行时 READ_PHONE_STATE 用于信号/网络类型，不读取电话号码；没有 Internet |
| 无障碍能力 | 为区分目标显示器窗口启用窗口检索声明；不调用 getRoot/getSource 或读取节点树。升级后旧服务可能需要用户手动重新开启以加载能力 |
| 三星真机 | **未执行，Flip5 未连接** |

14 项警告包括原有内部亮度接口、固定依赖版本、服务静态引用、程序创建 View 的工具构造器、中文文本拼接、物理坐标左右对齐，以及标题拖动的可访问性静态提示。服务销毁清空静态引用；面板另有独立关闭按钮，横条另有无障碍打开和显示/隐藏动作。

中间构建先后在生成目录发现 `PanelDrag 2.class` 和 `ic_launcher 2.xml` 副本，造成重复类型/非法资源名。最终将生成目录改为项目内 `Cache/build-output.nosync/app/`，完整构建通过；使用相对目录，其他平台仍可正常构建。失败原始记录位于 Cache，不计作功能通过证据。

## 尚需 Flip5 确认

1. One UI 的外屏首页、通知页、其他卡片页是否提供可识别窗口；应用切换后的自动规则是否正确，长按的临时选择是否符合预期。系统窗口不可识别时只能依靠手动长按，不能称自动识别已通过。
2. 真正跨 WindowManager 窗口的连续跟手、四方向缺口与触摸位置，以及与原侧栏/系统边缘手势的冲突。模拟器断言验证的是组件距离回调和纯几何，不是三星真实手势路由。
3. 息屏/解锁恢复、运行中切换应用常驻；无障碍被撤销或系统停止后的行为。锁屏仍隐藏全部窗口。
4. 状态条在真实外屏的遮挡、双卡信号、通知图标、权限撤销，以及 Shizuku、亮度和第三方磁贴兼容性。
5. 系统原生通知的显示器路由、外屏模糊、搜索键盘弹出后的应用中心大小及应用启动路由。

`0.3.0-ui/` 为原生组件预览，不是三星真机、真实悬浮窗口或模糊效果截图。测试入口只允许一次性 Android 模拟器，不应在日常手机上执行。

SHA-256：`1bc6a3638627c962f394dd9e38e25da663ca95abe27d360d14cbf7f9aec04175`。

本地证据：`Cache/tests/ui-0.3.0/build.log`、`instrumentation.txt`、`signature.txt` 和 `Cache/build-output.nosync/app/reports/`。APK 不依赖这些文件。

接口依据：[Android 多显示器窗口列表及权限要求](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#getWindowsOnAllDisplays())、[电量信息](https://developer.android.com/reference/android/os/BatteryManager)、[整机流量计数](https://developer.android.com/reference/android/net/TrafficStats)、[移动网络显示信息](https://developer.android.com/reference/android/telephony/TelephonyCallback.DisplayInfoListener)、[系统通知动作](https://developer.android.com/reference/android/accessibilityservice/AccessibilityService#GLOBAL_ACTION_NOTIFICATIONS)、[Material Symbols](https://developers.google.com/fonts/docs/material_symbols)。
