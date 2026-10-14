#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_SOURCES_DIR="${NATIVE_SOURCES_DIR:-$ROOT_DIR/build/native-sources}"
ZIG_VERSION="${ZIG_VERSION:?}"
ZIG="${ZIG:-$(command -v zig || command -v python-zig || true)}"

verify_checkout() {
    local name="$1" path="$2" expected="$3"
    if [[ ! -d "$path/.git" ]]; then
        printf '%s checkout not found at %s; run prepareNativeSources first.\n' "$name" "$path" >&2
        return 1
    fi
    if [[ "$(git -C "$path" rev-parse HEAD)" != "$expected" ]] ||
        [[ -n "$(git -C "$path" status --porcelain --untracked-files=normal)" ]]; then
        printf '%s must be an unmodified checkout of %s at %s\n' "$name" "$expected" "$path" >&2
        return 1
    fi
}

verify_checkout Ghostty "$NATIVE_SOURCES_DIR/ghostty" "${GHOSTTY_REVISION:?}"
verify_checkout FreeType "$NATIVE_SOURCES_DIR/freetype" "${FREETYPE_REVISION:?}"
verify_checkout HarfBuzz "$NATIVE_SOURCES_DIR/harfbuzz" "${HARFBUZZ_REVISION:?}"
verify_checkout libpng "$NATIVE_SOURCES_DIR/libpng" "${LIBPNG_REVISION:?}"

if [[ ! -x "$ZIG" ]]; then
    printf 'Zig %s is required; put zig on PATH or set ZIG to its executable.\n' "$ZIG_VERSION" >&2
    exit 1
fi
actual_zig="$("$ZIG" version)"
if [[ "$actual_zig" != "$ZIG_VERSION" ]]; then
    printf 'Zig is %s, expected %s\n' "$actual_zig" "$ZIG_VERSION" >&2
    exit 1
fi
printf 'Native dependency check passed.\n'
