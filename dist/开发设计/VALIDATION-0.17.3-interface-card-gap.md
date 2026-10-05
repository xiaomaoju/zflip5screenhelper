# 卡片互推间距验证

安装包：`flipcover-controls-0.17.3-interface-card-gap-debug.apk`。

SHA-256：`8d0e4d7b519ff3c7e801aec54d8d3858b98b8bfe496f8121952811d78e514893`。

`InterfaceCard.push` 按新旧卡片当前绘制与裁切边界约束旧卡片的位置，相邻边缘至少留6dp。间隙随入场展开、取消收回，保留90ms初始插值及随后一比一跟手；四页静止时仍为大框，`useCardSafeArea=false`。

- Debug应用、AndroidTest构建、Lint通过；22个单元测试套件共129项，失败及错误均为0。
- 原生模拟器 `interface-card`：959项断言通过，覆盖全部24种定向替换，并逐帧检查初始插值、半程、跟手和取消回弹的边界间距。
- 原生模拟器 `panel-entry`：657项断言通过，包含真实触摸分发、间隙及后续20px滑动的等量跟手；`launcher-swipe`：52项断言通过。
- `git diff --check` 通过。日志位于 `Cache/tests/interface-card-gap/`。
- ADB在SM-F731U1上保留配置安装成功，已安装 `base.apk` 与交付包SHA-256一致。未执行真实外屏手势和视觉实测；模拟器使用application-panel窗口，不能证明三星外屏系统窗口路由。
