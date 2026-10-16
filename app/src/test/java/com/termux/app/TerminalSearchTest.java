package com.termux.app;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.PopupMenu;
import android.widget.Scroller;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.termux.R;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.test.ShadowGhosttyTerminal;
import com.termux.view.TerminalView;
import com.termux.view.textselection.TextSelectionCursorController;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** Java search UI/dispatch tests; the native Ghostty suite verifies matching and wraparound. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN-w320dp-h640dp-mdpi",
    shadows = TerminalSearchTest.SearchTerminalShadow.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalSearchTest {
    private TermuxActivity activity;
    private TerminalView view;
    private TermuxTerminalViewClient client;
    private SearchTerminalShadow terminal;
    private final List<GhosttyTerminal> terminals = new ArrayList<>();

    @Before
    public void setUp() {
        // Attach the real app UI without starting TermuxService, a PTY or a Vulkan surface.
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(activity);
        properties.loadTermuxPropertiesFromDisk();
        ReflectionHelpers.setField(activity, "mProperties", properties);
        view = activity.findViewById(R.id.terminal_view);
        activity.mTerminalView = view;
        view.setVisibility(View.GONE);
        view.setTextSize(20);
        client = new TermuxTerminalViewClient(activity, null);
        activity.mTermuxTerminalViewClient = client;
        view.setTerminalViewClient(client);
        view.attachSession(newSession());
        terminal = Shadow.extract(view.getTerminal());
        activity.getTerminalToolbar().setVisibility(View.VISIBLE);
        controller.visible();
    }

    @After
    public void tearDown() {
        client.dismissTerminalSearch();
        idle();
        view.attachSession(null);
        ((ViewGroup) view.getParent()).removeView(view);
        for (GhosttyTerminal item : terminals) item.close();
    }

    @Test
    public void terminalMenuOpensOneMaterialSearchBarAboveTheKeyboard() {
        Menu menu = new PopupMenu(activity, view).getMenu();
        ReflectionHelpers.callInstanceMethod(activity, "populateTerminalActions",
            ClassParameter.from(Menu.class, menu));
        MenuItem find = null;
        for (int i = 0; i < menu.size(); i++) {
            if (activity.getString(R.string.action_find_in_terminal).contentEquals(menu.getItem(i).getTitle())) {
                assertNull("Find must appear only once", find);
                find = menu.getItem(i);
            }
        }
        assertNotNull(find);
        assertTrue(find.isEnabled());
        assertNotNull(find.getIcon());
        assertTrue(activity.onContextItemSelected(find));
        AlertDialog dialog = dialog();
        assertTrue(input(dialog).getParent().getParent() instanceof TextInputLayout);
        assertTrue(dialog.findViewById(R.id.terminal_search_previous) instanceof MaterialButton);
        assertFalse(dialog.findViewById(R.id.terminal_search_previous).isEnabled());
        assertFalse(dialog.findViewById(R.id.terminal_search_next).isEnabled());
        assertEquals(Gravity.BOTTOM, dialog.getWindow().getAttributes().gravity);
        assertEquals(0, dialog.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        assertEquals(EditorInfo.IME_ACTION_SEARCH, input(dialog).getImeOptions() & EditorInfo.IME_MASK_ACTION);
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());

        client.showTerminalSearch();
        assertSame(dialog, dialog());
        assertEquals("", takeOutput());
    }

    @Test
    public void unicodeQueryWaitsForSubmitAndArrowsUseTheUpstreamDirections() {
        client.showTerminalSearch();
        AlertDialog dialog = dialog();
        input(dialog).setText("目录 😀");
        assertTrue(terminal.queries.isEmpty());
        terminal.result = new int[]{2, 7};
        input(dialog).onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertEquals(activity.getString(R.string.terminal_search_results_count, 2, 7), status(dialog));

        dialog.findViewById(R.id.terminal_search_previous).performClick();
        dialog.findViewById(R.id.terminal_search_next).performClick();
        assertEquals(Arrays.asList("目录 😀", "目录 😀", "目录 😀"), terminal.queries);
        assertEquals(Arrays.asList(true, true, false), terminal.older);
        assertEquals(0, terminal.getKeyEventCount());
        assertEquals("", takeOutput());
    }

    @Test
    public void hardwareEnterKeepsFocusAndSearchesOnceWithShiftAndNumpadSupport() {
        client.showTerminalSearch();
        TextInputEditText input = input(dialog());
        input.setText("needle");
        assertTrue(input.requestFocus());
        int[] keyCodes = {KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER};
        int[] modifiers = {0, KeyEvent.META_SHIFT_ON, 0, KeyEvent.META_SHIFT_ON};
        for (int i = 0; i < keyCodes.length; i++) {
            long now = SystemClock.uptimeMillis();
            assertTrue(input.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                keyCodes[i], 0, modifiers[i])));
            assertTrue(input.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                keyCodes[i], 0, modifiers[i])));
            assertEquals(i + 1, terminal.queries.size());
            assertTrue("Enter must keep focus in the query field", input.hasFocus());
            assertEquals("needle", input.getText().toString());
        }
        assertEquals(Arrays.asList(true, false, true, false), terminal.older);
        input.onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertEquals(Arrays.asList(true, false, true, false, true), terminal.older);
        assertEquals(0, terminal.getKeyEventCount());
        assertEquals("", takeOutput());
    }

    @Test
    public void noMatchesAndQueryChangesClearResultsAndUpdateNavigation() {
        client.showTerminalSearch();
        AlertDialog dialog = dialog();
        input(dialog).setText("missing");
        terminal.result = new int[]{0, 0};
        input(dialog).onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertEquals(activity.getString(R.string.terminal_search_no_matches), status(dialog));
        assertFalse(dialog.findViewById(R.id.terminal_search_previous).isEnabled());
        assertFalse(dialog.findViewById(R.id.terminal_search_next).isEnabled());

        int clears = terminal.clears;
        input(dialog).setText("another query");
        assertEquals(clears + 1, terminal.clears);
        assertEquals(activity.getString(R.string.terminal_search_submit), status(dialog));
        assertTrue(dialog.findViewById(R.id.terminal_search_previous).isEnabled());
        assertTrue(dialog.findViewById(R.id.terminal_search_next).isEnabled());

        input(dialog).setText("");
        input(dialog).onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertEquals("", status(dialog));
        assertFalse(dialog.findViewById(R.id.terminal_search_previous).isEnabled());
        assertFalse(dialog.findViewById(R.id.terminal_search_next).isEnabled());
        assertEquals(Arrays.asList("missing"), terminal.queries);
    }

    @Test
    public void searchCancelsFlingAndSelectionAndPreservesTheViewportUntilDismissal() {
        appendHistory();
        view.setTopRow(-5);
        TextSelectionCursorController selection = ReflectionHelpers.callInstanceMethod(
            view, "getTextSelectionCursorController");
        ReflectionHelpers.setField(selection, "mIsSelectingText", true);
        Scroller scroller = ReflectionHelpers.getField(view, "mScroller");
        scroller.startScroll(0, 0, 0, 100, 1000);
        assertTrue(view.isSelectingText());
        assertFalse(scroller.isFinished());

        client.showTerminalSearch();
        assertFalse(view.isSelectingText());
        assertTrue(scroller.isFinished());
        view.onScreenUpdated();
        assertEquals(-5, view.getTopRow());
        int clears = terminal.clears;

        dialog().findViewById(R.id.terminal_search_close).performClick();
        idle();
        assertTrue(terminal.clears > clears);
        assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
        assertEquals(-5, view.getTopRow());
        view.onScreenUpdated();
        assertEquals(0, view.getTopRow());
        assertEquals("", takeOutput());
    }

    @Test
    public void dismissalPreservesExistingAutoscrollAndToolbarPreferences() {
        appendHistory();
        view.toggleAutoScrollDisabled();
        view.setTopRow(-5);
        activity.getTerminalToolbar().setVisibility(View.INVISIBLE);
        client.showTerminalSearch();
        dialog().dismiss();
        idle();
        assertEquals(View.INVISIBLE, activity.getTerminalToolbar().getVisibility());
        view.onScreenUpdated();
        assertEquals(-5, view.getTopRow());
    }

    @Test
    public void dismissalAfterTheLastSessionClosesKeepsTheToolbarHidden() {
        client.showTerminalSearch();
        view.attachSession(null);
        ReflectionHelpers.setField(activity, "mIsWaitingForSession", true);
        client.onSessionDetached();
        idle();
        assertFalse(dialog().isShowing());
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
    }

    @Test
    public void sessionReplacementAndActivityStopCloseSearchWithoutTouchingTheNewTerminal() {
        client.showTerminalSearch();
        AlertDialog oldDialog = dialog();
        input(oldDialog).setText("old session");
        TerminalSession replacement = newSession();
        view.attachSession(replacement);
        activity.updateSessionUi();
        idle();
        assertFalse(oldDialog.isShowing());
        SearchTerminalShadow newTerminal = Shadow.extract(replacement.getTerminal());
        assertEquals(0, newTerminal.clears);
        assertTrue(newTerminal.queries.isEmpty());

        client.showTerminalSearch();
        AlertDialog newDialog = dialog();
        client.onStop();
        idle();
        assertFalse(newDialog.isShowing());
        assertTrue(newTerminal.clears > 0);
    }

    @Test
    public void closedTerminalAndFailedSearchDoNotCrashOrSendInput() {
        client.showTerminalSearch();
        AlertDialog dialog = dialog();
        input(dialog).setText("needle");
        terminal.failNextSearch = true;
        input(dialog).onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertEquals(activity.getString(R.string.terminal_search_failed), status(dialog));
        input(dialog).onEditorAction(EditorInfo.IME_ACTION_SEARCH);
        assertEquals(activity.getString(R.string.terminal_search_results_count, 1, 1), status(dialog));

        view.getTerminal().close();
        dialog.findViewById(R.id.terminal_search_next).performClick();
        idle();
        assertFalse(dialog.isShowing());
        assertEquals(2, terminal.queries.size());
        assertEquals("", takeOutput());
    }

    @Test
    public void ctrlAltFHonorsTheHardwareShortcutSetting() {
        Object shared = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        Map<String, Object> values = ReflectionHelpers.getField(shared, "mMap");
        String setting = TermuxPropertyConstants.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS;
        Object original = values.put(setting, false);
        try {
            long now = SystemClock.uptimeMillis();
            KeyEvent press = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F,
                0, KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON);
            assertTrue(view.onKeyDown(KeyEvent.KEYCODE_F, press));
            assertTrue(dialog().isShowing());
            assertEquals("", takeOutput());
            client.dismissTerminalSearch();
            idle();

            values.put(setting, true);
            assertFalse(client.onKeyDown(KeyEvent.KEYCODE_F, press, view.getCurrentSession()));
            assertFalse(dialog().isShowing());
        } finally {
            if (original == null) values.remove(setting);
            else values.put(setting, original);
        }
    }

    @Test
    @Config(qualifiers = "zh-rCN-w320dp-h640dp-mdpi-night")
    public void compactNightThemeKeepsAllActionsReachable() {
        client.showTerminalSearch();
        AlertDialog dialog = dialog();
        View content = dialog.getWindow().getDecorView();
        content.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.AT_MOST));
        content.layout(0, 0, content.getMeasuredWidth(), content.getMeasuredHeight());
        for (int id : new int[]{R.id.terminal_search_previous, R.id.terminal_search_next,
            R.id.terminal_search_close}) {
            View button = dialog.findViewById(id);
            assertTrue(button.getWidth() >= 48);
            assertTrue(button.getHeight() >= 48);
            assertNotNull(button.getContentDescription());
            assertEquals(View.VISIBLE, button.getVisibility());
        }
        assertTrue(input(dialog).getWidth() > 100);
    }

    private TerminalSession newSession() {
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        GhosttyTerminal nativeTerminal = new GhosttyTerminal(session, 80, 24, 10, 20, 100);
        ReflectionHelpers.setField(session, "mTerminal", nativeTerminal);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        terminals.add(nativeTerminal);
        return session;
    }

    private void appendHistory() {
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < 60; i++) history.append("line\r\n");
        byte[] bytes = history.toString().getBytes(StandardCharsets.UTF_8);
        view.getTerminal().feed(bytes, 0, bytes.length);
        assertTrue(view.getScrollbackRows() > 5);
    }

    private String takeOutput() {
        Object queue = ReflectionHelpers.getField(view.getCurrentSession(), "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    private static AlertDialog dialog() {
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static TextInputEditText input(AlertDialog dialog) {
        return dialog.findViewById(R.id.terminal_search_input);
    }

    private static String status(AlertDialog dialog) {
        return ((TextView) dialog.findViewById(R.id.terminal_search_status)).getText().toString();
    }

    private static void idle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Record native requests and supply outcomes without duplicating Ghostty's matching engine. */
    @Implements(value = GhosttyTerminal.class, isInAndroidSdk = false)
    public static class SearchTerminalShadow extends ShadowGhosttyTerminal {
        final List<String> queries = new ArrayList<>();
        final List<Boolean> older = new ArrayList<>();
        int[] result = {1, 1};
        int clears;
        boolean failNextSearch;

        @Override
        @Implementation
        protected int[] search(String query, boolean next) {
            queries.add(query);
            older.add(next);
            if (failNextSearch) {
                failNextSearch = false;
                throw new IllegalStateException("Search unavailable");
            }
            return result;
        }

        @Override
        @Implementation
        protected void clearSearch() {
            clears++;
            super.clearSearch();
        }
    }
}
