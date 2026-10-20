# MDTerm native runtime

The upstream baseline is official [Termux v0.119.0-beta.3](https://github.com/termux/termux-app/tree/v0.119.0-beta.3),
commit [`e634d8f981f48b6b89202cf0e04533f0889e03b3`](https://github.com/termux/termux-app/commit/e634d8f981f48b6b89202cf0e04533f0889e03b3).
The existing MDTerm interface, settings, bookmarks, session drawer, cursor
gestures, text selection and optional IME composing preview are preserved.
Terminal emulation uses that release's Java emulator and Canvas renderer.
The additional Sixel, iTerm2 inline-image and Bitmap graphics extensions
introduced after beta.1 have been removed from the working tree.

The shared library and app runtime now use beta.3's `TermuxShellManager`,
`TermuxSession`, `AppShell`, runner and shell-creation APIs. Shared properties,
preferences, file/URI utilities, crash reports, plugin results and Android
permission handling follow the same source baseline, with MDTerm's interface
and existing security fixes carried forward. See the [baseline map](upstream-baseline.md)
and [retained fixes](upstream-fixes.md).

MDTerm uses `com.termux` for its Android application ID, Java namespace and
shared user ID. Its app name and Material Design interface remain MDTerm.
The application replaces the original Termux instead of installing alongside it.

Official Termux packages run directly under the app's UID with their original
prefix, `/data/data/com.termux/files/usr`, and home directory,
`/data/data/com.termux/files/home`. Interactive sessions launch their shell
through the terminal JNI layer; background commands use `Runtime.exec`.
Login-shell arguments, executable shebang handling and the system-shell
failsafe follow the upstream Termux v0.119.0-beta.3 implementation.

There is no bundled PRoot runtime, path translation, command wrapper or Intent
rewriting. Package commands such as `termux-open` and `termux-setup-storage`
target `com.termux` directly. The app initializes beta.3's shell environment
and writes its environment file; package-update and boot events use the
upstream shared shell manager. Bootstrap initialization restores the environment
file after installing the prefix.

## Building

The existing `downloadBootstraps` task supplies the checksum-verified official
bootstrap before native compilation. It uses the `aarch64` archive from
[`2026.02.12-r1+apt.android-7`](https://github.com/termux/termux-packages/releases/tag/bootstrap-2026.02.12-r1%2Bapt.android-7).
The build targets Android 12+ and arm64, with only `apt-android-7` packages.
Its expected SHA-256 is
`ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3`.
No Python runtime preparation or additional PRoot package downloads are required.
The build uses JDK 17 or newer, Gradle
9.2.1, Android Gradle Plugin 8.13.2, Android SDK 36 and NDK 29.0.14206865.
These build tools support the MDTerm interface dependencies independently of
the upstream runtime baseline. The application keeps `minSdkVersion=31` and
`targetSdkVersion=28`; bootstrap compatibility metadata records Android 7
(API 24) as the package variant's minimum, separately from the app minimum.

```sh
./gradlew :app:assembleDebug testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/debug/`. The debug build uses the
repository's test signing key. The default app and library version is `0.119.0-beta.3`;
MDTerm retains Android version code `1022` so the baseline change does not
require downgrading that code for existing installations.

## 16 KiB memory pages

The three JNI libraries (`libtermux.so`, `liblocal-socket.so` and
`libtermux-bootstrap.so`) explicitly link with
`-Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384`. NDK 29 also
supports 16 KiB pages by default. These libraries remain compatible with
4 KiB devices. The native sources do not assume a fixed memory page size;
the Java terminal's 4096-byte I/O buffers are independent of memory pages.

The APK uses uncompressed native libraries (`useLegacyPackaging false`).
AGP 8.13.2 aligns them to 16 KiB ZIP boundaries, allowing Android to load
them directly from the APK. The pinned official bootstrap also has
16 KiB-aligned ARM64 ELF executables and shared libraries, so its archive
does not need to be replaced.

Both APK workflows check every packaged native library and every ELF file
inside the bootstrap, including executables without a `.so` extension.
The verifier rejects incompatible LOAD segments and RELRO protection that
would overlap writable data on 16 KiB pages. SDK `zipalign` separately checks
the final APK's ZIP alignment before it is uploaded or published:

```sh
python3 tools/check_16k_alignment.py path/to/mdterm.apk app/src/main/cpp/bootstrap-aarch64.zip
"$ANDROID_HOME/build-tools/35.0.0/zipalign" -c -P 16 -v 4 path/to/mdterm.apk
```

For device validation, use an ARM64 device or emulator where
`adb shell getconf PAGE_SIZE` returns `16384`. Disable **Run app with page size
compat mode** in the app's system settings if available. Check a fresh
bootstrap installation, normal and failsafe sessions, multiple sessions,
`pkg update`, `pkg upgrade` and the Android integrations listed below.
Repeat the session checks on a 4 KiB device. APK alignment checks do not
replace these device checks. Existing or subsequently installed third-party
native binaries also need to support the device's page size.

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

For a local signed release, set `MDTERM_RELEASE_STORE_FILE` to the keystore path
and the same password and alias environment variables, then run
`./gradlew :app:assembleRelease`. Without signing configuration, local release
builds are unsigned. The workflow requires all secrets and verifies the signature.

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

## Storage permissions

Storage setup and report export use beta.3's shared Android permission APIs.
On supported Android versions, MDTerm requests All Files Access and rechecks
the grant when returning from system settings. Legacy storage permission
callbacks remain handled by the shared API. Storage symlinks are created only
after permission is granted. The setup includes beta.3's documents, podcasts,
audiobooks and external/media directory links.

## Validation and device checks

The build command above is a validation procedure, not a report that this
migration has completed a full build. Local Java/resource checks with a temporary
SDK 35 environment do not establish that the configured SDK 36/NDK 29 release
APK builds or runs successfully. Report the actual CI and device results for
the revision being released.

After installation, check a fresh terminal and a failsafe session, create and
close multiple sessions, and run `pkg update`, `termux-open` and
`termux-setup-storage`. Check the `RUN_COMMAND` service with a background
command if external integrations are used. These checks require the installed
APK; unit tests and APK inspection do not replace them.
