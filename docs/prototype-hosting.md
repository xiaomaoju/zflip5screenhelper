# 原型网站托管

网站入口：<https://xiaomaoju.github.io/zflip5screenhelper/>

直接体验教程：<https://xiaomaoju.github.io/zflip5screenhelper/flipcover-tutorial.html>

## 页面与资源

- `dist/index.html`：原型目录首页。
- `dist/flipcover-tutorial.html`：最新版外屏上手教程，打开即自动快速演示，包含第2屏三星原生启动器。
- `dist/flipcover-prototype.html`：与教程同内容的兼容入口，旧分享链接继续有效。
- `dist/settings-oneui-prototype.html`：One UI 设置交互原型。
- `dist/nfc-hotspot-prototype.html`：NFC 与移动热点开关、配置及连接设备交互原型。
- `dist/notification-force-jelly.html`：通知弹性、顺序分裂和合并清除演示。
- `dist/control-force-jelly.html`：控制中心单弹簧、同步回弹与局部反馈演示。
- `dist/task-force-jelly.html`：任务页连续滚动、关闭、失败回弹与空态演示。
- `dist/launcher-force-jelly.html`：启动器八区域受力、侧栏分裂与 Dock 演示。
- `dist/device-frames/preview.html`：设备外框预览及 PNG / SVG 下载。

教程两个入口各自内嵌完整教程脚本、样式、图标和设备外框。教程顶部“四个界面动效”按需加载上述四份独立 HTML，切换或返回时卸载旧演示，返回恢复此前自动播放状态；四份动画共用这些文件，不再维护教程专用副本。离线复制完整体验时，将这些 HTML 与 `device-frames/` 保持原相对位置。仅复制教程单文件仍可运行原教程，动效入口需要随附四份 HTML。上述页面不依赖 Codex、网络字体或本机绝对资源路径，可供普通浏览器直接打开或部署到 GitHub Pages / NAS。

## 发布与更新

GitHub 仓库 **Settings → Pages → Build and deployment → Source** 使用 **GitHub Actions**。

`.github/workflows/pages.yml` 在 `main` 分支收到相关原型文件更新时自动发布，也可以在 **Actions → Deploy prototype previews → Run workflow** 手动运行。工作流仅在 GitHub 的 Ubuntu runner 上执行；直接复制可部署文件，无需 Node.js、PHP 或 Android SDK。

工作流将明确列出的页面与依赖资源复制到 `Cache/build-output.nosync/pages/`，然后上传为 Pages 网站。它不发布整个 `dist/` 或仓库，不包含 APK、原始验证截图、本机缓存和 Android 源码。该目录是可丢弃的构建输出，不作为原型维护源。

更新现有原型：修改对应 HTML 或设备贴图，提交并推送至 `main`，在 Actions 中确认部署成功，再刷新网站。新增原型：将 HTML 及资源放入 `dist/`，在 `dist/index.html` 添加链接，并更新工作流的复制清单及必要的路径触发条件；不要只上传 ZIP。

此网站公开可访问。浏览器中的原型交互仅使用示例数据，不能替代 Android 功能或三星外屏真机验证。
