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
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.os.SystemClock;
import android.view.inputmethod.InputMethodManager;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.filepicker.TermuxDocumentsProvider;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;

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
    public void vibrationPreferenceUpdatesTerminalAndDrawerFeedbackWithoutRecreation() {
        TermuxActivity activity = drawerActivity(true);
        for (boolean enabled : new boolean[]{false, true}) {
            activity.getPreferences().setTerminalVibrationEnabled(enabled);
            ReflectionHelpers.callInstanceMethod(activity, "applyTerminalDisplayPreferences");
            assertEquals(enabled, activity.getTerminalView().isHapticFeedbackEnabled());
            assertEquals(enabled, activity.findViewById(R.id.new_session_button).isHapticFeedbackEnabled());
        }
    }

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
    public void cursorToggleIsIndependentOfCtrlAndStaysActiveAcrossKeyPresses() throws Exception {
        TermuxActivity activity = drawerActivity(true);
        ExtraKeysView keys = (ExtraKeysView) LayoutInflater.from(activity)
            .inflate(R.layout.view_terminal_toolbar_extra_keys, null);
        ExtraKeysInfo info = new ExtraKeysInfo("[['CTRL', 'CURSOR', 'ESC']]", "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        keys.reload(info, 52);
        activity.setExtraKeysView(keys);
        int[] keyPresses = {0};
        keys.setExtraKeysViewClient(new TerminalExtraKeys(activity.getTerminalView()) {
            @Override
            protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrl, boolean alt, boolean shift, boolean fn) {
                assertEquals("ESC", key);
                keyPresses[0]++;
            }
        });
        TermuxTerminalViewClient client = activity.getTermuxTerminalViewClient();
        MaterialButton ctrl = (MaterialButton) keys.getChildAt(0);
        MaterialButton cursor = (MaterialButton) keys.getChildAt(1);
        assertTrue(client.shouldUseHorizontalCursorGestures());
        assertFalse(client.shouldUseVerticalCursorGestures());
        ctrl.performClick();
        assertFalse(client.shouldUseVerticalCursorGestures());
        cursor.performClick();
        assertTrue(client.shouldUseVerticalCursorGestures());
        assertTrue(client.shouldUseVerticalCursorGestures());
        assertTrue(cursor.isChecked());
        assertTrue(ctrl.isChecked());
        assertEquals(0, keyPresses[0]);
        assertTrue(client.readControlKey());
        assertFalse(client.readControlKey());
        assertFalse(ctrl.isChecked());
        keys.getChildAt(2).performClick();
        assertEquals(1, keyPresses[0]);
        assertTrue(client.shouldUseVerticalCursorGestures());
        keys.reload(info, 52);
        cursor = (MaterialButton) keys.getChildAt(1);
        assertTrue(cursor.isChecked());
        cursor.performClick();
        assertFalse(client.shouldUseVerticalCursorGestures());
        assertFalse(cursor.isChecked());
        assertTrue(client.shouldUseHorizontalCursorGestures());
    }

    @Test
    public void openingDrawerHidesInputWithoutChangingToolbarPreference() {
        TermuxActivity activity = drawerActivity(true);
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        input.showSoftInput(activity.getTerminalView(), 0);
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());

        activity.getDrawer().openDrawer(Gravity.START, false);
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());

        Runnable delayedKeyboard = ReflectionHelpers.callInstanceMethod(
            activity.getTermuxTerminalViewClient(), "getShowSoftKeyboardRunnable");
        delayedKeyboard.run();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());

        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
    }

    @Test
    public void toolbarKeyboardButtonTogglesInputAfterCursorControl() {
        TermuxActivity activity = drawerActivity(true);
        int[] toggleRequests = {0};
        activity.mTermuxTerminalViewClient = new TermuxTerminalViewClient(activity, null) {
            @Override
            public void onToggleSoftKeyboardRequest() {
                assertFalse(activity.getDrawer().isDrawerVisible(Gravity.START));
                assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
                toggleRequests[0]++;
            }
        };
        activity.mTerminalView.setTerminalViewClient(activity.mTermuxTerminalViewClient);
        ReflectionHelpers.setField(activity, "mProperties", TermuxAppSharedProperties.init(activity));
        TermuxTerminalExtraKeys extraKeys = new TermuxTerminalExtraKeys(activity, activity.mTerminalView,
            activity.mTermuxTerminalViewClient, null);
        ExtraKeysView keys = activity.findViewById(R.id.terminal_toolbar_extra_keys);
        keys.setExtraKeysViewClient(extraKeys);
        keys.reload(extraKeys.getExtraKeysInfo(), 52);
        MaterialButton keyboard = (MaterialButton) keys.getChildAt(6);
        assertEquals("CURSOR", extraKeys.getExtraKeysInfo().getMatrix()[0][5].getKey());
        assertEquals("KEYBOARD", extraKeys.getExtraKeysInfo().getMatrix()[0][6].getKey());
        assertNotNull(keyboard.getIcon());
        assertEquals("", keyboard.getText().toString());
        assertEquals(activity.getString(com.termux.shared.R.string.extra_keys_keyboard_description),
            keyboard.getContentDescription());

        keyboard.performClick();
        assertEquals(1, toggleRequests[0]);
        keyboard.performClick();
        assertEquals(2, toggleRequests[0]);
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());

        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(2, toggleRequests[0]);
    }

    @Test
    public void closingDrawerRespectsDisabledToolbarAndChangesMadeWhileOpen() {
        TermuxActivity activity = drawerActivity(false);
        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());

        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.toggleTerminalToolbar();
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
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
        activity.getTerminalToolbar().setVisibility(showToolbar ? View.VISIBLE : View.GONE);
        ReflectionHelpers.callInstanceMethod(activity, "setAdaptiveDrawerLayout");
        measure(activity.getDrawer(), activity, 320, 640);
        return activity;
    }

    @Test
    public void drawerIconActionsFitNarrowAndCompactLayouts() {
        TermuxActivity activity = drawerActivity(true);
        MaterialButton newSession = activity.findViewById(R.id.new_session_button);
        ImageButton files = activity.findViewById(R.id.file_system_button);
        ImageButton settings = activity.findViewById(R.id.settings_button);
        for (int height : new int[]{640, 320, 640}) {
            measure(activity.getDrawer(), activity, 320, height);
            assertSame(settings.getParent(), files.getParent());
            assertEquals(settings.getTop(), files.getTop());
            assertTrue(files.getRight() <= settings.getLeft());
            assertEquals(newSession.getWidth(), newSession.getHeight());
            assertTrue(newSession.getWidth() >= Math.round(48 * activity.getResources().getDisplayMetrics().density));
        }
        assertEquals("", newSession.getText().toString());
        assertEquals(activity.getString(R.string.action_new_session), newSession.getContentDescription());
        assertEquals(activity.getString(R.string.action_open_file_system), files.getContentDescription());
        assertNotNull(newSession.getIcon());
        assertNotNull(files.getDrawable());
    }

    @Test
    public void toolbarScrollsOneRowWithHomeAndEndBeforePageKeysAndNoTextInput() throws Exception {
        Context context = themedContext();
        View root = LayoutInflater.from(context).inflate(R.layout.activity_termux, null);
        HorizontalScrollView toolbar = root.findViewById(R.id.terminal_toolbar);
        toolbar.setVisibility(View.VISIBLE);
        ExtraKeysView keys = toolbar.findViewById(R.id.terminal_toolbar_extra_keys);
        keys.reload(new ExtraKeysInfo(com.termux.shared.termux.settings.properties.TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 52);
        measure(toolbar, context, 304, 52);
        String[] labels = {"ESC", "CTRL", "ALT", "/", "TAB", "", "", "HOME", "END", "PGUP", "PGDN"};
        assertEquals(labels.length, keys.getChildCount());
        assertEquals(1, toolbar.getChildCount());
        assertEquals(1, keys.getRowCount());
        assertNoTextInput(toolbar);
        MaterialButton cursor = (MaterialButton) keys.getChildAt(5);
        assertNotNull(cursor.getIcon());
        assertEquals(context.getString(com.termux.shared.R.string.extra_keys_cursor_description),
            cursor.getContentDescription());
        float density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            MaterialButton key = (MaterialButton) keys.getChildAt(i);
            assertEquals(labels[i], key.getText().toString());
            assertEquals(keys.getChildAt(0).getTop(), key.getTop());
            assertTrue(key.getWidth() >= Math.round(48 * density));
            assertTrue(key.getHeight() > 0);
            assertTrue(key.getBottom() <= toolbar.getHeight());
            assertNotNull(key.getLayout());
            assertEquals(0, key.getLayout().getEllipsisCount(0));
            assertTrue(key.getLayout().getLineWidth(0) <= key.getWidth() - key.getCompoundPaddingLeft() - key.getCompoundPaddingRight());
        }
        assertTrue(toolbar.canScrollHorizontally(1));
        toolbar.scrollTo(keys.getWidth(), 0);
        assertTrue(toolbar.getScrollX() > 0);
        View lastKey = keys.getChildAt(keys.getChildCount() - 1);
        assertTrue(lastKey.getRight() - toolbar.getScrollX() <= toolbar.getWidth());
        assertFalse(toolbar.canScrollHorizontally(1));

        // A wider viewport fills the available space without adding another row.
        measure(toolbar, context, 600, 52);
        assertEquals(toolbar.getWidth(), keys.getWidth());
        assertFalse(toolbar.canScrollHorizontally(1));
        assertEquals(keys.getChildAt(0).getTop(), lastKey.getTop());
    }

    @Test
    public void draggingToolbarDoesNotToggleOrLockThePressedKey() throws Exception {
        Context context = themedContext();
        View root = LayoutInflater.from(context).inflate(R.layout.activity_termux, null);
        HorizontalScrollView toolbar = root.findViewById(R.id.terminal_toolbar);
        toolbar.setVisibility(View.VISIBLE);
        ExtraKeysView keys = toolbar.findViewById(R.id.terminal_toolbar_extra_keys);
        keys.reload(new ExtraKeysInfo(com.termux.shared.termux.settings.properties.TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 52);
        measure(toolbar, context, 304, 52);
        View ctrl = keys.getChildAt(1);
        float x = (ctrl.getLeft() + ctrl.getRight()) / 2f;
        float y = toolbar.getHeight() / 2f;
        float step = 20 * context.getResources().getDisplayMetrics().density;
        long time = SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(time, time + i * 30, actions[i], x - i * step, y, 0);
            toolbar.dispatchTouchEvent(event);
            event.recycle();
        }
        assertTrue(toolbar.getScrollX() > 0);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertEquals(Boolean.FALSE, keys.readSpecialButton(SpecialButton.CTRL, false));
        assertEquals(Boolean.FALSE, keys.readSpecialButton(SpecialButton.CURSOR, false));
        assertFalse(((MaterialButton) ctrl).isChecked());
        assertEquals(0, ((MaterialButton) ctrl).getStrokeWidth());
    }

    private static void assertNoTextInput(View view) {
        assertFalse(view instanceof EditText);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertNoTextInput(group.getChildAt(i));
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
