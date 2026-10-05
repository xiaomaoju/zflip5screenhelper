# 在线更新与发布

入口为设置中的“关于与更新日志”，或首页底部的小字“检查更新”按钮（直接打开更新页并开始检查）。仅用户点击检查或下载时连接更新服务器，不后台轮询、不自动下载、不上传通知、配置或设备信息。

## 客户端流程

1. 从根目录 `app-config.json` 的随包副本读取固定 catalog 地址和更新参数。
2. 手动检查目录：严格检查协议字段、包名和正整数版本，较高 `versionCode` 才提供更新；相同或更低版本不降级，系统版本不足单独提示。
3. 用户点击下载后串行流式写入私有更新目录，支持取消和重试；进度按百分比变化更新，不将 APK 读入内存。
4. 核验目录声明的大小、SHA-256、APK 包名、版本名称/编号、最低 SDK 和当前签名证书。签名证书比对使用 Android 包管理器；APK 签名的完整密码学验证仍由系统安装器执行。
5. 点击安装时重新核验文件元数据、签名证书和当前已安装版本。未授权时仅打开系统安装来源设置，返回后仍需用户点击安装；随后通过只读 FileProvider URI 交给系统确认，安装是否成功以系统返回的界面为准。

离开页面、Activity 销毁或暂停中的检查/下载会取消旧请求，清除监听和迟到回调。一个页面只持有一个串行工作线程和一个 APK 缓存，未交给安装器的文件在关闭后删除；交给安装器的文件保留，下次检查按总配置的保留期限清理。旋转后不自动重新检查、下载或重放安装动作。更新会使应用进程和服务重建，安装后需在三星真机检查无障碍、通知监听、Shizuku 和原生卡片恢复，不能保证服务始终在线。

HTTPS/HTTP 重定向始终限于原协议、主机、端口和项目目录，不接受 APK 的绝对 URL、查询、片段或越界路径。若将总配置改为 HTTP，Gradle 只为该主机生成明文例外；HTTP catalog 中的哈希不能认证目录来源，APK 安装仍必须通过签名证书比对和系统验证。

## 服务端 schema 1

catalog 保持服务端协议的原字段名称：

| 字段 | 约定 |
|---|---|
| `schemaVersion` | 固定整数 `1` |
| `packageName` | `io.github.flipcover.controls` |
| `versionCode` | 正整数，判断新版；发布编号是其十进制字符串 |
| `versionName` | 展示版本名称，与 APK 相同 |
| `minSdk` | 最低 Android API 等级，与 APK 相同 |
| `changelog` | 更新说明字符串，支持换行 |
| `apkPath` | 相对 catalog 所在目录的 APK 路径 |
| `apkSize` | APK 字节数 |
| `sha256` | APK 的 SHA-256，64 位小写十六进制字符串 |

固定 catalog 为 `/appversion/v1/ZFlip5/catalog.json`；构建 69 的 APK 路径为 `releases/69/flipcover-0.18.11.apk`。读取 catalog 时不使用本地缓存。404、403、超时、协议错误和校验失败各自提示，不显示为“已是最新版”。

服务器先完整保存两个文件，再原子切换发布指针。历史已发布 APK 保留下载，避免读到旧 catalog 后切换版本导致失败；已发布编号不可复用。取消发布和关闭客户端下载仍由服务端控制，客户端不调用管理接口。

## 生成并手动发布

今后主应用安装包统一按以下目录交付，路径相对于项目根目录：

| 构建类型 | 交付目录 | APK 文件名 |
| --- | --- | --- |
| Release，含更新测试基线 | `dist/update-release/<versionCode>/` | `flipcover-<versionName>.apk` |
| debug | `dist/update-debug/<versionCode>/` | `flipcover-<versionName>-debug.apk` |

两种目录都包含 APK 和同目录的 `catalog.json`。目录编号、包名、版本名称/编号和最低 SDK 必须与实际 APK 一致，大小和 SHA-256 从最终签名文件计算；catalog 沿用 schema 1，`apkPath` 按服务端约定写为 `releases/<versionCode>/<APK文件名>`。交付前验证 APK 签名和 catalog 一致性。

`Cache/build-output.nosync/` 是构建缓存，最终下载入口必须指向上表的版本目录，不再将新 APK 零散放在 `dist/` 根目录。已有历史包不自动搬移。两种构建继续使用同包名和同正式签名；低版本更新测试通过临时构建覆盖生成，不降低源码正式版本，也不覆盖已发布版本目录。

从项目根目录运行（Windows 使用 `gradlew.bat`）：

```sh
./gradlew :app:prepareUpdateRelease
```

任务先构建正式 APK，再根据 APK 构建产物的 `output-metadata.json`、本次最低 SDK 和对应版本更新日志生成目录，流式计算实际 APK 的大小和 SHA-256。输出位于 `dist/update-release/<versionCode>/`，包含版本化命名的 APK 和 `catalog.json`。

debug 使用 `:app:assembleDebug` 构建后，按上表命名整理到 `dist/update-debug/<versionCode>/` 并生成实际对应的 catalog；不要以 Release 产物替代 debug 包或只复制旧 catalog。测试基线采用临时版本名称时，catalog 的更新说明须明确标记测试用途。

在服务器创建 APK 类型项目 `ZFlip5`、项目版本 `v1`，以 `versionCode` 为发布编号上传该目录内的两个文件，再手动发布。这里只生成本地产物，不自动上传、部署或发布。后续发布必须沿用包名和正式签名并递增 `versionCode`；签名私钥不能上传到文件分发服务器。

## 验证入口

```sh
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest :app:prepareUpdateRelease
```

在明确选定的可丢弃模拟器安装主包和设备端测试包后执行 `app-update` 场景，测试运行器拒绝物理设备：

```sh
adb -s emulator-5582 shell am instrument -w -e scenario app-update io.github.flipcover.controls.test/io.github.flipcover.controls.UiSmokeInstrumentation
```

本地检查覆盖流式大小/哈希、取消、目录字段与路径、真实已签名 APK 元数据、签名证书比较及页面取消。三星外屏系统授权、安装器路由、成功覆盖安装后的配置/服务/原生卡片保留，需要独立物理真机验证。

## 更新操作区

更新卡片仅保留一行两个按钮：主按钮根据状态提供检查、下载或安装；处理期间显示进度状态并禁用重复操作，第二按钮用于取消。发现可安装新版后第二按钮为“重新检查”，其他空闲状态为“返回”。安装授权和系统安装确认仍由用户处理；返回首页快捷入口不会自动下载或安装。
