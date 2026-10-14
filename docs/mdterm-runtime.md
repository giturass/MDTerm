# MDTerm native runtime

The upstream source baseline is [Termux v0.119.0-beta.3](https://github.com/termux/termux-app/tree/v0.119.0-beta.3),
commit `e634d8f981f48b6b89202cf0e04533f0889e03b3`. MDTerm retains its Material
interface, terminal bookmarks, and IME composing preview from upstream
[PR #5242](https://github.com/termux/termux-app/pull/5242). The terminal core now uses Ghostty `libghostty-vt` and the Vulkan renderer
adapted from XYenon/termux-app-ghostty, including Kitty images and animations.
The later upstream Termux Sixel/iTerm renderer is not included. See
[Ghostty integration](ghostty.md) for pinned revisions and native build details.

Three later upstream fixes are retained:

- [3b66f879](https://github.com/termux/termux-app/commit/3b66f8799635a4dba4a206563048ff0e6792c487): `RunCommandService` does not write to request-specified result files before verifying that `allow-external-apps` is enabled.
- [084d709f](https://github.com/termux/termux-app/commit/084d709f): JNI releases the working-directory string with the matching `cwd` reference.
- [b4a2c32c](https://github.com/termux/termux-app/commit/b4a2c32c): `TermuxOpenReceiver.ContentProvider.getType()` handles URIs without a path segment without a null-pointer exception.

MDTerm uses `com.termux` for its Android application ID, Java namespace and
shared user ID. Its app name and Material Design interface remain MDTerm.
The application replaces the original Termux instead of installing alongside it.

Official Termux packages run directly under the app's UID with their original
prefix, `/data/data/com.termux/files/usr`, and home directory,
`/data/data/com.termux/files/home`. Interactive sessions launch their shell
through the terminal JNI layer; background commands use `Runtime.exec`.
Login-shell arguments, executable shebang handling and the system-shell
failsafe follow the upstream Termux implementation.

There is no bundled PRoot runtime, path translation, command wrapper or Intent
rewriting. Package commands such as `termux-open` and `termux-setup-storage`
target `com.termux` directly.

## Building

The `downloadBootstraps` task supplies the baseline's official bootstrap,
`2025.03.28-r1+apt-android-7`, verified by SHA-256 before native compilation.
First installation uses the baseline's second-stage initialization as well.
MDTerm's build and UI dependencies remain downstream customizations: Android
12+ and arm64 targets, Java 17, Gradle 9.2.1, AGP 8.13.2, SDK 36,
NDK 29.0.14206865, CMake 3.22.1, Zig 0.16.0, the ncurses `tic` tool,
and Material Components 1.12.0. The device must support Vulkan 1.1 (level 1).
The build fetches pinned Ghostty, FreeType, HarfBuzz and libpng sources, applies
the checked-in Android patch, and builds `libtermux.so` and matching terminfo.
Compilation and test builds run on GitHub Actions, not the local Termux device.
The workflows provide Zig, `tic`, Java and the Android toolchain. The
[test workflow](../.github/workflows/run_tests.yml) runs Java/Robolectric and
native Ghostty tests; the [build workflow](../.github/workflows/debug_build.yml)
uploads the debug APK and SHA-256 checksum. The debug APK uses the repository's
test signing key. No Python runtime or additional PRoot packages are bundled.

## Release signing

The [release workflow](../.github/workflows/release.yml) runs tests and builds
`:app:assembleRelease` with a private MDTerm key. It publishes a GitHub Release
when a `v*` tag is pushed. Manual runs on branches upload workflow artifacts only.
Update [release notes](release-notes.md) before tagging a new version.

The repository Actions Secrets are:

- `MDTERM_RELEASE_KEYSTORE_BASE64`: base64-encoded PKCS12 keystore.
- `MDTERM_RELEASE_STORE_PASSWORD`: keystore password.
- `MDTERM_RELEASE_KEY_ALIAS`: signing key alias.
- `MDTERM_RELEASE_KEY_PASSWORD`: signing key password.

The release workflow restores the keystore on the runner, requires all signing
secrets, and verifies the resulting APK's signature and Ghostty native contents.

The initial private key and its credentials are backed up locally under
`.signing/`, which is excluded from Git. Keep a secure backup of that directory:
future updates must use the same signing key. Debug builds retain the upstream
public test key and are not signature-compatible with MDTerm release APKs.

## Installation and migration

- An existing `com.termux` installation can only be updated if its signing key
  matches the new APK and Android's version requirements are satisfied.
  If signatures differ, back up data before uninstalling the old app and any
  incompatible Termux plugins. Uninstalling removes that app's private data.
- Termux plugins must use compatible signatures and the `com.termux` shared
  user ID. Restoring the package name does not bypass signature checks.
- The former coexistence build (`com.ericlee.mdterm`) has a different Android
  UID and data directory. The new APK cannot update it or automatically read
  its private files. Export needed files from that build before removing it,
  then restore them into the new installation. Reinstall packages from the
  official repository and check restored configuration for old absolute paths.

Neither building nor installing this APK migrates or deletes the former
coexistence build's data automatically.

## Terminal settings

Settings opens a list of categories: external applications, notifications,
appearance, sessions, keyboard and input, hardware keyboard shortcuts, terminal
toolbar, compatibility, and diagnostics. Tap a category to open its settings.
Keyboard toggle behavior and haptic feedback are under terminal toolbar;
terminal bell behavior is under notifications.

The Material settings screen edits the existing `~/.termux/termux.properties`
file. Like upstream Termux, it uses `~/.config/termux/termux.properties` when the
primary path has no readable regular file; a symlink at the properties file
itself is ignored. Opening settings does not create or rewrite a configuration.
Saving edits only the selected property, preserving comments, unknown options,
and multiline values. Empty text removes that override and restores the
upstream default. Invalid input or a failed save leaves the previous value in
place and displays an error.

The screen exposes fullscreen, theme, cursor, terminal margins, scrollback,
keyboard behavior, session shortcuts, bell behavior, and the other options in
the standard properties template. The obsolete `use-black-ui` setting is read
through the upstream compatibility mapping; theme changes write `night-mode`.
The default scrolling toolbar contains ESC, Ctrl, Alt, /, Tab, cursor pad,
keyboard, Home, End, PgUp, and PgDn. Toolbar keys can be edited in settings,
including their order, rows, macros, and swipe-up keys; this saves `extra-keys`
and applies the layout when returning to the terminal. Clearing the editor
restores the default layout. Symbol style, label capitalization, and height
remain configurable.

After a successful edit, returning to the terminal reloads its properties and
recreates the activity while preserving sessions. Scrollback size applies to
new sessions. The default directory applies when no current session exists;
otherwise new sessions inherit the current directory. Startup keyboard hiding
applies at the next app start. Character-based input takes priority over the
composing preview without changing the saved preview preference.

Fullscreen uses the upstream `fullscreen` property. An explicit old
`terminal_fullscreen` app preference migrates once when there is no explicit
file value; the file wins if both exist. Terminal bells follow `bell-character`.
The separate haptic-feedback setting controls toolbar and gesture feedback.
Android-only preferences such as soft-keyboard enablement, composing preview,
notifications, and diagnostics retain their existing preference storage.

## Terminal bookmarks

Long-press the terminal and choose **Save as bookmark** while at a shell prompt.
The bookmark captures the current directory before asking for its display name.
Bookmarks appear above sessions in the drawer; each card's menu can rename or
delete it. Opening a bookmark creates a new session.

Local bookmarks retain the foreground shell's directory. PRoot bookmarks retain
the proot-distro distribution and its internal directory, then reopen using
`pd login --work-dir` (or `proot-distro login` when `pd` is only a shell alias),
which uses the guest account's normal login shell and prompt configuration.
SSH bookmarks retain the SSH destination, connection arguments and remote
directory. A toast indicates that the environment is opening; password prompts
and connection errors remain visible in the terminal.

Distribution detection supports both the legacy `installed-rootfs/<name>` layout
and `proot-distro/containers/<name>/rootfs`. Relative rootfs arguments such as
`--rootfs=.` are resolved against the proot process's working directory, as used
by newer proot-distro versions.

Local directories are read from the foreground process without typing a command
into the terminal. SSH and PRoot directories use a short `pwd -P`/`base64` query
in the current terminal. PRoot emulates directory changes, so the host's
`/proc/<pid>/cwd` can stay at the rootfs even after `cd` inside the guest; it
cannot reliably identify the current guest directory.
Save from a shell prompt: the query clears unsubmitted input.
An unavailable directory or timed-out query is reported without saving a guessed
path. Arbitrary SSH remote commands and unidentified PRoot distributions cannot
be restored as interactive bookmarks.

## Device checks

After installation, check a fresh terminal and a failsafe session, create and
close multiple sessions, and run `pkg update`, `termux-open` and
`termux-setup-storage`. Check the `RUN_COMMAND` service with a background
command if external integrations are used. These checks require the installed
APK; unit tests and APK inspection do not replace them.
