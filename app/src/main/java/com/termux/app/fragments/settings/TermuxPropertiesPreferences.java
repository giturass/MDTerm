package com.termux.app.fragments.settings;

import android.content.Context;
import android.text.InputType;
import android.widget.Toast;

import androidx.annotation.StringRes;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.termux.R;
import com.termux.app.settings.properties.TermuxPropertiesSettings;
import com.termux.shared.termux.TermuxConstants;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Material controls backed by termux.properties, alongside Android-only app preferences. */
public final class TermuxPropertiesPreferences {

    private final MaterialPreferenceFragment fragment;
    private final Context context;
    private final TermuxPropertiesSettings settings;
    private final List<Preference> properties = new ArrayList<>();

    private TermuxPropertiesPreferences(MaterialPreferenceFragment fragment) {
        this.fragment = fragment;
        context = fragment.requireContext();
        settings = new TermuxPropertiesSettings(context);
    }

    public static TermuxPropertiesPreferences attach(MaterialPreferenceFragment fragment) {
        TermuxPropertiesPreferences controller = new TermuxPropertiesPreferences(fragment);
        controller.addPreferences();
        controller.reload();
        return controller;
    }

    /** Refreshes values after returning from an editor or another settings screen. */
    public void reload() {
        try {
            settings.reload();
            updateFileSummary();
            for (Preference preference : properties) {
                Object value = settings.get(preference.getKey());
                preference.setEnabled(true);
                if (preference instanceof MaterialSwitchPreference) {
                    ((MaterialSwitchPreference) preference).setChecked((Boolean) value);
                } else if (preference instanceof ListPreference) {
                    ((ListPreference) preference).setValue(String.valueOf(value));
                } else if (preference instanceof EditTextPreference) {
                    ((EditTextPreference) preference).setText(String.valueOf(value));
                }
            }
        } catch (IOException error) {
            for (Preference preference : properties) preference.setEnabled(false);
            Toast.makeText(context, context.getString(R.string.mdterm_prop_read_failed,
                error.getLocalizedMessage()), Toast.LENGTH_LONG).show();
        }
    }

    private void addPreferences() {
        PreferenceScreen screen = fragment.getPreferenceScreen();
        if (screen == null) throw new IllegalStateException("Create the preference screen before attaching properties");

        Preference information = new Preference(context);
        information.setKey("termux_properties_information");
        information.setOrder(-10);
        information.setTitle(R.string.mdterm_prop_file_title);
        information.setSelectable(false);
        screen.addPreference(information);
        updateFileSummary();

        PreferenceCategory appearance = category("appearance", R.string.mdterm_settings_appearance, 10);
        Preference fullscreen = fragment.findPreference("fullscreen");
        if (fullscreen == null) throw new IllegalStateException("Missing fullscreen preference");
        bind(fullscreen);
        list(appearance, "night-mode", R.string.mdterm_prop_night_mode, R.string.mdterm_prop_night_mode_summary,
            R.array.mdterm_prop_night_entries, "system", "true", "false");
        list(appearance, "terminal-cursor-style", R.string.mdterm_prop_cursor_style, 0,
            R.array.mdterm_prop_cursor_entries, "block", "bar", "underline");
        number(appearance, "terminal-cursor-blink-rate", R.string.mdterm_prop_cursor_blink,
            R.string.mdterm_prop_cursor_blink_summary, "0, 100–2000", false);
        number(appearance, "terminal-margin-horizontal", R.string.mdterm_prop_margin_horizontal,
            R.string.mdterm_prop_margin_summary, "0–100 dp", false);
        number(appearance, "terminal-margin-vertical", R.string.mdterm_prop_margin_vertical,
            R.string.mdterm_prop_margin_summary, "0–100 dp", false);

        PreferenceCategory input = category("input", R.string.mdterm_settings_input, 20);
        list(input, "bell-character", R.string.mdterm_prop_bell, R.string.mdterm_prop_bell_summary,
            R.array.mdterm_prop_bell_entries, "vibrate", "beep", "ignore");
        list(input, "back-key", R.string.mdterm_prop_back_key, 0,
            R.array.mdterm_prop_back_entries, "back", "escape");
        list(input, "volume-keys", R.string.mdterm_prop_volume_keys, 0,
            R.array.mdterm_prop_volume_entries, "virtual", "volume");
        list(input, "soft-keyboard-toggle-behaviour", R.string.mdterm_prop_keyboard_toggle, 0,
            R.array.mdterm_prop_keyboard_toggle_entries, "show/hide", "enable/disable");
        toggle(input, "hide-soft-keyboard-on-startup", R.string.mdterm_prop_hide_keyboard,
            R.string.mdterm_prop_hide_keyboard_summary);

        PreferenceCategory sessions = category("property_sessions", R.string.mdterm_prop_sessions, 30);
        edit(sessions, "default-working-directory", R.string.mdterm_prop_working_directory,
            R.string.mdterm_prop_working_directory_summary, context.getString(R.string.mdterm_prop_directory_hint),
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        number(sessions, "terminal-transcript-rows", R.string.mdterm_prop_transcript,
            R.string.mdterm_prop_transcript_summary, "100–50000", false);
        toggle(sessions, "disable-terminal-session-change-toast", R.string.mdterm_prop_disable_session_toast,
            R.string.mdterm_prop_disable_session_toast_summary);

        PreferenceCategory toolbar = category("property_toolbar", R.string.mdterm_prop_toolbar, 40);
        Preference toolbarInformation = new Preference(context);
        toolbarInformation.setTitle(R.string.mdterm_prop_toolbar_keys);
        toolbarInformation.setSummary(R.string.mdterm_prop_toolbar_keys_summary);
        toolbarInformation.setSelectable(false);
        toolbar.addPreference(toolbarInformation);
        list(toolbar, "extra-keys-style", R.string.mdterm_prop_extra_keys_style, 0,
            R.array.mdterm_prop_extra_keys_entries, "default", "arrows-only", "arrows-all", "all", "none");
        toggle(toolbar, "extra-keys-text-all-caps", R.string.mdterm_prop_extra_keys_caps,
            R.string.mdterm_prop_extra_keys_caps_summary);
        number(toolbar, "terminal-toolbar-height", R.string.mdterm_prop_toolbar_height,
            R.string.mdterm_prop_toolbar_height_summary, "0.4–3.0", true);

        PreferenceCategory shortcuts = category("property_shortcuts", R.string.mdterm_prop_shortcuts, 50);
        toggle(shortcuts, "disable-hardware-keyboard-shortcuts", R.string.mdterm_prop_disable_shortcuts,
            R.string.mdterm_prop_disable_shortcuts_summary);
        shortcut(shortcuts, "shortcut.create-session", R.string.mdterm_prop_shortcut_create, "ctrl + t");
        shortcut(shortcuts, "shortcut.next-session", R.string.mdterm_prop_shortcut_next, "ctrl + 2");
        shortcut(shortcuts, "shortcut.previous-session", R.string.mdterm_prop_shortcut_previous, "ctrl + 1");
        shortcut(shortcuts, "shortcut.rename-session", R.string.mdterm_prop_shortcut_rename, "ctrl + n");

        PreferenceCategory compatibility = category("property_compatibility", R.string.mdterm_prop_compatibility, 60);
        toggle(compatibility, "enforce-char-based-input", R.string.mdterm_prop_character_input,
            R.string.mdterm_prop_character_input_summary);
        toggle(compatibility, "ctrl-space-workaround", R.string.mdterm_prop_ctrl_space,
            R.string.mdterm_prop_ctrl_space_summary);
        toggle(compatibility, "use-fullscreen-workaround", R.string.mdterm_prop_fullscreen_workaround,
            R.string.mdterm_prop_fullscreen_workaround_summary);

        PreferenceCategory integration = category("property_integration", R.string.mdterm_prop_integration, 70);
        toggle(integration, "allow-external-apps", R.string.mdterm_prop_external_apps,
            R.string.mdterm_prop_external_apps_summary);
        category("notifications", R.string.mdterm_settings_notifications, 80);
        category("diagnostics", R.string.mdterm_settings_diagnostics, 90);
        category("extensions", R.string.mdterm_settings_extensions, 100);
    }

    private PreferenceCategory category(String key, @StringRes int title, int order) {
        PreferenceCategory category = fragment.findPreference(key);
        if (category == null) {
            category = new PreferenceCategory(context);
            category.setKey(key);
            category.setTitle(title);
            fragment.getPreferenceScreen().addPreference(category);
        }
        category.setOrder(order);
        return category;
    }

    private void updateFileSummary() {
        Preference information = fragment.findPreference("termux_properties_information");
        if (information == null) return;
        String path = settings.getFile().getAbsolutePath();
        if (path.startsWith(TermuxConstants.TERMUX_HOME_DIR_PATH + "/"))
            path = "~" + path.substring(TermuxConstants.TERMUX_HOME_DIR_PATH.length());
        information.setSummary(context.getString(R.string.mdterm_prop_file_summary, path));
    }

    private void toggle(PreferenceCategory category, String key, @StringRes int title, @StringRes int summary) {
        MaterialSwitchPreference preference = new MaterialSwitchPreference(context);
        preference.setKey(key);
        preference.setTitle(title);
        preference.setSummary(summary);
        bind(preference);
        category.addPreference(preference);
    }

    private void list(PreferenceCategory category, String key, @StringRes int title, @StringRes int help,
                      int entries, String... values) {
        ListPreference preference = new ListPreference(context);
        preference.setKey(key);
        preference.setTitle(title);
        preference.setDialogTitle(title);
        preference.setEntries(entries);
        preference.setEntryValues(values);
        preference.setSummaryProvider((Preference.SummaryProvider<ListPreference>) item ->
            summary(item.getEntry() == null ? "" : item.getEntry().toString(), help));
        bind(preference);
        category.addPreference(preference);
    }

    private void number(PreferenceCategory category, String key, @StringRes int title, @StringRes int help,
                        String hint, boolean decimal) {
        edit(category, key, title, help, hint, InputType.TYPE_CLASS_NUMBER |
            (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
    }

    private void shortcut(PreferenceCategory category, String key, @StringRes int title, String hint) {
        edit(category, key, title, R.string.mdterm_prop_shortcut_summary, hint,
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    }

    private void edit(PreferenceCategory category, String key, @StringRes int title, @StringRes int help,
                      String hint, int inputType) {
        EditTextPreference preference = new EditTextPreference(context);
        preference.setKey(key);
        preference.setTitle(title);
        preference.setDialogTitle(title);
        preference.setDialogMessage(context.getString(help) + "\n\n" + context.getString(R.string.mdterm_prop_empty_default));
        preference.getExtras().putInt(MaterialEditTextPreferenceDialog.INPUT_TYPE, inputType);
        preference.getExtras().putString(MaterialEditTextPreferenceDialog.INPUT_HINT, hint);
        preference.setSummaryProvider((Preference.SummaryProvider<EditTextPreference>) item -> {
            String value = String.valueOf(settings.get(key));
            return summary(value.isEmpty() ? context.getString(R.string.mdterm_prop_not_set) : value, help);
        });
        bind(preference);
        category.addPreference(preference);
    }

    private CharSequence summary(String value, @StringRes int help) {
        return help == 0 ? value : context.getString(R.string.mdterm_prop_value_with_help, value, context.getString(help));
    }

    private void bind(Preference preference) {
        preference.setPersistent(false);
        properties.add(preference);
        preference.setOnPreferenceChangeListener((item, value) -> {
            try {
                settings.set(item.getKey(), value);
                return true;
            } catch (IOException | IllegalArgumentException error) {
                String message = context.getString(error instanceof IOException
                    ? R.string.mdterm_prop_save_failed : R.string.mdterm_prop_validation_failed,
                    error.getLocalizedMessage());
                item.getExtras().putString(MaterialEditTextPreferenceDialog.VALIDATION_ERROR, message);
                Toast.makeText(context, message, Toast.LENGTH_LONG).show();
                return false;
            }
        });
    }
}
