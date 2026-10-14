#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_SOURCES_DIR="${NATIVE_SOURCES_DIR:-$ROOT_DIR/build/native-sources}"
ZIG="${ZIG:-$(command -v zig || command -v python-zig || true)}"
ZIG_VERSION="${ZIG_VERSION:?}"
ANDROID_NDK_VERSION="${ANDROID_NDK_VERSION:?}"
ANDROID_API_LEVEL="${ANDROID_API_LEVEL:?}"
ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}/ndk/$ANDROID_NDK_VERSION}"
PATCH_FILE="$ROOT_DIR/native/patches/ghostty-android.patch"

bash "$ROOT_DIR/scripts/verify-native-deps.sh"

if [[ ! -d "$ANDROID_NDK_HOME/toolchains/llvm/prebuilt" ]] ||
    ! grep -Eq "^Pkg.Revision[[:space:]]*=[[:space:]]*$ANDROID_NDK_VERSION[[:space:]]*$" "$ANDROID_NDK_HOME/source.properties"; then
    printf 'Android NDK %s is required at %s\n' "$ANDROID_NDK_VERSION" "$ANDROID_NDK_HOME" >&2
    exit 1
fi
if [[ ! "$ANDROID_API_LEVEL" =~ ^[0-9]+$ ]] || (( ANDROID_API_LEVEL < 31 )); then
    printf 'MDTerm requires Android API level 31 or newer.\n' >&2
    exit 1
fi

# Key patched worktrees by both the commit and patch. Updating either leaves
# previous cached sources intact instead of recursively deleting user paths.
patch_digest="$(sha256sum "$PATCH_FILE")"
patch_digest="${patch_digest%% *}"
GHOSTTY_SRC="$ROOT_DIR/terminal-emulator/build/ghostty-source/$GHOSTTY_REVISION-$patch_digest"
source_state="$GHOSTTY_SRC.mdterm-index"
mkdir -p "$(dirname "$GHOSTTY_SRC")"
if [[ ! -e "$GHOSTTY_SRC" ]]; then
    git clone --quiet --no-hardlinks "$NATIVE_SOURCES_DIR/ghostty" "$GHOSTTY_SRC"
    git -C "$GHOSTTY_SRC" checkout --quiet --detach "$GHOSTTY_REVISION"
    git -C "$GHOSTTY_SRC" apply --check "$PATCH_FILE"
    git -C "$GHOSTTY_SRC" apply --index "$PATCH_FILE"
    git -C "$GHOSTTY_SRC" write-tree > "$source_state"
fi
if [[ ! -s "$source_state" ]] ||
    [[ "$(git -C "$GHOSTTY_SRC" rev-parse HEAD)" != "$GHOSTTY_REVISION" ]] ||
    [[ "$(git -C "$GHOSTTY_SRC" write-tree)" != "$(cat "$source_state")" ]] ||
    ! git -C "$GHOSTTY_SRC" diff --quiet ||
    [[ -n "$(git -C "$GHOSTTY_SRC" ls-files --others --exclude-standard)" ]]; then
    printf 'Incomplete or modified patched Ghostty sources at %s; remove that generated directory before retrying.\n' "$GHOSTTY_SRC" >&2
    exit 1
fi

export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$ROOT_DIR/build/zig-cache}"
ZIG_LOCAL_CACHE_DIR="${GHOSTTY_ZIG_LOCAL_CACHE_DIR:-$ROOT_DIR/terminal-emulator/build/ghostty-zig-cache}"
build_jobs="${GHOSTTY_BUILD_JOBS:-4}"
if [[ ! "$build_jobs" =~ ^[1-9][0-9]*$ ]]; then
    printf 'GHOSTTY_BUILD_JOBS must be a positive integer.\n' >&2
    exit 1
fi
IFS=',' read -r -a requested_abis <<< "${TERMUX_APP_ABIS:-arm64-v8a}"
for android_abi in "${requested_abis[@]}"; do
    case "$android_abi" in
        arm64-v8a) zig_target="aarch64-linux-android" ;;
        *) printf 'Unsupported MDTerm Android ABI: %s\n' "$android_abi" >&2; exit 1 ;;
    esac
    output_dir="${GHOSTTY_ANDROID_OUTPUT_ROOT:-$ROOT_DIR/terminal-emulator/build/ghostty}/$android_abi"
    mkdir -p "$output_dir"
    (
        cd "$GHOSTTY_SRC"
        ANDROID_NDK_HOME="$ANDROID_NDK_HOME" "$ZIG" build \
            --prefix "$output_dir" \
            --cache-dir "$ZIG_LOCAL_CACHE_DIR" \
            -Demit-lib-vt=true \
            -Dtarget="$zig_target.$ANDROID_API_LEVEL" \
            -Doptimize=ReleaseFast \
            -Dstrip=true \
            -j"$build_jobs"
    )
    test -s "$output_dir/lib/libghostty-vt.a"
    test -s "$output_dir/include/ghostty/vt.h"
done
