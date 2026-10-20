import io
import struct
import unittest
import zipfile

from tools.check_16k_alignment import check_archive, check_elf


def elf(*segments, machine=183, elf_type=3):
    """Construct ELF64 fixtures with independently specified program headers."""
    header = struct.pack(
        "<16sHHIQQQIHHHHHH", b"\x7fELF\x02\x01\x01" + bytes(9),
        elf_type, machine, 1, 0, 64, 0, 0, 64, 56, len(segments), 0, 0, 0,
    )
    return header + b"".join(struct.pack("<IIQQQQQQ", *segment) for segment in segments)


def load(*, align=16384, offset=0, vaddr=0, memsz=0x8000, flags=6):
    return (1, flags, offset, vaddr, 0, 0, memsz, align)


def relro(vaddr, memsz):
    return (0x6474E552, 4, 0, vaddr, 0, 0, memsz, 1)


def check_zip(files, *, apk):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        for name, data in files.items():
            archive.writestr(name, data)
    with zipfile.ZipFile(buffer) as archive:
        return check_archive(archive, apk=apk)


class ElfAlignmentTest(unittest.TestCase):
    def test_accepts_16k_shared_library_and_executable(self):
        for elf_type in (2, 3):
            with self.subTest(elf_type=elf_type):
                check_elf(elf(load(), elf_type=elf_type))

    def test_accepts_larger_alignment(self):
        check_elf(elf(load(align=65536)))

    def test_rejects_4k_alignment_in_any_load(self):
        with self.assertRaisesRegex(ValueError, "LOAD alignment 4096"):
            check_elf(elf(load(), load(align=4096)))

    def test_rejects_non_power_of_two_alignment(self):
        with self.assertRaisesRegex(ValueError, "LOAD alignment"):
            check_elf(elf(load(align=24576)))

    def test_rejects_mismatched_load_address_and_offset(self):
        with self.assertRaisesRegex(ValueError, "not congruent"):
            check_elf(elf(load(vaddr=0x1000)))

    def test_accepts_aligned_relro_end(self):
        check_elf(elf(load(), relro(0, 0x4000)))

    def test_rejects_relro_overlapping_writable_data(self):
        with self.assertRaisesRegex(ValueError, "RELRO protection overlaps"):
            check_elf(elf(load(), relro(0, 0x1000)))

    def test_accepts_relro_in_own_load(self):
        check_elf(elf(load(memsz=0x1000), relro(0, 0x1000), load(vaddr=0x4000)))

    def test_rejects_relro_overlapping_another_writable_load(self):
        with self.assertRaisesRegex(ValueError, "RELRO protection overlaps"):
            check_elf(elf(load(memsz=0x1000), relro(0, 0x1000),
                          load(offset=0x2000, vaddr=0x2000)).ljust(0x2000, b"\0"))

    def test_rejects_missing_load_segments(self):
        with self.assertRaisesRegex(ValueError, "no LOAD segments"):
            check_elf(elf(relro(0, 0x4000)))

    def test_rejects_truncated_headers(self):
        data = elf(load())
        for length in (0, 4, 63, len(data) - 1):
            with self.subTest(length=length), self.assertRaises(ValueError):
                check_elf(data[:length])

    def test_rejects_truncated_load_contents(self):
        with self.assertRaisesRegex(ValueError, "LOAD segment bounds"):
            check_elf(elf((1, 6, 0, 0, 0, 1000, 1000, 16384)))

    def test_rejects_other_architectures(self):
        with self.assertRaisesRegex(ValueError, "ARM64 ELF64"):
            check_elf(elf(load(), machine=62))


class ArchiveAlignmentTest(unittest.TestCase):
    def test_checks_apk_libraries_and_ignores_assets(self):
        self.assertEqual(2, check_zip({
            "lib/arm64-v8a/libtermux.so": elf(load()),
            "lib/arm64-v8a/liblocal-socket.so": elf(load()),
            "assets/data": b"not ELF",
        }, apk=True))

    def test_reports_incompatible_library_path(self):
        with self.assertRaisesRegex(ValueError, "lib/arm64-v8a/bad.so: LOAD alignment"):
            check_zip({"lib/arm64-v8a/bad.so": elf(load(align=4096))}, apk=True)

    def test_rejects_non_elf_native_library(self):
        with self.assertRaisesRegex(ValueError, "bad.so: truncated ELF header"):
            check_zip({"lib/arm64-v8a/bad.so": b"not ELF"}, apk=True)

    def test_checks_bootstrap_executables_and_versioned_libraries(self):
        self.assertEqual(2, check_zip({
            "bin/bash": elf(load(), elf_type=2),
            "lib/libc.so.1": elf(load()),
            "bin/login": b"#!/bin/sh\nexec bash\n",
            "bin/sh": b"bash",  # ZIP symlink target, not an ELF file.
        }, apk=False))

    def test_rejects_incompatible_extensionless_bootstrap_executable(self):
        with self.assertRaisesRegex(ValueError, "bin/bash: LOAD alignment"):
            check_zip({"bin/bash": elf(load(align=4096))}, apk=False)

    def test_rejects_archives_without_native_files(self):
        for apk in (True, False):
            with self.subTest(apk=apk), self.assertRaisesRegex(ValueError, "no native ELF"):
                check_zip({"assets/data": b"text"}, apk=apk)


if __name__ == "__main__":
    unittest.main()
