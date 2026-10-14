#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUTPUT_DIR="${NATIVE_SOURCES_DIR:-$ROOT_DIR/build/native-sources}"
REVISION_DIR="$OUTPUT_DIR/.revisions"

checkout_source() {
    local name="$1" repository="$2" commit="$3" directory="$4"
    local path="$OUTPUT_DIR/$directory"
    if [[ ! "$commit" =~ ^[0-9a-f]{40}$ ]]; then
        printf '%s must be pinned to a full Git commit, got %s\n' "$name" "$commit" >&2
        return 1
    fi
    if [[ -L "$path" ]]; then
        printf 'Refusing to use a symlink as a checkout: %s\n' "$path" >&2
        return 1
    fi
    if [[ ! -d "$path/.git" ]]; then
        # Gradle creates declared output directories before running this task.
        # Initialize those empty directories, but never replace existing data
        # in a directory that is not already a checkout.
        if [[ -e "$path" && ! -d "$path" ]] ||
                [[ -d "$path" && -n "$(find "$path" -mindepth 1 -maxdepth 1 -print -quit)" ]]; then
            printf 'Refusing to replace a non-checkout directory: %s\n' "$path" >&2
            return 1
        fi
        if [[ "${NATIVE_SOURCES_OFFLINE:-0}" == 1 ]]; then
            printf '%s sources are missing; run prepareNativeSources once without --offline.\n' "$name" >&2
            return 1
        fi
        git init --quiet "$path"
        git -C "$path" remote add origin "$repository"
    fi
    if [[ "$(git -C "$path" remote get-url origin)" != "$repository" ]]; then
        printf 'Unexpected source repository at %s\n' "$path" >&2
        return 1
    fi
    if [[ -n "$(git -C "$path" status --porcelain --untracked-files=normal)" ]]; then
        printf 'Native sources were modified at %s; preserve or remove those edits before rebuilding.\n' "$path" >&2
        return 1
    fi
    if ! git -C "$path" cat-file -e "$commit^{commit}" 2>/dev/null; then
        if [[ "${NATIVE_SOURCES_OFFLINE:-0}" == 1 ]]; then
            printf '%s commit %s is not cached; run prepareNativeSources without --offline.\n' "$name" "$commit" >&2
            return 1
        fi
        git -C "$path" fetch --depth=1 origin "$commit"
    fi
    if [[ "$(git -C "$path" rev-parse --verify HEAD 2>/dev/null || true)" != "$commit" ]]; then
        git -C "$path" checkout --quiet --detach "$commit"
    fi
    [[ "$(git -C "$path" rev-parse HEAD)" == "$commit" ]]
    printf '%s\n' "$commit" > "$REVISION_DIR/$directory"
    printf 'Prepared %s at %s\n' "$name" "$commit"
}

mkdir -p "$REVISION_DIR"
checkout_source Ghostty "${GHOSTTY_REPOSITORY:?}" "${GHOSTTY_REVISION:?}" ghostty
checkout_source FreeType "${FREETYPE_REPOSITORY:?}" "${FREETYPE_REVISION:?}" freetype
checkout_source HarfBuzz "${HARFBUZZ_REPOSITORY:?}" "${HARFBUZZ_REVISION:?}" harfbuzz
checkout_source libpng "${LIBPNG_REPOSITORY:?}" "${LIBPNG_REVISION:?}" libpng
