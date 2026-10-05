# 0.17.3 · 白色电量与浅灰空槽

按用户更正，电池当前电量改用白色 `#FFFFFF`，空槽保留浅灰 `#C3C3C7`。白色从左侧随实际电量增加；满电全白，0% 或暂未取得电量时全浅灰。替代上一版深色电量方案。

继续复用时间、Wi-Fi、通知、充电图标的柔影参数，保留时间 11.5dp／700 字重、电池几何、外部百分比选项、独立分页点和双白条。

## 验证

- `assembleDebug` 与 `assembleDebugAndroidTest` 构建成功。
- 原生模拟器 `chrome-shadow` 场景通过 62 项断言，包括各外观模式的 25%／75% 白灰分区及空电、满电、阴影缓存。
- `git diff --check` 通过。此颜色修正未重复执行完整单元测试或 Lint；上一版的 158 项单元测试及 Lint 结果不作为本版新执行结果。
- ADB 更新安装到 SM-F731U1，返回 `Success`，包版本 0.17.3／53，更新时间 2026-10-01 18:23:25。
- 真机外屏观感待用户查看；模拟器绘制检查与安装成功不等同于物理外屏视觉验收。
- 旧状态安全区测试的配置版本断言 13／9 与当前 15／11 不一致，本次未执行或计为通过。

## 交付

- [验证 APK](FlipCover-0.17.3-battery-white-debug.apk) 与 [SHA-256](FlipCover-0.17.3-battery-white-debug.apk.sha256)。
- [灰底效果图](battery-white-gray-framed.png)、[白底效果图](battery-white-white-framed.png) 为本地原生渲染，最上层各叠加一次标准设备外框，已目视检查。
- 日志和原始渲染保存在 `Cache/tests/battery-white/`。
