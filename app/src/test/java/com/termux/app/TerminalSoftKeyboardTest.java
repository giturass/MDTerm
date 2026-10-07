package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Looper;
import android.view.inputmethod.InputMethodManager;

import com.termux.R;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.time.Duration;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "w320dp-h640dp")
public class TerminalSoftKeyboardTest {
    private ActivityController<TermuxActivity> controller;
    private TermuxActivity activity;
    private TermuxTerminalViewClient client;
    private InputMethodManager input;

    @Before
    public void attachTerminalWithoutStartingNativeSessions() {
        controller = Robolectric.buildActivity(TermuxActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity);
        assertNotNull(preferences);
        preferences.setSoftKeyboardEnabled(true);
        preferences.setSoftKeyboardEnabledOnlyIfNoHardware(false);
        ReflectionHelpers.setField(activity, "mPreferences", preferences);
        ReflectionHelpers.setField(activity, "mProperties", TermuxAppSharedProperties.init(activity));
        setStartupHidden(false);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        client = new TermuxTerminalViewClient(activity, null);
        activity.mTermuxTerminalViewClient = client;
        activity.mTerminalView.setTerminalViewClient(client);
        input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        controller.visible().windowFocusChanged(false);
    }

    @Test
    public void enteringTerminalWaitsForWindowFocusBeforeShowingKeyboard() {
        client.onResume();
        settle();
        assertTrue(activity.getTerminalView().hasFocus());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());

        controller.windowFocusChanged(true);
        settle();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
    }

    @Test
    public void recreatedTerminalShowsKeyboardEvenWhenViewAlreadyHasFocus() {
        ReflectionHelpers.setField(activity, "mIsActivityRecreated", true);
        activity.getTerminalView().requestFocus();
        controller.windowFocusChanged(true);
        client.onResume();
        settle();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
    }

    @Test
    public void returningToTerminalShowsKeyboardAgain() {
        controller.windowFocusChanged(true);
        client.onResume();
        settle();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());

        client.onHideSoftKeyboardRequest();
        controller.windowFocusChanged(false);
        client.onStop();
        client.onResume();
        settle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        controller.windowFocusChanged(true);
        settle();
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());
    }

    @Test
    public void explicitHideCancelsThePendingRequest() {
        client.onResume();
        client.onHideSoftKeyboardRequest();
        controller.windowFocusChanged(true);
        settle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
    }

    @Test
    public void stoppingCancelsThePendingRequest() {
        client.onResume();
        client.onStop();
        controller.windowFocusChanged(true);
        settle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
    }

    @Test
    public void disabledSoftKeyboardStaysHidden() {
        activity.getPreferences().setSoftKeyboardEnabled(false);
        assertStartupStaysHidden();
    }

    @Test
    public void hardwareKeyboardRestrictionStaysEffective() {
        activity.getPreferences().setSoftKeyboardEnabledOnlyIfNoHardware(true);
        Configuration configuration = activity.getResources().getConfiguration();
        configuration.keyboard = Configuration.KEYBOARD_QWERTY;
        configuration.hardKeyboardHidden = Configuration.HARDKEYBOARDHIDDEN_NO;
        assertStartupStaysHidden();
    }

    @Test
    public void explicitStartupHidingStaysEffective() {
        setStartupHidden(true);
        assertStartupStaysHidden();
    }

    @Test
    public void reloadingPropertiesDoesNotOpenKeyboard() {
        activity.getTerminalView().requestFocus();
        controller.windowFocusChanged(true);
        client.setSoftKeyboardState(false, true);
        settle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
    }

    private void assertStartupStaysHidden() {
        client.onResume();
        controller.windowFocusChanged(true);
        settle();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.getTerminalView().hasFocus());
    }

    private void setStartupHidden(boolean hidden) {
        Object shared = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        Map<String, Object> values = ReflectionHelpers.getField(shared, "mMap");
        String key = TermuxPropertyConstants.KEY_HIDE_SOFT_KEYBOARD_ON_STARTUP;
        values.put(key, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(activity,
            key, Boolean.toString(hidden)));
    }

    private static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
    }
}
