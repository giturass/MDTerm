package com.termux.view;

import android.app.Application;
import android.graphics.Typeface;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.termux.app.TermuxActivity;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class TerminalVolumeKeysTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void arrowsSendUpAndDownOnlyOnPress() throws Exception {
        Terminal terminal = new Terminal("arrows");
        assertTrue(terminal.properties.shouldUseVolumeKeysForCursor());
        for (int key : new int[]{KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN}) {
            terminal.press(key, 0);
            terminal.release(key);
        }
        assertEquals("\033[A\033[B", terminal.takeOutput());
        assertFalse(terminal.client.readControlKey());
        terminal.view.inputCodePoint('w', false, false);
        assertEquals("w", terminal.takeOutput());
    }

    @Test
    public void longPressRepeatsForEachDownAndCanceledReleaseSendsNothing() throws Exception {
        Terminal terminal = new Terminal("arrows");
        for (int repeat = 0; repeat < 3; repeat++)
            terminal.press(KeyEvent.KEYCODE_VOLUME_DOWN, repeat);
        assertEquals("\033[B\033[B\033[B", terminal.takeOutput());

        KeyEvent release = KeyEvent.changeFlags(event(KeyEvent.ACTION_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN, 0), KeyEvent.FLAG_FROM_SYSTEM | KeyEvent.FLAG_CANCELED);
        assertTrue(terminal.view.onKeyUp(release.getKeyCode(), release));
        assertEquals("", terminal.takeOutput());
    }

    @Test
    public void arrowsRespectApplicationCursorMode() throws Exception {
        Terminal terminal = new Terminal("arrows");
        byte[] mode = "\033[?1h".getBytes(StandardCharsets.UTF_8);
        terminal.view.mEmulator.append(mode, mode.length);
        terminal.press(KeyEvent.KEYCODE_VOLUME_UP, 0);
        terminal.press(KeyEvent.KEYCODE_VOLUME_DOWN, 0);
        assertEquals("\033OA\033OB", terminal.takeOutput());
    }

    @Test
    public void arrowsDoNotReadOrConsumeToolbarModifiersAndReturnToTheCursor() throws Exception {
        Terminal terminal = new Terminal("arrows");
        terminal.rejectModifierReads = true;
        terminal.view.setTopRow(-4);
        terminal.press(KeyEvent.KEYCODE_VOLUME_UP, 0);
        assertEquals("\033[A", terminal.takeOutput());
        assertEquals(0, terminal.view.getTopRow());
    }

    @Test
    public void arrowsCommitImePreviewBeforeMoving() throws Exception {
        Terminal terminal = new Terminal("arrows");
        InputConnection connection = terminal.view.onCreateInputConnection(new EditorInfo());
        assertTrue(connection.setComposingText("你好", 1));
        assertEquals("", terminal.takeOutput());

        terminal.press(KeyEvent.KEYCODE_VOLUME_UP, 0);
        assertEquals("你好\033[A", terminal.takeOutput());
        assertNull(ReflectionHelpers.getField(terminal.view, "mComposingText"));
        terminal.release(KeyEvent.KEYCODE_VOLUME_UP);
        assertEquals("", terminal.takeOutput());
    }

    @Test
    public void volumeModeLeavesBothButtonsToAndroid() throws Exception {
        Terminal terminal = new Terminal("volume");
        assertFalse(terminal.properties.shouldUseVolumeKeysForCursor());
        for (int key : new int[]{KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN}) {
            assertFalse(terminal.client.onKeyDown(key, event(KeyEvent.ACTION_DOWN, key, 0),
                terminal.view.mTermSession));
            assertFalse(terminal.client.onKeyUp(key, event(KeyEvent.ACTION_UP, key, 0)));
        }
        assertEquals("", terminal.takeOutput());
    }

    @Test
    public void virtualModeStillProvidesCtrlAndFn() throws Exception {
        Terminal terminal = new Terminal("virtual");
        terminal.press(KeyEvent.KEYCODE_VOLUME_DOWN, 0);
        assertTrue(terminal.client.readControlKey());
        terminal.view.inputCodePoint('c', false, false);
        assertEquals("\003", terminal.takeOutput());
        terminal.release(KeyEvent.KEYCODE_VOLUME_DOWN);
        assertFalse(terminal.client.readControlKey());

        terminal.press(KeyEvent.KEYCODE_VOLUME_UP, 0);
        terminal.view.inputCodePoint('w', false, false);
        assertEquals("\033[A", terminal.takeOutput());
        terminal.release(KeyEvent.KEYCODE_VOLUME_UP);
    }

    @Test
    public void reloadingTheModeClearsHeldVirtualModifiers() throws Exception {
        Terminal terminal = new Terminal("virtual");
        terminal.press(KeyEvent.KEYCODE_VOLUME_DOWN, 0);
        terminal.press(KeyEvent.KEYCODE_VOLUME_UP, 0);
        terminal.setMode("arrows");
        assertFalse(terminal.client.readControlKey());
        terminal.view.inputCodePoint('w', false, false);
        terminal.press(KeyEvent.KEYCODE_VOLUME_UP, 0);
        assertEquals("w\033[A", terminal.takeOutput());
    }

    private static KeyEvent event(int action, int key, int repeat) {
        return new KeyEvent(1, 1 + repeat, action, key, repeat, 0, 0, 0,
            KeyEvent.FLAG_FROM_SYSTEM, InputDevice.SOURCE_KEYBOARD);
    }

    /** Attaches an emulator without starting a native shell process. */
    private final class Terminal {
        final File file = temporary.newFile("termux.properties");
        final TermuxAppSharedProperties properties;
        final TerminalView view;
        TermuxTerminalViewClient client;
        boolean rejectModifierReads;

        Terminal(String mode) throws IOException {
            Application application = RuntimeEnvironment.getApplication();
            properties = TermuxAppSharedProperties.init(application);
            ReflectionHelpers.setField(properties, "mPropertiesFilePaths",
                Collections.singletonList(file.getAbsolutePath()));
            setMode(mode);

            TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
            ReflectionHelpers.setField(activity, "mProperties", properties);
            view = new TerminalView(application, null);
            ReflectionHelpers.setField(activity, "mTerminalView", view);
            client = new TermuxTerminalViewClient(activity, new TermuxTerminalSessionActivityClient(activity)) {
                @Override public boolean shouldEnableImeComposing() { return true; }
                @Override public boolean readExtraKeysSpecialButton(SpecialButton button) {
                    if (rejectModifierReads) throw new AssertionError("Volume arrows must leave modifiers untouched");
                    return false;
                }
            };
            client.onReloadProperties();
            view.setTerminalViewClient(client);
            TermuxTerminalSessionClientBase sessionClient = new TermuxTerminalSessionClientBase();
            TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100, sessionClient);
            TerminalEmulator emulator = new TerminalEmulator(session, 80, 24, 10, 20, 100, sessionClient);
            ReflectionHelpers.setField(session, "mEmulator", emulator);
            ReflectionHelpers.setField(session, "mShellPid", 1);
            view.mTermSession = session;
            view.mEmulator = emulator;
            view.mRenderer = new TerminalRenderer(20, Typeface.MONOSPACE);
        }

        void setMode(String mode) throws IOException {
            Files.write(file.toPath(), ("volume-keys=" + mode + "\n").getBytes(StandardCharsets.UTF_8));
            properties.loadTermuxPropertiesFromDisk();
            if (client != null) client.onReloadProperties();
        }

        void press(int key, int repeat) {
            assertTrue(view.onKeyDown(key, event(KeyEvent.ACTION_DOWN, key, repeat)));
        }

        void release(int key) {
            assertTrue(view.onKeyUp(key, event(KeyEvent.ACTION_UP, key, 0)));
        }

        String takeOutput() {
            Object queue = ReflectionHelpers.getField(view.mTermSession, "mTerminalToProcessIOQueue");
            byte[] bytes = new byte[4096];
            int count = ReflectionHelpers.callInstanceMethod(queue, "read",
                ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
            return new String(bytes, 0, count, StandardCharsets.UTF_8);
        }
    }
}
