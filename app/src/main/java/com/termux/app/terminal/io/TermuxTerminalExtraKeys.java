package com.termux.app.terminal.io;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.google.android.material.button.MaterialButton;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.view.TerminalView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class TermuxTerminalExtraKeys extends TerminalExtraKeys {

    private ExtraKeysInfo mExtraKeysInfo;
    private boolean mCursorGesturesEnabled;

    final TermuxActivity mActivity;
    final TermuxTerminalViewClient mTermuxTerminalViewClient;
    final TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    private static final String LOG_TAG = "TermuxTerminalExtraKeys";

    @Override
    public boolean performExtraKeyButtonHapticFeedback(View view, ExtraKeyButton buttonInfo, MaterialButton button) {
        // Returning true consumes feedback, including for keys inflated after onResume.
        return !mActivity.getPreferences().isTerminalVibrationEnabled();
    }

    public TermuxTerminalExtraKeys(TermuxActivity activity, @NonNull TerminalView terminalView,
                                   TermuxTerminalViewClient termuxTerminalViewClient,
                                   TermuxTerminalSessionActivityClient termuxTerminalSessionActivityClient) {
        super(terminalView);

        mActivity = activity;
        mTermuxTerminalViewClient = termuxTerminalViewClient;
        mTermuxTerminalSessionActivityClient = termuxTerminalSessionActivityClient;

        reload();
    }


    /**
     * Set the terminal extra keys and style.
     */
    public void reload() {
        mExtraKeysInfo = null;
        mCursorGesturesEnabled = mActivity.getPreferences().isCursorGesturesEnabled();

        try {
            String extrakeys = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
            String extraKeysStyle = (String) mActivity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);

            ExtraKeysConstants.ExtraKeyDisplayMap extraKeyDisplayMap = ExtraKeysInfo.getCharDisplayMapForStyle(extraKeysStyle);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(extraKeyDisplayMap) && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(extraKeysStyle)) {
                Logger.logError(TermuxSharedProperties.LOG_TAG, "The style \"" + extraKeysStyle + "\" for the key \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE + "\" is invalid. Using default style instead.");
                extraKeysStyle = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }

            mExtraKeysInfo = createExtraKeysInfo(extrakeys, extraKeysStyle);
        } catch (JSONException e) {
            Logger.showToast(mActivity, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: " + e.toString(), true);
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not load and set the \"" + TermuxPropertyConstants.KEY_EXTRA_KEYS + "\" property from the properties file: ", e);

            try {
                mExtraKeysInfo = createExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
                    TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE);
            } catch (JSONException e2) {
                Logger.showToast(mActivity, "Can't create default extra keys",true);
                Logger.logStackTraceWithMessage(LOG_TAG, "Could create default extra keys: ", e);
                mExtraKeysInfo = null;
            }
        }
    }

    /** Keep ordinary resumes from rebuilding the toolbar and interrupting active key presses. */
    public boolean reloadIfCursorGesturesChanged() {
        if (mCursorGesturesEnabled == mActivity.getPreferences().isCursorGesturesEnabled()) return false;
        reload();
        return true;
    }

    private ExtraKeysInfo createExtraKeysInfo(String layout, String style) throws JSONException {
        ExtraKeysInfo configured = new ExtraKeysInfo(layout, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        if (mCursorGesturesEnabled) return configured;

        // Adapt only the displayed layout. Retain the saved keys, macros, popups and style so
        // enabling gestures again restores the user's configuration without rewriting the file.
        Set<String> missingArrows = new LinkedHashSet<>(Arrays.asList("LEFT", "DOWN", "UP", "RIGHT"));
        for (ExtraKeyButton[] row : configured.getMatrix()) {
            for (ExtraKeyButton button : row) missingArrows.remove(button.getKey());
        }

        JSONArray rows = new JSONArray(layout);
        JSONArray adapted = new JSONArray();
        for (int row = 0; row < rows.length(); row++) {
            JSONArray keys = rows.getJSONArray(row);
            JSONArray adaptedKeys = new JSONArray();
            for (int col = 0; col < keys.length(); col++) {
                ExtraKeyButton button = configured.getMatrix()[row][col];
                Object key = keys.get(col);
                boolean cursorPopup = button.getPopup() != null
                    && SpecialButton.CURSOR.getKey().equals(button.getPopup().getKey());
                if (SpecialButton.CURSOR.getKey().equals(button.getKey())) {
                    Object popup = button.getPopup() != null && !cursorPopup
                        ? ((JSONObject) key).get(ExtraKeyButton.KEY_POPUP) : null;
                    for (String arrow : missingArrows) {
                        if (popup != null) {
                            adaptedKeys.put(new JSONObject().put(ExtraKeyButton.KEY_KEY_NAME, arrow)
                                .put(ExtraKeyButton.KEY_POPUP, popup));
                            popup = null;
                        } else {
                            adaptedKeys.put(arrow);
                        }
                    }
                    missingArrows.clear();
                    // If all arrows already exist, keep this control's custom alternate action.
                    if (popup != null) adaptedKeys.put(popup);
                } else {
                    if (cursorPopup) ((JSONObject) key).remove(ExtraKeyButton.KEY_POPUP);
                    adaptedKeys.put(key);
                }
            }
            if (adaptedKeys.length() > 0) adapted.put(adaptedKeys);
        }
        if (!missingArrows.isEmpty()) {
            if (adapted.length() == 0) adapted.put(new JSONArray());
            JSONArray lastRow = adapted.getJSONArray(adapted.length() - 1);
            for (String arrow : missingArrows) lastRow.put(arrow);
        }
        return new ExtraKeysInfo(adapted.toString(), style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
    }

    public ExtraKeysInfo getExtraKeysInfo() {
        return mExtraKeysInfo;
    }

    @SuppressLint("RtlHardcoded")
    @Override
    public void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrlDown, boolean altDown, boolean shiftDown, boolean fnDown) {
        if ("KEYBOARD".equals(key)) {
            if(mTermuxTerminalViewClient != null)
                mTermuxTerminalViewClient.onToggleSoftKeyboardRequest();
        } else if ("DRAWER".equals(key)) {
            DrawerLayout drawerLayout = mTermuxTerminalViewClient.getActivity().getDrawer();
            if (drawerLayout.isDrawerOpen(Gravity.LEFT))
                drawerLayout.closeDrawer(Gravity.LEFT);
            else
                drawerLayout.openDrawer(Gravity.LEFT);
        } else if ("PASTE".equals(key)) {
            if(mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onPasteTextFromClipboard(null);
        }  else if ("SCROLL".equals(key)) {
            TerminalView terminalView = mTermuxTerminalViewClient.getActivity().getTerminalView();
            if (terminalView != null && terminalView.mEmulator != null)
                terminalView.mEmulator.toggleAutoScrollDisabled();
        } else {
            super.onTerminalExtraKeyButtonClick(view, key, ctrlDown, altDown, shiftDown, fnDown);
        }
    }

}
