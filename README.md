# MixFlipMod

小米 MIX Flip / MIX Flip 2 通用增强模块

不只针对外屏：在改善外屏体验之外，也覆盖系统框架、桌面、输入法与全局搜索等场景。

支持 HyperOS 3.0 及以上，需要配合支持 libxposed API 101 的 Xposed 框架使用。

应用包名为 `com.parallelc.mixflipmod`。与原包名版本属于不同应用，安装后需要在 LSPosed 中重新启用并设置作用域，原版本的设置不会自动迁移；请避免同时启用两个版本。

## 功能

**系统框架**

- 解除外屏应用限制、所有应用合盖继续使用
- 自定义外屏输入法
- 自定义外屏应用渲染模式（全屏 / 不缩放 / 自定义缩放比例）
- 外屏隐藏状态栏（独立开关，隐藏后应用铺满顶部）
- 未适配应用外屏摄像头区域黑色背景
- 修复外屏键盘弹出时画面上移（默认关闭，仅默认/缩放兼容模式；开关后重新打开目标应用）
- 隐藏外屏状态栏时钟

**外屏桌面**

- 空白处双击锁屏
- 屏蔽特殊应用外屏启动页
- 最近任务样式（纵向/横向）与长按功能
- 应用桌面长按菜单增加「应用信息」

**系统界面**

- 恢复外屏通知长按菜单、控制中心样式
- 调整外屏通知图标最大数量

**全局搜索**

- 搜索键直接打开第一个已安装应用结果，未命中时保持原生
- 网页搜索交由系统默认浏览器打开
- 可选搜索引擎：百度 / 必应 / Google / 搜狗

**输入法与智能助理**

- 搜狗输入法小米版：恢复外屏工具栏、剪贴板候选词
- Gboard：外屏摄像头避让（无需替换 Gboard）
- 智能助理：支持添加外屏小部件

> 系统应用推荐缩放比例：电话 70%、短信 80%、相册 80%、时钟 70%、日历 70%、计算器 70%

## 构建

JDK 17 + Android SDK 37：

```bash
./gradlew :app:assembleDebug        # 可调试，未启用 R8
./gradlew :app:assembleUnminified   # 未启用 R8，用于性能对比（调试签名）
./gradlew :app:assembleRelease      # 启用 R8
```

产物位于 `app/build/outputs/apk/` 下的同名目录。

推送到 `main`、推送 `v*` 标签或手动运行 Actions 时，会按 `app/build.gradle.kts` 的版本构建并发布 Release（`-beta` 为 Pre-release，标签需与版本一致，如 `3.1.0` → `v3.1.0`）。

## 权限说明

读取已安装应用列表，用于显示应用名称、图标，并为按应用生效的功能提供选择列表。

## 致谢

实现思路参考 [KernelSU](https://github.com/tiann/KernelSU)、[HyperCeiler](https://github.com/ReChronoRain/HyperCeiler)、[AndroidX Compose Material Icons](https://android.googlesource.com/platform/frameworks/support/+/androidx-main/compose/material/material-icons-extended/)（本地图标保留 Apache-2.0 声明）。

## 许可

AGPL-3.0，详见 [LICENSE](LICENSE)
