package com.termux.app.terminal;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class ProotBookmarkLocationTest {
    private static final String STATE = "/data/data/com.termux/files/usr/var/lib/proot-distro";
    private static final String ROOT = STATE + "/containers/debian/rootfs";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void resolvesCurrentContainerAndLegacyRootfsDirectories() {
        for (String root : Arrays.asList(ROOT, STATE + "/installed-rootfs/debian")) {
            assertEquals("/root/test", ProotBookmarkLocation.resolve(
                Arrays.asList("proot", "--rootfs=" + root, "/bin/bash"), "/", root + "/root/test"));
            assertEquals("/", ProotBookmarkLocation.resolve(
                Arrays.asList("proot", "-r", root, "/bin/bash"), "/", root));
        }
    }

    @Test public void rootfsDotUsesTracerDirectoryAndPreservesUnusualNames() {
        assertEquals("/root/目录/a'b\nc", ProotBookmarkLocation.resolve(Arrays.asList(
            "/usr/bin/proot", "--rootfs=.", "--cwd=/root", "--bind=/dev", "--bind=/proc",
            "/bin/bash", "-l"), ROOT, ROOT + "/root/目录/a'b\nc"));
        assertEquals("/root/test", ProotBookmarkLocation.resolve(
            Arrays.asList("proot", "-rcontainers/debian/rootfs", "/bin/bash"), STATE, ROOT + "/root/test"));
    }

    @Test public void rootfsArgumentCanContainDotSegmentsAndTrailingSlash() {
        assertEquals("/root/test", ProotBookmarkLocation.resolve(
            Arrays.asList("proot", "--rootfs", ROOT + "/./../rootfs/", "/bin/bash"), "/", ROOT + "/root/test"));
        assertEquals("/tmp", ProotBookmarkLocation.resolve(
            Arrays.asList("proot", "-r", "/", "/bin/bash"), "/", "/tmp"));
    }

    @Test public void rootfsBoundaryAndExternalBindDirectoriesAreNotGuestPaths() {
        for (String cwd : Arrays.asList(ROOT + "-backup/root", "/storage/emulated/0", "relative", ROOT + "/root (deleted)")) {
            assertNull(cwd, ProotBookmarkLocation.resolve(
                Arrays.asList("proot", "--rootfs=" + ROOT, "/bin/bash"), ROOT, cwd));
        }
    }

    @Test public void resolvesOrdinaryProotDistroFlagsAndUnrelatedBindings() {
        assertEquals("/root/test", ProotBookmarkLocation.resolve(Arrays.asList(
            "proot", "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "--change-id=0:0",
            "--kernel-release=5.10", "--rootfs=.", "--cwd=/root", "--bind=/dev",
            "-b", "/proc", "--mount=/sys", "-m", "/storage:/mnt/shared!", "-v0", "/bin/bash"),
            ROOT, ROOT + "/root/test"));
    }

    @Test public void sourceAliasesStillAllowRestoringTheCanonicalRootfsDirectory() {
        for (String binding : Arrays.asList(ROOT + "/root:/alias", ROOT + ":/alias", ROOT + "/root",
            ".:/alias", "root:/alias", "/:/host-rootfs", "/data/data/com.termux/files/usr")) {
            for (String option : Arrays.asList("-b", "--bind", "-m", "--mount")) {
                assertEquals(binding, "/root/test", ProotBookmarkLocation.resolve(Arrays.asList(
                    "proot", "--rootfs=.", option, binding, "/bin/bash"), ROOT, ROOT + "/root/test"));
            }
        }
        assertEquals("/root/test", ProotBookmarkLocation.resolve(Arrays.asList(
            "proot", "--rootfs=.", "-b" + ROOT + "/root:/alias", "/bin/bash"), ROOT, ROOT + "/root/test"));
    }

    @Test public void bindingsShadowingTheCanonicalGuestDirectoryRequireQuery() {
        for (String binding : Arrays.asList("/host:/root", "/host:/root/test", "/host:/", "/host:/root!")) {
            assertNull(binding, ProotBookmarkLocation.resolve(Arrays.asList(
                "proot", "--rootfs=.", "--bind=" + binding, "/bin/bash"), ROOT, ROOT + "/root/test"));
        }
        assertEquals("/root/test", ProotBookmarkLocation.resolve(Arrays.asList(
            "proot", "--rootfs=.", "--bind=" + ROOT + "/root:/root", "/bin/bash"), ROOT, ROOT + "/root/test"));
    }

    @Test public void symlinkedGuestTargetsRequireQueryInsteadOfGuessing() throws Exception {
        File root = temporary.newFolder("rootfs");
        Files.createSymbolicLink(new File(root, "alias").toPath(), new File("/root").toPath());
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList(
            "proot", "--rootfs=.", "--bind=/host:/alias", "/bin/bash"), root.getCanonicalPath(), root.getCanonicalPath() + "/root/test"));
        assertEquals("/root/test", ProotBookmarkLocation.resolve(Arrays.asList(
            "proot", "--rootfs=.", "--bind=/host:/alias!", "/bin/bash"), root.getCanonicalPath(), root.getCanonicalPath() + "/root/test"));
    }

    @Test public void bindSourceBoundaryDoesNotConfuseSiblingDirectory() {
        assertEquals("/root/tests", ProotBookmarkLocation.resolve(Arrays.asList(
            "proot", "--rootfs=.", "--bind=" + ROOT + "/root/test:/alias", "/bin/bash"),
            ROOT, ROOT + "/root/tests"));
    }

    @Test public void unknownAndImplicitBindingsRequireGuestQuery() {
        for (String option : Arrays.asList("--unknown", "--unknown=value", "-z", "-R", "-S", "-q", "--qemu")) {
            assertNull(option, ProotBookmarkLocation.resolve(Arrays.asList(
                "proot", "--rootfs=.", option, "/other", "/bin/bash"), ROOT, ROOT + "/root/test"));
        }
    }

    @Test public void guestCommandArgumentsCannotAlterTheMapping() {
        assertEquals("/root/test", ProotBookmarkLocation.resolve(Arrays.asList(
            "proot", "--rootfs=.", "/bin/bash", "--bind=/:/alias", "--rootfs=/"), ROOT, ROOT + "/root/test"));
        assertEquals("/root/test", ProotBookmarkLocation.resolve(Arrays.asList(
            "proot", "--rootfs=.", "--", "/bin/bash", "--unknown"), ROOT, ROOT + "/root/test"));
    }

    @Test public void invalidOrIncompleteArgumentsRequireGuestQuery() {
        assertNull(ProotBookmarkLocation.resolve(Collections.emptyList(), ROOT, ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("bash", "--rootfs=."), ROOT, ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("proot", "--rootfs=.", "--bind"), ROOT, ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("proot", "--rootfs=.", "--bind=:/guest"), ROOT, ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("proot", "--rootfs=.", "--bind=/host:"), ROOT, ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("proot", "--rootfs=.", "--bind=/host:/a:/b"), ROOT, ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("proot", "--rootfs=.", "--bind=/host:/alias/../root"), ROOT, ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("proot", "--rootfs=."), "relative", ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("proot", "--rootfs=."), ROOT + " (deleted)", ROOT));
        assertNull(ProotBookmarkLocation.resolve(Arrays.asList("proot", "--rootfs=."), ROOT, ROOT + "/\0bad"));
    }
}
