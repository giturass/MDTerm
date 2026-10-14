#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PREPARE_SCRIPT="${MDTERM_PREPARE_NATIVE_SOURCES_SCRIPT:-$ROOT_DIR/scripts/prepare-native-sources.sh}"
TEST_TMP_BASE="${TMPDIR:-$HOME/tmp}"
mkdir -p "$TEST_TMP_BASE"
TEST_TMP_BASE="$(cd "$TEST_TMP_BASE" && pwd)"
TEST_ROOT="$(mktemp -d "$TEST_TMP_BASE/mdterm-native-sources-test.XXXXXX")"

cleanup() {
    # Only remove this test's mktemp directory, never a caller-supplied path.
    if [[ "$TEST_ROOT" == "$TEST_TMP_BASE"/mdterm-native-sources-test.* &&
            -d "$TEST_ROOT" && ! -L "$TEST_ROOT" ]]; then
        rm -rf -- "$TEST_ROOT"
    fi
}
trap cleanup EXIT

# Fixture fetches use local files exclusively, independent of personal Git
# configuration. No network, Gradle, Zig, Android SDK or compiler is required.
export GIT_ALLOW_PROTOCOL=file
export GIT_CONFIG_NOSYSTEM=1
export GIT_CONFIG_GLOBAL=/dev/null
export GIT_TERMINAL_PROMPT=0
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY
unset GIT_ALTERNATE_OBJECT_DIRECTORIES GIT_CONFIG_COUNT

dependencies=(ghostty freetype harfbuzz libpng)
for dependency in "${dependencies[@]}"; do
    fixture="$TEST_ROOT/fixtures/$dependency"
    git init --quiet --object-format=sha1 "$fixture"
    printf '%s fixture\n' "$dependency" > "$fixture/source.txt"
    git -C "$fixture" add source.txt
    git -C "$fixture" -c user.name='MDTerm fixture' \
        -c user.email='fixture@example.invalid' -c commit.gpgsign=false \
        commit --quiet -m fixture
    export "${dependency^^}_REPOSITORY=$fixture"
    export "${dependency^^}_REVISION=$(git -C "$fixture" rev-parse HEAD)"
done

fail() {
    printf 'FAIL: %s\n' "$*" >&2
    exit 1
}

prepare() {
    local output="$1" offline="${2:-0}"
    NATIVE_SOURCES_DIR="$output" NATIVE_SOURCES_OFFLINE="$offline" \
        bash "$PREPARE_SCRIPT" > "$TEST_ROOT/prepare.log" 2>&1
}

assert_prepared() {
    local output="$1" dependency expected
    for dependency in "${dependencies[@]}"; do
        expected="$(git -C "$TEST_ROOT/fixtures/$dependency" rev-parse HEAD)"
        [[ "$(git -C "$output/$dependency" rev-parse HEAD)" == "$expected" ]] ||
            fail "$dependency checkout does not match its pin"
        [[ "$(cat "$output/.revisions/$dependency")" == "$expected" ]] ||
            fail "$dependency revision stamp does not match its pin"
        [[ "$(cat "$output/$dependency/source.txt")" == "$dependency fixture" ]] ||
            fail "$dependency fixture contents changed"
        [[ -z "$(git -C "$output/$dependency" status --porcelain)" ]] ||
            fail "$dependency checkout is not clean"
    done
}

expect_success() {
    local label="$1" output="$2" offline="${3:-0}"
    if ! prepare "$output" "$offline"; then
        cat "$TEST_ROOT/prepare.log" >&2
        fail "$label"
    fi
    assert_prepared "$output"
    printf 'PASS: %s\n' "$label"
}

expect_refusal() {
    local label="$1" output="$2" reason="${3:-Refusing to replace a non-checkout directory:}"
    if prepare "$output"; then
        fail "$label unexpectedly replaced or reused an unsafe path"
    fi
    if ! grep -Fq "$reason" "$TEST_ROOT/prepare.log"; then
        cat "$TEST_ROOT/prepare.log" >&2
        fail "$label failed for an unrelated reason"
    fi
    printf 'PASS: %s\n' "$label"
}

expect_success 'missing output directories' "$TEST_ROOT/missing"

mkdir -p "$TEST_ROOT/one-empty/ghostty"
expect_success 'one precreated empty checkout directory' "$TEST_ROOT/one-empty"

for dependency in "${dependencies[@]}"; do
    mkdir -p "$TEST_ROOT/all-empty/$dependency"
done
expect_success 'all four Gradle output directories precreated' "$TEST_ROOT/all-empty"
expect_success 'prepared checkouts can be reused offline' "$TEST_ROOT/all-empty" 1

mkdir -p "$TEST_ROOT/nonempty/ghostty"
printf 'preserve me\n' > "$TEST_ROOT/nonempty/ghostty/keep.txt"
expect_refusal 'nonempty checkout directory' "$TEST_ROOT/nonempty"
[[ "$(cat "$TEST_ROOT/nonempty/ghostty/keep.txt")" == 'preserve me' ]] ||
    fail 'nonempty directory contents were changed'
[[ ! -e "$TEST_ROOT/nonempty/ghostty/.git" ]] || fail 'nonempty directory was initialized'

mkdir -p "$TEST_ROOT/hidden/ghostty"
printf 'preserve hidden data\n' > "$TEST_ROOT/hidden/ghostty/.keep"
expect_refusal 'directory containing only a hidden file' "$TEST_ROOT/hidden"
[[ "$(cat "$TEST_ROOT/hidden/ghostty/.keep")" == 'preserve hidden data' ]] ||
    fail 'hidden file was changed'
[[ ! -e "$TEST_ROOT/hidden/ghostty/.git" ]] || fail 'hidden-file directory was initialized'

mkdir -p "$TEST_ROOT/regular-file"
printf 'preserve file\n' > "$TEST_ROOT/regular-file/ghostty"
expect_refusal 'regular file in place of a checkout directory' "$TEST_ROOT/regular-file"
[[ "$(cat "$TEST_ROOT/regular-file/ghostty")" == 'preserve file' ]] ||
    fail 'regular file was changed'

mkdir -p "$TEST_ROOT/symlink-empty" "$TEST_ROOT/empty-target"
ln -s "$TEST_ROOT/empty-target" "$TEST_ROOT/symlink-empty/ghostty"
expect_refusal 'symlink to an empty directory' "$TEST_ROOT/symlink-empty" \
    'Refusing to use a symlink as a checkout:'
[[ -L "$TEST_ROOT/symlink-empty/ghostty" ]] || fail 'symlink was replaced'
[[ -z "$(find "$TEST_ROOT/empty-target" -mindepth 1 -maxdepth 1 -print -quit)" ]] ||
    fail 'symlink target was modified'

mkdir -p "$TEST_ROOT/symlink-checkout"
ln -s "$TEST_ROOT/missing/ghostty" "$TEST_ROOT/symlink-checkout/ghostty"
expect_refusal 'symlink to an already prepared checkout' "$TEST_ROOT/symlink-checkout" \
    'Refusing to use a symlink as a checkout:'
[[ -L "$TEST_ROOT/symlink-checkout/ghostty" ]] || fail 'checkout symlink was replaced'
assert_prepared "$TEST_ROOT/missing"

printf 'Native source preparation regression tests passed.\n'
