# Liquid Glass 张力组件

`com.kyant.backdrop.catalog.components.LiquidTensionSurface` 是 Backdrop 2.0.1 的项目扩展，通过公开的 `runtimeShaderEffect` 和库内着色器缓存融合一组表面。`LiquidTensionRenderer` 为原生 Canvas 提供同一效果；不修改第三方依赖或原版 Tab。

## 通知交互

一个按钮只分裂一次；两个按钮按左滑露出顺序，先清除、后设置。第一处分裂完成后才开始第二处，正常展开停点两者都完全脱离，回滑按相反顺序融合。按钮与卡片共用一张连续材质；完整图标位于表面之上，尚未开始分裂的图标隐藏，活动图标整枚淡入，不沿卡片边缘切割。

按钮不再固定等待卡片离开：初始圆面在停靠点左侧约10dp，先少量向左黏连跟随，再随拉动向右挣脱。实际边缘间距超过断链距离时保留向右速度，越过停靠点最多约2.5dp后弹回，给右侧轮廓与抗锯齿留出余量。前一按钮断链后才解锁后一按钮；快速跨过多个槽也逐帧解锁，不同时释放。回拉保留当前位置和速度再向卡片靠拢；同向持拉时用安全间距避免自身回弹重新接上液颈。最终圆面、图标、槽和触摸范围恢复原规格。

两按钮完全展开后，继续左拉的弹性位移超过约10dp才开始合并，约24dp达到删除距离目标（窄布局按可用弹性行程缩小阈值）。两个按钮向共同中心移动，设置图标逐渐淡出。实际两按钮依次断链并完成合并时，立即复用原有清除回调，锁住合并位置；两按钮按180ms线性插值淡出，不等待松手，也不回到两按钮展开位置。图标与共享玻璃圆面使用相同透明度，圆面尺寸仍为28.8dp。系统确认移除后卡片从当前姿态继续向外退出。未收到系统确认的请求在1200ms后恢复原行，不能把请求当成成功删除；通知身份变化同样中止旧请求的视觉状态。阈值以内反拉、取消、多指和尺寸变化仍可取消合并，惯性回弹不能触发清除。单按钮持续通知不启用该操作。

合并要求两个运动节点均已断开且接近停靠点，进度最多用约143ms追上目标，反拉可连续分开。快拉到最大距离仍保持先后断链；帧回调到达实际完整合并时就提交，不再等UP。提交后UP、反拉及后续帧均不能再次清除或重新分裂。普通按钮运动期间不接受尚未停靠的动作点击或无障碍动作，停靠后恢复原槽的点击语义。

清理渐隐只在已经完全断开的动作区域使用短时透明离屏层，同一组轮廓与内容按主体/动作两个不相交区域裁切绘制，图标与圆面共用一次透明度；卡片材质保持正常。该180ms阶段的GPU额外成本未单独测量，不能用普通分裂的稳态基准代替。

通知顶栏与侧滑动作共用控制中心 `PanelUi.SLOT` 的36dp槽、`ACTION_SCALE=.8` 的28.8dp圆面及16dp可见图标；相邻槽不另加边距，因此圆面间距为7.2dp。带计数的顶栏清除胶囊按内容确定宽度，保留相同高度和内缩比例。

## 复用接口

- `LiquidTensionMotion` 是不依赖Android或Compose的运动状态，适合横向等间距动作槽，最多七个动作节点。按最先露出顺序编号，使用 `configure(count, spacing, travel, restingGap, breakGap, overshoot)` 提供像素几何；`drive(revealDistance, outwardSpeed)` 更新拖动目标，`advance(seconds)` 用有界小步积分推进。`position(index)`、`velocity(index)` 和 `detached(index)` 投影到宿主表面；`ready()` 表示全部分离并接近停靠，业务阈值与点击仍由宿主管理。
- Motion不拥有时钟、View、材质、通知身份或清理动作；宿主在 `moving()` 或自己的合并进度未收稳时安排下一帧，取消、尺寸变化和卸载用 `snap(chosenDistance)` 清除旧速度并停止时钟。Compose宿主用帧时钟更新可观察状态，原生宿主使用一组共享 `postOnAnimation` 回调；不要为每个按钮创建永久动画。当前通知只使用一至两个节点，其他数量与宿主需要自行验证。
- `SharedGlassHost.glassOffsetX/Y` 提供宿主在Canvas绘制阶段施加的额外整体位移，默认0；独立底色与组轮廓取样共用该偏移。通知适配侧向受力和列表边界位移，共享绘制层无需识别通知行类型。

- `LiquidTensionGeometry` 使用组局部像素坐标。先 `reset()`，再按视觉顺序 `add(left, top, right, bottom, radius)`；最多八个表面，通知仅用两至三个。
- `connectionRange` 是平滑融合范围，三次平滑并集在对称连接处的最大空隙约为范围的三分之一。通知上限48dp，重叠期采用较宽过渡，接近断开时按正常停点空隙收细；按钮间距限制过渡上限，避免已分离按钮被重新连回。
- `splitHorizontallyInOrder(separationMargin, completionGap)`：形状0是移动卡片，按钮按左至右登记；从最右侧按钮开始，每帧至多一个活动连接。`completionGap` 传最后一按钮与正常停点卡片之间的实际空隙，确保结束时断开。通知分离余量上限2dp。
- `mergePair(first, second, progress, maximumRange)`：显式选择任意两个已登记表面，其他表面保持独立；支持只有两个按钮、没有卡片。宿主负责两者的位置，本函数控制液颈强度。内部使用缓存数组重排GPU输入，调用方的索引和几何数组顺序保持不变。`mergeLastPair` 保留为最后两个表面的便捷入口。通知使用索引1和2、最大融合范围16dp；清理阈值与动作仍属于通知宿主。
- `bevel`、`refraction` 为边缘宽度及折射上限；通知分别22dp、18.7dp，着色器按各表面实际半径与高度收窄。`edgeFade` 保持列表左侧渐隐。
- `LiquidTensionStyle` 是不可变材质参数：内部和边缘亮度、染色、亮度上限、高光、边缘阴影和降级颜色。`Default` 保留通知的既有参数和常量着色器路径；自定义样式使用另一缓存程序，最多保留默认与自定义两种程序。可通过 `copy(...)` 定制其他按钮组。
- Compose 使用 `LiquidTensionSurface(backdrop, geometry, modifier, style, enabled) { /* 独立按钮内容 */ }`，也可调用 `liquidTension(geometry, style)`。`geometry` 回调中读取驱动位置的Compose状态，再填充复用的几何对象；单独修改普通FloatArray不会主动触发重绘。组件在绘制阶段显式观察回调读取的状态，保证连续更新会重绘；回调只读取状态并填充几何，应可重复调用，不能执行点击或网络等副作用。每组只绘一次背景，布局尽量贴合组的可见范围。`enabled=false` 或系统不支持RuntimeShader时，按各节点绘制独立圆角底色，保留内容与点击，避免退化成整块矩形背板。
- 原生使用 `LiquidTensionRenderer.draw(canvas, width, height, geometry, sourceShader, localToSourceMatrix, style)`，style可省略；矩阵为九元素 Android Matrix 数组。传入会话已有纹理，未提供时采用深色材质；软件 Canvas 或 Android 13以下返回false，保留宿主原表面。
- `bounds(out)` 提供包含平滑并集扩展和抗锯齿余量的保守包围框，可用于宿主布局及剔除屏外组。原生Renderer保留原绘制矩形以保证默认像素精度；空组或完全屏外组返回false，单表面也可绘制。`prepare()` 可在主线程空闲时提前创建着色器对象，绘制时仍保留惰性准备。
- Renderer 在会话内缓存，卸载调用 `clear()`；Compose 缓存随 Modifier 卸载释放。两者均不拥有背景位图、不取截图、不创建后台动画。

`NotificationSwipeRow` 汇总卡片变形及按钮移动后的边界；`PanelGlassSession` 借出已有Gaussian纹理。原生宿主通过 `SharedGlassHost` 提供主体、动作表面、会话挂接和共享绘制状态。`GlassSurface` 在绑定时缓存通用宿主引用，不在绘制时遍历祖先，也不依赖具体通知行类型。通知行缓存动作表面引用，不逐帧按tag查找。`GlassSurface` 在共享材质启用期间持续停绘独立背景，模式切换时让子视图缓存失效，避免原圆边和不同底色重新叠入。轮廓使用三次平滑并集，使连接过渡的二阶导数连续；重叠期扩大过渡范围，收细时使用端点导数连续的插值。明暗保留融合梯度的实际幅度，不把液颈中央趋近零的梯度强行单位化。折射坡度在更宽的连续场中计算，防止细颈两侧的取样方向突然翻转；它与轮廓在同一组循环内求出，不增加背景取样或位图，也不折射文字和图标。宿主将卡片内容先绘、动作图标后绘，保留既有点击与无障碍动作。

## 性能评估（组件优化前的已确认版本）

2026-10-01，Android ARM64可丢弃模拟器，720×748、340dpi、60Hz。真实通知行和两个按钮连续移动，预热后以 baseline、tension、tension、baseline 顺序各采样3秒，共724帧。两种模式内容和背景会话相同，baseline恢复独立玻璃背景。数据由 Window FrameMetrics记录。

| 模式 | 总帧耗时中位数 | CPU绘制中位数 | GPU耗时中位数 | 总帧耗时P90 |
| --- | ---: | ---: | ---: | ---: |
| baseline A（181帧） | 17.496ms | 0.110ms | 13.734ms | 18.295ms |
| tension A（181帧） | 17.336ms | 0.140ms | 13.740ms | 18.162ms |
| tension B（181帧） | 17.055ms | 0.102ms | 14.079ms | 18.101ms |
| baseline B（181帧） | 17.076ms | 0.090ms | 13.950ms | 17.635ms |

两组平均CPU绘制中位数增加约0.021ms/帧；GPU中位数差约0.068ms，量级小于模拟器帧时波动，不据此承诺真机帧率。纹理字节数全程不变，fixture取样0次、预处理1次，无新增位图。轮廓与折射坡度采用解析计算，共用同一个形状循环。通知同一时刻只允许一行展开，静止后无额外帧循环。

当前两至三个表面的场景适合使用，可供其他紧凑按钮组复用。八表面为容量边界，并非实测性能承诺；多组同时运行或大面积接入需要重新测量。模拟器不证明三星GPU功耗、温度及物理外屏触摸手感。

## 验证入口

构建与测试见README。`liquid-tension` 覆盖顺序分裂、正反向像素连通区域、正常停点完全分离、完整活动图标、子视图缓存重绘无接缝、合并清理与取消、纹理复用、性能和释放。`notification-swipe`、`notification-center`、`notification-glass`覆盖原有手势、通知更新、删除及尺寸一致性。本轮证据位于 `Cache/tests/liquid-tension-curvature/`，前轮手势与尺寸证据保留在 `Cache/tests/liquid-tension-repair/`；交付说明与带标准机身贴图的效果图位于 `dist/`。

上游接口依据：[Backdrop自定义效果](https://github.com/Kyant0/AndroidLiquidGlass/blob/kmp/backdrop/src/commonMain/kotlin/com/kyant/backdrop/effects/RenderEffect.kt)。普通 `lens()` 只支持圆角矩形，因此融合形状使用库的自定义效果扩展。

### 曲率与明暗回归

`liquid-tension-curvature` 在相同几何下采样5个连接距离，每个距离分别使用平坦深色和高反差渐变背景，读取实际GPU截图中液颈中央相邻像素的最大通道差。旧版先实测失败：深色最大12/255，高反差最大202/255。最终版本分别降为2/255和4/255，10个样本全部通过；测试门限保持18/255，没有通过放宽门限掩盖旧问题。该指标是此固定图案的空间连续性回归，不代表任意背景必须同色。

前一轮低反差背景及连通区域检查不足以发现这个折射断层；保留 `before.log` 和旧版截图作为失败证据。曲率修复阶段通过原有93项张力、手势与释放断言。三次平滑并集原理见 [Iñigo Quílez：Smooth Minimum](https://iquilezles.org/articles/smin/)。

## 两个独立按钮的接入示例

在Compose宿主中复用一个几何对象；`mergeProgress` 由宿主状态提供，取值0到1。下面只定义背景，内容槽继续放宿主自己的图标、点击和无障碍语义。

```kotlin
val group = remember { LiquidTensionGeometry() }
val density = LocalDensity.current.density
LiquidTensionSurface(
    backdrop = backdrop,
    geometry = {
        val amount = mergeProgress()
        val radius = 14.4f * density
        val y = 18f * density
        val first = (18f + 18f * amount) * density
        val second = (54f - 18f * amount) * density
        group.reset()
        group.add(first - radius, y - radius, first + radius, y + radius, radius)
        group.add(second - radius, y - radius, second + radius, y + radius, radius)
        group.mergePair(0, 1, amount, 16f * density)
        group
    },
    modifier = Modifier.size(72.dp, 36.dp),
    style = LiquidTensionStyle.Default
) {
    // 按同一位置投影宿主内容；保留独立点击语义。
}
```

原生宿主在UI线程填充几何并调用Renderer，复用同一实例及源纹理；关闭时释放Renderer。使用现有运行时玻璃会话的宿主实现 `SharedGlassHost`，显式提供主体与动作表面引用，不需要通知专用tag。组件不会发起清理、启动应用或申请权限。

## 组件优化的验证范围

原生路径缓存宿主和动作表面引用，去掉逐帧查找；包围框用于剔除完全屏外的组。组绘制矩形及渐隐离屏层保持原来的范围，以保留已确认的GPU像素输出。`PanelGlassSession` 在主线程空闲时尝试准备着色器；清除宿主或关闭会话会移除待执行回调，立即开始拖动仍可走惰性准备。这减少首滑阶段的对象准备工作，但不等同于完成GPU首次管线编译，不能据此宣称首帧耗时已经降低。

`liquid-tension-component` 使用独立Compose宿主，覆盖没有卡片的双按钮合并、非相邻登记索引的合并、材质状态变化、显式降级、独立点击、尺寸变化、卸载，以及空闲准备与取消。显式降级检查走与低版本相同的分支，不代替真实Android 12或软件Compose环境验证。优化前后固定背景截图及独立检查日志保存在 `Cache/tests/liquid-tension-optimization/`。

### 运动组件与最终独立验证

2026-10-01，独立验证者对固定源码快照和APK完成：运动48、Compose12、张力102、侧滑152、通知玻璃15及静态合并3项断言；曲率10个样本全部通过，最大相邻通道差4/255。142项单元测试通过，包括运动的6项纯数值测试；Lint零错误、171条警告。`liquid-tension-motion` 检查实际View位置和GPU图，不仅检查运动公式；取样阶段保持第一、第二按钮各24dp本地进度，右移大于4dp和前一液颈完全断开的门限不变。

默认平坦、高反差及明确停稳合并状态与已批准版本的材质内容区最大像素差均为0。`liquid-tension-static-merge` 在两个版本上执行相同持拉、900ms等待和波纹结束操作，卡片位置与合并进度相同。此前短延时合并截图仍含按压波纹，与新增运动等待后的状态不同；原失败图和日志保留，不放宽像素门限。证据及独立结论为 `Cache/tests/liquid-tension-motion/verify/REPORT-final.md`。

运动状态复用固定数组，无逐帧对象或位图分配；原生通知每组一个按需帧回调，静止、取消、隐藏及卸载停止。已有纹理全程复用，无新增截图。本轮多模拟器并行负载使FrameMetrics波动明显，不将其用于提速或退化归因；真实手势GPU成本、三星物理触感、首帧和功耗仍需要真机测量。

### 通知联动联合版本

最终联合冻结版本的SHA256为 `05a1e21418e19ccc7be009f9c1580751e225ba492b50c0d2e49e87d072842420`。独立验证完成运动48、即时清理41、张力117、Compose12、曲率10、通知受力网络30、通知中心302、侧滑152、通知玻璃15、静态合并3、边界惯性20、通知受力10、通知回归27项检查。146项单元测试通过，Lint零错误、171条警告。

GPU截图实测圆面与图标在透明度1、0.5和0同步消失，卡片像素保持不变；实际合并完成后持拉即提交一次，确认移除继续向外退场，未确认可恢复。平坦、高反差、停稳合并三个默认材质对照最大像素差均为0。该联合版本保留通知整体联动与顺序分裂惯性，覆盖此前分开安装的两个版本；独立报告在 `Cache/tests/notification-liquid-combined/verify/REPORT.md`，交付与安装记录见 [联合版本验证说明](../dist/开发设计/VALIDATION-0.17.3-notification-liquid-combined.md)。模拟器证据不替代三星物理触感或功耗测量。

## 启动器侧栏复用

`LiquidTensionGeometry.splitVerticallyInOrder` 与横向分裂共用顺序、断开距离及融合计算，仅切换几何轴。`LauncherSidebarView` 提供主体和设置表面，动画只填充一组几何；初始单轮廓包住设置，随后按纵向两节点分裂。`SharedGlassHost.glassBodyRole` 默认保持通知卡片角色，启动器显式使用 `LAUNCHER_PANEL`；`PanelGlassSession.drawTension` 可选已有强模糊纹理，并支持初始单节点绘制。每个会话仍只有一组背景纹理，卸载释放Renderer及着色器引用。

侧栏设置图标起初以完整20dp规格显示在主体底部，圆面完全融入主体。`LauncherSidebarView.settingTranslationY` 以同一连续进度将圆面和图标向下移动，液颈随真实间距变细，末段断开后停在既有32dp独立槽位。移动仅作用于Canvas，实际输入视图不缩放或移动。内容渐隐边界随图标让出底部空间，分离后恢复完整列表；已有下滑收起的圆面适配仍同步缩放圆面和图标。固定入场估计900ms加停留100ms后执行659ms动作，随后在同一个Animator内用300ms渐入高光，不依赖区域弹簧是否完全归零。通知仍使用`LiquidTensionMotion.circleScale`的20%到100%缩放，父级操作槽及无障碍目标保持固定，后一个节点等待前一个断开，回滑反向缩回。两处均复用已有纹理，无新增取样、位图或持续帧循环。

## 通知与控制中心顶栏

`PanelActionHeader` 是 `ControlHeaderView` 与 `NotificationForceHeader` 的共用绘制层。最右按钮保持原位置，其余按钮按相邻顺序从右往左拉出；一个按钮完成后，下一个才开始。通知页圆面与完整图标同步从62%平滑放大到100%，图标整枚淡入；控制页保持原有缩放规格。尚未开始的图标隐藏，最终圆按钮及通知清除计数胶囊规格保持原样。标题不参与分裂。这里的左右是当前界面的横向坐标，不镜像显示器缺口。

`InterfaceCard.enter` 把实际滑入进度同步给顶栏，进度达到1的当帧启动分裂；没有固定入场估计、停留或延迟回调。玻璃和布局若晚于入场完成，则在它们可用时直接启动。顶栏每段为507ms（659ms ÷ 1.3，速度增加30%），不修改启动器侧栏的移动节奏。两按钮移动共507ms，三按钮共1014ms。移动结束后，沿同一有界Animator用300ms平滑过渡到原独立按钮材质及高光，图标和格位已停稳；材质透明度直接进入填充和边缘描边，避免淡入末帧切换绘制层产生跳变。

通过 `LiquidTensionGeometry.mergePair` 逐段融合当前相邻表面，使用本次 `PanelGlassSession` 已有纹理，不新增截图或后台工作。通知顶栏受力偏移和控制中心绘制矩阵进入几何及材质坐标。高光过渡期间共用轮廓短时使用透明绘制层，结束后撤掉组绘制，恢复原按钮材质和按压反馈，普通内容更新不重复播放；稳定后没有持续帧循环。

输入槽、标题、字号和静止位置保持原样。用户触摸顶栏先结束入场，再执行原操作；尺寸变化、隐藏、卸载、关闭系统动画和纹理不可用时结束分裂，恢复既有按钮。无玻璃、低版本或软件Canvas直接保留独立按钮。验证入口为设备端 `panel-header-split`，仅用于可丢弃模拟器；当前构建及验证交付见 `dist/开发设计/VALIDATION-0.17.3-highlight-level-300.md`。

## 分裂高光的统一时长

`LiquidTensionGeometry.HIGHLIGHT_DURATION_MS` 统一为300ms。通知与控制中心顶栏使用该时长交接既有材质；启动器侧栏与通知侧滑按钮持续使用共享材质，通过 `highlight` 参数仅渐入现有边缘高光，完成值1保持原有静止外观。通知侧滑在动作节点分离停稳后启动一个有界Animator，高光时钟不阻止既有点击或无障碍操作；反向移动、取消、关闭、卸载及纹理释放停止旧动画。默认不参与分裂的共享材质仍取1，不增加截图、位图或后台轮询。
