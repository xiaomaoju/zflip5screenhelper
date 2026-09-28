# 0.9.2 卡片留白与左下角清理 · 候选验证版

[安装包](FlipCover-0.9.2-debug.apk) · [SHA-256](FlipCover-0.9.2-debug.apk.sha256) · [最终效果图](0.9.2-ui/compact-tasks-framed.png)

包名 `io.github.flipcover.controls.debug`，versionCode 18，versionName 0.9.2，沿用本项目调试签名。没有新增权限或配置迁移。APK SHA-256：`316f6e8f02a3f7bf97beca0f0466f8dae3f96410edbebc2a0f4fdd4b10ab3f47`。

## 本次调整

- 三张完整任务卡片并排，目标宽高比为2∶3，不再填满可用高度；窄屏以112dp的最小目标高度保护标题和预览，最终高度仍受安全空间限制。优先居中，上下留白；空间紧张时上移，避免覆盖下方按钮。
- 清理入口移到下方留白的物理左下角，仅显示20dp的×，保留48dp触摸目标。它叠放在任务区域空白处，不增加底栏、不覆盖卡片；点击直接清理当前列表中可关闭的后台任务。
- 保留左右翻组、任意卡片点击恢复、上滑关闭、下滑锁定／解锁。单项与批量清理继续跳过锁定、固定和可见任务。锁定仅防本工具清理，不是系统保活。
- 系统快照保持等比显示；未修改任务读取、恢复、删除、身份校验或快照后端。相机缺口及显示器安全范围沿用现有规则。

## 本地验证

构建命令沿用README的JDK与SDK环境：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest :app:lintDebug
```

- Debug与测试APK构建成功；40项单元测试通过；Lint 0错误、38警告。新增的一项是左下角按钮采用物理 `Gravity.LEFT` 的RTL提示，此处按明确的左下角位置要求保留。
- 独立Android 16模拟器：720×748、340dpi、字体1.0通过84项任务页断言；440dpi、字体1.3的0°／90°／180°／270°各通过84项，共420项。检查包含三卡同屏、上下留白、清理按钮位于卡片下方且没有独立底栏、批量保护和手势；同时保留取消、短滑、斜滑、双指、刷新期间身份保护及预览生命周期检查。
- 目视检查标准密度及大字体的原生组件渲染，卡片、标题、预览、锁定标识和角落按钮均可见；最终720×748效果图经标准外框合成后再次检查。
- 最终APK包名、版本和签名验证通过。签名SHA-256：`d21ba7be36cb38e821a1ae98d7d220724bc5b8bed33de38e1ca17b222a371bf0`。

本轮日志位于 `Cache/tests/recents-0.9.2/`：`build.log`、`ui-340.log`、`ui-440-r0.log`至`ui-440-r3.log`。构建、单元测试与Lint报告位于 `Cache/build-output.nosync/app/`。任务后端未变，本轮未重复后端专项；其此前本地检查见[0.9.1验证记录](VALIDATION-0.9.1.md)。

## 效果图与真机边界

`0.9.2-ui/compact-tasks-framed.png` 使用原生组件的样例任务和图标回退画面，最上层仅叠加一次 `device-frames/zflip5-cover-overlay.svg`，保留外框、双摄、闪光灯和缺口；SVG版本内嵌同一画面与贴图。

此图不是物理Flip5截图。当前未连接真机，三星One UI窗口路由、Shizuku连接与快照，以及真实缺口附近的触摸和系统手势共存仍待验证。本地通过不等于三星真机验收完成。
