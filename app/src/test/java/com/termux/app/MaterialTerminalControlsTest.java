package com.termux.app;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.provider.DocumentsContract;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.InputMethodManager;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;
import com.termux.filepicker.TermuxDocumentsProvider;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN-w320dp-h640dp")
public class MaterialTerminalControlsTest {
    @Test
    public void fileButtonOpensSystemFileManagerAtMdtermRoot() {
        TermuxActivity activity = drawerActivity(true);
        Intent browse = new Intent(Intent.ACTION_VIEW).setDataAndType(
            TermuxDocumentsProvider.getRootUri(), DocumentsContract.Root.MIME_TYPE_ITEM);
        ResolveInfo manager = new ResolveInfo();
        manager.activityInfo = new ActivityInfo();
        manager.activityInfo.packageName = "com.android.documentsui";
        manager.activityInfo.name = "com.android.documentsui.files.FilesActivity";
        manager.activityInfo.applicationInfo = new ApplicationInfo();
        manager.activityInfo.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(browse, manager);
        ReflectionHelpers.callInstanceMethod(activity, "setFileSystemView");

        activity.findViewById(R.id.file_system_button).performClick();
        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(Intent.ACTION_VIEW, launched.getAction());
        assertEquals("com.android.documentsui", launched.getComponent().getPackageName());
        assertEquals(DocumentsContract.Root.MIME_TYPE_ITEM, launched.getType());
        assertEquals(TermuxConstants.TERMUX_PACKAGE_NAME + ".documents", launched.getData().getAuthority());
        assertEquals(TermuxConstants.TERMUX_FILES_DIR_PATH, DocumentsContract.getRootId(launched.getData()));
    }

    @Test
    public void ctrlSelectsVerticalCursorModeWithoutBeingConsumedByGestures() throws Exception {
        TermuxActivity activity = drawerActivity(true);
        ExtraKeysView keys = (ExtraKeysView) LayoutInflater.from(activity)
            .inflate(R.layout.view_terminal_toolbar_extra_keys, null);
        keys.reload(new ExtraKeysInfo("[['CTRL']]", "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 52);
        activity.setExtraKeysView(keys);
        TermuxTerminalViewClient client = activity.getTermuxTerminalViewClient();
        assertFalse(client.shouldUseVerticalCursorGestures());
        keys.getChildAt(0).performClick();
        assertTrue(client.shouldUseVerticalCursorGestures());
        assertTrue(client.shouldUseVerticalCursorGestures());
        assertTrue(((MaterialButton) keys.getChildAt(0)).isChecked());
        keys.getChildAt(0).performClick();
        assertFalse(client.shouldUseVerticalCursorGestures());
    }

    @Test
    public void openingDrawerHidesInputWithoutChangingToolbarPreference() {
        TermuxActivity activity = drawerActivity(true);
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        input.showSoftInput(activity.getTerminalView(), 0);
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());

        activity.getDrawer().openDrawer(Gravity.START, false);
        assertEquals(View.GONE, activity.getTerminalToolbarViewPager().getVisibility());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());

        Runnable delayedKeyboard = ReflectionHelpers.callInstanceMethod(
            activity.getTermuxTerminalViewClient(), "getShowSoftKeyboardRunnable");
        delayedKeyboard.run();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());

        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.VISIBLE, activity.getTerminalToolbarViewPager().getVisibility());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
    }

    @Test
    public void keyboardButtonTogglesInputOnlyAfterDrawerCloses() {
        TermuxActivity activity = drawerActivity(true);
        int[] toggleRequests = {0};
        activity.mTermuxTerminalViewClient = new TermuxTerminalViewClient(activity, null) {
            @Override
            public void onToggleSoftKeyboardRequest() {
                assertFalse(activity.getDrawer().isDrawerVisible(Gravity.START));
                assertEquals(View.VISIBLE, activity.getTerminalToolbarViewPager().getVisibility());
                toggleRequests[0]++;
            }
        };
        activity.mTerminalView.setTerminalViewClient(activity.mTermuxTerminalViewClient);

        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.findViewById(R.id.toggle_keyboard_button).performClick();
        assertEquals(0, toggleRequests[0]);
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(1, toggleRequests[0]);
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());

        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(1, toggleRequests[0]);
    }

    @Test
    public void closingDrawerRespectsDisabledToolbarAndChangesMadeWhileOpen() {
        TermuxActivity activity = drawerActivity(false);
        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.GONE, activity.getTerminalToolbarViewPager().getVisibility());

        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.toggleTerminalToolbar();
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());
        assertEquals(View.GONE, activity.getTerminalToolbarViewPager().getVisibility());
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.VISIBLE, activity.getTerminalToolbarViewPager().getVisibility());
    }

    private static TermuxActivity drawerActivity(boolean showToolbar) {
        // Attach the activity without starting terminal sessions or the native service.
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity);
        assertNotNull(preferences);
        preferences.setShowTerminalToolbar(showToolbar);
        ReflectionHelpers.setField(activity, "mPreferences", preferences);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        activity.mTermuxTerminalViewClient = new TermuxTerminalViewClient(activity, null);
        activity.mTerminalView.setTerminalViewClient(activity.mTermuxTerminalViewClient);
        activity.getTerminalToolbarViewPager().setVisibility(showToolbar ? View.VISIBLE : View.GONE);
        ReflectionHelpers.callInstanceMethod(activity, "setToggleKeyboardView");
        ReflectionHelpers.callInstanceMethod(activity, "setAdaptiveDrawerLayout");
        measure(activity.getDrawer(), activity, 320, 640);
        return activity;
    }

    @Test
    public void sessionAndFileActionsFitSideBySideOnNarrowScreen() {
        Context context = themedContext();
        View root = LayoutInflater.from(context).inflate(R.layout.activity_termux, null);
        measure(root, context, 320, 640);
        MaterialButton newSession = root.findViewById(R.id.new_session_button);
        MaterialButton files = root.findViewById(R.id.file_system_button);
        assertEquals(newSession.getTop(), files.getTop());
        assertTrue(files.getRight() <= newSession.getLeft());
        assertTrue(newSession.getWidth() > 0);
        assertNotNull(newSession.getIcon());
        assertNotNull(files.getIcon());

        View input = LayoutInflater.from(context).inflate(R.layout.view_terminal_toolbar_text_input, null);
        measure(input, context, 320, 52);
        TextInputLayout field = input.findViewById(R.id.terminal_toolbar_text_input_layout);
        assertNotNull(field.getEditText());
        assertTrue(field.getEditText().getHeight() > 0);
        assertTrue(field.getEditText().getBottom() <= field.getHeight());
    }

    @Test
    public void denseFunctionKeyRowShowsHomeWithoutTruncation() throws Exception {
        Context context = themedContext();
        ExtraKeysView keys = (ExtraKeysView) LayoutInflater.from(context)
            .inflate(R.layout.view_terminal_toolbar_extra_keys, null);
        keys.reload(new ExtraKeysInfo(com.termux.shared.termux.settings.properties.TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 52);
        measure(keys, context, 304, 52);
        String[] labels = {"ESC", "CTRL", "ALT", "HOME", "END", "PGUP", "PGDN"};
        assertEquals(labels.length, keys.getChildCount());
        for (int i = 0; i < labels.length; i++) {
            MaterialButton key = (MaterialButton) keys.getChildAt(i);
            assertEquals(labels[i], key.getText().toString());
            assertEquals(keys.getChildAt(0).getTop(), key.getTop());
            assertNotNull(key.getLayout());
            assertEquals(0, key.getLayout().getEllipsisCount(0));
            assertTrue(key.getLayout().getLineWidth(0) <= key.getWidth() - key.getCompoundPaddingLeft() - key.getCompoundPaddingRight());
        }
    }

    @Test
    public void modifierLatchAndLockKeepMaterialBackgroundAndCheckedState() throws Exception {
        Context context = themedContext();
        ExtraKeysView keys = (ExtraKeysView) LayoutInflater.from(context)
            .inflate(R.layout.view_terminal_toolbar_extra_keys, null);
        keys.reload(new ExtraKeysInfo("[['CTRL', 'ESC']]", "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 52);
        MaterialButton ctrl = (MaterialButton) keys.getChildAt(0);
        Drawable background = ctrl.getBackground();
        ctrl.performClick();
        assertTrue(ctrl.isChecked());
        assertEquals(Boolean.TRUE, keys.readSpecialButton(SpecialButton.CTRL, true));
        assertFalse(ctrl.isChecked());
        ctrl.performClick();
        keys.getSpecialButtons().get(SpecialButton.CTRL).setIsLocked(true);
        assertEquals(Boolean.TRUE, keys.readSpecialButton(SpecialButton.CTRL, true));
        assertTrue(ctrl.isChecked());
        assertTrue(ctrl.getStrokeWidth() > 0);
        ctrl.performClick();
        assertFalse(ctrl.isChecked());
        assertEquals(0, ctrl.getStrokeWidth());
        assertSame(background, ctrl.getBackground());
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_TermuxActivity_DayNight_NoActionBar);
    }

    private static void measure(View view, Context context, int width, int height) {
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.round(width * density), h = Math.round(height * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }
}
