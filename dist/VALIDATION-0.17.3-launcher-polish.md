# 启动器留白、边界稳定、文件夹与 Dock 入场 · 2026-10-01

验证包：`flipcover-controls-0.17.3-launcher-polish-debug.apk`，版本0.17.3（53），包名 `io.github.flipcover.controls.debug`。

SHA-256：`5aa5ced259e0dde16f113a03ac8aed34cc5ce228eeb1df1731a2467d4ceecd71`。

已通过 `adb install -r` 覆盖安装到连接的三星 SM-F731U1，返回 `Success`；系统包信息确认本次更新时间，`CoverService` 已重新连接。配置保留，没有在物理手机运行会清空偏好的 Instrumentation。

## 改动与原因

1. 内部应用卡片、侧栏和 Dock 与大背板留8dp空隙，参数归 `AppLauncherStyle.SURFACE_INSET`；浮窗和原生分区布局共用几何，固定5×3格位规则继续保留。原生0°仍为既有单背景布局。
2. 侧栏边界反馈上限从5dp提高到8dp，反向回到边界后释放；按住时保留整个关联平衡，松手回零并停帧。
3. 已定位并修复三条抖动路径：手势累计位移被反复当成新冲量；按住停帧时，把仍受相邻节点牵引的位置直接归零；两个速度主轴切换时拉伸瞬间翻转。分页现在只传入实际位移差，应变在两轴之间连续变化。重复同一边界 MOVE 120次不产生新网格／Dock冲量。
4. 文件夹玻璃的材质坐标包含 Canvas 分页、区域位移和瞬态拉伸；使原文件夹表面绘制缓存随位移失效，不再只依赖布局坐标。成员弹窗先设置样式再安装玻璃，避免样式替换已绑定的背景。更新复用当前背景纹理，不增加外部截屏或后台轮询。
5. 成员弹窗最大168dp，三列横向间距随背景宽度缩小，保留共享成员图标及52dp行规格；删除右上关闭×，编辑位于原关闭端。点击同卡片宿主内的任意外部空白关闭，成员拖出仍沿用原事务。
6. 文件夹管理菜单最大208dp，每行34dp，移除×；点击下方或其他外部空白直接关闭，不自动重新打开文件夹。内容超高时仅正文滚动，标题保留。
7. 文件夹打开从原文件夹入口出发，复用控制中心 `DetailSheetMotion` 的弹性缩放、位移及反向退出。全宿主点击层通过内边距保留正文安全范围，过冲也受该范围约束。
8. Dock 使用一个660ms有界时钟：先弹性展开胶囊，两端图标不绘制；约297ms弹出左侧九宫格，约350ms弹出右侧清理，各约251ms缩放／淡入。固定槽位和其他应用点击保留，端部动作完整出现后启用。无可见最近应用时不制造清理按钮；隐藏、卸载和释放取消时钟。

受力仍为八区域、八条连接，无逐图标物理状态。模型和材质坐标复用数组／矩阵，分页失效遍历不创建迭代器；Dock 时钟不逐帧计算清理目标或读取偏好。静止、按住平衡及等待系统请求不持续调度受力帧。

## 验证

所有 Instrumentation 均在可丢弃 Android 36 模拟器运行；物理手机仅安装、读取包信息及服务状态。

| 检查 | 结果 |
| --- | --- |
| debug / AndroidTest 构建 | PASS |
| JVM 单元测试 | 154项，失败0、错误0；含6项受力稳定测试 |
| lintDebug | PASS，0错误、176警告 |
| `git diff --check` | PASS |
| `launcher-force` | PASS，16项，含边界重复 MOVE、上下回弹、取消及卸载 |
| `launcher-polish` | PASS，18项，含绘制位移材质坐标、实际玻璃绑定、紧凑尺寸、外部关闭和 Dock 动画中段 |
| `interface-card` | PASS，1155项，含材质像素、进出、取消、分裂延时与释放 |
| `hub-motion` | PASS，43项 |
| `app-workspace` | PASS，50项 |
| `app-folders` | PASS，88项 |
| `folder-tools` | PASS，39项 |
| `folder-layout` | PASS，25项，当前340dpi／fontScale 1 |
| `workspace-dock-menu` | PASS，70项 |
| `dock-pin-placement` | PASS，138项 |
| `launcher-widget` | PASS，827项，含原生真实宿主、左右手与旋转对齐 |
| `hub-performance` | PASS，95项，含重复打开、缓存与订阅释放 |
| `task-force` | PASS，164项，启动器留白不扩展到任务正文 |
| `detail-motion` | PASS，1271项，原控制中心弹簧、安全区过冲、反向及卸载 |

临时证据在 `Cache/tests/launcher-polish/`，构建报告在 `Cache/build-output.nosync/app/`。`folder-raw.png` 是合成背景的原始模拟器验证截图，不是最终效果图；没有将它作为无标准机身贴图的最终效果交付。

## 剩余边界

已安装及模拟器通过不能代替三星外屏上的连续触摸手感、GPU帧耗时和功耗验收。本次修复的是已复现的位移／受力／材质路径，不保证所有掉帧或抖动都消失。侧栏越界松手、首尾不松手、文件夹重开与 Dock 双端弹出需实际操作复测。

前一份 `VALIDATION-0.17.3-launcher-force.md` 保留旧 `motion-continuity` 与 `standalone-dock` 的失败记录；它们仍依赖旧正文位移／动画归属，本次没有把这些旧完整场景标记为PASS。当前卡片、Dock布局与接受启动后的卡片退出路径由对应的新场景覆盖。
