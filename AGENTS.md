# 外屏快捷栏 · 项目协作规则

## 项目与文档入口

- Android 源码、资源和测试位于 `app/`，Gradle 配置位于根目录；`docs/` 保存开发流程与模块契约，`dist/` 保存最终交付、发布说明和独立 HTML 原型，`tools/` 保存独立辅助工具。
- `README.md` 只介绍软件定位、用途和功能，权限与兼容性边界按用户视角说明。`AGENTS.md` 只维护稳定结构、责任边界、硬约束及完成标准；实现参数、操作流程、验收记录和历史问题写入对应文档。
- 当前构建、签名、旧版迁移与验证入口为 [开发说明](docs/development.md)，使用及授权入口为 [使用说明](docs/getting-started.md)。历史记录不能替代当前构建配置。
- 单人维护直接提交 `main`，不要求 PR；main 禁止强推和删除，允许正常推送；工程检查、暂存审查、推送及独立发布边界见 [工程流程](docs/engineering-workflow.md)。`tools/project/` 统一仓库、静态网站与 APK 交付检查，CI 使用同一入口。
- 修改模块前读取 [模块契约](docs/module-contracts.md) 的相关章节及其专题文档；具体功能约束已迁入该文档，整理 AGENTS 不表示解除原有约束。行为变更同步更新其对应契约，避免重复或冲突的规则。

## 配置与数据唯一来源

- `app-config.json` 集中常用调节项，按 `update`、`defaults`、`appearance`、`motion` 分组，校验与生成由 `gradle/app-config.gradle` 管理，`AppConfig` 读取；修改后重新构建生效。用户偏好优先于默认值。
- 包名、版本、SDK 和依赖留在 Gradle；固定网格、安全约束、内部缓存上限及算法留在所属模块，密钥独立保存。共享样式类负责计算和双宿主适配，生成常量及资源不成为第二份配置源。详见 [配置说明](docs/app-configuration.md)。
- `Prefs` 与 `AppWorkspaceLayout` 是启动器持久数据的唯一来源；`AppLauncherStyle` 是两个启动器公共尺寸与几何的唯一来源。公共功能、数据和样式在共享层修改，并检查浮窗及原生卡片两个宿主，不维护第二份偏好。
- 草稿只在有效操作后变更，完成后原子保存；返回、取消、断连与卸载按对应会话释放。配置导入先验证布局、容量、唯一归属及重叠，保留本机显示器选择、权限和系统显示设置；设备与组件绑定身份不进入备份。

## 模块责任与查阅范围

| 模块 | 主要责任与入口 |
| --- | --- |
| 外屏窗口与快捷入口 | `CoverService` 管理显示器及窗口生命周期，`DockView` 管理快捷按钮，`PanelEntryView` 管理横向双白条，`DockGeometry` 统一几何；见 [模块契约](docs/module-contracts.md#快捷栏状态栏与显示器) |
| 设置 | `MainActivity` 管理路由与草稿，`SettingsUi` 管理设置主题，`SettingsNavigator` 管理来源栈，`SettingsOrderList` 管理列表编辑；见 [设置契约](docs/module-contracts.md#设置与外接输入) |
| 控制中心 | `Panels` 构建正文，`ControlEditorView` 管理面板内编辑，`ControlEditGrid` 与 `ControlCandidatesView` 共用编辑网格规格；见 [控制契约](docs/module-contracts.md#控制中心) |
| 启动器 | `AppHubView` 管理浮窗，`AppWorkspaceView` 与 `AppDragController` 管理分页与拖拽，`LauncherWidgetBridge` 与 `LauncherWidgetViews` 适配原生卡片；见 [双宿主边界](docs/launcher-hosts.md) 与 [桌面布局](docs/app-workspace-folder-plan.md) |
| 原生组合卡片 | `NativeWidgetProvider` 提供入口，`NativeWidgetBridge` 管理授权实例与 RemoteViews 生命周期，`NativeWidgetActivity` 管理会话草稿；见 [原生卡片契约](docs/module-contracts.md#原生卡片与双宿主启动器) |
| 输入 | `InputDevices` 发现设备，`InputSurface` 与 `InputNavigation` 管理本窗口导航，`NativeCardInput` 复用唯一可见授权卡片；见 [外接输入](docs/external-input.md) 与 [六键导航](docs/six-key-navigation.md) |
| 最近任务 | `RecentTasks` 校验身份，`SystemRecentTasks` 适配受限系统接口，`RecentTasksView` 与 `TaskPreviewView` 管理任务页及预览；见 [任务契约](docs/module-contracts.md#最近任务) 与 [任务受力](docs/task-force.md) |
| 公共界面与动效 | `InterfaceCard` 管理卡片、背景和触摸交接，`PanelSurface` 管理收起手势，`PanelGlassSession` 管理背景资源；见 [界面卡片](docs/interface-cards.md)、[玻璃](docs/panel-glass.md)、[控制受力](docs/control-force-design.md)、[启动器受力](docs/launcher-force-design.md) 与 [液态张力](docs/liquid-tension.md) |
| 状态、通知与媒体 | `StatusBarView`、`StatusAppVisibility` 管理助手状态栏，通知按标识更新既有行，`MediaSessions` 随媒体表面订阅；见 [状态栏契约](docs/module-contracts.md#快捷栏状态栏与显示器) 与 [通知媒体契约](docs/module-contracts.md#通知媒体与详情) |
| 在线更新 | `UpdateCatalog` 校验协议，`AppUpdater` 管理用户发起的检查与下载，`UpdateSettings` 管理交互；见 [在线更新与发布](docs/online-update.md) |

## 必须保持的边界

- 显示器专属操作只使用用户选择或可靠识别的副屏，绝不静默回退主屏 0；全系统开关明确标注。应用方向规则仅在本工具显式启动时执行一次，异步完成后重新校验目标外屏，不由前台事件强制方向。
- 权限由用户控制；不自动授权、不绕过锁屏、不增加 Root、驱动或任意 Shell 能力。`ShellService` 保持受限 Shizuku 白名单，`ShizukuBridge` 管理连接和后台调用；系统拒绝操作时不虚构成功或回退主屏。
- 不读取其他应用页面节点树、不注入系统输入、不持久化通知或使用历史。前台判断仅用所选外屏窗口元数据及事件包名；通知、最近任务和快照只在必要的可见会话内使用，身份变化或会话结束及时释放。
- 联网仅限用户发起的在线更新检查和下载，不上传通知、配置或设备信息，不增加分析追踪；更新校验完整文件与签名证书，由系统确认安装。系统控制中心限制选择只留在本机。
- 启动器两宿主固定5列3行、每页15格；应用等宽高，文件夹2×2逻辑占位。正常控制中心由 `Prefs.panelColumns` 管理3/4/5列与4行视口，编辑页独立紧凑排布，不改写正常列数或显示器 DPI。
- 双白条始终横排，左通知、右控制：0° 左顶部或右底部，180° 左顶部，90°/270° 左顶部或右顶部。统一避让真实缺口及强制 Home 边衬；不恢复侧边竖排，不镜像缺口，不声称禁用系统手势或写入全局导航策略。
- 状态栏按应用显隐只控制助手自绘的所选外屏状态栏，未知前台默认显示，空名单停用对应判断；控制中心状态行独立，不控制系统原生栏。
- 公共窗口、订阅、回调、图标、快照、输入与动画遵循挂载及会话生命周期；关闭、息屏、失焦、断连和卸载取消旧工作，迟到回调不能恢复旧会话。避免后台轮询、重复取样和为内容更新重建窗口。
- 设置更新不通过全局 `Ui` 修改运行时悬浮层；设置缩放不接入组合卡片配置。运行时材质与动效只改变绘制，保持既有静止几何和触摸范围；公共容器在子控件事件拆分前取消多指序列。
- 设置 UI 的设计、重构、原型和评审使用 [oneui-settings-design](.agents/skills/oneui-settings-design/SKILL.md)，按其协作规则参考 `mobile-android-design`；主题不自动扩展到运行时悬浮层或系统选择器。迁移入口见 [设置方案](docs/settings-ui-refactor-plan.md)。
- 第三方 APK 仅作为参考输入，不得修改、替换、重新签名或再次分发。`tools/notification-tester/` 是独立测试 App，仅管理自身通知，不依赖或修改主应用、不自动授权；入口见其 [README](tools/notification-tester/README.md)。

## 签名、交付与完成标准

- debug 与 Release 共用包名 `io.github.flipcover.controls` 和 `keystore.properties` 指定的正式签名；无密钥允许编译、单元测试和 Lint，所有 APK/AAB 打包必须有正式签名；新版本递增 `versionCode`，不恢复 `.debug` 后缀或默认调试签名。密钥及密码不入库，必须独立安全备份。
- 云端 APK 仅由与 Gradle 版本一致、来自 main 的版本标签触发；正式签名使用 `android-release` 环境 Secrets，普通 CI 不使用私钥。发布只传递已核验的公开产物，不覆盖已发布资产；未完成设备验收的云端包标为预发布。入口见 [工程流程](docs/engineering-workflow.md)。
- APK 交付到 `dist/update-release/<versionCode>/flipcover-<versionName>.apk` 或 `dist/update-debug/<versionCode>/flipcover-<versionName>-debug.apk`，同目录附实际元数据、大小及 SHA-256 对应的 `catalog.json`；`dist/update-debug/` 与 `dist/update-release/` 整目录仅保留本地并由 Git 忽略，包含 catalog、清单和发布说明；Release 使用 `:app:prepareUpdateRelease`。测试低版本仅临时覆盖构建版本，不降低正式版本、覆盖已发布目录或自动搬移历史产物。
- `dist/` 最终 UI 效果图必须在最上层叠加一次 `dist/device-frames/zflip5-cover-overlay.svg` 或对应透明 PNG，按 [贴图说明](dist/device-frames/README.md) 对齐并保留外框、双摄与闪光灯；方向同步旋转，不重复、拉伸或镜像。未合成截图仅作原始验证素材。
- HTML 原型与所需静态资源统一平铺交付到 `dist/html/`，目录内文件使用同目录相对路径，可整体上传到静态服务器；设备贴图副本由 `tools/device-frames/build.mjs` 从唯一源 `dist/device-frames/` 生成；明确文件入口，并在普通浏览器验证显示和已有交互，不依赖 Codex 环境或机器私有路径。
- 完成工作必须有成功构建与相关本地检查，分别说明本地、模拟器与物理真机证据及未验证项；原型及模拟器不能替代三星固件窗口路由、原生宿主与锁屏兼容性验收。详细流程见 [开发说明](docs/development.md)。
- `Cache/build-output.nosync/app/`：Gradle 所有应用产物与报告，由 Gradle 配置管理，可重建、不入库，不能作为最终交付入口。
- `Cache/build-output.nosync/project/`：工程工具的网站组装、测试夹具及发布准备临时文件，由 `tools/project/` 管理，可重建、不入库。
- `Cache/build-tools/` 与 `Cache/sdk/`：官方供应商的可丢弃本机构建工具，由本机环境管理，不入库；源码及构建配置不得依赖机器专属内容。其他临时证据按任务存入 Cache，不写入 AGENTS。
