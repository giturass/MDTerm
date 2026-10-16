package com.termux.app;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

import com.termux.shared.crash.TermuxCrashUtils;
import com.termux.shared.file.TermuxFileUtils;
import com.termux.shared.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.theme.TermuxThemeUtils;
import com.termux.shared.logger.Logger;


public class TermuxApplication extends Application {
    public void onCreate() {
        super.onCreate();

        DynamicColors.applyToActivitiesIfAvailable(this);

        // Set crash handler for the app
        TermuxCrashUtils.setCrashHandler(this);

        // Set log level for the app
        setLogLevel();

        // Settings and report activities can be opened before the terminal activity.
        TermuxThemeUtils.setAppNightMode(this);

        if (TermuxFileUtils.isTermuxFilesDirectoryAccessible(this, true, true) == null) {
            TermuxInstaller.setupDefaultMotd(this);
        }
    }

    private void setLogLevel() {
        // Load the log level from shared preferences and set it to the {@link Logger.CURRENT_LOG_LEVEL}
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(getApplicationContext());
        if (preferences == null) return;
        preferences.setLogLevel(null, preferences.getLogLevel());
        Logger.logDebug("Starting Application");
    }
}
