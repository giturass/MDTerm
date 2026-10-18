# Retained fixes on the beta.3 baseline

This migration uses official Termux v0.119.0-beta.3, commit
[`e634d8f981f48b6b89202cf0e04533f0889e03b3`](https://github.com/termux/termux-app/commit/e634d8f981f48b6b89202cf0e04533f0889e03b3).
The source map and scope are documented in [upstream-baseline.md](upstream-baseline.md).
The following existing MDTerm protections are retained while adopting beta.3 APIs.

| Area | Retained behavior |
| --- | --- |
| External command results | `RunCommandService` reads file-result settings only after `allow-external-apps` authorization. A rejected caller can receive a PendingIntent error but cannot create or overwrite result files through the error path. |
| Shared file provider | Canonical file paths must be descendants of the Termux files directory or shared storage, with a directory-separator boundary. Similarly named sibling paths do not pass the check. Termux properties remain read-only through the provider. |
| MIME lookup | A missing or empty final URI path segment returns no MIME type instead of dereferencing a null value. |
| Local file URIs | Received, executed and shared file paths preserve `#`, including a trailing empty fragment. URI construction encodes paths, and handlers reject missing local paths. |
| Executable validation | `AppShell` rejects null or empty background executables before process setup. `TermuxSession` treats an empty interactive executable as an unset executable and selects the default shell. |
| Session lifecycle | Closing a running session processes its result and detaches the activity client. Stopping the service updates the current UI. An empty, bound bookmark/session drawer remains ready to launch a session. |
| MOTD installation | The default `motd.sh` is created only when absent; empty files and dangling symlinks are preserved. Creation does not overwrite a file created by another caller, and a failed write can be retried. |

File and URI helpers, shared properties, preferences, plugin and crash reporting,
permission checks, `TermuxShellManager`, `TermuxSession`, and `AppShell` now use
beta.3's package layout and APIs. The app initializes and exports the shared
shell environment, including after bootstrap installation. Storage setup uses
the upstream legacy/All Files Access API and handles returning from Android's
permission settings before creating symlinks.

The terminal source follows beta.3's Java/Canvas implementation. Existing MDTerm
cursor gestures, composing preview, selection controls, extra keys and bookmark
location queries are retained. The extra Sixel, iTerm2 inline-image and Bitmap
paths introduced after beta.1 are removed.

## Bootstrap

Only the official `aarch64` archive from
[`2026.02.12-r1+apt.android-7`](https://github.com/termux/termux-packages/releases/tag/bootstrap-2026.02.12-r1%2Bapt.android-7)
is packaged. The Gradle package variant remains `apt-android-7`; `apt.android-7`
is the spelling in the release tag. The archive must be available and match its
SHA-256 before native assembly embeds it:

```text
ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3
```

## Verification scope

The app's bootstrap C, assembly and `Android.mk` files were compared byte for
byte with the official beta.3 source. The restored file receiver, system event
receiver and service-side terminal client were also checked against that source.

Relevant regression coverage includes external-command policy failures, file
sharing and URI paths, shell arguments, session actions, storage permission
returns, bookmarks, gestures, selection and IME input. Test definitions are not
proof of a passing run. This document does not carry forward test totals or APK
validation results from a different baseline. A full configured build, release
signature verification and installed-device checks still need their own results
for the revision being released.

### Local validation on 2026-10-18

| Suite | Distinct passing tests |
| --- | ---: |
| Terminal emulator | 156 |
| App, UI and runtime regression tests | 245 |
| Shared report-storage permissions | 5 |
| Total | 406 |

The app total combines the full suite with a successful 39-test rerun covering
external-command policy, session actions and property shortcuts after adapting
the notification test shadow to beta.3. The shared tests exercise legacy storage
grants, All Files Access, permission requests, saving after returning from
settings, and cancellation without another request.

This validation ran in Termux on ARM64 with JDK 17, Gradle 9.2.1 and AGP 8.13.2.
Temporary Gradle init scripts used the available Android 35 SDK/build tools and
host ARM64 `aapt2`, and disabled native compilation and bootstrap downloading.
App and shared Java sources, resources and test sources compiled successfully.
The app/shared tests ran at simulated Android API 31 using a temporary
Robolectric 4.11.1 override with `robolectric.conscryptMode=OFF`: the repository's
Robolectric 4.10 requires an unavailable Conscrypt Linux ARM64 native library.
The declared Robolectric 4.10 dependencies were also used for a separate test-source
compilation check. Repository SDK, NDK and test dependency versions remain unchanged.

`terminal-view/src/main` is unchanged from the MDTerm UI snapshot used for this
migration, and `git diff --check` passes. These results cover Java behavior and
resources; a full SDK 36/NDK 29 APK build, native-library packaging, release
signing and installed-device validation have not been performed locally.
