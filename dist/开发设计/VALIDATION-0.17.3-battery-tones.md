# 0.17.3 · 电池深浅电量分区

电池内部固定用深灰 `#373739` 表示当前电量、浅灰 `#C3C3C7` 表示未充满部分，深色从左侧随实际电量增长。0% 或暂未获取电量时显示浅灰底，100% 显示全深色。保留电池外部百分比选项，未增加电池内部数字。

电池继续使用时间、Wi-Fi、通知和充电图标共用的 `ChromeShadowDrawable.forIcon` 柔影。时间维持 11.5dp／700 字重，电池尺寸与圆角保持原样；分页点和双白条保留独立参数。

## 本地验证

- 完整重编译通过：`assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug`。
- 158 项单元测试通过，无失败或跳过；Lint 0 错误、176 警告。
- 模拟器原生 `chrome-shadow` 场景通过 62 项断言，覆盖各外观模式的 25%／75% 深浅分区、六档电量、透明度、时钟及共用阴影缓存。
- `git diff --check` 通过。
- 首次增量构建因本地缺失旧 class 文件失败；使用 `--rerun-tasks` 完整重编译后成功，未调整源码规避该缓存问题。
- 旧 `StatusSafeAreaChecks` 的配置版本断言仍为 13／9，当前为 15／11；本次未运行或将其计为通过。

## 物理设备

已通过 ADB 更新安装到 SM-F731U1，返回 `Success`。包版本为 0.17.3／53，系统记录更新时间为 2026-10-01 18:18:16。安装不等同于真机外屏观感确认，最终显示效果待用户查看。

## 交付

- [验证 APK](FlipCover-0.17.3-battery-tones-debug.apk) 与 [SHA-256](FlipCover-0.17.3-battery-tones-debug.apk.sha256)。
- [灰底效果图](battery-tones-gray-framed.png)、[白底效果图](battery-tones-white-framed.png)：来自原生本地渲染，均在最上层叠加一次项目标准 Z Flip5 外框，并已目视检查；不是真机外屏截图。
- 构建及测试日志保存在 `Cache/tests/battery-tones/`。
