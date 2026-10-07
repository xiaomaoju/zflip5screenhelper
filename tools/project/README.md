# 工程检查工具

从项目根目录运行，需要 Node.js 24 或更新的受支持版本。工具使用 Node 标准库，不需要安装 npm 依赖；路径由脚本位置解析，支持 Windows、macOS、Linux。

| 命令 | 职责 |
| --- | --- |
| `node tools/project/check.mjs` | Git 收录/忽略规则、敏感文件、当前文档链接、版本记录、网页资源与脚本检查 |
| `node --test tools/project/project.test.mjs` | 资源遗漏、脚本错误、目录越界、校验值和版本防覆盖的负向检查 |
| `node tools/project/site.mjs check` | 独立静态网站检查 |
| `node tools/project/site.mjs assemble Cache/build-output.nosync/project/pages` | 在全新目录组装网站，保留旧外框链接 |
| `node tools/project/check.mjs sdk-packages` | 从 Gradle 读取 SDK 版本，联网读取 Google 官方 schema 4 目录解析实际包名（含 major.0 格式）；缺包或目录失败明确报错 |
| `node tools/project/release.mjs verify dist/update-release/84` | 通过官方 SDK 检查真实 APK、catalog 和版本清单 |
| `node tools/project/cloud-release.mjs preflight` | 仅标签 CI：检查版本递增、标签与 Gradle 一致、main 祖先及发布状态 |
| `node tools/project/cloud-release.mjs publish` | 仅标签 CI：核验同次运行四份公开文件、草稿上传及下载哈希，公开 GitHub 预发布 |

`signing.mjs create/remove` 是标签签名作业的内部入口，从四个 Environment Secrets 还原受限权限的临时签名文件，拒绝替换既有本机配置；失败及结束时清理。普通仓库检查和发布作业均不读取签名 Secrets。全云端操作及故障恢复见 [工程流程](../../docs/engineering-workflow.md#全云端-github-apk)。

APK 核验要求 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）、JDK 17 和 Gradle 声明的 Build Tools。Node 脚本通过参数数组调用 `aapt` 与 `java -jar apksigner.jar`，不调用任意 shell，不打印私钥或密码。

正式证书的公开 SHA-256 指纹由 `release-certificate.sha256` 保存，APK 校验不依赖本地历史交付目录；这不是私钥。云端版本递增检查读取 Git 的上一提交和版本标签，不扫描被忽略的产物目录。

正式打包只使用 `./gradlew :app:prepareUpdateRelease`（Windows 为 `gradlew.bat`）。Gradle 先构建 Release、执行单元测试和 Lint，再调用 `release.mjs prepare`；不绕过 Gradle 单独调用 prepare 来分发旧构建缓存。已有版本目录拒绝覆盖，复核使用 verify。

网页输出和检查夹具位于 `Cache/build-output.nosync/project/`。组装入口拒绝现有目录和 Cache 外路径；重新检查时选择新目录。发布锁 `release.lock` 只在准备期间存在；中断后先确认没有其他准备进程，再清理这一个锁目录。历史档案可能引用本机素材；当前 README、协作规则和 `docs/`（历史汇总除外）必须使用随源码交付的链接。
