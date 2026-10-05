# 0.17.3 启动器页码记忆与 Dock 入场

验证包：`flipcover-controls-0.17.3-launcher-page-memory-debug.apk`，版本 0.17.3（53），调试包名 `io.github.flipcover.controls.debug`。

SHA-256：`643cdaeed680be995c3d3734f0386c530120cf2f4a110b739296e2af973b2401`。

## 本次行为

- 浮窗启动器与原生启动器卡片共用桌面浏览页码，关闭、息屏和应用进程重启后保留。
- 页码与系统 `BOOT_COUNT` 一起保存；设备重启后旧页码失效，重新从第一页开始。
- 应用目录就绪后恢复页码，页数减少时收敛到最后一页。搜索和文件夹的临时页码不覆盖桌面记录，拖拽预览不提前写入。
- 页码不进入配置或布局备份；保存页码不触发浮窗和顶层控件重建。
- 只打开浮窗 Dock 时始终从屏幕下方上滑入场，与白条在顶部或底部无关；静止位置、尺寸和触摸区域保持原样。

## 本地验证

| 检查 | 结果 |
| --- | --- |
| Debug APK、AndroidTest APK 构建 | PASS |
| JVM 单元测试 | 159 项，失败 0、错误 0 |
| Lint、`git diff --check` | PASS |
| `launcher-page-memory` | 12 项断言：重开、Dock 展开、搜索、排序、越界与启动身份失效 |
| `launcher-page-memory-restart` | 3 项断言：实际 force-stop 后的新进程恢复页码 |
| `launcher-page-memory-reboot` | 3 项断言：实际重启模拟器后从第一页开始 |
| `interface-card` | 1171 项断言，含顶部／底部白条时 Dock 都从下方入场 |
| `launcher-widget` | 836 项断言，含共享页码保存、卡片生命周期恢复与文件夹隔离 |

构建和模拟器日志位于 `Cache/tests/launcher-page-memory/`。使用独立模拟器 `emulator-5580` 验证，不在连接的物理手机上运行清空配置的测试，不安装或重启手机。

上述验证不代表三星外屏的真实无障碍窗口路由、原生卡片固件行为及动效观感已经验收，仍需物理真机确认。本次没有最终效果图。
