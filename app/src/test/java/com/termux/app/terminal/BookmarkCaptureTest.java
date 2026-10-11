package com.termux.app.terminal;

import android.app.Application;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.Base64;

import com.termux.terminal.TerminalSession;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, shadows = BookmarkCaptureTest.ProcOs.class)
public class BookmarkCaptureTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void prootQueriesCurrentDirectoryWhenKernelCwdStaysAtRootfs() throws Exception {
        File root = rootfs();
        TerminalSession session = prootSession(root, root);
        for (String path : new String[]{"/root/目录/a'b\nc", "/usr", "/"}) {
            Result result = capture(session);
            assertEquals("A PRoot kernel cwd is not the guest cwd", 1, result.queries);
            assertNull(result.bookmark);
            reply(session, path);
            assertNotNull(result.bookmark);
            assertEquals("proot", result.bookmark.kind);
            assertEquals("debian", result.bookmark.distro);
            assertEquals(path, result.bookmark.path);
            assertNotNull(result.bookmark.prootLaunch);
            assertEquals(root.getAbsolutePath(), result.bookmark.prootLaunch.workingDirectory);
        }
    }

    @Test public void prootAlsoQueriesWhenKernelCwdLooksLikeAGuestSubdirectory() throws Exception {
        File root = rootfs();
        File stale = new File(root, "root/old");
        Files.createDirectories(stale.toPath());
        TerminalSession session = prootSession(root, stale);
        Result result = capture(session);
        assertEquals(1, result.queries);
        reply(session, "/usr");
        assertEquals("/usr", result.bookmark.path);
    }

    @Test public void prootDoesNotRequireGuestKernelCwdToBeReadable() throws Exception {
        TerminalSession session = prootSession(rootfs(), null);
        Result result = capture(session);
        assertEquals(1, result.queries);
        reply(session, "/root/work");
        assertEquals("/root/work", result.bookmark.path);
    }

    @Test public void failedProotQueryNeverSavesKernelRootAsFallback() throws Exception {
        File root = rootfs();
        Result result = capture(prootSession(root, root));
        assertEquals(1, result.queries);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertNotNull(result.error);
        assertNull(result.bookmark);
    }

    @Test public void localShellStillSavesKernelCwdWithoutTypingAQuery() throws Exception {
        File cwd = temporary.newFolder("local");
        process(100, 0, 100, 100, cwd, "bash", "-l");
        Result result = capture(session());
        assertEquals(0, result.queries);
        assertEquals("local", result.bookmark.kind);
        assertEquals(cwd.getCanonicalPath(), result.bookmark.path);
        assertNull(result.bookmark.prootLaunch);
    }

    @Test public void prootCaptureIncludesAccountMountsShellAndLaunchEnvironment() throws Exception {
        File root = rootfs();
        process(100, 0, 100, 120, temporary.getRoot(), "bash", "-l");
        process(110, 100, 110, 120, root, "proot", "--rootfs=.", "--change-id=1000:1000",
            "--bind=/storage/shared:/mnt/shared", "--bind=/host/tmp:/tmp", "--sysvipc",
            "--cwd=/home/alice", "/bin/zsh", "-l");
        process(120, 110, 120, 120, null, "/bin/zsh", "-l");
        String[] environment = {"HOME=/home/alice", "USER=alice", "SHELL=/bin/zsh",
            "PATH=/usr/local/bin:/usr/bin:/bin", "LANG=zh_CN.UTF-8", "CUSTOM=value with spaces",
            "PROOT_NO_SECCOMP=1"};
        Files.write(new File(temporary.getRoot(), "110/environ").toPath(),
            (String.join("\0", environment) + "\0").getBytes(StandardCharsets.UTF_8));
        TerminalSession session = session();
        Result result = capture(session);
        reply(session, "/mnt/shared/work");
        assertEquals(Arrays.asList(environment), result.bookmark.prootLaunch.environment);
        assertTrue(result.bookmark.prootLaunch.arguments.contains("--change-id=1000:1000"));
        assertTrue(result.bookmark.prootLaunch.arguments.contains("--bind=/storage/shared:/mnt/shared"));
        assertTrue(result.bookmark.prootLaunch.arguments.contains("/bin/zsh"));
        assertTrue(BookmarkEnvironment.launchCommand(result.bookmark).contains("'--cwd=/mnt/shared/work'"));
    }

    @Test public void switchingUsersAfterProotLoginDoesNotSaveTheOriginalAccount() throws Exception {
        File root = rootfs();
        process(100, 0, 100, 120, temporary.getRoot(), "bash", "-l");
        process(110, 100, 110, 120, root, "proot", "--rootfs=.", "--change-id=0:0", "/bin/bash", "-l");
        process(115, 110, 115, 120, root, "su", "-l", "alice");
        process(120, 115, 120, 120, null, "/bin/bash", "-l");
        Result result = awaitCapture(session());
        assertNotNull(result.error);
        assertTrue(result.error.contains("切换了用户"));
        assertEquals(0, result.queries);
        assertNull(result.bookmark);
    }

    @Test public void originalSuLoginFromOlderProotDistroKeepsItsAccount() throws Exception {
        File root = rootfs();
        process(100, 0, 100, 120, temporary.getRoot(), "bash", "-l");
        process(110, 100, 110, 120, root, "proot", "--rootfs=.", "--root-id",
            "/usr/bin/env", "-i", "TERM=xterm-256color", "/bin/su", "-l", "alice");
        process(115, 110, 115, 120, root, "/bin/su", "-l", "alice");
        process(120, 115, 120, 120, null, "/bin/bash", "-l");
        TerminalSession session = session();
        Result result = capture(session);
        reply(session, "/home/alice/work");
        assertTrue(result.bookmark.prootLaunch.arguments.contains("alice"));
        assertTrue(BookmarkEnvironment.launchCommand(result.bookmark).contains("'/bin/su' '-l' 'alice' -c"));
    }

    private File rootfs() throws IOException {
        File root = new File(temporary.getRoot(), "proot-distro/containers/debian/rootfs");
        Files.createDirectories(root.toPath());
        return root;
    }

    private TerminalSession prootSession(File root, File guestKernelCwd) throws IOException {
        process(100, 0, 100, 120, temporary.getRoot(), "bash", "-l");
        process(110, 100, 110, 120, root, "proot", "--rootfs=.", "--cwd=/root", "/bin/bash");
        process(120, 110, 120, 120, guestKernelCwd, "/bin/bash", "-l");
        return session();
    }

    private TerminalSession session() {
        TerminalSession session = new TerminalSession("sh", "/", new String[0], new String[0], 100, null);
        ReflectionHelpers.setField(session, "mShellPid", 100);
        return session;
    }

    private void process(int pid, int parent, int group, int foregroundGroup, File cwd, String... args)
        throws IOException {
        File directory = temporary.newFolder(Integer.toString(pid));
        String stat = pid + " (shell) S " + parent + " " + group + " 100 34816 " + foregroundGroup + "\n";
        Files.write(new File(directory, "stat").toPath(), stat.getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "cmdline").toPath(),
            (String.join("\0", args) + "\0").getBytes(StandardCharsets.UTF_8));
        Files.write(new File(directory, "environ").toPath(),
            "HOME=/root\0USER=root\0PATH=/usr/bin:/bin\0".getBytes(StandardCharsets.UTF_8));
        if (cwd != null) Files.createSymbolicLink(new File(directory, "cwd").toPath(), cwd.toPath());
    }

    private Result capture(TerminalSession session) throws InterruptedException {
        Result result = awaitCapture(session);
        assertNull(result.error);
        return result;
    }

    private Result awaitCapture(TerminalSession session) throws InterruptedException {
        Result result = new Result();
        BookmarkEnvironment.capture(session, result, temporary.getRoot());
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (result.queries == 0 && result.bookmark == null && result.error == null
            && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(5);
        }
        assertTrue("Capture worker did not respond", result.queries > 0 || result.bookmark != null || result.error != null);
        return result;
    }

    private void reply(TerminalSession session, String path) {
        String nonce = ReflectionHelpers.getField(session, "mBookmarkLocationNonce");
        session.onBookmarkLocation("mdterm;" + nonce + ";" + Base64.encodeToString(
            (path + "\n").getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP));
    }

    private static final class Result implements BookmarkEnvironment.Callback {
        TerminalBookmark bookmark;
        String error;
        int queries;
        @Override public void onCaptured(TerminalBookmark value) { bookmark = value; }
        @Override public void onError(String message) { error = message; }
        @Override public void onQueryStarted() { queries++; }
    }

    @Implements(Os.class)
    public static class ProcOs {
        @Implementation public static String readlink(String path) throws ErrnoException {
            try {
                return Files.readSymbolicLink(Paths.get(path)).toString();
            } catch (IOException e) {
                throw new ErrnoException("readlink", OsConstants.ENOENT, e);
            }
        }
    }
}
