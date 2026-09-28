# 0.7.3 通知标题栏专项验证

日期：2026-09-27。

验证包：`FlipCover-0.7.3-notification-debug.apk`，包名 `io.github.flipcover.controls.debug`，versionCode 11，versionName 0.7.3。

SHA-256：`877da3134020235d6b9a9b7bd08379eb5bc3570c1fc539d722ca646c6f1a223a`。

## 本轮改动

- 把清理按钮移到标题栏，只显示垃圾桶图标和可清除数量；保留完整无障碍描述与工具提示。0 条时禁用并显示灰色 0，按钮位置保持稳定。
- 新增齿轮进入系统通知管理页，按用户选择管理所有 App 的通知；单张通知左滑的设置仍进入对应 App 的通知设置。
- 删除底部清理区域，让列表使用剩余安全空间。新通知提示和左侧标题共用固定区域并互斥显示，右侧操作不换行、不叠字。
- 未修改原有分组、通知清理请求、常驻通知保护或通知权限。

系统入口使用 Android 13 起提供的 `ACTION_ALL_APPS_NOTIFICATION_SETTINGS`；启动前仍核验所选副屏、亮屏与锁屏状态，明确指定目标显示器，失败不主动改到主屏。低于 Android 13 时提示入口不可用。[Android 官方接口](https://developer.android.com/reference/android/provider/Settings#ACTION_ALL_APPS_NOTIFICATION_SETTINGS)

## 本地结果

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Debug / AndroidTest 构建 | 成功 | `Cache/tests/notification-header/build-final.log` |
| JUnit | 34 项通过 | `Cache/build-output.nosync/app/test-results/testDebugUnitTest/` |
| Lint | 0 errors，33 warnings | `Cache/build-output.nosync/app/reports/lint-results-debug.txt` |
| 原生通知专项，720×748 / 340dpi | 74 项断言通过 | `Cache/tests/notification-header/ui-340.log` |
| 原生通知专项，440dpi / 1.3 倍字体 | 74 项断言通过 | `Cache/tests/notification-header/ui-large-font.log` |
| 原有通知与面板手势回归，440dpi | 27 项断言通过 | `Cache/tests/notification-header/regression-440.log` |
| 调试 APK 签名 | 通过 | `Cache/tests/notification-header/signature.txt` |
| 系统设置 Intent 解析 | 模拟器解析到系统 NotificationAppListActivity | 只证明本地系统存在入口 |

新增检查覆盖标题栏清理按钮位置、仅显示数量、完整无障碍说明、0 条禁用、三位数数量、标题空间、设置／清理／关闭按钮不重叠、底部区域移除，以及新通知提示出现与恢复时不叠字。沿用原有分组、摘要、新通知保护、长按、侧滑和四个停靠方向的断言。

独立只读复核未发现本轮新增的确定缺陷。原生图片已检查，最终效果使用标准设备外框叠加一次，见 `0.7.3-ui/`。

## 验证边界

- 未连接或操作物理手机。三星 One UI 是否将系统通知设置显示在所选外屏，以及返回后的窗口行为，仍需 Flip5 真机验证。
- 本包由当前共享工作区构建，包含已有的其他模块改动；本次仅验收通知标题栏与相关回归，不替代其他模块的验证。
- 真实通知监听回调和批量取消仍沿用 0.7.2 的验证边界：公开的快照读取与取消接口不具备原子性，极端并发下的摘要级联风险未由本轮 UI 改动解决。

历史复核：记录 `20260927T074914Z-6998978e801d` 中的真机路由、真实通知取消及并发边界继续标为 MONITORING；本轮未改变这些路径的清理规则。
