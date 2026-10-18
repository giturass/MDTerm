package com.termux.view;

import android.app.Application;
import android.graphics.Typeface;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalKeyboardInputTest {

    private static final String MODIFIER_LETTERS = "\u02dc\u02cb\u02c6";

    @Test
    public void imeCommitPreservesLiteralModifierLetters() {
        TerminalView view = terminal(new TermuxTerminalViewClientBase());
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());

        assertTrue(connection.commitText(MODIFIER_LETTERS, 1));

        assertEquals(MODIFIER_LETTERS, takeOutput(view));
    }

    @Test
    public void finishingImeCompositionPreservesLiteralModifierLetters() {
        TerminalView view = terminal(new TermuxTerminalViewClientBase());
        InputConnection connection = view.onCreateInputConnection(new EditorInfo());

        assertTrue(connection.setComposingText(MODIFIER_LETTERS, 1));
        assertEquals("", takeOutput(view));
        assertTrue(connection.finishComposingText());

        assertEquals(MODIFIER_LETTERS, takeOutput(view));
    }

    @Test
    public void virtualExtraKeysPreserveLiteralModifierLetters() {
        TerminalView view = terminal(new TermuxTerminalViewClientBase());

        MODIFIER_LETTERS.codePoints().forEach(codePoint -> view.inputCodePoint(codePoint, false, false));

        assertEquals(MODIFIER_LETTERS, takeOutput(view));
    }

    @Test
    public void physicalKeyboardsStillNormalizeModifierLettersToAscii() {
        TerminalView view = terminal(new TermuxTerminalViewClientBase());

        MODIFIER_LETTERS.codePoints().forEach(codePoint -> view.inputCodePoint(1, codePoint, false, false));

        assertEquals("~`^", takeOutput(view));
    }

    @Test
    public void languageSwitchReturnsToSystemWithoutConsumingTerminalModifiers() {
        TerminalView view = terminal(new TermuxTerminalViewClientBase() {
            @Override public boolean readControlKey() {
                throw new AssertionError("Language switch must not consume CTRL");
            }
            @Override public boolean readAltKey() {
                throw new AssertionError("Language switch must not consume ALT");
            }
            @Override public boolean readShiftKey() {
                throw new AssertionError("Language switch must not consume SHIFT");
            }
            @Override public boolean readFnKey() {
                throw new AssertionError("Language switch must not consume FN");
            }
        });
        KeyEvent event = new KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_LANGUAGE_SWITCH,
            0, 0, 1, 0, 0, InputDevice.SOURCE_KEYBOARD);

        assertFalse(view.onKeyDown(event.getKeyCode(), event));
        assertEquals("", takeOutput(view));
    }

    private static TerminalView terminal(TermuxTerminalViewClientBase viewClient) {
        TerminalView view = new TerminalView(RuntimeEnvironment.getApplication(), null);
        view.setTerminalViewClient(viewClient);
        TermuxTerminalSessionClientBase sessionClient = new TermuxTerminalSessionClientBase();
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100, sessionClient);
        TerminalEmulator emulator = new TerminalEmulator(session, 80, 24, 10, 20, 100, sessionClient);
        ReflectionHelpers.setField(session, "mEmulator", emulator);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        view.mTermSession = session;
        view.mEmulator = emulator;
        view.mRenderer = new TerminalRenderer(20, Typeface.MONOSPACE);
        return view;
    }

    private static String takeOutput(TerminalView view) {
        Object queue = ReflectionHelpers.getField(view.mTermSession, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }
}
