package com.termux.app.settings.properties;

import android.app.Application;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.MaterialEditTextPreferenceDialog;
import com.termux.app.fragments.settings.MaterialListPreferenceDialog;
import com.termux.app.fragments.settings.MaterialSwitchPreference;
import com.termux.app.fragments.settings.TermuxPropertiesPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN")
public class TermuxPropertiesUiTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private static final String ORIGINAL = "# 用户配置，保留注释与空行\r\n"
        + "allow-external-apps = true\r\n\r\n"
        + "fullscreen = false\r\n"
        + "terminal-cursor-style = block\r\n"
        + "terminal-transcript-rows = 2000\r\n"
        + "# 当前工具栏键位\r\n"
        + "extra-keys = [['ESC','CTRL','ALT','/','TAB','CURSOR','KEYBOARD','HOME','END','PGUP','PGDN']]\r\n"
        + "# 未知属性同样保留\r\ncustom-option = keep me\r\n";

    private static final String[] PROPERTY_KEYS = {
        "allow-external-apps", "default-working-directory", "disable-terminal-session-change-toast",
        "hide-soft-keyboard-on-startup", "soft-keyboard-toggle-behaviour", "terminal-transcript-rows",
        "volume-keys", "fullscreen", "use-fullscreen-workaround", "terminal-cursor-blink-rate",
        "terminal-cursor-style", "extra-keys-style", "extra-keys-text-all-caps", "night-mode",
        "disable-hardware-keyboard-shortcuts", "shortcut.create-session", "shortcut.next-session",
        "shortcut.previous-session", "shortcut.rename-session", "bell-character", "back-key",
        "enforce-char-based-input", "ctrl-space-workaround", "terminal-margin-horizontal",
        "terminal-margin-vertical", "terminal-toolbar-height"
    };

    @Test
    public void openingAndReloadingPropertiesPreservesTheWholeFileAndExistingToolbar() throws Exception {
        try (Screen screen = new Screen(ORIGINAL)) {
            byte[] original = ORIGINAL.getBytes(StandardCharsets.UTF_8);
            assertArrayEquals(original, Files.readAllBytes(screen.primary.toPath()));
            assertFalse(screen.secondary.exists());
            screen.properties.reload();
            assertArrayEquals(original, Files.readAllBytes(screen.primary.toPath()));

            for (String key : PROPERTY_KEYS) {
                Preference preference = screen.fragment.findPreference(key);
                assertNotNull(key, preference);
                assertFalse(key, preference.isPersistent());
                assertTrue(key, preference.isEnabled());
                assertSame(key, screen.fragment.getPreferenceScreen(), preference.getParent().getParent());
            }
            assertEquals(26, PROPERTY_KEYS.length);
            assertNull(screen.fragment.findPreference("extra-keys"));
            assertNull(screen.fragment.findPreference("terminal_fullscreen"));
            assertNotNull(screen.fragment.findPreference("terminal_vibration"));
            assertNotNull(screen.fragment.findPreference("terminal_margin_adjustment"));
            assertNotNull(screen.fragment.findPreference("log_level"));
            assertTrue(new TermuxPropertiesFile(screen.primary).read().getProperty("extra-keys")
                .contains("'ESC','CTRL','ALT'"));
        }
    }

    @Test
    public void switchAndListWritePropertiesAndRestoreAfterActivityRecreation() throws Exception {
        try (Screen screen = new Screen(ORIGINAL)) {
            MaterialSwitchPreference fullscreen = screen.fragment.findPreference("fullscreen");
            assertFalse(fullscreen.isChecked());
            click(screen.fragment, fullscreen);
            assertTrue(fullscreen.isChecked());

            ListPreference cursor = screen.fragment.findPreference("terminal-cursor-style");
            choose(screen.fragment, cursor, cursor.findIndexOfValue("bar"));
            assertEquals("bar", cursor.getValue());
            assertEquals("true", new TermuxPropertiesFile(screen.primary).read().getProperty("fullscreen"));
            assertEquals("bar", new TermuxPropertiesFile(screen.primary).read().getProperty("terminal-cursor-style"));
            assertEquals("keep me", new TermuxPropertiesFile(screen.primary).read().getProperty("custom-option"));
            assertTrue(new TermuxPropertiesFile(screen.primary).read().getProperty("extra-keys")
                .contains("'ESC','CTRL','ALT'"));

            byte[] saved = Files.readAllBytes(screen.primary.toPath());
            screen.activity.recreate();
            screen.bindTemporarySettings();
            fullscreen = screen.fragment.findPreference("fullscreen");
            cursor = screen.fragment.findPreference("terminal-cursor-style");
            assertTrue(fullscreen.isChecked());
            assertEquals("bar", cursor.getValue());
            assertArrayEquals(saved, Files.readAllBytes(screen.primary.toPath()));
        }
    }

    @Test
    public void invalidNumberStaysInTheDialogWithoutSavingAndCanBeCorrected() throws Exception {
        try (Screen screen = new Screen(ORIGINAL)) {
            EditTextPreference transcript = screen.fragment.findPreference("terminal-transcript-rows");
            screen.fragment.onDisplayPreferenceDialog(transcript);
            screen.fragment.getChildFragmentManager().executePendingTransactions();
            MaterialEditTextPreferenceDialog dialogFragment = (MaterialEditTextPreferenceDialog)
                screen.fragment.getChildFragmentManager().findFragmentByTag("mdterm.preference.dialog");
            assertNotNull(dialogFragment);
            AlertDialog dialog = (AlertDialog) dialogFragment.requireDialog();
            EditText input = dialog.findViewById(android.R.id.edit);
            assertNotNull(input);

            input.setText("50001");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            screen.fragment.getChildFragmentManager().executePendingTransactions();
            assertTrue(dialog.isShowing());
            assertEquals("50001", input.getText().toString());
            assertEquals("2000", transcript.getText());
            assertArrayEquals(ORIGINAL.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(screen.primary.toPath()));
            ViewParent parent = input.getParent();
            while (parent != null && !(parent instanceof TextInputLayout)) parent = parent.getParent();
            assertTrue(parent instanceof TextInputLayout);
            assertNotNull(((TextInputLayout) parent).getError());
            assertFalse(((TextInputLayout) parent).getError().toString().isEmpty());

            input.setText("5000");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            screen.fragment.getChildFragmentManager().executePendingTransactions();
            assertFalse(dialog.isShowing());
            assertEquals("5000", transcript.getText());
            assertEquals("5000", new TermuxPropertiesFile(screen.primary).read().getProperty("terminal-transcript-rows"));
        }
    }

    @Test
    public void failedSaveRejectsTheChangeAndKeepsTheDisplayedValue() throws Exception {
        try (Screen screen = new Screen(ORIGINAL)) {
            MaterialSwitchPreference fullscreen = screen.fragment.findPreference("fullscreen");
            ListPreference cursor = screen.fragment.findPreference("terminal-cursor-style");
            long revision = TermuxPropertiesSettings.getRevision();
            File backup = new File(screen.primary.getParentFile(), "original.properties");
            Files.move(screen.primary.toPath(), backup.toPath());
            Files.createDirectory(screen.primary.toPath());

            assertFalse(fullscreen.callChangeListener(true));
            click(screen.fragment, fullscreen);
            assertFalse(fullscreen.isChecked());
            choose(screen.fragment, cursor, cursor.findIndexOfValue("bar"));
            assertEquals("block", cursor.getValue());
            assertEquals(revision, TermuxPropertiesSettings.getRevision());
            assertTrue(screen.primary.isDirectory());
            assertArrayEquals(ORIGINAL.getBytes(StandardCharsets.UTF_8), Files.readAllBytes(backup.toPath()));
        }
    }

    @Test
    public void unreadableConfigurationDisablesOnlyFileBackedControls() throws Exception {
        try (Screen screen = new Screen(ORIGINAL)) {
            Files.move(screen.primary.toPath(), new File(screen.primary.getParentFile(), "original.properties").toPath());
            Files.createDirectory(screen.primary.toPath());
            screen.properties.reload();
            for (String key : PROPERTY_KEYS) assertFalse(key, screen.fragment.findPreference(key).isEnabled());
            assertTrue(screen.fragment.findPreference("terminal_vibration").isEnabled());
            assertTrue(screen.fragment.findPreference("log_level").isEnabled());
        }
    }

    private static void click(SettingsActivity.RootPreferencesFragment fragment, Preference preference) {
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) fragment.getListView().getAdapter();
        int position = adapter.getPreferenceAdapterPosition(preference);
        assertTrue(position >= 0);
        PreferenceViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(fragment.requireContext()),
            adapter.getItemViewType(position));
        adapter.onBindViewHolder(holder, position);
        assertTrue(holder.itemView.performClick());
    }

    private static void choose(SettingsActivity.RootPreferencesFragment fragment, ListPreference preference, int position) {
        assertTrue(position >= 0);
        fragment.onDisplayPreferenceDialog(preference);
        fragment.getChildFragmentManager().executePendingTransactions();
        MaterialListPreferenceDialog dialogFragment = (MaterialListPreferenceDialog)
            fragment.getChildFragmentManager().findFragmentByTag("mdterm.preference.dialog");
        assertNotNull(dialogFragment);
        AlertDialog dialog = (AlertDialog) dialogFragment.requireDialog();
        dialog.getListView().performItemClick(null, position, position);
        fragment.getChildFragmentManager().executePendingTransactions();
    }

    /** Every write is directed at TemporaryFolder, including after activity recreation. */
    private final class Screen implements AutoCloseable {
        final File primary;
        final File secondary;
        final ActivityController<SettingsActivity> activity;
        SettingsActivity.RootPreferencesFragment fragment;
        TermuxPropertiesPreferences properties;

        Screen(String source) throws IOException {
            File directory = temporary.newFolder();
            primary = new File(directory, "termux.properties");
            secondary = new File(directory, "secondary.properties");
            Files.write(primary.toPath(), source.getBytes(StandardCharsets.UTF_8));
            activity = Robolectric.buildActivity(SettingsActivity.class).setup();
            bindTemporarySettings();
        }

        void bindTemporarySettings() {
            SettingsActivity current = activity.get();
            current.getSupportFragmentManager().executePendingTransactions();
            fragment = (SettingsActivity.RootPreferencesFragment) current.getSupportFragmentManager()
                .findFragmentById(R.id.settings);
            assertNotNull(fragment);
            properties = ReflectionHelpers.getField(fragment, "propertyPreferences");
            ReflectionHelpers.setField(properties, "settings", new TermuxPropertiesSettings(current, primary, secondary));
            properties.reload();
        }

        @Override public void close() {
            activity.close();
        }
    }
}
