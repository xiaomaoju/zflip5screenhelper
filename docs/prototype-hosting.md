# 教程静态网站部署

## 上传入口与文件

`dist/html/` 是完整的静态网站交付目录。它包含10份 HTML 与3份设备贴图，所有文件平铺在同一层，没有子目录，也不引用该目录外的文件。样式、脚本、图标及二维码生成代码已内嵌，无需 Node.js、PHP、数据库或前端构建步骤。

将 `dist/html/` 内的全部13个文件上传到服务器的同一公开目录，保留文件名，不上传上级 `dist/`。入口为 `index.html`（页面目录）和 `flipcover-tutorial.html`（互动教程）；即使服务器没有默认首页规则，也可以直接访问完整文件地址。

| 文件 | 内容 |
| --- | --- |
| `index.html` | 全部页面入口 |
| `flipcover-tutorial.html` | 互动上手教程，打开自动快速演示 |
| `flipcover-prototype.html` | 与教程逐字一致的兼容入口 |
| `settings-oneui-prototype.html` | 独立设置交互原型 |
| `nfc-hotspot-prototype.html` | NFC、移动热点及二维码练习 |
| `notification-force-jelly.html` | 通知动效 |
| `control-force-jelly.html` | 控制中心动效 |
| `task-force-jelly.html` | 任务页动效 |
| `launcher-force-jelly.html` | 启动器与 Dock 动效 |
| `device-frame-preview.html` | 外框预览、底色切换及下载 |
| `zflip5-cover-overlay.svg` | 标准外框 SVG 副本 |
| `zflip5-cover-overlay.png` | 标准透明 PNG 副本 |
| `zflip5-cover-overlay@2x.png` | 二倍透明 PNG 副本 |

设备贴图的唯一维护源仍为 `dist/device-frames/zflip5-cover-overlay.svg`；运行 `node tools/device-frames/build.mjs` 会生成 PNG 并同步 `dist/html/` 的三个发布副本，不单独编辑这些副本。

## TEngineHttp 教程附件

按“更新项目类型并规划 HTML 分发”对话中确认的方案，在 APP 更新项目版本的“教程附件”中多选上传上述13个文件。附件独立于 APK 和 catalog，可单独更新；首版不支持子目录，本目录已按此限制整理。

例如项目为 `ZFlip5`，项目版本为 `v1`：

```text
https://example.com/appversion/v1/ZFlip5/docs/index.html
https://example.com/appversion/v1/ZFlip5/docs/flipcover-tutorial.html
```

实际域名、项目名、项目版本及编码以服务器“复制地址”返回的结果为准。页面间相对链接会自动跟随当前目录，不需要修改 HTML 中的域名，也可部署到任意其他 HTTP/HTTPS 静态目录。

服务器必须返回 HTML 内容类型 `text/html; charset=utf-8`，在浏览器内展示，而非强制附件下载；图片需返回对应的 PNG/SVG 内容类型。服务器策略要允许内嵌脚本和样式、同目录图片、教程中的 iframe、受沙箱约束的新窗口和下载。客户端 HTML 无法取消服务器施加的响应头限制。

教程仅在允许存储时保存学习步骤；TEngineHttp 计划采用不保留同源权限的沙箱，此时自动使用当前页面内存中的进度，刷新重置，按钮、演示、导入导出继续运行。动效页导航通过新标签页打开，避免子 iframe 请求导航顶层页面。教程内“返回教程”关闭并卸载动效 iframe，恢复之前的演示状态。该行为依据 [CSP sandbox 浏览器规则](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/sandbox)。

本轮已在本机 HTTP 深层目录与模拟上述限制的响应头下验证；TEngineHttp 附件服务仍由对应项目实现，实际上传及线上验证待该服务可用后执行。

## GitHub Pages

现有公开入口：[原型目录](https://xiaomaoju.github.io/zflip5screenhelper/) · [互动教程](https://xiaomaoju.github.io/zflip5screenhelper/flipcover-tutorial.html)。

仓库 Settings → Pages → Build and deployment → Source 选择 GitHub Actions。[工程 CI](../.github/workflows/ci.yml) 在 main 推送时检查仓库、网站及 Android 编译/测试/Lint；全部成功后才部署本次上传的同一网站 artifact，也可手动运行。`tools/project/site.mjs` 将验证后的 `dist/html/` 文件组装到全新的 Cache 输出目录，不维护另一份资源复制清单。为保留旧分享地址，发布目录另生成 `device-frames/preview.html` 跳转入口和旧贴图地址副本；这些兼容文件不需要上传到 TEngineHttp。

网站不发布 APK、Android 源码或本机验证资料。新增页面放入 `dist/html/` 并在 `index.html` 添加入口；新增外部资源必须同层随交付，并加入发布清单。

所有交互仅使用示例数据，不连接手机；浏览器检查不能替代三星外屏、锁屏和原生宿主的物理真机验证。
