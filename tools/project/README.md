# 工程检查工具

从项目根目录运行，需要 Node.js 24 或更新的受支持版本。工具使用 Node 标准库，不需要安装 npm 依赖；路径由脚本位置解析，支持 Windows、macOS、Linux。

| 命令 | 职责 |
| --- | --- |
| `node tools/project/check.mjs` | Git 收录/忽略规则、敏感文件、当前文档链接、版本记录、网页资源与脚本检查 |
| `node --test tools/project/project.test.mjs` | 资源遗漏、脚本错误、目录越界、校验值和版本防覆盖的负向检查 |
| `node tools/project/site.mjs check` | 独立静态网站检查 |
| `node tools/project/site.mjs assemble Cache/build-output.nosync/project/pages` | 在全新目录组装网站，保留旧外框链接 |
| `node tools/project/check.mjs sdk-packages` | 从 Gradle 的唯一配置读取 CI 所需 SDK 包，不维护第二份版本 |
| `node tools/project/release.mjs verify dist/update-release/72` | 通过官方 SDK 检查真实 APK、catalog 和版本清单 |

APK 核验要求 `ANDROID_HOME`（或 `ANDROID_SDK_ROOT`）、JDK 17 和 Gradle 声明的 Build Tools。Node 脚本通过参数数组调用 `aapt` 与 `java -jar apksigner.jar`，不调用任意 shell，不打印私钥或密码。

正式打包只使用 `./gradlew :app:prepareUpdateRelease`（Windows 为 `gradlew.bat`）。Gradle 先构建 Release、执行单元测试和 Lint，再调用 `release.mjs prepare`；不绕过 Gradle 单独调用 prepare 来分发旧构建缓存。已有版本目录拒绝覆盖，复核使用 verify。

网页输出和检查夹具位于 `Cache/build-output.nosync/project/`。组装入口拒绝现有目录和 Cache 外路径；重新检查时选择新目录。发布锁 `release.lock` 只在准备期间存在；中断后先确认没有其他准备进程，再清理这一个锁目录。历史档案可能引用本机素材；当前 README、协作规则和 `docs/`（历史汇总除外）必须使用随源码交付的链接。
