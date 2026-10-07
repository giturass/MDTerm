package com.termux.app;

import android.app.Application;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroupAdapter;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.termux.R;
import com.termux.app.activities.SettingsActivity;
import com.termux.app.fragments.settings.MaterialListPreferenceDialog;
import com.termux.app.fragments.settings.MaterialSwitchPreference;
import com.termux.app.fragments.settings.TermuxSettingsDataStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN")
public class MaterialSettingsTest {
    @Test
    public void homeShowsOrderedCategoriesAndBackRestoresItsTitle() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = controller.get();
            SettingsActivity.RootPreferencesFragment fragment = root(activity);
            assertNull(fragment.findPreference("about"));
            assertNull(fragment.findPreference("donate"));
            assertNull(fragment.findPreference("termux"));
            String[] categories = {"property_integration", "notifications", "appearance", "property_sessions",
                "input", "property_shortcuts", "property_toolbar", "property_compatibility", "diagnostics"};
            PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) fragment.getListView().getAdapter();
            assertEquals(categories.length, adapter.getItemCount());
            assertEquals(categories.length + 1, fragment.getPreferenceScreen().getPreferenceCount());
            for (int i = 0; i < categories.length; i++) {
                assertTrue(adapter.getItem(i) instanceof PreferenceScreen);
                assertEquals(categories[i], adapter.getItem(i).getKey());
            }
            Preference extensions = fragment.getPreferenceScreen().getPreference(categories.length);
            assertTrue(extensions instanceof PreferenceScreen);
            assertEquals("extensions", extensions.getKey());
            assertFalse(extensions.isVisible());
            assertEquals("property_toolbar", fragment.findPreference("terminal_vibration").getParent().getKey());
            assertEquals("property_toolbar", fragment.findPreference("soft-keyboard-toggle-behaviour").getParent().getKey());
            assertEquals("notifications", fragment.findPreference("bell-character").getParent().getKey());

            String homeTitle = title(activity);
            for (String category : categories) {
                String categoryTitle = fragment.findPreference(category).getTitle().toString();
                SettingsActivity.RootPreferencesFragment child = open(activity, category);
                assertEquals(category, child.getPreferenceScreen().getKey());
                assertEquals(category, child.requireArguments().getString(PreferenceFragmentCompat.ARG_PREFERENCE_ROOT));
                assertEquals(categoryTitle, title(activity));
                assertTrue(child.getPreferenceScreen().getPreferenceCount() > 0);
                activity.onBackPressed();
                fragment = root(activity);
                assertEquals(homeTitle, title(activity));
                assertEquals(categories.length, fragment.getListView().getAdapter().getItemCount());
            }
        }
    }

    @Test
    public void materialSwitchRowsPersistFromTheirCategoryScreens() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            String[][] groups = {
                {"property_toolbar", "terminal_vibration"},
                {"property_compatibility", "terminal_margin_adjustment"},
                {"input", "soft_keyboard_enabled", "soft_keyboard_enabled_only_if_no_hardware", "ime_composing_enabled"},
                {"diagnostics", "terminal_view_key_logging_enabled"},
                {"notifications", "plugin_error_notifications_enabled", "crash_report_notifications_enabled"}
            };
            SettingsActivity activity = controller.get();
            for (String[] group : groups) {
                SettingsActivity.RootPreferencesFragment fragment = open(activity, group[0]);
                for (int i = 1; i < group.length; i++) {
                    String key = group[i];
                    MaterialSwitchPreference preference = fragment.findPreference(key);
                    assertNotNull(key, preference);
                    assertSame(fragment.getPreferenceScreen(), preference.getParent());
                    PreferenceViewHolder holder = row(fragment, preference);
                    MaterialSwitch widget = (MaterialSwitch) holder.findViewById(androidx.preference.R.id.switchWidget);
                    assertNotNull(widget);
                    boolean oldValue = preference.isChecked();
                    assertEquals(oldValue, widget.isChecked());
                    assertTrue(holder.itemView.performClick());
                    PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) fragment.getListView().getAdapter();
                    adapter.onBindViewHolder(holder, adapter.getPreferenceAdapterPosition(preference));
                    assertEquals(!oldValue, widget.isChecked());
                    assertEquals(!oldValue, new TermuxSettingsDataStore(activity).getBoolean(key, oldValue));
                }
                activity.onBackPressed();
                root(activity);
            }

            controller.recreate();
            activity = controller.get();
            for (String[] group : groups) {
                SettingsActivity.RootPreferencesFragment fragment = open(activity, group[0]);
                for (int i = 1; i < group.length; i++) {
                    MaterialSwitchPreference restored = fragment.findPreference(group[i]);
                    assertEquals(new TermuxSettingsDataStore(activity).getBoolean(group[i], false), restored.isChecked());
                }
                activity.onBackPressed();
                root(activity);
            }
        }
    }

    @Test
    public void materialLogLevelDialogRespectsChangeListenerAndRestoresSelection() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity.RootPreferencesFragment fragment = open(controller.get(), "diagnostics");
            String diagnosticsTitle = title(controller.get());
            ListPreference preference = fragment.findPreference("log_level");
            String original = preference.getValue();
            int choice = (preference.findIndexOfValue(original) + 1) % preference.getEntryValues().length;
            preference.setOnPreferenceChangeListener((p, value) -> false);
            choose(fragment, preference, choice);
            assertEquals(original, preference.getValue());
            preference.setOnPreferenceChangeListener((p, value) -> true);
            choose(fragment, preference, choice);
            String selected = preference.getEntryValues()[choice].toString();
            assertEquals(selected, new TermuxSettingsDataStore(controller.get()).getString("log_level", null));
            controller.recreate();
            fragment = root(controller.get());
            assertEquals("diagnostics", fragment.getPreferenceScreen().getKey());
            assertEquals(diagnosticsTitle, title(controller.get()));
            ListPreference restored = fragment.findPreference("log_level");
            assertEquals(selected, restored.getValue());
            assertEquals(restored.getEntries()[choice], restored.getSummary());
            controller.get().onBackPressed();
            root(controller.get());
            assertEquals(controller.get().getString(R.string.mdterm_settings_title), title(controller.get()));
        }
    }

    private static SettingsActivity.RootPreferencesFragment root(SettingsActivity activity) {
        activity.getSupportFragmentManager().executePendingTransactions();
        return (SettingsActivity.RootPreferencesFragment) activity.getSupportFragmentManager().findFragmentById(R.id.settings);
    }

    private static SettingsActivity.RootPreferencesFragment open(SettingsActivity activity, String category) {
        SettingsActivity.RootPreferencesFragment fragment = root(activity);
        Preference preference = fragment.findPreference(category);
        assertNotNull(category, preference);
        assertTrue(row(fragment, preference).itemView.performClick());
        return root(activity);
    }

    private static PreferenceViewHolder row(SettingsActivity.RootPreferencesFragment fragment, Preference preference) {
        PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) fragment.getListView().getAdapter();
        int position = adapter.getPreferenceAdapterPosition(preference);
        assertTrue(preference.getKey(), position >= 0);
        PreferenceViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(fragment.requireContext()),
            adapter.getItemViewType(position));
        adapter.onBindViewHolder(holder, position);
        return holder;
    }

    private static String title(SettingsActivity activity) {
        return ((TextView) activity.findViewById(R.id.settings_title)).getText().toString();
    }

    private static void choose(SettingsActivity.RootPreferencesFragment fragment, Preference preference, int position) {
        fragment.onDisplayPreferenceDialog(preference);
        fragment.getChildFragmentManager().executePendingTransactions();
        MaterialListPreferenceDialog dialogFragment = (MaterialListPreferenceDialog) fragment.getChildFragmentManager()
            .findFragmentByTag("mdterm.preference.dialog");
        androidx.appcompat.app.AlertDialog dialog = (androidx.appcompat.app.AlertDialog) dialogFragment.requireDialog();
        dialog.getListView().performItemClick(null, position, position);
        fragment.getChildFragmentManager().executePendingTransactions();
    }
}
