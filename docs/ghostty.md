# Ghostty integration

MDTerm uses `libghostty-vt` for terminal emulation and the Android Vulkan renderer
adapted from [XYenon/termux-app-ghostty](https://github.com/XYenon/termux-app-ghostty/tree/9bdfe0b90bbe3e21cd6070e488d9d38abe3af3e9).
The application requires Android 12+, ARM64, and Vulkan 1.1 with Android Vulkan
feature level 1. Its package name, Termux bootstrap and software package paths
remain unchanged.

## Runtime

`TerminalSession` owns a `GhosttyTerminal` JNI handle and the Termux PTY. The
native core parses output, encodes keys, mouse and paste input, and manages
scrollback and selection. Finished sessions retain their contents until removed;
removal closes the native terminal after the PTY has finished.

`TerminalView` uses an Android `SurfaceView` and a dedicated render executor.
FreeType and HarfBuzz rasterize text and shape glyphs; the renderer composes the
frame and presents it with Vulkan. This is the reference project's Android
renderer, rather than Ghostty's desktop application or window system.

The integration supports Kitty graphics, including PNG/RGB/RGBA images and
animations, with a 64 MiB image storage limit for each primary/alternate screen. It also retains the
reference's synchronized output handling. Sixel and iTerm images are not
implemented by this renderer. Terminal programs must select Kitty output to
display images.

MDTerm's Material interface, theme palette, cursor gestures, immediate text
selection, and optional IME composition preview are preserved. Uncommitted input
is drawn over the Vulkan surface using the native cursor position and cell
colors; it is only sent to the PTY when committed. The `OSC 777;mdterm;...`
bookmark response is forwarded through Ghostty's unknown-sequence callback,
with the existing request nonce and path validation in `TerminalSession`.

OSC clipboard writes retain the app's text clipboard behavior. Clipboard read
requests are denied by default. The additional notification, progress and
program-status UI from the reference fork is not included.

## Sources and toolchain

| Dependency | Commit |
| --- | --- |
| Ghostty | `7ec9e26a29399b30ef78f06985fc75045b3cecb6` |
| FreeType | `0a0221a1347e2f1e07c395263540026e9a0aa7c7` |
| HarfBuzz | `36cb489cb02ce4b92099669ba9f9bea348eff93f` |
| libpng | `3061454d980de7d53608f594194cfac722721d2a` |

The source repositories and pins are declared in [build.gradle](../build.gradle).
The JNI bridge, renderer and Android patch originate from reference commit
`9bdfe0b90bbe3e21cd6070e488d9d38abe3af3e9`. MDTerm adds the bounded bookmark
callback, native cursor state used by its IME overlay, and CJK glyph width
adaptation. Keep the C API, Android patch, JNI bridge and terminfo in sync when
updating the Ghostty pin.

The build uses Java 17, Gradle 9.2.1, AGP 8.13.2, SDK 36, NDK 29.0.14206865,
CMake 3.22.1, Zig 0.16.0, Git, Bash and ncurses `tic`. The GitHub workflows
install Zig and CMake and cache pinned native sources. Native libraries are
linked with support for 16 KiB Android memory pages.

Gradle prepares sources under `build/native-sources`, applies
[`native/patches/ghostty-android.patch`](../native/patches/ghostty-android.patch)
in a separate generated checkout, builds `libghostty-vt.a`, then links it into
`libtermux.so` with the renderer. Native compilation depends on the single
`buildGhosttyAndroid` task, including when debug and release are requested
together. Existing source edits are rejected instead of silently overwritten.

`ZIG` may point to a custom Zig executable. `GHOSTTY_BUILD_JOBS` limits native
compilation parallelism (default 4). `TERMUX_APP_ABIS` retains MDTerm's
`arm64-v8a` restriction. Gradle `--offline` requires the pinned source checkouts
and toolchain dependencies to have already been downloaded.

## Terminfo and fonts

The `buildGhosttyTerminfo` task generates a compiled `xterm-ghostty` entry from
the same Ghostty revision and packages it with the source description. On
startup, and after bootstrap installation, MDTerm atomically installs this
entry at `$PREFIX/share/terminfo/x/xterm-ghostty`. Unchanged entries are left
alone. Ordinary new sessions use `TERM=xterm-ghostty` when a valid entry is
present; failsafe sessions and unsuccessful installations use `xterm-256color`.

The bundled JetBrains Mono font is copied to the app cache for FreeType.
`~/.termux/font.ttf` overrides it. Fonts in `~/.termux/fonts` are appended in
filename order, followed by the native renderer's Android system font fallback.
Explicit `colors.properties` values still override wallpaper theme defaults.

Ordinary CJK outline glyphs are fitted horizontally to their two terminal cells
before rasterization. The scale uses the complete shaped grapheme's advance,
preserving proportional side bearings and punctuation spacing. This avoids
extra gaps when the primary Latin font and the CJK fallback have different
width proportions. Glyph height, baseline, and terminal grid positions remain
unchanged. Color fonts, emoji, and bitmap-only fonts retain their existing sizing.

## Validation

All compilation, tests that require compilation, and APK builds run in GitHub
Actions. Local work is limited to source edits and static checks.

The [test workflow](../.github/workflows/run_tests.yml) runs Java/Robolectric
tests and an independent native job. That job compiles Android JNI with the
pinned NDK and runs Ghostty's `test-lib-vt` suites on the Linux host, using the
same patched source as Android. These cover terminal parsing, keyboard/paste,
resize/scrollback, Kitty image decode/storage/placements, and the added OSC 777
regressions. Host tests do not exercise the Android Vulkan driver or PNG hook.
The native job also runs CJK font fitting tests with the pinned FreeType and
HarfBuzz libraries, covering actual glyph rasterization, punctuation, combining
marks, and unchanged glyphs outside the CJK fitting path.

The [debug](../.github/workflows/debug_build.yml) and
[release](../.github/workflows/release.yml) workflows build APKs and verify their
ARM64 library, every Ghostty JNI export, Vulkan linkage, 16 KiB load-segment
alignment, compiled terminfo and dependency licenses. Test reports and native
build diagnostics are uploaded as workflow artifacts. Check the workflow run
for the tested commit before treating the integration as validated.

The legacy Java emulator remains available for its parser regression suite and
as a Robolectric test fixture. It is not the runtime terminal backend. UI tests
exercise MDTerm's input, gestures, selection and session behavior through a
shadow of the native terminal; they do not establish Vulkan driver compatibility.

Use a CI-built APK on a compatible Android device to check Kitty images and
animation, IME preview, custom fonts, theme reload, rotation, session switching,
and rapid session closure while output is arriving. Use the workflow artifacts
for those device checks; do not build on the local Termux device.

Native dependency licenses are included under `assets/licenses` in the APK;
see [LICENSE.md](../LICENSE.md).
