MDTerm for Android 12+ (arm64-v8a), based directly on official Termux v0.119.0-beta.3 (`e634d8f981f48b6b89202cf0e04533f0889e03b3`).

- Migrate the Java terminal emulator, Canvas renderer, shared library and app runtime to the official beta.3 source.
- Preserve the current MDTerm interface and operations: Material Design 3, dynamic colors, settings, bookmarks, session drawer and actions, cursor gestures, text selection, scrolling extra keys, and optional IME composing preview.
- Adopt beta.3 shell management, command runners, environment export, file receivers, plugin results, crash reporting and permission APIs.
- Remove the extra Sixel, iTerm2 inline-image and Bitmap graphics extensions introduced after beta.1.
- Keep security fixes for external-command file results, shared-file path boundaries, URI fragments and empty executable handling.
- Keep the official `2026.02.12-r1+apt.android-7` aarch64 bootstrap and SHA-256 validation, with beta.3 package-variant metadata.
- Keep Android 12+, ARM64, the `com.termux` package and native package paths, and the independent MDTerm release signing configuration.
- Add explicit 16 KiB ELF alignment for all JNI libraries and 16 KiB ZIP alignment for uncompressed APK libraries. Debug and release workflows verify the APK and every ELF file in the bundled bootstrap for 16 KiB compatibility.

Release APKs and SHA-256 files are produced by the release workflow. These notes do not assert that a full build or installed-device validation has completed for this migration; use the workflow results for the published revision.

此版本直接基于官方 Termux v0.119.0-beta.3，保留当前 MDTerm 的界面与操作，使用 `com.termux` 包名，面向 Android 12 及以上的 ARM64 设备，直接运行官方软件包。
发布密钥与原版 Termux 和本项目 debug 测试密钥不同，不能直接覆盖这些安装。
更换签名前请备份数据；卸载会删除应用私有数据。插件也需要匹配的签名。
旧共存版 `com.ericlee.mdterm` 的数据需要手动导出、恢复。
