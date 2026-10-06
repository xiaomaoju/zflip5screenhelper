# 单人维护、检查与发布

本项目由单人维护，日常直接提交 main，不要求 PR 或审批人数。main 保持可构建；较大的未完成改动可以放在短期 `codex/` 分支，完成检查后本地合并。当前稳定基线为 [0.18.14 / 72](../dist/update-release/72/RELEASE.md)。

## 日常修改

1. 查看工作区状态，fetch 远端；仅在工作区适合更新时使用 `git pull --ff-only`。保留尚未提交的工作，不自动 reset、强推或清理文件。
2. 按模块契约实现一个明确的改动。功能、目录迁移和工具链升级分别处理。流程/文档修改不递增 Android 版本；发布新 APK 时才按 Gradle 的唯一版本配置递增。
3. 执行 `node tools/project/check.mjs`、工具测试和相关 Gradle 检查。涉及运行界面时，追加对应模拟器场景或物理外屏验收。
4. 用 `git add <明确路径>` 或 `git add -p` 暂存；检查 `git diff --cached --stat`、`git diff --cached` 和 `git diff --cached --check`。每次提交只表达一个可解释的结果。
5. 正常提交并 push main。查看同一提交的 Actions 结果；失败则暂停发布，以新提交修复，不改写已推送历史。

推送是保存源码，Pages 部署是更新网站，APK 发布是交付安装包；三者分别报告实际结果。AI 协作按用户当前授权执行，用户要求暂不提交时只完成本地改动和检查。

## 自动检查与 Pages

[CI 工作流](../.github/workflows/ci.yml) 在 main/版本标签推送和手动运行时执行：

- Windows、macOS、Linux：仓库规则、当前文档链接、网页自包含资源、脚本语法和工程工具测试。
- Linux：按 Gradle 配置安装 SDK；无签名密钥执行 debug/release 单元测试、Lint 和相关编译，不产生安装包。
- 全部检查通过且分支为 main：部署本次检查上传的同一网站 artifact。部署权限只授予 Pages 作业，普通检查只有读取权限。

自动化使用固定 Action commit SHA、超时和有限报告保留期限；工具升级单独审查。普通 main 检查不使用私钥，不上传 APK。版本标签的专用作业从 `android-release` 环境 Secrets 取正式签名，发布经过检查的 APK；密钥、本机配置和签名缓存不上传。

main 使用禁止强推和删除的保护（对管理员也生效），同时允许正常推送；不要求 PR，不配置依赖推送前状态的合并门槛。本地未提交的 workflow 不能运行远端验收；首次推送后需要确认各平台执行成功。

## 正式 APK 发布

1. 修改 Gradle 中的版本与对应更新日志，完成源码检查并提交。工作区保持干净。
2. 运行 `./gradlew :app:prepareUpdateRelease`。任务执行 Release 构建、单元测试与 Lint，核对真实 APK 和正式证书，从实际 APK 生成 catalog、版本清单及发布说明。
3. 新版本完整文件在 Cache 中准备，校验后移入最终目录；已有版本目录一律拒绝替换。`verify` 只复核已有版本，不重写它。
4. 在专用测试环境验收安装与升级；三星窗口路由、锁屏、原生卡片及权限/服务恢复单独确认。将通过/失败/未执行项目写进该版本 RELEASE.md，绑定 APK 哈希，不把用户使用反馈改写为自动测试通过。
5. 提交可公开的 catalog、版本清单和发布说明。APK 和私钥不进入 Git；版本清单的 sourceCommit 保留实际准备时的源码提交。
6. 本机生成的包可按现有更新服务器协议上传并切换 catalog。全云端 GitHub 分发按下节执行，标签先指向完成检查的源码，APK 清单绑定该提交。源代码标签和版本记录提交可以不同，安装包来源以清单记录为准。

本机 prepare 入口不自动上传、创建标签、变更服务器或安装到手机；云端标签工作流自动发布 GitHub 预发布包。稳定发布前必须确认实际设备验收结果、下载文件的大小与 SHA-256、签名证书和更新说明。旧版 APK 持续保留；修复已安装版本时使用更高 versionCode，不重复旧编号。网站故障可以部署已验证的旧提交。

## 全云端 GitHub APK

首次配置仓库 Environment `android-release`，部署策略仅允许 `v*` 类型的 tag，保存四个 Environment Secrets：`SIGNING_KEYSTORE_BASE64`（现有正式 keystore 的 base64）、`SIGNING_STORE_PASSWORD`、`SIGNING_KEY_ALIAS`、`SIGNING_KEY_PASSWORD`。保留本机原密钥和独立备份，不新建或替换签名。凭据不写入命令参数、文档、Git 或资产；可通过 GitHub 环境 Secrets 界面或 `gh secret set --env android-release` 的标准输入配置。

日常直接推送 main。需要新的下载包时：

1. 递增 Gradle 的 versionName/versionCode，更新应用内 changelog，提交并推送 main。
2. 在该提交创建与 versionName 完全相同的标签（例如版本 0.18.15 使用 `v0.18.15`），正常推送标签；不得移动、删除后复用标签或已发布编号。
3. 标签流水线首先完成三平台工程工具检查和无密钥 Android 编译/单元测试/Lint，然后验证标签版本、编号与 main 祖先关系。
4. 专用签名作业短暂还原忽略目录下的 keystore 和 keystore.properties，调用同一 `:app:prepareUpdateRelease`；签名作业仅有源码读取权限，并在成功或失败后清理签名文件。
5. 仅 APK、原样 catalog、release-manifest.json 和 RELEASE.md 组成同次运行 artifact。独立发布作业不使用私钥，重新验证 APK 与官方证书、源码提交、运行 ID 及 Gradle 版本。
6. 发布作业先创建 GitHub 草稿，上传后重新下载四个文件核对哈希，全部匹配才公开为预发布；不设置为最新稳定版。模拟器/三星真机验收未执行的事实保留在说明和清单中，完成设备验收后才决定稳定发布。

GitHub Releases 提供独立 APK 下载，schema 1 catalog 原样作为核验/自托管附件，其 `apkPath` 仍是现有服务器协议，不是 GitHub 资产地址。本流程不修改应用更新 URL，不上传现有更新服务器，也不触发手机安装。

上传失败时保留草稿和 artifact，使用 Actions 的“重新运行失败作业”重试发布作业；已有草稿资产必须逐字节一致，缺失资产才补传，不允许 clobber。不要重跑签名作业来替换草稿；已公开的版本拒绝重建与覆盖，源码/产物变化使用新编号。完成后从同次 artifact 保存完整目录到 `dist/update-release/<versionCode>/`，提交其中三份公开元数据，APK 留本地或 GitHub。环境 Secrets 轮换只改变后续运行，不改变已发布资产。

## Git 与资料边界

源码、锁文件、Wrapper、当前文档、网页必需资产、标准设备框及公开版本元数据入库。构建环境、原始日志/录屏、APK 和私钥留在忽略目录或独立备份。`dist/开发设计/` 是历史设计与验收资料；其中引用的 Cache、旧 APK 和未公开效果图是本机历史素材，不能成为当前开发说明的必需资源。

根目录 .editorconfig 管理新编辑的风格，.gitattributes 管理文本和二进制及换行；本轮不批量重排稳定应用源码。新增依赖、许可证选择和大规模架构拆分独立评估，逐模块保证行为和测试证据。
