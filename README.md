# MDTerm

[![Release APK](https://github.com/giturass/MDTerm/actions/workflows/release.yml/badge.svg)](https://github.com/giturass/MDTerm/actions/workflows/release.yml)
[![Unit tests](https://github.com/giturass/MDTerm/actions/workflows/run_tests.yml/badge.svg)](https://github.com/giturass/MDTerm/actions/workflows/run_tests.yml)

MDTerm 是基于 [Termux v0.119.0-beta.3](https://github.com/termux/termux-app/tree/v0.119.0-beta.3) 的 Android 终端应用，面向 **Android 12 及以上、支持 Vulkan 1.1 的 ARM64 设备**，在保留 Termux 原生运行环境的基础上，重新设计界面、配色和日常操作。

这是独立维护的衍生项目。应用使用原版包名 `com.termux`，会替代原版 Termux，不能与其共存。安装前请阅读下方的[安装与迁移](#安装与迁移)。

[下载发布版](https://github.com/giturass/MDTerm/releases) · [反馈问题](https://github.com/giturass/MDTerm/issues) · [运行环境与签名说明](docs/mdterm-runtime.md)

## 与上游 Termux 的区别

应用运行环境以上游 `v0.119.0-beta.3` 为基础，终端核心和 Vulkan 渲染移植自 [termux-app-ghostty](https://github.com/XYenon/termux-app-ghostty)。保留 MDTerm 的界面、书签和光标手势，并接入普通输入法组合输入、终端搜索和程序任务状态。

| 方面 | 上游 Termux | MDTerm 当前实现 |
| --- | --- | --- |
| 系统与架构 | 覆盖更广的 Android 版本和设备架构 | 最低 Android 12（API 31），仅 `arm64-v8a` |
| 终端核心与图像 | Java 终端模拟器与 Android Canvas 渲染 | Ghostty `libghostty-vt` 核心、Vulkan 呈现，支持 Kitty 图像协议（含动画） |
| 应用界面 | 原有终端界面、菜单和偏好设置 | Material Design 3 风格，重做会话列表、菜单、设置、图标与通知样式 |
| 配色 | 使用原有主题与终端颜色配置 | 支持壁纸动态配色，统一终端、控件与设置颜色，并调整浅色模式下默认 ANSI 颜色的可读性 |
| 会话与终端操作 | 原有会话和终端操作入口 | 提供会话卡片、活动指示、长按改名、光标滑动手势、横向滚动工具栏和 MD3 操作菜单 |
| 设置组织 | 原有多层偏好设置 | 将终端设置集中到一级设置页，按显示、键盘与输入等类别组织 |
| 输入法组合输入 | 取决于上游版本是否包含相关改动 | 支持普通输入法组合输入，可配置 `enable-ime-input`，默认开启 |
| APK 发布 | 由各上游分发渠道构建和签名 | 本仓库 Actions 构建，发布版使用独立 MDTerm 签名，并提供 SHA-256 校验文件 |

界面跟随系统语言，包含简体中文。已有的 `~/.termux/colors.properties` 显式颜色配置优先于动态配色提供的默认值。

默认终端字体随应用内置，`~/.termux/font.ttf` 可覆盖默认字体。首次启动或升级后，如果 `~/.termux/motd.sh` 不存在，应用会安装带有自适应 MDTERM 字标、欢迎语和项目链接的默认登录欢迎脚本；已有脚本会保留。可用 `~/.hushlogin` 关闭登录欢迎消息。

### 沿用的运行环境

MDTerm 使用 Ghostty 处理终端协议，继续沿用 Termux 的 PTY 会话与软件包运行方式：

- Android 包名为 `com.termux`。
- 软件包前缀为 `/data/data/com.termux/files/usr`，主目录为 `/data/data/com.termux/files/home`。
- 官方 Termux 软件包直接在应用自身的运行环境中执行，使用 `pkg` / `apt` 管理。
- 首次初始化使用该版本的官方 bootstrap `2025.03.28-r1+apt-android-7` 及第二阶段初始化流程；构建时下载并校验其 SHA-256。
- 不内置 PRoot 兼容层，不进行路径翻译或 Intent 重写。

基线之外还保留上游的外部命令结果文件权限检查（[3b66f879](https://github.com/termux/termux-app/commit/3b66f8799635a4dba4a206563048ff0e6792c487)）、JNI 工作目录字符串释放（[084d709f](https://github.com/termux/termux-app/commit/084d709f)）及 `TermuxOpenReceiver` 无路径 URI 处理（[b4a2c32c](https://github.com/termux/termux-app/commit/b4a2c32c)）修复。

构建工具与界面依赖保留 MDTerm 定制，继续使用 Gradle 9.2.1、AGP 8.13.2 和 Material Components 1.12.0，详见[构建说明](docs/mdterm-runtime.md#building)。

MDTerm 的变化覆盖终端核心、渲染、应用界面与交互层，并未建立独立的软件包仓库。软件包相关资料仍可参考 [Termux 软件包项目](https://github.com/termux/termux-packages)和[包管理文档](https://github.com/termux/termux-packages/wiki/Package-Management)。

## Ghostty 终端与图片

应用默认使用 Ghostty 核心，并通过 Vulkan 呈现终端文字和 Kitty 协议图片。支持滚动历史、备用屏幕、原生键盘与鼠标编码、同步输出，以及 Kitty 图片和动画。主屏幕和备用屏幕的图片存储上限各为 64 MiB；显示效果也取决于终端程序是否使用 Kitty 图像协议。Sixel 和 iTerm 图像协议不在此次移植范围内。

收到 Android 内存压力通知时，应用会在后台分批压缩滚动历史，优先处理占用较大的会话。历史内容保留，访问时自动解压，无需配置。

支持与上游一致的 OSC 9／777 Android 通知，以及 OSC 9;4 进度条。通知和进度只响应应用当前可见的会话；OSC 7501 程序任务状态数据保存在对应会话中，会话卡片保留原有样式与交互。协议示例见 [Ghostty 接入说明](docs/ghostty.md#notifications-progress-and-task-status)。

应用会自动安装匹配的 `xterm-ghostty` terminfo，新建普通会话的 `$TERM` 为 `xterm-ghostty`。若安装失败或进入故障安全会话，则使用 `xterm-256color`。远程主机如不认识 `xterm-ghostty`，需要在远端安装对应 terminfo，或为该 SSH 会话使用 `TERM=xterm-256color`。

终端操作菜单中的“上一个提示符”和“下一个提示符”，以及 `Ctrl+Alt+PageUp` / `Ctrl+Alt+PageDown`，可在带有 OSC 133 标记的命令提示符之间跳转。应用为 Bash、Zsh、Fish、Nushell 安装自动加载的 Ghostty 集成，不修改 HOME 配置；Elvish 需执行 `use ghostty-integration`。升级或安装新 shell 后，请重启应用并新建会话；没有标记的旧输出无法回溯跳转。自定义启动配置及其他限制见 [shell 集成说明](docs/ghostty.md#prompt-navigation-and-shell-integration)。

字体仍可通过 `~/.termux/font.ttf` 覆盖；`~/.termux/fonts/` 内的 `.ttf`、`.otf`、`.ttc` 文件按文件名顺序作为后备字体。配色、复制粘贴、光标手势和输入法选项可在 MDTerm 设置中调整。

移植来源、固定版本、构建依赖与验证方法见 [Ghostty 接入说明](docs/ghostty.md)。

## 安装与迁移

### 安装要求

- Android 12 或更高版本。
- ARM64（`arm64-v8a`）设备，支持 Vulkan 1.1（Android Vulkan level 1）；当前构建不提供 ARM32 或 x86 APK。
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
- **光标控制方式**：“设置 → 键盘与输入 → 手势控制光标”默认开启。关闭后，工具栏显示上、下、左、右方向键，终端恢复普通滚动；返回终端即可生效。重新开启后恢复手势工具栏，自定义快捷键配置会保留。
- **横向移动光标**：手势控制开启时，在终端内容区域左右滑动，发送左、右方向键，方便调整命令行中的编辑位置。
- **纵向光标操作**：手势控制开启时，点击工具栏中的上下箭头＋锁头图标（“纵向光标手势”）启用后，在终端内容区域上下滑动会发送上、下方向键；在 Shell 中通常用于切换历史命令，在编辑器中按程序规则移动光标。再次点击图标关闭，恢复普通纵向滚动。此开关会保持启用，不会因按下其他按键自动关闭。
- **工具栏滚动**：在工具栏区域横向滑动，可查看未显示的快捷键；光标手势在终端内容区域操作。
- **文本选择与复制粘贴**：双击或长按终端文字可直接选词，拖动两端选择手柄调整范围，再点击浮动工具栏中的“复制”或“粘贴”；点击“更多”可打开终端操作菜单。单击终端仍可呼出键盘；终端程序启用鼠标跟踪时，使用长按选择文字。
- **超链接操作**：选中终端程序通过 OSC 8 输出的超链接后，点击“更多 → 打开链接／复制链接”；鼠标右键也可直接打开对应链接的菜单。
- **终端搜索**：在终端操作菜单中选择“查找终端内容”，或按 `Ctrl+Alt+F`。输入文字后按搜索／回车，用上、下按钮浏览较早、较新的匹配结果；关闭搜索会清除高亮。
- **终端偏好**：打开设置，在一级页面中调整显示、键盘与输入等选项。
- **输入法组合输入**：“设置 → 键盘与输入”中的“输入法组合输入”默认开启，也可在 `~/.termux/termux.properties` 中设置 `enable-ime-input=false` 关闭。未提交的文字由输入法管理，提交后发送到终端。需要“按字符输入”兼容处理时，先关闭此选项；返回终端后生效。
- **自定义终端颜色**：编辑 `~/.termux/colors.properties`；显式设置的颜色优先。

光标手势模拟方向键，具体效果由当前终端程序决定；文本选择模式下不触发光标滑动手势。

Android 对后台进程和子进程的系统限制仍然适用，MDTerm 不保证终端任务能绕过系统的进程管理。

## 问题反馈与上游资料

MDTerm 的界面、交互、安装及构建问题，请提交到[本仓库 Issues](https://github.com/giturass/MDTerm/issues)，并附上应用版本、Android 版本、设备型号和复现步骤。日志中如含个人信息，请先脱敏。

通用资料可参考：

- [上游 Termux 源码](https://github.com/termux/termux-app)
- [Termux Wiki](https://github.com/termux/termux-app/wiki)
- [Termux 软件包与包管理](https://github.com/termux/termux-packages)
- [通过 RUN_COMMAND 从其他应用执行命令](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent)

## 许可与致谢

MDTerm 基于 Termux 及其贡献者的工作，沿用项目的 **GPLv3-only** 许可及相关组件的许可例外，详见 [LICENSE.md](LICENSE.md) 和 [termux-shared/LICENSE.md](termux-shared/LICENSE.md)。Ghostty Android 适配、Vulkan 渲染和终端辅助功能基于 [XYenon/termux-app-ghostty](https://github.com/XYenon/termux-app-ghostty)，并使用 Ghostty、FreeType、HarfBuzz 和 libpng；相应许可证随 APK 一同提供。
