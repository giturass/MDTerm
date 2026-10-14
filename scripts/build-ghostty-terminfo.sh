#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NATIVE_SOURCES_DIR="${NATIVE_SOURCES_DIR:-$ROOT_DIR/build/native-sources}"
GHOSTTY_SRC="$NATIVE_SOURCES_DIR/ghostty"
GHOSTTY_REVISION="${GHOSTTY_REVISION:?}"
ZIG_VERSION="${ZIG_VERSION:?}"
ZIG="${ZIG:-$(command -v zig || command -v python-zig || true)}"
OUTPUT_DIR="${GHOSTTY_TERMINFO_OUTPUT_DIR:-$ROOT_DIR/app/build/generated/ghosttyTerminfo/assets/ghostty}"
export ZIG_GLOBAL_CACHE_DIR="${ZIG_GLOBAL_CACHE_DIR:-$ROOT_DIR/build/zig-cache}"

if [[ "$(git -C "$GHOSTTY_SRC" rev-parse HEAD)" != "$GHOSTTY_REVISION" ]] ||
    [[ -n "$(git -C "$GHOSTTY_SRC" status --porcelain --untracked-files=normal)" ]]; then
    printf 'Ghostty checkout must match %s without local edits.\n' "$GHOSTTY_REVISION" >&2
    exit 1
fi
if [[ ! -x "$ZIG" ]] || [[ "$("$ZIG" version)" != "$ZIG_VERSION" ]]; then
    printf 'Zig %s is required; put zig on PATH or set ZIG to its executable.\n' "$ZIG_VERSION" >&2
    exit 1
fi
command -v tic >/dev/null || {
    printf 'The ncurses tic command is required to build Ghostty terminfo.\n' >&2
    exit 1
}

# Compile in a private build directory. The APK needs only xterm-ghostty;
# compiling that name alone also supports Android filesystems without hardlinks.
work_dir="$ROOT_DIR/app/build/ghostty-terminfo-work"
mkdir -p "$work_dir" "$OUTPUT_DIR/terminfo/x"
"$ZIG" run \
    --cache-dir "$ROOT_DIR/app/build/ghostty-terminfo-zig-cache" \
    --dep ghostty_terminfo \
    "-Mroot=$ROOT_DIR/scripts/generate-ghostty-terminfo.zig" \
    "-Mghostty_terminfo=$GHOSTTY_SRC/src/terminfo/main.zig" \
    > "$work_dir/ghostty.terminfo"
sed '1s/^[^,]*,/xterm-ghostty|Ghostty terminal emulator,/' \
    "$work_dir/ghostty.terminfo" > "$work_dir/xterm-ghostty.terminfo"
tic -x -o "$work_dir/database" "$work_dir/xterm-ghostty.terminfo"
entry="$work_dir/database/x/xterm-ghostty"
if [[ ! -f "$entry" ]]; then
    entry="$work_dir/database/78/xterm-ghostty"
fi
test -s "$entry"
test -s "$work_dir/ghostty.terminfo"
cp "$work_dir/ghostty.terminfo" "$OUTPUT_DIR/ghostty.terminfo"
cp "$entry" "$OUTPUT_DIR/terminfo/x/xterm-ghostty"
