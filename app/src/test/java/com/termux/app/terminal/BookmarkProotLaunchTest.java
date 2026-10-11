package com.termux.app.terminal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class BookmarkProotLaunchTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void execLoginRestoresUidBindsFlagsShellAndGuestEnvironmentWithoutShellExpansion() throws Exception {
        File root = temporary.newFolder("rootfs");
        File proot = fakeProot();
        String path = "/home/alice/a'b;$(printf injected)\nnext";
        List<String> args = Arrays.asList(proot.getPath(), "--rootfs=.", "--change-id=1000:1000",
            "--bind=/shared dir:/mnt/shared", "--bind=/proc/self/fd/0:/dev/stdin",
            "--link2symlink", "--sysvipc", "--cwd=/home/alice", "/bin/zsh", "-l");
        List<String> env = Arrays.asList("HOME=/home/alice", "USER=alice", "PATH=/usr/bin:/bin",
            "SHELL=/bin/zsh", "CUSTOM=a'b;$(printf injected)\nnext", "PROOT_NO_SECCOMP=1");
        BookmarkProotLaunch saved = capture(args, env, root);
        TerminalBookmark bookmark = new TerminalBookmark("id", "test", "proot", "debian",
            Collections.emptyList(), path, saved);

        List<String> actual = launch(BookmarkEnvironment.launchCommand(bookmark));
        assertEquals(Arrays.asList(root.getPath(), "/home/alice", "alice", "/usr/bin:/bin",
            "a'b;$(printf injected)\nnext", "unset", "1"), actual.subList(0, 7));
        assertEquals(Arrays.asList("--rootfs=.", "--change-id=1000:1000",
            "--bind=/shared dir:/mnt/shared", "--bind=/proc/self/fd/0:/dev/stdin",
            "--link2symlink", "--sysvipc", "--cwd=" + path, "/bin/zsh", "-l"),
            actual.subList(7, actual.size()));
    }

    @Test public void shellLauncherRetainsGuestEnvWrapperButDoesNotStoreUnrelatedHostEnvironment() throws Exception {
        File root = temporary.newFolder("rootfs");
        List<String> args = Arrays.asList(fakeProot().getPath(), "-r", ".", "-i", "1000:1000",
            "-w", "/home/alice", "/usr/bin/env", "-i", "HOME=/home/alice", "USER=alice",
            "CUSTOM=guest value", "/bin/bash", "-l");
        BookmarkProotLaunch saved = capture(args, Arrays.asList("HOST_SECRET=do not save",
            "HOME=/host/home", "PROOT_NO_SECCOMP=1", "PROOT_L2S_DIR=/container/l2s"), root);
        assertEquals(Arrays.asList("HOME=/host/home", "PROOT_NO_SECCOMP=1", "PROOT_L2S_DIR=/container/l2s"), saved.environment);
        List<String> actual = launch(saved.launch("/home/alice/work", null));
        assertEquals(Arrays.asList("-r", ".", "-i", "1000:1000", "--cwd=/home/alice/work",
            "/usr/bin/env", "-i", "HOME=/home/alice", "USER=alice", "CUSTOM=guest value", "/bin/bash", "-l"),
            actual.subList(7, actual.size()));
    }

    @Test public void oldestLoginChangesDirectoryAfterSuAndCanBeSavedAgain() throws Exception {
        File root = temporary.newFolder("rootfs");
        List<String> args = Arrays.asList(fakeProot().getPath(), "--root-id", "-r", ".",
            "--cwd=/root", "/usr/bin/env", "-i", "TERM=xterm-256color", "/bin/su", "-l", "alice",
            "-c", "sh -c " + BookmarkEnvironment.quote("cd -- '/old' && exec \"${SHELL:-/bin/sh}\" -l"));
        BookmarkProotLaunch saved = capture(args, Collections.singletonList("PROOT_NO_SECCOMP=1"), root);
        assertEquals("alice", saved.arguments.get(saved.arguments.size() - 1));
        List<String> actual = launch(saved.launch("/work/new's", null));
        String enter = "cd -- '/work/new'\\''s' && exec \"${SHELL:-/bin/sh}\" -l";
        assertEquals(Arrays.asList("--root-id", "-r", ".", "--cwd=/work/new's",
            "/usr/bin/env", "-i", "TERM=xterm-256color", "/bin/su", "-l", "alice", "-c",
            "/bin/sh -c " + BookmarkEnvironment.quote(enter)), actual.subList(7, actual.size()));
    }

    @Test public void sshInsideProotRetainsGuestIdentityAndAppliesRemoteDirectoryOnlyToSsh() throws Exception {
        File root = temporary.newFolder("rootfs");
        BookmarkProotLaunch saved = capture(Arrays.asList(fakeProot().getPath(), "-r", ".",
            "--change-id=1000:1000", "--cwd=/home/alice", "/bin/fish", "-l"),
            Arrays.asList("HOME=/home/alice", "USER=alice", "PATH=/usr/bin:/bin"), root);
        TerminalBookmark bookmark = new TerminalBookmark("ssh", "remote", "ssh", "debian",
            Arrays.asList("-i", "/home/alice/key", "user@host"), "/remote/work", saved);
        List<String> actual = launch(BookmarkEnvironment.launchCommand(bookmark));
        assertEquals(Arrays.asList("-r", ".", "--change-id=1000:1000", "--cwd=/home/alice",
            "/bin/fish", "-l", "-c"), actual.subList(7, actual.size() - 1));
        assertTrue(BookmarkEnvironment.isProotRestoreCommand(actual.get(actual.size() - 1)));
        assertTrue(actual.get(actual.size() - 1).contains("/remote/work"));
        List<String> reopenedArgs = new ArrayList<>();
        reopenedArgs.add(saved.arguments.get(0));
        reopenedArgs.addAll(actual.subList(7, actual.size()));
        BookmarkProotLaunch reopened = capture(reopenedArgs, saved.environment, root);
        assertEquals(saved.arguments, reopened.arguments);
    }

    @Test public void captureRejectsBindingsThatOnlyExistForTheOriginalProcess() throws Exception {
        File root = temporary.newFolder("rootfs");
        for (String source : Arrays.asList("/proc/1234/fd/5", "/proc/self/fd/5", "/dev/fd/7", "/dev/pts/4")) {
            try {
                capture(Arrays.asList("proot", "-r", ".", "--bind=" + source + ":/mnt",
                    "/bin/bash", "-l"), Collections.emptyList(), root);
                fail("Expired bind source accepted: " + source);
            } catch (IllegalStateException expected) { }
        }
    }

    @Test public void captureDoesNotReplayAnArbitraryGuestCommand() throws Exception {
        File root = temporary.newFolder("rootfs");
        try {
            capture(Arrays.asList("proot", "-r", ".", "/bin/bash", "-c", "custom setup"),
                Collections.emptyList(), root);
            fail("Arbitrary guest program must not be treated as a login shell");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void captureAcceptsBundledInteractiveLoginFlagsAndRejectsArbitrarySuCommands() throws Exception {
        File root = temporary.newFolder("rootfs");
        for (String flag : Arrays.asList("-il", "-li")) {
            BookmarkProotLaunch saved = capture(Arrays.asList("proot", "-r", ".", "/bin/bash", flag),
                Collections.emptyList(), root);
            assertEquals(flag, saved.arguments.get(saved.arguments.size() - 1));
        }
        try {
            capture(Arrays.asList("proot", "-r", ".", "/usr/bin/env", "-i", "/bin/su", "-l",
                "root", "-c", "su - alice"), Collections.emptyList(), root);
            fail("An arbitrary su command must not restore a different account");
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void legacySshProotBookmarkCanBeSavedAgainWithEitherLauncherGeneration() throws Exception {
        File root = temporary.newFolder("rootfs");
        File pd = new File(temporary.getRoot(), "pd");
        Files.write(pd.toPath(), ("#!" + shell() + "\nprintf '%s\\000' \"$@\"\n")
            .getBytes(StandardCharsets.UTF_8));
        assertTrue(pd.setExecutable(true));
        TerminalBookmark legacy = new TerminalBookmark("id", "SSH", "ssh", "debian",
            Arrays.asList("-i", "/home/alice/a'b key", "user@host"), "/old/dir's");
        List<String> login = launch("PATH=" + BookmarkEnvironment.quote(temporary.getRoot().getPath())
            + " " + shell() + " -c " + BookmarkEnvironment.quote(BookmarkEnvironment.launchCommand(legacy)));
        assertEquals(Arrays.asList("login", "debian", "--", "sh", "-lc"), login.subList(0, 5));
        String sshCommand = login.get(5);

        List<String> v4 = Arrays.asList("proot", "-r", ".", "/usr/bin/env", "-i", "HOME=/home/alice",
            "sh", "-lc", sshCommand);
        BookmarkProotLaunch oldLauncher = capture(v4, Collections.emptyList(), root);
        assertEquals(Arrays.asList("proot", "-r", ".", "/usr/bin/env", "-i", "HOME=/home/alice",
            "sh", "-l"), oldLauncher.arguments);

        List<String> v5 = Arrays.asList("proot", "-r", ".", "/bin/bash", "-c",
            "sh -lc " + BookmarkEnvironment.quote(sshCommand));
        BookmarkProotLaunch newLauncher = capture(v5, Collections.singletonList("HOME=/home/alice"), root);
        assertEquals(Arrays.asList("proot", "-r", ".", "/bin/bash"), newLauncher.arguments);
        assertFalse(BookmarkEnvironment.isProotRestoreCommand("sh -lc 'exec ssh -t host; unsafe'"));
    }

    private BookmarkProotLaunch capture(List<String> args, List<String> env, File root) throws Exception {
        File process = new File(temporary.getRoot(), "110");
        Files.createDirectories(process.toPath());
        Files.write(new File(process, "environ").toPath(),
            (String.join("\0", env) + (env.isEmpty() ? "" : "\0")).getBytes(StandardCharsets.UTF_8));
        return BookmarkProotLaunch.capture(new BookmarkProcessSnapshot.Process(110, args, null),
            root.getPath(), temporary.getRoot());
    }

    private File fakeProot() throws Exception {
        File script = new File(temporary.getRoot(), "proot");
        Files.write(script.toPath(), ("#!" + shell() + "\n"
            + "printf '%s\\000' \"$PWD\" \"$HOME\" \"$USER\" \"$PATH\" \"${CUSTOM-unset}\" "
            + "\"${HOST_SECRET-unset}\" \"${PROOT_NO_SECCOMP-unset}\" \"$@\"\n")
            .getBytes(StandardCharsets.UTF_8));
        assertTrue(script.setExecutable(true));
        return script;
    }

    private List<String> launch(String command) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(shell(), "-c", command);
        builder.environment().put("HOST_SECRET", "fresh host value");
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream stream = process.getInputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = stream.read(buffer)) != -1) output.write(buffer, 0, count);
        }
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        assertEquals(text, 0, process.waitFor());
        return new ArrayList<>(Arrays.asList(text.split("\u0000")));
    }

    private static String shell() {
        String prefix = System.getenv("PREFIX");
        if (prefix != null && new File(prefix + "/bin/sh").canExecute()) return prefix + "/bin/sh";
        return new File("/bin/sh").canExecute() ? "/bin/sh" : "/system/bin/sh";
    }
}
