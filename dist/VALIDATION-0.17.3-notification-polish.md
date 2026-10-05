# 通知中心优化 · 0.17.3

安装包：[flipcover-controls-0.17.3-notification-polish-debug.apk](flipcover-controls-0.17.3-notification-polish-debug.apk)。沿用调试包身份及版本，不变更权限或配置。

## 改动

- 卡片改用现有18dp模糊纹理，减轻白色覆层；侧滑离开列表的边缘加入最多12dp渐隐，宽度随位移增加。
- 设置和清除取消文字，图标居中；保留44dp触摸区域、通知／分组清除语义和完整无障碍描述。
- 应用图标和展开箭头垂直居中。箭头不再使用玻璃底或白色选中状态，展开状态以方向及无障碍描述表达。
- 修复边界回弹残留吞掉原生惯性的问题；向外拉伸后反向滑入列表时，耗尽拉伸即可继续滚动和甩动。保持原有70%弹性强度及面板边界收起条件。

## 本地验证

- `assembleDebug`、`assembleDebugAndroidTest`、`testDebugUnitTest`、`lintDebug` 成功；129项单元测试通过，Lint为0错误、169警告。
- 同一长列表触摸用例在修复前复现：底部回弹后反向甩动，松手后滚动距离为0；修复后顶部／底部、静止／回弹中／反向拖动六种组合的20项检查通过。
- 原生通知整页278项、侧滑152项（包含渐隐像素检查）、原有通知与面板回归27项、通知玻璃渲染7项检查通过。
- 交付APK与构建产物字节一致。SHA-256：`b02f8162101162ee710ca62c8263957fc3f32b3cde736127eb255bcb8cad3f4b`。
- 日志和原始截图位于 `Cache/tests/notification-polish/`。

玻璃总检查未全部通过：一次运行受模拟器全屏提示遮挡，后续竖屏检查出现系统栏区域像素差异；横屏运行通过背景阶段后在控制中心编辑网格的边缘拖拽检查失败。未修改这些范围的实现，也不将总检查标为通过。本次通知材质、布局、展开按钮和侧滑已由独立场景 `notification-glass`、`notification-swipe` 验证。

以上为本地构建及可丢弃模拟器验证，未安装到三星手机，未完成物理外屏的惯性手感、触摸路由或原生宿主验收。

## 效果图

采用正式原生通知组件和本地测试背景，非手机实时通知。原始截图按开孔裁切，最上层仅叠加一次项目标准设备贴图。

- [侧滑操作与边缘渐隐](notification-polish/notification-actions-framed.png)
- [展开状态与图标对齐](notification-polish/notification-expanded-framed.png)
