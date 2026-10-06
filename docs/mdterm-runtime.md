# MDTerm native runtime

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
target `com.termux` directly. The upstream `termux-am` socket is at
`/data/data/com.termux/files/apps/com.termux/termux-am/am.sock`.

## Building

The existing `downloadBootstraps` task supplies the checksum-verified official
bootstrap before native compilation. The build targets Android 12+ and arm64,
with `apt-android-7` packages. No Python runtime preparation or additional
PRoot package downloads are required.

```sh
./gradlew :app:assembleDebug testDebugUnitTest
```

The APK is written to `app/build/outputs/apk/debug/`. The debug build uses the
repository's test signing key.

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
