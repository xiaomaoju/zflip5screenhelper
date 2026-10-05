# 校准外框统一替换 · 2026-09-30

`zflip5-cover-overlay.svg` 统一为此前按真机参数与用户照片修正的外屏框：836×992，屏幕映射矩形 `(44,98,748,720)`。PNG、二倍 PNG 和 calibrated 兼容副本从它生成；不再维护两套外观。

## 已更新的入口

| 页面 | 更新内容 |
| --- | --- |
| `../html/flipcover-tutorial.html` | 内嵌标准框、屏幕开孔、四方向坐标、整机缩放 |
| `../html/flipcover-prototype.html` | 与教程逐字相同的兼容入口 |
| `../html/settings-oneui-prototype.html` | 引用标准 SVG；同步机身比例与屏幕开孔 |
| `../html/nfc-hotspot-prototype.html` | 两台设备均使用内嵌标准框；同步开孔与缩放 |
| `../html/device-frame-preview.html` | 透明开孔、底色切换和资源下载预览 |

设置原型使用随文件交付的相对路径资源；其他页面内嵌外框。全部可用普通浏览器直接打开，不依赖本机服务。`dist/html/index.html` 只提供跳转入口，没有独立机型图。

投屏打包脚本也读取标准 PNG；包内兼容资源名不变。已安装投屏框的外观与该标准一致，本次没有重启投屏或改变手机显示设置。

## 静态效果图

共更新40个带框文件，单机图为836×992，SVG保持自包含；对比图和画廊保留各自多机展示布局。

| 目录 | 文件数 |
| --- | ---: |
| `dist/0.7.2-ui/` | 6 |
| `dist/0.7.3-ui/` | 4 |
| `dist/0.9.0-ui/`、`0.9.1-ui/`、`0.9.2-ui/` | 6 |
| `dist/runtime-ui-0.13.0/` | 4 |
| `dist/control-grid-editor/` | 3 |
| `dist/control-center-editor/` | 3 |
| `dist/settings-oneui-ui/` | 4 |
| `dist/notification-glass-trial/` | 9 |
| `dist/nfc-hotspot-ui/` | 1 |

优先使用原始 UI 截图或旧 SVG 内嵌的原始 UI。仅旧 NFC 总览与玻璃 V2 对比图缺少独立原图，取原有图的屏幕区域、去掉旧开孔外像素后再合成，因此其内容保留原有分辨率与裁切范围。所有内容等比缩放，空余处填黑，新外框在最上层叠一次。历史原始截图未改动，历史模拟器与真机验证结论不因换框升级。

## 验证

- Safari 通过实际 `file://` 打开教程、设置、NFC／热点和透明预览。教程正向及90°横向显示检查通过；设置首页进入外观和状态栏子页、热点进入连接设备子页正常。兼容教程内容与教程完全一致。
- 五个 HTML 的 JavaScript 语法检查通过，屏幕裁切定义各只有一组；内嵌或相对引用均指向标准 SVG。PNG尺寸与透明开孔检查通过，calibrated兼容副本逐字一致。
- 重新执行资源构建后，50个资源输出保持逐字一致。脚本依赖已声明并锁定，`npm ci --prefix tools/device-frames` 可安装，`node tools/device-frames/build.mjs` 成功。
- `:app:assembleDebug :app:testDebugUnitTest :app:lintDebug` 成功；本次未改 Android 源码。scrcpy 打包脚本语法检查通过。
- 本次是项目文件与本地浏览器验证；没有新增物理手机安全区验收，也没有发布线上网站。

本地替换清单、原版备份、资源校验和构建日志位于 `Cache/tests/frame-rollout/`，是本次可丢弃验证资料。日后常规更新使用 [贴图说明](README.md) 中的构建和合成入口。
