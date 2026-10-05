# Flip外屏助手 0.17.4 Release

日期：2026-10-02。构建使用当前工作区的完整应用源码，保留既有功能改动。

## 安装包

- [Flip外屏助手-0.17.4-release.apk](Flip外屏助手-0.17.4-release.apk)，约19 MiB。
- 包名：`io.github.flipcover.controls`；版本：`0.17.4`；versionCode：`54`。
- Release 不可调试，使用独立正式密钥；未开启代码压缩，沿用原发布构建行为。
- APK SHA-256：`06eece5620792913885367bb36bad633331b58cc578f72114fe270954ab51163`。
- 签名证书 SHA-256：`d97b13019c1e7bab2883055c5e150eb43e24228e63181dcd94e7dc71bdacdb22`。

## 本次调整

应用名称统一为“Flip外屏助手”，覆盖桌面名称、无障碍服务名称、设置首页、关于页、诊断报告和浮窗标题。名称由 `app_name` 资源统一维护。

debug 和 Release 共用包名及正式签名，不再使用 `.debug` 后缀。缺少 `keystore.properties` 或必要签名字段会明确报错，不回退到默认调试签名。Release 单元测试已显式启用。

更新旧综合界面测试中已经过时的侧栏坐标、应用搜索唯一性及配置/布局版本断言：侧栏按共享样式的内边距与实际嵌套坐标检查；搜索按精确包名选中目标，不假设测试应用不会同时匹配；配置版本15、布局版本11。未修改运行时布局或配置格式。

## 覆盖安装与签名保管

从本版开始，debug 与 Release 可以互相覆盖并保留应用数据，但新包 versionCode 必须不低于已安装版本；后续发布递增 versionCode，不支持低版本降级覆盖。同版54的 debug → Release → debug → Release 已在 Android 16 模拟器以 `adb install -r` 全部安装成功。初始化后的配置 XML 和私有数据标记在往返安装后逐字节相同；Release 启动成功，首次安装时间保持不变。

以前已经发出的 `.debug` 包名及旧调试签名不能通过修改新包追溯改变；本版与旧 debug 作为不同应用并存。先在旧版导出配置，再在本版导入；重新选择目标外屏并由用户手动授权。原生组件绑定身份不迁移，需重新添加组件。确认迁移后再卸载旧版，避免两份服务同时启用。

本机密钥位于 `signing/flip-assistant-release.keystore`，配置位于根目录 `keystore.properties`；文件权限为600，两者已被 Git 忽略。必须单独安全备份两份文件，并在今后所有构建中沿用，不随安装包分发、不提交仓库、不重新生成替代密钥。源码克隆需要自行配置签名。构建入口见根目录 README。

Android 更新所需的包名、签名与版本条件见 [Android 官方更新说明](https://developer.android.com/google/play/app-updates)，签名管理见 [Android 官方签名说明](https://developer.android.com/studio/publish/app-signing)。

## 本地验证结果

| 检查 | 结果 |
| --- | --- |
| Release、debug、设备测试 APK 构建 | 成功；Release 构建与检查命令退出码0 |
| Release 单元测试 | 163项，0失败、0错误、0跳过 |
| Release Lint | 0错误、169条警告；警告仍保留在报告中 |
| 两包身份核对 | 包名、versionCode与证书指纹相同；仅debug具有debuggable标记 |
| APK 签名与对齐 | apksigner验证成功，APK v2签名有效；zipalign 16 KiB页面检查通过 |
| 实际覆盖安装 | debug与Release双向覆盖成功，配置及私有文件保留 |
| 实际Release启动 | 关于页冷启动成功 |
| 实际Release综合界面检查 | 176项断言通过 |
| 实际Release设置专项 | 429项断言通过，含导航、取消、草稿、无障碍滑条、预览和文字可读性 |
| debug配置专项 | 178项断言通过，含导入导出、旧版本和原子拒绝 |
| debug启动器细节 | 364项断言通过 |
| debug原生启动器组件 | 836项检查通过 |
| debug控制中心网格 | 180项布局检查通过，含3/4/5列、数量、方向及字体尺寸 |
| debug通知中心 | 302项断言通过 |
| debug原生组合组件 | 144项断言复测通过；首轮未找到测试夹具，移除模拟器中遗留旧测试应用后复测通过，具体原因未单独隔离 |
| debug任务受力 | 181项断言通过 |
| debug任务请求队列 | 10项断言通过 |
| debug任务恢复 | 26项断言通过 |
| debug全屏任务页 | 54项断言通过 |
| 旧recent-tasks综合用例 | 未通过：`five-task strip follows a partial drag one-to-one without central damping`，见下文 |
| 源码差异检查 | git diff --check通过 |

## 未完成的验收

旧 `RecentTasksChecks.continuousScrolling` 在跟手位移断言处失败；该用例后续还要求慢速松手保持非居中偏移，与当前任务页必须单张居中的契约不一致。本次保留失败证据，未改写整套旧任务用例，也未把失败宣称为通过。新的任务受力、队列、恢复与全屏专项通过，但不能据此证明这条旧跟手断言不存在运行时问题，需后续单独排查。

自动化检查使用 Android 16 / API36、720×748、340dpi 模拟器。随后按用户明确要求，通过ADB在三星Z Flip5（SM-F731U1 / Android16）卸载旧 `io.github.flipcover.controls.debug` 并安装本版Release，两个命令均返回Success；设备端确认包名 `io.github.flipcover.controls`、版本0.17.4、versionCode54且无debuggable标记。卸载会移除旧应用数据；本次没有操作权限、配置或启动应用，其余设置由用户自行完成。独立通知测试应用和玻璃试验应用保留。

真机安装成功不等同于三星外屏路由、系统宿主、锁屏、权限、Shizuku、旋转和真实手势验收；这些功能尚未在本轮物理真机上操作验证。

详细构建日志、签名、安装前后配置及各场景输出保存在 `Cache/validation/release-0.17.4/`；单元测试及Lint报告位于 `Cache/build-output.nosync/app/reports/`。模拟器截图仅为原始验证素材，本次没有交付最终UI效果图。
