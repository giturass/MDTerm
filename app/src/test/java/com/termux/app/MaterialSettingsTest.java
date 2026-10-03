package com.termux.app;

import android.app.Application;
import android.widget.FrameLayout;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroupAdapter;
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
    public void flatMaterialSwitchRowsPersistExistingTerminalPreferences() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity activity = controller.get();
            SettingsActivity.RootPreferencesFragment fragment = root(activity);
            assertNull(fragment.findPreference("about"));
            assertNull(fragment.findPreference("donate"));
            assertNull(fragment.findPreference("termux"));
            String[] keys = {"terminal_margin_adjustment", "soft_keyboard_enabled",
                "soft_keyboard_enabled_only_if_no_hardware", "ime_composing_enabled",
                "terminal_view_key_logging_enabled", "plugin_error_notifications_enabled",
                "crash_report_notifications_enabled"};
            PreferenceGroupAdapter adapter = (PreferenceGroupAdapter) fragment.getListView().getAdapter();
            for (String key : keys) {
                MaterialSwitchPreference preference = fragment.findPreference(key);
                assertNotNull(key, preference);
                assertSame(fragment.getPreferenceScreen(), preference.getParent().getParent());
                int position = adapter.getPreferenceAdapterPosition(preference);
                PreferenceViewHolder holder = adapter.onCreateViewHolder(new FrameLayout(activity), adapter.getItemViewType(position));
                adapter.onBindViewHolder(holder, position);
                MaterialSwitch widget = (MaterialSwitch) holder.findViewById(androidx.preference.R.id.switchWidget);
                assertNotNull(widget);
                boolean oldValue = preference.isChecked();
                assertEquals(oldValue, widget.isChecked());
                assertTrue(holder.itemView.performClick());
                adapter.onBindViewHolder(holder, position);
                assertEquals(!oldValue, widget.isChecked());
                assertEquals(!oldValue, new TermuxSettingsDataStore(activity).getBoolean(key, oldValue));
            }
            controller.recreate();
            for (String key : keys) {
                MaterialSwitchPreference restored = root(controller.get()).findPreference(key);
                assertEquals(new TermuxSettingsDataStore(controller.get()).getBoolean(key, false), restored.isChecked());
            }
        }
    }

    @Test
    public void materialLogLevelDialogRespectsChangeListenerAndRestoresSelection() {
        try (ActivityController<SettingsActivity> controller = Robolectric.buildActivity(SettingsActivity.class).setup()) {
            SettingsActivity.RootPreferencesFragment fragment = root(controller.get());
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
            ListPreference restored = root(controller.get()).findPreference("log_level");
            assertEquals(selected, restored.getValue());
            assertEquals(restored.getEntries()[choice], restored.getSummary());
        }
    }

    private static SettingsActivity.RootPreferencesFragment root(SettingsActivity activity) {
        activity.getSupportFragmentManager().executePendingTransactions();
        return (SettingsActivity.RootPreferencesFragment) activity.getSupportFragmentManager().findFragmentById(R.id.settings);
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
