# 外屏快捷栏 · Flip Cover Controls

为三星 Galaxy Z Flip5 打造的外屏增强工具。项目起源于内屏损坏后的使用需求，希望只靠外屏，也能更方便地打开应用、切换任务、查看通知和控制常用功能，减少对展开手机的依赖。

当前应用版本为 **0.15.11**，面向 **Android 16 / One UI 8.5** 进行个人验证。无需 Root，部分系统操作需要 Shizuku 授权。

## 能做什么

- **外屏快捷操作**：自定义快捷按钮，通过底部双白条上滑打开通知中心或控制中心，支持快捷栏显隐与位置调整。
- **控制中心**：集中使用亮度、音量、媒体播放及常用系统控制，支持 3 / 4 / 5 列布局、按钮添加和拖动排序。
- **应用桌面与 Dock**：启动、搜索和整理应用，支持拼音及别名搜索、分页、文件夹和拖动排序；Dock 最多固定 4 个应用，并按空间显示最多 4 个最近应用。
- **外屏多任务**：查看和恢复外屏最近任务，上滑关闭、下滑锁定或解锁，并清理符合条件的未锁定任务。
- **通知与媒体**：查看已授权的活动通知，操作媒体播放；通知和最近任务历史只保存在内存中。
- **原生小组件组合卡片**：提供 6 个三星原生卡片入口，每张卡片可在 4×4 网格内组合多个已授权的 Android 桌面小组件。
- **外屏适配与个性化**：适配摄像头缺口和四个旋转方向，提供状态栏、应用方向、布局与显示偏好设置，支持配置导入导出和布局备份。

## 开始使用

1. 构建并安装调试 APK，打开应用设置。
2. 选择要使用的外屏，并按权限页面提示启用无障碍服务等所需权限。
3. 如需特权系统操作，启动 Shizuku 并授权本应用；查看通知和使用小组件时，分别完成对应的系统授权。
4. 根据外屏实际显示效果调整快捷栏、安全区域和应用布局。

显示器专属操作只作用于选定外屏；影响内外屏的系统开关会单独说明。项目不自动授权、不绕过锁屏，也不增加联网或分析追踪功能。三星固件、第三方小组件和系统窗口路由的兼容性仍需以物理真机验证为准。

## 从源码构建

准备 JDK 17、Android SDK Platform 36、Build Tools 35.0.0 和 Platform Tools，通过 `ANDROID_HOME` 或 Android Studio 配置 SDK 路径。项目使用 Gradle Wrapper 8.13 和 Android Gradle Plugin 8.13.2。

在项目根目录执行：

```sh
# macOS / Linux
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

# Windows
gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

调试安装包输出到 `Cache/build-output.nosync/app/outputs/apk/debug/app-debug.apk`，测试和 Lint 报告位于 `Cache/build-output.nosync/app/reports/`。调试签名用于个人验证，发布版签名尚未配置。成功构建和本地检查不等同于三星外屏真机验收。

## 项目结构

| 路径 | 内容 |
| --- | --- |
| `app/` | Android 源码、资源、单元测试和设备端测试 |
| `gradle/`、`gradlew`、`gradlew.bat` | 可复用的 Gradle 构建入口 |
| `docs/` | 设计方案、功能说明与历史记录 |
| `dist/` | 验证文档、HTML 原型和设备外框资源；安装包与生成的效果图不纳入源码提交 |
| `.agents/`、`AGENTS.md` | 项目协作与设置界面设计规则 |
| `Cache/` | 被 Git 忽略的本机工具、构建产物及临时验证资料 |

## 更多说明

- [详细功能、版本历史与真机验证流程](docs/project-history.md)
- [控制中心设置](CONTROL_CENTER_SETTINGS.md)
- [应用桌面与文件夹操作](docs/app-workspace-folder-plan.md)
- [配置导出范围](docs/configuration-export-coverage.md)
- [设置界面设计方案](docs/settings-ui-refactor-plan.md)
- [第三方组件声明](THIRD_PARTY_NOTICES.md)

本仓库保存源码和必要的项目资料。第三方参考 APK、个人录屏、签名密钥、SDK 和本机构建缓存不随仓库分发；历史文档中的安装包或效果图路径可能仅存在于本地交付目录。
