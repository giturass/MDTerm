#!/usr/bin/env python3
"""Check ARM64 ELF alignment in an APK and its Termux bootstrap without extracting.

APK ZIP alignment is checked separately with Android SDK zipalign -c -P 16 4.
See https://developer.android.com/guide/practices/page-sizes.
"""

import argparse
import struct
import zipfile
from typing import NamedTuple


PAGE_SIZE = 16 * 1024
ELF_HEADER = struct.Struct("<16sHHIQQQIHHHHHH")
PROGRAM_HEADER = struct.Struct("<IIQQQQQQ")
PT_LOAD = 1
PT_GNU_RELRO = 0x6474E552
PF_W = 2


class Segment(NamedTuple):
    type: int
    flags: int
    offset: int
    vaddr: int
    paddr: int
    filesz: int
    memsz: int
    align: int


def check_elf(data):
    if len(data) < ELF_HEADER.size:
        raise ValueError("truncated ELF header")
    header = ELF_HEADER.unpack_from(data)
    ident, elf_type, machine, version = header[:4]
    if ident[:7] != b"\x7fELF\x02\x01\x01" or machine != 183:
        raise ValueError("expected a little-endian ARM64 ELF64 file")
    if elf_type not in (2, 3) or version != 1:
        raise ValueError("expected an ELF executable or shared library")
    phoff, ehsize, phentsize, phnum = header[5], header[8], header[9], header[10]
    if (ehsize != ELF_HEADER.size or phentsize != PROGRAM_HEADER.size or not phnum
            or phnum == 0xFFFF or phoff < ehsize
            or phoff + phnum * phentsize > len(data)):
        raise ValueError("invalid ELF program header table")
    segments = [Segment(*PROGRAM_HEADER.unpack_from(data, phoff + i * phentsize))
                for i in range(phnum)]
    loads = [segment for segment in segments if segment.type == PT_LOAD]
    if not loads:
        raise ValueError("ELF has no LOAD segments")
    for segment in loads:
        if segment.filesz > segment.memsz or segment.offset + segment.filesz > len(data):
            raise ValueError("invalid LOAD segment bounds")
        if segment.align < PAGE_SIZE or segment.align & (segment.align - 1):
            raise ValueError(f"LOAD alignment {segment.align} is incompatible with 16 KiB pages")
        if (segment.vaddr - segment.offset) % segment.align:
            raise ValueError("LOAD address and file offset are not congruent at its alignment")

    for segment in segments:
        if segment.type != PT_GNU_RELRO or not segment.memsz:
            continue
        end = segment.vaddr + segment.memsz
        protected_end = (end + PAGE_SIZE - 1) // PAGE_SIZE * PAGE_SIZE
        if end == protected_end:
            continue
        # An unaligned RELRO end is safe when its LOAD segment contains only
        # RELRO and subsequent writable LOADs start on a separate 16 KiB page.
        if any(load.flags & PF_W and load.vaddr < protected_end
               and load.vaddr + load.memsz > end for load in loads):
            raise ValueError("RELRO protection overlaps writable data on a 16 KiB page")


def check_archive(archive, *, apk):
    checked = 0
    for entry in archive.infolist():
        if entry.is_dir():
            continue
        if apk and not (entry.filename.startswith("lib/") and entry.filename.endswith(".so")):
            continue
        with archive.open(entry) as stream:
            magic = stream.read(4)
            if not apk and magic != b"\x7fELF":
                continue
            data = magic + stream.read()
        try:
            check_elf(data)
        except ValueError as error:
            raise ValueError(f"{entry.filename}: {error}") from error
        checked += 1
    if not checked:
        raise ValueError("no native ELF files found")
    return checked


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", help="APK to check")
    parser.add_argument("bootstrap", help="bootstrap-aarch64.zip embedded in the APK build")
    args = parser.parse_args()
    for path, apk in ((args.apk, True), (args.bootstrap, False)):
        try:
            with zipfile.ZipFile(path) as archive:
                checked = check_archive(archive, apk=apk)
        except (OSError, ValueError, zipfile.BadZipFile) as error:
            parser.exit(1, f"{path}: {error}\n")
        print(f"{path}: {checked} ARM64 ELF files are 16 KiB compatible")


if __name__ == "__main__":
    main()
