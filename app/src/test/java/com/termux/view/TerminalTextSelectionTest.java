package com.termux.view;

import android.app.Application;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.SystemClock;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.PopupMenu;

import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

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
        view.mRenderer = new TerminalRenderer(20, Typeface.MONOSPACE);
        // Robolectric's legacy graphics do not supply real terminal cell metrics.
        ReflectionHelpers.setField(view.mRenderer, "mFontWidth", 10f);
        ReflectionHelpers.setField(view.mRenderer, "mFontLineSpacing", 20);
        ReflectionHelpers.setField(view.mRenderer, "mFontLineSpacingAndAscent", 0);
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
        TerminalEmulator emulator = new TerminalEmulator(session, 40, 8, 10, 20, 100, sessionClient);
        ReflectionHelpers.setField(session, "mEmulator", emulator);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        view.mTermSession = session;
        view.mEmulator = emulator;
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
        assertTrue(view.mEmulator.isMouseTrackingActive());
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
        int historyRows = view.mEmulator.getScreen().getActiveTranscriptRows();
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
    public void moreActionPreservesSelectedTextAndExitsBeforeOpeningContextMenu() {
        append("alpha beta");
        String[] contextText = {null};
        view.setContextMenuAction(() -> {
            assertFalse(view.isSelectingText());
            assertEquals(DrawerLayout.LOCK_MODE_UNLOCKED, drawer.getDrawerLockMode(Gravity.START));
            contextText[0] = view.getStoredSelectedText();
        });
        select(75, 10);
        host.mode.click(view.getTextSelectionCursorController().ACTION_MORE);
        assertEquals("beta", contextText[0]);
    }

    private void append(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        view.mEmulator.append(bytes, bytes.length);
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
