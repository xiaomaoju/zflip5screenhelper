# 启动器与 Dock 区域果冻受力 · 2026-10-01

验证包：`flipcover-controls-0.17.3-launcher-force-debug.apk`，版本0.17.3（53），包名 `io.github.flipcover.controls.debug`。本次未安装到物理三星手机。

SHA-256：`c5e1b722fafc0714399d30f9153656a3250bd7b300c538c7d5869bb955fdffa8`。

## 实现

- 固定八个区域受力点、八条连接：背板、应用容器、网格、搜索／排序／两条摘要整体、侧栏、设置、Dock、文件夹。图标继承所属区域的简单拉伸，不创建逐图标物理节点。
- 开启、横向分页、按压、拖起、文件夹和 Dock 展开／收起向同一数值模型传力；文字只平移，现有字号、尺寸、格位、间距与触摸区不变。
- 默认柔软果冻，位移限幅1.5–7dp，拉伸限幅6.5%；一帧两次固定子步，模型不在帧内分配对象。`AppHubView` 持有唯一受力帧回调，稳定及按住边界达到平衡后停帧，卸载、多指、隐藏和真实尺寸变化清理旧输入。
- 侧栏保留 ScrollView 内容滚动及惯性，只在上下边界施加非线性橡皮筋反馈，反向拖回边界后释放，不继续把内部滚动当越界。侧栏手势不收起整张卡片。
- 设置分裂固定从开启算起1500ms开始：`ENTRY_ESTIMATE_MS=900` + `SPLIT_WAIT_MS=600`，分裂用560ms。没有入场完成监听。取消收起后保留已分裂状态；新会话或重新展开仍按固定延时开始。
- 手动收起继续同步移动 InterfaceCard 背板与正文，从实际松手位置退出；保留应用区先收起、Dock后退出的既有流程。
- 背景反作用复用现有 `PanelGlassSession` 纹理，不增加截图、背景轮询、独立图标帧回调或常驻合成层。三星 RemoteViews 仍使用共享静止布局及内容，宿主横滑和原生侧栏不接入自定义受力。

## 本地验证

使用可丢弃 Android 36 模拟器，720×748、340dpi；所有会修改偏好的 Instrumentation 仅在模拟器运行。

| 检查 | 结果 |
| --- | --- |
| debug / AndroidTest 构建 | PASS |
| JVM 单元测试 | 152项，失败0、错误0；含4项新受力模型测试 |
| lintDebug | PASS，0错误、176警告；不是警告全清零 |
| `git diff --check` | PASS |
| `launcher-force` | PASS，15项：共同顶栏、区域传力、稳定停帧、上下边界、反向释放、取消及卸载 |
| `interface-card` | PASS，1155项：24种顶部／底部页面过渡、手动跟手、材质像素、分裂延时、取消和释放 |
| `hub-motion` | PASS，43项 |
| `app-workspace` | PASS，50项 |
| `app-folders` | PASS，88项 |
| `workspace-dock-menu` | PASS，69项 |
| `dock-pin-placement` | PASS，138项：固定／最近布局、预览、取消和重开 |
| `launcher-widget` | PASS，827项：真实 Android AppWidgetHost 与共享静止布局 |
| `hub-performance` | PASS，95项：五次重复打开、目录与图标缓存、订阅释放；不等于GPU帧时测量 |

受力模型另覆盖30/60/120Hz与长帧、快速反向冲量、节点限幅、关联传播、边界按住平衡停帧及释放回零。相关日志在 `Cache/tests/launcher-force-native/`，Gradle报告在 `Cache/build-output.nosync/app/`。

## 保留的失败与限制

- 旧 `motion-continuity` 未通过 `fresh notifications follows entry travel without an initial jump at rotation 0`。该断言读取 `CoverService.panel` 正文的 translationY；当前实现由 `panelCard` 承担位移，并有入口安全区短插值。该旧场景尚未迁移，不能标为PASS；新卡片场景的1155项通过不能改写这条失败日志。
- 旧 `standalone-dock` 未通过 `accepted app start in mode false shows a sliding intermediate frame`，仍假定 `AppHubView` 自身平移。当前卡片由 InterfaceCard 统一移动，展开后的退出由服务卡片动画管理。仅替换位移对象的临时测试探针继续在展开模式的旧动画归属断言失败，已撤销该测试改动；保留原场景失败记录。Dock 布局及新卡片接受启动后的退出路径已由对应通过场景覆盖，但不宣称旧完整场景通过。
- 未在物理三星外屏上测量触摸延迟、帧时间、GPU合成或频繁打开的功耗；模拟器的停帧及缓存检查不能代替真机手感与性能验收。三星宿主兼容性、系统Home接管及真实背景取样仍按原项目边界验收。

实现契约见 `docs/launcher-force-design.md`，宿主差异见 `docs/launcher-hosts.md`。原型入口继续保留在 `launcher-force-jelly.html`，验证包包含本次原生实现。
