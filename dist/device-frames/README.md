# Z Flip5 外屏镂空贴图

- `zflip5-cover-overlay.png`：836 × 992，RGBA 透明贴图，直接叠在 UI 上方。
- `zflip5-cover-overlay@2x.png`：1672 × 1984，二倍分辨率。
- `zflip5-cover-overlay.svg`：几何与颜色的唯一维护源；PNG 和 HTML 预览由它导出。
- `zflip5-cover-calibrated.svg/.png`：由上述标准资源生成的同内容兼容副本，供已有投屏入口使用，不单独修改。
- `../html/device-frame-preview.html`：独立浏览器预览，双击打开即可；棋盘格用于展示透明开孔，不包含在贴图中。

贴图仅包含外框、右下双摄和闪光灯。中间异形屏幕与机身外侧的 Alpha 为 0，边缘保留抗锯齿；没有黑色屏幕底图，也没有烘焙棋盘格。

## 参数来源

屏幕像素、圆角与缺口安全边距来自连接的 Z Flip5；铰链、较厚的下沿、双摄和闪光灯按用户参考照片修正。详细来源与精度边界见 [CALIBRATION.md](CALIBRATION.md)。机身轮廓和缺口过渡弧线是外观示意，不是工业工程图，也不能替代真机 DisplayCutout、WindowInsets 和窗口路由验证。

SVG 的 `data-screen="44 98 748 720"` 保存自然方向屏幕映射矩形。屏幕开孔以 SVG 遮罩路径为准，不能只用矩形或旧版百分比多边形裁剪。整机宽为 440 CSS px 时，高为 522.10526316 px；所有坐标均按 440/836 等比缩放。

| 顺时针角度 | 画布宽×高 | 屏幕矩形 x, y, width, height |
| --- | --- | --- |
| 0° | 836×992 | 44, 98, 748, 720 |
| 90° | 992×836 | 174, 44, 720, 748 |
| 180° | 836×992 | 44, 174, 748, 720 |
| 270° | 992×836 | 98, 44, 720, 748 |

## 效果图合成

1. 使用未带机身外框的 UI 原始截图。836×992 贴图对应的内部屏幕矩形为 `x=44, y=98, width=748, height=720`。
2. 将 UI 等比缩放并居中放入该矩形，按 SVG 的屏幕开孔裁剪；余隙可用 UI 背景色补齐，不拉伸文字和图标。机身外侧保留透明或使用展示页背景。
3. 最上层叠加贴图一次。相机区由贴图遮挡，UI 不得覆盖镜头、闪光灯和边框。已有外框的 HTML 原型应以此贴图替换外框，不能再套第二层。
4. 旋转展示时，屏幕开孔和贴图同步旋转，90°/270° 后画布为 992×836；不得通过镜像改变摄像头位置。
5. 检查圆角、底部斜边与摄像头遮挡，并将合成结果保存到 `dist/` 的对应 UI 目录，使用 `-framed.png` 后缀与原始截图区分。原始截图仍用于原生组件验证，不能当作最终效果图交付。

浏览器预览不依赖网络、外部字体、Codex iframe 或本地服务，既可通过 `file://` 打开，也可放在普通静态网页服务器上访问。

## 更新资源与原型

在项目根目录执行（需要 Node.js 和 npm）：

```sh
npm install --prefix tools/device-frames
node tools/device-frames/build.mjs
node tools/device-frames/compose.mjs path/to/raw.png path/to/result-framed.png
```

`build.mjs` 从唯一 SVG 源生成 PNG、兼容副本、预览，并同步教程、设置和 NFC／热点原型的机身尺寸与精确开孔。教程两个文件保持相同内容，三个标准贴图同时复制到 `dist/html/`，用于完整静态部署，发布副本不单独编辑。`compose.mjs` 可输出 PNG 或自包含 SVG，原图等比居中，空余位置填黑，最上层只叠一份标准外框。依赖版本在 `tools/device-frames/package.json` 中声明。

本次替换范围和验证见 [FRAME-ROLLOUT.md](FRAME-ROLLOUT.md)。
