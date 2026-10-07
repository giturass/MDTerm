package com.termux.app.activities;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.termux.R;
import com.termux.app.fragments.settings.MaterialPreferenceFragment;
import com.termux.app.fragments.settings.TermuxSettingsDataStore;
import com.termux.app.fragments.settings.TermuxPropertiesPreferences;
import com.termux.app.fragments.settings.termux.DebuggingPreferencesFragment;
import com.termux.shared.termux.settings.preferences.TermuxAPIAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxFloatAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxTaskerAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxWidgetAppSharedPreferences;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.theme.NightMode;

public class SettingsActivity extends AppCompatActivity
    implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
        setContentView(R.layout.activity_settings);
        MaterialToolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationOnClickListener(view -> onBackPressed());
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                .replace(R.id.settings, new RootPreferencesFragment()).commit();
        }
    }

    public void showSettingsHeader(CharSequence title, CharSequence summary) {
        TextView titleView = findViewById(R.id.settings_title);
        titleView.setText(title == null ? getString(R.string.mdterm_settings_title) : title);
        TextView summaryView = findViewById(R.id.settings_summary);
        summaryView.setText(summary);
        summaryView.setVisibility(summary == null || summary.length() == 0 ? View.GONE : View.VISIBLE);
    }

    @Override
    public boolean onPreferenceStartFragment(@NonNull PreferenceFragmentCompat caller, @NonNull Preference preference) {
        Fragment fragment = getSupportFragmentManager().getFragmentFactory()
            .instantiate(getClassLoader(), preference.getFragment());
        fragment.setArguments(preference.getExtras());
        getSupportFragmentManager().beginTransaction()
            .replace(R.id.settings, fragment).addToBackStack(preference.getKey()).commit();
        return true;
    }

    public static class RootPreferencesFragment extends MaterialPreferenceFragment {
        private TermuxPropertiesPreferences propertyPreferences;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            Context context = requireContext();
            getPreferenceManager().setPreferenceDataStore(new TermuxSettingsDataStore(context));
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            ListPreference logLevel = findPreference("log_level");
            TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, true);
            if (preferences != null)
                DebuggingPreferencesFragment.setLogLevelListPreferenceData(logLevel, context, preferences.getLogLevel());

            // Preference visibility must be updated on the main thread.
            boolean extensionsVisible = setExtensionVisible("termux_api", TermuxAPIAppSharedPreferences.build(context, false) != null);
            extensionsVisible |= setExtensionVisible("termux_float", TermuxFloatAppSharedPreferences.build(context, false) != null);
            extensionsVisible |= setExtensionVisible("termux_tasker", TermuxTaskerAppSharedPreferences.build(context, false) != null);
            extensionsVisible |= setExtensionVisible("termux_widget", TermuxWidgetAppSharedPreferences.build(context, false) != null);
            Preference extensions = findPreference("extensions");
            if (extensions != null) extensions.setVisible(extensionsVisible);

            propertyPreferences = TermuxPropertiesPreferences.attach(this);
        }

        @Override
        public void onResume() {
            super.onResume();
            if (propertyPreferences != null) propertyPreferences.reload();
        }

        private boolean setExtensionVisible(String key, boolean visible) {
            Preference preference = findPreference(key);
            if (preference != null) preference.setVisible(visible);
            return visible;
        }
    }
}
