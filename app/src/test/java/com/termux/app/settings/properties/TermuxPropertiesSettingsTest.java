package com.termux.app.settings.properties;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class TermuxPropertiesSettingsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private File primary;
    private File secondary;
    private TermuxPropertiesSettings settings;
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        primary = new File(temporary.getRoot(), ".termux/termux.properties");
        secondary = new File(temporary.getRoot(), ".config/termux/termux.properties");
        settings = new TermuxPropertiesSettings(context, primary, secondary);
    }

    @Test public void openingMissingConfigurationOnlyReadsUpstreamDefaults() throws Exception {
        settings.reload();
        assertEquals(false, settings.get("allow-external-apps"));
        assertEquals(false, settings.get("fullscreen"));
        assertEquals(0, settings.get("terminal-cursor-blink-rate"));
        assertEquals("block", settings.get("terminal-cursor-style"));
        assertEquals(2000, settings.get("terminal-transcript-rows"));
        assertEquals("vibrate", settings.get("bell-character"));
        assertEquals(true, settings.get("extra-keys-text-all-caps"));
        assertEquals(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, settings.get("extra-keys"));
        assertEquals("", settings.get("shortcut.create-session"));
        assertFalse(primary.exists());
        assertFalse(secondary.exists());
    }

    @Test public void secondaryConfigurationRemainsTheSingleSourceUntilPrimaryExists() throws Exception {
        write(secondary, "# custom\nallow-external-apps = true\nfullscreen = false\n");
        settings.reload();
        assertEquals(true, settings.get("allow-external-apps"));
        settings.set("fullscreen", true);
        assertFalse(primary.exists());
        assertEquals("true", new TermuxPropertiesFile(secondary).read().getProperty("fullscreen"));
        write(primary, "fullscreen=false\n");
        settings.reload();
        assertEquals(false, settings.get("fullscreen"));
        assertEquals(primary, settings.getFile());
    }

    @Test public void savesPreserveExternalEditsAndToolbarAndUseRuntimePropertyNames() throws Exception {
        String toolbar = "extra-keys = [[ESC, CTRL, \\\n TAB]]\n";
        write(primary, "# user template\n" + toolbar + "allow-external-apps = true\n");
        settings.reload();
        new TermuxPropertiesFile(primary).set("plugin-option", "edited elsewhere");
        long revision = TermuxPropertiesSettings.getRevision();
        settings.set("terminal-cursor-style", "bar");
        settings.set("bell-character", "ignore");
        Properties saved = new TermuxPropertiesFile(primary).read();
        assertEquals("bar", saved.getProperty("terminal-cursor-style"));
        assertEquals("ignore", saved.getProperty("bell-character"));
        assertEquals("edited elsewhere", saved.getProperty("plugin-option"));
        assertTrue(read(primary).contains(toolbar));
        assertTrue(TermuxPropertiesSettings.getRevision() > revision);
        assertEquals("ignore", settings.get("bell-character"));
    }

    @Test public void ignoredPrimaryDirectoryDoesNotShadowUsableSecondaryFile() throws Exception {
        assertTrue(primary.mkdirs());
        write(secondary, "fullscreen=false\n");
        settings.reload();
        assertEquals(secondary, settings.getFile());
        settings.set("fullscreen", true);
        assertTrue(primary.isDirectory());
        assertEquals("true", new TermuxPropertiesFile(secondary).read().getProperty("fullscreen"));
    }

    @Test public void ignoredPrimarySymlinkDoesNotShadowOrModifyItsTarget() throws Exception {
        File linked = temporary.newFile("linked.properties");
        write(linked, "fullscreen=true\n");
        assertTrue(primary.getParentFile().mkdirs());
        Files.createSymbolicLink(primary.toPath(), linked.toPath());
        write(secondary, "fullscreen=false\n");
        settings.reload();
        assertEquals(secondary, settings.getFile());
        assertEquals(false, settings.get("fullscreen"));
        settings.set("terminal-cursor-style", "bar");
        assertEquals("fullscreen=true\n", read(linked));
        assertTrue(Files.isSymbolicLink(primary.toPath()));
        assertEquals("bar", new TermuxPropertiesFile(secondary).read().getProperty("terminal-cursor-style"));
    }

    @Test public void numericBoundsAndResetUseUpstreamDefaults() throws Exception {
        settings.set("terminal-cursor-blink-rate", "100");
        settings.set("terminal-transcript-rows", "50000");
        settings.set("terminal-margin-horizontal", "100");
        settings.set("terminal-toolbar-height", "0.4");
        assertEquals(100, settings.get("terminal-cursor-blink-rate"));
        assertEquals(50000, settings.get("terminal-transcript-rows"));
        assertEquals(0.4f, settings.get("terminal-toolbar-height"));
        settings.set("terminal-cursor-blink-rate", "0");
        settings.set("terminal-transcript-rows", " ");
        assertEquals(0, settings.get("terminal-cursor-blink-rate"));
        assertEquals(2000, settings.get("terminal-transcript-rows"));
        assertFalse(new TermuxPropertiesFile(primary).read().containsKey("terminal-transcript-rows"));
    }

    @Test public void invalidInputNeverChangesFileOrRevision() throws Exception {
        write(primary, "# preserve exactly\nfullscreen = true\n");
        settings.reload();
        String original = read(primary);
        String[][] invalid = {{"terminal-cursor-blink-rate", "99"}, {"terminal-cursor-blink-rate", "2001"},
            {"terminal-transcript-rows", "99"}, {"terminal-transcript-rows", "50001"},
            {"terminal-margin-vertical", "-1"}, {"terminal-margin-horizontal", "101"},
            {"terminal-toolbar-height", "NaN"}, {"terminal-toolbar-height", "Infinity"},
            {"terminal-toolbar-height", "0.3"}, {"terminal-cursor-blink-rate", "1.5"},
            {"shortcut.create-session", "ctrl + ab"}, {"shortcut.create-session", "alt + t"},
            {"shortcut.create-session", "ctrl +"}, {"default-working-directory", "relative"},
            {"default-working-directory", new File(temporary.getRoot(), "absent").getAbsolutePath()},
            {"bell-character", "silent"}, {"extra-keys-style", "invalid"}, {"fullscreen", "yes"},
            {"extra-keys", "[['ESC']"}, {"extra-keys", "['ESC']"}, {"extra-keys", "[[42]]"},
            {"extra-keys", "[[{'display':'missing key'}]]"}};
        long revision = TermuxPropertiesSettings.getRevision();
        for (String[] entry : invalid) {
            try {
                settings.set(entry[0], entry[1]);
                fail(entry[0] + " accepted " + entry[1]);
            } catch (IllegalArgumentException expected) { }
            assertEquals(original, read(primary));
            assertEquals(revision, TermuxPropertiesSettings.getRevision());
        }
    }

    @Test public void customToolbarRoundTripsRowsPopupsAndMacrosAndCanResetToDefault() throws Exception {
        write(primary, "# Keep the template\ncustom-option = untouched\n");
        String layout = "[\n['ESC', {'key':'TAB','popup':'HOME'}],\n"
            + "[{'macro':'CTRL c','display':'Stop'}, 'KEYBOARD']\n]";
        settings.set("extra-keys", layout);
        settings.reload();
        assertEquals(layout, settings.get("extra-keys"));
        assertEquals(layout, new TermuxPropertiesFile(primary).read().getProperty("extra-keys"));
        assertEquals("untouched", new TermuxPropertiesFile(primary).read().getProperty("custom-option"));
        assertTrue(read(primary).startsWith("# Keep the template\n"));
        ExtraKeysInfo keys = new ExtraKeysInfo((String) settings.get("extra-keys"), "default",
            ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        assertEquals(2, keys.getMatrix().length);
        assertEquals("HOME", keys.getMatrix()[0][1].getPopup().getKey());
        assertEquals("Stop", keys.getMatrix()[1][0].getDisplay());

        settings.set("extra-keys", " ");
        assertEquals(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS, settings.get("extra-keys"));
        assertFalse(new TermuxPropertiesFile(primary).read().containsKey("extra-keys"));
    }

    @Test public void directoryAndShortcutRoundTrip() throws Exception {
        File directory = temporary.newFolder("shell directory");
        settings.set("default-working-directory", directory.getAbsolutePath());
        settings.set("shortcut.next-session", "CTRL + N");
        settings.reload();
        assertEquals(directory.getAbsolutePath(), settings.get("default-working-directory"));
        assertEquals("ctrl + n", settings.get("shortcut.next-session"));
        settings.set("shortcut.next-session", "");
        assertEquals("", settings.get("shortcut.next-session"));
    }

    @Test public void deprecatedBlackUiUsesUpstreamThemePrecedenceWithoutRewritingFile() throws Exception {
        write(primary, "use-black-ui = true\n");
        settings.reload();
        assertEquals("true", settings.get("night-mode"));
        assertEquals("use-black-ui = true\n", read(primary));
        settings.set("night-mode", "system");
        assertEquals("system", settings.get("night-mode"));
        assertEquals("true", new TermuxPropertiesFile(primary).read().getProperty("use-black-ui"));
    }

    @Test public void existingFileSettingWinsDuringLegacyFullscreenMigration() throws Exception {
        SharedPreferences old = context.getSharedPreferences("migration", Context.MODE_PRIVATE);
        old.edit().putBoolean("terminal_fullscreen", true).commit();
        write(primary, "# Keep authoritative file\nfullscreen=false\n");
        settings.migrateLegacyFullscreen(old);
        assertEquals(false, settings.get("fullscreen"));
        assertEquals("# Keep authoritative file\nfullscreen=false\n", read(primary));
        assertFalse(old.contains("terminal_fullscreen"));
    }

    @Test public void legacyFullscreenMigratesOnceAndOnlyWhenExplicitlySet() throws Exception {
        SharedPreferences old = context.getSharedPreferences("migration", Context.MODE_PRIVATE);
        settings.migrateLegacyFullscreen(old);
        assertFalse(primary.exists());
        old.edit().putBoolean("terminal_fullscreen", true).commit();
        settings.migrateLegacyFullscreen(old);
        assertEquals("true", new TermuxPropertiesFile(primary).read().getProperty("fullscreen"));
        assertFalse(old.contains("terminal_fullscreen"));
        settings.set("fullscreen", false);
        settings.migrateLegacyFullscreen(old);
        assertEquals(false, settings.get("fullscreen"));
    }

    @Test public void failedMigrationRetainsLegacyPreferenceForRetry() throws Exception {
        assertTrue(primary.mkdirs());
        SharedPreferences old = context.getSharedPreferences("migration", Context.MODE_PRIVATE);
        old.edit().putBoolean("terminal_fullscreen", true).commit();
        try {
            settings.migrateLegacyFullscreen(old);
            fail("Directory should not be overwritten");
        } catch (IOException expected) { }
        assertTrue(old.getBoolean("terminal_fullscreen", false));
        assertTrue(primary.isDirectory());
    }

    private static void write(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + parent);
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }
}
