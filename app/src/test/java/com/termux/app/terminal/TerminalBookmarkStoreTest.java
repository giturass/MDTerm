package com.termux.app.terminal;

import android.app.Application;
import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class TerminalBookmarkStoreTest {
    private Context context;
    private TerminalBookmarkStore store;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("terminal_bookmarks", Context.MODE_PRIVATE).edit().clear().commit();
        store = new TerminalBookmarkStore(context);
    }

    @Test
    public void savedEnvironmentAndQuotedPathsSurviveReopeningAndRename() {
        List<String> sshArgs = Arrays.asList("-p", "2222", "-i", "/home/key with spaces", "user@host");
        store.add(new TerminalBookmark("ssh", "Server", "ssh", "", sshArgs, "/work/中文 'directory"));
        store.add(new TerminalBookmark("proot", "Debian", "proot", "debian", Collections.emptyList(), "/root/work"));
        store.add(new TerminalBookmark("local", "Home", "local", "", Collections.emptyList(), "/data/home"));

        TerminalBookmarkStore reopened = new TerminalBookmarkStore(context);
        reopened.rename("ssh", "Renamed server");
        TerminalBookmark saved = reopened.getAll().get(0);
        assertEquals("Renamed server", saved.name);
        assertEquals("ssh", saved.kind);
        assertEquals(sshArgs, saved.sshArgs);
        assertEquals("/work/中文 'directory", saved.path);
        assertEquals("debian", reopened.getAll().get(1).distro);
        reopened.delete("proot");
        assertEquals(2, reopened.getAll().size());
        assertEquals("local", reopened.getAll().get(1).id);
    }

    @Test
    public void damagedEntryDoesNotHideValidBookmarks() {
        context.getSharedPreferences("terminal_bookmarks", Context.MODE_PRIVATE).edit()
            .putString("bookmarks", "[{\"id\":\"broken\"},"
                + "{\"id\":\"ok\",\"name\":\"Home\",\"kind\":\"local\",\"path\":\"/home\"}]")
            .commit();
        assertEquals(1, store.getAll().size());
        assertEquals("ok", store.getAll().get(0).id);
    }

    @Test public void prootLaunchSettingsSurviveStorageAndRenameWhileOldBookmarksRemainCompatible() {
        BookmarkProotLaunch launch = new BookmarkProotLaunch(Arrays.asList("proot", "--rootfs=.",
            "--change-id=1000:1000", "--bind=/shared dir:/mnt/shared", "/bin/zsh", "-l"),
            Arrays.asList("HOME=/home/alice", "USER=alice", "CUSTOM=a'b\nvalue"), "/containers/debian/rootfs");
        store.add(new TerminalBookmark("full", "Saved", "proot", "debian", Collections.emptyList(),
            "/mnt/shared/工作", launch));
        store.add(new TerminalBookmark("legacy", "Legacy", "proot", "debian", Collections.emptyList(), "/root"));
        TerminalBookmarkStore reopened = new TerminalBookmarkStore(context);
        reopened.rename("full", "Renamed");
        TerminalBookmark saved = reopened.getAll().get(0);
        assertEquals("Renamed", saved.name);
        assertEquals(launch.arguments, saved.prootLaunch.arguments);
        assertEquals(launch.environment, saved.prootLaunch.environment);
        assertEquals(launch.workingDirectory, saved.prootLaunch.workingDirectory);
        assertTrue(BookmarkEnvironment.launchCommand(saved).contains("'--cwd=/mnt/shared/工作'"));
        TerminalBookmark legacy = reopened.getAll().get(1);
        assertNull(legacy.prootLaunch);
        assertTrue(BookmarkEnvironment.launchCommand(legacy).contains("login --work-dir '/root' 'debian'"));
    }
}
