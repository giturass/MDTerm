package com.termux.app;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class GhosttyResourcesInstallerTest {

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsAndRepairsCompiledTerminfoWithoutRewritingUnchangedFiles() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File destination = new File(temporary.getRoot(), "terminfo/x/xterm-ghostty");
        byte[] expected = asset(context, GhosttyResourcesInstaller.ASSET_PATH);
        int magic = (expected[0] & 0xff) | ((expected[1] & 0xff) << 8);
        assertTrue(magic == 0x011a || magic == 0x021e);
        assertTrue(new String(expected, StandardCharsets.ISO_8859_1).contains("xterm-ghostty"));

        assertTrue(GhosttyResourcesInstaller.install(context, destination));
        assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));

        Files.setLastModifiedTime(destination.toPath(), FileTime.fromMillis(1000));
        long modified = destination.lastModified();
        assertTrue(GhosttyResourcesInstaller.install(context, destination));
        assertEquals(modified, destination.lastModified());

        Files.write(destination.toPath(), "broken".getBytes(StandardCharsets.US_ASCII));
        assertTrue(GhosttyResourcesInstaller.install(context, destination));
        assertArrayEquals(expected, Files.readAllBytes(destination.toPath()));
        assertNoTemporaryFiles(destination.getParentFile());
    }

    @Test
    public void installsAndRepairsBundledScriptsAndDiscoveryLoaders() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File prefix = temporary.newFolder("prefix");
        File resources = new File(prefix, "share/ghostty/shell-integration");
        assertTrue(GhosttyResourcesInstaller.installShellIntegration(context, prefix));

        String[][] installedFiles = {
            {"bash/ghostty.bash", "share/ghostty/shell-integration/bash/ghostty.bash"},
            {"bash/bash-preexec.sh", "share/ghostty/shell-integration/bash/bash-preexec.sh"},
            {"zsh/zshenv", "share/ghostty/shell-integration/zsh/.zshenv"},
            {"zsh/ghostty-integration", "share/ghostty/shell-integration/zsh/ghostty-integration"},
            {"fish/vendor_conf.d/ghostty-shell-integration.fish",
                "share/ghostty/shell-integration/fish/vendor_conf.d/ghostty-shell-integration.fish"},
            {"elvish/lib/ghostty-integration.elv",
                "share/ghostty/shell-integration/elvish/lib/ghostty-integration.elv"},
            {"nushell/vendor/autoload/ghostty.nu",
                "share/ghostty/shell-integration/nushell/vendor/autoload/ghostty.nu"},
            {"termux-ghostty.fish", "etc/fish/conf.d/termux-ghostty.fish"},
            {"elvish/lib/ghostty-integration.elv", "share/elvish/lib/ghostty-integration.elv"}
        };
        for (String[] file : installedFiles) {
            File destination = new File(prefix, file[1]);
            assertArrayEquals(asset(context, "ghostty/shell-integration/" + file[0]),
                Files.readAllBytes(destination.toPath()));
            Files.setLastModifiedTime(destination.toPath(), FileTime.fromMillis(1000));
        }
        assertTrue(GhosttyResourcesInstaller.installShellIntegration(context, prefix));
        for (String[] file : installedFiles) {
            File destination = new File(prefix, file[1]);
            assertEquals(1000, destination.lastModified());
            Files.write(destination.toPath(), "broken".getBytes(StandardCharsets.US_ASCII));
        }
        assertTrue(GhosttyResourcesInstaller.installShellIntegration(context, prefix));
        for (String[] file : installedFiles) {
            File destination = new File(prefix, file[1]);
            assertArrayEquals(asset(context, "ghostty/shell-integration/" + file[0]),
                Files.readAllBytes(destination.toPath()));
            assertNoTemporaryFiles(destination.getParentFile());
        }

        assertFalse(new File(resources, "zsh/zshenv").exists());
        assertFalse(new File(prefix, "etc/bash.bashrc").exists());
        assertFalse(new File(prefix, "etc/zshrc").exists());
        assertFalse(new File(prefix, "etc/bashrc.d").exists());
        assertFalse(new File(prefix, "etc/zshrc.d").exists());
        // Nu reports OSC 133 natively; do not import desktop ssh/sudo wrappers.
        assertFalse(new File(prefix, "share/nushell/vendor/autoload").exists());
    }

    @Test
    public void preservesSystemRcBytesPermissionsAndDoesNotDuplicateHooks() throws Exception {
        byte[] existing = "# legacy café\r\nexport SYSTEM_RC_LOADED=yes"
            .getBytes(StandardCharsets.ISO_8859_1);
        Set<PosixFilePermission> permissions = PosixFilePermissions.fromString("rw-r-----");
        for (String[] rc : new String[][]{
            {"bash.bashrc", "bash/ghostty.bash"}, {"zshrc", "zsh/ghostty-integration"}}) {
            File destination = temporary.newFile(rc[0]);
            Files.write(destination.toPath(), existing);
            Files.setPosixFilePermissions(destination.toPath(), permissions);

            assertTrue(GhosttyResourcesInstaller.installRcLoader(destination, rc[1]));
            byte[] installed = Files.readAllBytes(destination.toPath());
            assertArrayEquals(existing, Arrays.copyOf(installed, existing.length));
            assertEquals(permissions, Files.getPosixFilePermissions(destination.toPath()));
            String contents = new String(installed, StandardCharsets.ISO_8859_1);
            String source = ". \"$GHOSTTY_RESOURCES_DIR/shell-integration/" + rc[1] + "\"";
            assertTrue(contents.contains(source));
            assertEquals(contents.indexOf(source), contents.lastIndexOf(source));

            Files.setLastModifiedTime(destination.toPath(), FileTime.fromMillis(1000));
            assertTrue(GhosttyResourcesInstaller.installRcLoader(destination, rc[1]));
            assertEquals(1000, destination.lastModified());
            assertArrayEquals(installed, Files.readAllBytes(destination.toPath()));
            assertNoTemporaryFiles(destination.getParentFile());
        }
    }

    @Test
    public void preservesCustomRcSymlinksAndTheirHomeTargets() throws Exception {
        File home = temporary.newFolder("home");
        Path target = new File(home, ".zshrc").toPath();
        byte[] existing = "# custom user config\n".getBytes(StandardCharsets.UTF_8);
        Files.write(target, existing);
        Path rc = temporary.getRoot().toPath().resolve("zshrc");
        Files.createSymbolicLink(rc, target);

        assertTrue(GhosttyResourcesInstaller.installRcLoader(rc.toFile(), "zsh/ghostty-integration"));
        assertTrue(Files.isSymbolicLink(rc));
        assertEquals(target, Files.readSymbolicLink(rc));
        assertArrayEquals(existing, Files.readAllBytes(target));

        Path missingTarget = new File(home, ".bashrc").toPath();
        Path danglingRc = temporary.getRoot().toPath().resolve("bash.bashrc");
        Files.createSymbolicLink(danglingRc, missingTarget);
        assertTrue(GhosttyResourcesInstaller.installRcLoader(danglingRc.toFile(), "bash/ghostty.bash"));
        assertTrue(Files.isSymbolicLink(danglingRc));
        assertFalse(Files.exists(missingTarget));
        assertEquals(1, home.list().length);
    }

    @Test
    public void failedResourceInstallLeavesSystemRcUntouchedAndCanBeRetried() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File prefix = temporary.newFolder("prefix");
        File etc = new File(prefix, "etc");
        assertTrue(etc.mkdirs());
        File rc = new File(etc, "bash.bashrc");
        byte[] existing = "# package configuration\n".getBytes(StandardCharsets.UTF_8);
        Files.write(rc.toPath(), existing);
        File conflict = new File(prefix, "share/ghostty/shell-integration/bash/ghostty.bash");
        assertTrue(conflict.mkdirs());
        Path keep = new File(conflict, "keep").toPath();
        Files.write(keep, existing);

        assertFalse(GhosttyResourcesInstaller.installShellIntegration(context, prefix));
        assertArrayEquals(existing, Files.readAllBytes(rc.toPath()));
        assertArrayEquals(existing, Files.readAllBytes(keep));
        assertFalse(new File(prefix, "etc/fish/conf.d/termux-ghostty.fish").exists());
        assertNoTemporaryFiles(conflict.getParentFile());

        Files.delete(keep);
        Files.delete(conflict.toPath());
        assertTrue(GhosttyResourcesInstaller.installShellIntegration(context, prefix));
        assertArrayEquals(asset(context, "ghostty/shell-integration/bash/ghostty.bash"),
            Files.readAllBytes(conflict.toPath()));
        assertTrue(new String(Files.readAllBytes(rc.toPath()), StandardCharsets.UTF_8)
            .contains("$GHOSTTY_RESOURCES_DIR/shell-integration/bash/ghostty.bash"));
    }

    private static void assertNoTemporaryFiles(File directory) throws Exception {
        try (Stream<Path> files = Files.list(directory.toPath())) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    private static byte[] asset(Context context, String path) throws Exception {
        try (InputStream input = context.getAssets().open(path);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1)
                output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
