#!/usr/bin/env python3
"""Check the CI-built Android library's JNI exports, Vulkan link and page size."""

import argparse
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent.parent


def verify_library(library, ndk):
    data = library.read_bytes()
    if data[:6] != b"\x7fELF\x02\x01":
        raise ValueError(f"{library}: expected a 64-bit little-endian ELF")
    if struct.unpack_from("<H", data, 18)[0] != 183:
        raise ValueError(f"{library}: expected ARM64")
    offset = struct.unpack_from("<Q", data, 32)[0]
    entry_size, count = struct.unpack_from("<HH", data, 54)
    load_segments = []
    for index in range(count):
        entry = offset + index * entry_size
        if struct.unpack_from("<I", data, entry)[0] == 1:
            load_segments.append(struct.unpack_from("<Q", data, entry + 48)[0])
    if not load_segments or any(alignment < 16384 for alignment in load_segments):
        raise ValueError(f"{library}: load segments must support 16 KiB pages")

    tool_dirs = list((ndk / "toolchains/llvm/prebuilt").glob("*/bin"))
    if len(tool_dirs) != 1:
        raise ValueError(f"Cannot locate NDK LLVM tools under {ndk}")
    nm_output = subprocess.check_output(
        [str(tool_dirs[0] / "llvm-nm"), "--dynamic", "--defined-only", str(library)],
        text=True,
    )
    symbols = {line.split()[-1] for line in nm_output.splitlines() if line.split()}
    java = (ROOT / "terminal-emulator/src/main/java/com/termux/terminal/GhosttyTerminal.java").read_text()
    methods = re.findall(r"\bnative\s+[\w\[\]]+\s+(\w+)\s*\(", java)
    if not methods:
        raise ValueError("No Ghostty JNI declarations found")
    required = {f"Java_com_termux_terminal_GhosttyTerminal_{method}" for method in methods}
    missing = required - symbols
    if missing:
        raise ValueError("Missing JNI exports: " + ", ".join(sorted(missing)))
    dynamic = subprocess.check_output(
        [str(tool_dirs[0] / "llvm-readelf"), "--dynamic", str(library)], text=True
    )
    if not re.search(r"\(NEEDED\).*\[libvulkan\.so\]", dynamic):
        raise ValueError(f"{library}: Vulkan dependency is missing")
    print(f"Verified {len(methods)} Ghostty JNI exports, Vulkan and 16 KiB ARM64 ELF: {library}")


def verify_artifact(artifact, ndk):
    if artifact.suffix != ".apk":
        verify_library(artifact, ndk)
        return
    with zipfile.ZipFile(artifact) as apk:
        terminfo = apk.read("assets/ghostty/terminfo/x/xterm-ghostty")
        if terminfo[:2] not in (b"\x1a\x01", b"\x1e\x02") or b"xterm-ghostty" not in terminfo[:256]:
            raise ValueError("APK contains invalid Ghostty terminfo")
        for integration in (
            "bash/ghostty.bash", "bash/bash-preexec.sh",
            "zsh/zshenv", "zsh/ghostty-integration",
            "fish/vendor_conf.d/ghostty-shell-integration.fish",
            "elvish/lib/ghostty-integration.elv",
            "nushell/vendor/autoload/ghostty.nu", "termux-ghostty.fish", "termux-ghostty.nu",
        ):
            if not apk.read(f"assets/ghostty/shell-integration/{integration}").strip():
                raise ValueError(f"APK is missing the {integration} shell integration")
        for dependency in ("ghostty", "freetype", "harfbuzz", "libpng"):
            if not apk.read(f"assets/licenses/{dependency}.txt").strip():
                raise ValueError(f"APK is missing the {dependency} license")
        with tempfile.TemporaryDirectory(prefix="mdterm-ghostty-") as temporary:
            library = Path(temporary) / "libtermux.so"
            library.write_bytes(apk.read("lib/arm64-v8a/libtermux.so"))
            verify_library(library, ndk)
    print(f"Verified Ghostty terminfo, shell integrations and native licenses: {artifact}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("artifacts", type=Path, nargs="+", help="CI-built .so or .apk files")
    parser.add_argument("--ndk", type=Path, default=os.environ.get("ANDROID_NDK_HOME"))
    args = parser.parse_args()
    if args.ndk is None:
        parser.error("--ndk or ANDROID_NDK_HOME is required")
    for artifact in args.artifacts:
        verify_artifact(artifact, args.ndk)


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, struct.error, subprocess.CalledProcessError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
