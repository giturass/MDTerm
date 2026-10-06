package com.termux.app.terminal;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class BookmarkLaunchCommandTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void prootLaunchWithoutShellEnvironmentUsesDefaultGuestShell() throws Exception {
        File bin = temporary.newFolder("bin");
        launcher(bin, "pd");
        launcher(bin, "proot-distro");
        String path = "/root/space dir/a'b;$(printf injected)\nnext";

        assertEquals(Arrays.asList("pd", "login", "--work-dir", path, "debian"),
            launch(bin, path));
    }

    @Test public void prootLaunchFallsBackToPackageExecutableWhenPdIsOnlyAnAlias() throws Exception {
        File bin = temporary.newFolder("bin");
        launcher(bin, "proot-distro");

        assertEquals(Arrays.asList("proot-distro", "login", "--work-dir", "/root/test", "debian"),
            launch(bin, "/root/test"));
    }

    private List<String> launch(File bin, String path) throws Exception {
        TerminalBookmark bookmark = new TerminalBookmark("id", "test", "proot", "debian",
            Collections.emptyList(), path);
        ProcessBuilder builder = new ProcessBuilder(shell(), "-c", BookmarkEnvironment.launchCommand(bookmark));
        builder.environment().remove("SHELL");
        builder.environment().put("PATH", bin.getAbsolutePath());
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
        return Arrays.asList(text.split("\u0000"));
    }

    private void launcher(File bin, String name) throws Exception {
        File script = new File(bin, name);
        Files.write(script.toPath(), ("#!" + shell() + "\nprintf '%s\\000' '" + name
            + "' \"$@\"\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(script.setExecutable(true));
    }

    private static String shell() {
        String prefix = System.getenv("PREFIX");
        if (prefix != null && new File(prefix + "/bin/sh").canExecute()) return prefix + "/bin/sh";
        return new File("/bin/sh").canExecute() ? "/bin/sh" : "/system/bin/sh";
    }
}
