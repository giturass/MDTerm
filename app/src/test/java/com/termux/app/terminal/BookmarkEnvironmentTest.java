package com.termux.app.terminal;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class BookmarkEnvironmentTest {
    private static final String PROOT_STATE = "/data/data/com.termux/files/usr/var/lib/proot-distro";
    private static final String DEBIAN_ROOTFS = PROOT_STATE + "/containers/debian/rootfs";

    @Test public void sshKeepsPortIdentityJumpHostAndDestination() {
        assertEquals(Arrays.asList("-p", "2222", "-i", "/a key", "-Jjump", "user@host"),
            BookmarkEnvironment.sshArguments(Arrays.asList("/usr/bin/ssh", "-p", "2222",
                "-i", "/a key", "-Jjump", "-T", "user@host")));
    }

    @Test public void sshUnderstandsBundledOptionsAndExplicitOptionTerminator() {
        assertEquals(Arrays.asList("-vp", "2222", "--", "host"),
            BookmarkEnvironment.sshArguments(Arrays.asList("ssh", "-vp", "2222", "--", "host")));
    }

    @Test public void sshRemoteCommandIsRejectedInsteadOfRestoringWrongEnvironment() {
        try {
            BookmarkEnvironment.sshArguments(Arrays.asList("ssh", "host", "sudo", "-i"));
            fail("Remote commands are not an interactive SSH login target");
        } catch (IllegalStateException expected) { }
    }

    @Test public void prootDistroComesFromRootfsRatherThanExecutableName() {
        assertEquals("debian", BookmarkEnvironment.distroFromArguments(Arrays.asList("proot",
            "--rootfs=/data/data/com.termux/files/usr/var/lib/proot-distro/installed-rootfs/debian",
            "--cwd=/root")));
        assertEquals("ubuntu", BookmarkEnvironment.distroFromArguments(Arrays.asList("bash",
            "/usr/bin/pd", "login", "ubuntu")));
        assertEquals("", BookmarkEnvironment.distroFromArguments(Arrays.asList("bash", "-l")));
    }

    @Test public void prootRecognizesNewContainerRootfsAndRootfsAliases() {
        assertEquals("debian", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "/data/data/com.termux/files/usr/bin/proot", "--rootfs=" + DEBIAN_ROOTFS, "/bin/bash", "-l")));
        for (String option : Arrays.asList("-r", "-R", "-S", "--rootfs")) {
            assertEquals(option, "debian", BookmarkEnvironment.distroFromArguments(Arrays.asList(
                "proot", option, DEBIAN_ROOTFS, "/bin/bash")));
        }
    }

    @Test public void minimalProotLoginResolvesRootfsDotAgainstProotWorkingDirectory() {
        assertEquals("debian", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "proot", "--rootfs=.", "--cwd=/root", "--bind=/dev", "--bind=/proc", "/bin/bash", "-l"),
            DEBIAN_ROOTFS));
        assertEquals("debian", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "proot", "-r", "containers/debian/rootfs", "/bin/bash"), PROOT_STATE));
        assertEquals("debian", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "proot", "-r", ".", "/bin/bash"), PROOT_STATE + "/installed-rootfs/debian"));
    }

    @Test public void rootfsWinsOverBindingsFromOtherContainers() {
        assertEquals("debian", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "proot", "--bind=" + PROOT_STATE + "/installed-rootfs/ubuntu/root:/mnt/ubuntu",
            "--bind=" + PROOT_STATE + "/containers/alpine/rootfs:/mnt/alpine",
            "--rootfs=.", "--cwd=/data/data/com.termux/files/home", "/bin/bash"), DEBIAN_ROOTFS));
    }

    @Test public void unrelatedArgumentsAndWorkingDirectoriesDoNotIdentifyDistro() {
        assertEquals("", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "proot", "--bind=" + DEBIAN_ROOTFS + ":/mnt/debian", "/bin/bash"), DEBIAN_ROOTFS));
        assertEquals("", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "proot", "--rootfs=/", "/bin/echo", "--rootfs=" + DEBIAN_ROOTFS), DEBIAN_ROOTFS));
        assertEquals("", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "proot", "--rootfs=/", "--", "/bin/echo", "--rootfs=" + DEBIAN_ROOTFS), DEBIAN_ROOTFS));
        assertEquals("", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "cat", "--rootfs=" + DEBIAN_ROOTFS), DEBIAN_ROOTFS));
        assertEquals("", BookmarkEnvironment.distroFromArguments(Arrays.asList(
            "bash", "-l"), DEBIAN_ROOTFS));
    }

    @Test public void newContainerRootfsRequiresExactLayout() {
        for (String rootfs : Arrays.asList(DEBIAN_ROOTFS + "-backup",
                PROOT_STATE + "/containers/debian", "/work/containers/debian/rootfs")) {
            assertEquals(rootfs, "", BookmarkEnvironment.distroFromArguments(Arrays.asList(
                "proot", "--rootfs=" + rootfs, "/bin/bash")));
        }
    }

    @Test public void quotesShellMetacharactersAndSingleQuotes() {
        assertEquals("'/tmp/a'\\''b;$(touch bad)'", BookmarkEnvironment.quote("/tmp/a'b;$(touch bad)"));
    }

    @Test public void prootLaunchUsesPdAndQuotesPathAtBothShellLayers() {
        TerminalBookmark bookmark = new TerminalBookmark("id", "name", "proot", "debian",
            Collections.emptyList(), "/root/a'b");
        String inner = "cd -- " + BookmarkEnvironment.quote(bookmark.path)
            + " && exec \"${SHELL:-/bin/sh}\" -l";
        assertEquals("exec \"$(command -v pd || command -v proot-distro)\" login 'debian' -- sh -lc " + BookmarkEnvironment.quote(inner),
            BookmarkEnvironment.launchCommand(bookmark));
    }

    @Test public void sshLaunchPreservesConnectionOptionsAndQuotesRemoteDirectory() {
        TerminalBookmark bookmark = new TerminalBookmark("id", "name", "ssh", "",
            Arrays.asList("-p", "2222", "user@host"), "/srv/space dir");
        assertEquals("exec ssh -t '-p' '2222' 'user@host' "
                + BookmarkEnvironment.quote("sh -c " + BookmarkEnvironment.quote(
                    "cd -- '/srv/space dir' && exec \"${SHELL:-/bin/sh}\" -l")),
            BookmarkEnvironment.launchCommand(bookmark));
    }

    @Test public void reopenedSshCanBeSavedAgainWithoutKeepingOldDirectory() {
        String restore = "sh -c " + BookmarkEnvironment.quote("cd -- "
            + BookmarkEnvironment.quote("/old/dir's") + " && exec \"${SHELL:-/bin/sh}\" -l");
        assertEquals(Arrays.asList("-t", "host"),
            BookmarkEnvironment.sshArguments(Arrays.asList("ssh", "-t", "host", restore)));
    }

    @Test public void commandMasqueradingAsRestoreIsRejected() {
        String restore = "sh -c " + BookmarkEnvironment.quote(
            "cd -- '/tmp'; malicious; echo 'x' && exec \"${SHELL:-/bin/sh}\" -l");
        try {
            BookmarkEnvironment.sshArguments(Arrays.asList("ssh", "host", restore));
            fail("Unexpected remote command accepted");
        } catch (IllegalStateException expected) { }
    }

    @Test public void relativeSshFilesUseOriginalClientDirectoryIncludingBundledOptions() {
        assertEquals(Arrays.asList("-vi/work space/key", "-F", "/work space/config", "-E/work space/log",
                "-o", "IdentityFile=\"/work space/other-key\"", "host"),
            BookmarkEnvironment.normalizeSshPaths(Arrays.asList("-vikey", "-F", "config", "-Elog",
                "-o", "IdentityFile=other-key", "host"), "/work space", ""));
    }

    @Test public void absoluteAndSshExpandedPathsRetainTheirMeaning() {
        assertEquals(Arrays.asList("-i", "~/.ssh/id", "-F", "none", "-oIdentityFile=%d/.ssh/key", "host"),
            BookmarkEnvironment.normalizeSshPaths(Arrays.asList("-i", "~/.ssh/id", "-F", "none",
                "-oIdentityFile=%d/.ssh/key", "host"), "/unused", ""));
    }

    @Test public void prootRelativeKeyUsesGuestDirectoryAndUnknownBindingsFailClearly() {
        assertEquals(Arrays.asList("-i", "/root/key", "host"),
            BookmarkEnvironment.normalizeSshPaths(Arrays.asList("-i", "key", "host"),
                "/data/var/lib/proot-distro/installed-rootfs/debian/root", "debian"));
        try {
            BookmarkEnvironment.normalizeSshPaths(Arrays.asList("-i", "key", "host"), "/storage/shared", "debian");
            fail("An unknown guest bind must not produce a wrong credential path");
        } catch (IllegalStateException expected) { }
    }

    @Test public void newContainerRelativeSshFilesUseGuestDirectory() {
        assertEquals(Arrays.asList("-i", "/root/key", "-F/root/config", "host"),
            BookmarkEnvironment.normalizeSshPaths(Arrays.asList("-i", "key", "-Fconfig", "host"),
                DEBIAN_ROOTFS + "/root", "debian"));
        assertEquals(Arrays.asList("-i", "/key", "host"),
            BookmarkEnvironment.normalizeSshPaths(Arrays.asList("-i", "key", "host"),
                DEBIAN_ROOTFS, "debian"));
    }

    @Test public void relativeSshFilesRejectDifferentContainerAndRootfsPrefixes() {
        for (String cwd : Arrays.asList(PROOT_STATE + "/containers/debian-other/rootfs/root",
                DEBIAN_ROOTFS + "-backup/root", PROOT_STATE + "/containers/debian/sysdata",
                PROOT_STATE + "/installed-rootfs/debian-other/root")) {
            try {
                BookmarkEnvironment.normalizeSshPaths(Arrays.asList("-i", "key", "host"), cwd, "debian");
                fail("Unrelated container directory accepted: " + cwd);
            } catch (IllegalStateException expected) { }
        }
    }
}
