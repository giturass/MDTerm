package com.termux.view;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewGroup;

import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.test.ShadowGhosttyTerminal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalCursorGestureTest {
    @Test
    public void horizontalSwipesSendPlainArrowsInBothDirections() {
        TerminalView view = terminal(false);
        swipe(view, 80, 0);
        assertOnlyArrow(view, "\u001b[C");
        swipe(view, -80, 0);
        assertOnlyArrow(view, "\u001b[D");
        assertTrue(view.mScroller.isFinished());
    }

    @Test
    public void verticalSwipesScrollHistoryWhenCursorModeIsOff() {
        TerminalView view = terminal(false);
        byte[] lines = new byte[160];
        for (int i = 0; i < lines.length; i += 4) {
            lines[i] = 'x'; lines[i + 1] = '\r'; lines[i + 2] = '\n'; lines[i + 3] = ' ';
        }
        view.getTerminal().feed(lines, 0, lines.length);
        assertTrue(view.getScrollbackRows() > 0);
        long time = SystemClock.uptimeMillis();
        touch(view, time, time, MotionEvent.ACTION_DOWN, 150, 150);
        touch(view, time, time + 30, MotionEvent.ACTION_MOVE, 150, 230);
        assertEquals(0, takeOutput(view).length);
        assertTrue("Expected scrollback, top row=" + view.getTopRow(), view.getTopRow() < 0);
        touch(view, time, time + 60, MotionEvent.ACTION_CANCEL, 150, 230);
    }

    @Test
    public void disablingGesturesStopsBothCursorAxesAndKeepsHistoryScrolling() {
        TerminalView view = terminal(false, true);
        swipe(view, 80, 0);
        swipe(view, -80, 0);
        assertEquals(0, takeOutput(view).length);

        StringBuilder history = new StringBuilder();
        for (int i = 0; i < 40; i++) history.append("line\r\n");
        byte[] lines = history.toString().getBytes(StandardCharsets.UTF_8);
        view.getTerminal().feed(lines, 0, lines.length);
        long time = SystemClock.uptimeMillis();
        touch(view, time, time, MotionEvent.ACTION_DOWN, 150, 150);
        touch(view, time, time + 30, MotionEvent.ACTION_MOVE, 150, 230);
        assertEquals(0, takeOutput(view).length);
        assertTrue(view.getTopRow() < 0);
        touch(view, time, time + 60, MotionEvent.ACTION_CANCEL, 150, 230);
    }

    @Test
    public void cursorModeVerticalSwipesSendUnmodifiedArrowsAndDoNotFling() {
        TerminalView view = terminal(true);
        swipe(view, 0, -80);
        assertOnlyArrow(view, "\u001b[A");
        swipe(view, 0, 80);
        assertOnlyArrow(view, "\u001b[B");
        assertEquals(0, view.getTopRow());
        assertTrue(view.mScroller.isFinished());
    }

    @Test
    public void diagonalDragKeepsItsInitialAxisAndApplicationCursorEncoding() {
        TerminalView view = terminal(true);
        byte[] mode = "\u001b[?1h".getBytes(StandardCharsets.UTF_8);
        view.getTerminal().feed(mode, 0, mode.length);
        swipe(view, 80, 20);
        assertOnlyArrow(view, "\u001bOC");
    }

    @Test
    public void tinyMovementDoesNotMoveCursor() {
        TerminalView view = terminal(true);
        swipe(view, 1, 1);
        assertEquals(0, takeOutput(view).length);
    }

    @Test
    public void mouseDragKeepsScrollingInsteadOfSendingCursorKeys() {
        TerminalView view = terminal(true);
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, MotionEvent.ACTION_MOVE, 100, 100, 0);
        event.setSource(InputDevice.SOURCE_MOUSE);
        view.mGestureRecognizer.mListener.onDown(100, 100);
        view.mGestureRecognizer.mListener.onScroll(event, 80, 0);
        event.recycle();
        assertEquals(0, takeOutput(view).length);
    }

    @Test
    public void pinchingDoesNotMoveCursorAndNextSwipeStillWorks() {
        TerminalView view = terminal(true);
        long time = SystemClock.uptimeMillis();
        touch(view, time, time, MotionEvent.ACTION_DOWN, 150, 150);
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = 150 + 60 * i;
            coordinates[i].y = 150 + 60 * i;
            coordinates[i].pressure = 1;
        }
        MotionEvent secondFinger = MotionEvent.obtain(time, time + 20,
            MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coordinates, 0, 0, 1, 1, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
        view.onTouchEvent(secondFinger);
        secondFinger.recycle();
        MotionEvent move = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_MOVE, 230, 150, 0);
        view.mGestureRecognizer.mListener.onScroll(move, -80, 0);
        move.recycle();
        touch(view, time, time + 60, MotionEvent.ACTION_CANCEL, 230, 150);
        assertEquals(0, takeOutput(view).length);
        assertTrue(view.mScroller.isFinished());
        swipe(view, 80, 0);
        assertOnlyArrow(view, "\u001b[C");
    }

    @Test
    public void detachingSessionCancelsItsQueuedFlingWithoutAccessingAnEmptyTerminal() {
        TerminalView view = terminal(false);
        attachForPostedCallbacks(view);
        appendHistory(view);
        MotionEvent event = startFling(view);
        try {
            assertTrue(view.attachSession(null));
            assertTrue(view.mScroller.isFinished());

            Shadows.shadowOf(Looper.getMainLooper()).idle();

            assertNull(view.mTermSession);
            assertNull(view.getTerminal());
            assertEquals(0, view.getTopRow());
            assertTrue(view.mScroller.isFinished());
        } finally {
            event.recycle();
        }
    }

    @Test
    public void oldQueuedFlingDoesNotScrollOrCancelAnimationInReplacementSession() {
        TerminalView view = terminal(false);
        attachForPostedCallbacks(view);
        appendHistory(view);
        MotionEvent oldEvent = startFling(view);
        TerminalView replacement = terminal(false);
        appendHistory(replacement);
        byte[] mouseMode = "\u001b[?1000h".getBytes(StandardCharsets.UTF_8);
        replacement.getTerminal().feed(mouseMode, 0, mouseMode.length);
        assertTrue(replacement.getTerminal().isMouseTrackingActive());
        assertTrue(view.attachSession(replacement.mTermSession));
        assertTrue(view.mScroller.isFinished());
        assertSame(replacement.getTerminal(), view.getTerminal());
        view.setTopRow(-5);
        MotionEvent newEvent = startFling(view);
        try {
            // The old session's frame was posted first and must leave the new fling alone.
            Shadows.shadowOf(Looper.getMainLooper()).runOneTask();

            assertSame(replacement.mTermSession, view.mTermSession);
            assertEquals(-5, view.getTopRow());
            assertFalse(view.mScroller.isFinished());
            assertEquals(0, takeOutput(view).length);
        } finally {
            view.attachSession(null);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            oldEvent.recycle();
            newEvent.recycle();
        }
    }

    private static void attachForPostedCallbacks(TerminalView view) {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(view, new ViewGroup.LayoutParams(0, 0));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(view.isAttachedToWindow());
    }

    @Test
    public void promptNavigationCancelsQueuedFlingWithoutSendingMouseEvents() {
        TerminalView view = terminal(false);
        attachForPostedCallbacks(view);
        appendHistory(view);
        byte[] mouseMode = "\u001b[?1000h".getBytes(StandardCharsets.UTF_8);
        view.getTerminal().feed(mouseMode, 0, mouseMode.length);
        MotionEvent event = startFling(view);
        try {
            view.scrollToPrompt(-1);
            assertTrue(view.mScroller.isFinished());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            ShadowGhosttyTerminal terminal = Shadow.extract(view.getTerminal());
            assertEquals(java.util.Collections.singletonList(-1), terminal.getPromptScrollRequests());
            assertEquals(0, takeOutput(view).length);
        } finally {
            view.attachSession(null);
            event.recycle();
        }
    }

    private static void appendHistory(TerminalView view) {
        StringBuilder history = new StringBuilder();
        for (int i = 0; i < 60; i++) history.append("line\r\n");
        byte[] lines = history.toString().getBytes(StandardCharsets.UTF_8);
        view.getTerminal().feed(lines, 0, lines.length);
        assertTrue(view.getScrollbackRows() > 5);
    }

    private static MotionEvent startFling(TerminalView view) {
        long time = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(time, time, MotionEvent.ACTION_UP, 150, 150, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        view.mGestureRecognizer.mListener.onFling(event, 0, 4000);
        assertFalse(view.mScroller.isFinished());
        return event;
    }

    private static TerminalView terminal(boolean cursorMode) {
        return terminal(true, cursorMode);
    }

    private static TerminalView terminal(boolean gesturesEnabled, boolean cursorMode) {
        TerminalView view = new TerminalView(RuntimeEnvironment.getApplication(), null);
        view.setTerminalViewClient(new TermuxTerminalViewClientBase() {
            @Override public boolean shouldUseHorizontalCursorGestures() { return gesturesEnabled; }
            @Override public boolean shouldUseVerticalCursorGestures() { return gesturesEnabled && cursorMode; }
            @Override public boolean readControlKey() {
                throw new AssertionError("Cursor gestures must not consume CTRL");
            }
        });
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100, client);
        GhosttyTerminal terminal = new GhosttyTerminal(session, 80, 24, 10, 20, 100);
        ReflectionHelpers.setField(session, "mTerminal", terminal);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        // The Ghostty shadow supplies deterministic terminal cell metrics without native rendering.
        view.setTextSize(20);
        view.attachSession(session);
        return view;
    }

    private static void swipe(TerminalView view, float dx, float dy) {
        long time = SystemClock.uptimeMillis();
        touch(view, time, time, MotionEvent.ACTION_DOWN, 150, 150);
        touch(view, time, time + 30, MotionEvent.ACTION_MOVE, 150 + dx, 150 + dy);
        touch(view, time, time + 60, MotionEvent.ACTION_UP, 150 + dx, 150 + dy);
    }

    private static void touch(TerminalView view, long down, long time, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(down, time, action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        view.onTouchEvent(event);
        event.recycle();
    }

    private static byte[] takeOutput(TerminalView view) {
        Object queue = ReflectionHelpers.getField(view.mTermSession, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return java.util.Arrays.copyOf(bytes, count);
    }

    private static void assertOnlyArrow(TerminalView view, String arrow) {
        String actual = new String(takeOutput(view), StandardCharsets.UTF_8);
        assertFalse(actual.isEmpty());
        assertEquals("", actual.replace(arrow, ""));
    }

}
