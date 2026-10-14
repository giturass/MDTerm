#!/usr/bin/env bash

# Run by GitHub Actions on the Linux host, against the same patched revision
# that buildGhosttyAndroid compiles for the APK.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GHOSTTY_REVISION="${GHOSTTY_REVISION:?}"
ZIG_VERSION="${ZIG_VERSION:?}"
ZIG="${ZIG:-$(command -v zig || true)}"
if [[ ! -x "$ZIG" ]] || [[ "$("$ZIG" version)" != "$ZIG_VERSION" ]]; then
    printf 'Zig %s is required.\n' "$ZIG_VERSION" >&2
    exit 1
fi

patch_digest="$(sha256sum "$ROOT_DIR/native/patches/ghostty-android.patch")"
patch_digest="${patch_digest%% *}"
source_dir="$ROOT_DIR/terminal-emulator/build/ghostty-source/$GHOSTTY_REVISION-$patch_digest"
source_state="$source_dir.mdterm-index"
if [[ ! -s "$source_state" ]] ||
    [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$GHOSTTY_REVISION" ]] ||
    [[ "$(git -C "$source_dir" write-tree)" != "$(cat "$source_state")" ]] ||
    ! git -C "$source_dir" diff --quiet ||
    [[ -n "$(git -C "$source_dir" ls-files --others --exclude-standard)" ]]; then
    printf 'Run buildGhosttyAndroid first with unmodified pinned sources.\n' >&2
    exit 1
fi

build_jobs="${GHOSTTY_BUILD_JOBS:-2}"
if [[ ! "$build_jobs" =~ ^[1-9][0-9]*$ ]]; then
    printf 'GHOSTTY_BUILD_JOBS must be a positive integer.\n' >&2
    exit 1
fi
export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$ROOT_DIR/build/zig-cache}"
cd "$source_dir"
# This upstream step runs both the terminal and C API suites, including Kitty
# decode/storage/placement, resize/scrollback/input and MDTerm's OSC 777 patch.
"$ZIG" build test-lib-vt \
    --cache-dir "$ROOT_DIR/terminal-emulator/build/ghostty-native-test-cache" \
    -Demit-lib-vt=true \
    -Doptimize=Debug \
    -j"$build_jobs" \
    --summary all
