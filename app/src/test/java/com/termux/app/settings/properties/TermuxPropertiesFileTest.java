package com.termux.app.settings.properties;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.Assert.*;

public class TermuxPropertiesFileTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder(testDirectory());

    private static File testDirectory() {
        File directory = new File("build/tmp/termux-properties-tests");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Cannot create test directory: " + directory);
        }
        return directory;
    }

    @Test
    public void changesOnlyRequestedSettingAndKeepsToolbarAndCommentsByteForByte() throws Exception {
        File file = temporary.newFile("termux.properties");
        String before = "# 用户设置\r\n# fullscreen = false\r\n"
            + "extra-keys = [['ESC','CTRL'], \\\r\n  ['ALT','TAB']]\r\n"
            + "fullscreen = false\r\n! trailing comment\\\r\nunknown:value\n";
        write(file, before);

        TermuxPropertiesFile store = new TermuxPropertiesFile(file);
        store.set("fullscreen", "true");

        assertEquals(before.replace("\r\nfullscreen = false", "\r\nfullscreen = true"), read(file));
        assertEquals("[['ESC','CTRL'], ['ALT','TAB']]", store.read().getProperty("extra-keys"));
        assertEquals("value", store.read().getProperty("unknown"));
    }

    @Test
    public void escapedAndContinuedDuplicateKeysCannotOverrideSavedValue() throws Exception {
        File file = temporary.newFile("termux.properties");
        write(file, "# fullscreen = false\nfull\\\n  screen:false\n"
            + "full\\u0073creen false\nfullscreen=true\nother=stay\n");
        TermuxPropertiesFile store = new TermuxPropertiesFile(file);

        store.set("fullscreen", "new value");

        assertEquals("new value", store.read().getProperty("fullscreen"));
        assertEquals("# fullscreen = false\nfullscreen = new value\n"
            + "fullscreen = new value\nfullscreen = new value\nother=stay\n", read(file));
        store.set("fullscreen", null);
        assertEquals("# fullscreen = false\nother=stay\n", read(file));
        assertNull(store.read().getProperty("fullscreen"));
    }

    @Test
    public void escapedSeparatorsAndWhitespaceRoundTripWithoutNewPropertyInjection() throws Exception {
        File file = temporary.newFile("termux.properties");
        write(file, "key\\ with\\:separators\\==old\nunknown=keep\n");
        String key = "key with:separators=";
        String value = "  中文\\path\nfullscreen = true\r\t\f#!:= \ud83d\ude00\ud800";
        TermuxPropertiesFile store = new TermuxPropertiesFile(file);

        store.set(key, value);

        Properties properties = store.read();
        assertEquals(value, properties.getProperty(key));
        assertEquals(2, properties.size());
        assertEquals("keep", properties.getProperty("unknown"));
    }

    @Test
    public void readsPropertiesSyntaxIncludingCommentCharactersInContinuations() throws Exception {
        File file = temporary.newFile("termux.properties");
        String source = "! ignored\\\n# ignored too\n"
            + "spaced\\ key : first\\\n  #part-of-value\n"
            + "escaped=\\u4e2d\\u6587\\tvalue\nplain value\n";
        write(file, source);
        TermuxPropertiesFile store = new TermuxPropertiesFile(file);

        assertEquals("first#part-of-value", store.read().getProperty("spaced key"));
        assertEquals("中文\tvalue", store.read().getProperty("escaped"));
        assertEquals("value", store.read().getProperty("plain"));
        store.set("plain", "changed");
        assertEquals(source.replace("plain value", "plain = changed"), read(file));
    }

    @Test
    public void createsMissingDirectoryAndDoesNotCreateFileForAbsentDefault() throws Exception {
        File file = new File(temporary.getRoot(), ".termux/termux.properties");
        TermuxPropertiesFile store = new TermuxPropertiesFile(file);
        assertTrue(store.read().isEmpty());
        store.set("fullscreen", null);
        assertFalse(file.exists());

        store.set("fullscreen", "true");

        assertEquals("fullscreen = true\n", read(file));
        assertEquals("true", store.read().getProperty("fullscreen"));
    }

    @Test
    public void appendHandlesMissingNewlineAndTrailingContinuation() throws Exception {
        String[] originals = {"", "\\", "\\\n", "\\\r\n", "\\\n\n", "\\\n  ",
            "\\\n  \nexisting=yes\n", "unknown=keep", "unknown=keep\\", "unknown=keep\\\n",
            "unknown=keep\\\r\n", "# comment\\", "unknown=keep\r", "unknown=keep\\\n\\\n"};
        for (int i = 0; i < originals.length; i++) {
            File file = temporary.newFile("variant-" + i);
            write(file, originals[i]);
            TermuxPropertiesFile store = new TermuxPropertiesFile(file);
            Properties expected = store.read();
            expected.setProperty("fullscreen", "true");

            store.set("fullscreen", "true");

            assertTrue(read(file).startsWith(originals[i]));
            assertEquals("Preserve original properties for variant " + i, expected, store.read());
        }
    }

    @Test
    public void editingThroughSymbolicLinkKeepsLinkAndUpdatesTarget() throws Exception {
        File target = temporary.newFile("actual.properties");
        write(target, "fullscreen=false\nextra-keys=[['ESC','CTRL']]\n");
        File link = new File(temporary.getRoot(), "termux.properties");
        Files.createSymbolicLink(link.toPath(), target.toPath().toAbsolutePath());

        new TermuxPropertiesFile(link).set("fullscreen", "true");

        assertTrue(Files.isSymbolicLink(link.toPath()));
        assertEquals("fullscreen = true\nextra-keys=[['ESC','CTRL']]\n", read(target));
    }

    @Test
    public void reloadsExternalEditsBeforeEachSave() throws Exception {
        File file = temporary.newFile("termux.properties");
        write(file, "fullscreen=false\nunknown=before\n");
        TermuxPropertiesFile store = new TermuxPropertiesFile(file);
        store.read();
        write(file, "fullscreen=false\nunknown=edited elsewhere\nnew-key=preserved\n");

        store.set("fullscreen", "true");

        assertEquals("edited elsewhere", store.read().getProperty("unknown"));
        assertEquals("preserved", store.read().getProperty("new-key"));
    }

    @Test
    public void malformedPropertiesFailWithoutTouchingOriginal() throws Exception {
        File file = temporary.newFile("termux.properties");
        String invalid = "fullscreen=false\nunknown=\\uNOPE\n";
        write(file, invalid);

        try {
            new TermuxPropertiesFile(file).set("fullscreen", "true");
            fail("Invalid escape must be reported");
        } catch (IOException expected) {
            assertEquals(invalid, read(file));
        }
        assertEquals(1, temporary.getRoot().list().length);
    }

    @Test
    public void invalidUtf8FailsWithoutReplacingUnknownBytes() throws Exception {
        File file = temporary.newFile("termux.properties");
        byte[] invalid = {'#', (byte) 0xff, '\n'};
        Files.write(file.toPath(), invalid);

        try {
            new TermuxPropertiesFile(file).set("fullscreen", "true");
            fail("Invalid UTF-8 must be reported");
        } catch (IOException expected) {
            assertArrayEquals(invalid, Files.readAllBytes(file.toPath()));
        }
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
