# 撤除底边分段上滑

[安装包](flipcover-0.17.3-no-launcher-swipe-debug.apk)，沿用当前 0.17.3 调试包身份。本轮未覆盖物理手机上的应用。

已删除本对话加入的底边分段上滑入口、Dock 窗口触摸转交、单次震动展开和越界顺序收起，以及对应诊断、专属测试与当前使用说明。设置恢复为“白条滑入”。

保留按钮打开 Dock／启动器、双白条通知与控制中心手势、卡片推移、玻璃材质、侧栏、任务页及后续显示器安全边界修复。未回退整个工作区。旧分段手势安装包和说明已移至本轮 Cache 备份；其他历史验证报告中的 `launcher-swipe` 记录不代表当前仍有该功能。

验证结果：

- `assembleDebug`、`testDebugUnitTest`、`lintDebug`、`assembleDebugAndroidTest` 成功；142 项单元测试通过，Lint 0 错误，APK 签名校验通过。
- 模拟器 Dock 输入 677 项、启动器动画 43 项断言通过。
- 独立只读审查未发现误删其他功能或手势残余调用。
- `interface-card` 套件未通过玻璃画面高频细节抑制断言（`InterfaceCardChecks.checkLauncherGlass`）；本次未改动玻璃渲染实现，未进行修复，也未把该套件记为通过。
- 没有进行物理真机安装或触摸验收。

构建、测试日志和撤除前快照位于 `Cache/tests/remove-launcher-swipe/`。
