# 0.18.18

• 修复三星应用兼容缩放使外屏被误判为大屏，导致卡片提示“未找到可用外屏”的问题。
• 原生卡片和快捷栏由同一个服务入口确定启动目标；问题诊断同时显示应用尺寸与物理模式尺寸。

Source: `14cb59902e9fc4a3e339a043e780723a91aef428`

APK signature, identity, size and SHA-256 verified. Device acceptance must be recorded before stable publication.

## 根因与修复

在 SM-F731U1 / Android 16 上读取0.18.17实际诊断：Activity报告外屏1496×1440，系统物理模式为748×720；前者超过自动识别的150万像素上限。原生入口因此把target=-1传给服务，而服务同时识别selected=1；锁屏和设备认证锁定均为false。部分非应用入口同样报告display_missing，设置页也显示未找到外屏。

自动识别改用Display.Mode物理宽高，运行时几何仍使用原实现。两宿主的普通应用目标统一由CoverService解析，卡片不再传入预先解析的target。服务未运行时使用应用Context识别并进入共享启动逻辑。保留手动选屏、主屏/私有屏排除、系统锁屏策略及旧请求取消；不改显示分辨率、不自动授权。

## 验收与交付

- APK：20,440,509字节；SHA-256：`ad78eb92b8fa332b84e4a4cb68f01a8d479b8265f7a992d0578a415fe644fbc5`，正式签名及catalog一致性校验通过。
- 本地：Debug/Release单元测试各189项通过；Debug Lint、Release构建/Lint、工程检查通过；独立只读代码复核通过。
- 模拟器：安装本目录最终Release APK，`app-launch-reuse` 32项、`launcher-widget` 846项断言通过，覆盖自动选屏及Activity/非Activity上下文一致性、两宿主启动、过期中转参数、异步取消与诊断复制。
- 三星真机：已同签名覆盖安装到报告故障的SM-F731U1，版本为0.18.18/76；服务恢复。修复后的连续三次原生卡片请求均为target=1、selected=1，系统接受启动，不再出现此前target=-1的误判。尚未取得每次目标应用页面实际可见的独立确认，不将系统接受请求等同于全部固件/锁屏兼容性通过。
- 本次未上传更新服务器或切换在线catalog。现有配置与授权保留，未在真机运行会重置偏好的模拟器测试套件。
