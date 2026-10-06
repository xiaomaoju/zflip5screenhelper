# 开发、构建与验证

本文件是当前构建、签名、迁移与本地验证的入口。项目根目录执行文中的命令；版本和依赖以 Gradle 配置为准，历史验收文档中的旧环境、旧包名和旧签名说明仅供追溯。

单人维护的提交、推送、CI 和正式发布入口见 [工程流程](engineering-workflow.md)，可移植检查命令见 [工程工具](../tools/project/README.md)。

本轮工程改造的实测范围和未验证项见 [工程验收](engineering-validation.md)；这份记录不替代后续提交和设备验收。

## 文档职责

| 文件或目录 | 应写内容 | 不应承担的内容 |
| --- | --- | --- |
| `README.md` | 软件是什么、解决什么需求、面向谁、有哪些功能，以及影响使用的权限和兼容性边界 | 构建命令、类名与算法、开发协作规则、验收流水和历史问题 |
| `AGENTS.md` | 稳定项目结构、模块责任、共享数据与样式的唯一来源、安全约束、交付约定和完成标准 | 长篇功能规格、调试步骤、实现参数、测试结果与任务日志 |
| `docs/` | 开发与使用步骤、模块详细契约、设计方案、配置协议和验证方法 | 临时日志与机器私有配置 |
| `dist/` | 最终交付入口、发布说明、原型和验收结论 | 本机构建工具和中间产物 |
| `Cache/` | 可丢弃构建环境、临时证据与中间产物，不入 Git | 唯一源码、正式交付和可发布凭据 |

维护原则：用户能用来判断软件用途的内容写 README；后续贡献者必须遵守的稳定边界写 AGENTS；说明“具体怎样实现、构建或验收”的内容写对应 docs。当前模块行为的详细约束汇总在[模块契约](module-contracts.md)，修改时同步维护相关专题文档，避免同义配置或相互冲突的说明。

## 从源码构建

常用开发参数集中在根目录 [`app-config.json`](../app-config.json)，分为更新、设置默认值、外观和动效，修改后重新构建生效；已保存的用户设置优先。包名/版本/SDK/依赖、固定约束和内部实现细节仍保留在原处，详见[配置说明](app-configuration.md)。

准备 JDK 17、Android SDK Platform 37.0、Build Tools 36.0.0 和 Platform Tools，通过 `ANDROID_HOME` 或 Android Studio 配置 SDK 路径。项目使用 Gradle Wrapper 9.3.1、Android Gradle Plugin 9.1.1 和 Kotlin 2.4.10。

在项目根目录执行：

编译、单元测试和 Lint 不需要私钥；干净克隆和公共 CI 可执行以下检查，不生成安装包（Windows 使用 `gradlew.bat`）：

```sh
./gradlew :app:compileDebugJavaWithJavac :app:compileDebugKotlin :app:testDebugUnitTest :app:lintDebug :app:testReleaseUnitTest :app:lintRelease
```

生成 APK/AAB 前配置根目录 `keystore.properties`。debug 和 Release 统一使用包名 `io.github.flipcover.controls` 及同一签名；缺少签名配置时打包明确失败，不回退到默认 debug 签名。以下值须替换为自己的真实密钥配置：

```properties
storeFile=signing/flip-assistant-release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=flip-assistant
keyPassword=YOUR_KEY_PASSWORD
```

密钥和密码文件已被 Git 忽略，必须单独安全备份，并供后续两种构建持续使用。丢失密钥就无法按现有签名更新。源码克隆不包含密钥，构建前需自行配置。

```sh
# macOS / Linux
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Windows
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Release 构建与检查（Windows 使用 gradlew.bat）
./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

调试安装包输出到 `Cache/build-output.nosync/app/outputs/apk/debug/app-debug.apk`，正式安装包输出到 `Cache/build-output.nosync/app/outputs/apk/release/app-release.apk`，测试和 Lint 报告位于 `Cache/build-output.nosync/app/reports/`。后续 debug／Release 可互相覆盖安装并保留应用数据，前提是沿用这份密钥、相同包名且新包 `versionCode` 不低于已安装版本；新版本递增 `versionCode`，不支持用低版本包降级覆盖。

最终打包交付统一使用 `dist/update-release/<versionCode>/flipcover-<versionName>.apk`（Release）或 `dist/update-debug/<versionCode>/flipcover-<versionName>-debug.apk`（debug），同目录附带与实际 APK 对应的 `catalog.json`。不再将新 APK 散放在 `dist/` 根目录或仅交付 Cache 中间产物，详见[打包规范](online-update.md)。

旧版 debug 使用 `.debug` 包名及旧调试签名，首次迁移到本版会作为不同应用并存：先从旧版导出配置，再在新版导入，重新选择目标外屏并手动授权；原生组件身份不迁移，须重新添加绑定。确认迁移后再卸载旧版。成功构建和本地检查不等同于三星外屏真机验收；设备端检查及物理真机流程见下文。历史结果保留在[项目历史](project-history.md)，不作为当前环境配置。

## 本地与设备验证

完成工作应有成功构建和与改动相关的本地检查。涉及设备测试时先构建设备端测试包：

```sh
./gradlew :app:assembleDebugAndroidTest
```

`app/src/androidTest/` 的 `UiSmokeInstrumentation` 提供分场景检查。只在专用、可重置配置的测试模拟器上运行；这些检查可能重置应用偏好，不在日常使用的手机上运行。安装应用及测试 APK 后，按相关专题文档选择场景，例如：

```sh
adb -s <TEST_EMULATOR_SERIAL> shell am instrument -w -e scenario app-update io.github.flipcover.controls.test/io.github.flipcover.controls.UiSmokeInstrumentation
```

包名与签名规则见上文；不要沿用历史文档中的 `.debug.test` 包名。场景的操作范围、前置条件与断言以对应检查源码和专题文档为准。

本地编译、单元测试、Lint、模拟器 UI 检查和三星物理真机验证分别报告。系统窗口路由、缺口与导航边衬、锁屏与解锁恢复、Shizuku 实际执行、三星原生卡片及第三方小组件须在所选物理外屏确认，不能用原型或模拟器结果替代。系统调用失败、未连接设备或未执行的项目明确标为未验证。

原始截图与日志保留在 `Cache/` 的对应任务目录；正式 APK、发布说明和最终 UI 效果按 [AGENTS](../AGENTS.md) 的交付规则放入 `dist/`。历史结果见[项目历史](project-history.md)，在线更新与打包专项见[在线更新](online-update.md)。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `app/` | Android 源码、资源、单元测试和设备端测试 |
| `app-config.json` | 更新、设置默认值、主要外观与动效的常用调节参数 |
| `gradle/`、`gradlew`、`gradlew.bat` | 可复用的 Gradle 构建入口 |
| `docs/` | 开发流程、模块契约、功能边界、设计方案与验证文档 |
| `dist/` | `html/` 静态网站、`开发设计/` 历史资料、标准设备框及版本交付；公开 catalog/清单/说明入库，APK 独立分发；`开发设计/readme-gallery/` 的展示图片入库 |
| `tools/` | 设备外框、macOS 带框投屏工具和独立通知测试 App 源码 |
| `tools/project/` | 跨平台工程检查、网页组装、真实 APK 验证及正式版本准备 |
| `.github/workflows/ci.yml` | main 自动检查及检查通过后的 Pages 部署 |
| `.agents/`、`AGENTS.md` | 项目协作与设置界面设计规则 |
| `Cache/` | 被 Git 忽略的本机工具、构建产物及临时验证资料 |
