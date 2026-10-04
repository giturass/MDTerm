package com.termux.app.fragments.settings;

import android.content.Context;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceDataStore;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

/** Adapter for the existing terminal preferences; no migration or duplicate storage. */
public final class TermuxSettingsDataStore extends PreferenceDataStore {
    private final Context context;
    private final TermuxAppSharedPreferences preferences;

    public TermuxSettingsDataStore(Context context) {
        this.context = context.getApplicationContext();
        preferences = TermuxAppSharedPreferences.build(this.context, true);
    }

    @Override
    public boolean getBoolean(String key, boolean defaultValue) {
        if (preferences == null || key == null) return defaultValue;
        switch (key) {
            case "terminal_fullscreen": return preferences.isTerminalFullscreenEnabled();
            case "terminal_vibration": return preferences.isTerminalVibrationEnabled();
            case "terminal_margin_adjustment": return preferences.isTerminalMarginAdjustmentEnabled();
            case "soft_keyboard_enabled": return preferences.isSoftKeyboardEnabled();
            case "soft_keyboard_enabled_only_if_no_hardware": return preferences.isSoftKeyboardEnabledOnlyIfNoHardware();
            case "ime_composing_enabled": return preferences.isImeComposingEnabled();
            case "terminal_view_key_logging_enabled": return preferences.isTerminalViewKeyLoggingEnabled();
            case "plugin_error_notifications_enabled": return preferences.arePluginErrorNotificationsEnabled(false);
            case "crash_report_notifications_enabled": return preferences.areCrashReportNotificationsEnabled(false);
            default: return defaultValue;
        }
    }

    @Override
    public void putBoolean(String key, boolean value) {
        if (preferences == null || key == null) return;
        switch (key) {
            case "terminal_fullscreen": preferences.setTerminalFullscreenEnabled(value); break;
            case "terminal_vibration": preferences.setTerminalVibrationEnabled(value); break;
            case "terminal_margin_adjustment": preferences.setTerminalMarginAdjustment(value); break;
            case "soft_keyboard_enabled": preferences.setSoftKeyboardEnabled(value); break;
            case "soft_keyboard_enabled_only_if_no_hardware": preferences.setSoftKeyboardEnabledOnlyIfNoHardware(value); break;
            case "ime_composing_enabled": preferences.setImeComposingEnabled(value); break;
            case "terminal_view_key_logging_enabled": preferences.setTerminalViewKeyLoggingEnabled(value); break;
            case "plugin_error_notifications_enabled": preferences.setPluginErrorNotificationsEnabled(value); break;
            case "crash_report_notifications_enabled": preferences.setCrashReportNotificationsEnabled(value); break;
            default: break;
        }
    }

    @Override
    @Nullable
    public String getString(String key, @Nullable String defaultValue) {
        return preferences != null && "log_level".equals(key) ? String.valueOf(preferences.getLogLevel()) : defaultValue;
    }

    @Override
    public void putString(String key, @Nullable String value) {
        if (preferences != null && "log_level".equals(key) && value != null)
            preferences.setLogLevel(context, Integer.parseInt(value));
    }
}
