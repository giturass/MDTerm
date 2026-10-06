package com.termux.app.terminal;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

public class BookmarkEnvironmentTest {
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
}
