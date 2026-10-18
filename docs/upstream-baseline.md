# Official upstream baseline

MDTerm is based directly on the official
[Termux v0.119.0-beta.3](https://github.com/termux/termux-app/tree/v0.119.0-beta.3)
source at commit
[`e634d8f981f48b6b89202cf0e04533f0889e03b3`](https://github.com/termux/termux-app/commit/e634d8f981f48b6b89202cf0e04533f0889e03b3).
This commit is the source reference for this migration.

| Layer | Source and retained MDTerm behavior |
| --- | --- |
| Terminal emulator | Official beta.3 Java emulator, buffer, rows and session APIs; retain the OSC bookmark-location query integration. |
| Terminal rendering and input | Official beta.3 Java/Canvas renderer and terminal view, with existing MDTerm cursor gestures, IME composing preview and text-selection behavior. |
| Shared library | Official beta.3 `termux-shared` structure and APIs, with existing Material dialogs, extra-key styling, settings/preferences, dynamic colors and report presentation. |
| Shell runtime | Official beta.3 `TermuxShellManager`, `TermuxSession`, `AppShell`, execution runners, shell creation modes and environment export. Keep MDTerm session close/stop actions and empty-drawer behavior. |
| Android integration | Official beta.3 file receivers, system events, plugin and crash helpers, and shared permission APIs; keep the existing command/file security boundaries and All Files Access return handling. |
| Application UI | Preserve the current MDTerm activities, menus, settings, bookmarks, unified drawer, toolbar, colors, fonts, icons, notifications and default MOTD. Adapt their calls to the beta.3 APIs. |
| Native bootstrap | Official beta.3 bootstrap loader, with the current checksum-verified 2026 aarch64 package archive. |

The extra Sixel, iTerm2 inline-image and Bitmap graphics extensions introduced
after beta.1 have been removed. Rendering uses the Java/Canvas text-terminal
path. No alternate terminal engine is introduced.

## Build and distribution choices

The app remains `com.termux`, with the original Termux prefix and home paths.
Official packages run under the application's UID. The current Android 12+
(`minSdkVersion=31`), target SDK 28 and ARM64-only configuration remains.
Build tooling remains Gradle 9.2.1, Android Gradle Plugin 8.13.2, compile SDK 36,
and NDK 29.0.14206865.

The app and terminal libraries identify this baseline as `0.119.0-beta.3`.
Android version code remains `1022`. The package variant is `apt-android-7`,
with beta.3 BuildConfig metadata recording the bootstrap's Android 7/API 24
minimum separately from MDTerm's Android 12 minimum.

The bundled archive remains official
[`2026.02.12-r1+apt.android-7`](https://github.com/termux/termux-packages/releases/tag/bootstrap-2026.02.12-r1%2Bapt.android-7),
with SHA-256
`ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3`.
Release signing continues to use the independent MDTerm key supplied through
environment variables; debug builds use the repository's public test key.

See [retained fixes](upstream-fixes.md) for security boundaries and source checks,
and [runtime and signing](mdterm-runtime.md) for build commands, installation,
permissions and device-validation steps. A source migration or successful local
Java check does not establish a passing full release build or device run.
