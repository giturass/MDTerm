package com.termux.app.terminal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.*;

public class BookmarkProcessSnapshotTest {
    @Rule public TemporaryFolder proc = new TemporaryFolder();

    @Test public void localShellCanBeReadWithoutKernelChildrenFiles() throws IOException {
        process(100, 1, 100, 100, "bash", "/data/data/com.termux/files/usr/bin/bash", "-l");

        BookmarkProcessSnapshot snapshot = BookmarkProcessSnapshot.read(proc.getRoot(), 100);

        assertEquals(100, snapshot.foregroundGroup);
        assertProcess(snapshot.foreground, 100, "/data/data/com.termux/files/usr/bin/bash", "-l");
        assertNull(snapshot.foreground.parent);
        assertFalse(new File(proc.getRoot(), "100/task").exists());
    }

    @Test public void nestedShellIsFoundThroughParentIds() throws IOException {
        process(100, 1, 100, 120, "bash", "bash", "-l");
        process(110, 100, 110, 120, "bash", "bash");
        process(120, 110, 120, 120, "zsh", "zsh", "-l");

        BookmarkProcessSnapshot snapshot = BookmarkProcessSnapshot.read(proc.getRoot(), 100);

        assertEquals(120, snapshot.foregroundGroup);
        assertProcess(snapshot.foreground, 120, "zsh", "-l");
        assertProcess(snapshot.foreground.parent, 110, "bash");
        assertProcess(snapshot.foreground.parent.parent, 100, "bash", "-l");
        assertNull(snapshot.foreground.parent.parent.parent);
    }

    @Test public void foregroundRetainsSshAndProotAncestorArguments() throws IOException {
        process(100, 1, 100, 130, "bash", "bash", "-l");
        process(110, 100, 110, 130, "proot", "proot", "--rootfs=/rootfs/debian",
            "--cwd=/root/工作 目录");
        process(120, 110, 120, 130, "ssh", "/usr/bin/ssh", "-p", "2222", "-i",
            "/root/a key", "-Jjump", "user@host");
        process(130, 120, 130, 130, "ssh helper", "ssh-helper", "");

        BookmarkProcessSnapshot snapshot = BookmarkProcessSnapshot.read(proc.getRoot(), 100);

        assertProcess(snapshot.foreground, 130, "ssh-helper", "");
        assertProcess(snapshot.foreground.parent, 120, "/usr/bin/ssh", "-p", "2222", "-i",
            "/root/a key", "-Jjump", "user@host");
        assertProcess(snapshot.foreground.parent.parent, 110, "proot", "--rootfs=/rootfs/debian",
            "--cwd=/root/工作 目录");
        assertProcess(snapshot.foreground.parent.parent.parent, 100, "bash", "-l");
        assertNull(snapshot.foreground.parent.parent.parent.parent);
    }

    @Test public void deepestMatchingProcessWinsAmongMultipleChildren() throws IOException {
        process(100, 1, 100, 110, "bash", "bash", "-l");
        process(110, 100, 110, 110, "wrapper", "wrapper");
        process(120, 110, 110, 110, "wrapper child", "wrapper-child");
        process(130, 120, 110, 110, "nested ) shell", "bash", "-l");
        process(140, 100, 110, 110, "sibling", "sibling");
        process(150, 130, 150, 110, "background", "background");

        BookmarkProcessSnapshot snapshot = BookmarkProcessSnapshot.read(proc.getRoot(), 100);

        assertProcess(snapshot.foreground, 130, "bash", "-l");
        assertProcess(snapshot.foreground.parent, 120, "wrapper-child");
    }

    @Test public void sameGroupProgramIsNotMistakenForTheRootShell() throws IOException {
        process(100, 1, 100, 100, "sh", "sh", "-l");
        process(110, 100, 100, 100, "vim", "vim", "notes.md");

        BookmarkProcessSnapshot snapshot = BookmarkProcessSnapshot.read(proc.getRoot(), 100);

        assertProcess(snapshot.foreground, 110, "vim", "notes.md");
    }

    @Test public void unrelatedProcessesAndUnreadableEntriesDoNotBreakSnapshot() throws IOException {
        process(100, 1, 100, 110, "bash", "bash", "-l");
        process(110, 100, 110, 110, "zsh", "zsh");
        process(200, 1, 200, 110, "other shell", "other-shell");
        process(210, 200, 110, 110, "unrelated", "unrelated");
        process(220, 210, 110, 110, "unrelated child", "unrelated-child");
        stat(300, 1, 300, 300, "no cmdline");
        stat(310, 100, 310, 110, "background without cmdline");
        proc.newFolder("900");
        proc.newFolder("901", "stat");
        File malformed = proc.newFolder("902");
        Files.write(new File(malformed, "stat").toPath(), "not a proc stat\n".getBytes(StandardCharsets.UTF_8));
        proc.newFolder("self");

        BookmarkProcessSnapshot snapshot = BookmarkProcessSnapshot.read(proc.getRoot(), 100);

        assertProcess(snapshot.foreground, 110, "zsh");
        assertProcess(snapshot.foreground.parent, 100, "bash", "-l");
    }

    @Test(expected = IllegalStateException.class)
    public void unrelatedMatchingGroupCannotReplaceMissingForeground() throws IOException {
        process(100, 1, 100, 500, "bash", "bash", "-l");
        process(110, 100, 110, 500, "background", "background");
        process(500, 1, 500, 500, "other shell", "other-shell");
        process(510, 500, 500, 500, "other child", "other-child");

        BookmarkProcessSnapshot.read(proc.getRoot(), 100);
    }

    @Test(expected = IOException.class)
    public void unreadableForegroundCommandDoesNotFallBackToRootShell() throws IOException {
        process(100, 1, 100, 110, "bash", "bash", "-l");
        stat(110, 100, 110, 110, "zsh");

        BookmarkProcessSnapshot.read(proc.getRoot(), 100);
    }

    @Test(expected = IOException.class)
    public void unreadableAncestorCommandDoesNotLoseEnvironmentInformation() throws IOException {
        process(100, 1, 100, 120, "bash", "bash", "-l");
        stat(110, 100, 110, 120, "proot");
        process(120, 110, 120, 120, "bash", "bash", "-l");

        BookmarkProcessSnapshot.read(proc.getRoot(), 100);
    }

    private void process(int pid, int parent, int group, int foregroundGroup, String comm,
                         String... args) throws IOException {
        File directory = stat(pid, parent, group, foregroundGroup, comm);
        ByteArrayOutputStream command = new ByteArrayOutputStream();
        for (String argument : args) {
            command.write(argument.getBytes(StandardCharsets.UTF_8));
            command.write(0);
        }
        Files.write(new File(directory, "cmdline").toPath(), command.toByteArray());
    }

    private File stat(int pid, int parent, int group, int foregroundGroup, String comm) throws IOException {
        File directory = proc.newFolder(Integer.toString(pid));
        String value = pid + " (" + comm + ") S " + parent + " " + group + " 100 34816 "
            + foregroundGroup + " 0 0 0 0 0 0 0 0 0 0 0 1 0 1000 0 0\n";
        Files.write(new File(directory, "stat").toPath(), value.getBytes(StandardCharsets.UTF_8));
        return directory;
    }

    private void assertProcess(BookmarkProcessSnapshot.Process process, int pid, String... args) {
        assertNotNull(process);
        assertEquals(pid, process.pid);
        assertEquals(Arrays.asList(args), process.args);
    }
}
