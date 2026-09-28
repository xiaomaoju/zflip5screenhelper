# 0.6.0 原生布局证据

Android 16 ARM64 模拟器，720×748；`audit-340/` 与 `audit-440/` 分别对应 340/440 dpi。不是用户手机实测密度。

- 两个目录各保存 28 个页面/状态的测量 JSON 和对应截图。实际缺口校准只检查到“未选择外屏”状态；已连接手机的校准控件未作运行时验收。
- `controls.png`、`notifications-stable.png` 及目录中的面板图由产品实际原生 View 树绘制，排除模拟器自己的状态/导航栏，不是设计重绘。它们检查布局，不能证明真实悬浮窗口的层级或手指交付。
- 设置与侧栏图使用模拟器窗口截图，顶部/底部系统栏属于模拟器。应用网格在 340 dpi 为 4 列、440 dpi 为 3 列。440 dpi 控制面板需要滚动。
- `notifications-before.png` 为修改前保留的原生样例；`notifications-stable.png` 为新版短通知，`audit-340/notifications-long-title.png` 为长标题。样例只在内存构造，未发布为系统通知。
- `chrome-50.png`、`chrome-70.png`、`chrome-150.png` 保留本轮透明描边的大小/明暗背景检查；快捷栏不随状态栏大小改变。这些是背景样例，不是实时截屏取色。
- `status-size.png`、`appearance.png`、`navigation-avoidance.png`、`about.png` 是最终设置页；默认导航避让关闭，百分比默认关闭，版本 0.6.0 / 构建 7。

完整判断见上级的 `UI_AUDIT-0.6.0.md` 与 `VALIDATION.md`。未知开关显示问号、外屏亮度不可用是测试设备无 Shizuku 的真实状态。
