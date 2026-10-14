package com.termux.app.terminal;

import android.app.Application;
import android.os.Looper;
import android.util.Base64;

import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class)
public class BookmarkLocationProbeTest {
    private TerminalSession session() {
        TerminalSession session = new TerminalSession("sh", "/", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        ReflectionHelpers.setField(session, "mShellPid", 123);
        return session;
    }

    private String reply(TerminalSession session, String path) {
        String nonce = ReflectionHelpers.getField(session, "mBookmarkLocationNonce");
        return "mdterm;" + nonce + ";" + Base64.encodeToString(
            (path + "\n").getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }

    @Test public void acceptsOnlyMatchingNonceAndPreservesUnicodeAndEmbeddedNewlines() {
        TerminalSession session = session();
        Result result = new Result();
        session.requestBookmarkLocation(result);
        session.onBookmarkLocation("mdterm;wrong-nonce;L3RtcAo=");
        assertNull(result.path);
        assertEquals(0, result.failures);
        String payload = reply(session, "/目录/a\nb\n");
        session.onBookmarkLocation(payload);
        assertEquals("/目录/a\nb\n", result.path);
        assertEquals(1, result.successes);
        session.onBookmarkLocation(payload);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(1, result.successes);
        assertEquals(0, result.failures);
    }

    @Test public void invalidPathTimesOutInsteadOfSavingAndLateReplyIsIgnored() {
        TerminalSession session = session();
        Result result = new Result();
        session.requestBookmarkLocation(result);
        String late = reply(session, "/valid");
        session.onBookmarkLocation(reply(session, "relative/path"));
        assertNull(result.path);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(1, result.failures);
        session.onBookmarkLocation(late);
        assertNull(result.path);
    }

    @Test public void secondRequestCannotReplacePendingQuery() {
        TerminalSession session = session();
        Result first = new Result();
        Result second = new Result();
        session.requestBookmarkLocation(first);
        session.requestBookmarkLocation(second);
        assertEquals(1, second.failures);
        session.onBookmarkLocation(reply(session, "/first"));
        assertEquals("/first", first.path);
        assertNull(second.path);
    }

    @Test public void belTerminatedBookmarkReplyIsParsedFromFragmentedTerminalOutput() {
        assertFragmentedBookmarkReply("\007");
    }

    @Test public void stTerminatedBookmarkReplyIsParsedFromFragmentedTerminalOutput() {
        assertFragmentedBookmarkReply("\033\\");
    }

    private void assertFragmentedBookmarkReply(String terminator) {
        TerminalSession session = session();
        TerminalEmulator emulator = new TerminalEmulator(session, 40, 4, 10, 20, 100, null);
        Result result = new Result();
        session.requestBookmarkLocation(result);

        append(emulator, "before\033]777;mdterm;wrong-nonce;L3RtcAo=" + terminator);
        assertNull(result.path);
        assertEquals(0, result.successes);
        assertEquals(0, result.failures);

        String path = "/目录/😀/a\nb\n";
        String sequence = "\033]777;" + reply(session, path) + terminator;
        byte[] bytes = sequence.getBytes(StandardCharsets.UTF_8);
        // Split every boundary, including ESC + ] and the ESC + backslash terminator.
        for (int i = 0; i < bytes.length - 1; i++) {
            emulator.append(new byte[]{bytes[i]}, 1);
            assertNull(result.path);
            assertEquals(0, result.successes);
        }
        emulator.append(new byte[]{bytes[bytes.length - 1]}, 1);
        assertEquals(path, result.path);
        assertEquals(1, result.successes);

        // A repeated reply is ignored, and ordinary output resumes after the OSC sequence.
        append(emulator, sequence + "after");
        assertEquals("beforeafter", emulator.getScreen().getTranscriptText());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6));
        assertEquals(1, result.successes);
        assertEquals(0, result.failures);
    }

    private void append(TerminalEmulator emulator, String output) {
        byte[] bytes = output.getBytes(StandardCharsets.UTF_8);
        emulator.append(bytes, bytes.length);
    }

    private static final class Result implements TerminalSession.BookmarkLocationCallback {
        String path;
        int successes;
        int failures;
        @Override public void onLocation(String value) { path = value; successes++; }
        @Override public void onFailure() { failures++; }
    }
}
