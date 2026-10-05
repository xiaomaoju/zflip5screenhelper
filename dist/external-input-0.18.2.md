# 0.18.2 外接输入验证版

[安装 APK](Flip外屏助手-0.18.2-external-input-release.apk) · versionCode 60 · 同正式签名和包名，可覆盖升级。此版本已通过ADB安装到连接的Z Flip5。

SHA-256：`7b53964b75b25bb436c88337697d1c7bcc76940472193f7ed9c4e804fa324ddc`

- 设置 → 鼠标与触控板：选设备、方向/指针模式、灵敏度、识别与输入测试。
- 控制中心 → 外接设备：液态玻璃快捷设置弹窗。
- 键盘方向键选择，Enter确认，Esc退出调整，Shift+F10打开项目操作；文字输入保留光标行为。
- 三星原生启动器和助手组合卡片已纳入辅助输入；不扩展为系统全局控制。

蓝牙验收：先配对鼠标，再在设置中开启设备规则并将该鼠标设为方向导航。验证四向移动、单击、长按、右键、滚轮、退出助手、断开/重连。键盘不依赖鼠标规则总开关。测试用设备记录已移除，总开关恢复关闭。

[完整实现与测试记录](../docs/external-input.md) 列明本地通过项目、真机观察，以及两项改造前即可复现的旧回归失败。实际蓝牙鼠标、实际触控板和更多第三方小组件仍待验收。

## 真机界面

以下为开发验证过程的实际屏幕，均使用项目规定的唯一设备外框；测试设备名称不是用户蓝牙设备名称。

![外接设备玻璃快捷弹窗](external-input-quick-framed.png)
![原生启动器侧栏方向导航](external-input-native-navigation-framed.png)
![最终Release纯键盘方向导航](external-input-keyboard-framed.png)
![组合卡片按钮导航](external-input-widget-framed.png)
