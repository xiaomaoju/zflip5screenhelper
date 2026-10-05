# 侧栏实际回弹与 Dock 双端展开 · 2026-10-01

验证包：[flipcover-controls-0.17.3-launcher-edge-slots-debug.apk](flipcover-controls-0.17.3-launcher-edge-slots-debug.apk)，版本0.17.3（53），包名 `io.github.flipcover.controls.debug`。

SHA-256：`959a3d9dc5eebaafa7616e884a7511cd95c11122c804fc6c5c6ea12ceea971e4`。

已通过 `adb install -r` 覆盖安装到连接的三星 SM-F731U1，返回 `Success`，配置保留；系统包更新时间2026-10-01 18:33:59，`CoverService` 已重新绑定。物理手机只安装并读取包信息与服务状态，没有运行会修改偏好的 Instrumentation。

## 本次修正

- 侧栏原来的受力模型正常，但 STACK 玻璃 ViewGroup 的硬件显示列表跳过 `draw()`，导致 Canvas 平移未执行。先对旧 APK 加入硬件截图检查，复现为弹簧位置6.338dp、图标像素移动0；修正后同一手势图标实际移动13px，松手回零并停帧。平移移至 `dispatchDraw()`，玻璃和内容一起跟手。顶部组、文件夹预览与成员容器也显式启用绘制，避免同类问题。
- Dock 初始玻璃轮廓收回左右实际工具各24dp空间，只包住居中的应用核心，核心先轻微弹性拉伸。一个660ms时钟约297ms开始展开左端、350ms展开右端；轮廓补回空间时，九宫格从核心向左带出，清理从核心向右带出，配合缩放与淡入。取消原来的上移弹出。
- 动态圆角裁切与玻璃取样使用同一收缩边界，Dock 由自身绘制路径裁切，避免硬件静态轮廓再次截掉平移后的边缘。没有最近任务时不创建右侧清理槽位，只有左端收缩时也保持应用核心居中。
- 受力仍为八节点、八连接，Dock 仍仅一个入场时钟；复用 Path、RectF 和现有材质矩阵。测量及最终触摸槽位不变，不逐帧重排布局、重读偏好或计算清理目标。两端动作完整出现后启用，隐藏和卸载取消时钟。

## 本地检查

Instrumentation 全部运行于可丢弃 Android 36、720×748、340dpi 模拟器；它们不是三星物理外屏手感或帧率验收。

| 检查 | 结果 |
| --- | --- |
| debug / AndroidTest 构建 | PASS |
| JVM 单元测试 | 158项，失败0、错误0 |
| lintDebug | PASS，0错误、176警告 |
| `git diff --check` | PASS |
| `launcher-force` | PASS，20项，含硬件图标像素位移、按住平衡停帧、上下边界、松手回零、取消与卸载 |
| `launcher-polish` | PASS，27项，含实际绘制宽度收回两端空间、左右运动方向、单端核心居中、硬件绘制标志、玻璃及紧凑弹窗 |
| `interface-card` | PASS，1155项 |
| `hub-motion` | PASS，43项 |
| `app-folders` | PASS，88项 |
| `workspace-dock-menu` | PASS，70项 |
| `launcher-widget` | PASS，827项 |
| `hub-performance` | PASS，95项 |

证据保存在 `Cache/tests/launcher-edge-slots/`；旧包的像素失败见 `baseline-force.log`，修复后的像素结果见 `launcher-force.log`。Dock 绘制宽度检查直接绘制当前 View 到透明位图，并检查实际不透明区域；侧栏像素检查使用系统硬件截图。`folder-raw.png` 为模拟器原始验证素材，仅检查布局与材质，未作为最终效果图交付。

物理手机上的连续拉动、GPU 帧耗时与功耗尚需实际操作确认，本地停帧和生命周期检查不等于真机流畅度测量。前次报告保留历史结果；旧 `motion-continuity` 与 `standalone-dock` 完整场景仍没有标为通过，本次未重写其旧归属假设，相关当前行为由卡片和新的 Dock 场景覆盖。
