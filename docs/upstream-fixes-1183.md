# Upstream fixes for 1183

Reviewed on 2026-10-17 against upstream `master`
[`8629e632`](https://github.com/termux/termux-app/commit/8629e632fcb95da272221be327db653fb24befe9).
The starting MDTerm tree was `684f78dd`, based on Termux v0.118.3.
Because the branch restored an older source tree after earlier integrations,
applicability was checked against the source, including fixes whose commits
already appeared in the branch history.

## Applied fixes

| Upstream commit | Defect in the 1183 source and backport |
| --- | --- |
| [3b66f879](https://github.com/termux/termux-app/commit/3b66f879) | Read file-result settings only after `allow-external-apps` authorization. Rejected commands can still receive a PendingIntent error, but cannot create or overwrite result files. |
| [042487c2](https://github.com/termux/termux-app/commit/042487c2) | Reject null or empty background-task executables before preparing process arguments. Adapted to the v0.118.3 `TermuxTask` API. |
| [0fd354a4](https://github.com/termux/termux-app/commit/0fd354a4) | Treat an empty interactive-session executable like null so direct library callers get the default shell. |
| [9b5aad94](https://github.com/termux/termux-app/commit/9b5aad94) | Pass the requested failsafe flag when constructing a background task's environment. |
| [c567cc3b](https://github.com/termux/termux-app/commit/c567cc3b) | Handle failure to start or bind the terminal service without crashing the activity. Include English and Simplified Chinese errors. |
| [b80126fd](https://github.com/termux/termux-app/commit/b80126fd) | Catch failures while initializing or invoking the hidden-API bypass. |
| [d2221026](https://github.com/termux/termux-app/commit/d2221026) | Handle failure to load the terminal bell sound. |
| [45844885](https://github.com/termux/termux-app/commit/45844885) | Reset and disable keyboard bottom-margin adjustment in multi-window mode to prevent flicker. |
| [b4a2c32c](https://github.com/termux/termux-app/commit/b4a2c32c) | Avoid dereferencing a missing path segment during MIME-type lookup. |
| [b559d5a0](https://github.com/termux/termux-app/commit/b559d5a0), [74b23cb2](https://github.com/termux/termux-app/commit/74b23cb2), [3e518a6a](https://github.com/termux/termux-app/commit/3e518a6a), [b435d948](https://github.com/termux/termux-app/commit/b435d948) | Preserve `#` in received, executed and shared file paths, encode content-URI paths correctly, and reject missing local paths. The small shared helper also preserves an explicitly empty fragment as a trailing literal `#`. |
| [7bbc12c7](https://github.com/termux/termux-app/commit/7bbc12c7) | Use the URI basename when a received file has neither a provider display name nor an Intent title. |
| [231ecff5](https://github.com/termux/termux-app/commit/231ecff5) | Preserve literal modifier letters from IMEs and virtual keys; apply Bluetooth-keyboard normalization only to physical keyboard input. Preserve the existing three-argument input API for MDTerm extra keys. |
| [7d87ed76](https://github.com/termux/termux-app/commit/7d87ed76) | Let Android handle an external keyboard's language-switch key without consuming terminal modifiers. |
| [30ebb2de](https://github.com/termux/termux-app/commit/30ebb2de), [c8a74dc5](https://github.com/termux/termux-app/commit/c8a74dc5) | Correct reversed PgUp/PgDn termcap entries and encode Shift/Alt/Ctrl modifiers. |
| [47c473f9](https://github.com/termux/termux-app/commit/47c473f9) | Clear stale line-wrap flags when erasing through the right margin so copied text retains its line breaks. |
| [5532cc4f](https://github.com/termux/termux-app/commit/5532cc4f) | Use an integer for a row's UTF-16 length to prevent signed-short overflow. |
| [ef4775b6](https://github.com/termux/termux-app/commit/ef4775b6) | Increase the process-output queue and read buffer from 4 KiB to 64 KiB, addressing upstream's tmux output-stutter report. |
| [fd2ab4fc](https://github.com/termux/termux-app/commit/fd2ab4fc), partially | Require `ESC \` to terminate DCS, stop parsing discarded OSC commands after argument overflow, and release excess control-buffer capacity when discarding or reusing it. Keep the v0.118.3 parser structure, APC discard behavior and 8 KiB limit. |
| [8b72226d](https://github.com/termux/termux-app/commit/8b72226d) | Make OSC 52 Base64 flags explicit and repair the error message's missing closing quote. |
| [792c33c9](https://github.com/termux/termux-app/commit/792c33c9) | Request a permission group once, avoiding competing one-permission dialogs. |
| [55cdef01](https://github.com/termux/termux-app/commit/55cdef01) | Recognize numeric and hyphenated domain suffixes in the terminal URL selector. |
| [93d738ae](https://github.com/termux/termux-app/commit/93d738ae) | Remove all remaining trailing slashes after normalizing dot segments in paths. |
| [006bfeac](https://github.com/termux/termux-app/commit/006bfeac), [1b794b35](https://github.com/termux/termux-app/commit/1b794b35), [02ab8324](https://github.com/termux/termux-app/commit/02ab8324) | Restore default verbose command logging, use logcat-filter-compatible tags, and omit empty exception sections from error reports. |
| [3f0dec35](https://github.com/termux/termux-app/commit/3f0dec35) | Remove the deprecated manifest `extractNativeLibs` attribute; Gradle already sets `useLegacyPackaging true`. |

## Bootstrap

As requested, use only the official `aarch64` bootstrap from
[`2026.02.12-r1+apt.android-7`](https://github.com/termux/termux-packages/releases/tag/bootstrap-2026.02.12-r1%2Bapt.android-7),
following upstream [5f578d56](https://github.com/termux/termux-app/commit/5f578d56).
The Gradle package variant remains `apt-android-7`; `apt.android-7` is the spelling
in the official release tag. Other bootstrap download entries are removed, and
the existing ABI restriction remains `arm64-v8a`.

Expected SHA-256:

```text
ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3
```

## Applicability notes

The starting tree already contains the JNI string-release fix, explicit
serialization IDs, modern CSI/APC parsing fixes, the combining-character limit,
AutoFill corrections, bidirectional text rendering, cursor float conversion,
HiddenApiBypass 6.1, the Maven publishing fix, desugaring 1.1.5 and corrected
Gradle wrapper-validation actions.

Android 5/6-only fixes do not apply to MDTerm's Android 12 minimum. The SCROLL
position-cache fix depends on the absent SCROLL feature. The newer bootstrap
initialization fix concerns `termux.env` files, which the v0.118.3 runtime does
not create. New shell-manager and termux-am-socket APIs, bitmap protocols,
larger OSC 52 payloads and new storage shortcuts are separate feature changes.
MDTerm's Material dialogs and single extra-key toolbar also replace the old UI
paths affected by several historical fixes.

## Requested drawer appearance

Bookmark and session card widths and section separators match MDTerm's
[`ghostty` branch at b8be7185](https://github.com/giturass/MDTerm/commit/b8be7185ae53877488b67f5143ec9312a9149b14).
Both card types use 20 dp side insets. Each section title has a trailing 1 dp
divider using `colorOutlineVariant`, with a 12 dp gap after the title.
The shared card layout and 8 dp spacing between cards already match that branch.

## Validation

- `testDebugUnitTest`: 398 tests passed (244 app tests and 154 emulator tests),
  with no failures, errors or skipped tests.
- After the drawer appearance update, reran `SessionDrawerTest`, `BookmarkUiTest`
  and `SessionActionsTest`: all 42 tests passed. These cover changing viewport
  sizes, card spacing, narrow text/menu layouts and bookmark/session actions.
- The section-header XML and drawer-list layout attributes match `ghostty`.
- The downloaded bootstrap SHA-256 matches the upstream value above; inspected
  `bash`, `dpkg` and `apt` binaries are AArch64.
- `git diff --check` passed.

Local tests used Gradle 9.2.1, Termux JDK 21, a native `aapt2` override and the
existing Robolectric configuration that disables Conscrypt on the JVM.
The run containing APK assembly was canceled during unit tests at the user's
request. Subsequent runs contained only unit-test tasks; no APK was produced.
