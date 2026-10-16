package com.termux.view;

import android.app.Application;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.GhosttyTerminal;
import com.termux.terminal.TerminalSession;
import com.termux.test.ShadowGhosttyTerminal;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class,
    shadows = ShadowGhosttyTerminal.class)
public class TerminalImeInputTest {
    private static final String UNICODE_TEXT = "你好\ud83d\ude42e\u0301";

    @Test
    public void editorTypePreservesNormalCompositionAndExplicitCharacterFallbacks() {
        // Normal IME input has priority even when the character workaround is enabled.
        int[][] cases = {
            {1, 0, 1, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL},
            {1, 1, 1, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL},
            {0, 1, 1, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS},
            {0, 0, 1, InputType.TYPE_NULL},
            {0, 1, 0, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL}
        };
        for (int[] item : cases) {
            TerminalView view = createView(new InputClient(item[0] != 0, item[1] != 0, item[2] != 0));
            EditorInfo editor = new EditorInfo();
            assertNotNull(view.onCreateInputConnection(editor));
            assertEquals(item[3], editor.inputType);
            assertEquals(EditorInfo.IME_FLAG_NO_FULLSCREEN, editor.imeOptions);
        }
    }

    @Test
    public void committingCompositionSendsUnicodeOnceWithoutInlineOrLocalEcho() {
        TerminalView view = createView(new InputClient(true, false, true));
        GhosttyTerminal terminal = view.getTerminal();
        feed(terminal, "prompt> ");
        String screenBefore = terminal.getTranscript(true, false);
        InputConnection input = view.onCreateInputConnection(new EditorInfo());

        assertTrue(input.setComposingText("ni", 1));
        assertTrue(input.setComposingText("你\ud83d\ude42", 1));
        assertEquals("你\ud83d\ude42", input.getTextBeforeCursor(20, 0).toString());
        assertEquals("", takeOutput(view.getCurrentSession()));
        assertNoInlineText(view);
        assertEquals(screenBefore, terminal.getTranscript(true, false));

        assertTrue(input.commitText(UNICODE_TEXT, 1));
        assertEquals(UNICODE_TEXT, takeOutput(view.getCurrentSession()));
        assertEquals("", input.getTextBeforeCursor(20, 0).toString());
        assertTrue(input.finishComposingText());
        assertEquals("", takeOutput(view.getCurrentSession()));
        assertNoInlineText(view);
        assertEquals(screenBefore, terminal.getTranscript(true, false));
    }

    @Test
    public void finishingCompositionSendsTheEditableOnceAndClearsIt() {
        TerminalView view = createView(new InputClient(true, false, true));
        InputConnection input = view.onCreateInputConnection(new EditorInfo());
        assertTrue(input.setComposingText(UNICODE_TEXT, 1));
        assertEquals("", takeOutput(view.getCurrentSession()));

        assertTrue(input.finishComposingText());
        assertEquals(UNICODE_TEXT, takeOutput(view.getCurrentSession()));
        assertTrue(input.finishComposingText());
        assertEquals("", takeOutput(view.getCurrentSession()));
        assertEquals("", input.getTextBeforeCursor(20, 0).toString());
        assertNoInlineText(view);
        assertEquals("", view.getTerminal().getTranscript(true, true));
    }

    @Test
    public void imeSelectionAndComposingRegionRemainInTheEditableUntilCommit() {
        TerminalView view = createView(new InputClient(true, false, true));
        BaseInputConnection input = (BaseInputConnection) view.onCreateInputConnection(new EditorInfo());
        assertTrue(input.setComposingText("你好吗", 1));
        assertTrue(input.setComposingRegion(0, 1));
        // Moving outside the composing span must not force a terminal commit.
        assertTrue(input.setSelection(1, 3));

        Editable editable = input.getEditable();
        assertEquals("你好吗", editable.toString());
        assertEquals("好吗", input.getSelectedText(0).toString());
        assertEquals(1, Selection.getSelectionStart(editable));
        assertEquals(3, Selection.getSelectionEnd(editable));
        assertEquals(0, BaseInputConnection.getComposingSpanStart(editable));
        assertEquals(1, BaseInputConnection.getComposingSpanEnd(editable));
        assertEquals("", takeOutput(view.getCurrentSession()));
        assertNoInlineText(view);

        assertTrue(input.setSelection(3, 3));
        assertTrue(input.finishComposingText());
        assertEquals("你好吗", takeOutput(view.getCurrentSession()));
    }

    @Test
    public void keyboardsMayStillComposeWhenNullInputTypeWasRequested() {
        TerminalView view = createView(new InputClient(false, false, true));
        EditorInfo editor = new EditorInfo();
        InputConnection input = view.onCreateInputConnection(editor);
        assertEquals(InputType.TYPE_NULL, editor.inputType);

        assertTrue(input.setComposingText(UNICODE_TEXT, 1));
        assertEquals(UNICODE_TEXT, input.getTextBeforeCursor(20, 0).toString());
        assertEquals("", takeOutput(view.getCurrentSession()));
        assertNoInlineText(view);
        assertTrue(input.commitText(UNICODE_TEXT, 1));
        assertEquals(UNICODE_TEXT, takeOutput(view.getCurrentSession()));
        assertEquals("", view.getTerminal().getTranscript(true, true));
    }

    @Test
    public void switchingSessionsClearsTheDraftWithoutSubmittingItToEitherSession() {
        TerminalView view = createView(new InputClient(true, false, true));
        TerminalSession original = view.getCurrentSession();
        InputConnection originalInput = view.onCreateInputConnection(new EditorInfo());
        assertTrue(originalInput.setComposingText(UNICODE_TEXT, 1));
        TerminalSession replacement = createSession();

        assertTrue(view.attachSession(replacement));

        assertEquals("", originalInput.getTextBeforeCursor(20, 0).toString());
        assertTrue(originalInput.finishComposingText());
        assertEquals("", takeOutput(original));
        assertEquals("", takeOutput(replacement));
        InputConnection replacementInput = view.onCreateInputConnection(new EditorInfo());
        assertTrue(replacementInput.commitText("新会话", 1));
        assertEquals("新会话", takeOutput(replacement));
        assertEquals("", takeOutput(original));
    }

    @Test
    public void hardwareAndExtraKeysDoNotForceCompositionToCommit() {
        TerminalView view = createView(new InputClient(true, false, true));
        InputConnection input = view.onCreateInputConnection(new EditorInfo());
        assertTrue(input.setComposingText(UNICODE_TEXT, 1));
        long now = SystemClock.uptimeMillis();
        KeyEvent hardwareTab = new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB,
            0, 0, 1, 0, 0, InputDevice.SOURCE_KEYBOARD);

        assertTrue(view.onKeyDown(KeyEvent.KEYCODE_TAB, hardwareTab));
        assertEquals("\t", takeOutput(view.getCurrentSession()));
        assertEquals(UNICODE_TEXT, input.getTextBeforeCursor(20, 0).toString());
        assertTrue(view.handleKeyCode(KeyEvent.KEYCODE_DPAD_LEFT, 0));
        assertEquals("\033[D", takeOutput(view.getCurrentSession()));
        assertEquals(UNICODE_TEXT, input.getTextBeforeCursor(20, 0).toString());
        view.inputCodePoint(TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD, 'x', false, false);
        assertEquals("x", takeOutput(view.getCurrentSession()));
        assertEquals(UNICODE_TEXT, input.getTextBeforeCursor(20, 0).toString());

        assertTrue(input.finishComposingText());
        assertEquals(UNICODE_TEXT, takeOutput(view.getCurrentSession()));
    }

    private static TerminalView createView(InputClient client) {
        TerminalView view = new TerminalView(RuntimeEnvironment.getApplication(), null);
        view.setTerminalViewClient(client);
        view.setTextSize(20);
        view.attachSession(createSession());
        return view;
    }

    private static TerminalSession createSession() {
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0],
            100, new TermuxTerminalSessionClientBase());
        GhosttyTerminal terminal = new GhosttyTerminal(session, 10, 4, 10, 20, 100);
        ReflectionHelpers.setField(session, "mTerminal", terminal);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        return session;
    }

    private static void feed(GhosttyTerminal terminal, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        terminal.feed(bytes, 0, bytes.length);
    }

    private static String takeOutput(TerminalSession session) {
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    private static void assertNoInlineText(TerminalView view) {
        RecordingCanvas canvas = new RecordingCanvas();
        view.onDraw(canvas);
        assertTrue(canvas.textRuns.isEmpty());
    }

    private static final class InputClient extends TermuxTerminalViewClientBase {
        private final boolean enabled;
        private final boolean enforceCharacters;
        private final boolean selected;

        InputClient(boolean enabled, boolean enforceCharacters, boolean selected) {
            this.enabled = enabled;
            this.enforceCharacters = enforceCharacters;
            this.selected = selected;
        }

        @Override public boolean shouldEnableImeInput() { return enabled; }
        @Override public boolean shouldEnforceCharBasedInput() { return enforceCharacters; }
        @Override public boolean isTerminalViewSelected() { return selected; }
    }

    private static final class RecordingCanvas extends Canvas {
        final List<String> textRuns = new ArrayList<>();

        @Override public void drawText(String text, float x, float y, Paint paint) {
            textRuns.add(text);
        }

        @Override
        public void drawTextRun(char[] text, int index, int count, int contextIndex, int contextCount,
                                float x, float y, boolean isRtl, Paint paint) {
            textRuns.add(new String(text, index, count));
        }
    }
}
