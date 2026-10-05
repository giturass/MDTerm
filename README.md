# MDTerm

[![Release APK](https://github.com/giturass/MDTerm/actions/workflows/release.yml/badge.svg)](https://github.com/giturass/MDTerm/actions/workflows/release.yml)
[![Unit tests](https://github.com/giturass/MDTerm/actions/workflows/run_tests.yml/badge.svg)](https://github.com/giturass/MDTerm/actions/workflows/run_tests.yml)

MDTerm 是基于 [Termux](https://github.com/termux/termux-app) 的 Android 终端应用，面向 **Android 12 及以上的 ARM64 设备**，在保留 Termux 原生运行环境的基础上，重新设计界面、配色和日常操作。

这是独立维护的衍生项目。应用使用原版包名 `com.termux`，会替代原版 Termux，不能与其共存。安装前请阅读下方的[安装与迁移](#安装与迁移)。

[下载发布版](https://github.com/giturass/MDTerm/releases) · [反馈问题](https://github.com/giturass/MDTerm/issues) · [运行环境与签名说明](docs/mdterm-runtime.md)

## 与上游 Termux 的区别

以下对照以本项目沿用的上游实现为基础；上游后续版本可能继续变化。

| 方面 | 上游 Termux | MDTerm 当前实现 |
| --- | --- | --- |
| 系统与架构 | 覆盖更广的 Android 版本和设备架构 | 最低 Android 12（API 31），仅 `arm64-v8a` |
| 应用界面 | 原有终端界面、菜单和偏好设置 | Material Design 3 风格，重做会话列表、菜单、设置、图标与通知样式 |
| 配色 | 使用原有主题与终端颜色配置 | 支持壁纸动态配色，统一终端、控件与设置颜色，并调整浅色模式下默认 ANSI 颜色的可读性 |
| 会话与终端操作 | 原有会话和终端操作入口 | 提供会话卡片、活动指示、长按改名、横向滚动工具栏和 MD3 操作菜单 |
| 设置组织 | 原有多层偏好设置 | 将终端设置集中到一级设置页，按显示、键盘与输入等类别组织 |
| 输入法预编辑 | 取决于上游版本是否包含相关改动 | 已纳入上游 [PR #5242](https://github.com/termux/termux-app/pull/5242) 的可选组合文本预览，默认关闭 |
| APK 发布 | 由各上游分发渠道构建和签名 | 本仓库 Actions 构建，发布版使用独立 MDTerm 签名，并提供 SHA-256 校验文件 |

界面跟随系统语言，包含简体中文。已有的 `~/.termux/colors.properties` 显式颜色配置优先于动态配色提供的默认值。

### 沿用的运行环境

MDTerm 继续使用 Termux 的终端模拟器、会话与软件包运行方式：

- Android 包名为 `com.termux`。
- 软件包前缀为 `/data/data/com.termux/files/usr`，主目录为 `/data/data/com.termux/files/home`。
- 官方 Termux 软件包直接在应用自身的运行环境中执行，使用 `pkg` / `apt` 管理。
- 首次初始化使用官方 bootstrap；构建时下载并校验其 SHA-256。
- 不内置 PRoot 兼容层，不进行路径翻译或 Intent 重写。

因此，MDTerm 的主要变化在应用界面与交互层，并未建立独立的软件包仓库。软件包相关资料仍可参考 [Termux 软件包项目](https://github.com/termux/termux-packages)和[包管理文档](https://github.com/termux/termux-packages/wiki/Package-Management)。

## 安装与迁移

### 安装要求

- Android 12 或更高版本。
- ARM64（`arm64-v8a`）设备；当前构建不提供 ARM32 或 x86 APK。
- 首次使用及安装软件包时需要准备可用的网络连接。

从 [MDTerm Releases](https://github.com/giturass/MDTerm/releases) 下载发布 APK，可使用同一版本附带的 SHA-256 文件校验下载内容。安装并完成初始化后，可运行：

```sh
pkg update
pkg upgrade
```

### 从原版 Termux 切换

**请先备份数据。卸载应用会删除其私有目录中的文件。**

MDTerm 与原版 Termux 包名相同，但发布签名不同，通常无法直接覆盖安装：

1. 在旧应用中导出需要保留的主目录文件、配置和软件包清单。
2. 确认备份已放到卸载后仍可访问的位置，再卸载旧应用及签名不兼容的插件。
3. 安装 MDTerm，完成初始化，并从官方软件源重新安装所需软件包。
4. 恢复个人文件与配置，检查其中的绝对路径和外部应用集成。

同一签名的 MDTerm 版本之间可在满足 Android 版本更新规则时覆盖升级。Debug APK 使用仓库中的公开测试密钥，与 MDTerm Release APK 签名不兼容。

### 插件与旧共存版

Termux 插件仍需满足 Android 的签名及共享用户 ID 要求；包名相同不代表从其他渠道下载的插件能够直接使用。本仓库的应用构建也不等于已提供一整套配套插件。

早期共存版使用 `com.ericlee.mdterm`，与当前版本的数据目录及应用 UID 不同。当前 APK 无法覆盖该版本或自动读取其私有文件；请先从旧版手动导出，再恢复到新安装中。详细说明见[运行环境与迁移文档](docs/mdterm-runtime.md)。

## 常用操作

- **会话改名**：长按侧栏中的会话。
- **文本选择**：长按终端打开操作菜单，选择“选择文本”后拖动选择范围。
- **终端偏好**：打开设置，在一级页面中调整显示、键盘与输入等选项。
- **输入法组合文本预览**：在“设置 → 键盘与输入”中启用“输入法组合文本预览”，即可在光标处预览中文等输入法尚未提交的文字。该功能默认关闭。
- **自定义终端颜色**：编辑 `~/.termux/colors.properties`；显式设置的颜色优先。

Android 对后台进程和子进程的系统限制仍然适用，MDTerm 不保证终端任务能绕过系统的进程管理。

## 从源码构建

构建环境参考仓库的 [Release 工作流](.github/workflows/release.yml)：JDK 17、Android SDK Platform 36、Build Tools 35.0.0，以及 NDK `29.0.14206865`。使用项目自带的 Gradle Wrapper，并配置 Android SDK 路径。

```sh
git clone https://github.com/giturass/MDTerm.git
cd MDTerm
./gradlew :app:assembleDebug testDebugUnitTest
```

Debug APK 输出至 `app/build/outputs/apk/debug/`。构建过程会下载依赖及官方 bootstrap，需要网络访问。Debug 使用公开测试密钥，适合开发验证；日常安装请使用发布版。

构建签名 Release 的环境变量及密钥配置见[签名说明](docs/mdterm-runtime.md#release-signing)。本地未配置发布签名时，Release 构建不会自动获得 MDTerm 的发布签名。

### 发布流程

[Release APK 工作流](.github/workflows/release.yml)运行单元测试、构建 Release APK，并检查包名、最低系统版本、架构和签名，随后生成 SHA-256 校验文件。

- 推送语义化版本标签（例如 `v0.118.0-mdterm.1`）会创建 GitHub Release 并上传 APK 与校验文件。
- 在分支上手动运行工作流只上传 Actions 构建产物，不创建 Release。
- 发布说明维护在 [docs/release-notes.md](docs/release-notes.md)。

## 问题反馈与上游资料

MDTerm 的界面、交互、安装及构建问题，请提交到[本仓库 Issues](https://github.com/giturass/MDTerm/issues)，并附上应用版本、Android 版本、设备型号和复现步骤。日志中如含个人信息，请先脱敏。

通用资料可参考：

- [上游 Termux 源码](https://github.com/termux/termux-app)
- [Termux Wiki](https://github.com/termux/termux-app/wiki)
- [Termux 软件包与包管理](https://github.com/termux/termux-packages)
- [通过 RUN_COMMAND 从其他应用执行命令](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

## 许可与致谢

MDTerm 基于 Termux 及其贡献者的工作，沿用项目的 **GPLv3-only** 许可及相关组件的许可例外，详见 [LICENSE.md](LICENSE.md) 和 [termux-shared/LICENSE.md](termux-shared/LICENSE.md)。输入法组合文本预览相关贡献来源见上游 [PR #5242](https://github.com/termux/termux-app/pull/5242)。
