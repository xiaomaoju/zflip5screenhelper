# Dock 独立圆面液态吸合 · 2026-10-01

安装包：[flipcover-controls-0.17.3-dock-liquid-fusion-debug.apk](flipcover-controls-0.17.3-dock-liquid-fusion-debug.apk)，版本0.17.3（53），包名 `io.github.flipcover.controls.debug`。

SHA-256：`4f1df4509a8dccf24ac7777d43207ac726863cdb2236cfb14573b33be43bbfdc`。

已通过 `adb install -r` 覆盖安装到连接的三星 SM-F731U1，返回 `Success`，原配置保留；包更新时间2026-10-01 20:52:57，`CoverService` 已重新绑定。物理手机仅安装和读取状态，没有运行会修改配置的测试。

## 行为

九宫格、应用胶囊、清理起初为三个独立玻璃表面，应用胶囊仅包住应用，不绘制两端预留空位。既有入场估算900ms之后再等300ms，随后使用一个560ms时钟扩宽胶囊，同时让两端图标向中间非线性移动。两处液颈变宽并融入胶囊，最终恢复原有工具槽位、图标大小及padding；中间应用保持最终锚点。

`AppDockView` 直接实现共享玻璃宿主，复用 `LiquidTensionGeometry` 与既有背景纹理。三个轮廓在同一材质中绘制，结束收敛为一个轮廓，不切换材质。融合末段仅收小已被胶囊包住的圆面轮廓，消除残余边缘和最终切换闪烁；图标不参与缩放或淡出。Dock 色调沿用原增益及染色参数，其他组件保留默认材质。

静止几何与内容规则仍由共享 `AppLauncherStyle`、`AppDockLayout` 管理。暂时圆面直径22–28dp，间距至多4dp，按宿主余量计算；最终图标、四边padding、行高、容量及应用间距保持不变。绘制可越过行内padding，但仍受宿主边界限制，避免外移时截掉九宫格。只有左端时不创建右侧空位；没有应用核心时显示完整入口。

仍为八个区域受力点、八条连接，不增加逐图标弹簧。等待只有一个定时回调，吸合只有一个动画时钟；复用几何数组、矩形和Paint，不逐帧重测、读取偏好或计算清理目标。提前点击工具会完成装饰后立即执行，隐藏、取消、多指、尺寸变化及卸载取消等待和时钟。原生RemoteViews继续使用共享最终布局，不接入浮窗吸合。

## 本地验证

验证APK已固定，Instrumentation全部运行于可丢弃Android36模拟器，720×748、340dpi。

| 检查 | 结果 |
| --- | --- |
| debug / AndroidTest 构建 | PASS |
| JVM 单元测试 | 159项，失败0、错误0 |
| lintDebug | PASS，0错误、176警告 |
| `git diff --check` | PASS |
| `dock-fusion` | PASS，249项，实际GPU三表面到单表面连通性、双向内移、56段图标像素尺寸、全padding、固定应用锚点、等待及释放 |
| `launcher-polish` | PASS，364项，包含应用实际绘制锚点、文件夹材质与弹窗 |
| `launcher-force` | PASS，20项，侧栏实际图标移动13px并释放停帧 |
| `interface-card` | PASS，1162项 |
| `hub-motion` | PASS，43项 |
| `dock-pin-placement` | PASS，138项 |
| `launcher-widget` | PASS，827项，真实Android宿主与共享静止几何 |
| `hub-performance` | PASS，95项，重复打开与订阅释放 |
| `liquid-tension-component` | PASS，12项，原默认材质、Compose、合并及释放 |

白色及高反差纹理均比较吸合进度0.999与最终单轮廓的GPU截图，最大通道差门限8/255保持通过。图标像素包围框允许水平亚像素抗锯齿造成的1px差异，实际高度、View尺寸及所有padding保持不变；旧裁切缺陷产生明显宽度差，未通过扩大门限掩盖。

证据在 `Cache/tests/dock-liquid-fusion/`。初次绘制发现九宫格被行内padding裁切，保留失败记录；修复后完整显示九个点。初次轮廓收敛检测到47/255的边缘差异，加入末段圆面吸收后白色和高反差检查通过。卡片回归一度被另一次APK安装结束，系统退出原因确认为PACKAGE_UPDATED；固定包重跑通过。原生卡片夹具在模拟显示器移除后仍使用旧入口，恢复实际可见卡片并刷新RemoteViews后，真实PendingIntent与短Toast的原断言通过，未改动原生业务逻辑。

原始截图仅为验证素材，保存在Cache，未作为未合成标准机身贴图的最终效果图交付。模拟器检查和安装不代替三星外屏的触摸手感、GPU帧耗时及功耗实测。历史 `motion-continuity` 与 `standalone-dock` 旧完整场景仍未标为通过，当前卡片及Dock行为由本轮对应场景覆盖。
