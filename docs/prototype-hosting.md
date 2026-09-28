# 原型网站托管

网站入口：<https://xiaomaoju.github.io/zflip5screenhelper/>

## 页面与资源

- `dist/index.html`：原型目录首页。
- `dist/flipcover-prototype.html`：0.10.0 外屏交互原型。
- `dist/settings-oneui-prototype.html`：One UI 设置交互原型。
- `dist/device-frames/preview.html`：设备外框预览及 PNG / SVG 下载。

上述页面保留相对路径，既可通过普通浏览器直接打开，也可部署到 GitHub Pages 或 NAS。离线复制设置原型时，需要一并保留 `device-frames/` 内的 SVG；复制外框预览时，需要一并保留它链接的 PNG 与 SVG。

## 发布与更新

GitHub 仓库 **Settings → Pages → Build and deployment → Source** 使用 **GitHub Actions**。

`.github/workflows/pages.yml` 在 `main` 分支收到相关原型文件更新时自动发布，也可以在 **Actions → Deploy prototype previews → Run workflow** 手动运行。工作流仅在 GitHub 的 Ubuntu runner 上执行；直接复制可部署文件，无需 Node.js、PHP 或 Android SDK。

工作流将明确列出的页面与依赖资源复制到 `Cache/build-output.nosync/pages/`，然后上传为 Pages 网站。它不发布整个 `dist/` 或仓库，不包含 APK、原始验证截图、本机缓存和 Android 源码。该目录是可丢弃的构建输出，不作为原型维护源。

更新现有原型：修改对应 HTML 或设备贴图，提交并推送至 `main`，在 Actions 中确认部署成功，再刷新网站。新增原型：将 HTML 及资源放入 `dist/`，在 `dist/index.html` 添加链接，并更新工作流的复制清单及必要的路径触发条件；不要只上传 ZIP。

此网站公开可访问。浏览器中的原型交互仅使用示例数据，不能替代 Android 功能或三星外屏真机验证。
