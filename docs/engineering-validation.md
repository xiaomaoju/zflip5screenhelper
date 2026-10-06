# 工程改造本地验收 · 2026-10-05

本轮修改仓库规范、签名任务边界、工程检查及交付入口，保持应用 versionName 0.18.14、versionCode 72 和现有正式签名。运行时功能源码未修改；验收对象是本地未提交改动，不能用当前 HEAD 代替改动后的提交身份。

| 范围 | 实际结果 |
| --- | --- |
| 有密钥构建 | debug/Release assemble、单元测试和 Lint 成功 |
| 无密钥隔离源码 | debug/release 编译、单元测试和 Lint 成功；两变体各 182 项测试，无失败或错误 |
| 缺密钥打包 | 直接 APK、测试 APK 和两种 AAB 中间打包明确失败；--continue 不能绕过 AAB 包装保护 |
| 工程工具 | 8 项测试通过，覆盖资源缺失、JS 语法、外部资源/module 边界、符号链接越界、catalog 路径、版本防覆盖和哈希 |
| 仓库与网站 | 当前文档/忽略规则检查通过；10 页、9 份脚本、57 处资源引用通过；网站组装成功 |
| 发布任务 | AGP 9 SDK 读取和任务图通过；Release 构建、测试、Lint 后才调用准备工具 |
| 隔离真实准备 | 在 Cache 中独立源码副本实际签名构建、准备完整版本、核对 catalog/清单/API 工具版本；重复版本拒绝覆盖，无遗留锁 |
| 历史 APK | 69、70、71、72 的真实文件、catalog、大小、SHA 和正式签名通过；原稳定 72 APK 未替换 |
| CI 源码 | YAML 语法及 8 个固定 Action commit SHA 核验通过；部署依赖所有检查成功且只发布同次 artifact |
| main 设置 | GitHub API 确认禁止强推/删除并约束管理员，允许正常推送，不要求 PR 或状态检查 |

独立复核发现的 AAB 包装绕过、外部 JS/module 检查遗漏、非规范版本路径及符号链接越界已修复并重测。正式发布入口的 AGP 9 SDK 属性不兼容在实际任务实现检查中发现，已修正并通过真实隔离准备流程。

日志位于 `Cache/build-output.nosync/project/`；无密钥和真实准备副本位于 `Cache/tests/engineering-*`。测试副本拥有独立临时 Git 历史，用于验证 sourceCommit 和干净工作区要求，没有在主工程提交或推送。测试签名配置已删除，正式私钥未复制。

截至上述本地验收，尚未执行：新工作流的远端 CI、Windows/Linux 运行、模拟器 UI、三星物理真机、新 APK 上传/安装/正式发布。当前应用稳定性沿用用户反馈，本轮不据此宣称新的设备验收通过。仓库根目录未自动选择软件许可证；许可及第三方声明应在明确授权范围后独立整理。

流程入口见 [工程流程](engineering-workflow.md)，正式包记录见 [稳定基线](../dist/update-release/72/RELEASE.md)。

## 远端验收 · 2026-10-06

工程改造及后续 SDK 安装修复已正常提交并推送 main。源码提交 `3248df4601dfb2963d9c18f9ae83732a04cfb214` 的 [Actions 运行 37461448048](https://github.com/xiaomaoju/zflip5screenhelper/actions/runs/37461448048) 全部成功：

| 范围 | 实际结果 |
| --- | --- |
| Windows、macOS、Ubuntu | 三个平台分别完成仓库/网页检查和 10 项工程工具测试 |
| Linux Android | 无正式密钥的干净 runner 完成 SDK 安装、编译、debug/release 单元测试及 Lint；测试与 Lint 报告随运行保留 |
| Pages | 部署同次检查上传的网站 artifact；部署作业成功 |
| 线上文件 | [公开网站](https://xiaomaoju.github.io/zflip5screenhelper/) 的 13 个平铺文件均返回成功，响应内容逐字节匹配本地交付；4 个旧设备框地址可达 |
| 稳定包 | 72 的 APK SHA-256 仍为 `540f0a2e5817459dc0c6a64bb478e0c5716a6feb64b85c57a4068af18a59587f`，未替换或重新发布 |

首次远端执行暴露了旧 Android Action 默认安装已移除的 `tools`，以及旧 SDK 目录格式无法识别 `platforms;android-37.0`。修复独立提交，保留失败记录：明确只安装 platform-tools，固定新版 Action 与支持 schema 4 的官方 command-line tools；专用 SDK CLI 从 Google 官方目录解析 Gradle 声明版本的实际包名。两项解析回归测试覆盖整数/major.0 命名与缺包/预览包拒绝，应用 compileSdk 37、targetSdk 36 未修改。

远端证据补齐了工程工具跨平台执行和 Linux Android 构建检查；不代表 Windows/macOS Android 构建或设备功能验收。模拟器 UI、三星物理真机、新 APK 上传/安装/正式发布仍未执行。
