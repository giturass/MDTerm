MDTerm for Android 12+ (arm64-v8a), based on Termux.

- Material Design 3 interface, wallpaper dynamic colors, and Simplified Chinese.
- Updated terminal controls, settings, session management, and IME composing preview.
- Official Termux packages run directly with the `com.termux` package name; no bundled PRoot runtime.
- Built with the release build type, code shrinking enabled, and a dedicated MDTerm signing key.

The APK and SHA-256 checksum are built and verified by GitHub Actions after unit tests pass.

此版本使用 `com.termux` 包名，面向 Android 12 及以上的 ARM64 设备，直接运行官方软件包。
发布密钥与原版 Termux 和本项目 debug 测试密钥不同，不能直接覆盖这些安装。
更换签名前请备份数据；卸载会删除应用私有数据。插件也需要匹配的签名。
旧共存版 `com.ericlee.mdterm` 的数据需要手动导出、恢复。
