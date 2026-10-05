# 半屏任务页验证 · 0.17.3

安装包：[flipcover-0.17.3-task-half-screen.apk](flipcover-0.17.3-task-half-screen.apk)。效果图：[task-half-screen-framed.png](task-half-screen-framed.png)，使用真实渲染组件与模拟任务预览，最上层叠加一次项目标准设备外框。没有安装或操作物理三星设备。

任务窗口与卡片统一使用安全范围内的下半屏，高度为屏高的一半，安全高度不足时受限。面板外显示真实应用并透传点击；不建立背景取样会话，不启用全屏窗口模糊或压暗。系统任务快照仍按需读取，卡片透镜独立于背景取样；缺少快照显示圆角基础底与应用图标。清理退出同步淡出局部背板和正文。完整页面切入半屏任务页时，旧页面随推移渐隐，取消恢复原位置与透明度。

| 检查 | 结果 |
| --- | --- |
| assembleDebug / assembleDebugAndroidTest | PASS |
| testDebugUnitTest / lintDebug | PASS；保留现有 Lint 警告 |
| task-half-screen | PASS，39 个断言；实际窗口范围、区域外像素与点击、快照透镜、X、局部退出、四向手势与取消 |
| task-force | PASS，179 个断言；二十任务、四卡复用、串行关闭、失败回弹与释放 |
| task-close-failure | PASS，14 个断言；先成功后失败、已确认移除与复用 |
| git diff --check | PASS |

证据位于 `Cache/tests/task-half-screen/`。第一次额外 task-force 运行被重叠的 instrumentation 启动中断；串行重跑通过，未以中断结果作为功能通过证据。模拟器应用面板窗口不能证明三星无障碍悬浮窗口的路由、底层应用运行状态、功耗或手感。

用户后续图1提出更低的紧凑横条、卡片内应用名、右侧固定 X 及覆盖移动卡片的局部磨砂渐变。这些属于后续方案评估，尚未写入本安装包；本包的 X 仍在下方中央，名称与状态仍在卡片外。本包没有对其他应用画面做实时折射或局部磨砂。

APK SHA-256：`d7f4cfe66932cf3455167b7c3cf9b18ffad4d0c4315b6beae4b22c60321c8509`。
