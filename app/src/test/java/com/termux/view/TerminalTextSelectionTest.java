package com.termux.view;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ListAdapter;
import android.widget.PopupMenu;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.appcompat.app.AlertDialog;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.test.ShadowGhosttyTerminal;

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
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "w400dp-h640dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalTextSelectionTest {
    private TermuxActivity activity;
    private TerminalView view;
    private SelectionHost host;
    private DrawerLayout drawer;
    private ClipboardManager clipboard;
    private RecordingClient client;

    @Before
    public void setUp() {
        // Attach the real app client and clipboard callbacks without starting its service or a PTY.
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(activity);
        properties.loadTermuxPropertiesFromDisk();
        ReflectionHelpers.setField(activity, "mProperties", properties);
        ReflectionHelpers.setField(activity, "mIsVisible", true);

        drawer = new DrawerLayout(activity);
        drawer.setId(R.id.drawer_layout);
        host = new SelectionHost(activity);
        drawer.addView(host, new DrawerLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        drawer.addView(new FrameLayout(activity), new DrawerLayout.LayoutParams(
            120, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START));
        view = new TerminalView(activity, null);
        view.setId(R.id.terminal_view);
        view.setFocusableInTouchMode(true);
        // The Ghostty shadow supplies deterministic terminal cell metrics without native rendering.
        view.setTextSize(20);
        host.addView(view, new FrameLayout.LayoutParams(400, 160));
        ReflectionHelpers.setField(activity, "mTerminalView", view);
        client = new RecordingClient(activity);
        view.setTerminalViewClient(client);
        ReflectionHelpers.setField(activity, "mTermuxTerminalViewClient", client);
        activity.setContentView(drawer);
        controller.visible();
        drawer.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(640, View.MeasureSpec.EXACTLY));
        drawer.layout(0, 0, 400, 640);

        TermuxTerminalSessionClientBase sessionClient = new TermuxTerminalSessionClientBase();
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100, sessionClient);
        GhosttyTerminal terminal = new GhosttyTerminal(session, 40, 8, 10, 20, 100);
        ReflectionHelpers.setField(session, "mTerminal", terminal);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        view.attachSession(session);
        session.updateTerminalSessionClient(new TermuxTerminalSessionActivityClient(activity));
        clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.clearPrimaryClip();
    }

    @After
    public void tearDown() {
        if (view != null) view.stopTextSelectionMode();
        if (host != null) host.removeAllViews();
    }

    @Test
    public void longPressSelectsDirectlyWithAppClientAndCopyImmediatelyUnlocksDrawer() {
        view.setTerminalViewClient(new TermuxTerminalViewClient(activity, null));
        append("alpha beta");
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, 75, 10);
        idle(ViewConfiguration.getLongPressTimeout() + ViewConfiguration.getTapTimeout() + 1);
        touch(down, MotionEvent.ACTION_UP, 75, 10);

        assertTrue(view.isSelectingText());
        assertEquals("beta", view.getSelectedText());
        assertNull("Long press should not open the terminal actions dialog", ShadowDialog.getLatestDialog());
        assertEquals(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawer.getDrawerLockMode(Gravity.START));
        activity.updateSessionUi();
        assertTrue(view.isSelectingText());
        assertEquals(DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawer.getDrawerLockMode(Gravity.START));
        RecordingActionMode mode = host.mode;
        mode.click(view.getTextSelectionCursorController().ACTION_COPY);

        assertEquals("beta", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertFalse(view.isSelectingText());
        assertTrue(mode.finished);
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawer.getDrawerLockMode(Gravity.START));
        assertEquals("", takeOutput());
    }

    @Test
    public void doubleTapSelectsWordWithoutTriggeringTheSingleTapAction() {
        append("alpha beta");
        doubleTap(75, 10);
        assertTrue(view.isSelectingText());
        assertEquals("beta", view.getSelectedText());
        assertEquals(0, client.singleTaps);
        assertNotNull(host.mode);
    }

    @Test
    public void singleTapStillCallsClientAndTapWhileSelectingOnlyDismissesSelection() {
        append("alpha beta");
        tap(15, 10);
        idle(ViewConfiguration.getDoubleTapTimeout() + 1);
        assertEquals(1, client.singleTaps);
        assertFalse(view.isSelectingText());

        select(75, 10);
        tap(15, 50);
        idle(ViewConfiguration.getDoubleTapTimeout() + 1);
        assertFalse(view.isSelectingText());
        assertEquals(1, client.singleTaps);
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawer.getDrawerLockMode(Gravity.START));
    }

    @Test
    public void doubleTapPreservesMouseInputAndMouseTrackingApplications() {
        append("alpha beta");
        MotionEvent event = event(MotionEvent.ACTION_DOWN, 75, 10);
        event.setSource(InputDevice.SOURCE_MOUSE);
        view.mGestureRecognizer.mListener.onDoubleTap(event);
        assertFalse(view.isSelectingText());
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        append("\u001b[?1000h");
        assertTrue(view.getTerminal().isMouseTrackingActive());
        view.mGestureRecognizer.mListener.onDoubleTap(event);
        assertFalse(view.isSelectingText());

        // Long press remains available to copy text from mouse-aware applications.
        view.mGestureRecognizer.mListener.onLongPress(event);
        event.recycle();
        assertEquals("beta", view.getSelectedText());
        assertEquals("", takeOutput());
    }

    @Test
    public void multiTouchCannotStartSelectionAndNextDoubleTapStillWorks() {
        append("alpha beta");
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, 75, 10);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = 75 + 60 * i;
            coordinates[i].y = 10 + 60 * i;
            coordinates[i].pressure = 1;
        }
        MotionEvent secondFinger = MotionEvent.obtain(down, down,
            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.onTouchEvent(secondFinger);
        secondFinger.recycle();
        MotionEvent candidate = event(MotionEvent.ACTION_DOWN, 75, 10);
        view.mGestureRecognizer.mListener.onLongPress(candidate);
        view.mGestureRecognizer.mListener.onDoubleTap(candidate);
        candidate.recycle();
        assertFalse(view.isSelectingText());
        touch(down, MotionEvent.ACTION_CANCEL, 75, 10);
        idle(ViewConfiguration.getDoubleTapTimeout() + 1);

        doubleTap(75, 10);
        assertEquals("beta", view.getSelectedText());
    }

    @Test
    public void selectingTextSuppressesCursorGesturesButAllowsHistoryScrolling() {
        appendHistory();
        select(15, 10);
        drag(100, 100, 180, 100);
        assertTrue(view.isSelectingText());
        assertEquals("", takeOutput());
        drag(100, 50, 100, 110);
        assertTrue(view.isSelectingText());
        assertTrue(view.getTopRow() < 0);
        assertEquals("", takeOutput());
    }

    @Test
    public void blankCellDoesNotSelectAdjacentWordsAndPasteTracksClipboardChanges() {
        append("alpha beta");
        select(55, 10);
        assertEquals("", view.getSelectedText());
        Menu menu = host.mode.getMenu();
        MenuItem copy = menu.findItem(view.getTextSelectionCursorController().ACTION_COPY);
        MenuItem paste = menu.findItem(view.getTextSelectionCursorController().ACTION_PASTE);
        assertFalse(copy.isEnabled());
        assertFalse(paste.isEnabled());

        clipboard.setPrimaryClip(ClipData.newPlainText("", "insert"));
        host.mode.invalidate();
        assertFalse(copy.isEnabled());
        assertTrue(paste.isEnabled());
        clipboard.clearPrimaryClip();
        host.mode.invalidate();
        assertFalse(paste.isEnabled());
    }

    @Test
    public void selectionClampsPointsOutsideTheTerminalGrid() {
        append("alpha\u001b[8;40HZ");
        select(-1000, -1000);
        assertEquals("alpha", view.getSelectedText());
        view.stopTextSelectionMode();
        select(10000, 10000);
        assertEquals("Z", view.getSelectedText());
    }

    @Test
    public void selectionUsesTheVisibleScrollbackRow() {
        appendHistory();
        int historyRows = view.getScrollbackRows();
        assertTrue(historyRows > 0);
        view.setTopRow(-historyRows);
        select(15, 10);
        assertEquals("row0", view.getSelectedText());
    }

    @Test
    public void selectingInsideWideCharacterCopiesUnicodeWordIntact() {
        append("say 你好😀e\u0301 now");
        select(55, 10);
        assertEquals("你好😀e\u0301", view.getSelectedText());
        host.mode.click(view.getTextSelectionCursorController().ACTION_COPY);
        assertEquals("你好😀e\u0301", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertFalse(view.isSelectingText());
    }

    @Test
    public void immediatePasteClosesSelectionAndUsesTheTerminalPasteProtocol() {
        append("alpha\u001b[?2004h");
        clipboard.setPrimaryClip(ClipData.newPlainText("", "echo 你好\n"));
        select(15, 10);
        RecordingActionMode mode = host.mode;
        mode.click(view.getTextSelectionCursorController().ACTION_PASTE);
        assertFalse(view.isSelectingText());
        assertTrue(mode.finished);
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawer.getDrawerLockMode(Gravity.START));
        assertEquals("\u001b[200~echo 你好\r\u001b[201~", takeOutput());
    }

    @Test
    public void dismissingFloatingToolbarClearsSelectionAndUnlocksDrawer() {
        append("alpha beta");
        select(15, 10);
        RecordingActionMode mode = host.mode;
        mode.finish();
        assertFalse(view.isSelectingText());
        assertNull(view.getSelectedText());
        assertNull(view.getTextSelectionCursorController().getActionMode());
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawer.getDrawerLockMode(Gravity.START));
        // Stale callbacks must not copy or paste after the floating toolbar has gone away.
        mode.callback.onActionItemClicked(mode,
            mode.getMenu().findItem(view.getTextSelectionCursorController().ACTION_COPY));
        assertFalse(clipboard.hasPrimaryClip());
    }

    @Test
    @Config(shadows = HyperlinkTerminal.class)
    public void moreActionPreservesSelectedTextAndExitsBeforeOpeningContextMenu() {
        append("alpha beta");
        String[] contextText = {null};
        view.setContextMenuAction(() -> {
            assertFalse(view.isSelectingText());
            assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawer.getDrawerLockMode(Gravity.START));
            contextText[0] = view.getStoredSelectedText();
            assertEquals(HyperlinkTerminal.LINK, view.getContextHyperlink());
        });
        select(75, 10);
        assertEquals(HyperlinkTerminal.LINK, view.getContextHyperlink());
        host.mode.click(view.getTextSelectionCursorController().ACTION_MORE);
        assertEquals("beta", contextText[0]);
        view.onContextMenuClosed(null);
        assertNull(view.getContextHyperlink());
        assertNull(view.getStoredSelectedText());
    }

    @Test
    @Config(shadows = HyperlinkTerminal.class)
    public void selectingTextFromTheActionsMenuKeepsTheLinkAfterQueuedDismissal() {
        append("alpha beta");
        view.setContextMenuAction(() -> assertTrue(activity.showTerminalActions(75, 10)));
        assertTrue(view.showContextMenu(75, 10));
        AlertDialog actions = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(actions);

        clickTerminalAction(actions, R.string.action_select_text);
        assertFalse(actions.isShowing());
        assertFalse(view.isSelectingText());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(view.isSelectingText());
        assertEquals("beta", view.getSelectedText());
        assertEquals(HyperlinkTerminal.LINK, view.getContextHyperlink());
        host.mode.click(view.getTextSelectionCursorController().ACTION_MORE);
        AlertDialog links = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(actions, links);
        assertTrue(links.isShowing());

        clickTerminalAction(links, R.string.action_copy_link);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(HyperlinkTerminal.LINK,
            clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertFalse(view.isSelectingText());
        assertFalse(links.isShowing());
        assertNull(view.getContextHyperlink());
        assertEquals("", takeOutput());
    }

    @Test
    @Config(shadows = HyperlinkTerminal.class)
    public void copyingOrCancellingSelectionAndChangingSessionClearTheLinkTarget() {
        append("alpha beta");
        select(75, 10);
        assertEquals(HyperlinkTerminal.LINK, view.getContextHyperlink());
        host.mode.click(view.getTextSelectionCursorController().ACTION_COPY);
        assertEquals("beta", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertNull(view.getContextHyperlink());

        select(75, 10);
        host.mode.finish();
        assertNull(view.getContextHyperlink());

        view.setContextMenuAction(() -> { });
        assertTrue(view.showContextMenu(75, 10));
        assertEquals(HyperlinkTerminal.LINK, view.getContextHyperlink());
        view.attachSession(null);
        assertNull(view.getContextHyperlink());
    }

    @Test
    @Config(shadows = HyperlinkTerminal.class)
    public void mouseContextMenuTargetsTheClickedCellAndClearsUnrelatedSelection() {
        append("alpha beta\u001b[?1000h");
        String[] contextLink = {null};
        view.setContextMenuAction(() -> {
            assertFalse(view.isSelectingText());
            contextLink[0] = view.getContextHyperlink();
        });
        select(15, 10);
        MotionEvent.PointerProperties pointer = new MotionEvent.PointerProperties();
        pointer.id = 0;
        pointer.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords coordinates = new MotionEvent.PointerCoords();
        coordinates.x = 75;
        coordinates.y = 10;
        long now = SystemClock.uptimeMillis();
        MotionEvent click = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 1,
            new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coordinates},
            0, MotionEvent.BUTTON_SECONDARY, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            assertTrue(view.onTouchEvent(click));
        } finally {
            click.recycle();
        }
        MotionEvent release = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_UP, 1,
            new MotionEvent.PointerProperties[]{pointer}, new MotionEvent.PointerCoords[]{coordinates},
            0, 0, 1, 1, 0, 0, InputDevice.SOURCE_MOUSE, 0);
        try {
            assertTrue(view.onTouchEvent(release));
        } finally {
            release.recycle();
        }
        assertEquals(HyperlinkTerminal.LINK, contextLink[0]);
        assertEquals("", takeOutput());

        assertTrue(view.showContextMenu(15, 10));
        assertNull(contextLink[0]);
        assertTrue(view.showContextMenu(-1, 10));
        assertNull(contextLink[0]);
    }

    @Test
    public void promptNavigationClearsSelectionAndUnlocksDrawerWithoutMouseInput() {
        append("alpha beta\u001b[?1000h");
        select(15, 10);
        RecordingActionMode mode = host.mode;
        view.mScrollRemainder = 7;

        view.scrollToPrompt(-1);

        assertFalse(view.isSelectingText());
        assertTrue(mode.finished);
        assertNull(view.getSelectedText());
        assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawer.getDrawerLockMode(Gravity.START));
        assertEquals(0, view.mScrollRemainder, 0);
        ShadowGhosttyTerminal terminal = Shadow.extract(view.getTerminal());
        assertEquals(Collections.singletonList(-1), terminal.getPromptScrollRequests());
        assertEquals("", takeOutput());
    }

    @Test
    @Config(qualifiers = "zh-rCN-w400dp-h640dp-mdpi")
    public void terminalMenuDispatchesBothPromptDirections() {
        Menu menu = new PopupMenu(activity, view).getMenu();
        ReflectionHelpers.callInstanceMethod(activity, "populateTerminalActions",
            ClassParameter.from(Menu.class, menu));
        int[] labels = {R.string.action_previous_prompt, R.string.action_next_prompt};
        for (int label : labels) {
            MenuItem action = null;
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (activity.getString(label).contentEquals(item.getTitle())) {
                    assertNull("A prompt action must appear once", action);
                    action = item;
                }
            }
            assertNotNull(action);
            assertTrue(action.isEnabled());
            assertNotNull(action.getIcon());
            assertTrue(activity.onContextItemSelected(action));
        }
        ShadowGhosttyTerminal terminal = Shadow.extract(view.getTerminal());
        assertEquals(Arrays.asList(-1, 1), terminal.getPromptScrollRequests());
        assertEquals("", takeOutput());
    }

    @Test
    public void promptShortcutsPreserveCompositionAndHonorDisabledShortcuts() {
        Object shared = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        Map<String, Object> values = ReflectionHelpers.getField(shared, "mMap");
        String setting = TermuxPropertyConstants.KEY_DISABLE_HARDWARE_KEYBOARD_SHORTCUTS;
        Object original = values.put(setting, false);
        int[] keyDownCalls = {0};
        view.setTerminalViewClient(new TermuxTerminalViewClient(activity, null) {
            @Override public boolean shouldEnableImeInput() { return true; }
            @Override public boolean onKeyDown(int keyCode, KeyEvent event, TerminalSession session) {
                keyDownCalls[0]++;
                return super.onKeyDown(keyCode, event, session);
            }
        });
        try {
            InputConnection input = view.onCreateInputConnection(new EditorInfo());
            assertTrue(input.setComposingText("你好", 1));
            for (int keyCode : new int[]{KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN}) {
                promptKey(keyCode);
                assertEquals("你好", input.getTextBeforeCursor(10, 0).toString());
                assertEquals("", takeOutput());
            }
            ShadowGhosttyTerminal terminal = Shadow.extract(view.getTerminal());
            assertEquals(Arrays.asList(-1, 1), terminal.getPromptScrollRequests());
            assertEquals(2, keyDownCalls[0]);
            assertEquals("Navigation must not forward presses or releases", 0, terminal.getKeyEventCount());

            values.put(setting, true);
            promptKey(KeyEvent.KEYCODE_PAGE_UP);
            assertEquals(Arrays.asList(-1, 1), terminal.getPromptScrollRequests());
            assertEquals(3, keyDownCalls[0]);
            String output = takeOutput();
            assertFalse(output.contains("你好"));
            assertFalse("A disabled shortcut must reach the terminal", output.isEmpty());
            assertEquals("你好", input.getTextBeforeCursor(10, 0).toString());
            assertTrue(input.finishComposingText());
            assertEquals("你好", takeOutput());
        } finally {
            if (original == null) values.remove(setting);
            else values.put(setting, original);
        }
    }

    private void promptKey(int keyCode) {
        long now = SystemClock.uptimeMillis();
        int modifiers = KeyEvent.META_CTRL_ON | KeyEvent.META_ALT_ON;
        assertTrue(view.onKeyDown(keyCode, new KeyEvent(now, now,
            KeyEvent.ACTION_DOWN, keyCode, 0, modifiers)));
        assertTrue(view.onKeyUp(keyCode, new KeyEvent(now, now,
            KeyEvent.ACTION_UP, keyCode, 0, modifiers)));
    }

    private void clickTerminalAction(AlertDialog dialog, int label) {
        ListAdapter adapter = dialog.getListView().getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            MenuItem item = (MenuItem) adapter.getItem(i);
            if (activity.getString(label).contentEquals(item.getTitle())) {
                assertTrue(item.isEnabled());
                dialog.getListView().performItemClick(
                    adapter.getView(i, null, dialog.getListView()), i, adapter.getItemId(i));
                return;
            }
        }
        fail("Terminal action was missing: " + activity.getString(label));
    }

    private void append(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        view.getTerminal().feed(bytes, 0, bytes.length);
    }

    private void appendHistory() {
        for (int i = 0; i < 12; i++) append("row" + i + "\r\n");
    }

    private void select(float x, float y) {
        MotionEvent event = event(MotionEvent.ACTION_DOWN, x, y);
        view.startTextSelectionMode(event);
        event.recycle();
        assertTrue(view.isSelectingText());
        assertNotNull(host.mode);
    }

    private void tap(float x, float y) {
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, x, y);
        idle(20);
        touch(down, MotionEvent.ACTION_UP, x, y);
    }

    private void doubleTap(float x, float y) {
        tap(x, y);
        idle(60);
        tap(x, y);
        idle(ViewConfiguration.getDoubleTapTimeout() + 1);
    }

    private void drag(float x1, float y1, float x2, float y2) {
        long down = SystemClock.uptimeMillis();
        touch(down, MotionEvent.ACTION_DOWN, x1, y1);
        idle(20);
        touch(down, MotionEvent.ACTION_MOVE, x2, y2);
        touch(down, MotionEvent.ACTION_CANCEL, x2, y2);
    }

    private void touch(long down, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        view.onTouchEvent(event);
        event.recycle();
    }

    private static MotionEvent event(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        return event;
    }

    private static void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }

    private String takeOutput() {
        Object queue = ReflectionHelpers.getField(view.mTermSession, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    private static final class RecordingClient extends TermuxTerminalViewClient {
        int singleTaps;

        RecordingClient(TermuxActivity activity) { super(activity, null); }
        @Override public void onSingleTapUp(MotionEvent event) { singleTaps++; }
        @Override public boolean shouldUseHorizontalCursorGestures() { return true; }
        @Override public boolean shouldUseVerticalCursorGestures() { return true; }
    }

    /** Inject a native OSC 8 lookup result without asking the legacy text fixture to parse it. */
    @Implements(value = GhosttyTerminal.class, isInAndroidSdk = false)
    public static class HyperlinkTerminal extends ShadowGhosttyTerminal {
        static final String LINK = "https://example.org/path?q=hello%20world#section";

        @Implementation
        @Override
        protected String getHyperlink(int column, int viewportRow) {
            return column == 7 && viewportRow == 0 ? LINK : null;
        }
    }

    private static final class SelectionHost extends FrameLayout {
        RecordingActionMode mode;

        SelectionHost(Context context) { super(context); }

        @Override
        public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback, int type) {
            mode = new RecordingActionMode(getContext(), originalView, callback);
            mode.setType(type);
            assertTrue(callback.onCreateActionMode(mode, mode.getMenu()));
            return mode;
        }
    }

    /** Keep Android's real menu/callback behavior while avoiding platform floating-toolbar rendering. */
    private static final class RecordingActionMode extends ActionMode {
        final Callback callback;
        private final Context context;
        private final Menu menu;
        private CharSequence title;
        private CharSequence subtitle;
        private View customView;
        boolean finished;

        RecordingActionMode(Context context, View anchor, Callback callback) {
            this.context = context;
            this.callback = callback;
            menu = new PopupMenu(context, anchor).getMenu();
        }

        void click(int id) {
            MenuItem item = menu.findItem(id);
            assertNotNull(item);
            assertTrue(item.isEnabled());
            assertTrue(callback.onActionItemClicked(this, item));
        }

        @Override public void finish() {
            if (finished) return;
            finished = true;
            callback.onDestroyActionMode(this);
        }

        @Override public void invalidate() { callback.onPrepareActionMode(this, menu); }
        @Override public Menu getMenu() { return menu; }
        @Override public MenuInflater getMenuInflater() { return new MenuInflater(context); }
        @Override public void setTitle(CharSequence value) { title = value; }
        @Override public void setTitle(int id) { title = context.getString(id); }
        @Override public CharSequence getTitle() { return title; }
        @Override public void setSubtitle(CharSequence value) { subtitle = value; }
        @Override public void setSubtitle(int id) { subtitle = context.getString(id); }
        @Override public CharSequence getSubtitle() { return subtitle; }
        @Override public void setCustomView(View value) { customView = value; }
        @Override public View getCustomView() { return customView; }
    }
}
