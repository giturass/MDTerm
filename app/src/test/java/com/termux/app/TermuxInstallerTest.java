package com.termux.app;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import static org.junit.Assert.*;

public class TermuxInstallerTest {
    private static final byte[] DEFAULT_MOTD = "Default welcome\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CUSTOM_MOTD = "My custom welcome\n".getBytes(StandardCharsets.UTF_8);

    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void installsExecutableMotdAndCreatesMissingParentDirectories() throws Exception {
        File motd = new File(temporary.getRoot(), "home/.termux/motd.sh");
        assertFalse(motd.getParentFile().exists());

        TermuxInstaller.installDefaultMotd(new ByteArrayInputStream(DEFAULT_MOTD), motd);

        assertTrue(motd.isFile());
        assertArrayEquals(DEFAULT_MOTD, Files.readAllBytes(motd.toPath()));
        assertTrue(motd.canExecute());
    }

    @Test
    public void preservesExistingCustomMotd() throws Exception {
        File motd = temporary.newFile("motd.sh");
        Files.write(motd.toPath(), CUSTOM_MOTD);

        TermuxInstaller.installDefaultMotd(new ByteArrayInputStream(DEFAULT_MOTD), motd);

        assertArrayEquals(CUSTOM_MOTD, Files.readAllBytes(motd.toPath()));
    }

    @Test
    public void preservesEmptyMotd() throws Exception {
        File motd = temporary.newFile("motd.sh");

        TermuxInstaller.installDefaultMotd(new ByteArrayInputStream(DEFAULT_MOTD), motd);

        assertTrue(motd.isFile());
        assertEquals(0, Files.size(motd.toPath()));
    }

    @Test
    public void preservesSymbolicLinkAndItsCustomTarget() throws Exception {
        Path target = temporary.newFile("custom-motd.sh").toPath();
        Files.write(target, CUSTOM_MOTD);
        Path motd = temporary.getRoot().toPath().resolve("motd.sh");
        Files.createSymbolicLink(motd, target.getFileName());

        TermuxInstaller.installDefaultMotd(new ByteArrayInputStream(DEFAULT_MOTD), motd.toFile());

        assertTrue(Files.isSymbolicLink(motd));
        assertEquals(target.getFileName(), Files.readSymbolicLink(motd));
        assertArrayEquals(CUSTOM_MOTD, Files.readAllBytes(target));
    }

    @Test
    public void preservesDanglingSymbolicLinkWithoutCreatingItsTarget() throws Exception {
        Path target = temporary.getRoot().toPath().resolve("missing-motd.sh");
        Path motd = temporary.getRoot().toPath().resolve("motd.sh");
        Files.createSymbolicLink(motd, target.getFileName());

        TermuxInstaller.installDefaultMotd(new ByteArrayInputStream(DEFAULT_MOTD), motd.toFile());

        assertTrue(Files.isSymbolicLink(motd));
        assertEquals(target.getFileName(), Files.readSymbolicLink(motd));
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    public void preservesExistingDirectoryAndItsContents() throws Exception {
        File motd = temporary.newFolder("motd.sh");
        Path customFile = new File(motd, "custom.txt").toPath();
        Files.write(customFile, CUSTOM_MOTD);

        TermuxInstaller.installDefaultMotd(new ByteArrayInputStream(DEFAULT_MOTD), motd);

        assertTrue(Files.isDirectory(motd.toPath(), LinkOption.NOFOLLOW_LINKS));
        assertArrayEquals(CUSTOM_MOTD, Files.readAllBytes(customFile));
    }

    @Test
    public void failedCopyRemovesPartialFileAndAllowsRetry() throws Exception {
        File motd = new File(temporary.getRoot(), ".termux/motd.sh");
        IOException failure = new IOException("Input failed partway through the MOTD");
        InputStream failingInput = new InputStream() {
            private int bytesRead;

            @Override
            public int read() throws IOException {
                if (bytesRead == 4) throw failure;
                return DEFAULT_MOTD[bytesRead++] & 0xff;
            }
        };

        assertSame(failure, assertThrows(IOException.class,
            () -> TermuxInstaller.installDefaultMotd(failingInput, motd)));
        assertFalse(Files.exists(motd.toPath(), LinkOption.NOFOLLOW_LINKS));

        TermuxInstaller.installDefaultMotd(new ByteArrayInputStream(DEFAULT_MOTD), motd);

        assertArrayEquals(DEFAULT_MOTD, Files.readAllBytes(motd.toPath()));
        assertTrue(motd.canExecute());
    }
}
