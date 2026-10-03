package com.termux.app.fragments.settings.termux;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.Keep;
import com.termux.app.fragments.settings.MaterialPreferenceFragment;
import com.termux.app.fragments.settings.TermuxSettingsDataStore;
import androidx.preference.PreferenceManager;

import com.termux.R;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

@Keep
public class TerminalViewPreferencesFragment extends MaterialPreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        Context context = getContext();
        if (context == null) return;

        PreferenceManager preferenceManager = getPreferenceManager();
        preferenceManager.setPreferenceDataStore(new TermuxSettingsDataStore(context));

        setPreferencesFromResource(R.xml.termux_terminal_view_preferences, rootKey);
    }

}
