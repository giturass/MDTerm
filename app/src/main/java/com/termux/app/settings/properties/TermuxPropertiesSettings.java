package com.termux.app.settings.properties;

import android.content.Context;
import android.content.SharedPreferences;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;

import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static com.termux.shared.termux.settings.properties.TermuxPropertyConstants.*;

/** App-only editor; runtime values and defaults continue to use the upstream properties parser. */
public final class TermuxPropertiesSettings {
    private static final AtomicLong REVISION = new AtomicLong();
    private static final String LEGACY_FULLSCREEN = "terminal_fullscreen";
    private static final Set<String> EDITABLE = new HashSet<>(Arrays.asList(
        TermuxConstants.PROP_ALLOW_EXTERNAL_APPS, KEY_DEFAULT_WORKING_DIRECTORY,
        KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP,
        KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR, KEY_TERMINAL_TRANSCRIPT_ROWS,
        KEY_VOLUME_KEYS_BEHAVIOUR, KEY_USE_FULLSCREEN, KEY_USE_FULLSCREEN_WORKAROUND,
        KEY_TERMINAL_CURSOR_BLINK_RATE, KEY_TERMINAL_CURSOR_STYLE, KEY_EXTRA_KEYS, KEY_EXTRA_KEYS_STYLE,
        KEY_EXTRA_KEYS_TEXT_ALL_CAPS, KEY_NIGHT_MODE, KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS,
        KEY_SHORTCUT_CREATE_SESSION, KEY_SHORTCUT_NEXT_SESSION, KEY_SHORTCUT_PREVIOUS_SESSION,
        KEY_SHORTCUT_RENAME_SESSION, KEY_BELL_BEHAVIOUR, KEY_BACK_KEY_BEHAVIOUR,
        KEY_ENFORCE_CHAR_BASED_INPUT, KEY_USE_CTRL_SPACE_WORKAROUND,
        KEY_TERMINAL_MARGIN_HORIZONTAL, KEY_TERMINAL_MARGIN_VERTICAL,
        KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR));
    private final Context context;
    private final File primary;
    private final File secondary;
    private Properties values = new Properties();

    public TermuxPropertiesSettings(Context context) {
        this(context, TermuxConstants.TERMUX_PROPERTIES_PRIMARY_FILE,
            TermuxConstants.TERMUX_PROPERTIES_SECONDARY_FILE);
    }

    // Explicit paths keep tests isolated from the installed app's real configuration.
    TermuxPropertiesSettings(Context context, File primary, File secondary) {
        this.context = context.getApplicationContext();
        this.primary = primary;
        this.secondary = secondary;
    }

    public static long getRevision() {
        return REVISION.get();
    }

    public File getFile() {
        // Same selection as SharedProperties.getPropertiesFileFromList: readable regular files,
        // primary first, without following a symlink at the file itself.
        if (isReadablePropertiesFile(primary)) return primary;
        if (isReadablePropertiesFile(secondary)) return secondary;
        return primary;
    }

    private static boolean isReadablePropertiesFile(File file) {
        return Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.canRead();
    }

    private File editableFile() throws IOException {
        File file = getFile();
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS) && !isReadablePropertiesFile(file))
            throw new IOException("Not a readable regular properties file: " + file);
        return file;
    }

    public void reload() throws IOException {
        values = new TermuxPropertiesFile(editableFile()).read();
    }

    public Object get(String key) {
        checkKey(key);
        Properties effective = new Properties();
        effective.putAll(values);
        TermuxSharedProperties.replaceUseBlackUIProperty(effective);
        String value = effective.getProperty(key);
        Object parsed = TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(context, key, value);
        if (KEY_BELL_BEHAVIOUR.equals(key)) return MAP_BELL_BEHAVIOUR.inverse().get(parsed);
        if (KEY_TERMINAL_CURSOR_STYLE.equals(key)) return MAP_TERMINAL_CURSOR_STYLE.inverse().get(parsed);
        if (MAP_SESSION_SHORTCUTS.containsKey(key)) return parsed == null ? "" : value;
        if (KEY_EXTRA_KEYS_STYLE.equals(key) && !isExtraKeysStyle((String) parsed))
            return DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
        return parsed;
    }

    /** Empty text removes the explicit override, leaving comments and upstream defaults intact. */
    public void set(String key, Object value) throws IOException {
        checkKey(key);
        String text = value == null ? null : value.toString().trim();
        if (text != null && text.isEmpty()) text = null;
        if (text != null) text = validate(key, text);
        new TermuxPropertiesFile(editableFile()).set(key, text);
        REVISION.incrementAndGet();
        reload();
    }

    private String validate(String key, String value) {
        if (TERMUX_DEFAULT_FALSE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key)
            || TERMUX_DEFAULT_TRUE_BOOLEAN_BEHAVIOUR_PROPERTIES_LIST.contains(key)) {
            if (!"true".equals(value) && !"false".equals(value)) invalid();
            return value;
        }
        switch (key) {
            case KEY_TERMINAL_CURSOR_BLINK_RATE:
                return integer(value, IVALUE_TERMINAL_CURSOR_BLINK_RATE_MIN,
                    IVALUE_TERMINAL_CURSOR_BLINK_RATE_MAX, true);
            case KEY_TERMINAL_TRANSCRIPT_ROWS:
                return integer(value, IVALUE_TERMINAL_TRANSCRIPT_ROWS_MIN,
                    IVALUE_TERMINAL_TRANSCRIPT_ROWS_MAX, false);
            case KEY_TERMINAL_MARGIN_HORIZONTAL:
            case KEY_TERMINAL_MARGIN_VERTICAL:
                return integer(value, IVALUE_TERMINAL_MARGIN_HORIZONTAL_MIN,
                    IVALUE_TERMINAL_MARGIN_HORIZONTAL_MAX, false);
            case KEY_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR:
                try {
                    float number = Float.parseFloat(value);
                    if (Float.isNaN(number) || number < IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MIN
                        || number > IVALUE_TERMINAL_TOOLBAR_HEIGHT_SCALE_FACTOR_MAX) invalid();
                    return Float.toString(number);
                } catch (NumberFormatException e) {
                    invalid();
                }
                break;
            case KEY_DEFAULT_WORKING_DIRECTORY:
                File directory = new File(value);
                if (!directory.isAbsolute() || !directory.isDirectory() || !directory.canRead())
                    throw new IllegalArgumentException(context.getString(R.string.properties_error_directory));
                return value;
            case KEY_EXTRA_KEYS:
                try {
                    new ExtraKeysInfo(value, DEFAULT_IVALUE_EXTRA_KEYS_STYLE, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
                } catch (JSONException error) {
                    throw new IllegalArgumentException(context.getString(R.string.mdterm_prop_toolbar_keys_invalid), error);
                }
                return value;
            case KEY_EXTRA_KEYS_STYLE:
                if (!isExtraKeysStyle(value)) invalid();
                return value;
            default:
                if (MAP_SESSION_SHORTCUTS.containsKey(key)) {
                    // Upstream accepts Ctrl plus a single character. Restrict to one BMP character
                    // because its shortcut parser does not handle supplementary code points.
                    String[] parts = value.toLowerCase(Locale.ROOT).split("\\+", -1);
                    String character = parts.length == 2 ? parts[1].trim() : "";
                    if (parts.length != 2 || !"ctrl".equals(parts[0].trim())
                        || character.length() != 1 || Character.isSurrogate(character.charAt(0))
                        || Character.isISOControl(character.charAt(0)))
                        throw new IllegalArgumentException(context.getString(R.string.properties_error_shortcut));
                    return "ctrl + " + character;
                }
                if (KEY_BELL_BEHAVIOUR.equals(key) && MAP_BELL_BEHAVIOUR.containsKey(value)
                    || KEY_TERMINAL_CURSOR_STYLE.equals(key) && MAP_TERMINAL_CURSOR_STYLE.containsKey(value)
                    || KEY_BACK_KEY_BEHAVIOUR.equals(key) && MAP_BACK_KEY_BEHAVIOUR.containsKey(value)
                    || KEY_NIGHT_MODE.equals(key) && MAP_NIGHT_MODE.containsKey(value)
                    || KEY_VOLUME_KEYS_BEHAVIOUR.equals(key) && MAP_VOLUME_KEYS_BEHAVIOUR.containsKey(value)
                    || KEY_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR.equals(key) && MAP_SOFT_KEYBOARD_TOGGLE_BEHAVIOUR.containsKey(value))
                    return value;
                invalid();
        }
        throw new IllegalArgumentException(context.getString(R.string.properties_error_value));
    }

    private String integer(String value, int min, int max, boolean allowZero) {
        try {
            int number = Integer.parseInt(value);
            if ((allowZero && number == 0) || number >= min && number <= max) return Integer.toString(number);
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(context.getString(allowZero
            ? R.string.properties_error_integer_or_zero : R.string.properties_error_integer, min, max));
    }

    private void invalid() {
        throw new IllegalArgumentException(context.getString(R.string.properties_error_value));
    }

    private static boolean isExtraKeysStyle(String value) {
        return Arrays.asList("default", "arrows-only", "arrows-all", "all", "none").contains(value);
    }

    private static void checkKey(String key) {
        if (!EDITABLE.contains(key)) throw new IllegalArgumentException("Unsupported setting: " + key);
    }

    public static void migrateLegacyFullscreen(Context context) throws IOException {
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(context, false);
        if (preferences != null)
            new TermuxPropertiesSettings(context).migrateLegacyFullscreen(preferences.getSharedPreferences());
    }

    void migrateLegacyFullscreen(SharedPreferences preferences) throws IOException {
        if (!preferences.contains(LEGACY_FULLSCREEN)) return;
        reload();
        // An explicit file setting always wins over the obsolete UI preference.
        if (!values.containsKey(KEY_USE_FULLSCREEN))
            set(KEY_USE_FULLSCREEN, preferences.getBoolean(LEGACY_FULLSCREEN, false));
        preferences.edit().remove(LEGACY_FULLSCREEN).apply();
    }
}
