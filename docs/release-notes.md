MDTerm for Android 12+ (arm64-v8a, Vulkan 1.1 level 1), based on [Termux v0.119.0-beta.3](https://github.com/termux/termux-app/tree/v0.119.0-beta.3).

- Ghostty `libghostty-vt` terminal core and Vulkan presentation, adapted from [XYenon/termux-app-ghostty](https://github.com/XYenon/termux-app-ghostty), with Kitty images and animations. Matching `xterm-ghostty` terminfo is installed automatically.
- Material Design 3 interface, wallpaper dynamic colors, and Simplified Chinese.
- Retained MDTerm terminal controls, settings, session management and bookmarks. Normal IME composition replaces the cursor preview, with `enable-ime-input` enabled by default.
- OSC 9/777 Android notifications, OSC 9;4 progress, and OSC 7501 task status on session cards with details and dismissal of finished tasks.
- Terminal search from the menu or Ctrl+Alt+F, and Open link / Copy link actions for OSC 8 hyperlinks.
- Background scrollback compression under Android memory pressure, and automatic loading of the Nushell Ghostty integration module.
- Uses the baseline's `2025.03.28-r1+apt-android-7` bootstrap and second-stage initialization. Kitty images use the Ghostty renderer; the later upstream Termux Sixel/iTerm implementation is not included.
- Retains security fix [3b66f879](https://github.com/termux/termux-app/commit/3b66f8799635a4dba4a206563048ff0e6792c487): `RunCommandService` checks `allow-external-apps` before writing result files.
- Retains JNI working-directory string release fix [084d709f](https://github.com/termux/termux-app/commit/084d709f) and `TermuxOpenReceiver` missing-path fix [b4a2c32c](https://github.com/termux/termux-app/commit/b4a2c32c).
- Official Termux packages run directly with the `com.termux` package name; no bundled PRoot runtime.
- Keeps MDTerm's Gradle 9.2.1, AGP 8.13.2, and Material Components 1.12.0 customizations; release builds use code shrinking and a dedicated MDTerm signing key.

The APK and SHA-256 checksum are built and verified by GitHub Actions after unit tests pass.

此版本使用 `com.termux` 包名，面向 Android 12 及以上、支持 Vulkan 1.1（level 1）的 ARM64 设备，直接运行官方软件包。终端使用 Ghostty 核心并支持 Kitty 协议图片。
发布密钥与原版 Termux 和本项目 debug 测试密钥不同，不能直接覆盖这些安装。
更换签名前请备份数据；卸载会删除应用私有数据。插件也需要匹配的签名。
旧共存版 `com.ericlee.mdterm` 的数据需要手动导出、恢复。
